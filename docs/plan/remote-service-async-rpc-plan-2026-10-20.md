# rx-mimic 远程服务部署与异步 RPC 实施计划

日期：2026-10-20（Asia/Singapore）  
状态：本地实现与52项测试通过；已部署 AnZhi `D:\rx-mimic`，FRP/Nginx公网接入生效，京东人工登录恢复与淘宝联盟搜索随机商品详情已实测。CI自动发布与调用方迁移尚未执行，详见接入记录。

接入与执行记录：[远程异步任务接入任务](../README.md)。

## 1. 目标与建议

将 `cloud.f-li.cn:6014` SSH 对应的 Windows 主机作为 rx-mimic 后续统一运行与部署目标。首次部署验证通过后，日常构建、发布、回滚通过 GitHub Actions 完成。

**对外首选 HTTPS + JSON 异步任务 API：提交任务 → 返回 taskId → 查询状态/结果。** TB/JD 页面操作、翻页、登录接管可能持续数分钟，不能让浏览器任务生命周期依赖某个 HTTP/TCP 连接。请求超时、客户端断线不会丢失已经确认接收的任务。

复用现有持久化队列，第一期不增加 Redis、RabbitMQ、Kafka，也不以 gRPC 长流或 WebSocket 作为结果交付的唯一依据。已有 rxlib Remoting 如有调用方则保留在受控网络，并逐步统一成相同的 taskId 契约。此处“RPC”指远程调用能力，不要求绑定某种二进制协议。

本项目按浏览器业务服务实施，不因底层依赖 Netty 就改造成网络框架项目。当前主要瓶颈是浏览器执行和人工处理，优先保证正确性、持久性与操作环境一致性。

## 2. 本次已核实的事实

### 2.1 远端只读检查

| 项目 | 2026-10-20 检查结果 |
| --- | --- |
| SSH | 已使用指定账户成功连接；第二次完整检查远端退出码 0 |
| 系统 | Windows NT 10.0.19044.0 |
| 运行账户 | `anzhi-pc\anzhi` |
| 桌面 | `AnZhi` console 会话为 Active |
| Java | Temurin OpenJDK 21.0.12.1；可通过 PATH 调用 |
| Chrome | `C:\Program Files\Google\Chrome\Application\chrome.exe`，版本 155.0.8059.85 |
| 其他工具 | PATH 可找到 Maven、Git；未执行 Maven 构建 |
| 内存 | 总内存约 7.9 GiB，检查时空闲约 4.1 GiB |
| 磁盘 | C 盘空闲约 85 GiB，D 盘约 113 GiB |
| 目录 | `C:\app-crawler`、`D:\app-crawler`、`D:\projs_r\rx-mimic` 均不存在 |
| 默认 profile | `C:\Users\AnZhi\AppData\Local\rx-mimic\chrome\common` 不存在 |
| 现有运行项 | 检查未返回 81/443/6400/8505/8506 的监听项，未找到名字含 crawler/javabg 的计划任务；此结果不代表全部端口或全部部署目录均已排查 |

SSH 信息已新建于本机 `D:\home\.envKeys\ssh.json`，结构为 `schemaVersion + connections[]`，连接名 `rx-mimic`。文件包含用户指定的登录密码；禁用 ACL 继承并为当前 Windows 用户设置完全控制。密码不写入本计划、仓库、日志或构建产物。

本次连接观察到 SSH 主机公钥指纹 `SHA256:zm9KGOp8b2IRbTjDmlIpMLqNJBGlMszeIZCEvm1omLc`。这是首次连接采集值，尚未通过其他可信渠道核对；正式发布应核对并固定 known_hosts。

### 2.2 仓库现状与缺口

下列路径均相对仓库根目录。

