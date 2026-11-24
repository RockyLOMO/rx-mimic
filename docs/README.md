# rx-mimic 文档

整理日期：2026-11-24。本文只记当前代码和仓库配置里的运行方式。历史验收、实施计划和专题笔记在 [reference](reference/README.md)，不作为现行说明。

平台行为分别见 [京东](jd.md)、[淘宝](tb.md)、[爱企查](aqc.md)。订单、价格和存储见 [订单](order.md)、[价格跟踪](price-watch.md)、[存储](storage.md)。

## 运行

- Java 21、Spring Boot 3、Playwright。浏览器用本机 Chrome（`channel=chrome`），不用 Selenium。
- 仓库默认 HTTP `81`。生产示例只绑 `127.0.0.1:81`。
- 本机 profile 目录为空时用 `%LOCALAPPDATA%\rx-mimic\chrome\<profile>`，默认名 `common`。AnZhi 示例把 profile 放在 `D:/rx-mimic/profiles`。
- 同机只有一个服务进程，互斥量 `Global\rx-mimic-service`。同一个 profile 的 `maxActive=1`。`tabSlots=2` 只预开下一个排队任务的起始页，页面操作仍串行。
- 操作约束以仓库 `AGENTS.md` 为准。调试默认关闭；打开后保存步骤 HTML、标签视口 PNG 和桌面 PNG。快照保留 72 小时，总量 512MB，磁盘剩余低于 1GB 时不再新增。`GET /internal/debug/desktop` 只接受本机回环上的管理员密钥。

## 对外入口

调用方使用异步 v1。进程内路径是 `/api/v1`。公网片段 `deploy/network/rssclient-crawler-locations.inc` 只转发 `https://cloud.f-li.cn:6400/crawler/api/v1/`，不暴露 `/health`、`/internal`、`/custom`。

`/custom/**` 是遗留同步入口，`crawler.task-api.legacy-enabled` 默认 `false`，此时这些路径返回 404。生产示例关闭 Remoting（默认端口 1221）。

请求包装是 `success/code/message/release/data`。`release` 是发布序号，健康检查里的 `version` 是 Git SHA。调用方密钥放在 `crawler.task-api.api-keys.<callerId>`，空配置拒绝访问。

```http
POST /api/v1/tasks HTTP/1.1
Authorization: Bearer <caller-key>
Idempotency-Key: <stable-business-request-key>
Content-Type: application/json

{"action":"jdProductDetail","request":{"skuId":"100059484008","downloadImages":false}}
```

| 方法 | 作用 |
| --- | --- |
| `POST /tasks` | 提交。成功 HTTP 202。保存字符串 `taskId` |
| `GET /tasks/{taskId}` | 队列状态 |
| `GET /tasks/{taskId}/result` | 结果。未完成 409，过期 410 |
| `POST /tasks/{taskId}/cancel` | 取消。运行中先变成 `CANCEL_REQUESTED`，在下一次浏览器边界结束 |
| `POST /tasks/{taskId}/resume` | 人工处理完 `WAITING_USER` 后恢复同一任务 |

队列状态：`PENDING`、`RUNNING`、`WAITING_USER`、`CANCEL_REQUESTED`、`SUCCESS`、`FAILED`、`CANCELLED`、`TIMED_OUT`。业务状态在结果里另计，队列成功不等于业务成功。

公开 action：`jdSearchProduct`、`jdProductDetail`、`jdUnionSearchProducts`、`getPromotionUrl`、`getPromotionUrls`、`getPromotionOrders`、`loginCheck`、`tbSearchProduct`、`tbProductDetail`、`tbUnionSearchProducts`、`getTbPromotionUrl`、`getTbPromotionUrls`、`getTbPromotionOrders`、`aqcCompanySearch`、`aqcCompanyDetail`。

调用方不能传 `profileName`、`debugOutputDir`、`debugEnabled`、`forcePreflight`、`keepBrowserOpenOnLoginRequired`。批量转链的 `request` 是字符串数组。`getPromotionUrl` 可带 `fast`，默认 `false`，只作用于京东单条转链。

优先级：转链 200，搜索、详情、登录检查 100，降价检查普通 10、老化 40、手动刷新不超过 100，订单 0。同级按入队顺序。正在运行的任务不抢占。同一调用方、平台、profile 和 `sourceId` 只保留一条待执行订单任务，旧的待执行任务变为 `CANCELLED` / `ORDER_TASK_SUPERSEDED`。

默认容量：全局 100、每调用方 50、每分钟 60 次提交、批量最多 20。排队等待 1800 秒，普通执行 300 秒，订单执行 1800 秒，人工等待 600 秒，结果保留 7 天。浏览器内 `maxTaskMinutes` 默认 5 分钟，超时为 `TIMED_OUT` / `EXECUTION_TIMEOUT`。

其它 v1：订单见 [order.md](order.md)，价格见 [price-watch.md](price-watch.md)。返现队列是 `GET/PUT /api/v1/cashback/queue`，设备号用 `X-Device-Id` 或 `deviceId`。

Java 调用方用 `crawler-api` 的 `AsyncTaskClient`。`await` 超时只结束本次等待，不取消服务端任务。

## 本机脚本

跑登录或全链路前，先停掉占用同一 Chrome profile 的服务。

| 脚本 | 作用 |
| --- | --- |
| `start-login.bat [all\|jd\|tb] [profile]` | 可见 Chrome 人工登录。默认 `all` 和 `common`。同时只能开一个 |
| `start-test-chain.bat jd [keyword]` | 京东联盟搜索、转链、近 7 天订单、主站搜索、主站详情。默认关键字「抽纸」，推广位 `0` |
| `start-test-chain.bat tb [keyword]` | 淘宝对应链路。订单窗口是近 30 天 |
| `start-crawler-bootstrap.bat` | 本机前台启动。缺 jar 时先打包 |
| `start-crawler-detached.bat` | 脱离终端启动，日志在 `data\logs\crawler-service.log` |
| `deploy/app-crawler/start.bat` | 在部署机启动已安装服务 |
| `deploy/app-crawler/rollback.bat` | 切回上一份发布包 |

## 部署

AnZhi 应用根目录 `D:\rx-mimic`。发布走 GitHub Actions `.github/workflows/java-ssh-crawler.yml`，密钥名 `ANZHI_SSH`，内容是本机 `D:\home\.envKeys\ssh\rx-mimic-github-anzhi-ssh.json`。不要绕过 CI 直接覆盖远端 JAR 或 `scripts\service.ps1`。

工作流构建、上传、校验 SHA256、排空任务、切换版本并做健康检查。同一个 commit 的 release 不覆盖。离线测试在 `java-checks.yml`，不在部署工作流里跑商品验收。

网络模板在 `deploy/network/`。AnZhi 的 frpc 把本机 `127.0.0.1:81` 做成 STCP；rssClient 的 visitor 只绑 `127.0.0.1:6020`，再由上面的 Nginx 片段转到公网前缀。生产配置示例是 `deploy/app-crawler/application-prod.example.yml`，复制到 `D:\rx-mimic\config\application-prod.yml` 后填写密钥，不要把密钥写进仓库。

2026-10 的现场记录和当时的发布序号在 [reference/history/README.md](reference/history/README.md)。
