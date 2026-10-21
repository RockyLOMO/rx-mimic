# 滑动验证外围工程审查记录

日期：2026-10-21（Asia/Singapore）  
状态：审查里的三项已按建议落地。自动滑动验证算法和人工兜底顺序没有改。

## 审查范围与约定

本次仅审查滑动验证外围的权限、敏感数据保护、资源管理、调试产物和测试覆盖，不涉及验证码求解、轨迹参数、坐标校准、通过率或反检测优化。

保留用户已有的自动滑动验证算法、流程入口及“先自动处理，失败后人工兜底”的顺序。不剔除自动验证，不改成全人工操作。

本文为源码审查记录。没有修改业务代码，没有访问远端或运行真实验证码；整理本文时也未重新执行测试或部署。

## 问题汇总

| 优先级 | 问题 | 影响 | 状态 |
| --- | --- | --- | --- |
| P1 | 普通调用方可查看整个服务器桌面 | 越过任务归属隔离，暴露其他任务及桌面窗口 | 已修复 |
| P2 | 日志与通知的 URL 脱敏不完整 | 查询参数、fragment 或 userinfo 中的敏感内容可能泄露 | 已修复 |
| P2 | 调试快照缺少保留期和容量上限 | 敏感产物长期积累，并可能耗尽同盘数据库空间 | 已修复 |

## P1：桌面截图接口缺少管理员权限隔离

代码位置：

- [DebugViewController.java](../../crawler-bootstrap/src/main/java/org/rx/crawler/controller/DebugViewController.java)，第 17–19 行。
- [TaskApiAuthenticationFilter.java](../../crawler-bootstrap/src/main/java/org/rx/crawler/config/TaskApiAuthenticationFilter.java)，第 43–50 行。

`GET /api/v1/debug/desktop` 返回整个服务器桌面。当前过滤器只确认 Bearer key 属于某个已配置调用方，接口本身没有管理员权限或任务归属检查。

因此，拥有普通业务 key 的调用方也可能查看其他任务的已登录页面、人工登录界面，以及服务器上的其他窗口。现有 `Cache-Control: no-store` 只能控制缓存，不能补足访问权限。

建议：

1. 为桌面查看增加独立管理员权限，普通业务 key 不授予此能力。
2. 默认限制在受控管理入口访问，公网业务入口不直接提供全桌面查看。
3. 保留诊断用途，记录访问审计；不因权限调整改变自动验证流程。

建议回归：无 key 拒绝、普通调用方拒绝、管理员授权通过；确认业务任务接口的原有权限不受影响。

## P2：URL 脱敏存在遗漏

代码位置：

- [SliderVerifyHandler.java](../../crawler-bootstrap/src/main/java/org/rx/crawler/task/tb/SliderVerifyHandler.java)，`safeUrl`，第 552–565 行。
- [MailLoginNotificationService.java](../../crawler-bootstrap/src/main/java/org/rx/crawler/task/common/MailLoginNotificationService.java)，`redactChallengeUrl`，第 233 行起。

当前仅在地址包含 `x5sec`、`punish` 或 `_____tmd_____` 时移除查询参数。普通 `/captcha?token=...` 地址会原样保留，截断到固定长度也不能保证去除敏感参数。URL 的 fragment 与 userinfo 同样没有统一脱敏。

日志和邮件分别维护相似规则，容易出现处理差异。该问题属于信息保护，不涉及验证算法。

建议：

1. 日志与通知共用 URL 脱敏工具，使用结构化 URL 解析。
2. 默认去除 userinfo、查询串和 fragment；确需保留的业务参数采用白名单。
3. 审查异常信息输出路径，避免未经处理的原始 URL 从异常消息再次进入日志。

建议回归：普通 captcha URL、现有特殊地址、仅 fragment 的地址、带 userinfo 的地址，以及空值和非法 URL；同时验证日志与通知不会输出测试敏感串。

## P2：调试快照没有保留期和容量预算

代码位置：

- [DebugSnapshots.java](../../crawler-bootstrap/src/main/java/org/rx/crawler/task/common/DebugSnapshots.java)，第 37–44 行、第 61–66 行。
- 平台任务的调试记录器还会写入 HTML，与视口及全桌面 PNG 一起保存。

开启 debug 后，任务步骤和验证过程持续写入调试产物。审查项目源码和部署脚本，未找到对应的快照清理机制。数据库结果的保留期不等于这些文件的保留期。

如果外部没有另设清理任务，长期运行会持续积累包含登录页面、业务数据及桌面内容的文件；磁盘耗尽还可能影响同盘数据库和后续任务。未检查远端是否另行配置了清理机制，因此不能据此断言远端当前已发生磁盘问题。

建议：

1. 为调试产物独立配置保留期、总容量预算及磁盘余量阈值。
2. 限制调试目录访问权限；达到预算时优先停止新增调试产物并告警，避免影响业务结果写入。
3. 清理仅针对明确归属的调试文件，避开正在写入的任务目录；不得误删 profile、数据库或业务结果。

建议回归：过期与容量淘汰、运行中目录保护、写入失败，以及清理边界；确认业务数据和登录态不受影响。

## 已确认的资源管理措施

[WindowsMouse.java](../../crawler-bootstrap/src/main/java/org/rx/crawler/service/impl/WindowsMouse.java) 第 95–99 行已有 `finally`：仍处于按下状态时尝试释放按钮，并清理页面探针。

因此，不能把此前通用点击中“缺少 finally 释放”的结论直接套用到这里。该结论仅确认清理路径存在，不代表已验证所有原生输入失败场景。

## 验证记录与限制

审查时执行：

```powershell
mvn -B -pl crawler-bootstrap -am `
  '-DskipTests=false' '-Dmaven.test.skip=false' `
  '-Dsurefire.failIfNoSpecifiedTests=false' `
  '-Dtest=LoginNotificationServiceTest' test --no-transfer-progress
```

结果：`Tests run: 4, Failures: 0, Errors: 0, Skipped: 0`，`BUILD SUCCESS`。

这些测试使用 mock 邮件发送，未实际发送通知。通过的测试不能证明上述三个问题已解决；桌面接口权限、完整 URL 脱敏及快照保留策略尚缺针对性回归覆盖。

以上仅为审查与建议，未实施修复、未部署。平台业务行为仍见 [淘宝说明](../tb.md)，通用接入与运维见 [文档汇总](../README.md)。
