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
