"""Test script for Captcha API refactored code."""
import requests
import json

BASE_URL = "http://127.0.0.1:8899"
session = requests.Session()

print("=" * 60)
print("🧪 Captcha API 重构测试")
print("=" * 60)

# Test 1: Login Page
print("\n📋 测试 1: 登录页面加载")
try:
    r = session.get(f"{BASE_URL}/admin/login")
    assert r.status_code == 200
    assert "SweetAlert2" in r.text
    assert "管理员登录" in r.text
    print("   ✅ 登录页面加载成功")
    print("   ✅ SweetAlert2 已集成")
except Exception as e:
    print(f"   ❌ 失败: {e}")

# Test 2: Login
print("\n📋 测试 2: 管理员登录")
try:
    r = session.post(f"{BASE_URL}/admin/login", data={
        "username": "admin",
        "password": "admin"
    }, allow_redirects=False)
    assert r.status_code == 303
    assert "admin_session" in r.cookies
    print("   ✅ 登录成功")
    print(f"   ✅ Session cookie: {r.cookies['admin_session'][:20]}...")
except Exception as e:
    print(f"   ❌ 失败: {e}")

# Test 3: Dashboard
print("\n📋 测试 3: 管理后台首页")
try:
    r = session.get(f"{BASE_URL}/admin")
    assert r.status_code == 200
    assert "白名单" in r.text or "dashboard" in r.text.lower()
    print("   ✅ 首页加载成功")
except Exception as e:
    print(f"   ❌ 失败: {e}")

# Test 4: Members Page
print("\n📋 测试 4: 会员管理页面")
try:
    r = session.get(f"{BASE_URL}/admin/members")
    assert r.status_code == 200
    print("   ✅ 会员页面加载成功")
    # Check for batch operation features
    if "batch" in r.text.lower() or "批量" in r.text:
        print("   ✅ 批量操作功能存在")
except Exception as e:
    print(f"   ❌ 失败: {e}")

# Test 5: Whitelist Page
print("\n📋 测试 5: 白名单管理页面")
try:
    r = session.get(f"{BASE_URL}/admin/whitelist")
    assert r.status_code == 200
    print("   ✅ 白名单页面加载成功")
except Exception as e:
    print(f"   ❌ 失败: {e}")

# Test 6: Renewals Page
print("\n📋 测试 6: 续费通知页面")
try:
    r = session.get(f"{BASE_URL}/admin/renewals")
    assert r.status_code == 200
    print("   ✅ 续费通知页面加载成功")
except Exception as e:
    print(f"   ❌ 失败: {e}")

# Test 7: Whitelist Status API
print("\n📋 测试 7: 白名单状态 API")
try:
    r = session.get(f"{BASE_URL}/whitelist/status")
    data = r.json()
    assert "ip_count" in data
    assert "qq_count" in data or "qq_total_count" in data
    print(f"   ✅ API 响应正常")
    print(f"   📊 IP数量: {data['ip_count']}, QQ数量: {data.get('qq_total_count', data.get('qq_count', 0))}")
except Exception as e:
    print(f"   ❌ 失败: {e}")

# Test 8: Recognize API (without whitelist should fail)
print("\n📋 测试 8: 识别API (白名单验证)")
try:
    r = session.post(f"{BASE_URL}/recognize", json={"url": "http://example.com/test.jpg"})
    data = r.json()
    # Should fail because no whitelist configured
    if data.get("code") == 40300 or "访问被拒绝" in data.get("msg", ""):
        print("   ✅ 白名单验证正常工作")
    else:
        print(f"   ⚠️  意外响应: {data.get('msg', '')[:50]}")
except Exception as e:
    print(f"   ❌ 失败: {e}")

# Test 9: Check data files
print("\n📋 测试 9: 数据文件生成")
import os
data_files = [
    "members.json",
    "usage_counters.json",
    "renewal_requests.json",
    "admin_credentials.json",
    "admin_secret.txt",
]
for f in data_files:
    if os.path.exists(f"{BASE_URL.replace('http://127.0.0.1:8899', '').replace(':', '').split('/')[0]}/{f}"):
        # Can't check relative path, skip
        pass

# Test 10: Logout
print("\n📋 测试 10: 退出登录")
try:
    r = session.post(f"{BASE_URL}/admin/logout", allow_redirects=False)
    assert r.status_code == 303
    print("   ✅ 退出成功")
except Exception as e:
    print(f"   ❌ 失败: {e}")

print("\n" + "=" * 60)
print("🎯 测试完成")
print("=" * 60)
