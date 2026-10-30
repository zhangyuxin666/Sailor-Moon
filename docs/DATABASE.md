# 数据库与迁移脚本说明

当前唯一运行时数据库为 PostgreSQL 16 + pgvector，连接和 Flyway 配置见 [application.yml](../src/main/resources/application.yml)。SQLite 仅作为历史迁移来源。服务端使用 JdbcTemplate，模型不能直接写业务数据。

## 1. 结构初始化与版本管理

Spring 启动时由 Flyway 按版本执行 `src/main/resources/db/migration/`，历史记录在 `flyway_schema_history`；当前配置启用迁移，基线版本为 0。首次需能创建 `vector` 扩展，使用仓库 pgvector 镜像可提供该扩展。数据库账号权限和目标数据库须事先就绪。

不要手工逐个重复执行增量 SQL，也不要修改已在团队或部署环境执行过的迁移来修复字段。部分后续语句没有重复执行保护，应由 Flyway 管理；新增结构使用新版本。升级前备份数据，失败时保留原始错误与迁移记录，不能随意删除历史或清库。

## 2. V1–V8 迁移索引

1. [V1__business_schema.sql](../src/main/resources/db/migration/V1__business_schema.sql)：建立业务基础表、组织/班级/账号、作业/文件、活动/任务/表单/提醒、消息/QQ、审计、幂等及 `rag_documents`；启用 `vector`，向量为 1536 维，使用 HNSW 余弦索引。
2. [V2__agent_reliability.sql](../src/main/resources/db/migration/V2__agent_reliability.sql)：队列重试/心跳字段、步骤输入输出与幂等索引、`agent_runs`、知识内容哈希及范围内去重。
3. [V3__run_protocol.sql](../src/main/resources/db/migration/V3__run_protocol.sql)：统一运行状态，增加 context/resolution/cancelled 字段，并迁移旧运行状态。
4. [V4__run_input.sql](../src/main/resources/db/migration/V4__run_input.sql)：为等待补充的运行保存 `input_json`。
5. [V5__activity_management.sql](../src/main/resources/db/migration/V5__activity_management.sql)：容量/截止/关闭、报名状态/身份/回执、活动与任务版本、依赖、按账号消息和持久变更队列。历史报名规范化身份的回填保留已有数据。
6. [V6__reliable_execution.sql](../src/main/resources/db/migration/V6__reliable_execution.sql)：Worker/租约版本/期限、在途旧任务恢复边界、`outbound_deliveries` 及投递逻辑键和错误字段。
7. [V7__knowledge_and_recaps.sql](../src/main/resources/db/migration/V7__knowledge_and_recaps.sql)：资料版本、时间和 embedding_profile；建立带请求标识、版本、统计快照、正文与生成状态的 `activity_recaps`。旧向量标记 `legacy_unknown`，不会默认当作新向量来源。
8. [V8__participation_and_templates.sql](../src/main/resources/db/migration/V8__participation_and_templates.sql)：参与设置、追加式成员快照、签到、反馈、参与操作重放记录、业务模板及应用去重记录。

## 3. 核心实体关系

- **组织与身份**：`accounts` 经 `organization_members` 归属组织，经 `class_members` 进入班级；`class_organizations` 将班级与组织关联。`auth_sessions` 与 `account_security` 保存会话和账号安全状态。
- **作业**：`todos`、`todo_assignees`、`todo_reminders`、`classroom_deliveries` 保存待办、成员提交和投递；`file_assets` 保存附件元数据，文件本体在上传目录/卷。
- **活动**：`activities` 与 `activity_classes`、`activity_workflows`、`activity_channels` 关联班级、流程和群渠道；`tasks` / `activity_task_assignees` / `task_dependencies` 保存任务身份与依赖。
- **报名与提醒**：`forms → registrations` 保存动态题目和完整历史回答，`reminders` 保存活动提醒；取消凭证只存哈希。`activity_changes` 保存带版本管理的业务变更和通知状态。
- **编排与投递**：`agent_drafts`、`agent_runs`、`background_jobs`、`steps`、`idempotency_keys` 分别表示草案、业务运行、执行队列和已提交步骤；`outbound_deliveries`、`delivery_events` 等保留外发证据。
- **知识与复盘**：`rag_documents.organization_id IS NULL` 表示公共知识；资料包含内容、向量、版本和来源。`activity_recaps` 保存一次生成的统计快照及结果，不能据正文改写业务事实。
- **参与与模板**：`activity_participants` 是活动/账号复合主键的快照，签到与反馈引用该快照；`business_templates` 按组织存蓝图，`template_applications` 按账号/请求标识记录已产生的草案。

