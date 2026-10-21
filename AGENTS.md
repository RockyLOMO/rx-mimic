# rx-mimic 项目 AGENTS 指南

## 项目定位

- 本项目当前以 `JDK21 + Playwright` 为准，`Selenium` 已移除。
- 仅先支持 `Chrome`，其他浏览器暂不考虑。

## 第一原则（强制 · 最高优先级）

**唯一目标：防止被反爬识别，尽可能模拟真人操作。**

所有设计、改代码、加参数、写指纹脚本、做性能优化，都必须先过这一关：

1. **会不会让页面/风控更容易判定为机器人？**
2. **是不是更接近真实用户用本机 Chrome 手工操作？**

与此冲突时，以下一律让步：吞吐、并发、代码省事、测试方便、固定环境复现。

### 落地约束

| 维度 | 要求 |
|---|---|
| 浏览器 | 本机真实 Chrome（`channel=chrome`）+ 持久化 profile；不用 Playwright 自带测试浏览器冒充 |
| 操作 | 优先 Playwright 原生鼠标/键盘/滚动/拖拽；随机延迟、轨迹、悬停；禁止默认用 JS 合成点击/输入 |
| 指纹 | 只**去除自动化特征**（webdriver、automation 标志等）；**禁止**硬编码 GPU/CPU/语言/时区等“某台开发机”参数 |
| 环境 | 语言/时区/UA/硬件默认跟**运行机器 + 本机 Chrome/profile**；配置为空不要强行覆盖 |
| 脚本 | 少 JS 补丁；能不 `defineProperty` 就不改原型（改原型本身可被检测） |
| 启动参数 | 禁止 `--no-sandbox`、`--enable-automation` 等测试向黄条/特征；沙箱保持开启 |
| 登录/验证码 | 人工接管，程序只等待恢复，不绕过 |

### 反例（禁止）

- 为通过某检测站而伪造与 OS 冲突的 WebGL/硬件信息  
- 默认强制 `zh-CN` / 固定时区 / 固定 UA，换机后仍写死  
- 用 JS 强改 `navigator.language` 却与真实 Chrome 请求头不一致  
- 为提速默认 headless、拦图字体、关掉 humanInput  
- 为省事全局 `dispatchEvent` 代替真实点击  

## 指纹与运行环境匹配（从属于第一原则）

- 指纹相关能力必须与**运行机器**匹配，禁止写死开发机参数。
- 含：语言、时区、本机 Chrome 路径/版本、UA（默认不覆盖）、硬件并发、GPU 等。
- 配置为空：不覆盖，交给本机 Chrome + profile + 系统；显式配置才覆盖。
- 指纹脚本只做“去自动化特征”，不做与当前 OS/硬件冲突的伪造。
- **驱动说明**：不用 Selenium ChromeDriver。Playwright 驱动随 Maven 依赖；浏览器用本机 Chrome（`PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1`）。初始化探测本机 Chrome 并记版本，缺失则报错，不下载测试浏览器冒充。

## 通用抓取原则

- 任务执行前优先过 `https://bot.sannysoft.com/`（关键项通过即可，页面上其它诊断行不代表业务失败）。
- 进入登录页 → 人工接管；程序只等待和恢复后续流程。
- 初始页、登录等待、人工接管时长均可配置。
- 公共前置流程复用，任务内不重复造轮子。
- 页面操作优先 `Browser` 封装的原生点击/输入/移动/拖拽；仅定位、读状态或原生失败兜底时用 JS。

## 本地验证约定

- 本地验证/测试类默认显式 `debugEnabled=true`。安智 `application-prod.yml` 当前也打开，用来看远程每步页面；没有排查需要时再关。
- 服务进程同一台机器只允许一个实例，互斥量是 `Global\rx-mimic-service`。第二个进程打印 `rx-mimic service is already running.` 后退出。JVM 关闭 AWT headless，桌面截图才能抓到屏幕。
- 同一个 Chrome profile 仍只开一个浏览器。多标签默认 2 个，只用来提前打开下一个排队任务的起始页；页面操作和滑块拖拽仍串行，一次只操作前台标签。不要把 tabSlots 当成并行任务数，maxActive 保持 1。多账号多 profile 以后再做。
- 本地验证默认通用 Chrome profile（`common`），复用人工登录态。
- `debugEnabled=true` 时保存关键步骤 HTML、当前标签视口 PNG，以及整张桌面 PNG。调用方可用 `GET /api/v1/debug/desktop` 看当前桌面。
- 未指定 `debugOutputDir` 时写到项目约定本地调试目录。
- 抓取类验证完成后回复中打印核心结果与明细。

## 代码与测试

- 改任务流程后补充单测或验证记录。
- 关键抓取变更后至少一次本地集成验证。
- 京东文档只维护 `docs/jd.md`，淘宝文档只维护 `docs/tb.md`。
- 更新 `pom.xml` 或代码后，若存在 `../xiaofan`，须构建 `crawler-api` 并更新到 xiaofan：
  1. `mvn -B -pl crawler-api -am -Dmaven.test.skip=true jar`
  2. 复制 `crawler-api/target/crawler-api-1.0.jar` → `../xiaofan/xiaofan-bootstrap/lib/org/rx/crawler-api/1.0/crawler-api-1.0.jar`
  3. 在 `../xiaofan`：`mvn install:install-file -Dfile=xiaofan-bootstrap/lib/org/rx/crawler-api/1.0/crawler-api-1.0.jar -DgroupId=org.rx -DartifactId=crawler-api -Dversion=1.0 -Dpackaging=jar`
