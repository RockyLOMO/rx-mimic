# 淘宝抓取当前说明

本文维护淘宝/天猫主站和淘宝联盟（阿里妈妈）的业务行为。通用运行、远程 v1 接入、部署与发布见 [文档汇总](README.md)。下文 `/custom` 接口为本地兼容入口，不作为公网接入地址。

服务端口 `81`。浏览器是本机 Chrome，Playwright 驱动。TB Union 禁止主动 AJAX，联盟搜索、转链、订单由页面操作驱动，可监听自然响应补全字段；JD 允许主动 AJAX，淘宝主站现有流程保持不变。

## 数字商品 ID 与价格跟踪

2026-10-23 起业务统一使用数字 `productId`，联盟加密商品 ID 统一命名为 `encryptedProductId`，仅临时传递和缓存，不入价格表、持久任务结果或 JSONL。新模型不迁移旧价格数据。数字 ID、商品链接、口令和加密 ID 直接进入商品分析页，在当前分析页读取主商品，核对商品标题新标签的最终数字 ID，再点击当前商品“立即推广”；不再拿加密 ID 返回选品页重复搜索。只绑定已核验数字 ID 后建立跟踪，不依赖领券页跳转。SKU 规格 ID 与 item ID 分开。具体流程与验证记录见本文末尾“当前流程”。

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

转链在页面按名称匹配现有推广位并点击生成推广链接；失败明确返回，不主动调用 `xt.entry.json` 兜底。加密商品标识也走页面搜索和转链流程。任务入参 `adSiteName` 只能是这四个值。

未登录时浏览器保持打开，默认等待 180 秒，超时为 `LOGIN_REQUIRED`，并发送登录失效邮件。滑块先自动拖，仍失败则发送滑动验证邮件，再留给人工，等待时间用 `loginWaitSeconds`，默认 180 秒。同一任务、同一页面 300 秒内只发一封。邮件里的 punish 地址会去掉 query。

