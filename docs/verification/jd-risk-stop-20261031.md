# AnZhi 京东转链 RISK_STOP 排查（2026-10-31）

首轮只读检查远端服务、日志及已有任务 API；以下首轮诊断完成后，用户授权“处理下”，执行结果见末尾“授权处理结果”。

## 结论

当前重试失败由 rx-mimic 持久化风险熔断引起，不是失败商品结果缓存，也不是同一失败任务反复读取。用户描述为 riskinfo，实际抽查任务 errorCode 均为 RISK_STOP；不能据此把上游 riskinfo 与本次错误视为同一响应。

## 现场证据（UTC+08:00）

- 远端日志 D:\rx-mimic\logs\rx-mimic_info.log：06:27:34.821，PriceRiskGate 警告 platform=jd,count=3,limit=3,day=2026-10-31；随后08:04、08:24、08:44、09:01、09:19继续增加，09:19:23.971为count=8。
- 重发前任务2612、2618、2620均为FAILED/RISK_STOP。
- 重发后任务2624、2625、2626、2627分别于09:44:44、09:44:52、09:44:54、09:45:07创建，均为不同新任务，开始至失败约10–26毫秒，结果均为FAILED/RISK_STOP。
- /health/ready实查ready=true；远端run/current.json和本地HEAD均为b111e72ab1085c9dbdf2d7e0b5ac9414b3fca7e8。故不能归因于重发未切换到当前版本。
- 09:49、09:53、09:56日志显示后台京东订单流程继续执行，商品身份解析路径的风险门与服务整体可用性是不同条件。未读取订单业务结果，不能据此宣称全部登录/订单功能验收通过。

## 代码对应

- task/price/PriceRiskGate.java：record按平台及业务日累加；stopped查询当日持久计数；没有登录成功后的恢复/重置逻辑。
- task/price/PriceWatchStore.java：incrementRisk/riskCount存取price_visit_counter.risk_count，重发保留数据库，因此计数继续有效。
- task/price/ProductIdentityResolver.java:211：导航前risk.stopped即抛RISK_STOP，尚未进入本次商品页面。
- task/price/ProductCacheService.java:66：非SUCCESS不写商品成功缓存。
- config/AppConfig.java：默认阈值3，业务日从06:00起；task/price/PriceWatchClock.java按业务日隔离计数。次日06:00只是默认恢复边界，本次未完整核对运行时动态窗口配置。

## 首轮恢复建议与边界

立即恢复应先确认同一common profile的京东会话可用，再有针对性地解除当天京东风险熔断并提交新任务；不能只重发、反复登录或清空商品缓存。更长期应设计明确的人工恢复入口，在会话验证通过后恢复，并让App提示RISK_STOP的含义；需保留后台防重复失败保护。以上均未实施，真实转链恢复未验证。

## 授权处理结果

- 最终后端版本：`49aa59a750a643b1a7d4ab56bf21ba775d865a3c`；基础提交 `b111e72ab1085c9dbdf2d7e0b5ac9414b3fca7e8`，`sourceKind=working-tree`。源码摘要 SHA-256：`49aa59a750a643b1a7d4ab56bf21ba775d865a3c19cdb994426e070187ebeb38`；完整 JAR SHA-256：`e1b77b5889047b1609b773908bc90ce9ceffd5dc8b4d4b6e9352d79543b7c384`。源码快照 `source-snapshot.zip` 保留在同一远端 release 目录。没有提交或推送源码；后续 GitHub 发布须包含当前修复，不能将此标识当作 Git 提交号。
- 内部恢复端点仅允许回环+管理员密钥，先经 owner 队列验证京东推广工作台和会话，再 CAS 清除当前京东风险。保留访问预算、其他平台、未知风险及历史计数。新的安全固定中文消息可用于公开失败结果；App 的 FAILED 轮询路径也补同码映射。
- 首个候选版 `6af8485` 正向验证未通过，未清风险。现场检查发现原 `loginCheck` 打开“效果报表”页，而正向条件需要推广工作台；最终版改到已有配置的工作台入口，再验证通过，没有放宽为单纯“无登录框”。
- 2026-10-31 10:19:41（UTC+08:00）审计日志确认：`Product visit risk recovered after verified login, platform=jd, previousCount=8, day=2026-10-31`。管理端返回 `loginVerified=true/previousRiskCount=8/riskCount=0`。
- 实网新任务 #2646 `jdUnionSearchProducts` 与 #2647 `getPromotionUrl` 均 `SUCCEEDED/SUCCESS`，`cacheHit=false`；商品 `100018097536`，`identityResolved=true/hasShortUrl=true/hasToken=true`。没有重放旧失败任务或采用旧转链缓存；未在手机端操作原失败卡片，需用户显式点重试。
- 最终健康检查：live version 与 current.json 一致；`ready=true/draining=false/runningTasks=0`，browser/desktop/database/scheduler 均可用。

## 验证与交付边界

- JDK 21，Maven 最终 `package`：89 tests，0 failures/errors/skipped。包含风险 CAS、平台/日期隔离、保护预算、管理员认证、失败脱敏、真实本机 Chrome 的工作台/缺 Cookie/空白/riskinfo/验证遮罩夹具。首次测试因 Mockito 重设抛异常 stub 的测试写法报 1 error，修正后通过；中间编译发现静态方法引用实例 helper，修正后最终构建通过。
- Android `:app:testDebugUnitTest --tests org.rx.xiaofan.android.data.model.CashbackStatusDisplayTest`：5 tests，0 failures/errors/skipped，Kotlin/Java 编译通过。未打包、发布或安装 APK，手机仍是原有提示；服务端恢复不要求更新 APK。
- crawler-api 已构建，同步并安装到当前实际的 `xiaofan-fanli-bootstrap/lib/org/rx/crawler-api/1.0/crawler-api-1.0.jar`，两份 SHA-256 一致。AGENTS 中旧 `jar` lifecycle 命令无效且旧 xiaofan-bootstrap/lib 路径已不存在，因此改用标准 package 构建及当前 fanli 模块依赖路径。旧 API JAR 备份在本次任务临时目录。
- 本地证据：`%TEMP%/ai/20261031/rx-mimic-riskinfo/`，重点 `recovery-workbench-final.log`、`deploy-workbench-recovery.log`、`recover-workbench-live.log`、`live-probe-summary.json`。来源快照不含运维密钥文件。

## 备份与回滚

- 最终切换前已 drain、优雅停服，冷备份 `D:\rx-mimic\backups\risk-recovery-20261031-101848`：配置、current.json，以及 root-browser-task-db.mv.db（20,480 bytes）、data-browser-task-db.mv.db（19,767,296 bytes）、data-product-price-db.mv.db（6,344,704 bytes）。首次候选部署的备份也保留于 `risk-recovery-20261031-101449`。没有数据库结构变更。
- 旧稳定包 `b111e72ab1085c9dbdf2d7e0b5ac9414b3fca7e8` 和中间候选包保留；可按现有 service.ps1 的 Deploy 流程切回明确版本，不应假定 previous.json 仍指向最初稳定包。未执行回滚。
- 本次解除计数是独立的数据更新，切回旧 JAR 不会将 0 自动恢复为 8。不要在线覆盖数据库；冷备份回放会丢弃备份后的任务、订单等更新，须另外评估后执行。
- 全包上传因链路较慢停止了本任务自己的上传进程，运行服务未受影响。随后复用服务器未变 ZIP 压缩数据，仅上传差量并重建完整 JAR，双端 SHA-256 一致后才切换；最终差量 183,595 bytes，复用 342,577,571 bytes。未安装新工具。
