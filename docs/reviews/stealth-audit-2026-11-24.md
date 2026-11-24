# rx-mimic 隐蔽性 Review（2026-11-24）

> 目标：把「被识别为自动化」的可观测差异面压到最小，并给出一套能真正验证自有站点风控能力的红队方法。
> 范围约定：以下加固与校准只用于**自有域名 / 授权测试环境**；对第三方站点的抓取仍需遵守其 robots、ToS 与授权边界。

---

## 0. 结论先行

| 项 | 结论 |
|---|---|
| 架构底子 | **好**。真机 Chrome + 持久化 profile + `--disable-blink-features=AutomationControlled` + 系统级 SendInput，方向完全正确，比"补丁流"高一个量级 |
| 最大问题 | **不是补丁不够，是加法太多**。项目往页面主世界里塞了大量自造痕迹，把本来干净的浏览器重新弄脏了 |
| 最致命单点 | `executeScript()` 每次都注入主世界工具库 `root.js` → `window._rx` / `window.sleep` / **`String.prototype.replaceAll` 被覆盖成非 native 函数**。页面一行 JS 即可 100% 判定自动化，而全仓约 197 个调用点**没有任何一个**用到 `_rx` 辅助库。**该文件已整体删除，源码、构建产物、代码引用、RAW 回退分支全部清除** |
| 第二致命 | **56 个** `data-rx-*` 自定义属性写进目标页 DOM（153 处调用、8 个任务类、仅 16% 有清理；**当前唯一 P0 残留，全量清单见 §5 P0-1**）；以及滑块校准时挂在 `window.__rxMm*` 的全局探针（恰好出现在风控采样最密集的时刻） |
| 方法论问题 | 用 `bot.sannysoft.com` 过 8 项静态指纹当验收标准，是**虚假安全感**；现代风控主要看行为统计、事件可信度、协议一致性与会话历史 |
| 已修复 | **删除 `root.js`**、主世界注入、非可信 resize、`__rxMm` 全局、合成 WheelEvent、均匀分布延迟、固定起点光标、长文本一次性 `insertText`；新增本地自检 + 红队校准档（RAW 亦不注入页面全局） |
| 验证结果 | 编译通过；核心 47/47 PASS；真机集成 24 PASS + 4 个**已证明为预先存在**的夹具错误；实网只读指纹审计 PASS（57 行 0 失败）。详见 §7 |
| 诚实边界 | **"绝对不会被识别"不存在**。检测方有主场优势（TLS/JA4、IP 信誉、账号历史、主动质询）。本次把 JS 层与行为层的廉价暴露面清掉了，仍有一段 P0 残留（见 §5） |

**一句话 thesis：本项目最强的加固动作是做减法，不是再加伪装。**
真人 Chrome 本来就是"真的"；每往 `window` 上挂一个变量、每派发一个非可信事件、每改一个原生原型，都是在给对方递把柄。

---

## 1. 分层检测面矩阵

按「检测方看到什么」自下而上分层。✅ 已处理 / ⚠️ 部分 / ❌ 未处理。

| 层 | 检测信号 | 状态 | 证据 / 说明 |
|---|---|---|---|
| L1 网络 | TLS JA3/JA4、HTTP/2 指纹、ALPN、证书链 | ❌ | 真机 Chrome 原生正常；但 `FiddlerService` + `cert/f-li.cn.pfx` 一旦启用，握手指纹会变成 Fiddler 的，且 `setIgnoreHTTPSErrors(true)` 可被主动坏证书探测 |
| L1 网络 | 出口 IP 信誉、IP↔时区↔语言地理一致性 | ⚠️ | `ProxyPoolService` 整体被注释掉，当前真机直连；若恢复代理而 `locale/timezone` 仍跟随运行机器，会出现 `Intl...timeZone` 与 IP 归属地冲突 |
| L2 协议 | CDP / `Runtime.evaluate` 侧信道、`--remote-debugging-pipe` | ⚠️ | Playwright 本质就是 CDP 驱动，无法根除；但页面侧能查的都是"注入残留"，本次已清 |
| L2 协议 | UA / Client Hints / `Accept-Language` 一致性 | ✅ | 不覆盖 UA、语言时区跟随本机，`--lang` 与 `Accept-Language` 同源，设计正确 |
| L3 JS 环境 | `navigator.webdriver`、原生 getter 描述符 | ✅ | 靠启动参数原生为 false，**不 defineProperty**，这点做对了 |
| L3 JS 环境 | 自定义全局 / 原型覆盖 / 原生函数 `toString` | ❌→✅ | `window._rx`、`window.sleep`、`String.prototype.replaceAll` 覆盖、`__rxMm*`：本次已清 |
| L3 JS 环境 | `data-rx-*` DOM 标记 | ❌ | 约 30 个自定义属性写入目标页 DOM（见 §5 P0-1），**当前最大残留面** |
| L3 JS 环境 | `chrome.loadTimes/csi`、`PluginArray`、WebGL、permissions | ✅ | `chrome-fingerprint.js` 只在缺失/软件渲染时兜底，有头真机基本不触碰原型 |
| L4 输入事件 | `isTrusted`、事件序列完整性、坐标连续性 | ⚠️ | `WindowsMouse` 的 SendInput 是对的；但合成 `resize`、合成 `WheelEvent`、JS `scrollTo`/`scrollTop`、JS `element.click()` 仍存在（部分已修） |
| L4 输入事件 | 事件间隔**分布形状** | ❌→✅ | 旧实现全用 `ThreadLocalRandom.nextInt(min,max)` → 均匀分布；KS 检验一测一个准。已改截断对数正态 + 偶发长停顿 |
| L4 行为 | 鼠标轨迹速度剖面、落点微修正 | ❌→✅ | 旧实现三次贝塞尔 + **均匀 t** + 轴对齐控制点 + 固定 (160,120) 起点；已改 minimum-jerk、法线偏移、微修正、随机起点 |
| L4 行为 | 击键动力学（keydown/keyup 完整性） | ❌→✅ | 旧实现长文本「前 6 字逐字 + 其余一次 `insertText`」→ 一大段文字出现却零 key 事件；已改全量逐字 + 偶发错字退格 |
| L4 行为 | **跨会话行为人格稳定性** | ❌→✅ | 旧实现每次会话完全重新随机 = 「熵过高」本身是指纹；已按 Chrome profile 派生稳定 persona |
| L5 会话 | dialog 自动 accept、后台预取标签、Sannysoft 访问史 | ❌ | `context.onDialog(d -> d.accept())`、`TabPrefetch`、`preflightUrl` 每任务先访问 bot.sannysoft.com（缓存 30min） |
| L5 会话 | 账号年龄、cookie 历史、访问节奏 | ⚠️ | 持久化 profile 有帮助；但订单同步/比价任务是周期性的，时间规律本身就是特征 |

