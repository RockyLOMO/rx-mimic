# rx-mimic 京东与淘宝爬虫任务与集成指南

远程服务部署与异步 v1 接入见 [接入任务与执行记录](remote-service-async-rpc-integration-2026-10-20.md)，设计依据见 [实施计划](plan/remote-service-async-rpc-plan-2026-10-20.md)。远程部署默认关闭本文历史 `/custom` 接口，调用方须按 v1 文档接入。

京东当前行为以 [jd.md](jd.md) 为准，淘宝当前行为以 [tb.md](tb.md) 为准；下文和它们不一致时，以各自专门文档为准。

本项目基于 Java 21 + Playwright（直接驱动本机 Chrome）+ Spring Boot 3 构建，提供面向京东与淘宝/阿里妈妈两大生态的自动化爬虫与数据采集能力。

---

## 一、京东三大核心任务概述

项目针对京东生态实现了三大核心任务，支持异步 HTTP API、结果队列轮询、本地持久化与独立的单元/集成测试：

| 任务名称 | 任务类型 | 作用与规范 | 核心页面 | 对应 API |
| --- | --- | --- | --- | --- |
| **商品查看与详情** | `jdProductDetail` | 参考 `/jditem` Skill 规范，解析京东商品详情（标题、店铺、自营判定、主图、详情图、规格参数） | `https://item.jd.com/{skuId}.html` | `POST /custom/jd/product/detail`<br>`GET /custom/jd/task/{taskId}` |
| **可转链商品搜索** | `jdUnionSearchProducts` | 在京东联盟推广管理页按关键字搜索，返回可转链商品基础信息（SKU、标题、到手价、佣金比例、店铺）。结果不唯一。详情和转链另走后面两步 | `https://union.jd.com/proManager/index?pageNo=1` | `POST /custom/jd-union/searchProducts` |
| **商品转链** | `getPromotionUrl` | 在京东联盟工作台按商品 SKU 检索唯一商品卡片（过滤推荐商品），生成推广短链（优先取优惠券短链）并采集 `productInfo` | `https://union.jd.com/entire`<br>`https://union.jd.com/proManager/index?pageNo=1` | `POST /custom/jd-union/getPromotionUrl` |
| **转链后订单明细** | `getPromotionOrders` | 在京东联盟订单明细页面按日期区间（起止时间）检索推客推广订单，支持日期控件选择、滚动加载与多页拉取 | `https://union.jd.com/order` | `POST /custom/jd/promotion/orders` (RPC/Task) |

---

## 二、统一浏览器配置与免重复登录机制 (Unified Profile)

为满足**“浏览器的输入用统一的，避免重复登录”**这一核心约束，系统在 `BrowserProfileManager` 中统一了 Chrome 数据目录的解析逻辑：

1. **用户目录，一份 Profile**：
   - 默认目录是 `%LOCALAPPDATA%\rx-mimic\chrome\common`。登录脚本、单元测试、服务都不传 `profileName` 时打开这一份。
   - 工作目录在仓库根还是 `crawler-bootstrap` 都不影响这个路径，Cookie 不再散落在项目 `data/chrome`。
   - 换账号或隔离环境时再传 `profileName`，或把 `app.custom.chrome.profileBasePath` 设成绝对路径。
2. **不再拆 pool 副本**：
   - 一个 `profileName` 就是一个 Chrome 用户目录，默认只开一个浏览器实例。
   - 项目里旧的 `data/chrome/common_pool_0` 会在用户目录还没有 Cookie 时自动迁入一次。
3. **京东全站与联盟会话共享**：
   - 京东主站 Cookie（`.jd.com` 域下的 `thor`、`pin`、`token`）在商品详情与主站生效。
   - 京东联盟入口采用统一的门户地址 `https://union.jd.com/entire`，自动继承已登录会话，直接识别当前用户，避免旧版 `/overview` 页面触发偶发重定向至登录页。

---

## 三、任务详解与对标规范

### 1. 任务一：商品查看与详情（`jdProductDetail`）

