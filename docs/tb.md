# 淘宝抓取当前说明

这份文档是淘宝联盟（阿里妈妈）和淘宝主站的当前行为。`docs/plan/` 里原来的淘宝方案已经收进本文后删除。`README.md` 里较早的描述若和本文不一致，以本文为准。

服务端口 `81`。浏览器是本机 Chrome，Playwright 驱动。请求走已登录页面里的接口；页面点击只在接口没有给出完整结果时使用。

## 浏览器

登录、测试和服务共用同一个 Chrome 用户目录：

`C:\Users\R-wtg\AppData\Local\rx-mimic\chrome\common`

这是 `%LOCALAPPDATA%\rx-mimic\chrome\common`。Cookie 库是该目录下的 `Default\Network\Cookies`，加密密钥是同级的 `Local State`。这两份要成对保留，不能只拷 Cookie 库。项目里的 `data\chrome` 不再作为登录态目录。

默认 profile 名是 `common`。`maxActive` 保持 `1`。这个目录已经有 Chrome 打开时，不要再开第二个。登录脚本、链路测试和服务不能同时占用它。停服务前若要落盘登录态，先 `POST /custom/profile/close`。

业务页打开前会先做 bot 检测（`bot.sannysoft.com`，严格模式）。链路测试里 `forcePreflight` 为 true。

## 登录与风控判断

| 场景 | 方法 / 逻辑 | 通过条件 |
| --- | --- | --- |
| 联盟选品、转链 | `TbPromotionUrlTask` 状态匹配 | 不在登录墙，进入选品广场，有有效阿里妈妈 Cookie（`_tb_token_`、`cookie2` 等） |
| 联盟订单 | `TbPromotionOrdersTask` 状态匹配 | 不在登录墙，进入效果报表页；若弹滑块则自动滑块验证通过 |
| 主站商品详情、主站搜索 | `TbMallTask` 状态匹配 | 不在主站登录墙，商品详情数据或搜索列表正常解析 |

硬登录页包含：`login.taobao.com/member/login`、`login.taobao.com/havanaone/login`、`havanalogin`。已去掉过宽的裸 `/login` 匹配，避免误伤业务 URL。

若遇到会话续期页面（页面包含「确认继续用以下账号登录」或「快速进入」等文字），程序会自动点击续期按钮直接恢复会话，无需人工再次扫码。

若在订单报表或主站页面遇到 NC 滑块验证码，`SliderVerifyHandler` 用 Windows 系统光标按真实轨迹拖过滑块。自动拖失败后会刷新当前页，换一块新滑块再试。多次仍失败，则进入人工接管等待（默认等待 120 秒）。

## 任务

| 任务 | 作用 | 页面 | 接口 |
| --- | --- | --- | --- |
| `tbUnionSearchProducts` | 按关键字搜可转链商品，只要卡片基础信息 | `https://pub.alimama.com/portal/v2/pages/promo/goods/index.htm` | `POST /custom/tb/searchProducts` 或 `POST /custom/tb-union/searchProducts`，同步 |
| `getTbPromotionUrl` | 按商品 ID 生成推广链接，并带回 `productInfo` | 同上选品工作台 | `POST /custom/tb/getPromotionUrl` 或 `POST /custom/tb-union/getPromotionUrl`，同步 |
| `getTbPromotionUrls` | 同一会话里按多个关键字循环转链 | 同上 | `POST /custom/tb/getPromotionUrls` 或 `POST /custom/tb-union/getPromotionUrls`，请求体是字符串数组，同步 |
| `getTbPromotionOrders` | 按日期查推客订单 | `https://pub.alimama.com/portal/v2/pages/report/overview/index.htm` | `POST /custom/tb/getPromotionOrders` 或 `POST /custom/tb-union/getPromotionOrders`，同步 |
| `tbSearchProduct` | 主站关键字搜索 | `https://s.taobao.com/search` | `POST /custom/tb/search`，异步，`GET /custom/tb/task/{taskId}` |
| `tbProductDetail` | 主站商品详情 | `https://item.taobao.com/item.htm?id={itemId}` | `POST /custom/tb/product/detail`（同步）或 `POST /custom/tb/item/detail`（异步） |

联盟搜索、转链、订单的日期和关键字由调用方传入。订单入参日期支持 `yyyy-MM-dd` 或 `yyyy-MM-dd HH:mm:ss`。

## 调用

服务地址 `http://127.0.0.1:81`。联盟三个接口同步返回结果。主站搜索和异步详情先返回 `taskId`，再用 `GET /custom/tb/task/{taskId}` 取结果，状态是 `PENDING`、`RUNNING`、`SUCCESS`、`FAILED`。排队中的任务支持通过 `POST /custom/tb/task/{taskId}/cancel` 取消。

| 接口 | 必填 | 常用可选项 |
| --- | --- | --- |
| `POST /custom/tb/searchProducts` 或 `POST /custom/tb-union/searchProducts` | `keyword` | `maxResults` 默认 20，上限 40；`profileName` 默认 `common` |
| `POST /custom/tb/getPromotionUrl` 或 `POST /custom/tb-union/getPromotionUrl` | `keyword`（商品 ID / SKU） | `adZoneId`、`adSiteName`、`mediaName`；不传时用配置默认值 |
| `POST /custom/tb/getPromotionUrls` 或 `POST /custom/tb-union/getPromotionUrls` | 商品关键字字符串数组 | 推广位用配置 `defaultAdSiteName`。选品页和登录只进一次，再逐个转链 |
| `POST /custom/tb/getPromotionOrders` 或 `POST /custom/tb-union/getPromotionOrders` | `startTime`、`endTime`（格式 `yyyy-MM-dd` 或带时分秒） | `orderScene`（默认 1） |
| `POST /custom/tb/search` | `keyword` | `maxResults` 默认 60，上限 100 |
| `POST /custom/tb/item/detail`（异步）或 `POST /custom/tb/product/detail`（同步） | `itemId` 或 `productUrl`，只传一个时另一侧自动补全 | `downloadImages`、`includeSpecs` 不传时按配置处理 |

