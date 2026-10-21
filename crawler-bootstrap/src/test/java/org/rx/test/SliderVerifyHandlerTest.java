package org.rx.test;

import org.junit.jupiter.api.Test;
import org.rx.crawler.task.tb.SliderVerifyHandler;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class SliderVerifyHandlerTest {
    @Test
    public void slideDistanceRestsPastTheTrackEnd() {
        double trackLeft = 400;
        double handleWidth = 42;
        double trackWidth = 300;
        double startX = trackLeft + handleWidth / 2.0;
        double distance = SliderVerifyHandler.slideDistancePx(startX, handleWidth, trackLeft, trackWidth);
        double handleRight = startX + distance + handleWidth / 2.0;
        assertEquals(8.0, handleRight - (trackLeft + trackWidth), 0.01);
        assertTrue(distance > trackWidth - handleWidth);
    }

    @Test
    public void handleMustSitOnTheLeftBeforeDrag() {
        Map<String, Object> fresh = geometry(400, 400, 42, true);
        Map<String, Object> mid = geometry(430, 400, 42, true);
        assertTrue(SliderVerifyHandler.handleReadyAtLeft(fresh));
        assertFalse(SliderVerifyHandler.handleReadyAtLeft(mid));
        assertFalse(SliderVerifyHandler.handleReadyAtLeft(null));
    }

    @Test
    public void safeUrlDropsPunishTokenAndHintSeesEnglishRefresh() {
        String safe = SliderVerifyHandler.safeUrl("https://pub.alimama.com/punish?x5secdata=secret-token");
        assertEquals("https://pub.alimama.com/punish", safe);
        assertFalse(safe.contains("secret-token"));
        assertEquals("please-refresh", SliderVerifyHandler.pageHintText(
                "Oops... something's wrong. Please refresh and try again."));
        assertEquals("verify-failed", SliderVerifyHandler.pageHintText("验证失败，点击框体重试"));
    }

    private static Map<String, Object> geometry(double handleLeft, double trackLeft, double width, boolean trackKnown) {
        Map<String, Object> values = new HashMap<String, Object>();
        values.put("handleLeft", handleLeft);
        values.put("trackLeft", trackLeft);
        values.put("width", width);
        values.put("trackKnown", trackKnown);
        return values;
    }
}
