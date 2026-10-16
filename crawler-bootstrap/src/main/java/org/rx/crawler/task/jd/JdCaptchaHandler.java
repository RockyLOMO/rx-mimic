package org.rx.crawler.task.jd;

import lombok.extern.slf4j.Slf4j;
import org.rx.core.Extends;
import org.rx.core.Strings;
import org.rx.crawler.service.Browser;

import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.BiConsumer;

/**
 * 京东 JDJRV 验证码通用处理器（主站搜索/详情页风控滑块）。
 * 负责检测页面是否弹出 JDJRV 验证码（含跨源 iframe）、用原生 Mouse API 拟人拖拽滑块、
 * 失败后刷新重试。可被不同的京东任务复用，不耦合具体业务流程。
 *
 * <p>与淘宝 NC 滑块（{@code SliderVerifyHandler}）结构同源但选择器体系不同：
 * JDJRV 通常渲染在跨源 iframe（captcha.jd.com / cfe.jd.com）内，
 * 页面 JS 读不到 contentDocument，因此用 {@link Browser#locateInFrames}
 * 由 Playwright 原生 API 跨 frame 定位滑块坐标后拖拽。
 *
 * <p>非滑块类验证码（点选文字/图形等）无法自动处理，直接返回 false 交给任务层人工兜底。
 * 恢复策略同 NC：拖拽失败 → 刷新页面拿干净挑战 → 下一轮再拖。
 */
@Slf4j
public class JdCaptchaHandler {

    /** URL 级验证码/风控页标记 */
    private static final String[] CAPTCHA_URL_MARKERS = {
            "captcha", "verification", "riskcontrol", "secure.jd.com"
    };

    /** 强文案特征（登录页/正常页不会出现的验证码指令文案） */
    private static final String[] CAPTCHA_STRONG_KEYWORDS = {
            "拖动滑块完成验证", "拖动下方滑块", "请拖动滑块", "完成拼图", "请完成上方拼图"
    };

    /** JDJRV DOM 特征（主文档或 iframe src） */
    private static final String JDJRV_DOM_PROBE =
            "function visible(el){var st=getComputedStyle(el),r=el.getBoundingClientRect();" +
            "return st.display!=='none'&&st.visibility!=='hidden'&&r.width>0&&r.height>0;}" +
            "function norm(s){return (s||'').replace(/\\s+/g,' ').trim();}" +
            "function checkDoc(doc){" +
            "  if(!doc||!doc.body){return false;}" +
            "  var body=norm(doc.body.innerText||doc.body.textContent||'');" +
            "  var WORDS=['拖动滑块完成验证','拖动下方滑块','请拖动滑块','完成拼图','请完成上方拼图'];" +
            "  for(var i=0;i<WORDS.length;i++){if(body.indexOf(WORDS[i])>=0){return true;}}" +
            "  var suspects=doc.querySelectorAll('[class*=JDJRV],[id*=JDJRV],.JDJRV-bigimg,.JDJRV-slide,#small_img');" +
            "  for(var s=0;s<suspects.length;s++){if(visible(suspects[s])){return true;}}" +
            "  return false;" +
            "}" +
            "if(checkDoc(document)){return true;}" +
            "var ifrs=document.querySelectorAll('iframe');" +
            "for(var k=0;k<ifrs.length;k++){if(!visible(ifrs[k])){continue;}" +
            "  var src=(ifrs[k].src||'').toLowerCase();" +
            "  if(src.indexOf('captcha.jd.com')>=0||src.indexOf('cfe.jd.com')>=0||src.indexOf('csc.jd.com')>=0" +
            "     ||src.indexOf('riskcontrol')>=0||src.indexOf('jdjrv')>=0){return true;}" +
            "  try{var d=ifrs[k].contentDocument||(ifrs[k].contentWindow&&ifrs[k].contentWindow.document);if(d&&checkDoc(d)){return true;}}catch(e){}}" +
            "}" +
            "return false;";

    /** JDJRV 滑块按钮严格选择器（优先，避免误命中页面轮播等组件） */
    private static final String SLIDE_BTN_STRICT_SELECTOR =
            "[class*='JDJRV-slide-btn'],[id*='JDJRV-slide-btn'],[class*='JDJRV-slide-inner'],.JDJRV-suspend";

    /** 宽松选择器：仅在 frameUrl 属于验证码域时才接受 */
    private static final String SLIDE_BTN_LOOSE_SELECTOR = "[class*='slide-btn'],[class*='slider-btn']";