| 位置 | 已有能力 / 待处理问题 |
| --- | --- |
| `pom.xml`、`crawler-bootstrap/pom.xml` | Java 21、Spring Boot 3、Playwright、H2；测试默认跳过，需要显式开启 |
| `controller/JdMallController.java`、`controller/TbMallController.java`（位于 bootstrap Java 包） | 商城搜索/详情已有提交 taskId、查询、取消排队任务；当前没有显式 HTTP 202 |
| `controller/CustomCrawlController.java` | 联盟搜索、推广链接、订单等仍调用 submitAndWait；默认等待上限 600 秒，等待结束不等于后台任务终止 |
| 上述 TB 两个 Controller | 都声明 `POST /custom/tb/product/detail`，存在重复映射风险；应首先用上下文启动验证并消除冲突 |
| `task/common/CustomCrawlQueueService.java` | 已持久化请求/结果/状态，有条件领取、定时调度；启动将 RUNNING 重置为 PENDING，可能重复执行 |
| 同上 | 正常返回业务结果就可能被队列标记 SUCCESS，即使业务结果是 LOGIN_REQUIRED、TIMEOUT、FAILED；取消当前用 FAILED + cancelled 表示 |
| `config/CookieJarConfig.java` | 使用 `EntityDatabase.DEFAULT`；必须实测真实数据库文件位置，不能仅凭 H2 依赖推断落盘目录 |
| `task/common/CustomCrawlRemotingService.java` | rxlib TCP Remoting 直接执行任务、发送事件，不经过同一持久队列，断线事件不是可靠结果存储 |
| `CustomCrawlRemotingContract` | 包含 cookiesRaw、closeProfile 等管理能力，应与外部业务能力隔离 |
| `application.yml` | HTTP 81、Remoting 1221；队列默认并发 1；common profile、非 headless |
| `.github/workflows/java-ssh-crawler.yml` | 已有 Maven→SSH 发布雏形，但依赖旧目录、旧计划任务、写死健康入口，不能直接使用 |
| `deploy/app-crawler/start.bat`、`rollback.bat` | 使用 `taskkill /im java.exe`，会影响其他 Java 进程；必须先改为仅操作本服务 |

代码事实与历史 `docs/README.md` 有不一致处，实施以源码和运行验证为准。JD/TB 抓取行为文档仍分别维护在 `docs/jd.md`、`docs/tb.md`。

## 3. 远程运行与发布设计

### 3.1 单机有界面运行

- 继续使用运行机的真实 Chrome、`channel=chrome`、持久 profile、开启沙箱、`headless=false`，遵循仓库 AGENTS.md。
- Chrome 初期只使用一个 `common` profile，所有使用该 profile 的 TB/JD 任务、登录保活、人工接管串行协调。全局执行并发从 1 开始。
- Playwright 对象由固定工作线程创建和操作；仅设置线程池大小为 1 不足以证明所有调用都线程安全，保活、关闭、接管也要走相同调度入口。
- 使用 Windows 计划任务运行 Java，选择“仅当用户登录时运行”，账户为 AnZhi，使用 InteractiveToken。SSH 发布只触发该任务，不直接在 SSH 非交互会话中启动 Chrome。
- 维护可用的桌面登录及授权远程接管方式；首次由人工完成 TB/JD 登录。登录/验证码进入人工等待，程序只等待恢复。
- 无活动桌面时浏览器执行 readiness 不通过、停止领取任务并告警；不能用 Session 0 服务或默认 headless 替代。分别验证锁屏、断开远程桌面、重新登录和重启后的行为。

建议运行根目录为 `D:\rx-mimic`，实施前确认归属和可写性：

```text
D:\rx-mimic\
  releases\<commit-sha>\app.jar     # 不可变发布包及 SHA256、版本清单
  config\application-prod.yml       # 外置配置，密钥不随包分发
  data\                            # 固定数据库及结果目录，实际 DB 路径需验证
  profiles\common\                 # 显式指定到受支持的 profile 配置
  logs\
  run\                             # PID、当前版本、发布锁
  scripts\                         # 启停、健康检查、回滚
```

