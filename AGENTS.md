# rx-mercury 项目 AGENTS 指南

## 项目定位

- 本项目当前以 `JDK21 + Playwright` 为准，`Selenium` 已移除。
- 抓取目标优先考虑对抗反爬，流程以真实用户操作为主，不追求高效并发。
- 仅先支持 `Chrome`，其他浏览器暂不考虑。

## 指纹与运行环境匹配原则（强制）

- **所有指纹相关能力必须自动与运行机器匹配**，禁止写死开发机/某一台机器的环境参数。
- 包括但不限于：语言（`locale` / `Accept-Language` / `navigator.languages`）、时区、本机 Chrome 路径与版本、UA（默认不覆盖）、硬件并发与 GPU 等。
- 配置项为空时走运行时探测（`Locale.getDefault()`、`ZoneId.systemDefault()`、本机 Chrome 安装探测）；只有用户显式配置时才覆盖。
- 换机器部署时，语言/时区/Chrome 版本应随该机器变化；不得把某台机器的 `zh-CN`、`Asia/Shanghai`、固定 GPU/CPU 等硬编码进默认逻辑。
- 指纹脚本只做“去自动化特征”，不要伪造与当前 OS/硬件明显冲突的信息（例如 Windows 上写死 macOS Iris GPU）。
- **浏览器驱动说明**：本项目不使用 Selenium ChromeDriver，不需要“按 Chrome 版本下载 chromedriver”。Playwright Java 驱动随 Maven 依赖自带；浏览器使用本机 `channel=chrome`（`PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1`）。初始化时应探测本机 Chrome 是否存在并记录版本，缺失则明确报错，而不是下载另一套测试浏览器来冒充。

## 通用抓取原则

- 所有抓取任务执行前，优先通过 `https://bot.sannysoft.com/` 检测。
- 如果进入登录页，交给人工接管，程序只负责等待和恢复后续流程。
- 初始页、登录页等待时长、人工接管等待时长都必须可配置。
- 所有任务都应复用公共前置流程，不要在具体任务里重复造轮子。
- 页面操作优先使用 `Browser` 封装的原生点击、输入、鼠标移动、拖拽能力，模拟真实用户操作；仅在定位元素、读取页面状态或原生操作失败兜底时使用 JS。

## 本地验证约定

- 本地验证/测试类中默认要显式把 `debugEnabled=true` 打开；发布程序默认保持关闭。
- 本地验证和集成测试时默认使用通用 Chrome profile（`common`），方便复用并记录人工登录态、风控状态和页面调试状态。
- `debugEnabled=true` 时，任务应保存关键步骤的 HTML 快照，方便排查页面变化。
- 若未显式指定 `debugOutputDir`，优先写到项目约定的本地调试目录。
- 抓取类任务本地验证完成后，必须在回复中打印核心结果和抓取明细，方便人工复核。

## 代码与测试

- 修改任务流程后，必须补充对应单测或验证记录。
- 关键抓取流程变更后，至少跑一次本地集成验证。
- 文档同步更新到 `docs/plan/JD_Union_getPromotionUrl_Task.md`。
- 每次更新 `pom.xml` 或代码后，如果上级目录存在 `../xiaofan` 项目，必须自动构建 `crawler-api` 模块并更新到 `xiaofan` 项目中。
- 构建步骤：
  1. 在本项目执行 `mvn -B -pl crawler-api -am -Dmaven.test.skip=true jar`。
  2. 将产出的 `crawler-api/target/crawler-api-1.0.jar` 复制到 `../xiaofan/xiaofan-bootstrap/lib/org/rx/crawler-api/1.0/crawler-api-1.0.jar`。
  3. 切换到 `../xiaofan` 目录执行 `mvn install:install-file -Dfile=xiaofan-bootstrap/lib/org/rx/crawler-api/1.0/crawler-api-1.0.jar -DgroupId=org.rx -DartifactId=crawler-api -Dversion=1.0 -Dpackaging=jar` 安装到本地 Maven 仓库。