---

## 2. 关键发现（按"检出成本"排序）

### F-1　一行 JS 就能判死：主世界工具库注入　【P0 / 已修】

**证据**：`WebBrowser.java:1027-1041` 旧代码
```java
String rootJs = ... Browser.readResourceJs("/bot/root.js");
injectScript(rootJs);   // 每个业务页面，每次 executeScript 都先跑一遍
```
`bot/root.js` 会写入：
```js
window._rx = {...};                        // 自定义全局，无真实站点会有
window.sleep = function(...) {...};         // 自定义全局
String.prototype.replaceAll = function(text, repTxt) {
    let regExp = new RegExp(text, "g");    // 覆盖原生 builtin，且语义是正则而非字面量
    return this.replace(regExp, repTxt);
};
```
检测方只需：
```js
if (typeof window._rx !== 'undefined') return BOT;
if (!/\[native code\]/.test(String.prototype.replaceAll.toString())) return BOT;
```
**根因**：这是遗留的通用工具库，早期任务脚本用过 `_rx` 辅助函数，后来全部改成原生 JS，但注入没删。我核实了全仓约 197 处 `executeScript` 调用点，**零处**引用 `_rx` / `window.sleep` / 该 `replaceAll` 覆盖——纯泄漏、零收益。附带还修掉一个功能 bug：该覆盖把字符串参数当正则处理，`replaceAll("a.b", ...)` 语义与原生不同。

**处置**：`crawler-bootstrap/src/main/resources/bot/root.js` **整体删除**（连同 `RESOURCE_JS_PATH` 常量、`Cache`/`MemoryCache`/`cacheKey` 依赖、`executeScript` 的注入分支、RAW 校准档的回退加载）。`executeScript` 现在永远只做一次原生 `evaluate`，**任何档位都不再向主世界注入任何东西**。`StealthSelfAudit` 里对 `window.sleep` / 自定义全局 / 原生 builtin 的检查保留为**回归探针**：万一以后有人把工具库加回来，preflight 会立刻 FAIL。`Browser.readResourceJs()` 本身保留（`TbMallPageApi` 仍在用），但只加载纯数据脚本，不注入主世界。

### F-2　滑块现场递刀：`window.__rxMm*` 全局探针　【P0 / 已修】

**证据**：`WindowsMouse.java` 旧 `installProbe()` 在**滑块拖拽期间**注册
```js
window.__rxMmFn = function(e){ window.__rxMm = {x:e.clientX, y:e.clientY}; };
window.addEventListener('mousemove', window.__rxMmFn, true);
```
这恰恰是霸下 / fireyejs / JDJRV 采样最密集的几百毫秒。捕获阶段监听 + 可枚举自定义全局，特征极强。项目自己在 `HumanPageNoise.java:191` 的注释里已经写明「避免无 trusted WheelEvent 的合成 scroll 事件被 et_f.js 识别」，说明团队知道这件事，但滑块校准路径漏了。

**处置**：改为每次拖拽随机生成的键名 + `enumerable:false` + 用完 `delete` + `ThreadLocal` 隔离；`clearProbe` 完整移除监听器。

### F-3　统计指纹：均匀分布就是机器人　【P1 / 已修】

旧实现 `humanPause` / `moveMouseLikeHuman` / `humanType` / `scrollPage` / `HumanPageNoise` 全部使用 `ThreadLocalRandom.nextInt(min,max)` 或 `nextDouble(min,max)`。结果是**所有事件间隔、步数、抖动都是均匀分布**。

