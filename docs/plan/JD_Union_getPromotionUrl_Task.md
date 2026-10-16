# 京东联盟 getPromotionUrl 抓取任务

## 任务定义

- 任务名：`getPromotionUrl`
- 目标：根据京东商品 ID 和推广位名称，进入京东联盟后台生成推广短链。
- 运行前置：每次抓取前先通过 `https://bot.sannysoft.com/` 指纹检测。
- 浏览器：仅使用本机 Chrome + Playwright，持久化 profile，默认在任务结束后保留 Chrome 并按 profile 复用已有会话，优先模拟真实人工操作，不追求并发效率。

## 推广链接任务约定

- 任务名统一使用 `getPromotionUrl`。
- 不保留旧的大小写兼容名。
- `getPromotionUrl` 返回结果中必须包含 `productInfo`。
- `productInfo` 字段固定为：
  - `imageUrl`
  - `productName`
  - `productLink`
  - `commissionRate`
  - `price`
  - `storeName`
- 生成推广链接时，若同时存在优惠券链接和普通链接，优先取优惠券链接。

## 订单任务：getPromotionOrders

- 任务名：`getPromotionOrders`
- 初始页：`https://union.jd.com/entire`
- 登录页前缀：`https://union.jd.com/index?returnUrl=`
- 业务页面：`https://union.jd.com/order`
- 前置流程：复用公共 Sannysoft 检测与人工登录接管，默认等待配置仍来自 `app.custom.jdUnion`。
- 调试开关：`debugEnabled` 已提升到全局 `app.custom.debugEnabled`；发布默认关闭，测试/本地验证显式开启，便于读取 HTML 快照排查问题。
- 任务超时：`app.custom.maxTaskMinutes` 默认 4 分钟，超时后返回 `TIMEOUT`。
- 页面遮挡处理：进入页面后如果右上角 `消息公告` 遮挡主体，先点 `收起`。
- 窗口要求：任务开始时先最大化 Chrome，避免底部翻页和表格区域被裁切；底层 `WebBrowser.maximize()` 最大化后需同步 Playwright viewport，避免浏览器外壳已全屏但网页画布仍停留在旧尺寸。

### 入参

请求对象：`JdUnionPromotionOrdersRequest`

| 字段 | 必填 | 说明 | 示例 |
| --- | --- | --- | --- |
| `startTime` | 是 | 开始时间，格式 `yyyy-MM-dd`，要求 `startTime <= endTime` | `2026-04-15` |
| `endTime` | 是 | 结束时间，格式 `yyyy-MM-dd` | `2026-05-08` |
| `profileName` | 否 | Chrome profile 名称，默认 `common` | `common` |
| `forcePreflight` | 否 | 是否强制每次先跑 Sannysoft，默认使用配置 | `true` |
| `keepBrowserOpenOnLoginRequired` | 否 | 未登录时是否保留浏览器给人工接管 | `true` |
| `outputPath` | 否 | 结果 JSONL 写出路径 | `D:/app-crawler/data/jd-union/output.jsonl` |
| `debugEnabled` | 否 | 是否保存关键步骤 HTML 快照，未传时默认取 `app.custom.debugEnabled`；发布默认关闭，测试/本地验证显式传 `true` | `true` |
| `debugOutputDir` | 否 | debug 输出目录 | `D:/app-crawler/data/jd-union/debug` |

最小入参：

```json
{
  "startTime": "2026-04-15",
  "endTime": "2026-05-08"
}
```

### 出参

返回对象：`JdUnionPromotionOrdersResult`

| 字段 | 说明 |
| --- | --- |
| `status` | `SUCCESS` 表示成功，其它值表示失败原因 |
| `taskType` | 固定为 `getPromotionOrders` |
| `startTime` / `endTime` | 查询时间范围 |
| `orders` | 订单数组，无订单时为空数组 |
| `loginRequired` | 是否需要人工登录 |
| `fingerprintPassed` | Sannysoft 是否通过 |
| `diagnostics` | 页码、数量、debug 目录等诊断信息 |

`orders` 每项字段：

