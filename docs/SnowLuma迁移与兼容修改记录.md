# SnowLuma 迁移与兼容修改记录

> 更新时间：2026-08-30
> 目的：记录协议端从 NapCat 迁移到 SnowLuma 的全部环境变化与代码修改，换电脑/换人接手时可快速定位。

---

## 一、背景

机器人协议端从 **NapCat（Docker 容器）** 迁移到 **SnowLuma（官方 Docker 镜像）**。
SnowLuma 同样提供 OneBot v11 协议（WebSocket/HTTP），java 端连接配置基本不变，
但 SnowLuma 的事件结构与 NapCat 的私有扩展存在差异，由此产生了一系列兼容性修改（见第四节）。

当前多协议并行运行：

| QQ 号 | 昵称 | 协议端 | 端口 | 状态 |
|-------|------|--------|------|------|
| 3988941800 | 华适四 | SnowLuma 容器（主进程） | 8084 | ✅ 已迁移 |
| 1319279034 | 宣藩九 | SnowLuma 容器（qq-extra-1 多开） | 8083 | ✅ 已迁移（2026-08-31） |
| 3860863656 | 花晟九 | NapCat 容器 `3860863656-8082-6102` | 8086 | 未迁移，正常在线 |
| 3821805584 | - | NapCat 容器（已停止） | 8080 | 停用中 |

---

## 二、服务器环境

- 服务器：`ubuntu@42.194.185.3`（腾讯云，Ubuntu 24.04，2核3.5G），本机已配 SSH 免密公钥
- Docker 容器：
  - `snowluma`：官方镜像 `motricseven7/snowluma:latest`，端口映射
    `6081(noVNC) / 5099(WebUI) / 3000-3001(OneBot HTTP，3000=华适四/3001=宣藩九) / 8084(华适四 ws) / 8083(宣藩九 ws)`
    启动参数必须保留：`--shm-size=2g --cap-add=SYS_PTRACE --security-opt seccomp=unconfined`
    数据卷（删了丢登录态）：`qq-gateway-data`、`qq-client-config`、`qq-client-data`、
    `qq-client-account-2`（宣藩九独立 HOME，对应环境变量 `SNOWLUMA_EXTRA_QQ_HOMES=/app/qq-acct2`）
    OneBot 配置：`/app/data/config/onebot_<uin>.json`（宣藩九 8083 的 token 为 `1024*1024*1024`，与 java 配置一致）
  - `java-bot`：eclipse-temurin:17-jdk，**host 网络模式**，挂载 `/home/user/JavaBot -> /app`
  - 旧 NapCat 容器：`3860863656-8082-6102`（在用）、`3988941800-8084`（已停，待删）、
    `3821805584-8080`（已停）、`1319279034-8083`（已停，该账号已迁 SnowLuma，容器可删）
- ⚠️ 宿主机上曾手动装过一份 SnowLuma（systemd 服务 `snowluma`），已停用并 disable，
  可删除：`/etc/systemd/system/snowluma.service` 与 `/home/SnowLuma/`

### 访问方式（任选其一）

1. SSH 隧道（推荐，无需开安全组）：
   ```bash
   ssh -N -L 5099:127.0.0.1:5099 -L 6081:127.0.0.1:6081 ubuntu@42.194.185.3
   ```
   然后本机访问 `http://localhost:5099`（WebUI）和 `http://localhost:6081`（noVNC 远程桌面）
2. 直接访问 `http://42.194.185.3:5099 / :6081`：需在腾讯云控制台安全组放行 TCP 5099/6081
   （建议来源限制为自己的 IP）

### 登录凭据

- WebUI：用户 `admin`，初始密码在容器首次启动日志中：
  `docker logs snowluma 2>&1 | grep "initial credentials" | tail -1`
  ⚠️ 未改密前每次重建数据目录都会重新生成；**登录后请立即改密**
- noVNC 密码：存于服务器 `/home/ubuntu/snowluma-vnc-password.txt`
- QQ 扫码登录：noVNC 进远程桌面，扫 QQ 登录窗口的二维码；
  ⚠️ 同一账号扫码会把 NapCat 容器里的登录顶下线（单点登录）

