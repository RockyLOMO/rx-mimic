package org.rx.crawler.task.common;

import lombok.extern.slf4j.Slf4j;
import org.rx.core.Extends;
import org.rx.crawler.service.Browser;

import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 统一任务页面拟人化操作噪点工具。
 * 针对各爬虫任务页面（订单、商品详情、转链、搜索等），在不同访问批次中采取不同的随机拟人交互策略
 * （鼠标随机路径漫游、列表查验滚动、视线微动抖动、深度浏览平滑回滚、翻页拟人停顿等），
 * 防止反爬系统基于无交互、固定间隔、直线光标等特征判定为自动化脚本。
 */
@Slf4j
public final class HumanPageNoise {

    private HumanPageNoise() {
    }

    public enum NoisePattern {
        /** 顶部导航与搜索/筛选区域巡视扫描 */
        SCAN_NAVIGATION,
        /** 内容/表格/商品卡片查验浏览滚动 */
        CONTENT_INSPECTION,
        /** 迟疑微动与视线聚焦（小幅度光标抖动与短暂停顿） */
        HESITATION_MICRO_MOVES,
        /** 深度滑动浏览与中性空白停歇 */
        PAGE_BROWSE_AND_REST
    }

    /**
     * 在页面上应用随机选取的拟人操作噪点模式。
     *
     * @param browser 浏览器会话
     * @return 本次应用的模式
     */
    public static NoisePattern applyRandomNoise(Browser browser) {
        return applyRandomNoise(browser, "default");
    }

    /**
     * 在页面上应用随机选取的拟人操作噪点模式，附带上下文标识。
     *
     * @param browser 浏览器会话
     * @param context 触发上下文（如 jd-orders, tb-orders, jd-mall-detail 等）
     * @return 本次应用的模式
     */
    public static NoisePattern applyRandomNoise(Browser browser, String context) {
        NoisePattern[] patterns = NoisePattern.values();
        NoisePattern chosen = patterns[ThreadLocalRandom.current().nextInt(patterns.length)];
        if (browser == null) {
            return chosen;
        }
        applyPattern(browser, chosen, context);
        return chosen;
    }

    /**
     * 分页请求或多步操作之间的拟人等待与微动噪点。
     *
     * @param browser 浏览器会话
     * @param stepOrPage 即将执行的页码或步骤序号
     */
    public static void applyPaginationNoise(Browser browser, int stepOrPage) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        // 拟人思考与翻页阅读停顿 800ms ~ 2400ms
        int delay = 800 + random.nextInt(1600);
        Extends.sleep(delay);

