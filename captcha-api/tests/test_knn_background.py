"""Background preprocessing, live settings and admin previews with isolated runtime state."""
import asyncio
import importlib
import json
import os
from pathlib import Path
import sys
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import Mock, patch

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
STATE = tempfile.TemporaryDirectory()
if 'config' not in sys.modules:
    for name in ['members_file', 'usage_counters_file', 'renewal_requests_file', 'admin_credentials_file',
                 'admin_secret_file', 'admin_settings_file', 'allowed_ips_file', 'allowed_qq_file',
                 'error_dir', 'knn_review_dir', 'knn_background_file']:
        os.environ[name.upper()] = str(Path(STATE.name) / name)

import cv2
import numpy as np
from fastapi.testclient import TestClient
import knn_background as background
from knn_background import BackgroundOptions, BackgroundSettingsStore, StaleSettings, suppress_background
from services.knn_review_service import KnnReviewStore


class SettingsTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(); self.addCleanup(self.temp.cleanup)
        self.path = Path(self.temp.name) / 'background.json'
        self.store = BackgroundSettingsStore(self.path)

    def test_default_is_off_and_reading_does_not_create_a_file(self):
        self.assertFalse(self.store.read()['options']['enabled'])
        self.assertFalse(self.path.exists())

    def test_saved_parameters_survive_recreation_and_external_updates_are_visible(self):
        options = BackgroundOptions(enabled=True, strength=.65, sensitivity=25)
        self.store.save(options, self.store.read()['revision'])
        another = BackgroundSettingsStore(self.path)
        self.assertEqual(another.read()['options'], options.model_dump())
        another.save(BackgroundOptions(), another.read()['revision'])
        self.assertFalse(self.store.read()['options']['enabled'])

    def test_stale_form_cannot_overwrite_a_newer_configuration(self):
        revision = self.store.read()['revision']
        self.store.save(BackgroundOptions(enabled=True), revision)
        with self.assertRaises(StaleSettings):
            self.store.save(BackgroundOptions(), revision)
        self.assertTrue(self.store.read()['options']['enabled'])

    def test_write_failure_preserves_previous_configuration(self):
        self.store.save(BackgroundOptions(enabled=True), self.store.read()['revision'])
        before = self.path.read_bytes()
        with patch.object(background, 'write_json', side_effect=OSError('disk full')):
            with self.assertRaises(OSError): self.store.save(BackgroundOptions(), self.store.read()['revision'])
        self.assertEqual(before, self.path.read_bytes())

    def test_invalid_values_are_rejected_instead_of_silently_clamped(self):
        for payload in [{'enabled': 'false'}, {'strength': float('nan')}, {'strength': 1.01},
                        {'sensitivity': 101}, {'sensitivity': 1.5}, {'edge_padding': True},
                        {'edge_padding': 4}, {'preserve_interiors': 'true'}, {'unknown': 1}]:
            with self.subTest(payload=payload), self.assertRaises(ValueError): BackgroundOptions(**payload)

    def test_invalid_configuration_fails_visibly(self):
        self.path.write_text('{invalid', encoding='utf-8')
        with self.assertRaises(ValueError): self.store.read()
        self.path.write_text('[]', encoding='utf-8')
        with self.assertRaises(ValueError): self.store.read()


class AlgorithmTests(unittest.TestCase):
    def test_fades_pale_background_but_keeps_dark_and_colored_foreground_without_mutating_input(self):
        image = np.full((70, 120, 3), 210, np.uint8)
        image[20:40, 20:40] = [20, 30, 230]
        image[20:40, 70:90] = [30, 30, 30]
        before = image.copy()
        result, mask = suppress_background(image, BackgroundOptions(enabled=True, strength=1., edge_padding=0))
        np.testing.assert_array_equal(image, before)
        np.testing.assert_array_equal(result[25:35,25:35], before[25:35,25:35])
        np.testing.assert_array_equal(result[25:35,75:85], before[25:35,75:85])
        self.assertEqual(result[0,0].tolist(), [245,245,245])
        self.assertEqual(mask[0,0], 0)

    def test_disable_and_zero_strength_are_identity_operations(self):
        image = np.full((70,120,3), 210, np.uint8)
        for options in [BackgroundOptions(), BackgroundOptions(enabled=True, strength=0.)]:
            result, _ = suppress_background(image, options)
            np.testing.assert_array_equal(result, image)