这些是逻辑关系，不代表每条均有数据库外键。V1 中多处归属由业务服务验证，新增迁移的实际外键与 CHECK 以 SQL 为准，不能只靠字段名推断删除级联。

## 4. 约束、隔离与并发

活动变更先通过 `ActivityGate` 锁活动。有效报名身份使用部分唯一索引，报名请求键防止重放重复；任务按活动/plan_key 约束。依赖防环和当前成员资格等规则由服务层补充。

业务运行与队列状态不同：`agent_runs` 是 pending/running/waiting_input/succeeded/failed/cancelled，队列表关心 queued/running/succeeded/failed。租约期限和领取版本用于拒绝过期执行者提交，步骤唯一键支持已完成结果复用。

参与记录按活动/账号唯一，评分 CHECK 限 1–5，反馈版本防止静默覆盖；快照保留历史，当前成员权限需实时复查。模板应用、复盘生成和参与提交各有自己的请求重放约束，不应互换幂等作用域。

RAG SQL 过滤组织/公共资料及 embedding_profile；仅配置相同维度不代表向量空间兼容。时间字段存在历史 VARCHAR 和新增 TIMESTAMPTZ，客户端新写入使用明确时区；旧无偏移时间按系统 TIMEZONE 解释。

## 5. 运维查看与历史导入

可在正确数据库中查看迁移版本，例如：

```sql
SELECT installed_rank, version, description, success
FROM flyway_schema_history ORDER BY installed_rank;
```

使用 [scripts/migrate_sqlite_to_postgres.py](../scripts/migrate_sqlite_to_postgres.py) 前，先启动 Java 服务完成目标建表。脚本按共有列非覆盖复制并使用 `ON CONFLICT DO NOTHING`，不会自动修复历史账号、组织和向量语义；保留源库，核对记录和旧上传文件后再归档。命令示例见 README。

备份和恢复须一起保存数据库、附件以及 QQ 网关持久日志；镜像和 SQL 结构不能代替这些数据。[backup.ps1](../scripts/backup.ps1) 与 [restore.ps1](../scripts/restore.ps1) 要求显式项目名并先停止除 PostgreSQL 外的所有服务；恢复只接受新隔离项目的空数据库和空附件/日志存储，不先运行 Flyway，也不覆盖已有数据。完整命令及校验边界见 [DEPLOYMENT.md](DEPLOYMENT.md)。恢复到独立环境后需实际核对数据、文件下载、组织权限和投递状态，本文件不表示恢复已经验收完成。

## 数据复制与读写分离

启用 `compose.replication.yml` 后，`postgres` 是唯一写入主库，`postgres-replica` 是 PostgreSQL 16 物理流复制从库，分别使用 `postgres_data` 和 `postgres_replica_data`。`activity_replicator` 仅承担复制；`activity_reader` 仅有 public 表的 SELECT 权限，后续主账号创建的表也自动授予 SELECT。Flyway 只连接主库。复制槽限制 WAL 保留为 1GB，过期从库需要以新空卷重建，不能覆盖主库卷。

应用仅对审查后的 `Db.readList` 路径使用从库；以主库 WAL 位置检查从库同步进度，落后/失效则回主库。事务、权限、报名名额、复盘快照和一致性导出仍在主库完成。启动、权限验证、复制状态及备份恢复注意事项见 [READ_WRITE_SPLIT.md](READ_WRITE_SPLIT.md)。
