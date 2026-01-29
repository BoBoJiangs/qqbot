from fastapi import FastAPI, UploadFile, Form, Request, HTTPException
from fastapi.responses import JSONResponse, HTMLResponse, RedirectResponse, Response
from pydantic import BaseModel
import requests
import numpy as np
import cv2
import os
import hashlib
import json
from datetime import datetime, timedelta, timezone
import re
import time
import ipaddress
import socket
import urllib.parse
import threading
import logging
import hmac
import base64
import secrets
import html

app = FastAPI()

@app.get("/favicon.ico")
async def favicon():
    return Response(status_code=204)

_LOG_LEVEL_NAME = (os.getenv("LOG_LEVEL") or "INFO").upper()
logging.basicConfig(level=getattr(logging, _LOG_LEVEL_NAME, logging.INFO))
logger = logging.getLogger("captcha_api")

# 保存错误图片目录
SAVE_DIR = "errorPic"
os.makedirs(SAVE_DIR, exist_ok=True)

# 模型路径
YOLO_CONFIG = 'models/yolov4-tiny-dota2.cfg'
YOLO_WEIGHTS = 'models/yolov4-tiny-dota2_last.weights'
CLASSIFY_CONFIG = 'models/darknet.cfg'
CLASSIFY_WEIGHTS = 'models/darknet_last.weights'
CLASSIFY_LABELS = 'models/labels.txt'

# 载入分类标签
with open(CLASSIFY_LABELS, 'r', encoding='utf-8') as f:
    CLASS_LABELS = [line.strip() for line in f if line.strip()]

# 载入模型
detector = cv2.dnn.readNetFromDarknet(YOLO_CONFIG, YOLO_WEIGHTS)
classifier = cv2.dnn.readNetFromDarknet(CLASSIFY_CONFIG, CLASSIFY_WEIGHTS)
_DETECTOR_LOCK = threading.Lock()
_CLASSIFIER_LOCK = threading.Lock()
_DETECTOR_OUTPUT_LAYERS = detector.getUnconnectedOutLayersNames()

# ----------------- 白名单配置 -----------------
ALLOWED_IPS_FILE = "allowed_ips.txt"  # IP白名单文件
ALLOWED_QQ_FILE = "allowed_qq.txt"    # QQ白名单文件

# 修改正则表达式
_QQ_LINE_RE = re.compile(r'^(.*?)[:：]\s*((?:\d+)(?:#\d+)*)#?$')
_WHITELIST_CACHE_TTL_SECONDS = float(os.getenv("WHITELIST_CACHE_TTL", "2"))
_WHITELIST_CACHE = {
    "loaded_at": 0.0,
    "ips_mtime": None,
    "qq_mtime": None,
    "allowed_ips": set(),
    "allowed_qq": set(),
    "ip_info": {},
    "qq_info": {},
}

_IMAGE_MAX_BYTES = int(os.getenv("IMAGE_MAX_BYTES", "5000000"))
_IMAGE_MAX_REDIRECTS = int(os.getenv("IMAGE_MAX_REDIRECTS", "3"))
_IMAGE_CONNECT_TIMEOUT_SECONDS = float(os.getenv("IMAGE_CONNECT_TIMEOUT_SECONDS", "3"))
_IMAGE_READ_TIMEOUT_SECONDS = float(os.getenv("IMAGE_READ_TIMEOUT_SECONDS", "7"))
_SHOW_WHITELIST_DETAILS = (os.getenv("SHOW_WHITELIST_DETAILS") or "0") == "1"

ADMIN_CREDENTIALS_FILE = "admin_credentials.json"
ADMIN_SECRET_FILE = "admin_secret.txt"
ADMIN_SETTINGS_FILE = "admin_settings.json"
MEMBERS_FILE = "members.json"
USAGE_COUNTERS_FILE = "usage_counters.json"
RENEWAL_REQUESTS_FILE = "renewal_requests.json"

ADMIN_SESSION_HOURS = float(os.getenv("ADMIN_SESSION_HOURS", "12"))
NORMAL_DAILY_LIMIT = int(os.getenv("NORMAL_DAILY_LIMIT", "20"))
MONTH_CARD_MONTHLY_LIMIT = int(os.getenv("MONTH_CARD_MONTHLY_LIMIT", "0"))
ADMIN_LOCAL_ONLY = (os.getenv("ADMIN_LOCAL_ONLY") or "0") == "1"
DEFAULT_MONTH_CARD_VALID_DAYS = int(os.getenv("DEFAULT_MONTH_CARD_VALID_DAYS", "30"))

_FILE_LOCK = threading.Lock()
_MEMBERS_CACHE = {"loaded_at": 0.0, "mtime": None, "data": {"members": {}}}
_USAGE_CACHE = {"loaded_at": 0.0, "mtime": None, "data": {"counters": {}}}
_RENEWALS_CACHE = {"loaded_at": 0.0, "mtime": None, "data": {"requests": []}}
_SETTINGS_CACHE = {"loaded_at": 0.0, "mtime": None, "data": {}}

class DownloadError(Exception):
    pass

def _is_disallowed_ip(ip_str: str) -> bool:
    try:
        ip = ipaddress.ip_address(ip_str)
    except ValueError:
        return True

    return (
        ip.is_private
        or ip.is_loopback
        or ip.is_link_local
        or ip.is_multicast
        or ip.is_reserved
        or ip.is_unspecified
    )

def _validate_fetch_url(url: str) -> str:
    parsed = urllib.parse.urlparse(url)
    if parsed.scheme not in {"http", "https"}:
        raise ValueError("unsupported scheme")

    hostname = parsed.hostname
    if not hostname:
        raise ValueError("missing hostname")

    if hostname.lower() in {"localhost"}:
        raise ValueError("disallowed hostname")

    try:
        ipaddress.ip_address(hostname)
        candidate_ips = [hostname]
    except ValueError:
        try:
            addrinfos = socket.getaddrinfo(hostname, parsed.port or (443 if parsed.scheme == "https" else 80))
        except socket.gaierror as e:
            raise ValueError("resolve failed") from e
        candidate_ips = list({ai[4][0] for ai in addrinfos if ai and ai[4]})

    if not candidate_ips or any(_is_disallowed_ip(ip) for ip in candidate_ips):
        raise ValueError("disallowed ip")

    return url

def fetch_image_bytes(url: str) -> bytes:
    session = requests.Session()
    current_url = _validate_fetch_url(url)

    for _ in range(_IMAGE_MAX_REDIRECTS + 1):
        with session.get(
            current_url,
            stream=True,
            allow_redirects=False,
            timeout=(_IMAGE_CONNECT_TIMEOUT_SECONDS, _IMAGE_READ_TIMEOUT_SECONDS),
            headers={"User-Agent": "captcha-api/1.0"},
        ) as resp:
            if resp.status_code in {301, 302, 303, 307, 308} and resp.headers.get("Location"):
                current_url = urllib.parse.urljoin(current_url, resp.headers["Location"])
                current_url = _validate_fetch_url(current_url)
                continue

            if resp.status_code != 200:
                raise DownloadError(f"bad status: {resp.status_code}")

            total = 0
            chunks = []
            for chunk in resp.iter_content(chunk_size=65536):
                if not chunk:
                    continue
                total += len(chunk)
                if total > _IMAGE_MAX_BYTES:
                    raise DownloadError("too large")
                chunks.append(chunk)

            if total == 0:
                raise DownloadError("empty")

            return b"".join(chunks)

    raise DownloadError("too many redirects")

def error_payload(message: str):
    return {
        "code": 1,
        "error": message,
        "msg": message,
        "result": f"识别失败：{message}",
        "emojiList": [],
    }

def recognize_error_payload(code: int, message: str):
    return {
        "code": int(code),
        "msg": message,
        "result": f"识别失败：{message}",
        "emojiList": [],
    }

def _map_http_exception_code(status_code: int, detail: str) -> int:
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

def _h(value) -> str:
    return html.escape("" if value is None else str(value), quote=True)

def _now_utc() -> datetime:
    return datetime.now(timezone.utc)

def _read_text_file(path: str) -> str:
    try:
        with open(path, "r", encoding="utf-8") as f:
            return f.read()
    except FileNotFoundError:
        return ""

def _atomic_write_text(path: str, content: str):
    directory = os.path.dirname(os.path.abspath(path)) or "."
    base = os.path.basename(path)
    tmp_path = os.path.join(directory, f".{base}.{secrets.token_hex(8)}.tmp")
    with open(tmp_path, "w", encoding="utf-8", newline="\n") as f:
        f.write(content)
    os.replace(tmp_path, path)

def _read_json_file(path: str, default):
    try:
        with open(path, "r", encoding="utf-8") as f:
            return json.load(f)
    except FileNotFoundError:
        return default
    except Exception:
        return default

def _atomic_write_json(path: str, data):
    content = json.dumps(data, ensure_ascii=False, indent=2)
    _atomic_write_text(path, content)

def _b64url_encode(raw: bytes) -> str:
    return base64.urlsafe_b64encode(raw).decode("ascii").rstrip("=")

def _b64url_decode(text: str) -> bytes:
    pad = "=" * (-len(text) % 4)
    return base64.urlsafe_b64decode((text + pad).encode("ascii"))

def _get_or_create_admin_secret() -> bytes:
    with _FILE_LOCK:
        existing = _read_text_file(ADMIN_SECRET_FILE).strip()
        if existing:
            try:
                return _b64url_decode(existing)
            except Exception:
                pass
        secret_bytes = secrets.token_bytes(32)
        _atomic_write_text(ADMIN_SECRET_FILE, _b64url_encode(secret_bytes))
        return secret_bytes

