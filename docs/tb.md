# 淘宝抓取当前说明

本文维护淘宝/天猫主站和淘宝联盟（阿里妈妈）的业务行为。通用运行、远程 v1 接入、部署与发布见 [文档汇总](README.md)。下文 `/custom` 接口为本地兼容入口，不作为公网接入地址。

服务端口 `81`。浏览器是本机 Chrome，Playwright 驱动。请求走已登录页面里的接口；页面点击只在接口没有给出完整结果时使用。

## 数字商品 ID 与价格跟踪

2026-10-23 起业务统一使用数字 `productId`，联盟加密商品 ID 统一命名为 `encryptedProductId`，仅临时传递和缓存，不入价格表、持久任务结果或 JSONL。新模型不迁移旧价格数据。淘宝加密 ID 可临时用于转链，最终访问推广链接解析数字 item ID，再建立跟踪；SKU 规格 ID 与 item ID 分开。

接口、缓存、预算与验证状态见 [数字商品与价格跟踪](price-watch.md)。本文后续的 `skuId/itemId` 字段仅按对应平台含义理解，不能据字段名将加密值当数字 ID。

## 浏览器

本机与远端 profile、单实例和串行调度约定见 [通用运行约定](README.md#通用运行约定)。默认 profile 名 `common`，同一目录只允许一个 Chrome 实例；准备落盘登录态时，可通过本地管理入口 `POST /custom/profile/close` 安全关闭。

业务页打开前先做 bot 检测（`bot.sannysoft.com`）；链路测试中 `forcePreflight=true`。平台登录判断见下文。

## 专题资料

[淘宝反爬技术细节](tb-anti-scraping.md) 保留原研究内容，仅更名；平台当前行为仍以本文及仓库 AGENTS.md 为准。

[滑动验证外围工程审查](reviews/tb-slider-engineering-review-2026-10-21.md) 记录权限、脱敏和调试产物管理问题；仅审查，不改变已有自动验证及人工兜底顺序。

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

主站搜索、主站详情和联盟转链在读数据前，会先把鼠标在页面内容区移动 2 到 4 下，再向下轻滚一段并回到顶部。滑块还在时不做这个动作。商品页如果已经出现「访问异常提示」，这是淘宝按网络和浏览器环境把详情降级，不是没弹出的滑块；继续移动鼠标不会把详情换回来，任务停下来等一段时间再打开。

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

成功只表示最终 JSON 里的必填字段有数据。选填字段页面上有就写入；没有就不补假数据，也不因此失败。淘宝风控降级时价格有时会被打码成 `2?`。详情价格是必填，打码价不能算成功。

商品详情和转链失败时，本次响应保持失败，商品名、价格、店铺、图片、规格、转链地址这些业务字段清空。`fallback` 带回同一商品上一次成功的结果；本地还没有成功记录时 `fallback` 为空。成功响应不带 `fallback`。记录持久化在 H2 数据库（`data/browser-task-db.mv.db`），按数字商品 ID、联盟加密 ID 和请求关键字查找，并联动价格监控表提供降级兜底；避免海量小 JSON 文件对磁盘与 IO 产生性能影响。安智的运行目录是 `D:\rx-mimic`，换版本不会被覆盖。

联盟搜索和主站搜索会丢掉字段不齐的卡片。一张完整卡片都没有时，任务失败，不返回成功。

| 结果 | 必填 | 选填，有则写入 |
| --- | --- | --- |
| 联盟搜索卡片 | `itemId` 为联盟加密 ID；`productName`；`productLink` 为 http；`price`、`commissionRate` 含数字；`storeName`；`imageUrl` 为 http | `couponAmount`、`monthSales` |
| 转链 | `itemId` 为数字商品 ID；`mktItemId` 为联盟加密 ID；`promotionUrl` 为 http | `productInfo` 的商品名、链接、价格、佣金、店铺、图片。页面或接口给出就写入 |
| 订单 | 列表可以为空。非空行要有 `productName`、`storeName`、`orderNo`（至少 8 位数字）、`orderStatus`、`orderTime` | `productLink` 和金额字段。不填充假数据 |
| 主站搜索卡片 | `itemId` 为数字；`title`；`price` 含数字；`productUrl` 包含该 itemId；`shopName`；`imageUrl` 为 http | `commentCount`、`goodRate`、`soldCount` |
| 主站详情 | `itemId` 为数字；`title`；`price` 为完整数字（`[￥¥]?\d+(\.\d+)?`）；`shopName` | 主图、详情图、规格、商品链接。页面上有就写入，没有不影响成功 |

主站搜索按 `maxResults` 自动翻页。页数 = ceil(期望条数 / 第一页实际条数)。第一页条数取 mtop `itemsArray` 和页面商品卡片里较大的一边，不用调用方传入的条数去截断这一页。2026 年 PC 搜索一页大约 46 条，代码不写死这个数。安全上限是 8 页。翻页时点「下一页」；点完页面没变，再用搜索链接的 `page`、`s` 打开下一页。条数已经够、算出的页数已经够、没有下一页、下一页和上一页是同一批商品，或没有新的商品 ID 时停止。诊断字段有 `pageSize`、`pagesNeeded`、`pagesFetched`。每一页都来自 mtop 时 `channel=ajax`，否则是 `page`。联盟搜索不走这套翻页。

主站详情先滚完图文详情（`#imageTextInfo-content`）。到底后再往下最多两下，然后停，不跟着页尾「看了又看」一直加载。页面上方的「看了又看」标签不是终点。

## 闭环怎么算通过

一条链路全部通过，淘宝功能才算正常：

1. 阿里妈妈选品广场按关键字搜可转链商品。
2. 从结果里取一个商品 ID 生成推广链接（转链）。
3. 查推客订单。订单列表可以为空，但状态必须成功，并且走接口通道。
4. 淘宝主站关键字搜索，验证返回有效商品卡片。
5. 用同一个商品 ID 打开淘宝主站商品详情。商品 ID、标题、价格、店铺有值。主图、详情图和规格页面上有就写入。

搜索、转链、订单、主站详情的诊断字段 `channel` 应为 `ajax`。页面通道只说明接口没有给出完整结果。

在 `D:\projs_r\rx-mimic` 下执行 `start-test-chain.bat tb`。不传关键字时测试默认用「抽纸」，推广位用 `0`。调试输出在 `crawler-bootstrap/target/tb-union-chain`。不要把推广链接的追踪参数、Cookie 或签名写进日志摘录。

## 价格跟踪

联盟 `itemId` 按不透明字符串保存。加密串不是数字商品 ID。数字 ID 只从最终商品页读取：主机是 `item.taobao.com`、`item.tmall.com` 或 `detail.tmall.com`，路径以 `/item.htm` 或 `/item.html` 结尾，并且查询参数 `id` 是数字。联盟中转、登录页和其他参数都不当作商品 ID。

每个平台一个业务日最多访问多少个商品，写在 `app.custom.priceWatch.dailyMaxProductVisits`。淘宝目前是 `tb: 100`。登录失效或滑动验证失败达到 `dailyMaxLoginOrSliderFailures`（默认 3）后，当天停止该平台后续商品访问。业务日从 `windowStart`（06:00）起算，过了午夜也不重置。0 或负数表示不按这条停。用户自己提交的搜索和转链仍可入队。

## 运行与验证入口

通用启动、登录与部署脚本见 [汇总脚本表](README.md#本地与部署脚本)。平台全链路命令和验收标准见上文“闭环怎么算通过”。远程调用统一使用 [v1 异步契约](README.md#调用方接入任务)，保留 taskId 后查询状态和结果。

## 远程现场验收记录（2026-10-20）

以下从原远程集成记录归入平台文档，仅代表当轮样本与验收结论；本次文档整理未重新执行。公共重启、结果持久化与发布证据见 [汇总](README.md#平台验收入口与共同重启证据)。

淘宝 taskId `2` 在京东完成后自动领取；用户在真实Chrome完成淘宝登录及滑动验证。测试商品 `601440653278` 最终显示不存在，任务为 `SUCCEEDED`、业务为 `NOT_FOUND`，未伪造成抓取到商品。当前验证了淘宝执行与无商品结果交付；后续已按用户要求通过联盟搜索随机抽取当前商品，并完成正向详情回包，见下表。商品价格仅为此验收任务实际页面返回值。

联盟搜索与随机商品正向验证：

| taskId | 操作 | 实际结果 |
| --- | --- | --- |
| 3 | `tbUnionSearchProducts`，关键词“纸巾”，maxResults=5 | `SUCCEEDED/SUCCESS`，返回5条，联盟总命中89501 |
| 4 | 尝试用选中联盟加密ID经现有转链接口取真实商品链接 | `FAILED/BUSINESS_FAILED`，当轮登录账号未找到当时配置中的既有推广位“5”（历史配置；本文当前推广位为 `0`–`3`）；未新建推广位。转链业务仍需配置实际存在的推广位后单独验收 |
| 5 | 对随机选中的商品标题执行主站搜索 | `SUCCEEDED/SUCCESS`，返回5条；其中1条标题与店铺均完全匹配随机样本，定位真实商品ID |
| 6 | `tbProductDetail`，`978367204794`，不下载图片 | `SUCCEEDED/SUCCESS`，返回下述详情 |

随机方法：从task3实际返回的5条中用 `secrets.choice` 等概率选择，选中第2条；未用固定旧商品ID冒充搜索结果。联盟 `itemId` 是加密 outputMktId，不能直接当淘宝数字ID；主站搜索仅用于定位同标题、同店铺的真实商品链接。

| 项目 | task 6 实际返回 |
| --- | --- |
| 商品ID | `978367204794` |
| 标题 | 【李佳琦直播间双11尾款日】可心柔乳霜纸云柔巾抽纸M码100抽48包 |
| 店铺 / 详情页展示价 | corou可心柔旗舰店 / 239 |
| 图片与规格 | 主图3、详情图0、规格1；未下载图片文件 |
| 指纹与登录 | fingerprintPassed=true，loginRequired=false |

联盟展示价178.00与主站/详情展示价239属于不同页面实际返回，未将其等同为同SKU成交价。详情长图本例未返回，长图及全部规格完整性尚未验收；核心标题、店铺、价格与主图正向回包已验证。

## 交互可靠性修复远程验收（2026-10-21）

部署代码：`663b8b79ef97c91dcc4228d77ee0878a30811325`。由 [GitHub Actions](https://github.com/RockyLOMO/rx-mimic/actions/runs/39597420195) 构建并部署到 AnZhi；已通过 SSH 核对实际运行版本，浏览器、桌面、数据库、调度均 ready。

本轮修复通用点击/输入检查、最大化窗口滚轮、JS 滚动范围、响应事件等待、定时保活及人工兜底等待期限。现有自动滑动验证文件、`mouseDrag` 方法及流程入口保持不变，人工仍仅为自动处理失败后的兜底。

以下通过公网 v1 API 在远端真实 Chrome 上执行；搜索关键字为“抽纸”，转链采用联盟搜索结果，详情采用主站搜索结果。不使用固定旧商品冒充搜索结果，未使用缓存结果兜底。

| 步骤 | taskId | 执行 / 业务状态 | 结果 |
| --- | --- | --- | --- |
| 联盟搜索 | `51` | `SUCCEEDED / SUCCESS` | 3 条有效商品 |
| 转链（推广位 0） | `52` | `SUCCEEDED / SUCCESS` | 有效推广链接及完整 productInfo；不在文档输出追踪参数 |
| 近 7 天订单 | `53` | `SUCCEEDED / SUCCESS` | 0 条订单；空列表允许成功 |
| 主站搜索 | `54` | `SUCCEEDED / SUCCESS` | 3 条有效商品 |
| 主站详情 | `55` | `SUCCEEDED / SUCCESS` | 标题、价格、店铺、主图及规格必填检查通过 |

详情样本：`722284014928`，加厚整箱60包原生木浆抽纸纸巾家用实惠装可湿水餐巾纸家庭装纸抽；店铺：诱姿丽人旗舰店；页面展示价：36.8。主图 3 张，规格 2 条。详情长图返回 0 张，本轮没有验证长图完整性；未下载图片文件。

自动滑动验证现场证据：20:16:47 检测到 `02-order-slider`，attempt=`1/5`；20:16:57 记录 `Slider verify passed`、attempt=`1`。本轮自动通过，未进入人工兜底。

验证构建：本地 CI 同范围 112 项测试全部通过；另一个真实 Chrome 本地 HTML 集成测试通过。GitHub Linux runner 共 112 项、0 失败、0 错误，Windows 专属实例锁测试跳过 1 项（本机已通过）。新增回归已加入 CI 的无真实站点测试清单。

结束状态：代码版本不变，`draining=false`、`runningTasks=0`、`waitingUser=false`、`ready=true`。以上为本轮记录，后续运行状态需重新查询。

### 2026-10-23 AnZhi 发布后本机定向验证

部署完成后从本机运行 `verify_products.py --access-file ... --platform tb`。联盟商品搜索之后，转链任务返回 `getTbPromotionUrl_TASK_FAILED_BUSINESS_FAILED`，本轮未完成淘宝数字身份与价格跟踪整链路验收；不能计作通过。该实测独立于 GitHub Actions 部署，服务发布及启动健康检查已成功。
