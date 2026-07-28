package org.rx.crawler.task.tb;

import lombok.extern.slf4j.Slf4j;
import org.rx.core.Extends;
import org.rx.core.Strings;
import org.rx.crawler.service.Browser;

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
            "滑块验证", "安全验证", "访问验证", "行为验证", "请完成验证", "验证失败"
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
                    "var WORDS=['请拖动下方滑块完成验证','拖动滑块','拖到最右边','按住滑块','滑块验证','安全验证','访问验证','行为验证','请完成验证','请按住滑块'];" +
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
     * <p>失败路径（强制）：拖拽未通过 →（可选轻点失败条）→ <strong>刷新页面</strong> → 下一轮再拖。
     * 跳转验证页与浮层滑块共用此策略；刷新比页内死磕 .errloading 更接近真人恢复方式。
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
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        int attempts = Math.max(1, maxAttempts);
        for (int attempt = 0; attempt < attempts; attempt++) {
            if (!isSliderVerifyPage(browser)) {
                return true;
            }
            boolean jumped = isJumpedVerifyUrl(browser.getCurrentUrl());
            log.info("Slider verify detected, step={}, attempt={}/{}, jumpedVerifyPage={}, url={}",
                    stepTag, attempt + 1, attempts, jumped, browser.getCurrentUrl());

            // 等待滑块 handle 渲染（NC 异步注入，常需 1-3s）；跳转验证页 / 首次给更长时间
            long handleWait = jumped ? (attempt == 0 ? 10000 : 8000) : (attempt == 0 ? 8000 : 6000);
            boolean handleReady = waitSliderHandleReady(browser, handleWait);
            if (!handleReady) {
                log.warn("Slider handle not ready in {}ms, refresh page then retry, step={}, attempt={}, jumped={}",
                        handleWait, stepTag, attempt + 1, jumped);
                // handle 没渲染 → 刷新换干净挑战，比硬点容器更稳；最后一轮也刷新便于人工接管时页是新的
                refreshForFreshChallenge(browser, snapshot, stepTag, attempt + 1, retryDelay, jumped);
                // 刷新后若已不在验证页（偶发 challenge 自愈 / 风控放行），直接成功
                if (!isSliderVerifyPage(browser)) {
                    log.info("Slider verify cleared after reload (no drag), step={}, attempt={}", stepTag, attempt + 1);
                    return true;
                }
                continue;
            }
            // 拖拽前像真人一样停顿一会（首次更长，后续逐步缩短，模拟"看清楚→犹豫→操作"）
            long preThink = attempt == 0 ? rnd.nextLong(800, 1600) : rnd.nextLong(400, 1100);
            Extends.sleep(preThink);
            doSnapshot(snapshot, browser, stepTag + "-before-slide-" + (attempt + 1));

            boolean slid = simulateSlideDrag(browser, snapshot, stepTag, attempt + 1);
            // mouseUp 后 NC 后端校验需要 1-2s；attempt 越大等得越久（避免被识别为机器频率）
            long postWait = Math.max(2500, retryDelay * 2) + attempt * 600L + rnd.nextLong(0, 600);
            Extends.sleep(postWait);
            doSnapshot(snapshot, browser, stepTag + "-after-slide-" + (attempt + 1));

            if (slid && !isSliderVerifyPage(browser)) {
                log.info("Slider verify passed, step={}, attempt={}", stepTag, attempt + 1);
                return true;
            }
            log.warn("Slider verify failed, will refresh page then retry, step={}, attempt={}/{}, slid={}, jumped={}, url={}",
                    stepTag, attempt + 1, attempts, slid, jumped, browser.getCurrentUrl());

            // 失败文案出现时先轻点一次「点击框体重试」（浮层场景偶发有效），仍失败再整页刷新。
            // 跳转 punish 页上 .errloading 多数无效，但成本低，点完立刻刷新不耽误。
            clickRetryContainerIfPresent(browser, snapshot, stepTag, attempt + 1);
            if (!isSliderVerifyPage(browser)) {
                log.info("Slider verify passed after click-retry, step={}, attempt={}", stepTag, attempt + 1);
                return true;
            }

            // NC 失败后当前 challenge 常被会话级拉黑：必须刷新页面才能拿干净挑战再拖。
            // 最后一轮失败同样刷新，避免卡在「验证失败」脏态交给人工。
            refreshForFreshChallenge(browser, snapshot, stepTag, attempt + 1, retryDelay, jumped);
            if (!isSliderVerifyPage(browser)) {
                log.info("Slider verify cleared after fail-reload, step={}, attempt={}", stepTag, attempt + 1);
                return true;
            }
        }
        log.warn("Slider verify failed after {} attempts, step={}, stillVerifyPage={}, url={}",
                attempts, stepTag, isSliderVerifyPage(browser), browser.getCurrentUrl());
        return false;
    }

    /**
     * 刷新当前页面以获取干净的 NC 挑战，然后供下一轮拖拽使用。
     * NC 是会话级风控：本地拖拽失败后，后端把当前 challenge token 拉黑，再怎么拖也不行；
     * 唯一可靠的恢复方式就是像真人一样刷新页面（优先 location.reload，失败再 nativeGet）。
     */
    private void refreshForFreshChallenge(Browser browser, BiConsumer<Browser, String> snapshot,
            String stepTag, int attempt, long retryDelay, boolean jumpedVerifyPage) {
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        try {
            // 刷新前先做真人式停顿，模拟「失败 → 看一眼 → 决定刷新」
            long preReload = jumpedVerifyPage
                    ? 1000 + rnd.nextLong(600, 1600)
                    : 800 + rnd.nextLong(400, 1400);
            Extends.sleep(preReload);
            doSnapshot(snapshot, browser, stepTag + "-before-reload-" + attempt);

            String currentUrl = browser.getCurrentUrl();
            log.info("Refreshing page for fresh NC challenge (fail then retry), step={}, attempt={}, jumped={}, url={}",
                    stepTag, attempt, jumpedVerifyPage, currentUrl);
            if (Strings.isEmpty(currentUrl) || currentUrl.startsWith("about:")) {
                log.warn("Cannot reload, currentUrl invalid, step={}, attempt={}", stepTag, attempt);
                Extends.sleep(Math.max(2000, retryDelay));
                return;
            }
            boolean reloaded = tryLocationReload(browser, currentUrl);
            if (!reloaded) {
                // 兜底：直接 navigate 当前 URL（与 F5 等价的二次路径）
                log.info("location.reload unavailable/failed, fallback nativeGet, step={}, attempt={}", stepTag, attempt);
                browser.nativeGet(currentUrl);
            }
        } catch (Exception e) {
            log.warn("Reload page fail, step={}, attempt={}, error={}", stepTag, attempt, e.getMessage());
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
        // 主动等到 handle 出现或超时，避免下一轮一上来又 not-ready 空转
        long readyWait = jumpedVerifyPage ? 6000 : 4000;
        boolean ready = waitSliderHandleReady(browser, readyWait);
        log.info("After reload handleReady={}, step={}, attempt={}, url={}",
                ready, stepTag, attempt, browser.getCurrentUrl());
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
                    log.info("location.reload completed, url={}", url);
                    return true;
                }
            } catch (Exception ignored) {
                // 仍在导航中
            }
        }
        log.warn("location.reload wait timeout, expectedUrl={}", expectedUrl);
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
     * 模拟人工缓慢向右拖拽滑块验证的滑块按钮。
     * 通过 JS 找到滑块元素坐标，再用 Playwright 原生 Mouse API 模拟拖拽。
     * 返回 true 表示已成功模拟拖拽（不代表验证通过），false 表示未找到滑块。
     */
    public boolean simulateSlideDrag(Browser browser, BiConsumer<Browser, String> snapshot,
            String stepTag, int attempt) {
        // 跨主文档与同源 iframe 严格定位 NC 滑块 handle，返回主页面绝对视口坐标 {x,y,width,height,trackWidth,inIframe}
        // 严格策略：只认 NC 标准选择器（#nc_1_n1z / .btn_slide / [id$=_n1z]），
        // 不要兜底命中页面其他 [class*=slider] 组件（如订单列表的轮播），避免拖错对象。
        Map<String, Object> sliderInfo = browser.executeScript(
                "function visible(el){if(!el){return false;}var st=getComputedStyle(el),r=el.getBoundingClientRect();return st.display!=='none'&&st.visibility!=='hidden'&&r.width>0&&r.height>0;}" +
                "function validHandle(el){if(!visible(el)){return false;}var r=el.getBoundingClientRect();return r.width>=20&&r.width<=80&&r.height>=20&&r.height<=80;}" +
                "function searchDoc(doc,offX,offY){" +
                "  if(!doc){return null;}" +
                // 严格匹配 NC handle：精确 ID + class，附加尺寸校验过滤误命中
                "  var handles=Array.prototype.slice.call(doc.querySelectorAll('#nc_1_n1z,.btn_slide,[class*=btn_slide],[id$=_n1z][role=button]')).filter(validHandle);" +
                "  if(handles.length===0){return null;}" +
                // NC 轨道：必须包含 handle 才算同一个 widget
                "  var handle=handles[0];" +
                "  var track=null,p=handle.parentElement;" +
                "  for(var d=0;p&&d<6;d++,p=p.parentElement){" +
                "    var c=((p.className||'')+'').toLowerCase();" +
                "    if(c.indexOf('nc_scale')>=0||c.indexOf('nc_wrapper')>=0||(p.id||'').indexOf('nc_1_n1t')>=0||(p.id||'').indexOf('nc_1_wrapper')>=0){track=p;break;}" +
                "  }" +
                "  if(!track){track=doc.querySelector('#nc_1_n1t,.nc_scale,[class*=nc_scale],[id$=_n1t]');}" +
                "  var hr=handle.getBoundingClientRect(),trackWidth=hr.width*7;" + // 兜底估算
                "  if(track&&visible(track)){var tr=track.getBoundingClientRect();if(tr.width>=hr.width*3){trackWidth=tr.width;}}" +
                "  return {x:offX+hr.left+hr.width/2,y:offY+hr.top+hr.height/2,handleLeft:offX+hr.left,width:hr.width,height:hr.height,trackWidth:trackWidth,inIframe:offX>0||offY>0};" +
                "}" +
                "var info=searchDoc(document,0,0);" +
                "if(info){return info;}" +
                "var ifrs=document.querySelectorAll('iframe');" +
                "for(var k=0;k<ifrs.length;k++){if(!visible(ifrs[k])){continue;}try{var d=ifrs[k].contentDocument||(ifrs[k].contentWindow&&ifrs[k].contentWindow.document);if(!d){continue;}var fr=ifrs[k].getBoundingClientRect();var r=searchDoc(d,fr.left,fr.top);if(r){return r;}}catch(e){}}" +
                "return null;");

        if (sliderInfo == null) {
            log.warn("NC slider handle not found, cannot simulate drag, step={}, attempt={}", stepTag, attempt);
            doSnapshot(snapshot, browser, stepTag + "-slide-no-handle-" + attempt);
            return false;
        }

        double startX = toDouble(sliderInfo.get("x"));
        double startY = toDouble(sliderInfo.get("y"));
        double handleWidth = toDouble(sliderInfo.get("width"));
        double trackWidth = toDouble(sliderInfo.get("trackWidth"));
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

        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        // 拖拽距离 = 轨道宽 - 滑块宽，兜底用视口宽度的 70%
        double dragDistance;
        if (trackWidth > handleWidth + 10) {
            dragDistance = trackWidth - handleWidth - 2;
        } else {
            double viewportWidth = toDouble(browser.executeScript("return window.innerWidth || 800;"));
            dragDistance = Math.max(200, viewportWidth * 0.70 - handleWidth);
        }
        // 每次尝试给距离微小扰动（首次精确贴边，后续 ±3px 模拟人手轻微偏差），更难被识别
        if (attempt > 1) {
            dragDistance += rnd.nextDouble(-3.0, 3.0);
        }
        // 起点 Y 加微小漂移：真人按住滑块时 Y 坐标也会有零点几像素偏移
        double startYJitter = rnd.nextDouble(-1.5, 1.5);
        // 终点 Y 漂移更明显，模拟向右拖时手腕自然上下摆动
        double endYJitter = rnd.nextDouble(-2.0, 2.5);
        // 步数随机化：attempt=1 慢一点（30-38 步，像首次小心拖动），
        // attempt>=2 加快（首次失败后真人会"我再用力拖一次"，更急更快），步数 18-26。
        // NC 后端对 trajectory 既检测过慢（< 200ms 全程）也检测过慢（> 3s 像机器逐帧动），
        // 我们已经在 200ms-2s 区间，加快重试可能反而更像真人的"急躁"操作。
        int steps;
        if (attempt <= 1) {
            steps = 30 + rnd.nextInt(9);
        } else {
            steps = Math.max(16, 24 - (attempt - 2) * 3 + rnd.nextInt(6));
        }

        double effectiveStartY = startY + startYJitter;
        double endX = startX + dragDistance;
        double endY = startY + endYJitter;
        log.info("Simulate slide drag, attempt={}, startX={}, startY={}, handleWidth={}, trackWidth={}, dragDistance={}, steps={} (faster={}), inIframe={}",
                attempt, startX, effectiveStartY, handleWidth, trackWidth, dragDistance, steps, attempt > 1, inIframe);

        // 使用 Playwright 原生 Mouse API 模拟拖拽（坐标已经是主页面视口绝对坐标）
        browser.mouseDrag(startX, effectiveStartY, endX, endY, steps);
        return true;
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
            log.warn("Click retry container error, step={}, attempt={}, error={}", stepTag, attempt, e.getMessage());
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
                log.warn("Read body fail, currentUrl={}, error={}", browser.getCurrentUrl(), message);
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

    /** Object -> double 工具方法，兼容 Integer / Long / Double / String */
    private double toDouble(Object val) {
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
