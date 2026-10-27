# 安装部署与镜像交付

本文对应仓库现有 Dockerfile、Compose 和启动脚本。系统结构见 [ARCHITECTURE.md](ARCHITECTURE.md)，首次业务操作见 [USER_GUIDE.md](USER_GUIDE.md)，数据与迁移见 [DATABASE.md](DATABASE.md)。下列命令在项目根目录执行，环境文件中的真实密钥不进入 Git 或课堂截图。

## 1. 环境与交付内容

推荐 Docker Desktop 的 Linux 容器模式和支持 `env_file.required` 的 Docker Compose。首次源码构建需下载依赖和基础镜像。Dockerfile 使用 Node 22 构建 React、Maven 3.9.9/JDK 21 构建 WAR，最后运行于 Tomcat 10.1/JDK 21；AI 镜像使用 Python 3.12，网关使用 Node 22，数据库为 `pgvector/pgvector:pg16`。

仅在本机直接开发时另装 JDK 21、Maven、Node.js 22、Python 3.11+（容器固定 3.12）。前端构建和测试命令见 [DEVELOPMENT.md](DEVELOPMENT.md)。Spring Boot 版本以 `pom.xml` 的 3.5.16 为准。

课程交付应提供源码、七类团队资料、所选 Compose、对应 Dockerfile、Nginx/Caddy 配置及无密钥环境样例。镜像包另行交付，不把 `node_modules`、`target`、实际 `.env`、数据库、上传文件或个人任务记录混入源码。

## 2. 默认双 Tomcat 集群

```powershell
Copy-Item .env.example .env
.\start.bat -Build -NoBrowser
```

已有对应镜像时可以省略 `-Build`。启动脚本检查 Docker Engine，默认设置入口端口为 18080，启动 PostgreSQL、AI、两个 Tomcat 和 Nginx，并等待入口就绪。访问 `http://127.0.0.1:18080/`；首次访问创建管理者和组织。

```powershell
docker compose -f compose.cluster.yml ps
docker compose -f compose.cluster.yml logs --tail 100 app1 app2 nginx
Invoke-RestMethod http://127.0.0.1:18080/health/ready
Invoke-RestMethod http://127.0.0.1:18080/cluster/node
```

`app1` / `app2` 默认宿主诊断端口为 18081 / 18082；PostgreSQL 仅绑定本机 55432，AI 默认仅绑定本机 8000。脚本通过 `-Port` 改入口，直接使用 Compose 时 `.env` 的 `APP_PORT` 生效；样例值为 8080，不能据此假定直接 Compose 也使用 18080。集群演示及节点切换见 [TOMCAT_CLUSTER.md](TOMCAT_CLUSTER.md)。

## 3. 单实例开发与可选 QQ

```powershell
.\start.bat -Single -Build -Port 8080 -NoBrowser
```

单机使用 `compose.yml` 的 `api` 服务，与集群相同的外部 Tomcat WAR 镜像。两种开发模式默认都使用 `./data/uploads` 和数据库命名卷；切换模式前核对 Compose 项目与卷归属，避免同时占用同一端口或误用另一套数据。

本地 QQ 需要有效机器人凭据。填入 `QQ_BOT_APP_ID`、`QQ_BOT_APP_SECRET` 和独立 `QQ_GATEWAY_TOKEN` 后，脚本可自动启用网关，也可显式传 `-WithQq`。配置机器人凭据并不等于班级已经获得群授权；绑定与选择流程见用户手册。

不配置模型密钥可使用离线建议；`EMBEDDING_BASE_URL` / `EMBEDDING_API_KEY` 留空时使用开发用哈希向量。配置真实嵌入服务时须与数据库 `vector(1536)` 和向量来源一致，旧向量来源不兼容时需重新索引。

## 4. 生产编排

```powershell
Copy-Item .env.production.example .env.production
# 编辑域名、数据库密码、两个不同的内部令牌及按需使用的服务凭据

docker compose --env-file .env.production -p activity-prod -f compose.prod.yml config --quiet
docker compose --env-file .env.production -p activity-prod -f compose.prod.yml up -d --build
```

配置域名 DNS 指向部署主机并开放 80/443。Caddy 负责 HTTPS 与到单 Tomcat 的代理；数据库、AI、网关不发布宿主端口。`COOKIE_SECURE=true` 和 `PUBLIC_BASE_URL=https://域名` 由生产编排注入。检查 Caddy、API 和依赖服务日志以及域名下 `/health/ready`。

生产文件包含单个 QQ Gateway 服务；真正使用 QQ 仍须提供平台凭据并完成业务授权。生产是单个 Tomcat，不能把开发集群的两节点演示结论当作生产高可用承诺。

