# 活动管家 API 契约（v2）

浏览器只调用 Spring Boot（默认 `:8080`）。登录成功后身份来自 HttpOnly 的 `activity_session` Cookie；服务端不信任请求体或查询参数中的 `user_id`。错误统一返回：

```json
{"detail":"用户可读的错误说明"}
```

## 当前能力说明

本文描述已有接口与协议。当前页面已接入等待补充、失败重试、报名管理、任务改派、活动取消与改期。取消在步骤边界生效，并保留已完成/在途操作事实；已接入租约续约、执行凭证保护、有限重试、持久化投递与未知结果核对；保证边界见下文。

## 认证与账号

- `GET /auth/status`：初始化状态和当前账号。
- `POST /auth/bootstrap`：创建首个管理者及组织。
- `POST /auth/login`、`POST /auth/logout`。
- `POST /auth/change-password`：提交 `current_password`、`new_password`。新密码不能与当前密码相同；成功撤销全部登录会话、清除当前 Cookie，返回 `{"status":"ok","reauthenticate":true}`，须使用新密码重新登录。
- `POST /accounts/{account_id}/reset-password`：仅当前组织有管理权限的管理者可重置有效参与者密码；历史多组织账号拒绝重置。成功撤销目标全部会话，并要求目标使用临时密码登录后修改。
- `POST /privacy/consent`、`GET /account/export`、`DELETE /account`。
- `GET /audit-logs?limit=100`：仅管理者。

登录响应及 `GET /auth/status` 的账号包含 `force_password_change`。值大于 0 时，受保护业务 API 返回 `403 {"detail":"请先修改初始密码或重置后的密码","code":"password_change_required"}`；账号只能进行必要身份、改密、退出及公开页面操作。网页统一进入设置页改密，完成后重新登录。

未登录或会话过期的受保护接口返回 `403 {"detail":"登录会话已失效，请重新登录","code":"authentication_required"}`。网页在原页面弹出当前账号的重新登录对话框，保留尚未提交的输入；重新登录成功后手动重试原操作。普通权限拒绝不会触发重新登录。首次认证状态读取失败显示可重试错误。

## 班级、成员与作业

- `POST /classes`：创建班级，默认不绑定 QQ 群。
- `GET /classes/{class_id}/qq-bindings`：管理者查询本组织授权群，返回 `groups`、当前有效的 `group_openid`、`qq_group_bound` 和 `binding_needs_update`。
- `PUT /classes/{class_id}/qq-binding`：请求 `{"group_openid":"授权群ID"}`，传 `null` 解绑；返回 `class_id`、`group_openid`、`qq_group_bound`。越界或失效绑定拒绝，变更写审计。
- `GET|POST /classes/{class_id}/members`。
- `POST /classes/{class_id}/members/import-excel`。
- `GET /classes/member-template.xlsx`。
- `GET /portal/dashboard`：管理者返回班级和待办；参与者另含 `activity_tasks`，只返回分配给本人账号的活动任务及活动标题、状态、时间。
- `POST /classes/{class_id}/todos`。
- `GET /todos/{todo_id}`。
- `POST /todos/{todo_id}/submit`：multipart，字段 `note`、可选 `file`。
- `POST /todos/{todo_id}/remind`。
- `POST /todos/{todo_id}/reminders`。
- `GET /todos/{todo_id}/submissions/{account_id}/file`。

成员 JSON/Excel 导入执行整批校验。已有用户名仅在账号有效、角色为参与者且唯一归属当前组织时复用；跨组织、未归属和历史多组织账号被拒绝，不通过导入自动取得其密码重置权限。

班级通知配置流程：设置页获取机器人绑定码 → 在 QQ 群完成绑定 → 成员管理页刷新授权群列表 → 明确选择并保存。历史无权群会显示失效，活动通知、作业提醒和群内查询均复查当前组织授权。

## Agent 草案

- `POST /agent/drafts`：请求 `{"class_id":"...","text":"..."}`，Spring 调用内部 FastAPI 生成草案。
- `GET /agent/drafts`、`GET /agent/drafts/{id}`。
- `PUT /agent/drafts/{id}`：保存人工修改后的结构化草案。
- `POST /agent/drafts/{id}/publish`：Spring 根据草案执行最小业务流程。

