# rx-mimic 远程异步任务接入与执行记录

日期：2026-10-20（Asia/Singapore）。关联：[实施计划](plan/remote-service-async-rpc-plan-2026-10-20.md)。

2026-10-21 CI配置已精简为一个Secret，完整填写步骤见 [AnZhi GitHub Actions 配置](github-actions-anzhi-setup-2026-10-21.md)。

## 接入约定

运行机为 `anzhi-pc\anzhi`，应用根目录 `D:\rx-mimic`，Java 21、本机 Chrome、有界面 InteractiveToken 计划任务。HTTP 仅监听 `127.0.0.1:81`，真实 profile 为 `profiles\common`，数据与版本目录分离。外部只接入新 v1 API；历史 Cookie、profile、同步接口和管理入口不经 Nginx 暴露。

链路固定为：调用方 → `https://cloud.f-li.cn:6400/crawler/api/v1` → rssClient Nginx → `127.0.0.1:6020` STCP visitor → rssClient frps → AnZhi `D:\home\.app\frp` 的 frpc → `127.0.0.1:81/api/v1`。rssServer 保持原主线路职责，不迁移 AnZhi SSH 或桌面映射。

不启动 `jmx_prometheus_javaagent.jar`，不需要 `jmx_prometheus_config.yaml`，不新增 JMX exporter 8080 监听。任务指标、JVM 堆/堆外与 Chrome RSS 先通过健康接口、运行日志及受控主机观测记录。

## 现场基线

2026-10-20 本轮只读 SSH 检查：

- SSH `cloud.f-li.cn:6014` 的 ECDSA nistp384 指纹与原计划记录一致：`SHA256:zm9KGOp8b2IRbTjDmlIpMLqNJBGlMszeIZCEvm1omLc`。这是连续性校验，首次信任仍需独立渠道核对。
- AnZhi console 会话 Active；运行根目录尚不存在；81/8080/1221/8505/8506 未发现监听。
- 远端 FRP 文件 `D:\home\.app\frp\anzhi\anzhi-frpc.toml`，上游 `cloud.f-li.cn:4000`，SSH `22→6014`，桌面接管 `5900→6017`。
- rssClient `192.168.31.4` 的 frps/frpc-main 容器运行；4000、6014、6017 监听；6020 未发现监听。`nginx -t` 成功。
- rssClient 的 8080 已由其他 Docker 服务监听；无需变更它，不把“移除 exporter”解释为释放此主机现有 8080。
- 凭据只从仓库外 `D:\home\.envKeys\ssh.json` 和 `rss-env/readme.md` 原始连接资料读取，不复制到本文。

## 调用方接入任务

| 编号 | 工作 | 验收 |
| --- | --- | --- |
| A1 | 为每个调用方外置配置独立 Bearer key | 空配置拒绝访问；不同调用方不能查看、取消、恢复彼此任务 |
| A2 | 将 base URL 设为 `https://cloud.f-li.cn:6400/crawler/api/v1` | 不使用 SSH 6014 或 Remoting 1221 作为 HTTP 地址 |
| A3 | 提交任务携带 `Idempotency-Key`，保存字符串 taskId | 同调用方/action/key 和相同参数返回原任务；参数不同 409 |
| A4 | 根据状态轮询，等待失败/超时保留 taskId | 本地等待到期只停止等待；不自动创建新任务或取消服务端任务 |
| A5 | 处理 WAITING_USER，人工接管后显式 resume | 人工确认不等于业务成功；重新检查真实登录态 |
| A6 | 完成后读取 result；显式取消使用 cancel | 未完成 409；过期 410；未知或无权 404 |
| A7 | 验证断线、重试、服务重启 | 已确认落库任务和幂等记录可找回，副作用任务不盲目重放 |

请求包装沿用 `success/code/message/data`。例如：

```http
POST /crawler/api/v1/tasks HTTP/1.1
Host: cloud.f-li.cn:6400
Authorization: Bearer <caller-key>
Idempotency-Key: <stable-business-request-key>
Content-Type: application/json

{"action":"jdProductDetail","request":{"skuId":"100059484008","downloadImages":false}}
```