_ADMIN_SECRET_BYTES = _get_or_create_admin_secret()

def _hash_password(password: str, salt: bytes | None = None) -> dict:
    if salt is None:
        salt = secrets.token_bytes(16)
    dk = hashlib.pbkdf2_hmac("sha256", password.encode("utf-8"), salt, 200_000)
    return {"salt": _b64url_encode(salt), "hash": _b64url_encode(dk), "algo": "pbkdf2_sha256", "iter": 200_000}

def _verify_password(password: str, record: dict) -> bool:
    try:
        salt = _b64url_decode(record["salt"])
        expected = _b64url_decode(record["hash"])
        iterations = int(record.get("iter") or 200_000)
        dk = hashlib.pbkdf2_hmac("sha256", password.encode("utf-8"), salt, iterations)
        return hmac.compare_digest(dk, expected)
    except Exception:
        return False

def _load_admin_credentials() -> dict:
    default = {
        "username": "admin",
        "password": _hash_password("admin"),
        "must_change_password": True,
    }
    with _FILE_LOCK:
        data = _read_json_file(ADMIN_CREDENTIALS_FILE, default)
        if "username" not in data or "password" not in data:
            data = default
        if not os.path.exists(ADMIN_CREDENTIALS_FILE):
            _atomic_write_json(ADMIN_CREDENTIALS_FILE, data)
        return data

def _save_admin_credentials(data: dict):
    with _FILE_LOCK:
        _atomic_write_json(ADMIN_CREDENTIALS_FILE, data)

def _make_admin_token(username: str) -> str:
    now_ts = int(time.time())
    exp_ts = now_ts + int(ADMIN_SESSION_HOURS * 3600)
    payload = {"u": username, "iat": now_ts, "exp": exp_ts}
    payload_bytes = json.dumps(payload, separators=(",", ":"), ensure_ascii=False).encode("utf-8")
    payload_b64 = _b64url_encode(payload_bytes)
    sig = hmac.new(_ADMIN_SECRET_BYTES, payload_b64.encode("ascii"), hashlib.sha256).digest()
    return f"{payload_b64}.{_b64url_encode(sig)}"

def _verify_admin_token(token: str) -> dict | None:
    try:
        payload_b64, sig_b64 = token.split(".", 1)
        expected_sig = hmac.new(_ADMIN_SECRET_BYTES, payload_b64.encode("ascii"), hashlib.sha256).digest()
        if not hmac.compare_digest(_b64url_encode(expected_sig), sig_b64):
            return None
        payload = json.loads(_b64url_decode(payload_b64))
        if int(payload.get("exp") or 0) < int(time.time()):
            return None
        return payload
    except Exception:
        return None

def _admin_current_user(request: Request) -> str | None:
    token = request.cookies.get("admin_session")
    if not token:
        return None
    payload = _verify_admin_token(token)
    if not payload:
        return None
    return payload.get("u")

def _render_admin_page(title: str, body_html: str, message: str | None = None) -> HTMLResponse:
    css = """
    <style>
      :root { color-scheme: dark; }
      body { margin:0; font-family: ui-sans-serif, system-ui, -apple-system, Segoe UI, Roboto, Helvetica, Arial; background: radial-gradient(1200px 600px at 15% 0%, #0b1b34 0%, #070b12 50%, #05070d 100%); color:#e6edf3; }
      a { color:#93c5fd; text-decoration:none; }
      a:hover { color:#bfdbfe; text-decoration:none; }
      .app { display:flex; min-height: 100vh; }
      .sidebar { width: 240px; padding: 18px 14px; border-right: 1px solid #182033; background: linear-gradient(180deg, rgba(15,23,42,.9), rgba(2,6,23,.6)); position: sticky; top:0; height: 100vh; box-sizing:border-box; }
      .brand { display:flex; align-items:center; gap: 10px; padding: 10px 12px; border-radius: 12px; background: rgba(15,23,42,.6); border:1px solid rgba(148,163,184,.15); }
      .logo { width: 34px; height: 34px; border-radius: 10px; background: linear-gradient(135deg, #22d3ee, #60a5fa); }
      .brand-title { font-weight: 700; letter-spacing: .2px; }
      .brand-sub { font-size: 12px; color:#94a3b8; margin-top: 2px; }
      .nav { margin-top: 14px; display:flex; flex-direction: column; gap: 6px; }
      .nav a { display:block; padding: 10px 12px; border-radius: 10px; color:#cbd5e1; border:1px solid transparent; }
      .nav a:hover { background: rgba(30,41,59,.55); border-color: rgba(148,163,184,.18); color:#e2e8f0; }
      .main { flex: 1; padding: 18px 18px 40px 18px; box-sizing:border-box; }
      .header { display:flex; align-items:center; justify-content: space-between; gap: 12px; padding: 14px 16px; border-radius: 14px; background: rgba(15,23,42,.55); border:1px solid rgba(148,163,184,.15); }
      .title { font-size: 18px; font-weight: 700; }
      .content { margin-top: 14px; }
      .row { display:flex; gap: 12px; flex-wrap: wrap; }
      .row > * { flex: 1 1 340px; }
      .card { background: rgba(15,23,42,.55); border:1px solid rgba(148,163,184,.15); border-radius: 14px; padding: 16px; box-shadow: 0 10px 30px rgba(0,0,0,.25); }
      h2 { margin: 0 0 12px 0; font-size: 14px; color:#cbd5e1; letter-spacing: .2px; }
      label { display:block; font-size: 12px; color:#94a3b8; margin-top: 10px; }
      input, select, textarea { width:100%; box-sizing:border-box; padding:10px 12px; border-radius:12px; border:1px solid rgba(148,163,184,.18); background: rgba(2,6,23,.55); color:#e6edf3; outline: none; }
      input:focus, select:focus, textarea:focus { border-color: rgba(96,165,250,.75); box-shadow: 0 0 0 4px rgba(59,130,246,.15); }
      textarea { min-height: 96px; resize: vertical; }
      button { padding: 10px 12px; border-radius: 12px; border:1px solid rgba(148,163,184,.22); background: linear-gradient(180deg, rgba(30,41,59,.85), rgba(2,6,23,.55)); color:#e6edf3; cursor:pointer; }
      button:hover { border-color: rgba(96,165,250,.75); }
      .btn { display:inline-flex; align-items:center; justify-content:center; padding: 10px 12px; border-radius: 12px; border:1px solid rgba(148,163,184,.22); background: linear-gradient(180deg, rgba(30,41,59,.85), rgba(2,6,23,.55)); color:#e6edf3; }
      .btn:hover { border-color: rgba(96,165,250,.75); }
      .btn-sm { padding: 6px 10px; border-radius: 10px; font-size: 12px; }
      .btn-danger { border-color: rgba(248,113,113,.35); background: rgba(127,29,29,.25); }
      .btn-danger:hover { border-color: rgba(248,113,113,.65); }
      .muted { color:#94a3b8; font-size: 12px; }
      .msg { padding: 10px 12px; border-radius: 12px; background: rgba(2,6,23,.55); border:1px solid rgba(148,163,184,.18); margin: 12px 0; }
      table { width:100%; border-collapse: collapse; overflow:hidden; border-radius: 14px; border:1px solid rgba(148,163,184,.15); background: rgba(2,6,23,.35); }
      thead th { font-size: 12px; color:#a5b4fc; text-transform: uppercase; letter-spacing: .08em; padding: 12px 10px; background: rgba(15,23,42,.55); border-bottom: 1px solid rgba(148,163,184,.15); text-align:left; }
      tbody td { padding: 12px 10px; border-bottom:1px solid rgba(148,163,184,.12); font-size: 13px; color:#e2e8f0; text-align:left; vertical-align: middle; }
      tbody tr:hover td { background: rgba(30,41,59,.35); }
      form.inline { display:inline; }
      .actions { display:inline-flex; gap: 8px; align-items:center; flex-wrap: wrap; }
      .actions form { margin: 0; }
      @media (max-width: 860px) {
        .app { flex-direction: column; }
        .sidebar { width: 100%; height: auto; position: static; border-right: none; border-bottom: 1px solid rgba(148,163,184,.12); }
      }
    </style>
    """
    msg_html = f'<div class="msg">{message}</div>' if message else ""
    html = f"""
    <html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">{css}<title>{title}</title></head>
    <body>
      <div class="app">
        <aside class="sidebar">
          <div class="brand">
            <div class="logo"></div>
            <div>
              <div class="brand-title">Captcha Admin</div>
              <div class="brand-sub">白名单 · 会员 · 监控</div>
            </div>
          </div>
          <div class="nav">
            <a href="/admin">首页</a>
            <a href="/admin/whitelist">白名单</a>
            <a href="/admin/members">会员</a>
            <a href="/admin/renewals">续费通知</a>
            <a href="/admin/password">改密</a>
          </div>
        </aside>
        <main class="main">
          <div class="header">
            <div class="title">{title}</div>
            <form class="inline" method="post" action="/admin/logout"><button type="submit">退出</button></form>
          </div>
          <div class="content">
            {msg_html}
            {body_html}
          </div>
        </main>
      </div>
    </body></html>
    """
    return HTMLResponse(html)

def _require_admin(request: Request) -> str:
    user = _admin_current_user(request)
    if not user:
        raise HTTPException(status_code=303, headers={"Location": "/admin/login"})
    return user