| 字段 | 说明 |
| --- | --- |
| `productName` | 商品名称 |
| `productLink` | 商品超链接；页面 DOM 未提供时可为空 |
| `productPrice` | 商品价格 |
| `storeName` | 店铺名 |
| `orderNo` | 订单号 |
| `mainOrderNo` | 主单号 |
| `orderStatus` | 订单状态 |
| `time` | 时间原始文本 |
| `orderTime` | 下单时间 |
| `finishTime` | 完成时间 |
| `settleTime` | 结算时间 |
| `estimatedBillingAmount` | 预估计佣金额 |
| `estimatedCommission` | 预估佣金 |
| `commissionRate` | 佣金比例 |
| `shareRate` | 分成比例 |
| `actualBillingAmount` | 实际计佣金额 |
| `actualCommission` | 实际佣金 |
| `quantity` | 数量原始文本 |
| `productQuantity` | 商品数量 |
| `afterSaleQuantity` | 售后数量 |
| `returnQuantity` | 退货数量 |
| `promotionInfo` | 推广信息原始文本 |
| `promotionPosition` | 推广位 |
| `orderType` | 订单类型 |

### 页面流程

1. 进入 `https://union.jd.com/entire`，必要时等待人工登录。
2. 点击左侧菜单 `订单明细`，再点击 `推客推广订单明细`；如果页面结构变化导致菜单不可用，兜底进入 `https://union.jd.com/order`。
3. 如果页面右上角有 `消息公告` 遮挡主体，先点击 `收起`。
4. 任务开始时先把浏览器窗口最大化。
5. 点击 `时间范围` 后面的非原生日期范围组件。
6. 按入参月份调整弹框月份：左侧月份对齐 `startTime`，点击左侧日期；如果 `endTime` 与左侧同月则继续点左侧日期，否则调整右侧月份后点击右侧日期。
7. 点击 `查找订单`。
8. 抓取当前页订单表格，优先读取 `outerHTML` 并解析；如果 `下一页` 可点击则翻页继续抓取，直到下一页不可点击。
9. 如果表格区域需要额外滚动，先滚动到表格底部再继续读下一屏。
10. 如果翻页后页面数据重复，视为已到末页，停止继续翻页。

### HTTP 调用

```http
POST /custom/jd-union/getPromotionOrders
Content-Type: application/json
```

```json
{
  "startTime": "2026-04-15",
  "endTime": "2026-05-08",
  "debugEnabled": true
}
```

## 入参

请求对象：`PromotionUrlRequest`

| 字段 | 必填 | 说明 | 示例 |
| --- | --- | --- | --- |
| `keyword` | 是 | 商品 ID，只允许数字 | `100341910908` |
| `adSiteName` | 是 | 推广位名称，只允许数字 | `5` |
| `profileName` | 否 | Chrome profile 名称，默认 `common` | `common` |
| `forcePreflight` | 否 | 是否强制每次先跑 Sannysoft，默认使用配置 | `true` |
| `keepBrowserOpenOnLoginRequired` | 否 | 未登录时是否保留浏览器给人工接管 | `true` |
| `outputPath` | 否 | 结果 JSONL 写出路径 | `D:/app-crawler/data/jd-union/output.jsonl` |

最小入参：

```json
{
  "keyword": "100341910908",
  "adSiteName": "5"
}
```

## 出参

返回对象：`PromotionUrlResult`

关键字段：

| 字段 | 说明 |
| --- | --- |
| `status` | `SUCCESS` 表示成功，其它值表示失败原因 |
| `taskType` | 固定为 `getPromotionUrl` |
| `keyword` | 商品 ID |
| `adSiteName` | 推广位名称 |
| `productInfo` | 商品信息 DTO |
| `promotionUrl` | 成功时返回推广链接 |
| `loginRequired` | 是否需要人工登录 |
| `fingerprintPassed` | Sannysoft 是否通过 |
| `message` | 失败说明 |
| `diagnostics` | 诊断信息 |

成功示例：

```json
{
  "status": "SUCCESS",
  "taskType": "getPromotionUrl",
  "keyword": "100341910908",
  "adSiteName": "5",
  "productInfo": {
    "imageUrl": "https://...",
    "productName": "小白熊摇奶器保温二合一温奶...",
    "productLink": "https://item.jd.com/...",
    "commissionRate": "4.8%",
    "price": "480.95",
    "storeName": "小白熊京东自营旗舰店"
  },
  "mediaName": "微信",
  "profileName": "common",
  "promotionUrl": "https://u.jd.com/f6FcZZw",
  "loginRequired": false,
  "fingerprintPassed": true,
  "message": ""
}
```