提交成功为 HTTP 202；状态查询 `GET /tasks/{taskId}`；结果 `GET /tasks/{taskId}/result`；取消 `POST /tasks/{taskId}/cancel`；恢复 `POST /tasks/{taskId}/resume`。所有路径均相对 base URL，taskId 始终按字符串处理。

支持 action：`jdSearchProduct`、`jdProductDetail`、`tbSearchProduct`、`tbProductDetail`、`jdUnionSearchProducts`、`tbUnionSearchProducts`、`getPromotionUrl`、`getTbPromotionUrl`、`getPromotionOrders`、`getTbPromotionOrders`、`loginCheck`、`getPromotionUrls`、`getTbPromotionUrls`。批量推广 request 使用字符串数组，其他沿用 crawler-api 中对应平台请求 DTO；profile、文件输出路径和浏览器预检设置由服务端管理。

`crawler.task-api.api-keys.<callerId>` 外置配置密钥；`crawler.task-api.public-base-path=/crawler/api/v1` 确保返回地址包含代理前缀。容量默认全局100、每调用方50、提交每分钟60、批量上限20；队列等待1800秒、普通执行600秒、订单1800秒、人工等待600秒、结果保留604800秒。生产值需按真实任务耗时调整。

Java 调用方可使用 `crawler-api` 的 `AsyncTaskClient`：

```java
var client = new AsyncTaskClient(URI.create("https://cloud.f-li.cn:6400/crawler/api/v1"), apiKey);
var accepted = client.submit("jdProductDetail", request, businessRequestKey);
String taskId = accepted.getJSONObject("data").getString("taskId");
// 持久保存 taskId；TimeoutException 只结束本次等待，之后可继续 status/result。
var status = client.await(taskId, Duration.ofMinutes(10));
var result = client.result(taskId);
```

批量任务返回父 `taskId` 与 `childTaskIds`；每个关键词独立执行，父任务结果保留逐项成功/失败。恢复仅重跑人工等待子项，已成功子项保持结果。`cancel` 表示请求取消；正在执行的页面操作只能在返回安全点结算取消/超时，不保证硬性抢占时限。

## 运维落地顺序

1. 本地构建、定向测试，按 AGENTS 要求构建并同步 crawler-api 至相邻 xiaofan；保留目标旧包备份与哈希。
2. 检查 AnZhi 活动桌面、Java/Chrome、目录权限、FRP 版本、已有进程/任务；建立运行目录，外置 API/admin/STCP 凭据，不覆盖已有数据或 profile。
3. 使用 `deploy/app-crawler` 脚本注册仅用户登录时运行的计划任务，首次部署不可变制品并校验 SHA256；健康检查必须验证版本、数据库与桌面。
4. 网络模板位于 `deploy/network/`。现场使用独立 `D:\rx-mimic\config\frpc.toml` 与 `rx-mimic-frp` 交互计划任务，复用原 `D:\home\.app\frp\frpc.exe`，不重启原 `anzhi-frp`。连接/token 沿用原配置来源；publisher 与 visitor 均使用 `user="rx-mimic"`，STCP 密钥只保存在受限配置中。
5. rssClient 独立 visitor 进程/容器使用 `rssclient-crawler-visitor.toml`，host 网络仅绑定 `127.0.0.1:6020`，`RX_MIMIC_FRP_TOKEN` 使用现有 frps token，两个 STCP 端共享随机密钥。配置和环境文件权限600，不提交仓库。先 `frpc verify`，再启动并确认只绑定回环。STCP 不需要新公网 remotePort。
6. 备份 rssClient `/home/nginx/conf-http/k8s.conf`，把 `rssclient-crawler-locations.inc` 引入现有 `cloud.f-li.cn:6400` server 块，`nginx -t` 后平滑 reload。仅转发 `/crawler/api/v1/`，不将 `/health`、`/internal` 或 `/custom` 暴露。
7. 验证无 key/错 key拒绝、正确 key提交/查询、跨调用方隔离，外网入口能取回断线任务；人工登录 TB/JD、断线恢复及超时取消逐项验收。
8. GitHub `rx-mimic-production` 环境只配置一个 `ANZHI_SSH` JSON Secret（`endpoint/password/hostKey`，host+port+账号合并在endpoint），AnZhi 用户级 `RX_MIMIC_ADMIN_KEY` 与外置应用 caller key；核对环境权限；首期仅手动发布。真实演练 drain、上传失败、哈希不符、健康失败与回滚。