def _load_members() -> dict:
    now = time.time()
    exists = os.path.exists(MEMBERS_FILE)
    mtime = os.path.getmtime(MEMBERS_FILE) if exists else None
    if (now - _MEMBERS_CACHE["loaded_at"]) < 2 and mtime == _MEMBERS_CACHE["mtime"]:
        return _MEMBERS_CACHE["data"]

    with _FILE_LOCK:
        data = _read_json_file(MEMBERS_FILE, {"members": {}})
        if "members" not in data or not isinstance(data["members"], dict):
            data = {"members": {}}
        if not exists:
            _atomic_write_json(MEMBERS_FILE, data)
        _MEMBERS_CACHE.update({"loaded_at": now, "mtime": mtime, "data": data})
        return data

def _save_members(data: dict):
    with _FILE_LOCK:
        _atomic_write_json(MEMBERS_FILE, data)
        _MEMBERS_CACHE.update({"loaded_at": time.time(), "mtime": os.path.getmtime(MEMBERS_FILE), "data": data})

def _load_usage() -> dict:
    now = time.time()
    exists = os.path.exists(USAGE_COUNTERS_FILE)
    mtime = os.path.getmtime(USAGE_COUNTERS_FILE) if exists else None
    if (now - _USAGE_CACHE["loaded_at"]) < 1 and mtime == _USAGE_CACHE["mtime"]:
        return _USAGE_CACHE["data"]

    with _FILE_LOCK:
        data = _read_json_file(USAGE_COUNTERS_FILE, {"counters": {}})
        if "counters" not in data or not isinstance(data["counters"], dict):
            data = {"counters": {}}
        if not exists:
            _atomic_write_json(USAGE_COUNTERS_FILE, data)
        _USAGE_CACHE.update({"loaded_at": now, "mtime": mtime, "data": data})
        return data

def _save_usage(data: dict):
    with _FILE_LOCK:
        _atomic_write_json(USAGE_COUNTERS_FILE, data)
        _USAGE_CACHE.update({"loaded_at": time.time(), "mtime": os.path.getmtime(USAGE_COUNTERS_FILE), "data": data})

def _load_renewals() -> dict:
    now = time.time()
    exists = os.path.exists(RENEWAL_REQUESTS_FILE)
    mtime = os.path.getmtime(RENEWAL_REQUESTS_FILE) if exists else None
    if (now - _RENEWALS_CACHE["loaded_at"]) < 2 and mtime == _RENEWALS_CACHE["mtime"]:
        return _RENEWALS_CACHE["data"]

    with _FILE_LOCK:
        data = _read_json_file(RENEWAL_REQUESTS_FILE, {"requests": []})
        if "requests" not in data or not isinstance(data["requests"], list):
            data = {"requests": []}
        if not exists:
            _atomic_write_json(RENEWAL_REQUESTS_FILE, data)
        _RENEWALS_CACHE.update({"loaded_at": now, "mtime": mtime, "data": data})
        return data

def _save_renewals(data: dict):
    with _FILE_LOCK:
        _atomic_write_json(RENEWAL_REQUESTS_FILE, data)
        _RENEWALS_CACHE.update({"loaded_at": time.time(), "mtime": os.path.getmtime(RENEWAL_REQUESTS_FILE), "data": data})

def _default_settings() -> dict:
    return {
        "normal_daily_limit": NORMAL_DAILY_LIMIT,
        "month_card_valid_days": DEFAULT_MONTH_CARD_VALID_DAYS,
        "month_card_monthly_limit": MONTH_CARD_MONTHLY_LIMIT,
        "admin_session_hours": ADMIN_SESSION_HOURS,
        "admin_local_only": ADMIN_LOCAL_ONLY,
    }

def _load_settings() -> dict:
    now = time.time()
    exists = os.path.exists(ADMIN_SETTINGS_FILE)
    mtime = os.path.getmtime(ADMIN_SETTINGS_FILE) if exists else None
    if (now - _SETTINGS_CACHE["loaded_at"]) < 2 and mtime == _SETTINGS_CACHE["mtime"]:
        return _SETTINGS_CACHE["data"]

    with _FILE_LOCK:
        defaults = _default_settings()
        data = _read_json_file(ADMIN_SETTINGS_FILE, defaults)
        if not isinstance(data, dict):
            data = defaults
        for k, v in defaults.items():
            if k not in data:
                data[k] = v
        if not exists:
            _atomic_write_json(ADMIN_SETTINGS_FILE, data)
        _SETTINGS_CACHE.update({"loaded_at": now, "mtime": mtime, "data": data})
        return data

def _save_settings(data: dict):
    with _FILE_LOCK:
        _atomic_write_json(ADMIN_SETTINGS_FILE, data)
        _SETTINGS_CACHE.update({"loaded_at": time.time(), "mtime": os.path.getmtime(ADMIN_SETTINGS_FILE), "data": data})

def _settings_int(settings: dict, key: str, fallback: int) -> int:
    try:
        value = int(settings.get(key, fallback))
        return value
    except Exception:
        return fallback

def _settings_bool(settings: dict, key: str, fallback: bool) -> bool:
    raw = settings.get(key, fallback)
    if isinstance(raw, bool):
        return raw
    if isinstance(raw, (int, float)):
        return bool(raw)
    if isinstance(raw, str):
        return raw.strip() in {"1", "true", "True", "yes", "on"}
    return fallback

def _ensure_member_exists(member_key: str, name: str | None = None):
    data = _load_members()
    members = data.setdefault("members", {})
    if member_key in members:
        return
    now = _now_utc()
    members[member_key] = {
        "name": (name or "").strip(),
        "tier": "permanent",
        "enabled": True,
        "expires_at": "",
        "updated_at": _format_iso(now),
        "created_at": _format_iso(now),
    }
    _save_members(data)

def _parse_iso_datetime(value: str | None) -> datetime | None:
    if not value:
        return None
    try:
        dt = datetime.fromisoformat(value.replace("Z", "+00:00"))
        if dt.tzinfo is None:
            dt = dt.replace(tzinfo=timezone.utc)
        return dt.astimezone(timezone.utc)
    except Exception:
        return None

def _format_iso(dt: datetime | None) -> str:
    if not dt:
        return ""
    return dt.astimezone(timezone.utc).isoformat().replace("+00:00", "Z")

def _usage_key_from_request(request: Request) -> str | None:
    auth_type = getattr(request.state, "auth_type", None)
    auth_info = getattr(request.state, "auth_info", None)
    if auth_type == "ip" and auth_info:
        return f"ip:{auth_info}"
    if auth_type == "qq" and auth_info:
        return f"qq:{auth_info}"
    return None

def _is_counted_path(path: str) -> bool:
    return path in {"/recognize", "/report_error"}

def _enforce_membership_and_quota(request: Request):
    path = request.url.path
    if not _is_counted_path(path):
        return

    member_key = _usage_key_from_request(request)
    if not member_key:
        return

    now = _now_utc()
    members_data = _load_members()
    member = (members_data.get("members") or {}).get(member_key) or {}
    enabled = bool(member.get("enabled", True))
    tier = (member.get("tier") or "permanent").strip().lower()
    expires_at = _parse_iso_datetime(member.get("expires_at"))

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
        daily_limit_raw = int(member.get("daily_limit", NORMAL_DAILY_LIMIT))
    except Exception:
        daily_limit_raw = NORMAL_DAILY_LIMIT
    daily_limit = daily_limit_raw if daily_limit_raw > 0 else None
    _increment_usage(member_key, now, daily_limit=daily_limit, monthly_limit=None)

def _increment_usage(member_key: str, now: datetime, daily_limit: int | None, monthly_limit: int | None):
    usage = _load_usage()
    counters = usage.setdefault("counters", {})
    c = counters.setdefault(member_key, {})

    day = now.date().isoformat()
    ym = f"{now.year:04d}-{now.month:02d}"
    if c.get("daily_date") != day:
        c["daily_date"] = day
        c["daily_count"] = 0
    if c.get("monthly_ym") != ym:
        c["monthly_ym"] = ym
        c["monthly_count"] = 0

    if daily_limit is not None and int(c.get("daily_count") or 0) >= daily_limit:
        raise HTTPException(status_code=429, detail=f"普通用户每天仅能调用{daily_limit}次")
    if monthly_limit is not None and int(c.get("monthly_count") or 0) >= monthly_limit:
        raise HTTPException(status_code=429, detail="月卡本月调用次数已用完")

    c["daily_count"] = int(c.get("daily_count") or 0) + 1
    c["monthly_count"] = int(c.get("monthly_count") or 0) + 1
    c["updated_at"] = _format_iso(now)
    _save_usage(usage)
# 加载IP白名单
def load_ip_whitelist():
    allowed_ips = set()
    ip_info = {}
    if not os.path.exists(ALLOWED_IPS_FILE):
        return allowed_ips, ip_info

    with open(ALLOWED_IPS_FILE, "r", encoding="utf-8") as f:
        for line_num, raw_line in enumerate(f, 1):
            line = (raw_line or "").strip()
            if not line or line.startswith("#"):
                continue

            name = ""
            ip_part = line
            if ":" in line or "：" in line:
                sep = "：" if "：" in line else ":"
                left, right = line.split(sep, 1)
                name = left.strip()
                ip_part = right.strip()

            candidates = re.split(r"[#,\s]+", ip_part)
            for candidate in candidates:
                ip = (candidate or "").strip()
                if not ip:
                    continue
                allowed_ips.add(ip)
                if name:
                    ip_info[ip] = name
                elif ip not in ip_info:
                    ip_info[ip] = ""

    return allowed_ips, ip_info

# 加载QQ白名单

