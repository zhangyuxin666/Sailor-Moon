# 活动管家开发规范

当前架构见 [ARCHITECTURE.md](ARCHITECTURE.md)，业务职责见 [TEAM.md](TEAM.md)。技术目录描述实现边界，不代表整目录归某一个角色。

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
npm --prefix web test
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
start.bat
start.bat -Single -WithQq
```

只有修改源码或 Dockerfile 后才使用 `--build`。构建阶段会持久缓存 Maven、npm 和
pip 下载内容。默认模式为 Nginx + 两个外部 Tomcat；`-Single` 用于单实例开发和
QQ Gateway。

访问地址：集群入口 `http://127.0.0.1:18080`，单实例模式
`http://127.0.0.1:8080`，AI 健康检查 `http://127.0.0.1:8000/health/ready`。

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
- AI 调用保留可重试传输策略；QQ 发送不对未知传输结果直接重发，只对明确未发送的临时错误执行有限队列重试。认证、权限和参数错误不自动重试。
- QQ Gateway 使用持久日志和 Idempotency-Key，结合 Spring 独立事务意图/结果，重启后保留成功或未知事实；未知结果先核对。

## 统一检查入口

[scripts/check.ps1](../scripts/check.ps1) 要求 PowerShell 7.2+、Node.js 22、Python 3.12、Java 21 和 Maven 3.9；完整检查另需 Docker Engine 和 Compose v2。从仓库根目录执行：

```powershell
# 完整本地回归：锁定依赖安装、单元测试、前端构建、WAR verify、Compose 和离线跨服务流程
pwsh -File scripts/check.ps1 -WithE2E
# 无 Docker 环境：仍执行测试和构建
pwsh -File scripts/check.ps1 -SkipDocker
# 已按锁文件装好依赖时可复用环境；自定义解释器可传 -Python 或 -Maven
pwsh -File scripts/check.ps1 -SkipInstall -Python .\.venv\Scripts\python.exe
# 备份归档、挂载和拒绝覆盖的纯安全契约检查，不调用 Docker
pwsh -File scripts/test-backup-contract.ps1
```

默认会安装 `web`、`qq-gateway` 的 npm 锁定依赖和 `requirements-test.lock`；不传 `-WithE2E` 时仍检查 Compose 配置，但不运行跨服务流程。`-WithE2E` 不能与 `-SkipDocker` 同用。脚本对测试注入虚假内部令牌和离线地址，Python 在空临时目录执行，Compose 校验不读取本地实际环境文件；真实 QQ 和模型验收需另行记录。

跨服务检查只操作保留测试项目 `sm-ci` 和 `compose.test.yml`，依次覆盖单实例、双 Tomcat，并将报告写到 `target/e2e-single.json`、`target/e2e-cluster.json`。结束时会删除该测试项目及其测试卷；不要把个人或业务数据部署在 `sm-ci` 下。报告的存在和命令说明不代表本次已运行或远端 CI 已通过，应核对当前执行输出。备份/恢复实际流程见 [DEPLOYMENT.md](DEPLOYMENT.md)。

## 测试与提交门槛

```powershell
mvn test
.\.venv\Scripts\python.exe -m pytest tests_ai -q
npm --prefix web test
node --check qq-gateway/server.mjs
npm --prefix qq-gateway test
docker compose config --quiet
docker compose -f compose.cluster.yml config --quiet
git diff --check
```

涉及数据库、跨服务调用或部署的修改还必须在 Docker 全栈上跑一次 `/health/ready` 和登录到活动创建的冒烟流程。测试不得调用真实 QQ 或生产模型。

提交前同步规则：API 改动更新 `docs/API.md`；环境变量更新 `.env.example`；数据库改动增加 Flyway；部署改动更新 `README.md`。

## 页面与确认快照回归

前端测试入口 `npm --prefix web test` 自动运行 `web/tests/*.test.mjs`，包括时间、身份跳转、会话错误分类、步骤状态、问卷答案及题目校验。可用 `node web/node_modules/typescript/bin/tsc -p web/tsconfig.json --noEmit --incremental false` 单独检查类型。

草案到执行必须保留人工确认的字段；改动 `ConfirmedPlans`、草案发布或运行补充逻辑时，同时验证已有任务账号关系、原问卷题目与选项、真实计划更新、恢复缺口校验、重复恢复、终态拒绝和失败重试复用已完成步骤。仅用接口转发测试不能覆盖这些规则。

页面失败应可重试，并保留未提交内容。`authentication_required` 与一般权限拒绝分开处理；会话过期重新登录时保留原页面状态。步骤和运行错误不回传上游响应、连接串或密钥。浏览器模拟 API 验收与真实 PostgreSQL 事务/并发验收须分别记录，不互相替代。

运行状态面板首次读取或观察到状态变化时，要同步刷新活动详情，避免运行已完成而任务/表单仍显示旧数据。主动修改密码成功后应明确清理本地账号再进入登录页，不走意外会话过期的保留页面流程；这两条均应通过真实浏览器检查。

