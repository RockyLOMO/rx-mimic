# 淘宝联盟 getTbPromotionOrders 抓取任务

最后更新：2026-05-27

## 任务定义

- 任务名：`getTbPromotionOrders`
- 目标：进入阿里妈妈订单明细页，按付款时间范围抓取推广订单列表。
- 运行前置：先过 `https://bot.sannysoft.com/` 指纹检测；需要人工登录时交给人工接管。
- 浏览器：仅使用本机 Chrome + Playwright。
- Chrome profile：默认使用 `common`，用于复用和记录人工登录态。
- Chrome 生命周期：`app.custom.chrome.closeBrowserAfterTask` 默认 `false`，任务完成后保留并复用同 profile 的 Chrome；设为 `true` 可恢复普通任务完成即关闭。
- 窗口要求：任务创建 Chrome 时即最大化窗口，保证 Sannysoft、首页、登录接管页和订单页使用一致的可视区域；若运行中再次最大化，需要同步 Playwright viewport，避免浏览器外壳已全屏但网页画布仍停留在旧尺寸。
- 操作节奏：每次关键操作前后加入随机等待，尽量模拟真实用户操作节奏。
- 页面操作：优先使用 `Browser` 封装的原生点击、输入、鼠标拖拽能力；JS 只用于定位元素、读取状态和兜底获取坐标。

## 入参

请求对象：`TbPromotionOrdersRequest`

| 字段 | 必填 | 说明 | 示例 |
| --- | --- | --- | --- |
| `startTime` | 是 | 开始日期，格式 `yyyy-MM-dd` | `2026-04-15` |
| `endTime` | 是 | 结束日期，格式 `yyyy-MM-dd` | `2026-05-08` |
| `profileName` | 否 | Chrome profile 名称，默认 `common` | `common` |
| `forcePreflight` | 否 | 是否强制每次先跑 Sannysoft | `true` |
| `keepBrowserOpenOnLoginRequired` | 否 | 未登录时是否保留浏览器给人工接管 | `true` |
| `debugEnabled` | 否 | 是否保存关键步骤 HTML 快照，未传时默认取全局配置；发布默认关闭，测试/本地验证显式传 `true` | `true` |
| `debugOutputDir` | 否 | debug 输出目录 | `D:/app-crawler/data/tb/debug` |

校验规则：

- `startTime <= endTime`
- 日期范围为空不允许执行

## 出参

返回对象：`TbPromotionOrdersResult`

| 字段 | 说明 |
| --- | --- |
| `status` | `SUCCESS`、`LOGIN_REQUIRED`、`TIMEOUT`、`FAILED` 等 |
| `taskType` | 固定为 `getTbPromotionOrders` |
| `startTime` / `endTime` | 查询时间范围 |
| `orders` | 订单数组，无订单时为空数组 |
| `loginRequired` | 是否需要人工登录 |
| `fingerprintPassed` | Sannysoft 是否通过 |
| `diagnostics` | 页码、条数、debug 目录等诊断信息 |

`orders` 每项字段：

| 字段 | 说明 |
| --- | --- |
| `productName` | 商品名称 |
| `productLink` | 商品超链接；页面 DOM 未提供时为空 |
| `productPrice` | 商品价格；页面未提供时为空 |
| `storeName` | 店铺名 |
| `orderNo` | 订单号 |
| `mainOrderNo` | 主单号 |
| `orderStatus` | 订单状态 |
| `orderTime` | 下单时间 |
| `estimatedBillingAmount` | 预估计佣金额 |
| `estimatedCommission` | 预估佣金，第 4 列两行右侧金额相加 |
| `commissionRate` | 佣金比例 |
| `actualBillingAmount` | 实际计佣金额；实际佣金不为空时取预估计佣金额 |
| `actualCommission` | 实际佣金，第 5 列两行右侧金额相加；页面为 `--` 时为空 |

## 页面流程

1. 打开 `https://pub.alimama.com/portal/v2/home/plus/index.htm`，必要时等待人工登录。
2. 如果已登录并直接进入首页，程序应自动接管后续流程。
3. 如果登录后跳转到 `https://pub.alimama.com/?forward=...` 中转页，等待页面加载稳定后点击 `快速进入` 或 `快速登录`。
4. 点击快速入口后，如果页面仍停留在中转页，不立即刷新；继续等待跳转完成，必要时再次点击。
5. 进入业务页面 `https://pub.alimama.com/portal/v2/effect/order/overviewOrder/page/index.htm`。
6. 每个关键页面和操作节点都先做滑块验证前置校验。
7. 等待订单页出现 `付款时间`、`搜索订单编号`、`订单状态`、`佣金比例` 等关键字后，再开始操作筛选条件。