def load_qq_whitelist():
    """加载QQ白名单，格式：用户名：QQ号#QQ号"""
    allowed_qq = set()
    qq_info = {}
    
    if os.path.exists(ALLOWED_QQ_FILE):
        with open(ALLOWED_QQ_FILE, "r", encoding="utf-8") as f:
            for line_num, line in enumerate(f, 1):
                line = line.strip()
                if line and not line.startswith('#'):
                    if line.isdigit():
                        allowed_qq.add(line)
                        qq_info[line] = ""
                        continue
                    # 使用正则表达式匹配格式：用户名：QQ号#QQ号
                    match = _QQ_LINE_RE.match(line)
                    if match:
                        comment = match.group(1).strip()
                        qq_part = match.group(2).strip()
                        
                        # 分割QQ号
                        qq_numbers = [num for num in qq_part.split('#') if num]
                        
                        for qq_num in qq_numbers:
                            if qq_num.isdigit():
                                allowed_qq.add(qq_num)
                                qq_info[qq_num] = comment
                            else:
                                logger.warning(f"第{line_num}行有无效的QQ号: {qq_num}")
                    else:
                        logger.warning(f"第{line_num}行格式不正确: {line}")
    
    logger.info(f"加载了 {len(allowed_qq)} 个QQ号")
    return allowed_qq, qq_info

def get_whitelists():
    now = time.time()
    ips_exists = os.path.exists(ALLOWED_IPS_FILE)
    qq_exists = os.path.exists(ALLOWED_QQ_FILE)
    ips_mtime = os.path.getmtime(ALLOWED_IPS_FILE) if ips_exists else None
    qq_mtime = os.path.getmtime(ALLOWED_QQ_FILE) if qq_exists else None

    is_fresh = (
        (now - _WHITELIST_CACHE["loaded_at"]) < _WHITELIST_CACHE_TTL_SECONDS
        and ips_mtime == _WHITELIST_CACHE["ips_mtime"]
        and qq_mtime == _WHITELIST_CACHE["qq_mtime"]
    )
    if is_fresh:
        return (
            _WHITELIST_CACHE["allowed_ips"],
            _WHITELIST_CACHE["allowed_qq"],
            _WHITELIST_CACHE["ip_info"],
            _WHITELIST_CACHE["qq_info"],
        )

    allowed_ips, ip_info = load_ip_whitelist()
    allowed_qq, qq_info = load_qq_whitelist()
    _WHITELIST_CACHE.update(
        {
            "loaded_at": now,
            "ips_mtime": ips_mtime,
            "qq_mtime": qq_mtime,
            "allowed_ips": allowed_ips,
            "allowed_qq": allowed_qq,
            "ip_info": ip_info,
            "qq_info": qq_info,
        }
    )
    return allowed_ips, allowed_qq, ip_info, qq_info

async def extract_qq_number(request: Request):
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

# 初始化时创建示例文件（如果不存在）
def initialize_whitelist_files():
    """初始化白名单文件"""
    # 创建IP白名单文件
    if not os.path.exists(ALLOWED_IPS_FILE):
        with open(ALLOWED_IPS_FILE, "w", encoding="utf-8") as f:
            f.write("# IP白名单文件，每行一个IP地址\n")
            f.write("# 支持格式：用户名：IP 或 用户名:IP（也支持一行多个IP，用 # 或 , 分隔）\n")
            f.write("# 示例：\n")
            f.write("本机：127.0.0.1\n")
            f.write("办公室：192.168.1.100#192.168.1.101\n")
        logger.info("已创建IP白名单文件: %s", ALLOWED_IPS_FILE)
    
    # 创建QQ白名单文件
    if not os.path.exists(ALLOWED_QQ_FILE):
        with open(ALLOWED_QQ_FILE, "w", encoding="utf-8") as f:
            f.write("# QQ白名单文件格式：用户名：QQ号#QQ号\n")
            f.write("# 示例：\n")
            f.write("一心：819463350#1018454301\n")
            f.write("虎虎：1212222211#213321233\n")
        logger.info("已创建QQ白名单文件: %s", ALLOWED_QQ_FILE)

# 初始化白名单文件
initialize_whitelist_files()

def _bootstrap_members_from_whitelist():
    try:
        allowed_ips, allowed_qq, ip_info, qq_info = get_whitelists()
        for ip in sorted(allowed_ips):
            _ensure_member_exists(f"ip:{ip}", ip_info.get(ip) or "")
        for qq in sorted(allowed_qq):
            _ensure_member_exists(f"qq:{qq}", qq_info.get(qq) or "")
    except Exception:
        logger.exception("初始化默认会员失败")

_bootstrap_members_from_whitelist()

def _save_ip_whitelist(ip_info: dict):
    name_to_ips = {}
    unnamed_ips = []
    for ip, name in (ip_info or {}).items():
        ip = (ip or "").strip()
        if not ip:
            continue
        name = (name or "").strip()
        if not name:
            unnamed_ips.append(ip)
            continue
        name_to_ips.setdefault(name, []).append(ip)

    lines = [
        "# IP白名单文件，每行一个IP地址",
        "# 支持格式：用户名：IP 或 用户名:IP（也支持一行多个IP，用 # 或 , 分隔）",
        "# 该文件可手动编辑，也可通过 /admin/whitelist 页面维护",
        "",
    ]

    for ip in sorted(set(unnamed_ips)):
        lines.append(ip)

    for name in sorted(name_to_ips.keys()):
        ips = "#".join(sorted(set(name_to_ips[name])))
        lines.append(f"{name}：{ips}")

    _atomic_write_text(ALLOWED_IPS_FILE, "\n".join(lines).rstrip() + "\n")

def _save_qq_whitelist(qq_info: dict):
    comment_to_qq = {}
    unnamed = []
    for qq, comment in (qq_info or {}).items():
        qq = str(qq or "").strip()
        if not qq:
            continue
        comment = (comment or "").strip()
        if not comment:
            unnamed.append(qq)
            continue
        comment_to_qq.setdefault(comment, []).append(qq)

    lines = [
        "# QQ白名单文件格式：用户名：QQ号#QQ号",
        "# 该文件可手动编辑，也可通过 /admin/whitelist 页面维护",
        "",
    ]

    for qq in sorted(set(unnamed)):
        lines.append(qq)

    for comment in sorted(comment_to_qq.keys()):
        qqs = "#".join(sorted(set(comment_to_qq[comment])))
        lines.append(f"{comment}：{qqs}")

    _atomic_write_text(ALLOWED_QQ_FILE, "\n".join(lines).rstrip() + "\n")

def _update_ip_entry(name: str, ip: str):
    allowed_ips, ip_info = load_ip_whitelist()
    ip = (ip or "").strip()
    if not ip:
        return
    ip_info[ip] = (name or "").strip()
    _save_ip_whitelist(ip_info)
    _ensure_member_exists(f"ip:{ip}", name)

def _delete_ip_entry(ip: str):
    allowed_ips, ip_info = load_ip_whitelist()
    ip = (ip or "").strip()
    if ip in ip_info:
        ip_info.pop(ip, None)
        _save_ip_whitelist(ip_info)

def _update_qq_entry(comment: str, qq: str):
    allowed_qq, qq_info = load_qq_whitelist()
    qq = str(qq or "").strip()
    if not qq:
        return
    qq_info[qq] = (comment or "").strip()
    _save_qq_whitelist(qq_info)
    _ensure_member_exists(f"qq:{qq}", comment)

def _delete_qq_entry(qq: str):
    allowed_qq, qq_info = load_qq_whitelist()
    qq = str(qq or "").strip()
    if qq in qq_info:
        qq_info.pop(qq, None)
        _save_qq_whitelist(qq_info)

# 中间件：检查白名单
@app.middleware("http")
async def whitelist_middleware(request: Request, call_next):
    path = request.url.path
    if path.startswith("/admin") or path in {"/favicon.ico", "/robots.txt"}:
        return await call_next(request)

    client_ip = request.client.host
    
    # 加载白名单
    allowed_ips, allowed_qq, ip_info, qq_info = get_whitelists()
    
    # 情况1：如果IP白名单为空且QQ白名单为空，允许所有访问
    if not allowed_ips and not allowed_qq:
        return await call_next(request)
    
    # 情况2：检查IP白名单（优先）
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
    
    # 情况3：检查QQ白名单
    qq_number = await extract_qq_number(request)
    
    # 检查QQ号是否在白名单中
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
    
    # 情况4：两个都不满足，拒绝访问
    error_msg = f"访问被拒绝 (IP: {client_ip}"
    if qq_number:
        error_msg += f", QQ: {qq_number}"
    error_msg += ")"
    
    return JSONResponse(
        status_code=403,
        content=recognize_error_payload(40300, error_msg)
    )
# ------------------------------------------------

def detect_objects(image):
    blob = cv2.dnn.blobFromImage(image, 1/255.0, (416, 416), swapRB=True, crop=False)
    with _DETECTOR_LOCK:
        detector.setInput(blob)
        outputs = detector.forward(_DETECTOR_OUTPUT_LAYERS)
    h, w = image.shape[:2]
    boxes, class_ids, confidences = [], [], []
    for output in outputs:
        for det in output:
            scores = det[5:]
            class_id = np.argmax(scores)
            confidence = scores[class_id]
            if confidence > 0.5:
                cx, cy, bw, bh = (det[:4] * np.array([w, h, w, h])).astype(int)
                x, y = int(cx - bw / 2), int(cy - bh / 2)
                boxes.append([x, y, int(bw), int(bh)])
                class_ids.append(class_id)
                confidences.append(float(confidence))

    if not boxes:
        return []

    indices = cv2.dnn.NMSBoxes(boxes, confidences, 0.5, 0.4)
    results = []
    if len(indices) > 0:
        for i in indices.flatten():
            results.append({'box': boxes[i], 'class_id': class_ids[i], 'confidence': confidences[i]})
    return results

