package org.rx.crawler.task.tb;

import org.rx.crawler.service.Browser;
import org.rx.crawler.task.common.ProductInfoDto;
import org.rx.crawler.task.price.ProductIdentityParser;
import org.rx.crawler.task.price.ProductIdentityResolver;

import java.util.Map;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;

/** 商品分析页。只读主商品区域，忽略历史价格与相似推荐；操作使用浏览器原生输入/点击。 */
public final class TbUnionAnalysisPage {
    static final String INPUT = "input[placeholder='请输入要分析的商品链接/商品ID/淘口令']";
    static final String ANALYZE = "button.left-analysic-btn-show";
    static final String PROMOTE = "#detail button[data-spm-click*='locaid=ai_good_promo']";
    static final String PRODUCT_TITLE = "#detail a[href*='uland.taobao.com/item/edetail']";
    private static final Pattern TOKEN = Pattern.compile("[￥¥$₤《(（][A-Za-z0-9]{8,20}[￥¥$₤》)）]");

    private final java.util.function.LongSupplier nanoTime;

    public TbUnionAnalysisPage() { this(System::nanoTime); }
    TbUnionAnalysisPage(java.util.function.LongSupplier nanoTime) { this.nanoTime = nanoTime; }

    public record Hit(ProductInfoDto info, String materialId) { }
    public record MaterialResolution(String materialId, String numericId, String analysisInput) { }

    /** One browser-navigation fallback for links the union analysis page cannot expand itself. */
    public MaterialResolution resolveMaterialWithLinkFallback(Browser browser, String baseUrl, String input,
            int timeoutSeconds, java.util.function.Function<String, VerificationGate> gateForTargetUrl) throws TimeoutException {
        boolean link = input != null && (input.regionMatches(true, 0, "https://", 0, 8)
                || input.regionMatches(true, 0, "http://", 0, 7));
        if (link && !isAllowedProductLink(input)) throw new org.rx.crawler.task.common.TaskApiException(400,
                "INVALID_PRODUCT_LINK", "INVALID_PRODUCT_LINK");
        try {
            String material = resolveMaterial(browser, baseUrl, input, link ? Math.min(15, timeoutSeconds) : timeoutSeconds,
                    gateForTargetUrl.apply(entryUrl(baseUrl, input)));
            return new MaterialResolution(material, ProductIdentityParser.isNumeric(input) ? input : null, input);
        } catch (TimeoutException e) {
            if (!link) throw e;
        } catch (org.rx.crawler.task.common.TaskApiException e) {
            if (!link || !"NO_REBATE_PRODUCT".equals(e.getCode())) throw e;
        }
        String numeric = resolveLinkedProductId(browser, input, Math.min(20, timeoutSeconds), gateForTargetUrl.apply(input));
        String material = resolveMaterial(browser, baseUrl, numeric, timeoutSeconds,
                gateForTargetUrl.apply(entryUrl(baseUrl, numeric)));
        return new MaterialResolution(material, numeric, numeric);
    }

