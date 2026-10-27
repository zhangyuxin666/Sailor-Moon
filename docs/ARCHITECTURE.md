# 当前系统架构与演进边界

本文件描述源码和部署配置中的当前结构、服务边界与已知限制。接口契约见 [API.md](API.md)，部署操作见 [DEPLOYMENT.md](DEPLOYMENT.md) 和 [集群说明](TOMCAT_CLUSTER.md)。详细设计见 [DESIGN.md](DESIGN.md)。

## 1. 业务入口与服务边界

- 浏览器只访问 Spring Boot 公网业务 API，React 构建文件同源托管。Spring 负责会话、权限、组织/班级、事务、持久化、工具副作用和审计。
- FastAPI 位于 `ai_service/`，只提供内部 AI、嵌入和 RAG 检索；使用 `X-Internal-Token`。它不鉴权浏览器账号，不创建业务实体，也不发送 QQ。
- Node.js QQ Gateway 负责 QQ WebSocket 和群消息 HTTP 传输；使用 `X-Gateway-Token` 与 `Idempotency-Key`。业务归属和权限由 Spring 校验。
- PostgreSQL 16 + pgvector 是唯一运行时数据库。Flyway `src/main/resources/db/migration/` 管理结构；SQLite 仅保留为迁移源。
- 文件在 `/app/data/uploads`；开发/集群模式绑定宿主目录，生产模式使用 `uploads_data` 命名卷。
- `AI_INTERNAL_TOKEN` 与 `QQ_GATEWAY_TOKEN` 为不同内部令牌。文档、测试证据不得复制真实密钥或完整联系方式。

## 2. 当前三种部署配置

### 默认开发集群：compose.cluster.yml

```text
浏览器 / React
  -> Nginx :18080
       -> Tomcat app1 :8080 -> Spring Boot WAR
       -> Tomcat app2 :8080 -> 同一份 Spring Boot WAR
            -> 共享 PostgreSQL + pgvector
            -> FastAPI AI
            -> 共享 data/uploads 宿主挂载目录
```

同一 WAR 含前端静态产物和业务 API。会话与任务存数据库；后台任务通过数据库抢占。Nginx upstream 包含两个节点和故障转移配置。

集群可通过 qq profile 启动一个 QQ Gateway；两个 Tomcat 共用它，入站回调经 Nginx 分配到任一节点。网关使用独立持久卷保存投递意图和结果，不能扩容为两份机器人长连接。

### 单实例开发：compose.yml

浏览器 -> 单个外部 Tomcat/Spring Boot API（默认 :8080）；API 访问 PostgreSQL、FastAPI，并可选择启动 QQ Gateway。开发文件使用宿主 `data/uploads`。具体参数见 README。

### 生产部署：compose.prod.yml

浏览器 -> Caddy（HTTPS）-> 单个 Tomcat/Spring Boot API -> PostgreSQL、FastAPI、QQ Gateway。数据库和附件使用命名卷，内部服务不直接发布公网端口。

备份、空目标恢复及持久数据范围见 [部署说明](DEPLOYMENT.md)；脚本显式选择 Compose 项目并核对实际挂载，实际恢复结果需要独立环境验收。

## 3. 构建和代码目录

- `web/`：React 18 + TypeScript + Vite，产物 `web/dist` 由 Maven 复制到 WAR 的静态资源。
- `pom.xml`：Java 21 编译目标、Spring Boot 3、WAR 打包；`Dockerfile` 使用外部 Tomcat 10.1 + JDK 21 运行 `ROOT.war`。
- `src/main/java/.../api`：浏览器业务接口与内部 QQ 入口；`security`：会话与安全过滤；`service`：业务与调度；`integration`：AI/QQ 客户端。
- `orchestration/`：统一协议、状态、固定编排和执行日志。
- `ai_service/`、`qq-gateway/`：分别为 AI 服务与消息网关。
- `docs/DESIGN.md` 说明模块、权限、关键时序、事务和恢复设计，并保留视觉规范；数据库关系与 V1–V8 见 `docs/DATABASE.md`。

角色按 [TEAM.md](TEAM.md) 分工，不能把全部 Java 业务或全部前端页面视为一个角色的工作。

## 4. 当前业务流和未闭合的位置

1. 管理者选择班级并生成草案，AI 返回结构化建议，允许人工修改。
2. 发布由 Spring 执行。分析时生成完整执行建议，人工确认后保存快照；后台复用确认字段，不再重新规划。缺信息进入等待，管理者通过详情页补充并继续，失败可重试。
3. Worker 执行固定流程：规划 -> 表单 -> 任务 -> 日历 -> 提醒 -> QQ 交付。
4. `agent_runs` 与 `steps` 记录业务状态；`background_jobs` 负责排队和执行。两套状态分别维护。
5. 报名者填写公开表单；创建者可按题目查看完整回答和历史字段。参与者从总览或任务页完成自己的作业与活动任务，不读取私有报名统计。
6. 复盘从数据库取得聚合快照并保存正文、版本与生成信息；生成不结束活动，创建者另行确认结束。

负责人候选及正式任务已使用账号 ID；正式规划携带组织 ID。已有任务账号关系保留，缺少明确身份的旧未完成方案需要重新确认。班级 QQ 群由管理者显式选择，入站查询和发送均复查组织授权。

页面已区分队列与工作流状态，按真实步骤状态显示尝试次数和安全错误；会话过期在原页面重新登录，保留未提交输入。

已接入活动取消、改期、报名规则及任务管理。后台执行使用独立心跳、每次领取凭证和版本保护；外部投递使用数据库独立事务及网关持久日志，未知结果停止自动重发并提供核对。备份恢复和完整项目交付仍需进一步验收。

## 5. 演进原则