活动草案在分析阶段生成 `execution_plan`，含任务账号 ID、物料和提醒安排。页面可编辑标题、说明、活动时间、问卷题目与工作流，并在发布前查看任务和物料。保存及发布时，顶层确认字段覆盖嵌套计划中的对应值，工作流重新形成受限 `tool_intents`。发布把确认快照写入 `activities.plan_json` 和 `agent_runs.plan_json`，与创建运行处于同一事务；执行时复用该快照，不再次调用 AI 规划。问卷保留题目顺序、标签、类型、必填标记及选项，并确保包含姓名和联系方式。历史活动草案缺完整任务安排且仍启用任务分配时，须重新分析后确认。已发布草案在页面只读，可进入发布结果。历史等待运行若完全没有已确认计划，拒绝用空任务/空题目替代，需重新生成草案发布。

AI 只负责意图、规划和文本生成。创建作业、活动、表单、任务、提醒或 QQ 消息的副作用全部由 Spring Boot 执行。

活动方案包含受限的 `tool_intents`；Spring 按白名单、账号权限和草案工作流执行，不接受模型直接操作数据库或外部平台。

如果 `activity` 或 `survey` 草案仍缺少关键信息，发布会创建活动与运行记录，但不会启动编排；响应中会带 `run_id`、`run_status=waiting_input` 和 `missing_information`。`assignment` 缺截止时间仍要求先补全草案。

## 活动

`POST /activities` 返回 `202 Accepted`：

```json
{"class_id":"class-id","text":"下周五举办班级分享会","publish_to_qq":true}
```

```json
{"activity_id":"a1b2c3","run_id":"r1b2c3","status":"queued","qq_group_attached":false}
```

- `GET /runs/{run_id}`：查询持久化后台任务。
- `POST /runs/{run_id}/input`：为 `waiting_input` 的运行保存补充信息，请求体为 `{"input":{...}}`。
- `POST /runs/{run_id}/resume`：补充信息后重新入队；重复调用处于 `pending/running` 的运行是幂等的。
- `GET /portal/activities`：当前账号可见活动。
- `GET /activities/{activity_id}`：仅创建者可读，返回 `run_id`、方案、任务、表单、提醒、执行步骤和投递记录；步骤含真实 `status`、`phase`、`attempts`、脱敏错误。
- `POST /activities/{activity_id}/messages`：手动发送 QQ 通知。
- `GET /activities/{activity_id}/form-stats`：仅创建者可读，返回 `form_id`、`title`、`fields`、`count`、`registrations`；每条报名含完整 `extra`，页面按题目标签展示，历史未知字段保留字段名。
- `POST /activities/{activity_id}/recap`。
- `POST /reminders/{reminder_id}/cancel`。
- `PATCH /tasks/{task_id}`：`pending` 或 `done`；管理者仅操作自己活动的任务，参与者仅操作通过 `activity_task_assignees.account_id` 分配给本人的任务。参与者从总览或任务页面处理，不读取活动私有详情或报名统计。
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

`POST /internal/ai/plan` 的 `available_assignees` 为 `[{"account_id":"账号ID","display_name":"姓名"}]`，并携带由业务服务确定的 `organization_id`。输出 `tasks` 每项包含 `title`、`account_id` 和展示用 `assignee`；服务端按当前班级有效账号 ID 校验，不能用姓名替代 ID。任务查询返回 `account_id` 与账号当前显示名。

既有 `activity_task_assignees` 关系保持不变。旧缓存方案缺账号 ID 时，只允许复用已经落库且唯一明确的任务账号关系；没有关系、存在歧义或账号已失效时保留原任务为待分配，不按姓名猜测负责人。

## 知识库业务接口

- `POST /knowledge/documents`：仅管理者；Spring 调用 AI 嵌入接口并写入 pgvector，相同组织、来源和内容重复提交返回 `idempotent=true`。
- `POST /knowledge/search`：登录用户；Spring 注入当前组织 ID 后调用内部 RAG 检索。

## Agent 运行阶段

`GET /runs/{run_id}` 返回后台任务状态、`agent_run`，以及编排协议字段 `context`、`result`、`next_actions`、`input`、`plan`；不直接返回 `agent_run` 内部的原始 JSON 列，历史和新错误均转为固定的安全提示。

`agent_run.status` 是业务工作流状态，取值固定为六个：

- `pending`：已创建，等待执行。
- `running`：正在执行。
- `waiting_input`：信息不足，等待用户补充。
- `succeeded`：已完成。
- `failed`：失败，可重试或恢复。
- `cancelled`：已取消，不再执行后续步骤。