- **对标规范**：完整实现 `/jditem`（`C:\Users\R-wtg\.agents\skills\jd-item\SKILL.md`）要求：
  - **输入参数**：支持 `skuId` 纯数字（如 `100059484008`）或 `productUrl`（如 `https://item.jd.com/100059484008.html`），可指定 `downloadImages`（默认 `false`，开启时下载至 `data/jd-mall/images/{skuId}/`）与 `includeSpecs`（默认 `true`）。
  - **店铺与自营判定**：
    - 采集 `result.shopName`（如 `KANS韩束京东自营旗舰店`）。
    - 判定规则：若店铺名称包含“自营”子串，则判定为京东自营商品（`店铺：{shopName}｜自营 ✅`）；否则判定为第三方店铺。
  - **页面适配**：适配现代京东详情页 DOM 结构，监听 `规格参数`、`售后保障`、`关注店铺`、`进店逛逛`、`.sku-title-name` 等关键字就绪状态，规避长超时。
  - **规格提取**：提取 `specs` 列表，包含品牌、商品编号、产地、保质期、批准文号等参数键值对。
  - **主图与详情图**：采集商品顶部主图（`mainImages`）与详情长图（`detailImages`），支持图片 URL 及本地下载文件校验。

### 2. 任务二：商品转链（`getPromotionUrl`）

- **业务流程**：
  1. 通过 `https://union.jd.com/entire` 确认登录态并进入推广管理页面 `https://union.jd.com/proManager/index?pageNo=1`。
  2. 在搜索框输入商品 SKU / 关键字并点击搜索。
  3. **推荐商品过滤与精准卡片匹配**：京东联盟搜索结果下方常附带“为您推荐以下相似商品”推荐流。通过 `isRecommend`（匹配 `.recommend-goods` 选择器）严格屏蔽推荐流卡片；卡片评分对目标 SKU 增加精准权重并优先选取 `.card.search-card`。
  4. 提取商品完整 `productInfo`：
     - `productName`：商品标题
     - `productLink`：商品真实落地链接（`https://item.jd.com/{sku}.html`）
     - `commissionRate`：佣金比例（如 `2%`）
     - `price`：到手价（如 `19.9`）
     - `storeName`：店铺名称（如 `伊利牛奶京东自营旗舰店`）
     - `imageUrl`：商品图片
  5. 点击主推卡片的“我要推广”按钮，选择媒体类型（默认 `导购媒体推广`）与导购媒体（如 `微信`），提取最终推广短链（如 `https://u.jd.com/xxxxxx`）。

### 3. 任务三：转链后订单（`getPromotionOrders`）

- **业务流程**：
  1. 访问 `https://union.jd.com/entire`，导航至推客推广订单明细 `https://union.jd.com/order`。
  2. 输入起止日期范围（`startTime` 与 `endTime`，格式 `yyyy-MM-dd`）。
  3. 自动操作 Element UI 双月日历控件导航并点选起止日期，点击“查询”。
  4. 遍历表格行并按滚动机制分页提取订单明细字段（订单号、下单时间、计税金额、预估佣金、实际佣金、订单状态等）。

---

## 四、三大独立集成测试与本地验证

项目提供 3 个完全独立的集成测试，每个测试均使用统一的持久化 profile 运行，并已在本地执行验证通过。

### 运行环境准备

在 Windows 环境下，确保使用 JDK 21 并引用 Apache Maven：

```powershell
$env:JAVA_HOME = "C:\Program Files\Java\jdk-21"
```

> **注意**：由于根 `pom.xml` 默认设置了 `<skipTests>true</skipTests>`，在运行测试时需显式传入 `-DskipTests=false -Dmaven.test.skip=false -Dsurefire.failIfNoSpecifiedTests=false`。

---

### 测试 1：商品查看与详情集成测试

- **测试类与方法**：`org.rx.test.JdMallTaskTests#jdMallDetailIntegrationShouldDownloadImages`
- **执行命令**：

```powershell
D:\home\.shell\apache-maven\bin\mvn.cmd -B -pl crawler-bootstrap -am `
  "-DskipTests=false" "-Dmaven.test.skip=false" "-Dsurefire.failIfNoSpecifiedTests=false" `
  "-Dtest=JdMallTaskTests#jdMallDetailIntegrationShouldDownloadImages" `
  "-Djd.mall.detail.integration=true" "-Djd.mall.skuId=100059484008" "-Djd.mall.downloadImages=false" test
