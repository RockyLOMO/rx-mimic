# 基于 Java 21 与 Playwright 的现代反爬对抗技术方案

## 1. 方案背景
随着 Web 防护技术（如 Cloudflare, DataDome, Akamai）的演进，传统的 Selenium 方案因其明显的 WebDriver 特征和性能瓶颈已逐渐失效。本方案旨在利用 **Java 21 的高性能并发特性**（虚拟线程）与 **Playwright 的底层协议优势**，构建一套难以被识别的工业级爬虫系统。

## 2. 核心技术栈
| 组件 | 版本 | 说明 |
| :--- | :--- | :--- |
| **JDK** | 21 (LTS) | 利用 Project Loom (虚拟线程) 实现数万级高并发，无需复杂的异步编程。 |
| **Playwright for Java** | 最新版 | 基于 CDP 协议，原生支持自动等待，避开 WebDriver 检测。 |
| **CloakBrowser / Chromium** | 特制版 | 抹除浏览器底层指纹（Canvas, WebGL, RTC, WebGL 等）。 |
| **Maven / Gradle** | - | 项目构建与依赖管理。 |

## 3. 对抗策略设计

### 3.1 环境伪装 (Fingerprinting)
* **浏览器指纹混淆**：
    * 通过 `BrowserContextOptions` 注入随机生成的 `User-Agent`、`ViewportSize` 和 `DeviceScaleFactor`。
    * 使用 `addInitScript` 注入 Stealth 脚本，修改 `navigator.webdriver` 标志及补全浏览器插件列表。
    * **rx-mimic 已落地**：`WebBrowser` 对持久化 Chrome 调用 `setIgnoreDefaultArgs(["--enable-automation"])`，避免 Playwright 默认自动化标志导致 `navigator.webdriver=true` 与顶部「正受到自动测试软件的控制」横幅；任务级 `fingerprintEnabled=true` 时加载 `stealth.min.js` + `chrome-fingerprint.js`，以 `https://bot.sannysoft.com/` 为任务前置校验。
* **底层特征抹除**：
    * 集成 **CloakBrowser** 内核。由于 CloakBrowser 修改了 Chromium C++ 源码，它可以从硬件级别模拟不同的 GPU、声卡和字体库，绕过高级 Canvas 指纹检测。

### 3.2 行为模拟 (Human Behavior Simulation)
* **鼠标轨迹模拟**：摒弃直接点击，使用 `page.mouse().move()` 生成带加速度的随机曲线路径。
* **打字速度仿真**：在输入框操作时，通过随机延迟模拟人类真实的击键间隔。
* **网络流拦截**：利用 Playwright 的 `route()` 功能拦截并丢弃不必要的图片、广告及追踪 JS，既提速又减少被检测风险。
* **阿里 NC 滑块（rx-mimic）**：当前行为见 `docs/tb.md`。`SliderVerifyHandler` 用 Windows `SendInput` 拖真实系统光标；失败后刷新当前页换新滑块，仍失败再人工等待。

### 3.3 网络层对抗
* **动态代理池**：集成高性能住宅代理（Residential Proxy），每个线程/虚拟线程分配独立的代理 IP。
* **TLS 指纹绕过**：针对 Cloudflare 针对性检查的 JA3 指纹，必要时在 Java 层集成 `tls-client` 或自定义 SSLContext，模拟真实浏览器的握手特征。

## 4. 系统架构图示

1.  **调度层 (Virtual Threads)**：每个任务分配一个虚拟线程，极轻量。
2.  **引擎层 (Playwright)**：管理浏览器上下文。
3.  **驱动层 (CloakBrowser/Chromium)**：负责实际渲染。
4.  **代理层 (Proxy Rotation)**：隐藏真实 IP。

## 5. 实施路线图 (Roadmap)

### 第一阶段：基础构建
* 配置 Java 21 环境，引入 Playwright 依赖。
* 封装 `PlaywrightManager` 类，管理浏览器实例池。

### 第二阶段：指纹与 Stealth 集成
* 下载并配置 CloakBrowser 二进制文件。
* 编写 Java 脚本自动化注入混淆代码。
* 测试在 [sannysoft.com](https://bot.sannysoft.com) 等检测站的评分。

### 第三阶段：高并发与稳定性
* 引入虚拟线程 Executor，实现任务的大规模分发。
* 加入自动重试逻辑与异常截图分析（Playwright Tracing）。

## 6. 注意事项
1.  **无头模式 (Headless)**：高级反爬网站对无头模式极其敏感，建议优先使用有头模式或通过 `xvfb` 运行。
2.  **IP 质量**：工具再强，IP 质量（黑名单权重）仍是 50% 的成败关键。
3.  **合规性**：请务必遵循目标网站的 `robots.txt` 和当地法律法规。
