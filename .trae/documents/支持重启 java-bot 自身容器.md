## 目标
- 在不依赖手动 SSH 的情况下，从页面触发重启 `java-bot`。
- 避免用户看到 Bad Gateway，改为可控提示与自动恢复。

## 方案
- 保留“禁止 stop/remove 自身容器”。
- 对“restart 自身容器”改为异步：先返回 202/成功消息，再后台延迟触发 Docker restart。

## 后端改动
- 在 DockerService 增加 `restartSelfAsync(containerId)` 或在 `restartContainer` 内对 self 分支走异步执行。
- DockerController 的 `/containers/{id}/restart`：
  - 非 self：同步重启，返回 200。
  - self：返回 202，并携带提示文案与建议等待时间。

## 前端改动
- 点击重启自身时：
  - 显示“将重启服务，页面会短暂断开，约 N 秒后自动恢复”。
  - 发送请求后开始轮询 `/api/docker/diagnose` 或 `/api/docker/containers`，直到恢复再刷新列表。

## 验证方式
- 本地打包通过。
- 在服务器上点击重启 `java-bot`：
  - 先收到 202 提示；
  - 页面短暂不可用后自动恢复；
  - 容器重启确实发生（通过列表状态/启动时间变化验证）。