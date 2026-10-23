package org.rx.crawler.task.price;

import lombok.RequiredArgsConstructor;
import org.rx.crawler.task.common.CustomCrawlStatus;
import org.rx.crawler.task.jd.JdUnionProductItem;
import org.rx.crawler.task.jd.JdUnionProductSearchRequest;
import org.rx.crawler.task.jd.JdUnionProductSearchResult;
import org.rx.crawler.task.jd.JdUnionPromotionTask;
import org.rx.crawler.task.tb.TbPromotionUrlTask;
import org.rx.crawler.task.tb.TbUnionProductItem;
import org.rx.crawler.task.tb.TbUnionProductSearchRequest;
import org.rx.crawler.task.tb.TbUnionProductSearchResult;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
public class BrowserUnionPriceProbe implements UnionPriceProbe {
    private final JdUnionPromotionTask jdTask;
    private final TbPromotionUrlTask tbTask;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private ProductIdentityResolver identities;

    private boolean matches(int platform, String profile, String wanted, String returned, String url) {
        ProductIdentityParser.Hit direct = ProductIdentityParser.inspect(platform, url);
        if (direct.kind == ProductIdentityParser.PageKind.PRODUCT) return wanted.equals(direct.productId);
        if (wanted.equals(returned)) return true;
        String cached = identities == null ? null : identities.cached(platform, profile, returned);
        if (wanted.equals(cached)) return true;
        if (identities != null && ProductIdentityResolver.isEncrypted(returned)) {
            try { return wanted.equals(identities.resolve(platform, profile, returned)); }
            catch (org.rx.crawler.task.common.TaskApiException e) { return false; }
        }
        return false;
    }

    @Override
    public Probe probe(int platform, String productId, String profileName) {
        if (!ProductIdentityParser.isNumeric(productId)) throw new IllegalArgumentException("Numeric productId required");
        if (platform == PriceCodes.JD) {
            return jd(productId, profileName);
        }
        if (platform == PriceCodes.TB) {
            return tb(productId, profileName);
        }
        Probe probe = new Probe();
        probe.setMessage("UNKNOWN_PLATFORM");
        return probe;
    }

    private Probe jd(String productId, String profileName) {
        JdUnionProductSearchRequest request = new JdUnionProductSearchRequest();
        request.setKeyword(productId);
        request.setMaxResults(5);
        request.setProfileName(profileName);
        JdUnionProductSearchResult result = jdTask.searchProducts(request);
        Probe probe = new Probe();
        probe.setStatus(result.getStatus() == null ? CustomCrawlStatus.FAILED : result.getStatus());
        probe.setMessage(result.getMessage() == null ? "" : result.getMessage());
        if (probe.getStatus() != CustomCrawlStatus.SUCCESS) {
            return probe;
        }
        List<JdUnionProductItem> matched = new ArrayList<JdUnionProductItem>();
        if (result.getItems() != null) {
            for (JdUnionProductItem item : result.getItems()) {
                if (item != null && matches(PriceCodes.JD, profileName, productId, item.getSkuId(), item.getProductLink())) {
                    matched.add(item);
                }
            }
        }
        if (matched.size() != 1) {
            probe.setExactMatch(false);
            probe.setMessage(matched.isEmpty() ? "NOT_MATCHED" : "MULTIPLE_MATCHED");
            return probe;
        }
        JdUnionProductItem item = matched.get(0);
        probe.setExactMatch(true);
        probe.setTitle(item.getProductName());
        probe.setShop(item.getStoreName());
        probe.setImage(item.getImageUrl());
        probe.setRawPrice(item.getPrice());
        probe.setPriceField(item.getPriceField());
        return probe;
    }

    private Probe tb(String productId, String profileName) {
        TbUnionProductSearchRequest request = new TbUnionProductSearchRequest();
        request.setKeyword(productId);
        request.setMaxResults(5);
        request.setProfileName(profileName);
        TbUnionProductSearchResult result = tbTask.searchProducts(request);
        Probe probe = new Probe();
        probe.setStatus(result.getStatus() == null ? CustomCrawlStatus.FAILED : result.getStatus());
        probe.setMessage(result.getMessage() == null ? "" : result.getMessage());
        if (probe.getStatus() != CustomCrawlStatus.SUCCESS) {
            return probe;
        }
        List<TbUnionProductItem> matched = new ArrayList<TbUnionProductItem>();
        if (result.getItems() != null) {
            for (TbUnionProductItem item : result.getItems()) {
                if (item != null && matches(PriceCodes.TB, profileName, productId, item.getItemId(), item.getProductLink())) {
                    matched.add(item);
                }
            }
        }
        if (matched.size() != 1) {
            probe.setExactMatch(false);
            probe.setMessage(matched.isEmpty() ? "NOT_MATCHED" : "MULTIPLE_MATCHED");
            return probe;
        }
        TbUnionProductItem item = matched.get(0);
        probe.setExactMatch(true);
        probe.setTitle(item.getProductName());
        probe.setShop(item.getStoreName());
        probe.setImage(item.getImageUrl());
        probe.setRawPrice(item.getPrice());
        probe.setPriceField(item.getPriceField());
        return probe;
    }
}