JSONL 产出已移除，商品资料与缓存统一写入商品数据库（见 [商品主表与查询缓存](price-watch.md#商品主表与查询缓存2026-10-23)）；下载的图片在 `data/tb-mall/images/{itemId}/`。这些都相对服务工作目录 `D:\projs_r\rx-mimic`。

订单行除必填字段外，还有这些可空字段：`productLink`、`productPrice`、`mainOrderNo`、`finishTime`、`settleTime`、`estimatedBillingAmount`、`estimatedCommission`、`commissionRate`、`shareRate`、`actualBillingAmount`、`actualCommission`、`productQuantity`、`promotionPosition`。页面上商品链接有时为空，所以 `productLink` 在订单行中不是必填。

## 页面操作与自然响应

统一通过页面输入、查询按钮、推广位选择和翻页等操作完成任务，不自行组装或调用平台网关。原主动 AJAX 快捷路径及回退已禁用；禁止重新启用 `fetch` / XHR / SDK 签名请求。

页面操作自然触发的响应可以监听解析，随后与当前页面订单号或商品身份核对；没有响应时读取可确认的 DOM 字段，未知值留空。不要复制 token、Cookie、签名或私自维护分页协议。

订单日期通过页面日期选择器设置；空订单列表可以成功。有订单时需校验必填字段，金额四项和时间的语义见 [订单字段规范](order.md#2-订单字段对应关系2026-10-24-确认)。

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

主站搜索按 `maxResults` 自动翻页。页数 = ceil(期望条数 / 第一页实际条数)。第一页条数取 mtop `itemsArray` 和页面商品卡片里较大的一边，不用调用方传入的条数去截断这一页。2026 年 PC 搜索一页大约 46 条，代码不写死这个数。安全上限是 8 页。翻页时点「下一页」；点完页面没变，再用搜索链接的 `page`、`s` 打开下一页。条数已经够、算出的页数已经够、没有下一页、下一页和上一页是同一批商品，或没有新的商品 ID 时停止。诊断字段有 `pageSize`、`pagesNeeded`、`pagesFetched`。页面操作后读取 mtop 自然响应时，诊断通道表示响应读取来源，不表示主动调用接口。联盟搜索不走这套翻页。

主站详情先滚完图文详情（`#imageTextInfo-content`）。到底后再往下最多两下，然后停，不跟着页尾「看了又看」一直加载。页面上方的「看了又看」标签不是终点。

## 闭环怎么算通过

一条链路全部通过，淘宝功能才算正常：

1. 阿里妈妈选品广场按关键字搜可转链商品。
2. 从结果里取一个商品 ID 生成推广链接（转链）。
3. 查推客订单。订单列表可以为空，但状态必须成功，并且由页面查询操作采集。
4. 淘宝主站关键字搜索，验证返回有效商品卡片。
5. 用同一个商品 ID 打开淘宝主站商品详情。商品 ID、标题、价格、店铺有值。主图、详情图和规格页面上有就写入。

TB Union 搜索、转链、订单禁止主动 AJAX，只读取页面操作及其自然响应；JD 允许主动 AJAX。详见[订单字段与采集约束](order.md#2-订单字段对应关系2026-10-24-确认)。

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

## 数字 ID 无法召回时的身份恢复（2026-10-23）

现场样本按数字 ID 查询选品广场会返回“暂无数据”，不能假定所有数字 ID 都能精准搜回。程序和数据库继续以数字商品 ID 为准：缓存有效时，适配器临时复用最近验证过的联盟材料；缓存不存在时优先复用已保存的完整标题、店铺，必要时按数字 ID 获取主站详情，再用完整标题搜索联盟。同店铺候选排在前面，但不会直接取第一条；每个候选必须经推广链接的最终 URL 验证数字 ID 才能采用价格。

一次恢复默认最多核对 3 个候选，取值范围 1–5；旧标题搜索与刷新详情后的搜索共用额度。默认恢复预算 90 秒（可配置 10–180 秒），在浏览器操作边界检查，超时不再追加操作，不强行中断当前共用 Chrome 操作。配置为 `app.custom.priceWatch.tbIdentityMaxCandidates`、`tbIdentityTimeoutSeconds`。到上限返回 `TB_IDENTITY_CANDIDATE_LIMIT`，超时返回 `TB_IDENTITY_TIME_LIMIT`；不更新价格或触发降价提醒。

正反向材料提示均在同一个最多 500 条、2 MiB、6 小时 TTL 的内存缓存中，不写数据库；重启后仅靠数字 ID、标题、店铺即可尝试恢复。数字商品搜索在适配器中转为材料转链与最终数字 ID 校验，避免把不支持的数字关键词反复提交选品广场。详情恢复不下载图片，不读取规格；主站访问同样计入预算。


### 2026-10-23：商品分析页精确查询

- 普通关键词继续使用选品广场；数字商品 ID、商品/推广短长链接、淘口令、联盟加密 ID 改走 `https://pub.alimama.com/portal/v2/union-lab.htm?pageKey=analysic`，对应配置 `custom.tbPromotion.analysisUrl`。
- `itemId` 参数接收完整输入（先 URL 编码），直接打开分析结果；保留原生表单输入作为页面辅助能力。等加载结束，仅抓取主商品卡片的商品名、店铺、当前价格、佣金率，不读取历史价格或相似推荐。
- **主商品名打开的新标签页**才是数字 ID 核验入口：只读真实淘宝/天猫商品 URL，数字输入必须与其一致，关闭本次详情标签后返回分析页，再点“立即推广”。推广弹层商品名也须匹配。联盟加密 ID、口令及推广链接与已核验数字 ID 的映射只进有界内存缓存，后续边界复用这次证明；不再依赖领券推广链接自动跳到详情。88VIP 价格用独立 `unionAnalysis88VipPrice` 比较序列，普通分析价格用 `unionAnalysisPrice`。
- 商品名 + 店铺 + 候选转链方案退出生产主流程，旧实现标记为 deprecated 保留参考；不再为数字 ID 启动详情抓取或遍历候选。
- 本机页面核对：数字 ID `1079405698632` 得到雅萌商品，数字 ID `776371444627` 切换为蓝氏商品（到手价 105.00，佣金率 1.50%）；服务端完整链路验证另见后续记录。


#### 早期推广链接验证记录（2026-10-23，已被下方标题新标签方案替代）

- 本地定向 Maven 构建：52 项测试通过；Python 本地验证脚本测试：16 项通过。提交 `6ec40ce` 的独立 CI 编译/离线回归通过（Actions 39902182598）。部署工作流未加入任何商品验证。
- AnZhi 实测 `776371444627`：新商品分析页可完成分析、选位并生成推广链接；该链接落到 `uland.taobao.com/coupon/edetail` 领券中间页，未自动进入真实商品页。因此响应 `identityResolved=false`，未创建价格监控记录（查询监控表对应记录数为 0）。**完整身份核验与价格入库尚未通过**。
- 浏览器工具的站点安全策略阻止检查该领券页；已请求人工说明点击主商品后的页面行为。此处不能将领券页上的营销 ID 或输入数字直接充当最终 URL 核验结果，也不能为了通过验证放宽入库条件。
- 本地精确验证命令（部署后单独运行；当前完整链路待解决领券中间页）：`python deploy/app-crawler/verify_products.py --access-file <本机凭据JSON路径> --tb-product-id 776371444627`。它核对数字 ID、原始长链接、推广链接三个输入、最终身份以及对应价格来源的快照，不输出推广链接或密钥。
- 京口令/淘口令归一与有界缓存已覆盖模拟单测；口令真实页面链路尚未实测。


#### 标题新标签方案与验证边界（2026-10-23 15:15）

- 按用户确认的页面行为调整：`itemId=数字ID/淘口令/长短链接` → 主商品分析 → 点击“日推广量”左侧商品名 → 新标签页真实商品 URL 提取数字 ID → 关闭临时标签返回分析页 → “立即推广”转链。
- 新标签只借用第二个槽位；可释放系统预加载标签，不关闭其它已有标签；异常路径也清理本次 popup 并恢复原页。读取最多 30 秒，只接受连续两次稳定的真实商品 URL；登录页、领券中间页或数字 ID 不一致均不会入库价格。
- 本机已核对直接数字参数 `776371444627` 能正确返回蓝氏商品，随后输入框、地址栏与商品卡统一到同一个临时营销 ID。
- 最新本地 Maven 构建：60 项定向测试全部通过（含标签创建/读取/关闭异常清理、数字身份、输入分流、数据库边界及 Spring 上下文）；Python 验证脚本仍为 16 项通过。
- **标题打开详情的新标签真实跳转尚未实测**：此前浏览器工具对 `uland.taobao.com` 的站点安全限制仍有效，本次没有换工具或执行服务脚本访问该受限页面。已完成编译与模拟回归，不能将其表述为完整线上商品验证通过。


#### 发布结果

- 代码提交 `2434f1cd5170af6622db32fa5bbad05ba070c35f` 已部署 AnZhi，部署工作流 [39916690451](https://github.com/RockyLOMO/rx-mimic/actions/runs/39916690451) 成功。
- 部署后本地 SSH 脚本只读核对 `/health/live` 与 `/health/ready`：两者版本均为上述提交，`draining=false`。没有在部署工作流执行商品验证。
- 独立 CI [39916684764](https://github.com/RockyLOMO/rx-mimic/actions/runs/39916684764) 成功：Java 187 项、失败 0、错误 0、跳过 1；Python 16 项通过。
- 验证范围仍以“标题新标签方案与验证边界”为准：部署健康与离线回归通过，不代表真实商品新标签跳转和价格入库已实测通过。


### 历史流程：分析页仅换联盟物料（2026-10-23，已被 2026-10-25 当前流程替代）

按用户最新确认的操作实现：

1. 用数字商品 ID（或口令/链接）访问 `union-lab.htm?pageKey=analysic&itemId=<URL编码输入>`。
2. 等地址栏 `itemId` 变为联盟加密 ID，只读取该地址，不打开商品标题或领券链接。
3. 返回原选品页，用该加密 ID 精确搜索；仅接受唯一结果，使用结果里最新的加密 ID 走原转链流程。
4. 已知数字 ID 与本次分析输出、唯一选品结果绑定；接口若返回不同数字 ID 则拒绝。映射与推广链接只进有界内存，数据库仍只存数字 ID 及价格。

- 加密 ID 在选品结果里可能再变化，因此不以加密串相等判断商品是否相同。
- 普通关键词仍走原选品搜索；商品名 + 店铺恢复继续停用。冷启动、没有数字 ID 线索的口令/加密物料，如果联盟接口也未给数字 ID，则明确未解析，不进入领券页兜底，不记录价格。
- 本次不调用 `readLinkDestination`，新标签详情核验方案不再是生产主流程。
- 本地 57 项定向测试通过，BUILD SUCCESS；AnZhi 部署后实测记录待补。


### 淘宝所有活跃页面的滑动验证接入（2026-10-23）

本次补的是各任务调用原处理器的覆盖，`SliderVerifyHandler.java` 的检测、拖拽、重试算法保持不变。

| 入口 | 接入位置与恢复策略 |
| --- | --- |
| 商品分析页 | 等待地址栏物料 ID 的每轮轮询先调用既有验证处理；刷新恢复到完整分析参数 URL，不误回选品首页；验证码处理耗时不占 URL 变化等待预算，整体仍受任务截止时间约束。 |
| 联盟选品/转链 | 原页面处理保留；页面操作仍经过既有验证流程，主动网关路径已禁用。 |
| 联盟订单 | 日期、读行、翻页、列表等待均保留既有验证检查；只读取页面操作自然响应，验证失败停止，防止读取默认日期/页码数据。 |
| 淘宝/天猫搜索、详情 | 删除 identityOnly 跳过；分页、响应等待、详情滚动均检查；恢复到当前商品或搜索页，不固定回首页；失败停止读取及发布成功。 |
| 淘宝保活 | 先通过滑动验证再判断登录/健康；失败通知并保留窗口，不将验证码页判为 healthy。 |

验证：本地 `mvn ... -Dtest=TbVerificationFlowTest,SliderVerifyHandlerTest,TbUnionAnalysisPageTest,TbUnionPageApiTest,TbNumericLookupTest,MallSearchPagesTest,LoginKeepAliveServiceTest,LoginNotificationServiceTest,ProductIdentityResolverTest,PriceApplicationContextTest package`，61 项通过，BUILD SUCCESS（包括网关上下文丢失仍调用验证钩子的回归）。新增测试进入独立 CI；部署工作流仍只执行部署。

已知实测记录：先前物料桥接版本在 AnZhi 已完成 `776371444627` → 唯一选品 → 转链 → 数字 ID 确认 → 价格入库，快照 `105.00 / unionPromotionPrice / comparable=true`；后续完整复测触发分析页滑块，暴露原轮询漏接，本节修改针对该缺口及其它淘宝入口一并修复。新部署后的完整复测结果另记。


#### 实测补充：滑块通过后的续期页

- AnZhi `2b93097` 实测日志：`16:06:28 step=02-analysis-slider attempt=1/5` 检测到整页滑块；刷新后 `16:06:54 Slider verify passed ... attempt=2`。已证实原处理器实际被调用且第二轮通过，算法文件未修改。
- 随后停在 `pub.alimama.com/?forward=...` 的“确认登录 / 快速进入”续期页，旧物料轮询因未恢复续期而超时。本次在每轮验证完成后调用原 `completeForwardLanding`，再检查续期后的验证码并读取物料 ID。
- 分析页地址识别允许官方 HTTP/HTTPS 地址，同时严格校验域名、路径、pageKey，避免官方 forward 参数里的 HTTP 地址被误判；保活将“快速进入”视为未恢复状态，避免 healthy 误报。

#### 完整实测与失败中止（2026-10-23）

- AnZhi `e6aeffe` 本轮实际触发分析页滑块：`16:29:18 step=02-analysis-slider attempt=1/5`，`16:30:02 Slider verify passed ... attempt=3`；随后完成后续查询，原 `SliderVerifyHandler.java` 算法文件保持不变。
- 部署成功后从本机运行 `python deploy/app-crawler/verify_products.py --access-file <本机凭据JSON路径> --tb-product-id 776371444627`：数字 ID、原始商品长链接、已生成推广链接三项均输出 `TB_ANALYSIS_INPUT_OK`；最终输出 `TB_ANALYSIS_VERIFY_OK productId=776371444627 price=105.00 snapshots=9`。这些验证不在部署 Action 中执行。
- 修复数字搜索结果的持久化/响应白名单漏掉 `identityResolved`，保留来源明确给出的标记，不因字段是纯数字就推断已验证。相关定向回归 43 项通过。
- 最后复查修复联盟关键词搜索 `waitSearchSettled` 吞掉滑块失败的问题：现在抛固定 `TB_SLIDER_VERIFICATION_REQUIRED`，终止搜索和转链，不再读取遮罩下可能残留的旧卡片。相关定向构建 38 项通过，独立 CI [39924595383](https://github.com/RockyLOMO/rx-mimic/actions/runs/39924595383) 通过。
- 最终代码 `5b16519a043a34e19b1b9f8bc194e13ea2ab4553` 部署 [39924705017](https://github.com/RockyLOMO/rx-mimic/actions/runs/39924705017) 成功；部署后本地 SSH 核对 live/ready 版本一致，`draining=false`。最后补丁只涉及验证失败中止；上述完整商品实测发生在前一版 `e6aeffe`。
- 真实触发滑块的端到端证据覆盖分析→选品→转链链路；订单、主站搜索/详情、保活的接入有定向回归覆盖，本轮没有逐一人工制造验证码验证。


## 订单字段与页面采集约束（2026-10-24）

完整公共字段映射见 [订单字段对应关系](order.md#2-订单字段对应关系2026-10-24-确认)。TB Union 搜索、转链、订单不允许主动 AJAX 或主动网关兜底；可以读取页面操作自然响应。该禁令不适用于 JD。

淘宝子订单号为公共订单号，父订单放 `extensions.parentOrderNo`。付款金额→预估计佣金额；结算金额→实际计佣金额；付款预估佣金收入→预估佣金；结算预估佣金收入→实际佣金。`tkEarningTime` 按项目业务口径记为签收时间，佣金结算日期按下个月 20 日推算。`siteName` 从点击页面“订单查询”自然产生的响应读取为推广位显示原文，同时保留原生 `adzoneId/siteId/adzoneName`；禁止主动请求 `report.getTbkOrderDetails.json`。加密 `mktId` 不作公共数字商品 ID。

App 默认只显示付款时间、签收时间、结算时间：页面付款时间为付款，页面“结算时间”/`tkEarningTime` 为签收；佣金结算日期统一按签收次月20日计算，`yyyy-MM-dd`。


## 转链短链与口令契约（2026-10-24）

JD/TB 统一返回 `shortUrl`（平台原生短链接）与 `promotionToken`（完整分享口令），二者是独立字段。App 展示和复制统一为 `shortUrl#promotionToken`，`#` 为字面连接符，无 `${}` 包装；不要把展示组合串作为网络短链请求。缺单字段时仅展示已有字段，不添加多余 `#`；历史 `promotionUrl` / `longUrl` 暂留兼容，不将长链伪装成短链。

TB 通过原生页面切换“淘口令”格式，从当前文案提取 `¥…¥` / `￥…￥` 完整口令（含内部标记/空格）和 `tb.cn` 的原生短链，例如 `m.tb.cn`；忽略金额、页面外链接和隐藏旧文案。此过程不主动请求联盟 AJAX。
旧的缺少短链或口令的成功缓存不再作为完整命中返回，需刷新获取新格式；有效新结果仍按原缓存时效使用。

本轮转链输出验证：后端定向59项全部通过，含JD/TB两个可见本机Chrome素材弹窗样例，验证原生标签切换、短链接、完整短口令及隐藏/页面外干扰项过滤；未进行真实账号远程现场验收。


### 2026-10-25：商品分析无返利结果

- 分析页地址栏未产生物料 ID、且页面停留在空结果占位时，先确认表单可操作，再通过原生点击“立即分析”提交一次。继续沿用既有滑动验证处理，不主动调用淘宝联盟 AJAX。
- 在完整等待预算结束后，只有本次已提交、结果持续为空且没有加载、登录、验证码或页面错误迹象，才返回 `NO_REBATE_PRODUCT`，提示“非返利商品，暂不支持转链”。加载未完成、页面异常等仍保留原错误，不据此判定无佣金。
- 错误码须穿过物料解析、转链结果与任务接口传至 App；App 显示业务提示，不自动重试该失败。此处表示本次联盟分析未给出可返利物料，并非商品永久不参加推广的保证。
- 本地 Chrome 隔离页面集成覆盖：原生提交一次后稳定空白返回业务失败；持续加载不判为非返利。该用例使用本地页面夹具，不代表已对示例商品做线上验证。


### 当前流程：商品分析页直接推广（2026-10-25）

- 原流程“分析页换加密 ID → 回选品页智能搜索”可能打开另一个分析标签；浏览器仅登记该标签，任务仍在原选品页执行搜索回退和推广，造成当前页错位。商品输入现在在新导航的分析页完成读取、身份核验和“立即推广”，不再重复搜索加密 ID，也不全局抢占最新标签。普通关键词仍走选品搜索。
- 本次导航的地址栏、输入框、标题链接可能使用不同联盟加密别名；仅在官方分析页读取有效主商品卡片，不要求三个别名字符串相等。数字身份通过主商品标题打开的详情新标签最终 URL 核验，核验后关闭临时详情标签并返回原分析页；与原数字输入不一致时禁止推广。推荐区不参与取值。
- 每段等待、身份核验前后和推广素材采集仍走原有验证处理；自动滑动验证算法和人工兜底顺序不变。TB Union 仍禁止主动 AJAX。
- 分析页推广弹层的链接格式采用折叠下拉：原生点击“短链接”展开后再选“淘口令”。原选品页可直接点击淘口令的版本继续兼容，输出仍为 `shortUrl` 和 `promotionToken`。

- 两条代码路径均保留：`app.custom.tbPromotion.analysisViaGoodsSearch=false`（默认）直接在 `union-lab` 推广；设为 `true` 时回选品页搜索加密 ID，搜索动作仅清理本次来源页新开的官方分析标签，再回原搜索页继续设置推广位，不抢占或关闭既有/预加载标签。备用路径的标签恢复有本地 Chrome 夹具回归，本轮未将备用路径作为默认实跑。
- 输入、下拉过滤和确认操作均通过 Playwright/Browser 原生键盘或点击；淘宝推广任务移除了 JS 改值、原型 setter、`dispatchEvent` 与 JS 点击兜底。相关规则已写入根目录 `AGENTS.md`。
- 同一推广素材先读取“短链接”模式，再选择“淘口令”读取口令，分别组装为两个字段。分析页实测官方短链为 `https://s.click.taobao.com/<短路径>`，解析器兼容这一形式，同时不把带签名查询串的长网关链接当短链。
- 推广位确认后读取素材区实际 `mm_...` ID，与请求推广位严格一致才返回成功；不能仅根据弹窗关闭或链接改变判断生效。
- 2026-10-25 本地真实 Chrome、已登录 `common` profile 实跑：商品 `786248509602`（华为手环表带），价格 `5.78`、佣金率 `0.90%`；默认推广位 `0` 返回 `SUCCESS`，推广位 `1` 实际切换至 `mm_10299978403_3442650231_116333200319` 后返回 `SUCCESS`，两次均取得短链与淘口令。旧流程实跑曾返回 `PAGE_CHANGED / TB promotion material dialog not ready`。自动滑块曾在旧流程实跑中通过，算法文件未改动。本轮未部署远端。
- 本轮定向回归 `82` 项全部通过（含真实 Chrome 本地页面夹具：标签正常/异常恢复、旧标签保留、推广位字段定位、不同营销 ID 别名、单选与下拉素材格式、短链/口令分模式读取）。`crawler-api` 构建并同步到 xiaofan 本地依赖；未触发发布。