    /** 验证码 iframe 元素选择器（定位拖拽终点用） */
    private static final String CAPTCHA_IFRAME_SELECTOR =
            "iframe[src*='captcha'],iframe[src*='JDJRV'],iframe[src*='jdjrv'],iframe[src*='riskControl'],iframe[src*='verification']";

    /**
     * 检测当前页面是否为京东验证码/风控页。
     */
    public boolean isCaptchaPage(Browser browser) {
        try {
            String url = browser.getCurrentUrl();
            if (isCaptchaUrl(url)) {
                return true;
            }
            Boolean detected = browser.executeScript(JDJRV_DOM_PROBE);
            return Boolean.TRUE.equals(detected);
        } catch (Exception e) {
            return false;
        }
    }

    public boolean isCaptchaUrl(String url) {
        if (Strings.isEmpty(url)) {
            return false;
        }
        String lower = url.toLowerCase();
        for (String marker : CAPTCHA_URL_MARKERS) {
            if (lower.contains(marker)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 检测并处理验证码，最多自动拖拽 maxAttempts 次；验证通过或无验证返回 true，全部失败返回 false。
     *
     * @param browser     浏览器实例
     * @param stepTag     当前步骤标签（日志/快照命名）
     * @param maxAttempts 最大自动拖拽次数
     * @param retryDelay  基础重试等待毫秒
     * @param snapshot    快照回调 (browser, snapshotName)，可为 null
     */
    public boolean checkAndHandle(Browser browser, String stepTag, int maxAttempts,
            long retryDelay, BiConsumer<Browser, String> snapshot) {
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        int attempts = Math.max(1, maxAttempts);
        for (int attempt = 0; attempt < attempts; attempt++) {
            if (!isCaptchaPage(browser)) {
                return true;
            }
            log.info("JD captcha detected, step={}, attempt={}/{}, url={}",
                    stepTag, attempt + 1, attempts, browser.getCurrentUrl());
            doSnapshot(snapshot, browser, stepTag + "-captcha-before-slide-" + (attempt + 1));

            Map<String, Object> handle = locateSlideHandle(browser);
            if (handle == null) {
                log.warn("JD captcha slide handle not found (maybe non-slider captcha), step={}, attempt={}",
                        stepTag, attempt + 1);
                // 非滑块类无法自动处理，不刷新死磕，直接交给人工兜底
                return false;
            }
            // 拖拽前像真人一样停顿一会（首次更长，模拟"看清楚→犹豫→操作"）
            long preThink = attempt == 0 ? rnd.nextLong(800, 1600) : rnd.nextLong(400, 1100);
            Extends.sleep(preThink);

            boolean slid = simulateSlideDrag(browser, handle, attempt + 1);
            // 后端校验需要 1-3s；attempt 越大等得越久
            long postWait = Math.max(2500, retryDelay * 2) + attempt * 600L + rnd.nextLong(0, 600);
            Extends.sleep(postWait);
            doSnapshot(snapshot, browser, stepTag + "-captcha-after-slide-" + (attempt + 1));

            if (slid && !isCaptchaPage(browser)) {
                log.info("JD captcha passed, step={}, attempt={}", stepTag, attempt + 1);
                return true;
            }
            log.warn("JD captcha slide failed, refresh page then retry, step={}, attempt={}/{}, url={}",
                    stepTag, attempt + 1, attempts, browser.getCurrentUrl());

            // 刷新拿干净挑战（JDJRV 同样存在会话级 challenge 拉黑）
            reloadCurrentPage(browser, retryDelay, attempt + 1);
            if (!isCaptchaPage(browser)) {
                log.info("JD captcha cleared after reload, step={}, attempt={}", stepTag, attempt + 1);
                return true;
            }
        }
        log.warn("JD captcha failed after {} auto attempts, step={}, url={}",
                attempts, stepTag, browser.getCurrentUrl());
        return false;
    }

    /**
     * 定位 JDJRV 滑块按钮：先严格 JDJRV 选择器；宽松选择器仅在验证码域 frame 内接受。
     * 返回 {x, y, width, height, frameUrl}，未找到返回 null。
     */
    private Map<String, Object> locateSlideHandle(Browser browser) {
        Map<String, Object> handle = browser.locateInFrames(SLIDE_BTN_STRICT_SELECTOR);
        if (handle != null) {
            return handle;
        }
        Map<String, Object> loose = browser.locateInFrames(SLIDE_BTN_LOOSE_SELECTOR);
        if (loose == null) {
            return null;
        }
        String frameUrl = String.valueOf(loose.get("frameUrl"));
        String lower = frameUrl == null ? "" : frameUrl.toLowerCase();
        if (lower.contains("captcha") || lower.contains("jdjrv") || lower.contains("riskcontrol")
                || lower.contains("cfe.jd.com") || lower.contains("verification")) {
            return loose;
        }
        return null;
    }

    /**
     * 拟人拖拽 JDJRV 滑块：满轨拖到验证码容器右缘。返回 true 表示已执行拖拽（不代表通过）。
     */
    private boolean simulateSlideDrag(Browser browser, Map<String, Object> handle, int attempt) {
        double startX = toDouble(handle.get("x"));
        double startY = toDouble(handle.get("y"));
        double handleWidth = toDouble(handle.get("width"));
        if (!Double.isFinite(startX) || !Double.isFinite(startY) || startX < 1 || startY < 1) {
            return false;
        }
        if (handleWidth < 20 || handleWidth > 90) {
            log.warn("Suspicious JD captcha handle width={}, refuse to drag, attempt={}", handleWidth, attempt);
            return false;
        }

        // 拖拽距离：优先用验证码 iframe 容器宽度（locateInFrames 的 x 为中心点坐标）；
        // 目标 = 滑块右缘拖到 iframe 右缘内 4~10px。兜底视口 60%。
        double dragDistance = 0;
        Map<String, Object> iframeBox = browser.locateInFrames(CAPTCHA_IFRAME_SELECTOR);
        if (iframeBox != null) {
            double frameCenterX = toDouble(iframeBox.get("x"));
            double frameWidth = toDouble(iframeBox.get("width"));
            if (frameWidth > handleWidth + 40 && frameCenterX > 1) {
                double frameRight = frameCenterX + frameWidth / 2;
                double handleRight = startX + handleWidth / 2;
                dragDistance = frameRight - 4 - ThreadLocalRandom.current().nextDouble(0, 6) - handleRight;
            }
        }
        if (dragDistance < 40) {
            double viewportWidth = toDouble(browser.executeScript("return window.innerWidth || 800;"));
            dragDistance = Math.max(200, viewportWidth * 0.60 - handleWidth);
        }

        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        if (attempt > 1) {
            dragDistance += rnd.nextDouble(-3.0, 3.0);
        }
        double startYJitter = rnd.nextDouble(-1.5, 1.5);
        double endYJitter = rnd.nextDouble(-2.0, 2.5);
        int steps;
        if (attempt <= 1) {
            steps = 30 + rnd.nextInt(9);
        } else {
            steps = Math.max(16, 24 - (attempt - 2) * 3 + rnd.nextInt(6));
        }

        double endX = startX + dragDistance;
        double endY = startY + endYJitter;
        log.info("Simulate JD captcha slide drag, attempt={}, startX={}, startY={}, handleWidth={}, dragDistance={}, steps={}",
                attempt, startX, startY + startYJitter, handleWidth, dragDistance, steps);
        browser.mouseDrag(startX, startY + startYJitter, endX, endY, steps);
        return true;
    }

    /**
     * 刷新当前页面（优先 location.reload 模拟真人 F5），等待文档重建。
     */
    private void reloadCurrentPage(Browser browser, long retryDelay, int attempt) {
        try {
            Extends.sleep(800 + ThreadLocalRandom.current().nextLong(400, 1400));
            String currentUrl = browser.getCurrentUrl();
            if (Strings.isEmpty(currentUrl) || currentUrl.startsWith("about:")) {
                return;
            }
            try {
                browser.executeScript("try{location.reload();}catch(e){}");
            } catch (Exception e) {
                String msg = e.getMessage();
                if (msg != null && (msg.contains("Execution context was destroyed")
                        || msg.contains("because of a navigation")
                        || msg.contains("Target closed"))) {
                    // reload 触发导航属正常
                }
            }
        } catch (Exception e) {
            log.warn("Reload JD captcha page fail, attempt={}, error={}", attempt, e.getMessage());
        }
        Extends.sleep(2500 + ThreadLocalRandom.current().nextLong(800, 2200) + Math.min(2000L, attempt * 400L));
    }

    private void doSnapshot(BiConsumer<Browser, String> snapshot, Browser browser, String name) {
        if (snapshot != null) {
            snapshot.accept(browser, name);
        }
    }

    private double toDouble(Object val) {
        if (val == null) return 0;
        if (val instanceof Number) return ((Number) val).doubleValue();
        try {
            return Double.parseDouble(val.toString());
        } catch (Exception e) {
            return 0;
        }
    }
}