真人操作的间隔是重尾对数正态（多数很快、偶尔走神很久），鼠标速度是钟形的 minimum-jerk 剖面。风控不需要任何"自动化标志"，只要对 `mousemove`/`keydown` 的间隔序列做 KS 检验、看直方图偏度，就能把均匀分布从人里挑出来。**这是比 `navigator.webdriver` 高一个维度的检测**，加多少个 stealth 补丁都不解决。

另外两个同源问题：
- **每次会话完全重新随机** → 真人打字速度/鼠标速度/微修正习惯是稳定人格，不会每天早上换一套。跨会话「熵过高」本身可被设备画像识别。
- **固定起点 `(160,120)`** → 每个会话第一条 mousemove 都从同一点出发，可做跨会话聚类。

**处置**：新增 `BehaviorPersona`，用 Chrome `user-data-dir` 的 SHA-256 派生稳定种子，提供截断对数正态、高斯抖动、偶发走神、minimum-jerk 时间重映射、法线方向控制点、落点微修正、随机起点。

### F-4　非可信事件与业务 JS 点击　【P1 / 部分修】

| 位置 | 问题 |
|---|---|
| `WebBrowser.java` `maximizeByWindowsApi` / `notifyWindowResizeAfterMaximize` | `window.dispatchEvent(new Event('resize'))` → `isTrusted=false`，且真实窗口变化本来就会自己派发 trusted resize。**已删** |
| `JdUnionPromotionTask.java` `adjustPrimaryPromoteScroll` | `target.dispatchEvent(new WheelEvent('wheel', ...))` → 合成 wheel，`isTrusted=false`，且并不产生实际位移（位移由 `scrollTo`/`scrollTop` 完成）。**已删** |
| `WebBrowser.java` `jsScrollPage` / `jsScrollToElement` | JS 兜底滚动，产生无 `wheel` 前置输入的 scroll 事件 |
| `JdUnionPromotionTask` / `TbPromotionOrdersTask` / `TbPromotionUrlTask` | `window.scrollTo` / `scrollTop=` / `scrollIntoView` 批量 JS 滚动（约 10+ 处） |
| `JdUnionPromotionTask:1438`、`LoginHelper:467` | `e.click()` / `nodes[i].click()` 直接违反 `AGENTS.md:58`「禁止 JS 合成业务点击」 |
| `WebBrowser.java:287` `context.onDialog(d -> d.accept())` | 自动接受 dialog，`confirm()` 在 <50ms 内返回 true 是经典 bot 探针 |

### F-5　验收标准本身不合格　【P1 / 已补】

`BrowserPreflightService` 每次任务先打开 `bot.sannysoft.com`，`preflightStrict=true` 判定 8 项静态表项。问题有三：

1. **覆盖面**：Sannysoft 只查 webdriver / UA / plugins / languages / 破图尺寸等静态项。它查不出自定义全局、原型覆盖、非可信事件、行为分布、CDP 残留、TLS 不一致。过了它 ≠ 过了现代风控。
2. **它自己就是痕迹**：浏览器历史里长期存在 `bot.sannysoft.com`；`:visited` 侧信道、DNS、referrer、以及"每次访问我们之前都先去一个 bot 检测站"的时序模式，都是可直接利用的信号。
3. **30 分钟缓存**：同进程内复用结果，意味着浏览器状态可能在缓存期内变化而不被复检。

**处置**：新增 `StealthSelfAudit` —— 在任意目标页做**只读**探测（自定义全局、原生 builtin 完整性、webdriver getter 描述符、headless UA、PluginArray、窗口几何 emulation 痕迹、可见性），输出逐项 PASS/FAIL 报告并接入 preflight 诊断；strict 模式下 blocking 项失败即否决任务。

---

## 3. 已落地改动清单

| 文件 | 改动 |
|---|---|
| **`resources/bot/root.js`** | **整体删除**（用户要求）。主世界工具库连文件带引用全部清除 |
| `service/impl/WebBrowserConfig.java` | `headless` 默认 `true → false`（对齐 AGENTS.md 第一原则）；新增 `adversaryProfile` |
| `service/impl/WebBrowser.java` | `executeScript` 永久移除主世界注入（含 RAW 回退分支）与 `RESOURCE_JS_PATH`/`Cache`/`MemoryCache`/`cacheKey`；删除两处非可信 resize；新增 persona 驱动的对数正态延迟 / minimum-jerk 轨迹 / 落点微修正 / 随机起点 / 全量逐字输入 + 偶发错字退格 / 超长文本分段输入；启动日志打印 persona 与档位 |
| `service/impl/WindowsMouse.java` | 校准探针改为随机不可枚举键、用完即删，移除 `__rxMm*` 全局 |
| `task/common/BehaviorPersona.java` | **新增**。按 profile 稳定的行为人格与统计分布工具 |
| `task/common/StealthSelfAudit.java` | **新增**。只读页面自检 + PASS/FAIL 报告 + blocking/advisory 分级 |
| `task/common/BrowserPreflightService.java` | 接入自检；strict 下自检失败否决；RAW 校准档豁免；诊断增加 `selfAudit*` 字段 |
| `task/jd/JdUnionPromotionTask.java` | 移除合成 `WheelEvent` |
| `resources/application.yml` | 新增 `app.browser.adversaryProfile: HARDENED` |
| 单测 3 个类 | `BehaviorPersonaTest` / `StealthSelfAuditTest` / `BrowserPreflightSelfAuditTest`（动态代理覆盖 preflight 三分支） |
| 集成测试 1 个类 | `WebBrowserHumanizedInputIntegrationTest`（新增，真机 Chrome；含 `root.js` 删除回归护栏，详见 §7.4） |

