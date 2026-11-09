"""Member management routes.

Handles member CRUD operations, filtering, and batch operations.
"""
import urllib.parse
from datetime import datetime, timezone, timedelta
from pathlib import Path
from fastapi import APIRouter, Request, Form, Query
from fastapi.responses import HTMLResponse, RedirectResponse, JSONResponse
import config
from services.member_service import get_member_service
from services.auth_service import get_auth_service
from data.whitelist_repository import get_whitelist_repository
from admin_ui import AdminUI


# Create router
member_routes = APIRouter()

# Initialize services
auth_service = get_auth_service()
member_service = get_member_service()
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


@member_routes.get("/admin/members", response_class=HTMLResponse)
async def admin_members_get(
    request: Request,
    q: str = Query(default=""),
    tier: str = Query(default=""),
    expired: str = Query(default=""),
    msg: str = Query(default="")
):
    """Render members list page with filtering."""
    user, redirect = _admin_guard(request)
    if redirect:
        return redirect

    # Get filter parameters
    q = q.strip()
    tier_filter = tier.strip().lower()
    expired_filter = expired.strip().lower()

    # Get members
    members = member_service.get_all_members()

    # Filter members
    filtered = []
    now = datetime.now(timezone.utc)

    for key, member in members.items():
        # Apply search filter
        if q:
            q_lower = q.lower()
            name = member.get("name", "").lower()
            if q_lower not in name and q_lower not in key.lower():
                continue

        # Apply tier filter
        if tier_filter:
            member_tier = (member.get("tier") or "").strip().lower()
            if member_tier != tier_filter:
                continue

        # Apply expired filter (only for month tier)
        if expired_filter == "yes":
            if member.get("tier") != "month":
                continue
            expires_at = member_service._parse_iso_datetime(member.get("expires_at"))
            if not expires_at or expires_at > now:
                continue
        elif expired_filter == "no":
            if member.get("tier") == "month":
                expires_at = member_service._parse_iso_datetime(member.get("expires_at"))
                if expires_at and expires_at <= now:
                    continue

        filtered.append((key, member))

    # Build table rows and calculate today's total statistics
    # Group members by name, but keep "未命名" separate
    grouped_members = {}
    unnammed_members = []

    for key, member in filtered:
        # Try to get name from member data first
        name = member.get("name", "")

        # If name is empty, try to get from whitelist
        if not name:
            if key.startswith("ip:"):
                ip_value = key[3:]
                _, ip_info = whitelist_repo.load_ip_whitelist()
                name = ip_info.get(ip_value, "")
            elif key.startswith("qq:"):
                qq_value = key[3:]
                _, qq_info = whitelist_repo.load_qq_whitelist()
                name = qq_info.get(qq_value, "")

        # Use "未命名" if still empty
        if not name:
            name = "未命名"

        if name == "未命名":
            unnammed_members.append((key, member))
        else:
            if name not in grouped_members:
                grouped_members[name] = []
            grouped_members[name].append((key, member))

    rows = []
    today_total_count = 0
    today_total_error = 0

    # Process named members (grouped)
    for name, member_list in grouped_members.items():
        # Use the first member's info for display
        first_key, first_member = member_list[0]
        member_tier = first_member.get("tier", "normal")
        enabled = first_member.get("enabled", True)

        # Aggregate usage statistics for all members with this name
        group_total_count = 0
        group_error_count = 0
        for key, member in member_list:
            usage_counter = member_service._usage_repo.get_counter(key)
            group_total_count += usage_counter.get("total_count", 0)
            group_error_count += usage_counter.get("error_count", 0)

        # Accumulate today's total statistics
        today_total_count += group_total_count
        today_total_error += group_error_count

        # Group all keys by type
        ip_keys = [k[3:] for k, m in member_list if k.startswith("ip:")]
        qq_keys = [k[3:] for k, m in member_list if k.startswith("qq:")]

        # Determine kind label and values display
        if ip_keys and qq_keys:
            kind_label = "IP+QQ"
            ip_display = '#'.join(ip_keys[:2])
            qq_display = '#'.join(qq_keys[:2])
            kind_values_display = f"IP:{_h(ip_display)}{'...' if len(ip_keys) > 2 else ''} QQ:{_h(qq_display)}{'...' if len(qq_keys) > 2 else ''}"
            kind_count = f"{len(ip_keys)}+{len(qq_keys)}"
        elif ip_keys:
            kind_label = "IP"
            kind_values_display = _h('#'.join(ip_keys[:3]))
            if len(ip_keys) > 3:
                kind_values_display += f"... (+{len(ip_keys)-3})"
            kind_count = str(len(ip_keys))
        elif qq_keys:
            kind_label = "QQ"
            kind_values_display = _h('#'.join(qq_keys[:3]))
            if len(qq_keys) > 3:
                kind_values_display += f"... (+{len(qq_keys)-3})"
            kind_count = str(len(qq_keys))
        else:
            kind_label = "未知"
            kind_values_display = ""
            kind_count = "0"

        # Tier badge
        tier_badge_class = {
            "permanent": "permanent",
            "month": "month",
            "normal": "normal",
        }.get(member_tier, "normal")
        tier_label = member_service.get_tier_label(member_tier)

        # Status badge
        if not enabled:
            status_badge = '<span class="status-badge disabled">禁用</span>'
        else:
            status_badge = '<span class="status-badge enabled">启用</span>'

        # Expiry status - use first member for display
        first_member["_key"] = first_key
        expiry_text, expiry_class = member_service.get_expiry_status(first_member)
        expiry_html = f'<span class="expiry-{expiry_class}">{_h(expiry_text)}</span>'

        # Edit URL - edit grouped user (show all IPs/QQs in the form)
        edit_buttons = ""
        if ip_keys:
            edit_buttons += f"<a class='btn btn-sm' href='/admin/members/form?name={urllib.parse.quote(name)}&kind=ip'>编辑IP</a>"
        if qq_keys:
            edit_buttons += f"<a class='btn btn-sm' href='/admin/members/form?name={urllib.parse.quote(name)}&kind=qq'>编辑QQ</a>"
        if not edit_buttons:
            edit_buttons = f"<a class='btn btn-sm' href='/admin/members/form?key={urllib.parse.quote(first_key)}'>编辑</a>"

        # Collect all keys for batch operations
        all_keys = [k for k, m in member_list]
        all_keys_json = str(all_keys).replace("'", "\"")

        rows.append(
            "<tr>"
            f"<td style='text-align:center;'><input type='checkbox' class='row-checkbox' data-keys='{all_keys_json}' /></td>"
            f"<td style='white-space:nowrap;'>{kind_label}</td>"
            f"<td style='font-family:monospace;max-width:120px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;' title='{_h(kind_values_display)}'>{kind_values_display}</td>"
            f"<td style='max-width:120px;overflow:hidden;text-overflow:ellipsis;'>{_h(name)}</td>"
            f"<td><span class='status-badge {tier_badge_class}' style='white-space:nowrap;'>{tier_label}</span></td>"
            f"<td style='white-space:nowrap;'>{status_badge}</td>"
            f"<td style='white-space:nowrap;font-size:0.8125rem;'>{expiry_html}</td>"
            f"<td style='text-align:right;white-space:nowrap;'><span class='muted'>今日</span> <strong>{group_total_count}</strong></td>"
            f"<td style='text-align:right;white-space:nowrap;'><span class='muted'>今日错</span> <strong>{group_error_count}</strong></td>"
            f"<td style='white-space:nowrap;'><div class='actions' style='gap:6px;'>"
            f"{edit_buttons}"
            f"<form class='inline' method='post' action='/admin/members/delete'>"
            f"<input type='hidden' name='name' value='{_h(name)}' />"
            f"<button class='btn-danger btn-sm' type='submit'>删除</button>"
            f"</form></div></td>"
            "</tr>"
        )

    # Process unnamed members (individual rows)
    for key, member in unnammed_members:
        member_tier = member.get("tier", "normal")
        enabled = member.get("enabled", True)

        # Get usage statistics
        usage_counter = member_service._usage_repo.get_counter(key)
        total_count = usage_counter.get("total_count", 0)
        error_count = usage_counter.get("error_count", 0)
        # Accumulate today's total statistics
        today_total_count += total_count
        today_total_error += error_count

        # Parse key
        if key.startswith("ip:"):
            kind_label = "IP"
            kind_value = key[3:]
        elif key.startswith("qq:"):
            kind_label = "QQ"
            kind_value = key[3:]
        else:
            kind_label = "未知"
            kind_value = key

        # Tier badge
        tier_badge_class = {
            "permanent": "permanent",
            "month": "month",
            "normal": "normal",
        }.get(member_tier, "normal")
        tier_label = member_service.get_tier_label(member_tier)

        # Status badge
        if not enabled:
            status_badge = '<span class="status-badge disabled">禁用</span>'
        else:
            status_badge = '<span class="status-badge enabled">启用</span>'

        # Expiry status
        member["_key"] = key
        expiry_text, expiry_class = member_service.get_expiry_status(member)
        expiry_html = f'<span class="expiry-{expiry_class}">{_h(expiry_text)}</span>'

        # Edit URL
        edit_url = f"/admin/members/form?key={urllib.parse.quote(key)}"

        # Single key for batch operations
        single_keys_json = str([key]).replace("'", "\"")

        rows.append(
            "<tr>"
            f"<td style='text-align:center;'><input type='checkbox' class='row-checkbox' data-keys='{single_keys_json}' /></td>"
            f"<td style='white-space:nowrap;'>{kind_label}</td>"
            f"<td style='font-family:monospace;max-width:120px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;' title='{_h(kind_value)}'>{_h(kind_value)}</td>"
            f"<td style='max-width:120px;overflow:hidden;text-overflow:ellipsis;color:var(--text-secondary);'>未命名</td>"
            f"<td><span class='status-badge {tier_badge_class}' style='white-space:nowrap;'>{tier_label}</span></td>"
            f"<td style='white-space:nowrap;'>{status_badge}</td>"
            f"<td style='white-space:nowrap;font-size:0.8125rem;'>{expiry_html}</td>"
            f"<td style='text-align:right;white-space:nowrap;'><span class='muted'>今日</span> <strong>{total_count}</strong></td>"
            f"<td style='text-align:right;white-space:nowrap;'><span class='muted'>今日错</span> <strong>{error_count}</strong></td>"
            f"<td style='white-space:nowrap;'><div class='actions' style='gap:6px;'>"
            f"<a class='btn btn-sm' href='{_h(edit_url)}'>编辑</a>"
            f"<form class='inline' method='post' action='/admin/members/delete'>"
            f"<input type='hidden' name='key' value='{_h(key)}' />"
            f"<button class='btn-danger btn-sm' type='submit'>删除</button>"
            f"</form></div></td>"
            "</tr>"
        )

    # Build filter links
    filter_links = []
    if q or tier or expired:
        filter_links.append(f"<a href='/admin/members' class='btn btn-sm'>清除筛选</a>")

    current_filter = []
    if q:
        current_filter.append(f"搜索: {_h(q)}")
    if tier_filter:
        tier_labels = {"permanent": "永久", "month": "月卡", "normal": "普通"}
        current_filter.append(f"等级: {tier_labels.get(tier_filter, tier_filter)}")
    if expired_filter == "yes":
        current_filter.append("已到期")
    elif expired_filter == "no":
        current_filter.append("未到期")

    today_statistics = (Path(__file__).resolve().parents[1] / 'templates/recognition_today.html').read_text(encoding='utf-8')

    body = f"""
    {today_statistics}
    <div class="row" style="margin-bottom:16px;">
      <div class="card" style="flex:1;">
        <h2>会员用量（当前筛选）</h2>
        <p class="muted">用量用于会员额度管理，按 UTC 日期重置；旧版报错来自旧上报接口。实际验证结果以上方全局统计为准。</p>
        <div style="display:flex;gap:24px;margin-top:10px;flex-wrap:wrap;">
          <div>
            <div class="muted">用户数</div>
            <div style="font-size:1.5rem;font-weight:bold;color:#22D3EE;">{len(grouped_members) + len(unnammed_members)}</div>
          </div>
          <div>
            <div class="muted">今日用量（UTC）</div>
            <div style="font-size:1.5rem;font-weight:bold;color:#4CAF50;">{today_total_count}</div>
          </div>
          <div>
            <div class="muted">旧版报错（UTC）</div>
            <div style="font-size:1.5rem;font-weight:bold;color:#f44336;">{today_total_error}</div>
          </div>
        </div>
      </div>
    </div>

    <div class="card">
      <h2>筛选</h2>
      <form method="get" style="display:flex;gap:10px;flex-wrap:wrap;align-items:flex-end;">
        <div style="flex:1;">
          <label>搜索</label>
          <input name="q" value="{_h(q)}" placeholder="用户名或IP/QQ" />
        </div>
        <div style="flex:0 0 120px;">
          <label>等级</label>
          <select name="tier">
            <option value="">全部</option>
            <option value="permanent" {'selected' if tier_filter=='permanent' else ''}>永久会员</option>
            <option value="month" {'selected' if tier_filter=='month' else ''}>月卡会员</option>
            <option value="normal" {'selected' if tier_filter=='normal' else ''}>普通用户</option>
          </select>
        </div>
        <div style="flex:0 0 120px;">
          <label>到期</label>
          <select name="expired">
            <option value="">全部</option>
            <option value="yes" {'selected' if expired_filter=='yes' else ''}>已到期</option>
            <option value="no" {'selected' if expired_filter=='no' else ''}>未到期</option>
          </select>
        </div>
        <div>
          <button type="submit">筛选</button>
        </div>
      </form>
      {"".join(filter_links) if filter_links else ""}
      {f'<div class="muted" style="margin-top:8px;">当前筛选：{" | ".join(current_filter)}</div>' if current_filter else ''}
    </div>

    <div class="card" style="margin-top:16px;">
      <div style="display:flex;justify-content:space-between;align-items:center;margin-bottom:12px;">
        <h2>用户列表 ({len(grouped_members)} + {len(unnammed_members)} 未命名)</h2>
        <div class="actions">
          <button type="button" class="btn btn-sm" onclick="refreshCache()" title="重新加载白名单和会员数据">🔄 刷新缓存</button>
          {f'<a class="btn btn-sm" href="/admin/members/form?kind=qq&comment={urllib.parse.quote(q)}">添加同用户QQ</a>' if q else ''}
          <a class="btn btn-sm" href="/admin/members/form">新增会员</a>
        </div>
      </div>

      <div class="batch-actions">
        <div style="display:flex;gap:10px;align-items:center;flex-wrap:wrap;">
          <span>已选：<span id="selected-count">0</span></span>
          <button type="button" class="btn btn-sm" onclick="batchEnable()">批量启用</button>
          <button type="button" class="btn btn-sm" onclick="batchDisable()">批量禁用</button>
          <button type="button" class="btn btn-sm" onclick="batchSetPermanent()">设为永久</button>
          <button type="button" class="btn btn-sm" onclick="batchExtend()">延期30天</button>
          <button type="button" class="btn-danger btn-sm" onclick="batchDelete()">批量删除</button>
        </div>
      </div>

      <div class="table-wrapper" style="margin-top:12px;overflow-x:auto;">
        <table style="min-width:900px;">
          <thead>
            <tr>
              <th style="width:50px;text-align:center;"><input type="checkbox" onclick="toggleAllCheckboxes(this)" /></th>
              <th style="width:60px;">类型</th>
              <th style="width:120px;">IP/QQ</th>
              <th style="width:120px;">用户名</th>
              <th style="width:90px;">等级</th>
              <th style="width:60px;">状态</th>
              <th style="width:130px;">到期时间</th>
              <th style="width:90px;text-align:right;">今日用量（UTC）</th>
              <th style="width:90px;text-align:right;">旧版报错（UTC）</th>
              <th style="width:110px;">操作</th>
            </tr>
          </thead>
          <tbody>
            {"".join(rows) or '<tr><td colspan="10" class="muted">暂无用户</td></tr>'}
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
      let count = 0;
      checked.forEach(box => {{
        const keys = JSON.parse(box.dataset.keys || '[]');
        count += keys.length;
      }});
      document.getElementById('selected-count').textContent = count;
    }}

    function getCheckedKeys() {{
      const checked = document.querySelectorAll('.row-checkbox:checked');
      const keys = [];
      checked.forEach(box => {{
        const rowKeys = JSON.parse(box.dataset.keys || '[]');
        keys.push(...rowKeys);
      }});
      return keys;
    }}
    function batchOperation(action) {{
      const keys = getCheckedKeys();
      if (keys.length === 0) {{
        alert('请先选择会员');
        return;
      }}
      if (!confirm(`确定要对 ${{keys.length}} 个会员执行此操作吗？`)) {{
        return;
      }}

      fetch('/admin/members/batch', {{
        method: 'POST',
        headers: {{'Content-Type': 'application/json'}},
        body: JSON.stringify({{action, keys}})
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

    function batchEnable() {{ batchOperation('enable'); }}
    function batchDisable() {{ batchOperation('disable'); }}
    function batchSetPermanent() {{ batchOperation('set_permanent'); }}
    function batchExtend() {{ batchOperation('extend_30'); }}
    function batchDelete() {{ batchOperation('delete'); }}

    async function refreshCache() {{
      try {{
        const response = await fetch('/admin/refresh_cache', {{
          method: 'POST',
          headers: {{'Content-Type': 'application/json'}}
        }});
        const data = await response.json();
        if (data.code === 0) {{
          alert('✓ ' + data.msg);
          location.reload();
        }} else {{
          alert('✗ ' + data.msg);
        }}
      }} catch (error) {{
        alert('✗ 刷新失败：' + error.message);
      }}
    }}

    document.querySelectorAll('.row-checkbox').forEach(box => {{
      box.addEventListener('change', updateSelectedCount);
    }});
    </script>
    """

    safe_msg = _h(msg) if msg else None
    return _render_admin_page("会员管理", body, safe_msg)


