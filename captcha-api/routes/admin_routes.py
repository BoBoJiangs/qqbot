"""Admin authentication and dashboard routes.

Handles admin login, logout, password management, and dashboard.
"""
import urllib.parse
from fastapi import APIRouter, Request, Form, Response
from fastapi.responses import HTMLResponse, RedirectResponse, JSONResponse
from services.auth_service import get_auth_service
from services.member_service import get_member_service
from services.renewal_service import get_renewal_service
from data.whitelist_repository import get_whitelist_repository
from admin_ui import AdminUI


# Create router
admin_routes = APIRouter()

# Initialize services
auth_service = get_auth_service()
member_service = get_member_service()
renewal_service = get_renewal_service()
whitelist_repo = get_whitelist_repository()
admin_ui = AdminUI()


def _h(value: str) -> str:
    """HTML escape helper."""
    import html
    return html.escape("" if value is None else str(value), quote=True)


def _admin_guard(request: Request):
    """Guard function for admin routes.

    Returns:
        Tuple of (username, response) where response is None if authenticated,
        or a redirect response if not.
    """
    if auth_service.is_local_only() and request.client.host not in {"127.0.0.1", "::1"}:
        return None, HTMLResponse("仅允许本机访问管理后台（可通过环境变量 ADMIN_LOCAL_ONLY=0 关闭）", status_code=403)

    user = auth_service.get_current_user(request)
    if not user:
        return None, RedirectResponse("/admin/login", status_code=303)

    if auth_service.must_change_password() and request.url.path not in {"/admin/password", "/admin/logout"}:
        return user, RedirectResponse("/admin/password", status_code=303)

    return user, None


def _render_admin_page(title: str, body_html: str, message: str = None) -> HTMLResponse:
    """Render admin page with layout.

    Args:
        title: Page title
        body_html: Page body HTML
        message: Optional message to display

    Returns:
        HTMLResponse
    """
    return admin_ui.render_page(title, body_html, message)


@admin_routes.get("/admin/login", response_class=HTMLResponse)
async def admin_login_get(request: Request):
    """Render admin login page."""
    if auth_service.is_local_only() and request.client.host not in {"127.0.0.1", "::1"}:
        return HTMLResponse("仅允许本机访问管理后台（可通过环境变量 ADMIN_LOCAL_ONLY=0 关闭）", status_code=403)

    user = auth_service.get_current_user(request)
    if user:
        return RedirectResponse("/admin", status_code=303)

    return admin_ui.login_page()


@admin_routes.post("/admin/login")
async def admin_login_post(request: Request):
    """Handle admin login form submission."""
    if auth_service.is_local_only() and request.client.host not in {"127.0.0.1", "::1"}:
        return HTMLResponse("仅允许本机访问管理后台（可通过环境变量 ADMIN_LOCAL_ONLY=0 关闭）", status_code=403)

    form = await request.form()
    username = str(form.get("username") or "").strip()
    password = str(form.get("password") or "").strip()

    if not auth_service.verify_login(username, password):
        return admin_ui.login_page("账号或密码错误")

    token = auth_service.create_token(username)
    resp = RedirectResponse("/admin", status_code=303)
    resp.set_cookie("admin_session", token, httponly=True, samesite="lax")
    return resp


@admin_routes.post("/admin/logout")
async def admin_logout_post(request: Request):
    """Handle admin logout."""
    resp = RedirectResponse("/admin/login", status_code=303)
    resp.delete_cookie("admin_session")
    return resp


@admin_routes.get("/admin", response_class=HTMLResponse)
async def admin_index(request: Request):
    """Render admin dashboard."""
    user, redirect = _admin_guard(request)
    if redirect:
        return redirect

    allowed_ips, allowed_qq, _, _ = whitelist_repo.get_whitelists()
    members = member_service.get_all_members()
    member_count = len(members)
    renewals = renewal_service.get_all_requests()
    renewal_count = len(renewals)

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


