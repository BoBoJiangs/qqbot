"""Capability-authenticated feedback and protected global statistics queries."""
import logging
from pathlib import Path
from typing import Literal

from fastapi import APIRouter, Query, Request
from fastapi.responses import JSONResponse
from pydantic import BaseModel, ConfigDict, Field
from starlette.concurrency import run_in_threadpool

from admin_ui import AdminUI
from routes.admin_routes import _admin_guard
from services.recognition_statistics import StatisticsError, get_recognition_statistics

statistics_routes = APIRouter()


def error_response(error):
    return JSONResponse(status_code=error.status,content={'error':str(error)})


def admin_denial(request):
    user,response = _admin_guard(request)
    if response is not None:
        return JSONResponse(status_code=401 if user is None else 403,content={'error':'请先登录管理后台并完成密码设置'})
    return None


def read_denial(request):
    if get_recognition_statistics().authorize_read(request.headers.get('X-Statistics-Key','')):
        return None
    return JSONResponse(status_code=403,content={'error':'统一统计查询密钥无效或未配置'})


class FeedbackPayload(BaseModel):
    model_config = ConfigDict(extra='forbid')
    recognitionId: str = Field(pattern=r'^[0-9a-f]{32}$')
    feedbackToken: str = Field(min_length=32,max_length=128)
    outcome: Literal['success','failure','unconfirmed']
    reason: str = Field(min_length=1,max_length=64)


@statistics_routes.post('/statistics/feedback')
async def feedback(payload: FeedbackPayload):
    try:
        return await run_in_threadpool(get_recognition_statistics().settle,payload.recognitionId,
                                      payload.feedbackToken,payload.outcome,payload.reason)
    except StatisticsError as error:
        return error_response(error)


async def summary_result(server_id,qq):
    try:
        return await run_in_threadpool(get_recognition_statistics().summary,server_id,qq)
    except StatisticsError as error:
        return error_response(error)


@statistics_routes.get('/statistics/summary')
async def public_summary(request: Request, server_id: str | None = Query(None,max_length=64),
                         qq: str | None = Query(None,pattern=r'^\d{1,20}$')):
    denial=read_denial(request)
    return denial if denial is not None else await summary_result(server_id,qq)


@statistics_routes.get('/statistics/servers')
async def public_servers(request: Request):
    denial=read_denial(request)
    return denial if denial is not None else await run_in_threadpool(get_recognition_statistics().servers)


@statistics_routes.get('/admin/recognition-statistics')
async def statistics_page(request: Request):
    user,response=_admin_guard(request)
    if response is not None:return response
    body=(Path(__file__).resolve().parents[1]/'templates/recognition_statistics.html').read_text(encoding='utf-8')
    return AdminUI().render_page('全局验证统计',body)


@statistics_routes.get('/admin/recognition-statistics/data')
async def admin_summary(request: Request,server_id: str | None = Query(None,max_length=64),
                        qq: str | None = Query(None,pattern=r'^\d{1,20}$')):
    denial=admin_denial(request)
    return denial if denial is not None else await summary_result(server_id,qq)


@statistics_routes.get('/admin/recognition-statistics/servers')
async def admin_servers(request: Request):
    denial=admin_denial(request)
    return denial if denial is not None else await run_in_threadpool(get_recognition_statistics().servers)


@statistics_routes.post('/admin/recognition-statistics/access-key')
async def admin_key(request: Request):
    denial=admin_denial(request)
    return denial if denial is not None else {'key':get_recognition_statistics().read_key()}


class ResetPayload(BaseModel):
    epoch: str = Field(pattern=r'^[0-9a-f]{32}$')


@statistics_routes.post('/admin/recognition-statistics/reset')
async def reset_statistics(payload: ResetPayload,request: Request):
    denial=admin_denial(request)
    if denial is not None:return denial
    try:
        return await run_in_threadpool(get_recognition_statistics().reset,payload.epoch)
    except StatisticsError as error:return error_response(error)


async def tracked_recognition(req,request,recognize):
    """Only feedback-capable clients participate; older clients remain compatible."""
    ticket=None
    if req.statistics is not None:
        try:
            ticket=await run_in_threadpool(get_recognition_statistics().start,req.statistics,req.recognizer,
                                          request.query_params.get('qq'))
        except Exception:
            logging.getLogger('captcha_api').exception('统一统计创建识别记录失败')
    response=await recognize(req,request)
    if ticket is not None:
        if response.get('serviceError') and response.get('error') == '图片下载失败':
            try:
                await run_in_threadpool(get_recognition_statistics().allow_image_upload,
                                        ticket['recognitionId'], req.url)
                response['uploadFallback'] = True
            except Exception:
                logging.getLogger('captcha_api').exception('创建原图上传兜底凭据失败')
        logging.getLogger('captcha_api').info(
            '识别结果 recognitionId=%s recognizer=%s code=%s serviceError=%s message=%s emojiCount=%s',
            ticket['recognitionId'],req.recognizer,response.get('code'),response.get('serviceError',False),
            response.get('error') or response.get('msg') or '',len(response.get('emojiList') or []))
        response.update(ticket)
        # Only Java can distinguish an unusable answer from a service failure.
        # Until feedback arrives, even a non-zero recognition response is unconfirmed.
    elif req.statistics is not None:
        response['statisticsError']='统一统计暂不可用，本次识别未记入全局统计'
    return response