`succeeded` 和 `cancelled` 是运行终态。已就绪活动可以取消，但原成功运行保留 `succeeded` 事实，活动本身变为 `cancelled`；等待、待执行、执行中或失败运行随活动进入取消终态。后台完成、失败和步骤提交均受执行凭证保护。

注意区分两套状态：`background_jobs.status` 是执行队列状态（`queued`、`running`、`succeeded`、`failed`），回答“这条任务执行了没有”；`agent_runs.status` 是业务工作流状态，回答“业务走到哪一步”。不要把两者混用。

协议字段：

- `context`：所有智能体共享的调用上下文，含 `user_id`、`organization_id`、`class_id`、`activity_id`、`run_id`、`request_id`，未写入时为 `null`。
- `result`：统一结果，含 `status`、`data`、`citations`、`next_actions`、`error`，未写入时为 `null`。
- `next_actions`：从 `result` 中提取的下一步动作列表，没有结果时为空数组。
- `input`：等待用户补充时保存的结构化输入，来源为 `agent_runs.input_json`，没有输入时为 `null`。

- `plan`：本次运行的已确认执行快照，补充信息保存后同步更新。

等待输入仅接受 `title`、`description`、`event_time`、`form_fields`、`materials`、`tasks`、`remind_minutes_before` 和 `answers`。`answers` 是“缺失问题 → 回答文本”的映射，例如：

```json
{"input":{"event_time":"2026-10-29T07:30:00Z","answers":{"集合地点":"东门"}}}
```

保存时合并输入并更新真实计划和剩余缺口，保持 `waiting_input`。恢复前重新校验缺口和时间，只有补全后才入队。失败运行可直接恢复，显式手动重试重置本次自动重试预算；重复恢复已排队/执行中的运行返回幂等结果，成功与取消状态拒绝恢复。网页定时刷新不会覆盖正在填写的补充内容。

等待与恢复流程：

```text
草案缺信息
  -> agent_runs.status = waiting_input
  -> activities.status = waiting_input
  -> 本次 background_jobs 结束，不执行 AI/表单/任务/提醒/QQ

POST /runs/{run_id}/input
  -> 合并 input_json，更新活动与运行 plan_json、剩余缺口，仍保持 waiting_input

POST /runs/{run_id}/resume
  -> 校验补充完整性，清理旧结果与错误，agent_runs 回到 pending
  -> activities 回到 queued
  -> 原 background_job 重新排队，手动重试 attempts 清零
  -> 已成功方案和步骤继续通过 plan_json、idempotency_keys 和业务表查重复用
```

`phase` 描述当前执行阶段：

- `phase=planning`：读取确认快照；直接调用活动接口且没有快照时才生成方案和工具意图。
- `phase=execution`：Spring 按白名单执行落库动作。
- `phase=delivery`：Spring 调用 QQ Gateway 等交付通道。

跨服务重试保持同一幂等键。后台任务使用 `next_attempt_at` 指数退避，QQ Gateway 在进程内合并相同 `Idempotency-Key` 的并发或重复请求。

## 时间字段

前端 `datetime-local` 使用浏览器本地时间，API 写入使用显式 UTC（Z）ISO 8601；读取 Z 或 offset 时先转换成本地时间再显示，反复保存保持同一时刻。已有无 offset 时间按本地墙上时间显示；后台对无 offset 活动/提醒使用配置的 `TIMEZONE`（默认 Asia/Shanghai），新客户端应始终提交带时区时间。

## 状态码

- `400`：业务参数或当前状态不允许。
- `401`：内部服务令牌无效。
- `403`：未登录或无权访问。
- `404`：资源不存在。
- `409`：唯一性冲突。
- `422`：结构化请求字段校验失败。
- `502`：AI 或 QQ Gateway 暂时不可用。

## 报名规则、回执与取消

