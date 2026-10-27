# 活动管家（Activity Assistant）

面向班级和社团的活动协作平台：管理者用自然语言描述事务，系统生成可编辑草案，并在确认后完成作业收集、活动报名、任务分工、QQ 通知、日历提醒和复盘。

活动详情支持报名容量、截止与关闭、取消报名、整体取消和改期；任务支持说明、期限、优先级、前置依赖、待分配及改派。变更记录显示通知状态并支持失败重试，参与者按账号收到站内通知。管理操作及并发边界见 [API 文档](docs/API.md)。

系统边界、部署模式与已知限制见 [架构说明](docs/ARCHITECTURE.md)，团队职责见 [协作分工](docs/TEAM.md)。

## 课程交付资料索引

以下是项目共享资料，不是个人任务清单：

1. [功能分析与需求](docs/REQUIREMENTS.md)：角色、用例、业务规则和验收目标。
2. [接口说明](docs/API.md)：现有浏览器与内部 API、状态、权限和并发契约。
3. [详细设计](docs/DESIGN.md)：模块、关键流程、一致性、安全与界面规范；配合 [架构说明](docs/ARCHITECTURE.md)。
4. [安装部署与镜像交付](docs/DEPLOYMENT.md)：三种部署、环境、WAR/镜像及持久化边界。
5. [操作手册](docs/USER_GUIDE.md)：管理者、参与者和公开报名者的操作流程。
6. [源码说明](docs/SOURCE_GUIDE.md)：代码入口、模块与测试位置。
7. [数据库脚本说明](docs/DATABASE.md)：数据关系、约束、V1–V8 和历史导入。

团队分工见 [TEAM](docs/TEAM.md)，开发约定见 [DEVELOPMENT](docs/DEVELOPMENT.md)。资料描述当前实现与交付方式，具体环境的验收结果另附证据。

## 当前架构

```text
                         ┌──> Tomcat app-1 :8080 ──┐
浏览器 ──> Nginx :18080 ─┤                         ├──> PostgreSQL 16 + pgvector
                         └──> Tomcat app-2 :8080 ──┤
                                                   └──> FastAPI AI :8000
```

- **Nginx**：集群统一入口，使用轮询把请求分给两个 Tomcat；节点失败时自动切换。
- **Tomcat 10.1 × 2**：分别运行同一份活动管家 WAR，包含 React 前端和 Spring Boot API。
- **Spring Boot**：负责 React 前端托管、Spring Security、账号/组织/班级、作业、活动、表单、文件、审计、持久化任务队列和定时提醒。
- **FastAPI**：仅提供带内部令牌的 AI、Agent 推理和 RAG 接口，不承载浏览器业务 API，也不产生业务副作用。
- **Node.js QQ Gateway**：单个可选的 QQ WebSocket 网关，可供单机或双 Tomcat 共用，持久日志跨重启保留。
- **PostgreSQL + pgvector**：唯一数据库。Flyway 创建业务表和 `rag_documents` 向量表。
- **React 前端**：Vite + React + TypeScript 单页应用，源码位于 `web/`，构建产物 `web/dist` 由 Spring Boot 打包托管（同源，原接口路径不变）。

服务间调用使用 `AI_INTERNAL_TOKEN` 和 `QQ_GATEWAY_TOKEN`。模型未配置时，AI 服务使用确定性的离线规则；未配置嵌入模型时，RAG 使用本地哈希向量作为开发降级方案。

## 快速启动

需要 Docker Desktop。复制配置并启动：

```powershell
Copy-Item .env.example .env
start.bat
```

默认启动两个外部 Tomcat 和一个 Nginx，访问 <http://127.0.0.1:18080/>。
首次访问会要求创建管理者、组织和登录密码。完整集群说明见
[docs/TOMCAT_CLUSTER.md](docs/TOMCAT_CLUSTER.md)。

Windows 也可以双击 `start.bat`，或执行：

```powershell
start.bat -NoBrowser
start.bat -Build
start.bat -Single -Port 8080
start.bat -Single -WithQq
```

`start.bat` 默认启动集群并使用已有镜像，不会重复构建。修改源码、`pom.xml`、
前端文件或 Dockerfile 后，使用 `start.bat -Build`。Maven、npm 和 pip 依赖会使用
Docker 持久缓存。`-Single` 选择单实例开发模式；集群也支持 `start.bat -WithQq`，两个 Tomcat 共用一个网关。

