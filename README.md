# 活动管家（Activity Assistant）

面向学生社团/班级活动组织的 AI Agent 助手：**一句话发起活动，全流程自动跑**。
自动完成活动策划与物料清单、报名问卷与统计、任务分工派发、活动前定时提醒、活动后复盘总结。

## 架构

```
用户一句话需求
      │
      ▼
┌─────────────────────────────────────────────┐
│ Orchestrator（编排：规划 → 执行 → 交付）       │
│  1. generate_plan     LLM 生成策划+物料清单    │
│  2. create_form       生成报名问卷             │
│  3. assign_tasks      任务派发到人 + 消息通知   │
│  4. create_calendar   生成日历事件（ICS）      │
│  5. schedule_reminder 活动前定时提醒           │
└─────────────────────────────────────────────┘
      │ 工具调用（统一封装：幂等 + 指数退避重试）
      ▼
  send_message / create_form / form_stats / create_calendar_event
      │
      ▼
  SQLite（活动、任务、问卷、报名、提醒、消息、幂等键）
  APScheduler（定时提醒，支持取消，重启后自动重建）
```

- 未配置 `LLM_API_KEY` 时使用离线规则式 `MockLLMClient`，全流程开箱可跑；
  配置后走 OpenAI 兼容接口（`app/agent/llm.py`）。
- Mock 工具（消息/表单/日历）只落库 + 打日志，接真实通道时替换对应 `run()` 即可。

## 工程化设计

- **幂等**：所有副作用工具执行前查 `idempotency_keys` 表，命中直接返回缓存结果，
  Agent 重试/重跑不会重复发消息、重复建表单（`app/core/idempotency.py`）。
- **失败重试**：工具执行统一包指数退避重试，只对 `ToolTransientError` 等可重试异常重试
  （`app/core/retry.py`、`app/tools/base.py`）。
- **权限控制**：所有按活动操作的接口在 service 层校验 `user_id` 是否为创建者，
  越权返回 403（`app/core/permissions.py`）。报名提交是公开接口。
- **统一数据层**：SQLAlchemy 同时支持本地 SQLite 与部署 PostgreSQL，部署配置见 `compose.yml`。
- **持久化后台执行**：API 返回执行编号，独立 Worker 执行 Agent 和工具；失败任务自动重试且状态可查询。
- **提醒可取消**：独立 Scheduler 只投递到期任务，Worker 发送前再次检查状态。

## 快速开始

```bash
python -m venv .venv
source .venv/Scripts/activate   # Windows Git Bash；PowerShell 用 .venv\Scripts\Activate.ps1
pip install -r requirements.txt
cp .env.example .env            # 可选，不配也能跑（离线 Mock）

uvicorn app.main:app --reload   # 终端 1：API
python -m app.worker            # 终端 2：后台任务
python -m app.scheduler.runner  # 终端 3：提醒调度
python -m pytest tests/ -v      # 运行测试
```

## API 示例

```bash
# 一句话发起活动（返回 202、activity_id 和 run_id）
curl -X POST http://127.0.0.1:8000/activities \
  -H "Content-Type: application/json" \
  -d '{"user_id": "alice", "text": "下周三晚上7点社团招新宣讲会"}'

# 报名（公开接口）
curl -X POST http://127.0.0.1:8000/forms/{form_id}/registrations \
  -H "Content-Type: application/json" -d '{"name": "小明", "contact": "13800000000"}'

# 查看报名统计（仅活动创建者）
curl "http://127.0.0.1:8000/activities/{activity_id}/form-stats?user_id=alice"

# 活动后复盘
curl -X POST "http://127.0.0.1:8000/activities/{activity_id}/recap?user_id=alice"

# 取消提醒
curl -X POST "http://127.0.0.1:8000/reminders/{reminder_id}/cancel?user_id=alice"
```

完整接口契约见 `docs/API.md`，四人职责见 `docs/TEAM.md`。部署 PostgreSQL、API、Worker 和 Scheduler 可运行 `docker compose up --build`。

## 目录结构

```
app/
  main.py               FastAPI 入口
  config.py             环境变量配置
  models/               SQLite/PostgreSQL 数据层 + Pydantic 模型
  jobs.py               持久化后台队列
  worker.py             Agent/工具任务 Worker
  core/                 幂等、重试、权限、异常
  tools/                工具基类（幂等+重试封装）与消息/表单/日历工具
  agent/                LLM 客户端（真实/Mock）、提示词、规划器、编排器
  scheduler/            APScheduler 提醒调度（可取消、重启重建）
  services/             业务门面（权限校验入口）
tests/                  幂等/重试/权限/全流程/提醒/API 冒烟测试
```
