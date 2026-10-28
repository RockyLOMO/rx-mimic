# 数字商品 ID 与降价跟踪

更新：2026-10-27。新模型直接生效，不迁移旧价格数据，不保留联盟加密 ID 别名表或旧字段兼容层。

## 商品身份

- 统一业务字段为 `productId`：JD 数字 SKU ID、TB/天猫数字 item ID，均使用字符串传输。唯一商品键为 `(platform, productId)`。
- `encryptedProductId` 统一指联盟加密商品 ID，只允许出现在平台适配器、临时输入和有界内存中。它不是推广链接。
- JD 收到加密 ID 时，先访问京粉物料入口及最终商品 URL，取数字 SKU，再搜索联盟；不会将加密字符串提交为联盟搜索关键词。
- TB 收到加密 ID 时，通过已有平台转链适配器生成链接，再访问最终商品 URL 提取数字 item ID。
- 商品 URL 解析支持 JD PC 商品页、移动商品 HTML 页、`item.m.jd.com/ware/view.action?wareId=数字`，以及淘宝/天猫标准商品页。登录页、中间页和任意站点的 `id` 参数不构成商品身份。
- TB SKU 规格 ID 不会被当作商品 item ID。明确的数字输入与最终跳转数字 ID 不符时，不建立价格跟踪。

普通关键词搜索不会为整页商品逐一转链。数字身份明确的卡片才落库；其余卡片仅在临时响应里提供 `encryptedProductId`、标题、价格等，选中商品后再解析。

## 存储边界

新价格库为 `./data/product-price-db.mv.db`，可通过 `app.storage.priceDbPath` 设置。通用任务/Cookie 库仍使用 `app.storage.h2DbPath`。

商品、转链事实、订阅、快照、outbox 和广播事件仅引用数字商品身份或内部外键。没有 `price_product_alias`，不保存加密 ID 或加密 ID 集合。推广 URL、口令、长短链允许保存到转链缓存，缓存键仍是平台数字商品 ID 与推广位。

规范化发生在持久队列入库之前；结果按白名单保存，去掉独立加密 ID 字段和诊断正文。已移除 JSONL 写入功能及 outputPath 参数。联盟页面的原始调试截图/HTML及链接收集不会被额外归档；诊断使用脱敏状态。

身份映射与临时结果缓存各最多 500 项、2 MiB，写入后 6 小时失效，合计最多 1000 项、4 MiB。相同输入的正在进行的解析合并，最多同时等待 20 个不同输入。重启清空，不使用磁盘缓存或 Redis。

加密输入解析在提交入口有限等待，最长 120 秒。未完成时返回 `IDENTITY_PENDING_RETRY`，完成后重新提交可命中缓存；尚未数字化的输入不获得持久 taskId，也不承诺跨重启恢复。已完成数字化的任务、商品与历史可以正常恢复。

推广链接只在临时任务结果中返回。缓存淘汰、过期或重启后，持久结果仍有数字身份与商品信息，但不再含原推广链接。需要链接时，用数字 ID 再次转链；不会为了通知重试而重复转链。

## 跟踪接口

接口均位于现有 `/api/v1` 下，沿用 Bearer 认证、调用方归属检查和 `success/code/message/data` 包装。公网路径使用部署配置的前缀。

| 方法与路径 | 用途 |
| --- | --- |
| `POST /tasks` | 联盟搜索或转链；数字、加密 ID、链接在入口规范化，普通搜索关键词保留 |
| `GET /tasks/{taskId}/result` | 读取结果，缓存仍在时提供临时推广链接 |
| `GET /price-watches` | 当前调用方的跟踪列表，返回数字 `productId` 与商品直达链接 |
| `POST /price-watches` | 关注已入库数字商品，或更新目标价、优先级 |
| `POST /price-watches/{id}/pause` | 暂停 |
| `POST /price-watches/{id}/resume` | 恢复 |
| `POST /price-watches/{id}/refresh` | 受预算及每日一次限制的手动刷新，当天已采集返回 DAILY_CACHED |
| `GET /price-watches/{id}/snapshots?days=30` | 按日价格历史，支持30/60/90天 |
| `GET /products/{platform}/{productId}/snapshots?days=30` | 商品按日价格历史，默认30天，只读取数据库 |
| `GET /price-watches/budget` | 窗口、各平台访问与风控计数 |