class PipelineFixtures:
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(); self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.store = BackgroundSettingsStore(self.root / 'background.json')
        self.review_store = KnnReviewStore(self.root / 'review')
        self.image = np.full((70,120,3), 210, np.uint8)
        self.image[20:40,70:90] = [30,30,30]
        self.encoded = cv2.imencode('.png', self.image)[1].tobytes()
        self.api = importlib.import_module('routes.api_routes')
        self.matcher = Mock()
        self.matcher.predict.side_effect = lambda image, boxes, *args: [
            {'index':i,'label':'书本','accepted':True,'confidence':.8} for i in range(len(boxes))]
        self.patch(background, 'get_background_store', return_value=self.store)
        self.detect = self.patch(self.api, 'detect_objects', side_effect=lambda image: [
            {'box':box,'class_id':1,'confidence':.9} for box in
            ([[70,20,20,20]] if image[0,0,0] > 235 else [[5,20,20,20],[70,20,20,20]])])
        self.classify = self.patch(self.api, 'classify_char', return_value='汽车')
        self.patch(self.api, 'knn_classifier', self.matcher)
        self.patch(self.api, 'get_knn_review_store', return_value=self.review_store)
        self.patch(self.api, 'fetch_image_bytes', return_value=self.encoded)

    def patch(self, target, name, *args, **kwargs):
        context = patch.object(target, name, *args, **kwargs)
        result = context.start(); self.addCleanup(context.stop); return result

    def recognize(self, recognizer='knn'):
        request = SimpleNamespace(state=SimpleNamespace(), client=SimpleNamespace(host='testclient'))
        return asyncio.run(self.api.recognize_from_url(self.api.ImageURLRequest(url='https://example.com/image.png',
                          recognizer=recognizer), request))

    def enable(self):
        return self.store.save(BackgroundOptions(enabled=True, strength=1.), self.store.read()['revision'])


class PipelineTests(PipelineFixtures, unittest.TestCase):
    def test_primary_never_reads_background_settings_and_uses_original_detection(self):
        with patch.object(self.store, 'read', side_effect=AssertionError('Primary must not read settings')):
            result = self.recognize('primary')
        self.assertEqual(result['emojiList'], ['汽车','汽车'])
        self.matcher.predict.assert_not_called()
        np.testing.assert_array_equal(self.detect.call_args.args[0], self.image)

    def test_next_knn_request_reads_new_settings_but_classifies_and_extracts_features_from_original(self):
        self.assertEqual(len(self.recognize()['emojiList']), 2)
        saved = self.enable()
        result = self.recognize()
        self.assertEqual(result['emojiList'], ['书本'])
        self.assertEqual(result['knnBackground']['revision'], saved['revision'])
        self.assertTrue(result['knnBackground']['applied'])
        self.assertFalse(np.array_equal(self.detect.call_args.args[0], self.image))
        np.testing.assert_array_equal(self.matcher.predict.call_args.args[0], self.image)
        np.testing.assert_array_equal(self.classify.call_args.args[0], self.image[20:40,70:90])
        self.store.save(BackgroundOptions(), self.store.read()['revision'])
        self.assertEqual(len(self.recognize()['emojiList']), 2)

    def test_error_snapshot_keeps_original_pixels_and_exact_processing_revision(self):
        self.enable(); response = self.recognize()
        record = self.review_store.report_failure(response['knnReviewId'], 'ip:testclient', 'game_rejected')
        self.assertEqual(record['preprocessing'], response['knnBackground'])
        self.assertEqual(record['attempts'][-1]['preprocessing'], response['knnBackground'])
        image = cv2.imdecode(np.frombuffer(self.review_store.image_path(record['id']).read_bytes(),np.uint8),cv2.IMREAD_COLOR)
        np.testing.assert_array_equal(image, self.image)

    def test_invalid_settings_do_not_silently_run_knn_with_other_parameters(self):
        self.store.path.write_text('{bad', encoding='utf-8')
        result = self.recognize()
        self.assertNotEqual(result['code'], 0)
        self.matcher.predict.assert_not_called()


