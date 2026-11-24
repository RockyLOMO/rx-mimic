# 京东

现行行为。通用接入见 [文档汇总](README.md)。订单字段见 [订单](order.md)，价格与数字 ID 见 [价格跟踪](price-watch.md)。

服务用本机 Chrome。联盟搜索、转链、订单优先走已登录页面里的 AJAX，失败或字段不完整时回退页面操作。主站搜索和详情走页面操作。

## 登录

| 场景 | 条件 |
| --- | --- |
| 联盟搜索、转链、订单、登录检查 | `JdLoginState.unionReady`：不在联盟登录墙，正文是推广工作台，并且有会话 Cookie |
| 主站搜索、详情 | `JdLoginState.mallReady`：不在主站登录墙，正文已出来，并且有会话 Cookie |

会话 Cookie 是 `pt_key` + `pt_pin`，或 `thor` 再加 `pin` / `unick`。主站登录墙是 `passport.jd.com`，或「扫码登录 / 密码登录 / 短信验证码登录 / 忘记密码」里至少两项。详情页单独出现「密码登录」不算登录墙。

联盟入口确认登录墙后，先用 `app.custom.jdUnion.loginKeysFile` 里的账号密码登录并解滑动拼图，再通知人工。滑块自动次数默认 3 次。未登录时浏览器保持打开，默认再等 180 秒，超时 `LOGIN_REQUIRED`。

## 任务

| action | 页面 | 本地遗留入口（默认关闭） |
| --- | --- | --- |
| `jdUnionSearchProducts` | `https://union.jd.com/proManager/index?pageNo=1` | `POST /custom/jd-union/searchProducts` |
| `getPromotionUrl` | 同上 | `POST /custom/jd-union/getPromotionUrl` |
| `getPromotionUrls` | 同上，请求体是字符串数组 | `POST /custom/jd-union/getPromotionUrls` |
| `getPromotionOrders` | `https://union.jd.com/order` | `POST /custom/jd-union/getPromotionOrders` |
| `loginCheck` | 联盟工作台 | `POST /custom/jd-union/login/check` |
| `jdSearchProduct` | `https://search.jd.com/Search?keyword={keyword}` | `POST /custom/jd/search`，再 `GET /custom/jd/task/{taskId}` |
| `jdProductDetail` | `https://item.jd.com/{skuId}.html` | `POST /custom/jd/product/detail`，同一个任务查询 |

公网调用用 `POST /api/v1/tasks`。联盟搜索 `keyword` 必填，`maxResults` 默认 20、上限 40。转链 `keyword` 是 SKU、链接或分享文案，`adSiteName` 只能是 `0`–`3`。订单 `startTime`、`endTime` 为 `yyyy-MM-dd`。主站搜索 `maxResults` 默认 60、上限 100，最多 8 页。详情 `skuId` 与 `productUrl` 只传一个时另一侧补全；`downloadImages`、`includeSpecs` 默认 true。详情图和主图视频只留原链接。

## 推广位

只使用已有的 `0`–`3`，默认 `0`，不新建。媒体默认「导购媒体推广」/ `wx` / `4101055439`。配置在 `app.custom.jdUnion.adSites`：

| 名称 | ID |
| --- | --- |
| `0` | `3100287104` |
| `1` | `3100287105` |
| `2` | `3100287106` |
| `3` | `3100287107` |

页面按名称选择。列表对不上时，用配置里的推广位 ID 走原有 `getCode`。订单里的推广位优先保留平台名称；只有 ID 时按上表映射成 `0`–`3`，未知 ID 保留原值。

## 转链

期望的完整输出是 `shortUrl`（原生短链，如 `u.jd.com`、`3.cn`、`3.jd.hk`、`u.jd.hk`）和 `promotionToken`（短口令整段）。`promotionUrl`、`longUrl` 仍可出现，不把长链当成短链。转链缓存把缺少短链或口令的结果视为未命中。`shortUrl`、`promotionUrl`、`promotionToken` 三者全空时，队列把成功改成 `PROMOTION_RESULT_INCOMPLETE`。

