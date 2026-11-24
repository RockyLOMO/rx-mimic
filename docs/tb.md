# 淘宝

现行行为。通用接入见 [文档汇总](README.md)。订单字段见 [订单](order.md)，价格与数字 ID 见 [价格跟踪](price-watch.md)。

服务用本机 Chrome。淘宝联盟搜索、转链、订单只做页面操作，可以读取这次操作自然产生的响应。不自行拼接、签名或重放联盟 AJAX。主站搜索和详情保持页面与自然响应。

## 登录与验证

联盟选品和转链要离开登录墙、进入选品或分析页，并带有阿里妈妈会话 Cookie（如 `_tb_token_`、`cookie2`）。订单要进入效果报表页。主站要离开登录墙，并且搜索列表或详情能解析。

硬登录页包括 `login.taobao.com/member/login`、`login.taobao.com/havanaone/login`、`havanalogin`。页面出现「确认继续用以下账号登录」或「快速进入」时，程序点击续期，不等人工扫码。

NC 滑块由 `SliderVerifyHandler` 用系统光标拖动。失败先刷新，自动次数用尽后发邮件并继续等待。同一平台、同一 profile 默认 1800 秒内只发一封。未登录默认再等 180 秒，超时 `LOGIN_REQUIRED`。

商品页若已是「访问异常提示」，标题、价格、店铺、主图和仍在的好评、参数继续读；视频、详情长图、参数表没出来就留空。详情价格被打码时不算成功。

## 任务

| action | 页面 | 本地遗留入口（默认关闭） |
| --- | --- | --- |
| `tbUnionSearchProducts` | `https://pub.alimama.com/portal/v2/pages/promo/goods/index.htm` | `POST /custom/tb/searchProducts` 或 `/custom/tb-union/searchProducts` |
| `getTbPromotionUrl` | 商品分析页；默认不回选品页再搜 | `POST /custom/tb/getPromotionUrl` 或 `/custom/tb-union/getPromotionUrl` |
| `getTbPromotionUrls` | 同上，请求体是字符串数组 | 对应的 `getPromotionUrls` |
| `getTbPromotionOrders` | `https://pub.alimama.com/portal/v2/effect/order/overviewOrder/page/index.htm` | `POST /custom/tb/getPromotionOrders` 或 `/custom/tb-union/getPromotionOrders` |
| `tbSearchProduct` | `https://s.taobao.com/search` | `POST /custom/tb/search`，再 `GET /custom/tb/task/{taskId}` |
| `tbProductDetail` | `https://item.taobao.com/item.htm?id={itemId}` | `POST /custom/tb/item/detail` |

公网调用用 `POST /api/v1/tasks`。搜索 `keyword` 必填。联盟 `maxResults` 默认 20、上限 40。主站默认 60、上限 100，最多 8 页。转链 `keyword` 可以是数字 ID、加密 ID、链接或口令，`adSiteName` 只能是 `0`–`3`。订单时间支持 `yyyy-MM-dd` 或 `yyyy-MM-dd HH:mm:ss`，`orderScene` 默认 1。详情 `downloadImages` 默认 false，`includeSpecs` 默认 true。详情图和视频只留原链接。

## 推广位

只使用已有的 `0`–`3`，默认 `0`，媒体名 `RockyLOMO`。配置在 `app.custom.tbPromotion.adSites`：

| 名称 | pid |
| --- | --- |
| `0` | `mm_10299978403_3442650231_116337200060` |
| `1` | `mm_10299978403_3442650231_116333200319` |
| `2` | `mm_10299978403_3442650231_116335500196` |
| `3` | `mm_10299978403_3442650231_116332950344` |

按名称点选。生成后读取素材区实际 `mm_...`，必须和请求的推广位一致。不调用 `xt.entry.json` 兜底。

## 转链

`analysisViaGoodsSearch` 默认 `false`：数字 ID、商品链接、口令和加密 ID 进入商品分析页，读取主商品，用标题打开的详情标签核对最终数字 ID，再点当前商品「立即推广」。核对后关掉临时详情标签。与输入的数字 ID 不一致则不推广。普通关键字仍走选品搜索。设为 `true` 时才回选品页按加密 ID 搜索。

分析页提交后，在等待结束时结果仍为空、且没有加载、登录、验证码或页面错误，返回 `NO_REBATE_PRODUCT`（「非返利商品，暂不支持转链」）。这只表示这次分析没有给出可返利物料。

短链和口令分两次读取：先「短链接」，再「淘口令」。`shortUrl` 是 `tb.cn`、`m.tb.cn`、`e.tb.cn` 或 `s.click.taobao.com` 这类原生短链；`promotionToken` 是完整淘口令。带签名查询串的长链接不算短链。缺任一字段的旧缓存不算命中。`fast` 不作用于淘宝。

数字 ID 不能从选品广场直接搜回时，先用已保存的标题和店铺找候选，必要时刷新主站详情。每个候选都要经推广链接的最终 URL 核对数字 ID。默认最多 3 个候选、90 秒，配置为 `tbIdentityMaxCandidates`、`tbIdentityTimeoutSeconds`。超限分别是 `TB_IDENTITY_CANDIDATE_LIMIT`、`TB_IDENTITY_TIME_LIMIT`，不更新价格。

## 结果

成功表示必填字段有值。搜索丢掉不完整卡片。空订单列表可以成功。失败响应可以带 `fallback`，指向同一商品上一次成功结果；成功响应不带。

| 结果 | 必填 | 有则写入 |
| --- | --- | --- |
| 联盟搜索卡片 | `itemId`、`productName`、http `productLink`、含数字的价格和佣金比例、`storeName`、http `imageUrl` | `couponAmount`、`monthSales` |
| 转链 | http `promotionUrl`；数字 `itemId`（解析到时） | `shortUrl`、`promotionToken`、`productInfo` |
| 订单行 | `productName`、`storeName`、至少 8 位数字的 `orderNo`、`promotionPosition`、`orderStatus`，以及 `orderTime` 或 `paidTime` | 金额、链接。未知留空 |
| 主站搜索卡片 | 数字 `itemId`、`title`、完整数字价格、包含该 ID 的 `productUrl`、http 主图 | 店铺、评论、好评、销量。店铺没显示不丢商品 |
| 主站详情 | 数字 `itemId`、`title`、完整数字价格、`shopName` | 主图、详情图原链接、规格、视频原链接、好评、评论数 |

主站搜索以当前页商品卡片决定成员和顺序。自然响应只补同一 `itemId` 的空字段，不混入推荐商品。详情滚完 `#imageTextInfo-content` 后最多再两下。

## 怎么算链路通过

1. 选品广场按关键字搜出可转链商品。
2. 用其中一个商品生成带短链和口令的推广链接。
3. 按日期查订单。列表可以空，状态必须成功。
4. 主站搜索返回完整卡片。
5. 打开对应详情，商品 ID、标题、价格、店铺有值。

本机命令：`start-test-chain.bat tb`。滑块和指纹的研究笔记在 [reference/tb-anti-scraping.md](reference/tb-anti-scraping.md)，不覆盖本文。
