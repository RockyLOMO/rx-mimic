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

复用闲置 Chrome 时，如果当前标签已经关掉，导航会在同一个窗口里新开一个标签再打开目标页，不另起浏览器。

若在订单报表、转链或主站页面遇到 NC 滑块验证码，`SliderVerifyHandler` 用 Windows 系统光标拖过滑块。按下之前，光标先从视口里一个随机点沿曲线慢慢移到滑块起点，每次控制点都重新随机，不回放固定轨迹。每次失败先刷新页面，等滑块回到轨道左侧再拖。刷新后若仍停在失败提示页，订单和转链会重新打开原来的业务入口。自动次数用尽后，用和登录失效同一套邮件通知人工，然后继续等待。

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
| `POST /custom/tb/getPromotionUrl` 或 `POST /custom/tb-union/getPromotionUrl` | `keyword`（商品 ID / SKU）、`adSiteName`（只能是 `0`、`1`、`2`、`3`） | `mediaName` 默认 `RockyLOMO`。不传推广位时任务内部用 `0` |
| `POST /custom/tb/getPromotionUrls` 或 `POST /custom/tb-union/getPromotionUrls` | 商品关键字字符串数组 | 推广位用配置 `defaultAdSiteName`。选品页和登录只进一次，再逐个转链 |
| `POST /custom/tb/getPromotionOrders` 或 `POST /custom/tb-union/getPromotionOrders` | `startTime`、`endTime`（格式 `yyyy-MM-dd` 或带时分秒） | `orderScene`（默认 1） |
| `POST /custom/tb/search` | `keyword` | `maxResults` 期望最多抓多少条，默认 60，上限 100。超过一页时按第一页实际条数自动翻页 |
| `POST /custom/tb/item/detail`（异步）或 `POST /custom/tb/product/detail`（同步） | `itemId` 或 `productUrl`，只传一个时另一侧自动补全 | `downloadImages`、`includeSpecs` 不传时按配置处理 |

## 推广位

只允许 `0`、`1`、`2`、`3` 这四个推广位，默认 `0`。别的名称会在打开浏览器前拒绝。不会新建推广位。媒体名是 `RockyLOMO`。名称和 pid 写在 `app.custom.tbPromotion.adSites`：

| 推广位 | pid |
| --- | --- |
| `0` | `mm_10299978403_3442650231_116337200060` |
| `1` | `mm_10299978403_3442650231_116333200319` |
| `2` | `mm_10299978403_3442650231_116335500196` |
| `3` | `mm_10299978403_3442650231_116332950344` |

转链先按名称匹配已登录页的推广位列表。名称对不上时，用上表里的 pid 调 `xt.entry.json`。加密商品 ID 转链失败时不改走页面点击。接口入参 `adSiteName` 只能是这四个值。

未登录时浏览器保持打开，默认等待 180 秒，超时为 `LOGIN_REQUIRED`，并发送登录失效邮件。滑块先自动拖，仍失败则发送滑动验证邮件，再留给人工，等待时间用 `loginWaitSeconds`，默认 180 秒。同一任务、同一页面 300 秒内只发一封。邮件里的 punish 地址会去掉 query。

联盟结果追加到 `data/tb-union/output.jsonl`。主站结果追加到 `data/tb-mall/output.jsonl`，下载的图片在 `data/tb-mall/images/{itemId}/`。这些都相对服务工作目录 `D:\projs_r\rx-mimic`。

订单行除必填字段外，还有这些可空字段：`productLink`、`productPrice`、`mainOrderNo`、`finishTime`、`settleTime`、`estimatedBillingAmount`、`estimatedCommission`、`commissionRate`、`shareRate`、`actualBillingAmount`、`actualCommission`、`productQuantity`、`promotionPosition`。页面上商品链接有时为空，所以 `productLink` 在订单行中不是必填。

## 先接口，再点击

所有操作优先走页面内接口（带登录态与页面签名），接口未返回完整数据时才降级使用页面 DOM 解析或原生点击兜底：

