"""Captcha API - Main application entry point.

Refactored to use modular architecture with separate layers for:
- Data access (repositories)
- Business logic (services)
- API routes (routes)
"""
import os
import re
from datetime import datetime, timezone
from fastapi import FastAPI, Request, HTTPException
from fastapi.responses import JSONResponse
from fastapi.exceptions import RequestValidationError

# Import modules using absolute imports
import sys
import os
# Add current directory to path for imports
current_dir = os.path.dirname(os.path.abspath(__file__))
if current_dir not in sys.path:
    sys.path.insert(0, current_dir)

from config import settings
from data.whitelist_repository import get_whitelist_repository
from data.usage_repository import get_usage_repository
from services.member_service import get_member_service
from routes.admin_routes import admin_routes
from routes.member_routes import member_routes
from routes.renewal_routes import renewal_routes
from routes.recognize_routes import recognize_routes
from routes.api_routes import api_routes

# Initialize FastAPI app
app = FastAPI()

# Initialize repositories and services
whitelist_repo = get_whitelist_repository()
usage_repo = get_usage_repository()
member_service = get_member_service()

# Initialize whitelist files
whitelist_repo.initialize_files()

# Bootstrap members from whitelist
def bootstrap_members_from_whitelist():
    """Create member records for all whitelist entries."""
    try:
        allowed_ips, allowed_qq, ip_info, qq_info = whitelist_repo.get_whitelists()
        for ip in sorted(allowed_ips):
            member_service.ensure_member_from_whitelist("ip", ip, ip_info.get(ip, ""))
        for qq in sorted(allowed_qq):
            member_service.ensure_member_from_whitelist("qq", qq, qq_info.get(qq, ""))
    except Exception as e:
        import logging
        logger = logging.getLogger("captcha_api")
        logger.exception("初始化默认会员失败")


bootstrap_members_from_whitelist()

# Configure logging
import logging
_LOG_LEVEL_NAME = (os.getenv("LOG_LEVEL") or "INFO").upper()
logging.basicConfig(level=getattr(logging, _LOG_LEVEL_NAME, logging.INFO))
logger = logging.getLogger("captcha_api")


# ============== Exception Handlers ==============

@app.exception_handler(RequestValidationError)
async def validation_exception_handler(request: Request, exc: RequestValidationError):
    """Handle validation errors with detailed logging."""
    if request.url.path == "/report_error":
        content_type = request.headers.get("content-type")
        content_length = request.headers.get("content-length")
        logger.warning(
            "参数校验失败(422) path=%s content-type=%s content-length=%s errors=%s",
            request.url.path,
            content_type,
            content_length,
            exc.errors(),
        )
        try:
            form = await request.form()
            logger.warning("422 表单字段: %s", list(form.keys()))
            for key in form.keys():
                val = form.get(key)
                if hasattr(val, "filename"):
                    logger.warning("422 文件字段 %s filename=%s", key, getattr(val, "filename", None))
                else:
                    s = str(val)
                    if len(s) > 200:
                        s = s[:200] + "..."
                    logger.warning("422 文本字段 %s=%s", key, s)
        except Exception as e:
            logger.warning("422 解析表单失败: %s", e)

    return JSONResponse(
        status_code=422,
        content={"detail": exc.errors(), "code": 422, "msg": "参数校验失败"},
    )


@app.get("/favicon.ico")
async def favicon():
    """Return empty response for favicon."""
    from fastapi.responses import Response
    return Response(status_code=204)


# ============== Helper Functions ==============

def _map_http_exception_code(status_code: int, detail: str) -> int:
    """Map HTTP exception to custom error code."""
    detail = (detail or "").strip()
    if status_code == 403:
        if "到期" in detail:
            return 40302
        if "禁用" in detail:
            return 40301
        return 40300
    if status_code == 429:
        if "每天" in detail:
            return 42901
        if "本月" in detail or "月卡" in detail:
            return 42902
        return 42900
    return int(status_code) if status_code else 1


def recognize_error_payload(code: int, message: str) -> dict:
    """Create error response payload for recognition failures."""
    return {
        "code": int(code),
        "msg": message,
        "result": f"识别失败：{message}",
        "emojiList": [],
    }


def _usage_key_from_request(request: Request) -> str | None:
    """Extract usage key from request based on auth type."""
    auth_type = getattr(request.state, "auth_type", None)
    auth_info = getattr(request.state, "auth_info", None)
    if auth_type == "ip" and auth_info:
        return f"ip:{auth_info}"
    if auth_type == "qq" and auth_info:
        return f"qq:{auth_info}"
    return None