未登录时浏览器保持打开，默认等待 180 秒，超时为 `LOGIN_REQUIRED`。滑块先自动拖，仍失败再留给人工，默认 120 秒。

联盟结果追加到 `data/tb-union/output.jsonl`。主站结果追加到 `data/tb-mall/output.jsonl`，下载的图片在 `data/tb-mall/images/{itemId}/`。这些都相对服务工作目录 `D:\projs_r\rx-mimic`。

订单行除必填字段外，还有这些可空字段：`productLink`、`productPrice`、`mainOrderNo`、`finishTime`、`settleTime`、`estimatedBillingAmount`、`estimatedCommission`、`commissionRate`、`shareRate`、`actualBillingAmount`、`actualCommission`、`productQuantity`、`promotionPosition`。页面上商品链接有时为空，所以 `productLink` 在订单行中不是必填。

## 先接口，再点击

所有操作优先走页面内接口（带登录态与页面签名），接口未返回完整数据时才降级使用页面 DOM 解析或原生点击兜底：

1. **联盟商品搜索**：在已登录选品页内通过前端网关请求 `skyleap.distribution.site.json`，直接提取结构化数据。
2. **联盟商品转链**：在已登录页内调用 `xt.entry.json` 网关获取 `s.click.taobao.com` 推广链接与佣金信息。
3. **推客订单查询**：在报表页通过网关请求 `report.getTbkOrderDetails.json` 获取订单明细。
4. **主站商品搜索**：先请求无线推荐/搜索接口 `mtop.relationrecommend.wirelessrecommend.recommend`，失败时降级解析页面卡片。
5. **主站商品详情**：页面加载后优先提取服务端直出上下文 `window.__ICE_APP_CONTEXT__` 与详情图接口 `desc.alicdn.com`，完整解析标题、价格、店铺、主图与规格属性。

空订单列表是成功。有订单时，每一行的必填字段都必须有值。

## 结果字段

成功只表示最终 JSON 里的必填字段有数据。接口或页面抓取本身返回了，但必填字段缺失，不算成功。淘宝风控降级时价格有时会被打码成 `2?`，因此价格字段必须通过完整数字校验（`[￥¥]?\d+(\\.\\d+)?`）。

联盟搜索和主站搜索会丢掉字段不齐的卡片。一张完整卡片都没有时，任务失败，不返回成功。

| 结果 | 必填 | 可空 |
| --- | --- | --- |
| 联盟搜索卡片 | `itemId` 为数字；`productName`；`productLink` 为 http 且包含该 itemId；`price`、`commissionRate` 含数字；`storeName`；`imageUrl` 为 http | 无 |
| 转链 | `promotionUrl` 为 http（`s.click.taobao.com`）。`productInfo` 的 `productName`、`productLink`（http，关键字是数字时链接须包含它）、`price`、`commissionRate`、`storeName`、`imageUrl` | 不改共用 `ProductInfoDto` 的注解。淘宝校验在 `TbResultChecks` |
| 订单 | 列表可以为空。非空行要有 `productName`、`storeName`、`orderNo`（至少 8 位数字）、`orderStatus`、`orderTime` | `productLink` 和金额字段。不填充假数据 |
| 主站搜索卡片 | `itemId` 为数字；`title`；`price` 含数字；`productUrl` 包含该 itemId；`shopName`；`imageUrl` 为 http | `commentCount`、`goodRate`、`soldCount` |
| 主站详情 | `itemId` 为数字；`title`；`price` 含数字；`shopName`；`productUrl` 包含该 itemId；至少一张主图（`mainImages`）是 http | 详情图（`detailImages`，PC 淘宝常使用无线端详情 `useWirelessDesc: true` / `pcResistDetail`，故选填）。`includeSpecs` 为 true 时，至少一条规格同时有名称和值 |

主站详情先滚完图文详情（`#imageTextInfo-content`）。到底后再往下最多两下，然后停，不跟着页尾「看了又看」一直加载。页面上方的「看了又看」标签不是终点。

## 闭环怎么算通过

一条链路全部通过，淘宝功能才算正常：

1. 阿里妈妈选品广场按关键字搜可转链商品。
2. 从结果里取一个商品 ID 生成推广链接（转链）。
3. 查推客订单。订单列表可以为空，但状态必须成功，并且走接口通道。
4. 淘宝主站关键字搜索，验证返回有效商品卡片。
5. 用同一个商品 ID 打开淘宝主站商品详情，标题、价格、店铺、主图和规格有值。

搜索、转链、订单、主站详情的诊断字段 `channel` 应为 `ajax`。页面通道只说明接口没有给出完整结果。

在 `D:\projs_r\rx-mimic` 下执行。每个 `-D` 都要加引号。父 POM 默认跳过测试，所以要显式打开。

```text
mvn -B -pl crawler-bootstrap -am "-DskipTests=false" "-Dsurefire.failIfNoSpecifiedTests=false" "-Dtest=TbUnionChainTests#tbUnionChainShouldSearchPromoteAndQueryOrders" "-Dtb.union.chain.integration=true" "-Dtb.union.chain.keyword=抽纸" test
```

调试输出在 `crawler-bootstrap/target/tb-union-chain`。不要把推广链接的追踪参数、Cookie 或签名写进日志摘录。