## 日期选择流程

1. 点击 `付款时间` 右侧的日期组件。
2. 页面可能弹出 `选择日期：` 或 `选择时间：` 面板；两种标题都按同一日期面板处理。
3. 面板第一层包含：
   - 开始日期输入框。
   - 结束日期输入框。
   - 快捷日期选项，例如 `今日`、`昨日`、`过去7天`、`过去30天`。
   - `确定`、`取消` 按钮。
4. 点击开始日期输入区域，打开 MUX 日期范围选择器。
5. 按入参选择日期：
   - 左侧月份对齐 `startTime` 月份。
   - 点击左侧对应日期作为开始日期。
   - 如果 `endTime` 与左侧同月，直接在左侧选择结束日期。
   - 如果不在同月，切换右侧月份到 `endTime` 月份，再在右侧选择结束日期。
6. 点击日期面板中的 `确定`。
7. 校验 `付款时间` 标签已显示 `startTime 至 endTime`，例如 `2026-04-13 至 2026-05-13`。
8. 如果日期面板打开但月份面板未出现，应再次点击面板里的日期输入框，而不是判定页面变化。

## 搜索流程

1. 优先点击 `搜索订单编号` 输入框右侧的查找按钮。
2. 如果日期范围选择后列表已自动刷新，且页面没有可见查找按钮，可跳过搜索按钮点击。
3. 搜索后等待列表稳定，再抓取订单。
4. 如果没有订单，返回空数组。

历史需求原始描述：

```text
1. 打开https://pub.alimama.com/portal/v2/effect/order/overviewOrder/page/index.htm
2. 页面 付款时间下拉 右侧是 时间范围 选取组件（非原生的）。
3. 选择 startTime 和 endTime 后点击查找订单按钮。
4. 如果没有订单返回空数组；如果有订单按出参字段抓取成数组。
```

## 前置校验与接管

1. 每次任务执行前先跑 Sannysoft 指纹检测。
2. 如果进入淘宝登录页，保留当前 Chrome profile 给人工登录。
3. 人工登录完成后，程序继续检测当前 URL 和页面内容：
   - `login_jump` 跳转页：按登录接管状态处理，继续等待真实登录页或登录完成后的业务页，不提前进入订单抓取流程。
   - 登录中转页：继续点击 `快速进入`。
   - 首页：程序接管，导航到订单明细页。
   - 订单页：直接继续日期选择与抓取。
4. 如果出现滑块验证页，执行滑块处理逻辑；多次失败后等待人工接管。

旧版简略页面流程：

1. 打开 `https://pub.alimama.com/portal/v2/home/plus/index.htm`，必要时等待人工登录。
2. 如果登录后跳转到 `https://pub.alimama.com/?forward=...` 中转页，先点击 `快速进入`。
3. 进入业务页面 `https://pub.alimama.com/portal/v2/effect/order/overviewOrder/page/index.htm`。
4. 点击 `付款时间` 右侧的 `时间范围` 非原生组件。
5. 弹出 `选择时间：` 面板后，点击第二行任意日期输入框打开日期选择器。
6. 按入参选择日期：
   - 左侧月份对齐 `startTime` 月份。
   - 点击左侧对应日期作为开始日期。
   - 如果 `endTime` 与左侧同月，直接在左侧选择结束日期。
   - 如果不在同月，切换右侧月份到 `endTime` 月份，再在右侧选择结束日期。
7. 点击 `搜索订单编号` 输入框右侧的 `查找订单`。
8. 如果没有订单，返回空数组。

## 表格抓取逻辑

1. 订单列表优先直接读取 DOM 行结构。
2. DOM 不稳定时，保存并解析列表 `outerHTML`。
3. 每行按页面从左到右解析：
   - 第 1 列：商品名称、商品链接、店铺名、主单号、订单号、预估计佣金额、下单时间。
   - 第 2 列：订单状态。
   - 第 3 列：佣金比例。
   - 第 4 列：两行右侧金额相加为预估佣金。
   - 第 5 列：两行右侧金额相加为实际佣金；如果为 `--`，实际佣金为空；实际佣金不为空时，实际计佣金额取预估计佣金额。