## 文字流程

1. 打开 `https://bot.sannysoft.com/`。
2. 检查浏览器指纹结果：
   - `navigator.webdriver` 必须为空或缺失。
   - `window.chrome` 必须存在。
   - `plugins`、`languages` 等基础项必须正常。
   - 严格模式下页面报告不能出现明显失败项。
3. Sannysoft 通过后，进入京东联盟初始页：`https://union.jd.com/overview`。
4. 如果跳转到登录页 `https://union.jd.com/index?returnUrl=` 或 `passport.jd.com`：
   - 程序暂停等待人工登录。
   - 默认最多等待 `180` 秒，可通过配置调整。
   - 人工登录完成后，页面应回到初始页或京东联盟已登录页面。
5. 进入商品推广页：
   - 优先从左侧菜单点击 `我要推广`。
   - 再点击 `商品推广`。
   - 如果菜单未找到，兜底进入 `https://union.jd.com/proManager/index?pageNo=1`。
6. 在商品推广页搜索商品：
   - 在商品搜索输入框输入 `keyword`。
   - 点击 `搜索全部商品`。
   - 等待结果加载。
7. 搜索后页面下滚，避免目标商品的 `我要推广` 按钮被遮挡。
8. 从唯一商品卡片提取商品信息并写入 `productInfo`：
   - 商品图片 URL
   - 商品名
   - 商品链接
   - 佣金比例
   - 到手价
   - 店铺名
9. 判断搜索结果：
   - `所有结果 共0件商品` 或无结果文案：返回 `NOT_FOUND`。
   - 搜索结果数量大于 1：返回 `MULTIPLE_MATCHED`。
   - 唯一结果：继续。
10. 点击目标商品区域内的 `我要推广`。
11. 如果出现权益确认，点击 `已获取权益，继续推广` 或 `继续推广`。
12. 在 `生成推广链接` 弹窗内：
    - 推广类型选择 `导购媒体推广`。
    - 点击 `所属导购媒体` 非原生下拉框。
    - 在下拉选项中点击 `微信`。
    - 投放推广位选择 `选择推广位`。
    - 点击 `推广位名称` 非原生下拉框。
    - 在下拉选项中点击入参 `adSiteName`，例如 `5`。
13. 点击 `获取推广链接`。
14. 等待推广链接生成。
15. 点击 `复制`。
16. 从弹窗输入框、文本区域或页面文本读取 `https://...` 链接，优先取 `优惠券链接`，再取普通 `推广链接`，写入 `promotionUrl`。

### 问题记录：`我要推广` 按钮误定位

- 商品推广页存在多个 `我要推广` 文案：左侧导航菜单也叫 `我要推广`，商品卡片内的主按钮也叫 `我要推广`。
- 之前按钮定位条件过宽，只按文案查找时会误选左侧导航项；诊断坐标表现为 `left=0`、`width≈179.8`、`top≈100`，虽然 `clickable=true`，但点击后仍停留在列表页，不会打开生成推广链接弹窗。
- 正确目标必须限定为商品卡片内按钮：
  - 元素优先是 `button.card-button`。
  - 祖先商品卡片文本必须同时包含 `佣金比例`、`预估收益`、`到手价`、`一键领链`。
  - 祖先文本不能包含 `为您推荐以下相似商品`。
- 搜索后需要先滚动到商品卡片操作区，否则商品卡片的 `我要推广` 可能被底部批量操作栏遮住。
- 当前修复策略：
  - 先给目标商品卡片按钮打 `data-rx-jd-primary-promote=1` 标记。
  - 再调用 `Browser.scrollToElement` 优先走 Playwright 原生滚动。
  - 如果按钮仍不在安全点击区，使用 JS 按按钮 `top/bottom` 坐标反复校正，直到落在 `safeTop` 与 `safeBottom` 之间。
  - diagnostics 中记录 `primaryPromoteScroll`，用于确认是否滚到了商品按钮；正确示例坐标为 `left≈251`、`top≈395`、`bottom≈437`、`clickable=true`。

