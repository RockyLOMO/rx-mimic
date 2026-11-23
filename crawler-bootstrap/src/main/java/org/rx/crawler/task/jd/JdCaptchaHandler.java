package org.rx.crawler.task.jd;

import lombok.extern.slf4j.Slf4j;
import org.rx.core.Extends;
import org.rx.core.Strings;
import org.rx.crawler.service.Browser;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.BiConsumer;

/**
 * 京东验证码处理器。主站风控滑块仍按轨道拖到底；登录后的滑动拼图先按缺口计算距离再拖。
 * 验证码常在跨源 iframe 里，坐标和截图都走 Playwright 的 frame API。
 * 点选、旋转等非滑块验证码不处理，交给人工。
 */
@Slf4j
public class JdCaptchaHandler {

    private static final String[] CAPTCHA_URL_MARKERS = {
            "captcha", "verification", "riskcontrol", "secure.jd.com", "iv.jd.com"
    };

    private static final String[] CAPTCHA_STRONG_KEYWORDS = {
            "拖动滑块完成验证", "拖动下方滑块", "请拖动滑块", "完成拼图", "请完成上方拼图",
            "拖动滑块填充拼图", "向右滑动完成拼图", "向右拖动滑块"
    };

    private static final String JDJRV_DOM_PROBE =
            "function visible(el){var st=getComputedStyle(el),r=el.getBoundingClientRect();" +
            "return st.display!=='none'&&st.visibility!=='hidden'&&r.width>0&&r.height>0;}" +
            "function norm(s){return (s||'').replace(/\\s+/g,' ').trim();}" +
            "function checkDoc(doc){" +
            "  if(!doc||!doc.body){return false;}" +
            "  var body=norm(doc.body.innerText||doc.body.textContent||'');" +
            "  var WORDS=['拖动滑块完成验证','拖动下方滑块','请拖动滑块','完成拼图','请完成上方拼图'," +
            "    '拖动滑块填充拼图','向右滑动完成拼图','向右拖动滑块'];" +
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
            "     ||src.indexOf('iv.jd.com')>=0||src.indexOf('jcap')>=0" +
            "     ||src.indexOf('riskcontrol')>=0||src.indexOf('jdjrv')>=0){return true;}" +
            "  try{var d=ifrs[k].contentDocument||(ifrs[k].contentWindow&&ifrs[k].contentWindow.document);if(d&&checkDoc(d)){return true;}}catch(e){}}" +
            "}" +
            "return false;";

    private static final String SLIDE_BTN_STRICT_SELECTOR =
            "[class*='JDJRV-slide-btn'],[id*='JDJRV-slide-btn'],[class*='JDJRV-slide-inner'],.JDJRV-suspend";

    private static final String SLIDE_BTN_LOOSE_SELECTOR = "[class*='slide-btn'],[class*='slider-btn']";

    private static final String CAPTCHA_IFRAME_SELECTOR =
            "iframe[src*='captcha'],iframe[src*='JDJRV'],iframe[src*='jdjrv'],iframe[src*='riskControl'],iframe[src*='verification'],iframe[src*='iv.jd.com']";

    private static final String[] PUZZLE_BG_SELECTORS = {
            ".JDJRV-bigimg img", "img.JDJRV-bigimg", ".JDJRV-bigimg"
    };
    private static final String[] PUZZLE_PIECE_SELECTORS = {
            "#small_img", ".JDJRV-smallimg img", "img.JDJRV-smallimg", ".JDJRV-smallimg"
    };
    private static final String[] PUZZLE_TRACK_SELECTORS = {
            ".JDJRV-slide-bg", ".JDJRV-slide"
    };
    private static final String[] PUZZLE_REFRESH_SELECTORS = {
            ".JDJRV-refresh", "[class*='JDJRV-refresh']", "[class*='captcha-refresh']"
    };
    private static final String[] PUZZLE_COPY = {
            "完成拼图", "拖动滑块填充拼图", "向右滑动完成拼图", "拖动滑块完成验证", "请拖动滑块"
    };

