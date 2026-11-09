"""Offline statistics contract tests; no production data, models or HTTP services."""
import importlib
import os
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
STATE = tempfile.TemporaryDirectory()
for name in ['members_file', 'usage_counters_file', 'renewal_requests_file', 'admin_credentials_file',
             'admin_secret_file', 'admin_settings_file', 'allowed_ips_file', 'allowed_qq_file',
             'error_dir', 'knn_review_dir', 'knn_background_file', 'recognition_statistics_file',
             'recognition_statistics_key_file']:
    os.environ[name.upper()] = str(Path(STATE.name) / name)

from fastapi.testclient import TestClient
from services.recognition_statistics import BEIJING, RecognitionStatistics, StatisticsContext, StatisticsError


class StatisticsFixture(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(); self.addCleanup(self.temp.cleanup)
        self.path = Path(self.temp.name)
        self.store = RecognitionStatistics(self.path / 'stats.db', self.path / 'read.key')

    def start(self, model='primary', server='server-a', qq='1001'):
        return self.store.start(StatisticsContext(serverId=server, serverName='测试服务器'), model, qq)

    def settle(self, ticket, outcome='success', reason='game_accepted'):
        return self.store.settle(ticket['recognitionId'], ticket['feedbackToken'], outcome, reason)


class StatisticsTests(StatisticsFixture):
    def test_unique_tickets_and_pending_requests_are_unconfirmed(self):
        first, second = self.start(), self.start()
        self.assertNotEqual(first['recognitionId'], second['recognitionId'])
        self.assertNotEqual(first['feedbackToken'], second['feedbackToken'])
        stats = self.store.summary()['models']['primary']['total']
        self.assertEqual(stats, dict(attempts=2, successes=0, failures=0, unconfirmed=2, success_rate=0.))
        with self.store.connect() as db:
            self.assertNotEqual(db.execute('SELECT token_hash FROM recognitions LIMIT 1').fetchone()[0], first['feedbackToken'])

    def test_data_survives_api_restart_and_unconfirmed_is_excluded_from_rate(self):
        self.settle(self.start()); self.settle(self.start(), 'failure', 'no_usable_answer'); self.start()
        self.store = RecognitionStatistics(self.path / 'stats.db', self.path / 'read.key')
        result = self.store.summary()
        self.assertEqual(result['models']['primary']['total']['success_rate'], 50.)
        self.assertEqual(result['models']['primary']['total']['unconfirmed'], 1)
        self.assertIn('成功率 50.00%', result['text'])

    def test_duplicate_parallel_feedback_settles_exactly_once(self):
        ticket = self.start()
        with ThreadPoolExecutor(max_workers=8) as executor:
            results = list(executor.map(lambda _: self.settle(ticket), range(16)))
        self.assertEqual(sum(not result['duplicate'] for result in results), 1)
        self.assertEqual(self.store.summary()['models']['primary']['total']['successes'], 1)
        with self.assertRaises(StatisticsError) as error:
            self.settle(ticket, 'failure', 'game_rejected')
        self.assertEqual(error.exception.status, 409)

    def test_wrong_token_and_invalid_reason_cannot_change_results(self):
        ticket = self.start()
        with self.assertRaises(StatisticsError) as error:
            self.store.settle(ticket['recognitionId'], 'x' * 43, 'success', 'game_accepted')
        self.assertEqual(error.exception.status, 403)
        with self.assertRaises(StatisticsError): self.settle(ticket, 'success', 'no_usable_answer')
        self.assertEqual(self.store.summary()['models']['primary']['total']['unconfirmed'], 1)

    def test_global_server_qq_and_combined_filters_share_one_database(self):
        self.settle(self.start()); self.settle(self.start('knn', qq='1002'), 'failure', 'game_rejected')
        self.settle(self.start('knn', server='server-b'))
        summary = self.store.summary()
        self.assertEqual((summary['source_servers'], summary['source_qq']), (2, 2))
        self.assertEqual(summary['models']['knn']['total']['success_rate'], 50.)
        self.assertEqual(self.store.summary('server-a')['models']['knn']['total']['failures'], 1)
        self.assertEqual(self.store.summary(qq='1001')['models']['knn']['total']['successes'], 1)
        self.assertEqual(self.store.summary('server-b', '1002')['source_servers'], 0)
        self.assertIn('server-a', self.store.servers()['text'])
        with self.assertRaises(StatisticsError): self.store.summary('unknown')

    def test_today_uses_beijing_request_date_even_if_feedback_arrives_next_day(self):
        previous = datetime(2026, 11, 2, 23, 59, tzinfo=BEIJING)
        current = datetime(2026, 11, 3, 0, 1, tzinfo=BEIJING)
        with patch('services.recognition_statistics.time.time', return_value=previous.timestamp()): old = self.start()
        with patch('services.recognition_statistics.time.time', return_value=current.timestamp()): new = self.start()
        self.settle(old); self.settle(new, 'failure', 'game_rejected')
        with patch('services.recognition_statistics.datetime') as clock:
            clock.now.return_value = current
            summary = self.store.summary()
        self.assertEqual(summary['date'], '2026-11-03')
        self.assertEqual(summary['models']['primary']['today']['attempts'], 1)
        self.assertEqual(summary['models']['primary']['today']['successes'], 0)
        self.assertEqual(summary['models']['primary']['total']['successes'], 1)

    def test_reset_rejects_old_feedback_and_stale_reset_requests(self):
        ticket = self.start(); epoch = self.store.summary()['epoch']
        result = self.store.reset(epoch)
        self.assertNotEqual(epoch, result['epoch'])
        self.assertEqual(result['models']['primary']['total']['attempts'], 0)
        with self.assertRaises(StatisticsError) as error: self.settle(ticket)
        self.assertEqual(error.exception.status, 404)
        with self.assertRaises(StatisticsError) as error: self.store.reset(epoch)
        self.assertEqual(error.exception.status, 409)
        self.settle(self.start())
        self.assertEqual(self.store.summary()['models']['primary']['total']['successes'], 1)

    def test_query_key_is_persistent_and_validated(self):
        key = self.store.read_key()
        self.assertEqual(key, RecognitionStatistics(self.path / 'stats.db', self.path / 'read.key').read_key())
        self.assertTrue(self.store.authorize_read(key)); self.assertFalse(self.store.authorize_read('wrong'))


class StatisticsRouteTests(StatisticsFixture):
    def setUp(self):
        super().setUp()
        self.routes = importlib.import_module('routes.statistics_routes')
        self.api = importlib.import_module('routes.api_routes')
        self.app_module = importlib.import_module('captcha_api')
        for target, name, value in [
            (self.routes, 'get_recognition_statistics', self.store),
        ]:
            patcher = patch.object(target, name, return_value=value); patcher.start(); self.addCleanup(patcher.stop)
        patcher = patch.object(self.app_module.whitelist_repo, 'get_whitelists', return_value=(set(), set(), {}, {}))
        patcher.start(); self.addCleanup(patcher.stop)
        self.client = TestClient(self.app_module.app); self.addCleanup(self.client.close)
        self.headers = {'X-Statistics-Key': self.store.read_key()}

    def recognize(self, response, statistics=True, model='primary'):
        body = {'url': 'https://example.com/image.png', 'recognizer': model}
        if statistics: body['statistics'] = {'version': 1, 'serverId': 'server-a', 'serverName': '测试服务器'}
        with patch.object(self.api, 'recognize_url_content', return_value=response):
            result = self.client.post('/recognize?qq=1001', json=body)
        self.assertEqual(result.status_code, 200)
        return result.json()

    def test_recognition_waits_for_java_feedback_even_when_no_answer_is_available(self):
        ticket = self.recognize({'code': 1, 'msg': '未识别到内容'})
        self.assertEqual(self.store.summary()['models']['primary']['total']['unconfirmed'], 1)
        result = self.client.post('/statistics/feedback', json={**ticket, 'outcome': 'failure', 'reason': 'no_usable_answer'})
        # Feedback accepts only the ticket and result fields, never the recognition body.
        self.assertEqual(result.status_code, 422)
        result = self.client.post('/statistics/feedback', json={
            'recognitionId': ticket['recognitionId'], 'feedbackToken': ticket['feedbackToken'],
            'outcome': 'failure', 'reason': 'no_usable_answer'})
        self.assertEqual(result.status_code, 200)
        self.assertEqual(self.store.summary()['models']['primary']['total']['failures'], 1)

    def test_successful_recognition_alone_never_counts_as_success(self):
        ticket = self.recognize({'code': 0, 'emojiList': ['兔子']}, model='knn')
        self.assertIn('recognitionId', ticket)
        self.assertEqual(self.store.summary()['models']['knn']['total']['successes'], 0)
        self.assertEqual(self.store.summary()['models']['knn']['total']['unconfirmed'], 1)

    def test_legacy_clients_still_recognize_but_are_excluded_from_statistics(self):
        result = self.recognize({'code': 0, 'emojiList': ['兔子']}, statistics=False)
        self.assertNotIn('recognitionId', result)
        self.assertEqual(self.store.summary()['source_servers'], 0)

    def test_api_storage_failure_keeps_recognition_available(self):
        with patch.object(self.store, 'start', side_effect=OSError('disk unavailable')):
            result = self.recognize({'code': 0, 'emojiList': ['兔子']})
        self.assertEqual(result['code'], 0); self.assertIn('statisticsError', result)

    def test_qq_queries_require_key_and_admin_uses_same_summary(self):
        self.settle(self.start())
        self.assertEqual(self.client.get('/statistics/summary').status_code, 403)
        self.assertEqual(self.client.get('/statistics/servers').status_code, 403)
        public = self.client.get('/statistics/summary', headers=self.headers)
        self.assertEqual(public.status_code, 200)
        with patch.object(self.routes, '_admin_guard', return_value=('admin', None)):
            admin = self.client.get('/admin/recognition-statistics/data')
            self.assertEqual(public.json(), admin.json())
            self.assertIn('QQ 命令展示', self.client.get('/admin/recognition-statistics').text)
        self.assertEqual(self.client.get('/admin/recognition-statistics/data').status_code, 401)
        self.assertEqual(self.client.post('/admin/recognition-statistics/reset', json={'epoch': public.json()['epoch']}).status_code, 401)

    def test_feedback_bypasses_membership_expiry_but_still_checks_its_own_token(self):
        ticket = self.start()
        with patch.object(self.app_module.whitelist_repo, 'get_whitelists', return_value=({'blocked'}, {'other'}, {}, {})):
            with patch.object(self.app_module, '_enforce_membership_and_quota', side_effect=AssertionError('must not charge')):
                body = {**ticket, 'outcome': 'success', 'reason': 'game_accepted'}
                self.assertEqual(self.client.post('/statistics/feedback', json=body).status_code, 200)
                self.assertTrue(self.client.post('/statistics/feedback', json=body).json()['duplicate'])
                body['feedbackToken'] = 'x' * 43
                self.assertEqual(self.client.post('/statistics/feedback', json=body).status_code, 403)

    def test_dashboard_statistics_do_not_infer_outcomes_from_member_usage(self):
        self.settle(self.start())
        self.settle(self.start(), 'failure', 'game_rejected')
        self.start()
        self.settle(self.start('knn', server='server-b', qq='2002'), 'failure', 'game_rejected')
        admin = importlib.import_module('routes.admin_routes')
        members = importlib.import_module('routes.member_routes')
        usage = {'total_count': 230, 'error_count': 0, 'daily_count': 230}
        configured = {'qq:1001': {'name': '测试用户', 'tier': 'permanent', 'enabled': True}}
        with patch.object(admin, '_admin_guard', return_value=('admin', None)), \
                patch.object(members, '_admin_guard', return_value=('admin', None)), \
                patch.object(members.member_service, 'get_all_members', return_value=configured), \
                patch.object(members.member_service._usage_repo, 'get_counter', return_value=usage), \
                patch.object(admin.renewal_service, 'get_all_requests', return_value={}), \
                patch.object(self.routes, '_admin_guard', return_value=('admin', None)):
            for path in ['/admin', '/admin/members', '/admin/members?q=no-such-member']:
                page = self.client.get(path)
                self.assertEqual(page.status_code, 200)
                self.assertIn('今日验证统计（全局）', page.text)
                self.assertIn("fetch('/admin/recognition-statistics/data'", page.text)
                self.assertNotIn('今日总统计', page.text)
                self.assertNotIn('100.0%', page.text)
            summary = self.client.get('/admin/recognition-statistics/data').json()
            self.assertEqual(summary['models']['primary']['today'],
                             dict(attempts=3, successes=1, failures=1, unconfirmed=1, success_rate=50.))
            self.assertEqual(summary['models']['knn']['today']['failures'], 1)
            member_page = self.client.get('/admin/members').text
            self.assertIn('今日用量（UTC）', member_page)
            self.assertIn('<strong>230</strong>', member_page)
            self.assertNotIn('准确率', member_page)
        self.assertEqual(usage, {'total_count': 230, 'error_count': 0, 'daily_count': 230})


if __name__ == '__main__': unittest.main()
