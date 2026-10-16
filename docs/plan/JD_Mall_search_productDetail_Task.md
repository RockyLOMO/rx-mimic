# 京东商城 jdSearchProduct / jdProductDetail 抓取任务

> 使用操作手册（起服务/调 API/取数据/常见问题）见 [JD_Mall_Usage_Guide.md](JD_Mall_Usage_Guide.md)；本文档为任务设计与实现细节。

## 任务定义

- 任务名：`jdSearchProduct`（关键字搜索商品）、`jdProductDetail`（商品详情抓取，含详情长图下载）
- 目标：
  - `jdSearchProduct`：在京东主站（www.jd.com / search.jd.com）按关键字搜索，提取商品卡（skuId/标题/价格/图/店铺/链接）。
  - `jdProductDetail`：进入 item.jd.com 商品详情页，提取标题/价格/店铺/主图相册/规格参数/详情长图，并按需把图片下载到服务器本地。
- 与京东联盟任务（`getPromotionUrl` 等）的区别：本任务走**京东主站**，无需联盟后台；登录态影响部分价格/店铺字段展示。
- 运行前置：每次抓取前先通过 `https://bot.sannysoft.com/` 指纹检测（有 30 分钟缓存）。
- 浏览器：仅使用本机 Chrome + Playwright，持久化 profile（默认 `common`），任务间浏览器常驻复用（`closeBrowserAfterTask=false` + `minIdle=1`），优先模拟真实人工操作。

## API（异步）

Controller：`JdMallController`，`@RequestMapping("/custom/jd")`，服务端口 `81`。提交即返回 taskId，由 H2 持久化队列（`custom_crawl_task_queue`，默认串行 `queueMaxConcurrency=1`）后台执行，轮询查询接口取结果。

| 端点 | 说明 |
| --- | --- |
| `POST /custom/jd/search` | 提交搜索任务，action=`jdSearchProduct` |
| `POST /custom/jd/product/detail` | 提交详情任务，action=`jdProductDetail` |
| `GET /custom/jd/task/{taskId}` | 查询任务状态与结果；查无返回 `NOT_FOUND` |

### 搜索入参（`JdSearchRequest`）

| 字段 | 必填 | 说明 | 默认 |
| --- | --- | --- | --- |
| `keyword` | 是 | 搜索关键字 | - |
| `maxResults` | 否 | 期望条数，上限 100（单页懒加载上限） | `60` |
| `profileName` | 否 | Chrome profile | `common` |
| `forcePreflight` | 否 | 强制每次 Sannysoft 预检 | `false` |
| `keepBrowserOpenOnLoginRequired` | 否 | 登录等待期间保留浏览器 | `true` |
| `outputPath` | 否 | 结果 JSONL 路径 | `./data/jd-mall/output.jsonl` |
| `debugEnabled` / `debugOutputDir` | 否 | HTML 步骤快照 | `false` / `./data/jd-mall/debug` |

### 详情入参（`JdProductDetailRequest`）

| 字段 | 必填 | 说明 | 默认 |
| --- | --- | --- | --- |
| `skuId` / `productUrl` | 二选一 | 商品 ID 或 `item.jd.com/{sku}.html` 链接，只传其一时另一侧自动补全 | - |
| `downloadImages` | 否 | 是否下载图片到本地 | `true` |
| `includeSpecs` | 否 | 是否提取规格参数 | `true` |
| 其余字段 | 否 | 同搜索入参 | - |

### 查询出参（`JdTaskQueryResult`）

`{taskId, action, status(PENDING/RUNNING/SUCCESS/FAILED), errorMessage, createdAt, startedAt, finishedAt, result}`。

- `SUCCESS` 时 `result` 按 action 反序列化：搜索 → `JdSearchResult`（`items[].{skuId,title,price,imageUrl,shopName,productUrl}`），详情 → `JdProductDetailResult`（`mainImages`/`detailImages` 每项 `{url,localPath,fileName}`、`specs[].{name,value}`）。
- `FAILED` 时 `result` 为对应类型的失败对象（`status=FAILED` + message）。