直接使用 Compose 启动集群：

```powershell
docker compose -f compose.cluster.yml up -d
```

常用检查：

```powershell
docker compose -f compose.cluster.yml ps
docker compose -f compose.cluster.yml logs -f app1 app2 nginx
Invoke-RestMethod http://127.0.0.1:18080/health/ready
Invoke-RestMethod http://127.0.0.1:18080/cluster/node
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
docker compose --env-file .env.production -p activity-prod -f compose.prod.yml up -d --build
```

Caddy 只反向代理 Spring Boot。FastAPI、QQ Gateway 和 PostgreSQL 均不直接暴露公网端口。备份须先停止所选项目除 PostgreSQL 外的所有服务，显式传入 Compose 文件和真实项目名；恢复只允许新隔离项目的空数据库与空附件/日志卷。可复制的停写、备份和恢复流程见 [部署说明](docs/DEPLOYMENT.md#6-持久化升级与恢复)，默认备份位于仓库外且不归档 `.env`。

统一检查使用 `pwsh -File scripts/check.ps1 -WithE2E`，无 Docker 时传 `-SkipDocker`；运行环境、离线边界和测试项目清理说明见 [开发规范](docs/DEVELOPMENT.md#统一检查入口)。

## 目录结构

```text
src/main/java/                 Spring Boot 业务后端
src/main/resources/db/         Flyway PostgreSQL/pgvector 迁移
src/test/java/                 Java 单元测试
ai_service/                    FastAPI AI、Agent、RAG
tests_ai/                      AI 服务测试
qq-gateway/                    Node.js QQ Gateway
web/                          React + TypeScript + Vite 前端（构建产物 dist 由 Spring Boot 托管）
docs/DESIGN.md                 详细设计、视觉令牌与组件规范
scripts/                       启动、备份和 SQLite 迁移工具
compose.cluster.yml            Nginx + 双外部 Tomcat + PostgreSQL + AI
compose.yml                    单实例开发编排
compose.prod.yml               Caddy + 生产编排
nginx/nginx.conf               集群 upstream、超时和故障转移
docs/TOMCAT_CLUSTER.md         集群搭建与课堂演示说明
```

业务 API 见 [docs/API.md](docs/API.md)，开发约定见 [docs/DEVELOPMENT.md](docs/DEVELOPMENT.md)。


后台任务通过独立心跳续约和领取版本保护，默认租约30秒、单次执行上限600秒、最多3次自动尝试。外部通知失败分为明确未发送和结果未知；未知时停止自动重发，活动详情可核对网关持久记录。qq_delivery_data 卷须保留，不能通过删卷来“解除”未知状态。配置与边界见 [API](docs/API.md#执行租约与投递恢复)。


知识库页面支持组织资料列表、详情、编辑/删除和检索来源；公共资料只读。生成建议显示模型/离线模式、检索状态和来源版本，离线模板不默认猜日期。旧资料向量来源不明时须编辑保存重新索引。

活动详情的复盘保存版本、聚合快照与降级信息，刷新/重登可查历史；只把聚合数据交给模型，整个AI服务断开也有本地兜底。生成复盘和结束活动分别操作，结束会关闭报名与未发送提醒。契约见 [API](docs/API.md#复盘版本与独立结束活动)。

## 签到、导出与业务模板

活动详情可开放签到与反馈并查看到场、缺席和评分分布；成员从“签到与反馈”页（`/participation`）操作本人记录。两项默认关闭，就绪时保存开放设置会追加当前有效成员；结束后只可反馈且不追加成员，反馈须先签到。复盘使用到场和评分聚合，反馈原文不进入模型。

活动详情提供报名和任务 CSV，待办管理提供未交名单 CSV；下载检查当前组织权限，支持中文 BOM、历史答案及公式注入防护。管理者从“业务模板”（`/templates`）选择内置/本组织模板，或从自己的草案/活动提取脱敏结构，再编辑通用内容并应用到班级。应用只创建待确认草案，须补全日期和负责人后自行确认发布。完整契约见 [API 文档](docs/API.md#签到反馈与参与统计第七阶段)。
