"""Read-only KNN retry using human-confirmed raw/expanded crop features."""
import hashlib
import json
import math
from pathlib import Path
from threading import Lock

import cv2
import numpy as np


def normalize(rows):
    return rows / np.maximum(np.linalg.norm(rows, axis=1, keepdims=True), 1e-12)


def crop_pair(image, box):
    x, y, width, height = map(int, box)
    height_img, width_img = image.shape[:2]
    x1, y1 = max(0, x), max(0, y)
    x2, y2 = min(width_img, x + width), min(height_img, y + height)
    if x2 <= x1 or y2 <= y1:
        raise ValueError('KNN 检测框无效')
    pad_x, pad_y = math.ceil((x2-x1)*.15), math.ceil((y2-y1)*.15)
    return (image[y1:y2, x1:x2],
            image[max(0,y1-pad_y):min(height_img,y2+pad_y),
                  max(0,x1-pad_x):min(width_img,x2+pad_x)])


def extract_features(network, network_lock, input_size, pairs):
    names = [name for name in network.getLayerNames() if name.startswith('avgpool')]
    if not names:
        raise ValueError('分类模型没有 avgpool 特征层')
    images = [crop for pair in pairs for crop in pair]
    if not images:
        return np.empty((0,0),np.float32)
    blob = cv2.dnn.blobFromImages(images,1/255.,input_size,swapRB=True,crop=False)
    with network_lock:
        network.setInput(blob)
        output = np.asarray(network.forward(names[-1]),np.float32)
    if output.shape[0] != len(images) or not np.all(np.isfinite(output)):
        raise ValueError('KNN 特征输出无效')
    rows = output.reshape(len(images),-1)
    return normalize(np.concatenate([normalize(rows[0::2]),normalize(rows[1::2])],axis=1))


def fingerprint(config, weights):
    return {name:hashlib.sha256(Path(path).read_bytes()).hexdigest() for name,path in [
        ('classifier_config_sha256',config),('classifier_weights_sha256',weights)]}


class KnnClassifier:
    def __init__(self, index_path, report_path, config_path, weights_path, labels):
        self.index_path, self.report_path = Path(index_path),Path(report_path)
        self.config_path,self.weights_path = config_path,weights_path
        self.allowed_labels = frozenset(labels)
        self._model_assets = fingerprint(config_path,weights_path)
        self._lock = Lock()
        self._stamp = None

    def _load(self):
        stamp = (self.index_path.stat().st_mtime_ns,self.report_path.stat().st_mtime_ns)
        if stamp == self._stamp:
            return
        metadata = json.loads(self.report_path.read_text(encoding='utf-8'))
        if metadata.get('model_assets') != self._model_assets:
            raise ValueError('KNN 索引与分类模型不匹配，请重建索引')
        if metadata.get('index_sha256') != hashlib.sha256(self.index_path.read_bytes()).hexdigest():
            raise ValueError('KNN 索引与元数据不匹配')
        with np.load(self.index_path,allow_pickle=False) as data:
            features = np.asarray(data['features'],np.float32)
            labels = [str(label) for label in data['labels'].tolist()]
        if (features.ndim != 2 or features.shape[0] != len(labels) or not labels
                or features.shape[1] != metadata.get('feature_dim')
                or not np.all(np.isfinite(features)) or np.any(np.linalg.norm(features,axis=1) <= 0)
                or not set(labels) <= self.allowed_labels):
            raise ValueError('KNN 索引内容无效')
        thresholds = metadata['threshold']
        if any(not 0 <= float(thresholds[name]) <= 1 for name in
               ['similarity_threshold','margin_threshold','vote_threshold']):
            raise ValueError('KNN 阈值无效')
        self._features,self._labels,self._thresholds = normalize(features),labels,thresholds
        self._stamp = stamp

    def predict(self, image, boxes, primary_labels, network, network_lock, input_size):
        with self._lock:
            self._load()
            covered = frozenset(self._labels)
            eligible = [i for i,label in enumerate(primary_labels) if label in covered]
            predictions = [{'index':i,'accepted':False,'reason':'class_not_covered'} for i in range(len(boxes))]
            features = extract_features(network,network_lock,input_size,
                [crop_pair(image,boxes[i]) for i in eligible])
            if eligible and features.shape[1] != self._features.shape[1]:
                raise ValueError('KNN 特征维度不匹配')
            for index,feature in zip(eligible,features):
                similarities = self._features @ feature
                order = np.argsort(-similarities,kind='stable')[:min(5,len(self._labels))]
                votes,scores = {},{}
                for neighbor in order:
                    label,score = self._labels[int(neighbor)],float(similarities[neighbor])
                    votes[label] = votes.get(label,0.)+max(score,0.)
                    scores[label] = max(scores.get(label,-1.),score)
                label = max(votes,key=lambda value:(votes[value],value))
                confidence = min(1.,max(0.,scores[label]))
                margin = min(1.,max(0.,confidence-max([s for name,s in scores.items() if name!=label],default=0.)))
                share = votes[label]/sum(votes.values()) if sum(votes.values()) else 0.
                accepted = (confidence >= self._thresholds['similarity_threshold']
                    and margin >= self._thresholds['margin_threshold'] and share >= self._thresholds['vote_threshold'])
                predictions[index] = {'index':index,'label':label,'confidence':confidence,'margin':margin,
                    'vote_share':share,'accepted':accepted,'reason':'accepted' if accepted else 'uncertain'}
            return predictions
