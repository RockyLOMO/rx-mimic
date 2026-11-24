package org.rx.crawler.task.jd;

import lombok.extern.slf4j.Slf4j;
import org.rx.core.Extends;
import org.rx.crawler.service.Browser;

import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.BiConsumer;
import java.util.function.Predicate;

/**
 * 联盟登录墙出现时，用密钥文件里的京东账号提交登录，再解滑动拼图。
 * 点选、输入、拖动都走 Playwright 或系统鼠标，不在页面里执行点击脚本。
 * 没有密钥文件、找不到登录框或拼图没通过时返回 false，调用方继续等人工。
 */
@Slf4j
public class JdUnionPasswordLogin {
    private static final String ACCOUNT = "input[type='text'],input[type='tel'],input[type='number'],input:not([type])";
    private static final String PASSWORD = "input[type='password']";

    private final JdCaptchaHandler captchaHandler;

    public JdUnionPasswordLogin() {
        this(new JdCaptchaHandler());
    }

    public JdUnionPasswordLogin(JdCaptchaHandler captchaHandler) {
        this.captchaHandler = captchaHandler == null ? new JdCaptchaHandler() : captchaHandler;
    }

    /**
     * 设定后把登录拼图的截图和缺口匹配结果存到该目录，用于排查。默认不保存。
     */
    public JdUnionPasswordLogin setPuzzleDumpDir(java.nio.file.Path dir) {
        captchaHandler.setPuzzleDumpDir(dir);
        return this;
    }

    /**
     * @param stillLogin 当前页是否仍是登录墙。登录完成返回 true。
     */
    public boolean attempt(Browser browser, JdUnionConfig config, BiConsumer<Browser, String> snapshot,
            Predicate<Browser> stillLogin) {
        if (browser == null || config == null || stillLogin == null) {
            return false;
        }
        JdLoginKeys.Credentials credentials = JdLoginKeys.load(config.getLoginKeysFile());
        if (credentials == null) {
            log.info("JD union password login skipped, keys file unavailable");
            return false;
        }
        try {
            if (!captchaHandler.isCaptchaPage(browser) && !loginFormReady(browser)) {
                log.info("JD union login form not found, url={}", browser.getCurrentUrl());
                return false;
            }
            if (!captchaHandler.isCaptchaPage(browser)) {
                if (!browser.typeAboveInFrames(PASSWORD, credentials.username())
                        && !browser.typeInFrames(ACCOUNT, credentials.username())) {
                    log.warn("JD union account field was not filled");
                    return false;
                }
                if (!browser.typeInFrames(PASSWORD, credentials.password())) {
                    log.warn("JD union password field was not filled");
                    return false;
                }
                // 京东点「登录」会先发一次 bind-suspend，把页面加载起的全部鼠标移动一起提交。
                // 填完立刻点、光标一步跳到按钮上，轨迹里就没有中间过程；先把光标自然移开、停一下，
                // 再交给 clickTextInFrames 的连续轨迹点击。
                pauseBeforeSubmit(browser);
                boolean clicked = browser.clickTextInFrames("登\\s*录", "登录", 40, 20, 520, 80);
                if (!clicked) {
                    clicked = browser.clickInFrames("#loginsubmit, .btn-entry, .login-btn", 80, 24);
                }
                if (!clicked) {
                    log.warn("JD union login button was not found");
                    return false;
                }
                log.info("JD union password login submitted");
                waitForCaptchaOrRedirect(browser, stillLogin);
            }
            if (captchaHandler.isCaptchaPage(browser)) {
                doSnapshot(snapshot, browser, "jd-login-puzzle");
                captchaHandler.solvePuzzle(browser, "jd-union-login",
                        Math.max(1, config.getCaptchaMaxAutoAttempts()),
                        Math.max(200, config.nextStepDelayMillis()), snapshot);
            }
            return waitUntilClear(browser, stillLogin);
        } catch (RuntimeException e) {
            log.warn("JD union password login failed, error={}", e.getMessage());
            return false;
        }
    }

    /**
     * 提交前的一次视线移动和停顿。只读密码框坐标，用连续鼠标轨迹移开一点，不执行页面脚本。
     */
    private void pauseBeforeSubmit(Browser browser) {
        try {
            Map<String, Object> field = browser.locateInFrames(PASSWORD);
            if (field != null) {
                browser.mouseMove(number(field.get("x")) + 30, number(field.get("y")) - 18);
            }
        } catch (RuntimeException e) {
            log.debug("JD union pre-submit pointer drift skipped, error={}", e.getMessage());
        }
        Extends.sleep(ThreadLocalRandom.current().nextLong(260, 720));
    }

    private boolean loginFormReady(Browser browser) {
        if (browser.locateInFrames(PASSWORD) != null) {
            return true;
        }
        Map<String, Object> tab = browser.locateTextInFrames("账号登录", true, 40, 16);
        if (tab == null) {
            return false;
        }
        browser.mouseClick(number(tab.get("x")), number(tab.get("y")));
        Extends.sleep(600);
        return browser.locateInFrames(PASSWORD) != null;
    }

    private void waitForCaptchaOrRedirect(Browser browser, Predicate<Browser> stillLogin) {
        for (int i = 0; i < 10; i++) {
            Extends.sleep(500);
            if (!stillLogin.test(browser) || captchaHandler.isCaptchaPage(browser)) {
                return;
            }
        }
    }

    private boolean waitUntilClear(Browser browser, Predicate<Browser> stillLogin) {
        long deadline = System.currentTimeMillis() + 15000L;
        while (System.currentTimeMillis() < deadline) {
            if (!stillLogin.test(browser) && !captchaHandler.isCaptchaPage(browser)) {
                log.info("JD union password login cleared the login wall");
                return true;
            }
            Extends.sleep(500);
        }
        log.info("JD union password login did not clear the login wall, url={}", browser.getCurrentUrl());
        return false;
    }

    private static double number(Object value) {
        return value instanceof Number ? ((Number) value).doubleValue() : 0;
    }

    private static void doSnapshot(BiConsumer<Browser, String> snapshot, Browser browser, String name) {
        if (snapshot != null) {
            snapshot.accept(browser, name);
        }
    }
}
