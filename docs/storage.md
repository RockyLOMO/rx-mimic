# 存储

路径相对进程工作目录。AnZhi 示例把库放在 `D:/rx-mimic/data/`。没有 JSONL 订单或转链流水。

## 目录

```text
data/
├── browser-task-db.mv.db       # 任务、订单、返现队列
├── product-price-db.mv.db      # 商品、价格、搜索和转链缓存
├── playwright-driver/          # Playwright 驱动解压目录
├── keepalive/urls.json         # 登录保活 URL，每平台最多 50 条
├── jd-union/debug/             # 联盟调试快照
├── jd-mall/debug/
├── jd-mall/images/{skuId}/
├── tb-union/debug/
├── tb-mall/debug/
├── tb-mall/images/{itemId}/
├── aqc/debug/
├── temp/                       # 浏览器下载
└── logs/crawler-service.log    # 脱离终端启动时的控制台日志
backups/db/                     # 两个库的 zip，默认每两天 03:00，保留 30 天
```

Chrome 用户数据不在项目里。本机默认 `%LOCALAPPDATA%\rx-mimic\chrome\<profile>`，AnZhi 示例是 `D:/rx-mimic/profiles`。

调试快照在 `debugEnabled=true` 时写入对应平台的 `debug` 目录：步骤 HTML、标签视口 PNG、桌面 PNG。保留 72 小时，总量 512MB，剩余空间低于 1GB 时停止新增。仓库默认关闭调试；生产示例打开。

## 任务库

配置 `app.storage.h2DbPath`，默认 `./data/browser-task-db`。

| 表 | 用途 | 保留 |
| --- | --- | --- |
| `custom_crawl_task_queue` | 异步任务、幂等键、请求和结果 | 结果大字段保留 7 天（`crawler.task-api.retention-seconds`） |
| `promotion_order` | 订单主表。平台、源、订单号、时间、金额、统一状态 | 不按时间删除 |
| `promotion_order_item` | 商品明细和平台扩展 JSON | 不按时间删除 |
| `order_sync_state` | 每个 `sourceId` 的同步游标 | 按源更新 |
| `order_event` | 仅 `order.created` | 30 天 |
| `order_event_delivery` | 每个接收方的投递状态 | 30 天 |
| `client_cashback_queue` | 调用方加设备的返现队列 JSON | 随客户端覆盖 |

HTTP Cookie 由 `CookieJarConfig` 使用同一个任务库，按 Cookie 自身过期时间淘汰。

## 价格库

配置 `app.storage.priceDbPath`，默认 `./data/product-price-db`。商品身份只存数字 `productId`，不存联盟加密 ID。

| 表 | 用途 | 保留 |
| --- | --- | --- |
| `price_product` | 商品主表。`(platform, platform_product_id)` 唯一。基础、联盟和详情 JSON 分列，空值不覆盖旧值 | 商品行保留。详情响应缓存 12 小时 |
| `product_search_cache` | 搜索结果 | 30 分钟 |
| `product_promotion_cache` | 转链结果。键是商品和推广位 | 14 天。缺 `shortUrl` 或 `promotionToken` 不算命中 |
| `price_watch` | 跟踪规则：目标价、梯队、下次检查时间 | 随订阅更新 |
| `price_snapshot` | 价格采样 | 保留 |
| `price_promotion_record` | 跟踪和转链的关联 | 保留 |
| `price_visit_counter` | 按平台和业务日的访问与风控计数 | 按日 |
| `price_alert_outbox` | 降价提醒待发送 | 随投递更新 |
| `price_event` | 降价事件 | 广播保留 7 天 |
| `price_event_delivery` | 降价广播投递 | 7 天 |

对应配置：

```yaml
app:
  product-cache:
    search-ttl-seconds: 1800
    detail-ttl-seconds: 43200
    promotion-ttl-seconds: 1209600
  storage:
    h2DbPath: ./data/browser-task-db
    priceDbPath: ./data/product-price-db
    backupDir: ./backups/db
    backupCron: "0 0 3 */2 * ?"
    backupRetentionDays: 30
```

加密 ID 到数字 ID 的对应关系只放在进程内有界缓存，重启清空。
