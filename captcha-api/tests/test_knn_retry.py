"""Offline tests: no external HTTP, member data or live counters are touched."""
import asyncio
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
from unittest.mock import Mock, patch

ROOT=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(ROOT))
STATE=tempfile.TemporaryDirectory()
for name in ['members_file','usage_counters_file','renewal_requests_file','admin_credentials_file',
             'admin_secret_file','admin_settings_file','allowed_ips_file','allowed_qq_file','error_dir','knn_review_dir']:
    os.environ[name.upper()]=str(Path(STATE.name)/name)
import cv2
import numpy as np
from knn_classifier import KnnClassifier,crop_pair,extract_features,fingerprint
import api_routes as api


class Network:
    def __init__(self, direction=(1.,0.)):
        self.direction=direction;self.calls=0
    def getLayerNames(self): return ['avgpool_1']
    def setInput(self,blob): self.batch=blob.shape[0]
    def forward(self,layer):
        self.calls+=1
        return np.tile(np.asarray(self.direction,np.float32),(self.batch,1))


class KnnTests(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.addCleanup(self.temp.cleanup)
        self.root=Path(self.temp.name)
        self.cfg,self.weights=self.root/'m.cfg',self.root/'m.weights'
        self.cfg.write_bytes(b'config');self.weights.write_bytes(b'weights')
        self.index,self.report=self.root/'index.npz',self.root/'report.json'
        np.savez_compressed(self.index,features=np.asarray([[1,0,1,0],[1,0,1,0],[0,1,0,1]],np.float32),
                            labels=np.asarray(['书本','书本','汽车']))
        self.meta={'model_assets':fingerprint(self.cfg,self.weights),'feature_dim':4,
            'index_sha256':hashlib.sha256(self.index.read_bytes()).hexdigest(),
            'threshold':{'similarity_threshold':.9,'margin_threshold':.1,'vote_threshold':.8}}
        self.write_report()
        self.matcher=KnnClassifier(self.index,self.report,self.cfg,self.weights,['书本','汽车','飞机'])
        self.image=np.full((60,100,3),220,np.uint8)
    def write_report(self): self.report.write_text(json.dumps(self.meta),encoding='utf-8')
    def predict(self,label='汽车',network=None):
        return self.matcher.predict(self.image,[[10,10,30,30]],[label],network or Network(),Lock(),(64,64))[0]
    def test_accepted_neighbor_replaces_primary_class(self):
        result=self.predict();self.assertTrue(result['accepted']);self.assertEqual(result['label'],'书本')
    def test_uncovered_class_is_never_forced_into_reference_classes(self):
        network=Network();result=self.predict('飞机',network)
        self.assertFalse(result['accepted']);self.assertEqual(result['reason'],'class_not_covered');self.assertEqual(network.calls,0)
    def test_uncertain_feature_is_not_accepted(self): self.assertFalse(self.predict(network=Network((-1,0)))['accepted'])
    def test_missing_index_fails_without_calling_a_network(self):
        self.index.unlink()
        with self.assertRaises(FileNotFoundError): self.predict()
    def test_model_and_index_fingerprints_are_checked(self):
        self.meta['model_assets']['classifier_weights_sha256']='wrong';self.write_report()
        with self.assertRaisesRegex(ValueError,'不匹配'): self.predict()
        self.meta['model_assets']=fingerprint(self.cfg,self.weights);self.meta['index_sha256']='wrong';self.write_report()
        with self.assertRaisesRegex(ValueError,'不匹配'): self.predict()
    def test_feature_dimension_mismatch_is_rejected(self):
        self.meta['feature_dim']=5;self.write_report()
        with self.assertRaisesRegex(ValueError,'无效'): self.predict()
    def test_raw_and_expanded_crops_match_review_index_conventions(self):
        raw,expanded=crop_pair(self.image,[10,10,30,30])
        self.assertEqual(raw.shape,(30,30,3));self.assertEqual(expanded.shape,(40,40,3))
        feature=extract_features(Network(),Lock(),(64,64),[(raw,expanded)])
        np.testing.assert_allclose(feature,[[2**-.5,0,2**-.5,0]],atol=1e-6)


class RouteTests(unittest.TestCase):
    api=api
    def recognize(self,recognizer='primary',matcher=None):
        api=self.api
        image=np.full((60,100,3),220,np.uint8)
        encoded=cv2.imencode('.png',image)[1].tobytes()
        detections=[{'box':[50,10,20,20],'class_id':1,'confidence':.95},
                    {'box':[5,10,20,20],'class_id':1,'confidence':.95}]
        request=SimpleNamespace(state=SimpleNamespace(),client=SimpleNamespace(host='127.0.0.1'))
        with patch.object(api,'fetch_image_bytes',return_value=encoded),patch.object(api,'detect_objects',return_value=detections), \
             patch.object(api,'classify_char',side_effect=['汽车','飞机']),patch.object(api,'knn_classifier',matcher):
            return asyncio.run(api.recognize_from_url(api.ImageURLRequest(url='https://example.com/a.png',recognizer=recognizer),request))
    def test_primary_request_keeps_original_result_without_knn(self):
        matcher=Mock();result=self.recognize(matcher=matcher)
        self.assertEqual(result['emojiList'],['汽车','飞机']);matcher.predict.assert_not_called()
    def test_partial_knn_preserves_unknown_candidates_and_positions(self):
        matcher=Mock();matcher.predict.return_value=[{'index':0,'accepted':True,'label':'书本'},
                                                    {'index':1,'accepted':False,'reason':'class_not_covered'}]
        result=self.recognize('knn',matcher)
        self.assertEqual(result['emojiList'],['书本','飞机']);self.assertEqual(result['knnAppliedIndices'],[0])
        self.assertEqual(result['recognizer'],'knn')
        self.assertEqual(matcher.predict.call_args.args[1],[[5,10,20,20],[50,10,20,20]])
    def test_missing_index_is_explicit_and_never_pretends_to_apply_knn(self):
        matcher=Mock();matcher.predict.side_effect=FileNotFoundError('missing')
        result=self.recognize('knn',matcher)
        self.assertEqual(result['code'],0);self.assertEqual(result['knnAppliedIndices'],[])
        self.assertEqual(result['knnStatus'],'unavailable');self.assertEqual(result['emojiList'],['汽车','飞机'])
    def test_unknown_recognizer_is_rejected(self):
        with self.assertRaises(ValueError): self.api.ImageURLRequest(url='https://example.com/a.png',recognizer='other')


class ActualApplicationRouteTests(RouteTests):
    api=importlib.import_module('routes.api_routes')

    def test_application_registers_the_knn_capable_recognize_endpoint(self):
        application=importlib.import_module('captcha_api').app
        endpoints=[route.endpoint for route in application.routes if getattr(route,'path',None)=='/recognize']
        self.assertEqual(endpoints,[self.api.recognize_from_url])
        self.assertEqual(self.api.ImageURLRequest(url='https://example.com/a.png').recognizer,'primary')


if __name__=='__main__': unittest.main()