### 调用示例

```bash
# 搜索（立即返回 taskId）
curl -X POST http://127.0.0.1:81/custom/jd/search -H 'Content-Type: application/json' \
  -d '{"keyword":"小米手机","maxResults":30,"debugEnabled":true}'

# 轮询结果
curl http://127.0.0.1:81/custom/jd/task/{taskId}

# 详情（skuId 与 productUrl 二选一）
curl -X POST http://127.0.0.1:81/custom/jd/product/detail -H 'Content-Type: application/json' \
  -d '{"skuId":"100243429829","downloadImages":true}'
```

## 登录门禁与 cookie 持久化

- 进入京东页先判断登录态（`isJdLoggedIn`）：**双标记**——passport 会话（`pt_key`+`pt_pin`）或 扫码会话（`thor`+`pin`/`unick`）任一存在即视为已登录；两者皆无则判定未登录。
- 未登录时：保持浏览器打开（`keepBrowserOpenOnLoginRequired`）等待人工扫码/登录，轮询 `loginWaitSeconds`（默认 180s = 3 分钟）；超时返回 FAILED（message 注明登录超时）并停止任务。首页「你好，请登录」入口文案不算未登录。
- **cookie 跨重启持久化链路**（实测有效）：
  1. 人工登录落在池实例 profile（如 `common_pool_0`）后，必须**优雅关闭浏览器**（`POST /custom/profile/close`）才会全量落盘 Chrome cookie 库。
  2. Chrome cookie 用 `Local State` 内密钥加密——跨目录复制必须 `Local State` + `Default/Network/Cookies` **成对**复制。
  3. 登录 cookie 同步进种子目录 `data/chrome/common/`（`Local State` + `Default/Network/Cookies` + `Default/Preferences`）后，任何 JVM 重启新种的池实例自带登录态。
- **池启动竞态**（曾经的坑）：JVM 重启时 prewarm 与队列崩溃恢复任务并发，若种子目录无登录 cookie，任务可能抢先用空 common 目录种出第二个无登录实例（如 `common_pool_1`），表现为「明明登录过却提示未登录」。根治 = 把登录 cookie 同步进 common 种子（上一步）；另 `app.custom.chrome.maxActive=1` 保证单实例复用。

## 流程要点（2026-08 新版页面 DOM，全部实测）

### 搜索（runSearchFlow）

1. 进入 www.jd.com → JDJRV 验证码检测 → 拟人输入：JS 打标 `data-rx-jd-mall-search-input`（候选 `input#key` 等）后 `elementPress` 原生逐键输入。
2. **提交优先输入框回车**（React 搜索栏通用）→ 再点真实按钮（打标排除 `ai_search`/`image_search` 区域——`ai_search_wrap_right` 的 innerText 恰为「搜索」但是假按钮）；以「URL 到达 search.jd.com / 出现商品卡」为提交成功判据，仍停首页则直跳 `searchUrlTemplate` 兜底（`diagnostics.searchNavigateFallback=true`）。
3. 等结果：轮询商品卡数量，期间每轮先过验证码检测；超时快照 + message 带当前 URL。
4. 拟人分步滚动触发懒加载（≤`maxScrollRounds`，两轮不增长提前停）。
5. JS 提取（多选择器评分兜底）：
   - 卡片：`li.gl-item/[data-sku]`（旧）与 CSS-module hash 卡（新）——标题 `span[title]` / `[class*="goods_title"] span[title]`；
   - 价格：`[class*="_price_"]` / `.p-price` 容器整段文本去空白，¥ 在 `<i>`、数字在兄弟 span；
   - 店铺：`[class*="shopName"] a` / `[class*="shop"] a` / `.p-shop a`；
   - `//` 图补 `https:`，缺链接按 sku 拼 `item.jd.com/{sku}.html`。