@member_routes.get("/admin/members/form", response_class=HTMLResponse)
async def admin_members_form_get(
    request: Request,
    key: str = Query(default=""),
    name: str = Query(default=""),
    kind: str = Query(default=""),
    values: str = Query(default=""),
    comment: str = Query(default="")
):
    """Render member form page."""
    user, redirect = _admin_guard(request)
    if redirect:
        return redirect

    # Support both 'key' (single member) and 'name' (grouped by user) for editing
    is_edit = False
    member = None
    renew_days = 30

    # If name parameter is provided, find all members with that name
    if name and not key:
        all_members = member_service.get_all_members()
        # First, try to find members with exact name match
        members_with_name = [(k, m) for k, m in all_members.items() if m.get("name") == name]

        # If not found, try to match by whitelist (for members with empty name)
        if not members_with_name:
            for k, m in all_members.items():
                # For members without name, check whitelist
                if not m.get("name"):
                    whitelist_name = ""
                    if k.startswith("ip:"):
                        ip_value = k[3:]
                        _, ip_info = whitelist_repo.load_ip_whitelist()
                        whitelist_name = ip_info.get(ip_value, "")
                    elif k.startswith("qq:"):
                        qq_value = k[3:]
                        _, qq_info = whitelist_repo.load_qq_whitelist()
                        whitelist_name = qq_info.get(qq_value, "")

                    if whitelist_name == name:
                        members_with_name.append((k, m))

        if members_with_name:
            # Use first member's data for form defaults
            key, member = members_with_name[0]
            is_edit = True

            # Group values by type
            ip_keys = [k[3:] for k, m in members_with_name if k.startswith("ip:")]
            qq_keys = [k[3:] for k, m in members_with_name if k.startswith("qq:")]

            # Set kind and values for display
            kind = (kind or "").strip().lower()
            if kind == "ip" and ip_keys:
                values = "#".join(ip_keys)
            elif kind == "qq" and qq_keys:
                values = "#".join(qq_keys)
            elif qq_keys and not ip_keys:
                kind = "qq"
                values = "#".join(qq_keys)
            elif ip_keys and not qq_keys:
                kind = "ip"
                values = "#".join(ip_keys)
            elif qq_keys and ip_keys:
                kind = "qq"
                values = "#".join(qq_keys)
            else:
                kind = ""
                values = ""
        else:
            # Name not found, treat as new
            is_edit = False
            kind = ""
            values = ""

    elif key:
        # Old-style single member editing
        is_edit = True
        member = member_service.get_member(key)
        if not member:
            return _render_admin_page("编辑会员", "<div>会员不存在</div>")

        # Parse key
        if key.startswith("ip:"):
            kind = "ip"
            values = key[3:]
        elif key.startswith("qq:"):
            kind = "qq"
            values = key[3:]
        else:
            kind = ""
            values = ""

    if is_edit and member:
        name = (member.get("name") or name or "").strip()
        tier = member.get("tier", "normal")
        enabled = member.get("enabled", True)
        expires_at = member.get("expires_at", "")
        daily_limit = member.get("daily_limit", config.settings.normal_daily_limit)

        # Calculate remaining days for month tier
        if expires_at and tier == "month":
            try:
                from datetime import datetime, timezone
                exp = member_service._parse_iso_datetime(expires_at)
                if exp and exp > datetime.now(timezone.utc):
                    days_left = (exp - datetime.now(timezone.utc)).days
                    renew_days = max(1, days_left)
            except:
                pass
        else:
            expires_at = ""
    else:
        # Creating new member
        name = ""
        tier = "normal"
        enabled = True
        expires_at = ""
        daily_limit = config.settings.normal_daily_limit

    kind_options = ""
    for k in ("ip", "qq"):
        selected = 'selected' if kind == k else ''
        kind_options += f'<option value="{k}" {selected}>{k.upper()}</option>'

    tier_options = ""
    for t in ("normal", "month", "permanent"):
        selected = 'selected' if tier == t else ''
        label = {"normal": "普通用户", "month": "月卡会员", "permanent": "永久会员"}[t]
        tier_options += f'<option value="{t}" {selected}>{label}</option>'

    enabled_checked = 'checked' if enabled else ''

    # Pre-fill whitelist info if creating from whitelist
    if not is_edit and kind and values:
        member_key = f"{kind}:{values}"
        existing_member = member_service.get_member(member_key)
        if kind == "ip":
            _, ip_info = whitelist_repo.load_ip_whitelist()
            # 如果会员已存在且有名称，优先使用会员的名称；否则使用白名单名称
            if existing_member and existing_member.get("name"):
                name = existing_member.get("name", "")
            else:
                name = ip_info.get(values, "")
        elif kind == "qq":
            _, qq_info = whitelist_repo.load_qq_whitelist()
            # 如果会员已存在且有名称，优先使用会员的名称；否则使用 comment 或白名单名称
            if existing_member and existing_member.get("name"):
                name = existing_member.get("name", "")
            elif comment:
                name = comment
            else:
                name = qq_info.get(values, "")

    body = f"""
    <form method="post" action="/admin/members/form" id="memberForm">
      <div class="form-group">
        <h3>基本信息</h3>
        <div class="row">
          <div>
            <label>类型</label>
            <select name="kind" id="kindSelect" onchange="updateKindHint()">
              {kind_options}
            </select>
          </div>
          <div>
            <label>{kind.upper() if kind else 'IP/QQ'}</label>
            <input name="values" value="{_h(values)}" placeholder="{_h('多个IP地址用#分隔' if kind=='ip' else '多个QQ号用#分隔' if kind=='qq' else 'IP地址或QQ号(多个用#分隔)')}" required />
          </div>
        </div>
        <div style="margin-top:10px;">
          <label>名称（必填）</label>
          <input name="name" value="{_h(name)}" placeholder="用户名或备注" required />
          <div class="muted" style="margin-top:4px;">相同名称的会员将合并显示和编辑，等级和天数对所有IP/QQ生效</div>
        </div>
      </div>

      <div class="form-group">
        <h3>等级设置</h3>
        <label>会员等级</label>
        <select name="tier" id="tierSelect" onchange="updateTierFields()">
          {tier_options}
        </select>
        <div class="muted" style="margin-top:6px;">
          永久会员：无限制<br>
          月卡会员：设置续费天数，到期后无法使用<br>
          普通用户：每日调用次数有限制
        </div>
      </div>

      <div class="form-group" id="expiryField" style="display:none;">
        <h3>到期设置</h3>
        <label>续费天数</label>
        <input type="number" name="renew_days" id="renewDaysInput" value="{renew_days}" min="0" style="width:150px;" placeholder="输入天数" />
        <div class="muted" style="margin-top:6px;">
          设置会员的续费天数，从今天开始计算。0 表示立即到期
        </div>
      </div>

      <div class="form-group" id="limitField" style="display:none;">
        <h3>调用限制</h3>
        <label>每日调用次数限制</label>
        <input type="number" name="daily_limit" value="{daily_limit}" min="0" />
        <div class="muted" style="margin-top:6px;">
          0 表示无限制
        </div>
      </div>

      <div class="form-group">
        <h3>状态</h3>
        <div class="checkbox-wrapper">
          <input type="checkbox" name="enabled" id="enabledCheckbox" {enabled_checked} />
          <label for="enabledCheckbox" style="display:inline;">启用此会员</label>
        </div>
      </div>

      <input type="hidden" name="key" value="{_h(key)}" />

      <button type="submit" style="margin-top:12px;">保存</button>
      <button type="button" onclick="history.back()" style="margin-top:12px;">取消</button>
    </form>

    <script>
    // Set expiry date for JavaScript (only if editing month tier)
    window.expiryDate = "{_h(expires_at)}";
    </script>

    <script>
    function updateKindHint() {{
      const kind = document.getElementById('kindSelect').value;
      const input = document.querySelector('input[name="values"]');
      input.placeholder = kind === 'ip' ? 'IP地址' : 'QQ号';
    }}

    function updateTierFields() {{
      const tier = document.getElementById('tierSelect').value;
      document.getElementById('expiryField').style.display = tier === 'month' ? 'block' : 'none';
      document.getElementById('limitField').style.display = tier === 'normal' ? 'block' : 'none';

      // For month tier, calculate remaining days if editing
      if (tier === 'month' && window.expiryDate) {{
        const now = new Date();
        const expiry = new Date(window.expiryDate);
        if (expiry > now) {{
          const diffTime = expiry - now;
          const diffDays = Math.ceil(diffTime / (1000 * 60 * 60 * 24));
          if (diffDays > 0) {{
            document.getElementById('renewDaysInput').value = diffDays;
          }}
        }}
      }}
    }}

    // Initialize
    updateTierFields();
    </script>
    """

    title = "编辑会员" if is_edit else "新增会员"
    return _render_admin_page(title, body)