### Cookie 记录

- `cookies.html` 现在作为对比页使用，会同时展示当前 `HttpServletRequest` 的 `Cookie` 头和 `HttpClientCookieJar` 里同一 URL 对应的 cookie。
- `GET /cookies/raw` 现在支持可选 `profileName` 参数；不传时默认读取通用 profile，对应 remoting 接口 `CustomCrawlRemotingContract#cookiesRaw(String profileName, String url)`。
- `cookiesRaw(profileName, url)` 在 `profileName` 非空时，会优先按指定 Chrome profile 读取当前浏览器上下文 cookie；`profileName` 为空时，默认走通用 profile。
- 当前 cookie 保存策略已经统一收口到 `BrowserProfileManager.ProfileLease.close()`：任务执行结束、释放 profile lease 时，自动把当前 Playwright 页面上下文中的 cookie 一次性保存进 `HttpClientCookieJar`，包括 `HttpOnly` cookie。
- 任务代码里不再要求各自手工调用 `browser.saveCookies(false)`；统一由 profile lease 关闭时兜底保存。
- 本地持久化改为 `HttpClientCookieJar + H2CookieStorage`，不再依赖 Redis cookie 容器。

### Chrome 会话复用

- `app.custom.chrome.closeBrowserAfterTask=false` 为默认行为：任务结束仅释放 profile 任务锁并同步 cookie，不关闭 Chrome；后续使用同一 `profileName` 的任务直接复用现有 Playwright/Chrome 会话。
- 设置 `app.custom.chrome.closeBrowserAfterTask=true` 时，普通任务执行结束后立即关闭 Chrome，恢复一次任务一次浏览器的行为。
- 当 `closeBrowserAfterTask=true` 且任务进入人工登录接管时，`keepBrowserOpenOnLoginRequired` 和 `keepBrowserOpenSecondsOnLoginRequired` 仍可临时保留 Chrome。
- 无论配置取值，`POST /custom/profile/close` 对应的显式关闭入口以及应用停止都会真正释放已缓存的 Chrome 会话。

配置示例：

```yaml
app:
  custom:
    chrome:
      profileBasePath: ./data/chrome
      defaultProfileName: common
      closeBrowserAfterTask: false
```

## Debug 模式

- `debugEnabled=true` 时，任务会在 `debugOutputDir/profileName/keyword-时间戳/` 下保存每个关键步骤的 `html` 快照。
- 全局默认开关来自 `app.custom.debugEnabled`，发布默认关闭；请求里的 `debugEnabled` 可显式覆盖，测试类默认显式打开。
- 每个快照文件名包含步骤序号，便于按执行顺序排查。
- 默认关闭，不影响正常抓取流程。

## 登录接管通知

- 公共前置流程检测到登录页时，会触发 `LoginNotificationService` 通知人工接管；任务内部二次跳到登录页时也会补发通知。
- 当前通知实现为邮件提醒，默认关闭；开启后通过 `rxlib-x` 的 `Helper.sendEmail(...)` 发送，SMTP 配置来自 `MiddlewareConfig.smtp.*`。
- 同一 `taskType + profileName + currentUrl` 默认 300 秒内只提醒一次，避免轮询或重试时重复发邮件。
- 定时登录保活由 `LoginKeepAliveService` 负责，JD 两个任务共用 `jdLoginKeepAlive`，TB 两个任务共用 `tbLoginKeepAlive`。
- 保活逻辑会随机访问一个平台业务页；如果 URL 或页面内容显示返回登录页，结果标记为 `LOGIN_REQUIRED`，保留浏览器并触发人工通知。
- 每次任务成功后，会从当前页面随机提取几个同域名链接，持久化到本地 URL 仓库；后续保活优先从这些动态 URL 中随机挑选。
- 保活默认关闭，避免发布后未经配置就周期性打开 Chrome；开启后可通过 HTTP 手动触发检查。

配置示例：