## 活动管理与数据迁移

V5 新增报名和任务管理结构。创建新迁移，不能修改已共享的 V1–V4。历史报名不自动删除或合并，迁移验证须包含规范化后的重复身份；有效人数包含保留的历史记录。回执只保存哈希，日志和私有统计不返回取消凭证。

修改取消、报名、任务或执行步骤时遵循活动优先的锁顺序；运行恢复先取得活动锁，避免与取消形成反向锁序。步骤与管理操作写入使用 Spring 事务代理，跨服务只能调用访问方法，不能直接读取代理对象字段。外部通知走已提交的变更队列，失败重试保持原逻辑键。

关键回归包括：最后名额并发、请求重放、动态字段绕过、取消释放名额、历史重复、任务循环和跨活动依赖、改派后的旧账号失权、版本冲突、日历 UID/SEQUENCE、旧提醒失效、执行中取消的步骤边界以及通知失败恢复。单实例数据库通过不代表 Worker 租约、网关重启或未知外部投递全部通过。


## Worker 与投递回归

修改队列必须验证双 Worker 下长于租约的步骤持续续约、进程退出后接管、旧领取凭证失效、最大时长和有限重试、活动取消不被迟到完成覆盖。领取令牌仅供内部使用，不能暴露在运行 API；回收保持活动优先的锁序。业务写入、步骤与本地幂等结果属于同一事务，不要在编排中绕过 ActivityGate。

修改投递必须覆盖响应丢失、网关重启、SDK 调用在途退出、外发成功后步骤提交前退出。只有确认未发送才可自动重试；不要把404/连接错误或不完整日志解释成未发送。网关去重卷不可随容器删除。测试可注入 SDK 回调替身，但必须调用真实 gateway-http 与 PersistentDeliveryJournal；不能把假 QQ 验收当成平台实网测试。

Node 回归入口 npm --prefix qq-gateway test 包含持久化/并发/崩溃语义。Java 关键回归包含 JobLeaseTest 与 OutboundDeliveryServiceTest；数据库事务与杀进程场景还须在独立 PostgreSQL 和双 Tomcat 上验证，不以 mock 替代。

V6 升级须先停止旧版 Worker，核对并处理旧版在途外发，再整体更新节点；不要混跑缺少租约保护的旧节点。旧版失败/未知投递没有新协议的持久证据，须人工核对，不能将普通重试解释为安全重放。保留原数据库和网关卷，升级/回退前备份另按部署流程完成。


## 知识与复盘验证

修改规划须同时验证正式入口和确认草案的组织范围、公共引用、模型断开、检索失败/空依据、无猜测日期及待确认恢复。生成依据由服务端保存，不能从草案编辑请求信任或重写来源信息。向量模型变化须更换profile并重索引，不能混用历史未知向量。

资料更新须将正文、向量、profile和版本共同提交；嵌入失败保持旧版，删除后不再参与新检索。公开资料不能在组织维护接口改写，跨组织ID返回404，参与者不能维护资料。前端成功删除后应清空新建表单，失败保留输入。

复盘测试应捕获Java→AI及AI→模型两层真实请求，断言不包含报名姓名/联系方式/完整回答、任务说明或活动标题；统计与数据库核对。还须覆盖同请求并发只生成一次、旧版本不变、重新登录读取、模型与整个AI服务分别失败、生成超时/迟到结果、生成不结束活动及显式结束的幂等/提醒关闭。文档不能用模拟模型成功代表实网模型已验证。

## 第七阶段开发约定

V8 以新增迁移保存参与设置、追加式成员快照、签到/反馈及业务模板；不改写已共享迁移。签到/反馈修改与活动结束共同先锁活动，写入、审计和请求重放结果在同一事务内；反馈更新还检查本人版本。`ready` 开放保存可追加成员，`finished` 不扩充快照，统计不能因成员离班而删除历史事实。组织权限和活动归属由服务端复查。

CSV 导出由 ExportService 显式选列，CsvExport 统一 BOM、RFC4180 和公式注入防护；新增列须先确认必要性，不能直接序列化数据库整行或 metadata。验证中文、引号/换行、前导空白/控制符/全角公式、真实数字、历史嵌套答案与无权下载。

业务模板 API 为 `/business-templates`，SPA 为 `/templates`，避免路由冲突。复用源记录只保留脱敏结构，手工编辑仅接受白名单蓝图；应用只产生待确认草案，不能绕过原发布权限和确认流程。前端基础编辑与高级蓝图须同步，失败保留输入。

本阶段回归应覆盖默认关闭、重复签到、反馈版本、结束与签到并发、结束后新成员不改变分母、组织失权、模板跨组织/版本/请求冲突和生成新草案。捕获两层 AI 请求，确认反馈原文不进入复盘。自动化测试结果与真实 API、浏览器及外部平台验收分别记录，不据单元测试宣布全部验收通过。