    public boolean isCaptchaPage(Browser browser) {
        try {
            String url = browser.getCurrentUrl();
            if (isCaptchaUrl(url)) {
                return true;
            }
            if (browser.locateInFrames("#small_img,.JDJRV-bigimg,.JDJRV-smallimg,[class*='JDJRV-slide-btn']") != null) {
                return true;
            }
            if (hasPuzzleCopy(browser)) {
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
     * 检测并处理验证码。拼图按缺口拖动，普通滑块拖到轨道尽头。失败后刷新页面再试。
     */
    public boolean checkAndHandle(Browser browser, String stepTag, int maxAttempts,
            long retryDelay, BiConsumer<Browser, String> snapshot) {
        try (org.rx.crawler.task.common.TaskTiming.Span ignored =
                org.rx.crawler.task.common.TaskTiming.span("slider.jd", stepTag)) {
            return checkAndHandleBody(browser, stepTag, maxAttempts, retryDelay, snapshot, true);
        }
    }

    /**
     * 登录拼图：只在验证码内部刷新，不刷新整个登录页。验证码消失返回 true。
     */
    public boolean solvePuzzle(Browser browser, String stepTag, int maxAttempts,
            long retryDelay, BiConsumer<Browser, String> snapshot) {
        try (org.rx.crawler.task.common.TaskTiming.Span ignored =
                org.rx.crawler.task.common.TaskTiming.span("slider.jd.puzzle", stepTag)) {
            return checkAndHandleBody(browser, stepTag, maxAttempts, retryDelay, snapshot, false);
        }
    }

    private boolean checkAndHandleBody(Browser browser, String stepTag, int maxAttempts,
            long retryDelay, BiConsumer<Browser, String> snapshot, boolean reloadOnFailure) {
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        int attempts = Math.max(1, maxAttempts);
        for (int attempt = 0; attempt < attempts; attempt++) {
            if (!isCaptchaPage(browser)) {
                return true;
            }
            log.info("JD captcha detected, step={}, attempt={}/{}, url={}",
                    stepTag, attempt + 1, attempts, browser.getCurrentUrl());
            doSnapshot(snapshot, browser, stepTag + "-captcha-before-slide-" + (attempt + 1));

            PuzzleShot puzzle = capturePuzzle(browser);
            Map<String, Object> handle = locateSlideHandle(browser);
            if (handle == null) {
                log.warn("JD captcha slide handle not found (maybe non-slider captcha), step={}, attempt={}",
                        stepTag, attempt + 1);
                return false;
            }
            long preThink = attempt == 0 ? rnd.nextLong(800, 1600) : rnd.nextLong(400, 1100);
            Extends.sleep(preThink);

            boolean slid;
            if (puzzle != null && puzzle.imagesFound) {
                if (puzzle.drag < 12) {
                    log.warn("JD puzzle gap was not measured, step={}, attempt={}", stepTag, attempt + 1);
                    slid = false;
                } else {
                    slid = dragHandle(browser, handle, puzzle.drag, attempt + 1, true);
                }
            } else {
                slid = simulateSlideDrag(browser, handle, attempt + 1);
            }
            long postWait = Math.max(2500, retryDelay * 2) + attempt * 600L + rnd.nextLong(0, 600);
            Extends.sleep(postWait);
            doSnapshot(snapshot, browser, stepTag + "-captcha-after-slide-" + (attempt + 1));

            if (slid && !isCaptchaPage(browser)) {
                log.info("JD captcha passed, step={}, attempt={}", stepTag, attempt + 1);
                return true;
            }
            if (reloadOnFailure) {
                log.warn("JD captcha slide failed, refresh page then retry, step={}, attempt={}/{}, url={}",
                        stepTag, attempt + 1, attempts, browser.getCurrentUrl());
                reloadCurrentPage(browser, retryDelay, attempt + 1);
                if (!isCaptchaPage(browser)) {
                    log.info("JD captcha cleared after reload, step={}, attempt={}", stepTag, attempt + 1);
                    return true;
                }
            } else {
                log.warn("JD puzzle slide failed, refresh challenge then retry, step={}, attempt={}/{}",
                        stepTag, attempt + 1, attempts);
                refreshChallenge(browser);
            }
        }
        log.warn("JD captcha failed after {} auto attempts, step={}, url={}",
                attempts, stepTag, browser.getCurrentUrl());
        return false;
    }

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
                || lower.contains("cfe.jd.com") || lower.contains("iv.jd.com") || lower.contains("verification")) {
            return loose;
        }
        return null;
    }

    /**
     * 拟人拖拽普通 JDJRV 滑块：满轨拖到验证码容器右缘。
     */
    private boolean simulateSlideDrag(Browser browser, Map<String, Object> handle, int attempt) {
        double startX = toDouble(handle.get("x"));
        double handleWidth = toDouble(handle.get("width"));
        if (handleWidth < 20 || handleWidth > 90) {
            log.warn("Suspicious JD captcha handle width={}, refuse to drag, attempt={}", handleWidth, attempt);
            return false;
        }
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
        if (attempt > 1) {
            dragDistance += ThreadLocalRandom.current().nextDouble(-3.0, 3.0);
        }
        return dragHandle(browser, handle, dragDistance, attempt, false);
    }

    private boolean dragHandle(Browser browser, Map<String, Object> handle, double dragDistance, int attempt, boolean puzzle) {
        double startX = toDouble(handle.get("x"));
        double startY = toDouble(handle.get("y"));
        double handleWidth = toDouble(handle.get("width"));
        if (!Double.isFinite(startX) || !Double.isFinite(startY) || startX < 1 || startY < 1) {
            return false;
        }
        if (puzzle && (handleWidth < 12 || handleWidth > 160)) {
            log.warn("Suspicious JD puzzle handle width={}, refuse to drag, attempt={}", handleWidth, attempt);
            return false;
        }
        if (dragDistance < 12) {
            return false;
        }
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        double startYJitter = rnd.nextDouble(-1.5, 1.5);
        double endYJitter = rnd.nextDouble(-2.0, 2.5);
        int steps = attempt <= 1 ? 30 + rnd.nextInt(9) : Math.max(16, 24 - (attempt - 2) * 3 + rnd.nextInt(6));
        double endX = startX + dragDistance;
        double endY = startY + endYJitter;
        log.info("Simulate JD captcha slide drag, puzzle={}, attempt={}, startX={}, startY={}, handleWidth={}, dragDistance={}, steps={}",
                puzzle, attempt, startX, startY + startYJitter, handleWidth, dragDistance, steps);
        browser.mouseDrag(startX, startY + startYJitter, endX, endY, steps);
        return true;
    }

    private PuzzleShot capturePuzzle(Browser browser) {
        try {
            byte[] bgBytes = firstShot(browser, PUZZLE_BG_SELECTORS);
            byte[] pieceBytes = firstShot(browser, PUZZLE_PIECE_SELECTORS);
            Map<String, Object> bgBox = firstBox(browser, PUZZLE_BG_SELECTORS);
            Map<String, Object> pieceBox = firstBox(browser, PUZZLE_PIECE_SELECTORS);
            if (bgBytes == null || pieceBytes == null || bgBox == null || pieceBox == null) {
                return null;
            }
            BufferedImage bg = decode(bgBytes);
            BufferedImage piece = decode(pieceBytes);
            if (bg == null || piece == null) {
                return PuzzleShot.unmeasured();
            }
            double bgCssW = toDouble(bgBox.get("width"));
            double bgCssH = toDouble(bgBox.get("height"));
            double pieceCssW = toDouble(pieceBox.get("width"));
            double bgTop = toDouble(bgBox.get("y")) - bgCssH / 2;
            double pieceTopCss = toDouble(pieceBox.get("y")) - toDouble(pieceBox.get("height")) / 2 - bgTop;
            int pieceTopShot = bgCssH <= 1 ? 0 : (int) Math.round(pieceTopCss * bg.getHeight() / bgCssH);
            int gap = JdPuzzleGap.matchLeft(bg, piece, pieceTopShot);
            Map<String, Object> track = firstBox(browser, PUZZLE_TRACK_SELECTORS);
            Map<String, Object> handle = browser.locateInFrames(SLIDE_BTN_STRICT_SELECTOR);
            double trackW = track == null ? 0 : toDouble(track.get("width"));
            double handleW = handle == null ? 0 : toDouble(handle.get("width"));
            double drag = JdPuzzleGap.dragCss(gap, bg.getWidth(), bgCssW, pieceCssW, trackW, handleW);
            log.info("JD puzzle measured, gapShot={}, pieceTopShot={}, bgShot={}x{}, bgCss={}, pieceCss={}, trackCss={}, handleCss={}, drag={}",
                    gap, pieceTopShot, bg.getWidth(), bg.getHeight(), bgCssW, pieceCssW, trackW, handleW, drag);
            return new PuzzleShot(true, drag);
        } catch (RuntimeException e) {
            log.warn("JD puzzle measure failed, error={}", e.getMessage());
            return null;
        }
    }

    private void refreshChallenge(Browser browser) {
        try {
            Map<String, Object> refresh = firstBox(browser, PUZZLE_REFRESH_SELECTORS);
            if (refresh == null) {
                Extends.sleep(600);
                return;
            }
            browser.mouseClick(toDouble(refresh.get("x")), toDouble(refresh.get("y")));
        } catch (RuntimeException e) {
            log.warn("Refresh JD puzzle challenge fail, error={}", e.getMessage());
        }
        Extends.sleep(1200 + ThreadLocalRandom.current().nextLong(300, 900));
    }

    private boolean hasPuzzleCopy(Browser browser) {
        for (String phrase : PUZZLE_COPY) {
            if (browser.locateTextInFrames(phrase, false, 8, 8) != null) {
                return true;
            }
        }
        return false;
    }

    private byte[] firstShot(Browser browser, String[] selectors) {
        for (String selector : selectors) {
            byte[] bytes = browser.screenshotInFrames(selector);
            if (bytes != null && bytes.length > 32) {
                return bytes;
            }
        }
        return null;
    }

    private Map<String, Object> firstBox(Browser browser, String[] selectors) {
        for (String selector : selectors) {
            Map<String, Object> box = browser.locateInFrames(selector);
            if (box != null) {
                return box;
            }
        }
        return null;
    }

    private static BufferedImage decode(byte[] bytes) {
        if (bytes == null || bytes.length < 32) {
            return null;
        }
        try {
            return ImageIO.read(new ByteArrayInputStream(bytes));
        } catch (Exception e) {
            return null;
        }
    }

    private void reloadCurrentPage(Browser browser, long retryDelay, int attempt) {
        try {
            Extends.sleep(800 + ThreadLocalRandom.current().nextLong(400, 1400));
            String currentUrl = browser.getCurrentUrl();
            if (Strings.isEmpty(currentUrl) || currentUrl.startsWith("about:")) {
                return;
            }
            try {
                browser.navigateUrl(currentUrl, Browser.BODY_SELECTOR, 20);
            } catch (Exception e) {
                log.warn("Reload JD captcha page fail, attempt={}, error={}", attempt, e.getMessage());
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

    private static final class PuzzleShot {
        private final boolean imagesFound;
        private final double drag;

        private PuzzleShot(boolean imagesFound, double drag) {
            this.imagesFound = imagesFound;
            this.drag = drag;
        }

        private static PuzzleShot unmeasured() {
            return new PuzzleShot(true, -1);
        }
    }
}
