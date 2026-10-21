# 京东抓取当前说明

这份文档是京东联盟和京东主站的当前行为。`docs/plan/` 里原来的京东方案已经收进本文后删除。`README.md` 里较早的京东描述若和本文不一致，以本文为准。

服务端口 `81`。浏览器是本机 Chrome，Playwright 驱动。请求走已登录页面里的接口；页面点击只在接口没有给出完整结果时使用。

## 浏览器

登录、测试和服务共用同一个 Chrome 用户目录：

`C:\Users\R-wtg\AppData\Local\rx-mimic\chrome\common`

这是 `%LOCALAPPDATA%\rx-mimic\chrome\common`。Cookie 库是该目录下的 `Default\Network\Cookies`，加密密钥是同级的 `Local State`。这两份要成对保留，不能只拷 Cookie 库。项目里的 `data\chrome` 不再作为登录态目录。

默认 profile 名是 `common`。`maxActive` 保持 `1`。这个目录已经有 Chrome 打开时，不要再开第二个。登录脚本、链路测试和服务不能同时占用它。停服务前若要落盘登录态，先 `POST /custom/profile/close`。

业务页打开前会先做 bot 检测（`bot.sannysoft.com`，严格模式）。链路测试里 `forcePreflight` 为 true。

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

转链先按名称在页面列表里匹配。名称对不上时，用上表里的推广位 ID 调 `getCode`。接口入参 `adSiteName` 只能是这四个值。

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

联盟结果追加到 `data/jd-union/output.jsonl`。主站结果追加到 `data/jd-mall/output.jsonl`，下载的图片在 `data/jd-mall/images/{skuId}/`。这些都相对服务工作目录 `D:\projs_r\rx-mimic`。

订单行除必填字段外，还有这些可空字段：`productLink`、`productPrice`、`mainOrderNo`、`finishTime`、`settleTime`、`estimatedBillingAmount`、`estimatedCommission`、`commissionRate`、`shareRate`、`actualBillingAmount`、`actualCommission`、`productQuantity`、`afterSaleQuantity`、`returnQuantity`、`promotionPosition`。店铺名来自接口字段 `skuShopName`。页面上商品链接经常为空，所以 `productLink` 不是必填。

## 先接口，再点击

搜索、转链、订单都先在已登录页面里请求 `https://api.m.jd.com/api`（`withCredentials`，页面自己的签名）。签名参数、Cookie 不写入日志，也不写进本文。

接口没有给出可校验的完整结果时，再走原来的页面点击。搜索或转链接口返回了结构，但必填字段缺失，同样落入页面兜底，不能直接当成功。

订单接口 `listOrderSku` 的时间参数：

- 入参只有日期时，开始时间补成 `yyyy-MM-dd 00:00:00`，结束时间补成 `yyyy-MM-dd 23:59:59`。
- 入参已经带时间时，原样传递，不重复追加。
- 这是订单页默认区间：7 天前零点到今天 `23:59:59`。页面日期选择器仍然只接收 `yyyy-MM-dd`。

空订单列表是成功。有订单时，每一行的必填字段都必须有值。

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
3. 查最近 7 天订单。订单列表可以为空，但状态必须成功，并且走接口通道。
4. 主站按同一个关键字搜索（`jdSearchProduct`，非联盟）。卡片要有 SKU、标题、价格、商品链接、店铺、主图。
5. 从主站搜索结果里取一个 SKU 打开详情，标题、价格、店铺、主图和规格有值。

搜索、转链、订单的诊断字段 `channel` 应为 `ajax`。页面通道只说明接口没有给出完整结果。

在 `D:\projs_r\rx-mimic` 下执行 `start-test-chain.bat jd`。不传关键字时测试默认用「抽纸」，推广位用 `0`。调试输出在 `crawler-bootstrap/target/jd-union-chain`。不要把推广链接的追踪参数、Cookie 或签名写进日志摘录。

## 脚本

这些脚本都在仓库根目录，除非另写路径。跑登录或全链路前先停掉占用 `common` 这个 Chrome 目录的服务。

| 脚本 | 作用 |
| --- | --- |
| `start-login.bat [all\|jd\|tb] [profile]` | 打开可见 Chrome 做人工登录。默认 `all`，第二个参数是 profile，默认 `common`。同一时间只能开一个，第二次直接退出并打印 `start-login.bat is already running.`。实际逻辑在 `start-login.ps1`。 |
| `start-test-chain.bat jd [keyword]` | 京东全链路：联盟搜索、转链、近 7 天订单、主站搜索、主站详情。窗口可见。推广位固定 `0`。 |
| `start-test-chain.bat tb [keyword]` | 淘宝全链路：联盟搜索、转链、近 30 天订单、主站搜索、主站详情。窗口可见。推广位固定 `0`。 |
| `start-crawler-bootstrap.bat` | 本机前台启动爬虫服务。缺 jar 时先打包。 |
| `start-crawler-bootstrap.bat jd-union-orders-verify` | 同样启动服务，并打开京东联盟订单调试目录。这不是全链路。 |
| `start-crawler-detached.bat` | 脱离当前终端启动已打包的服务，日志在 `data\logs\crawler-service.log`。 |
| `deploy/app-crawler/start.bat` | 在部署机上启动已安装的 rx-mimic 服务。 |
| `deploy/app-crawler/rollback.bat` | 在部署机上切回上一份发布包。 |
