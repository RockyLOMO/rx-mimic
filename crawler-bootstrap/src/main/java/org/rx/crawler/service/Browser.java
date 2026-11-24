package org.rx.crawler.service;

import org.rx.core.FluentWait;
import org.rx.core.Linq;
import org.rx.core.Reflects;
import org.rx.io.DuplexStream;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeoutException;

public interface Browser extends AutoCloseable {
    String BLANK_URL = "about:blank", BODY_SELECTOR = "body";

    static String readResourceJs(String resourcePath) {
        return DuplexStream.readString(Reflects.getResource(resourcePath), StandardCharsets.UTF_8);
    }

    BrowserType getType();

    void setCookieRegion(String cookieRegion);

    long getWaitMillis();

    /** Wait on the browser owner thread while allowing browser events to be delivered. */
    default void waitForEvents(long millis) {
        org.rx.core.Extends.sleep(Math.max(0, millis));
    }

    /** Keep an action on its source tab; local browsers may discard only its matching new popups. */
    default void runInCurrentTab(Runnable action, java.util.function.Predicate<String> discardPopup, int settleMillis) {
        action.run();
    }

    /** Follow this action's new tab, replacing its source tab, or follow its same-tab navigation. */
    default boolean runAndFollowNewTab(Runnable action, java.util.function.Predicate<String> acceptedUrl, int timeoutMillis) {
        action.run();
        return false;
    }

    String getCurrentUrl();

    default FluentWait createWait(int timeoutSeconds) {
        return FluentWait.polling(timeoutSeconds * 1000L, getWaitMillis());
    }

    default void navigateBlank() {
        nativeGet(BLANK_URL);
    }

    void navigateUrl(String url) throws TimeoutException;

    void navigateUrl(String url, String locatorSelector) throws TimeoutException;

    void navigateUrl(String url, String locatorSelector, int timeoutSeconds) throws TimeoutException;

    void nativeGet(String url);

    String saveCookies(boolean reset) throws TimeoutException;

    void clearCookies(boolean onlyBrowser);

    void setRawCookie(String rawCookie);

    String getRawCookie();

    /**
     * 基本的 selector，不能包含 :eq(1) 等
     *
     * @param selector CSS 选择器
     * @return 是否存在匹配元素
     */
    boolean hasElement(String selector);

    String elementText(String selector);

    Linq<String> elementsText(String selector);

    String elementVal(String selector);

    Linq<String> elementsVal(String selector);

    String elementAttr(String selector, String... attrArgs);

    Linq<String> elementsAttr(String selector, String... attrArgs);

    void elementClick(String selector);

    void elementClick(String selector, boolean waitElementLocated);

    /** 原生点击打开详情新标签，只读符合条件的最终 URL，然后关闭本次新标签并回到原页。 */
    default String readLinkDestination(String selector, java.util.function.Predicate<String> acceptedUrl, int timeoutSeconds) {
        throw new UnsupportedOperationException("readLinkDestination requires a local browser");
    }

    void elementPress(String selector, String keys);

    void elementPress(String selector, String keys, boolean waitElementLocated);

    void waitElementLocated(String selector) throws TimeoutException;

    void waitElementLocated(String selector, int timeoutSeconds) throws TimeoutException;

    void injectScript(String script);

    //Boolean, Long, String, List, WebElement
    <T> T executeScript(String script, Object... args);

    <T> T injectAndExecuteScript(String injectScript, String script, Object... args);

    <T> T executeConfigureScript(String scriptName, Object... args);

    byte[] screenshotAsBytes(String selector);

    /** 当前标签视口 PNG。不含系统光标。默认实现返回空。 */
    default byte[] captureViewportPng() {
        return new byte[0];
    }

    void focus();

    void maximize();

    void normalize();

    /**
     * 使用浏览器原生 Mouse API 按拟人轨迹移动到指定坐标。
     * 默认空实现，仅 WebBrowser 支持。
     */
    default void mouseMove(double x, double y) {
        // 默认空实现
    }

    /**
     * 使用浏览器原生 Mouse API 按拟人轨迹移动后点击指定坐标。
     * 默认空实现，仅 WebBrowser 支持。
     */
    default void mouseClick(double x, double y) {
        // 默认空实现
    }

    /**
     * 按拟人滚轮动作滚动页面。优先由具体浏览器实现使用原生滚轮 API，必要时兜底 JS。
     */
    default void scrollPage(double deltaY) {
        scrollPage(0, deltaY);
    }

    /**
     * 按拟人滚轮动作滚动页面。deltaY 大于 0 表示向下滚动。
     */
    default void scrollPage(double deltaX, double deltaY) {
        // 默认空实现
    }

    /**
     * 将元素滚动到视口内的相对位置，viewportRatio 取值 0~1，0.5 表示视口中部。
     */
    default boolean scrollToElement(String selector, double viewportRatio) {
        return false;
    }

    /**
     * 使用 Playwright 原生 Mouse API 模拟人工拖拽，从 (startX, startY) 缓慢拖动到 (endX, endY)。
     * 默认空实现，仅 WebBrowser 支持。
     *
     * @param startX 起点 X 坐标
     * @param startY 起点 Y 坐标
     * @param endX   终点 X 坐标
     * @param endY   终点 Y 坐标
     * @param steps  拖拽步数，步数越多越慢越像人类操作
     */
    default void mouseDrag(double startX, double startY, double endX, double endY, int steps) {
        // 默认空实现
    }