def sort_detections_reading_order(detections):
    """
    按检测框中心点的x坐标从左到右排序
    简单而直接：完全按照水平位置从左到右排列
    """
    if not detections:
        return detections
    
    # 计算每个检测框的中心x坐标
    def get_center_x(det):
        x, y, w, h = det["box"]
        return x + w / 2
    
    # 按中心x坐标排序（从左到右）
    return sorted(detections, key=get_center_x)

def classify_char(region):
    resized = cv2.resize(region, (32, 32))
    blob = cv2.dnn.blobFromImage(resized, 1/255.0, (32, 32), swapRB=True)
    with _CLASSIFIER_LOCK:
        classifier.setInput(blob)
        preds = classifier.forward().reshape(-1)
    idx = int(np.argmax(preds))
    if 0 <= idx < len(CLASS_LABELS):
        return CLASS_LABELS[idx]
    return str(idx)

class ImageURLRequest(BaseModel):
    url: str

@app.post("/recognize")
def recognize_from_url(req: ImageURLRequest, request: Request, qq: str = None):
    """
    识别图片中的文字和表情
    参数：
    - req: 包含图片URL的JSON对象
    - qq: 查询参数，QQ号（可选）
    """
    try:
        # 获取认证信息
        auth_type = getattr(request.state, 'auth_type', None)
        auth_info = getattr(request.state, 'auth_info', None)
        
        # 记录访问日志
        if auth_type == "ip":
            logger.info("识别请求 - IP白名单访问: %s", auth_info)
        elif auth_type == "qq":
            qq_comment = getattr(request.state, 'qq_comment', "未知用户")
            logger.info("识别请求 - QQ白名单访问: %s (%s)", auth_info, qq_comment)
        else:
            logger.info("识别请求 - 未认证访问: %s", request.client.host)
        
        try:
            img_bytes = fetch_image_bytes(req.url)
        except ValueError:
            return error_payload("图片URL不允许")
        except DownloadError:
            return error_payload("图片下载失败")

        np_arr = np.frombuffer(img_bytes, np.uint8)
        image = cv2.imdecode(np_arr, cv2.IMREAD_COLOR)
        if image is None:
            return error_payload("图片解码失败")

        # 检测物体并按照中心点从左到右排序
        detections = sort_detections_reading_order(detect_objects(image))

        text_result = ""
        emoji_result = []

        for det in detections:
            x, y, w, h = det['box']
            h_img, w_img = image.shape[:2]
            x = max(0, x)
            y = max(0, y)
            x_end = min(w_img, x + w)
            y_end = min(h_img, y + h)
            if x_end <= x or y_end <= y:
                continue
            crop = image[y:y_end, x:x_end]
            if crop.size == 0:
                continue
            label = classify_char(crop)
            if det["class_id"] == 0:
                text_result += label
            else:
                emoji_result.append(label)

        if not text_result and not emoji_result:
            return error_payload("未识别到内容")

        response = {
            "code": 0,
            "result": text_result,
            "emojiList": emoji_result
        }
        
        # 如果是QQ认证，返回用户信息
        if auth_type == "qq":
            qq_comment = getattr(request.state, 'qq_comment', "未知用户")
            response["user_info"] = {
                "qq": auth_info,
                "comment": qq_comment
            }
        
        return response

    except Exception as e:
        logger.exception("识别失败: %s", e)
        return error_payload("服务异常")

@app.post("/report_error")
async def report_error(
    request: Request,
    image: UploadFile, 
    question: str = Form(...), 
    answer: str = Form(...), 
    imageUrl: str = Form(...),
    qq: str = Form(None)
):
    """
    报告识别错误
    参数：
    - image: 错误图片文件
    - question: 问题描述
    - answer: 正确答案
    - imageUrl: 图片URL
    - qq: QQ号（可选）
    """
    try:
        auth_type = getattr(request.state, 'auth_type', None)
        auth_info = getattr(request.state, 'auth_info', None)
        
        if auth_type == "ip":
            logger.info("错误报告 - IP白名单访问: %s", auth_info)
        elif auth_type == "qq":
            qq_comment = getattr(request.state, 'qq_comment', "未知用户")
            logger.info("错误报告 - QQ白名单访问: %s (%s)", auth_info, qq_comment)
        else:
            logger.info("错误报告 - 未认证访问: %s", request.client.host)
        
        img_bytes = await image.read()
        if not img_bytes:
            return error_payload("未收到图片数据")

        md5_hash = hashlib.md5(img_bytes).hexdigest()
        img_path = os.path.join(SAVE_DIR, f"{md5_hash}.jpg")
        json_path = os.path.join(SAVE_DIR, f"{md5_hash}.json")

        if not os.path.exists(img_path):
            with open(img_path, "wb") as f:
                f.write(img_bytes)
            
            save_data = {
                "question": question,
                "answer": answer,
                "imageUrl": imageUrl,
                "ip": request.client.host,
                "auth_type": auth_type,
                "auth_info": auth_info,
                "time": datetime.now().isoformat()
            }
            
            if auth_type == "qq":
                qq_comment = getattr(request.state, 'qq_comment', "未知用户")
                save_data["qq"] = auth_info
                save_data["user_comment"] = qq_comment
            
            with open(json_path, "w", encoding="utf-8") as f:
                json.dump(save_data, f, ensure_ascii=False, indent=2)
            
            return {"code": 0, "msg": "已保存"}

        return {"code": 0, "msg": "图片已存在，未重复保存"}
    except Exception as e:
        logger.exception("错误上报失败: %s", e)
        return error_payload("服务异常")

# API：查看白名单状态
@app.get("/whitelist/status")
async def get_whitelist_status(request: Request):
    """查看当前白名单状态"""
    client_ip = request.client.host
    auth_type = getattr(request.state, 'auth_type', None)
    allowed_ips, allowed_qq, ip_info, qq_info = get_whitelists()
    if not auth_type and client_ip not in {"127.0.0.1", "::1"}:
        return JSONResponse(status_code=403, content={"detail": "访问被拒绝"})
    
    # 按用户分组显示QQ号
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
    if _SHOW_WHITELIST_DETAILS:
        ip_users = {}
        for ip in allowed_ips:
            comment = (ip_info.get(ip) or "未命名").strip() or "未命名"
            ip_users.setdefault(comment, []).append(ip)
        response["ip_users"] = ip_users
        response["qq_users"] = users_dict
    return response

@app.post("/membership/renewal_request")
async def membership_renewal_request(request: Request):
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

    members = _load_members()
    member = (members.get("members") or {}).get(member_key) or {}
    tier = (member.get("tier") or "normal").strip().lower()
    expires_at = _parse_iso_datetime(member.get("expires_at"))
    now = _now_utc()

    data = _load_renewals()
    reqs = data.setdefault("requests", [])
    request_id = secrets.token_hex(8)
    reqs.append(
        {
            "id": request_id,
            "member_key": member_key,
            "tier": tier,
            "expires_at": _format_iso(expires_at) if expires_at else "",
            "note": note,
            "requested_at": _format_iso(now),
            "client_ip": request.client.host,
        }
    )
    _save_renewals(data)
    return {"code": 0, "msg": "已提交续费通知"}

def _admin_login_page(message: str | None = None) -> HTMLResponse:
    css = """
    <style>
      :root { color-scheme: dark; }
      body { margin:0; font-family: ui-sans-serif, system-ui, -apple-system, Segoe UI, Roboto, Helvetica, Arial; background: radial-gradient(1200px 600px at 15% 0%, #0b1b34 0%, #070b12 50%, #05070d 100%); color:#e6edf3; }
      .wrap { max-width: 980px; margin: 0 auto; padding: 48px 18px; display:flex; align-items:center; justify-content:center; min-height: calc(100vh - 96px); box-sizing:border-box; }
      .shell { width: 100%; display:grid; grid-template-columns: 1.1fr .9fr; gap: 16px; }
      .hero { padding: 26px; border-radius: 16px; background: rgba(15,23,42,.45); border:1px solid rgba(148,163,184,.15); }
      .hero-title { font-size: 22px; font-weight: 800; letter-spacing: .3px; margin: 0 0 8px 0; }
      .hero-sub { color:#94a3b8; font-size: 13px; line-height: 1.6; }
      .card { padding: 22px; border-radius: 16px; background: rgba(15,23,42,.55); border:1px solid rgba(148,163,184,.15); box-shadow: 0 14px 40px rgba(0,0,0,.28); }
      h1 { margin: 0 0 12px 0; font-size: 16px; color:#cbd5e1; letter-spacing: .2px; }
      label { display:block; font-size: 12px; color:#94a3b8; margin-top: 10px; }
      input { width:100%; box-sizing:border-box; padding:10px 12px; border-radius:12px; border:1px solid rgba(148,163,184,.18); background: rgba(2,6,23,.55); color:#e6edf3; outline:none; }
      input:focus { border-color: rgba(96,165,250,.75); box-shadow: 0 0 0 4px rgba(59,130,246,.15); }
      button { margin-top: 14px; padding: 10px 12px; border-radius: 12px; border:1px solid rgba(148,163,184,.22); background: linear-gradient(135deg, rgba(34,211,238,.18), rgba(96,165,250,.18)); color:#e6edf3; cursor:pointer; width:100%; font-weight: 700; }
      button:hover { border-color: rgba(96,165,250,.75); }
      .msg { padding: 10px 12px; border-radius: 12px; background: rgba(2,6,23,.55); border:1px solid rgba(148,163,184,.18); margin: 10px 0; }
      .muted { color:#94a3b8; font-size: 12px; margin-top: 10px; }
      @media (max-width: 860px) { .shell { grid-template-columns: 1fr; } .hero { display:none; } }
    </style>
    """
    msg_html = f'<div class="msg">{message}</div>' if message else ""
    html = f"""
    <html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">{css}<title>管理员登录</title></head>
    <body><div class="wrap">
      <div class="shell">
        <div class="hero">
          <div style="display:flex;align-items:center;gap:10px;margin-bottom:12px;">
            <div style="width:38px;height:38px;border-radius:12px;background:linear-gradient(135deg,#22d3ee,#60a5fa);"></div>
            <div>
              <div style="font-weight:800;">Captcha Admin</div>
              <div style="font-size:12px;color:#94a3b8;margin-top:2px;">后台管理 · 白名单 · 会员 · 续费</div>
            </div>
          </div>
          <div class="hero-title">欢迎回来</div>
          <div class="hero-sub">登录后可维护 IP/QQ 白名单、默认会员等级、调用次数与月卡有效期，并在续费通知中一键延长月卡。</div>
        </div>
        <div class="card">
          <h1>管理员登录</h1>
          {msg_html}
          <form method="post" action="/admin/login">
            <label>账号</label>
            <input name="username" value="admin" autocomplete="username" />
            <label>密码</label>
            <input name="password" type="password" autocomplete="current-password" />
            <button type="submit">登录</button>
          </form>
          <div class="muted">默认账号：admin 默认密码：admin（首次登录后强制改密）</div>
        </div>
      </div>
    </div></body></html>
    """
    return HTMLResponse(html)

