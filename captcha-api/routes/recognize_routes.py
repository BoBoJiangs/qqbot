"""Captcha recognition demo routes.

Provides web interface for captcha recognition testing.
"""
from fastapi import APIRouter, Request
from fastapi.responses import HTMLResponse, RedirectResponse
from services.auth_service import get_auth_service
from admin_ui import AdminUI


# Create router
recognize_routes = APIRouter()

# Initialize services
auth_service = get_auth_service()
admin_ui = AdminUI()


def _admin_guard(request: Request):
    """Guard function for admin routes."""
    if auth_service.is_local_only() and request.client.host not in {"127.0.0.1", "::1"}:
        return None, HTMLResponse("仅允许本机访问管理后台（可通过环境变量 ADMIN_LOCAL_ONLY=0 关闭）", status_code=403)

    user = auth_service.get_current_user(request)
    if not user:
        return None, RedirectResponse("/admin/login", status_code=303)

    return user, None


def _render_admin_page(title: str, body_html: str) -> HTMLResponse:
    """Render admin page with layout."""
    return admin_ui.render_page(title, body_html)


@recognize_routes.get("/admin/recognize", response_class=HTMLResponse)
async def admin_recognize_get(request: Request):
    """Render captcha recognition page."""
    user, redirect = _admin_guard(request)
    if redirect:
        return redirect

    body = """
    <div class="card">
      <h2>验证码识别</h2>

      <div class="form-group">
        <h3>上传本地图片</h3>
        <input type="file" id="fileInput" accept="image/*" style="margin-bottom:10px;">
        <button onclick="recognizeUpload()" class="btn">识别上传图片</button>
      </div>

      <div class="form-group">
        <h3>或输入网络图片URL</h3>
        <input type="text" id="urlInput" placeholder="https://example.com/captcha.png" style="margin-bottom:10px;">
        <button onclick="recognizeUrl()" class="btn">识别URL图片</button>
      </div>
    </div>

    <div id="results" style="display:none;margin-top:16px;">
      <div class="card">
        <div style="display:flex;justify-content:space-between;align-items:center;margin-bottom:16px;">
          <h2 style="margin:0;">识别结果</h2>
          <div style="display:flex;gap:16px;align-items:center;">
            <div style="font-size:14px;">
              <span class="muted">识别文字:</span>
              <strong id="resultText" style="font-size:16px;margin-left:8px;"></strong>
            </div>
            <div style="font-size:14px;">
              <span class="muted">表情符号:</span>
              <strong id="resultEmoji" style="font-size:16px;margin-left:8px;"></strong>
            </div>
            <div style="font-size:14px;">
              <span class="muted">总数:</span>
              <strong id="totalCount" style="font-size:16px;margin-left:8px;">0</strong>
            </div>
          </div>
        </div>

        <div style="margin-bottom:16px;">
          <h3 style="font-size:14px;margin-bottom:12px;">标注原图</h3>
          <img id="annotatedImage" style="max-width:100%;border-radius:12px;border:1px solid var(--border-subtle);">
        </div>

        <div>
          <h3 style="font-size:14px;margin-bottom:12px;">分割详情 (<span id="segmentCount">0</span>个)</h3>
          <div id="segmentsGrid" style="display:grid;grid-template-columns:repeat(auto-fill,minmax(150px,1fr));gap:12px;"></div>
        </div>
      </div>
    </div>

    <div id="loading" style="display:none;position:fixed;top:0;left:0;right:0;bottom:0;background:rgba(0,0,0,.7);z-index:1000;align-items:center;justify-content:center;">
      <div style="text-align:center;">
        <div style="font-size:16px;color:var(--text-primary);">正在识别...</div>
      </div>
    </div>

    <script>
    function showLoading() {
      document.getElementById('loading').style.display = 'flex';
    }

    function hideLoading() {
      document.getElementById('loading').style.display = 'none';
    }

    async function recognizeUpload() {
      const fileInput = document.getElementById('fileInput');
      if (!fileInput.files || !fileInput.files[0]) {
        alert('请选择图片文件');
        return;
      }

      const formData = new FormData();
      formData.append('image', fileInput.files[0]);

      showLoading();
      try {
        const response = await fetch('/recognize_upload', {
          method: 'POST',
          body: formData
        });
        const data = await response.json();
        displayResults(data);
      } catch (error) {
        alert('识别失败：' + error.message);
      } finally {
        hideLoading();
      }
    }

    async function recognizeUrl() {
      const url = document.getElementById('urlInput').value.trim();
      if (!url) {
        alert('请输入图片URL');
        return;
      }

      showLoading();
      try {
        const response = await fetch('/recognize_detail', {
          method: 'POST',
          headers: {'Content-Type': 'application/json'},
          body: JSON.stringify({url: url})
        });
        const data = await response.json();
        displayResults(data);
      } catch (error) {
        alert('识别失败：' + error.message);
      } finally {
        hideLoading();
      }
    }

    function displayResults(data) {
      if (data.code !== 0) {
        alert(data.msg || '识别失败');
        return;
      }

      // Show results section
      document.getElementById('results').style.display = 'block';

      // Update summary
      document.getElementById('resultText').textContent = data.result || '(无)';
      document.getElementById('resultEmoji').textContent = data.emojiList.join(' ') || '(无)';
      document.getElementById('totalCount').textContent = data.total_count || 0;

      // Annotated image
      document.getElementById('annotatedImage').src = data.annotated_image;

      // Segments grid
      const grid = document.getElementById('segmentsGrid');
      grid.innerHTML = '';

      if (data.items && data.items.length > 0) {
        document.getElementById('segmentCount').textContent = data.items.length;

        data.items.forEach((item, index) => {
          const card = document.createElement('div');
          card.className = 'card';
          card.style.padding = '12px';
          card.style.margin = '0';
          card.style.animation = 'fadeIn 0.3s ease';

          const confidenceColor = item.confidence > 0.8 ? '#86EFAC' : item.confidence > 0.6 ? '#FBBF24' : '#FCA5A5';

          card.innerHTML = `
            <div style="text-align:center;margin-bottom:8px;">
              <img src="${item.image}" style="width:100%;border-radius:8px;border:1px solid var(--border-subtle);">
            </div>
            <div style="text-align:center;">
              <div style="font-size:18px;font-weight:700;margin-bottom:4px;">${item.label}</div>
              <div style="font-size:11px;color:${confidenceColor};">
                ${(item.confidence * 100).toFixed(1)}% 置信度
              </div>
              <div style="font-size:11px;color:var(--text-secondary);margin-top:4px;">
                ${item.type === 'text' ? '文字' : '表情'}
              </div>
              <div style="font-size:11px;color:var(--text-secondary);">
                #${index + 1}
              </div>
            </div>
          `;
          grid.appendChild(card);
        });
      } else {
        document.getElementById('segmentCount').textContent = '0';
        grid.innerHTML = '<div class="muted">未检测到分割区域</div>';
      }

      // Scroll to results
      document.getElementById('results').scrollIntoView({behavior: 'smooth'});
    }

    // File input change handler
    document.getElementById('fileInput').addEventListener('change', function(e) {
      if (e.target.files && e.target.files[0]) {
        document.getElementById('urlInput').value = '';
      }
    });

    // URL input change handler
    document.getElementById('urlInput').addEventListener('input', function(e) {
      if (e.target.value) {
        document.getElementById('fileInput').value = '';
      }
    });
    </script>
    """

    return _render_admin_page("验证码识别", body)