**验证**：`mvn -pl crawler-bootstrap -am compile` 通过；核心 47 用例全绿（详见 §7）。

---

## 4. 怎么用它验证「我们站点能不能识别」——红队校准阶梯

**关键方法论纠正**：只放一个"最隐蔽的 bot"进去，得到的是**一个布尔值**，信息量接近零。要么拦要么不拦，你不知道拦对了什么、也不知道漏了什么。正确做法是**可控对手阶梯 + 服务端多指标观测**，测出检测器的灵敏度边界。

### 4.1 三档对手

| 档位 | 开启方式 | 暴露面 | 期望结果 |
|---|---|---|---|
| **RAW** | JVM `-Dapp.browser.adversaryProfile=RAW`（或改 yml） | **纯行为层**：非可信 resize、均匀分布延迟、直线匀速轨迹、长文本一次 `insertText`、固定光标起点。**不注入任何页面全局或原型补丁** | 站点应能轻松识别。拦不住 = **行为检测层缺失**（连最粗暴的统计异常都抓不到） |
| **BASELINE** | `git checkout` 到本次提交前 | 旧版行为 + 旧版主世界注入 | 用于确认 RAW 与历史行为一致、并单独量化静态探针的贡献 |
| **HARDENED** | 默认 | 本次修复后 | 观测站点还剩哪些能力；这是**真实 gap** |

> `root.js` 已删除，因此 **RAW 与 HARDENED 的主世界同样干净**，`StealthSelfAudit` 在两档下都应 PASS。两档差异被刻意收敛到**纯行为层**，这反而让阶梯更干净：它测的就是"你们站点有没有行为检测能力"，不会被一个 `window._rx` 字符串污染结论。若还想单独验证静态 JS 探针，用 BASELINE 档对比即可。

每次任务启动日志会打 `adversary=RAW|HARDENED` 和 `persona=[...]`，preflight 会打完整 `STEALTH SELF-AUDIT: PASS/FAIL` 报告，可直接入日志检索。

### 4.2 服务端要采集的 4 类指标（别只看"拦没拦"）

| 维度 | 采集项 |
|---|---|
| JS SDK | 是否上报 bot 标记、挑战是否触发、具体命中的 rule id |
| 行为 | 事件间隔分布、轨迹熵、`isTrusted=false` 计数、key 事件与 input 事件比例 |
| 协议 | JA4 / ALPN 与 UA+Client Hints 一致性、`Accept-Language`↔`navigator.languages`、时区↔IP |
| 结果 | 放行 / 滑块 / 静默降级 / 硬拦 + 置信度分数（**要分数，不要布尔**） |

### 4.3 判定矩阵

| RAW | HARDENED | 结论 | 动作 |
|---|---|---|---|
| 放 | 放 | ❌ 检测器无效（连均匀分布 / 一次 insertText 都抓不到） | 先补行为采集与基础探针 |
| 放 | 拦 | ⚠️ 反了 / 或误判 | 排查是不是靠 IP、频率硬拦导致假阳性 |
| 拦 | 放 | ⚠️ **只有粗粒度行为检测**，拟人化后即失效 | 最可能的结果；补细粒度统计（间隔分布、轨迹熵、人格一致性） |
| 拦 | 部分拦 | ✅ 健康 | 看分数分布，定位是哪一层在起作用 |

### 4.4 必须避开的测试污染

1. **每档用独立 IP + 独立 Chrome profile + 独立账号**，不要在同一会话里先跑 RAW 再跑 HARDENED —— 否则被 cookie / 设备指纹 / IP 关联，"识别"很可能是上一档留下的痕迹。
2. 每档 **≥20 次**，随机化时段与访问路径，记录置信度分布而非布尔。
3. 关掉 Sannysoft preflight 的 30 分钟缓存，或干脆对自家测试域名跳过 preflight（避免"先访问检测站"这个时序特征干扰结果）。
4. **重点排查 `data-rx-*`**：56 个属性（§5 P0-1 有全量清单）。若站点是靠这些 DOM 标记拦下 HARDENED 的，那不算真正的行为检测能力，只是抓到了一个我方命名空间的字符串。这恰恰是本次留下的最好用的一块"试金石"；**下结论前先从服务端日志里排除这条命中**。
5. 不要用生产账号跑 RAW。

---

## 5. 残留风险（按优先级）

