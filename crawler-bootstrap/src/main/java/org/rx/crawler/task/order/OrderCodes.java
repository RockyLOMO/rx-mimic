package org.rx.crawler.task.order;

public final class OrderCodes {
    public static final String PLATFORM_JD = "jd";
    public static final String PLATFORM_TB = "tb";

    public static final int DELIVERY_PENDING = 0;
    public static final int DELIVERY_IN_PROGRESS = 1;
    public static final int DELIVERY_SUCCESS = 2;
    public static final int DELIVERY_RETRY = 3;
    public static final int DELIVERY_EXHAUSTED = 4;

    public static final String CHANNEL_WEBHOOK = "webhook";
    public static final String EVENT_ORDER_CREATED = "order.created";

    private OrderCodes() {
    }
}