    /**
     * 拖动拼图时的落点修正。人拖拼图时会先拖到缺口跟前，停下来看一眼，再一点点挪到对上为止。
     * 这里做同样的事：轨迹走完、松开之前回调，返回应挪到的位置，挪过去后再回调一次。
     * 回调在鼠标按下期间、浏览器线程上执行，只应读取页面状态，不要再发鼠标键盘事件。
     * 轨迹中途不回调：停下来读页面会在轨迹里留下一段不自然的停顿。
     */
    interface DragCourse {
        /**
         * 松开前调用，currentX 是光标当前的 X。返回光标应挪到的 X；不需要再挪就原样返回 currentX。
         */
        double settle(double currentX);
    }

    /**
     * 同 {@link #mouseDrag}，途中经 course 修正落点。默认不支持修正，按原终点拖。
     */
    default void mouseDragTracked(double startX, double startY, double endX, double endY, int steps,
            DragCourse course) {
        mouseDrag(startX, startY, endX, endY, steps);
    }

    /**
     * 在主文档与全部 iframe（含跨源，如京东 JDJRV 验证码）中查找选择器首个可见元素，
     * 返回主帧视口绝对坐标。默认不支持，仅 WebBrowser 实现
     * （Playwright 原生可读跨源 iframe 元素坐标，JS contentDocument 读不到）。
     *
     * @param selector CSS 选择器
     * @return {x, y, width, height, frameUrl}，x/y 为元素中心点；未找到返回 null
     */
    default java.util.Map<String, Object> locateInFrames(String selector) {
        // 默认空实现
        return null;
    }

    /**
     * 在全部 frame 里找宽度落在区间内的可见元素，取最窄的一个。用于拼图滑块圆钮，避免抓到整条滑轨。
     */
    default java.util.Map<String, Object> locateNarrowestInFrames(String selector, double minWidth, double maxWidth) {
        return null;
    }

    /**
     * 在主文档与全部 iframe 中读第一个可见元素的属性值，如拼图块图片的 src。找不到返回 null。
     * 只读属性，不在页面里执行脚本。
     */
    default String attributeInFrames(String selector, String attributeName) {
        return null;
    }

    /**
     * 在主文档与全部 iframe 中截取选择器对应元素。透明像素保留。找不到返回 null。
     */
    default byte[] screenshotInFrames(String selector) {
        return null;
    }

    /**
     * 在主文档与全部 iframe 里用 Playwright 的文本定位找可见元素。
     * exact 为 true 时整段文字必须一致，用来点「登录」而不会点到「账号登录」。
     * 多个匹配时取面积最大的那个。找不到返回 null。
     */
    default java.util.Map<String, Object> locateTextInFrames(String text, boolean exact, double minWidth, double minHeight) {
        return null;
    }

    /**
     * 在包含该选择器的 frame 里点击输入框并键入。找不到或校验失败返回 false。
     */
    default boolean typeInFrames(String selector, String text) {
        return false;
    }

    /**
     * 在锚点（如密码框）正上方最近的可见文本框里键入。用于登录账号框，避免点到页面上别的输入框。
     */
    default boolean typeAboveInFrames(String anchorSelector, String text) {
        return false;
    }

    /**
     * 在跨源 frame 里点击文字本身。用尺寸上下限排除整页容器，点击走 Playwright Locator.click。
     * text 是正则。找不到返回 false。
     */
    default boolean clickTextInFrames(String textPattern, String compactText, double minWidth, double minHeight,
            double maxWidth, double maxHeight) {
        return false;
    }

    /**
     * 在跨源 frame 里点击第一个尺寸合格的可见元素。点击走 Playwright Locator.click。
     */
    default boolean clickInFrames(String selector, double minWidth, double minHeight) {
        return false;
    }

    /**
     * 通过浏览器上下文下载资源（带 cookie/UA，与页面内请求一致）。默认不支持，仅 WebBrowser 实现。
     *
     * @param url 资源地址
     * @return 资源字节内容；非 2xx 抛出异常
     */
    default byte[] downloadAsBytes(String url) {
        throw new UnsupportedOperationException("downloadAsBytes is only supported by WebBrowser");
    }

    /**
     * 对当前聚焦元素按单键（如 Enter），走 Playwright 键盘通道。默认不支持，仅 WebBrowser 实现。
     *
     * @param key 键名（Playwright 键名，如 "Enter"、"Escape"）
     */
    default void pressKey(String key) {
        throw new UnsupportedOperationException("pressKey is only supported by WebBrowser");
    }

    /**
     * 被动记录 URL 含 urlKeyword 的 xhr/fetch/script 响应体；只监听，不改页面、不额外发请求。仅 WebBrowser 实现。
     */
    default void startResponseCapture(String urlKeyword) {
        throw new UnsupportedOperationException("startResponseCapture is only supported by WebBrowser");
    }

    /**
     * 取出并清空已记录的响应体（按到达顺序），继续监听。
     */
    default java.util.List<String> drainResponseCapture(String urlKeyword) {
        throw new UnsupportedOperationException("drainResponseCapture is only supported by WebBrowser");
    }

    default void stopResponseCapture(String urlKeyword) {
    }
}
