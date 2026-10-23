# rx-mimic 文档汇总

整理日期：2026-10-21。本文汇总通用运行、异步接入、部署和运维；平台业务只维护在各自文档。验收数据保留原日期，本文整理未重新运行抓取、发布或远端验证。

## 文档导航

| 文档 | 内容 |
| --- | --- |
| [京东说明](jd.md) | JD 主站/联盟任务、参数、推广位、结果校验与平台验收 |
| [淘宝说明](tb.md) | TB/天猫/阿里妈妈任务、参数、推广位、结果校验与平台验收 |
| [存储规范说明](storage.md) | 本地文件、H2 数据库表、离线流水与调试目录规范 |
| [淘宝反爬技术细节](tb-anti-scraping.md) | 原 `code_artifact.md`，仅重命名，专题原文保留；不作为当前实现或实测证明 |
| [滑动验证外围工程审查](reviews/tb-slider-engineering-review-2026-10-21.md) | 权限、URL 脱敏、快照保留及测试覆盖；仅审查，未实施修复 |
| [远程异步服务实施计划](plan/remote-service-async-rpc-plan-2026-10-20.md) | 最初设计与实施清单，现场接入以本文为准 |
| [数字商品与价格跟踪](price-watch.md) | 新数字 ID 契约、临时缓存、独立价格库、API 与验证状态 |
| [联盟价格跟踪计划](plan/union-product-price-watch-plan-2026-10-21.md) | 双平台价格跟踪与降价提醒设计 |
| [降价通知广播计划](plan/price-drop-broadcast-plan-2026-10-21.md) | 后台 job 现状、通用 JSON 事件、Webhook 广播与游标拉取 |
| [订单存储与同步通知计划](plan/order-storage-sync-webhook-plan-2026-10-23.md) | 订单统一存 H2、读库 API、后台同步与新订单 Webhook；移除订单 JSONL |
| [订单查询与 Webhook 接入](order.md) | 订单只读 API、调用方 Webhook 订阅接口、验签与接入规范 |
| [通用反爬历史方案](plan/Anti_Scraping_Plan_Java_Playwright.md) | 早期设计资料，实际约束以仓库 AGENTS.md 为准 |
| [旧集成指南归档](archive/legacy-integration-guide-2026-10-21.md) | 整理前 README 的历史任务说明与本地测试记录，不作为当前接口说明 |

## 通用运行约定

- Java 21 + Spring Boot 3 + Playwright，使用本机真实 Chrome、持久 profile 与可见桌面。
- 本机默认 profile 为 `%LOCALAPPDATA%\rx-mimic\chrome\common`；AnZhi 使用 `D:\rx-mimic\profiles\common`。不能把开发机用户名路径照搬到远端。
- 同机服务仅一个实例（互斥量 `Global\rx-mimic-service`），同 profile 的 `maxActive=1`。默认两个标签仅用于预开下一任务起始页，页面操作仍串行。
- 登录脚本、链路测试与服务不能同时占用同一 profile。Cookie 库和 Local State 需要成对保留；跨用户/跨机器复制不等于登录态可用。
- 登录、验证码的处理遵循仓库 AGENTS.md；专题研究、历史实现记录不能覆盖当前操作约束。
- 开启 debug 后保存 HTML、标签视口 PNG 与桌面 PNG。调试目录默认保留 72 小时，总量上限 512MB，磁盘剩余低于 1GB 时停止新增调试文件。`GET /internal/debug/desktop` 只在本机回环用管理员密钥查看桌面，普通调用方密钥和公网入口都不能看。
- 平台文档的 `/custom` 为本地兼容接口，远端部署不对公网开放；新调用方统一使用下文 v1 API。

## 远程入口与运行目录

运行机为 `anzhi-pc\anzhi`，应用根目录 `D:\rx-mimic`，Java 21、本机 Chrome、有界面 InteractiveToken 计划任务。HTTP 仅监听 `127.0.0.1:81`，真实 profile 为 `profiles\common`，数据与版本目录分离。外部只接入新 v1 API；历史 Cookie、profile、同步接口和管理入口不经 Nginx 暴露。

链路固定为：调用方 → `https://cloud.f-li.cn:6400/crawler/api/v1` → rssClient Nginx → `127.0.0.1:6020` STCP visitor → rssClient frps → AnZhi `D:\home\.app\frp` 的 frpc → `127.0.0.1:81/api/v1`。rssServer 保持原主线路职责，不迁移 AnZhi SSH 或桌面映射。

