# 订单存储查询与新订单 Webhook 订阅接入指南

更新日期：2026-10-24
服务基准地址：`https://cloud.f-li.cn:6400/crawler/api/v1`

---

## 1. 业务概述与架构

本服务负责将京东联盟与淘宝联盟的推广订单统一持久化至 H2 数据库，并提供低延迟查询与实时事件通知能力：

1. **调用方只读 H2 数据库**：调用方查询订单直接从本地数据库读取，耗时在毫秒级，**不等待浏览器爬虫实时抓取**，亦不占用浏览器实例。
2. **后台智能定时同步（防反爬与静默时段）**：服务端内部运行后台同步协调器，复用登录会话，在设定的随机时间区间（默认 8 ~ 15 分钟）内以秒级浮动间隔调度拉取近期订单，并在任务页面注入拟人化操作噪点（光标巡视、滚动查验、拟人停顿等）。**支持每日静默时段配置（默认每日 0 点到 8 点不执行定时订单任务同步）**，静默期不唤起爬虫，8 点自动恢复并加入随机抖动执行。
3. **4 个核心必填字段严格保障**：所有入库订单均保证具备 **订单号 (`orderNo`)**、**商品名 (`productName`)**、**店铺名 (`storeName`)**、**推广位 (`promotionPosition`)** 4 个核心字段，不符合校验规则的数据自动跳过并告警。
4. **新订单 Webhook 由调用方订阅**：当后台同步检测到**新订单首次成功入库**时，在同一事务中登记不可变领域事件 `order.created`，并向**已订阅该数据源的调用方 Webhook 地址**进行异步安全投递。

```mermaid
flowchart TD
    subgraph CrawlerService["rx-mimic 爬虫服务"]
        SyncJob["后台同步协调器<br/>(区间随机定时 8~15min + 拟人噪点)"] --> Engine["京东 / 淘宝联盟爬虫"]
        Engine --> Ingest["入库事务与校验<br/>(4项必填: 单号/商品/店铺/推广位)"]
        Ingest --> DB[("订单主表 orders<br/>明细表 order_items")]
        Ingest --> Outbox[("事件表 order_events<br/>投递表 order_deliveries")]
        Outbox --> Dispatcher["异步 Webhook 投递 Worker<br/>(HMAC-SHA256 签名 + 指数退避重试)"]
        DB --> ReadAPI["只读查询 API<br/>(/api/v1/orders)"]
    end

    subgraph CallerSystem["调用方系统 (业务方 / 返利系统)"]
        SubMgr["调用方订阅接口<br/>(POST /api/v1/orders/webhook/subscriptions)"] -.-> Outbox
        Dispatcher -- "POST <targetUrl><br/>(带 X-Order-Signature 签名)" --> Receiver["调用方接收端 Webhook"]
        Receiver -- "1. 验签防重放<br/>2. 返回 200 OK<br/>3. 异步入账 / 发通知" --> BizQueue["业务异步处理队列"]
        CallerSystem -- "定时对账 / 补漏拉取" --> ReadAPI
    end
```

---

## 2. 订单字段对应关系（2026-10-24 确认）

金额对金额、佣金对佣金，以下四项分别保存和展示，禁止相互替代、相加或交叉映射。实际值为 `0` 必须保留 `0`；未抓到的值为 `null`，不拿预估值补实际值。