def _admin_guard(request: Request):
    if ADMIN_LOCAL_ONLY and request.client.host not in {"127.0.0.1", "::1"}:
        return None, HTMLResponse("仅允许本机访问管理后台（可通过环境变量 ADMIN_LOCAL_ONLY=0 关闭）", status_code=403)
    user = _admin_current_user(request)
    if not user:
        return None, RedirectResponse("/admin/login", status_code=303)
    creds = _load_admin_credentials()
    if creds.get("must_change_password") and request.url.path not in {"/admin/password", "/admin/logout"}:
        return user, RedirectResponse("/admin/password", status_code=303)
    return user, None

@app.get("/admin/login")
async def admin_login_get(request: Request):
    if ADMIN_LOCAL_ONLY and request.client.host not in {"127.0.0.1", "::1"}:
        return HTMLResponse("仅允许本机访问管理后台（可通过环境变量 ADMIN_LOCAL_ONLY=0 关闭）", status_code=403)
    user = _admin_current_user(request)
    if user:
        return RedirectResponse("/admin", status_code=303)
    return _admin_login_page()

@app.post("/admin/login")
async def admin_login_post(request: Request):
    if ADMIN_LOCAL_ONLY and request.client.host not in {"127.0.0.1", "::1"}:
        return HTMLResponse("仅允许本机访问管理后台（可通过环境变量 ADMIN_LOCAL_ONLY=0 关闭）", status_code=403)
    form = await request.form()
    username = str(form.get("username") or "").strip()
    password = str(form.get("password") or "").strip()

    creds = _load_admin_credentials()
    if username != creds.get("username") or not _verify_password(password, creds.get("password") or {}):
        return _admin_login_page("账号或密码错误")

    token = _make_admin_token(username)
    resp = RedirectResponse("/admin", status_code=303)
    resp.set_cookie("admin_session", token, httponly=True, samesite="lax")
    return resp

@app.post("/admin/logout")
async def admin_logout_post(request: Request):
    resp = RedirectResponse("/admin/login", status_code=303)
    resp.delete_cookie("admin_session")
    return resp

@app.get("/admin")
async def admin_index(request: Request):
    user, redirect = _admin_guard(request)
    if redirect:
        return redirect

    allowed_ips, allowed_qq, ip_info, qq_info = get_whitelists()
    members = _load_members()
    member_count = len((members.get("members") or {}).keys())
    renewals = _load_renewals()
    renewal_count = len(renewals.get("requests") or [])
    body = f"""
    <div class="row">
      <div class="card">
        <h2>白名单</h2>
        <div class="muted">IP：{len(allowed_ips)}，QQ：{len(allowed_qq)}</div>
        <div style="margin-top:10px;"><a href="/admin/whitelist">打开配置</a></div>
      </div>
      <div class="card">
        <h2>会员</h2>
        <div class="muted">已配置：{member_count}</div>
        <div style="margin-top:10px;"><a href="/admin/members">打开管理</a></div>
      </div>
      <div class="card">
        <h2>续费通知</h2>
        <div class="muted">待处理：{renewal_count}</div>
        <div style="margin-top:10px;"><a href="/admin/renewals">打开列表</a></div>
      </div>
    </div>
    """
    return _render_admin_page("管理后台", body)

@app.get("/admin/password")
async def admin_password_get(request: Request):
    user = _admin_current_user(request)
    if not user:
        return RedirectResponse("/admin/login", status_code=303)
    body = """
    <form method="post" action="/admin/password">
      <label>旧密码</label>
      <input name="old_password" type="password" autocomplete="current-password" />
      <label>新密码</label>
      <input name="new_password" type="password" autocomplete="new-password" />
      <label>确认新密码</label>
      <input name="new_password2" type="password" autocomplete="new-password" />
      <button type="submit" style="margin-top:12px;">保存</button>
    </form>
    """
    return _render_admin_page("修改管理员密码", body)

@app.post("/admin/password")
async def admin_password_post(request: Request):
    user = _admin_current_user(request)
    if not user:
        return RedirectResponse("/admin/login", status_code=303)
    form = await request.form()
    old_password = str(form.get("old_password") or "")
    new_password = str(form.get("new_password") or "")
    new_password2 = str(form.get("new_password2") or "")
    if not new_password or len(new_password) < 6:
        return _render_admin_page("修改管理员密码", "<div>新密码至少 6 位</div>")
    if new_password != new_password2:
        return _render_admin_page("修改管理员密码", "<div>两次输入的新密码不一致</div>")
    creds = _load_admin_credentials()
    if not _verify_password(old_password, creds.get("password") or {}):
        return _render_admin_page("修改管理员密码", "<div>旧密码不正确</div>")
    creds["password"] = _hash_password(new_password)
    creds["must_change_password"] = False
    _save_admin_credentials(creds)
    return _render_admin_page("修改管理员密码", '<div>已更新密码</div><div style="margin-top:10px;"><a href="/admin">返回首页</a></div>')

@app.get("/admin/whitelist")
async def admin_whitelist_get(request: Request):
    user, redirect = _admin_guard(request)
    if redirect:
        return redirect

    allowed_ips, allowed_qq, ip_info, qq_info = get_whitelists()
    ip_rows = []
    for ip in sorted(allowed_ips):
        name = (ip_info.get(ip) or "").strip() or "未命名"
        edit_url = "/admin/members/form?kind=ip&values=" + urllib.parse.quote(ip)
        ip_rows.append(
            "<tr>"
            f"<td>{_h(ip)}</td>"
            f"<td>{_h(name)}</td>"
            "<td><div class='actions'>"
            f"<a class='btn btn-sm' href=\"{_h(edit_url)}\">编辑</a>"
            f"<form class='inline' method='post' action='/admin/whitelist/ip/delete'>"
            f"<input type='hidden' name='ip' value='{_h(ip)}'/>"
            f"<button class='btn-danger btn-sm' type='submit'>删除</button></form>"
            "</div></td>"
            "</tr>"
        )

    qq_groups = {}
    for qq in sorted(allowed_qq):
        comment = (qq_info.get(qq) or "").strip() or "未命名"
        qq_groups.setdefault(comment, []).append(qq)

    qq_rows = []
    for comment in sorted(qq_groups.keys()):
        qq_list = qq_groups[comment]
        edit_url = "/admin/members/form?kind=qq&comment=" + urllib.parse.quote(comment)
        qq_rows.append(
            "<tr>"
            f"<td>{_h(comment)}</td>"
            f"<td>{len(qq_list)}</td>"
            f"<td>{_h('#'.join(qq_list))}</td>"
            "<td><div class='actions'>"
            f"<a class='btn btn-sm' href=\"{_h(edit_url)}\">编辑</a>"
            f"<form class='inline' method='post' action='/admin/whitelist/qq_group/delete'>"
            f"<input type='hidden' name='comment' value='{_h(comment)}'/>"
            f"<button class='btn-danger btn-sm' type='submit'>删除</button></form>"
            "</div></td>"
            "</tr>"
        )

    body = f"""
    <div class="row">
      <div class="card">
        <h2>IP 白名单</h2>
        <div class="muted">新增/编辑请到 <a href="/admin/members/form">新增会员</a>。</div>
        <div style="margin-top:12px;"><a class="btn" href="/admin/members/form">新增会员</a></div>
        <div style="margin-top:12px;"></div>
        <table>
          <thead><tr><th>IP</th><th>用户</th><th>操作</th></tr></thead>
          <tbody>{''.join(ip_rows) or '<tr><td colspan="3" class="muted">暂无</td></tr>'}</tbody>
        </table>
      </div>
      <div class="card">
        <h2>QQ 白名单</h2>
        <div class="muted">按用户分组显示（相同用户只显示一条）。新增/编辑请到 <a href="/admin/members/form">新增会员</a>。</div>
        <div style="margin-top:12px;"><a class="btn" href="/admin/members/form">新增会员</a></div>
        <div style="margin-top:12px;"></div>
        <table>
          <thead><tr><th>用户</th><th>数量</th><th>QQ 列表</th><th>操作</th></tr></thead>
          <tbody>{''.join(qq_rows) or '<tr><td colspan="4" class="muted">暂无</td></tr>'}</tbody>
        </table>
      </div>
    </div>
    """
    return _render_admin_page("白名单", body)