@admin_routes.post("/admin/refresh_cache")
async def admin_refresh_cache(request: Request):
    """Clear all caches and force reload."""
    user, redirect = _admin_guard(request)
    if redirect:
        return redirect

    # Clear whitelist cache
    whitelist_repo.clear_cache()
    # Clear usage cache
    from data.usage_repository import get_usage_repository
    usage_repo = get_usage_repository()
    usage_repo.clear_cache()
    # Clear member service cache
    member_service.clear_cache()

    return JSONResponse({"code": 0, "msg": "缓存已清除，数据已重新加载"})


@admin_routes.get("/admin/password", response_class=HTMLResponse)
async def admin_password_get(request: Request):
    """Render password change page."""
    user = auth_service.get_current_user(request)
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


@admin_routes.post("/admin/password")
async def admin_password_post(request: Request):
    """Handle password change form submission."""
    user = auth_service.get_current_user(request)
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

    try:
        auth_service.change_password(user, old_password, new_password)
        return _render_admin_page(
            "修改管理员密码",
            '<div>已更新密码</div><div style="margin-top:10px;"><a href="/admin">返回首页</a></div>'
        )
    except ValueError as e:
        return _render_admin_page("修改管理员密码", f"<div>{str(e)}</div>")


@admin_routes.get("/admin/whitelist", response_class=HTMLResponse)
async def admin_whitelist_get(request: Request):
    """Render whitelist management page."""
    user, redirect = _admin_guard(request)
    if redirect:
        return redirect

    allowed_ips, allowed_qq, ip_info, qq_info = whitelist_repo.get_whitelists()

    # Build IP table rows
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

    # Group QQs by comment
    qq_groups = {}
    for qq in sorted(allowed_qq):
        comment = (qq_info.get(qq) or "").strip() or "未命名"
        qq_groups.setdefault(comment, []).append(qq)

    qq_rows = []
    for comment in sorted(qq_groups.keys()):
        qq_list = qq_groups[comment]
        # 跳转到会员列表页面，按用户名搜索
        manage_url = f"/admin/members?q={urllib.parse.quote(comment)}"

        # 限制QQ列表显示，避免过长
        qq_display = "#".join(qq_list)
        if len(qq_display) > 100:  # 超过100字符则截断
            qq_display = qq_display[:100] + f"... (共{len(qq_list)}个)"

        qq_rows.append(
            "<tr>"
            f"<td>{_h(comment)}</td>"
            f"<td>{len(qq_list)}</td>"
            f"<td style='max-width:300px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;' title='{_h('#'.join(qq_list))}'>{_h(qq_display)}</td>"
            "<td><div class='actions'>"
            f"<a class='btn btn-sm' href=\"{_h(manage_url)}\">查看会员</a>"
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


@admin_routes.post("/admin/whitelist/ip/delete")
async def admin_whitelist_ip_delete(request: Request):
    """Handle IP whitelist entry deletion."""
    user, redirect = _admin_guard(request)
    if redirect:
        return redirect

    form = await request.form()
    ip = str(form.get("ip") or "").strip()

    if ip:
        member_key = f"ip:{ip}"
        # 1. 删除白名单
        _, ip_info = whitelist_repo.load_ip_whitelist()
        if ip in ip_info:
            del ip_info[ip]
            whitelist_repo.save_ip_whitelist(ip_info)

        # 2. 同步删除对应的会员
        member_service.delete_member(member_key)

    return RedirectResponse("/admin/whitelist", status_code=303)


@admin_routes.post("/admin/whitelist/qq_group/delete")
async def admin_whitelist_qq_group_delete(request: Request):
    """Handle QQ whitelist group deletion."""
    user, redirect = _admin_guard(request)
    if redirect:
        return redirect

    form = await request.form()
    comment = str(form.get("comment") or "").strip()

    if comment:
        _, qq_info = whitelist_repo.load_qq_whitelist()
        if comment == "未命名":
            to_delete = [qq for qq, c in qq_info.items() if not (c or "").strip()]
        else:
            to_delete = [qq for qq, c in qq_info.items() if (c or "").strip() == comment]

        # 1. 删除QQ白名单
        for qq in to_delete:
            qq_info.pop(qq, None)

        whitelist_repo.save_qq_whitelist(qq_info)

        # 2. 同步删除对应的会员
        for qq in to_delete:
            member_key = f"qq:{qq}"
            member_service.delete_member(member_key)

    return RedirectResponse("/admin/whitelist", status_code=303)
