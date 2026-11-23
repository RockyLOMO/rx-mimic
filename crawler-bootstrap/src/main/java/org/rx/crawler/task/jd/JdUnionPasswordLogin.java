package org.rx.crawler.task.jd;

import lombok.extern.slf4j.Slf4j;
import org.rx.core.Extends;
import org.rx.crawler.service.Browser;

import java.util.Map;
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
                if (!browser.typeInFrames(ACCOUNT, credentials.username())) {
                    log.warn("JD union account field was not filled");
                    return false;
                }
                if (!browser.typeInFrames(PASSWORD, credentials.password())) {
                    log.warn("JD union password field was not filled");
                    return false;
                }
                Map<String, Object> submit = browser.locateTextInFrames("登录", true, 80, 24);
                if (submit == null) {
                    log.warn("JD union login button was not found");
                    return false;
                }
                browser.mouseClick(number(submit.get("x")), number(submit.get("y")));
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
        long deadline = System.currentTimeMillis() + 8000L;
        while (System.currentTimeMillis() < deadline) {
            if (!stillLogin.test(browser)) {
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
