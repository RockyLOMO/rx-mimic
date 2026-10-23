# 数字商品 ID 与降价跟踪

更新：2026-10-23。新模型直接生效，不迁移旧价格数据，不保留联盟加密 ID 别名表或旧字段兼容层。

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
| `POST /price-watches/{id}/refresh` | 受预算约束的手动刷新 |
| `GET /price-watches/{id}/snapshots` | 价格观察历史 |
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

新订阅默认普通层：10 小时间隔；重点 4 小时、冷门 48 小时，窗口外顺延，不能在重启后集中补跑。当前版本按分层间隔运行；计划中的按活动日历加权和变价时段统计属于后续优化，不以“20 点必定改价”为前提。

提醒保留同口径基线、降价复核、outbox、邮件/广播重试及调用方隔离。默认阈值为下降至少 3% 且至少 1 元，复核延迟 15 分钟，发送前要求 30 分钟内的新鲜快照。通知默认关闭，须配置实际接收方及邮件/广播渠道后启用。事件与邮件只包含数字 ID、商品直达地址和价格条件，不保存推广材料。

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