- 保留 Java 业务入口、内部 FastAPI、单实例 QQ Gateway 和 PostgreSQL 的边界，在现有架构上修复与增强。
- 人工确认后的结构化数据作为执行依据；模型只提供建议和归纳。
- 组织、账号、权限与业务事实由服务端确定，所有私有知识检索必须传组织范围。
- 取消停止后续步骤；已经完成或在途的外部动作需要真实记录，不能宣称撤回。
- 外部投递先明确实际保证、未知结果的核对和重试边界，不承诺未经验证的绝对只发送一次。
- 数据库结构升级通过新迁移；所有阶段同步必要测试、API、前端类型和文档。


## 活动管理的一致性边界

报名、任务修改、改期、取消和执行步骤统一先锁活动，再访问运行、报名表或任务。报名在事务中检查身份与有效人数，配合唯一索引避免并发超额与重复。管理操作使用请求标识重放与资源版本校验，更新、审计、按账号站内通知及持久化变更队列共同提交。

编排仍不持有整条流程的长事务；每一步通过 ActivityGate 加锁执行。涉及外部调用的一步可能持锁等待该次调用结束，因此取消在步骤边界生效，并保留在途事实。BackgroundJobService 与 AgentRunService 拒绝以迟到的普通完成回写覆盖取消状态；租约版本和每次领取令牌在步骤前后校验，回收与步骤共享锁序，过期执行者不能提交数据库业务结果。

RegistrationService 负责动态字段、容量、回执和取消；TaskManagementService 负责依赖、成员身份、版本与负载建议；ActivityManagementService 负责生命周期和日历/提醒协调。ChangeDeliveryService 消费持久化的 activity_changes，外部通知失败可单独重试，无须重复修改业务实体。报名的个人管理内容不广播到群。

Spring 事务服务可能是代理对象，依赖的配置与 JSON 转换通过方法访问，不能直接读取另一服务实例字段；报名请求重放已有代理调用回归。


## 可靠执行与外部边界

JobScheduler 的业务执行与独立心跳分开；JobLease 将一次领取绑定到当前线程，清理后不得泄漏给下一任务。BackgroundJobService 只允许当前有效 owner/版本完成或失败，超时回收增加版本并同步 agent_runs。每步在 ActivityGate 事务中同时保存业务记录与 ExecutionJournal，成功步骤可复用；进程在插入后、提交前终止会整体回滚。

QQ 不能加入业务数据库事务。OutboundStore / DeliveryLogService 用 REQUIRES_NEW 保存发送意图和投递事实，不随外层步骤回滚丢失。成功外发后、步骤提交前崩溃时，新 Worker 读取持久结果，补齐步骤记录，不再发消息。失败和未知各有明确状态，只有网关证实未发送才自动重试；未知结果通过授权核对入口恢复。

Node 网关的 PersistentDeliveryJournal 在单个网关进程内合并并发请求，使用摘要文件、原子替换和 fsync 保留意图/成功证据；发送中进程退出后保留 unknown。网关和 PostgreSQL 的持久数据均须保留；不覆盖平台/SDK 内部重试、磁盘或整个持久卷丢失等情况。入站消息在数据库按 message_id 去重，机器人回复也用网关日志；入站业务已提交但回复尚未建立日志时进程退出，仍可能无回复，应人工重新查询，不能承诺跨系统原子交付。


## 知识范围与可信复盘

活动班级决定正式规划组织，账号组织决定资料维护范围，AI服务不会采纳用户自报组织来扩权。KnowledgeService 负责资料权限、去重、版本和正文/向量原子更新；RagStore 在 SQL 同时限制组织/公共资料及 embedding_profile，检索异常以 unavailable 区分无依据。历史未知向量保留并要求显式重索引，避免混合不同向量空间。

AI生成与检索证据分别产生：AiEngine 标识 model/offline 与安全降级原因，RagStore 产生资料ID/来源/版本/摘录，模型提供的 generation 不作为可信证据。确认草案保留证据快照，失效资料不会进入新检索，但既有引用不被悄悄改写。离线日期留空，正式执行先等待人工补充。

RecapStore 在活动锁内保存统计快照和递增版本；RecapService 只传白名单聚合到AI，网络调用不占用活动事务。AI服务再做聚合白名单校验且不注入RAG，降低个人信息与知识文本进入复盘提示的风险。正文只作文字建议，统计展示和完成率由保存的数据库快照确定。请求重放复用同版，失败或超时保留状态，迟到生成不覆盖已结束的生成记录。活动结束是另一个有权限、版本和请求标识的事务。

## 参与记录、导出与业务模板

ParticipationService 复用 ActivityGate 的活动锁协调开放设置、签到、反馈与结束。activity_participants 是追加式成员快照，签到与反馈按活动/账号唯一，独立请求记录支持重放；当前成员资格决定操作权限，历史快照决定到场统计分母。`ready` 开放保存可追加成员，`finished` 不再扩充。RecapStore 保存到场与评分聚合，RecapService 和 AI 服务分别做白名单过滤，反馈原文仅保留在授权业务查询中。

ExportService 在授权和组织校验后显式查询必要列，CsvExport 统一输出 UTF-8 BOM、RFC4180 及公式防护；ExportController 固定下载文件名并禁用缓存。浏览器使用同源会话下载，不建立公开导出链接，不序列化数据库内部字段。

TemplateService 将可复用蓝图与活动执行数据分开存储；历史来源只抽取脱敏结构，本组织模板按创建者及版本维护，内置模板只读。应用在事务内按账号/请求标识去重，只写新的 agent_drafts，继续由既有确认发布链路产生副作用。`/business-templates` 承载业务 API，`/templates` 由 React 承载，来源选择通过受限 `/business-templates/sources` 获取。
