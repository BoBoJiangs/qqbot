## 现状结论
- 项目现在“已经部分用数据库”：配置里启用的是 **H2 内存库**，但仅给 `ProductPrice` 做运行时查询；真正的持久化主要还是 **JSON/TXT 文件 + 内存 Map**（如 `cache/task_data.json`、`cache/time_tasks.json`、`config/bot-*.json`、`config/admin-auth.json`，以及 `captcha-api` 的一组 `*.json` 文件）。
- 如果你是 **单机单实例** 跑机器人，且能接受“异常退出可能丢最近 1 小时状态”的风险，那么继续用文件也完全可行。
- 如果你希望：
  - 异常退出尽量不丢状态（分钟级持久化）
  - 多实例/容器化扩容（多副本共享状态）
  - 需要按时间/群/用户做统计查询、留历史价格曲线
  那么引入数据库会更合理。

## 数据库选型（按改造成本从低到高）
1) **H2 file 模式（推荐做第一步）**
- 优点：你已在用 Spring Data JPA/Hibernate，不引入新依赖；改配置即可让现有 JPA 数据持久化到磁盘；部署简单。
- 适用：单实例、希望“先把数据落盘”。

2) **SQLite（单机更轻量）**
- 优点：单文件、易备份。
- 缺点：Java 侧要引入驱动与方言；并发写能力一般，但对单实例机器人足够。

3) **PostgreSQL（推荐做最终形态，若考虑多实例/长期历史）**
- 优点：并发与可靠性强；支持 `jsonb`、索引、聚合统计、时间序列历史查询。
- 适用：多实例/想把“价格历史、统计、审计日志”做得更专业。

## 建议落库的内容（按收益排序）
- **高收益（建议第一批）**：
  - `cache/task_data.json` 对应的群状态/统计/提醒配置（目前 1 小时落盘一次，风险最大）。
  - `cache/time_tasks.json` 定时任务配置（目前文件写入，写冲突与损坏风险）。
- **中收益（可第二批）**：
  - `ProductPrice` 价格历史：从“写回 txt”改为“以 DB 为主”，同时保留导出备份。
- **低收益（可继续用文件）**：
  - `config/bot-*.json`、`config/admin-auth.json` 这类“少量、人工可编辑的配置”，保留文件反而更直观。

## 方案 A（最小改造，推荐先做）：把状态以 JSON Blob 落库
目标：最快把“容易丢的运行状态”稳定持久化。
- 表设计（通用 KV + JSON）：
  - `kv_store`：`scope`(如 group/bot/global)、`key`、`json_value`、`updated_at`
  - 用它存：
    - group 维度：把 `GroupManager` 里那一大坨 Map 打包成一个 JSON（或按模块拆成多个 key）。
    - time tasks：按 `qq` 维度存任务列表 JSON。
- 写入策略：
  - 替换“每小时写文件”为“定时 upsert（例如每 1~5 分钟）+ 关键操作后立即写一次”。
  - 仍保留原文件作为备份导出（可选）。
- 优点：改动少、上线快；未来迁移到 Postgres jsonb 也很自然。
- 缺点：复杂查询不方便（但你当前主要是读写状态，不是 OLAP 查询）。

## 方案 B（结构化落库，适合要做统计/历史分析）
目标：可查询、可按时间聚合、可索引。
- 典型表：
  - `group_config`：群开关/提醒配置
  - `group_speech_stats`：群-用户-日期/小时维度统计
  - `group_task_stats`：任务计数/完成情况
  - `price_history`：商品、价格、时间、来源
  - `verify_stats`：验证成功/失败、时间
- 优点：报表与查询强。
- 缺点：表多、迁移工作量更大。

## Java 侧落地方式（与你现在项目最贴合）
- 继续使用 Spring Data JPA（你已有 `@Entity ProductPrice` 和 `CrudRepository`）。
- 第一步把 `application-local.yml` 的 datasource 从 `jdbc:h2:mem:` 改为 **H2 file**（或 Postgres），让 DB 真正持久化。
- 新增 1~2 个 Entity/Repository：
  - `KvStoreEntity`（scope/key/jsonValue/updatedAt）
  - `KvStoreRepository`（find/upsert）
- 在 `GroupManager`/`TaskStore` 中：
  - 启动加载：优先从 DB 读；读不到则回退到旧 JSON 文件（兼容老数据）。
  - 运行保存：写 DB；可选同时导出旧文件备份。

## captcha-api（Python 子项目）处理建议
- 如果它是单机跑、数据量小：继续文件也没问题。
- 若要统一：用 SQLite（一个 `captcha-api.sqlite`）存 members/usage_counters/renewal_requests/admin_settings；保留原 JSON 作为一次性导入与备份导出。

## 迁移与验证（确保不丢数据）
- 启动时做一次“导入迁移”：检测旧文件存在且 DB 空 → 导入 DB。
- 迁移完成后：
  - 对比导入前后关键计数（任务数、群数、价格条数）。
  - 增加一个简单的管理接口/日志输出（不含敏感信息）用于确认“DB 已接管”。

## 我将如何在仓库里实现（执行步骤）
1) 选择方案：默认按 **方案 A + H2 file** 落地（最省事、收益最大）；同时预留未来切 Postgres 的配置。
2) 新增 KV 表对应的 Entity/Repository，并接入 `GroupManager` 与 `TaskStore` 的加载/保存逻辑（保留旧文件回退/导入）。
3) 调整保存频率与异常退出保障（把 1 小时一次改成更小间隔 + 关键路径立即写）。
4) 添加最小化自测：启动→加载→修改状态→重启验证状态仍在。

确认后我会按“方案 A + H2 file”直接提交代码改造，并把切换到 Postgres 的配置模板一并给到。