"""Admin-only KNN preprocessing controls and isolated image previews."""
import base64
import importlib
from pathlib import Path
import logging

import cv2
import numpy as np
from fastapi import APIRouter, File, Form, Request, UploadFile
from fastapi.responses import JSONResponse
from pydantic import BaseModel, ConfigDict, Field
from starlette.concurrency import run_in_threadpool

from admin_ui import AdminUI
from config import settings
from knn_background import BackgroundOptions, StaleSettings, get_background_store
from routes.admin_routes import _admin_guard
from services.knn_review_service import get_knn_review_store

knn_background_routes = APIRouter()


def guard_json(request):
    user, response = _admin_guard(request)
    if response is not None:
        return JSONResponse(status_code=401 if user is None else 403,
                            content={'error': '请先登录管理后台并完成密码设置'})
    return None


@knn_background_routes.get('/admin/knn-background')
async def background_page(request: Request):
    user, response = _admin_guard(request)
    if response is not None:
        return response
    body = (Path(__file__).resolve().parents[1] / 'templates/knn_background.html').read_text(encoding='utf-8')
    return AdminUI().render_page('KNN 背景抑制', body)


@knn_background_routes.get('/admin/knn-background/settings')
async def read_background_settings(request: Request):
    denial = guard_json(request)
    if denial is not None:
        return denial
    try:
        return get_background_store().read()
    except (ValueError, OSError):
        return JSONResponse(status_code=400, content={'error': '背景抑制配置无法读取，请检查配置文件'})


class SettingsPayload(BaseModel):
    model_config = ConfigDict(extra='forbid')
    options: BackgroundOptions
    revision: str = Field(pattern=r'^[0-9a-f]{64}$')


@knn_background_routes.post('/admin/knn-background/settings')
async def save_background_settings(payload: SettingsPayload, request: Request):
    denial = guard_json(request)
    if denial is not None:
        return denial
    try:
        return await run_in_threadpool(get_background_store().save, payload.options, payload.revision)
    except StaleSettings as error:
        return JSONResponse(status_code=409, content={'error': str(error)})
    except (ValueError, OSError):
        logging.getLogger('captcha_api').exception('KNN 背景参数保存失败')
        return JSONResponse(status_code=400, content={'error': '参数保存失败，原配置保持不变'})


def png(image):
    success, data = cv2.imencode('.png', image)
    if not success:
        raise ValueError('预览图片编码失败')
    return 'data:image/png;base64,' + base64.b64encode(data.tobytes()).decode('ascii')


def preview_recognition(data, options):
    image = cv2.imdecode(np.frombuffer(data, np.uint8), cv2.IMREAD_COLOR)
    if image is None:
        raise ValueError('图片无法解码，请选择 PNG 或 JPEG 图片')
    if image.shape[0] * image.shape[1] > 2_000_000:
        raise ValueError('预览图片不能超过 200 万像素')
    api = importlib.import_module('routes.api_routes')
    original, original_context = api.recognize_image(image, 'knn', options.model_copy(update={'enabled': False}))
    processed, processed_context = api.recognize_image(image, 'knn', options)
    return {'width': int(image.shape[1]), 'height': int(image.shape[0]), 'options': options.model_dump(),
            'original': {**original, 'items': original_context['items'], 'image': png(image)},
            'processed': {**processed, 'items': processed_context['items'], 'image': png(processed_context['image'])},
            'mask_image': png(processed_context['mask']),
            'changed_fraction': float(np.mean(np.any(image != processed_context['image'], axis=2)))}


@knn_background_routes.post('/admin/knn-background/preview')
async def preview_background(request: Request, options_json: str = Form('{}'),
                             sample_id: str | None = Form(None), image: UploadFile | None = File(None)):
    denial = guard_json(request)
    if denial is not None:
        if image is not None:
            await image.close()
        return denial
    try:
        options = BackgroundOptions.model_validate_json(options_json)
        if image is not None and sample_id:
            raise ValueError('请选择上传图片或已有错题中的一种')
        if image is not None:
            data = await image.read(settings.image_max_bytes + 1)
        elif sample_id:
            path = get_knn_review_store().image_path(sample_id)
            if not path.is_file():
                raise ValueError('错题图片不存在')
            if path.stat().st_size > settings.image_max_bytes:
                raise ValueError('图片过大')
            data = path.read_bytes()
        else:
            raise ValueError('请先选择图片或从人工纠错页面进入预览')
        if not data or len(data) > settings.image_max_bytes:
            raise ValueError('图片为空或超过大小限制')
        return await run_in_threadpool(preview_recognition, data, options)
    except (ValueError, OSError) as error:
        return JSONResponse(status_code=400, content={'error': str(error) if not isinstance(error, OSError) else '图片无法读取'})
    except Exception:
        logging.getLogger('captcha_api').exception('KNN 背景抑制预览失败')
        return JSONResponse(status_code=400, content={'error': '预览识别失败，请检查检测模型与 KNN 索引'})
    finally:
        if image is not None:
            await image.close()