关注参数示例：

```json
{"platform":"jd","productId":"100018097536","targetPrice":35.00,"tier":0}
```

`tier`：0=重点、1=普通、2=冷门；目标价必须大于 0 且最多两位小数。`productId` 尚未建档时返回 `PRODUCT_NOT_FOUND`，先完成联盟搜索/转链及身份解析。

搜索默认只记录有数字身份的价格观察，转链完成且身份解析成功后自动建跟踪。`identityResolved=false` 表示链接可能已生成，但尚未建立数字身份和价格跟踪。

## 调度、预算与提醒

```yaml
app:
  storage:
    priceDbPath: ./data/product-price-db
  custom:
    priceWatch:
      enabled: true
      timezone: Asia/Shanghai
      windowStart: "06:00"
      windowEnd: "01:00"
      dailyTotalVisits: 180
      dailyBackgroundVisits: 120
      foregroundReserveVisits: 60
      dailyMaxProductVisits:
        jd: 100
        tb: 100
      activeWatchLimit: 30
      backgroundQueuedLimit: 2
      notifyEnabled: false
```

总量、后台子额度、前台预留和各平台上限共同约束查价、解析与联盟访问；后台保活计入同一预算。一次业务访问不等于一个 HTTP 请求。缓存命中不额外导航，后台任务始终在现有 Chrome owner 线程串行执行。

同一平台数字商品按日监控，普通/重点/冷门统一按日运行。当天去重只使用内存布隆过滤器，不查询文件或数据库历史；键为平台+数字商品ID，按配置时区自然日轮换（默认北京时间），进程重启清空。预计10万商品、目标误判率0.1%，位数组约176KiB；误判允许漏查当天某商品，重启后可能重复采集。当日搜索/转链获取真实成功价格、或监控已尝试过（包括实际失败），都标记过滤器。调度、手动刷新与已排队任务共用同一个过滤器，下次采集分散在次日开放窗口内。

提醒保留同口径基线、outbox、邮件/广播重试及调用方隔离。默认阈值为下降至少 3% 且至少 1 元，15分钟后复核数据库候选；日频价格在24小时内有效（更长的显式配置仍可保留），覆盖夜间通知静默期，过期则等待正常日频采集。通知等待/重试不再提前触发额外抓价。通知默认关闭，须配置实际接收方及邮件/广播渠道后启用。事件与邮件只包含数字 ID、商品直达地址和价格条件，不保存推广材料。

历史接口只保留范围内每天最后一条成功且价格为正的真实快照，按日期升序返回，增加`date`（YYYY-MM-DD）；失败不会抹掉当天早先的成功价格。30/60/90天均包含今天，无记录日期不补造价格；不把商品缓存参考值伪造为走势。旧的日内多条记录保留在库中，接口聚合展示。

## 验证状态

自动化验证覆盖数字商品唯一性、缓存容量/TTL、JD 先解析再搜索、持久化材料过滤、任务归属、预算预留、跨日窗口、价格复核、投递重试和完整 Spring 启动。

2026-10-23 定向回归：`Tests run: 135, Failures: 0, Errors: 0, Skipped: 6`，即 129 项执行通过。包含真实 H2 队列的入库检查：加密输入先转换为数字，请求/结果 CLOB 均不含原材料和推广 URL；调用方仍可从进程内缓存取得临时推广结果。

构建与依赖同步：