| 通用 DTO 字段 | 京东页面 | 淘宝页面 / 页面自然响应字段 |
| --- | --- | --- |
| `orderNo` | 订单号 | 子订单编号 / `tradeId` |
| `productName` / `storeName` | 商品名 / 店铺 | 商品名 / 店铺 / `itemTitle`、`sellerShopTitle` |
| `skuId` / `productLink` | 真实商品详情链接中的数字 SKU | 真实商品详情链接中的数字商品 ID；`mktId` 为营销加密标识，不能当数字 ID |
| `orderTime` | 平台“下单时间”原值，业务含义为付款时间；兼容保留 | 平台创建时间 / `tkCreateTime`，兼容保留，不作为 App 默认时间项 |
| `paidTime` | 平台“下单时间” / `orderTimeStr`、`orderTime` | 付款时间 / `tbPaidTime`；`tkPaidTime` 另保留原值 |
| `finishTime` | 完成时间，即签收时间 | 页面标为“结算”的时间 / `tkEarningTime`，按本项目业务口径视为签收时间 |
| `settleTime` | 签收月份的下个月 20 日 | 签收月份的下个月 20 日 |
| `productQuantity`（入库为 `quantity`） | 商品数量 | 商品数量 / `itemNum` |
| `promotionPosition` | 推广位 ID | `siteName` 原文，兼容回退 `adzoneName` / `adzoneId`；缺失不得捏造默认值 |
| `estimatedBillingAmount` | 预估计佣金额 | 付款金额 / `alipayTotalPrice` |
| `estimatedCommission` | 预估佣金 | 付款预估佣金收入 / `pubSharePreFee` |
| `actualBillingAmount` | 实际计佣金额 | 结算金额 / `payPrice` |
| `actualCommission` | 实际佣金 | 结算预估佣金收入 / `pubShareFee` |
| `commissionRate` | 佣金比例 | 页面总提成率 / `tkTotalRateForCommissionAndSdy` 等 |
| `orderStatus` / `normalizedStatus` | 原始状态 / 统一状态 | 原始状态 / 统一状态 |

App 默认时间区仅显示 **付款时间、签收时间、结算时间**，分别对应 `paidTime`、`finishTime`、`settleTime`。不再重复展示下单时间。

两平台佣金结算日期统一按**签收月份的下一个月 20 日**计算，显示 `yyyy-MM-dd`，例如 2026-10-19 → 2026-11-20、2026-11-01 → 2026-12-20、2026-12-31 → 2027-01-20。未知签收日期则结算日期未知，不用付款时间推算。JD 原始页面结算值保留在 `extensions.rawPlatformSettleTime`，不覆盖统一推算口径；`settlementDateSource=receipt-next-month-20`。该日期是业务预期佣金结算日期，不直接证明佣金已经发放。


统一状态：`PENDING`、`PAID`、`COMPLETED`、`SETTLED`、`INVALID`、`UNKNOWN`。淘宝原“已结算”按此业务口径映射 `COMPLETED`（签收），原文保留，不能仅凭该文案认定佣金已发放。

### 平台扩展字段

公共字段放 `PromotionOrderItem` 与 H2 订单/明细 DTO；平台特有字段放 `extensions`，H2 使用 JSON 列保存，并随只读订单接口返回。兼容旧平铺字段期间，新页面优先读结构化扩展。

- 京东：`afterSaleQuantity`（售后数量）、`returnQuantity`（退货数量）、`shareRate`、`rawOrderStatus`。两数量必须分别按标签读取；任一个大于 0，统一状态为 `INVALID` / 已失效，即使平台原状态仍是“已完成”。两个都是 0 应显示 0；缺失显示未知，不能从其它数量猜测。
- 淘宝：`parentOrderNo`（父订单）、`siteName`、`siteId`、`adzoneId`、`adzoneName`、`refundTag`、`mktId`、原始付款/结算时间、原状态等。`refundTag` 不等同于京东退货或售后数量。
- Android 返现页使用紧凑卡片：平台/单号/状态同一行，商品标题最多两行、店铺和数量同行；四金额以预估/实际两行、计佣金额/佣金两列展示。时间区只显示付款时间、签收时间、结算时间；商品 ID、推广位、平台信息点击展开。

### 按平台区分的 AJAX 采集约束（2026-10-24 修订）

**JD 允许主动 AJAX；TB Union（淘宝联盟）禁止主动 AJAX。** 该限制不能泛化成所有平台禁止。

- **JD**：恢复联盟搜索、转链、订单优先调用已登录浏览器页面内的 AJAX 网关；调用失败或字段校验不完整时沿用页面操作兜底。登录态与签名使用既有浏览器环境，敏感参数不记日志。恢复通道不回退本轮订单字段、零金额、退货/售后与日期语义修复。
- **TB Union**：搜索、转链、订单统一通过页面操作执行。禁止自行拼接、签名、重放或直接请求联盟 AJAX / 网关接口，包括 `executeScript` 中的 `fetch`、XHR、页面 SDK 或浏览器请求客户端；主动调用不能作为主通道或回退。
- **TB Union 自然响应允许读取**：点击页面查询、翻页前注册响应监听，读取页面操作自然产生的响应，按当前页子订单号匹配。例如从 `report.getTbkOrderDetails.json` 的自然响应读取 `siteName`；不自行请求该接口，不复制 Cookie、token、签名或维护分页协议。缺失响应时读取页面可确认字段，未知值留空。
- 淘宝主站已有页面操作、自然响应、SSR/DOM 流程保持现状；图片资产下载不属于这里限制的 TB Union 业务 AJAX。

