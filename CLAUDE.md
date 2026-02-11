# CLAUDE.md

此文件为 Claude Code (claude.ai/code) 在本仓库中工作时提供指导。

## 语言偏好

**重要：请始终使用中文与用户交流和回答问题。**

## 项目概述

这是一个基于 Java 的 QQ 机器人自动化系统，用于修仙主题游戏。采用 Spring Boot 2.5.4 和 Java 11 构建，通过 WebSocket 连接 NapCat/QQ 机器人框架，实现修炼、炼丹、悬赏令、宗门任务等游戏功能的自动化。

**包名**: `top.sshh.qqbot`
**框架**: 自定义 bot-core 库 (`lib/bot-core-1.0.0.jar`) - `com.zhuangxv.bot`
**版本**: 1.0.4

## 构建和运行命令

```bash
# 构建项目（在项目根目录生成 bot.jar）
mvn clean package

# 构建带验证码识别模块的版本（OpenCV）
mvn clean package -P with-captcha

# 构建不带验证码的版本（默认）
mvn clean package -P no-captcha

# 跳过测试构建
mvn clean package -DskipTests

# 使用 Spring Boot Maven 插件运行
mvn spring-boot:run

# 直接运行构建好的 JAR
java -jar bot.jar

# 使用指定配置文件位置运行
java -jar bot.jar --spring.config.location=file:./config/
```

**重启脚本**:
- Windows: `restart_bot.bat`
- Linux/Mac: `restart_bot.sh` (带日志的完整重启流程)

## 配置说明

### 主要配置文件

- **应用配置**: `src/main/resources/application.yml` (设置激活的 profile: `local`)
- **本地环境**: `src/main/resources/application-local.yml` (Docker、NapCat、机器人设置)
- **机器人配置**: `config/bot-{qq_number}.json` (每个机器人的运行时配置)
- **管理员认证**: `config/admin-auth.json` (Web 界面认证)

### 关键配置项

**Docker 与 NapCat** (`application-local.yml`):
```yaml
docker:
  host: auto  # Unix Socket 自动检测

napcat:
  image: docker.1ms.run/mlikiowa/napcat-docker:latest
  data-root: /home/user/JavaBot
  qq-data-dir: /root/qq_data
  napcat-config-dir: /root/napcat_config
```

**机器人配置** (`application-local.yml`):
```yaml
bot:
  - type: ws              # 连接类型: ws (正向) 或 ws-reverse (反向)
    url: ws://host:port   # WebSocket 地址
    accessToken: ...      # 访问令牌
    groupId: ...          # 修炼群号
    controlQQ: ...        # 控制者QQ号，多个用 & 分隔
    masterQQ: ...         # 主号QQ（controlQQ 未设置时的回退）
```

### 单实例锁

应用使用端口 62345 的 ServerSocket 防止多实例运行。可通过以下方式禁用：
- JVM 参数: `-Dinstance.lock.enabled=false`
- 环境变量: `INSTANCE_LOCK_ENABLED=false`

## 架构设计

### 核心组件

**入口**: `QqBotApplication.java`
- 带有 `@EnableScheduling` 的 Spring Boot 应用
- 通过 ServerSocket 实现实例锁定

**机器人框架** (`lib/bot-core-1.0.0.jar`):
- 自定义 QQ 机器人框架 (`com.zhuangxv.bot`)
- 通过 WebSocket 连接 NapCat/QQ 机器人
- 基于注解的消息处理: `@GroupMessageHandler`, `@FriendMessageHandler`, `@OnQQConnected`
- BotFactory 模式支持多机器人

### 服务层结构

**主要服务**:
- `TestService.java` (~150KB) - 核心机器人命令处理和自动化逻辑
- `GroupManager.java` - 群消息管理、提醒、任务调度
- `BotConfigManager.java` - 配置持久化和运行时管理
- `DockerService.java` - NapCat 容器的 Docker 管理
- `UpdateService.java` - 自动更新机制

**功能模块**:
- `service/liandan/` - 炼丹模块 (AutoAlchemyTask, AutoBuyHerbs, DanCalculator)
- `AutoBuyGoods.java` - 自动购买（捡漏）
- `AutoSellGoods.java` - 自动出售
- `PriceTask.java` - 市场价格监控
- `FamilyTask.java` - 宗门任务自动化
- `XiaoBeiService.java` - 小北游戏机器人集成

### 控制器层

REST API 接口（都在 `/api/` 路径下）:
- `BotConfigController` - 机器人配置增删改查
- `BotConfigWebController` - Web 界面支持
- `DockerController` - Docker 容器管理
- `AuthController` - Web 界面认证
- `TestController` - 测试接口

**Web 界面**: `src/main/resources/static/`
- `bot-config.html` - 机器人配置界面
- `container-manager.html` - Docker 容器管理
- `index.html` - 价格走势图
- `login.html` - 登录页面

### 数据层

**数据库**: 内存 H2 数据库，使用 JPA/Hibernate
- 实体类在 `data/` 包中
- 自动 DDL: `spring.jpa.hibernate.ddl-auto=update`

**配置存储**: `config/` 目录下的 JSON 文件
- 通过 `ConcurrentHashMap` 运行时缓存
- 支持热重载，无需重启

### 并发处理

- `ForkJoinPool` 20 线程用于并行处理
- `@Scheduled` 定时任务线程池大小 10
- 通过 `ConcurrentHashMap` 保证机器人状态的线程安全

## 机器人命令系统

### 消息处理规则