```

- **验证证据与断言**：
  - 测试通过（`Tests run: 1, Failures: 0, Errors: 0, Skipped: 0`）。
  - 返回 `status: SUCCESS`。
  - 断言标题非空（`韩束白蛮腰防晒乳30ml防晒霜美白亮肤京东自营防紫外线SPF50+生日礼物`）。
  - 断言店铺非空（`KANS韩束京东自营旗舰店`，包含“自营”）。
  - 成功提取 12 项规格参数及 9 张商品主图、18 张详情图。

---

### 测试 2：商品转链集成测试

- **测试类与方法**：`org.rx.test.JdUnionPromotionTaskTests#jdUnionAuthorizedIntegration`
- **执行命令**：

```powershell
D:\home\.shell\apache-maven\bin\mvn.cmd -B -pl crawler-bootstrap -am `
  "-DskipTests=false" "-Dmaven.test.skip=false" "-Dsurefire.failIfNoSpecifiedTests=false" `
  "-Dtest=JdUnionPromotionTaskTests#jdUnionAuthorizedIntegration" `
  "-Djd.union.integration=true" "-Djd.union.keyword=100411400536" "-Dapp.custom.jdUnion.forcePreflight=false" test
```

- **验证证据与断言**：
  - 测试通过（`Tests run: 1, Failures: 0, Errors: 0, Skipped: 0`）。
  - 返回 `status: SUCCESS`。
  - 断言生成有效推广短链（`promotionUrl: https://u.jd.com/F1FVju1`）。
  - 断言商品卡片完整采集：`productName` 为 `伊利金典3.8g乳蛋白鲜活纯牛奶(吨吨瓶)250ml单盒 90天短保`，`productLink` 为 `https://item.jd.com/100411400536.html`，`price` 为 `19.9`，`commissionRate` 为 `2%`，`storeName` 为 `伊利牛奶京东自营旗舰店`。
  - 确认过滤推荐商品流，未发生误点击。

---

### 测试 3：转链后订单明细集成测试

- **测试类与方法**：`org.rx.test.JdUnionPromotionTaskTests#jdUnionPromotionOrdersIntegrationShouldSaveReadableDebugSnapshot`
- **执行命令**：

```powershell
D:\home\.shell\apache-maven\bin\mvn.cmd -B -pl crawler-bootstrap -am `
  "-DskipTests=false" "-Dmaven.test.skip=false" "-Dsurefire.failIfNoSpecifiedTests=false" `
  "-Dtest=JdUnionPromotionTaskTests#jdUnionPromotionOrdersIntegrationShouldSaveReadableDebugSnapshot" `
  "-Djd.union.orders.integration=true" "-Dapp.custom.jdUnion.forcePreflight=false" test
```

- **验证证据与断言**：
  - 测试通过（`Tests run: 1, Failures: 0, Errors: 0, Skipped: 0`）。
  - 返回 `status: SUCCESS`。
  - 断言 `orders` 集合非空且 `loginRequired=false`。
  - 成功生成各步骤快照（`00-entire-loaded.html`、`00-order-menu-opened.html`、`02-order-page-ready.html`、`03-date-range-selected.html`、`04-search-clicked.html`、`05-page-001-collected.html`）。

---

## 五、服务启动与 HTTP API 契约

### 1. 服务启动

推荐使用脱离会话模式启动服务（防止进程随终端会话关闭）：

```powershell
powershell -c "Invoke-CimMethod Win32_Process -MethodName Create -Arguments @{CommandLine='cmd /c D:\projs_r\rx-mimic\start-crawler-detached.bat'}"
```

服务端口为 `81`。探活命令：

```bash
curl -s -m 5 http://127.0.0.1:81/custom/jd/task/1
```

### 2. 详情接口调用示例（对标 `/jditem`）

```bash
# 提交异步抓取任务
curl -X POST http://127.0.0.1:81/custom/jd/product/detail \
  -H "Content-Type: application/json" \
  -d '{"skuId":"100059484008","downloadImages":false}'