@member_routes.post("/admin/members/form")
async def admin_members_form_post(request: Request):
    """Handle member form submission."""
    user, redirect = _admin_guard(request)
    if redirect:
        return redirect

    form = await request.form()
    key = str(form.get("key") or "").strip()
    kind = str(form.get("kind") or "").strip().lower()
    values_str = str(form.get("values") or "").strip()
    name = str(form.get("name") or "").strip()
    tier = str(form.get("tier") or "").strip().lower()
    renew_days_str = str(form.get("renew_days") or "30").strip()
    daily_limit_str = str(form.get("daily_limit") or "0").strip()
    enabled = form.get("enabled") is not None

    # Validate
    if not name:
        return _render_admin_page("保存会员", "<div>用户名不能为空</div>")

    if kind not in ("ip", "qq"):
        return _render_admin_page("保存会员", "<div>类型无效</div>")

    if not values_str:
        return _render_admin_page("保存会员", "<div>IP/QQ不能为空</div>")

    if tier not in ("normal", "month", "permanent"):
        return _render_admin_page("保存会员", "<div>等级无效</div>")

    # Parse multiple values separated by #
    values_list = [v.strip() for v in values_str.split('#') if v.strip()]

    # Calculate expiry date from renew days for month tier
    expires_at = ""
    if tier == "month":
        try:
            renew_days = int(renew_days_str)
            if renew_days > 0:
                expiry_date = datetime.now(timezone.utc) + timedelta(days=renew_days)
                expires_at = expiry_date.astimezone(timezone.utc).isoformat().replace("+00:00", "Z")
        except:
            pass
    # For other tiers, ensure expires_at is empty
    elif tier != "month":
        expires_at = ""

    # Parse daily limit
    try:
        daily_limit = int(daily_limit_str) if tier == "normal" else None
    except:
        daily_limit = None

    _, ip_info = whitelist_repo.load_ip_whitelist()
    _, qq_info = whitelist_repo.load_qq_whitelist()

    all_members = member_service.get_all_members()
    existing_member = member_service.get_member(key) if key else None
    group_name = ((existing_member or {}).get("name") or name or "").strip()

    def _belongs_to_group(member_key: str, member: dict, target_name: str) -> bool:
        if not target_name:
            return False
        stored_name = (member.get("name") or "").strip()
        if stored_name == target_name:
            return True
        if stored_name:
            return False
        if member_key.startswith("ip:"):
            return (ip_info.get(member_key[3:]) or "").strip() == target_name
        if member_key.startswith("qq:"):
            return (qq_info.get(member_key[3:]) or "").strip() == target_name
        return False

    existing_members_in_group = {
        k: m for k, m in all_members.items()
        if _belongs_to_group(k, m, group_name)
    } if group_name else {}

    existing_values_in_kind = {
        k.split(":", 1)[1] for k in existing_members_in_group.keys()
        if k.startswith(f"{kind}:") and ":" in k
    }

    values_set = set(values_list)
    removed_values = sorted(v for v in existing_values_in_kind if v not in values_set)

    deleted_count = 0
    for value in removed_values:
        member_key = f"{kind}:{value}"
        if member_service.delete_member(member_key):
            deleted_count += 1

    # Create or update members for each submitted value
    created_count = 0
    updated_count = 0

    for value in values_list:
        member_key = f"{kind}:{value}"
        existing_value_member = member_service.get_member(member_key)

        member_data = {
            "name": name,
            "tier": tier,
            "enabled": enabled,
            "expires_at": expires_at,
        }

        if daily_limit is not None and tier == "normal":
            member_data["daily_limit"] = daily_limit

        if existing_value_member:
            member_service.update_member(member_key, **member_data)
            updated_count += 1
        else:
            member_service.create_member(member_key, **member_data)
            created_count += 1

    # Apply tier/enabled/expires/name to other keys in the same group (e.g. QQ + IP)
    refreshed_members = member_service.get_all_members()
    group_keys_after = [
        k for k, m in refreshed_members.items()
        if _belongs_to_group(k, m, group_name) and k != "" and k != key
    ] if group_name else []
    for other_key in group_keys_after:
        if other_key.startswith(f"{kind}:") and other_key.split(":", 1)[1] in values_set:
            continue
        member_service.update_member(
            other_key,
            name=name,
            tier=tier,
            enabled=enabled,
            expires_at=expires_at,
            daily_limit=daily_limit if tier == "normal" else None,
        )

    # Sync with whitelist (update both kinds in the group; delete removed values)
    refreshed_members = member_service.get_all_members()
    final_group_keys = [
        k for k, m in refreshed_members.items()
        if (m.get("name") or "").strip() == name
    ]
    final_ips = [k[3:] for k in final_group_keys if k.startswith("ip:")]
    final_qqs = [k[3:] for k in final_group_keys if k.startswith("qq:")]

    for ip in final_ips:
        ip_info[ip] = name
    for qq in final_qqs:
        qq_info[qq] = name

    if kind == "ip":
        for ip in removed_values:
            ip_info.pop(ip, None)
    elif kind == "qq":
        for qq in removed_values:
            qq_info.pop(qq, None)

    whitelist_repo.save_ip_whitelist(ip_info)
    whitelist_repo.save_qq_whitelist(qq_info)

    # Clear cache to ensure list displays updated data
    member_service.clear_cache()
    whitelist_repo.clear_cache()

    total_count = created_count + updated_count
    message = f"已保存（新增 {created_count}，更新 {updated_count}，删除 {deleted_count}）"
    return RedirectResponse(f"/admin/members?msg={urllib.parse.quote(message)}", status_code=303)