4. 表头行必须过滤，不允许把 `订单信息`、`订单状态` 等表头当作订单数据。
5. 有效订单行必须至少包含 `orderNo` 或 `mainOrderNo`。
6. 行数据按订单号、主单号、下单时间、佣金等组合去重后汇总。

## 翻页逻辑

1. 每页抓完后滚动到底部。
2. 因页面非全屏时，右侧分页和 `下一页` 可能被表格横向滚动区域遮挡；检测或点击 `下一页` 前，需要把页面或表格横向滚动到最右。
3. 检查 `下一页` 是否可点击。
4. 如果可点击，点击后继续抓下一页。
5. 翻页后再次检查滑块验证。
6. 等待新一页列表稳定后继续抓取。
7. 直到 `下一页` 不可点击为止。

## 通用随机操作节奏

1. 任务级 `stepDelayMillis` 保留固定基础等待。
2. 新增 `stepDelayRandomMillis`，实际等待为基础等待加随机偏移。
3. 浏览器级点击、输入、导航等操作前加入 `operationRandomMinDelayMillis` 到 `operationRandomMaxDelayMillis` 的随机停顿。
4. 鼠标点击、拖拽和输入继续使用 Playwright 原生事件，避免只用 JS 合成事件。

## Debug 快照

本地验证和排查页面变化时，测试类/请求里必须显式开启 `debugEnabled=true`，发布配置默认关闭。

关键快照约定：

| 快照名 | 说明 |
| --- | --- |
| `01-entry` | Sannysoft 和入口处理后页面 |
| `02-forward-landing-entered` | 登录后中转页或快速入口页 |
| `02-order-page-loaded` | 订单页已打开 |
| `02-order-page-ready` | 订单页关键元素出现 |
| `03-date-range-after-open-click` | 点击付款时间后的日期弹层 |
| `03-date-range-before-inner-input-click` | 第一层日期面板已出现 |
| `03-date-range-after-inner-input-click` | MUX 月份面板已出现 |
| `03-date-range-start-day-selected` | 开始日期已选择 |
| `03-date-range-selected` | 日期范围已应用到页面 |
| `04-search-clicked` | 搜索动作后 |
| `05-page-NNN-collected` | 当前页已抓取 |
| `*-before-slide-*` / `*-after-slide-*` | 滑块验证前后 |
| `*-before-reload-*` / `*-after-reload-*` | 滑块失败后刷新页面换干净 NC 挑战前后 |

默认本地调试目录：

```text
target/tb-promotion-debug/{profileName}/{startTime}-{endTime}-{timestamp}
```

## 本地验证记录

- 本地验证建议开启 `debugEnabled=true`。
- 示例入参：近一个月的 `startTime` 和 `endTime`。
- 期望结果：有多页订单数据返回。

2026-05-13 实测：

| 项 | 结果 |
| --- | --- |
| 入参 | `startTime=2026-04-13`，`endTime=2026-05-13` |
| Chrome profile | `common` |
| Sannysoft | 通过 |
| 日期选择 | 页面成功显示 `2026-04-13 至 2026-05-13` |
| 抓取状态 | `SUCCESS` |
| 当前页订单数 | 5 |
| 表头过滤 | 已过滤 `订单信息` 表头行 |
| Debug 目录 | `crawler-bootstrap/target/tb-promotion-debug/common/2026-04-13-2026-05-13-20260513_165326_970` |

2026-05-15 实测：

| 项 | 结果 |
| --- | --- |
| 入参 | `startTime=2026-04-15`，`endTime=2026-05-15` |
| Chrome profile | `common` |
| Sannysoft | 通过 |
| 抓取状态 | `SUCCESS` |
| 当前页订单数 | 5 |
| 风控处理 | 修正隐藏残留 `nc_*` / `baxia` / `nocaptcha` DOM 被误判为滑块的问题；滑块清除后流程可继续执行 |
| Debug 目录 | `crawler-bootstrap/target/tb-promotion-debug/common/2026-04-15-2026-05-15-20260515_140836_650` |

问题定位与修复：

- 现象：滑块验证处理完成后，页面已经回到订单明细报表，但任务没有立即继续，而是在 `waitSliderVerifyCleared` 中等待到超时。
- 原因：页面会残留隐藏的 `nc_*`、`baxia`、`nocaptcha` 等风控 DOM。旧逻辑只要发现这些特征节点就判定为滑块页，没有判断节点是否可见。
- 修复：`SliderVerifyHandler.isSliderVerifyPage` 的 DOM 探测只认可见风控节点；隐藏残留节点不再算滑块。可见 iframe 才进入同源内容检查。
- 验证：同一入参重新执行订单集成验证后返回 `SUCCESS`，抓取订单 5 条。