@app.post("/admin/whitelist/ip/delete")
async def admin_whitelist_ip_delete(request: Request):
    user, redirect = _admin_guard(request)
    if redirect:
        return redirect
    form = await request.form()
    ip = str(form.get("ip") or "").strip()
    if ip:
        _delete_ip_entry(ip)
    return RedirectResponse("/admin/whitelist", status_code=303)

@app.post("/admin/whitelist/qq_group/delete")
async def admin_whitelist_qq_group_delete(request: Request):
    user, redirect = _admin_guard(request)
    if redirect:
        return redirect
    form = await request.form()
    comment = str(form.get("comment") or "").strip()
    if comment:
        allowed_qq, qq_info = load_qq_whitelist()
        if comment == "未命名":
            to_delete = [qq for qq, c in qq_info.items() if not (c or "").strip()]
        else:
            to_delete = [qq for qq, c in qq_info.items() if (c or "").strip() == comment]
        for qq in to_delete:
            qq_info.pop(qq, None)
        _save_qq_whitelist(qq_info)
    return RedirectResponse("/admin/whitelist", status_code=303)

def _member_key(kind: str, value: str) -> str:
    kind = (kind or "").strip().lower()
    value = str(value or "").strip()
    if kind not in {"ip", "qq"}:
        raise ValueError("kind")
    if not value:
        raise ValueError("value")
    return f"{kind}:{value}"

@app.get("/admin/members")
async def admin_members_get(request: Request):
    user, redirect = _admin_guard(request)
    if redirect:
        return redirect

    members_data = _load_members()
    members = members_data.get("members") or {}
    usage = _load_usage().get("counters") or {}

    def tier_cn(tier: str) -> str:
        t = (tier or "").strip().lower()
        if t == "month":
            return "月卡"
        if t == "permanent":
            return "永久"
        return "普通"

    rows = []
    for key in sorted(members.keys()):
        m = members.get(key) or {}
        tier = (m.get("tier") or "permanent").strip().lower()
        enabled = bool(m.get("enabled", True))
        name = (m.get("name") or "").strip()
        exp = (m.get("expires_at") or "").strip()
        u = usage.get(key) or {}
        daily = int(u.get("daily_count") or 0)
        daily_limit_raw = m.get("daily_limit", NORMAL_DAILY_LIMIT)
        try:
            daily_limit = int(daily_limit_raw)
        except Exception:
            daily_limit = NORMAL_DAILY_LIMIT
        renewal_days_raw = m.get("renewal_days", DEFAULT_MONTH_CARD_VALID_DAYS)
        try:
            renewal_days = int(renewal_days_raw)
        except Exception:
            renewal_days = DEFAULT_MONTH_CARD_VALID_DAYS

        config_text = "-"
        if tier == "normal":
            config_text = "日限 不限" if daily_limit <= 0 else f"日限 {daily_limit}"
        elif tier == "month":
            config_text = f"续费 {max(1, renewal_days)}天"

        edit_url = "/admin/members/form?key=" + urllib.parse.quote(key)
        rows.append(
            "<tr>"
            f"<td>{_h(key)}</td>"
            f"<td>{_h(name or '未命名')}</td>"
            f"<td>{_h(tier_cn(tier))}</td>"
            f"<td>{'启用' if enabled else '禁用'}</td>"
            f"<td>{_h(exp or '-')}</td>"
            f"<td>{daily}</td>"
            f"<td>{_h(config_text)}</td>"
            "<td><div class='actions'>"
            f"<a class='btn btn-sm' href=\"{_h(edit_url)}\">编辑</a>"
            f"<form class='inline' method='post' action='/admin/members/delete'>"
            f"<input type='hidden' name='key' value='{_h(key)}'/>"
            f"<button class='btn-danger btn-sm' type='submit'>删除</button></form>"
            "</div></td>"
            "</tr>"
        )

    body = f"""
    <div class="card">
      <h2>会员列表</h2>
      <div class="muted">新增/编辑会员会同时维护对应的 IP/QQ 白名单。</div>
      <div style="margin-top:12px;"><a class="btn" href="/admin/members/form">新增会员</a></div>
      <div style="margin-top:12px;"></div>
      <table>
        <thead><tr><th>Key</th><th>名称</th><th>类型</th><th>状态</th><th>到期</th><th>今日调用</th><th>配置</th><th>操作</th></tr></thead>
        <tbody>{''.join(rows) or '<tr><td colspan="8" class="muted">暂无</td></tr>'}</tbody>
      </table>
    </div>
    """
    return _render_admin_page("会员", body)

@app.get("/admin/members/form")
async def admin_members_form_get(request: Request):
    user, redirect = _admin_guard(request)
    if redirect:
        return redirect

    params = request.query_params
    key = (params.get("key") or "").strip()
    kind = (params.get("kind") or "").strip().lower()
    comment = (params.get("comment") or "").strip()
    values_text = (params.get("values") or "").strip()

    current_name = ""
    current_kind = kind or ""
    current_values = []
    current_key = ""

    members = (_load_members().get("members") or {})
    member = {}

    allowed_ips, allowed_qq, ip_info, qq_info = get_whitelists()

    if key:
        current_key = key
        if ":" in key:
            current_kind = key.split(":", 1)[0].strip().lower()
            single_value = key.split(":", 1)[1].strip()
            current_values = [single_value] if single_value else []
        member = members.get(key) or {}

    if current_kind == "ip":
        if values_text:
            current_values = [v for v in re.split(r"[#,\s]+", values_text) if v]
        if current_values:
            current_name = (ip_info.get(current_values[0]) or "").strip()
    elif current_kind == "qq":
        if comment:
            current_name = comment
            if comment == "未命名":
                current_values = [qq for qq, c in qq_info.items() if not (c or "").strip()]
            else:
                current_values = [qq for qq, c in qq_info.items() if (c or "").strip() == comment]
        elif values_text:
            current_values = [v for v in re.split(r"[#,\s]+", values_text) if v]
            if current_values:
                c0 = (qq_info.get(current_values[0]) or "").strip()
                current_name = c0 or "未命名"
                if current_name == "未命名":
                    current_values = [qq for qq, c in qq_info.items() if not (c or "").strip()]
                else:
                    current_values = [qq for qq, c in qq_info.items() if (c or "").strip() == current_name]
        if current_values and not current_key:
            current_key = f"qq:{current_values[0]}"
            member = members.get(current_key) or {}

    tier = (member.get("tier") or "permanent").strip().lower()
    enabled = bool(member.get("enabled", True))
    expires_at = (member.get("expires_at") or "").strip()
    daily_limit = member.get("daily_limit", NORMAL_DAILY_LIMIT)
    renewal_days = member.get("renewal_days", DEFAULT_MONTH_CARD_VALID_DAYS)

    body = f"""
    <div class="card">
      <h2>新增 / 编辑会员</h2>
      <form method="post" action="/admin/members/form">
        <label>类型</label>
        <select name="kind">
          <option value="ip" {"selected" if current_kind == "ip" else ""}>IP</option>
          <option value="qq" {"selected" if current_kind == "qq" else ""}>QQ</option>
        </select>
        <label>用户名称（用于 QQ 分组显示）</label>
        <input name="name" value="{_h(current_name)}" placeholder="例如：sin / 一心" />
        <label>值（支持多个：用 # 或 , 或空格分隔）</label>
        <input name="values" value="{_h('#'.join(current_values))}" placeholder="例如：1.2.3.4#5.6.7.8 或 3205807349#2915739699" />
        <label>会员等级</label>
        <select name="tier">
          <option value="normal" {"selected" if tier == "normal" else ""}>普通</option>
          <option value="month" {"selected" if tier == "month" else ""}>月卡</option>
          <option value="permanent" {"selected" if tier == "permanent" else ""}>永久</option>
        </select>
        <label>状态</label>
        <select name="enabled">
          <option value="1" {"selected" if enabled else ""}>启用</option>
          <option value="0" {"selected" if not enabled else ""}>禁用</option>
        </select>
        <div id="normalFields">
          <label>普通会员：每日调用次数上限（0 表示不限制，默认 {NORMAL_DAILY_LIMIT}）</label>
          <input name="daily_limit" type="number" min="0" value="{_h(daily_limit)}" />
        </div>
        <div id="monthFields">
          <label>月卡会员：续费天数（默认 {DEFAULT_MONTH_CARD_VALID_DAYS}）</label>
          <input name="renewal_days" type="number" min="1" value="{_h(renewal_days)}" />
          <div class="muted" style="margin-top:10px;">当前到期：{_h(expires_at or '-')}</div>
        </div>
        <input type="hidden" name="key" value="{_h(current_key)}" />
        <div style="margin-top:12px; display:flex; gap:10px; flex-wrap:wrap;">
          <button type="submit">保存</button>
          <a href="/admin/members"><button type="button">返回列表</button></a>
        </div>
      </form>
      <script>
        (function() {{
          var tierSelect = document.querySelector('select[name="tier"]');
          var normalFields = document.getElementById('normalFields');
          var monthFields = document.getElementById('monthFields');
          function syncFields() {{
            var t = (tierSelect && tierSelect.value) || 'permanent';
            if (normalFields) normalFields.style.display = (t === 'normal') ? '' : 'none';
            if (monthFields) monthFields.style.display = (t === 'month') ? '' : 'none';
          }}
          if (tierSelect) tierSelect.addEventListener('change', syncFields);
          syncFields();
        }})();
      </script>
      <div class="muted" style="margin-top:10px;">月卡到期后提示：当前验证月卡已到期，请联系管理</div>
    </div>
    """
    return _render_admin_page("会员编辑", body)