- `mvn -o -B -pl crawler-bootstrap -am '-Dmaven.test.skip=true' package`：`BUILD SUCCESS`，产物为 `crawler-api/target/crawler-api-1.0.jar`、`crawler-bootstrap/target/crawler-bootstrap-1.0.jar`。
- API JAR 已同步至相邻 xiaofan 的 `xiaofan-fanli-bootstrap/lib/org/rx/crawler-api/1.0/crawler-api-1.0.jar`，并通过 `install:install-file` 安装到本地 Maven 仓库。
- 源 API JAR、xiaofan 副本和本地 Maven 制品 SHA256 一致：`A61411C866824607928EA070034036DAD619E5F90248715A6BBBAD6AF992BA79`。
- xiaofan 执行 `mvn -o -B -pl xiaofan-fanli-bootstrap -am '-Dmaven.test.skip=true' compile`：`BUILD SUCCESS`。

2026-10-23 本机真实 Chrome 的 JD 搜索→转链→最终 URL 数字 ID→缓存复用→数字 ID 再搜索已通过：样本 `100018097536`，联盟展示价 `39.8`。此前未识别 JD 移动 `ware/view.action` 路径的问题已修复。

TB 真实验证返回 `LOGIN_REQUIRED`，尚未完成登录后的转链跳转验证；没有把它计作通过。邮件外发和远端部署未执行。

## 部署后本机验收

`java-checks.yml` 独立运行离线回归；`java-ssh-crawler.yml` 只负责构建制品、SSH 上传、校验 SHA256、排空任务、切换版本与启动健康检查。部署 workflow 不执行单测或商品验收，不配置商品 API 验收 secret。

SSH 已改为公钥登录：Actions 使用 `D:\home\.envKeys\ssh\rx-mimic-github-anzhi-ssh.json` 对应的 `f-li.cn:5913`；本机使用 `cloud.f-li.cn:6014` 和同目录下 `anzhi/id_ed25519`，继续固定主机公钥。

部署完成后在项目根目录执行：

```powershell
python deploy/app-crawler/verify_products.py --access-file D:/home/.envKeys/rx-mimic-access.json
```

该 JSON 只在本机读取，使用其中 `baseUrl` 和 `apiKey`，不上传 GitHub。脚本每个平台选一个商品，验证转链数字身份、相同材料缓存复用、数字 ID 再转链一致性、自动关注及价格快照；只输出数字 ID、价格和固定状态码。遇到登录/人工接管或超时，取消本次测试任务并返回失败。测试会生成少量正常商品关注及价格快照，沿用现有通知开关。

默认新价格库为工作目录下 `data/product-price-db`，部署配置示例显式指定 `D:/rx-mimic/data/product-price-db`。