FRP 模板字段参考 [FRP v0.71.0 官方配置](https://raw.githubusercontent.com/fatedier/frp/v0.71.0/conf/frpc_full_example.toml)。本轮已用本机 frpc 0.71.0、占位环境变量验证两份 TOML，均输出 syntax is ok；这不代表远端加载或 STCP 链路验收。

## 执行状态与验证

本轮已完成本地实现、统一构建、真实 JAR 冒烟、AnZhi 首次部署及公网接入。京东与淘宝核心详情链路已实测；尚未提交/推送代码、配置 GitHub secrets、触发 CI 或切换 xiaofan 生产调用端。

- 应用目录：`D:\rx-mimic`（用户本轮明确要求 D 盘根目录下新建）；首次部署前目录和同名任务均不存在。
- release：`8a60682fccbca1e6069640eeefa3f2dff09f10ea`；这是本地工作树内容摘要的前40位，**不是已提交的 Git commit**。基础提交 `e35b4755183577584d90dc4996f5b0e56bdf24ac`；完整源码摘要 `8a60682fccbca1e6069640eeefa3f2dff09f10ea66a924282564bdfb43cb479b`，见远端 `releases/<release>/manifest.json`。
- 本地/远端 JAR SHA256：`70efa26664194b894918895ef1b7805c38f651f9535968335da32e7eabae44c2`，发布脚本校验一致。
- 应用任务 `rx-mimic`：AnZhi InteractiveToken，登录触发，失败最多3次重试；独立 FRP 任务 `rx-mimic-frp` 同样使用登录触发及有界重试。
- AnZhi 仅应用 HTTP `127.0.0.1:81`；81之外未新增1211、1221、8080监听。真实 Chrome 预热成功，desktop/browser/database/scheduler/ready 全为 true。
- 应用停止、重启演练通过：旧 PID 9852优雅退出，新 PID 2692接管，相同 release；真实 D 盘数据库重开和 Chrome 重建后 readiness 全通过。PID只为当轮证据，不用作未来操作目标。
- 公网 TLS 验证正常；无 key401、错 key401、未知任务404、非法 action400。真实京东 taskId=`1`、淘宝 taskId=`2` 均202入队，重复同幂等键仍返回原ID，返回地址保留 `/crawler/api/v1` 前缀。
- 本机凭据路径已登记到 `~/.agents/installed-tools.json` 的 env-key 项，登记中没有密钥值。调用方及管理凭据只保存在本机受限 `D:\home\.envKeys\rx-mimic-access.json`；AnZhi `D:\rx-mimic\config\application-prod.yml` 及 `frpc.toml` 目录 ACL 已收紧。新增 AnZhi 用户级环境变量 `RX_MIMIC_ADMIN_KEY`，启动脚本主动读取，不需要改系统变量或重启机器；普通既有终端需刷新环境才可直接使用。

本地统一测试：52项，0失败/0错误/0跳过，`package BUILD SUCCESS`。真实 JAR 通过上下文启动、503未就绪、401鉴权、404跨调用方隔离、409未完成结果、legacy404、字符串taskId/Location前缀、进程重启后任务/幂等恢复、取消与优雅退出。按仓库要求另构建 crawler-api，复制至 `../xiaofan/xiaofan-bootstrap/lib/org/rx/crawler-api/1.0/crawler-api-1.0.jar` 并 `install:install-file BUILD SUCCESS`；源/目标SHA256均 `adcbb19d672601a7fc5cfceec1545c01f3df43cec6dc637ca4270105d16c74a6`。仓库约定中的 `mvn ... jar` 不是有效生命周期，实际使用 `package` 完成同一制品构建。

本机 T: 临时卷曾令 H2 重开误判只读：`File.canWrite=true`，但 `Files.isWritable=false`/provider检查报 Incorrect function。原样数据库复制到普通 C: 临时目录后可写、ALTER/UPDATE成功；T:新建数据库即使显式SHUTDOWN后仍复现。没有据此认定数据库损坏，也未绕过 H2 权限检查。AnZhi D: 已实际执行 Java NIO 写探针并通过，服务重启通过。

| 阶段 | 本轮产物 / 状态 | 上线剩余条件 |
| --- | --- | --- |
| P0 | 实际基线、重复路由修复、健康/安全脚本；交互任务已注册并启动验证 | SSH首次信任的独立渠道核对仍待完成 |
| P1 | v1/归属/幂等/容量/队列/批量/取消/恢复/固定路径/owner线程，52测与真实JAR重启通过 | 已验证京东人工恢复与双平台核心详情；其余action、执行中崩溃和极端超时演练待做 |
| P2 | 手工首发与FRP/Nginx已生效；哈希、健康、优雅退出/重启通过，workflow已实现 | 提交审核后的代码、配置GitHub环境凭据、自动发布及真实失败回滚演练 |
| P3 | Java `AsyncTaskClient` 与本接入任务文档 | xiaofan等已有生产调用方按业务逐项迁移，当前未切换其线上endpoint |

CI 固定构建 `RockyLOMO/rxlib@f831f76cde8257d8a33188ab37f315c72c456b43`，先 Java 8 编译依赖、再 Java 21 编译 rx-mimic。该依赖完整 reactor 的本地 Java 8 `package` 已成功；本地 `install` 最后替换缓存 JAR 遇到 Windows 文件锁，未强制删除。CI 实际运行仍待提交后验证。

离线部署演练使用独立临时根、模拟管理接口与进程，不注册或启动真实服务：PowerShell 5.1/7 各9项检查通过，覆盖成功切换、drain忙超时恢复、健康失败且回滚成功仍报发布失败、回滚健康失败、shutdown拒绝时不强杀、PID复用拒绝、坏哈希、非法版本路径。模板两份 TOML 校验通过；Nginx片段在rssClient临时独立配置中 `nginx -t` 成功，临时语法验证通过后，正式片段已安装并平滑 reload，线上Nginx active。

`Initialize` 安装的 `scripts/service.ps1` 不随每次发布自动覆盖；升级启动器需要单独审核/备份。发布只能选择包含本轮实现且可从默认分支到达的完整 SHA，不把本地未提交代码标记成已发布版本。

## 真实平台验收记录

京东 taskId `1` 在首次进入登录/安全验证页后转为 `WAITING_USER / LOGIN_REQUIRED`。用户在AnZhi真实Chrome手工完成验证，本轮调用同一taskId的resume，重新检查登录态后成功；没有新建重复任务。状态最终 `SUCCEEDED / SUCCESS`，退出HTTP客户端后仍可取回结果。

| 项目 | task 1 实际返回 |
| --- | --- |
| SKU | `100059484008` |
| 标题 | 韩束白蛮腰防晒乳30ml防晒霜美白亮肤京东自营防紫外线SPF50+生日礼物 |
| 展示价格 / 店铺 | ¥25 / KANS韩束京东自营旗舰店 |
| 当前商品好评 | 99%好评 |
| 图片与规格 | 主图9、详情图22、规格12；本次不下载图片文件 |
| 指纹与登录 | fingerprintPassed=true，loginRequired=false |

淘宝 taskId `2` 在京东完成后自动领取；用户在真实Chrome完成淘宝登录及滑动验证。测试商品 `601440653278` 最终显示不存在，任务为 `SUCCEEDED`、业务为 `NOT_FOUND`，未伪造成抓取到商品。当前验证了淘宝执行与无商品结果交付；后续已按用户要求通过联盟搜索随机抽取当前商品，并完成正向详情回包，见下表。商品价格仅为此验收任务实际页面返回值。

联盟搜索与随机商品正向验证：

| taskId | 操作 | 实际结果 |
| --- | --- | --- |
| 3 | `tbUnionSearchProducts`，关键词“纸巾”，maxResults=5 | `SUCCEEDED/SUCCESS`，返回5条，联盟总命中89501 |
| 4 | 尝试用选中联盟加密ID经现有转链接口取真实商品链接 | `FAILED/BUSINESS_FAILED`，当前登录账号未找到配置中的既有推广位“5”；未新建推广位。转链业务仍需配置实际存在的推广位后单独验收 |
| 5 | 对随机选中的商品标题执行主站搜索 | `SUCCEEDED/SUCCESS`，返回5条；其中1条标题与店铺均完全匹配随机样本，定位真实商品ID |
| 6 | `tbProductDetail`，`978367204794`，不下载图片 | `SUCCEEDED/SUCCESS`，返回下述详情 |

随机方法：从task3实际返回的5条中用 `secrets.choice` 等概率选择，选中第2条；未用固定旧商品ID冒充搜索结果。联盟 `itemId` 是加密 outputMktId，不能直接当淘宝数字ID；主站搜索仅用于定位同标题、同店铺的真实商品链接。

| 项目 | task 6 实际返回 |
| --- | --- |
| 商品ID | `978367204794` |
| 标题 | 【李佳琦直播间双11尾款日】可心柔乳霜纸云柔巾抽纸M码100抽48包 |
| 店铺 / 详情页展示价 | corou可心柔旗舰店 / 239 |
| 图片与规格 | 主图3、详情图0、规格1；未下载图片文件 |
| 指纹与登录 | fingerprintPassed=true，loginRequired=false |

联盟展示价178.00与主站/详情展示价239属于不同页面实际返回，未将其等同为同SKU成交价。详情长图本例未返回，长图及全部规格完整性尚未验收；核心标题、店铺、价格与主图正向回包已验证。

首批任务1和2完成后再次优雅重启：PID2692→12736，readiness全true；通过公网重新读取task1/2状态及结果仍为原SUCCESS/NOT_FOUND，证明现场跨进程结果持久化。最终两只AnZhi计划任务均Running，draining=false、runningTasks=0、waitingUser=false，HTTP仅回环81。

## 现场配置与回滚依据

- rssClient 新配置：`/home/frp/rx-mimic-visitor.toml`、`/home/frp/rx-mimic-visitor-compose.yml`、`/home/nginx/conf-http/rx-mimic-locations.inc`；修改 `/home/nginx/conf-http/k8s.conf` 的6400 server块。原配置备份：`/home/backups/rx-mimic-20261020_204248/k8s.conf`。
- visitor镜像：`ghcr.io/fatedier/frpc:v0.71.0@sha256:99ece6a2b62cfc68731e0df289af804ff1c699911cfc47871856434f1d6d53ee`，复用已有镜像；容器 `rx-mimic-visitor` 使用host网络、只读根、cap_drop ALL、no-new-privileges和日志轮转，RestartCount=0。
- AnZhi FRP 配置使用受限文件渲染凭据；模板使用环境占位符，现场没有为 FRP新增用户环境变量。原 `anzhi-frp` 任务和 `anzhi-frpc.toml` 未改动。
- 服务变更同时登记于 `D:\projs\rss-env\AGENTS.md`、FRP/Nginx各自readme；Nginx本地配置副本也已补同一include和片段。

## 回滚

本次首发前无旧服务、无旧数据库；停止本服务可保留数据和profile待恢复，不存在可切回的旧release。后续从旧队列升级前先停机备份数据库。新增状态写入后，历史旧 JAR 的枚举可能无法读取，不能回滚到不识别新状态的构建；只能在兼容本轮队列 schema/枚举的版本之间切换，或在明确数据损失范围后恢复升级前备份。应用使用发布脚本保留的兼容前一版本并检查健康；失败必须明确返回失败，不以成功回滚掩盖发布失败。数据/profile 不随 JAR 回退覆盖。网络回退先恢复 Nginx 配置并语法检查/平滑加载，再停专用 visitor、恢复 AnZhi FRP 配置，验证 SSH6014/桌面6017仍可用；保留备份。不重启 frps、RSS、WOW、数据库或虚拟机来处理应用回滚。