### 详情（runDetailFlow）

1. 进入 `item.jd.com/{sku}.html` → 验证码检测 → `waitSummaryMounted` 轮询主信息区挂载（`.sku-title-name` 等，最多 pageTimeoutSeconds/2）。
2. 基础信息（`READ_PRODUCT_BASE_SCRIPT`）：
   - 标题 `.sku-title-name` / `.sku-name`；店铺 `.top-name[title]` / `.shopName a`；
   - 价格 `[class*="product-price--value"]` / `.product-price--main` 等可见非骨架容器（骨架类 `continuous-skeleton-item--price` 需等真实价格渲染）→ **整段文本正则取首个 ¥ 金额**（防促销文案混入）；
   - 主图相册 `.image-carousel-track img` 等 → URL 升级：`/n[12]/(s\d+x\d+_)?jfs/` → `/n0/jfs/`（原图）、`/s\d+x\d+_jfs/` → `/s1440x1440_jfs/`（主图高清）。
3. 规格参数（`readProductSpecs`）：在 `div.attribute`（**display:none 隐藏区，必须用 title/textContent，innerText 为空**）——`.list .item` 行（`.label .text` 名称 + `.value` 值）为主、`.highlight-attrs .item`（`.title` 值 + `.desc .text` 名称）补充；注意 `class="parameter"` 是主图轮播里的「规格参数」导航卡，不是规格表；旧版 `.Ptable` dl/dt/dd 兜底。
4. 详情长图（两类来源，渐进收集合并）：
   - `<img>` 标签：`n[012]/pcpubliccms` 路径（手机等品类文本模块的说明图）；
   - **SSD 装修模块 CSS background-image**（服装等品类的图文长图主力）：位于 `#detail-main` 内 `.ssd-module` 楼层，随滚动懒挂载——收集脚本扫 `getComputedStyle(el).backgroundImage`，段白名单 `(img|popshop|sku|n[012]|pcpubliccms)/jfs/`（`imagetools` 段是 UI 图标库排除），楼层活动区（`ssd-floor-activity`）随 `activity` 关键字排除；
   - 拟人滚动 `detailScrollSteps` 步（滚到底自动提前结束），每 3 步渐进收集合并，最终兜底再收一次。
5. 去重：排除主图 jfs 路径、排除 `/s54x54_jfs/` 等缩略规格段（双边 ≤300 视为缩略）、上限 `maxDetailImages`。
6. 下载（`downloadImages=true`）：走浏览器上下文 `context.request()`（带 cookie/UA，与页面请求一致）串行下载，步间随机延迟，保存 `{imageSaveDir}/{skuId}/main|detail/{序号}.{ext}`；CDN 内容协商可能返回 **avif**，按响应内容落 `.avif` 扩展名（图片有效）；单张失败跳过记 `diagnostics.downloadFailures-*`，不失败整个任务。

### 风控与登录兜底

- 验证码（JDJRV）：`JdCaptchaHandler` 先自动拟人拖拽（`Browser.locateInFrames` 跨 frame 定位滑块，Playwright 原生坐标），失败刷新重试 ≤`captchaMaxAutoAttempts`；非滑块类（点选文字）不自动处理。
- 自动处理仍失败 → 推送登录通知 + `lease.keepOpen` + 人工轮询等待 `captchaHumanWaitSeconds`（默认 120s），超时返回 FAILED（message 注明验证码未通过）。
- 登录页判定：`looksLikeJdMallLoginBody` 要求命中 ≥2 个登录页强特征（扫码登录/密码登录/短信验证码登录/忘记密码），防止首页「你好，请登录」入口文案误判。
- 任务时长：`app.custom.maxTaskMinutes`；详情带图片下载时 ×2。

## 配置（app.custom.jdMall）

