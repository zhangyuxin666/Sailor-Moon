# 源码说明与阅读入口

本说明帮助组员和课程评审从业务追踪实现。模块职责见 [TEAM.md](TEAM.md)，详细机制见 [DESIGN.md](DESIGN.md)，服务边界见 [ARCHITECTURE.md](ARCHITECTURE.md)。

## 1. 启动、配置与构建

- [ActivityAssistantApplication.java](../src/main/java/com/activityassistant/ActivityAssistantApplication.java)：Spring Boot 启动与 WAR 容器入口。
- [pom.xml](../pom.xml)：Java 21、Spring Boot 3.5.16、WAR、测试依赖及 `web/dist` 资源打包。
- [application.yml](../src/main/resources/application.yml)：数据库/Flyway、上传、会话、AI/QQ 地址与环境变量映射。
- [Dockerfile](../Dockerfile)：React 构建、Maven 打包和外部 Tomcat 运行；AI、网关分别使用 `Dockerfile.ai`、`Dockerfile.gateway`。
- [scripts/run.ps1](../scripts/run.ps1)：`start.bat` 调用的启动、构建选项与就绪等待。部署模式见 [DEPLOYMENT.md](DEPLOYMENT.md)。
- [scripts/check.ps1](../scripts/check.ps1)：锁文件安装、测试、前端/WAR 构建、Compose 与可选离线跨服务检查；参数见 [DEVELOPMENT.md](DEVELOPMENT.md)。
- [scripts/backup.ps1](../scripts/backup.ps1)、[restore.ps1](../scripts/restore.ps1)、[backup-common.ps1](../scripts/backup-common.ps1)：停写备份、空目标恢复及挂载/归档校验；纯安全契约入口为 [test-backup-contract.ps1](../scripts/test-backup-contract.ps1)。

## 2. Java 业务后端

包根为 `src/main/java/com/activityassistant/`：

- `api/`：HTTP 入口、请求校验、账号获取与响应；`ApiModels` 定义结构化请求，`PageController` 处理前端页面入口。
- `security/`、`config/SecurityConfig`：数据库会话认证、改密限制、来源校验、密码哈希及 Spring Security 规则；具体组织/资源权限继续在业务服务检查。
- `service/ClassroomService`、`AuthService`、`StorageService`：班级与待办、成员、账号及文件归属。
- `service/AgentDraftService`、`ConfirmedPlans`：草案分析、确认快照与发布；`ActivityService` 为活动业务门面。
- `orchestration/ActivityOrchestrator`、`ExecutionJournal`、`RunStatus`：受限工具流程、步骤幂等和运行协议；`AgentRunService`、`RunLifecycleService` 维护运行状态与补充/恢复。
- `service/BackgroundJobService`、`JobScheduler`、`JobLease`：数据库领取、独立心跳、执行凭证、超时与有限恢复。
- `service/ActivityGate`、`RegistrationService`、`TaskManagementService`、`ActivityManagementService`：活动锁、报名、依赖/指派、生命周期/日历与变更事务。
- `service/OutboundStore`、`OutboundDeliveryService`、`DeliveryLogService`、`ChangeDeliveryService`、`QqService`：持久投递、未知结果、变更通知与 QQ 业务授权。
- `service/KnowledgeService`、`RecapStore`、`RecapService`：知识权限/向量版本、统计快照及复盘持久化。
- `service/ParticipationService`、`ExportService`、`TemplateService`：签到反馈快照、受限列 CSV、模板和幂等应用草案。
- `integration/`：`AiClient`、`QqGatewayClient` 封装内部 HTTP 调用；`persistence/Db` 封装 JDBC；`support/` 放安全错误、时间、ID、CSV 等公共能力。

阅读一次复杂活动建议按 `AgentController → AgentDraftService → ActivityService → BackgroundJobService/JobScheduler → ActivityOrchestrator → 业务工具/ExecutionJournal` 追踪；接口精确方法和路径以 [API.md](API.md) 及 Controller 为准。

## 3. React 前端

[web/src/App.tsx](../web/src/App.tsx) 定义路由，`components/Layout` 定义角色导航，`ProtectedRoute` 做页面准入，服务端仍是最终权限边界。

- `pages/Login`、`Settings`、`Privacy`：登录/初始化、账号与隐私。
- `pages/Members`、`Assignments`、`Portal`：班级/成员、作业、个人总览和本人活动任务。
- `pages/Agent`、`ActivityList`、`ActivityDetail`：草案与活动管理闭环。
- `pages/PublicForm`：公开报名与本人回执。
- `pages/Knowledge`、`Templates`、`Participation`：知识、业务模板、本人签到反馈。
- `components/ParticipationManagement`、`RecapPanel`、`ExportActions`：活动详情中可复用的管理区。
- `lib/`、`hooks/`、`types/`：请求/认证、状态与数据契约；`styles/global.css` 维护全局样式。

前端构建为 Vite 静态产物，部署时由同一 WAR 托管，不需要生产环境另跑 Vite。`web/package.json` 列出开发、构建和测试脚本。

## 4. AI 与 QQ

- [ai_service/main.py](../ai_service/main.py)：内部令牌校验、健康、规划、草案、复盘、回复、嵌入和检索入口；不开浏览器业务路由。
- `ai_service/models.py`：Pydantic 请求/结果结构；`llm.py`：模型调用与离线规则；`rag.py`：向量来源和组织过滤检索；`config.py`：环境配置。
- [qq-gateway/server.mjs](../qq-gateway/server.mjs)：QQ 连接与入站事件；`gateway-http.mjs`：内部消息 HTTP；`delivery-cache.mjs`、`persistent-delivery.mjs`：并发合并和持久投递日志。

AI 不创建业务实体，网关不代替组织权限校验，业务副作用由 Java 控制。

## 5. 数据、测试与修改约定

[数据库脚本说明](DATABASE.md) 解释 V1–V8，SQL 为字段/约束的准确信息。Java 测试位于 `src/test/java`，AI 测试位于 `tests_ai`，前端测试位于 `web/tests`，网关测试由其 `package.json` 指定。命令统一见 [DEVELOPMENT.md](DEVELOPMENT.md)，本文不重复测试数量或声称全部通过。

修改业务时从功能主责出发，联动 Controller、服务、前端类型/页面、API 文档及必要测试；结构变化增加 Flyway 迁移，不改已执行的历史脚本。真实密钥、业务附件、临时截图和个人任务记录不属于共享源码交付。
