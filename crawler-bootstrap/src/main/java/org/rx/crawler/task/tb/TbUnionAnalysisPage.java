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

    public Hit analyze(Browser browser, String input, int timeoutSeconds) throws TimeoutException {
        browser.waitElementLocated(INPUT, timeoutSeconds);
        browser.elementPress(INPUT, input, false);
        browser.elementClick(ANALYZE);
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