现有自动滑动验证及人工兜底顺序保持原有实现，不因采集通道调整而跳过或替换。

---

## 3. 调用方 Webhook 订阅接口

调用方通过本组 REST 接口动态注册、管理其接收 Webhook 通知的端点。所有请求均需在请求头携带调用方的认证凭据：`Authorization: Bearer <caller-key>`。

### 3.1 创建 / 注册 Webhook 订阅

调用方调用此接口登记自己的回调地址和用于验签的密钥。

- **请求方法**：`POST`
- **请求路径**：`/api/v1/orders/webhook/subscriptions`
- **请求头**：
  - `Authorization: Bearer <caller-key>`
  - `Content-Type: application/json`

**请求体参数**：

| 参数 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `targetUrl` | String | 是 | 调用方的 HTTP(S) 回调接收地址（生产环境强制 HTTPS，本地开发支持 localhost / 127.0.0.1） |
| `secret` | String | 是 | 共享签名密钥（建议至少 16 位的随机安全字符串），用于服务端生成 HMAC-SHA256 签名及调用方验签 |
| `platform` | String | 否 | 订阅平台过滤：`"all"`（默认，全部平台）、`"jd"`（仅京东）、`"tb"`（仅淘宝） |
| `sources` | List<String> | 否 | 订阅特定数据源 ID 列表，如 `["jd-main", "tb-main"]`，缺省表示订阅当前账号可见的所有源 |
| `events` | List<String> | 否 | 订阅事件类型列表，缺省为 `["order.created"]` |
| `description` | String | 否 | 订阅用途描述，如 `"返利主系统新订单通知"` |

**请求示例**：
```http
POST /crawler/api/v1/orders/webhook/subscriptions HTTP/1.1
Host: cloud.f-li.cn:6400
Authorization: Bearer your-caller-api-key
Content-Type: application/json

{
  "targetUrl": "https://api.yourcompany.com/v1/webhooks/orders",
  "secret": "whsec_9f7a8b6c5d4e3f2a1b0c9d8e7f6a5b4c",
  "platform": "all",
  "events": ["order.created"],
  "description": "返利结算系统新订单通知"
}
```

**响应示例**：
```json
{
  "success": true,
  "code": "SUCCESS",
  "message": "Subscription created successfully",
  "data": {
    "subscriptionId": "sub_ord_8829104",
    "callerId": "caller-fanli-main",
    "targetUrl": "https://api.yourcompany.com/v1/webhooks/orders",
    "platform": "all",
    "events": ["order.created"],
    "status": "ACTIVE",
    "createdAt": "2026-10-23T12:00:00Z"
  }
}
```

---

### 3.2 查询当前调用方的订阅列表

- **请求方法**：`GET`
- **请求路径**：`/api/v1/orders/webhook/subscriptions`
- **请求头**：`Authorization: Bearer <caller-key>`

**响应示例**：
```json
{
  "success": true,
  "code": "SUCCESS",
  "data": [
    {
      "subscriptionId": "sub_ord_8829104",
      "targetUrl": "https://api.yourcompany.com/v1/webhooks/orders",
      "platform": "all",
      "events": ["order.created"],
      "status": "ACTIVE",
      "consecutiveFailures": 0,
      "lastDeliveryAt": "2026-10-23T12:05:30Z",
      "lastDeliveryStatus": "SUCCESS",
      "createdAt": "2026-10-23T12:00:00Z"
    }
  ]
}
```

---

### 3.3 修改 Webhook 订阅

- **请求方法**：`PUT`
- **请求路径**：`/api/v1/orders/webhook/subscriptions/{subscriptionId}`
- **请求头**：`Authorization: Bearer <caller-key>`、`Content-Type: application/json`

**请求体**：
```json
{
  "targetUrl": "https://api.yourcompany.com/v1/webhooks/orders-new",
  "secret": "whsec_new_secret_key_string",
  "status": "ACTIVE"
}
```

