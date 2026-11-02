"""Authenticated KNN failure review and explicit index publication."""
import importlib
import json
from pathlib import Path
from typing import Literal

from fastapi import APIRouter, Request, Query
from fastapi.responses import FileResponse, JSONResponse
from pydantic import BaseModel, Field
from starlette.concurrency import run_in_threadpool

from admin_ui import AdminUI
from config import settings
from routes.admin_routes import _admin_guard
from services.knn_review_service import get_knn_review_store

knn_review_routes = APIRouter()


def guard_json(request):
    user, response = _admin_guard(request)
    if response is not None:
        return JSONResponse(status_code=401 if user is None else 403,
                            content={'error': '请先登录管理后台并完成密码设置'})
    return None


def model_api():
    return importlib.import_module('routes.api_routes')


@knn_review_routes.get('/admin/knn-review')
async def review_page(request: Request):
    user, response = _admin_guard(request)
    if response is not None:
        return response
    body = (Path(__file__).resolve().parents[1] / 'templates/knn_review.html').read_text(encoding='utf-8')
    return AdminUI().render_page('KNN 人工纠错', body)


@knn_review_routes.get('/admin/knn-review/data')
async def review_data(request: Request, status: str = Query('pending', pattern='^(pending|confirmed|ignored|all)$'),
                      offset: int = Query(0, ge=0), limit: int = Query(100, ge=1, le=100)):
    denial = guard_json(request)
    if denial is not None:
        return denial
    result = get_knn_review_store().list_records(status, offset, limit)
    result['labels'] = [label for label in model_api().CLASS_LABELS if not label.isdigit()]
    path = Path(settings.knn_report_path)
    meta = json.loads(path.read_text(encoding='utf-8')) if path.exists() else {}
    result['index'] = {key: meta.get(key) for key in ['documents', 'candidates', 'reference_labels', 'published_at']}
    return result


@knn_review_routes.get('/admin/knn-review/sample/{sample_id}')
async def review_sample(sample_id: str, request: Request):
    denial = guard_json(request)
    if denial is not None:
        return denial
    try:
        return get_knn_review_store().get(sample_id)
    except (ValueError, FileNotFoundError) as error:
        return JSONResponse(status_code=404, content={'error': str(error)})


@knn_review_routes.get('/admin/knn-review/image/{sample_id}')
async def review_image(sample_id: str, request: Request):
    denial = guard_json(request)
    if denial is not None:
        return denial
    try:
        path = get_knn_review_store().image_path(sample_id)
        if not path.is_file():
            raise FileNotFoundError('图片不存在')
        return FileResponse(path, media_type='image/png', headers={'Cache-Control': 'private, no-store'})
    except (ValueError, FileNotFoundError) as error:
        return JSONResponse(status_code=404, content={'error': str(error)})


class ReviewPayload(BaseModel):
    items: list[dict] = Field(min_length=1, max_length=50)
    category: Literal['classification', 'detection', 'unclear'] = 'classification'
    note: str = Field(default='', max_length=2000)


@knn_review_routes.post('/admin/knn-review/sample/{sample_id}/confirm')
async def confirm_review(sample_id: str, payload: ReviewPayload, request: Request):
    denial = guard_json(request)
    if denial is not None:
        return denial
    try:
        return get_knn_review_store().save_review(sample_id, payload.items, model_api().CLASS_LABELS,
                                                 payload.category, payload.note)
    except (ValueError, FileNotFoundError) as error:
        return JSONResponse(status_code=400, content={'error': str(error)})


class StatusPayload(BaseModel):
    status: Literal['pending', 'ignored']


@knn_review_routes.post('/admin/knn-review/sample/{sample_id}/status')
async def change_review_status(sample_id: str, payload: StatusPayload, request: Request):
    denial = guard_json(request)
    if denial is not None:
        return denial
    try:
        return get_knn_review_store().set_status(sample_id, payload.status)
    except (ValueError, FileNotFoundError) as error:
        return JSONResponse(status_code=400, content={'error': str(error)})


@knn_review_routes.post('/admin/knn-review/publish')
async def publish_review_index(request: Request):
    denial = guard_json(request)
    if denial is not None:
        return denial
    api = model_api()
    try:
        return await run_in_threadpool(get_knn_review_store().publish, api.knn_classifier, api.classifier,
                                      api._CLASSIFIER_LOCK, api._CLASSIFIER_INPUT_SIZE)
    except Exception as error:
        import logging
        logging.getLogger('captcha_api').exception('KNN 纠错索引发布失败，保留原索引')
        return JSONResponse(status_code=400, content={'error': str(error)})
