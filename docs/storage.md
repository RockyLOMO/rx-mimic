# rx-mimic 文件与数据库存储规范说明

整理日期：2026-10-21。  
本文档梳理汇总 `rx-mimic` 运行期间涉及的所有**文件存储位置、格式、负责组件及其具体业务用途**。

所有项目自身产生的数据文件统一收口于运行目录下的 `./data/`，浏览器持久会话位于用户环境目录，密钥凭据外置存储，确保环境隔离与运行安全。

---

## 目录结构总览

```text
rx-mimic/
├── data/                                 # 运行时本地数据总根目录
│   ├── product-price-db.mv.db            # 新数字商品价格库，不导入旧数据
│   ├── browser-task-db.mv.db             # H2 嵌入式主数据库文件
│   ├── browser-task-db.trace.db          # H2 运行跟踪日志
│   ├── keepalive/
│   │   └── urls.json                     # 登录保活动态 URL 存储池
│   ├── jd-union/                         # 京东联盟数据
│   │   ├── output.jsonl                  # 订单/转链离线流水
│   │   └── debug/                        # 失败/排障 DOM快照与截图
│   ├── jd-mall/                          # 京东商城数据
│   │   ├── output.jsonl                  # 搜索与商品详情离线流水
│   │   ├── images/                       # 商品主图/轮播图暂存
│   │   └── debug/                        # 失败/排障 DOM快照与截图
│   ├── tb-union/                         # 淘宝联盟（阿里妈妈）数据
│   │   ├── output.jsonl                  # 订单/转链离线流水
│   │   └── debug/                        # 失败/排障 DOM快照与截图
│   ├── tb-mall/                          # 淘宝商城数据
│   │   ├── output.jsonl                  # 搜索与商品详情离线流水
│   │   ├── images/                       # 商品主图/长图暂存
│   │   └── debug/                        # 失败/排障 DOM快照与截图
│   ├── fiddler/                          # Fiddler 抓包转储目录
│   ├── temp/                             # 临时文件下载与导出中转
│   └── logs/
│       └── crawler-service.log           # 后台守护进程控制台标准输出日志
│
└── [外置运行环境]
    ├── %LOCALAPPDATA%\rx-mimic\chrome\   # 真实 Chrome 用户数据目录（持久化 Cookie、LocalStorage 等）
    └── D:\home\.envKeys\keys.json        # 外置敏感密钥文件（SMTP 授权码等，禁止入库）
```

---

## 一、嵌入式数据库存储（H2 Database）

结构化数据使用 H2。通用任务/Cookie 库为 `browser-task-db`，2026-10-23 起数字商品价格库独立为 `product-price-db`；价格库路径通过 `app.storage.priceDbPath` 配置。不迁移旧价格表，不保存加密 ID、别名映射或推广链接全文，详见 [数字商品与价格跟踪](price-watch.md)。

