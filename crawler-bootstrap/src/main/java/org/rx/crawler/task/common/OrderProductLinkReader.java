package org.rx.crawler.task.common;

import org.rx.crawler.service.Browser;
import org.rx.crawler.task.price.ProductIdentityParser;

/** Resolves only a current visible order's product link using the existing temporary-tab lifecycle. */
public final class OrderProductLinkReader {
    private OrderProductLinkReader() { }

    public static void resolve(Browser browser, PromotionOrderItem item, int platform, int timeoutSeconds) {
        if (item.getSkuId() != null && item.getSkuId().matches("\\d+")) return;
        var direct = ProductIdentityParser.inspect(platform, item.getProductLink());
        if (direct.kind == ProductIdentityParser.PageKind.PRODUCT) {
            item.setSkuId(direct.productId);
            return;
        }
        if (item.getOrderNo() == null || item.getOrderNo().isBlank()) return;
        String selector = "[data-rx-order-product-link]";
        try {
            Boolean found = browser.executeScript("""
                    document.querySelectorAll('[data-rx-order-product-link]').forEach(e=>e.removeAttribute('data-rx-order-product-link'));
                    const order=String(arguments[0]);
                    const visible=e=>{const r=e.getBoundingClientRect(),s=getComputedStyle(e);return r.width>0&&r.height>0&&s.display!=='none'&&s.visibility!=='hidden';};
                    const rows=Array.from(document.querySelectorAll('tbody tr,.next-table-row,[class*="table-row"],[class*="TableRow"]'));
                    for(const row of rows){
                      if(!visible(row)||!(row.innerText||'').split(/[^0-9]+/).includes(order))continue;
                      const cell=row.querySelector('td,.next-table-cell,[class*="table-cell"],[class*="TableCell"]')||row;
                      const links=Array.from(cell.querySelectorAll('a')).filter(a=>visible(a)&&(a.innerText||'').trim()&&!/店铺|订单|复制|查看/.test(a.innerText));
                      const link=links.find(a=>a.classList.contains('sku-name'))||links[0];
                      if(link){link.setAttribute('data-rx-order-product-link','1');return true;}
                    }
                    return false;
                    """, item.getOrderNo());
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