- `PUT /activities/{id}/registration-settings`：仅创建者，请求 `request_id`、`expected_version`、`capacity`（整数1–100000或null不限）、`registration_status`（open/closed）、`registration_deadline`（带时区时间或空清除）。容量不能低于当前有效报名数；可以设置过去时间以立即截止。
- `GET /public/forms/{id}` 追加 `version`、`capacity`、`registration_deadline`、`registration_status`、`registration_count`、`remaining`、`accepting_registrations` 和 `closed_reason`。不公开报名者明细。
- `POST /forms/{id}/registrations`：提交 `name`（1–120字）、`contact`（1–255字）、`extra`。新客户端同时提交随机 `request_id` 和 `cancellation_token`（32–100字符的回执凭证），返回 `registration_id`、`status`、`cancellation_token`。未提交请求标识的旧客户端仍可报名，服务器生成回执。相同请求和内容可重试，不占第二个名额；同请求不同内容或凭证返回409。
- 姓名和联系方式经 Unicode NFKC、大小写与联系方式空白标准化后共同识别一份有效报名。并发提交对活动和报名表加事务行锁，再检查有效人数；容量、身份与请求唯一索引共同保护。关闭、截止、活动取消或满额不能新增；已成功请求重放只返回原事实。
- `extra` 的新回答只接受已发布题目的字段名。文字为最多2000字的字符串，选择题必须命中选项，必填不可空白；未知字段、数组、对象与错误类型拒绝，错误指明题目或字段。已有历史扩展答案保留展示，不因新规则删除。
- `POST /forms/{formId}/registrations/{registrationId}/receipt`：请求 `cancellation_token`，读取自己的回执状态，不返回他人信息。
- `POST /forms/{formId}/registrations/{registrationId}/cancel`：同样凭回执取消，重复调用幂等；取消释放名额。凭证只保存哈希，统计接口不返回哈希或凭证。网页支持保存回执、跨设备输入回执和取消，刷新时核对服务器状态。
- `POST /activities/{id}/registrations/{registrationId}/cancel`：创建者可以取消本活动报名，含缺回执的历史报名；提交 `reason` 与 `request_id`，保留历史并写审计。
- 私有统计 `count` 是有效报名数，`registrations` 同时保留 `id`、`status`、`cancelled_at` 和完整答案。历史重复报名不自动合并或删除；仍有效的历史重复记录也阻止再次报名。

## 活动取消、改期与变更通知

- `POST /activities/{id}/cancel`：创建者提交 `request_id`、`reason`（1–1000字）。关闭报名、取消未发送提醒、终止可继续的运行与队列动作；重复取消幂等，已结束活动拒绝取消。
- `POST /runs/{runId}/cancel`：取消非成功的活动运行，对所属活动执行相同规则。成功运行已终止，须使用活动取消入口。
- 每个业务步骤和提醒发送先锁活动并检查取消状态。取消会等待当前步骤结束；取消返回后不开始后续步骤。旧的 finish/fail 不得覆盖取消状态。此策略保留已完成及在途外部动作，不承诺撤回消息。
- `POST /activities/{id}/reschedule`：仅已就绪活动；请求 `request_id`、`expected_version`、`event_time`（未来时间）、`reason`、`deadline_policy`（keep/set/clear），set另含 `registration_deadline`。保留截止时若截止晚于新活动时间则拒绝，须明确调整。
- 改期更新活动与运行计划、日历快照，使旧定时提醒失效并创建/复用新时间提醒。日历保持既有 UID，增加 SEQUENCE；取消后下载日历带 STATUS:CANCELLED。已发送提醒不宣称撤回。
- `GET /activities/{id}/changes`：创建者查看变更、原状态及 `notification_status`（pending/local/sent/failed/cancelled）和安全错误。
- `POST /activities/{id}/changes/{changeId}/retry`：重试失败通知，复用原变更与投递幂等键；已完成通知不重复发送。取消后的旧非取消通知停止。
- 实体修改、审计、站内消息和通知队列入队同事务提交；外部投递由后台执行。没有授权群时保留站内记录，报名规则/单人报名管理不向群公开个人信息。群通知失败可查询、重试；外部接收后响应丢失等未知结果仍有跨进程保证边界。

## 任务属性、依赖、待分配和改派

