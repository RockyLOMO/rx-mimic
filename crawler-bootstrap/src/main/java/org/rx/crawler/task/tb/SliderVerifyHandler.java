package org.rx.crawler.task.tb;

import lombok.extern.slf4j.Slf4j;
import org.rx.core.Extends;
import org.rx.core.Strings;
import org.rx.crawler.service.Browser;
import org.rx.crawler.task.common.SensitiveUrls;

import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.BiConsumer;

/**
 * 阿里妈妈/淘宝联盟滑块验证通用处理器。
 * 负责检测页面是否弹出滑块验证、模拟拟人拖拽滑块、处理验证失败重试点击等。
 * 可被不同的爬虫任务复用，不耦合具体业务流程。
 *
 * <p>核心恢复策略：验证失败后<strong>刷新页面再试</strong>。
 * 整页跳转验证（punish/x5sec）或浮层 NC 失败后，当前 challenge token 会被会话级拉黑，
 * 页内再点「点击框体重试」往往无效；必须像真人一样 F5 / 刷新，拿干净挑战后再拖。
 */
@Slf4j
public class SliderVerifyHandler {

    /** 滑块验证页面特征关键词 */
    private static final String[] SLIDER_KEYWORDS = {
            "请拖动下方滑块完成验证", "拖动滑块", "拖到最右边", "按住滑块", "请按住滑块",
            "滑块验证", "安全验证", "访问验证", "行为验证", "请完成验证", "验证失败",
            "Please drag the slider", "Please slide to verify", "slide to verify"
    };

    /** 验证失败重试特征关键词 */
    private static final String[] VERIFY_FAILED_KEYWORDS = {
            "验证失败", "点击框体重试"
    };

    /** URL 级跳转验证页标记（整页 punish，非业务页浮层） */
    private static final String[] JUMPED_VERIFY_URL_MARKERS = {
            "/punish", "x5sec", "_____tmd_____", "captcha"
    };