先按现有配置项实现固定 profile/数据路径，若 EntityDatabase 没有外置路径能力则补配置。工作目录固定到运行根，避免版本切换产生新数据库。初期 JVM 堆上限建议 512 MiB 起测，再结合 Chrome RSS 调整，不能将 JVM 堆上限当作整机内存上限。

### 3.2 GitHub Actions 发布

推荐 GitHub 托管 runner 构建，通过 SSH/SFTP 上传同一制品到目标机；远端无需承担日常 Maven 构建。暂不把保有登录态的浏览器主机注册为 self-hosted runner。

计划流程：

1. PR / push 执行编译与不需要登录态的测试；依赖解析须覆盖 rxlib SNAPSHOT 的来源与可用性。
2. 首期仅 `workflow_dispatch` 发布指定提交；流程稳定后再决定是否打开 main 自动发布。使用 concurrency 避免同时发布，禁止取消正在切换版本的发布。
3. 精确选择 `crawler-bootstrap` 可执行 JAR，生成 SHA256、commit SHA、构建时间；固定第三方 action 到审查过的 commit。
4. SSH 凭据存 GitHub 环境 secrets，优先独立部署密钥。账户授权、known_hosts、端口与远端脚本保持一致；不提交本机 ssh.json，也不把密码插入 shell 命令。
5. 上传到新版本暂存目录，校验哈希；上传或校验失败不影响当前版本。
6. 服务进入 draining：拒绝新提交但允许查任务，停止领取；等待当前任务结束。超出可配置发布窗口则取消本次切换并恢复服务，不直接杀掉正在人工接管的任务。
7. 定向停止本服务 PID，核对进程命令行/版本归属；确保所属 Chrome/profile 已安全释放，再切换版本。禁止按 java.exe 全局杀进程。
8. 启动交互式计划任务，检查 liveness、readiness、版本 SHA、队列数据库可读写。查询不存在 taskId 不能替代健康接口。
9. 新版本未通过健康检查时，回滚应用版本并再次验活；workflow 最终仍标为失败，记录回滚结果。

数据/profile 不随版本覆盖。数据库变更优先扩展迁移，但本轮新增任务状态无法被历史旧枚举识别，首发前必须停机备份；仅允许在识别新状态的兼容构建之间回滚。不兼容迁移单独安排备份/恢复和维护窗口，不能假设回滚 JAR 就能回滚数据库。H2 备份采用其支持的备份方式或停机复制，不能直接复制正在写入的数据库文件。

### 3.3 外部入口

入口方案固定为复用 rssClient 的 `https://cloud.f-li.cn:6400/crawler/api/v1`；Nginx 转至本机 STCP visitor `127.0.0.1:6020`，通过现有 frps 与 AnZhi frpc 到 `127.0.0.1:81`。配置模板位于 `deploy/network/`；独立publisher/visitor与Nginx已上线，不重启原AnZhi FRP。

2026-10-20 重新只读核实：AnZhi 使用 `D:\home\.app\frp\anzhi\anzhi-frpc.toml`，连接 `cloud.f-li.cn:4000`；现有 SSH `22→6014`、桌面 `5900→6017`。rssClient `192.168.31.4` 的 frps 和 Nginx 正常，`nginx -t` 通过；rssServer 保持现有主线路职责。不得把 AnZhi 当作本地 LAN 直连主机。

按用户要求不启用 `jmx_prometheus_config.yaml`，启动/回滚不得携带 JMX Prometheus javaagent，不开放 exporter 8080 端口。监控先使用应用健康、任务状态与日志，不另起监控监听。

- 公网只暴露经鉴权的 HTTPS 业务入口。应用绑定回环/受控网卡，1221 Remoting 与管理接口仅内网/VPN 或关闭。
- 首期调用方使用独立 API key，经 TLS 传输；按调用方限制频率、积压数、任务和结果访问权限，密钥可轮换。
- 反向代理只需支持短请求；不能把连接超时改成几十分钟作为异步改造方案。
- 订单、截图、Cookie、profile 属于敏感数据；下载接口检查任务所有权，不对外返回本机绝对路径或原始 Cookie。