def _is_counted_path(path: str) -> bool:
    """Check if path should count towards usage limits."""
    return path in {"/recognize", "/report_error"}


def _enforce_membership_and_quota(request: Request):
    """Enforce membership tier and quota limits."""
    from fastapi import HTTPException
    from datetime import timedelta

    path = request.url.path
    if not _is_counted_path(path):
        return

    member_key = _usage_key_from_request(request)
    if not member_key:
        return

    now = datetime.now(timezone.utc)
    member = member_service.get_member(member_key)
    if not member:
        member = {"enabled": True, "tier": "normal"}

    enabled = bool(member.get("enabled", True))
    tier = (member.get("tier") or "permanent").strip().lower()
    expires_at = member_service._parse_iso_datetime(member.get("expires_at"))

    if not enabled:
        raise HTTPException(status_code=403, detail="账号已被禁用，请联系管理")

    if tier == "month":
        if expires_at and now > expires_at:
            raise HTTPException(status_code=403, detail="当前验证月卡已到期，请联系管理")

    if tier == "permanent":
        _increment_usage(member_key, now, daily_limit=None, monthly_limit=None)
        return

    if tier == "month":
        _increment_usage(member_key, now, daily_limit=None, monthly_limit=None)
        return

    try:
        daily_limit_raw = int(member.get("daily_limit", settings.normal_daily_limit))
    except Exception:
        daily_limit_raw = settings.normal_daily_limit
    daily_limit = daily_limit_raw if daily_limit_raw > 0 else None
    _increment_usage(member_key, now, daily_limit=daily_limit, monthly_limit=None)


def _increment_usage(member_key: str, now: datetime, daily_limit: int | None, monthly_limit: int | None, is_error: bool = False):
    """Increment usage counter for member."""
    from fastapi import HTTPException
    usage_repo.increment_usage(member_key, now, daily_limit, monthly_limit, is_error)


async def extract_qq_number(request: Request):
    """Extract QQ number from request query, headers, or body."""
    qq_number = request.query_params.get("qq") or request.headers.get("X-QQ-Number")
    if qq_number:
        return qq_number

    if request.method in {"POST", "PUT", "PATCH"}:
        content_type = request.headers.get("content-type") or ""
        if "application/json" in content_type:
            try:
                payload = await request.json()
            except Exception:
                payload = None
            if isinstance(payload, dict):
                qq_number = payload.get("qq") or payload.get("QQ")
                if qq_number:
                    return str(qq_number)
        if "application/x-www-form-urlencoded" in content_type or "multipart/form-data" in content_type:
            try:
                form = await request.form()
            except Exception:
                form = None
            if form is not None:
                qq_number = form.get("qq") or form.get("QQ")
                if qq_number:
                    return str(qq_number)

    return None


# ============== Middleware ==============

@app.middleware("http")
async def whitelist_middleware(request: Request, call_next):
    """Check whitelist and enforce membership/quota limits."""
    path = request.url.path
    # Bypass whitelist check for admin pages, static assets, and demo recognition endpoints
    if path.startswith("/admin") or path in {"/favicon.ico", "/robots.txt", "/whitelist/status"} or path in {"/recognize_upload", "/recognize_detail"}:
        return await call_next(request)

    client_ip = request.client.host

    # Load whitelists
    allowed_ips, allowed_qq, ip_info, qq_info = whitelist_repo.get_whitelists()

    # Case 1: If both whitelists are empty, allow all access
    if not allowed_ips and not allowed_qq:
        return await call_next(request)

    # Case 2: Check IP whitelist (priority)
    if allowed_ips and client_ip in allowed_ips:
        request.state.auth_type = "ip"
        request.state.auth_info = client_ip
        request.state.ip_comment = ip_info.get(client_ip, "")
        try:
            _enforce_membership_and_quota(request)
        except HTTPException as e:
            code = _map_http_exception_code(e.status_code, e.detail)
            return JSONResponse(status_code=e.status_code, content=recognize_error_payload(code, e.detail))
        return await call_next(request)

    # Case 3: Check QQ whitelist
    qq_probe_request = request
    if request.method in {"POST", "PUT", "PATCH"}:
        content_type = request.headers.get("content-type") or ""
        if (
            "multipart/form-data" in content_type
            or "application/x-www-form-urlencoded" in content_type
            or "application/json" in content_type
        ):
            body = await request.body()

            _downstream_sent = False

            async def downstream_receive():
                nonlocal _downstream_sent
                if _downstream_sent:
                    return {"type": "http.request", "body": b"", "more_body": False}
                _downstream_sent = True
                return {"type": "http.request", "body": body, "more_body": False}

            request._receive = downstream_receive

            _probe_sent = False

            async def probe_receive():
                nonlocal _probe_sent
                if _probe_sent:
                    return {"type": "http.request", "body": b"", "more_body": False}
                _probe_sent = True
                return {"type": "http.request", "body": body, "more_body": False}

            qq_probe_request = Request(request.scope, receive=probe_receive)

    qq_number = await extract_qq_number(qq_probe_request)

    # Check if QQ number is in whitelist
    if qq_number and allowed_qq and qq_number in allowed_qq:
        request.state.auth_type = "qq"
        request.state.auth_info = qq_number
        request.state.qq_number = qq_number
        request.state.qq_comment = qq_info.get(qq_number, "未知用户")
        try:
            _enforce_membership_and_quota(request)
        except HTTPException as e:
            code = _map_http_exception_code(e.status_code, e.detail)
            return JSONResponse(status_code=e.status_code, content=recognize_error_payload(code, e.detail))
        return await call_next(request)

    # Case 4: Neither whitelist matched, deny access
    error_msg = f"访问被拒绝 (IP: {client_ip}"
    if qq_number:
        error_msg += f", QQ: {qq_number}"
    error_msg += ")"

    return JSONResponse(
        status_code=403,
        content=recognize_error_payload(40300, error_msg)
    )


