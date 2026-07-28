# 淘宝联盟 getTbPromotionUrl 抓取任务

最后更新：2026-05-27

## 任务范围

- 任务名：`getTbPromotionUrl`
- 浏览器：本机 Chrome + Playwright，复用持久化 profile；默认任务结束后保留同 profile 的 Chrome 会话供下一次任务直接复用。
- 窗口要求：浏览器默认最大化启动，保证 Sannysoft、首页、登录接管页和业务页使用一致的可视区域。
- 前置流程：复用公共 Sannysoft 检测、人工登录接管和等待恢复流程。
- 风控滑块：任务关键步骤会优先识别滑动验证并保存 debug 快照；识别逻辑覆盖跳转验证页、页面文案和未跳转的可见浮层滑块，检测后优先调用公共 `SliderVerifyHandler` 自动处理（失败后刷新页面换干净 NC 挑战，见 [TB_getPromotionOrders_Task.md](./TB_getPromotionOrders_Task.md) 滑块章节），仍失败则等待人工接管。
- **登录态**：使用 `common` profile 的池实例目录（`common_pool_0`）持久化 Cookie；已有会话时**禁止**用空/旧的 `common` 源目录覆盖池实例。阿里妈妈「确认登录 / 快速进入」是**会话续期**（Cookie 仍有效），不算硬登录，自动点快速进入，不进人工等登录。硬登录仅密码/扫码页。
- **耗时**：`forcePreflight=false` 时同进程 30 分钟内复用 Sannysoft；步骤死等 `stepDelay` 适度下调，鼠标拟人轨迹/滑块拖拽不降。
- 浏览器反自动化：`WebBrowser` 启动时 `setIgnoreDefaultArgs(["--enable-automation"])`，配合任务级指纹脚本，避免 NC 因 `navigator.webdriver` 判失败。
- 页面操作：优先使用 `Browser` 封装的原生点击、输入、鼠标移动能力；JS 只用于定位、读取页面状态或兜底。

## 目标网站

- 初始页：`https://pub.alimama.com/portal/v2/home/plus/index.htm`
- 登录页前缀：`https://login.taobao.com/havanaone/login/login.htm`
- 业务页面：`https://pub.alimama.com/portal/v2/pages/promo/goods/index.htm`

## 入参

请求对象：`PromotionUrlRequest`

| 字段 | 必填 | 说明 |
| --- | --- | --- |
| `keyword` | 是 | 商品 id、商品名或商品链接 |
| `adSiteName` | 是 | 推广位数字，例如 `5` |
| `mediaName` | 否 | 媒体名称；不传时使用页面当前默认值 |
| `profileName` | 否 | Chrome profile 名称，默认 `common` |
| `forcePreflight` | 否 | 是否强制每次先跑 Sannysoft |
| `debugEnabled` | 否 | 是否保存关键 HTML 快照；发布默认关闭，测试/本地验证显式传 `true` |
| `debugOutputDir` | 否 | 调试快照目录 |

## 出参

返回对象：`PromotionUrlResult`

| 字段 | 说明 |
| --- | --- |
| `taskType` | 固定为 `getTbPromotionUrl` |
| `productInfo` | 复用 `ProductInfoDto`，包含商品图、名称、链接、佣金率、价格、店铺名；`price` 优先匹配「到手价」，回退「单价 / 2件单价 / ￥」 |
| `promotionUrl` | 推广链接，优先从输入框/文本区读取，点击一键复制后再兜底读取页面内容 |
| `fingerprintPassed` | Sannysoft 是否通过 |
| `loginRequired` | 是否需要人工登录 |
| `diagnostics` | 调试诊断信息 |

## 批量入口

- Remoting：`getTbPromotionUrls(List<String> keywords)`
- HTTP：`POST /custom/tb/getPromotionUrls`
- 请求体：字符串数组，例如 `["西麦纯燕麦片3kg", "咖啡豆"]`

批量模式复用单条 `getTbPromotionUrl` 的抓取逻辑；Sannysoft、登录接管、进入淘宝联盟选品页只执行一次，从输入 `keyword` 开始循环抓取每个商品，返回 `List<PromotionUrlResult>`。批量模式默认使用配置项 `app.custom.tbPromotion.defaultAdSiteName` 作为推广位。

## Cookie 与 Profile

- 调试入口 `GET /cookies/raw` 支持可选 `profileName` 参数；不传时默认读取通用 profile。
- remoting 接口统一使用 `CustomCrawlRemotingContract#cookiesRaw(String profileName, String url)`。
- `profileName` 非空时，cookie 会按指定 Chrome profile 读取；`profileName` 为空时，默认走通用 profile。
- 任务执行结束后，不再在各任务里分散调用 `browser.saveCookies(false)`；统一由 `BrowserProfileManager.ProfileLease.close()` 在释放 profile lease 时保存当前浏览器上下文 cookie 到 `HttpClientCookieJar`。
- `cookiesRaw(profileName, url)` 在读取前会把当前浏览器上下文 cookie 同步进 `HttpClientCookieJar`，因此可用于读取最新登录态。
- `app.custom.chrome.closeBrowserAfterTask` 默认 `false`，任务完成不关闭 Chrome；设置为 `true` 时普通任务完成即关闭，登录人工接管的临时保留配置仍有效。

## 页面流程

