package org.rx.crawler.task.price;

/** 价格业务枚举。库存数字，中文只写在这里。 */
public final class PriceCodes {
    public static final int JD = 1;
    public static final int TB = 2;

    /** 联盟选品接口或联盟页面卡片。 */
    public static final int SOURCE_UNION = 1;

    /** 0 未知，1 普通售价，3 已说明的到手价或券后价。 */
    public static final int KIND_UNKNOWN = 0;
    public static final int KIND_LIST = 1;
    public static final int KIND_FINAL = 3;

    /** 0 待激活，1 跟踪，2 暂停，3 待人工。 */
    public static final int WATCH_PENDING = 0;
    public static final int WATCH_ACTIVE = 1;
    public static final int WATCH_PAUSED = 2;
    public static final int WATCH_MANUAL = 3;

    /** 0 待解析，1 已验证最终商品页，2 需人工确认。 */
    public static final int IDENTITY_PENDING = 0;
    public static final int IDENTITY_VERIFIED = 1;
    public static final int IDENTITY_MANUAL = 2;

    /** 1 成功，2 未精确命中，3 风控或登录，4 身份不符。 */
    public static final int FETCH_OK = 1;
    public static final int FETCH_MISS = 2;
    public static final int FETCH_RISK = 3;
    public static final int FETCH_IDENTITY = 4;

    /** 0 待复核，1 待发送，2 已发送，3 待重试，4 取消。 */
    public static final int ALERT_REVIEW = 0;
    public static final int ALERT_READY = 1;
    public static final int ALERT_SENT = 2;
    public static final int ALERT_RETRY = 3;
    public static final int ALERT_CANCEL = 4;

    /** 0 待投递，1 投递中，2 成功，3 待重试，4 重试耗尽。 */
    public static final int DELIVERY_PENDING = 0;
    public static final int DELIVERY_IN_PROGRESS = 1;
    public static final int DELIVERY_SUCCESS = 2;
    public static final int DELIVERY_RETRY = 3;
    public static final int DELIVERY_EXHAUSTED = 4;

    public static final String CHANNEL_WEBHOOK = "webhook";
    public static final String CHANNEL_MAIL = "mail";
    public static final String EVENT_PRICE_DROPPED = "price.dropped";

    /** 0 重点，1 普通，2 冷门。 */
    public static final int TIER_FOCUS = 0;
    public static final int TIER_NORMAL = 1;
    public static final int TIER_COLD = 2;

    private PriceCodes() {
    }

    public static int platform(String raw) {
        if (raw == null) {
            return 0;
        }
        String value = raw.trim().toLowerCase();
        if ("jd".equals(value) || "1".equals(value)) {
            return JD;
        }
        if ("tb".equals(value) || "taobao".equals(value) || "tmall".equals(value) || "2".equals(value)) {
            return TB;
        }
        return 0;
    }

    public static String platformKey(int platform) {
        if (platform == JD) {
            return "jd";
        }
        if (platform == TB) {
            return "tb";
        }
        return "";
    }
}
