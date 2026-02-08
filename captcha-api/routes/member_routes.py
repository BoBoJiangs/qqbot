"""Member management routes.

Handles member CRUD operations, filtering, and batch operations.
"""
import urllib.parse
from datetime import datetime, timezone, timedelta
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
    expired: str = Query(default="")
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

    # Build table rows
    rows = []
    for key, member in filtered:
        name = _h(member.get("name", ""))
        member_tier = member.get("tier", "normal")
        enabled = member.get("enabled", True)

        # Get usage statistics
        usage_counter = member_service._usage_repo.get_counter(key)
        total_count = usage_counter.get("total_count", 0)
        error_count = usage_counter.get("error_count", 0)
        accuracy = round((1 - error_count / total_count) * 100, 1) if total_count > 0 else 100.0

        # If name is empty, try to get from whitelist
        if not name:
            if key.startswith("ip:"):
                kind_value = key[3:]
                _, ip_info = whitelist_repo.load_ip_whitelist()
                name = _h(ip_info.get(kind_value, ""))
            elif key.startswith("qq:"):
                kind_value = key[3:]
                _, qq_info = whitelist_repo.load_qq_whitelist()
                # For QQ, need to find which comment group this QQ belongs to
                for qq_comment, qq_list in qq_info.items():
                    if kind_value in qq_list.split('#'):
                        name = _h(qq_comment)
                        break

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
        expiry_text, expiry_class = member_service.get_expiry_status(member)
        expiry_html = f'<span class="expiry-{expiry_class}">{_h(expiry_text)}</span>'

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

        # Edit URL
        edit_url = f"/admin/members/form?key={urllib.parse.quote(key)}"

        # Accuracy color
        accuracy_class = "expiry-ok" if accuracy >= 90 else "expiry-warning" if accuracy >= 70 else "expiry-expired"

        rows.append(
            "<tr>"
            f"<td style='text-align:center;'><input type='checkbox' class='row-checkbox' data-key='{_h(key)}' /></td>"
            f"<td style='white-space:nowrap;'>{kind_label}</td>"
            f"<td style='font-family:monospace;white-space:nowrap;'>{_h(kind_value)}</td>"
            f"<td style='max-width:180px;overflow:hidden;text-overflow:ellipsis;'>{_h(name)}</td>"
            f"<td><span class='status-badge {tier_badge_class}' style='white-space:nowrap;'>{tier_label}</span></td>"
            f"<td style='white-space:nowrap;'>{status_badge}</td>"
            f"<td style='white-space:nowrap;font-size:0.8125rem;'>{expiry_html}</td>"
            f"<td style='text-align:right;white-space:nowrap;'><span class='muted'>总计</span> <strong>{total_count}</strong></td>"
            f"<td style='text-align:right;white-space:nowrap;'><span class='muted'>错误</span> <strong>{error_count}</strong></td>"
            f"<td style='text-align:center;white-space:nowrap;'><span class='{accuracy_class}'><strong>{accuracy:.1f}%</strong></span></td>"
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

    body = f"""
    <div class="card">
      <h2>筛选</h2>
      <form method="get" style="display:flex;gap:10px;flex-wrap:wrap;align-items:flex-end;">
        <div style="flex:1;">
          <label>搜索</label>
          <input name="q" value="{_h(q)}" placeholder="名称或IP/QQ" />
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
        <h2>会员列表 ({len(filtered)})</h2>
        <div class="actions">
          <button type="button" class="btn btn-sm" onclick="refreshCache()" title="重新加载白名单和会员数据">🔄 刷新缓存</button>
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

      <div class="table-wrapper" style="margin-top:12px;">
        <table>
          <thead>
            <tr>
              <th style="width:50px;text-align:center;"><input type="checkbox" onclick="toggleAllCheckboxes(this)" /></th>
              <th style="width:70px;">类型</th>
              <th style="width:130px;">值</th>
              <th style="width:180px;">名称</th>
              <th style="width:100px;">等级</th>
              <th style="width:70px;">状态</th>
              <th style="width:140px;">到期时间</th>
              <th style="width:110px;text-align:right;">总次数</th>
              <th style="width:100px;text-align:right;">错误次数</th>
              <th style="width:90px;text-align:center;">准确率</th>
              <th style="width:130px;">操作</th>
            </tr>
          </thead>
          <tbody>
            {"".join(rows) or '<tr><td colspan="11" class="muted">暂无会员</td></tr>'}
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

    function getCheckedKeys() {{
      const checked = document.querySelectorAll('.row-checkbox:checked');
      return Array.from(checked).map(box => box.dataset.key);
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

    return _render_admin_page("会员管理", body)


@member_routes.get("/admin/members/form", response_class=HTMLResponse)
async def admin_members_form_get(
    request: Request,
    key: str = Query(default=""),
    kind: str = Query(default=""),
    values: str = Query(default=""),
    comment: str = Query(default="")
):
    """Render member form page."""
    user, redirect = _admin_guard(request)
    if redirect:
        return redirect

    # Determine if editing or creating
    is_edit = bool(key)
    member = None

    if is_edit:
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

        name = member.get("name", "")
        tier = member.get("tier", "normal")
        enabled = member.get("enabled", True)
        expires_at = member.get("expires_at", "")
        daily_limit = member.get("daily_limit", config.settings.normal_daily_limit)

        # Calculate remaining days for month tier
        renew_days = 30
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
        if kind == "ip":
            _, ip_info = whitelist_repo.load_ip_whitelist()
            name = ip_info.get(values, "")
        elif kind == "qq":
            _, qq_info = whitelist_repo.load_qq_whitelist()
            if comment:
                # Find QQs with this comment
                for qq, c in qq_info.items():
                    if c == comment:
                        values = qq
                        break
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
            <input name="values" value="{_h(values)}" placeholder="{_h('IP地址' if kind=='ip' else 'QQ号' if kind=='qq' else 'IP地址或QQ号')}" required />
          </div>
        </div>
        <div style="margin-top:10px;">
          <label>名称（可选）</label>
          <input name="name" value="{_h(name)}" placeholder="用户名或备注" />
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
    values = str(form.get("values") or "").strip()
    name = str(form.get("name") or "").strip()
    tier = str(form.get("tier") or "").strip().lower()
    renew_days_str = str(form.get("renew_days") or "30").strip()
    daily_limit_str = str(form.get("daily_limit") or "0").strip()
    enabled = form.get("enabled") is not None

    # Validate
    if kind not in ("ip", "qq"):
        return _render_admin_page("保存会员", "<div>类型无效</div>")

    if not values:
        return _render_admin_page("保存会员", "<div>IP/QQ不能为空</div>")

    if tier not in ("normal", "month", "permanent"):
        return _render_admin_page("保存会员", "<div>等级无效</div>")

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

    # Build member key
    new_key = f"{kind}:{values}"

    # Check if editing different key
    if key and key != new_key:
        # Delete old member
        member_service.delete_member(key)
        key = new_key

    if key:
        # Update existing member
        member_service.update_member(
            new_key,
            name=name,
            tier=tier,
            enabled=enabled,
            expires_at=expires_at if expires_at else None,
            daily_limit=daily_limit
        )
        message = "已更新会员"
    else:
        # Create new member
        member_service.create_member(
            new_key,
            name=name,
            tier=tier,
            enabled=enabled,
            expires_at=expires_at,
            daily_limit=daily_limit
        )

        # Also add to whitelist
        if kind == "ip":
            _, ip_info = whitelist_repo.load_ip_whitelist()
            ip_info[values] = name
            whitelist_repo.save_ip_whitelist(ip_info)
        elif kind == "qq":
            _, qq_info = whitelist_repo.load_qq_whitelist()
            qq_info[values] = name
            whitelist_repo.save_qq_whitelist(qq_info)

        message = "已创建会员"

    return _render_admin_page("保存会员", f'<div>{message}</div><div style="margin-top:10px;"><a href="/admin/members">返回列表</a></div>')


@member_routes.post("/admin/members/delete")
async def admin_members_delete(request: Request):
    """Handle member deletion."""
    user, redirect = _admin_guard(request)
    if redirect:
        return redirect

    form = await request.form()
    key = str(form.get("key") or "").strip()

    if key and member_service.delete_member(key):
        # 同步删除白名单中的对应条目
        if key.startswith("ip:"):
            ip = key[3:]
            _, ip_info = whitelist_repo.load_ip_whitelist()
            if ip in ip_info:
                del ip_info[ip]
                whitelist_repo.save_ip_whitelist(ip_info)
        elif key.startswith("qq:"):
            qq = key[3:]
            _, qq_info = whitelist_repo.load_qq_whitelist()
            if qq in qq_info:
                del qq_info[qq]
                whitelist_repo.save_qq_whitelist(qq_info)

        return _render_admin_page("删除会员", '<div>已删除会员</div><div style="margin-top:10px;"><a href="/admin/members">返回列表</a></div>')
    else:
        return _render_admin_page("删除会员", '<div>删除失败</div>')


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
            return JSONResponse({"success": True, "message": f"已启用 {count} 个会员"})

        elif action == "disable":
            count = member_service.batch_update_members(keys, enabled=False)
            return JSONResponse({"success": True, "message": f"已禁用 {count} 个会员"})

        elif action == "set_permanent":
            count = member_service.batch_update_members(keys, tier="permanent")
            return JSONResponse({"success": True, "message": f"已将 {count} 个会员设为永久"})

        elif action == "extend_30":
            count = member_service.batch_update_members(keys, extend_days=30)
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

            return JSONResponse({"success": True, "message": f"已删除 {count} 个会员（含白名单同步删除）"})

        else:
            return JSONResponse({"success": False, "message": "未知操作"})

    except Exception as e:
        return JSONResponse({"success": False, "message": str(e)})
