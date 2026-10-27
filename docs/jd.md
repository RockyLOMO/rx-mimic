# 京东抓取当前说明

本文维护京东联盟和京东主站的业务行为。通用运行、远程 v1 接入、部署与发布见 [文档汇总](README.md)。下文 `/custom` 接口为本地兼容入口，不作为公网接入地址。

服务端口 `81`。浏览器是本机 Chrome，Playwright 驱动。JD 允许主动 AJAX；联盟搜索、转链、订单优先使用已登录页面内 AJAX，失败或字段不完整时回退页面操作。

## 数字商品 ID 与价格跟踪

2026-10-23 起业务统一使用数字 `productId`，联盟加密商品 ID 统一命名为 `encryptedProductId`，仅临时传递和缓存，不入价格表、持久任务结果或 JSONL。新模型不迁移旧价格数据。京东联盟搜索不支持加密 ID，必须先通过京粉/推广链接解析数字 SKU，再搜索。

接口、缓存、预算与验证状态见 [数字商品与价格跟踪](price-watch.md)。本文后续的 `skuId/itemId` 字段仅按对应平台含义理解，不能据字段名将加密值当数字 ID。

## 浏览器

本机与远端 profile、单实例和串行调度约定见 [通用运行约定](README.md#通用运行约定)。默认 profile 名 `common`，同一目录只允许一个 Chrome 实例；准备落盘登录态时，可通过本地管理入口 `POST /custom/profile/close` 安全关闭。

业务页打开前先做 bot 检测（`bot.sannysoft.com`）；链路测试中 `forcePreflight=true`。平台登录判断见下文。

## 登录判断

两套判断不能混用。

| 场景 | 方法 | 通过条件 |
| --- | --- | --- |
| 联盟搜索、转链、订单，以及登录脚本确认联盟 | `JdLoginState.unionReady` | 不在联盟登录墙，正文是推广工作台，并且有会话 Cookie |
| 主站商品详情、主站搜索 | `JdLoginState.mallReady` | 不在主站登录墙，正文已出来，并且有会话 Cookie |

会话 Cookie 是 `pt_key` + `pt_pin`，或者 `thor` 再加 `pin` / `unick`。

详情页不要求出现「我要推广」。详情页上单独出现「密码登录」不算登录墙。主站登录墙是 `passport.jd.com`，或者「扫码登录 / 密码登录 / 短信验证码登录 / 忘记密码」里至少出现两项。

联盟页左侧旧菜单里没有「我要推广」是正常的。这个入口在用户下拉里。可见导航是「我的推广 / 效果报表 / 结算中心 / 工具场景 / 消息公告」。

登录脚本在人工登录后会打开一次联盟工作台，并用 `unionReady` 确认。登录失效邮件由任务或保活检查触发，密钥文件不进仓库、不进本文。

## 任务

| 任务 | 作用 | 页面 | 接口 |
| --- | --- | --- | --- |
| `jdUnionSearchProducts` | 按关键字搜可转链商品，只要卡片基础信息 | `https://union.jd.com/proManager/index?pageNo=1` | `POST /custom/jd-union/searchProducts`，同步 |
| `getPromotionUrl` | 按 SKU 生成推广链接，并带回 `productInfo` | 同上推广工作台 | `POST /custom/jd-union/getPromotionUrl`，同步 |
| `getPromotionOrders` | 按日期查推客订单 | `https://union.jd.com/order` | `POST /custom/jd-union/getPromotionOrders`，同步 |
| `jdSearchProduct` | 主站关键字搜索 | `https://www.jd.com` / `search.jd.com` | `POST /custom/jd/search`，异步，`GET /custom/jd/task/{taskId}` |
| `jdProductDetail` | 主站商品详情 | `https://item.jd.com/{skuId}.html` | `POST /custom/jd/product/detail`，异步，同一个任务查询 |

联盟搜索、转链、订单的日期和关键字由调用方传入。订单入参日期格式是 `yyyy-MM-dd`。页面日期控件继续用这个格式。

## 推广位

只允许 `0`、`1`、`2`、`3` 这四个推广位，默认 `0`。请求 `4`–`9` 或其他名称会在打开浏览器前拒绝。不会新建推广位。

默认媒体类型是「导购媒体推广」，默认媒体名是「wx」，媒体 ID 是 `4101055439`。名称和推广位 ID 写在 `app.custom.jdUnion.adSites`：

| 推广位 | 推广位 ID |
| --- | --- |
| `0` | `3100287104` |
| `1` | `3100287105` |
| `2` | `3100287106` |
| `3` | `3100287107` |

转链在页面选择现有推广位，按名称匹配；页面列表匹配不到时，恢复使用已配置推广位 ID 调用 `getCode` 的原有逻辑。任务入参 `adSiteName` 只能是这四个值。

## 调用

服务地址 `http://127.0.0.1:81`。联盟三个接口同步返回结果。主站搜索和详情先返回 `taskId`，再用 `GET /custom/jd/task/{taskId}` 取结果，状态是 `PENDING`、`RUNNING`、`SUCCESS`、`FAILED`。

| 接口 | 必填 | 常用可选项 |
| --- | --- | --- |
| `POST /custom/jd-union/searchProducts` | `keyword` | `maxResults` 默认 20，上限 40；`profileName` 默认 `common` |
| `POST /custom/jd-union/getPromotionUrl` | `keyword`（SKU）、`adSiteName`（只能是 `0`、`1`、`2`、`3`） | `mediaName`；不传时用配置里的默认媒体名 `wx`。不传推广位时任务内部用 `0` |
| `POST /custom/jd-union/getPromotionOrders` | `startTime`、`endTime`，格式 `yyyy-MM-dd`，且开始不晚于结束 | 空列表也是成功 |
| `POST /custom/jd/search` | `keyword` | `maxResults` 期望最多抓多少条，默认 60，上限 100。超过一页时按第一页实际条数自动翻页 |
| `POST /custom/jd/product/detail` | `skuId` 或 `productUrl`，只传一个时另一侧自动补全 | `downloadImages`、`includeSpecs` 不传时都按 true |

未登录时浏览器保持打开，默认等待 180 秒，超时为 `LOGIN_REQUIRED`。滑块先自动拖，仍失败再留给人工，默认 120 秒。

JSONL 产出已移除，商品资料与缓存统一写入商品数据库（见 [商品主表与查询缓存](price-watch.md#商品主表与查询缓存2026-10-23)）；下载的图片在 `data/jd-mall/images/{skuId}/`。这些都相对服务工作目录 `D:\projs_r\rx-mimic`。

订单行除必填字段外，还有这些可空字段：`productLink`、`productPrice`、`mainOrderNo`、`finishTime`、`settleTime`、`estimatedBillingAmount`、`estimatedCommission`、`commissionRate`、`shareRate`、`actualBillingAmount`、`actualCommission`、`productQuantity`、`afterSaleQuantity`、`returnQuantity`、`promotionPosition`。店铺名读取页面；被动响应中的对应字段为 `skuShopName`。页面上商品链接经常为空，所以 `productLink` 不是必填。

## JD 优先 AJAX，页面操作兜底（2026-10-24 恢复）

JD 允许主动 AJAX。联盟搜索、转链、订单恢复原先浏览器页面内网关调用，沿用页面登录态与签名；失败或必填字段校验不完整时走既有页面点击、输入、日期选择与翻页流程。该策略仅适用于 JD，TB Union 仍禁止主动 AJAX。

订单 AJAX 与页面解析共用已确认字段语义：四金额独立保存、实际零值不替换、售后/退货分别读取，任一大于零则统一状态失效；平台“下单时间”归入付款时间，“完成时间”归入签收时间；结算日期一律为签收次月20日。通道诊断 `channel=ajax` 或 `page` 表示实际采用的路径。

空订单列表可以成功，有订单时必须校验字段。签名、Cookie 等敏感信息不写日志。

## 结果字段

成功只表示最终 JSON 里的必填字段有数据。接口或页面抓取本身返回了，但字段是空的，不算成功。

联盟搜索和主站搜索会丢掉字段不齐的卡片。一张完整卡片都没有时，任务失败，不返回成功。

| 结果 | 必填 | 可空 |
| --- | --- | --- |
| 联盟搜索卡片 | `skuId` 为数字；`productName`；`productLink` 为 http 且包含该 SKU；`price`、`commissionRate` 含数字；`storeName`；`imageUrl` 为 http | 无 |
| 转链 | `promotionUrl` 为 http。`productInfo` 的 `productName`、`productLink`（http，关键字是数字 SKU 时链接须包含它）、`price`、`commissionRate`、`storeName`、`imageUrl` | 不改共用 `ProductInfoDto` 的注解。京东校验在 `JdResultChecks` |
| 订单 | 列表可以为空。非空行要有 `productName`、`storeName`、`orderNo`（至少 8 位数字）、`orderStatus`、`orderTime` | `productLink` 和金额字段。不把店铺填成「京东」，也不把状态填成「未知」 |
| 主站搜索卡片 | `skuId` 为数字；`title`；`price` 含数字；`productUrl` 包含该 SKU；`shopName`；`imageUrl` 为 http | `commentCount`、`goodRate`、`soldCount` |
| 主站详情 | `skuId` 为数字；`title`；`price` 含数字；`shopName`；`productUrl` 包含该 SKU；至少一张主图是 http；`goodRate` 含百分数，是当前商品的好评度 | 详情图。`includeSpecs` 为 true 时，至少一条规格同时有名称和值 |

`includeSpecs` 为空不等于 true。链路测试显式传 true，并且 `downloadImages` 为 false。

主站搜索按 `maxResults` 自动翻页。页数 = ceil(期望条数 / 第一页实际条数)。2026 年 `search.jd.com` 打开后大约 30 个商品，页尾有「下一页」和总页数。代码不写死页大小，以这一页读到的卡片数为准。第一屏已经有一页商品时不再往下滚，避免把下拉加载的下一批算进这一页。安全上限是 8 页。翻页优先点「下一页」，点不动再用搜索链接的 `page`、`s` 打开下一页。条数已经够、算出的页数已经够、没有下一页、下一页和上一页是同一批 SKU，或没有新的 SKU 时停止。诊断字段有 `pageSize`、`pagesNeeded`、`pagesFetched`。联盟搜索不走这套翻页。

主站详情先滚完商品详情长图（`#detail-main`）。到底后再往下最多两下，然后停，不跟着页尾「看了又看」一直加载。规格在详情上方，不靠滚进推荐区来读取。

好评度是当前商品自己的，不是整页入口上的汇总。先点「超xx%买家赞不绝口」打开商品评价，再点「当前商品」。上方选中的类型（默认「全部」）这时显示的才是这个商品的好评度，例如 `100%好评`。评价列表也跟着变成当前商品。没有点「当前商品」之前的百分比不算数。

## 闭环怎么算通过

一条链路都通过，京东功能才算正常：

1. 联盟按关键字搜可转链商品。
2. 从结果里取一个 SKU 转链。
3. 查最近 7 天订单。订单列表可以为空，但状态必须成功；优先验证 AJAX，失败或字段不完整时允许页面兜底。
4. 主站按同一个关键字搜索（`jdSearchProduct`，非联盟）。卡片要有 SKU、标题、价格、商品链接、店铺、主图。
5. 从主站搜索结果里取一个 SKU 打开详情，标题、价格、店铺、主图和规格有值。

JD 联盟搜索、转链、订单允许并优先主动 AJAX，失败或字段不完整时页面兜底；主站流程保持现状。详见[订单字段与采集约束](order.md#2-订单字段对应关系2026-10-24-确认)。

在 `D:\projs_r\rx-mimic` 下执行 `start-test-chain.bat jd`。不传关键字时测试默认用「抽纸」，推广位用 `0`。调试输出在 `crawler-bootstrap/target/jd-union-chain`。不要把推广链接的追踪参数、Cookie 或签名写进日志摘录。

## 价格跟踪

联盟 `skuId` 按不透明字符串保存。加密串不是数字商品 ID。数字 ID 只从最终商品页读取，主机是 `item.jd.com` 或 `item.m.jd.com`，路径是 `/{数字}.html`。联盟中转、登录页和任意查询参数都不当作商品 ID。

每个平台一个业务日最多访问多少个商品，写在 `app.custom.priceWatch.dailyMaxProductVisits`。京东目前是 `jd: 100`。登录失效或滑动验证失败达到 `dailyMaxLoginOrSliderFailures`（默认 3）后，当天停止该平台后续商品访问。业务日从 `windowStart`（06:00）起算，过了午夜也不重置。0 或负数表示不按这条停。用户自己提交的搜索和转链仍可入队。

## 运行与验证入口

通用启动、登录与部署脚本见 [汇总脚本表](README.md#本地与部署脚本)。平台全链路命令和验收标准见上文“闭环怎么算通过”。远程调用统一使用 [v1 异步契约](README.md#调用方接入任务)，保留 taskId 后查询状态和结果。

## 远程现场验收记录（2026-10-20）

以下从原远程集成记录归入平台文档，仅代表当轮样本与验收结论；本次文档整理未重新执行。公共重启、结果持久化与发布证据见 [汇总](README.md#平台验收入口与共同重启证据)。

京东 taskId `1` 在首次进入登录/安全验证页后转为 `WAITING_USER / LOGIN_REQUIRED`。用户在AnZhi真实Chrome手工完成验证，本轮调用同一taskId的resume，重新检查登录态后成功；没有新建重复任务。状态最终 `SUCCEEDED / SUCCESS`，退出HTTP客户端后仍可取回结果。

| 项目 | task 1 实际返回 |
| --- | --- |
| SKU | `100059484008` |
| 标题 | 韩束白蛮腰防晒乳30ml防晒霜美白亮肤京东自营防紫外线SPF50+生日礼物 |
| 展示价格 / 店铺 | ¥25 / KANS韩束京东自营旗舰店 |
| 当前商品好评 | 99%好评 |
| 图片与规格 | 主图9、详情图22、规格12；本次不下载图片文件 |
| 指纹与登录 | fingerprintPassed=true，loginRequired=false |

## 交互可靠性修复远程验收（2026-10-21）

部署代码：`663b8b79ef97c91dcc4228d77ee0878a30811325`。由 [GitHub Actions](https://github.com/RockyLOMO/rx-mimic/actions/runs/39597420195) 构建并部署到 AnZhi；已通过 SSH 核对实际运行版本，浏览器、桌面、数据库、调度均 ready。

本轮修复通用点击/输入检查、最大化窗口滚轮、JS 滚动范围、响应事件等待、定时保活及人工兜底等待期限。现有自动滑动验证文件、`mouseDrag` 方法及流程入口保持不变，人工仍仅为自动处理失败后的兜底。

以下通过公网 v1 API 在远端真实 Chrome 上执行；搜索关键字为“抽纸”，转链采用联盟搜索结果，详情采用主站搜索结果。不使用固定旧商品冒充搜索结果，未使用缓存结果兜底。

| 步骤 | taskId | 执行 / 业务状态 | 结果 |
| --- | --- | --- | --- |
| 联盟搜索 | `46` | `SUCCEEDED / SUCCESS` | 3 条有效商品 |
| 转链（推广位 0） | `47` | `SUCCEEDED / SUCCESS` | 有效推广链接及完整 productInfo；不在文档输出追踪参数 |
| 近 7 天订单 | `48` | `SUCCEEDED / SUCCESS` | 4 条订单；空列表允许成功 |
| 主站搜索 | `49` | `SUCCEEDED / SUCCESS` | 3 条有效商品 |
| 主站详情 | `50` | `SUCCEEDED / SUCCESS` | 标题、价格、店铺、主图及规格必填检查通过 |

详情样本：`10052455755336`，维达抽纸巾 3层100抽软抽餐巾纸 卫生纸整箱 3层 100抽*20包 整箱装；店铺：维达居家日用旗舰店；页面展示价：¥28.71。主图 9 张，规格 13 条。详情长图返回 0 张，本轮没有验证长图完整性；未下载图片文件。

验证构建：本地 CI 同范围 112 项测试全部通过；另一个真实 Chrome 本地 HTML 集成测试通过。GitHub Linux runner 共 112 项、0 失败、0 错误，Windows 专属实例锁测试跳过 1 项（本机已通过）。新增回归已加入 CI 的无真实站点测试清单。

结束状态：代码版本不变，`draining=false`、`runningTasks=0`、`waitingUser=false`、`ready=true`。以上为本轮记录，后续运行状态需重新查询。

### 2026-10-23 AnZhi 发布后本机验证

发布 `dcff713` 完成后，从本机运行商品验收脚本验证 AnZhi 的真实 common Chrome：`PRODUCT_VERIFY_OK platform=jd productId=100018097536 price=39.8`。覆盖转链解析数字 ID、同材料缓存复用、数字 ID 再转链、自动关注及价格快照入库。商品验收未在部署 workflow 中执行。


## 订单字段与页面采集约束（2026-10-24）

完整公共字段映射见 [订单字段对应关系](order.md#2-订单字段对应关系2026-10-24-确认)。JD 允许主动 AJAX，联盟搜索、转链、订单已恢复 AJAX 优先及页面兜底；禁止主动 AJAX 的规则仅针对 TB Union。

京东订单分别读取预估计佣金额、预估佣金、实际计佣金额、实际佣金；平台下单时间作为付款时间，完成时间作为签收时间；结算时间为签收次月20日，显示 `yyyy-MM-dd`，平台原结算值仅保留扩展原值。售后数量和退货数量放 `extensions`，任一大于 0 将统一状态设为 `INVALID`，平台原状态保留。商品数量不能当作售后/退货数量。真实数字 SKU 来自商品详情链接，不能从订单号或其它数字字段推断。

App 默认只显示付款时间、签收时间、结算时间；JD 原状态 `3` 映射已完成，售后/退货任一正数仍优先显示已失效。


## 京东分享短链与 App 重复转链排查（2026-10-24）

`3.cn` 属于京东分享短链入口。原实现仅接受 `u.jd.com` 等入口，又通过主机是否以 `jd.com` 结尾判断平台，因此 `3.cn` 在身份归一化阶段被本服务以 `INVALID_PRODUCT_LINK` / `INVALID_PLATFORM_LINK`（HTTP 409）拒绝，尚未进入京东联盟 AJAX 搜索；不是京东 AJAX 返回 409。

现已将 `3.cn` 列入明确的京东入口集合，并从分享文案中只提取第一个 HTTP(S) 链接，保留 `jkl=@...@` 等必要参数。通过现有 Chrome 页面导航跟随跳转，只有最终 URL 为可信商品详情页时才能确认数字 SKU；例如 `item.m.jd.com/product/{数字}.html`。短链参数中的数字、未知页面及登录页均不得充当商品 ID；平台错配、伪装域名、userinfo 和显式端口继续拒绝。

JD AJAX 允许的规则保持不变；TB Union 仍禁止主动 AJAX。App 的一小时重复过滤、100条队列上限和恢复前台刷新是客户端行为，不代替服务端商品身份校验。

验证：`JdShortLinkBrowserIntegrationTest`（`-Dbrowser.shortlink.integration=true`）使用可见本机 Chrome、独立临时 profile 与全本地路由，覆盖 `3.cn` 页面跳转至移动商品页及生产 Navigator/身份解析；与身份、缓存、联盟边界和 JD AJAX 回归共63项全部通过。尚未部署或通过真实账号网关验证该具体商品。


## 转链短链与口令契约（2026-10-24）

JD/TB 统一返回 `shortUrl`（平台原生短链接）与 `promotionToken`（完整分享口令），二者是独立字段。App 展示和复制统一为 `shortUrl#promotionToken`，`#` 为字面连接符，无 `${}` 包装；不要把展示组合串作为网络短链请求。缺单字段时仅展示已有字段，不添加多余 `#`；历史 `promotionUrl` / `longUrl` 暂留兼容，不将长链伪装成短链。

JD 使用第一个原生短链接（如 `u.jd.com` / `3.cn`），以及京口令页的“短口令”整段（含 `!` / `！` 和尾部标记），不取长口令中的商品标题。AJAX 响应优先明确 `jShortCommand` 字段；短链或短口令未齐时复用页面弹窗，原生切换短链接与京口令标签补齐。
旧的缺少短链或口令的成功缓存不再作为完整命中返回，需刷新获取新格式；有效新结果仍按原缓存时效使用。

本轮转链输出验证：后端定向59项全部通过，含JD/TB两个可见本机Chrome素材弹窗样例，验证原生标签切换、短链接、完整短口令及隐藏/页面外干扰项过滤；未进行真实账号远程现场验收。


## 订单推广位名称归一化（2026-10-25）

JD 订单 AJAX 解析此前优先读取 `spId`，即使同时有 `spName` 也会显示数字 ID；DOM 兜底此前只解析“推广位Id”，忽略“推广位名称”。现两条路径优先保留平台返回的名称；仅有 ID 时按配置 `adSites` 精确映射为名称 `0`～`3`（`3100287104`～`3100287107`）。未知 ID 保留原值，不猜测推广位。扩展字段 `promotionPositionId` 保留实际 ID，`rawPromotionPosition` / `rawPromotionPositionName` 保留归一化前字段，DOM 原文本保留在 `rawPromotionInfo`。

已核对转链请求：`getCode` 的 `promotionId` 来自推广位列表/配置的 ID，`promotionTypeId` 来自媒体 ID，未发现把名称当作 ID 传入的代码证据；本次修正订单读取兼容，不依据订单显示差异改写网关协议。

新增回归覆盖 AJAX 同时返回名称与 ID、仅 ID 的已知/未知映射、明确平台名称优先，以及 DOM 标签解析；实际远程最新订单与网关参数仍需现场验证。

本轮定向回归 52 项全部通过，覆盖 JD/TB 推广位映射、H2 商品 ID 复用与禁止覆盖，以及真实 Chrome 本地页面夹具中的原生点击、详情跳转和返回订单页。`crawler-api` 已构建并同步到 xiaofan 本地依赖；本轮未部署 AnZhi，未进行远程真实账号验收。

## 主站搜索第一页实测：ddr3内存自营（2026-10-25）

使用本机真实 Chrome、已登录 `common` profile，运行实际 `JdMallTask.search`；`maxResults=100`、`maxSearchPages=1`，不使用搜索缓存，不打开第二页。在同一次搜索页面逐屏原生滚动，独立保存原始商品卡文本、标题属性、图片、链接及位置，与任务返回逐 SKU 比对。搜索结果会随账号、地区和时间变化，下面是本轮现场值，不要求复现用户截图的完全相同排序或价格。

首次验证页面完整加载后有 60 张卡、60 个唯一 SKU，但程序只返回前 30 条。原因是节点数达到 20 就跳过滚动；另有合法商品卡没有展示店铺名，被必填校验过滤的风险。

修正后：当前页按配置逐屏原生滚动至商品列表尾部稳定，不因节点数短暂停止增长而提前退出；保留页面未展示店铺名的商品，店铺留空并在 `searchCardsWithoutShopName` 列出 SKU。补齐 `productId=skuId`；删除主站搜索原生输入失败后的 JS setter / `dispatchEvent` 兜底。自动滑块处理不变。

| 指标 | 同次页面基准 | 修正后返回 |
| --- | ---: | ---: |
| 商品卡 / 唯一 SKU | 60 / 60 | 60 / 60 |
| 缺失 / 额外 / 重复 SKU | — | 0 / 0 / 0 |
| 标题、主图、主展示价、商品链接 | 60 | 60，逐项吻合 |
| 页面展示销量 | 50 | 50，逐 SKU 吻合 |
| 页面展示好评指标 | 37 | 37，逐 SKU 吻合 |
| 页面展示评论数 | 0 | 0，不将已售或人看过当评论数 |
| 页面未展示店铺名 | 8 | 8，保留空值且不丢商品 |

重点核对前两行及最后一行（每行 6 件）：

| 行 | SKU | 商品简名 | 主展示价（元） | 店铺名 |
| --- | --- | --- | ---: | --- |
| 1 | 100204284610 | 忆捷 DDR3 8G | 98 | 已取得 |
| 1 | 100156901441 | 长城 DDR3 8G | 99.25 | 已取得 |
| 1 | 100218231786 | 七彩虹 DDR3 4G | 59 | 已取得 |
| 1 | 100201281940 | 忆捷 DDR3 4G | 64 | 已取得 |
| 1 | 100014291279 | 玖合 DDR3 8G | 99 | 已取得 |
| 1 | 3209891 | 光威 DDR3 8G | 128 | 已取得 |
| 2 | 100169262837 | 七彩虹 DDR3 8G | 115 | 已取得 |
| 2 | 100149226928 | 七彩虹低压 DDR3 8G | 115 | 已取得 |
| 2 | 7035948 | 金百达 DDR3 8G | 149 | 已取得 |
| 2 | 100004898715 | 联想 DDR3 8G | 149 | 已取得 |
| 2 | 100150967668 | 长城 DDR3 普条 8G | 109 | 已取得 |
| 2 | 100252464544 | 联想 DDR3L 8G | 149 | 已取得 |
| 末 | 100425498120 | 联想笔记本 DDR3L 4G | 239 | 页面未展示 |
| 末 | 100278093921 | 联想笔记本 DDR3L 8G | 399 | 页面未展示 |
| 末 | 100290461683 | 联想笔记本 DDR3L 4G | 269 | 已取得 |
| 末 | 100212345218 | 朗科 DU03 8G | 416 | 已取得 |
| 末 | 100366515504 | 联想 DDR3L 4G | 269 | 页面未展示 |
| 末 | 100325252209 | 联想笔记本 DDR3L 8G | 299 | 页面未展示 |

`price` 为卡片主展示价格；例如第一件主价 98 元、另列到手价 108 元，二者没有混用。活动、优惠券、服务标签、到手价等尚未作为独立 DTO 字段返回，本次不宣称已采集这些额外信息。店铺名缺失时也不从客服链接的品牌 `seller` 参数虚构店铺名。

验证证据在 `%TEMP%/ai/20261025/jd-search-firstpage-fixed/`：`returned.json`、`final-cards.json`、`full-page.html`、`first-row.png`、`second-row.png`、`last-row.png`、`audit-rechecked.json`。初版核对脚本对页面跨节点小数（如 `99\n.\n25`）产生 3 个误报，修正后对同次保存的 DOM 和返回结果重新核验通过；没有把后续“人看过”等数字拼入价格。

复验命令：`mvn -B -nsu -pl crawler-bootstrap -am "-Dtest=JdSearchFirstPageAuditTest#compareSavedLiveEvidence,JdSearchLoadingTest,JdResultChecksTest,JdMallTaskTests" -Djd.firstPage.audit=true -Djd.audit.saved=true "-Djd.audit.output=<上述证据目录>" -Dmaven.test.skip=false -DskipTests=false -Dsurefire.failIfNoSpecifiedTests=false test`。结果 `Tests run: 19, Failures: 0, Errors: 0, Skipped: 2`、`BUILD SUCCESS`；两项未启用的是原有独立实网搜索/详情测试，真实第一页抓取已由本次专项实跑完成。需要重新实跑时选择 `JdSearchFirstPageAuditTest#compareReturnedProductsWithFullyScrolledFirstPage`，提供新的 `jd.audit.output` 目录。本轮只验证第一页，未部署 AnZhi。

## 主站搜索前两页验证（2026-10-25）

关键词仍为 `ddr3内存自营`，本机真实 Chrome、common profile 串行执行原任务。专项审计配置 `maxSearchPages=2`、`maxResultsLimit=200`、`maxResults=200`，仅为完整覆盖两页；生产默认100条上限未更改。审计在原生“下一页”点击前额外保存第一页独立DOM，完成第二页后保存第二页，审计额外耗时使用10分钟任务预算，未修改生产默认预算。

| 指标 | 结果 |
| --- | --- |
| 原始第一页 / 第二页商品数 | 59 / 60 |
| 两页各自重复 SKU | 0 / 0 |
| 平台两页 SKU 交集 | 0 |
| 返回商品 / 唯一 SKU | 119 / 119 |
| 相对两页 DOM 的缺失 / 额外 / 重复 | 0 / 0 / 0 |
| 返回顺序 | 与第一页接第二页的 DOM 顺序一致 |
| 主价、主图、身份及页面字段 | 119项逐商品核对一致 |

价格验证必须定位主价容器，不能取整张卡第一个金额：第二页9件商品前置“领国家补贴减￥1500”，其真实主价为14199等，程序返回主价正确。初版通用审计把1500当预期价，修正为基于同次原始HTML的主价容器核对后，119项全部通过，不因误报更改业务价格。

证据：`%TEMP%/ai/20261025/jd-search-two-pages/` 下 `returned.json`、`page-1-cards.json`、`page-2-cards.json`、两页HTML与各页首/次/末行PNG、`audit-rechecked.json`。最终定向回归24项全部通过、`BUILD SUCCESS`，包括保存实网页面的完整成员与顺序复核，以及淘宝成员修正与登录弹窗识别测试。未部署AnZhi。淘宝两页进展见 [淘宝主站搜索验证](tb.md#主站搜索页面核对与两页验证2026-10-25)。

## 请求100条时补齐后续页（2026-10-26）

关键词：`单P带漏电 20A 占一位 自营`。只读核对AnZhi日志：10:40:40第一页读取58件；10:40:49下一页元素定位超时，随后使用URL兜底；10:41:31第二次读取后去重累计仅60件，但因预计只需要两页而返回成功。不能以“已经访问两页”替代“已取得100个有效唯一SKU”。

本轮修正：

- 持续收集到请求数量或页面明确到末页；第二页有重复/无效卡片时继续后页，按首次出现顺序去重，达到100后截取，正常60+60时返回第一页60＋第二页40。
- 新版顶部 `_quick-result_` 分页使用稳定CSS定位原生点击右箭头，不再依赖易随React重渲染消失的临时属性。新版URL备用页码使用逻辑页码；旧版仍兼容旧分页规则。明确末页后不拼接地址继续抓。
- 商品读取、计数、列表尾部判断统一优先限定在主搜索结果容器，排除“相关商品较少”后的“猜你喜欢”、商品精选与其它页面模块。主列表尾部到达后停留等稳定，避免继续滚进推荐区。滚动保护上限60，未稳定时明确超时。
- 翻页异常、页面没变化或页数保护上限不足时明确失败，不再把部分结果当成成功。旧的100条请求但不足100条的缓存会被绕过；只有数量满足或已经确认实际末页的主站结果才可命中搜索缓存。末页证明仅存内部缓存元信息，不对外暴露。

真实Chrome本地响应夹具覆盖：60＋40补齐100、第二页大量重叠后继续第三页、真实末页不足100且不混入推荐商品、翻页失败不伪装完整成功。该夹具不访问电商网站；实网与AnZhi发布状态另行记录，不能用夹具代替线上验证。

同关键词本地实网核对：common账号此时显示1/1页，主搜索结果15件，另有30件“猜你喜欢”；修正后返回15件，主列表成员、顺序、字段均一致，无额外、遗漏或重复，确认末页后未翻页。账号/地区/时间与AnZhi的1/3页截图不同，不能把本机15件当成线上完整结果，也不能把推荐商品算进搜索结果来凑100。证据：`%TEMP%/ai/20261026/jd-search-100-final-live/`。本轮20项检查通过，包含实网核对、真实Chrome本地响应分页夹具、加载回归与缓存回归；旧版尾部稳定检测曾因滚过主列表后进入推荐区而超时，已改为到尾停留检查并重新通过实网核对。

最终另用多页关键词 `ddr3内存自营` 实网验证：11:32:51第一页60件，11:33:17第二页补齐后返回100件；100个唯一SKU、`pagesFetched=2`、`searchComplete=true`。本轮主要核对跨页计数和重复，未执行100件逐页独立截图字段审计；此前额外逐页截图审计在原生鼠标命令中等待过久，已停止该次审计，未将其计作通过。最终20项定向检查全部通过，`BUILD SUCCESS`。证据：`%TEMP%/ai/20261026/jd-search-100-ddr-final/returned.json` 与 `maven.log`。API构建及xiaofan本地依赖同步完成；本次未发布或修改AnZhi配置。

## 非返利商品误显示转链成功（2026-10-26）

只读核对 AnZhi 15:31:56 的任务 #841：队列为 `SUCCEEDED`，但结果业务状态为 `NOT_FOUND`，`productInfo` 缺失，`promotionUrl`、`shortUrl`、`promotionToken` 均为空。用户随后确认该商品不可转链。原因是 App 只检查队列执行状态，没有检查业务结果，因而显示空白的成功卡片；不是商品名字段单独丢失。

修复：仅转链动作将 `NOT_FOUND` 归为失败并保留 `NOT_FOUND` 错误码，避免未经明确判断就宣称所有查无结果的商品都无佣金。空素材的 `SUCCESS` 改为失败 `PROMOTION_RESULT_INCOMPLETE`。普通搜索/详情的空结果仍保留原有成功执行语义。App 同时校验业务状态及有效转链内容，拒绝空值、占位字符串及仅回显原始输入链接；失败不进入成功卡片或成功通知分支。新增诊断只记录任务号、状态和字段是否存在，不记录完整链接、口令或鉴权信息。

验证：两组队列测试与缓存回归共57项通过；App业务、格式及错误提示21项通过，Kotlin编译通过；API构建及xiaofan本地依赖同步通过。额外执行 `UnionMaterialBoundaryTest` 时，16项中4项失败，均为既有“入队前解析推广加密ID”的预期与当前异步提交实现不一致，本轮未改该类生产代码或测试，未将其计作通过。

本轮未重新执行该商品的实网转链、未部署AnZhi、未发布App。远程页面快照当时因调试目录容量上限暂停，不能从日志判断页面具体空结果原因；App上传日志位于独立Hub，需要其管理员会话，本轮未取到该上传文件，以上判定依据为服务端任务结果。证据在 `%TEMP%/ai/20261026/missing-promotion-name/`。

## CI 既有 5 项失败的测试对齐（2026-10-26）

java-checks 在 master 连续多次红的 5 项与上轮提交逐一比对相同，均为既有问题，本轮只改测试、不改生产代码：

- `UnionMaterialBoundaryTest` 4 项：3e1add5 起转链关键词改为入队不解析（提交路径开 Chrome 会让 POST /tasks 无法及时返回 202），旧测试仍断言入队前解析。重写为对齐异步设计的等价保证：批量关键词在队列保持原样且不触碰 resolver；不同加密别名在结果阶段绑定同一数字身份（`remember` 双别名）；平台/profile 在结果阶段转发且不跨平台复用；结果阶段解析失败时转链链接保留在 live 但绝不宣称身份、不泄漏加密素材。
- `ProductCacheApiIntegrationTest` 1 项：74f29cb 起推广缓存快照缺 `shortUrl`/`promotionToken` 时 `fresh()` 强制回源，测试种子缺 `promotionToken` 导致缓存永不命中、任务停在 PENDING。种子补全原生口令字段。

验证：本地按 CI 同款 44 类测试列表 + `package` 目标跑通，275 项 0 失败，`BUILD SUCCESS`。纯测试改动，未动 crawler-api，无需 xiaofan 同步；未重新部署 AnZhi。

## 京东单条转链 fast 模式（2026-10-26）

`getPromotionUrl` 的 `PromotionUrlRequest` 新增 `fast`，默认 `false`；省略、false或null均使用原有流程。此参数仅作用于京东单条转链，淘宝、搜索、订单、登录检查均不启用；旧批量 `getPromotionUrls` 仍接收字符串数组，协议未改，不携带此开关。调用方如需批量快速转链，可逐条提交 `getPromotionUrl` 到既有串行队列。

异步任务入参示例（保留当前请求包装）：

```json
{
  "action": "getPromotionUrl",
  "request": {
    "keyword": "100156915831",
    "adSiteName": "0",
    "fast": true,
    "useCache": false
  }
}
```

现有普通模式本身已有 AJAX 尝试，但会先进入推广菜单。快速模式在原有预检和登录判断下直接进入工作台页面，在菜单操作前尝试 AJAX；成功且商品信息、短链和完整短口令校验通过后直接返回。失败则回到原有页面推广流程，不重复尝试第二次 AJAX。快速模式页面回退使用原生 Playwright 输入，不通过 JS 改输入框值或合成事件。

快速模式复制当前请求的节奏配置，将步骤延迟上限设为160ms、随机附加延迟上限80ms（原配置更低时不抬高），并跳过 `jd-promotion` 随机噪点。页面就绪等待、超时上限、预检、登录、访问预算、商品ID核验、推广位设置与已有自动滑块验证均保留；不改共享配置、浏览器指纹及其它任务。

缓存仍与普通模式共用，`fast` 不改变商品/推广位缓存键。对比抓取耗时必须显式 `useCache=false`，否则可能只是命中缓存。内部诊断增加 `fast`、`fastAjaxBeforeWorkbench`、`fastPageFallback`、`ajaxPromotionMillis`、`promotionElapsedMillis`；总耗时从进入执行方法计起，不包含排队及此前的输入商品身份解析。

验证：90项定向检查通过，覆盖缺省/false路径、快速AJAX成功、AJAX失败页面回退、请求局部配置隔离、登录检查不受影响、任务队列持久化与透传、预加载地址、既有缓存与API解析回归。本机可见Chrome执行生产AJAX脚本、用本地响应夹具返回完整商品信息及短链口令，4次请求、0次菜单点击，结果通过；夹具不访问真实京东，不能据此声称线上提速百分比。实网提速幅度未验证，未部署AnZhi。日志在 `%TEMP%/ai/20261026/jd-promotion-fast/`。

### 2026-10-27 JD 页面价格来源修复

AnZhi 的 25/26 日转链价格中，部分 `sourceField` 为空。代码核对确认 AJAX 商品字段会写入来源，但 `readProductInfo`（转链页面）和 `readPromotionCards`（联盟搜索页面）只提取价格数字，没有同步语义。现统一解析明确的到手价/券后价为 `finalPrice`、京东价/售价为 `wlPrice`；优先提取售价语义，避免将前面的佣金数字当价格。无明确标签的展示数值标记 `jdUnionDisplayedPrice`，保持不可比较；区间和“起”价不作为确定到手价比较。

旧 JD 转链缓存如果已有价格但缺来源，将重新抓取，不继续用 14 天 TTL 掩盖字段缺失。历史来源不明快照不改写为可信价格；新的实时刷新建立有来源的观测。

回归包含页面转链及搜索两条映射、佣金与到手价同卡、原价/到手价共存、区间和未知标签。原生操作与已有滑块处理未改动。

### 2026-10-27 优惠券双短链与最后一步跳转

AnZhi任务1374的17:41日志显示：生成按钮完成、京口令页签读取后，17:41:21由身份归一流程主动导航`u.jd.com`，随后停在`plogin.m.jd.com`，最终`PRODUCT_ID_UNRESOLVED`。这不是已证实的页签误点击：旧逻辑把优惠券短链同时作为返回链接和数字身份核验入口，优惠券活动落地页要求移动端登录；移动登录域名还被漏判为OTHER。

- 优先返回优惠券短链的规则保留。短链页按“优惠券链接”“推广链接”行标题分别读取，不再仅取整弹窗第一个URL。
- 同一生成弹窗的普通推广短链仅作为临时身份核验入口，仍访问真实商品URL并核对数字ID；成功后在原有有界身份内存缓存关联优惠券短链。该辅助URL不进入持久结果或商品主表。
- 京口令页签仍使用原生鼠标点击；拒绝把同名外部链接当作页签，支持正常页签及空锚点/无操作锚点。原滑块、登录与推广位流程保持原样。
- 补充`plogin.m.jd.com/plogin.jd.com`登录分类，真实需要登录时明确返回LOGIN_REQUIRED，不伪造身份核验成功。
- 本地完整299项测试通过。真实本机可见Chrome在离线双链接弹窗夹具中完成优惠券优先、普通链接辅助、短口令完整读取、同名外部链接不点击、隐藏旧弹窗忽略及无券回退验证；`WebBrowserJdPromotionIntegrationTest,UnionMaterialBoundaryTest`构建成功。