2026-10-23 CI [39880905721](https://github.com/RockyLOMO/rx-mimic/actions/runs/39880905721) 通过：Java 156 项、失败 0、错误 0、跳过 1。此前 SSH 中断已恢复公钥登录；曾将商品验收放入部署流程，现按要求移至本机脚本，验收结果独立于发布状态记录。
### 2026-10-23 本轮发布与本机实测

- 部署 [39896043408](https://github.com/RockyLOMO/rx-mimic/actions/runs/39896043408) 成功：`Deployment healthy: dcff7133799ed7f6c76ce068514bb08e74825c80`。发布使用 `f-li.cn:5913` 公钥认证，workflow 没有执行商品验收或单测。
- 对应独立 CI [39896041445](https://github.com/RockyLOMO/rx-mimic/actions/runs/39896041445) 成功。本机部署/验收单测 13 项通过。
- 发布结束后，本机脚本验证 JD 成功：`productId=100018097536 price=39.8`，覆盖加密材料解析、数字 ID 再转链、缓存与关注/快照入库。
- TB 本机完整验收未通过；定向重试定位为 `getTbPromotionUrl_TASK_FAILED_BUSINESS_FAILED`，即搜索后的转链任务失败。此前一次结果为非成功业务状态。未将 TB 计为通过，也未因验收失败回滚已健康发布的服务；具体转链失败原因待进一步排查。
- 原 `RX_MIMIC_SMOKE_ACCESS` GitHub 环境 secret 已移除。本机脚本支持 `--platform jd` 或 `--platform tb`，凭据只从 `--access-file` 指定文件读取。
淘宝数字 ID 不能召回时的恢复、候选上限与时间预算见 [淘宝身份恢复](tb.md#数字-id-无法召回时的身份恢复2026-10-23)。`tbIdentityMaxCandidates` 默认 3，旧标题与详情刷新共用；`tbIdentityTimeoutSeconds` 默认 90。只缓存临时材料提示，候选仍需最终 URL 与数字 ID 一致，超限不写价格。

## 商品主表与查询缓存（2026-10-23）

`price_product` 现作为全站唯一商品主表，唯一键为 `(platform, platform_product_id)`，数字 ID 保存为字符串以避免精度损失。永久保存基础资料 `basic_json`、联盟返利资料 `union_json`、主站详情 `detail_json`；空字段不覆盖旧值，联盟更新不会抹掉主站的规格和详情图。原价格表的 `product_id` 继续关联这张主表，不再另建平行商品身份表。

| 数据 | 保存位置 | 有效期与缓存键 |
| --- | --- | --- |
| 商品基础资料、返利资料、详情 | `price_product` | 永久，无过期删除 |
| 最近成功详情响应 | 同表 `detail_result_json/detail_cached_at/detail_expires_at` | 默认 6 小时；平台 + 数字商品 ID；额外检查规格/下载选项完整性 |
| 搜索缓存 | `product_search_cache` | 默认 30 分钟；平台 + keyword + type（1 联盟、2 主站）+ maxResults 等影响结果的参数 |
| 转链缓存 | `product_promotion_cache` | 默认 1 小时；商品主表主键 + 推广位；平台由商品关联确定，不按账号/profile/media 隔离 |
| 价格快照、订阅与提醒 | 原 price_snapshot/price_watch/outbox 等 | 仅保留价格业务状态，以商品主键关联 |

配置在 `application.yml`，也可用环境配置覆盖：

```yaml
app:
  product-cache:
    search-ttl-seconds: 1800
    detail-ttl-seconds: 21600
    promotion-ttl-seconds: 3600
```

公开商品任务统一在队列入口查询有效缓存，命中即产生已完成任务，不领取浏览器或预热页面。缓存过期/不存在才执行抓取。批量转链按单商品复用同一入口。价格监控及内部身份解析走实时任务，不使用公开查询缓存。

搜索结果保存的是当次成功结果快照和数字商品身份，同时补全主表；详情抓取前入队归一数字 ID，成功后还必须核对真实详情页面 `currentUrl` 的数字 ID。未解析物料不能进入主表、搜索缓存或价格记录；多个加密 ID 映射仍只在有界内存中保存。转链缓存保存平台实际给出的 `promotionToken/shortUrl/longUrl`，没有返回的形式保持 null，不额外请求或拼造口令。

失败、未登录、超时、未找到等状态保持本次含义；历史成功响应从数据库放入独立 `fallback`，不覆盖本次 `items/productInfo/promotionUrl`。过期缓存仍可用作兜底，失败不会续期或覆盖它。`fallback.cachedAt/cacheExpiresAt` 标明历史时间，`fallback.cacheHit=false`。成功命中有效缓存时顶层 `cacheHit=true`，保留原始 `createdAt`，不伪造本次采集时间。

缓存和兜底价格不会作为新观察生成价格快照或触发降价提醒。命中转链缓存仍登记商品关注，但只复用已有价格快照；后台真实刷新才更新主表及价格序列。存价格快照、转链记录或监控时，会再次要求关联商品存在且平台商品 ID 为纯数字。

新增读取接口：`GET /api/v1/products/{jd|tb}/{数字商品ID}`（与任务 API 使用相同认证），返回内部主键、平台、数字 ID 和 `basic/rebate/detail` 三组资料。主表没有过期语义；详情响应缓存的 TTL 仅控制何时重新抓取。

JSONL 产出类、写入点、outputPath 参数和默认路径已删除，旧 `crawl_fallback_record` 服务不再使用。调试 HTML/PNG、Cookie 和队列/订单数据库仍按各自用途管理。

### 本次验证与部署

- 独立 CI [39965088963](https://github.com/RockyLOMO/rx-mimic/actions/runs/39965088963) 成功：Java 219 项、失败 0、错误 0、跳过 1；本地 Python 测试 23 项通过。
- 覆盖真实 HTTP 认证→任务队列→H2→最终响应的缓存命中及超时 fallback；在无交互桌面的条件下缓存仍可立即返回。数据库关闭后重新打开，商品主表及转链缓存仍可读，身份内存丢失不导致缓存命中再次访问推广 URL。
- AnZhi 部署 [39964431653](https://github.com/RockyLOMO/rx-mimic/actions/runs/39964431653) 成功，代码版本 `224329e2293f6592c158af9c51a6eadbeeaf7be6`；live/ready 版本一致，`draining=false`。之后提交只含本地验证脚本和测试。
- 部署完成后，从本地执行 `python deploy/app-crawler/verify_products.py --access-file <凭据JSON> --tb-product-id 776371444627 --cache-check`，输出 `TB_CACHE_VERIFY_OK productId=776371444627`：重复转链、重复联盟搜索均命中数据库缓存，商品主表内部键为 3，价格 `105.00`，佣金率 `1.50%`。
- 本轮服务日志 `21:54:56 step=gateway-slider attempt=1/5`，`21:55:06 Slider verify passed ... attempt=1`，验证处理后完成查询。生产没有人为制造超时或注销登录，失败场景由本地真实 HTTP 集成测试覆盖。
- 代码中 JSONL 写入已移除；删除 AnZhi 磁盘旧 JSONL 的命令被自动审批以 `blocked by policy` 拦截，未执行删除。未提供更具体的拦截原因，旧文件仍待清理。

### 2026-10-27 调度与显示时间修复

- AnZhi 25/26 日日志每 30 秒重复 `PriceWatchScheduler -> enqueueBackground -> IDEMPOTENCY_CONFLICT`。内部旧键仅 watchId/plan；现在使用版本前缀、watchId/plan 及完整刷新请求 SHA-256，不修改公开任务接口的严格幂等校验。
- 单个监控入队异常记录可读 pauseReason，并在 5 分钟后重试；同一轮继续处理其他到期商品。任务关联增加计划序号比较，重复提交者不会取消已关联的同一任务。
- 新 JD 页面来源缺失修复见 jd.md；后台未知来源/不满足比较条件时快照明确标 `NOT_COMPARABLE`，不会伪造历史来源。
- 商品主档 GET 增加真实 `createdAt/updatedAt`；监控 GET 增加 `monitoringEnabled/lastAttemptAt/currentTaskId`。缓存参考快照 `id=0` 使用实际资料/缓存时间、`comparable=false`，不再把查询时刻冒充采集时刻。真实快照时间统一为 ISO UTC。
- 本地完整构建 288 项测试通过，包括历史短键冲突、单商品异常不阻断后续监控、同任务竞争不误取消、JD 页面来源、缓存与真实 HTTP 响应回归。

### 2026-10-27 在线恢复验证

- GitHub CI [40443008968](https://github.com/RockyLOMO/rx-mimic/actions/runs/40443008968) 与部署 [40443011666](https://github.com/RockyLOMO/rx-mimic/actions/runs/40443011666) 成功。AnZhi live/ready 均为 `e93effc9e9033d49fb6f07b5aa1fe91601d9c6c5`，draining=false。
- 部署后本地读取 API，6 个 JD 关注商品均已恢复后台采集并生成 `wlPrice` 来源快照；TB `776371444627` 生成 `105.00 / unionAnalysisPrice` 新快照。25 日的 JD `100204284610`、`100003525600` 与26日的 `100059891910` 均刷新成功。
- 从本地提交 `getPromotionUrl(keyword=100059891910, adSiteName=0, useCache=false)`，页面分支返回 `identityResolved=true / price=48.84 / priceField=finalPrice`，快照40已落库。后台同商品 `wlPrice=54.34` 属于另一价格口径，保留来源以避免混用比较。
- 部署后11:18起日志中幂等冲突为0；11:20:49已有自动滑块第4次通过记录，原验证流程仍正常执行。
- 在线补查发现 TB `786248509602` 报 `DETAIL_TAB_SLOT_UNAVAILABLE`：预加载标签被接管后清除了目标标记，遗留的非活动任务页占用第二个槽位。补充跟踪预加载及接管后原任务页的归属，详情读取仅回收这些可丢弃的非活动页；未知归属页仍保留并拒绝第三个标签。增加回归测试覆盖标记清除及接管最后一个预加载页的场景。
- Android 修复已提交 xiaofan `f4294831`：117项测试通过、Debug APK构建成功；未做真机UI验收。

部署后验证继续在本地执行，部署工作流不调用商品验收脚本：

```powershell
python deploy/app-crawler/verify_products.py --access-file <本机凭据JSON> --platform jd --price-product-id 100059891910
python deploy/app-crawler/verify_products.py --access-file <本机凭据JSON> --platform tb --price-product-id 786248509602
```

该模式每个商品只做一次 `useCache=false` 转链，核对数字身份、有效价格来源以及真实可比较快照落库；日志仅输出平台、数字ID和价格，不输出推广链接/口令。标签位补丁相关本地30项测试通过；独立CI [40444103564](https://github.com/RockyLOMO/rx-mimic/actions/runs/40444103564) Java290项、失败0、错误0、跳过1；新增验证脚本后本地Python24项通过。

标签位补丁部署 [40444106412](https://github.com/RockyLOMO/rx-mimic/actions/runs/40444106412) 成功，AnZhi live/ready 均为 `c1b27349c6a5d9cedbde6b5f402cf7474d2830c9`。部署后从本地项目依次执行上面两条命令，均返回 `PRICE_SOURCE_VERIFY_OK`：TB `786248509602 / 5.78 / unionAnalysisPrice`，JD `100059891910 / 48.84 / finalPrice`。商品快照验证的是数据库真实记录，未使用缓存参考值。

最终本地调用 `POST /price-watches/5/refresh`（遵守现有窗口、预算及缓存限制），任务1203成功，输出 `TB_MONITOR_REFRESH_OK 2026-10-27T03:32:29.476+00:00`。快照47为 `5.78 / unionAnalysisPrice / comparable=true`，最近成功与下次检查均已更新。至此现有8个关注商品（JD6个、TB2个）均有今日真实新快照、非空最近成功时间，currentTaskId为空、pauseReason为空；部署后日志没有新增幂等冲突。

### 2026-10-27 每日采集与30/60/90天走势

- 平台+数字商品ID共享内存布隆过滤器：当天有真实成功价格或已执行监控尝试时，重复手动刷新返回`DAILY_CACHED`，调度/已入队任务也跳过平台访问。实际尝试失败也不在当天重试；预算或窗口准入直接拒绝不计为已采集。商品表与价格表仅负责存储资料及走势，不参与是否抓过的判断。
- 首次转链当天已有成功快照，首次监控直接排到次日开放时间；历史关注即使还存着小时级计划，调度读取时也会纠正为次日，无需改生产数据库。
- 两个snapshots接口支持`days=30|60|90`，缺省30；范围包含今天，每天保留最后成功正价，旧日内多条记录只折叠展示，不破坏存量数据。HTTP回归覆盖默认值、三个范围、非法范围400，且不启动平台任务。
- 降价通知等待/复核只使用数据库候选，不为复核额外抓价；保留同口径比较、鲜度与通知条件。
- Android默认30天，展开/切换天数重新GET历史，不再读取主档和监控状态。移除整段监控/参考价/采集时间/重新读取按钮；卡片文案改为“转链时价格”，复制格式为`短链# 口令`。
- 验证脚本`deploy/app-crawler/verify_price_history.py`在部署完成后从本地执行，检查按日去重、默认30天、60/90范围。可选`--refresh-watch-id 7`允许一次真实刷新，再检查同进程布隆命中不产生任务/额外平台访问；不从历史表恢复布隆过滤器。部署工作流不执行该脚本。

### 2026-10-27 布隆版部署与验收

按用户最新要求，已删除查询数据库判断当天是否抓过的`priceChecked`方法；使用同一进程共享的Guava布隆过滤器，仅存平台+数字ID哈希位，不落文件、不落表、不从历史数据恢复。按北京时间自然日轮换；重启清空，允许重启后重复一次及约0.1%误判导致当天漏查。历史数据仍照常存商品/快照表，用于走势展示。

- 本地Java完整297项测试全部通过，Python24项通过；Android117项测试通过并生成Debug APK。API JAR同步安装成功。
- GitHub CI [40455028347](https://github.com/RockyLOMO/rx-mimic/actions/runs/40455028347) 成功，Java297项、失败0、错误0、跳过1。
- 部署 [40455031051](https://github.com/RockyLOMO/rx-mimic/actions/runs/40455031051) 成功，AnZhi live/ready均为`7440d668a74e686c8957fd40969d84e44c7000dd`，draining=false。
- 本地执行`python deploy/app-crawler/verify_price_history.py --access-file <本机凭据JSON> --refresh-watch-id 7`：先完成一次JD真实监控采集，再次刷新命中内存过滤器不创建任务；默认30天及30/60/90天每日数据验证输出`DAILY_HISTORY_VERIFY_OK watches=10 ranges=30,60,90 cachedRefresh=1 productVisitDelta=0`。增量0指首次采集完成之后的历史读取和重复刷新阶段。
- 内部priceRefresh任务属于调度器，不通过公开`/tasks/{id}`轮询；本地脚本已改为读取同一调用方的监控状态。部署工作流仍只部署，商品验收由本地脚本执行。
- Android代码已提交xiaofan `e09ec242`；转链文案、`短链# 口令`格式及精简走势区域已完成。APK未进行真机UI验收。

### 2026-10-28 AnZhi 每日调度只读核查

核查时间为北京时间 2026-10-28 11:26（与新加坡同为 UTC+8），线上版本 `9f424a97a3cb602110be55f98b8029007178a626`。仅读取监控、按日快照、访问预算和服务器日志，没有提交刷新任务或修改生产数据。

- 19 个监控均为 active；已到期未执行为 0，`currentTaskId` 与 `pauseReason` 均为空。最新有效快照为今天的 6 个（4 个后台刷新、2 个今日转链），昨天的 12 个，另 1 个没有有效正价快照。16 个已有有效价格的旧商品都能查到 10 月 27 日快照，昨天并非整日没有执行。
- 今天后台刷新已经成功：JD `100059891910` 06:30（54.34）、`100022774276` 08:01（26.82）、`100422229700` 10:10（109）、`100363826188` 10:12（129）。昨天转链的部分商品尚未到今天分散的计划时刻，例如 JD `100049073942` 12:55、`100228599775` 13:03、`100224812831` 14:14、`100063802465` 15:21。此时不应为“尚无今天数据”增加重复采集。
- `DailyProductBloom` 每次读取/写入前，以配置时区计算自然日；日期变化时创建全新内存过滤器。`PriceDailyHistoryTest.bloomIsPlatformScopedRotatesAtLocalMidnightAndIsEmptyAfterRestart` 已覆盖当地午夜轮换、平台隔离和重启清空。实际线上跨日新快照也证实没有被昨天的过滤器永久拦截。访问预算仍按 06:00 开始的业务日计算，两者日期语义不同。
- 当前访问预算 JD 10/100、TB 0/100，登录/滑块失败计数均为 0，没有预算或风险停机。
- 独立异常：TB `742153669744` 的最近尝试/成功时间为 10 月 27 日 22:30，今天下次为 21:28，但 30 天走势为空。服务器日志确认 `priceRefresh taskId=1516` 在 22:30:02 至 22:30:24 执行了真实页面访问及推广操作，没有对应异常日志。当前代码的“最近成功”只要求精确商品匹配与任务 SUCCESS，不要求价格为有效正值；走势读取则过滤 `price_amount>0`。因此两者可能不一致。当前只读接口未暴露被过滤的原始快照，尚不能判断该商品是页面无报价、零价还是提取失败，不将其归因于布隆过滤器。建议后续将“商品任务成功”和“有效价格采集成功”区分，保留实际无价格原因。
