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

    public record Hit(ProductInfoDto info, String materialId) { }

    public static boolean isProductInput(String value) {
        if (value == null) return false;
        String input = value.trim();
        return ProductIdentityParser.isNumeric(input) || ProductIdentityResolver.isEncrypted(input)
                || input.toLowerCase(java.util.Locale.ROOT).contains("https://")
                || input.toLowerCase(java.util.Locale.ROOT).contains("http://") || isToken(input);
    }

    public static boolean isToken(String input) { return input != null && TOKEN.matcher(input).find(); }

    public static boolean isAnalysisUrl(String url) {
        return url != null && url.startsWith("https://pub.alimama.com/portal/v2/union-lab.htm?")
                && url.contains("pageKey=analysic");
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
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(Math.max(1, timeoutSeconds));
        do {
            browser.waitForEvents(500);
            long beforeVerification = System.nanoTime();
            if (!verification.clear()) throw new org.rx.crawler.task.common.TaskApiException(409,
                    "TB_SLIDER_VERIFICATION_REQUIRED", "TB_SLIDER_VERIFICATION_REQUIRED");
            // Shared handler owns challenge retries/manual waiting; do not spend the URL-change timeout on that wait.
            deadline += System.nanoTime() - beforeVerification;
            String id = materialFromUrl(browser.getCurrentUrl());
            if (TbUnionPageApi.isMktId(id)) return id;
        } while (System.nanoTime() < deadline);
        throw new TimeoutException("TB_ANALYSIS_MATERIAL_TIMEOUT");
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

    private Hit awaitResult(Browser browser, int timeoutSeconds) throws TimeoutException {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(timeoutSeconds);
        do {
            browser.waitForEvents(500);
            // Submission disables the control and rewrites input to a marketing ID after completion.
            // Never accept the previous product while a new analysis is still loading.
            Boolean ready = browser.executeScript("var b=document.querySelector(arguments[0]);return !!b&&!b.disabled;", ANALYZE);
            if (Boolean.TRUE.equals(ready)) {
                Map<String, Object> data = browser.executeScript("""
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
                        if(!currentInput||currentInput.value!==id||new URL(location.href).searchParams.get('itemId')!==id)return null;
                        return {title:link.innerText,shop:shop?shop.innerText:'',image:image?image.src:'',
                            link:href,material:id,text:card.innerText};
                        """);
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