```yaml
spring:
app:
  smtp:
    host: smtp.gmail.com
    port: 587
    startTls: true
    timeoutMillis: 15000
    username: robot@example.com
    password: ${MAIL_PASSWORD}
    from: robot@example.com
    to: ops@example.com
  custom:
    loginNotification:
      enabled: true
      minIntervalSeconds: 300
      mail:
        enabled: true
        subjectPrefix: "[rx-mimic]"
    loginKeepAlive:
      enabled: true
      jdEnabled: true
      tbEnabled: true
      initialDelayMillis: 60000
      fixedDelayMillis: 1800000
      pageTimeoutSeconds: 60
      harvestEnabled: true
      harvestPerRunCount: 5
      maxUrlsPerPlatform: 50
      urlStorePath: D:/app-crawler/data/keepalive/urls.json
```

手动检查入口：

```http
POST /custom/jd-union/login/keepAlive
POST /custom/tb/login/keepAlive
```

## 当前项目 Server 交互

当前项目作为 server 时，由 Spring Boot 启动 `crawler-bootstrap`。配置项：

```yaml
app:
  custom:
    remotingEnabled: true
    remotingListenPort: 1221
```

启动后，`CustomCrawlRemotingService` 会在初始化时执行：

```java
Remoting.register(this, appConfig.getCustom().getRemotingListenPort(), false);
```

也就是说，当前项目会把统一的 `CustomCrawlRemotingContract` 暴露到 `1221` 端口。

## Remoting Client 调用

客户端复用独立的 JD Union API jar 和 RPC 依赖：

`crawler-api` 模块提供：

- `org.rx.crawler.task.common.CustomCrawlRemotingContract`
- `org.rx.crawler.task.common.PromotionUrlRequest`
- `org.rx.crawler.task.common.PromotionUrlResult`
- `org.rx.crawler.task.jd.JdUnionPromotionOrdersRequest`
- `org.rx.crawler.task.jd.JdUnionPromotionOrdersResult`

外部项目可直接依赖：

```xml
<dependency>
    <groupId>org.rx</groupId>
    <artifactId>crawler-api</artifactId>
    <version>1.0</version>
</dependency>
```

```java
import org.rx.crawler.task.common.CustomCrawlRemotingContract;
import org.rx.crawler.task.common.PromotionUrlRequest;
import org.rx.crawler.task.common.PromotionUrlResult;
import org.rx.net.rpc.Remoting;
import org.rx.net.rpc.RpcClientConfig;

import static org.rx.core.Extends.tryClose;

public class JdUnionGetPromotionUrlClient {
    public static void main(String[] args) {
        CustomCrawlRemotingContract client = Remoting.createFacade(
                CustomCrawlRemotingContract.class,
                RpcClientConfig.statefulMode("127.0.0.1:1221", 0));
        try {
            PromotionUrlRequest request = new PromotionUrlRequest();
            request.setKeyword("100341910908");
            request.setAdSiteName("5");

            PromotionUrlResult result = client.getJdPromotionUrl(request);
            System.out.println(result.getStatus());
            System.out.println(result.getPromotionUrl());
            System.out.println(result.getMessage());
        } finally {
            tryClose(client);
        }
    }
}
```

统一推荐调用：

```java
PromotionUrlResult result = client.getJdPromotionUrl(request);
```

批量推广链接调用：

```java
List<PromotionUrlResult> results = client.getJdPromotionUrls(Arrays.asList("100059484008", "100002715968"));
```

`getJdPromotionUrls(List<String> keywords)` 只接收商品关键词列表，任务会复用单条 `getPromotionUrl` 的抓取逻辑；Sannysoft、登录接管、进入京东联盟商品推广工作台只执行一次，从输入 `keyword` 开始循环抓取每个商品，返回 `List<PromotionUrlResult>`。批量模式默认使用配置项 `app.custom.jdUnion.defaultAdSiteName` 作为推广位。

## HTTP 调用入口

除 remoting 外，当前项目也暴露 HTTP 入口：

```http
POST /custom/jd-union/getPromotionUrl
Content-Type: application/json
```

请求：

```json
{
  "keyword": "100341910908",
  "adSiteName": "5"
}
```

批量入口：

```http
POST /custom/jd-union/getPromotionUrls
Content-Type: application/json
```

请求体是字符串数组：

```json
["100059484008", "100002715968"]
```

响应外层为 `Result<List<PromotionUrlResult>>`。