不启动 `jmx_prometheus_javaagent.jar`，不需要 `jmx_prometheus_config.yaml`，不新增 JMX exporter 8080 监听。任务指标、JVM 堆/堆外与 Chrome RSS 先通过健康接口、运行日志及受控主机观测记录。

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

请求包装沿用 `success/code/message/data`，并带发布序号 `release`。`release` 从 1 开始，每次 GitHub Action 发布一个新提交时加 1；回滚沿用该提交原来的序号，本地未发布时是 0。健康检查里的 `version` 仍是 Git 提交 SHA。例如：

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

## 本地与部署脚本

这些脚本都在仓库根目录，除非另写路径。跑登录或全链路前先停掉占用 `common` 这个 Chrome 目录的服务。

| 脚本 | 作用 |
| --- | --- |
| `start-login.bat [all\|jd\|tb] [profile]` | 打开可见 Chrome 做人工登录。默认 `all`，第二个参数是 profile，默认 `common`。同一时间只能开一个，第二次直接退出并打印 `start-login.bat is already running.`。实际逻辑在 `start-login.ps1`。 |
| `start-test-chain.bat jd [keyword]` | 京东全链路：联盟搜索、转链、近 7 天订单、主站搜索、主站详情。窗口可见。推广位固定 `0`。 |
| `start-test-chain.bat tb [keyword]` | 淘宝全链路：联盟搜索、转链、近 30 天订单、主站搜索、主站详情。窗口可见。推广位固定 `0`。 |
| `start-crawler-bootstrap.bat` | 本机前台启动爬虫服务。缺 jar 时先打包。 |
| `start-crawler-bootstrap.bat jd-union-orders-verify` | 同样启动服务，并打开京东联盟订单调试目录。这不是全链路。 |
| `start-crawler-detached.bat` | 脱离当前终端启动已打包的服务，日志在 `data\logs\crawler-service.log`。 |
| `deploy/app-crawler/start.bat` | 在部署机上启动已安装的 rx-mimic 服务。 |
| `deploy/app-crawler/rollback.bat` | 在部署机上切回上一份发布包。 |

## GitHub Actions 配置与发布

对应 workflow：`Build and deploy rx-mimic`，文件 `.github/workflows/java-ssh-crawler.yml`。以下配置记录更新至 2026-10-21。

### 部署 Secret

