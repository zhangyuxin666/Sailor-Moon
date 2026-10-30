# PostgreSQL 主从复制与读写分离

系统提供可选的“一主一从”：主库 `postgres` 接收全部写入，从库 `postgres-replica` 使用 PostgreSQL 16 物理流复制。两个 Tomcat 各有主库连接池和独立只读连接池。普通单库部署仍可运行；仅启动两个 Tomcat 不代表启用了数据库读写分离。

## 1. 启用与升级

在已有 `.env` 中新增以下配置；不要覆盖原有环境文件，也不要将真实密码提交到 Git：

```dotenv
DB_READ_PASSWORD=replace-with-a-long-random-reader-password
DB_REPLICATION_PASSWORD=replace-with-another-long-random-replication-password
```

在项目根目录执行（需要 Docker Desktop Linux Engine）：

```powershell
.\start.bat -ReadWriteSplit -Build -NoBrowser
# 单 Tomcat 也可演示数据库读写分离：
# .\start.bat -Single -ReadWriteSplit -Build -Port 8080 -NoBrowser
```

启动脚本保留 `postgres_data`，为主库增加 WAL/复制认证配置，运行可重复的账号与权限初始化，再将主库复制到独立的 `postgres_replica_data`。旧主库已有数据也支持此流程，不依赖只在空目录执行的 initdb 脚本。主库容器配置更新会导致短暂重启，执行前保存操作并安排维护时间。首次复制耗时取决于数据库大小。

`replica-setup` 退出码为 0 是正常完成；应用启动初期从库尚未就绪时查询回主库。启用后，后续启动仍使用 `-ReadWriteSplit`，或在 `.env` 中设置 `DB_READ_ENABLED=true` 让普通启动自动启用。若要回到单库，将其设为 `false` 并省略该参数；集群启动脚本的孤儿容器清理会移除从库容器，命名卷保留。

直接运行 Compose 时，必须同时设置 `DB_READ_ENABLED=true` 并叠加文件：

```powershell
$env:DB_READ_ENABLED='true'
docker compose -f compose.cluster.yml -f compose.replication.yml up -d --build
```

单机将基础文件改为 `compose.yml`。生产使用 `--env-file .env.production -f compose.prod.yml -f compose.replication.yml`，在 `.env.production` 中设置上述密码、`DB_READ_ENABLED=true` 和原有 `POSTGRES_PASSWORD`。默认数据库名/主账号为 `activity`，与三个标准 Compose 文件一致。复制与只读密码必须不同于主账号密码。

## 2. 哪些查询去哪里

- 从库候选：活动列表、活动详情中的执行步骤和投递记录、活动变更记录、本人通知列表。
- 主库：所有增删改、登录会话、账号/组织/班级权限校验、报名容量与回执、签到反馈、任务状态、工作流租约、复盘生成和历史、CSV 一致性导出、Flyway 迁移、AI 知识库访问。
- 所有 Spring 事务保持主库连接，包括只读事务；不按 HTTP GET 或 SQL 文本自动猜测分流。

实现入口是 `Db.readList` 和 `ReplicaReads`。现有 `Db.one/list/update` 始终使用主库。活动列表的权限条件也包含在其查询中；分流前取得主库 WAL 位置作为同步屏障，确保从库已包含该时刻前提交的权限及业务变化。

每次分流先在主库取得 `pg_current_wal_lsn()`，再在同一只读从库连接上检查 `pg_is_in_recovery()` 和 `pg_last_wal_replay_lsn()`，确认追上后才查询；检查与查询使用 READ COMMITTED。落后、查询错误、从库不可达或从库已被提升为主库时，直接读取主库。应用不会返回“报名成功但刚提交的数据消失”的旧快照，也不把事务内未提交的数据交给从库。主库屏障查询仍有少量开销，这种方案优先保证课程项目的正确性，并非所有查询都能卸载。

从库连接使用 `activity_reader`：仅授予 public 表 SELECT、数据库连接和 schema 使用权限，并设置默认只读事务。主库上的默认表权限覆盖后续 Flyway 创建的表。复制使用独立的 `activity_replicator` 账号。主从之间使用 Compose 内部网络和密码认证，从库不映射宿主端口。

## 3. 如何证明已经分流