## 5. WAR 与镜像交付

Docker 多阶段构建顺序是 `npm ci` → 前端构建 → Maven 打包 → 把 WAR 复制为 Tomcat 的 `ROOT.war`。Dockerfile 的 Maven 打包跳过测试，因此发布前须另外执行开发文档中的测试。只运行 Maven 不会自动构建 React，已有 `web/dist` 才能随资源进入 WAR。

如果课程要求独立 WAR，可先执行：

```powershell
npm --prefix web ci
npm --prefix web run build
mvn package
```

WAR 位于 `target/activity-assistant-*.war`，需要 Tomcat 10.1/JDK 21、相同数据库/AI/存储配置，不能部署到旧 `javax.servlet` 容器。正常 Docker 交付使用 `Dockerfile`，无需手工复制 WAR。

离线镜像交付步骤：

1. 在联网机器按选定 Compose 构建业务/AI 镜像；使用 QQ 时也构建网关，并准备对应 PostgreSQL、Nginx 或 Caddy 镜像。
2. 从实际镜像列表核对名称、标签、平台与镜像 ID，记录所用源码版本和 Compose 项目名称。基础镜像浮动标签不能代替实际交付版本记录。
3. 用 `docker image save -o activity-assistant-images.tar` 加上上述实际镜像名导出，连同该版本的 Compose、代理配置及环境样例交付；不要包含真实凭据或业务卷。
4. 目标机执行 `docker image load -i activity-assistant-images.tar`，按交付约定保持项目名称及镜像标签一致，填写本机环境文件后用所选 Compose 启动；核对镜像不存在时先补齐交付包。
5. 运行健康检查和首次登录流程；已有业务迁移则另外恢复持久数据。镜像归档不包含数据库与附件。

## 6. 持久化、升级与恢复

开发/集群持久数据是 PostgreSQL 命名卷、`data/uploads` 以及启用 QQ 后的 `qq_delivery_data`；生产还包括 `uploads_data` 和 Caddy 数据/配置卷。QQ 日志与数据库投递记录共同保留去重和未知结果证据，不能通过删卷解决未知状态。

升级前保存当前源码/镜像版本、Compose 与配置、数据库、附件和网关日志；暂停业务写入并核对在途外发。涉及租约协议的旧 Worker 应先停止，不能与缺少保护的旧版本混跑。新版本由 Flyway 执行增量迁移，不能覆盖旧迁移或通过清库“升级”。

### 6.1 停写后备份

[backup.ps1](../scripts/backup.ps1) 要求显式传入 `-ComposeFile` 和 `-ProjectName`；生产配置另传 `-EnvFile`。先用 `docker compose ls` 核对实际项目名，不能为已有环境临时换名。以下示例对应以 `-p activity-prod` 启动的生产项目：

```powershell
$sourceProject = "activity-prod"
$sourceCompose = "compose.prod.yml"
$sourceEnv = ".env.production"
$sourceArgs = @("--env-file", $sourceEnv, "-p", $sourceProject, "-f", $sourceCompose)
docker compose @sourceArgs --profile qq stop
docker compose @sourceArgs up -d --no-deps --wait postgres
docker ps --filter "label=com.docker.compose.project=$sourceProject" --format "table {{.Names}}\t{{.Status}}"
# 确认该项目仅 postgres 运行，并关闭外部数据库客户端及附件目录写入者
.\scripts\backup.ps1 -ComposeFile $sourceCompose -EnvFile $sourceEnv -ProjectName $sourceProject -IncludeQq
```

暂停范围包括 AI、所有应用节点、代理、网关及同项目残留服务；如列表仍有其他运行容器，先停止对应写入者再备份。保留已创建的 `api`（集群为 `app1/app2`）和网关容器，脚本通过 inspect 获取实际挂载及相对路径对应的 WorkingDir，不使用猜测目录。不要用 `down -v` 代替停写。开发/集群请替换为实际 Compose、环境文件和项目名；未使用 QQ 时省略 `-IncludeQq`。只要该项目已有网关容器，脚本仍会备份其日志；显式传此开关还会拒绝遗漏网关。

默认输出到仓库同级的 `../activity-backups/`，也可用 `-OutputDirectory D:\Backups\activity` 指定仓库外目录。成功目录包含 PostgreSQL 自定义格式 `database.dump`、`uploads.tar`、存在网关时的 `qq-deliveries.tar` 和状态为 complete 的 `manifest.json`。清单保存大小、SHA-256 和源挂载标识，校验后才把临时目录改为最终目录；仅文件存在不能说明备份成功。脚本不归档 Compose、`.env` 或其他配置；版本、配置和密钥须另行安全保管，数据库及附件备份本身也须限制访问。Caddy 证书/配置卷不在该脚本范围内。

