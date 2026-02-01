from fastapi.responses import HTMLResponse


class AdminUI:
    def __init__(self):
        self._admin_css = """
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
          .form-group { background: rgba(2,6,23,.35); border:1px solid rgba(148,163,184,.12); border-radius: 12px; padding: 16px; margin-bottom: 16px; }
          .form-group h3 { margin: 0 0 14px 0; font-size: 14px; color:#a5b4fc; display:flex; align-items:center; gap: 8px; }
          .form-group h3::before { content: ''; width: 3px; height: 16px; background: linear-gradient(180deg, #22d3ee, #60a5fa); border-radius: 2px; }
          .checkbox-wrapper { display:flex; align-items:center; gap: 8px; padding: 6px 0; }
          input[type="checkbox"] { width: 18px; height: 18px; accent-color: #22d3ee; cursor: pointer; }
          .batch-actions { margin-top: 12px; padding: 12px; background: rgba(15,23,42,.45); border-radius: 12px; border:1px solid rgba(148,163,184,.15); }
          .validation-msg { padding: 8px 12px; border-radius: 8px; font-size: 12px; margin-top: 8px; display: none; }
          .validation-msg.error { background: rgba(127,29,29,.25); border:1px solid rgba(248,113,113,.35); color: #fca5a5; }
          .validation-msg.success { background: rgba(20,83,45,.25); border:1px solid rgba(34,197,94,.35); color: #86efac; }
          .validation-msg.show { display: block; }
          .status-badge { display:inline-block; padding: 4px 10px; border-radius: 8px; font-size: 11px; font-weight: 600; text-transform: uppercase; letter-spacing: .05em; }
          .status-badge.enabled { background: rgba(20,83,45,.25); border:1px solid rgba(34,197,94,.35); color: #86efac; }
          .status-badge.disabled { background: rgba(127,29,29,.25); border:1px solid rgba(248,113,113,.35); color: #fca5a5; }
          .status-badge.normal { background: rgba(30,41,59,.55); border:1px solid rgba(148,163,184,.18); color: #94a3b8; }
          .status-badge.month { background: rgba(59,130,246,.25); border:1px solid rgba(96,165,250,.35); color: #93c5fd; }
          .status-badge.permanent { background: rgba(168,85,247,.25); border:1px solid rgba(192,132,252,.35); color: #c4b5fd; }
          .expiry-warning { color: #fbbf24; font-weight: 600; }
          .expiry-expired { color: #f87171; font-weight: 600; }
          .expiry-ok { color: #86efac; }
          .nav a.active { background: rgba(59,130,246,.25); border-color: rgba(96,165,250,.5); color: #93c5fd; }
          .table-wrapper { overflow-x: auto; border-radius: 14px; }
          .number-input-wrapper { display:inline-flex; align-items:center; gap: 4px; }
          .number-input-wrapper input { width: 60px; padding: 6px 8px; text-align: center; }
          @media (max-width: 860px) {
            .app { flex-direction: column; }
            .sidebar { width: 100%; height: auto; position: static; border-right: none; border-bottom: 1px solid rgba(148,163,184,.12); }
          }
        </style>
        """

        self._login_css = """
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

    def render_page(self, title: str, body_html: str, message: str | None = None) -> HTMLResponse:
        msg_html = f'<div class="msg">{message}</div>' if message else ""
        html = f"""
        <html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">{self._admin_css}<title>{title}</title></head>
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

    def login_page(self, message: str | None = None) -> HTMLResponse:
        msg_html = f'<div class="msg">{message}</div>' if message else ""
        html = f"""
        <html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">{self._login_css}<title>管理员登录</title></head>
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
              <div class="hero-sub">登录后可维护 IP/QQ 白名单、会员等级、调用次数与月卡续费天数，并在续费通知中审批延长月卡。</div>
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

