package org.rx.crawler.task.tb;

import lombok.extern.slf4j.Slf4j;
import org.rx.core.Extends;
import org.rx.crawler.service.Browser;

import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 读页面前的短暂停顿和指针移动。不点击购买、推广或验证码。
 * 风控已经打出「访问异常」时，这些动作不会把详情换回来。
 */
@Slf4j
final class TbHumanNoise {
    private TbHumanNoise() {
    }

    static void wander(Browser browser) {
        try {
            Map<String, Object> box = browser.executeScript(
                    "return {w: Math.floor(window.innerWidth||0), h: Math.floor(window.innerHeight||0)};");
            int width = number(box == null ? null : box.get("w"));
            int height = number(box == null ? null : box.get("h"));
            if (width < 480 || height < 320) {
                return;
            }
            ThreadLocalRandom random = ThreadLocalRandom.current();
            int moves = 2 + random.nextInt(3);
            for (int i = 0; i < moves; i++) {
                int x = 90 + random.nextInt(Math.max(1, width - 260));
                int y = 110 + random.nextInt(Math.max(1, height - 240));
                browser.mouseMove(x, y);
            }
            int down = 80 + random.nextInt(160);
            browser.executeScript("window.scrollBy(0," + down + ")");
            Extends.sleep(200 + random.nextInt(400));
            browser.executeScript("window.scrollTo(0,0)");
            Extends.sleep(160 + random.nextInt(280));
        } catch (Exception e) {
            log.debug("TB human noise skipped, error={}", e.getMessage());
        }
    }

    private static int number(Object value) {
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        return 0;
    }
}