- **文件路径**：`./data/browser-task-db.mv.db`（以及 `./data/browser-task-db.trace.db`）
- **核心底层**：[`EntityDatabaseImpl`](file:///d:/projs_r/rx-mimic/crawler-bootstrap/src/main/java/org/rx/crawler/config/CookieJarConfig.java)
- **核心数据表结构**：

| 表名 | 管理组件 / 服务 | 具体用途与存储内容 |
| :--- | :--- | :--- |
| **`HTTP_CLIENT_COOKIE`** | `HttpClientCookieJar` | **HTTP 请求 Cookie 持久化**：记录域名、路径、过期时间及键值对，实现客户端跨请求、重启后的 Cookie 复用。 |
| **`crawl_tasks`** | [`CustomCrawlQueueService`](file:///d:/projs_r/rx-mimic/crawler-bootstrap/src/main/java/org/rx/crawler/task/common/CustomCrawlQueueService.java) | **异步爬虫任务队列与生命周期管理**：<br>• 任务状态机（`PENDING`, `RUNNING`, `SUCCEEDED`, `FAILED`, `CANCELLED` 等）；<br>• 入参 JSON、执行结果 JSON、业务错误码与错误信息；<br>• 调度优先级（前台抢占/后台排队）、幂等去重键（`idempotency_key`）；<br>• 批处理父子任务管理、超时控制及进程重启后自动恢复。 |
| **`crawl_fallback_record`** | [`CrawlFallbackService`](file:///d:/projs_r/rx-mimic/crawler-bootstrap/src/main/java/org/rx/crawler/task/common/CrawlFallbackService.java) | **抓取失败降级兜底快照库**：<br>• 存储京东/淘宝的商品详情与转链最近一次抓取成功的完整快照；<br>• 建立 `(platform, record_type, lookup_key)` 唯一索引，微秒级命中历史结果；<br>• 联动价格监控表动态重构兜底，彻底替代早期磁盘小 JSON 文件方案。 |
| **`price_product`** | [`PriceWatchStore`](file:///d:/projs_r/rx-mimic/crawler-bootstrap/src/main/java/org/rx/crawler/task/price/PriceWatchStore.java) | **价格监控-商品主表**：记录平台（`jd`/`tb`）、全局唯一商品 ID、商品标题、店铺名称、规范化直达 URL 与更新时间；金额在快照表分口径保存。 |
| **`price_promotion_record`** | [`PriceWatchStore`](file:///d:/projs_r/rx-mimic/crawler-bootstrap/src/main/java/org/rx/crawler/task/price/PriceWatchStore.java) | **价格监控-转链记录表**：记录数字商品外键、调用方、规范化任务编号、推广位及生成时间；不保存推广链接或加密材料。 |
| **`price_watch`** | [`PriceWatchStore`](file:///d:/projs_r/rx-mimic/crawler-bootstrap/src/main/java/org/rx/crawler/task/price/PriceWatchStore.java) | **价格监控-规则配置表**：基准价、降价阈值、监控频次级别（重点/普通/冷门）、告警开关与通知目标。 |
| **`price_snapshot`** | [`PriceWatchStore`](file:///d:/projs_r/rx-mimic/crawler-bootstrap/src/main/java/org/rx/crawler/task/price/PriceWatchStore.java) | **价格监控-时序快照表**：记录历次巡检采样的标价、券后价、到手价与佣金走势。 |
| **`price_alert_outbox`** | [`PriceWatchStore`](file:///d:/projs_r/rx-mimic/crawler-bootstrap/src/main/java/org/rx/crawler/task/price/PriceWatchStore.java) | **价格监控-告警出件箱**：异步邮件降价提醒队列、投递状态、重试计数与最终发送时间。 |
| **`price_event`** | [`PriceWatchStore`](file:///d:/projs_r/rx-mimic/crawler-bootstrap/src/main/java/org/rx/crawler/task/price/PriceWatchStore.java) | **价格监控-不可变降价事件表**：<br>• 存储经复核成立的降价事件（`price.dropped`），记录所属 `owner_id`、事件类型、契约版本、完整结构化 Payload JSON 与触发时间戳；<br>• 提供基于自增 ID 游标的分页拉取索引 `(owner_id, id)`，支持外部调用方拉取与保留期清理。 |
| **`price_event_delivery`** | [`PriceWatchStore`](file:///d:/projs_r/rx-mimic/crawler-bootstrap/src/main/java/org/rx/crawler/task/price/PriceWatchStore.java)<br>[`PriceDeliveryService`](file:///d:/projs_r/rx-mimic/crawler-bootstrap/src/main/java/org/rx/crawler/task/price/PriceDeliveryService.java) | **价格监控-逐接收方投递记录表**：<br>• 记录每个订阅者（Webhook / Mail 渠道）对特定降价事件的独立投递生命周期与状态（`PENDING`, `IN_PROGRESS`, `SUCCESS`, `RETRY`, `EXHAUSTED`）；<br>• 记录重试次数、下次尝试时间戳、HTTP 返回码及异常信息，实现单接收方故障隔离、指数退避重试与崩溃恢复。 |
| **`price_visit_counter`** | [`PriceWatchStore`](file:///d:/projs_r/rx-mimic/crawler-bootstrap/src/main/java/org/rx/crawler/task/price/PriceWatchStore.java) | **价格监控-风控计数器**：按业务日（每日 06:00 起算）统计每个平台已访问的商品数、登录失效与滑块次数，超限自动熔断。 |

---

## 二、业务数据文件与缓存（JSON / JSONL）

| 存储路径 | 格式 | 对应的管理组件 | 具体用途 |
| :--- | :---: | :--- | :--- |
| **`./data/keepalive/urls.json`** | JSON | [`FileKeepAliveUrlStore`](file:///d:/projs_r/rx-mimic/crawler-bootstrap/src/main/java/org/rx/crawler/task/common/FileKeepAliveUrlStore.java) | **登录保活 URL 池**：动态记录在爬取过程中收集到的合法商品/页面 URL，用于在保活时间窗（如 17:00-18:00）内定时巡检刷新登录态。 |
| **`./data/jd-union/output.jsonl`** | JSONL | [`JdUnionConfig`](file:///d:/projs_r/rx-mimic/crawler-bootstrap/src/main/java/org/rx/crawler/task/jd/JdUnionConfig.java) | **京东联盟离线流水**：转链明细、订单拉取等数据逐行以 JSONL 格式追加导出，便于审计与离线分析。 |
| **`./data/jd-mall/output.jsonl`** | JSONL | [`JdMallConfig`](file:///d:/projs_r/rx-mimic/crawler-bootstrap/src/main/java/org/rx/crawler/task/jd/JdMallConfig.java) | **京东商城离线流水**：京东主站搜索结果、商品详情抓取结果逐行追加导出。 |
| **`./data/tb-union/output.jsonl`** | JSONL | [`TbPromotionConfig`](file:///d:/projs_r/rx-mimic/crawler-bootstrap/src/main/java/org/rx/crawler/task/tb/TbPromotionConfig.java) | **淘宝联盟离线流水**：阿里妈妈联盟搜索、转链、订单明细逐行追加导出。 |
| **`./data/tb-mall/output.jsonl`** | JSONL | [`TbMallConfig`](file:///d:/projs_r/rx-mimic/crawler-bootstrap/src/main/java/org/rx/crawler/task/tb/TbMallConfig.java) | **淘宝商城离线流水**：淘宝/天猫主站搜索结果、商品详情抓取结果逐行追加导出。 |

> [!NOTE]
> **兜底存储性能优化说明**：  
> 早期在 `./data/tb-fallback/` 下按商品 ID 或关键字写入独立小 JSON 文件的方案已**全量废弃并移除**（对应老类 `TbLastResultStore` 已删除）。  
> 现已由 H2 数据库的 `crawl_fallback_record` 表统一管理，结合价格监控表实现精准快照与动态重构双层兜底，彻底消除了小文件大量读写带来的磁盘碎片、文件句柄占用和 IO 延迟瓶颈。

---

## 三、浏览器环境与媒体文件存储

| 存储路径 | 格式 | 对应的管理组件 | 具体用途 |
| :--- | :---: | :--- | :--- |
| **`%LOCALAPPDATA%\rx-mimic\chrome\<profileName>`**<br>（例如 `common`） | 目录簇 | [`BrowserProfileManager`](file:///d:/projs_r/rx-mimic/crawler-bootstrap/src/main/java/org/rx/crawler/task/common/BrowserProfileManager.java) | **Chrome 独立用户数据目录**：<br>• 保存真实的 Chromium 会话、LocalStorage、IndexedDB、Cookie 及扩展插件；<br>• 独立于项目代码目录，确保重跑脚本或清理项目临时文件时**不会破坏已有登录态**；<br>• 远端运行环境（如 AnZhi）使用 `D:\rx-mimic\profiles\common`。 |
| **`./data/jd-mall/images/`**<br>**`./data/tb-mall/images/`** | 图片文件 | [`JdMallTask`](file:///d:/projs_r/rx-mimic/crawler-bootstrap/src/main/java/org/rx/crawler/task/jd/JdMallTask.java)<br>[`TbMallTask`](file:///d:/projs_r/rx-mimic/crawler-bootstrap/src/main/java/org/rx/crawler/task/tb/TbMallTask.java) | **商品详情图片暂存**：京东和淘宝商品详情页下载的主图、轮播图、长图等媒体文件暂存。 |

---

## 四、调试排障、代理与运维文件存储

| 存储路径 | 格式 | 对应的管理组件 | 具体用途 |
| :--- | :---: | :--- | :--- |
| **`./data/jd-union/debug/`**<br>**`./data/jd-mall/debug/`**<br>**`./data/tb-union/debug/`**<br>**`./data/tb-mall/debug/`** | HTML / PNG | 各模块 Task 与 [`DebugSnapshots`](file:///d:/projs_r/rx-mimic/crawler-bootstrap/src/main/java/org/rx/crawler/task/common/DebugSnapshots.java) | **排障现场快照**：当任务开启 `debugEnabled: true` 或遇风控失败时，自动保存当时页面的 DOM HTML、浏览器视口截图以及桌面全屏截图（用于定位滑块拼图、弹窗遮罩或登录失效现场）。 |
| **`./data/fiddler/`** | TXT / 报文 | [`FiddlerService`](file:///d:/projs_r/rx-mimic/crawler-bootstrap/src/main/java/org/rx/crawler/service/FiddlerService.java) | **Fiddler 抓包中间件目录**：接收由 Fiddler 抓包脚本转储的网络请求与响应报文，用于接口逆向分析与监控。 |
| **`./data/temp/`** | 临时文件 | [`AppConfig`](file:///d:/projs_r/rx-mimic/crawler-bootstrap/src/main/java/org/rx/crawler/config/AppConfig.java) (`downloadPath`) | **中转临时目录**：浏览器文件下载中转、临时报表导出暂存，由 Windows 定期计划清理任务负责维护。 |
| **`./data/logs/crawler-service.log`** | 文本日志 | [`start-crawler-detached.bat`](file:///d:/projs_r/rx-mimic/start-crawler-detached.bat) | **后台进程日志**：后台非阻塞模式（Detached 守护进程）启动时的控制台标准输出（stdout）与标准错误（stderr）转储日志。 |

---

## 五、外置安全配置文件（禁止入库）

| 存储路径 | 格式 | 对应的管理组件 | 具体用途 |
| :--- | :---: | :--- | :--- |
| **`D:\home\.envKeys\keys.json`** | JSON | [`MailLoginNotificationService`](file:///d:/projs_r/rx-mimic/crawler-bootstrap/src/main/java/org/rx/crawler/task/common/MailLoginNotificationService.java) | **外部敏感凭据文件**：存放 SMTP 邮件报警发送方账号、密码/授权码等私密参数。文件独立外置于代码仓库与项目运行目录之外，杜绝源码与提交泄漏风险。 |