@app.post("/admin/members/form")
async def admin_members_form_post(request: Request):
    user, redirect = _admin_guard(request)
    if redirect:
        return redirect

    form = await request.form()
    kind = str(form.get("kind") or "").strip().lower()
    name = str(form.get("name") or "").strip()
    values_text = str(form.get("values") or "").strip()
    tier = str(form.get("tier") or "permanent").strip().lower()
    enabled = str(form.get("enabled") or "1").strip() == "1"

    try:
        daily_limit = int(str(form.get("daily_limit") or NORMAL_DAILY_LIMIT))
    except Exception:
        daily_limit = NORMAL_DAILY_LIMIT
    daily_limit = max(0, daily_limit)

    try:
        renewal_days = int(str(form.get("renewal_days") or DEFAULT_MONTH_CARD_VALID_DAYS))
    except Exception:
        renewal_days = DEFAULT_MONTH_CARD_VALID_DAYS
    renewal_days = max(1, renewal_days)

    values = [v for v in re.split(r"[#,\s]+", values_text) if v]
    if not values:
        return _render_admin_page("会员编辑", "<div>值不能为空</div>")

    now = _now_utc()
    members_data = _load_members()
    members = members_data.setdefault("members", {})

    if kind == "ip":
        ip_info_map = load_ip_whitelist()[1]
        for ip in values:
            try:
                ipaddress.ip_address(ip)
            except Exception:
                return _render_admin_page("会员编辑", f"<div>IP 格式不正确：{_h(ip)}</div>")
            ip_info_map[ip] = name
        _save_ip_whitelist(ip_info_map)

        for ip in values:
            key = f"ip:{ip}"
            existing = members.get(key) or {}
            m = {
                "name": name or existing.get("name") or "",
                "tier": tier,
                "enabled": enabled,
                "updated_at": _format_iso(now),
                "created_at": existing.get("created_at") or _format_iso(now),
            }
            if tier == "normal":
                m["daily_limit"] = daily_limit
                m["expires_at"] = ""
                m.pop("renewal_days", None)
            elif tier == "month":
                m["renewal_days"] = renewal_days
                exp = now + timedelta(days=renewal_days)
                m["expires_at"] = _format_iso(exp)
                m.pop("daily_limit", None)
            else:
                m["expires_at"] = ""
                m.pop("daily_limit", None)
                m.pop("renewal_days", None)
            members[key] = m

    elif kind == "qq":
        for qq in values:
            if not str(qq).isdigit():
                return _render_admin_page("会员编辑", f"<div>QQ 格式不正确：{_h(qq)}</div>")

        allowed_qq, qq_info_map = load_qq_whitelist()
        to_delete = [qq for qq, c in qq_info_map.items() if (c or "").strip() == name]
        if name == "未命名" or not name:
            to_delete = [qq for qq, c in qq_info_map.items() if not (c or "").strip()]
        for qq in to_delete:
            qq_info_map.pop(qq, None)
        for qq in values:
            qq_info_map[str(qq)] = name if name != "未命名" else ""
        _save_qq_whitelist(qq_info_map)

        for qq in values:
            key = f"qq:{qq}"
            existing = members.get(key) or {}
            m = {
                "name": name or existing.get("name") or "",
                "tier": tier,
                "enabled": enabled,
                "updated_at": _format_iso(now),
                "created_at": existing.get("created_at") or _format_iso(now),
            }
            if tier == "normal":
                m["daily_limit"] = daily_limit
                m["expires_at"] = ""
                m.pop("renewal_days", None)
            elif tier == "month":
                m["renewal_days"] = renewal_days
                exp = now + timedelta(days=renewal_days)
                m["expires_at"] = _format_iso(exp)
                m.pop("daily_limit", None)
            else:
                m["expires_at"] = ""
                m.pop("daily_limit", None)
                m.pop("renewal_days", None)
            members[key] = m
    else:
        return _render_admin_page("会员编辑", "<div>类型不正确</div>")

    _save_members(members_data)
    return RedirectResponse("/admin/members", status_code=303)

@app.post("/admin/members/delete")
async def admin_members_delete(request: Request):
    user, redirect = _admin_guard(request)
    if redirect:
        return redirect
    form = await request.form()
    key = str(form.get("key") or "").strip()
    if key:
        data = _load_members()
        members = data.setdefault("members", {})
        members.pop(key, None)
        _save_members(data)
    return RedirectResponse("/admin/members", status_code=303)

@app.get("/admin/renewals")
async def admin_renewals_get(request: Request):
    user, redirect = _admin_guard(request)
    if redirect:
        return redirect

    data = _load_renewals()
    reqs = list(data.get("requests") or [])
    reqs.sort(key=lambda r: r.get("requested_at") or "", reverse=True)
    members = (_load_members().get("members") or {})

    def tier_cn(tier: str) -> str:
        t = (tier or "").strip().lower()
        if t == "month":
            return "月卡"
        if t == "permanent":
            return "永久"
        return "普通"

    rows = []
    for r in reqs:
        rid = str(r.get("id") or "")
        key = str(r.get("member_key") or "")
        tier = str(r.get("tier") or "")
        exp = str(r.get("expires_at") or "")
        note = str(r.get("note") or "")
        at = str(r.get("requested_at") or "")
        cip = str(r.get("client_ip") or "")
        m = members.get(key) or {}
        try:
            days = int(m.get("renewal_days", DEFAULT_MONTH_CARD_VALID_DAYS))
        except Exception:
            days = DEFAULT_MONTH_CARD_VALID_DAYS
        days = max(1, days)
        rows.append(
            "<tr>"
            f"<td>{_h(rid)}</td><td>{_h(key)}</td><td>{_h(tier_cn(tier))}</td><td>{_h(exp or '-')}</td><td>{_h(note)}</td><td>{_h(cip)}</td><td>{_h(at)}</td>"
            "<td><div class='actions'>"
            f"<form class='inline' method='post' action='/admin/renewals/approve'><input type='hidden' name='id' value='{_h(rid)}'/><button class='btn-sm' type='submit'>批准+{days}天</button></form>"
            f"<form class='inline' method='post' action='/admin/renewals/dismiss'><input type='hidden' name='id' value='{_h(rid)}'/><button class='btn-danger btn-sm' type='submit'>忽略</button></form>"
            "</div></td>"
            "</tr>"
        )
    body = f"""
    <div class="muted">用户可调用 /membership/renewal_request 提交续费通知；这里进行审批与延长月卡。</div>
    <h2 style="margin-top:18px;">待处理列表（{len(reqs)}）</h2>
    <table>
      <thead><tr><th>ID</th><th>Key</th><th>类型</th><th>到期</th><th>备注</th><th>IP</th><th>时间</th><th>操作</th></tr></thead>
      <tbody>{''.join(rows) or '<tr><td colspan="8" class="muted">暂无</td></tr>'}</tbody>
    </table>
    """
    return _render_admin_page("续费通知", body)

@app.post("/admin/renewals/approve")
async def admin_renewals_approve(request: Request):
    user, redirect = _admin_guard(request)
    if redirect:
        return redirect
    form = await request.form()
    rid = str(form.get("id") or "").strip()
    data = _load_renewals()
    reqs = data.setdefault("requests", [])
    target = None
    rest = []
    for r in reqs:
        if str(r.get("id") or "") == rid and target is None:
            target = r
        else:
            rest.append(r)
    data["requests"] = rest
    _save_renewals(data)

    if target:
        key = str(target.get("member_key") or "").strip()
        if key:
            now = _now_utc()
            members_data = _load_members()
            members = members_data.setdefault("members", {})
            m = members.get(key) or {"tier": "month"}
            try:
                days = int(m.get("renewal_days", DEFAULT_MONTH_CARD_VALID_DAYS))
            except Exception:
                days = DEFAULT_MONTH_CARD_VALID_DAYS
            days = max(1, days)
            exp = _parse_iso_datetime(m.get("expires_at"))
            base = exp if exp and exp > now else now
            m["tier"] = "month"
            m["enabled"] = True
            m["renewal_days"] = days
            m["expires_at"] = _format_iso(base + timedelta(days=days))
            m["updated_at"] = _format_iso(now)
            m["created_at"] = m.get("created_at") or _format_iso(now)
            members[key] = m
            _save_members(members_data)

    return RedirectResponse("/admin/renewals", status_code=303)

@app.post("/admin/renewals/dismiss")
async def admin_renewals_dismiss(request: Request):
    user, redirect = _admin_guard(request)
    if redirect:
        return redirect
    form = await request.form()
    rid = str(form.get("id") or "").strip()
    data = _load_renewals()
    reqs = data.setdefault("requests", [])
    data["requests"] = [r for r in reqs if str(r.get("id") or "") != rid]
    _save_renewals(data)
    return RedirectResponse("/admin/renewals", status_code=303)