@member_routes.post("/admin/members/delete")
async def admin_members_delete(request: Request):
    """Handle member deletion."""
    user, redirect = _admin_guard(request)
    if redirect:
        return redirect

    form = await request.form()
    key = str(form.get("key") or "").strip()
    name = str(form.get("name") or "").strip()

    # Support both key (single member) and name (batch delete by user)
    if key:
        # Delete single member by key
        keys_to_delete = [key]
    elif name:
        # Delete all members with this name
        all_members = member_service.get_all_members()
        # For "未命名", also include members with empty name
        if name == "未命名":
            keys_to_delete = [k for k, m in all_members.items() if not m.get("name")]
        else:
            keys_to_delete = [k for k, m in all_members.items() if m.get("name") == name]
    else:
        return _render_admin_page("删除会员", '<div>参数错误</div>')

    if not keys_to_delete:
        return _render_admin_page("删除会员", '<div>未找到要删除的会员</div>')

    # Delete all members
    deleted_count = 0
    ip_to_delete = []
    qq_to_delete = []

    for key in keys_to_delete:
        if member_service.delete_member(key):
            deleted_count += 1
            if key.startswith("ip:"):
                ip_to_delete.append(key[3:])
            elif key.startswith("qq:"):
                qq_to_delete.append(key[3:])

    # Sync delete from whitelist
    if ip_to_delete:
        _, ip_info = whitelist_repo.load_ip_whitelist()
        for ip in ip_to_delete:
            ip_info.pop(ip, None)
        whitelist_repo.save_ip_whitelist(ip_info)

    if qq_to_delete:
        _, qq_info = whitelist_repo.load_qq_whitelist()
        for qq in qq_to_delete:
            qq_info.pop(qq, None)
        whitelist_repo.save_qq_whitelist(qq_info)

    return _render_admin_page("删除会员", f'<div>已删除 {deleted_count} 个会员记录</div><div style="margin-top:10px;"><a href="/admin/members">返回列表</a></div>')