**普通命令** (如 "命令"、"当前设置"、"修炼模式"):
- 使用注解参数 `ignoreItself = ONLY_ITSELF`
- 在机器人的修炼群内可直接使用 (`groupId == botConfig.groupId`)
- 在其他群需要消息包含机器人 QQ 号（见 `Utils.isAtSelf`）

**控制命令** (如 "执行"、"循环执行"、"弟子听令"):
- 标记 `isAt = true`（需要 @机器人）
- 需要控制者身份：发送者 QQ 必须在 `botConfig.controlQQ` 中（用 `&` 分隔）或等于 `botConfig.masterQQ`

### 关键命令模式

**多机器人控制** (掌门/弟子体系):
- `执行 <内容>` - 自动 @小小(3889001741) 并发送命令
- `循环执行 <内容>` - 多行格式，倒数第2行为次数，倒数第1行为间隔秒数
- `弟子听令执行 <内容>` - 广播到所有被控机器人
- `<编号><命令>` 或 `<爱称><命令>` - 按编号/昵称指定特定机器人

**炼丹命令** (见 `AutoAlchemyTask`, `AutoBuyHerbs`):
- `炼丹命令` - 显示炼丹菜单
- `开始自动炼丹` / `停止自动炼丹`
- `采购药材<药材> <万价>` - 多行采购药材

**定时任务** (`SchedulerTask.java`):
- `设置定时任务` - 多行格式: `HH:mm <内容>`

完整命令参考请查看 `.trae/documents/机器人命令详解.md`

## 游戏数据文件

位于 `properties/` 和 `src/main/resources/properties/`:
- `坊市价格.txt` - 市场价格
- `药材价格.txt` - 药材价格
- `丹方查询.txt` - 炼丹配方
- `丹药坊市价值.txt` - 丹药市场价值
- `丹药炼金价值.txt` - 丹药炼金价值

## API 接口

### Docker 管理 (`/api/docker`)
- `GET /api/docker/containers?all=true` - 列出容器
- `GET /api/docker/diagnose?reconnect=false` - Docker 诊断
- `POST /api/docker/containers/{id}/restart` - 重启容器
- `POST /api/docker/containers/{id}/stop` - 停止容器（保护自身）
- `DELETE /api/docker/containers/{id}` - 删除容器（移除 ws-reverse 配置）
- `POST /api/docker/napcat` - 创建 Napcat 容器

### 认证 (`/api/auth`)
- `POST /api/auth/login` - 登录 `{username, password}`
- `GET /api/auth/me` - 检查登录状态
- `POST /api/auth/logout` - 登出
- `POST /api/auth/change-password` - 修改密码

### 机器人配置 (`/api/bot-config`)
- `GET /api/bot-config/all` - 获取所有配置
- `GET /api/bot-config/{botId}` - 获取单个机器人配置和状态
- `PUT /api/bot-config/{botId}` - 更新配置 (Body: Map)
- `POST /api/bot-config/{botId}/reset` - 重置为默认
- `GET /api/bot-config/options` - 配置项说明
- `GET /api/bot-config/{botId}/status` - 运行时状态
- `GET /api/bot-config/{botId}/export` - 导出配置
- `POST /api/bot-config/{botId}/import` - 导入配置
- `GET /api/bot-config/bots` - 在线机器人列表

## 开发注意事项

### 代码历史
- 部分类包含反编译注释（FernFlower）
- 大量使用 `ConcurrentHashMap` 保证线程安全的机器人状态
- 广泛使用 Spring 注解 (@Component, @Service, @Autowired, @Scheduled)

### 机器人连接类型
- **正向 (ws)**: 机器人主动连接 NapCat WebSocket 服务器
- **反向 (ws-reverse)**: NapCat 主动连接机器人的 WebSocket 服务器

### 验证码识别
三种模式 (`autoVerifyModel`):
- 0: 手动（不自动验证）
- 1: 半自动（失败后停止验证）
- 2: 全自动（持续重试）

实现位置: `verifycode/RemoteVerifyCode.java` (Python API 集成)

### 自动更新系统
- 通过 `update.enabled: true` 启用
- 定期检查远程 URL (`update.checkIntervalMs`)
- 下载到 `bot.jar.new`，重启脚本在下次重启时应用
- 可配置自动应用和自动重启

## 重要文件位置

```
D:\Java\qqbot\
├── src/main/java/top/sshh/qqbot/
│   ├── QqBotApplication.java          # 入口
│   ├── config/                         # Spring 配置
│   ├── controller/                     # REST API
│   ├── data/                          # 数据模型/实体
│   ├── service/                       # 业务逻辑
│   │   ├── liandan/                   # 炼丹模块
│   │   ├── impl/                      # 工具类
│   │   └── utils/                     # 工具
│   ├── constant/                       # 常量（游戏数据）
│   └── verifycode/                    # 验证码处理
├── src/main/resources/
│   ├── application*.yml/properties    # Spring 配置
│   ├── properties/                    # 游戏数据文件
│   └── static/                        # Web 界面
├── config/                           # 机器人 JSON 配置
├── properties/                       # 游戏数据副本
├── Docker/                           # Docker 文件
├── lib/bot-core-1.0.0.jar         # 自定义机器人框架
├── pom.xml                          # Maven 配置
└── bot.jar                          # 构建产物
```

## 常见问题

- **"之前启动的程序没有关闭"**: 单实例锁生效 - 需结束前一个进程或禁用锁
- **删除容器后未移除 ws-reverse 配置**: 可能需要手动从 `application-local.yml` 中移除
- **容器缺少 `WS_URL` 环境变量**: 自动移除功能失效，检查容器配置
