# 文件上传下载：验收与录屏讲解

独立文件中心已移除。本文保留原课堂演示步骤和验证证据作为历史记录。当前版本可按「可复现验证」一节检查文件传输 API。

## 原演示的五项要求与实现位置

| 要求 | 实现与讲解重点 |
| --- | --- |
| 大文件切片、合并、损坏重传 | `web/src/pages/Files.tsx` 中 `selectFile/runUpload`；`file-hash.ts` 每次仅读取2MiB增量计算SHA-256；`FileController.putChunk` 限制单次读取8MiB；`FileService.putChunk/complete`；`StorageService.mergeChunks` 合并前重验磁盘分片，顺序合并并返回整文件哈希 |
| 小文件批量上传 | 前端 `uploadBatch` 将多个文件追加为同名 `files` 字段；后端 `/files/batch` 逐个处理，返回每个文件的 `ok/error`，不会把HTTP失败当成功 |
| 上传与下载断点续传 | 上传：IndexedDB保存会话及整文件指纹，刷新后重选原文件、核对SHA-256、查询服务端 `uploaded`，只发送剩余片；下载：把真实Blob块写入IndexedDB，刷新后读取已落盘的块，只请求缺块。通过 `Range/If-Range/ETag` 避免新旧文件混拼 |
| 进度条 | XHR上传进度按 `loaded/total` 计算；大文件为“已确认字节+在途字节”；下载为“已缓存字节+正在接收字节”。发送到100%仍要等待合并/校验；只有业务确认完成后显示成功。重试不会重复累计 |
| 高并发下载 | 客户端至多4条Range连接；服务端请求各用独立FileChannel，不把整文件读进内存。Java21虚拟线程、Nginx连接池/轮询，以及每节点默认128个下载许可共同控制资源。超过许可返回429和Retry-After。多个节点必须共用数据库和上传目录 |

## 原演示操作顺序

1. 选择一个约12MiB的测试文件，勾选“故意损坏一个分片”；观察日志出现 `CHUNK_CHECKSUM_MISMATCH`，自动重传该片。
2. 上传一部分后点击暂停，记录已确认字节，刷新页面。重新选择同一原文件，观察“恢复服务端已确认”的日志及只上传缺片；更换内容的文件会被SHA-256检查拦下。
3. 一次选择多个小文件，观察每个结果及总进度；包含空文件时展示该项失败，其他项照常处理。
4. 对刚上传的文件下载，等至少一个2MiB分块落入浏览器缓存后暂停并刷新，再点同一文件“下载/续传”或“4连接并发下载”。完成后核对输出SHA-256。
5. 用代码讲清：前端怎样切片、后端怎样通知坏片、怎样恢复上传会话和下载数据、两类进度怎样计算，以及“单文件4连接”与“服务端多用户并发”之间的区别。

## 错误恢复协议

- 分片PUT必须提供合法 `X-Chunk-Sha256`。内容不匹配：HTTP400，`code=CHUNK_CHECKSUM_MISMATCH`、`chunk_index`、`expected`、`actual`；这一片不写入磁盘和数据库。
- 合并时分片缺失或落盘后损坏：HTTP409，`code=MISSING_CHUNKS`、`missing:[序号...]`。前端仅把这些分片置为待传，最多执行有限轮修复，持续故障时暂停供用户处理。
- 同一上传任务用PostgreSQL行锁协调PUT、complete和abort，可跨应用实例；数据库唯一索引防止同一个upload_id产生多个最终文件。complete重试返回原file_id。临时分片在事务提交后清理。
- 下载支持闭区间、开放区间及后缀Range。满足范围返回206和精确Content-Range；无效范围返回416及总长度；If-Range不匹配则返回200整文件，客户端拒绝将其当作续传片段拼接。HEAD不写响应体。
- 上传每片最多3次网络/校验重试；永久权限错误立即停止；不会无限重试耗尽带宽。下载繁忙/网络异常也采用有限退避。

## 可复现验证

```powershell
npm ci --prefix web
npm test --prefix web
npm run build --prefix web
mvn -B -ntp verify
python scripts/verify_file_transfers.py --java "C:/Program Files/Java/jdk-21/bin/java.exe" --war "<构建目录>/target/activity-assistant-2.0.0.war" --output "D:/tmp/sailor-files-proof"
```

API 验收使用 Python `httpx` 和 Docker 中的 `pgvector/pgvector:pg16`。脚本仅创建带随机名称的测试容器，使用18543/18403端口和输出目录内的测试文件；端口占用时直接停止，不连接现有业务数据库。退出时只结束自己启动的进程树和测试容器。

本机中文路径下Maven重新打包出现文件重命名失败；完整验收在纯英文路径的隔离副本中完成，副本代码与工作区一致。遇到相同问题时可把工程复制到英文目录再构建，切勿把数据库或.env上传到仓库。

验证证据见 [file-transfers.json](verification/file-transfers.json)：后端全量252项测试、前端25项测试、独立PostgreSQL真实接口与浏览器流程10项检查均通过。并发验收为24个工作线程、120个Range请求，逐字节核对31,457,280字节；实际耗时是本机Python客户端和本机服务综合结果，不代表生产环境吞吐上限。视频和大测试文件保留在工作区外。

## 部署与边界

- 普通启动使用Java21的Spring Boot虚拟线程；Docker及课堂集群的外部Tomcat连接器也显式启用虚拟线程。虚拟线程减少阻塞连接占用平台线程，不会增加磁盘带宽，仍要按机器调整下载许可、数据库连接池和Nginx容量。
- 反向代理请求上限128MiB，配合24×5MiB的小文件批量上传；分片上传每次最多8MiB，整体最高2GiB，还受账号总配额限制。大文件哈希和合并均使用固定大小缓冲。
- 浏览器下载缓存受IndexedDB可用空间限制；无痕模式、清除站点数据或磁盘配额不足会影响续传。界面提供缓存清理按钮。刷新恢复是同一浏览器、同一账号、同一站点的恢复，不是跨设备续传。
- 上传刷新后需要重新选择原文件，因为浏览器不会让网页在未授权情况下重读用户磁盘文件；完整SHA-256用于确认内容，而非仅靠文件名判断。
- 下载完成后保留缓存便于再次保存；文件变化、校验不一致或用户手动清理后会重新获取。网络发送进度与服务器业务完成状态分别展示。
- 代码支持集群共享上传目录；仅本机单服务进行了此次文件并发实测。不能把4路并行或虚拟线程配置本身当作千人同时下载的压力测试结论。

## 官方参考

- [Tomcat虚拟线程执行器](https://tomcat.apache.org/tomcat-10.1-doc/config/executor.html)
- [HTTP Range](https://developer.mozilla.org/en-US/docs/Web/HTTP/Reference/Headers/Range)
- [HTTP If-Range](https://developer.mozilla.org/en-US/docs/Web/HTTP/Reference/Headers/If-Range)
- [IndexedDB](https://developer.mozilla.org/en-US/docs/Web/API/IndexedDB_API)
- [hash-wasm增量哈希](https://github.com/Daninet/hash-wasm)