```powershell
docker compose -f compose.cluster.yml -f compose.replication.yml ps -a
docker compose -f compose.cluster.yml -f compose.replication.yml exec -T postgres psql -U activity -d activity -c "SELECT application_name,state,sync_state FROM pg_stat_replication"
docker compose -f compose.cluster.yml -f compose.replication.yml exec -T postgres-replica psql -U activity -d activity -c "SELECT pg_is_in_recovery(),pg_last_wal_replay_lsn()"
```

预期复制状态 `streaming`、模式 `async`，从库 `pg_is_in_recovery()` 为 `t`。管理者登录后在同一浏览器打开 `/cluster/database`，查看 `enabled=true`；访问活动列表/详情或通知后，`replica_queries` 应增长。从库短暂落后可能使 `primary_fallbacks` 增长，刷新后再观察。计数按 Tomcat 实例独立累计，重启归零；集群比较时使用同一个节点的 18081 或 18082 地址并在该地址登录。状态接口不暴露连接串或密码，普通成员无权查看。

可在维护或独立测试环境停止从库，再验证列表仍可访问、fallback 增长；恢复从库后查询会重新分流。主库宕机时写入和权限判断仍会失败，不会自动将从库提升。

## 4. 自动验收

先执行 Java 21/Maven 的 `mvn verify` 生成 WAR，再运行：

```powershell
python scripts/test_readwrite.py --output ..\项目改进记录\辅助资料\readwrite-acceptance.json
```

脚本只创建随机命名的独立 Compose 项目、动态端口和测试数据；先在普通单库中创建账号和业务数据，再为同一数据卷启用复制。验证两个 Tomcat 共享登录并实际分流、只读账号及后续新表权限、复制暂停后的最新数据回读、从库断开降级、恢复后的分流及登录权限即时生效，最后清理自己的测试容器和卷。需要 Docker、Python 3.12 和已构建 WAR；CI 也执行此验收。报告必须放项目外。

## 5. 备份、恢复与故障边界

- 备份仍以主库逻辑备份为准，从库和流复制不能替代备份；误删会被复制。
- 使用现有备份/恢复脚本前，停止除 `postgres` 外的全部服务，包含 `postgres-replica`；脚本会拒绝仍有其他服务运行的情况。
- 恢复到全新项目时先只启动主库并创建应用容器挂载，按原恢复手册完成恢复，再首次启动复制。不要把旧从库卷接到恢复后的主库上。
- 复制槽 `activity_read_replica` 的 WAL 保留上限为 1GB。从库长期离线超过保留范围可能需要重新做基准备份。不要删主库卷；在核对项目与卷名后，为从库选择一个新的空卷重建。脚本拒绝覆盖非空/非 standby 目录；失败的半成品卷也需要人工核对后换新卷。
- 关闭读写分离后若长期不再使用复制，在确认从库已停且槽不活跃后，由维护者删除该复制槽，避免继续保留 WAL。
- 密码修改后需重新运行 `replica-setup` 并重新创建从库及应用容器，使连接凭据一致。
- 此功能为异步复制、读分流与回主库降级；没有实现自动主库选举或自动故障切换，也不会把故障从库当成新的写入目标。

原理参考：[PostgreSQL 流复制](https://www.postgresql.org/docs/16/warm-standby.html)、[pg_basebackup](https://www.postgresql.org/docs/16/app-pgbasebackup.html)。

## 6. 已验证的范围

2026-10-29，本地 Java 21 构建与 231 项 Java 测试通过，单机/集群/生产配置叠加复制配置的校验通过。独立 Docker/PostgreSQL 16/双 Tomcat 环境中，`scripts/test_readwrite.py` 的 8 组验收全部通过：

1. 已有账号和业务数据的普通单库升级为主从流复制，原数据保留。
2. 两个 Tomcat 共享登录并实际通过只读从库查询业务数据。
3. 只读账号能查询后续创建的新表，即使关闭默认只读选项也无删除权限；从库本身拒绝写入。
4. 暂停复制后新提交的数据通过主库回读，不返回过期结果。
5. 从库停止时，两个 Tomcat 的业务查询继续可用。
6. 从库使用原有数据卷重启后，重新连接主库并恢复分流。
7. 重复初始化及主库重启后，业务数据与复制连接均保留。
8. 从库落后时，在主库撤销会话仍立即阻止后续访问。

测试只使用独立项目与测试数据，结束后清理其容器、卷及临时应用镜像；正常项目需按第 1 节重新构建启动才会启用新功能。CI 已接入同一脚本，远端执行结果以 GitHub Actions 为准。
