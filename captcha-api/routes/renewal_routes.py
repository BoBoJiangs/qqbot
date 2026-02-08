"""Renewal request management routes.

Handles renewal request approval, rejection, and listing.
"""
import urllib.parse
from datetime import datetime, timezone, timedelta
from fastapi import APIRouter, Request, Query
from fastapi.responses import HTMLResponse, RedirectResponse, JSONResponse
from services.renewal_service import get_renewal_service
from services.member_service import get_member_service
from services.auth_service import get_auth_service
from admin_ui import AdminUI


# Create router
renewal_routes = APIRouter()

# Initialize services
auth_service = get_auth_service()
renewal_service = get_renewal_service()
member_service = get_member_service()
admin_ui = AdminUI()


def _h(value: str) -> str:
    """HTML escape helper."""
    import html
    return html.escape("" if value is None else str(value), quote=True)


def _admin_guard(request: Request):
    """Guard function for admin routes."""
    if auth_service.is_local_only() and request.client.host not in {"127.0.0.1", "::1"}:
        return None, HTMLResponse("仅允许本机访问管理后台", status_code=403)

    user = auth_service.get_current_user(request)
    if not user:
        return None, RedirectResponse("/admin/login", status_code=303)

    if auth_service.must_change_password() and request.url.path != "/admin/logout":
        return user, RedirectResponse("/admin/password", status_code=303)

    return user, None


def _render_admin_page(title: str, body_html: str, message: str = None) -> HTMLResponse:
    """Render admin page with layout."""
    return admin_ui.render_page(title, body_html, message)


@renewal_routes.get("/admin/renewals", response_class=HTMLResponse)
async def admin_renewals_get(
    request: Request,
    status_filter: str = Query(default="", alias="status")
):
    """Render renewal requests list page."""
    user, redirect = _admin_guard(request)
    if redirect:
        return redirect

    # Get all requests
    requests = renewal_service.get_all_requests()

    # Filter by status
    if status_filter:
        requests = [r for r in requests if r.get("status") == status_filter]

    # Sort by created_at (newest first)
    requests.sort(key=lambda r: r.get("created_at", ""), reverse=True)

    # Build table rows
    rows = []
    now = datetime.now(timezone.utc)

    for req in requests:
        req_id = req.get("id", "")
        member_key = req.get("member_key", "")
        member_name = _h(req.get("member_name", ""))
        requested_days = req.get("requested_days", 30)
        note = _h(req.get("note", ""))
        status = req.get("status", "pending")
        created_at = req.get("created_at", "")

        # Parse created_at
        created_dt = renewal_service._parse_iso_datetime(created_at)
        if created_dt:
            tz_bj = timezone(timedelta(hours=8))
            created_str = created_dt.astimezone(tz_bj).strftime("%Y-%m-%d %H:%M:%S")
        else:
            created_str = ""

        # Status badge
        status_class, status_label = renewal_service.get_status_badge(status)

        # Member info
        if member_key.startswith("qq:"):
            member_display = f"QQ: {_h(member_key[3:])}"
        elif member_key.startswith("ip:"):
            member_display = f"IP: {_h(member_key[3:])}"
        else:
            member_display = _h(member_key)

        # Parse key
        if member_key.startswith("ip:"):
            kind_label = "IP"
            kind_value = member_key[3:]
        elif member_key.startswith("qq:"):
            kind_label = "QQ"
            kind_value = member_key[3:]
        else:
            kind_label = "未知"
            kind_value = member_key

        rows.append(
            "<tr>"
            f"<td>{member_display}</td>"
            f"<td>{_h(member_name)}</td>"
            f"<td>{requested_days}</td>"
            f"<td><span class='status-badge {status_class}'>{status_label}</span></td>"
            f"<td>{created_str}</td>"
            f"<td>"
            f"<input type='checkbox' class='row-checkbox' data-id='{_h(req_id)}' data-status='{_h(status)}' />"
            f"</td>"
            f"<td><div class='actions'>"
        )

        # Add action buttons based on status
        if status == "pending":
            rows.append(
                f"<form class='inline' method='post' action='/admin/renewals/{_h(req_id)}/approve'>"
                f"<button class='btn btn-sm' type='submit'>批准</button></form>"
                f"<form class='inline' method='post' action='/admin/renewals/{_h(req_id)}/ignore'>"
                f"<button class='btn btn-sm' type='submit'>忽略</button></form>"
            )
        else:
            rows.append(
                f"<form class='inline' method='post' action='/admin/renewals/{_h(req_id)}/delete'>"
                f"<button class='btn-danger btn-sm' type='submit'>删除</button></form>"
            )

        rows.append(
            f"</div></td>"
            "</tr>"
        )

    # Calculate statistics
    all_requests = renewal_service.get_all_requests()
    pending_count = sum(1 for r in all_requests if r.get("status") == "pending")
    approved_count = sum(1 for r in all_requests if r.get("status") == "approved")
    ignored_count = sum(1 for r in all_requests if r.get("status") == "ignored")

    body = f"""
    <div class="row">
      <div class="card">
        <h2>统计</h2>
        <div class="muted">
          待处理：{pending_count} | 已批准：{approved_count} | 已忽略：{ignored_count}
        </div>
      </div>
      <div class="card">
        <h2>筛选</h2>
        <div style="display:flex;gap:8px;flex-wrap:wrap;">
          <a class="btn btn-sm" href="/admin/renewals">全部</a>
          <a class="btn btn-sm" href="/admin/renewals?status=pending">待处理</a>
          <a class="btn btn-sm" href="/admin/renewals?status=approved">已批准</a>
          <a class="btn btn-sm" href="/admin/renewals?status=ignored">已忽略</a>
        </div>
      </div>
    </div>

    <div class="card" style="margin-top:16px;">
      <div style="display:flex;justify-content:space-between;align-items:center;margin-bottom:12px;">
        <h2>续费通知列表 ({len(requests)})</h2>
      </div>

      <div class="batch-actions">
        <div style="display:flex;gap:10px;align-items:center;flex-wrap:wrap;">
          <span>已选：<span id="selected-count">0</span></span>
          <button type="button" class="btn btn-sm" onclick="batchApprove()">批量批准</button>
          <button type="button" class="btn btn-sm" onclick="batchIgnore()">批量忽略</button>
          <button type="button" class="btn-danger btn-sm" onclick="batchDelete()">批量删除</button>
        </div>
      </div>

      <div class="table-wrapper" style="margin-top:12px;">
        <table>
          <thead>
            <tr>
              <th>会员</th>
              <th>名称</th>
              <th>申请天数</th>
              <th>状态</th>
              <th>提交时间</th>
              <th style="width:50px;"><input type="checkbox" onclick="toggleAllCheckboxes(this)" /></th>
              <th style="width:180px;">操作</th>
            </tr>
          </thead>
          <tbody>
            {"".join(rows) or '<tr><td colspan="7" class="muted">暂无续费通知</td></tr>'}
          </tbody>
        </table>
      </div>
    </div>

    <script>
    function toggleAllCheckboxes(cb) {{
      const checkboxes = document.querySelectorAll('.row-checkbox');
      checkboxes.forEach(box => box.checked = cb.checked);
      updateSelectedCount();
    }}

    function updateSelectedCount() {{
      const checked = document.querySelectorAll('.row-checkbox:checked');
      document.getElementById('selected-count').textContent = checked.length;
    }}

    function getCheckedIds() {{
      const checked = document.querySelectorAll('.row-checkbox:checked');
      return Array.from(checked).map(box => box.dataset.id);
    }}

    function batchOperation(action) {{
      const ids = getCheckedIds();
      if (ids.length === 0) {{
        alert('请先选择续费通知');
        return;
      }}
      if (!confirm(`确定要对 ${{ids.length}} 个续费通知执行此操作吗？`)) {{
        return;
      }}

      fetch('/admin/renewals/batch', {{
        method: 'POST',
        headers: {{'Content-Type': 'application/json'}},
        body: JSON.stringify({{action, ids}})
      }}).then(r => r.json()).then(data => {{
        if (data.success) {{
          alert(data.message);
          location.reload();
        }} else {{
          alert('操作失败：' + data.message);
        }}
      }}).catch(err => {{
        alert('操作失败：' + err);
      }});
    }}

    function batchApprove() {{ batchOperation('approve'); }}
    function batchIgnore() {{ batchOperation('ignore'); }}
    function batchDelete() {{ batchOperation('delete'); }}

    document.querySelectorAll('.row-checkbox').forEach(box => {{
      box.addEventListener('change', updateSelectedCount);
    }});
    </script>
    """

    return _render_admin_page("续费通知", body)