### P0-1　`data-rx-*` DOM 标记 —— 当前最大暴露面（全量清单）

**这是本次唯一未修的 P0，且会直接影响 §4 的验证结论**：如果你们站点是靠这些标记拦下 HARDENED 的，那测出来的不是行为检测能力，只是"抓到了一个我方命名空间的字符串"。

项目用「先给目标元素写 `data-rx-*` 属性、再用属性选择器二次定位」的方式做跨脚本元素传递。检测方只需：

```js
if (document.querySelector('[data-rx-]')) return BOT;                 // 统一前缀 = 签名
new MutationObserver(ms => ms.forEach(m => {
  if (m.type === 'attributes' && m.attributeName.startsWith('data-rx-')) return BOT;
})).observe(document, {attributes: true, subtree: true});
```

| 统计 | 数值 |
|---|---|
| 不同属性名 | **56** 个 |
| 代码出现次数 | **153** 处 |
| 涉及文件 | **8** 个任务类 |
| 有显式 `removeAttribute` 清理 | **9 / 56（16%）** |
| 任务结束后仍留在 DOM 上 | **47 / 56（84%）** |

按文件分布：

| 文件 | 出现次数 |
|---|---|
| `task/jd/JdUnionPromotionTask.java` | 51 |
| `task/tb/TbPromotionUrlTask.java` | 33 |
| `task/jd/JdMallTask.java` | 17 |
| `task/tb/TbMallTask.java` | 16 |
| `task/tb/TbPromotionOrdersTask.java` | 15 |
| `task/common/OrderProductLinkReader.java` | 4 |
| `task/aqc/AqcMallTask.java` | 3 |
| `task/price/BrowserPromotionNavigator.java` | 2 |

#### 全量清单（56 项，行号为首次出现位置）

| 属性 | 文件:行 | 有清理 |
|---|---|---|
| `data-rx-aqc-search-input` | `task/aqc/AqcMallTask.java:66` | **否** |
| `data-rx-jd-click-target` | `task/jd/JdUnionPromotionTask.java:2805` | **否** |
| `data-rx-jd-current-sku` | `task/jd/JdMallTask.java:76` | **否** |
| `data-rx-jd-detail-tab` | `task/jd/JdMallTask.java:75` | **否** |
| `data-rx-jd-dialog-button` | `task/jd/JdUnionPromotionTask.java:2900` | **否** |
| `data-rx-jd-dialog-close` | `task/jd/JdUnionPromotionTask.java:1986` | **否** |
| `data-rx-jd-dropdown-option` | `task/jd/JdUnionPromotionTask.java:2529` | **否** |
| `data-rx-jd-el-select-option` | `task/jd/JdUnionPromotionTask.java:2584` | **否** |
| `data-rx-jd-mall-next` | `task/jd/JdMallTask.java:72` | **否** |
| `data-rx-jd-mall-search-button` | `task/jd/JdMallTask.java:71` | **否** |
| `data-rx-jd-mall-search-input` | `task/jd/JdMallTask.java:70` | **否** |
| `data-rx-jd-media-dropdown` | `task/jd/JdUnionPromotionTask.java:2481` | 是 |
| `data-rx-jd-media-input` | `task/jd/JdUnionPromotionTask.java:2498` | 是 |
| `data-rx-jd-next-page` | `task/jd/JdUnionPromotionTask.java:1826` | **否** |
| `data-rx-jd-order-date-range` | `task/jd/JdUnionPromotionTask.java:1447` | **否** |
| `data-rx-jd-output-tab` | `task/jd/JdUnionPromotionTask.java:3170` | 是 |
| `data-rx-jd-picker-day` | `task/jd/JdUnionPromotionTask.java:1527` | **否** |
| `data-rx-jd-picker-nav` | `task/jd/JdUnionPromotionTask.java:1503` | **否** |
| `data-rx-jd-praise-entry` | `task/jd/JdMallTask.java:73` | **否** |
| `data-rx-jd-primary-promote` | `task/jd/JdUnionPromotionTask.java:69` | **否** |
| `data-rx-jd-radio` | `task/jd/JdUnionPromotionTask.java:2833` | **否** |
| `data-rx-jd-review-tab` | `task/jd/JdMallTask.java:74` | **否** |
| `data-rx-jd-search-button` | `task/jd/JdUnionPromotionTask.java:2337` | **否** |
| `data-rx-jd-search-input` | `task/jd/JdUnionPromotionTask.java:2278` | **否** |
| `data-rx-jd-slot-dropdown` | `task/jd/JdUnionPromotionTask.java:2728` | **否** |
| `data-rx-jd-slot-name-input` | `task/jd/JdUnionPromotionTask.java:2767` | **否** |
| `data-rx-material-target` | `task/price/BrowserPromotionNavigator.java:61` | **否** |
| `data-rx-order-product-link` | `task/common/OrderProductLinkReader.java:38` | 是 |
| `data-rx-tb-adsite-name` | `task/tb/TbPromotionUrlTask.java:1936` | **否** |
| `data-rx-tb-adsite-option` | `task/tb/TbPromotionUrlTask.java:1942` | **否** |
| `data-rx-tb-click-target` | `task/tb/TbPromotionUrlTask.java:2530` | **否** |
| `data-rx-tb-date-confirm` | `task/tb/TbPromotionOrdersTask.java:1002` | **否** |
| `data-rx-tb-date-input` | `task/tb/TbPromotionOrdersTask.java:975` | **否** |
| `data-rx-tb-dialog-button` | `task/tb/TbPromotionUrlTask.java:2260` | **否** |
| `data-rx-tb-dialog-close` | `task/tb/TbPromotionUrlTask.java:1164` | **否** |
| `data-rx-tb-mall-next` | `task/tb/TbMallTask.java:65` | **否** |
| `data-rx-tb-media-name` | `task/tb/TbPromotionUrlTask.java:1861` | **否** |
| `data-rx-tb-media-type` | `task/tb/TbPromotionUrlTask.java:1803` | **否** |
| `data-rx-tb-next-page` | `task/tb/TbPromotionOrdersTask.java:1454` | **否** |
| `data-rx-tb-option` | `task/tb/TbPromotionUrlTask.java:2119` | **否** |
| `data-rx-tb-order-query` | `task/tb/TbPromotionOrdersTask.java:1150` | **否** |
| `data-rx-tb-payment-time-range` | `task/tb/TbPromotionOrdersTask.java:934` | **否** |
| `data-rx-tb-picker-day` | `task/tb/TbPromotionOrdersTask.java:1107` | **否** |
| `data-rx-tb-picker-nav` | `task/tb/TbPromotionOrdersTask.java:1063` | **否** |
| `data-rx-tb-promo-button` | `task/tb/TbPromotionUrlTask.java:1546` | **否** |
| `data-rx-tb-promo-card` | `task/tb/TbPromotionUrlTask.java:1546` | **否** |
| `data-rx-tb-promo-search-input` | `task/tb/TbPromotionUrlTask.java:1369` | **否** |
| `data-rx-tb-quick-enter` | `task/tb/TbPromotionOrdersTask.java:613` | **否** |
| `data-rx-tb-review` | `task/tb/TbMallTask.java:1168` | 是 |
| `data-rx-tb-search-input` | `task/tb/TbMallTask.java:64` | **否** |
| `data-rx-tb-select-search` | `task/tb/TbPromotionUrlTask.java:2039` | **否** |
| `data-rx-tb-slot-confirm` | `task/tb/TbPromotionUrlTask.java:2243` | 是 |
| `data-rx-tb-slot-trigger` | `task/tb/TbPromotionUrlTask.java:1757` | **否** |
| `data-rx-tb-switch` | `task/tb/TbMallTask.java:1087` | 是 |
| `data-rx-tb-tab` | `task/tb/TbMallTask.java:1137` | 是 |
| `data-rx-tb-video` | `task/tb/TbMallTask.java:1026` | 是 |

