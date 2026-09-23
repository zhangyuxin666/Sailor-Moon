# 活动管家 API 契约（v2）

浏览器只调用 Spring Boot（默认 `:8080`）。登录成功后身份来自 HttpOnly 的 `activity_session` Cookie；服务端不信任请求体或查询参数中的 `user_id`。错误统一返回：

```json
{"detail":"用户可读的错误说明"}
```

## 认证与账号

- `GET /auth/status`：初始化状态和当前账号。
- `POST /auth/bootstrap`：创建首个管理者及组织。
- `POST /auth/login`、`POST /auth/logout`。
- `POST /auth/change-password`。
- `POST /accounts/{account_id}/reset-password`：管理者重置本组织参与者密码。
- `POST /privacy/consent`、`GET /account/export`、`DELETE /account`。
- `GET /audit-logs?limit=100`：仅管理者。

## 班级、成员与作业

- `POST /classes`。
- `GET|POST /classes/{class_id}/members`。
- `POST /classes/{class_id}/members/import-excel`。
- `GET /classes/member-template.xlsx`。
- `GET /portal/dashboard`。
- `POST /classes/{class_id}/todos`。
- `GET /todos/{todo_id}`。
- `POST /todos/{todo_id}/submit`：multipart，字段 `note`、可选 `file`。
- `POST /todos/{todo_id}/remind`。
- `POST /todos/{todo_id}/reminders`。
- `GET /todos/{todo_id}/submissions/{account_id}/file`。

## Agent 草案

- `POST /agent/drafts`：请求 `{"class_id":"...","text":"..."}`，Spring 调用内部 FastAPI 生成草案。
- `GET /agent/drafts`、`GET /agent/drafts/{id}`。
- `PUT /agent/drafts/{id}`：保存人工修改后的结构化草案。
- `POST /agent/drafts/{id}/publish`：Spring 根据草案执行最小业务流程。

AI 只负责意图、规划和文本生成。创建作业、活动、表单、任务、提醒或 QQ 消息的副作用全部由 Spring Boot 执行。

活动方案包含受限的 `tool_intents`；Spring 按白名单、账号权限和草案工作流执行，不接受模型直接操作数据库或外部平台。

## 活动

`POST /activities` 返回 `202 Accepted`：

```json
{"class_id":"class-id","text":"下周五举办班级分享会","publish_to_qq":true}
```

```json
{"activity_id":"a1b2c3","run_id":"r1b2c3","status":"queued","qq_group_attached":false}
```

- `GET /runs/{run_id}`：查询持久化后台任务。
- `GET /portal/activities`：当前账号可见活动。
- `GET /activities/{activity_id}`：方案、任务、表单、提醒、执行步骤和投递记录。
- `POST /activities/{activity_id}/messages`：手动发送 QQ 通知。
- `GET /activities/{activity_id}/form-stats`。
- `POST /activities/{activity_id}/recap`。
- `POST /reminders/{reminder_id}/cancel`。
- `PATCH /tasks/{task_id}`：`pending` 或 `done`。
- `GET /activities/{activity_id}/calendar.ics`。

## 公开报名

- `GET /forms/{form_id}`：原生报名页。
- `GET /public/forms/{form_id}`：报名页元数据。
- `POST /forms/{form_id}/registrations`：请求字段 `name`、`contact`、可选 `extra`。

## QQ Gateway 内部接口

Node.js 调用 Spring Boot：

- `POST /internal/qq/events`，请求头 `X-Gateway-Token`。

Spring Boot 调用 Node.js：

- `POST /internal/send-group`，请求头 `X-Gateway-Token` 和稳定的 `Idempotency-Key`。

## AI/RAG 内部接口

FastAPI 仅在内部网络监听，所有写入和推理接口要求 `X-Internal-Token`：

- `POST /internal/ai/plan`
- `POST /internal/ai/analyze-work-request`
- `POST /internal/ai/recap`
- `POST /internal/ai/reply`
- `POST /internal/ai/embed`
- `POST /internal/rag/search`

RAG 查询携带 `organization_id`，只返回本组织和全局文档。FastAPI 不提供文档写入路由。

## 知识库业务接口

- `POST /knowledge/documents`：仅管理者；Spring 调用 AI 嵌入接口并写入 pgvector，相同组织、来源和内容重复提交返回 `idempotent=true`。
- `POST /knowledge/search`：登录用户；Spring 注入当前组织 ID 后调用内部 RAG 检索。

## Agent 运行阶段

`GET /runs/{run_id}` 除后台任务状态外还返回 `agent_run`：

- `phase=planning`：模型生成结构化方案和工具意图。
- `phase=execution`：Spring 按白名单执行落库动作。
- `phase=delivery`：Spring 调用 QQ Gateway 等交付通道。

跨服务重试保持同一幂等键。后台任务使用 `next_attempt_at` 指数退避，QQ Gateway 在进程内合并相同 `Idempotency-Key` 的并发或重复请求。

## 状态码

- `400`：业务参数或当前状态不允许。
- `401`：内部服务令牌无效。
- `403`：未登录或无权访问。
- `404`：资源不存在。
- `409`：唯一性冲突。
- `422`：结构化请求字段校验失败。
- `502`：AI 或 QQ Gateway 暂时不可用。
