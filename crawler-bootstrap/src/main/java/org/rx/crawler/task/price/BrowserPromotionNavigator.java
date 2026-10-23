package org.rx.crawler.task.price;

import lombok.RequiredArgsConstructor;
import org.rx.core.Extends;
import org.rx.core.Strings;
import org.rx.crawler.config.AppConfig;
import org.rx.crawler.service.Browser;
import org.rx.crawler.service.impl.ApiConfigureScriptExecutor;
import org.rx.crawler.service.impl.WebBrowserConfig;
import org.rx.crawler.task.common.BrowserProfileManager;
import org.rx.crawler.task.common.CustomCrawlStatus;
import org.rx.util.BeanMapper;
import org.springframework.stereotype.Service;

import static org.rx.core.Extends.tryClose;

/** 用现有 Chrome profile 打开推广链接。不额外做 bot 预检，避免一次解析算两次访问。 */
@Service
@RequiredArgsConstructor
@lombok.extern.slf4j.Slf4j
public class BrowserPromotionNavigator implements PromotionNavigator {
    private final AppConfig appConfig;
    private final BrowserProfileManager profileManager;

    @Override
    public Nav open(int platform, String profileName, String promotionUrl) {
        Nav nav = new Nav();
        if (promotionUrl == null || promotionUrl.isBlank()) {
            nav.setMessage("PROMOTION_URL_MISSING");
            return nav;
        }
        String profile = profileManager.normalizeProfileName(profileName == null || profileName.isBlank()
                ? profileManager.defaultProfileName() : profileName);
        boolean headless = platform == PriceCodes.TB
                ? appConfig.getCustom().getTbPromotion().isHeadless()
                : appConfig.getCustom().getJdUnion().isHeadless();
        BrowserProfileManager.ProfileLease lease = null;
        try {
            WebBrowserConfig config = BeanMapper.DEFAULT.map(appConfig.getBrowser(), WebBrowserConfig.class);
            config.setProfileDataPath(profileManager.resolveProfileDataPath(profile));
            config.setHeadless(headless);
            config.setFingerprintEnabled(true);
            config.setFingerprintHeadless(headless);
            if (Strings.isEmpty(config.getConfigureScriptExecutorType())) {
                config.setConfigureScriptExecutorType(ApiConfigureScriptExecutor.class.getName());
            }
            lease = profileManager.acquire(profile, config);
            Browser browser = lease.getBrowser();
            browser.navigateUrl(promotionUrl, Browser.BODY_SELECTOR, 30);
            String current = browser.getCurrentUrl();
            String body = "";
            boolean clickedMaterial = false;
            for (int i = 0; i < 15; i++) {
                browser.waitForEvents(800);
                String next = browser.getCurrentUrl();
                if (!clickedMaterial && platform == PriceCodes.JD && next.startsWith("https://jingfen.jd.com/detail/")) {
                    // Read-only selector discovery followed by a native click; no synthesized JS click.
                    Boolean found = browser.executeScript("var a=Array.from(document.querySelectorAll('a')).find(function(a){"
                            + "var r=a.getBoundingClientRect();return r.width>0&&r.height>0&&"
                            + "(/^https?:\\/\\/(item\\.jd\\.com|union-click\\.jd\\.com|u\\.jd\\.com)\\//).test(a.href);});"
                            + "if(a){a.setAttribute('data-rx-material-target','1');return true;}return false;");
                    if (Boolean.TRUE.equals(found)) {
                        browser.elementClick("[data-rx-material-target='1']");
                        clickedMaterial = true;
                        continue;
                    }
                }
                ProductIdentityParser.Hit hit = ProductIdentityParser.inspect(platform, next);
                if (hit.kind == ProductIdentityParser.PageKind.LOGIN) {
                    nav.setStatus(CustomCrawlStatus.LOGIN_REQUIRED);
                    nav.setPageKind(hit.kind);
                    nav.setFinalUrl(next);
                    nav.setMessage("LOGIN_REQUIRED");
                    return nav;
                }
                if (hit.kind == ProductIdentityParser.PageKind.PRODUCT && next.equals(current)) {
                    body = bodyText(browser);
                    return product(nav, next, hit.productId, body);
                }
                current = next;
            }
            ProductIdentityParser.Hit last = ProductIdentityParser.inspect(platform, current);
            log.info("Product identity navigation incomplete, platform={}, host={}, kind={}, shape={}, bodyFlags={}",
                    platform, safeHost(current), last.kind, safeShape(current), bodyFlags(bodyText(browser)));
            nav.setFinalUrl(current);
            nav.setPageKind(last.kind);
            if (last.kind == ProductIdentityParser.PageKind.PRODUCT) {
                return product(nav, current, last.productId, bodyText(browser));
            }
            nav.setStatus(CustomCrawlStatus.SUCCESS);
            nav.setMessage(last.kind == ProductIdentityParser.PageKind.LOGIN ? "LOGIN_REQUIRED" : "UNRESOLVED");
            if (last.kind == ProductIdentityParser.PageKind.LOGIN) {
                nav.setStatus(CustomCrawlStatus.LOGIN_REQUIRED);
            }
            return nav;
        } catch (Exception e) {
            nav.setStatus(CustomCrawlStatus.FAILED);
            nav.setMessage("NAVIGATE_FAILED");
            log.info("Product identity navigation failed, platform={}, errorType={}", platform, e.getClass().getSimpleName());
            return nav;
        } finally {
            tryClose(lease);
        }
    }

    private Nav product(Nav nav, String url, String productId, String body) {
        nav.setStatus(CustomCrawlStatus.SUCCESS);
        nav.setPageKind(ProductIdentityParser.PageKind.PRODUCT);
        nav.setFinalUrl(url);
        nav.setProductId(productId);
        nav.setBodyText(body);
        nav.setMessage("RESOLVED");
        return nav;
    }

    private String bodyText(Browser browser) {
        try {
            String body = browser.executeScript("return document.body ? document.body.innerText : '';");
            if (body == null) {
                return "";
            }
            return body.length() <= 2000 ? body : body.substring(0, 2000);
        } catch (Exception e) {
            return "";
        }
    }

    private String safeHost(String url) {
        try { return java.net.URI.create(url).getHost(); } catch (Exception e) { return "unknown"; }
    }
    private String bodyFlags(String body) {
        return "login=" + body.contains("登录") + ",verify=" + (body.contains("验证") || body.contains("滑块"))
                + ",app=" + body.contains("APP") + ",unavailable=" + (body.contains("失效") || body.contains("不存在"));
    }
    private String safeShape(String url) {
        try {
            java.net.URI uri = java.net.URI.create(url);
            return uri.getPath().replaceAll("[A-Za-z0-9_-]{16,}", "TOKEN").replaceAll("[0-9]+", "N")
                    + " keys=" + (uri.getRawQuery() == null ? "" : java.util.Arrays.stream(uri.getRawQuery().split("&"))
                    .map(part -> part.split("=", 2)[0]).filter(key -> key.matches("[a-zA-Z_]{1,24}"))
                    .limit(12).collect(java.util.stream.Collectors.joining(",")));
        } catch (Exception e) { return "unknown"; }
    }
}