1. 进入阿里妈妈初始页，执行 Sannysoft 与登录接管；`login_jump` 跳转页按登录接管状态处理，不提前进入推广链接流程。
2. 打开选品推广业务页。
3. 在 placeholder 为“请输入你要搜索的商品/类目/商品链接”的输入框输入 `keyword`。
4. 点击“智能搜索”。
5. 若搜索点击未进入搜索态，使用带 `fn=search&q=` 的业务 URL 兜底进入搜索结果页。
6. 下拉页面，若没有商品则返回 `NOT_FOUND`。
7. 取第一条商品卡片（选品中心 UI 改版后卡片文案可能无「到手价」，识别规则见下节），先用原生鼠标移动 hover，再抓取商品信息：
   - `imageUrl`
   - `productName` + `productLink`：优先取商品标题外层的 `a[href][data-spm-click*=d_good_select_list_title_click]`，也就是标题区域的详情页超链接。
   - `price`
   - `commissionRate`
   - `storeName`
8. 点击第一条商品的“立即推广”。
9. 在素材弹窗中点击“推广位”选择框。
10. 在选择推广位弹窗中选择“他方平台”到“社交平台”。
11. 选择媒体名称：显式传 `mediaName` 则必选；未传时 **best-effort 尝试与 `adSiteName` 同名的媒体**（账号上常见「媒体 5 / 推广位 5会员」，页面默认常是第一个媒体「1」，不切换则推广位下拉里只有「1会员」）。
12. 打开“推广位名称”（可搜索下拉可先输入编号过滤），选择首个编号等于 `adSiteName` 的选项（匹配 `5` / `5会员` 等）。
13. 点击“确认”。
14. 回到素材弹窗，点击“一键复制”，读取 `promotionUrl`。

## 商品卡片识别（选品中心 UI）

2026-05 起阿里妈妈选品列表常见文案为「佣金率 / 单件佣金 / 2件单价￥xx / 立即推广」，**不再固定显示「到手价」**。

`TbPromotionUrlTask` 中以下逻辑已统一放宽（`scrollToFirstProductCard`、`markFirstProductCard.scoreCard`、`readFirstProductTitle`、`markFirstPromoteButton.goodCard`）：

- **必须同时满足**：含 `佣金率` / `单件佣金` / `月支出佣金` 之一；且含 `立即推广` / `￥` / `单价` 之一；含商品图。
- **尺寸过滤**：卡片宽约 220~760px、高约 180~560px，避免命中侧栏筛选或引导弹层。
- **不再要求**：正文必须包含「到手价」。

## 本地验证

默认建议开启：

```json
{
  "keyword": "西麦纯燕麦片3kg高蛋白质0添加蔗糖即食谷物速食懒人代餐冲饮早餐",
  "adSiteName": "5",
  "debugEnabled": true
}
```

本地集成验证命令：

```powershell
mvn -pl crawler-bootstrap -am "-Dmaven.test.skip=false" "-DskipTests=false" "-Dtest=TbPromotionUrlTaskTests#tbPromotionUrlIntegrationShouldSaveReadableDebugSnapshot" "-Dtb.promotion.url.integration=true" test
```

2026-05-15 本地验证记录：

- Sannysoft：`passed`
- 入参：`keyword=西麦纯燕麦片3kg高蛋白质0添加蔗糖即食谷物速食懒人代餐冲饮早餐`，`adSiteName=5`，`profileName=common`
- 结果：`status=PAGE_CHANGED`，`message=TB promotion slider verify not cleared while entering goods page`
- 明细：进入选品页后在 `02-goods-ready-slider` 检测到滑块浮层，自动重试 3 次均未找到可拖拽把手，已保存 debug 快照到 `target/tb-promotion-url-debug`

2026-05-27 本地验证记录（滑块 + 商品卡识别修复后）：

| 项 | 结果 |
| --- | --- |
| 入参 | `keyword=西麦纯燕麦片3kg高蛋白质0添加蔗糖即食谷物速食懒人代餐冲饮早餐`，`adSiteName=5` |
| Sannysoft | 通过，`webdriver=null` |
| 状态 | `SUCCESS` |
| `promotionUrl` | `https://m.tb.cn/h.R4nODXt` |
| 商品 | 西麦纯燕麦片3kg…；店铺 seamild西麦旗舰店；佣金率 1.80%；价格 ￥40.40 |
| 滑块 | 本次未触发；历史 iframe 形态已由公共 `SliderVerifyHandler` 支持 |
| 商品卡 | 放宽「到手价」硬要求后，`05-product-card-missing` 问题已消除 |
| Debug | `target/tb-promotion-url-debug/common/西麦纯燕麦片3kg…-20260527_111852_894` |

集成验证命令（与上节相同）：

```powershell
mvn -pl crawler-bootstrap -am "-Dmaven.test.skip=false" "-DskipTests=false" "-Dtest=TbPromotionUrlTaskTests#tbPromotionUrlIntegrationShouldSaveReadableDebugSnapshot" "-Dtb.promotion.url.integration=true" test
```

订单任务集成验证（对照）：

```powershell
mvn -pl crawler-bootstrap -am "-Dmaven.test.skip=false" "-DskipTests=false" "-Dtest=TbPromotionOrdersTaskTests#tbPromotionOrdersIntegrationShouldSaveReadableDebugSnapshot" "-Dtb.promotion.orders.integration=true" "-Dtb.promotion.orders.startTime=2026-04-27" "-Dtb.promotion.orders.endTime=2026-05-27" test
```
