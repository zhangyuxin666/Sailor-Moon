# 活动管家开发规范

本文档规定活动管家项目的开发环境、模块边界、Git 协作、代码风格、接口与数据库变更、测试、安全和文档要求。所有成员提交代码前都应遵守本规范。

## 1. 技术环境

团队统一使用以下主要版本：

| 组件 | 版本或要求 |
|---|---|
| Python | 3.12（最低 3.11） |
| Node.js | 20 LTS 或 22 LTS |
| PostgreSQL | 16 |
| Docker | Docker Desktop 最新稳定版 |
| 后端 | FastAPI、Pydantic 2、SQLAlchemy 2 |
| 测试 | Pytest |

本地开发可以使用 SQLite，合并前涉及数据库、并发任务或部署的改动必须使用 PostgreSQL 验证。

## 2. 本地环境搭建

```powershell
python -m venv .venv
.\.venv\Scripts\Activate.ps1
python -m pip install -r requirements.txt
Copy-Item .env.example .env

npm install
```

核心服务分别运行：

```powershell
# 网页和 API
python -m uvicorn app.main:app --reload --port 8001

# Agent 和工具后台任务
python -m app.worker

# 定时提醒
python -m app.scheduler.runner

# QQ 机器人，需要有效的 QQ 配置
npm run qq-gateway
```

访问地址：

- 网页：`http://127.0.0.1:8001/`
- API 文档：`http://127.0.0.1:8001/docs`
- 健康检查：`http://127.0.0.1:8001/health`

## 3. 目录和模块边界

```text
app/agent/          LLM、提示词、需求理解和 Agent 编排
app/core/           认证、权限、审计、幂等、重试和公共异常
app/integrations/   QQ 等外部平台适配
app/models/         数据库结构和 Pydantic 模型
app/scheduler/      定时提醒调度
app/services/       活动、班级、草稿和文件业务逻辑
app/static/         HTML、CSS 和 JavaScript 页面
app/tools/          Agent 可调用的表单、消息、日历和 QQ 工具
tests/              自动化测试
docs/               需求、接口、分工和开发文档
```

分层规则：

1. `main.py` 只负责路由、请求解析、身份获取和响应，不堆积复杂业务逻辑。
2. 业务规则放在 `services/`。
3. 外部副作用必须通过 `tools/` 或 `integrations/` 执行。
4. 数据库事实不能由模型生成；模型只负责理解、规划和文本生成。
5. Agent 不能绕过权限和工具层直接执行外部操作。
6. 跨模块调用使用公开方法和结构化模型，不依赖其他模块的内部实现。

## 4. 四人代码责任

| 成员 | 主要范围 |
|---|---|
| A | 班级、成员、报名、作业、信息收集、统计及相关页面 |
| B | 需求理解、策划智能体、LLM、提示词、RAG 和引用评测 |
| C | 任务分配、消息、日历、QQ、工具幂等、重试和权限 |
| D | 主控编排、后台队列、Worker、Scheduler、复盘、数据库和部署 |

公共文件主要维护人：

| 文件 | 维护人 |
|---|---|
| `app/main.py` | D |
| `app/models/database.py` | D |
| `app/models/schemas.py` | D，相关成员共同评审 |
| `app/agent/llm.py` | B |
| `app/tools/registry.py` | C |
| `docs/API.md` | D，接口开发者同步更新 |
| `README.md` | D |

修改公共文件前应先在群内说明目的和字段变化，避免多人同时修改造成冲突。

## 5. Git 分支规范

禁止直接在 `main` 上开发。每项工作从最新 `main` 创建分支：

```powershell
git switch main
git pull --ff-only origin main
git switch -c feature/b-planning-rag
```

分支命名格式：

```text
feature/<成员>-<功能>     新功能
fix/<成员>-<问题>         缺陷修复
docs/<成员>-<文档>        文档修改
refactor/<成员>-<模块>    重构
test/<成员>-<模块>        测试补充
```

示例：

```text
feature/a-registration-limit
feature/b-planning-rag
fix/c-qq-idempotency
refactor/d-workflow-resume
```

每名成员必须配置自己的 Git 身份：

```powershell
git config user.name "自己的 GitHub 用户名"
git config user.email "自己的邮箱"
```

禁止多人共用同一个 Git 身份提交。

## 6. Commit 规范

一个提交只完成一个明确目标，提交前检查差异：

```powershell
git status
git diff --check
git diff --staged
```

提交信息格式：

```text
类型(模块): 简短说明
```

允许的类型：

```text
feat      新功能
fix       缺陷修复
refactor  重构
test      测试
docs      文档
chore     构建、依赖或配置
```

示例：

```text
feat(rag): 增加活动资料权限过滤检索
fix(auth): 修复初始化密码错误显示
test(qq): 补充重复消息幂等测试
docs(api): 更新任务状态接口说明
```

禁止使用“修改”“更新一下”“最终版”等无法说明内容的提交信息。

## 7. Pull Request 规范

所有功能通过 Pull Request 合入 `main`。PR 至少包含：

- 解决的问题和最终行为。
- 修改的主要模块。
- API、数据库、配置或依赖变化。
- 测试命令和结果。
- 页面变化截图或演示说明。
- 已知限制和后续任务。

合并要求：

1. 分支已同步最新 `main`。
2. 自动化测试通过。
3. 没有提交密钥、数据库文件、上传文件和调试日志。
4. 接口变化已更新 `docs/API.md`。
5. 配置变化已更新 `.env.example`。
6. 数据库结构变化包含迁移方案。
7. 至少一名非作者成员完成代码审查。

## 8. Python 代码规范

