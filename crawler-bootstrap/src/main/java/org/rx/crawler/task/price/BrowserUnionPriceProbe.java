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

    @Override
    public Probe probe(int platform, String unionProductId, String profileName) {
        if (platform == PriceCodes.JD) {
            return jd(unionProductId, profileName);
        }
        if (platform == PriceCodes.TB) {
            return tb(unionProductId, profileName);
        }
        Probe probe = new Probe();
        probe.setMessage("UNKNOWN_PLATFORM");
        return probe;
    }

    private Probe jd(String unionProductId, String profileName) {
        JdUnionProductSearchRequest request = new JdUnionProductSearchRequest();
        request.setKeyword(unionProductId);
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
                if (item != null && unionProductId.equals(item.getSkuId())) {
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

    private Probe tb(String unionProductId, String profileName) {
        TbUnionProductSearchRequest request = new TbUnionProductSearchRequest();
        request.setKeyword(unionProductId);
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
                if (item != null && unionProductId.equals(item.getItemId())) {
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