---

### 3.4 删除 / 取消 Webhook 订阅

- **请求方法**：`DELETE`
- **请求路径**：`/api/v1/orders/webhook/subscriptions/{subscriptionId}`
- **请求头**：`Authorization: Bearer <caller-key>`

**响应示例**：
```json
{
  "success": true,
  "code": "SUCCESS",
  "message": "Subscription deleted successfully"
}
```

---

### 3.5 触发连通性测试 (Ping Test)

调用方可主动请求触发一条模拟的测试事件，用于联调调用方接收端的连通性与验签逻辑。

- **请求方法**：`POST`
- **请求路径**：`/api/v1/orders/webhook/subscriptions/{subscriptionId}/test`
- **请求头**：`Authorization: Bearer <caller-key>`

**响应示例**：
```json
{
  "success": true,
  "code": "SUCCESS",
  "data": {
    "testEventId": "evt_test_771829",
    "httpStatus": 200,
    "latencyMillis": 85,
    "signatureVerified": true
  }
}
```

---

## 4. 调用方接收端 Webhook 接口规范

当新订单存入后，本系统将向调用方订阅的 `targetUrl` 发送 HTTP POST 请求。调用方需按照以下规范实现接收端接口。

### 4.1 HTTP 请求协议

- **请求方式**：`POST`
- **请求协议**：`HTTPS`（超时阈值：连接 3 秒，读取 5 秒）
- **HTTP 请求头**：

| 请求头 | 说明 | 示例 |
| --- | --- | --- |
| `Content-Type` | 报文类型 | `application/json; charset=utf-8` |
| `X-Order-Event-Id` | **全局唯一事件 ID（调用方幂等去重键）** | `"evt-ord-20261023-9001"` |
| `X-Order-Delivery-Id` | 本次投递记录 ID（重试时各次不同） | `"4012"` |
| `X-Order-Timestamp` | 秒级时间戳（防重放攻击） | `"1792750058"` |
| `X-Order-Signature` | **HMAC-SHA256 签名（防篡改与身份认证）** | `"sha256=a1b2c3d4..."` |

### 4.2 事件 Payload 结构

```json
{
  "eventId": "evt-ord-20261023-9001",
  "type": "order.created",
  "version": 1,
  "occurredAt": "2026-10-23T10:07:38.683Z",
  "data": {
    "orderId": 1,
    "sourceId": "jd-main",
    "platform": "jd",
    "orderNo": "3656467016410282",
    "productName": "雨虹飞鱼厨房烟道止逆阀全密封油烟机专用排烟管厨房通用",
    "storeName": "雨虹飞鱼京东自营旗舰店",
    "promotionPosition": "默认推广位",
    "skuId": "100156915831",
    "orderTime": "2026-10-19 15:39:45",
    "finishTime": "2026-10-20 10:19:15",
    "settleTime": "2026-11-20",
    "orderStatus": "3",
    "itemCount": 1,
    "totalAmount": 51.77,
    "totalCommission": 2.59,
    "discoveredAt": "2026-10-23T10:07:38.683Z",
    "initialBackfill": false,
    "orderPath": "/api/v1/orders/1"
  }
}
```

**Payload 字段明细说明**：

- `eventId`：事件全局唯一标识，如 `evt-ord-...`。重试投递时保持相同。
- `type`：事件类型。新订单固定为 `order.created`。
- `version`：契约版本号，当前为 `1`。
- `occurredAt`：事件生成时间（ISO-8601 UTC）。
- `data.orderId`：本系统内部订单唯一数字主键，用于请求 `/api/v1/orders/{orderId}` 获取完整明细。
- `data.platform`：平台标识，`jd`（京东）或 `tb`（淘宝）。
- `data.orderNo`：**【必填字段】** 平台原生订单号。
- `data.productName`：**【必填字段】** 商品名称（若一单多商品，为首个商品标题或聚合标题）。
- `data.storeName`：**【必填字段】** 店铺名称。
- `data.promotionPosition`：**【必填字段】** 推广位名称。
- `data.skuId`：商品 SKU ID。
- `data.orderTime`：用户下单时间。
- `data.totalAmount`：订单总金额（元）。
- `data.totalCommission`：订单预估分成佣金（元）。
- `data.initialBackfill`：布尔值。`true` 表示属于初次启动时的历史回填单；`false` 表示常规轮询增量单。
- `data.orderPath`：相对路径，调用方使用自身 API Key 访问 `GET {baseUrl}{orderPath}` 可拉取该单的多行商品明细及平台原始 JSON。

