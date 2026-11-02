"""KNN failure isolation, review permissions and lossless index publication."""
import hashlib
import importlib
import json
import os
from pathlib import Path
import sys
import tempfile
from threading import Lock
from types import SimpleNamespace
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
STATE = tempfile.TemporaryDirectory()
if 'config' not in sys.modules:
    for name in ['members_file', 'usage_counters_file', 'renewal_requests_file', 'admin_credentials_file',
                 'admin_secret_file', 'admin_settings_file', 'allowed_ips_file', 'allowed_qq_file', 'error_dir', 'knn_review_dir']:
        os.environ[name.upper()] = str(Path(STATE.name) / name)

import cv2
import numpy as np
from fastapi.testclient import TestClient
from knn_classifier import KnnClassifier, fingerprint
from services import knn_review_service as service
from services.knn_review_service import KnnReviewStore


class Network:
    def getLayerNames(self): return ['avgpool_1']
    def setInput(self, blob): self.batch = blob.shape[0]
    def forward(self, layer): return np.tile(np.asarray([1., 0.], np.float32), (self.batch, 1))


class ReviewFixtures:
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.store = KnnReviewStore(self.root / 'review')
        self.image = np.full((60, 100, 3), 200, np.uint8)
        self.labels = ['书本', '汽车', '兔子']
        cfg, weights = self.root / 'm.cfg', self.root / 'm.weights'
        cfg.write_bytes(b'cfg'); weights.write_bytes(b'weights')
        index, report = self.root / 'index.npz', self.root / 'meta.json'
        np.savez_compressed(index, features=np.asarray([[1, 0, 1, 0], [0, 1, 0, 1]], np.float32), labels=np.asarray(['书本', '汽车']))
        meta = {'model_assets': fingerprint(cfg, weights), 'index_sha256': hashlib.sha256(index.read_bytes()).hexdigest(),
                'feature_dim': 4, 'documents': 2, 'candidates': 2, 'reference_labels': ['书本', '汽车'],
                'threshold': {'similarity_threshold': .5, 'margin_threshold': 0., 'vote_threshold': 0.}}
        report.write_text(json.dumps(meta), encoding='utf-8')
        self.matcher = KnnClassifier(index, report, cfg, weights, self.labels)
        self.network = Network()
        self.network_lock = Lock()

    def capture(self, owner='qq:1001'):
        return self.store.capture(self.image, [[10, 10, 30, 30]], ['汽车'],
            [{'index': 0, 'label': '书本', 'accepted': True, 'confidence': .8}], owner, 'https://example.com/a.png')

    def failure(self):
        return self.store.report_failure(self.capture(), 'qq:1001', 'game_rejected', '请点击第1个表情', '书本')

    def confirm(self, sample, label='兔子', category='classification'):
        return self.store.save_review(sample['id'], [{'box': [10, 10, 30, 30], 'label': label}], self.labels, category)

    def publish(self):
        return self.store.publish(self.matcher, self.network, self.network_lock, (64, 64))


class ReviewStoreTests(ReviewFixtures, unittest.TestCase):
    def test_snapshot_alone_does_not_become_an_error_or_training_sample(self):
        self.capture()
        self.assertEqual(self.store.list_records()['total'], 0)
        result = self.publish()
        self.assertEqual(result['candidates'], 2)
        self.assertEqual(result['review_documents'], 0)

    def test_confirmed_failure_is_saved_separately_with_original_predictions(self):
        sample = self.failure()
        self.assertEqual(sample['source'], 'knn')
        self.assertEqual(sample['status'], 'pending')
        self.assertTrue(self.store.image_path(sample['id']).is_file())
        self.assertEqual(sample['prediction_items'][0]['primary_label'], '汽车')
        self.assertEqual(sample['prediction_items'][0]['knn']['label'], '书本')
        self.assertFalse((self.root / 'errorPic').exists())

    def test_repeated_reports_and_equal_images_are_deduplicated(self):
        review_id = self.capture()
        first = self.store.report_failure(review_id, 'qq:1001', 'game_rejected')
        again = self.store.report_failure(review_id, 'qq:1001', 'game_rejected')
        self.assertEqual(first['id'], again['id'])
        self.assertEqual(len(again['attempts']), 1)
        self.failure()
        self.assertEqual(self.store.list_records()['total'], 1)
        self.assertEqual(len(self.store.get(first['id'])['attempts']), 2)

    def test_snapshot_cannot_be_reported_by_another_account(self):
        review_id = self.capture()
        with self.assertRaises(PermissionError):
            self.store.report_failure(review_id, 'qq:2002', 'game_rejected')
        self.assertEqual(self.store.list_records()['total'], 0)

    def test_expired_snapshot_is_not_treated_as_an_error(self):
        review_id = self.capture()
        os.utime(self.store._path('pending', review_id), (0, 0))
        with self.assertRaises(FileNotFoundError):
            self.store.report_failure(review_id, 'qq:1001', 'game_rejected')

    def test_invalid_ids_and_invalid_truth_boxes_cannot_escape_storage(self):
        with self.assertRaises(ValueError): self.store.get('../../members')
        sample = self.failure()
        for box, label in [([-1, 0, 20, 20], '书本'), ([0, 0, 101, 20], '书本'),
                           ([1., 0, 20, 20], '书本'), ([0, 0, 20, 20], '预测标签')]:
            with self.subTest(box=box, label=label), self.assertRaises(ValueError):
                self.store.save_review(sample['id'], [{'box': box, 'label': label}], self.labels)

    def test_only_confirmed_classification_reviews_expand_the_existing_index(self):
        sample = self.failure()
        self.assertEqual(self.publish()['candidates'], 2)
        self.confirm(sample)
        published = self.publish()
        self.assertEqual(published['candidates'], 3)
        self.assertEqual(published['documents'], 3)
        self.assertEqual(published['review_documents'], 1)
        self.assertIn('兔子', json.loads(self.matcher.report_path.read_text())['reference_labels'])
        self.assertEqual(self.publish()['candidates'], 3)
        baseline = np.load(self.store.root / 'baseline/reference_index.npz', allow_pickle=False)
        self.assertEqual(baseline['features'].shape[0], 2)

    def test_editing_or_revoking_a_confirmed_sample_does_not_accumulate_old_labels(self):
        sample = self.failure()
        self.confirm(sample, '兔子'); self.publish()
        self.confirm(sample, '书本'); self.publish()
        self.assertNotIn('兔子', json.loads(self.matcher.report_path.read_text())['reference_labels'])
        self.store.set_status(sample['id'], 'pending')
        self.assertEqual(self.publish()['candidates'], 2)

    def test_detection_and_unclear_reviews_are_archived_without_training(self):
        sample = self.failure()
        for category in ['detection', 'unclear']:
            self.confirm(sample, category=category)
            self.assertEqual(self.publish()['candidates'], 2)

    def test_publication_failure_rolls_back_both_index_files(self):
        sample = self.failure(); self.confirm(sample)
        original_index = self.matcher.index_path.read_bytes()
        original_report = self.matcher.report_path.read_bytes()
        original_write = service.atomic_write
        failed = False
        def interrupted(path, content):
            nonlocal failed
            if path == self.matcher.report_path and not failed:
                failed = True
                raise OSError('simulated disk failure')
            return original_write(path, content)
        with patch.object(service, 'atomic_write', side_effect=interrupted), self.assertRaises(OSError):
            self.publish()
        self.assertEqual(self.matcher.index_path.read_bytes(), original_index)
        self.assertEqual(self.matcher.report_path.read_bytes(), original_report)
        self.matcher._load()


