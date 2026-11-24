# 订单

调用方只读已经入库的订单，不等待浏览器。后台同步和字段口径以当前代码为准。通用接入见 [文档汇总](README.md)。

## 只读接口

基址是 `/api/v1`，公网前缀见汇总。均需 `Authorization: Bearer <caller-key>`。调用方只能看到自己被授权的 `sourceId`。默认源是 `jd-main`、`tb-main`，`allowedCallers` 含 `*` 时所有已认证调用方可见。

| 方法 | 作用 |
| --- | --- |
| `GET /orders` | 分页查询。参数：`platform`、`sourceId`、`startDate`、`endDate`、`orderNo`、`productId`、`skuId`、`productName`、`orderStatus`、`page`（默认 1）、`pageSize`（默认 50） |
| `GET /orders/{id}` | 一条订单及其明细。无记录 404，无源权限 403 |
| `GET /order-sync/status` | 各源的同步状态。可加 `sourceId` |

没有 `POST /orders/webhook/subscriptions`。接收地址写在配置里，不由调用方运行时注册。

## 后台同步

`app.custom.orderSync` 默认开启。扫描间隔 30 秒。每个源的执行间隔在 3–5 分钟之间随机，默认 4 分钟；京东和淘宝之间再隔 `platformGapMinutes`（3）分钟。每天 0 点到 8 点（`Asia/Shanghai`）不跑定时同步，8 点后恢复。首次回补 7 天，平时重叠 3 天，低频回补 30 天。

同步复用联盟订单任务和已有登录态。新提交的待执行订单任务会取消同一调用方、平台、profile、`sourceId` 上更早的待执行任务，错误码 `ORDER_TASK_SUPERSEDED`。运行中或等待人工的任务不被新提交打断。

## 字段

金额和佣金四项分开保存。实际值 `0` 保留 `0`；没抓到是 `null`，不用预估值填实际值。

| 字段 | 京东 | 淘宝 |
| --- | --- | --- |
| `orderNo` | 订单号 | 子订单号 |
| `productName` / `storeName` | 商品名 / 店铺 | 商品名 / 店铺 |
| `skuId` | 商品详情链接里的数字 SKU | 商品详情链接里的数字商品 ID。`mktId` 不是数字 ID |
| `paidTime` | 平台「下单时间」 | 付款时间 |
| `finishTime` | 完成时间，按签收 | 页面标为结算的时间，按签收 |
| `settleTime` | 签收月份的下个月 20 日 | 同左 |
| `promotionPosition` | 推广位名称；只有 ID 时按配置映射为 `0`–`3` | 推广位原文。缺失不编造 |
| `estimatedBillingAmount` / `estimatedCommission` | 预估计佣金额 / 预估佣金 | 付款金额 / 付款预估佣金 |
| `actualBillingAmount` / `actualCommission` | 实际计佣金额 / 实际佣金 | 结算金额 / 结算佣金 |

`settleTime` 显示 `yyyy-MM-dd`。没有签收时间就不推算，也不用付款时间代替。扩展字段 `settlementDateSource=receipt-next-month-20`。这是预期结算日，不表示佣金已经打款。

统一状态 `normalizedStatus`：

| 值 | 来源 |
| --- | --- |
| `INVALID` | 京东售后或退货数量大于 0；或原文为已失效、无效、无效-取消、已取消、已退款。淘宝原始状态 `13` 也是 |
| `PENDING` | 待付款、未付款、已下单 |
| `PAID` | 已付款、已支付。淘宝原始状态 `12` |
| `COMPLETED` | 已完成、已签收、确认收货。京东原始状态 `3`。淘宝原文「已结算」或 `3` 也映射到这里，表示签收，不表示佣金已发放 |
| `SETTLED` | 非淘宝特例下的「已结算 / 佣金已结算」 |
| `UNKNOWN` | 空值或未识别文案 |

平台原文留在 `orderStatus` 和 `extensions`。京东 `afterSaleQuantity`、`returnQuantity` 分开记，不能用商品数量代替。淘宝 `refundTag` 不是退货件数。

抓取结果要有订单号、商品名、店铺、推广位和状态。京东还要 `orderTime`，淘宝要 `orderTime` 或 `paidTime` 至少一个。缺这些字段时，这一通道不算成功。写入数据库时，缺少订单号、商品名、店铺或推广位的行会跳过并记警告。

## 新订单通知

首次成功入库时写 `order.created`。更新已有订单不发这个事件。事件表 `order_event`，投递表 `order_event_delivery`，保留 `retentionDays`（默认 30）天。

接收方在 `app.custom.orderSync.webhook.subscribers`：

```yaml
app:
  custom:
    orderSync:
      webhook:
        enabled: true
        subscribers:
          - id: fanli
            sourceIds: ["jd-main", "tb-main"]
            url: https://caller.example/hooks/orders
            signingSecretEnv: ORDER_WEBHOOK_SECRET
```

`sourceIds` 为空或含 `*` 时匹配所有源。密钥优先读 `signingSecretEnv` 指向的环境变量，否则用 `signingSecret`。不要把密钥写进仓库。

投递体字段：`eventId`、`type=order.created`、`version=1`、`occurredAt`，以及 `data.orderId`、`sourceId`、`platform`、`orderNo`、`orderTime`、`discoveredAt`、`initialBackfill`、`itemCount`、`orderPath`。明细用 `orderPath` 再查只读接口。

请求头：

| 头 | 值 |
| --- | --- |
| `Content-Type` | `application/json` |
| `X-Order-Event-Id` | 事件 ID |
| `X-Order-Delivery-Id` | 投递记录 ID |
| `X-Order-Timestamp` | Unix 秒 |
| `X-Order-Signature` | `sha256=` 加 HMAC-SHA256 十六进制。签名字符串是 `{timestamp}.{原始 JSON}` |

2xx 算成功。429 和 5xx 重试。其它 4xx 不再试。超时默认 5 秒。

首次回补产生的事件带 `initialBackfill=true`。`bootstrapNotify` 默认 true。