---

## 5. 安全签名校验机制

为防止请求伪造、篡改与重放攻击，服务端对每一次 Webhook 调用均进行 HMAC-SHA256 签名。**调用方必须在处理业务前完成验签**。

### 5.1 验签算法步骤

1. **提取请求头**：读取 `X-Order-Timestamp` 和 `X-Order-Signature`。
2. **防重放窗口检查**：计算本地当前时间与 `X-Order-Timestamp` 的差值绝对值：
   $$\Delta t = | \text{currentTimeSeconds} - \text{headerTimestamp} |$$
   若 $\Delta t > 300$ 秒（5 分钟），判定为重放攻击，直接返回 HTTP 400。
3. **构造待签名原文**：
   $$\text{toSign} = \text{headerTimestamp} + "." + \text{rawHttpRequestBody}$$
   *注意：必须使用未经任何反序列化/重新格式化的原始请求 Body UTF-8 字节串。*
4. **计算 HMAC-SHA256**：使用调用方订阅时登记的 `secret` 作为密钥，计算 `toSign` 的哈希值，转为小写 16 进制字符串。
5. **安全比对**：将服务端签名格式与本地计算值比对：
   $$\text{"sha256="} + \text{computedHex} \equiv \text{X-Order-Signature}$$
   *强烈建议采用常量时间比较算法（Constant-time comparison），防止时序侧信道攻击。*

---

### 5.2 多语言验签代码示例

#### Java (Spring Boot) 示例

```java
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

public class WebhookSigner {

    public static boolean verify(String rawBody, String timestampStr, String signatureHeader, String secret) {
        if (signatureHeader == null || !signatureHeader.startsWith("sha256=")) {
            return false;
        }
        try {
            long timestamp = Long.parseLong(timestampStr);
            long now = System.currentTimeMillis() / 1000L;
            if (Math.abs(now - timestamp) > 300) {
                return false; // 超出 5 分钟时间窗口
            }

            String toSign = timestamp + "." + (rawBody == null ? "" : rawBody);
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] hash = mac.doFinal(toSign.getBytes(StandardCharsets.UTF_8));
            String expected = "sha256=" + HexFormat.of().formatHex(hash);

            // 常量时间比对
            return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                signatureHeader.getBytes(StandardCharsets.UTF_8)
            );
        } catch (Exception e) {
            return false;
        }
    }
}
```

#### Python 示例 (FastAPI / Flask)

```python
import hmac
import hashlib
import time

def verify_order_webhook(raw_body_bytes: bytes, timestamp_str: str, signature_header: str, secret: str) -> bool:
    if not signature_header or not signature_header.startswith("sha256="):
        return False
    try:
        timestamp = int(timestamp_str)
        if abs(time.time() - timestamp) > 300:
            return False  # 防重放窗口 5 分钟
        
        to_sign = f"{timestamp}.".encode("utf-8") + raw_body_bytes
        computed = hmac.new(secret.encode("utf-8"), to_sign, hashlib.sha256).hexdigest()
        expected = f"sha256={computed}"
        return hmac.compare_digest(expected, signature_header)
    except Exception:
        return False
```

#### Node.js 示例 (Express)

```javascript
const crypto = require('crypto');

function verifyOrderWebhook(rawBodyBuffer, timestampHeader, signatureHeader, secret) {
    if (!signatureHeader || !signatureHeader.startsWith('sha256=')) {
        return false;
    }
    const timestamp = parseInt(timestampHeader, 10);
    const now = Math.floor(Date.now() / 1000);
    if (Math.abs(now - timestamp) > 300) {
        return false;
    }

    const hmac = crypto.createHmac('sha256', secret);
    hmac.update(`${timestamp}.`);
    hmac.update(rawBodyBuffer);
    const expected = `sha256=${hmac.digest('hex')}`;

    return crypto.timingSafeEqual(Buffer.from(expected), Buffer.from(signatureHeader));
}
```