class ReviewAdminTests(ReviewFixtures, unittest.TestCase):
    def setUp(self):
        super().setUp()
        self.routes = importlib.import_module('routes.knn_review_routes')
        self.actual_api = importlib.import_module('routes.api_routes')
        self.admin = importlib.import_module('routes.admin_routes')
        self.app = importlib.import_module('captcha_api').app
        self.patch(importlib.import_module('captcha_api').whitelist_repo, 'get_whitelists',
                   return_value=(set(), set(), {}, {}))
        self.client = TestClient(self.app)
        self.addCleanup(self.client.close)
        self.patch(self.routes, 'get_knn_review_store', return_value=self.store)
        self.patch(self.actual_api, 'get_knn_review_store', return_value=self.store)
        self.patch(self.routes, 'model_api', return_value=SimpleNamespace(CLASS_LABELS=self.labels,
                   knn_classifier=self.matcher, classifier=self.network, _CLASSIFIER_LOCK=self.network_lock,
                   _CLASSIFIER_INPUT_SIZE=(64, 64)))
        self.patch(self.admin.auth_service, 'must_change_password', return_value=False)
        self.token = self.admin.auth_service.create_token('review-admin')

    def patch(self, target, name, **kwargs):
        context = patch.object(target, name, **kwargs)
        result = context.start(); self.addCleanup(context.stop); return result

    def login(self):
        self.client.cookies.set('admin_session', self.token)

    def test_unauthenticated_users_cannot_read_images_or_publish(self):
        sample = self.failure()
        self.assertEqual(self.client.get('/admin/knn-review/data').status_code, 401)
        self.assertEqual(self.client.get('/admin/knn-review/image/' + sample['id']).status_code, 401)
        self.assertEqual(self.client.post('/admin/knn-review/publish', json={}).status_code, 401)

    def test_admin_page_can_confirm_publish_and_reopen_a_sample(self):
        self.login(); sample = self.failure()
        page = self.client.get('/admin/knn-review')
        self.assertEqual(page.status_code, 200)
        self.assertIn('发布已确认样本到索引', page.text)
        self.assertIn('knn-canvas', page.text)
        base = '/admin/knn-review/sample/' + sample['id']
        self.assertEqual(self.client.get('/admin/knn-review/image/' + sample['id']).headers['content-type'], 'image/png')
        response = self.client.post(base + '/confirm', json={'items': [{'box': [10, 10, 30, 30], 'label': '兔子'}]})
        self.assertEqual(response.status_code, 200)
        published = self.client.post('/admin/knn-review/publish', json={})
        self.assertEqual(published.status_code, 200)
        self.assertEqual(published.json()['candidates'], 3)
        self.assertEqual(self.client.post(base + '/status', json={'status': 'pending'}).status_code, 200)
        self.assertEqual(self.client.post('/admin/knn-review/publish', json={}).json()['candidates'], 2)

    def test_failure_report_uses_snapshot_owner_without_changing_usage_counters(self):
        review_id = self.capture(owner='ip:testclient')
        with patch.object(self.actual_api.usage_repo, 'increment_usage') as increment:
            response = self.client.post('/knn/report_failure', json={'reviewId': review_id, 'reason': 'game_rejected'})
            increment.assert_not_called()
        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.json()['code'], 0)
        wrong_owner = self.capture(owner='qq:other')
        self.assertEqual(self.client.post('/knn/report_failure', json={'reviewId': wrong_owner, 'reason': 'game_rejected'}).status_code, 403)
        self.assertEqual(self.client.post('/knn/report_failure', json={'reviewId': 'bad', 'reason': 'expired'}).status_code, 422)


if __name__ == '__main__': unittest.main()