2026-05-26 / 2026-05-27 实测（滑块与指纹修复后）：

| 项 | 结果 |
| --- | --- |
| 入参 | `startTime=2026-04-26` ~ `2026-05-26`；`2026-04-27` ~ `2026-05-27` |
| Chrome profile | `common` |
| Sannysoft | 通过，`webdriver=null` |
| 抓取状态 | `SUCCESS` |
| 订单数 | 各 5 条 |
| 滑块 | `02-order-slider` attempt=1 通过（含 iframe 遮罩形态 `inIframe=true`） |
| 关键修复 | 见下文「2026-05-26 滑块与反自动化修复」 |

本地集成验证命令：

```powershell
mvn -pl crawler-bootstrap -am "-Dmaven.test.skip=false" "-DskipTests=false" "-Dtest=TbPromotionOrdersTaskTests#tbPromotionOrdersIntegrationShouldSaveReadableDebugSnapshot" "-Dtb.promotion.orders.integration=true" "-Dtb.promotion.orders.startTime=2026-04-27" "-Dtb.promotion.orders.endTime=2026-05-27" test
```

## 调用入口

HTTP 入口：

```text
POST /custom/tb/getPromotionOrders
```

Remoting RPC 统一使用：

```java
org.rx.crawler.task.common.CustomCrawlRemotingContract#getTbPromotionOrders
```

## Cookie 与 Profile

- 调试入口 `GET /cookies/raw` 支持可选 `profileName` 参数；不传时默认读取通用 profile。
- remoting 接口统一使用 `CustomCrawlRemotingContract#cookiesRaw(String profileName, String url)`。
- `profileName` 非空时，cookie 会按指定 Chrome profile 读取；`profileName` 为空时，默认走通用 profile。
- 当前 cookie 保存策略统一收口到 `BrowserProfileManager.ProfileLease.close()`：淘宝订单任务执行结束、释放 profile lease 时，自动把当前 Playwright 页面上下文 cookie 保存到 `HttpClientCookieJar`。
- 任务代码里不再单独手工保存 cookie；读取 `cookiesRaw(profileName, url)` 时也会先同步当前浏览器上下文 cookie，再返回 raw cookie。

## 滑块验证处理

公共实现：`org.rx.crawler.task.tb.SliderVerifyHandler`（`getTbPromotionOrders` / `getTbPromotionUrl` 共用）。

淘宝联盟页面在高频访问或账号风控时，会弹出"亲，请拖动下方滑块完成验证"的验证页（阿里妈妈防爬机制）。形态包括：

- **页面级**：URL 含 `_____tmd_____/punish`、`x5secdata` 等，整页跳转验证码拦截页。
- **遮罩级**：业务页未跳转，同源 iframe / 浮层内挂载 NC（`#baxia-punish`、`#nocaptcha`），`simulateSlideDrag` 会计算 iframe 偏移后的主文档绝对坐标（`inIframe=true`）。

### 浏览器反自动化（前置条件）

`WebBrowser` 启动持久化 Chrome 时通过 `setIgnoreDefaultArgs(["--enable-automation"])` 移除 Playwright 默认自动化标志，避免：

- 顶部横幅「Chrome 正受到自动测试软件的控制」；
- `navigator.webdriver === true` 被阿里 NC / `fireyejs` 直接判失败。

配合 `application.yml` 中任务级 `fingerprintEnabled=true` 注入的 `stealth.min.js` + `chrome-fingerprint.js`，Sannysoft 应显示 `WebDriver: missing (passed)`。

### 触发节点

以下三个节点后会自动检测并处理滑块验证：

1. 导航进入订单页后（`02-order-slider`）
2. 点击"查找订单"搜索后（`04-search-slider`）
3. 每次翻页后（`05-page-NNN-slider`）

### 检测逻辑

- URL 包含 `/punish`、`x5sec`、`_____tmd_____` 或 `captcha` 时，直接判定为验证页。
- 读取页面 `body.innerText`，若包含以下任意文字则判定为验证页：
  - `请拖动下方滑块完成验证`
  - `拖动滑块`
  - `拖到最右边`
  - `按住滑块`
  - `请按住滑块`
  - `滑块验证`
  - `安全验证`
  - `访问验证`
  - `行为验证`
  - `请完成验证`
  - `验证失败`
