# 活动管家开发规范

## 技术栈

| 层 | 技术 |
|---|---|
| 业务后端 | Java 21、Spring Boot 3、Spring Security、JdbcTemplate、Flyway |
| AI/Agent/RAG | Python 3.12、FastAPI、Pydantic 2、psycopg、pgvector |
| QQ | Node.js 22、QQ Bot SDK |
| 前端 | React 18、TypeScript、Vite、React Router |
| 数据库 | PostgreSQL 16 + pgvector（唯一数据库） |

SQLite 只作为旧数据迁移源，不再支持作为运行时数据库。

## 模块边界

```text
src/main/java/.../api          公网业务路由和内部 QQ 入站路由
src/main/java/.../service      业务规则、任务和调度
src/main/java/.../security     Cookie 会话与 Spring Security
src/main/java/.../integration  AI 和 QQ Gateway HTTP 客户端
src/main/resources/db          Flyway 迁移
ai_service                     AI、Agent、RAG，仅内部接口
qq-gateway                     QQ 长连接与消息传输
web                           React 单页应用（Vite 构建，dist 由 Spring Boot 托管）
```

规则：

1. 登录、权限、事务、业务状态和副作用属于 Spring Boot。
2. FastAPI 不创建业务实体、不鉴权浏览器用户、不直接发送 QQ 消息；知识文档向量也由 Spring 写库。
3. Node Gateway 不判断业务权限，只验证内部令牌并传递消息。
4. 数据库事实不能由模型生成；Spring 只向 AI 传递完成任务所需的最小上下文。
5. 所有表结构变更通过 Flyway；禁止服务启动时临时建业务表。
6. RAG 查询必须带组织过滤，全局资料的 `organization_id` 为 `NULL`。

## 本地命令

```powershell
Copy-Item .env.example .env
docker compose up -d postgres ai

# JDK 21
mvn test
mvn spring-boot:run

# Python
.\.venv\Scripts\python.exe -m pytest tests_ai -q

# Node/Compose 静态检查
node --check qq-gateway/server.mjs
npm --prefix qq-gateway test
docker compose config --quiet

# 前端（React + TypeScript，目录 web/）
cd web
npm ci
npm run dev      # 开发：Vite 代理到 :8080，热更新
npm run build    # 生产构建：产出 web/dist
```

全栈运行：

```powershell
docker compose up -d --build
docker compose --profile qq up -d --build
```

访问地址：Spring Boot `http://127.0.0.1:8080`，AI 健康检查 `http://127.0.0.1:8000/health/ready`。

## API 与安全

- 浏览器身份只从 HttpOnly、SameSite=Strict 的 Cookie 读取。
- 写请求进行 Origin 校验；生产必须启用 HTTPS 和安全 Cookie。
- 内部 AI/QQ 接口使用不同的高熵令牌。
- 参数校验失败返回 `422`，业务错误 `400`，未登录/越权 `403`，资源不存在 `404`。
- 上传文件校验大小、扩展名、用户配额和访问归属。
- 关键管理操作写入 `audit_logs`。
- 密码格式继续使用 PBKDF2-HMAC-SHA256 310,000 次，以兼容旧数据。

## 任务、幂等和恢复

- Spring 调度器从 PostgreSQL `background_jobs` 使用 `FOR UPDATE SKIP LOCKED` 抢占任务。
- Agent Run 明确记录 `planning → execution → delivery`，步骤保存稳定幂等键和结构化输入输出。
- 表单、消息、日历和 QQ 投递使用稳定幂等键。
- 仅对尚未达到最大尝试次数的后台任务重试。
- 到期提醒先入持久化任务队列，投递前再次检查 `scheduled` 状态。
- 外部依赖失败必须记录错误，不得伪造成功状态。
- AI 与 QQ 调用仅对连接错误、5xx 和 429 做指数退避；4xx 不重试。
- QQ Gateway 使用 `Idempotency-Key` 合并同进程内重复投递；Spring 数据库幂等记录是最终业务依据。

## 测试与提交门槛

```powershell
mvn test
.\.venv\Scripts\python.exe -m pytest tests_ai -q
node --check qq-gateway/server.mjs
npm --prefix qq-gateway test
docker compose config --quiet
git diff --check
```

涉及数据库、跨服务调用或部署的修改还必须在 Docker 全栈上跑一次 `/health/ready` 和登录到活动创建的冒烟流程。测试不得调用真实 QQ 或生产模型。

提交前同步规则：API 改动更新 `docs/API.md`；环境变量更新 `.env.example`；数据库改动增加 Flyway；部署改动更新 `README.md`。