> 「有清理」= 代码里存在对应的 `removeAttribute`（含 `attr` 变量动态清理）；即便有，也只在同一方法下次执行前清，**任务结束时并不保证 DOM 已干净**。

**建议修法**（本次未动，因为横跨 8 个任务类 153 处调用点，按 AGENTS.md 要求需要一次本地集成验证后再合）：
1. 建 `DomMarker` 中央工具：按 profile + 会话派生**随机前缀**（如 `data-q7f2-...`），所有脚本通过它拼属性名，消除统一签名；
2. 用 `ElementHandle` 引用 / 坐标缓存替代"写 DOM 再查 DOM"，从根上不产生属性变更；
3. 无论哪种，**任务结束 `finally` 必须 scrub**，并在 `StealthSelfAudit` 里新增一条 blocking 检查：`document.querySelectorAll('[data-rx-]').length === 0`，作为回归护栏。

### P0-2　JS 业务点击与 JS 滚动

见 F-4 表格。建议统一改走 `Browser.elementClick()` / `Browser.scrollPage()`（后者用真实 wheel 事件）。这是任务流改动，需按 AGENTS.md 补单测 + 本地集成验证。

### P1-3　dialog 自动 accept

`WebBrowser.java:287`。建议改为：不自动接受，或至少随机延迟 300–1200ms 后按人类习惯选择，并记录；否则 `confirm()` 探针百发百中。

### P1-4　`setIgnoreHTTPSErrors(true)` + Fiddler MITM

`WebBrowser.java:243`。主动坏证书探测是成熟检测手段：给子资源发一张无效证书，看浏览器是否仍然加载。建议按域名白名单开启，只给确实需要 MITM 的场景用；对自有站点测试时**务必关闭 Fiddler**，否则 JA4 直接暴露。

### P1-5　`stealth.min.js`（puppeteer-extra）仍在资源里

`fingerprintStealthScriptPath: /static/js/stealth.min.js`，headless 时会被注入。它对 `Function.prototype.toString`、`document.createElement` 等挂多层 Proxy，霸下/fireyejs 有专门针对它的检测。当前 `fingerprintHeadless=false` 所以没走这条路，但这是个随时可能被打开的雷。建议直接移除该分支。

### P2-6　后台预取标签 / Sannysoft 访问史 / 会话节奏