# ============== Register Routes ==============

# Include all route modules
app.include_router(admin_routes, tags=["admin"])
app.include_router(member_routes, tags=["members"])
app.include_router(renewal_routes, tags=["renewals"])
app.include_router(recognize_routes, tags=["recognize"])
app.include_router(api_routes, tags=["api"])


# ============== Additional API Endpoints ==============

@app.get("/whitelist/status")
async def get_whitelist_status(request: Request):
    """Get current whitelist status."""
    auth_type = getattr(request.state, 'auth_type', None)
    allowed_ips, allowed_qq, ip_info, qq_info = whitelist_repo.get_whitelists()

    if not auth_type and request.client.host not in {"127.0.0.1", "::1"}:
        return JSONResponse(status_code=403, content={"detail": "访问被拒绝"})

    # Group QQs by user
    users_dict = {}
    for qq_num, comment in qq_info.items():
        if comment not in users_dict:
            users_dict[comment] = []
        users_dict[comment].append(qq_num)

    response = {
        "ip_count": len(allowed_ips),
        "qq_user_count": len(users_dict),
        "qq_total_count": len(allowed_qq),
    }

    if settings.show_whitelist_details:
        ip_users = {}
        for ip in allowed_ips:
            comment = (ip_info.get(ip) or "未命名").strip() or "未命名"
            ip_users.setdefault(comment, []).append(ip)
        response["ip_users"] = ip_users
        response["qq_users"] = users_dict

    return response


@app.post("/membership/renewal_request")
async def membership_renewal_request(request: Request):
    """Submit a membership renewal request."""
    member_key = _usage_key_from_request(request)
    if not member_key:
        return JSONResponse(status_code=400, content={"detail": "未识别到用户身份"})

    note = ""
    content_type = request.headers.get("content-type") or ""
    if "application/json" in content_type:
        try:
            payload = await request.json()
        except Exception:
            payload = None
        if isinstance(payload, dict):
            note = str(payload.get("note") or payload.get("msg") or "").strip()
    else:
        try:
            form = await request.form()
        except Exception:
            form = None
        if form is not None:
            note = str(form.get("note") or form.get("msg") or "").strip()

    member = member_service.get_member(member_key) or {}
    tier = (member.get("tier") or "normal").strip().lower()
    expires_at = member_service._parse_iso_datetime(member.get("expires_at"))
    now = datetime.now(timezone.utc)

    # Get QQ number if applicable
    qq_number = None
    if member_key.startswith("qq:"):
        qq_number = member_key[3:]

    # Create renewal request
    from services.renewal_service import get_renewal_service
    renewal_service = get_renewal_service()
    renewal_service.create_request(
        member_key=member_key,
        member_name=member.get("name", ""),
        qq_number=qq_number,
        days=30
    )

    return {"code": 0, "msg": "已提交续费通知"}


# ============== Startup ==============

@app.on_event("startup")
async def startup_event():
    """Log startup info."""
    logger.info("Captcha API started with modular architecture")
    logger.info("Admin session hours: %.1f", settings.admin_session_hours)
    logger.info("Normal daily limit: %d", settings.normal_daily_limit)
    logger.info("Month card monthly limit: %d", settings.month_card_monthly_limit)
    logger.info("Admin local only: %s", settings.admin_local_only)