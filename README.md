# 活动管家（Activity Assistant）

面向班级和社团的活动协作平台：管理者用自然语言描述事务，系统生成可编辑草案，并在确认后完成作业收集、活动报名、任务分工、QQ 通知、日历提醒和复盘。

## 当前架构

```text
浏览器 ──> Caddy / Spring Boot :8080 ──> PostgreSQL 16 + pgvector
                         │
                         ├──> FastAPI AI :8000 ──> DeepSeek/OpenAI-compatible API
                         │                  └──> pgvector RAG
                         │
QQ 群 <──> Node.js QQ Gateway :3000 <──────┘
```

- **Spring Boot**：唯一公网业务入口；负责 React 前端托管、Spring Security、账号/组织/班级、作业、活动、表单、文件、审计、持久化任务队列和定时提醒。
- **FastAPI**：仅提供带内部令牌的 AI、Agent 推理和 RAG 接口，不承载浏览器业务 API，也不产生业务副作用。
- **Node.js QQ Gateway**：维持 QQ WebSocket 连接，接收入站事件并提供内部群消息发送接口；业务判断仍在 Spring Boot。
- **PostgreSQL + pgvector**：唯一数据库。Flyway 创建业务表和 `rag_documents` 向量表。
- **React 前端**：Vite + React + TypeScript 单页应用，源码位于 `web/`，构建产物 `web/dist` 由 Spring Boot 打包托管（同源，原接口路径不变）。

服务间调用使用 `AI_INTERNAL_TOKEN` 和 `QQ_GATEWAY_TOKEN`。模型未配置时，AI 服务使用确定性的离线规则；未配置嵌入模型时，RAG 使用本地哈希向量作为开发降级方案。

## 快速启动

需要 Docker Desktop。复制配置并启动：

```powershell
Copy-Item .env.example .env
docker compose up -d --build
```

访问 <http://127.0.0.1:8080/>。首次访问会要求创建管理者、组织和登录密码。

Windows 也可以双击 `start.bat`，或执行：

```powershell
start.bat -NoBrowser
start.bat -Port 8081
start.bat -WithQq
```

默认 Compose 启动 PostgreSQL、AI 和 Spring Boot。QQ Gateway 使用可选 profile；在 `.env` 填写 `QQ_BOT_APP_ID`、`QQ_BOT_APP_SECRET` 后，可以运行：

```powershell
docker compose --profile qq up -d --build
```

常用检查：

```powershell
docker compose ps
docker compose logs -f api ai
Invoke-RestMethod http://127.0.0.1:8080/health/ready
Invoke-RestMethod http://127.0.0.1:8000/health/ready
```

## 本地开发

业务后端要求 JDK 21：

```powershell
mvn test
mvn spring-boot:run
```

AI 服务要求 Python 3.11+：

```powershell
python -m venv .venv
.\.venv\Scripts\Activate.ps1
python -m pip install -r requirements.txt
python -m uvicorn ai_service.main:app --reload --port 8000
python -m pytest tests_ai -q
```

Node Gateway：

```powershell
Set-Location qq-gateway
npm ci
npm test
npm run qq-gateway
```

Spring Boot 本机运行时仍需要 PostgreSQL 和 AI 服务。可只启动依赖：

```powershell
docker compose up -d postgres ai
$env:SPRING_DATASOURCE_URL="jdbc:postgresql://127.0.0.1:55432/activity"
mvn spring-boot:run
```

## AI 与 RAG 内部接口

以下接口只供 Spring Boot 或受信任的运维任务调用，必须携带 `X-Internal-Token`：

- `POST /internal/ai/plan`：生成结构化活动方案。
- `POST /internal/ai/analyze-work-request`：识别作业、通知、问卷或复杂活动并生成草案。
- `POST /internal/ai/recap`：根据数据库提供的真实统计生成复盘。
- `POST /internal/ai/reply`：结合活动上下文和 RAG 回答 QQ 群问题。
- `POST /internal/ai/embed`：生成知识文档向量，仅返回计算结果。
- `POST /internal/rag/search`：按组织隔离执行 pgvector 检索。

知识文档写入统一走 Spring Boot 的 `POST /knowledge/documents`；Spring 校验当前组织和管理者权限、调用 AI 生成向量，然后由 Spring 写入 PostgreSQL。FastAPI 不写业务数据。

示例：

```powershell
$session = New-Object Microsoft.PowerShell.Commands.WebRequestSession
Invoke-RestMethod http://127.0.0.1:8080/auth/login -Method Post -WebSession $session -ContentType application/json -Body '{"username":"manager","password":"your-password"}'
$body = @{source="安全手册"; content="户外活动必须准备应急联系人"; metadata=@{kind="policy"}} | ConvertTo-Json
Invoke-RestMethod http://127.0.0.1:8080/knowledge/documents -Method Post -WebSession $session -ContentType application/json -Body $body
```

生产环境应配置真正的嵌入服务（`EMBEDDING_BASE_URL`、`EMBEDDING_API_KEY`），并确保维度与迁移中的 `vector(1536)` 一致。

## 从旧 SQLite 迁移

旧的 `activity.db` 不会被删除。先启动新服务让 Flyway 建表，再执行一次非覆盖式迁移：

```powershell
python scripts/migrate_sqlite_to_postgres.py activity.db postgresql://activity:activity@127.0.0.1:55432/activity
```

脚本使用 `ON CONFLICT DO NOTHING`，不会覆盖 PostgreSQL 中已存在的记录。确认数据无误后再自行归档 SQLite 文件。
本地 Compose 将原有的 `data/uploads` 直接挂载给 Spring Boot，因此旧文件的 `storage_key` 仍可继续读取；生产命名卷迁移需另外复制该目录内容。

## 生产部署

```powershell
Copy-Item .env.production.example .env.production
docker compose --env-file .env.production -f compose.prod.yml up -d --build
```

Caddy 只反向代理 Spring Boot。FastAPI、QQ Gateway 和 PostgreSQL 均不直接暴露公网端口。备份命令：

```powershell
powershell -File scripts/backup.ps1
```

## 目录结构

```text
src/main/java/                 Spring Boot 业务后端
src/main/resources/db/         Flyway PostgreSQL/pgvector 迁移
src/test/java/                 Java 单元测试
ai_service/                    FastAPI AI、Agent、RAG
tests_ai/                      AI 服务测试
qq-gateway/                    Node.js QQ Gateway
web/                          React + TypeScript + Vite 前端（构建产物 dist 由 Spring Boot 托管）
docs/DESIGN.md                 前端视觉令牌与组件规范
scripts/                       启动、备份和 SQLite 迁移工具
compose.yml                    本地四服务编排
compose.prod.yml               Caddy + 生产编排
```

业务 API 见 [docs/API.md](docs/API.md)，开发约定见 [docs/DEVELOPMENT.md](docs/DEVELOPMENT.md)。
