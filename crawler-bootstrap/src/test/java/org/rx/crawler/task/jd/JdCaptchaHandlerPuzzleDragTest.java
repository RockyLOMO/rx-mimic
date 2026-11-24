package org.rx.crawler.task.jd;

import org.junit.jupiter.api.Test;
import org.rx.crawler.service.Browser;

import java.awt.image.BufferedImage;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.rx.crawler.task.jd.JdPuzzleScenes.Style;

class JdCaptchaHandlerPuzzleDragTest {
    /**
     * 页面校验允许的误差。超了滑钮弹回、验证码还在。
     */
    private static final double PAGE_TOLERANCE = 3;

    @Test
    void knobTravelsFurtherThanThePiece() {
        assertLandsOnTheHole(153, 40, 0.93, false);
    }

    @Test
    void pieceTravelsFurtherThanTheKnob() {
        assertLandsOnTheHole(88, 34, 1.2, false);
    }

    @Test
    void knobAndPieceTravelTheSame() {
        assertLandsOnTheHole(120, 50, 1.0, false);
    }

    @Test
    void inlineImagesGiveTheExactOffset() {
        // 真实页面就是这样：拼图块和背景图都是 src 里的内联 PNG，拼图块的元素截图不透明。
        assertLandsOnTheHole(143, 73, 1.0, true);
    }

    /**
     * @param ratio       页面里拼图块位移和滑钮位移的比例
     * @param inlineImages 背景图和拼图块都是 src 里的内联 PNG，拼图块的元素截图不透明
     */
    private static void assertLandsOnTheHole(int holeX, int holeY, double ratio, boolean inlineImages) {
        FakePage page = new FakePage(holeX, holeY, ratio, inlineImages);

        boolean passed = new JdCaptchaHandler().solvePuzzle(page.browser, "test", 1, 200, null);

        // 位移比不是 1 时，按计划的滑钮行程直接拖会落空，这个用例才算考到了中途修正。
        if (ratio != 1.0) {
            assertTrue(Math.abs(page.plannedDrag * ratio - page.target) > PAGE_TOLERANCE,
                    "planned drag " + page.plannedDrag + " would already land the piece on " + page.target);
        }
        assertTrue(passed, "piece moved " + page.pieceMoved + " of " + page.target
                + ", planned knob drag " + page.plannedDrag);
        assertEquals(page.target, page.pieceMoved, 0.5);
    }

    /**
     * 假的验证码页面。几何照本机真实登录页（显示缩放 1.25），拼图块位移 = 滑钮位移 × ratio。
     */
    private static final class FakePage {
        private static final double BG_LEFT = 1040;
        private static final double BG_TOP = 207.2;
        private static final double BG_WIDTH = 281.4;
        private static final double BG_HEIGHT = 110.6;
        private static final double PIECE_WIDTH = 39.02;
        private static final double PIECE_HEIGHT = 39.02;
        private static final double KNOB_X = 1061.2;
        private static final double KNOB_Y = 348.1;
        private static final double KNOB_WIDTH = 55;

        private final Browser browser = mock(Browser.class);
        private final double ratio;
        private final double target;
        private final double pieceTop;
        private double pieceMoved;
        private double knobMoved;
        private double plannedDrag;
        private boolean open = true;

        private FakePage(int holeX, int holeY, double ratio, boolean inlineImages) {
            JdPuzzleScenes.Scene scene = JdPuzzleScenes.scene(holeX, holeY, Style.MASCOT_ON_DARK_DISK);
            this.ratio = ratio;
            this.target = holeX * (BG_WIDTH / JdPuzzleScenes.BG_WIDTH);
            this.pieceTop = holeY * (BG_HEIGHT / JdPuzzleScenes.BG_HEIGHT);
            BufferedImage piece = inlineImages
                    ? JdPuzzleScenes.composited(scene, 0xff606880) : scene.piece;
            when(browser.getCurrentUrl()).thenReturn("https://union.jd.com/index");
            when(browser.screenshotInFrames(".JDJRV-bigimg img")).thenReturn(scene.backgroundPng());
            when(browser.screenshotInFrames("#small_img")).thenReturn(JdPuzzleScenes.png(piece));
            if (inlineImages) {
                // 原始图和截图同分辨率，拼图块的内部像素就是背景图上缺口处的像素。
                when(browser.attributeInFrames("#small_img", "src")).thenReturn(
                        dataUri(JdPuzzleScenes.png(scene.piece)));
                when(browser.attributeInFrames(".JDJRV-bigimg img", "src")).thenReturn(
                        dataUri(scene.backgroundPng()));
            }
            when(browser.locateInFrames(anyString())).thenAnswer(call -> box(call.getArgument(0)));
            // Mockito 对返回 Map 的方法默认给空 Map，这里页面没有「拖动滑块」之类的文字。
            when(browser.locateTextInFrames(anyString(), anyBoolean(), anyDouble(), anyDouble())).thenReturn(null);
            when(browser.locateNarrowestInFrames(anyString(), anyDouble(), anyDouble()))
                    .thenAnswer(call -> box("knob"));
            doAnswer(call -> {
                drag(call.getArgument(0), call.getArgument(2), call.getArgument(5));
                return null;
            }).when(browser).mouseDragTracked(anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyInt(), any());
        }

        private Map<String, Object> box(String selector) {
            if (selector.startsWith("#small_img")) {
                return open ? box(BG_LEFT + pieceMoved + PIECE_WIDTH / 2, BG_TOP + pieceTop + PIECE_HEIGHT / 2,
                        PIECE_WIDTH, PIECE_HEIGHT) : null;
            }
            if (selector.equals(".JDJRV-bigimg img")) {
                return box(BG_LEFT + BG_WIDTH / 2, BG_TOP + BG_HEIGHT / 2, BG_WIDTH, BG_HEIGHT);
            }
            if (selector.startsWith(".JDJRV-slide")) {
                return box(BG_LEFT + BG_WIDTH / 2, KNOB_Y, BG_WIDTH, 46);
            }
            return box(KNOB_X + knobMoved, KNOB_Y, KNOB_WIDTH, KNOB_WIDTH);
        }

        /**
         * 模拟一次按下、拖动、松开。主轨迹停在目标跟前，代码在松开前反复问落点、挪过去，
         * 直到对上为止。页面里滑钮跟着光标走，拼图块按位移比跟着滑钮走，位置取整。
         */
        private void drag(double startX, double endX, Browser.DragCourse course) {
            plannedDrag = endX - startX;
            double cursor = plannedDrag - 2.2;
            for (int round = 0; round < 4; round++) {
                knobMoved = cursor;
                pieceMoved = Math.round(knobMoved * ratio);
                double release = course.settle(startX + cursor);
                if (Math.abs(release - startX - cursor) < 0.5) {
                    break;
                }
                cursor = release - startX;
            }
            if (Math.abs(pieceMoved - target) <= PAGE_TOLERANCE) {
                open = false;
            } else {
                pieceMoved = 0;
                knobMoved = 0;
            }
        }

        private static String dataUri(byte[] png) {
            return "data:image/png;base64," + Base64.getEncoder().encodeToString(png);
        }

        private static Map<String, Object> box(double centerX, double centerY, double width, double height) {
            Map<String, Object> box = new LinkedHashMap<String, Object>();
            box.put("x", centerX);
            box.put("y", centerY);
            box.put("width", width);
            box.put("height", height);
            box.put("frameUrl", "https://passport.jd.com/common/loginPage");
            return box;
        }
    }
}