## 4. 异步契约与可靠性

### 4.1 传输方案比较

| 方案 | 适配度 | 决策 |
| --- | --- | --- |
| HTTPS JSON：提交 + 轮询 | 跨语言、便于代理/鉴权，复用现有队列 | 第一期采用 |
| SSE | 适合界面显示进度，但断线需补查 | 第二期按需增加，查询为权威来源 |
| Webhook | 适合服务器间推送，需要签名/重试/去重 | 有实际调用方需求再增加 |
| gRPC unary | 可同样实现 submit/get，但需新增协议与接入成本 | 暂不引入；后续强类型多语言调用量大时复评 |
| rxlib Remoting 事件 | Java 客户端可复用，当前不等于可靠异步 | 受控网络兼容，接入同一队列 |
| MQ | 有利于多机分发，单 profile 仍串行 | 多机、多账号扩展时再引入 |

### 4.2 建议新增 v1 接口（尚未实现）

沿用实际 `Result` 包装：`success/code/message/data`，不另起 `msg` 字段。使用新版本接口，避免直接把旧同步接口返回类型改掉造成调用方不兼容。

| 接口 | 语义 |
| --- | --- |
| `POST /api/v1/tasks` | 校验 action 与对应参数；事务落库后返回 HTTP 202 + taskId；带 Location、Retry-After |
| `GET /api/v1/tasks/{taskId}` | HTTP 200 返回状态、阶段、时间、业务状态；轻量查询不重复传大结果 |
| `GET /api/v1/tasks/{taskId}/result` | 完成返回业务结果；未完成 409 TASK_NOT_FINISHED；过期 410；不存在或无权访问按统一规则返回 404 |
| `POST /api/v1/tasks/{taskId}/cancel` | 排队中可取消；运行中记录取消请求，在安全点处理，重复请求幂等 |
| `POST /api/v1/tasks/{taskId}/resume` | 仅允许所有者恢复 WAITING_USER，必须重新检查真实登录态，不能直接标记成功 |
| `GET /health/live`、`GET /health/ready` | 分别检查进程存活、队列/桌面/浏览器调度可用性，返回版本；细节限管理访问 |

示例提交：

```http
POST /api/v1/tasks
Authorization: Bearer <caller-api-key>
Idempotency-Key: <caller-generated-key>
Content-Type: application/json

{"action":"jdProductDetail","request":{"skuId":"100059484008","downloadImages":false}}
```

```http
HTTP/1.1 202 Accepted
Location: /api/v1/tasks/123
Retry-After: 2
Content-Type: application/json

{"success":true,"code":"SUCCESS","message":"","data":{"taskId":"123","status":"PENDING","statusUrl":"/api/v1/tasks/123"}}
```

v1 taskId 使用字符串避免 JavaScript 大整数精度问题；数据库可继续使用 bigint。对外 action 建白名单，对每一种 request 做 jakarta.validation 校验，拒绝任意类名反射执行。所有 TB/JD 搜索、详情、联盟搜索、转链、订单与批量任务都要覆盖，保持平台结果 DTO。

### 4.3 状态与执行规则