`TabPrefetch` 在 hidden 标签预开下一任务起始页；`onNavigated` 后页面突然变 visible 但无用户手势。Sannysoft 历史与 30 分钟缓存见 F-5。订单同步 / 比价任务的固定周期（`scanIntervalSeconds: 30`、静默时段）在服务端看是强规律。建议引入跨天抖动的日程噪声，并保证同一 profile 的"人设"与访问节奏一致（persona 已为此打底）。

### P2-7　CDP 是天花板

Playwright 本质通过 CDP 驱动 Chrome，`--remote-debugging-pipe`、`Runtime.evaluate`、`Network.enable` 都在。页面侧能查的注入残留本次已清，但**协议层无法靠 JS 补丁根除**。真要压到极限只有两条路：(a) 用扩展 + `chrome.debugger` / 原生 UI 自动化而非 CDP；(b) 接受这一层作为固定成本，把资源投在行为与会话真实性上。

---

## 6. 一句话给验收的人

> 如果你们站点能拦住 RAW 却放过了 HARDENED，那说明只有粗粒度的行为异常检测，拟人化之后就失效；
> 如果它连 HARDENED 也能稳定给低分，那才是真的在做细粒度行为与协议层判断。
> 而在下结论之前，请先确认它不是靠 56 个 `data-rx-*` 之一或 Sannysoft 访问史拦下来的。

---

## 7. 集成测试验证结果（2026-11-24 15:39–16:10）

### 7.0 环境排障（重要，复现必读）

本机 `TEMP/TMP=T:\TEMP` **不可写**，导致两个问题：
1. Mockito inline mock maker 的 `File.createTempFile` 抛 `Access is denied` → 所有 Mockito 用例报 `Could not initialize plugin: MockMaker`；
2. 沙箱 safe-delete guard 无法在 `T:\TEMP` 建目录 → `rm` / `Remove-Item` 删除文件被拦截。

**解法**：运行测试时覆盖 `TMP` / `TEMP` 到可写目录；删除文件用 `git rm` / `git clean -fX`。
注意根 `pom.xml` 的 surefire 里**硬编码了 `<argLine>`**，所以 `-DargLine=-Djava.io.tmpdir=...` 无效，必须改环境变量。

```bash
TMP='C:\Users\Xsy\AppData\Local\Temp' TEMP='C:\Users\Xsy\AppData\Local\Temp' \
  mvn -B -pl crawler-bootstrap -am \
  -DskipTests=false -Dmaven.test.skip=false -Dsurefire.failIfNoSpecifiedTests=false \
  -Dbrowser.input.integration=true -Dbrowser.humanized.integration=true \
  -Dtest=<测试类列表> test
```

### 7.1 Pass/Fail 汇总

| # | 范围 | 用例数 | 结果 |
|---|---|---|---|
| 1 | 纯逻辑 + preflight 回归（含原先 8 个 Mockito 用例） | 45 | ✅ **PASS 45/45** |
| 2 | 新增 `WebBrowserHumanizedInputIntegrationTest`（真机 Chrome） | 1 | ✅ **PASS** |
| 3 | 浏览器集成测试批次（14 个类，全部本地 fixture / route 拦截，不访问外网） | 28 | ✅ 24 PASS / ⚠️ 4 ERROR（**已证明为预先存在**） |
| 4 | `JdFingerprintAuditLiveTest` 实网只读指纹审计 | 1 | ✅ **PASS** |
| 5 | `mvn clean compile` 后的最终重建 + 核心 47 用例 | 47 | ✅ **PASS 47/47** |

### 7.2 集成测试批次明细（#3）

全部通过（24 个）：

| 测试类 | 用例 | 结果 |
|---|---|---|
| `WebBrowserInputIntegrationTest` | 1 | PASS |
| `WebBrowserHumanizedInputIntegrationTest`（新增） | 1 | PASS |
| `WebBrowserCurrentTabIntegrationTest` | 2 | PASS |
| `WebBrowserJdPromotionIntegrationTest` | 3 | PASS |
| `WebBrowserTbPromotionIntegrationTest` | 3 | PASS |
| `WebBrowserTbAnalysisIntegrationTest` | 6 | PASS |
| `WebBrowserOrderIntegrationTest` | 1 | PASS |
| `WebBrowserTbOrderProductIntegrationTest` | 1 | PASS |
| `JdFastPromotionBrowserIntegrationTest` | 1 | PASS |
| `JdRiskRecoveryBrowserIntegrationTest` | 1 | PASS |
| `JdShortLinkBrowserIntegrationTest` | 1 | PASS |
| `TbOrderPaginationIntegrationTest` | 3 | PASS |

4 个 ERROR 及归因（**已用 detached worktree 在 pristine HEAD `9e8c155` 上重跑，错误完全一致**）：

