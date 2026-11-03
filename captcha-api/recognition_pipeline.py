"""Shared URL-recognition and admin-preview pipeline; no downloads or usage writes."""
import logging

from knn_background import prepare_detection_image

logger = logging.getLogger('captcha_api')


def run_recognition(image, recognizer, *, detector, sorter, classifier_fn, matcher,
                    network, network_lock, input_size, background_options=None):
    detection_image, mask, background = image, None, None
    if recognizer == 'knn':
        detection_image, mask, background = prepare_detection_image(image, background_options)
    detections = sorter(detector(detection_image))
    rows, boxes, primary_labels = [], [], []
    text_result = ''
    for det in detections:
        x, y, w, h = det['box']
        height, width = image.shape[:2]
        x, y = max(0, x), max(0, y)
        x_end, y_end = min(width, x+w), min(height, y+h)
        if x_end <= x or y_end <= y:
            continue
        # Keep the same raw/expanded crop features as the existing reference index.
        label = classifier_fn(image[y:y_end, x:x_end])
        box = [x, y, x_end-x, y_end-y]
        row = {'box': box, 'label': label, 'confidence': float(det['confidence']),
               'type': 'text' if det['class_id'] == 0 else 'emoji'}
        if det['class_id'] == 0:
            text_result += label
        else:
            row['emoji_index'] = len(primary_labels)
            primary_labels.append(label)
            boxes.append(box)
        rows.append(row)
    context = {'image': detection_image, 'mask': mask, 'items': rows,
               'emoji_boxes': boxes, 'primary_labels': primary_labels}
    if not text_result and not primary_labels:
        return {'code': 1, 'error': '未识别到内容', 'msg': '未识别到内容',
                'result': '识别失败：未识别到内容', 'emojiList': [],
                'recognizer': recognizer, **({'knnBackground': background} if background else {})}, context
    labels = list(primary_labels)
    response = {'code': 0, 'result': text_result, 'emojiList': labels, 'recognizer': recognizer}
    if recognizer == 'knn':
        response.update(knnAppliedIndices=[], knnBackground=background)
        try:
            if matcher is None:
                raise ValueError('KNN 尚未初始化')
            predictions = matcher.predict(image, boxes, primary_labels, network, network_lock, input_size)
            response['knn'] = predictions
            for item in predictions:
                if item['accepted']:
                    labels[item['index']] = item['label']
                    response['knnAppliedIndices'].append(item['index'])
            response['knnStatus'] = 'applied' if response['knnAppliedIndices'] else 'uncertain'
            for row in rows:
                if row['type'] == 'emoji':
                    index = row['emoji_index']
                    row.update(primary_label=primary_labels[index], label=labels[index], knn=predictions[index])
        except Exception as error:
            logger.warning('KNN 重试不可用: %s', error)
            labels[:] = primary_labels
            response['knnAppliedIndices'] = []
            response['knnStatus'] = 'unavailable'
    return response, context