---

## 6. 响应规范、重试机制与幂等设计

### 6.1 调用方响应规范

1. **快速确认（Fast Acknowledgement）**：
   - 调用方接收端在完成验签并保存事件后，**必须立即响应 HTTP 2xx（推荐 HTTP `200 OK`）**。
   - 响应体格式：`{"success": true}`。
2. **耗时处理必须异步化**：
   - 本系统设置的 Webhook 响应超时时间为 **5 秒**。
   - 调用方禁止在 HTTP 回调主线程中执行耗时的外部 RPC、短信发送、慢 SQL 统计等操作，请将事件 ID 放入调用方本地消息队列（如 Redis Stream / RabbitMQ）异步消费。

### 6.2 投递重试机制

若调用方端点无法连通、超时或返回 HTTP 5xx / 429，系统自动执行**指数退避重试**：

- **最多重试 4 次**（含首次共计 5 次尝试）：
  - 第 1 次重试：失败后 **1 分钟**
  - 第 2 次重试：失败后 **5 分钟**
  - 第 3 次重试：失败后 **15 分钟**
  - 第 4 次重试：失败后 **60 分钟**
- **Retry-After 支持**：若调用方返回 HTTP 429 并携带 `Retry-After: <seconds>` 响应头，系统优先遵循该时间进行避让重试。
- **致命错误中止**：若调用方返回除 429 以外的 HTTP 4xx（如 400 Bad Request、401 Unauthorized、404 Not Found），系统判定为客户端配置不可恢复错误，立即停止后续重试，并将投递标记为 `EXHAUSTED`。

### 6.3 幂等性处理（调用方必做）

根据分布式网络规范，Webhook 保证**至少一次交付（At-least-once Delivery）**。在网络偶发抖动或重试时，调用方可能会收到相同事件。

**调用方幂等方案**：
1. **优先使用 `X-Order-Event-Id` 去重**：在调用方数据库建立 `order_event_history` 表，将 `eventId` 设为主键或唯一索引。收到 Webhook 时尝试先插入，若唯一约束冲突则直接返回 200 并跳过重复业务处理。
2. **业务维度去重**：以 `(platform, orderNo)` 组合作为业务唯一键。

---

## 7. 主动只读查询与对账 API

作为 Webhook 异步通知的主动兜底手段，调用方可定期调用以下接口进行对账或拉取完整商品行明细。

### 7.1 分页与条件查询订单列表

- **请求方法**：`GET`
- **请求路径**：`/api/v1/orders`
- **请求头**：`Authorization: Bearer <caller-key>`

**查询参数**：
- `orderNo`（可选）：平台订单号（精确匹配 `platformOrderNo` 或主单号 `mainOrderNo`）
- `productId` 或 `skuId`（可选）：商品数字 ID / SKU ID（查询包含该商品的订单）
- `productName`（可选）：商品标题模糊搜索关键词
- `platform`（可选）：平台标识，`jd` 或 `tb`
- `sourceId`（可选）：授权数据源，如 `jd-main`、`tb-main`
- `orderStatus`（可选）：订单状态代码（如 `"3"` 已完成）
- `startDate`（可选）：下单开始日期，格式 `yyyy-MM-dd`
- `endDate`（可选）：下单结束日期，格式 `yyyy-MM-dd`
- `page`（可选）：页码，从 1 开始，默认 1
- `pageSize`（可选）：每页条数，默认 50，上限 200

**排序规则**：
- 默认严格按 **`orderTime DESC, id DESC`（下单时间倒序）** 输出，最新产生的订单排在最前面。