@renewal_routes.post("/admin/renewals/{request_id}/approve")
async def admin_renewal_approve(request: Request, request_id: str):
    """Approve a renewal request."""
    user, redirect = _admin_guard(request)
    if redirect:
        return redirect

    if renewal_service.approve_request(request_id):
        return RedirectResponse("/admin/renewals", status_code=303)
    else:
        return _render_admin_page("批准续费", '<div>续费通知不存在</div>')


@renewal_routes.post("/admin/renewals/{request_id}/ignore")
async def admin_renewal_ignore(request: Request, request_id: str):
    """Ignore a renewal request."""
    user, redirect = _admin_guard(request)
    if redirect:
        return redirect

    if renewal_service.ignore_request(request_id):
        return RedirectResponse("/admin/renewals", status_code=303)
    else:
        return _render_admin_page("忽略续费", '<div>续费通知不存在</div>')


@renewal_routes.post("/admin/renewals/{request_id}/delete")
async def admin_renewal_delete(request: Request, request_id: str):
    """Delete a renewal request."""
    user, redirect = _admin_guard(request)
    if redirect:
        return redirect

    if renewal_service.delete_request(request_id):
        return RedirectResponse("/admin/renewals", status_code=303)
    else:
        return _render_admin_page("删除续费", '<div>续费通知不存在</div>')


@renewal_routes.post("/admin/renewals/batch")
async def admin_renewals_batch(request: Request):
    """Handle batch renewal operations."""
    user, redirect = _admin_guard(request)
    if redirect:
        return redirect

    try:
        payload = await request.json()
        action = payload.get("action", "")
        ids = payload.get("ids", [])

        if not ids:
            return JSONResponse({"success": False, "message": "未选择续费通知"})

        if action == "approve":
            count = renewal_service.batch_approve_requests(ids)
            return JSONResponse({"success": True, "message": f"已批准 {count} 个续费通知"})

        elif action == "ignore":
            count = renewal_service.batch_ignore_requests(ids)
            return JSONResponse({"success": True, "message": f"已忽略 {count} 个续费通知"})

        elif action == "delete":
            count = renewal_service.batch_delete_requests(ids)
            return JSONResponse({"success": True, "message": f"已删除 {count} 个续费通知"})

        else:
            return JSONResponse({"success": False, "message": "未知操作"})

    except Exception as e:
        return JSONResponse({"success": False, "message": str(e)})