@member_routes.post("/admin/members/batch")
async def admin_members_batch(request: Request):
    """Handle batch member operations."""
    user, redirect = _admin_guard(request)
    if redirect:
        return redirect

    try:
        payload = await request.json()
        action = payload.get("action", "")
        keys = payload.get("keys", [])

        if not keys:
            return JSONResponse({"success": False, "message": "未选择会员"})

        if action == "enable":
            count = member_service.batch_update_members(keys, enabled=True)
            # Clear cache
            member_service.clear_cache()
            return JSONResponse({"success": True, "message": f"已启用 {count} 个会员"})

        elif action == "disable":
            count = member_service.batch_update_members(keys, enabled=False)
            # Clear cache
            member_service.clear_cache()
            return JSONResponse({"success": True, "message": f"已禁用 {count} 个会员"})

        elif action == "set_permanent":
            count = member_service.batch_update_members(keys, tier="permanent")
            # Clear cache
            member_service.clear_cache()
            return JSONResponse({"success": True, "message": f"已将 {count} 个会员设为永久"})

        elif action == "extend_30":
            count = member_service.batch_update_members(keys, extend_days=30)
            # Clear cache
            member_service.clear_cache()
            return JSONResponse({"success": True, "message": f"已延长 {count} 个会员的到期时间"})

        elif action == "delete":
            # 删除会员
            count = member_service.batch_delete_members(keys)

            # 同步删除白名单中的对应条目
            ip_to_delete = []
            qq_to_delete = []

            for key in keys:
                if key.startswith("ip:"):
                    ip_to_delete.append(key[3:])
                elif key.startswith("qq:"):
                    qq_to_delete.append(key[3:])

            # 批量删除IP白名单
            if ip_to_delete:
                _, ip_info = whitelist_repo.load_ip_whitelist()
                for ip in ip_to_delete:
                    ip_info.pop(ip, None)
                whitelist_repo.save_ip_whitelist(ip_info)

            # 批量删除QQ白名单
            if qq_to_delete:
                _, qq_info = whitelist_repo.load_qq_whitelist()
                for qq in qq_to_delete:
                    qq_info.pop(qq, None)
                whitelist_repo.save_qq_whitelist(qq_info)

            # Clear cache
            member_service.clear_cache()
            whitelist_repo.clear_cache()

            return JSONResponse({"success": True, "message": f"已删除 {count} 个会员（含白名单同步删除）"})

        else:
            return JSONResponse({"success": False, "message": "未知操作"})

    except Exception as e:
        return JSONResponse({"success": False, "message": str(e)})
