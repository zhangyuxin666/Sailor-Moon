# 四人职责与协作边界

| 成员 | 负责范围 | 主要目录 | 对接边界 |
|---|---|---|---|
| A：产品与前端 | 页面、交互、前端状态和业务联调 | `web/` | 只调用 Spring Boot 公网 API |
| B：AI、Agent 与 RAG | 意图解析、策划、复盘、问答、向量检索与评测 | `ai_service/`、`tests_ai/` | 只输出结构化决策或文本，不直接产生业务副作用 |
| C：Spring 业务后端 | 认证、组织、班级、作业、活动、表单、文件、审计和权限 | `src/main/java/`、`src/test/java/` | 向前端提供 API；向 AI 提供最小可信上下文 |
| D：基础设施与集成 | PostgreSQL/pgvector、Flyway、任务调度、QQ Gateway、Compose、备份与监控 | `src/main/resources/db/`、`qq-gateway/`、`compose*.yml`、`scripts/` | 确保内部服务隔离、任务可恢复、数据可迁移 |

合并顺序：先冻结 API 和 Flyway 结构，再完成 Spring 业务与 AI 内部契约，然后联调 QQ 和前端。跨服务字段变更必须同步更新 `docs/API.md` 和环境变量示例。
