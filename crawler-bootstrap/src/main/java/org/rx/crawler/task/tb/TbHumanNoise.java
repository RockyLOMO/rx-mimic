package org.rx.crawler.task.tb;

import org.rx.crawler.service.Browser;
import org.rx.crawler.task.common.HumanPageNoise;

/**
 * 读页面前的短暂停顿和指针移动。统一委托给 HumanPageNoise。
 */
final class TbHumanNoise {
    private TbHumanNoise() {
    }

    static void wander(Browser browser) {
        HumanPageNoise.applyRandomNoise(browser, "tb-wander");
    }
}
