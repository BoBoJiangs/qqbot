"""Original-byte upload continues a failed download without another call or settlement."""
import base64
import importlib
import json
import time
from concurrent.futures import ThreadPoolExecutor
from unittest.mock import patch

import cv2
import numpy as np
from fastapi.testclient import TestClient
from test_recognition_statistics import StatisticsFixture


class ImageUploadFallbackTests(StatisticsFixture):
    def setUp(self):
        super().setUp()
        self.api = importlib.import_module('routes.api_routes')
        self.statistics_routes = importlib.import_module('routes.statistics_routes')
        self.app_module = importlib.import_module('captcha_api')
        for target in [self.api, self.statistics_routes]:
            patcher = patch.object(target, 'get_recognition_statistics', return_value=self.store)
            patcher.start(); self.addCleanup(patcher.stop)
        patcher = patch.object(self.app_module.whitelist_repo, 'get_whitelists', return_value=(set(), set(), {}, {}))
        patcher.start(); self.addCleanup(patcher.stop)
        self.client = TestClient(self.app_module.app); self.addCleanup(self.client.close)
        self.url = 'https://example.com/captcha.png'
        self.image = cv2.imencode('.png', np.full((30, 60, 3), 200, np.uint8))[1].tobytes()

    def failed_download(self, recognizer='primary'):
        body = {'url': self.url, 'recognizer': recognizer, 'statistics': {'serverId': 'server-a'}}
        with patch.object(self.api, 'fetch_image_bytes', side_effect=TimeoutError('download timeout')):
            response = self.client.post('/recognize?qq=1001', json=body)
        self.assertEqual(response.status_code, 200)
        self.assertTrue(response.json()['uploadFallback'])
        return response.json()

    def payload(self, ticket):
        return {'recognitionId': ticket['recognitionId'], 'feedbackToken': ticket['feedbackToken'],
                'url': self.url, 'imageBase64': base64.b64encode(self.image).decode()}

    def test_upload_reuses_ticket_and_waits_for_real_game_feedback(self):
        ticket = self.failed_download()
        with patch.object(self.api, 'fetch_image_bytes', side_effect=AssertionError('must use uploaded bytes')), \
             patch.object(self.api, 'recognize_image', return_value=({'code': 0, 'emojiList': ['兔子']}, {})) as recognize:
            response = self.client.post('/recognize/fallback?qq=1001', json=self.payload(ticket))
        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.json()['recognitionId'], ticket['recognitionId'])
        self.assertEqual(response.json()['feedbackToken'], ticket['feedbackToken'])
        self.assertEqual(response.json()['imageSource'], 'java_upload')
        np.testing.assert_array_equal(recognize.call_args.args[0], cv2.imdecode(np.frombuffer(self.image, np.uint8), 1))
        stats = self.store.summary()['models']['primary']['total']
        self.assertEqual((stats['attempts'], stats['unconfirmed'], stats['successes']), (1, 1, 0))
        self.settle(ticket)
        self.assertEqual(self.store.summary()['models']['primary']['total']['successes'], 1)

    def test_duplicate_upload_cannot_run_recognition_twice(self):
        ticket = self.failed_download()
        with patch.object(self.api, 'recognize_image', return_value=({'code': 0, 'emojiList': ['兔子']}, {})) as recognize:
            self.assertEqual(self.client.post('/recognize/fallback?qq=1001', json=self.payload(ticket)).status_code, 200)
            self.assertEqual(self.client.post('/recognize/fallback?qq=1001', json=self.payload(ticket)).status_code, 409)
        self.assertEqual(recognize.call_count, 1)
        self.assertEqual(self.store.summary()['models']['primary']['total']['attempts'], 1)

    def test_parallel_upload_claims_allow_only_one_continuation(self):
        ticket = self.failed_download()
        def claim(_):
            try:
                self.store.claim_image_upload(ticket['recognitionId'], ticket['feedbackToken'], self.url, '1001')
                return True
            except ValueError:
                return False
        with ThreadPoolExecutor(max_workers=8) as workers:
            self.assertEqual(sum(workers.map(claim, range(8))), 1)

    def test_wrong_token_qq_or_url_cannot_use_another_request(self):
        ticket = self.failed_download()
        for field, value in [('feedbackToken', 'x' * 43), ('url', 'https://example.com/other.png')]:
            body = self.payload(ticket); body[field] = value
            self.assertEqual(self.client.post('/recognize/fallback?qq=1001', json=body).status_code, 403)
        self.assertEqual(self.client.post('/recognize/fallback?qq=1002', json=self.payload(ticket)).status_code, 403)
        with patch.object(self.api, 'recognize_image', return_value=({'code': 0}, {})):
            self.assertEqual(self.client.post('/recognize/fallback?qq=1001', json=self.payload(ticket)).status_code, 200)

    def test_successful_url_recognition_cannot_request_free_upload(self):
        ticket = self.start()
        self.assertEqual(self.client.post('/recognize/fallback?qq=1001', json=self.payload(ticket)).status_code, 404)

    def test_invalid_image_encoding_and_oversized_payload_are_rejected(self):
        ticket = self.failed_download()
        body = self.payload(ticket); body['imageBase64'] = 'not base64!'
        self.assertEqual(self.client.post('/recognize/fallback?qq=1001', json=body).status_code, 400)
        body['imageBase64'] = ''
        self.assertEqual(self.client.post('/recognize/fallback?qq=1001', json=body).status_code, 422)
        body['imageBase64'] = base64.b64encode(b'x' * (self.api.settings.image_max_bytes + 1)).decode()
        self.assertIn(self.client.post('/recognize/fallback?qq=1001', json=body).status_code, {400, 422})

    def test_expired_or_settled_request_cannot_run_upload_recognition(self):
        ticket = self.failed_download()
        with patch('services.recognition_statistics.time.time', return_value=time.time() + 301):
            self.assertEqual(self.client.post('/recognize/fallback?qq=1001', json=self.payload(ticket)).status_code, 410)
        self.settle(ticket, 'unconfirmed', 'service_error')
        self.assertEqual(self.client.post('/recognize/fallback?qq=1001', json=self.payload(ticket)).status_code, 404)

    def test_knn_upload_preserves_review_snapshot_and_recognizer(self):
        ticket = self.failed_download('knn')
        with patch.object(self.api, 'recognize_image', return_value=(
                {'code': 0, 'emojiList': ['兔子'], 'knnStatus': 'applied', 'knn': [], 'recognizer': 'knn'},
                {'emoji_boxes': [], 'primary_labels': []})) as recognize, \
             patch.object(self.api, 'get_knn_review_store') as review:
            review.return_value.capture.return_value = 'review-id'
            result = self.client.post('/recognize/fallback?qq=1001', json=self.payload(ticket)).json()
        self.assertEqual(recognize.call_args.args[1], 'knn')
        self.assertEqual(result['knnReviewId'], 'review-id')
        self.assertEqual(self.store.summary()['models']['knn']['total']['attempts'], 1)

    def test_upload_checks_whitelist_and_does_not_charge_original_request_twice(self):
        with patch.object(self.app_module.whitelist_repo, 'get_whitelists', return_value=(set(), {'1001'}, {}, {})), \
             patch.object(self.app_module, '_increment_usage') as charge:
            ticket = self.failed_download()
            with patch.object(self.api, 'recognize_image', return_value=({'code': 0}, {})):
                self.assertEqual(self.client.post('/recognize/fallback?qq=1001', json=self.payload(ticket)).status_code, 200)
            self.assertEqual(charge.call_count, 1)
            self.assertEqual(self.client.post('/recognize/fallback?qq=9999', json=self.payload(ticket)).status_code, 403)

    def test_invalid_uploaded_image_remains_unconfirmed(self):
        ticket = self.failed_download()
        body = self.payload(ticket); body['imageBase64'] = base64.b64encode(b'not an image').decode()
        result = self.client.post('/recognize/fallback?qq=1001', json=body).json()
        self.assertTrue(result['serviceError'])
        self.assertEqual(result['recognitionId'], ticket['recognitionId'])
        self.assertEqual(self.store.summary()['models']['primary']['total']['unconfirmed'], 1)

    def test_reset_also_removes_upload_capabilities(self):
        ticket = self.failed_download()
        self.store.reset(self.store.summary()['epoch'])
        self.assertEqual(self.client.post('/recognize/fallback?qq=1001', json=self.payload(ticket)).status_code, 404)