备份和恢复均要求 Linux 容器、已就绪的 PostgreSQL 镜像及其中的 GNU tar/pg_restore；脚本不会拉镜像，也不会替操作者停止或启动服务。完成备份后，由操作者按原编排恢复源服务。

### 6.2 恢复到全新隔离项目

[restore.ps1](../scripts/restore.ps1) 只接受与清单源项目不同的显式项目名、空数据库和空的附件/日志存储。它核对校验和、归档路径及类型、数据库格式和实际挂载隔离，拒绝覆盖、源目标共卷及彼此重叠的目标路径。只恢复可信来源；校验和只能检测损坏，不能证明 SQL 内容可信。

以下使用原版 `compose.prod.yml` 的项目范围命名卷，准备一个从未使用的新项目；先检出与备份匹配的源码/部署版本。若修改过卷的 `name:`、`external:` 或使用 bind 挂载，须先改成独立空存储。开发文件的 `./data/uploads` 不会因更换项目名自动隔离，不可原样作为恢复目标。

```powershell
# 改为上一步输出的完整备份目录
$backupDirectory = "D:\Backups\activity\20261027T010000Z-12345678"
$targetProject = "activity-recovery-" + (Get-Date -Format "yyyyMMddHHmmss")
$targetCompose = "compose.prod.yml"
$targetEnv = ".env.production"
$targetArgs = @("--env-file", $targetEnv, "-p", $targetProject, "-f", $targetCompose)
$manifest = Get-Content -LiteralPath (Join-Path $backupDirectory "manifest.json") -Raw -Encoding UTF8 | ConvertFrom-Json
$stores = @("api")
if (@($manifest.storage.kind) -contains "qq-deliveries") { $stores += "qq-gateway" }
# 仅准备同版镜像和停止状态容器，不启动 Java/Flyway、AI 或网关
docker compose @targetArgs build @stores
docker compose @targetArgs create --no-build --no-deps @stores
docker compose @targetArgs --profile qq stop
docker compose @targetArgs up -d --no-deps --wait postgres
docker ps --filter "label=com.docker.compose.project=$targetProject" --format "table {{.Names}}\t{{.Status}}"
.\scripts\restore.ps1 -BackupDirectory $backupDirectory -ComposeFile $targetCompose -EnvFile $targetEnv -ProjectName $targetProject
```

准备步骤可用已交付的同版镜像代替构建，但应先确认新项目能引用正确镜像。恢复前不能启动应用来“先建表”；Flyway 表和业务表已存在也会被视为非空并拒绝。目标是否创建 QQ 容器须与备份一致。脚本不创建/删除数据库，不清空目录；失败时保留隔离目标供检查，诊断后换新的空目标重试，不自动抹除部分恢复内容。

恢复完成后应用、AI 和网关仍保持停止。先核对迁移历史、组织/成员及业务记录，再在隔离配置下检查原附件下载、权限、任务和投递记录；成功标准包括数据库和真实文件内容一致。演练环境启动前要隔离端口、域名、定时提醒及实网 QQ/模型凭据，不能直接整栈启动生产副本。注意 Compose 的 `--env-file` 只控制变量插值，不会替换服务中固定的 `env_file: .env.production`；演练须使用独立配置目录/文件并检查服务实际配置。是否切换业务入口由操作者核对结果后决定。

`test-backup-contract.ps1` 提供不调用 Docker 的安全契约检查，不能代替上述真实恢复与业务核对。测试与统一检查命令见 [DEVELOPMENT.md](DEVELOPMENT.md)。

## 7. 常见定位

- **Docker Engine 不可用**：先启动 Docker Desktop，待 Engine 可响应再重试启动命令。
- **页面仍是旧版本**：源码变化后使用 `-Build`，确认入口连接到对应 Compose 项目。
- **API 未就绪**：看数据库健康、Flyway、AI 和 Tomcat 日志；AI 的 readiness 主要检查数据库连通，不能证明真实模型或 QQ 可用。
- **端口冲突**：启动脚本用 `-Port` 调整入口；诊断端口及依赖端口还须与所选 Compose 配置一致。
- **报名链接域名错误或写入被拒**：核对外部访问地址与 `PUBLIC_BASE_URL`、代理及 Cookie 配置。
- **QQ 失败/unknown**：核对凭据、当前组织群绑定、网关健康及持久结果，再使用授权核对入口；不要盲目重发。

本文是部署说明，不能替代具体环境的构建、回归与恢复验收记录。