- `GET /activities/{id}/task-management`：仅创建者，返回任务及有效成员候选；成员含 `account_id`、姓名、账号、学号和当前 `pending_count`，按负载排序，仅作人工确认建议。
- `POST /activities/{id}/tasks`：新建；`PUT /activities/{id}/tasks/{taskId}`：编辑/改派。请求含 `request_id`、`title`、`description`（最多4000字）、`deadline`、`priority`（low/normal/high/urgent）、`account_id`（空表示待分配）、`depends_on`（本活动任务ID数组）。编辑另须 `expected_version`。版本冲突409，格式/循环/跨活动依赖400。
- 任务输出含上述属性、`version`、`unassigned_reason` 和账号关系。负责人下拉框以学号或账号区分同名成员；空/失效模型负责人不按姓名匹配，保留待分配任务。人工选择无效成员会拒绝，保留当前关系。
- 策划任务可以用 `key` 与 `depends_on` 的 key 数组表达依赖；执行后转为正式任务ID关系。每个计划位置有稳定标识，重试不覆盖人工修改或混用人工新增任务。旧任务仅复用唯一明确的关系，歧义要求人工确认。
- 改派时旧关系立即撤销，新关系生效，状态回到待完成，并向新旧负责人分别记录站内通知；原负责人和原状态保存在变更记录。相同请求不重复通知。
- `PATCH /tasks/{taskId}` 保持原请求结构；仅创建者或当前负责人能操作，且受前置依赖与取消状态约束。同状态重试不增加版本和重复审计。
- `GET /notifications`：当前账号自己的站内变更通知，不按姓名识别接收人。

## 数据升级

Flyway V5 增加报名规则/回执、活动与任务版本、任务属性/依赖及持久化变更记录。V1–V4 不变。旧报名保留，规范化重复只给第一条建立唯一身份键，其余保留历史记录并计入有效人数；新请求同时检查这些历史记录。旧任务标为 legacy 来源，不能与人工新任务随意合并。


## 执行租约与投递恢复

- 队列每次领取生成新的随机执行令牌，并增加 lease_version。独立线程续约，默认租约30秒、单次最长600秒、最多3次自动尝试；WORKER_LEASE_SECONDS / WORKER_MAX_SECONDS / WORKER_RECOVERY_MILLIS 可配置。到期回收先获取活动锁，再更新队列和运行状态。资源、权限或参数错误不自动重试。
- 完成、失败、心跳与步骤提交检查令牌/版本/有效期；过期执行者不能继续提交数据库副作用。数据库语句和锁等待受剩余执行期限约束；外部请求使用独立 HTTP 超时。取消仍在步骤边界生效，已经开始的外部调用可能完成。
- GET /runs/{id} 增加 worker_instance、lease_version、lease_until、deadline_at、attempts、heartbeat_at 等诊断字段；不返回内部 locked_by 执行令牌。节点信息用于排查，不表示某个节点独占活动。
- 外部投递先在独立事务提交 outbound_deliveries 意图，状态为 sending/sent/failed/unknown。QQ 网关在调用 SDK 前写入并同步持久日志，成功后同步结果；同键同内容复用成功结果，不同内容409。sending 在重启后按 unknown 处理。
- failed 仅表示明确未发送（例如网关尚未就绪）；连接中断、响应丢失或 SDK 异常可能已被接收，标为 unknown 并停止自动重发。网关有已成功的持久记录时可安全核对；没有证据则保留 unknown，须人工核对群记录，不能把缺失记录当作未发送。
- POST /activities/{id}/deliveries/{deliveryId}/check：仅活动创建者且当前群授权有效。核对网关持久记录及内容摘要，确认 sent 后同步投递、提醒/通知及队列；必要时继续未完成的活动流程，复用已完成动作。无法确认返回 status=unknown，不再次发送。旧记录没有逻辑键时提示人工核对。
- POST /activities/{id}/reminders/{reminderId}/retry：仅创建者且活动未取消。只对 failed 提醒重置有限预算，使用同一逻辑键；scheduled/sent 重复请求幂等；unknown 先核对，cancelled 不重发。
- POST /reminders/{id}/cancel 与发送共享活动锁。尚未发起时取消；如果已在途并最终发送，返回 status=sent，页面明确不能撤回。提醒失败/未知原因和历史记录保留，扫描不会给同一逻辑提醒新增投递日志。
- 手动通知页面保留失败请求标识，遇到相同内容的未知投递时先核对；成功核对后清理对应文本。此机制不承诺 QQ 平台、SDK 内部行为或丢失持久卷后的绝对只发送一次。

### 网关查询与持久卷

GET /internal/deliveries/{encodedKey} 仅接受 X-Gateway-Token，返回 status、payload_hash 和可用的 external_id，不回传群消息正文。QQ_DELIVERY_PATH 默认 /app/data/deliveries；Compose 使用 qq_delivery_data 持久卷，不能在网关重建时清空，也不要启动多个机器人实例共享此文件日志目录。目录损坏或不可读时按未知结果处理，禁止绕过日志直接发送。