- 执行状态拟定 `PENDING / RUNNING / WAITING_USER / CANCEL_REQUESTED / SUCCEEDED / FAILED / CANCELLED / TIMED_OUT`；结果保留期到期用 410 表达。旧接口通过适配层保持既有状态兼容。
- 执行状态与业务状态分开存储：业务 `LOGIN_REQUIRED` 在等待期内对应 WAITING_USER；业务失败不得显示为成功。`NOT_FOUND` 等正常查询结论可执行成功但业务无结果，按 action 定义映射表。
- 进度用实际阶段，例如预检、打开页面、等待人工、抓取第 N 页；总页数未知时不伪造百分比。
- 默认轮询 2 秒，逐步退避至 5 秒并加抖动，遵循 Retry-After；客户端等待到期仅停止等待，显式 cancel 才请求终止任务。
- 分开配置入队等待期限、执行期限、人工处理期限、结果保留期；先测量各 action 的耗时分位数再定生产值。启动建议：排队 30 分钟、详情/搜索执行 10 分钟、订单执行 30 分钟、人工等待 10 分钟、结果保留 7 天，均为待压测调整值。
- 人工等待期间保持 profile 所有权，暂停该 profile 后续任务；到期安全结束并释放。进程重启后不能假定原 Page 能恢复，应重新检查环境后决定重放或失败。
- 批量任务拆成可追踪子任务，支持部分成功和逐项结果，限制最大批量数，避免一个大任务长期占队列。
- 队列容量初始建议 100，按调用方设额外配额；超限立即 429 + Retry-After。提交确认必须以数据库持久化成功为前提。
- 幂等键按调用方 + action + key 建唯一约束，同时记录请求摘要；相同参数返回同一 taskId，参数变化返回 409。显式重试通过新请求关联原任务，不偷偷创建重复任务。
- 初期维持单服务实例与单 profile；固定 owner 线程串行调度，数据库条件领取防重复。后续多实例必须增加租约/心跳/所有权并发校验，不能同时打开同一个 H2 文件或 Chrome profile。
- 修正启动无条件 RUNNING→PENDING：仅确定可安全重放的只读任务自动重试；转链等存在副作用的步骤结果不确定时标记失败并携带可重试性/原因，人工核查后重试。明确提供至少一次执行及去重策略，不承诺 exactly-once。
- 运行中取消采用协作式安全点；不得跨线程直接操作 Playwright 对象或杀共享 Chrome。未达到安全点前仍显示 CANCEL_REQUESTED，不能提前宣告已取消。

若后续增加 Webhook，使用持久 outbox、事件 ID、签名、投递重试和接收端去重；回调地址设白名单防止向任意内网地址发请求。Webhook/SSE 通知失败不能改变任务的真实执行结果。

## 5. 实施任务与顺序

本轮已获“生成接入任务文档、执行计划”授权。按以下阶段执行并逐项记录证据；未勾选项不得视为完成。首次真实平台登录依赖人工接管，GitHub secrets 使用受限入口设置。

| 阶段 | 任务 | 完成标准 |
| --- | --- | --- |
| P0 环境基线 | 固定 SSH 指纹；核实外网入口/端口/TLS、账户权限、桌面接管、运行目录、Apollo/外部依赖及配置来源 | 配置清单可复现；密钥仅外置；桌面启动验证通过 |
| P0 启动与发布风险 | 验证并消除 TB 重复路由；替换全局 kill 脚本；建立独立计划任务与健康接口 | Spring 上下文能启动；不影响其他 Java 进程；无桌面不领取浏览器任务 |
| P1 队列与 v1 | 全部动作走统一队列，补幂等/容量/状态/期限/取消/恢复/鉴权与归属 | 短请求入队后断线，仍可凭同一 taskId 找回结果 |
| P1 数据与线程 | 固定数据库/profile路径；保证浏览器 owner 线程；改崩溃恢复，验证保活与任务互斥 | 重启不丢已确认任务；无 profile 双开和跨线程错误 |
| P2 手工首发 | 构建不可变包，远端创建目录与交互计划任务，人工登录 TB/JD，部署 smoke test | 本地制品哈希=远端；服务版本、健康、双平台任务可验证 |
| P2 自动发布 | 修改 Actions、登记 secrets/known_hosts、drain、健康检查、失败回滚 | 连续发布、失败回滚及服务重启演练通过 |
| P3 接入与运营 | Java 客户端异步封装；现有调用方迁移；按需增加通知 | 调用方明确区分提交成功、业务完成、人工等待和等待超时 |

建议改动位置：`crawler-api` 新增通用任务 DTO/契约；`crawler-bootstrap` 的 controller、队列 service、remoting 适配、profile 调度、健康与鉴权；`deploy/app-crawler` 发布脚本；现有 `.github/workflows/java-ssh-crawler.yml`。不要新建第二套浏览器执行实现。

