# 统一验证码验证统计

从新版上线后开始累计，不迁移旧版本地历史。旧 Java 仍可识别，但不纳入这份成功、失败统计。所有需要参与统计的 Java 服务都必须升级，同时连接同一个验证码 API。

## 数据口径

- 新 Java 在 `/recognize?qq=机器人QQ` 请求中携带 `statistics: {version: 1, serverId, serverName}`，API 返回独立的 `recognitionId` 和结果上报凭据 `feedbackToken`。
- API 记录调用时间、服务器、QQ、主模型或 KNN；识别出结果不代表验证成功。Java 收到游戏成功、失败回执，或确认无可用答案后，才上报对应结果和原因。
- 接口异常、验证码过期、人工接管、重启时尚未确认、回执超时均为未确认。无可用答案、KNN 重复已失败答案均为失败；随机点击的成功不归功于模型。
- 主模型与 KNN 分开统计，成功率为 `成功 / (成功 + 失败)`，未确认不进入分母。
- 今日按北京时间 `00:00–次日00:00` 的**识别发起时间**归属。昨天调用、今天收到回执的记录仍计入昨天。
- 一个编号只结算一次。相同结果重复上报直接返回成功，不重复计数；试图改变已结算结果返回 409。
- API 暂时无法保存统计时，识别仍继续，响应包含 `statisticsError`，该次不计入统一统计。

## 上线配置

1. 更新验证码 API 源码并重启。API 默认在工作目录创建 `recognition_statistics.db`、WAL 文件和 `recognition_statistics.key`。这些文件需要持久化；Docker 重建时应挂载持久卷。API 多个 worker 使用同一数据库路径。
2. 在 API 后台打开「全局验证统计」，展开「接入说明与查询密钥」，复制查询密钥。
3. 更新每台 Java 服务的 `bot.jar`，把同一查询密钥写入各服务的物理文件 `config/captcha-statistics.key`，再重启服务。该密钥仅用于查询；上报使用每次识别的独立凭据。

可选的 Java 配置：

```yaml
captcha:
  shitu-api-url: http://your-captcha-api:8000/
  statistics:
    server-id: server-a           # 可省略，首次启动自动生成并持久化
    server-name: 上海服务器       # 可省略，默认主机名
    read-key-file: ./config/captcha-statistics.key
    # read-key: 也可直接配置查询密钥，优先于文件
```

服务器编号必须唯一，允许字母、数字、点、下划线和短横线，最长 64 字符。复制整份 Java 运行数据库到新服务器时，应为新服务指定不同的 `server-id`。

Java 的服务器身份和待上报队列通过现有 `LocalDataFiles` 存入本地 SQLite（默认 `config/qqbot.db`），文档键分别为 `config/captcha-statistics-instance.json`、`config/captcha-statistics-outbox.json`，并非必须存在的物理 JSON 文件。上报失败保留队列，每 10 秒自动重试，服务重启后继续补报。重启前未提交确定结果的识别按未确认补报；无回执超过 10 分钟也按未确认处理。

## 查询

```text
验证统计
验证统计 服务器列表
验证统计 服务器 server-a
验证统计 QQ 123456789
验证统计 服务器 server-a QQ 123456789
```

QQ 命令和 API 后台使用同一份数据库、同一套格式。默认全局，主模型与 KNN 分别展示累计和今日的调用、成功、失败、未确认及成功率。

后台首页和会员页的「今日验证统计（全局）」也读取同一个 `/admin/recognition-statistics/data` 接口，按北京时间显示主模型与 KNN 的今日结果，不受会员列表筛选影响。会员用量和旧版报错单独标明 UTC 日期口径，用于额度管理和旧接口记录，不再用它们推算验证准确率。

```text
验证码验证统计（全局）
统计范围：全部服务器 · 全部 QQ
统计来源：3 台 Java 服务 · 8 个 QQ

主模型
累计：调用 1000，成功 920，失败 50，未确认 30，成功率 94.85%
今日：调用 100，成功 94，失败 4，未确认 2，成功率 95.92%

KNN 重试
累计：调用 50，成功 32，失败 15，未确认 3，成功率 68.09%
今日：调用 4，成功 3，失败 1，未确认 0，成功率 75.00%
```

以上为格式示例，非真实数据。API 后台支持按服务器、QQ 筛选，以及清空全局统计重新计数。清空后旧编号的延迟上报返回 404，Java 停止重试该编号；旧数据不会重新计入。QQ 的「重置验证统计」会提示前往 API 后台操作。

## API

- `POST /statistics/feedback`：JSON 为 `{recognitionId, feedbackToken, outcome, reason}`。`outcome` 为 `success`、`failure` 或 `unconfirmed`，原因由 Java 按已约定枚举填写。接口独立校验凭据，会员到期不会阻断已产生记录的补报。
- `POST /recognize/fallback?qq=...`：仅当 URL 下载失败且返回 `uploadFallback=true` 时，由 Java 上传 `{recognitionId, feedbackToken, url, imageBase64}`。传送原始图片字节，沿用原识别编号，不新增调用、不重复扣使用次数。凭据绑定 QQ 与原 URL，5 分钟内仅可用一次。主模型、KNN 均支持，最终仍等待游戏回执；兜底失败归未确认，不随机点击、不上报为模型错题。
- `GET /statistics/summary?server_id=server-a&qq=123456789`：查询参数可省略，携带 `X-Statistics-Key`，返回结构化统计以及 QQ 展示用 `text`。
- `GET /statistics/servers`：携带 `X-Statistics-Key`，查询已接入服务器列表。
- `/admin/recognition-statistics`：需要登录并完成初始密码修改的后台页面，支持查询、读取密钥和重置。

API 环境变量 `RECOGNITION_STATISTICS_FILE`、`RECOGNITION_STATISTICS_KEY_FILE` 可覆盖数据库及查询密钥文件路径。

## 离线验证

```text
python -B -m unittest discover -s tests -v
mvn test
```

测试使用临时 SQLite、伪造游戏回执和本机 HTTP 服务，不访问线上验证码 API、不使用真实会员数据。