响应外层为统一 `Result<T>`：

```json
{
  "success": true,
  "code": "SUCCESS",
  "message": "",
  "data": {
    "status": "SUCCESS",
    "taskType": "getPromotionUrl",
    "keyword": "100341910908",
    "adSiteName": "5",
    "productInfo": {
      "imageUrl": "https://...",
      "productName": "...",
      "productLink": "https://...",
      "commissionRate": "4.8%",
      "price": "480.95",
      "storeName": "..."
    },
    "promotionUrl": "https://u.jd.com/f6FcZZw",
    "fingerprintPassed": true,
    "loginRequired": false
  }
}
```

## 下个抓取任务描述模板

后续新任务可以按下面格式发需求，便于直接实现。

已新增淘宝联盟推广链接任务说明：[TB_getPromotionUrl_Task.md](./TB_getPromotionUrl_Task.md)，用于记录 `getTbPromotionUrl` 的入参、页面流程、调试和验证命令。

```md
# 抓取任务

## 任务名
例如：getPromotionUrl

## 目标网站
- 初始页：
- 登录页前缀：
- 业务页面：

## 入参
| 字段 | 类型 | 必填 | 说明 | 示例 |
| --- | --- | --- | --- | --- |

## 出参
| 字段 | 类型 | 说明 |
| --- | --- | --- |

## 前置通用流程
1. 是否需要先过 Sannysoft：
2. 是否需要人工登录接管：
3. 初始页最大等待时间：
4. 登录页最大等待时间：

## 页面操作流程
1. 打开什么页面。
2. 点击什么菜单或按钮。
3. 输入什么字段。
4. 遇到弹窗/非原生下拉/遮挡/分页时如何处理。
5. 最终从哪里读取结果。

## 固定选项
- 选项 A：
- 选项 B：

## 异常判断
- 无结果：
- 多结果：
- 页面结构变化：
- 登录失效：

## 验证用例
- 测试账号状态：
- 示例入参：
- 期望结果：
```

## 本次验证记录

验证命令使用 JDK 21、本机 Chrome profile：

```powershell
mvn -pl crawler-bootstrap -am "-Dmaven.test.skip=false" "-DskipTests=false" "-Dtest=JdUnionPromotionTaskTests#jdUnionAuthorizedIntegration" "-Djd.union.integration=true" "-Djd.union.keyword=100341910908" "-Djd.union.adSiteName=5" test
```

验证结果：

```text
Sannysoft: passed
JD keyword: 100341910908
adSiteName: 5
status: SUCCESS
promotionUrl: https://u.jd.com/f6FcZZw
```

## 维护记录

- 2026-05-27：淘宝联盟公共能力同步（详见 [TB_getPromotionOrders_Task.md](./TB_getPromotionOrders_Task.md)、[TB_getPromotionUrl_Task.md](./TB_getPromotionUrl_Task.md)）：`WebBrowser` 移除 `--enable-automation`；`SliderVerifyHandler` 失败后刷新换 NC 挑战、NC handle 严格识别与 iframe 坐标；`getTbPromotionUrl` 商品卡不再要求「到手价」。集成验证：`getTbPromotionOrders` / `getTbPromotionUrl` 均 `SUCCESS`。
- 2026-05-26：新增 `app.custom.chrome.closeBrowserAfterTask` 配置，默认 `false`；JD/TB 公共 `BrowserProfileManager` 在任务完成后保留并复用同 profile 的 Chrome 会话，减少反复启动和关闭浏览器耗时，设置为 `true` 可恢复任务完成即关闭。
- 2026-05-15：同步记录公共依赖与滑块检测维护；Lombok 升级到 `1.18.46`，淘宝联盟公共滑块检测改为 URL、页面文案、可见浮层 DOM 三路判断；京东联盟推广链接主流程无代码变更。
- 2026-05-15：补充淘宝联盟滑块清除等待修正；原因是滑块清除后页面残留隐藏的 `nc_*` / `baxia` / `nocaptcha` 风控 DOM，旧逻辑误判滑块仍存在并在 `waitSliderVerifyCleared` 等到超时；现改为只认可见风控节点，订单集成验证 `2026-04-15` 至 `2026-05-15` 返回 `SUCCESS`。