## 知识资料、生成依据与日期确认

知识范围由服务端的账号组织或活动班级组织确定，客户端 organization_id 不授予访问权。正式规划、草案分析和计划生成均传组织范围；检索只包含该组织和公共资料。公共资料仅供读取，管理者只维护自己组织资料。

- GET /knowledge/documents：返回 documents，最多最近200条，含 id/source/version/embedding_profile/时间。GET /knowledge/documents/{uuid} 返回正文和元数据；无权或不存在404。
- POST /knowledge/documents：保留 source/content/metadata 请求。按本组织规范化来源+正文去重，并发相同上传只保留一条，返回 idempotent 与 indexed/needs_reindex。历史重复不自动删除，历史无哈希资料也先复用。
- PUT /knowledge/documents/{uuid}：source、content、metadata、expected_version。生成新向量成功后，正文/向量/模型标识/版本原子更新；失败保留原资料。版本冲突或本组织相同资料409。DELETE /knowledge/documents/{uuid}?expectedVersion=N 按版本删除，后续检索立即不使用该资料；历史引用快照保留。
- POST /knowledge/search：query、top_k，返回 results、retrieval_status、embedding_profile；结果含 source/content/version/score。unavailable 是检索失败，empty 是无相关依据，不能混为同一种成功空列表。
- generation 保存 mode(model/offline)、fallback_reason、retrieval_status、embedding_profile、sources、pending_fields。来源由检索层确定，包含资料ID、来源、版本、摘录；模型不能自报可信引用。草案编辑不会覆盖原生成依据，确认执行快照保留分析和规划依据。
- 未配置或模型失败时使用明确标识的离线模板，不自动填三天后的活动/截止日期。缺失日期列入 pending_fields。正式活动缺日期进入 waiting_input，不先创建表单/任务/日历/提醒；补充并确认后复用计划继续。赋予模型建议的明确时间也须经过草案人工确认。
- 向量模型/端点与维度有稳定 profile，检索只比较同 profile。V7 保留历史正文和向量，无法判定来源的旧向量标为 legacy_unknown，默认不参与相似度检索；编辑保存可显式重新索引。本地哈希向量仅是开发检索降级，不冒充正常语义模型。

## 复盘版本与独立结束活动

仅活动创建者可生成、查看复盘。POST /activities/{id}/recap 接收 request_id；同活动同请求只生成一版，重试/并发复用原记录。兼容不传请求体的旧调用，每次视为新意图。响应含 id/version/status/recap/generation/snapshot/时间，可能为 generating/succeeded/failed。

复盘先保存生成时的活动版本、状态、统计时间和结构化快照，再在事务外调用AI。快照包括有效/取消报名、任务总数/完成/待分配、步骤/投递/提醒状态计数；不将模型文字当作数字事实。AI只收到白名单聚合值，不传活动标题、姓名、联系方式、问卷原文、任务说明或逐人明细，复盘不额外检索RAG资料。模型失败时返回聚合模板；整个AI服务不可用时由Spring保存可核对的本地兜底。

GET /activities/{id}/recaps 返回最近20版；GET /activities/{id}/recaps/{recapId} 可读取指定历史版本。重登和刷新不丢正文；重新生成新增版本，不改写旧快照。生成超过180秒的遗留记录在下一次读取时标为失败，迟到结果不得覆盖，可显式生成新版本。

生成复盘不设置 finished。允许在 ready/finished/cancelled/failed 的稳定状态生成快照；取消后生成复盘只保存事实，不恢复活动。POST /activities/{id}/finish 使用 request_id、expected_version，只有 ready 可结束；重复请求幂等。结束关闭报名、取消未发送提醒并保留已发送事实，记录管理变更与通知。任务历史保留，后续补填的完成情况体现在新复盘版本中。

V7 增加知识版本/模型标识以及 activity_recaps。旧版未保存的复盘正文无法恢复；旧 finished 状态不会被凭空转回 ready。新生成版本遵循上述分离规则。

## 签到、反馈与参与统计（第七阶段）

