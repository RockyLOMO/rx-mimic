# 京东转链登录误报与任务取消验证（2026-10-29）

## 远端定位（只读，无部署）

- AnZhi #2137：2026-10-29 12:18 已完成联盟生成操作，随后结果边界打开生成短链，要求零售站点登录，最终 USER_TIMEOUT。
- AnZhi #2144：12:39:57 已读生成弹窗口令，随后重复打开输出短链，停在 pro.m.jd.com 活动中转页，12:40:11 身份解析失败。
- 两者并非联盟尚未生成链接，而是生成之后的额外身份检查使成功结果丢失。

## 修改范围

- 数字 SKU 经原始输入解析、联盟商品链接严格匹配该 SKU、商品信息及短链/口令完整时，在当前 profile 的有界缓存中绑定本次生成链接与 SKU；结果边界复用关系，不重复访问零售页。关键词、信息缺失、域名或 SKU 不符不绑定。登录页 returnUrl 不作为身份证据。
- 运行中 cancel 设置任务专属信号；owner 线程在浏览器操作及等待边界退出，安全清理 lease，队列置 CANCELLED。当前同步导航等操作仍受自身超时约束；不跨线程关闭 Chrome、不强停 owner，不改滑块算法。
- 取消信号不会进入其它任务线程，cleanup 可临时屏蔽信号；结果归一化及任务完成后检查取消状态，避免继续登记价格/订单结果。

## 验证

- Maven 定向回归：99 项通过，0 失败/错误（队列、取消作用域、身份缓存/边界、京东结果校验、fast 模式及真实 Chrome fixture）。
- 最终取消竞态护栏补充后，队列及取消测试再次运行：45 项通过，0 失败/错误。
- 真实本机 Chrome、本地响应 fixture：完整商品、短链、口令及数字身份返回；零次访问零售站；5 秒等待期间取消信号在 2 秒内退出，随后浏览器仍可使用。未对生产商品伪造结果。
- crawler-bootstrap package：BUILD SUCCESS；crawler-api 已构建并同步安装至相邻 xiaofan。
- 测试日志保存在任务临时目录 jd-login-cancel（final-backend-tests.log、cancel-final-tests.log、backend-package.log），未写入密钥。

App `:app:testDebugUnitTest :app:compileDebugKotlin`：132 项测试通过、0 失败/错误/跳过，Kotlin 编译通过。覆盖取消接受/拒绝、同幂等键找回提交、WAITING_USER恢复、超时状态及取消意图持久化。App 取消和人工重试说明见 xiaofan/docs/android-cashback.md。源代码尚未推送或部署，AnZhi 与手机当前安装版本尚未更新。
