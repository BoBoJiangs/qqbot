"""Hot-configurable, whole-image background suppression before KNN detection."""
import hashlib
import json
from functools import lru_cache
from pathlib import Path
from threading import RLock

import cv2
import numpy as np
from pydantic import BaseModel, ConfigDict, Field

from config import settings
from services.knn_review_service import write_json, utc_now


class BackgroundOptions(BaseModel):
    model_config = ConfigDict(extra='forbid', frozen=True)
    enabled: bool = Field(default=False, strict=True)
    strength: float = Field(default=0.8, ge=0, le=1, allow_inf_nan=False, strict=True)
    sensitivity: int = Field(default=30, ge=0, le=100, strict=True)
    edge_padding: int = Field(default=1, ge=0, le=3, strict=True)
    preserve_interiors: bool = Field(default=True, strict=True)


class StaleSettings(ValueError):
    pass


class BackgroundSettingsStore:
    def __init__(self, path):
        self.path = Path(path)
        self.lock = RLock()

    def read(self):
        with self.lock:
            data = json.loads(self.path.read_text(encoding='utf-8')) if self.path.exists() else {}
            if not isinstance(data, dict):
                raise ValueError('背景抑制配置必须是对象')
            options = BackgroundOptions.model_validate(data.get('options', {}))
            revision = hashlib.sha256(json.dumps(options.model_dump(), sort_keys=True).encode()).hexdigest()
            return {'options': options.model_dump(), 'revision': revision, 'updated_at': data.get('updated_at')}

    def save(self, options, expected_revision):
        with self.lock:
            current = self.read()
            if current['revision'] != expected_revision:
                raise StaleSettings('参数已被其他页面修改，请刷新后重新调整')
            write_json(self.path, {'options': options.model_dump(), 'updated_at': utc_now()})
            return self.read()


@lru_cache(maxsize=1)
def get_background_store():
    return BackgroundSettingsStore(settings.knn_background_file)


def suppress_background(image, options):
    """Use only pixels, never detection boxes, to protect saturated/dark foreground."""
    if not options.enabled or options.strength == 0:
        return image, np.full(image.shape[:2], 255, np.uint8)
    if image.shape[0] * image.shape[1] > 2_000_000:
        raise ValueError('背景抑制仅支持不超过 200 万像素的图片')
    hsv = cv2.cvtColor(image, cv2.COLOR_BGR2HSV)
    seed = ((hsv[:, :, 1] >= 50 + options.sensitivity * 1.3)
            | (hsv[:, :, 2] <= 160 - options.sensitivity * .8)).astype(np.uint8) * 255
    mask = cv2.morphologyEx(seed, cv2.MORPH_CLOSE, np.ones((3, 3), np.uint8))
    if options.preserve_interiors:
        padded = cv2.copyMakeBorder(mask, 1, 1, 1, 1, cv2.BORDER_CONSTANT, value=0)
        flooded = padded.copy()
        cv2.floodFill(flooded, None, (0, 0), 255)
        holes = cv2.bitwise_not(flooded)[1:-1, 1:-1]
        count, components, stats, _ = cv2.connectedComponentsWithStats(holes, 8)
        area_limit = min(2500, max(256, int(min(image.shape[:2]) ** 2 * .4)))
        small_holes = stats[:, cv2.CC_STAT_AREA] <= area_limit
        small_holes[0] = False
        mask[small_holes[components]] = 255
    if options.edge_padding:
        size = 2 * options.edge_padding + 1
        mask = cv2.dilate(mask, np.ones((size, size), np.uint8))
    soft = cv2.GaussianBlur(mask, (3, 3), 0).astype(np.float32) / 255.
    soft[mask > 0] = 1.
    alpha = (1. - options.strength * (1. - soft))[:, :, None]
    result = np.clip(np.rint(image.astype(np.float32) * alpha + 245 * (1. - alpha)), 0, 255).astype(np.uint8)
    return result, mask


def prepare_detection_image(image, options=None):
    if options is None:
        current = get_background_store().read()
        options = BackgroundOptions.model_validate(current['options'])
        revision = current['revision']
    else:
        revision = None
    processed, mask = suppress_background(image, options)
    info = {'enabled': options.enabled, 'applied': bool(options.enabled and options.strength),
            'options': options.model_dump(), 'revision': revision,
            'pipeline': 'suppress_then_detect_original_crops'}
    return processed, mask, info