代码或 pom 改动后，按仓库 AGENTS.md 构建 crawler-api 并同步到相邻 xiaofan，执行其规定的 install-file 步骤；先检查目标是否存在及版本状态。本轮代码改动须执行该联动并记录构建及安装结果。

## 6. 验证与验收清单

- [x] 本机 ssh.json 可解析、包含必需连接字段；输出只验证密码是否存在，不输出密码。
- [x] ssh.json 禁用 ACL 继承并为当前用户授权；文件在仓库外。
- [x] SSH 成功登录，远端只读环境探测完成；完整探测退出码 0。
- [x] 阅读现有队列、HTTP/RPC、部署 workflow 与脚本；本轮进入实现。
- [x] 最小构建：`mvn -B -pl crawler-bootstrap -am -DskipTests package`。
- [x] 52项定向测试显式 `-DskipTests=false -Dmaven.test.skip=false`，覆盖路由上下文、并发重复提交、队列满、状态映射、过期、取消与鉴权隔离。
- [ ] 真实持久数据库测试：提交后重启、领取后崩溃、完成入库后响应丢失，检查数据及幂等结果。
- [ ] 远端有界面集成测试：JD/TB 各至少一条长任务；包含断开客户端后取回结果、人工登录后继续，以及人工等待到期。
- [ ] 现有 Chrome profile、登录工具、服务、保活互斥验证；确认锁屏/重启场景符合运行约束。
- [ ] 发布演练：上传失败、制品哈希错误、健康失败、当前任务未结束、回滚失败均有明确状态，不误报成功。
- [ ] 暂定服务目标：可用且未过载时提交 P95 < 1 秒（不含执行）；状态查询 P95 < 300ms；长任务不占请求连接。实测后调整并记录样本、环境与耗时。
- [ ] 监控：各 action 排队/执行 P50/P95/P99、积压数量与最老任务、业务失败率、人工等待时长、重试/取消、JVM heap/堆外及 direct buffer、Chrome 进程树 RSS、profile/结果/日志磁盘占用、版本与回滚结果。

原计划阶段未执行编译、部署或业务验收；本轮实际验证与剩余事项详见接入任务文档，不以设计说明代替验收。

## 7. 实施前需落实的配置项

默认方案已可推进开发；以下属于上线依赖，不影响先完成实现和本地验证：

1. 接入使用现有 cloud.f-li.cn:6400 TLS 入口和 STCP，不新增公网业务/JMX端口；部署前检查 visitor 6020 空闲、现有 Nginx server 块与 FRP user 命名。
2. 允许访问的调用方及初始 API key；是否需要在 xiaofan 等现有调用方保留 Remoting 兼容。
3. GitHub 仓库/环境权限、部署 secret 与密钥配置，以及 rxlib SNAPSHOT 的构建可获得性。
4. 远端 AnZhi 的长期桌面会话维护、登录接管方式及首次 TB/JD 人工授权。
5. 外部配置服务是否沿用、实际数据库位置、数据保留与备份预算。

## 8. 官方资料依据

- [Microsoft：Asynchronous Request-Reply](https://learn.microsoft.com/en-us/azure/architecture/patterns/async-request-reply)：提交返回 202，用 Location 指向状态资源，Retry-After 控制轮询；本方案据此结合现有队列设计。
- [Playwright Java：Multithreading](https://playwright.dev/java/docs/multithreading)：Playwright 及其派生对象不是线程安全对象；本方案使用固定工作线程与 profile 串行协调。
- [GitHub：Secure use reference](https://docs.github.com/en/actions/reference/security/secure-use)：self-hosted runner 的代码执行和凭据暴露需隔离；本方案优先在托管 runner 构建，部署主机只运行审核后的服务制品。
- [GitHub：Deployments and environments](https://docs.github.com/en/actions/reference/workflows-and-actions/deployments-and-environments)：部署环境与环境 secrets 可用于区分发布权限；实际可用能力以仓库类型及套餐为准。
