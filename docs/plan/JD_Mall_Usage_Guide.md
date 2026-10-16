# 京东抓取（jdSearchProduct / jdProductDetail）使用指南

面向使用者的操作手册：起服务 → 调 API → 取数据。任务设计细节见 [JD_Mall_search_productDetail_Task.md](JD_Mall_search_productDetail_Task.md)。

## 1. 前置条件

- JDK 21（编译运行均需要）、本机已装 Chrome（Playwright `channel=chrome` 直接驱动本机 Chrome，无需下载浏览器内核）。
- Playwright 依赖版本：`1.62.0`（父 pom `playwright.version` 统一管理，升级只改这一处）。
- 反爬基础已内置，无需额外操作：
  - stealth 指纹注入（每次任务先过 `bot.sannysoft.com` 预检，30 分钟缓存）；
  - 拟人操作：鼠标贝塞尔轨迹（24-56 步）、逐键输入（90-260ms/键）、动作间随机延迟；
  - 每步操作间随机停顿（700+0~400ms）、滚动步间随机（400-1300ms）；
  - **页面导航前随机间歇 1.5-4s**（`navigationPauseMillis=1500` + 随机 `2500`）——如「搜索完→点开详情页」之间的自然停顿；
  - 图片下载间隔 150-750ms 随机。

## 2. 启动服务

方式一（推荐）：双击仓库根目录 `start-crawler-bootstrap.bat`（已含全部 JVM 参数）。

方式二（命令行，注意必须带 4 个 `--add-opens`，否则反射 patch 静态初始化失败起不来）：

```bat
cd /d D:\projs_r\rx-mimic
java --add-opens=java.base/java.lang=ALL-UNNAMED --add-opens=java.base/java.io=ALL-UNNAMED ^
  --add-opens=java.base/java.net=ALL-UNNAMED --add-opens=java.base/java.util.concurrent=ALL-UNNAMED ^
  -jar crawler-bootstrap/target/crawler-bootstrap-1.0.jar
```

- 服务端口 `81`；就绪判定：`curl http://127.0.0.1:81/custom/jd/task/1` 返回 200/404 即可用。
- 浏览器**常驻**（任务间不关，`closeBrowserAfterTask=false`），profile 池 `data/chrome/common`。
- 工作目录必须是仓库根（`data/`、H2 库等相对路径基于 cwd）。

方式三（脱离会话，推荐给 Agent 用）：`start-crawler-detached.bat` 通过 WMI 在会话进程树之外启动 java，
进程不会被 Agent 会话退出连带杀掉，日志落 `data/logs/crawler-service.log`：

```powershell
powershell -c "Invoke-CimMethod Win32_Process -MethodName Create -Arguments @{CommandLine='cmd /c D:\projs_r\rx-mimic\start-crawler-detached.bat'}"
```

停止：`netstat -ano | findstr :81` 找 PID → `taskkill /F /PID <pid>`。

## 3. 登录（一次性，人工）

- 登录由人工处理：任务进入京东页后先判登录态（passport `pt_key`+`pt_pin` 或扫码 `thor` 会话任一存在即已登录）。
- 未登录时浏览器保持打开并等待人工扫码/登录，**3 分钟**（`loginWaitSeconds=180`）超时后任务以 `LOGIN_REQUIRED` 停止；重新提交任务即可再等。
- 登录 cookie 已持久化：人工登录一次 + 优雅关闭浏览器后，登录态固化在 `data/chrome/common`（种子），此后重启服务/机器均免登录（实测跨多次 JVM 重启有效）。
- 若登录态失效（如长期未访问被京东过期）：再提交任意任务 → 浏览器弹出 → 人工重新扫码 → 之后执行 `curl -X POST http://127.0.0.1:81/custom/profile/close` 优雅关闭落盘。

## 4. API 使用（异步：提交即返回 taskId，轮询取结果）

### 4.1 按关键字搜索商品

```bash
curl -X POST http://127.0.0.1:81/custom/jd/search -H "Content-Type: application/json" ^
  -d "{\"keyword\":\"小米手机\",\"maxResults\":30,\"debugEnabled\":false}"
# 返回：{"success":true,...,"data":{"taskId":15,"action":"jdSearchProduct","status":"PENDING"}}
```

| 入参 | 必填 | 默认 | 说明 |
| --- | --- | --- | --- |
| `keyword` | 是 | - | 搜索关键字 |
| `maxResults` | 否 | `60` | 期望条数（单页懒加载上限 ~100） |
| `debugEnabled` | 否 | `false` | 开后写 HTML 步骤快照（排查用） |

### 4.2 抓取商品详情（含图片下载）

```bash
curl -X POST http://127.0.0.1:81/custom/jd/product/detail -H "Content-Type: application/json" ^
  -d "{\"skuId\":\"100243429829\",\"downloadImages\":true}"
```

