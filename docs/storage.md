# rx-mimic 文件、数据库存储与缓存规范说明

更新日期：2026-10-26。  
本文档梳理汇总 `rx-mimic` 运行期间涉及的所有**文件存储位置、数据库表结构、负责组件、缓存时效（TTL）及其具体业务用途**。

所有项目自身产生的数据统一收口于运行目录下的 `./data/`（分为两个物理隔离的 H2 嵌入式数据库及相关业务数据文件），真实浏览器持久会话位于用户环境目录，密钥凭据外置存储。

---

## 目录结构总览

```text
rx-mimic/
├── data/                                 # 运行时本地数据总根目录
│   ├── browser-task-db.mv.db             # H2 嵌入式主任务与订单数据库
│   ├── browser-task-db.trace.db          # 主任务库运行跟踪日志
│   ├── product-price-db.mv.db            # H2 嵌入式商品资料与价格/转链缓存库
│   ├── product-price-db.trace.db         # 价格库运行跟踪日志
│   ├── keepalive/
│   │   └── urls.json                     # 登录保活动态 URL 候选池
│   ├── debug/                            # 统一排障 DOM 快照与截图（按 24h 滚动淘汰）
│   ├── jd-union/                         # 京东联盟数据
│   │   ├── output.jsonl                  # 订单/转链离线流水
│   │   └── debug/                        # 历史排障快照
│   ├── jd-mall/                          # 京东商城数据
│   │   ├── output.jsonl                  # 搜索与详情离线流水
│   │   └── images/                       # 商品主图/轮播图暂存
│   ├── tb-union/                         # 淘宝联盟（阿里妈妈）数据
│   │   ├── output.jsonl                  # 订单/转链离线流水
│   │   └── debug/                        # 历史排障快照
│   ├── tb-mall/                          # 淘宝商城数据
│   │   ├── output.jsonl                  # 搜索与详情离线流水
│   │   └── images/                       # 商品主图/长图暂存
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

## 一、双 H2 嵌入式数据库与表结构总览

`rx-mimic` 采用双 H2 数据库实例物理隔离：
- **主任务与订单库**：`${app.storage.h2DbPath:./data/browser-task-db}`（负责爬虫任务生命周期调度、推广订单明细、同步状态及底层 HTTP Cookie）
- **商品价格与缓存库**：`${app.storage.priceDbPath:./data/product-price-db}`（负责商品统一身份主表、价格时序快照、降价监控，以及搜索/转链/详情缓存）

### 1. 主任务与订单库（`./data/browser-task-db.mv.db`）

| 表名 | 管理组件 / 服务 | 具体用途与存储内容 | 缓存/留存时效（TTL）与清理机制 |
| :--- | :--- | :--- | :--- |
| **`custom_crawl_task_queue`** | [`CustomCrawlQueueService`](file:///d:/projs_r/rx-mimic/crawler-bootstrap/src/main/java/org/rx/crawler/task/common/CustomCrawlQueueService.java) | **异步爬虫任务队列与生命周期管理**：<br>• 存储任务状态（`PENDING`, `RUNNING`, `WAITING_USER`, `SUCCESS`, `TIMED_OUT`, `FAILED`, `CANCELLED`）；<br>• 调度优先级、入参 JSON、执行结果 JSON、幂等去重键（`idempotency_key`）、耗时统计等。 | **结果保留 7 天（604,800 秒）**<br>`crawler.task-api.retention-seconds`<br>每秒定时扫描，超时后将 `result_json` 置为 NULL 清空大字段释放磁盘，保留基础元数据供状态查询。 |
| **`promotion_order`** | [`OrderStore`](file:///d:/projs_r/rx-mimic/crawler-bootstrap/src/main/java/org/rx/crawler/task/order/OrderStore.java) | **推广订单主表**：<br>• 记录平台（`jd`/`tb`）、渠道来源（`source_id`）、平台订单号、主订单号、下单时间、结算时间、预估与实际计费金额、预估与实际佣金等。 | **永久持久化**（业务核心数据，不删除）。 |
| **`promotion_order_item`** | [`OrderStore`](file:///d:/projs_r/rx-mimic/crawler-bootstrap/src/main/java/org/rx/crawler/task/order/OrderStore.java) | **推广订单商品明细表**：<br>• 记录商品 SKU ID / 商品 ID、商品标题、落地链接、店铺名、推广位（PID）、件数、分项佣金与扩展字段；<br>• 兼作淘宝子订单与商品数字 ID 映射缓存（`restoreTbProductIds`）。 | **永久持久化**（业务核心数据，不删除）。 |
| **`order_sync_state`** | [`OrderStore`](file:///d:/projs_r/rx-mimic/crawler-bootstrap/src/main/java/org/rx/crawler/task/order/OrderStore.java) | **各渠道/账号定时订单同步状态表**：<br>• 记录 `jd-main`、`tb-main` 等各 source 的上次成功抓取时间戳、下次调度时间、当前运行任务 ID 与同步游标。 | **永久持久化**（按 source_id 主键原地更新）。 |
| **`order_event`** | [`OrderStore`](file:///d:/projs_r/rx-mimic/crawler-bootstrap/src/main/java/org/rx/crawler/task/order/OrderStore.java) | **订单状态变更事件总线表**：<br>• 记录订单状态迁移（`order.created`, `order.paid`, `order.settled` 等）不可变事件流水及结构化 Payload。 | **保留 30 天**<br>`app.crawler.orderSync.webhook.retentionDays`<br>定期物理删除：`delete from ... where created_at < now - 30天`。 |
| **`order_event_delivery`** | [`OrderStore`](file:///d:/projs_r/rx-mimic/crawler-bootstrap/src/main/java/org/rx/crawler/task/order/OrderStore.java)<br>[`OrderDeliveryService`](file:///d:/projs_r/rx-mimic/crawler-bootstrap/src/main/java/org/rx/crawler/task/order/OrderDeliveryService.java) | **订单 Webhook 逐接收方投递记录表**：<br>• 记录每个订阅端点的投递状态、重试次数、下次尝试时间与错误日志。 | **保留 30 天**<br>与 `order_event` 同步物理清理。 |
| **`storedcookie`** | [`CookieJarConfig`](file:///d:/projs_r/rx-mimic/crawler-bootstrap/src/main/java/org/rx/crawler/config/CookieJarConfig.java)<br>`HttpClientCookieJar` | **底层 HTTP 请求 Cookie 持久化**：<br>• 保存域、路径、值、SameSite 及过期时间戳，保证重启后复用。 | 遵循 HTTP Cookie 本身的 **`Expires / Max-Age`**，加载时淘汰过期记录。 |

---

### 2. 商品资料与价格/转链缓存库（`./data/product-price-db.mv.db`）

| 表名 | 管理组件 / 服务 | 具体用途与存储内容 | 缓存/留存时效（TTL）与清理机制 |
| :--- | :--- | :--- | :--- |
| **`product_promotion_cache`** | [`ProductCacheStore`](file:///d:/projs_r/rx-mimic/crawler-bootstrap/src/main/java/org/rx/crawler/task/price/ProductCacheStore.java)<br>[`ProductCacheService`](file:///d:/projs_r/rx-mimic/crawler-bootstrap/src/main/java/org/rx/crawler/task/price/ProductCacheService.java) | **商品转链与推广链接缓存表**：<br>• 记录商品 ID、推广位名（`ad_site_name`）、淘口令（`promotion_token`）、短链（`short_url`）、长链（`long_url`）及完整结果 JSON。 | **14 天（1,209,600 秒）**<br>`app.product-cache.promotion-ttl-seconds`<br>`(product_id, ad_site_name)` 联合主键。过期后重新抓取转链；口令或短链缺失时强制刷新。 |
| **`product_search_cache`** | [`ProductCacheStore`](file:///d:/projs_r/rx-mimic/crawler-bootstrap/src/main/java/org/rx/crawler/task/price/ProductCacheStore.java)<br>[`ProductCacheService`](file:///d:/projs_r/rx-mimic/crawler-bootstrap/src/main/java/org/rx/crawler/task/price/ProductCacheService.java) | **商品搜索结果缓存表**：<br>• 记录搜索缓存哈希 Key、平台、搜索类型、关键字、搜索参数 JSON 及命中商品列表结果 JSON。 | **30 分钟（1,800 秒）**<br>`app.product-cache.search-ttl-seconds`<br>`expires_at = now + 30m`。过期重新抓取；若爬虫抓取异常，取历史成功记录作为 **fallback 兜底**返回。 |
| **`price_product`** | [`PriceWatchStore`](file:///d:/projs_r/rx-mimic/crawler-bootstrap/src/main/java/org/rx/crawler/task/price/PriceWatchStore.java)<br>[`ProductCacheStore`](file:///d:/projs_r/rx-mimic/crawler-bootstrap/src/main/java/org/rx/crawler/task/price/ProductCacheStore.java) | **商品唯一主表与详情抓取快照**：<br>• 基础永久资料：平台、全局唯一数字商品 ID、商品标题、店铺名、规范化落地 URL；<br>• 聚合资料：`basic_json`、`union_json`、`detail_json`；<br>• 详情快照缓存字段：`detail_result_json`, `detail_cached_at`, `detail_expires_at`。 | 商品基础信息永久留存。<br>其中**商品主站详情快照**时效为 **12 小时（43,200 秒）**（`app.product-cache.detail-ttl-seconds`）。过期重新抓取，失败时支持 fallback。 |
| **`price_watch`** | [`PriceWatchStore`](file:///d:/projs_r/rx-mimic/crawler-bootstrap/src/main/java/org/rx/crawler/task/price/PriceWatchStore.java) | **价格监控规则配置表**：<br>• 记录目标价、降价阈值金额与比例、巡检梯队级别、下次检测时间及计划状态。 | **永久持久化**（按订阅关系更新）。 |
| **`price_snapshot`** | [`PriceWatchStore`](file:///d:/projs_r/rx-mimic/crawler-bootstrap/src/main/java/org/rx/crawler/task/price/PriceWatchStore.java) | **价格时序采样快照表**：<br>• 记录历次巡检采集的标价、券后价、到手价与佣金走势，构建历史价格曲线。 | **永久持久化**（时序采样流水）。 |
| **`price_promotion_record`** | [`PriceWatchStore`](file:///d:/projs_r/rx-mimic/crawler-bootstrap/src/main/java/org/rx/crawler/task/price/PriceWatchStore.java) | **价格监控-关联转链关联表**：<br>• 记录监控商品与前台/后台生成转链任务的对应关系。 | **永久持久化**。 |
| **`price_visit_counter`** | [`PriceWatchStore`](file:///d:/projs_r/rx-mimic/crawler-bootstrap/src/main/java/org/rx/crawler/task/price/PriceWatchStore.java) | **价格监控-风控频次计数器**：<br>• 按 `(platform, business_date)` 统计每日抓取频次与风控滑块触发计数，超阈值自动熔断。 | 按每日业务日期记录。 |
| **`price_alert_outbox`**<br>**`price_event`**<br>**`price_event_delivery`** | [`PriceWatchStore`](file:///d:/projs_r/rx-mimic/crawler-bootstrap/src/main/java/org/rx/crawler/task/price/PriceWatchStore.java)<br>[`PriceDeliveryService`](file:///d:/projs_r/rx-mimic/crawler-bootstrap/src/main/java/org/rx/crawler/task/price/PriceDeliveryService.java) | **降价提醒出件箱、降价事件流水与投递记录**：<br>• 记录降价复核成立后的事件总线与邮件/Webhook 投递状态机。 | 履约完成后归档保留。 |

---

## 二、业务缓存与留存时效总表（速查）

| 缓存类别 | 缓存标识 / 存储载体 | 默认 TTL / 留存时长 | 对应配置项 | 关键特性 / 淘汰逻辑 |
| :--- | :--- | :---: | :--- | :--- |
| **商品转链缓存** | `product_promotion_cache` 表 | **14 天** | `app.product-cache.promotion-ttl-seconds: 1209600` | 跨调用复用转链结果，过期重新转链；短链或淘口令空时强制穿透。 |
| **商品详情缓存** | `price_product.detail_result_json` | **12 小时** | `app.product-cache.detail-ttl-seconds: 43200` | 主站详情页完整快照；过期后重新抓取，失败时提供历史结果 fallback。 |
| **商品搜索缓存** | `product_search_cache` 表 | **30 分钟** | `app.product-cache.search-ttl-seconds: 1800` | 列表页结果缓存；过期后重新检索，异常时 fallback。 |
| **异步任务结果** | `custom_crawl_task_queue` 表 | **7 天** | `crawler.task-api.retention-seconds: 604800` | 到期清空大字段 `result_json`，释放磁盘。 |
| **订单事件流水** | `order_event` / `order_event_delivery` 表 | **30 天** | `app.crawler.orderSync.webhook.retentionDays: 30` | 到期自动执行物理 `DELETE`。 |
| **登录态预检缓存** | JVM 内存 (`ConcurrentHashMap`) | **30 分钟** | `preflightCacheMinutes: 30` | 避免短时间内每个任务都重复执行耗时的账号状态健康检查。 |
| **空闲浏览器连接** | `BrowserProfileManager` 进程池 | **60 秒** | `keepOpenSeconds: 60` | 任务完成后保留 Chrome 实例 60 秒供连续任务复用，超时自动关闭释放内存。 |
| **排障快照文件** | 本地文件（`./data/debug/`） | **24 小时** | `DebugSnapshotPolicy (retentionHours: 24)` | 超出 24 小时的排错截图与 HTML 自动清理。 |
| **登录保活 URL 池** | 本地文件（`./data/keepalive/urls.json`） | 最多 **100 条/平台** | `maxUrlsPerPlatform: 100` | 滚动采样真实合法商品链接，用于保活巡检。 |

---

## 三、相关核心配置示例（`application.yml`）

```yaml
app:
  storage:
    h2DbPath: ./data/browser-task-db          # 主任务与订单库
    priceDbPath: ./data/product-price-db      # 商品资料与价格/转链缓存库
  product-cache:
    search-ttl-seconds: 1800                  # 搜索缓存：30 分钟
    detail-ttl-seconds: 43200                 # 详情快照缓存：12 小时
    promotion-ttl-seconds: 1209600            # 转链缓存：14 天 (1209600秒)
  crawler:
    orderSync:
      webhook:
        retentionDays: 30                     # 订单事件日志保留：30 天
    loginKeepAlive:
      preflightCacheMinutes: 30               # 登录态预检内存缓存：30 分钟
      maxUrlsPerPlatform: 100                 # 登录保活 URL 最大记录数

crawler:
  task-api:
    retention-seconds: 604800                 # 异步任务结果保留：7 天（到期清理 result_json）
```