# 响应
{"data":{"taskId":1,"status":"PENDING","action":"jdProductDetail"}}

# 轮询获取结果
curl http://127.0.0.1:81/custom/jd/task/1
```

---

## 六、淘宝/阿里妈妈三大核心任务概述

针对淘宝/天猫商城与阿里妈妈（淘宝客/淘宝联盟）生态，系统实现了三大核心自动化任务：

| 任务名称 | 任务类型 | 作用与规范 | 核心页面 | 对应 API |
| --- | --- | --- | --- | --- |
| **商品查看包含详情** | `tbProductDetail` | 解析淘宝/天猫商品详情（标题、价格、店铺名、规格参数、主图相册及详情长图懒加载提取与下载） | `https://item.taobao.com/item.htm?id={itemId}`<br>`https://detail.tmall.com/item.htm?id={itemId}` | `POST /custom/tb/product/detail`<br>`GET /custom/tb/task/{taskId}` |
| **商品转链** | `getTbPromotionUrl` | 阿里妈妈联盟后台商品库检索，输入关键字/商品标题，匹配推广卡片，选择推广位并提取二合一淘口令/推广链接及 `productInfo` | `https://pub.alimama.com/portal/v2/pages/promo/goods/index.htm` | `POST /custom/tb/promotion/url`<br>`POST /custom/tb/promotion/urls` |
| **转链后订单** | `getTbPromotionOrders` | 阿里妈妈联盟效果报表-订单明细，按起止日期范围筛选并采集推客推广订单列表（含滑块绕过与多页拉取） | `https://pub.alimama.com/portal/v2/effect/order/overviewOrder/page/index.htm` | `POST /custom/tb/promotion/orders` |

---

## 七、淘宝统一 Cookie 收口机制 (Unified Profile & Cookie Sharing)

所有淘宝/阿里妈妈任务严格遵守**“同一 Cookie 收口、统一 Profile 输入，避免重复登录”**原则：

1. **统一 Profile 目录**：
   - 任务和测试默认使用 `common` profile，物理路径为 `%LOCALAPPDATA%\rx-mimic\chrome\common`。
   - 淘宝主站（`item.taobao.com`、`detail.tmall.com`）与阿里妈妈（`pub.alimama.com`）均属于阿里账号体系（Havana 统一单点登录认证）。
2. **免重复登录机制**：
   - 在这份用户 Profile 里登录淘宝或阿里妈妈后，`.taobao.com`、`.tmall.com`、`.alimama.com` 的 Cookie 留在同一目录。
   - 商品详情任务（`tbProductDetail`）、转链任务（`getTbPromotionUrl`）以及转链订单任务（`getTbPromotionOrders`）共用该实例，**任一任务完成登录，其他所有任务自动继承有效会话**。
3. **未登录精准感知与人工接管**：
   - 未登录状态访问阿里妈妈时，中转页 `https://pub.alimama.com/?forward=...` 显示“立即登录”，系统精准识别为 `CustomCrawlStatus.LOGIN_REQUIRED`。
   - Havana 统一登录 URL 适配为现代统一端点：`https://login.taobao.com/havanaone/login/login.htm?bizName=taobao&redirectURL=...`。
   - 触发登录时浏览器窗口保持打开（默认 180 秒），支持人工扫码/账密登录接管，登录成功后自动继续后续流程。

---

## 八、淘宝三大独立集成测试与本地验证

项目提供 3 个完全独立的淘宝集成测试，全部在本地运行并通过验证：

### 测试 1：商品查看包含详情集成测试

- **测试类与方法**：`org.rx.test.TbMallTaskTests#tbProductDetailIntegrationShouldDownloadImages`
- **执行命令**：

```powershell
$env:JAVA_HOME = "C:\Program Files\Java\jdk-21"
D:\home\.shell\apache-maven\bin\mvn.cmd -B -pl crawler-bootstrap -am `
  "-DskipTests=false" "-Dmaven.test.skip=false" "-Dsurefire.failIfNoSpecifiedTests=false" `
  "-Dtest=TbMallTaskTests#tbProductDetailIntegrationShouldDownloadImages" `
  "-Dtb.mall.detail.integration=true" "-Dtb.mall.itemId=601440653278" "-Dapp.custom.tbMall.forcePreflight=false" test
