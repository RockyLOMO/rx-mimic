package org.rx.crawler.task.order;

import org.rx.crawler.service.Browser;
import org.rx.crawler.task.common.HumanPageNoise;

/**
 * 订单页面拟人化操作噪点工具，统一桥接至 {@link HumanPageNoise}。
 */
public final class OrderHumanNoise {

    private OrderHumanNoise() {
    }

    public static HumanPageNoise.NoisePattern applyDifferentPageNoise(Browser browser) {
        return HumanPageNoise.applyRandomNoise(browser, "order-page");
    }

    public static void applyPaginationNoise(Browser browser, int pageNo) {
        HumanPageNoise.applyPaginationNoise(browser, pageNo);
    }
}