1. **联盟商品搜索**：在已登录选品页内通过前端网关请求 `skyleap.distribution.site.json`，直接提取结构化数据。
2. **联盟商品转链**：在已登录页内调用 `xt.entry.json` 网关获取 `s.click.taobao.com` 推广链接与佣金信息。
3. **推客订单查询**：在报表页通过网关请求 `report.getTbkOrderDetails.json` 获取订单明细。
4. **主站商品搜索**：先截获页面自己发出的 `mtop.relationrecommend.wirelessrecommend.recommend`，读 `itemsArray`。没有这份响应，或条数比页面卡片少时，再解析页面卡片。期望条数超过一页时继续翻页，下一页同样先等新的 mtop，再看页面卡片。
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

主站搜索按 `maxResults` 自动翻页。页数 = ceil(期望条数 / 第一页实际条数)。第一页条数取 mtop `itemsArray` 和页面商品卡片里较大的一边，不用调用方传入的条数去截断这一页。2026 年 PC 搜索一页大约 46 条，代码不写死这个数。安全上限是 8 页。翻页时点「下一页」；点完页面没变，再用搜索链接的 `page`、`s` 打开下一页。条数已经够、算出的页数已经够、没有下一页、下一页和上一页是同一批商品，或没有新的商品 ID 时停止。诊断字段有 `pageSize`、`pagesNeeded`、`pagesFetched`。每一页都来自 mtop 时 `channel=ajax`，否则是 `page`。联盟搜索不走这套翻页。

主站详情先滚完图文详情（`#imageTextInfo-content`）。到底后再往下最多两下，然后停，不跟着页尾「看了又看」一直加载。页面上方的「看了又看」标签不是终点。

## 闭环怎么算通过

一条链路全部通过，淘宝功能才算正常：

1. 阿里妈妈选品广场按关键字搜可转链商品。
2. 从结果里取一个商品 ID 生成推广链接（转链）。
3. 查推客订单。订单列表可以为空，但状态必须成功，并且走接口通道。
4. 淘宝主站关键字搜索，验证返回有效商品卡片。
5. 用同一个商品 ID 打开淘宝主站商品详情，标题、价格、店铺、主图和规格有值。

搜索、转链、订单、主站详情的诊断字段 `channel` 应为 `ajax`。页面通道只说明接口没有给出完整结果。

在 `D:\projs_r\rx-mimic` 下执行 `start-test-chain.bat tb`。不传关键字时测试默认用「抽纸」，推广位用 `0`。调试输出在 `crawler-bootstrap/target/tb-union-chain`。不要把推广链接的追踪参数、Cookie 或签名写进日志摘录。

## 脚本

这些脚本都在仓库根目录，除非另写路径。跑登录或全链路前先停掉占用 `common` 这个 Chrome 目录的服务。

| 脚本 | 作用 |
| --- | --- |
| `start-login.bat [all\|jd\|tb] [profile]` | 打开可见 Chrome 做人工登录。默认 `all`，第二个参数是 profile，默认 `common`。同一时间只能开一个，第二次直接退出并打印 `start-login.bat is already running.`。实际逻辑在 `login.ps1`。 |
| `start-test-chain.bat jd [keyword]` | 京东全链路：联盟搜索、转链、近 7 天订单、主站搜索、主站详情。窗口可见。推广位固定 `0`。 |
| `start-test-chain.bat tb [keyword]` | 淘宝全链路：联盟搜索、转链、近 30 天订单、主站搜索、主站详情。窗口可见。推广位固定 `0`。 |
| `start-crawler-bootstrap.bat` | 本机前台启动爬虫服务。缺 jar 时先打包。 |
| `start-crawler-bootstrap.bat jd-union-orders-verify` | 同样启动服务，并打开京东联盟订单调试目录。这不是全链路。 |
| `start-crawler-detached.bat` | 脱离当前终端启动已打包的服务，日志在 `data\logs\crawler-service.log`。 |
| `deploy/app-crawler/start.bat` | 在部署机上启动已安装的 rx-mimic 服务。 |
| `deploy/app-crawler/rollback.bat` | 在部署机上切回上一份发布包。 |
