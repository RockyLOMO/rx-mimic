# rx-mimic 京东爬虫任务与集成指南

本项目基于 Java 21 + Playwright（直接驱动本机 Chrome）+ Spring Boot 3 构建，提供面向京东商城与京东联盟的自动化爬虫与数据采集能力。

---

## 一、京东三大核心任务概述

项目针对京东生态实现了三大核心任务，支持异步 HTTP API、结果队列轮询、本地持久化与独立的单元/集成测试：

| 任务名称 | 任务类型 | 作用与规范 | 核心页面 | 对应 API |
| --- | --- | --- | --- | --- |
| **商品查看与详情** | `jdProductDetail` | 参考 `/jditem` Skill 规范，解析京东商品详情（标题、店铺、自营判定、主图、详情图、规格参数） | `https://item.jd.com/{skuId}.html` | `POST /custom/jd/product/detail`<br>`GET /custom/jd/task/{taskId}` |
| **商品转链** | `getPromotionUrl` | 在京东联盟工作台按商品 SKU / 关键字检索商品卡片（精准卡片匹配，过滤推荐商品），生成推广短链（优先取优惠券短链）并采集 `productInfo` | `https://union.jd.com/entire`<br>`https://union.jd.com/proManager/index?pageNo=1` | `POST /custom/jd/promotion/url` (RPC/Task) |
| **转链后订单明细** | `getPromotionOrders` | 在京东联盟订单明细页面按日期区间（起止时间）检索推客推广订单，支持日期控件选择、滚动加载与多页拉取 | `https://union.jd.com/order` | `POST /custom/jd/promotion/orders` (RPC/Task) |

---

## 二、统一浏览器配置与免重复登录机制 (Unified Profile)

为满足**“浏览器的输入用统一的，避免重复登录”**这一核心约束，系统在 `BrowserProfileManager` 中统一了 Chrome 数据目录的解析逻辑：

1. **绝对路径与多模块统一定位**：
   - 默认基础路径配置为 `./data/chrome`。
   - 当在根工程目录或子模块（如 `crawler-bootstrap`）运行命令/测试时，`BrowserProfileManager.resolveBaseProfileDir()` 自动探测并归一化为项目根目录的绝对路径 `D:\projs_r\rx-mimic\data\chrome`。
   - 防止子模块运行时在 `crawler-bootstrap/data/chrome` 产生空白的隔离目录导致丢失登录会话。
2. **实例池会话保留**：
   - 任务和测试默认使用 `common` profile，实例运行在 `data/chrome/common_pool_0`。
   - `BrowserProfileManager` 检查实例目录中若已具备可用 Chrome 会话（如包含 `Default/Network/Cookies`），则自动跳过覆盖，原样保留已有登录态。
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
