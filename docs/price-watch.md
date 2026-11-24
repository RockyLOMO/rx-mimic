# 价格跟踪

数字商品 ID、跟踪接口和调度。表和缓存时间见 [存储](storage.md)。京东、淘宝怎么拿到数字 ID，见各自文档。

## 身份

业务字段是字符串 `productId`：京东数字 SKU，或淘宝/天猫数字 item ID。唯一键是 `(platform, productId)`。

`encryptedProductId` 只出现在平台适配和临时内存里，不进价格表，也不进持久任务结果。推广链接、口令和短链可以进转链缓存，缓存键仍是数字商品 ID 加推广位。

京东收到加密 ID 时，先打开最终商品页取出数字 SKU，再搜联盟。淘宝收到加密 ID 时走分析页，核对详情标签上的数字 ID 后再推广。SKU 规格 ID 不是商品 ID。登录页、中转页和任意站点的 `id` 参数不能当商品身份。明确的数字输入和最终跳转不一致时，不建跟踪。

普通关键字搜索不为整页商品逐个转链。已有数字身份的卡片才落库。

## 接口

都在 `/api/v1` 下，沿用 Bearer 和 `success/code/message/data`。

| 方法 | 作用 |
| --- | --- |
| `POST /tasks` | 联盟搜索或转链。入口做身份规范化 |
| `GET /tasks/{taskId}/result` | 读结果。缓存还在时带临时推广链接 |
| `GET /price-watches` | 当前调用方的跟踪列表 |
| `POST /price-watches` | 关注已入库商品，或更新目标价和梯队 |
| `POST /price-watches/{id}/pause` | 暂停 |
| `POST /price-watches/{id}/resume` | 恢复 |
| `POST /price-watches/{id}/refresh` | 手动刷新。当天已采集返回 `DAILY_CACHED` |
| `GET /price-watches/{id}/snapshots?days=30` | 该订阅的按日价格。支持 30、60、90 |
| `GET /products/{platform}/{productId}` | 商品主档 |
| `GET /products/{platform}/{productId}/snapshots?days=30` | 只读价格历史 |
| `GET /price-watches/budget` | 当前窗口和各平台计数 |
| `GET /price-events?after=&limit=` | 按游标拉取降价事件。游标过期 410 |

关注体示例：

```json
{"platform":"jd","productId":"100018097536","targetPrice":35.00,"tier":0}
```

`tier`：0 重点、1 普通、2 冷门。目标价大于 0，最多两位小数。商品尚未建档返回 `PRODUCT_NOT_FOUND`。`identityResolved=false` 表示链接可能已生成，但还没有数字身份和跟踪。

历史接口只返回范围内每天最后一条成功且价格为正的快照，带 `date`（`YYYY-MM-DD`）。失败不抹掉当天更早的成功价。没有记录的日期不补价格。

## 调度

```yaml
app:
  custom:
    priceWatch:
      enabled: true
      timezone: Asia/Shanghai
      windowStart: "06:00"
      windowEnd: "01:00"
      dailyTotalVisits: 180
      dailyBackgroundVisits: 120
      foregroundReserveVisits: 60
      dailyMaxProductVisits:
        jd: 100
        tb: 100
      dailyMaxLoginOrSliderFailures: 3
      tbIdentityMaxCandidates: 3
      tbIdentityTimeoutSeconds: 90
      activeWatchLimit: 30
      backgroundQueuedLimit: 2
      dropAmount: 1.00
      dropPercent: 3
      reviewDelayMinutes: 15
      snapshotFreshMinutes: 30
      notifyStartHour: 8
      notifyEndHour: 23
      notifyEnabled: false
      broadcast:
        enabled: false
        retentionDays: 7
```

后台检查在现有 Chrome 上串行执行，内部 action 是 `priceRefresh`。缓存命中不再打开页面。当天是否已经采集用进程内布隆过滤器，重启后可能重复采一次。

降价默认至少下降 3% 且至少 1 元，15 分钟后用库里的候选复核。通知默认关闭。邮件和广播只带数字 ID、商品直达地址和价格，不带推广材料。广播接收方写在 `priceWatch.broadcast.subscribers`，没有单独的订阅 REST。

登录或滑块失败达到当天阈值后返回 `RISK_STOP`，并停止该平台后续商品访问。访问超限是 `DAILY_VISIT_LIMIT`。窗口外是 `VISIT_WINDOW_CLOSED`。

部署后的商品抽查脚本：

```powershell
python deploy/app-crawler/verify_products.py --access-file D:/home/.envKeys/rx-mimic-access.json
```

可加 `--platform jd` 或 `--platform tb`。凭据只从本机文件读。
