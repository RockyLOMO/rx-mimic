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
import java.util.function.DoubleSupplier;

/**
 * 京东验证码处理器。主站风控滑块仍按轨道拖到底；登录后的滑动拼图先定位缺口，
 * 拖到缺口跟前，再按拼图块的实际位置修正落点。
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

    private static final String SLIDE_BTN_LOOSE_SELECTOR = "[class*='slide-btn'],[class*='slider-btn']";

    /**
     * 设定后把每次量的拼图截图落盘。
     */
    private java.nio.file.Path puzzleDumpDir;

    private static final String CAPTCHA_IFRAME_SELECTOR =
            "iframe[src*='captcha'],iframe[src*='JDJRV'],iframe[src*='jdjrv'],iframe[src*='riskControl'],iframe[src*='verification'],iframe[src*='iv.jd.com']";

    private static final String[] PUZZLE_BG_SELECTORS = {
            ".JDJRV-bigimg img", "img.JDJRV-bigimg", ".JDJRV-bigimg"
    };
    private static final String[] PUZZLE_PIECE_SELECTORS = {
            "#small_img", ".JDJRV-smallimg img", "img.JDJRV-smallimg", ".JDJRV-smallimg"
    };
    private static final String[] PUZZLE_REFRESH_SELECTORS = {
            ".JDJRV-img-refresh", "[class*='JDJRV-img-refresh']", ".JDJRV-refresh", "[class*='JDJRV-refresh']",
            "[class*='captcha-refresh']"
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

            // 先看一会儿再量，避开拼图刚弹出的入场动画和还没加载完的图片。
            long preThink = attempt == 0 ? rnd.nextLong(800, 1600) : rnd.nextLong(400, 1100);
            Extends.sleep(preThink);
            PuzzleShot puzzle = capturePuzzle(browser, stepTag, attempt + 1);
            Map<String, Object> handle = locateSlideHandle(browser);
            if (handle == null) {
                log.warn("JD captcha slide handle not found (maybe non-slider captcha), step={}, attempt={}",
                        stepTag, attempt + 1);
                return false;
            }

            double originX = toDouble(handle.get("x"));
            boolean slid;
            if (puzzle != null && puzzle.imagesFound) {
                if (puzzle.drag < 12) {
                    log.warn("JD puzzle gap was not measured, step={}, attempt={}", stepTag, attempt + 1);
                    slid = false;
                } else {
                    slid = dragHandle(browser, handle, puzzle.drag, attempt + 1, true,
                            new PieceCourse(browser, stepTag, puzzle, () -> knobLeft(browser),
                                    toDouble(handle.get("x")) - toDouble(handle.get("width")) / 2));
                }
            } else {
                slid = simulateSlideDrag(browser, handle, attempt + 1);
            }
            doSnapshot(snapshot, browser, stepTag + "-captcha-after-slide-" + (attempt + 1));
            if (slid && puzzleSettled(browser, originX)) {
                log.info("JD captcha passed, step={}, attempt={}", stepTag, attempt + 1);
                return true;
            }
            boolean snappedBack = sliderReturned(browser, originX);
            if (!snappedBack) {
                log.info("JD puzzle piece stayed on the gap, skip refresh, step={}, attempt={}",
                        stepTag, attempt + 1);
                if (!isCaptchaPage(browser)) {
                    log.info("JD captcha passed after the piece stayed, step={}, attempt={}", stepTag, attempt + 1);
                    return true;
                }
                return false;
            }
            if (attempt >= attempts - 1) {
                log.warn("JD puzzle snapped back on the last attempt, slid={}, step={}", slid, stepTag);
                break;
            }
            if (reloadOnFailure) {
                log.warn("JD captcha slide failed, refresh page then retry, step={}, attempt={}/{}, url={}",
                        stepTag, attempt + 1, attempts, browser.getCurrentUrl());
                reloadCurrentPage(browser, retryDelay, attempt + 1);
                if (!isCaptchaPage(browser)) {
                    log.info("JD captcha cleared after reload, step={}, attempt={}", stepTag, attempt + 1);
                    return true;
                }
            } else if (puzzle != null && puzzle.refreshedByPage(browser)) {
                // 拖歪之后京东自己换了图，不用再点「换一张」。
                log.info("JD puzzle refreshed by the page after the failed slide, step={}, attempt={}/{}",
                        stepTag, attempt + 1, attempts);
            } else {
                log.warn("JD puzzle snapped back, refresh challenge then retry, slid={}, step={}, attempt={}/{}",
                        slid, stepTag, attempt + 1, attempts);
                refreshChallenge(browser);
            }
        }
        log.warn("JD captcha failed after {} auto attempts, step={}, url={}",
                attempts, stepTag, browser.getCurrentUrl());
        return false;
    }

    /**
     * 松开后京东要等接口才关弹窗。滑块还停在缺口上时不能点「换一张」。
     * 弹回起点才算这次被拒绝。
     */
    private boolean puzzleSettled(Browser browser, double originX) {
        long deadline = System.currentTimeMillis() + 12000L;
        while (System.currentTimeMillis() < deadline) {
            if (!isCaptchaPage(browser)) {
                return true;
            }
            if (sliderReturned(browser, originX)) {
                return false;
            }
            Extends.sleep(400);
        }
        return !isCaptchaPage(browser);
    }

    private boolean sliderReturned(Browser browser, double originX) {
        Map<String, Object> handle = locateSlideHandle(browser);
        return handle != null && toDouble(handle.get("x")) < originX + 28;
    }

    private static Map<String, Object> locateSlideHandle(Browser browser) {
        Map<String, Object> handle = browser.locateNarrowestInFrames(
                "[class*='JDJRV-slide-btn'],[id*='JDJRV-slide-btn']", 16, 90);
        if (handle != null) {
            return handle;
        }
        return browser.locateNarrowestInFrames(
                SLIDE_BTN_LOOSE_SELECTOR + ",[class*='move-btn'],[class*='drag-btn'],[class*='handler']", 16, 80);
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

    private boolean dragHandle(Browser browser, Map<String, Object> handle, double dragDistance, int attempt,
            boolean puzzle) {
        return dragHandle(browser, handle, dragDistance, attempt, puzzle, null);
    }

    /**
     * @param course 拼图拖动的落点修正；普通滑块没有要对的缺口，传 null
     */
    private boolean dragHandle(Browser browser, Map<String, Object> handle, double dragDistance, int attempt,
            boolean puzzle, Browser.DragCourse course) {
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
        browser.mouseDragTracked(startX, startY + startYJitter, endX, endY, steps, course);
        return true;
    }

    /**
     * 拖到缺口跟前之后，按拼图块的实际位置修正落点。
     * 京东的滑钮和拼图块位移一样，脚本里两者都取「当前 clientX 减按下时的 clientX」；这里不写死：
     * 松开前从页面上读一次滑钮和拼图块各走了多远，得到位移比，再按误差挪。
     */
    private static final class PieceCourse implements Browser.DragCourse {
        private final Browser browser;
        private final String stepTag;
        private final String pieceSelector;
        /**
         * 滑钮左缘的当前位置，CSS 像素；读不到时为 NaN。
         */
        private final DoubleSupplier knobLeft;
        /**
         * 拼图块左缘、滑钮左缘的起点，和拼图块左缘应移动的距离，CSS 像素。
         */
        private final double pieceLeftStart;
        private final double knobLeftStart;
        private final double target;
        private double ratio = 1;

        private PieceCourse(Browser browser, String stepTag, PuzzleShot puzzle, DoubleSupplier knobLeft,
                double knobLeftStart) {
            this.browser = browser;
            this.stepTag = stepTag;
            this.pieceSelector = puzzle.pieceSelector;
            this.knobLeft = knobLeft;
            this.pieceLeftStart = puzzle.pieceLeft;
            this.knobLeftStart = knobLeftStart;
            this.target = puzzle.target;
        }

        @Override
        public double settle(double currentX) {
            Double pieceLeft = pieceLeft();
            if (pieceLeft == null) {
                return currentX;
            }
            double pieceMoved = pieceLeft - pieceLeftStart;
            double knobMoved = knobLeft.getAsDouble() - knobLeftStart;
            if (knobMoved > 12 && pieceMoved > 6) {
                double measured = pieceMoved / knobMoved;
                if (measured >= 0.5 && measured <= 2) {
                    ratio = measured;
                }
            }
            double error = target - pieceMoved;
            log.info("JD puzzle settle, step={}, knobMoved={}, pieceMoved={}, ratio={}, target={}, error={}",
                    stepTag, round(knobMoved), round(pieceMoved), round(ratio * 1000) / 1000, round(target), round(error));
            if (Math.abs(error) <= 0.5) {
                return currentX;
            }
            return currentX + error / ratio;
        }

        private Double pieceLeft() {
            Map<String, Object> box = browser.locateInFrames(pieceSelector);
            if (box == null) {
                return null;
            }
            return toDouble(box.get("x")) - toDouble(box.get("width")) / 2;
        }
    }

    /**
     * 滑钮左缘，CSS 像素；读不到时为 NaN。
     */
    private static double knobLeft(Browser browser) {
        Map<String, Object> handle = locateSlideHandle(browser);
        return handle == null ? Double.NaN : toDouble(handle.get("x")) - toDouble(handle.get("width")) / 2;
    }

    private PuzzleShot capturePuzzle(Browser browser, String stepTag, int attempt) {
        try {
            waitForStablePuzzle(browser);
            PuzzlePart bg = firstPart(browser, PUZZLE_BG_SELECTORS);
            PuzzlePart piece = firstPart(browser, PUZZLE_PIECE_SELECTORS);
            if (bg == null || piece == null) {
                return null;
            }
            MeasuredGap measured = MeasuredGap.NONE;
            for (int round = 0; round < 3; round++) {
                if (round > 0) {
                    Extends.sleep(450);
                    bg = firstPart(browser, bg.selector);
                    piece = firstPart(browser, piece.selector);
                    if (bg == null || piece == null) {
                        break;
                    }
                }
                if (bg.image == null || piece.image == null) {
                    break;
                }
                measured = locateGap(browser, bg, piece);
                // 图片没加载完时重来；图片已在但认不出缺口，再量也是同一张图。
                if (!"no-outline".equals(measured.match.reason) && !"weak-contrast".equals(measured.match.reason)) {
                    break;
                }
            }
            dumpPuzzle(stepTag, attempt, bg, piece, measured);
            if (measured.image == null) {
                return PuzzleShot.unmeasured();
            }
            JdPuzzleGap.Match match = measured.match;
            double bgCssW = bg.width;
            double bgCssH = bg.height;
            double pieceCssW = piece.width;
            double pieceOffset = piece.left() - bg.left();
            Map<String, Object> handle = locateSlideHandle(browser);
            double handleW = handle == null ? 0 : toDouble(handle.get("width"));
            // 拼图块左缘要移到背景图上缺口左缘。缺口位置在背景图的截图像素或原始图像素上，
            // 按「展示宽度 / 那张图的宽度」换算成 CSS 像素。
            // 滑钮行程先按「和拼图块位移一样」计划，拖动时再按拼图块的实际位置修正。
            double target = match.x * (bgCssW / measured.image.getWidth()) - pieceOffset;
            double drag = match.found() ? JdPuzzleGap.clampDrag(target, bgCssW - pieceOffset, pieceCssW) : -1;
            // 页面量到的拼图块高度，换算到同一张图的像素，用来核对纵向是否对上。
            double pieceTopFromPage = (piece.top() - bg.top()) / bgCssH * measured.image.getHeight();
            log.info("JD puzzle measured, step={}, attempt={}, gap=({},{}), found={}, reason={}, match={}, score={}, "
                            + "coverage={}, rival={}, pieceTopFromPage={}, image={}x{}, bgCss={}x{}, pieceCss={}x{}, "
                            + "handleCss={}, pieceOffset={}, target={}, drag={}",
                    stepTag, attempt, match.x, match.y, match.found(), match.reason, measured.source, round(match.score),
                    round(match.coverage * 100) / 100, round(match.rival), round(pieceTopFromPage),
                    measured.image.getWidth(), measured.image.getHeight(), round(bgCssW), round(bgCssH),
                    round(pieceCssW), round(piece.height), round(handleW),
                    round(pieceOffset), round(target), round(drag));
            return new PuzzleShot(true, drag, piece.selector, piece.left(), target, bg.selector, measured.bgSrc);
        } catch (RuntimeException e) {
            log.warn("JD puzzle measure failed, error={}", e.getMessage());
            return null;
        }
    }

    /**
     * 缺口定位，三种办法从准到粗：
     * 一、原始图内容匹配。拼图块是从背景图上缺口处裁下来的原图，内部像素和背景图逐点对应，这最准。
     * 二、原始图轮廓匹配。背景图是纯色时内容分不出位置，改按「缺口是压暗的拼图块形状」找。
     * 三、截图轮廓匹配。拿不到内联的原图时用元素截图；形状优先取原始拼图块的透明通道，
     * 没有再从截图里切。元素截图叠在背景图上，没有透明通道，切出来的形状不一定可靠。
     */
    private MeasuredGap locateGap(Browser browser, PuzzlePart bg, PuzzlePart piece) {
        String bgSrc = browser.attributeInFrames(bg.selector, "src");
        String pieceSrc = browser.attributeInFrames(piece.selector, "src");
        BufferedImage naturalBg = decodeDataUri(bgSrc);
        BufferedImage naturalPiece = decodeDataUri(pieceSrc);
        // 页面量到的拼图块高度：缺口和它在同一高度，拖动只能是水平的。
        double pieceTop = piece.top() - bg.top();
        if (naturalBg != null && naturalPiece != null) {
            boolean[][] shape = JdPuzzleGap.shapeFromAlpha(naturalPiece, naturalPiece.getWidth(), naturalPiece.getHeight());
            if (shape != null) {
                double hint = pieceTop / bg.height * naturalBg.getHeight();
                JdPuzzleGap.Match content = JdPuzzleGap.matchContent(naturalBg, naturalPiece, hint);
                if (content.found()) {
                    return new MeasuredGap(content, naturalBg, naturalPiece, "image-content", bgSrc);
                }
                log.info("JD puzzle content match not decisive, reason={}, fall back to the outline", content.reason);
                return new MeasuredGap(
                        JdPuzzleGap.locate(naturalBg, shape, naturalPiece.getWidth(), naturalPiece.getHeight(), hint),
                        naturalBg, naturalPiece, "image-outline", bgSrc);
            }
        }
        double pieceTopShot = pieceTop / bg.height * bg.image.getHeight();
        if (naturalPiece != null) {
            boolean[][] shape = JdPuzzleGap.shapeFromAlpha(naturalPiece, piece.image.getWidth(), piece.image.getHeight());
            if (shape != null) {
                return new MeasuredGap(JdPuzzleGap.locate(bg.image, shape, piece.image.getWidth(), piece.image.getHeight(),
                        pieceTopShot), bg.image, naturalPiece, "screenshot-outline+image-shape", bgSrc);
            }
        }
        return new MeasuredGap(JdPuzzleGap.locate(bg.image, piece.image, pieceTopShot), bg.image, null,
                "screenshot-outline", bgSrc);
    }

    /**
     * 解码 data:image/…;base64,… 形式的内联图片。不是内联图片或解码失败返回 null。
     */
    private static BufferedImage decodeDataUri(String uri) {
        if (uri == null || !uri.startsWith("data:image/")) {
            return null;
        }
        int comma = uri.indexOf(',');
        if (comma < 0) {
            return null;
        }
        try {
            return decode(java.util.Base64.getMimeDecoder().decode(uri.substring(comma + 1)));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * 量到的缺口，连同所用的图片，供换算和落盘。
     */
    private static final class MeasuredGap {
        private static final MeasuredGap NONE = new MeasuredGap(JdPuzzleGap.Match.NONE, null, null, "none", null);

        private final JdPuzzleGap.Match match;
        /**
         * 匹配所参照的背景图：原始图或元素截图，match 的坐标是它的像素。
         */
        private final BufferedImage image;
        /**
         * 原始拼图块图片，带透明通道；没有时为 null。
         */
        private final BufferedImage piece;
        private final String source;
        /**
         * 量的时候背景图的 src。拖动失败后京东会自己换图，src 变了就不用再点「换一张」。
         */
        private final String bgSrc;

        private MeasuredGap(JdPuzzleGap.Match match, BufferedImage image, BufferedImage piece, String source,
                String bgSrc) {
            this.match = match;
            this.image = image;
            this.piece = piece;
            this.source = source;
            this.bgSrc = bgSrc;
        }
    }

    /**
     * 拼图块位置连读两次不变再量，等换图后的入场动画走完。
     */
    private void waitForStablePuzzle(Browser browser) {
        Map<String, Object> previous = null;
        long deadline = System.currentTimeMillis() + 2500L;
        while (System.currentTimeMillis() < deadline) {
            Map<String, Object> box = firstBox(browser, PUZZLE_PIECE_SELECTORS);
            if (box != null && previous != null
                    && Math.abs(toDouble(box.get("x")) - toDouble(previous.get("x"))) < 0.5
                    && Math.abs(toDouble(box.get("y")) - toDouble(previous.get("y"))) < 0.5
                    && Math.abs(toDouble(box.get("width")) - toDouble(previous.get("width"))) < 0.5) {
                return;
            }
            previous = box;
            Extends.sleep(250);
        }
    }

    /**
     * 设定后每次量缺口都把背景、拼图块和匹配结果落盘，用于排查。默认不保存。
     */
    public void setPuzzleDumpDir(java.nio.file.Path dir) {
        this.puzzleDumpDir = dir;
    }

    private void dumpPuzzle(String stepTag, int attempt, PuzzlePart bg, PuzzlePart piece, MeasuredGap measured) {
        if (puzzleDumpDir == null) {
            return;
        }
        try {
            java.nio.file.Files.createDirectories(puzzleDumpDir);
            String base = stepTag + "-" + attempt;
            if (bg != null && bg.image != null) {
                write(bg.image, base + "-bg.png");
            }
            if (piece != null && piece.image != null) {
                write(piece.image, base + "-piece.png");
            }
            if (measured.image != null && (bg == null || measured.image != bg.image)) {
                write(measured.image, base + "-bg-src.png");
            }
            if (measured.piece != null) {
                write(measured.piece, base + "-piece-src.png");
            }
            JdPuzzleGap.Match match = measured.match;
            if (measured.image != null && match.x >= 0) {
                int width = measured.image.getWidth();
                int height = measured.image.getHeight();
                BufferedImage overlay = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
                java.awt.Graphics2D graphics = overlay.createGraphics();
                try {
                    graphics.drawImage(measured.image, 0, 0, null);
                } finally {
                    graphics.dispose();
                }
                // 画拼图块的框和透明通道的轮廓，不贴拼图块截图：截图不透明，贴上去会把缺口盖住。
                int colour = match.found() ? 0xff00ff00 : 0xffff0000;
                int pieceW = measured.piece == null ? (piece == null ? 0 : piece.image.getWidth()) : measured.piece.getWidth();
                int pieceH = measured.piece == null ? (piece == null ? 0 : piece.image.getHeight()) : measured.piece.getHeight();
                for (int x = match.x; x <= match.x + pieceW && x < width; x++) {
                    overlay.setRGB(x, Math.max(0, match.y), colour);
                    if (match.y + pieceH < height) {
                        overlay.setRGB(x, match.y + pieceH, colour);
                    }
                }
                for (int y = match.y; y <= match.y + pieceH && y < height; y++) {
                    overlay.setRGB(Math.max(0, match.x), y, colour);
                    if (match.x + pieceW < width) {
                        overlay.setRGB(match.x + pieceW, y, colour);
                    }
                }
                if (measured.piece != null) {
                    int w = measured.piece.getWidth();
                    int h = measured.piece.getHeight();
                    for (int y = 0; y < h; y++) {
                        for (int x = 0; x < w; x++) {
                            boolean solid = ((measured.piece.getRGB(x, y) >>> 24) & 0xff) >= 128;
                            if (!solid) {
                                continue;
                            }
                            boolean edge = false;
                            for (int dy = -1; dy <= 1 && !edge; dy++) {
                                for (int dx = -1; dx <= 1; dx++) {
                                    int nx = x + dx;
                                    int ny = y + dy;
                                    boolean inside = nx >= 0 && ny >= 0 && nx < w && ny < h;
                                    if (inside ? ((measured.piece.getRGB(nx, ny) >>> 24) & 0xff) < 128 : true) {
                                        edge = true;
                                        break;
                                    }
                                }
                            }
                            if (edge && match.x + x >= 0 && match.y + y >= 0 && match.x + x < width && match.y + y < height) {
                                overlay.setRGB(match.x + x, match.y + y, colour);
                            }
                        }
                    }
                }
                write(overlay, base + "-match.png");
            }
        } catch (Exception e) {
            log.debug("JD puzzle dump fail, error={}", e.getMessage());
        }
    }

    private void write(BufferedImage image, String name) throws java.io.IOException {
        ImageIO.write(image, "png", puzzleDumpDir.resolve(name).toFile());
    }

    /**
     * 拼图截图和它的页面位置。x、y 是元素中心，在主帧视口坐标系里。
     */
    private static final class PuzzlePart {
        private final String selector;
        private final BufferedImage image;
        private final double centerX;
        private final double centerY;
        private final double width;
        private final double height;

        private PuzzlePart(String selector, byte[] bytes, Map<String, Object> box) {
            this.selector = selector;
            this.image = decode(bytes);
            this.centerX = toDouble(box.get("x"));
            this.centerY = toDouble(box.get("y"));
            this.width = toDouble(box.get("width"));
            this.height = toDouble(box.get("height"));
        }

        private double left() {
            return centerX - width / 2;
        }

        private double top() {
            return centerY - height / 2;
        }
    }

    private PuzzlePart firstPart(Browser browser, String[] selectors) {
        for (String selector : selectors) {
            byte[] bytes = browser.screenshotInFrames(selector);
            Map<String, Object> box = browser.locateInFrames(selector);
            if (bytes == null || bytes.length <= 32 || box == null) {
                continue;
            }
            return new PuzzlePart(selector, bytes, box);
        }
        return null;
    }

    private PuzzlePart firstPart(Browser browser, String selector) {
        byte[] bytes = browser.screenshotInFrames(selector);
        Map<String, Object> box = browser.locateInFrames(selector);
        if (bytes == null || bytes.length <= 32 || box == null) {
            return null;
        }
        return new PuzzlePart(selector, bytes, box);
    }

    private void refreshChallenge(Browser browser) {
        try {
            // 「换一张」在标题栏右端。点整条标题栏的中心会落在标题和按钮之间的空白上，换不了图。
            boolean clicked = browser.clickTextInFrames("换\\s*一\\s*张", "换一张", 20, 10, 200, 60);
            if (!clicked) {
                Map<String, Object> refresh = firstBox(browser, PUZZLE_REFRESH_SELECTORS);
                if (refresh == null) {
                    log.warn("JD puzzle refresh control not found");
                    Extends.sleep(600);
                    return;
                }
                browser.mouseClick(toDouble(refresh.get("x")), toDouble(refresh.get("y")));
            }
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

    private static double toDouble(Object val) {
        if (val == null) return 0;
        if (val instanceof Number) return ((Number) val).doubleValue();
        try {
            return Double.parseDouble(val.toString());
        } catch (Exception e) {
            return 0;
        }
    }

    private static double round(double value) {
        return Math.round(value * 10.0) / 10.0;
    }

    private static final class PuzzleShot {
        private final boolean imagesFound;
        /**
         * 计划的滑钮行程，CSS 像素；没量到缺口时为 -1。
         */
        private final double drag;
        private final String pieceSelector;
        /**
         * 拼图块左缘的起点和应移动的距离，CSS 像素。
         */
        private final double pieceLeft;
        private final double target;
        /**
         * 量的时候背景图的 src。拖动失败后京东会自己换图，src 变了就不用再点「换一张」。
         */
        private final String bgSelector;
        private final String bgSrc;

        private PuzzleShot(boolean imagesFound, double drag, String pieceSelector, double pieceLeft, double target,
                String bgSelector, String bgSrc) {
            this.imagesFound = imagesFound;
            this.drag = drag;
            this.pieceSelector = pieceSelector;
            this.pieceLeft = pieceLeft;
            this.target = target;
            this.bgSelector = bgSelector;
            this.bgSrc = bgSrc;
        }

        private boolean refreshedByPage(Browser browser) {
            if (bgSrc == null) {
                return false;
            }
            Extends.sleep(700);
            return !bgSrc.equals(browser.attributeInFrames(bgSelector, "src"));
        }

        private static PuzzleShot unmeasured() {
            return new PuzzleShot(true, -1, "", 0, 0, null, null);
        }
    }
}