- 若页面没有跳转而是直接弹出浮层滑块，则继续检查可见的 `iframe/div/span/input/button` 节点；元素 `id/class/name/src/title/aria-label/data-spm` 中包含 `nc_`、`awsc`、`captcha`、`punish`、`baxia`、`滑块`、`验证码`、`安全验证` 或 `x5sec` 时，判定为滑块验证。
- 滑块处理完成后可能残留隐藏的风控 DOM。隐藏节点不再判定为滑块，避免人工处理后 `waitSliderVerifyCleared` 阶段误判未清除并等待到超时。

### 模拟滑动策略

不再使用容易被前端反爬风控指纹识别的 JS `dispatchEvent` 模拟合成事件，全面升级为通过 **Playwright 原生 `Mouse` API** 直接向浏览器发送真实鼠标物理事件。

1. **WebBrowser.mouseDrag — 增强拟人拖拽轨迹**：
   - **随机步数**：实际步数在指定步数基础上加 `steps ± 5~8` 的随机偏移，避免因步数固定特征被识别。
   - **三段变速缓动**：起步慢加速（二次缓入）→ 中段匀速推进 → 末段慢减速（二次缓出），完美契合人类手指发力习惯。
   - **随机中途停顿**：拖拽至 1/4~1/2 以及 2/3~4/5 进度时，随机选取 1~2 个停顿点暂停 80~280ms，模拟人类滑动时的停顿和犹豫感。
   - **微回退抖动**：中段滑动中有 5% 的概率向后微回退 1~4px 并产生微小抖动，极大地丰富了滑动路径的动态特征。
   - **过冲回调机制**：模拟真人用力过猛的惯性：到达终点时先轻轻超过终点 3~8px 停顿，随后分 2~3 步缓慢拉回到精确的终点位置后松开。
   - **衰减抖动**：X/Y 方向引入动态抖动幅度，抖动随拖动进度逐渐衰减（例如起步时波动较大，快完成时手指微调稳定）。

2. **`SliderVerifyHandler.checkAndHandle` — 自动重试与失败后刷新**：
   - **最大重试**：订单任务与推广链接任务均 `checkAndHandle(..., maxAttempts=5, ...)`。
   - **Handle 定位**：仅认 NC 标准选择器（`#nc_1_n1z`、`.btn_slide`、`[id$=_n1z][role=button]`），handle 宽度须在 25~70px；未就绪则跳过拖拽，避免误拖页面其他小元素。
   - **拖拽步数**：首次 attempt 较慢（约 30~38 步）；第 2 次起加快（约 16~26 步），模拟失败后「再用力拖一次」。
   - **失败后刷新再试（关键）**：跳转验证页（URL 含 `/punish` / `x5sec` / `_____tmd_____`）或浮层 NC 拖拽失败后，当前 challenge 常被会话级拉黑。流程为：失败 →（可选轻点 `.errloading`）→ **`location.reload()` 模拟 F5**（失败再 `nativeGet(currentUrl)`）→ 等 handle 就绪 → 下一轮再拖。跳转验证页 post-reload 等待更长。快照：`*-before-reload-*` / `*-after-reload-*`。
   - **`.errloading` 点击（轻量前置）**：`clickRetryContainerIfPresent` 仍保留；主恢复路径是刷新，不依赖误点 `.nc-container` 顶部。
   - **快照**：`before-slide` / `after-slide` / `before-reload` / `after-reload`。

### 2026-05-26 滑块与反自动化修复（摘要）

| 问题 | 原因 | 修复 |
| --- | --- | --- |
| 滑块始终 `error:0Pfpus` | `--enable-automation` → `navigator.webdriver=true` | `WebBrowser` 忽略默认 `--enable-automation` |
| 失败后 2~5 次 `handle not ready` | 点错容器 Y≈24，NC 未重置 | 失败后改 `refreshForFreshChallenge` |
| 误识别 handle `width=15` | 命中非 NC 元素 | 严格 NC 选择器 + 宽度校验 |
| 遮罩滑块拖不动 | 坐标在 iframe 内未换算 | `simulateSlideDrag` 累加 iframe `getBoundingClientRect` 偏移 |

### 诊断字段

| 字段 | 说明 |
| --- | --- |
| `sliderVerifyAt` | 最近一次触发验证的步骤标签 |
| `sliderVerifyPassed` | 自动/重试验证成功时写入 `true` |
| `sliderVerifyFailed` | 达到 `maxAttempts` 仍失败时写入触发步骤名 |