当前记录：`ANZHI_SSH` 已添加为 Repository Secret；Environment 无同名项时直接读取，无需重复配置。需要新增或更新时，可在仓库 Secrets 中操作，也可打开 [rx-mimic-production 环境设置](https://github.com/RockyLOMO/rx-mimic/settings/environments/24876535130/edit)，在 **Environment secrets** 中新增：

| 项目 | 填写内容 |
| --- | --- |
| Name | `ANZHI_SSH` |
| Secret | 本机 `D:\home\.envKeys\ssh\rx-mimic-github-anzhi-ssh.json` 的全部 JSON 内容 |

填写文件内容，不是文件路径。连接地址、账号、私钥、固定主机公钥已经生成，无需逐项填写。可在 PowerShell 中复制：

```powershell
Get-Content -LiteralPath 'D:\home\.envKeys\ssh\rx-mimic-github-anzhi-ssh.json' -Raw | Set-Clipboard
```

文件结构是 `endpoint/privateKey/hostKey` 三个字段，Actions endpoint 为 `ssh://AnZhi@f-li.cn:5913`（本机连接使用 `cloud.f-li.cn:6014`），同时包含账号、host 和 port；主机公钥不重复配置 host/port。

原来的 `DEPLOY_HOST`、`DEPLOY_PORT`、`DEPLOY_USER`、`DEPLOY_KEY`、`DEPLOY_KNOWN_HOSTS` 在新版 workflow 中均不再读取。运行目录仍固定 `D:\rx-mimic`；远端管理员凭据继续使用现有受限配置和用户级环境变量。部署 workflow 只负责构建、上传、切换与启动健康检查；回归测试由独立 CI 执行，商品验收在部署完成后从本机运行。

### 生效与运行

1. 将本次 workflow、`deploy/app-crawler/deploy_ssh.py` 和测试改动提交推送到默认分支；改动需在默认分支生效。
2. 仅配置 `ANZHI_SSH`；本机商品验收方法见 [价格跟踪说明](price-watch.md#部署后本机验收)。
3. 打开 [Build and deploy rx-mimic](https://github.com/RockyLOMO/rx-mimic/actions/workflows/java-ssh-crawler.yml)，commit 选填：留空部署默认分支最新提交；需要指定版本时填写默认分支可达的完整40位commit SHA，再手动运行。

新版在构建前执行只读 SSH 连接与初始化检查。随后构建同一制品、校验本地和远端 SHA256，调用已有发布脚本执行 drain、优雅退出、版本切换和健康检查。主机公钥必须匹配；上传或远端命令失败会使 job 失败。SFTP 空闲超时120秒，远端部署命令超时30分钟；job总超时45分钟。

同一个 commit 对应不可变 release，已经存在的 release 会拒绝覆盖；不要用重复发布绕过该检查。

### 2026-10-21 配置验证记录

- 失败 run `39532999235`：构建、测试、制品准备通过；上传阶段5个原部署变量均为空，在主机参数检查时退出，尚未连接 SSH。
- 沿用本机已有 AnZhi SSH 密码认证；没有修改远端 SSH 登录配置、应用文件或计划任务。
- 新脚本 `--check` 已真实登录 `anzhi-pc\anzhi`，确认已有服务脚本及运行任务。
- ECDSA 主机公钥指纹与此前部署记录一致：`SHA256:zm9KGOp8b2IRbTjDmlIpMLqNJBGlMszeIZCEvm1omLc`。
- 单元测试覆盖连接解析、错误配置、主机公钥固定、认证失败关闭、制品哈希校验、远端失败及上传超时。Workflow YAML 与嵌入 Python 语法检查通过。
- 新 Secret 文件禁用 ACL 继承，仅授权当前 Windows 用户；密码没有写入仓库、文档或命令参数。用户已将 `ANZHI_SSH` 添加为 Repository Secret；环境未同名覆盖时可以直接读取，无需重复添加Environment Secret。新版完整CI发布按本轮运行记录确认。

## 运维落地顺序

1. 本地构建、定向测试，按 AGENTS 要求构建并同步 crawler-api 至相邻 xiaofan；保留目标旧包备份与哈希。
2. 检查 AnZhi 活动桌面、Java/Chrome、目录权限、FRP 版本、已有进程/任务；建立运行目录，外置 API/admin/STCP 凭据，不覆盖已有数据或 profile。
3. 使用 `deploy/app-crawler` 脚本注册仅用户登录时运行的计划任务，首次部署不可变制品并校验 SHA256；健康检查必须验证版本、数据库与桌面。
4. 网络模板位于 `deploy/network/`。现场使用独立 `D:\rx-mimic\config\frpc.toml` 与 `rx-mimic-frp` 交互计划任务，复用原 `D:\home\.app\frp\frpc.exe`，不重启原 `anzhi-frp`。连接/token 沿用原配置来源；publisher 与 visitor 均使用 `user="rx-mimic"`，STCP 密钥只保存在受限配置中。
5. rssClient 独立 visitor 进程/容器使用 `rssclient-crawler-visitor.toml`，host 网络仅绑定 `127.0.0.1:6020`，`RX_MIMIC_FRP_TOKEN` 使用现有 frps token，两个 STCP 端共享随机密钥。配置和环境文件权限600，不提交仓库。先 `frpc verify`，再启动并确认只绑定回环。STCP 不需要新公网 remotePort。
6. 备份 rssClient `/home/nginx/conf-http/k8s.conf`，把 `rssclient-crawler-locations.inc` 引入现有 `cloud.f-li.cn:6400` server 块，`nginx -t` 后平滑 reload。仅转发 `/crawler/api/v1/`，不将 `/health`、`/internal` 或 `/custom` 暴露。
7. 验证无 key/错 key拒绝、正确 key提交/查询、跨调用方隔离，外网入口能取回断线任务；人工登录 TB/JD、断线恢复及超时取消逐项验收。
8. GitHub 使用一个 `ANZHI_SSH` JSON Secret（Repository Secret 可用；Environment 同名项优先）（`endpoint/password/hostKey`，host+port+账号合并在endpoint），AnZhi 用户级 `RX_MIMIC_ADMIN_KEY` 与外置应用 caller key；核对环境权限；首期仅手动发布。真实演练 drain、上传失败、哈希不符、健康失败与回滚。

FRP 模板字段参考 [FRP v0.71.0 官方配置](https://raw.githubusercontent.com/fatedier/frp/v0.71.0/conf/frpc_full_example.toml)。本轮已用本机 frpc 0.71.0、占位环境变量验证两份 TOML，均输出 syntax is ok；这不代表远端加载或 STCP 链路验收。

## 接入、部署与公共验收记录

以下保留 2026-10-20 执行记录及当时待办；后续配置更新以此前 GitHub Actions 一节为准，不把历史状态当作当前在线状态。

## 现场基线

2026-10-20 本轮只读 SSH 检查：

- SSH `cloud.f-li.cn:6014` 的 ECDSA nistp384 指纹与原计划记录一致：`SHA256:zm9KGOp8b2IRbTjDmlIpMLqNJBGlMszeIZCEvm1omLc`。这是连续性校验，首次信任仍需独立渠道核对。
- AnZhi console 会话 Active；运行根目录尚不存在；81/8080/1221/8505/8506 未发现监听。
- 远端 FRP 文件 `D:\home\.app\frp\anzhi\anzhi-frpc.toml`，上游 `cloud.f-li.cn:4000`，SSH `22→6014`，桌面接管 `5900→6017`。
- rssClient `192.168.31.4` 的 frps/frpc-main 容器运行；4000、6014、6017 监听；6020 未发现监听。`nginx -t` 成功。
- rssClient 的 8080 已由其他 Docker 服务监听；无需变更它，不把“移除 exporter”解释为释放此主机现有 8080。
- 凭据只从仓库外 `D:\home\.envKeys\ssh.json` 和 `rss-env/readme.md` 原始连接资料读取，不复制到本文。

## 执行状态与验证

以下为 2026-10-20 首发时记录：已完成本地实现、统一构建、真实 JAR 冒烟、AnZhi 首次部署及公网接入，京东与淘宝核心详情链路已实测。当时尚未提交/推送代码、配置 GitHub secrets、触发 CI 或切换 xiaofan 生产调用端。2026-10-21 的 Secret 配置进展见本文 GitHub Actions 一节；其他状态不据此推定已完成。

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

## 平台验收入口与共同重启证据

京东实际回包见 [京东现场验收](jd.md#远程现场验收记录2026-10-20)，淘宝实际回包与未完成项见 [淘宝现场验收](tb.md#远程现场验收记录2026-10-20)。

首批任务1和2完成后再次优雅重启：PID2692→12736，readiness全true；通过公网重新读取task1/2状态及结果仍为原SUCCESS/NOT_FOUND，证明现场跨进程结果持久化。最终两只AnZhi计划任务均Running，draining=false、runningTasks=0、waitingUser=false，HTTP仅回环81。

## 现场配置与回滚依据

- rssClient 新配置：`/home/frp/rx-mimic-visitor.toml`、`/home/frp/rx-mimic-visitor-compose.yml`、`/home/nginx/conf-http/rx-mimic-locations.inc`；修改 `/home/nginx/conf-http/k8s.conf` 的6400 server块。原配置备份：`/home/backups/rx-mimic-20261020_204248/k8s.conf`。
- visitor镜像：`ghcr.io/fatedier/frpc:v0.71.0@sha256:99ece6a2b62cfc68731e0df289af804ff1c699911cfc47871856434f1d6d53ee`，复用已有镜像；容器 `rx-mimic-visitor` 使用host网络、只读根、cap_drop ALL、no-new-privileges和日志轮转，RestartCount=0。
- AnZhi FRP 配置使用受限文件渲染凭据；模板使用环境占位符，现场没有为 FRP新增用户环境变量。原 `anzhi-frp` 任务和 `anzhi-frpc.toml` 未改动。
- 服务变更同时登记于 `D:\projs\rss-env\AGENTS.md`、FRP/Nginx各自readme；Nginx本地配置副本也已补同一include和片段。

## 回滚

本次首发前无旧服务、无旧数据库；停止本服务可保留数据和profile待恢复，不存在可切回的旧release。后续从旧队列升级前先停机备份数据库。新增状态写入后，历史旧 JAR 的枚举可能无法读取，不能回滚到不识别新状态的构建；只能在兼容本轮队列 schema/枚举的版本之间切换，或在明确数据损失范围后恢复升级前备份。应用使用发布脚本保留的兼容前一版本并检查健康；失败必须明确返回失败，不以成功回滚掩盖发布失败。数据/profile 不随 JAR 回退覆盖。网络回退先恢复 Nginx 配置并语法检查/平滑加载，再停专用 visitor、恢复 AnZhi FRP 配置，验证 SSH6014/桌面6017仍可用；保留备份。不重启 frps、RSS、WOW、数据库或虚拟机来处理应用回滚。
