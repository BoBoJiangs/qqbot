import asyncio
import sys

sys.path.insert(0, ".")

from starlette.requests import Request

from services.auth_service import get_auth_service
from routes.member_routes import admin_members_form_get


async def _receive():
    return {"type": "http.request", "body": b"", "more_body": False}


async def main():
    token = get_auth_service().create_token("admin")
    scope = {
        "type": "http",
        "http_version": "1.1",
        "method": "GET",
        "scheme": "http",
        "path": "/admin/members/form",
        "raw_path": b"/admin/members/form",
        "query_string": b"",
        "headers": [(b"cookie", f"admin_session={token}".encode("utf-8"))],
        "client": ("127.0.0.1", 12345),
        "server": ("testserver", 80),
    }
    req = Request(scope, _receive)
    resp = await admin_members_form_get(req, key="", kind="", values="", comment="")
    body = getattr(resp, "body", b"") or b""
    print({"status_code": getattr(resp, "status_code", None), "has_renewDaysInput": b"renewDaysInput" in body})


if __name__ == "__main__":
    asyncio.run(main())