| 入参 | 必填 | 默认 | 说明 |
| --- | --- | --- | --- |
| `skuId` / `productUrl` | 二选一 | - | 商品 ID 或 `item.jd.com/{sku}.html` 链接，只传其一时另一侧自动补全 |
| `downloadImages` | 否 | `true` | 图片下载到本地 |
| `includeSpecs` | 否 | `true` | 提取规格参数 |
| `debugEnabled` | 否 | `false` | 步骤快照 |

### 4.3 轮询结果

```bash
curl http://127.0.0.1:81/custom/jd/task/{taskId}
```

- `status`：`PENDING`（排队）→ `RUNNING` → `SUCCESS` / `FAILED`。
- 队列串行（`queueMaxConcurrency=1`）：搜索/详情任务按提交顺序执行；建议 8-10s 轮询一次。
- `SUCCESS` 时 `result`：
  - 搜索：`items[].{skuId,title,price,imageUrl,shopName,productUrl}` + `resultCount`、`loginRequired`、`fingerprintPassed`；
  - 详情：`title`、`price`、`shopName`、`specs[].{name,value}`、`mainImages[]`/`detailImages[]`（每项 `{url,localPath,fileName}`）。

### 4.4 典型流程：搜索 → 详情（推荐顺序）

1. 提交搜索任务，轮询至 SUCCESS，从 `items` 里选目标商品拿 `skuId`；
2. 提交详情任务（同 `skuId`）；
3. 两任务衔接处服务自动插入 1.5-4s 随机间歇，模拟人「看完搜索结果再点商品」，无需自行 sleep。

## 5. 数据落盘位置

服务工作目录 = `D:\projs_r\rx-mimic`：

| 数据 | 位置 | 说明 |
| --- | --- | --- |
| 商品信息 JSONL | `data/jd-mall/output.jsonl` | 每任务一行（搜索 items / 详情全字段），追加写 |
| 图片文件 | `data/jd-mall/images/{skuId}/main/001.png`、`.../detail/001.jpg` | 扩展名按实际内容（CDN 可能返回 `.avif`，图片有效） |
| 任务结果（API 数据源） | `browser-task-db.mv.db`（H2） | `GET /custom/jd/task/{id}` 读这里，重启不丢 |
| 排查快照 | `data/jd-mall/debug/common/{关键词或sku}-{时间戳}/` | `debugEnabled=true` 时生成 |

## 6. 常见问题

| 现象 | 处理 |
| --- | --- |
| 任务 `LOGIN_REQUIRED` / message 含 login wait timeout | 登录态失效：重新提交任务，浏览器弹出后人工扫码（3 分钟内），成功后再执行 `POST /custom/profile/close` 落盘 cookie |
| 出现滑块验证码 | 先自动拟人拖拽（≤3 次），仍不过则浏览器留屏等人工 120s；超时任务失败，重新提交 |
| 搜索结果价格/店铺个别为空 | 页面懒加载浮动，重跑该任务即可 |
| 详情 `detailImages` 为 0 | 部分品类（如手机）详情以文本模块为主、无长图，属正常；服装等品类有 15-20 张 SSD 楼层长图 |
| 改代码后重打包失败 `Unable to rename jar` | 旧服务进程锁着 jar：`netstat -ano | findstr :81` 找 PID → `taskkill /F /PID <pid>` → 重打包 |
| 需要停服且保留登录 cookie | 先 `curl -X POST http://127.0.0.1:81/custom/profile/close`（优雅关闭浏览器）再停进程 |
| `mvn -pl` 报 could not find project / artifact | 命令行编译必须带 `-am`，且先 `cd /d D:\projs_r\rx-mimic` |

## 7. 常用调优键（application.yml `app.custom.jdMall`）

| 键 | 默认 | 说明 |
| --- | --- | --- |
| `defaultMaxResults` / `maxScrollRounds` | `60` / `12` | 搜索条数 / 滚动加载轮数 |
| `maxDetailImages` / `detailScrollSteps` | `120` / `28` | 长图上限 / 详情滚动步数（滚到底自动提前结束） |
| `navigationPauseMillis` / `navigationPauseRandomMillis` | `1500` / `2500` | 导航前随机间歇（调大更拟人，任务更慢） |
| `stepDelayMillis` / `stepDelayRandomMillis` | `700` / `400` | 步骤间随机停顿 |
| `loginWaitSeconds` | `180` | 人工登录等待上限 |
| `imageSaveDir` | `./data/jd-mall/images` | 图片保存目录 |
| `downloadDelayMillis` | `150` | 相邻图片下载基础间隔（另有 0-600ms 随机） |

改动后需重新打包并重启服务：

```bat
cd /d D:\projs_r\rx-mimic
set JAVA_HOME=C:\Program Files\Java\jdk-21
"D:\home\.shell\apache-maven\bin\mvn.cmd" -B -pl crawler-bootstrap -am -Dtest=JdMallTaskTests ^
  -Dmaven.test.skip=false -DskipTests=false -Dsurefire.failIfNoSpecifiedTests=false package
```