class AdminTests(PipelineFixtures, unittest.TestCase):
    def setUp(self):
        super().setUp()
        self.routes = importlib.import_module('routes.knn_background_routes')
        self.admin = importlib.import_module('routes.admin_routes')
        self.app = importlib.import_module('captcha_api').app
        self.patch(self.routes, 'get_background_store', return_value=self.store)
        self.patch(self.routes, 'get_knn_review_store', return_value=self.review_store)
        self.patch(importlib.import_module('captcha_api').whitelist_repo, 'get_whitelists', return_value=(set(),set(),{},{}))
        self.patch(self.admin.auth_service, 'must_change_password', return_value=False)
        self.client = TestClient(self.app); self.addCleanup(self.client.close)

    def login(self): self.client.cookies.set('admin_session', self.admin.auth_service.create_token('test-admin'))

    def preview(self, options=None, **kwargs):
        return self.client.post('/admin/knn-background/preview', data={
            'options_json':json.dumps((options or BackgroundOptions(enabled=True, strength=1.)).model_dump()), **kwargs},
            files={'image':('sample.png',self.encoded,'image/png')} if 'sample_id' not in kwargs else None)

    def test_settings_and_previews_require_admin_login(self):
        self.assertEqual(self.client.get('/admin/knn-background/settings').status_code, 401)
        payload={'options':BackgroundOptions().model_dump(),'revision':self.store.read()['revision']}
        self.assertEqual(self.client.post('/admin/knn-background/settings',json=payload).status_code,401)
        self.assertEqual(self.preview().status_code,401)
        self.assertFalse(self.store.path.exists())

    def test_save_is_persistent_and_invalid_or_stale_forms_keep_the_existing_configuration(self):
        self.login(); initial=self.client.get('/admin/knn-background/settings').json()
        payload={'options':BackgroundOptions(enabled=True).model_dump(),'revision':initial['revision']}
        self.assertEqual(self.client.post('/admin/knn-background/settings',json=payload).status_code,200)
        self.assertTrue(self.recognize()['knnBackground']['applied'])
        self.assertEqual(self.client.post('/admin/knn-background/settings',json=payload).status_code,409)
        payload['options']['sensitivity']=101
        self.assertEqual(self.client.post('/admin/knn-background/settings',json=payload).status_code,422)
        self.assertEqual(self.store.read()['options']['sensitivity'],30)

    def test_preview_uses_real_pipeline_without_saving_config_capturing_errors_or_charging_usage(self):
        self.login()
        with patch.object(self.api.usage_repo,'increment_usage') as usage, patch.object(self.review_store,'capture') as capture:
            response=self.preview()
        self.assertEqual(response.status_code,200,response.text)
        data=response.json()
        self.assertEqual(len(data['original']['items']),2)
        self.assertEqual(len(data['processed']['items']),1)
        self.assertGreater(data['changed_fraction'],0)
        self.assertFalse(self.store.path.exists())
        capture.assert_not_called();usage.assert_not_called()
        for call in self.matcher.predict.call_args_list:np.testing.assert_array_equal(call.args[0],self.image)

    def test_page_and_existing_error_preview_preserve_private_access(self):
        self.login()
        self.assertIn('保存并生效',self.client.get('/admin/knn-background').text)
        response=self.recognize();record=self.review_store.report_failure(response['knnReviewId'],'ip:testclient','game_rejected')
        self.assertEqual(self.preview(sample_id=record['id']).status_code,200)
        self.assertEqual(self.preview(sample_id='../admin_secret.txt').status_code,400)

    def test_invalid_upload_and_invalid_parameters_do_not_change_configuration(self):
        self.login()
        response=self.client.post('/admin/knn-background/preview',files={'image':('bad.png',b'bad','image/png')})
        self.assertEqual(response.status_code,400)
        response=self.client.post('/admin/knn-background/preview',data={'options_json':'{"strength":2}'},files={'image':('a.png',self.encoded,'image/png')})
        self.assertEqual(response.status_code,400)
        self.assertFalse(self.store.path.exists())


if __name__ == '__main__': unittest.main()