**响应示例**：
```json
{
  "success": true,
  "code": "SUCCESS",
  "data": {
    "total": 1,
    "page": 1,
    "pageSize": 50,
    "initialized": true,
    "lastSuccessfulSyncAt": "2026-10-23T10:18:25.599Z",
    "stale": false,
    "items": [
      {
        "id": 1,
        "sourceId": "jd-main",
        "platform": "jd",
        "platformOrderNo": "3656467016410282",
        "mainOrderNo": "0",
        "orderStatus": "3",
        "normalizedStatus": "3",
        "orderTime": "2026-10-19 15:39:45",
        "finishTime": "2026-10-20 10:19:15",
        "settleTime": "2026-11-20",
        "itemCount": 1,
        "totalAmount": 51.77,
        "totalCommission": 2.59,
        "firstDiscoveredAt": "2026-10-23T10:07:38.683Z",
        "lastSyncedAt": "2026-10-23T10:07:38.683Z",
        "items": [
          {
            "id": 1,
            "skuId": "100156915831",
            "productName": "雨虹飞鱼厨房烟道止逆阀全密封油烟机专用排烟管厨房通用",
            "productLink": "https://item.jd.com/100156915831.html",
            "storeName": "雨虹飞鱼京东自营旗舰店",
            "promotionPosition": "默认推广位",
            "quantity": 1,
            "actualBillingAmount": 51.77,
            "actualCommission": 2.59,
            "commissionRate": "5%"
          }
        ]
      }
    ]
  }
}
```

---

### 7.2 查询单笔订单详情及全量商品明细

- **请求方法**：`GET`
- **请求路径**：`/api/v1/orders/{orderId}`
- **请求头**：`Authorization: Bearer <caller-key>`

**响应数据**（包含订单主信息及 `items` 完整明细数组）：
```json
{
  "success": true,
  "code": "SUCCESS",
  "data": {
    "id": 1,
    "sourceId": "jd-main",
    "platform": "jd",
    "platformOrderNo": "3656467016410282",
    "orderStatus": "3",
    "orderTime": "2026-10-19 15:39:45",
    "finishTime": "2026-10-20 10:19:15",
    "settleTime": "2026-11-20",
    "itemCount": 1,
    "totalAmount": 51.77,
    "totalCommission": 2.59,
    "items": [
      {
        "id": 1,
        "skuId": "100156915831",
        "productName": "雨虹飞鱼厨房烟道止逆阀全密封油烟机专用排烟管厨房通用",
        "productLink": "https://item.jd.com/100156915831.html",
        "storeName": "雨虹飞鱼京东自营旗舰店",
        "promotionPosition": "默认推广位",
        "quantity": 1,
        "actualBillingAmount": 51.77,
        "actualCommission": 2.59,
        "commissionRate": "5%",
        "orderStatus": "3"
      }
    ]
  }
}
```

---

### 7.3 查询订单后台同步调度状态

- **请求方法**：`GET`
- **请求路径**：`/api/v1/order-sync/status`
- **请求头**：`Authorization: Bearer <caller-key>`

用于监控各平台数据源的最近抓取完成时间与下一次计划同步时间：
```json
{
  "success": true,
  "code": "SUCCESS",
  "data": [
    {
      "sourceId": "jd-main",
      "platform": "jd",
      "initialized": true,
      "syncStatus": "IDLE",
      "lastSuccessfulSyncAt": "2026-10-23T10:18:00Z",
      "nextSyncAt": "2026-10-23T10:29:43Z"
    },
    {
      "sourceId": "tb-main",
      "platform": "tb",
      "initialized": true,
      "syncStatus": "IDLE",
      "lastSuccessfulSyncAt": "2026-10-23T10:18:25Z",
      "nextSyncAt": "2026-10-23T10:28:15Z"
    }
  ]
}
```

---

### 7.4 定时订单同步与静默时段配置说明

为防止反爬与符合人类行为习惯，后台订单定时抓取支持配置**每日静默时段**（默认每日 0 点至 8 点暂停定时抓取）：

```yaml
app:
  custom:
    orderSync:
      enabled: true
      scanIntervalSeconds: 30
      defaultIntervalMinutes: 4
      minIntervalMinutes: 3
      maxIntervalMinutes: 5
      # 每日静默时段配置：0点到8点不执行定时订单任务同步（可修改时段或设 quietHoursEnabled: false 关闭）
      quietHoursEnabled: true
      quietHoursStart: 0    # 静默开始时间（小时，0-23，含，默认 0 点）
      quietHoursEnd: 8      # 静默结束时间（小时，0-23，不含，默认 8 点，即 00:00~08:00 不执行）
      timezone: Asia/Shanghai
```