---

## 三、连接配置（java 端无需改动）

SnowLuma WebUI「节点配置」里为 3988941800 配置了 OneBot ws 服务端，
**刻意沿用旧 NapCat 的端口和 token**，因此 `application-local.yml` 无需修改：

```yaml
- type: ws
  url: ws://127.0.0.1:8084
  accessToken: 139ae1583d7442ea4b6aaa1b231850025c56a1bffa98de5db782d49ca7707e55
```

- 消息格式必须保持 **「数组」**，不要切「CQ 码」（bot-core 按 JSON 数组解析事件，切 CQ 码会全盘崩溃）
- java-bot 容器是 host 网络，`127.0.0.1` 即宿主机，SnowLuma 容器需用 `-p 8084:8084` 映射端口

---

## 四、代码修改清单（本次兼容性修复）

### 1. `Utils.java`（新增公共方法，核心）

- `parseButtonsFromMessage(Bot bot, String message, Integer messageId)`：
  从消息文本中解析 `inline_keyboard` 段重建 Buttons。
  兼容两种格式：① 整体为 JSON 数组；② 拼接渲染中嵌着的 `json[{...}]` 段
  （`extractJsonSegment` 花括号配平提取，容忍转义）
- `resolveMsgSeq(Bot bot, Integer messageId)`：通过 `get_msg` 动作把 message_id
  反查成协议端的 `message_seq`（SnowLuma 点击按钮要求 msg_seq 为无符号消息序号）

### 2. `GetMsgApi.java`（新增）

自定义 BaseApi：action=`get_msg`，参数 `message_id`，用于上面的 msgSeq 反查。

### 3. `RemoteVerifyCode.java`（验证码自动识别）

| 位置 | 修改 |
|------|------|
| `autoVerifyCode` 入口 | 注入的 buttons 为 null **或空列表**时，调用 `Utils.parseButtonsFromMessage` 兜底 |
| `getImageInfo` | 题目文本改为从后往前找包含"请点击"的段（SnowLuma 下最后一段是键盘，不含文字），兜底用整条 message |
| `getEmojiAnswer` | 序号正则锚定 `第(\d+|[一二三四五六七八九十]+)个`。原逻辑匹配题目里第一个数字，markdown 渲染的题目含图片尺寸/版本号等无关数字（如 1100px、%3A2），导致序号错位 |

### 4. `TestService.java`

| 位置 | 修改 |
|------|------|
| `识别大号接收码` | 接收码提取改用 UUID 正则（`[0-9a-f]{8}-...`），失败才回退旧的 split 逻辑 |
| `艾特小号执行` | 消息链无文本段时（纯卡片消息）直接 return，修复 `get(0)` 数组越界崩溃 |
| `自动点击按钮` | 接入按钮兜底解析（确认赠送灵石弹窗的自动点确认） |
| `验证码判断` | 接入按钮兜底解析（验证码暂停任务 + botButtonMap 记录） |

### 5. `WsKeepAliveTask.java`（新增）

每 40 秒对所有 Bot 发送一次 `get_login_info`。
原因：SnowLuma 的 ws 服务端把静默约 90 秒的连接判定为半开强制断开
（`client silent for ~90s, terminating half-open connection`），
bot-core 客户端空闲时不发心跳，导致连接反复掉线、断连窗口内消息丢失。

### 根因速查（为什么 NapCat 正常、SnowLuma 异常）

| 现象 | 根因 |
|------|------|
| 按钮/验证码完全不动 | bot-core 的 `MessageButtonInjector` 只认 NapCat 私有的 `raw.msgSeq` + `raw.elements[0].inlineKeyboardElement`，SnowLuma 事件没有这些字段 |
| 接收码提取成乱码 | SnowLuma 下小小回复是 markdown 卡片，bot-core 渲染为 `content[...]` 拼进消息文本，旧的按"冒号+空格"切分切在卡片头部 |
| 第N个表情点错 | 题目里混入 markdown 头部的无关数字（图片尺寸等） |
| 时好时坏 | ws 每 90 秒被 SnowLuma 掐断，断连窗口内事件丢失 |

---