`fast` 默认 `false`，只作用于单条 `getPromotionUrl`。为 true 时，预检和登录通过后先尝试 AJAX；失败再走原来的页面流程，页面回退仍用原生输入和点击。它不改共享延迟，也不改变缓存键。对比耗时要显式 `useCache=false`。

分享短链和京东国际域名先跟随跳转，只在最终商品页确认数字 SKU。登录页、活动中转页和查询参数里的数字不算商品 ID。已确认的数字 SKU 与本次生成链接在当前 profile 的有界缓存里绑定后，不再为了核验身份打开这条生成链接。

空转链材料不算成功。`NOT_FOUND`、`NO_REBATE_PRODUCT`、`PROMOTION_RESULT_INCOMPLETE` 都是失败。推广入口、弹窗、媒体、推广位或输出缺失使用对应的 `PROMOTION_*`，不用「非返利」代替。页面搜索出现「访问频繁 / 请求频繁 / 操作频繁」时，等待 3 秒后再点搜索，最多额外 3 次；仍受限为 `UPSTREAM_RATE_LIMITED`。

价格优先取明确的到手价或券后价（`finalPrice`），其次京东价或售价（`wlPrice`）。没有标签的展示价标记为 `jdUnionDisplayedPrice`，不拿来和历史价比较。

## 结果

成功表示必填字段有值。搜索丢掉不完整卡片；一张完整卡片都没有则任务失败。空订单列表可以成功。

| 结果 | 必填 |
| --- | --- |
| 联盟搜索卡片 | `skuId` 为数字或临时加密 ID；`productName`；`productLink` 含该 ID；`price`、`commissionRate` 含数字；`storeName`；`imageUrl` 为 http |
| 转链 | `promotionUrl` 为 http。`productInfo` 要有名称、http 链接、价格、佣金比例、店铺、主图。关键字是数字 SKU 时链接须包含它 |
| 订单行 | `productName`、`storeName`、至少 8 位数字的 `orderNo`、`promotionPosition`、`orderStatus`、`orderTime` |
| 主站搜索卡片 | 数字 `skuId`、`title`、含数字的 `price`、包含该 SKU 的 `productUrl`、http `imageUrl`。店铺名可以空，不编造 |
| 主站详情 | 数字 `skuId`、`title`、价格、`shopName`、包含 SKU 的 `productUrl`、至少一张 http 主图、带百分数的 `goodRate`。`includeSpecs=true` 时至少一条规格同时有名称和值 |

主站搜索按主结果列表翻页，不把「猜你喜欢」算进条数。达到请求条数或确认末页才停止；翻页失败不把半页结果当成完整成功。详情滚完 `#detail-main` 后最多再两下，不跟着页尾推荐继续加载。

## 风险保护

每个业务日京东最多访问 `app.custom.priceWatch.dailyMaxProductVisits.jd` 个商品，默认 100。业务日从 `windowStart`（06:00）起算。登录失效或滑动验证失败达到 `dailyMaxLoginOrSliderFailures`（默认 3）后，当天停止该平台后续商品访问，错误码 `RISK_STOP`。重新登录或重启不会自动清掉全部计数。

下一次需要真实抓取的京东单条转链，若熔断里有明确的登录失败、且其它风险未单独到阈值，会先做 `loginCheck`。通过后只扣除登录失败计数。滑块、验证码和未分类的历史计数仍要管理员处理。

管理员入口只接受本机回环和管理员 Bearer：

- `GET /internal/risk/jd/status`
- `POST /internal/risk/jd/recover`：先验证推广工作台，再按验证前的计数原子清当前业务日的京东风险

## 怎么算链路通过

1. 联盟按关键字搜出可转链商品。
2. 用结果里的 SKU 转出带短链和口令的链接。
3. 查最近 7 天订单。列表可以空，状态必须成功。
4. 主站搜索同一关键字，卡片满足上表。
5. 打开其中一个详情，标题、价格、店铺、主图、好评度和规格有值。

本机命令：`start-test-chain.bat jd`。
