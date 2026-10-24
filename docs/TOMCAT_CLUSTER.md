# Tomcat 集群部署与课堂演示

## 部署结构

```text
                         ┌──> app1（Tomcat 10.1 + ROOT.war）──┐
浏览器 -> Nginx :18080 ─┤                                       ├──> PostgreSQL
                         └──> app2（Tomcat 10.1 + ROOT.war）──┘
                                      │
                                      └──> FastAPI AI :8000
```

两个 Tomcat 部署的是同一份业务 WAR，访问 Nginx 后看到的是活动管家业务系统，
不是单独的测试页面。

## 关键文件

- `compose.cluster.yml`：Nginx、app1、app2、PostgreSQL、AI 的集群编排。
- `nginx/nginx.conf`：`upstream`、轮询、超时和故障转移配置。
- `Dockerfile`：构建 React、Spring Boot WAR，并部署到外部 Tomcat。
- `src/main/java/.../api/ClusterController.java`：返回当前实例标识。
- `web/src/components/Layout.tsx`：在工作台侧边栏显示当前节点。
- `scripts/run.ps1`：默认启动集群，`-Single` 启动单实例。

## 启动

```powershell
Set-Location "D:\学校\大三上\课程\软件工程\Sailor-Moon"
start.bat
```

首次构建或修改源码后：

```powershell
start.bat -Build
```

入口：

- 业务集群：<http://127.0.0.1:18080>
- app1 直连：<http://127.0.0.1:18081>
- app2 直连：<http://127.0.0.1:18082>

## 给老师演示

一键检查：

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\show-cluster.ps1
```

需要现场演示故障转移时：

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\show-cluster.ps1 -Failover
```

脚本会停止 `app1`、确认请求全部转到 `app2`，然后自动恢复 `app1`。

```powershell
docker compose -f compose.cluster.yml ps
```

应看到 `app1`、`app2` 和 `nginx`，其中两个 app 都是 `catalina.sh run`。

验证轮询：

```powershell
1..10 | ForEach-Object {
    Invoke-RestMethod http://127.0.0.1:18080/cluster/node
} | Group-Object instance | Select-Object Name, Count
```

预期两个节点各约一半请求。登录活动管家后，工作台左下角也会显示当前节点。

验证故障转移：

```powershell
docker compose -f compose.cluster.yml stop app1
Invoke-WebRequest http://127.0.0.1:18080/login -UseBasicParsing
docker compose -f compose.cluster.yml start app1
```

`app1` 停止期间业务页面仍返回 200，`/cluster/node` 全部返回 `app-2`。

## Nginx 配置

```nginx
upstream activity_cluster {
    server app1:8080 max_fails=2 fail_timeout=10s;
    server app2:8080 max_fails=2 fail_timeout=10s;
    keepalive 32;
}
```

- 默认策略为轮询。
- `max_fails=2`：连续失败两次后暂时摘除节点。
- `fail_timeout=10s`：节点摘除观察时间。
- `proxy_next_upstream`：连接失败、超时或 502/503/504 时切换节点。
- Nginx 返回 `X-Upstream-Addr`，可在浏览器开发者工具中看到实际后端地址。

## Tomcat 参数

- 版本：Tomcat `10.1`
- Java：Temurin JDK `21`
- HTTP Connector：容器内 `8080`
- shutdown 端口：容器内 `8005`
- `connectionTimeout`：`20000ms`
- `maxParameterCount`：`1000`
- `maxThreads`：未显式配置，使用 Tomcat 默认 `200`
- `minSpareThreads`：未显式配置，使用 Tomcat 默认 `10`
- `acceptCount`：未显式配置，使用 Tomcat 默认 `100`
- `maxConnections`：未显式配置，使用 Tomcat 默认 `8192`
- AJP 8009：注释关闭
- HTTPS 8443：注释关闭，由入口层处理 TLS
- JVM：`-Xms256m -Xmx512m -Dfile.encoding=UTF-8`
- 业务部署：`webapps/ROOT.war`

注意：官方 `server.xml` 中有一段被 `<!-- -->` 注释掉的示例线程池
`maxThreads="150" minSpareThreads="4"`，它不是当前生效配置。

两个 Tomcat 使用独立的容器网络，因此内部都监听 8080，不冲突。宿主机分别映射为
18081 和 18082。如果使用两台传统解压版 Tomcat，则需要分别修改
`server.xml` 的 shutdown、HTTP 和 AJP 端口。

查看正在运行的实际 Connector：

```powershell
docker compose -f compose.cluster.yml exec -T app1 `
  sh -c "sed -n '60,110p' /usr/local/tomcat/conf/server.xml"
```

## 为什么登录不会丢

项目没有使用 Tomcat 本地 Session。登录 token 存在共享 PostgreSQL 的
`auth_sessions` 表中，两个 Tomcat 都能验证同一个 Cookie：

```text
浏览器 Cookie -> app1/app2 -> 同一个 PostgreSQL auth_sessions
```

上传文件保存在两个 app 共享的 `data/uploads` 目录。后台任务使用
`FOR UPDATE SKIP LOCKED` 抢占，多个实例不会重复执行同一任务。

## 单实例模式

需要 QQ Gateway 时可启动单实例：

```powershell
start.bat -Single -Port 8080
start.bat -Single -WithQq
```

集群模式使用外部 Tomcat；单实例模式仍使用相同 WAR 镜像，只运行一个 Tomcat。
