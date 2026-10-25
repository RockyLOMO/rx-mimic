package org.rx.crawler.task.common;

import org.rx.crawler.service.Browser;
import org.rx.crawler.task.price.ProductIdentityParser;
import java.util.Map;

/** Resolves only a current visible order's product link using the existing temporary-tab lifecycle. */
public final class OrderProductLinkReader {
    private OrderProductLinkReader() { }

    public static void resolve(Browser browser, PromotionOrderItem item, int platform, int timeoutSeconds) {
        resolve(browser, item, platform, timeoutSeconds, null);
    }

    /** The caller owns a page-scoped cache: never reuse another account or order page's observations. */
    public static void resolve(Browser browser, PromotionOrderItem item, int platform, int timeoutSeconds,
            Map<String, String> resolvedProducts) {
        if (item.getSkuId() != null && item.getSkuId().matches("\\d+")) return;
        var direct = ProductIdentityParser.inspect(platform, item.getProductLink());
        if (direct.kind == ProductIdentityParser.PageKind.PRODUCT) {
            item.setSkuId(direct.productId);
            return;
        }
        if (item.getOrderNo() == null || item.getOrderNo().isBlank()) return;
        String originalLink = item.getProductLink();
        String cacheKey = originalLink == null || originalLink.isBlank() || originalLink.startsWith("javascript:")
                ? null : platform + ":" + originalLink;
        if (resolvedProducts != null && cacheKey != null) {
            String cached = resolvedProducts.get(cacheKey);
            var hit = ProductIdentityParser.inspect(platform, cached);
            if (hit.kind == ProductIdentityParser.PageKind.PRODUCT) {
                item.setSkuId(hit.productId);
                item.setProductLink(cached);
                item.getExtensions().remove("productIdentityError");
                return;
            }
        }
        String selector = "[data-rx-order-product-link]";
        try {
            Boolean found = browser.executeScript("""
                    document.querySelectorAll('[data-rx-order-product-link]').forEach(e=>e.removeAttribute('data-rx-order-product-link'));
                    const order=String(arguments[0]);
                    const normalize=s=>String(s||'').replace(/\\s+/g,' ').trim();
                    const productName=normalize(arguments[1]),productLink=String(arguments[2]||'');
                    const visible=e=>{const r=e.getBoundingClientRect(),s=getComputedStyle(e);return r.width>0&&r.height>0&&s.display!=='none'&&s.visibility!=='hidden';};
                    const rows=Array.from(document.querySelectorAll('tbody tr,.next-table-row,[class*="table-row"],[class*="TableRow"]'));
                    for(const row of rows){
                      if(!visible(row)||!(row.innerText||'').split(/[^0-9]+/).includes(order))continue;
                      const cell=row.querySelector('td,.next-table-cell,[class*="table-cell"],[class*="TableCell"]')||row;
                      const links=Array.from(cell.querySelectorAll('a')).filter(a=>visible(a)&&normalize(a.innerText));
                      // Match the parsed current product before any generic link. Product titles may
                      // legitimately contain words such as 店铺/订单; substring blacklists lose them.
                      let link=productLink&&!productLink.startsWith('javascript:')
                        ? links.find(a=>a.getAttribute('href')===productLink||a.href===productLink) : null;
                      if(!link&&productName)link=links.find(a=>normalize(a.innerText)===productName||normalize(a.getAttribute('title'))===productName);
                      if(!link&&!productName&&(!productLink||productLink.startsWith('javascript:'))){
                        link=links.find(a=>a.classList.contains('sku-name'))
                          ||links.find(a=>!/^(?:店铺|订单|复制|查看)(?:详情)?$/.test(normalize(a.innerText)));
                      }
                      if(link){link.setAttribute('data-rx-order-product-link','1');return true;}
                    }
                    return false;
                    """, item.getOrderNo(), item.getProductName(), originalLink);
            if (!Boolean.TRUE.equals(found)) {
                item.getExtensions().put("productIdentityError", "PRODUCT_LINK_NOT_VISIBLE");
                return;
            }
            String destination = browser.readLinkDestination(selector, url -> {
                var kind = ProductIdentityParser.inspect(platform, url).kind;
                return kind == ProductIdentityParser.PageKind.PRODUCT || kind == ProductIdentityParser.PageKind.LOGIN;
            }, Math.max(1, timeoutSeconds));
            var resolved = ProductIdentityParser.inspect(platform, destination);
            if (resolved.kind == ProductIdentityParser.PageKind.PRODUCT) {
                item.setProductLink(destination);
                item.setSkuId(resolved.productId);
                item.getExtensions().remove("productIdentityError");
                if (resolvedProducts != null && cacheKey != null && resolvedProducts.size() < 100) {
                    resolvedProducts.put(cacheKey, destination);
                }
            } else {
                item.getExtensions().put("productIdentityError", resolved.kind == ProductIdentityParser.PageKind.LOGIN
                        ? "PRODUCT_LOGIN_REQUIRED" : "PRODUCT_IDENTITY_UNRESOLVED");
            }
        } catch (Exception e) {
            item.getExtensions().put("productIdentityError", "PRODUCT_LINK_READ_FAILED");
        } finally {
            try { browser.executeScript("document.querySelectorAll('[data-rx-order-product-link]').forEach(e=>e.removeAttribute('data-rx-order-product-link'));return true;"); }
            catch (Exception ignored) { }
        }
    }
}