## 五、待验证 / 遗留问题

1. **[刚修复待验证] 按钮点击 msgSeq**：已改为通过 `get_msg` 反查 `message_seq` 再点击
   （此前传 message_id 是负数，被 `f.uint()` 校验/消息定位拒绝，报
   `API调用失败[action=click_inline_keyboard_button]: null`）。
   需实际触发一次验证码/灵石确认弹窗确认能点上。
2. **识别接口准确率**：shitu 识别偶尔漏识别图中表情（如题目要"第6个"、只识别出 5 个），
   此时序号被钳位到最后一项可能点错。属于外部识别服务准确率，如频繁可考虑换服务或识别结果不全时重试。
3. **花晟九迁移**：3860863656 迁移方案已定未执行——再增开一个 extra HOME
   （`SNOWLUMA_EXTRA_QQ_HOMES=/app/qq-acct2,/app/qq-acct3` + 独立卷 `qq-client-account-3:/app/qq-acct3`
   + `-p 8086:8086`，shm 已是 2g），扫码后配置 ws 服务端 0.0.0.0:8086
   （token 与 java 配置一致即可，java 端不用改）。注意服务器内存 3.5G，
   snowluma 双开已占 1.3G，三开前评估内存。
4. **停用账号**：8080(3821805584) NapCat 容器已停，确认不要后可
   `docker rm` 并注释掉 java 配置里对应的 bot 条目（否则日志持续刷重试失败）。
   1319279034 已于 2026-08-31 迁入 SnowLuma（8083），旧 NapCat 容器 `1319279034-8083` 可删。
5. **清理**：确认稳定后删除旧容器 `3988941800-8084`、`1319279034-8083`；宿主机 snowluma 的 systemd 服务可删。
6. **WebUI 密码**：初始密码若未修改，重建容器/清数据卷后会重新生成，记得改密。
7. **多开 QQ 的自动登录**：SnowLuma 容器重启后 QQ 可能停在登录窗口，在 noVNC 里
   给每个 QQ 窗口勾选「自动登录」可免去重复扫码（2026-08-31 重建容器后华适四即因此需要手动点登录）。

---

## 六、常用命令

```bash
# 重启 java-bot（改完代码 mvn package 后）
scp target/bot.jar ubuntu@42.194.185.3:/tmp/bot.jar.new
ssh ubuntu@42.194.185.3 'sudo docker stop java-bot && sudo mv /tmp/bot.jar.new /home/user/JavaBot/bot.jar && sudo docker start java-bot'

# SnowLuma 容器运维
sudo docker restart snowluma          # 重启（登录态在卷里，可能需重扫码，建议QQ窗口勾选自动登录）
sudo docker logs -f snowluma          # 看日志
sudo docker exec snowluma supervisorctl status   # 多开QQ进程状态

# 看验证码/按钮调试日志
ssh ubuntu@42.194.185.3 'sudo docker logs --since 10m java-bot 2>&1 | grep 验证码调试'

# OneBot HTTP API 调试（容器内直连，token 见 SnowLuma 节点配置）
sudo docker exec snowluma node -e "fetch('http://127.0.0.1:3000/<action>',{method:'POST',headers:{'Content-Type':'application/json','Authorization':'Bearer <token>'},body:JSON.stringify({...})}).then(r=>r.text()).then(console.log)"
```

---

## 七、多账号扩展（官方方案速记）

- 每个额外 QQ 一个独立 HOME 卷 + 环境变量 `SNOWLUMA_EXTRA_QQ_HOMES=/app/qq-acct2,/app/qq-acct3`
- 每账号独立 `config/onebot_<uin>.json`，WebUI 自动列出所有 UIN
- 每个 QQ 进程约占 300-500MB 内存，多开建议 `--shm-size=2g`
- 所有 QQ 窗口在同一 noVNC 桌面里，分别扫码即可
- 临时手动多开（不挂卷，重建容器后登录态丢失）：
  `docker exec -u snowluma -e DISPLAY=:1 -e HOME=/app/qq-acct2 -d snowluma sh -lc 'qq --no-sandbox ${SNOWLUMA_QQ_FLAGS}'`
