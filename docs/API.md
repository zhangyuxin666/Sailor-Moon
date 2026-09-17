# 活动管家 API 契约（v1）

所有时间使用 ISO 8601；当前脚手架暂以 `user_id` 表示调用者，接入登录后应从认证令牌读取身份。

## 发起活动

`POST /activities`，成功返回 `202 Accepted`。

```json
{"user_id":"alice","text":"下周五晚举办 80 人社团招新，预算 1000 元"}
```

```json
{"activity_id":"a1b2c3","run_id":"r1b2c3","status":"queued"}
```

前端随后轮询 `GET /runs/{run_id}?user_id=alice`。状态为 `queued | running | succeeded | failed`；失败时查看 `error`。

## 查询执行状态和活动

- `GET /runs/{run_id}?user_id=alice`：仅活动创建者可查，返回任务类型、状态、尝试次数、错误和时间字段。
- `GET /activities/{activity_id}?user_id=alice`：仅创建者可访问，返回活动、策划、任务、问卷、提醒和步骤。

## 报名、统计、复盘和提醒

- `POST /forms/{form_id}/registrations`：公开报名，请求字段为 `name`、`contact`、可选 `extra`。
- `GET /activities/{activity_id}/form-stats?user_id=alice`：创建者查看报名统计。
- `POST /activities/{activity_id}/recap?user_id=alice`：根据真实报名和任务数据生成复盘。
- `POST /reminders/{reminder_id}/cancel?user_id=alice`：取消尚未发送的提醒。

## B 负责的 RAG 内部接口

RAG 不直接暴露给公网，由规划器调用：

```python
retrieve(query: str, user_id: str, top_k: int = 5) -> list[RetrievedChunk]
```

`RetrievedChunk` 必须包含 `chunk_id`、`document_id`、`title`、`content`、`location`、`score`。规划结果增加 `citations`，每条引用只保存检索返回的 `chunk_id`。无命中时返回空列表，禁止生成虚假引用。

## 错误约定

- `403`：无权操作该活动。
- `404`：资源或执行记录不存在。
- `422`：请求字段校验失败。
- 后台执行错误记录在执行状态的 `error` 字段，不通过创建接口返回。