- 使用 4 个空格缩进和 UTF-8 编码。
- 函数、变量和模块使用 `snake_case`。
- 类使用 `PascalCase`。
- 常量使用 `UPPER_SNAKE_CASE`。
- 公共函数写简短 docstring。
- 新接口使用 Pydantic 模型，不直接接收无约束的字典。
- 避免过长函数；路由不负责复杂业务编排。
- 捕获具体异常，禁止无说明地吞掉异常。
- 不在日志中输出密码、Cookie、AppSecret 和完整联系方式。
- 时间统一使用 ISO 8601；业务时区由 `TIMEZONE` 配置。

导入顺序：标准库、第三方依赖、项目内部模块，各组之间空一行。

## 9. JavaScript 和页面规范

- 使用 `const` 和 `let`，禁止新增 `var`。
- API 请求统一封装错误解析，不直接显示对象。
- 所有动态文本写入页面前进行 HTML 转义。
- 提交按钮执行期间禁用，防止重复提交。
- 加载、空数据、成功和失败状态都必须有明确提示。
- 不在前端保存模型密钥、QQ 密钥和数据库密码。
- 新页面适配常见手机宽度。
- 公共样式优先复用，避免为同一种组件重复定义样式。

## 10. API 规范

- 路径使用名词和复数资源，例如 `/activities/{activity_id}`。
- 创建返回 `201`；后台受理返回 `202`；删除或无响应成功可返回 `204`。
- 参数校验失败返回 `422`，权限不足返回 `403`，资源不存在返回 `404`。
- 后台任务接口返回稳定的 `run_id`，前端通过状态接口查询。
- 登录后的身份只从安全会话获取，不信任客户端传入的 `user_id`。
- 列表接口新增数据量后必须支持分页。
- 错误统一使用：

```json
{"detail": "用户可读的错误说明"}
```

结构化校验错误由前端格式化后显示。

## 11. 数据库规范

- 表名和字段名使用 `snake_case`。
- 业务主键使用稳定 ID，不使用会变化的名称作为关联条件。
- 高频查询和关联字段建立索引。
- 所有业务数据有明确的组织、班级、活动或账号归属。
- 跨组织查询必须在 SQL 或服务层明确过滤。
- 具有副作用的重复请求使用数据库唯一约束或幂等键保护。
- 禁止手工修改生产数据库结构。
- 引入 Alembic 后，每次结构修改必须提交迁移脚本并验证升级和回滚。
- 日志和向量库中不得保存密码及不必要的个人信息。

## 12. Agent 和 RAG 规范

- 智能体输入和输出必须使用 Pydantic 模型。
- 智能体只输出决策和结构化数据，副作用交给工具层。
- 信息不足时返回 `missing_information`，不能编造关键事实。
- 负责人只能从当前班级真实成员中选择。
- 报名数、完成率和投递状态只能从数据库读取。
- RAG 检索必须带组织权限过滤。
- 引用只能使用检索返回的真实 `chunk_id`。
- 无检索结果时返回空引用，不生成虚假来源。
- 提示词修改应补充对应的结构化输出测试。
- 模型不可用时应有明确错误或可说明的降级行为。

统一工作流状态：

```text
pending、running、waiting_input、succeeded、failed、cancelled
```

## 13. 工具、幂等和重试规范

- 表单、消息、QQ、日历和提醒等副作用必须经过工具层。
- 每个副作用操作必须有稳定、可复现的幂等键。
- 幂等键应包含业务资源 ID、操作类型和必要的内容摘要。
- 只对网络超时、连接中断和限流等瞬时异常重试。
- 权限错误、参数错误和业务冲突立即失败。
- 重试必须有最大次数和退避间隔。
- 外部平台结果不明确时先查询或对账，不能直接重复发送。

## 14. 测试规范

每个功能由开发者提交对应测试。最低要求：

- 正常路径。
- 参数边界。
- 权限拒绝。
- 重复请求或幂等。
- 外部依赖失败。
- 与修改相关的恢复或取消场景。

本地测试命令：

```powershell
python -m pytest tests -q
python smoke_check.py
```

合并前必须运行全量测试。测试不得调用真实 QQ、真实模型或生产数据库，应使用 Mock、临时数据库或 monkeypatch。

不要编写只重复实现细节、无法发现实际错误的测试。

## 15. 配置和安全规范

- `.env`、`.env.production`、密钥、Cookie 和数据库密码禁止提交。
- 新增环境变量时同步更新 `.env.example` 和生产示例。
- 示例配置只能使用占位值。
- 已暴露的密钥必须立即在平台重置，删除 Git 文件并不能使其恢复安全。
- 上传文件必须校验扩展名、Content-Type、大小和配额。
- 所有管理操作进行权限检查，关键操作写入审计日志。
- 生产环境使用 HTTPS、安全 Cookie 和强随机数据库密码。

## 16. 文档同步规范

| 修改内容 | 必须同步更新 |
|---|---|
| 新增或修改 API | `docs/API.md` |
| 新增环境变量 | `.env.example` |
| 修改启动或部署方式 | `README.md` |
| 修改产品行为 | `docs/REQUIREMENTS.md` |
| 修改成员职责 | `docs/TEAM.md` |
| 修改数据库结构 | 迁移脚本和数据库说明 |

文档应描述当前最终行为，不保留已经放弃的实现方案。

## 17. 合并前检查清单

```text
[ ] 只修改了本任务相关文件
[ ] git diff --check 通过
[ ] 没有密钥、数据库文件和调试输出
[ ] Python 和 JavaScript 可以正常解析
[ ] 新功能有有效测试
[ ] python -m pytest tests -q 通过
[ ] 权限、幂等和失败场景已检查
[ ] API、配置和 README 已按需更新
[ ] PR 描述包含验证结果和已知限制
```

任何成员发现规范与当前项目不一致时，应通过文档 PR 修改本文件，而不是在个人分支长期使用不同规则。