    static boolean isAllowedProductLink(String input) {
        try {
            var uri = java.net.URI.create(input);
            return ("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                    && uri.getUserInfo() == null && uri.getPort() == -1 && uri.getHost() != null
                    && java.util.Set.of("e.tb.cn", "m.tb.cn", "tb.cn", "item.taobao.com", "item.tmall.com", "item.m.taobao.com",
                    "detail.tmall.com", "detail.m.tmall.com", "m.intl.taobao.com", "s.click.taobao.com", "click.taobao.com",
                    "uland.taobao.com").contains(uri.getHost().toLowerCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) { return false; }
    }

    private String resolveLinkedProductId(Browser browser, String input, int timeoutSeconds, VerificationGate verification)
            throws TimeoutException {
        browser.navigateUrl(input, Browser.BODY_SELECTOR, timeoutSeconds);
        long deadline = nanoTime.getAsLong() + java.util.concurrent.TimeUnit.SECONDS.toNanos(Math.max(1, timeoutSeconds));
        do {
            browser.waitForEvents(500);
            long before = nanoTime.getAsLong();
            if (!verification.clear()) throw new org.rx.crawler.task.common.TaskApiException(409,
                    "TB_SLIDER_VERIFICATION_REQUIRED", "TB_SLIDER_VERIFICATION_REQUIRED");
            deadline += nanoTime.getAsLong() - before;
            var hit = ProductIdentityParser.inspect(org.rx.crawler.task.price.PriceCodes.TB, browser.getCurrentUrl());
            if (hit.kind == ProductIdentityParser.PageKind.LOGIN) throw new org.rx.crawler.task.common.TaskApiException(409,
                    "LOGIN_REQUIRED", "LOGIN_REQUIRED");
            if (hit.kind == ProductIdentityParser.PageKind.PRODUCT && ProductIdentityParser.isNumeric(hit.productId)) return hit.productId;
        } while (nanoTime.getAsLong() < deadline);
        throw new org.rx.crawler.task.common.TaskApiException(409, "IDENTITY_UNRESOLVED", "IDENTITY_UNRESOLVED");
    }

    public static boolean isProductInput(String value) {
        if (value == null) return false;
        String input = value.trim();
        return ProductIdentityParser.isNumeric(input) || ProductIdentityResolver.isEncrypted(input)
                || input.toLowerCase(java.util.Locale.ROOT).contains("https://")
                || input.toLowerCase(java.util.Locale.ROOT).contains("http://") || isToken(input);
    }

    public static boolean isToken(String input) { return input != null && TOKEN.matcher(input).find(); }

    public static boolean isAnalysisUrl(String url) {
        try {
            var uri = java.net.URI.create(url);
            return ("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
                    && "pub.alimama.com".equalsIgnoreCase(uri.getHost()) && uri.getUserInfo() == null && uri.getPort() == -1
                    && "/portal/v2/union-lab.htm".equals(uri.getPath()) && uri.getRawQuery() != null
                    && java.util.Arrays.stream(uri.getRawQuery().split("&")).anyMatch(field -> field.equals("pageKey=analysic"));
        } catch (RuntimeException ignored) { return false; }
    }

    public static String entryUrl(String baseUrl, String input) {
        return baseUrl + (baseUrl.contains("?") ? "&" : "?") + "itemId="
                + java.net.URLEncoder.encode(input, java.nio.charset.StandardCharsets.UTF_8);
    }

    public Hit open(Browser browser, String baseUrl, String input, int timeoutSeconds) throws TimeoutException {
        browser.navigateUrl(entryUrl(baseUrl, input), Browser.BODY_SELECTOR, timeoutSeconds);
        return awaitResult(browser, timeoutSeconds);
    }

    /** 数字 ID / 链接 / 口令仅借分析页换物料，结果直接从该页地址栏读取。 */
    public String resolveMaterial(Browser browser, String baseUrl, String input, int timeoutSeconds) throws TimeoutException {
        return resolveMaterial(browser, baseUrl, input, timeoutSeconds, () -> true);
    }

    @FunctionalInterface
    public interface VerificationGate { boolean clear() throws TimeoutException; }

    public String resolveMaterial(Browser browser, String baseUrl, String input, int timeoutSeconds,
            VerificationGate verification) throws TimeoutException {
        browser.navigateUrl(entryUrl(baseUrl, input), Browser.BODY_SELECTOR, timeoutSeconds);
        long deadline = nanoTime.getAsLong() + java.util.concurrent.TimeUnit.SECONDS.toNanos(Math.max(1, timeoutSeconds));
        boolean submitted = false;
        int readyPolls = 0;
        int emptyPolls = 0;
        long emptySince = 0;
        do {
            browser.waitForEvents(500);
            long beforeVerification = nanoTime.getAsLong();
            if (!verification.clear()) throw new org.rx.crawler.task.common.TaskApiException(409,
                    "TB_SLIDER_VERIFICATION_REQUIRED", "TB_SLIDER_VERIFICATION_REQUIRED");
            // Shared handler owns challenge retries/manual waiting; do not spend the URL-change timeout on that wait.
            deadline += nanoTime.getAsLong() - beforeVerification;
            String currentUrl = browser.getCurrentUrl();
            String id = materialFromUrl(currentUrl);
            if (TbUnionPageApi.isMktId(id)) return id;
            String state = isAnalysisUrl(currentUrl) ? analysisState(browser, input) : null;
            readyPolls = "EMPTY".equals(state) || "READY".equals(state) ? readyPolls + 1 : 0;
            if ("EMPTY".equals(state)) {
                emptyPolls++;
            } else {
                emptyPolls = 0;
                emptySince = nanoTime.getAsLong();
            }
            if (readyPolls >= 2 && !submitted) {
                // A ready form may have no empty-state hint at all. Submit once, only after stable readiness.
                browser.elementClick(ANALYZE);
                submitted = true;
                emptyPolls = 0;
                emptySince = nanoTime.getAsLong();
            }
        } while (nanoTime.getAsLong() < deadline);
        // Only a submitted, settled empty result is a business failure. Loading, login and broken pages remain errors.
        if (submitted && emptyPolls >= 3
                && nanoTime.getAsLong() - emptySince >= java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(1500)) {
            throw new org.rx.crawler.task.common.TaskApiException(409,
                    "NO_REBATE_PRODUCT", "非返利商品，暂不支持转链");
        }
        throw new TimeoutException("TB_ANALYSIS_MATERIAL_TIMEOUT");
    }

    static String analysisState(Browser browser, String input) {
        return browser.executeScript("""
                var visible=e=>!!e&&!!(e.offsetWidth||e.offsetHeight||e.getClientRects().length);
                var field=document.querySelector(arguments[0]),button=document.querySelector(arguments[1]);
                if(!visible(field)||!visible(button)||field.value.trim()!==arguments[2].trim())return 'UNKNOWN';
                var body=document.body.innerText||'';
                if(/登录后|请登录|网络异常|网络错误|加载失败|请求失败|系统繁忙|服务异常|访问受限|验证码|滑动验证/.test(body))return 'UNKNOWN';
                if(button.disabled||button.getAttribute('aria-busy')==='true'
                  ||Array.from(document.querySelectorAll('[aria-busy="true"],[role="progressbar"],.next-loading-indicator,.next-loading-tip,.ant-spin-spinning,.next-overlay-backdrop')).some(visible))return 'BUSY';
                var root=document.getElementById('detail');
                if(root&&Array.from(root.querySelectorAll('a[href*="uland.taobao.com/item/edetail"],button[data-spm-click*="ai_good_promo"]')).some(visible))return 'RESULT';
                return /输入商品链接\\/ID[，,、\\s]*智能分析/.test(body)?'EMPTY':'READY';
                """, INPUT, ANALYZE, input);
    }

    static String materialFromUrl(String url) {
        if (!isAnalysisUrl(url)) return "";
        try {
            String query = java.net.URI.create(url).getRawQuery();
            for (String field : query.split("&")) {
                if (field.startsWith("itemId=")) return java.net.URLDecoder.decode(field.substring(7),
                        java.nio.charset.StandardCharsets.UTF_8);
            }
        } catch (IllegalArgumentException ignored) { }
        return "";
    }

    public Hit analyze(Browser browser, String input, int timeoutSeconds) throws TimeoutException {
        browser.waitElementLocated(INPUT, timeoutSeconds);
        browser.elementPress(INPUT, input, false);
        browser.elementClick(ANALYZE);
        return awaitResult(browser, timeoutSeconds);
    }

    /** Continue only on the freshly navigated analysis page, with the shared verification gate. */
    public Hit awaitReadyProduct(Browser browser, int timeoutSeconds, VerificationGate verification) throws TimeoutException {
        return awaitResult(browser, timeoutSeconds, verification, true);
    }

    private Hit awaitResult(Browser browser, int timeoutSeconds) throws TimeoutException {
        return awaitResult(browser, timeoutSeconds, () -> true, false);
    }

    private Hit awaitResult(Browser browser, int timeoutSeconds, VerificationGate verification, boolean aliasesAllowed) throws TimeoutException {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(timeoutSeconds);
        do {
            browser.waitForEvents(500);
            long beforeVerification = System.nanoTime();
            if (!verification.clear()) throw new org.rx.crawler.task.common.TaskApiException(409,
                    "TB_SLIDER_VERIFICATION_REQUIRED", "TB_SLIDER_VERIFICATION_REQUIRED");
            deadline += System.nanoTime() - beforeVerification;
            if (aliasesAllowed && !isAnalysisUrl(browser.getCurrentUrl())) continue;
            // Submission disables the control and rewrites input to a marketing ID after completion.
            // Never accept the previous product while a new analysis is still loading.
            Boolean ready = browser.executeScript("var b=document.querySelector(arguments[0]);return !!b&&!b.disabled;", ANALYZE);
            if (Boolean.TRUE.equals(ready)) {
                String script = """
                        var root=document.getElementById('detail');
                        if(!root)return null;
                        var link=root.querySelector('a[href*="uland.taobao.com/item/edetail"]');
                        var button=root.querySelector('button[data-spm-click*="locaid=ai_good_promo"]');
                        if(!link||!button)return null;
                        var card=button.parentElement;
                        while(card&&card!==root&&(!card.contains(link)||card.innerText.indexOf('佣金率')<0))card=card.parentElement;
                        if(!card||card===root)return null;
                        var shop=root.querySelector('a.shop-title'),image=card.querySelector('img');
                        var href=link.href,id=new URL(href,location.href).searchParams.get('id')||'';
                        var currentInput=document.querySelector('input[placeholder="请输入要分析的商品链接/商品ID/淘口令"]');
                        var addressId=new URL(location.href).searchParams.get('itemId')||'';
                        var marketing=s=>/^[A-Za-z0-9_-]{24,}$/.test(s)&&/[A-Za-z]/.test(s);
                        if(!currentInput||!currentInput.value.trim())return null;
                        if(arguments[0]) {
                            // The site can keep the submitted URL/numeric ID in the input after analysis completes.
                            // Readiness comes from this fresh analysis page's address and main product result.
                            if(location.hostname!=='pub.alimama.com'||location.pathname!=='/portal/v2/union-lab.htm'
                              ||new URL(location.href).searchParams.get('pageKey')!=='analysic'
                              ||!marketing(addressId)||!marketing(id))return null;
                        } else if(currentInput.value!==id||addressId!==id)return null;
                        return {title:link.innerText,shop:shop?shop.innerText:'',image:image?image.src:'',
                            link:href,material:id,text:card.innerText};
                        """;
                Map<String, Object> data = aliasesAllowed ? browser.executeScript(script, true) : browser.executeScript(script);
                if (data != null) {
                    Hit hit = parse(data);
                    if (hit != null) return hit;
                }
            }
        } while (System.nanoTime() < deadline);
        throw new TimeoutException("TB_ANALYSIS_TIMEOUT");
    }

    public void promote(Browser browser) { browser.elementClick(PROMOTE); }

    public String readProductId(Browser browser, int timeoutSeconds) {
        String url = browser.readLinkDestination(PRODUCT_TITLE, candidate -> {
            var kind = ProductIdentityParser.inspect(org.rx.crawler.task.price.PriceCodes.TB, candidate).kind;
            return kind == ProductIdentityParser.PageKind.PRODUCT || kind == ProductIdentityParser.PageKind.LOGIN;
        }, Math.min(30, timeoutSeconds));
        var hit = ProductIdentityParser.inspect(org.rx.crawler.task.price.PriceCodes.TB, url);
        if (hit.kind == ProductIdentityParser.PageKind.LOGIN)
            throw new org.rx.crawler.task.common.TaskApiException(409, "LOGIN_REQUIRED", "LOGIN_REQUIRED");
        if (hit.kind != ProductIdentityParser.PageKind.PRODUCT)
            throw new org.rx.crawler.task.common.TaskApiException(409, "IDENTITY_UNRESOLVED", "IDENTITY_UNRESOLVED");
        return hit.productId;
    }

    static Hit parse(Map<String, Object> data) {
        String title = text(data, "title"), material = text(data, "material");
        if (title.isBlank() || material.isBlank()) return null;
        var info = new ProductInfoDto();
        info.setProductName(title); info.setStoreName(text(data, "shop"));
        info.setImageUrl(text(data, "image")); info.setProductLink(text(data, "link"));
        String content = text(data, "text").replaceAll("\\s+", "");
        var price = Pattern.compile("价格(88VIP价|券后价|到手价|活动价|折后价|预估价)?[￥¥]([0-9,]+(?:\\.[0-9]{1,2})?)(?![0-9.])").matcher(content);
        if (price.find()) {
            info.setPrice(price.group(2).replace(",", ""));
            info.setPriceField("88VIP价".equals(price.group(1)) ? "unionAnalysis88VipPrice" : "unionAnalysisPrice");
        }
        var rate = Pattern.compile("佣金率([0-9]+(?:\\.[0-9]+)?)%").matcher(content);
        if (rate.find()) info.setCommissionRate(rate.group(1) + "%");
        return new Hit(info, material);
    }

    private static String text(Map<String, Object> data, String name) {
        Object value = data.get(name); return value == null ? "" : value.toString().trim();
    }
}