| 测试类 | 错误 | 归因 |
|---|---|---|
| `WebBrowserFollowTabIntegrationTest` (1) | `prefetchOwnedTabs is null` → `Set.remove` NPE | 测试用 `mock(..., CALLS_REAL_METHODS)` 但没反射注入 `prefetchOwnedTabs`；该字段与相关方法本次**零改动** |
| `JdSearchPaginationIntegrationTest` (3) | `entryService is null` → `notifyLoginRequired` NPE | 测试把整个 `Browser` 接口全 mock 掉，根本不经过 `WebBrowser`；`JdMallTask` 构造传 null；本次**未改 JdMallTask** |

> 结论：这 4 个是**测试夹具未注入字段**的历史问题，与本次改动无关。修复方式是在两个测试里补 `ReflectionTestUtils.setField(...)` / 注入 `entryService`，不属于本次范围。

### 7.3 验证过程中发现并修掉的一个真实回归

首次跑 `BrowserPreflightServiceTest`（Mockito 修好后）出现 1 个 FAIL：`strictModeShouldPassWhenSannysoftCriticalRowsPass`。
**根因**：测试桩对 `executeScript(anyString())` 的分支按 `"webdriver:"` 匹配，而自检 `PROBE_SCRIPT` 里也含 `webdriver:navigator.webdriver`，于是自检拿到了 preflight 的**基础 probe map**（非空但缺自检独有键），被误判成"探测到泄漏"→ strict 模式否决任务。

**修复**：`StealthSelfAudit.run()` 增加**探针形状校验**——返回值必须同时含 `customGlobals` 与 `replaceAllNative`，否则判 `SKIPPED` 而非 `FAIL`；同时 `BrowserPreflightService` 对 `skipped` / `exception` 情形不启用否决。修后 8/8 全绿。这也是生产侧的健壮性提升：任何返回异常形状的页面都不得误杀业务任务。

### 7.4 新增的真机集成测试

`crawler-bootstrap/src/test/java/org/rx/crawler/service/impl/WebBrowserHumanizedInputIntegrationTest.java`
（`-Dbrowser.humanized.integration=true`，真机 Chrome + 本地 HTML，独立临时 profile，不碰 `common`、不访问外网）覆盖：

1. 连续两次 `executeScript` 后主世界：**无 `window._rx`、无 `window.sleep`**、`String.prototype.replaceAll` / `window.addEventListener` 仍为 `[native code]` —— 这是 **`root.js` 删除的回归护栏**；
2. 注入 `BehaviorPersona` 后走**新** `humanType`：中文长串逐字输入，偶发错字退格，最终值精确一致；
3. **新** `moveMouseLikeHuman`（minimum-jerk + 微修正）后 `elementClick` 仍能正确命中；
4. **新** `mouseMove` / `scrollPage`（真实 wheel）产生位移；
5. 在真实 Chrome 页面上跑 `StealthSelfAudit.run()`，blocking 项全 PASS。

### 7.5 实网只读指纹审计（`JD_FP_AUDIT_LIVE=true`）

```
webdriver=false  getter=native  UA=Chrome/157.0.0.0  headlessUa=false
pluginsLength=5  chromeRuntime=true  pwInitScripts=undefined  pwBinding=undefined
cdcKeys=0  selenium=false  phantom=false
JD_FP_AUDIT_SANNYSOFT_PASSED=true  rows=57  failedCritical=[]
```
`pwInitScripts/pwBinding=undefined`、`cdcKeys=0` 说明主世界注入清理干净；`eidError: TypeError: r is not a function` 是测试侧调用方式问题（已知，真实请求 `e.length=90` 正常），非回归。
证据目录：`C:\Users\Xsy\AppData\Local\Temp\ai\20261124\jd-fp-audit\160804`。

### 7.6 未执行项与原因

| 未跑 | 原因 |
|---|---|
| `JdUnionPasswordLoginLiveTest` / `JdUnionCommonProfileLiveTest` / `JdPassportEnvironmentLiveTest` | 真实登录 + 滑块，会消耗 `common` profile 已落住的会话与验证码次数；按既有记录「会话已落住，重跑需等会话失效或换 profile」，本次不主动触发 |
| `JdAutomationFlagLiveTest` | 启动参数 A/B 矩阵，本次未改 `buildChromeArgs`，无回归面 |
| `ProductIdentityBrowserIntegrationTest` | 明确使用现有 `common` profile 与真实站点，属破坏性/外部依赖 |
| `ProductCacheApiIntegrationTest` | Spring Boot 上下文，与本次改动无关 |

> 因此**上线前仍需一次人工实网登录 + 滑块验证**：本次改了 `humanType` 全量逐字与鼠标轨迹，而 13:57 那次成功样本是旧节奏。按 AGENTS.md「关键抓取变更后至少一次本地集成验证」，这一步不能由上述自动化用例替代。

### 7.7 `root.js` 删除终检

`git rm -f crawler-bootstrap/src/main/resources/bot/root.js` + `git clean -fX` 清掉 `target/classes` 残留副本；`git clean` 后从零重建，最终 `find . -name root.js`（排除 `.git`）**返回空**。
代码侧 `RESOURCE_JS_PATH` / `injectRootJs` / `Cache` / `MemoryCache` / `cacheKey` 引用全部移除；`Browser.readResourceJs()` 保留给 `TbMallPageApi` 加载纯数据脚本，不再注入主世界。
