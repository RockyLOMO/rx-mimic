package org.rx.crawler.task.common;

import org.rx.core.EventPublisher;
import org.rx.crawler.task.jd.JdSearchRequest;
import org.rx.crawler.task.jd.JdSearchResult;
import org.rx.crawler.task.jd.JdUnionPromotionOrdersRequest;
import org.rx.crawler.task.jd.JdUnionPromotionOrdersResult;
import org.rx.crawler.task.tb.TbSearchRequest;
import org.rx.crawler.task.tb.TbSearchResult;
import org.rx.crawler.task.tb.TbPromotionOrdersRequest;
import org.rx.crawler.task.tb.TbPromotionOrdersResult;

import java.util.List;

public interface CustomCrawlRemotingContract extends EventPublisher<CustomCrawlRemotingContract>, AutoCloseable {
    String EVENT_JD_PROMOTION_RESULT = "getJdPromotionUrlResult";
    String EVENT_JD_PROMOTION_URLS_RESULT = "getJdPromotionUrlsResult";
    String EVENT_JD_PROMOTION_ORDERS_RESULT = "getJdPromotionOrdersResult";
    String EVENT_TB_PROMOTION_ORDERS_RESULT = "getTbPromotionOrdersResult";
    String EVENT_TB_PROMOTION_URL_RESULT = "getTbPromotionUrlResult";
    String EVENT_TB_PROMOTION_URLS_RESULT = "getTbPromotionUrlsResult";

    PromotionUrlResult getJdPromotionUrl(PromotionUrlRequest request);

    List<PromotionUrlResult> getJdPromotionUrls(List<String> keywords);

    default JdSearchResult jdSearchProduct(JdSearchRequest request) {
        throw new UnsupportedOperationException();
    }

    JdUnionPromotionOrdersResult getJdPromotionOrders(JdUnionPromotionOrdersRequest request);

    PromotionUrlResult getTbPromotionUrl(PromotionUrlRequest request);

    List<PromotionUrlResult> getTbPromotionUrls(List<String> keywords);

    default TbSearchResult tbSearchProduct(TbSearchRequest request) {
        throw new UnsupportedOperationException();
    }

    TbPromotionOrdersResult getTbPromotionOrders(TbPromotionOrdersRequest request);

    PromotionUrlResult loginCheck(PromotionUrlRequest request);

    String cookiesRaw(String profileName, String url);

    boolean closeProfile(String profileName);

    @Override
    default void close() {
    }
}
