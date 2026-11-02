"""Separate KNN failure review queue and publication of human-confirmed features."""
from datetime import datetime, timezone
from functools import lru_cache
import hashlib
import io
import json
import os
from pathlib import Path
import re
import shutil
from threading import Lock, RLock
import time
import uuid

import cv2
import numpy as np

from knn_classifier import crop_pair, extract_features


def utc_now():
    return datetime.now(timezone.utc).isoformat()


def request_owner(request):
    kind = getattr(request.state, 'auth_type', None)
    account = getattr(request.state, 'auth_info', None)
    return str(kind) + ':' + str(account) if kind and account else 'ip:' + request.client.host


def atomic_write(path, content):
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_name('.' + path.name + '.' + uuid.uuid4().hex + '.tmp')
    try:
        temporary.write_bytes(content)
        os.replace(temporary, path)
    finally:
        temporary.unlink(missing_ok=True)


def write_json(path, data):
    atomic_write(path, (json.dumps(data, ensure_ascii=False, indent=2) + '\n').encode('utf-8'))


class KnnReviewStore:
    def __init__(self, root, pending_ttl=3600):
        self.root = Path(root)
        self.pending_ttl = pending_ttl
        self.lock = RLock()
        self.publish_lock = Lock()
        self._cleaned_at = 0
        for folder in ['pending', 'errors/images', 'errors/records']:
            (self.root / folder).mkdir(parents=True, exist_ok=True)

    def _path(self, folder, sample_id, suffix='.json'):
        if not re.fullmatch(r'[0-9a-f]{64}', sample_id):
            raise ValueError('样本编号无效')
        return self.root / folder / (sample_id + suffix)

    def capture(self, image, boxes, primary_labels, predictions, owner, source_url):
        """Keep a short-lived snapshot; it enters errors/ only after a failure report."""
        if image.size > 6_000_000:
            raise ValueError('复核图片过大')
        encoded = cv2.imencode('.png', image)[1].tobytes()
        review_id = uuid.uuid4().hex + uuid.uuid4().hex
        image_id = hashlib.sha256(encoded).hexdigest()
        pending = {'review_id': review_id, 'image_id': image_id, 'owner': owner,
                   'created_at': utc_now(), 'source_url': source_url,
                   'width': int(image.shape[1]), 'height': int(image.shape[0]),
                   'items': [{'box': list(map(int, box)), 'primary_label': label,
                              'knn': predictions[i] if i < len(predictions) else {}}
                             for i, (box, label) in enumerate(zip(boxes, primary_labels))]}
        with self.lock:
            atomic_write(self._path('pending', review_id, '.png'), encoded)
            write_json(self._path('pending', review_id), pending)
            self._cleanup_pending()
        return review_id

    def _cleanup_pending(self):
        now = time.time()
        if now - self._cleaned_at < 60:
            return
        self._cleaned_at = now
        paths = sorted((self.root / 'pending').glob('*.json'), key=lambda path: path.stat().st_mtime)
        for position, path in enumerate(paths):
            if now - path.stat().st_mtime > self.pending_ttl or len(paths) - position > 1000:
                if re.fullmatch(r'[0-9a-f]{64}', path.stem):
                    path.unlink(missing_ok=True)
                    path.with_suffix('.png').unlink(missing_ok=True)

    def report_failure(self, review_id, owner, reason, title='', answer=''):
        if reason not in {'game_rejected', 'same_image_again', 'no_usable_answer', 'same_failed_answer'}:
            raise ValueError('失败原因无效')
        with self.lock:
            pending_path = self._path('pending', review_id)
            if not pending_path.is_file():
                # An acknowledged report may be retried after a network timeout.
                for path in (self.root / 'errors/records').glob('*.json'):
                    record = json.loads(path.read_text(encoding='utf-8'))
                    if any(item['review_id'] == review_id and item['owner'] == owner
                           for item in record.get('attempts', [])):
                        return record
                raise FileNotFoundError('识别快照已过期或不存在')
            if time.time() - pending_path.stat().st_mtime > self.pending_ttl:
                raise FileNotFoundError('识别快照已过期')
            pending = json.loads(pending_path.read_text(encoding='utf-8'))
            if pending['owner'] != owner:
                raise PermissionError('识别快照不属于当前账号')
            sample_id = pending['image_id']
            record_path = self._path('errors/records', sample_id)
            if record_path.is_file():
                record = json.loads(record_path.read_text(encoding='utf-8'))
            else:
                record = {'id': sample_id, 'created_at': utc_now(), 'status': 'pending',
                          'width': pending['width'], 'height': pending['height'],
                          'source': 'knn', 'attempts': [], 'review_revision': 0}
            atomic_write(self.image_path(sample_id), self._path('pending', review_id, '.png').read_bytes())
            record['prediction_items'] = pending['items']
            record['attempts'] = (record['attempts'] + [{'review_id': review_id, 'owner': owner,
                'reason': reason, 'title': title[:2000], 'answer': answer[:1000], 'time': utc_now()}])[-30:]
            record.update(status='pending', updated_at=utc_now())
            write_json(record_path, record)
            pending_path.unlink(missing_ok=True)
            self._path('pending', review_id, '.png').unlink(missing_ok=True)
            return record

    def image_path(self, sample_id):
        return self._path('errors/images', sample_id, '.png')

    def get(self, sample_id):
        with self.lock:
            path = self._path('errors/records', sample_id)
            if not path.is_file():
                raise FileNotFoundError('样本不存在')
            return json.loads(path.read_text(encoding='utf-8'))

    def list_records(self, status='pending', offset=0, limit=100):
        with self.lock:
            records = [json.loads(path.read_text(encoding='utf-8'))
                       for path in (self.root / 'errors/records').glob('*.json')]
            counts = {name: sum(item['status'] == name for item in records)
                      for name in ['pending', 'confirmed', 'ignored']}
            selected = sorted((item for item in records if status == 'all' or item['status'] == status),
                              key=lambda item: item.get('updated_at', item['created_at']), reverse=True)
            rows = [{'id': item['id'], 'status': item['status'], 'updated_at': item.get('updated_at'),
                     'reason': item['attempts'][-1]['reason'], 'title': item['attempts'][-1]['title']}
                    for item in selected[offset:offset+limit]]
            publication_path = self.root / 'publication.json'
            publication = json.loads(publication_path.read_text(encoding='utf-8')) if publication_path.exists() else None
            return {'items': rows, 'total': len(selected), 'counts': counts, 'publication': publication}

    def save_review(self, sample_id, items, labels, category='classification', note=''):
        with self.lock:
            record = self.get(sample_id)
            if category not in {'classification', 'detection', 'unclear'}:
                raise ValueError('问题类型无效')
            if not items or len(items) > 50:
                raise ValueError('请保留 1–50 个真实图标框；无法确认的图片可忽略')
            cleaned = []
            for item in items:
                box, label = item.get('box'), item.get('label')
                if not isinstance(box, list) or len(box) != 4 or any(type(v) is not int for v in box):
                    raise ValueError('框坐标必须是四个整数')
                x, y, width, height = box
                if x < 0 or y < 0 or width < 2 or height < 2 or x+width > record['width'] or y+height > record['height']:
                    raise ValueError('框超出图片范围或尺寸过小')
                if label not in labels:
                    raise ValueError('请选择有效的真值标签')
                cleaned.append({'box': box, 'label': label})
            cleaned.sort(key=lambda item: item['box'][0] + item['box'][2] / 2)
            record.update(status='confirmed', review_items=cleaned, category=category, note=note[:2000],
                          review_revision=record['review_revision']+1, updated_at=utc_now(), confirmed_at=utc_now())
            write_json(self._path('errors/records', sample_id), record)
            return record

    def set_status(self, sample_id, status):
        if status not in {'pending', 'ignored'}:
            raise ValueError('状态无效')
        with self.lock:
            record = self.get(sample_id)
            record.update(status=status, updated_at=utc_now(), review_revision=record['review_revision']+1)
            write_json(self._path('errors/records', sample_id), record)
            return record

    def publish(self, matcher, network, network_lock, input_size):
        """Rebuild from the immutable initial index plus current confirmed classification reviews."""
        if matcher is None or network is None:
            raise ValueError('分类模型或 KNN 尚未就绪')
        if not self.publish_lock.acquire(blocking=False):
            raise ValueError('索引正在发布，请稍后再试')
        try:
            with self.lock:
                records = [json.loads(path.read_text(encoding='utf-8'))
                           for path in (self.root / 'errors/records').glob('*.json')]
                records = [item for item in records if item['status'] == 'confirmed' and item.get('category') == 'classification']
            base = self.root / 'baseline'
            with matcher._lock:
                matcher._load()
                if not base.exists():
                    temporary_base = self.root / ('.baseline-' + uuid.uuid4().hex)
                    temporary_base.mkdir(parents=True)
                    shutil.copy2(matcher.index_path, temporary_base / 'reference_index.npz')
                    shutil.copy2(matcher.report_path, temporary_base / 'reference_meta.json')
                    os.replace(temporary_base, base)
            metadata = json.loads((base / 'reference_meta.json').read_text(encoding='utf-8'))
            if metadata['model_assets'] != matcher._model_assets:
                raise ValueError('基准索引与当前模型不匹配')
            if hashlib.sha256((base / 'reference_index.npz').read_bytes()).hexdigest() != metadata['index_sha256']:
                raise ValueError('基准索引校验失败')
            with np.load(base / 'reference_index.npz', allow_pickle=False) as reference:
                features = [reference['features'].copy()]
                all_labels = [str(label) for label in reference['labels'].tolist()]
            revision_map = {}
            for record in records:
                image = cv2.imdecode(np.frombuffer(self.image_path(record['id']).read_bytes(), np.uint8), cv2.IMREAD_COLOR)
                if image is None:
                    raise ValueError('已确认图片无法读取')
                review = record['review_items']
                if any(item['label'] not in matcher.allowed_labels for item in review):
                    raise ValueError('已确认标签与当前分类模型不匹配')
                features.append(extract_features(network, network_lock, input_size,
                                [crop_pair(image, item['box']) for item in review]))
                all_labels.extend(item['label'] for item in review)
                revision_map[record['id']] = record['review_revision']
            matrix = np.concatenate(features, axis=0)
            output = io.BytesIO()
            np.savez_compressed(output, features=matrix, labels=np.asarray(all_labels))
            index_bytes = output.getvalue()
            metadata.update(source='human_confirmed_annotations_and_knn_reviews',
                            documents=metadata['documents']+len(records), candidates=len(all_labels),
                            reference_labels=sorted(set(all_labels)), review_documents=len(records),
                            published_at=utc_now(), published_reviews=revision_map,
                            index_sha256=hashlib.sha256(index_bytes).hexdigest())
            publication = {'published_at': metadata['published_at'], 'documents': metadata['documents'],
                           'candidates': metadata['candidates'], 'review_documents': len(records),
                           'published_reviews': revision_map}
            backup = self.root / 'index_backups' / uuid.uuid4().hex
            backup.mkdir(parents=True)
            publication['backup'] = backup.name
            with matcher._lock:
                previous_index, previous_report = matcher.index_path.read_bytes(), matcher.report_path.read_bytes()
                (backup / 'reference_index.npz').write_bytes(previous_index)
                (backup / 'reference_meta.json').write_bytes(previous_report)
                try:
                    atomic_write(matcher.index_path, index_bytes)
                    write_json(matcher.report_path, metadata)
                    matcher._stamp = None
                    matcher._load()
                    write_json(self.root / 'publication.json', publication)
                except Exception:
                    atomic_write(matcher.index_path, previous_index)
                    atomic_write(matcher.report_path, previous_report)
                    matcher._stamp = None
                    matcher._load()
                    raise
            return publication
        finally:
            self.publish_lock.release()


@lru_cache(maxsize=1)
def get_knn_review_store():
    from config import settings
    return KnnReviewStore(settings.knn_review_dir, settings.knn_review_pending_ttl_seconds)