| 键 | 默认 | 说明 |
| --- | --- | --- |
| `homeUrl` / `searchUrlTemplate` / `itemUrlTemplate` / `loginUrlPrefix` | 见 application.yml | 入口与登录判定 URL |
| `pageTimeoutSeconds` / `initialPageTimeoutSeconds` | `60` / `60` | 页面加载超时 |
| `stepDelayMillis` / `stepDelayRandomMillis` | `700` / `400` | 步骤死等（拟人轨迹仍靠 WebBrowser humanInput） |
| `navigationPauseMillis` / `navigationPauseRandomMillis` | `1500` / `2500` | 页面导航前随机间歇（搜索→详情等跨页衔接的拟人停顿） |
| `loginWaitSeconds` | `180` | 人工登录等待（3 分钟超时停止） |
| `captchaMaxAutoAttempts` / `captchaHumanWaitSeconds` | `3` / `120` | JDJRV 自动拖拽次数 / 人工兜底等待 |
| `defaultMaxResults` / `maxScrollRounds` | `60` / `12` | 搜索条数与滚动轮数 |
| `maxDetailImages` / `detailScrollSteps` | `120` / `28` | 详情图上限 / 滚动步数（2026 版页面更长，滚到底自动提前结束） |
| `imageSaveDir` / `downloadDelayMillis` | `./data/jd-mall/images` / `150` | 图片保存目录与下载间隔 |
| `defaultOutputPath` / `debugOutputDir` | `./data/jd-mall/output.jsonl` / `./data/jd-mall/debug` | JSONL 与快照输出 |

debug 快照（`debugEnabled=true`）含 `04b-image-inventory`：全部 img 的可见性/类链/naturalWidth×H/URL 清单 + `#detail-main` 内 SSD 楼层 background-image 清单（`BG` 行），长图缺失时据此排查。

## 验证记录（2026-08-28 实测，本机真实浏览器）

- 编译：`mvn -B -pl crawler-api,crawler-bootstrap -am package`（JDK 21）→ BUILD SUCCESS。
- 单测：`JdMallTaskTests` 12 例 10 过 2 skip（集成门控）；`CustomCrawlQueueServiceTest` 5 例全过。
- 集成实测（服务级，curl 提交/轮询）：
  - **搜索**：`小米手机` 57-59 条（标题/价格/图/店铺/链接齐全，价格形如 `¥1146.65` 无空格无促销文案，走首页输入+回车真实路径，`fingerprintPassed=true`）；`短袖T恤` 20 条同验。
  - **详情（手机品类）** sku 100291234557：标题/价格/店铺 ✓，specs 32 条（品牌/入网型号/屏幕刷新率等），主图 14-17 张 s1440x1440 高清，说明图 0-3 张（手机品类详情以文本模块为主，图数随懒加载浮动属正常）。
  - **详情（服装品类）** sku 100243429829：specs 21 条，主图 6 张，**详情长图 20 张**（SSD 楼层 background-image，`/sku/jfs/` 段），26/26 本地文件落盘（5-178KB）。
  - **登录门禁**：全部任务 `loginRequired=false`；登录态（thor 扫码会话，58 cookie）经 `Local State`+`Cookies` 成对复制进 common 种子后，跨 ≥6 次 JVM 重启全部免登录复用。
- 排查备忘：
  - 命令行启动服务必须带 4 个 `--add-opens`（java.base 的 lang/io/net/util.concurrent），否则 `Reflects` patch `LinkedBlockingQueue` 静态初始化失败；标准命令见 `start-crawler-bootstrap.bat`。
  - 重打包前先杀监听 81 端口的 java（jar 锁）；`mvn -pl` 必须带 `-am`。
  - Java 内嵌 JS 字符串改动后用 node --check 验证拼接结果（`/regex/` 字面量内的转义是高频翻车点）。
- 集成测试（JUnit 门控，可选）：`-Djd.mall.integration=true -Djd.mall.keyword=小米手机` / `-Djd.mall.detail.integration=true -Djd.mall.skuId=...`