- `GET /participation/activities`：当前有效班级成员可参与的活动；`GET /activities/{id}/participation/me`：本人的签到、反馈及版本，不返回他人反馈。
- `GET /activities/{id}/participation`：活动创建者且仍有当前班级组织管理权限可查看成员快照、到场/缺席、评分分布与反馈原文。
- `PUT /activities/{id}/participation/settings`：相同管理权限；提交 `request_id`、`expected_version`、`attendance_open`、`feedback_open`。两项默认关闭；在 `ready` 状态保存任一开放项会追加当前有效班级成员到快照，再次保存可纳入新成员，历史成员不删除。`finished` 只可配置反馈，不再追加成员或重新开放签到；取消活动不能开放。
- `POST /activities/{id}/check-ins`：提交 `request_id`，默认本人；创建者可另传 `account_id` 代签到。目标须为当前有效成员且已进入快照，只在 `ready` 且签到开放时写入；同一成员只保留一份签到事实。
- `POST /activities/{id}/feedback`：提交 `request_id`、整数 `rating`（1–5）、可选 `comment`（最多2000字）、`expected_version`（首次0，后续使用本人反馈版本）。仅快照内、当前有效且已签到的成员可提交或修改自己的反馈，须在 `ready/finished` 且反馈开放时操作。请求重放复用结果，同标识不同内容或版本冲突返回409。

结束活动同时关闭签到，保留历史签到和反馈。复盘快照新增成员快照人数、到场/缺席、反馈数、评分总和及分布；Java→AI、AI→模型均只传固定聚合白名单，反馈原文和逐人成员明细不进入模型。

## 常用 CSV 导出（第七阶段）

- `GET /activities/{id}/exports/registrations.csv`：创建者且仍有当前班级组织管理权限；导出全部报名表的报名及取消状态，动态回答按题目标签展开，历史未知字段与嵌套值保留为安全文本。
- `GET /activities/{id}/exports/tasks.csv`：同上；导出任务说明、期限、优先级、依赖、状态和负责人真实账号/学号，不按旧姓名猜测账号。
- `GET /todos/{id}/exports/missing.csv`：当前班级组织管理者；只导出待办未交成员的必要身份和待办信息，不含提交备注、附件或无关敏感字段。

下载使用当前登录会话，服务端重新检查归属与权限。响应为 `text/csv; charset=UTF-8`、UTF-8 BOM、RFC4180 引号转义及 CRLF，使用固定安全文件名、`Cache-Control: no-store` 和 `X-Content-Type-Options: nosniff`。文本检测涵盖前导空白/控制符及全角公式前缀，危险单元格加单引号；实际数字保留数值。取消凭证/哈希、密码、会话、任意内部 metadata 不作为导出列，反馈原文不在本次导出范围。

## 业务模板（第七阶段）

网页入口为 `/templates`，业务 API 使用 `/business-templates`，两者路径分开：

- `GET /business-templates`、`GET /business-templates/{id}`：管理者读取内置只读模板和本组织模板；列表响应为 `templates`。
- `GET /business-templates/sources`：返回当前管理者在本组织创建的 `drafts`、`activities` 来源选项。
- `POST /business-templates`：提交 `name`、`description`，以及三选一的 `blueprint`、`source_draft_id`、`source_activity_id`；保存为本组织新模板。
- `PUT /business-templates/{id}`：仅模板创建者修改，提交 `name`、`description`、`blueprint`、`expected_version`；`DELETE /business-templates/{id}?expectedVersion=N` 按版本删除。内置和他人模板可另存，不能直接修改或删除。
- `POST /business-templates/{id}/apply`：提交 `class_id`、`request_id`；仅可应用到当前组织内可管理的班级，返回 `draft_id`、`status=draft`、`idempotent`。相同请求复用同一草案，标识用于其他模板或班级返回409。

从历史草案/活动保存时只复制白名单结构：保留问卷类型及任务依赖，旧自由文本换成占位，清空物料、日期、旧身份和运行数据；需在基础表单或高级蓝图编辑器填写通用内容。应用模板创建新的待确认草案，日期和负责人须重新确认，群发布默认关闭；不会自动发布、创建活动或发送消息，后续仍走原草案保存与确认发布流程。

## 数据库分流状态

`GET /cluster/database`：需要有效登录且为管理者。返回 `enabled`、`replica_queries`、`primary_fallbacks` 和 `policy`，不返回连接信息或密钥。计数按当前 Tomcat 实例累计，重启清零。此接口说明配置及实际路由计数，不代表数据库自动故障切换。详细验证步骤见 [READ_WRITE_SPLIT.md](READ_WRITE_SPLIT.md)。
