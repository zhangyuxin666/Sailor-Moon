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
- **任务进度**：活动创建者可将任务标记为 `pending` 或 `done`，复盘会读取最新进度。
- **日历下载**：活动创建者可下载已生成的 ICS 文件；未带时区的活动时间按 `TIMEZONE` 解释。

## 快速开始

```bash
python -m venv .venv
source .venv/Scripts/activate   # Windows Git Bash；PowerShell 用 .venv\Scripts\Activate.ps1
pip install -r requirements.txt
cp .env.example .env            # 可选，不配也能跑（离线 Mock）

uvicorn app.main:app --reload --port 8001  # 终端 1：网页 + API（8000 被占用时使用 8001）
python -m app.worker            # 终端 2：后台任务
python -m app.scheduler.runner  # 终端 3：提醒调度
npm install                    # 首次安装 QQ 官方 Node.js SDK
npm run qq-gateway             # 终端 4：QQ 官方机器人网关
python -m pytest tests/ -v      # 运行测试
```

启动后访问 `http://127.0.0.1:8001/` 使用中文可视化界面；开发者接口文档位于
`http://127.0.0.1:8001/docs`。

首次访问会进入初始化页面：创建第一个管理者账号后，管理者可以创建班级、批量录入
参与者账号、发布作业/待办、查看全班完成情况，并立即或定时通过 QQ 群提醒未完成人员。
参与者使用管理者分配的账号登录，可以查看自己的待办并上传文件或填写完成说明。
原 AI 活动策划工作区保留在 `http://127.0.0.1:8001/activity`。

管理者也可以进入 `http://127.0.0.1:8001/agent`，用自然语言描述班级事务。Agent 会先判断
意图和复杂度，生成可编辑草案与建议流程；只有管理者确认后才发布。收作业等简单任务只创建
作业与提交入口，通知只发送消息，信息收集只创建表单，复杂活动才启用策划、报名、分工、
日历和定时提醒。

主要页面彼此独立：

- `/portal`：总览
- `/agent`：需求理解、草案修改与确认发布
- `/assignments`：作业、待办与提交统计
- `/activity`：复杂活动监控与复盘
- `/members`：Excel 成员导入与账号管理
- `/settings`：账号、QQ 连接、隐私和审计日志

## QQ 官方机器人接入

1. 在 [QQ 开放平台](https://q.qq.com/) 创建机器人，将 `AppID` 和 `AppSecret` 填入 `.env`。
2. 启动 `npm run qq-gateway`，把机器人添加到用于组织活动的 QQ 群。
3. 网页顶部会显示六位绑定码，在群里 `@机器人` 发送 `绑定 123456`。
4. 绑定成功后，从网页发起活动会自动向该群发送活动方案、分工和公开报名链接；定时提醒与手动催办也会走 QQ。

手机需要能访问报名链接。仅在本机演示时可使用默认 `PUBLIC_BASE_URL`；需要群成员报名时，
请将它设置为已部署的 HTTPS 域名，或同一局域网中可访问的电脑 IP 地址。

## 生产部署

1. 将 `.env.production.example` 复制为 `.env.production`，填写域名、随机数据库密码、模型密钥和 QQ 机器人密钥。
2. 将域名解析到服务器公网 IP，开放 TCP 80/443 端口。
3. 运行 `docker compose --env-file .env.production -f compose.prod.yml up -d --build`。
4. Caddy 会自动申请并续期 HTTPS 证书；API、Worker、Scheduler、QQ 网关和 PostgreSQL 均配置了自动重启。
5. 使用 `powershell -File scripts/backup.ps1` 备份数据库与本地上传文件，并定期验证恢复。

公开使用前必须在 QQ 开放平台重置已经暴露过的 `AppSecret`。生产环境推荐把
`STORAGE_BACKEND` 改为 `s3`，避免作业文件仅存在单台服务器。平台包含组织隔离、
登录限流、安全 Cookie、来源校验、文件类型/大小/配额限制、隐私同意、个人数据导出、
参与者注销和关键操作审计；短信/邮箱找回密码仍需接入第三方服务商后才能启用。

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

# 完成任务（任务 ID 可在活动详情的 tasks 中找到）
curl -X PATCH "http://127.0.0.1:8000/tasks/{task_id}?user_id=alice" \
  -H "Content-Type: application/json" -d '{"status": "done"}'

# 下载日历事件
curl -o activity.ics "http://127.0.0.1:8000/activities/{activity_id}/calendar.ics?user_id=alice"
```

完整接口契约见 `docs/API.md`，四人职责见 `docs/TEAM.md`。部署 PostgreSQL、API、Worker 和 Scheduler 可运行 `docker compose up --build`。
重新梳理后的角色、业务流程、工程约束与验收标准见 `docs/REQUIREMENTS.md`。

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
