package org.rx.crawler.task.price;

import lombok.Data;

@Data
public class PriceDropEvent {
    private String eventId;
    private String type = PriceCodes.EVENT_PRICE_DROPPED;
    private int version = 1;
    private String occurredAt;
    private PriceEventData data;

    @Data
    public static class PriceEventData {
        private String watchId;
        private String platform;
        private String productId;
        private String title;
        private String shopName;
        private String productUrl;
        private String currency = "CNY";
        private String baselinePrice;
        private String currentPrice;
        private String dropAmount;
        private String dropPercent;
        private int priceKind;
        private String priceField;
        private String observedAt;
    }
}