**行为规则**：
1. **静默时段行为**：处于静默时段内时（如凌晨 00:00 ~ 08:00），后台调度器跳过提交新的爬虫订单抓取任务，不唤起浏览器；
2. **自动恢复与随机扰动**：静默时段结束后（08:00 后），系统自动恢复调度，并在 08:00 基础上增加随机秒级扰动（1 ~ 5 分钟），避免整点突发访问；
3. **健康状态查看**：通过 `GET /api/v1/order-sync/status` 可查看各数据源的 `nextSyncAt`，在静默时段内会自动顺延展示为早晨恢复执行的时间点。

---

## 8. 调用方接入 Checklist

- [ ] **步骤 1：获取凭据**：获取分配给调用方的专属 Bearer API Key，确认具备订单读取及订阅权限。
- [ ] **步骤 2：部署回调接收端**：
  - 支持公开 HTTPS 访问，配置有效 SSL 证书；
  - 实现 HMAC-SHA256 签名校验（比对 `X-Order-Signature`，校验 `X-Order-Timestamp` 窗口）；
  - 成功接收并落库事件 ID 后，快速返回 `200 OK`，整体耗时控制在 3 秒以内；
  - 基于 `X-Order-Event-Id` 实现幂等去重。
- [ ] **步骤 3：注册 Webhook 订阅**：调用 `POST /api/v1/orders/webhook/subscriptions` 提交 `targetUrl` 与 `secret`。
- [ ] **步骤 4：连通性与验签联调**：调用 `POST /api/v1/orders/webhook/subscriptions/{id}/test` 发送测试事件，确认接收端返回 200 且验签通过。
- [ ] **步骤 5：上线与兜底巡检**：正式接收 `order.created` 事件；每日定时调用 `GET /api/v1/orders` 进行数据核对。


## 本轮验证记录（2026-10-24）

- 后端定向回归：72 项，0 失败、0 错误、0 跳过，Maven BUILD SUCCESS。覆盖订单查询/投递/调度、H2 增量列与旧数据投影、实际金额零值、TB 稳定子订单明细身份、JD 独立售后/退货状态、四金额映射与日期、主动 AJAX 入口禁用及既有验证流程回归。
- 本机真实 Chrome 集成：`WebBrowserOrderIntegrationTest`（开关 `-Dbrowser.order.integration=true`）通过。原生点击本地样例查询按钮，由页面自然发请求，生产响应监听器读取并解析推广位/四金额；原生点击商品打开详情标签并取得数字 ID，再关闭临时标签并恢复订单页。使用独立临时 profile 与本地路由样例，不能代替真实 JD/TB 账号现场验证。
- Android：`:app:testDebugUnitTest :app:compileDebugKotlin`，62 项测试无失败，BUILD SUCCESSFUL；未打 APK、未做实机视觉验收。
- `crawler-api` 打包成功，按仓库约定备份并同步至 xiaofan 的依赖目录，安装至本地 Maven；xiaofan-fanli-bootstrap 及依赖模块 compile 成功。
- 本轮未部署远程服务；AnZhi 空订单账号不作为字段完整性的验证证据。


### 后续修订：恢复 JD AJAX（2026-10-24）

此前“全部禁止主动 AJAX”的约束已按用户新要求收窄为 **TB Union 禁止、JD 允许**。JD 联盟搜索/转链/订单恢复 AJAX 优先、页面兜底；TB Union 仍只采用页面操作及自然响应。字段映射和滑动验证逻辑不变。

修订后定向回归 35 项全部通过（JD AJAX 可调用并保留实际零值/失效状态/日期、TB 主动入口禁用、DOM 与 H2 字段回归）；另本轮本机真实 Chrome 页面响应/商品弹窗集成 1 项通过。未进行远程发布或真实 JD 网关现场验收。


### 后续修订：统一时间与紧凑订单卡片（2026-10-24）

JD 平台“下单时间”作为付款时间，“完成时间”作为签收时间；TB 付款时间作为付款时间，平台“结算时间”作为签收时间。两平台佣金结算日期均为签收次月20日，App 时间标签仅为“付款时间、签收时间、结算时间”。历史 JD 订单读取时兼容付款时间缺失及原状态数字3，未知签收不推算结算日期。

Android 卡片压缩重复行与留白，保留四金额独立含义；完整单号、商品ID、推广位和平台扩展可展开查看。验证：后端44项（含本地真实Chrome集成）全部通过；Android编译通过，63项单元测试无失败。未打APK、未部署，尚未做新版设备视觉验收。