```

- **验证证据与断言**：
  - 测试通过（`Tests run: 1, Failures: 0, Errors: 0, Skipped: 0`）。
  - Sannysoft 指纹预检通过（`webdriver: false`，`chrome: true`）。
  - 支持 `itemId` 或详情链接解析，自动处理滑块验证检测，支持主图相册与详情图懒加载下载至本地目录 `data/tb-mall/images/{itemId}/`。

---

### 测试 2：商品转链集成测试

- **测试类与方法**：`org.rx.test.TbPromotionUrlTaskTests#tbPromotionUrlIntegrationShouldSaveReadableDebugSnapshot`
- **执行命令**：

```powershell
$env:JAVA_HOME = "C:\Program Files\Java\jdk-21"
D:\home\.shell\apache-maven\bin\mvn.cmd -B -pl crawler-bootstrap -am `
  "-DskipTests=false" "-Dmaven.test.skip=false" "-Dsurefire.failIfNoSpecifiedTests=false" `
  "-Dtest=TbPromotionUrlTaskTests#tbPromotionUrlIntegrationShouldSaveReadableDebugSnapshot" `
  "-Dtb.promotion.url.integration=true" "-Dapp.custom.tbPromotion.forcePreflight=false" test
```

- **验证证据与断言**：
  - 测试通过（`Tests run: 1, Failures: 0, Errors: 0, Skipped: 0`）。
  - 复用 `data/chrome/common_pool_0` 实例池会话。
  - 正确处理阿里妈妈转链商品搜索、卡片定位与推广链接生成；未授权/未登录时准确返回 `LOGIN_REQUIRED` 并保存完整调试快照。

---

### 测试 3：转链后订单集成测试

- **测试类与方法**：`org.rx.test.TbPromotionOrdersTaskTests#tbPromotionOrdersIntegrationShouldSaveReadableDebugSnapshot`
- **执行命令**：

```powershell
$env:JAVA_HOME = "C:\Program Files\Java\jdk-21"
D:\home\.shell\apache-maven\bin\mvn.cmd -B -pl crawler-bootstrap -am `
  "-DskipTests=false" "-Dmaven.test.skip=false" "-Dsurefire.failIfNoSpecifiedTests=false" `
  "-Dtest=TbPromotionOrdersTaskTests#tbPromotionOrdersIntegrationShouldSaveReadableDebugSnapshot" `
  "-Dtb.promotion.orders.integration=true" "-Dapp.custom.tbPromotion.forcePreflight=false" test
```

- **验证证据与断言**：
  - 测试通过（`Tests run: 1, Failures: 0, Errors: 0, Skipped: 0`）。
  - 同样收口在 `common_pool_0`，自动导航订单报表页，支持日期范围选择、滑块验证自动拖拽重试与多页订单数据采集。

---

## 九、淘宝 HTTP API 契约与调用示例

### 1. 提交商品详情抓取任务

```bash
curl -X POST http://127.0.0.1:81/custom/tb/product/detail \
  -H "Content-Type: application/json" \
  -d '{"itemId":"601440653278","downloadImages":false,"includeSpecs":true}'

# 响应
{"data":{"taskId":2,"status":"PENDING","action":"tbProductDetail"}}
```

### 2. 轮询获取任务结果

```bash
curl http://127.0.0.1:81/custom/tb/task/2
```

### 3. 提交商品转链任务

```bash
curl -X POST http://127.0.0.1:81/custom/tb/promotion/url \
  -H "Content-Type: application/json" \
  -d '{"keyword":"西麦纯燕麦片3kg","adSiteName":"5"}'
```

### 4. 提交转链订单查询任务

```bash
curl -X POST http://127.0.0.1:81/custom/tb/promotion/orders \
  -H "Content-Type: application/json" \
  -d '{"startTime":"2026-09-16","endTime":"2026-10-16"}'
```