        if (browser == null) {
            return;
        }
        try {
            ViewportSize vp = getViewport(browser);
            int x = 120 + random.nextInt(Math.max(1, vp.width - 240));
            int y = 200 + random.nextInt(Math.max(1, vp.height - 300));
            browser.mouseMove(x, y);

            // 50% 概率触发小幅滚动抖动
            if (random.nextBoolean()) {
                int deltaY = -40 + random.nextInt(90);
                browser.scrollPage(0, deltaY);
                Extends.sleep(120 + random.nextInt(250));
            }
        } catch (Exception e) {
            log.debug("Pagination noise skipped: {}", e.getMessage());
        }
    }

    /**
     * 拟人短暂停顿。
     */
    public static void applyPacing(int minMillis, int maxMillis) {
        if (maxMillis <= minMillis) {
            Extends.sleep(Math.max(0, minMillis));
            return;
        }
        Extends.sleep(ThreadLocalRandom.current().nextInt(minMillis, maxMillis + 1));
    }

    private static void applyPattern(Browser browser, NoisePattern pattern, String context) {
        try {
            ViewportSize vp = getViewport(browser);
            ThreadLocalRandom random = ThreadLocalRandom.current();
            log.info("Applying human page noise: context={}, pattern={}, viewport={}x{}",
                    context, pattern, vp.width, vp.height);

            switch (pattern) {
                case SCAN_NAVIGATION -> {
                    // 模拟视线扫过顶部菜单/筛选区域
                    int steps = 2 + random.nextInt(3);
                    for (int i = 0; i < steps; i++) {
                        int x = 100 + (i * ((vp.width - 250) / steps)) + random.nextInt(60);
                        int y = 80 + random.nextInt(80);
                        browser.mouseMove(x, y);
                        Extends.sleep(120 + random.nextInt(260));
                    }
                    // 微幅滚轮
                    int down = 60 + random.nextInt(120);
                    browser.scrollPage(0, down);
                    Extends.sleep(200 + random.nextInt(400));
                    browser.scrollPage(0, -down);
                    Extends.sleep(150 + random.nextInt(300));
                }
                case CONTENT_INSPECTION -> {
                    // 模拟鼠标移至内容主体/卡片/表格区域查阅
                    int startX = 220 + random.nextInt(Math.max(1, vp.width - 450));
                    int startY = 250 + random.nextInt(150);
                    browser.mouseMove(startX, startY);
                    Extends.sleep(180 + random.nextInt(300));

                    // 滚动浏览列表行或内容
                    int scrollY = 160 + random.nextInt(260);
                    browser.scrollPage(0, scrollY);
                    // 查阅停留
                    Extends.sleep(400 + random.nextInt(600));

                    // 移动到另一内容位置
                    int endX = 180 + random.nextInt(Math.max(1, vp.width - 380));
                    int endY = 320 + random.nextInt(180);
                    browser.mouseMove(endX, endY);
                    Extends.sleep(220 + random.nextInt(350));

                    // 滚回顶部
                    browser.scrollPage(0, -scrollY);
                    Extends.sleep(180 + random.nextInt(280));
                }
                case HESITATION_MICRO_MOVES -> {
                    // 模拟人在操作前的犹豫与光标微小漂移
                    int baseX = 250 + random.nextInt(Math.max(1, vp.width - 500));
                    int baseY = 200 + random.nextInt(Math.max(1, vp.height - 400));
                    browser.mouseMove(baseX, baseY);
                    Extends.sleep(150 + random.nextInt(200));

                    int twitches = 3 + random.nextInt(4);
                    for (int i = 0; i < twitches; i++) {
                        int jitterX = baseX + (-18 + random.nextInt(37));
                        int jitterY = baseY + (-15 + random.nextInt(31));
                        browser.mouseMove(jitterX, jitterY);
                        Extends.sleep(80 + random.nextInt(160));
                    }

                    // 偶尔微幅滚动
                    if (random.nextBoolean()) {
                        browser.scrollPage(0, -30 + random.nextInt(70));
                    }
                    Extends.sleep(300 + random.nextInt(500));
                }
                case PAGE_BROWSE_AND_REST -> {
                    // 较深幅度滑动到底部再回到上方，移至空白边距停歇
                    int scrollDown = 280 + random.nextInt(380);
                    browser.scrollPage(0, scrollDown);
                    Extends.sleep(500 + random.nextInt(700));

                    // 移至左侧或右侧中性空白区
                    int neutralX = random.nextBoolean() ? (40 + random.nextInt(60)) : (vp.width - 60 - random.nextInt(40));
                    int neutralY = 180 + random.nextInt(250);
                    browser.mouseMove(neutralX, neutralY);
                    Extends.sleep(250 + random.nextInt(450));

                    // 平滑回滚
                    browser.executeScript("window.scrollTo({top: 0, behavior: 'smooth'})");
                    Extends.sleep(300 + random.nextInt(500));
                }
            }
        } catch (Exception e) {
            log.debug("Human noise execution skipped, context={}, pattern={}, error={}",
                    context, pattern, e.getMessage());
        }
    }

    private static ViewportSize getViewport(Browser browser) {
        try {
            Map<String, Object> box = browser.executeScript(
                    "return {w: Math.floor(window.innerWidth||0), h: Math.floor(window.innerHeight||0)};");
            int width = number(box == null ? null : box.get("w"));
            int height = number(box == null ? null : box.get("h"));
            if (width >= 480 && height >= 320) {
                return new ViewportSize(width, height);
            }
        } catch (Exception ignored) {
        }
        return new ViewportSize(1280, 800);
    }

    private static int number(Object value) {
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        return 0;
    }

    private record ViewportSize(int width, int height) {
    }
}