    /**
     * 检测当前页面是否为滑块验证页（通过页面文字特征、URL、特定元素判断，含同源 iframe）。
     */
    public boolean isSliderVerifyPage(Browser browser) {
        try {
            String url = browser.getCurrentUrl();
            if (isJumpedVerifyUrl(url)) {
                return true;
            }

            String body = readBodySnippet(browser);
            if (containsAny(body, SLIDER_KEYWORDS)) {
                return true;
            }

            // 滑块通过或人工处理后，页面可能残留隐藏的 nc/baxia/nocaptcha 节点。
            // 这里必须只认可见节点，否则 waitSliderVerifyCleared 会误判滑块仍存在并等到超时。
            Boolean detected = browser.executeScript(
                    "function norm(s){return (s||'').replace(/\\s+/g,' ').trim();}" +
                    "function visible(el){var st=getComputedStyle(el),r=el.getBoundingClientRect();return st.display!=='none'&&st.visibility!=='hidden'&&r.width>0&&r.height>0;}" +
                    "var WORDS=['请拖动下方滑块完成验证','拖动滑块','拖到最右边','按住滑块','滑块验证','安全验证','访问验证','行为验证','请完成验证','请按住滑块','Please drag the slider','Please slide to verify'];" +
                    "function checkDoc(doc){" +
                    "  if(!doc||!doc.body){return false;}" +
                    "  var body=norm(doc.body.innerText||doc.body.textContent||'');" +
                    "  for(var i=0;i<WORDS.length;i++){if(body.indexOf(WORDS[i])>=0){return true;}}" +
                    "  var suspects=doc.querySelectorAll('#nc_1_n1z,#nc_1_n1t,#nc_1_wrapper,#baxia-punish,#nocaptcha,.btn_slide,.nc_scale,.nc_wrapper,.nc-container,punish-component');" +
                    "  for(var s=0;s<suspects.length;s++){if(visible(suspects[s])){return true;}}" +
                    "  var nodes=doc.querySelectorAll('iframe,div,span,input,button');" +
                    "  for(var j=0;j<nodes.length;j++){var e=nodes[j];" +
                    "    var meta=[e.id,e.className,e.name,e.src||'',e.getAttribute&&e.getAttribute('title')||'',e.getAttribute&&e.getAttribute('aria-label')||'',e.getAttribute&&e.getAttribute('data-spm')||''].join(' ');" +
                    "    if(!/(nc_|nc-|awsc|captcha|punish|baxia|滑块|验证码|安全验证|x5sec)/i.test(meta)){continue;}" +
                    "    if(visible(e)){return true;}" +
                    "  }" +
                    "  return false;" +
                    "}" +
                    "if(checkDoc(document)){return true;}" +
                    "var ifrs=document.querySelectorAll('iframe');" +
                    "for(var k=0;k<ifrs.length;k++){if(!visible(ifrs[k])){continue;}try{var d=ifrs[k].contentDocument||(ifrs[k].contentWindow&&ifrs[k].contentWindow.document);if(d&&checkDoc(d)){return true;}}catch(e){}}" +
                    "return false;");
            return Boolean.TRUE.equals(detected);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 是否整页跳转验证页（URL 含 punish / x5sec / tmd 等）。
     * 与业务页浮层 NC 区分：跳转页失败后优先整页刷新再试。
     */
    public boolean isJumpedVerifyUrl(String url) {
        return containsAny(url, JUMPED_VERIFY_URL_MARKERS);
    }

    /**
     * 检测并处理滑块验证，最多重试指定次数。
     * 验证成功或无验证时返回 true；全部重试失败返回 false。
     *
     * <p>失败路径：拖完仍在验证页 → 下一轮<b>先刷新</b>，等滑块回到轨道左侧，再拖。
     * 不在失败页上先点重试条。punish 刷新后若仍是「请刷新」且滑块不在左侧，改开干净业务入口。
     *
     * @param browser     浏览器实例
     * @param stepTag     当前步骤标签（用于日志和快照命名）
     * @param maxAttempts 最大拖拽次数（订单任务建议 5，推广链接建议 5）
     * @param retryDelay  每次重试等待毫秒数
     * @param snapshot    快照回调 (browser, snapshotName)，可为 null
     * @return true=验证通过或无验证，false=全部重试失败
     */
    public boolean checkAndHandle(Browser browser, String stepTag, int maxAttempts,
            long retryDelay, BiConsumer<Browser, String> snapshot) {
        return checkAndHandle(browser, stepTag, maxAttempts, retryDelay, snapshot, null);
    }

    /**
     * @param freshEntryUrl 业务入口。刷新后仍停在 punish 失败页时改开这一页。可为 null。
     */
    public boolean checkAndHandle(Browser browser, String stepTag, int maxAttempts,
            long retryDelay, BiConsumer<Browser, String> snapshot, String freshEntryUrl) {
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        int attempts = Math.max(1, maxAttempts);
        for (int attempt = 0; attempt < attempts; attempt++) {
            if (!isSliderVerifyPage(browser)) {
                return true;
            }
            String hint = pageHint(browser);
            boolean jumped = isJumpedVerifyUrl(browser.getCurrentUrl());
            log.info("Slider verify detected, step={}, attempt={}/{}, jumpedVerifyPage={}, hint={}, url={}",
                    stepTag, attempt + 1, attempts, jumped, hint, safeUrl(browser.getCurrentUrl()));

            boolean refreshFirst = attempt > 0 || "please-refresh".equals(hint) || "verify-failed".equals(hint);
            if (!refreshFirst) {
                long handleWait = jumped ? 10000 : 8000;
                boolean ready = waitSliderHandleReady(browser, handleWait);
                if (!ready || !handleReadyAtLeft(readSliderGeometry(browser))) {
                    log.info("Slider not ready at left, refresh before first drag, step={}, attempt={}, handleReady={}",
                            stepTag, attempt + 1, ready);
                    refreshFirst = true;
                }
            }
            if (refreshFirst) {
                log.info("Refresh page before slide, step={}, attempt={}/{}, hint={}",
                        stepTag, attempt + 1, attempts, hint);
                refreshForFreshChallenge(browser, snapshot, stepTag, attempt + 1, retryDelay, jumped, freshEntryUrl);
                if (!isSliderVerifyPage(browser)) {
                    log.info("Slider verify cleared after refresh, step={}, attempt={}", stepTag, attempt + 1);
                    return true;
                }
                long handleWait = jumped ? 8000 : 6000;
                boolean ready = waitSliderHandleReady(browser, handleWait);
                boolean atLeft = ready && handleReadyAtLeft(readSliderGeometry(browser));
                log.info("After refresh before drag, step={}, attempt={}, handleReady={}, atLeft={}, hint={}",
                        stepTag, attempt + 1, ready, atLeft, pageHint(browser));
                if (!atLeft) {
                    continue;
                }
            }

            long preThink = attempt == 0 ? rnd.nextLong(800, 1600) : rnd.nextLong(400, 1100);
            Extends.sleep(preThink);
            doSnapshot(snapshot, browser, stepTag + "-before-slide-" + (attempt + 1));

            boolean slid = simulateSlideDrag(browser, snapshot, stepTag, attempt + 1);
            long postWait = Math.max(2500, retryDelay * 2) + attempt * 600L + rnd.nextLong(0, 600);
            Extends.sleep(postWait);
            doSnapshot(snapshot, browser, stepTag + "-after-slide-" + (attempt + 1));

            if (slid && !isSliderVerifyPage(browser)) {
                log.info("Slider verify passed, step={}, attempt={}", stepTag, attempt + 1);
                return true;
            }
            log.warn("Slider verify failed, next attempt refreshes first, step={}, attempt={}/{}, slid={}, hint={}, url={}",
                    stepTag, attempt + 1, attempts, slid, pageHint(browser), safeUrl(browser.getCurrentUrl()));
        }
        log.warn("Slider verify failed after {} attempts, refresh once for manual, step={}, url={}",
                attempts, stepTag, safeUrl(browser.getCurrentUrl()));
        refreshForFreshChallenge(browser, snapshot, stepTag, attempts, retryDelay,
                isJumpedVerifyUrl(browser.getCurrentUrl()), freshEntryUrl);
        return false;
    }

    /**
     * 刷新当前页面以获取干净的 NC 挑战，然后供下一轮拖拽使用。
     * NC 是会话级风控：本地拖拽失败后，后端把当前 challenge token 拉黑，再怎么拖也不行；
     * 唯一可靠的恢复方式就是像真人一样刷新页面（优先 location.reload，失败再 nativeGet）。
     */
    private void refreshForFreshChallenge(Browser browser, BiConsumer<Browser, String> snapshot,
            String stepTag, int attempt, long retryDelay, boolean jumpedVerifyPage, String freshEntryUrl) {
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        try {
            // 刷新前先做真人式停顿，模拟「失败 → 看一眼 → 决定刷新」
            long preReload = jumpedVerifyPage
                    ? 1000 + rnd.nextLong(600, 1600)
                    : 800 + rnd.nextLong(400, 1400);
            Extends.sleep(preReload);
            doSnapshot(snapshot, browser, stepTag + "-before-reload-" + attempt);

            String currentUrl = browser.getCurrentUrl();
            log.info("Refreshing page before next slide, step={}, attempt={}, jumped={}, hint={}, url={}",
                    stepTag, attempt, jumpedVerifyPage, pageHint(browser), safeUrl(currentUrl));
            if (Strings.isEmpty(currentUrl) || currentUrl.startsWith("about:")) {
                log.warn("Cannot reload, currentUrl invalid, step={}, attempt={}", stepTag, attempt);
                Extends.sleep(Math.max(2000, retryDelay));
                return;
            }
            boolean reloaded = tryLocationReload(browser, currentUrl);
            if (!reloaded) {
                log.info("location.reload unavailable, fallback nativeGet current page, step={}, attempt={}",
                        stepTag, attempt);
                browser.nativeGet(currentUrl);
            }
        } catch (Exception e) {
            log.warn("Reload page fail, step={}, attempt={}, error={}", stepTag, attempt, SensitiveUrls.redactText(e.getMessage()));
            try {
                String url = browser.getCurrentUrl();
                if (!Strings.isEmpty(url) && !url.startsWith("about:")) {
                    browser.nativeGet(url);
                }
            } catch (Exception ignored) {
            }
        }
        // 刷新后等页面 DOM 重建 + NC iframe 加载；跳转验证页给更长缓冲
        long postReload = (jumpedVerifyPage ? 3200 : 2500)
                + rnd.nextLong(800, 2200)
                + Math.min(2000L, attempt * 400L);
        Extends.sleep(postReload);
        long readyWait = jumpedVerifyPage ? 6000 : 4000;
        boolean ready = waitSliderHandleReady(browser, readyWait);
        boolean atLeft = ready && handleReadyAtLeft(readSliderGeometry(browser));
        String hint = pageHint(browser);
        // F5 打在 punish 上常常还是同一块失败页。滑块不在左侧或页面还在说请刷新时，改开业务入口。
        if ((!atLeft || "please-refresh".equals(hint))
                && !Strings.isEmpty(freshEntryUrl)
                && isJumpedVerifyUrl(browser.getCurrentUrl())
                && !isJumpedVerifyUrl(freshEntryUrl)) {
            log.info("Reload kept the failed challenge, opening clean entry, step={}, attempt={}, hint={}, handleAtLeft={}",
                    stepTag, attempt, hint, atLeft);
            try {
                browser.nativeGet(freshEntryUrl);
                Extends.sleep(1800 + rnd.nextLong(400, 1200));
                ready = waitSliderHandleReady(browser, readyWait);
                atLeft = ready && handleReadyAtLeft(readSliderGeometry(browser));
                hint = pageHint(browser);
            } catch (Exception e) {
                log.warn("Open clean entry fail, step={}, attempt={}, error={}", stepTag, attempt, SensitiveUrls.redactText(e.getMessage()));
            }
        }
        log.info("After refresh handleReady={}, handleAtLeft={}, hint={}, step={}, attempt={}, url={}",
                ready, atLeft, hint, stepTag, attempt, safeUrl(browser.getCurrentUrl()));
        doSnapshot(snapshot, browser, stepTag + "-after-reload-" + attempt);
    }

    /**
     * 优先用 location.reload() 模拟真人按 F5；等待文档重建后返回 true。
     * 导航上下文销毁属正常现象，用轮询 currentUrl/body 判定加载完成。
     */
    private boolean tryLocationReload(Browser browser, String expectedUrl) {
        try {
            browser.executeScript("try{location.reload();}catch(e){}");
        } catch (Exception e) {
            // reload 触发导航时 executeScript 可能抛 “Execution context was destroyed”
            String msg = e.getMessage();
            if (msg == null || (!msg.contains("Execution context was destroyed")
                    && !msg.contains("because of a navigation")
                    && !msg.contains("Target closed"))) {
                log.debug("location.reload script error: {}", msg);
                return false;
            }
        }
        // 等文档重建：URL 仍在、body 可读
        long deadline = System.currentTimeMillis() + 12000L;
        while (System.currentTimeMillis() < deadline) {
            Extends.sleep(300L);
            try {
                String url = browser.getCurrentUrl();
                if (Strings.isEmpty(url) || url.startsWith("about:")) {
                    continue;
                }
                // 仍在同一验证域即可（reload 后 query 可能略变）
                Object ready = browser.executeScript(
                        "return !!(document && document.body && document.readyState"
                                + " && (document.readyState==='interactive'||document.readyState==='complete'));");
                if (Boolean.TRUE.equals(ready)) {
                    log.info("location.reload completed, url={}", safeUrl(url));
                    return true;
                }
            } catch (Exception ignored) {
                // 仍在导航中
            }
        }
        log.warn("location.reload wait timeout, expectedUrl={}", safeUrl(expectedUrl));
        return false;
    }

    /** 轮询等待滑块 handle 出现并可见，最多等待 maxWaitMillis 毫秒。 */
    private boolean waitSliderHandleReady(Browser browser, long maxWaitMillis) {
        long deadline = System.currentTimeMillis() + maxWaitMillis;
        while (System.currentTimeMillis() < deadline) {
            try {
                Boolean ready = browser.executeScript(
                        "function visible(el){if(!el){return false;}var st=getComputedStyle(el),r=el.getBoundingClientRect();return st.display!=='none'&&st.visibility!=='hidden'&&r.width>0&&r.height>0;}" +
                        "function check(doc){if(!doc){return false;}var h=doc.querySelector('#nc_1_n1z,.btn_slide,[class*=btn_slide]');return !!(h&&visible(h));}" +
                        "if(check(document)){return true;}" +
                        "var ifrs=document.querySelectorAll('iframe');" +
                        "for(var i=0;i<ifrs.length;i++){try{var d=ifrs[i].contentDocument||(ifrs[i].contentWindow&&ifrs[i].contentWindow.document);if(check(d)){return true;}}catch(e){}}" +
                        "return false;");
                if (Boolean.TRUE.equals(ready)) {
                    return true;
                }
            } catch (Exception ignored) {
            }
            Extends.sleep(300L);
        }
        return false;
    }

    /**
     * 找到滑块视口坐标后，用系统光标按下并拖到尽头。
     * JS 只读位置，不改滑块、不派发鼠标事件。
     * 返回 true 表示已经拖过（不代表验证通过），false 表示未找到滑块。
     */
    public boolean simulateSlideDrag(Browser browser, BiConsumer<Browser, String> snapshot,
            String stepTag, int attempt) {
        Map<String, Object> sliderInfo = readSliderGeometry(browser);

        if (sliderInfo == null) {
            log.warn("NC slider handle not found, cannot simulate drag, step={}, attempt={}", stepTag, attempt);
            doSnapshot(snapshot, browser, stepTag + "-slide-no-handle-" + attempt);
            return false;
        }

        double startX = toDouble(sliderInfo.get("x"));
        double startY = toDouble(sliderInfo.get("y"));
        double handleWidth = toDouble(sliderInfo.get("width"));
        double handleLeft = toDouble(sliderInfo.get("handleLeft"));
        double trackLeft = toDouble(sliderInfo.get("trackLeft"));
        double trackWidth = toDouble(sliderInfo.get("trackWidth"));
        boolean trackKnown = Boolean.TRUE.equals(sliderInfo.get("trackKnown"));
        boolean inIframe = Boolean.TRUE.equals(sliderInfo.get("inIframe"));

        if (!Double.isFinite(startX) || !Double.isFinite(startY) || startX < 1 || startY < 1) {
            log.warn("Slider handle coordinates invalid, startX={}, startY={}, step={}, attempt={}", startX, startY, stepTag, attempt);
            doSnapshot(snapshot, browser, stepTag + "-slide-invalid-coord-" + attempt);
            return false;
        }
        // 二次校验：handle 尺寸必须落在 NC handle 合理范围内（width 25-70）
        if (handleWidth < 25 || handleWidth > 70) {
            log.warn("Suspicious handle width={}, refuse to drag, step={}, attempt={}", handleWidth, stepTag, attempt);
            doSnapshot(snapshot, browser, stepTag + "-slide-bad-handle-" + attempt);
            return false;
        }

        if (trackKnown && !handleReadyAtLeft(sliderInfo)) {
            log.warn("Refuse drag, handle not at left, handleLeft={}, trackLeft={}, step={}, attempt={}",
                    handleLeft, trackLeft, stepTag, attempt);
            doSnapshot(snapshot, browser, stepTag + "-slide-not-left-" + attempt);
            return false;
        }

        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        // 终点是滑块中心。系统轨迹会过冲再拉回 endX，所以 endX 要比轨道右端再过去几个像素。
        // 不再减 2，也不再在重试时随机减距离，避免停在终点前面。
        double dragDistance;
        if (trackKnown && trackWidth > handleWidth + 10) {
            dragDistance = slideDistancePx(startX, handleWidth, trackLeft, trackWidth);
        } else {
            double viewportWidth = toDouble(browser.executeScript("return window.innerWidth || 800;"));
            dragDistance = Math.max(200, viewportWidth * 0.70 - handleWidth) + 8;
        }
        double startYJitter = rnd.nextDouble(-1.5, 1.5);
        double endYJitter = rnd.nextDouble(-2.0, 2.5);
        // steps 只给系统光标不可用时的 Playwright 兜底。WindowsMouse 走 HumanSlideTrack，不看这个步数。
        int steps = attempt <= 1 ? 30 + rnd.nextInt(9) : Math.max(16, 24 - (attempt - 2) * 3 + rnd.nextInt(6));

        double effectiveStartY = startY + startYJitter;
        double endX = startX + dragDistance;
        double endY = startY + endYJitter;
        double endGap = trackKnown ? (trackLeft + trackWidth) - (endX + handleWidth / 2.0) : Double.NaN;
        log.info("Simulate slide drag, attempt={}, handleLeft={}, trackLeft={}, trackWidth={}, handleWidth={}, start=({},{}), end=({},{}), distance={}, endGap={}, inIframe={}, osCursor=true",
                attempt, round1(handleLeft), round1(trackLeft), round1(trackWidth), round1(handleWidth),
                round1(startX), round1(effectiveStartY), round1(endX), round1(endY), round1(dragDistance), round1(endGap), inIframe);

        browser.mouseDrag(startX, effectiveStartY, endX, endY, steps);
        return true;
    }

    /**
     * 只读滑块位置。不改 DOM，不派发鼠标事件。
     */
    private Map<String, Object> readSliderGeometry(Browser browser) {
        try {
            return browser.executeScript(
                    "function visible(el){if(!el){return false;}var st=getComputedStyle(el),r=el.getBoundingClientRect();return st.display!=='none'&&st.visibility!=='hidden'&&r.width>0&&r.height>0;}" +
                    "function validHandle(el){if(!visible(el)){return false;}var r=el.getBoundingClientRect();return r.width>=20&&r.width<=80&&r.height>=20&&r.height<=80;}" +
                    "function searchDoc(doc,offX,offY){" +
                    "  if(!doc){return null;}" +
                    "  var handles=Array.prototype.slice.call(doc.querySelectorAll('#nc_1_n1z,.btn_slide,[class*=btn_slide],[id$=_n1z][role=button]')).filter(validHandle);" +
                    "  if(handles.length===0){return null;}" +
                    "  var handle=handles[0];" +
                    "  var track=null,p=handle.parentElement;" +
                    "  for(var d=0;p&&d<6;d++,p=p.parentElement){" +
                    "    var c=((p.className||'')+'').toLowerCase();" +
                    "    if(c.indexOf('nc_scale')>=0||c.indexOf('nc_wrapper')>=0||(p.id||'').indexOf('nc_1_n1t')>=0||(p.id||'').indexOf('nc_1_wrapper')>=0){track=p;break;}" +
                    "  }" +
                    "  if(!track){track=doc.querySelector('#nc_1_n1t,.nc_scale,[class*=nc_scale],[id$=_n1t]');}" +
                    "  var hr=handle.getBoundingClientRect(),trackWidth=hr.width*7,trackLeft=0,trackKnown=false;" +
                    "  if(track&&visible(track)){var tr=track.getBoundingClientRect();if(tr.width>=hr.width*3){trackWidth=tr.width;trackLeft=offX+tr.left;trackKnown=true;}}" +
                    "  return {x:offX+hr.left+hr.width/2,y:offY+hr.top+hr.height/2,handleLeft:offX+hr.left,width:hr.width,height:hr.height,trackWidth:trackWidth,trackLeft:trackLeft,trackKnown:trackKnown,inIframe:offX>0||offY>0};" +
                    "}" +
                    "var info=searchDoc(document,0,0);" +
                    "if(info){return info;}" +
                    "var ifrs=document.querySelectorAll('iframe');" +
                    "for(var k=0;k<ifrs.length;k++){if(!visible(ifrs[k])){continue;}try{var d=ifrs[k].contentDocument||(ifrs[k].contentWindow&&ifrs[k].contentWindow.document);if(!d){continue;}var fr=ifrs[k].getBoundingClientRect();var r=searchDoc(d,fr.left,fr.top);if(r){return r;}}catch(e){}}" +
                    "return null;");
        } catch (Exception e) {
            log.debug("Read slider geometry fail, error={}", e.getMessage());
            return null;
        }
    }

    /**
     * 检测页面是否出现"验证失败，点击框体重试"提示，若有则点击 NC 验证框体触发重置。
     */
    public void clickRetryContainerIfPresent(Browser browser, BiConsumer<Browser, String> snapshot,
            String stepTag, int attempt) {
        try {
            String body = readBodySnippet(browser);
            if (!containsAny(body, VERIFY_FAILED_KEYWORDS)) {
                return;
            }
            log.info("Slider verify failed hint detected, clicking container to retry, step={}, attempt={}", stepTag, attempt);
            doSnapshot(snapshot, browser, stepTag + "-verify-failed-" + attempt);

            // NC 失败后 DOM 会变：handle/track 消失，取而代之的是 .errloading（橙色错误条），
            // 这才是「点击框体重试」对应的元素。优先点它，再回退到 handle/track/container。
            // 同时所有候选都校验 rect 合理性（宽高最小阈值 + 大小 < 视口），避免命中位置异常元素。
            Map<String, Object> containerInfo = browser.executeScript(
                    "function visible(el){if(!el){return false;}var st=getComputedStyle(el),r=el.getBoundingClientRect();return st.display!=='none'&&st.visibility!=='hidden'&&r.width>0&&r.height>0;}" +
                    "function sane(r){return r.width>=20&&r.height>=10&&r.width<window.innerWidth*0.9&&r.height<window.innerHeight*0.9&&r.top>=0;}" +
                    "function pack(offX,offY,el,r){return {x:offX+r.left+r.width/2,y:offY+r.top+r.height/2,w:r.width,h:r.height,tag:el.tagName+'#'+(el.id||'')+'.'+(el.className||'').split(' ')[0]};}" +
                    "function searchDoc(doc,offX,offY){" +
                    "  if(!doc){return null;}" +
                    // 1) 优先 .errloading（NC 失败后唯一的「点击重试」目标）
                    "  var er=doc.querySelector('.errloading,[id*=nc_1_refresh]');" +
                    "  if(er&&visible(er)){var r=er.getBoundingClientRect();if(sane(r)){return pack(offX,offY,er,r);}}" +
                    // 2) handle（成功状态或刚渲染时）
                    "  var h=doc.querySelector('#nc_1_n1z,.btn_slide,[class*=btn_slide]');" +
                    "  if(h&&visible(h)){var hr=h.getBoundingClientRect();if(sane(hr)){return pack(offX,offY,h,hr);}}" +
                    // 3) track
                    "  var t=doc.querySelector('#nc_1_n1t,.nc_scale,[class*=nc_scale]');" +
                    "  if(t&&visible(t)){var tr=t.getBoundingClientRect();if(sane(tr)){return pack(offX,offY,t,tr);}}" +
                    // 4) nc_wrapper（包含 errloading 的最小容器，宽度 300px 左右）
                    "  var w=doc.querySelector('#nc_1_wrapper,.nc_wrapper');" +
                    "  if(w&&visible(w)){var wr=w.getBoundingClientRect();if(sane(wr)){return pack(offX,offY,w,wr);}}" +
                    // 5) 兜底：含「验证失败」文字的元素
                    "  var all=doc.querySelectorAll('div,span,p,a');" +
                    "  for(var i=0;i<all.length;i++){var txt=(all[i].innerText||all[i].textContent||'').trim();if(txt.indexOf('验证失败')>=0&&visible(all[i])){var fr=all[i].getBoundingClientRect();if(sane(fr)){return pack(offX,offY,all[i],fr);}}}" +
                    "  return null;" +
                    "}" +
                    "var info=searchDoc(document,0,0);" +
                    "if(info){return info;}" +
                    "var ifrs=document.querySelectorAll('iframe');" +
                    "for(var k=0;k<ifrs.length;k++){if(!visible(ifrs[k])){continue;}try{var d=ifrs[k].contentDocument||(ifrs[k].contentWindow&&ifrs[k].contentWindow.document);if(!d){continue;}var fr=ifrs[k].getBoundingClientRect();var r=searchDoc(d,fr.left,fr.top);if(r){return r;}}catch(e){}}" +
                    "return null;");

            if (containerInfo == null) {
                log.warn("Verify-failed container not found, step={}, attempt={}", stepTag, attempt);
                return;
            }

            double cx = toDouble(containerInfo.get("x"));
            double cy = toDouble(containerInfo.get("y"));
            Object tagInfo = containerInfo.get("tag");
            if (cx < 1 || cy < 1) {
                log.warn("Verify-failed container coordinates invalid, x={}, y={}", cx, cy);
                return;
            }
            // 点击位置加微小随机偏移，更像真人
            ThreadLocalRandom rnd = ThreadLocalRandom.current();
            double clickX = cx + rnd.nextDouble(-3.0, 3.0);
            double clickY = cy + rnd.nextDouble(-2.0, 2.0);
            log.info("Clicking verify-failed target {} at ({}, {}), step={}, attempt={}",
                    tagInfo, clickX, clickY, stepTag, attempt);
            browser.mouseDrag(clickX, clickY, clickX, clickY, 1);
            // 重试动画 + handle 复位通常需要 1-2s
            Extends.sleep(1500 + rnd.nextLong(0, 800));
            doSnapshot(snapshot, browser, stepTag + "-verify-retry-clicked-" + attempt);
        } catch (Exception e) {
            log.warn("Click retry container error, step={}, attempt={}, error={}", stepTag, attempt, SensitiveUrls.redactText(e.getMessage()));
        }
    }

    /**
     * 读取页面 body 文本摘要（最多 2000 字符），带导航重试。
     * 同时拼接同源 iframe 的 body 文本，方便检测百夏滑块场景。
     */
    public String readBodySnippet(Browser browser) {
        String body = "";
        for (int i = 0; i < 3; i++) {
            try {
                body = browser.executeScript(
                        "var parts=[document.body?(document.body.innerText||document.body.textContent||''):''];" +
                        "var ifrs=document.querySelectorAll('iframe');" +
                        "for(var k=0;k<ifrs.length;k++){try{var d=ifrs[k].contentDocument||(ifrs[k].contentWindow&&ifrs[k].contentWindow.document);if(d&&d.body){parts.push(d.body.innerText||d.body.textContent||'');}}catch(e){}}" +
                        "return parts.join('\\n');");
                break;
            } catch (Exception e) {
                String message = e.getMessage();
                if (message != null && (message.contains("Execution context was destroyed")
                        || message.contains("because of a navigation"))) {
                    log.debug("Read body while navigating, retry={}", i + 1);
                    Extends.sleep(500L * (i + 1));
                    continue;
                }
                log.warn("Read body fail, currentUrl={}, error={}", safeUrl(browser.getCurrentUrl()), SensitiveUrls.redactText(message));
                return "";
            }
        }
        if (Strings.isEmpty(body) || body.length() <= 2000) {
            return body;
        }
        return body.substring(0, 2000);
    }

    // ---- 内部工具方法 ----

    private void doSnapshot(BiConsumer<Browser, String> snapshot, Browser browser, String name) {
        if (snapshot != null) {
            snapshot.accept(browser, name);
        }
    }

    private String pageHint(Browser browser) {
        return pageHintText(readBodySnippet(browser));
    }

    /** 失败页文案。please-refresh 时先刷新，不要在错误条上空点。 */
    public static String pageHintText(String body) {
        if (Strings.isEmpty(body)) {
            return "unknown";
        }
        String lower = body.toLowerCase(java.util.Locale.ROOT);
        if (lower.contains("please refresh") || lower.contains("something's wrong")
                || body.contains("请刷新") || body.contains("刷新页面") || body.contains("刷新后再试")) {
            return "please-refresh";
        }
        if (body.contains("验证失败") || body.contains("点击框体重试")) {
            return "verify-failed";
        }
        return "slider";
    }

    /** 日志地址去掉账号、查询串和 fragment。 */
    public static String safeUrl(String url) {
        return SensitiveUrls.redact(url);
    }

    /**
     * 滑块中心要从 startX 拖到「轨道右端 - 半个滑块 + 8px」。
     * 系统轨迹最后拉回这个终点，多出来的 8px 用来抵消拉回后停在终点前面。
     */
    public static double slideDistancePx(double startX, double handleWidth, double trackLeft, double trackWidth) {
        double past = 8.0;
        double endCenter = trackLeft + trackWidth - handleWidth / 2.0 + past;
        double distance = endCenter - startX;
        double shortest = trackWidth - handleWidth;
        if (distance < shortest) {
            distance = shortest + past;
        }
        return distance;
    }

    public static boolean handleReadyAtLeft(Map<String, Object> geometry) {
        if (geometry == null) {
            return false;
        }
        double width = toDouble(geometry.get("width"));
        if (width < 25 || width > 70) {
            return false;
        }
        if (!Boolean.TRUE.equals(geometry.get("trackKnown"))) {
            return true;
        }
        double handleLeft = toDouble(geometry.get("handleLeft"));
        double trackLeft = toDouble(geometry.get("trackLeft"));
        return handleLeft <= trackLeft + 14.0;
    }

    private static double round1(double value) {
        if (!Double.isFinite(value)) {
            return value;
        }
        return Math.round(value * 10.0) / 10.0;
    }

    /** Object -> double 工具方法，兼容 Integer / Long / Double / String */
    private static double toDouble(Object val) {
        if (val == null) return 0;
        if (val instanceof Number) return ((Number) val).doubleValue();
        try { return Double.parseDouble(val.toString()); } catch (Exception e) { return 0; }
    }

    private boolean containsAny(String value, String... keys) {
        if (Strings.isEmpty(value)) {
            return false;
        }
        for (String key : keys) {
            if (value.contains(key)) {
                return true;
            }
        }
        return false;
    }
}
