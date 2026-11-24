package org.rx.crawler.task.jd;

import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.rx.crawler.task.jd.JdPuzzleScenes.PIECE_HEIGHT;
import static org.rx.crawler.task.jd.JdPuzzleScenes.PIECE_WIDTH;
import static org.rx.crawler.task.jd.JdPuzzleScenes.Style;

public class JdPuzzleGapTest {
    @Test
    public void locateFindsTheDarkenedPuzzleHole() {
        int holeX = 168;
        int holeY = 36;
        int pieceW = 52;
        int pieceH = 48;
        BufferedImage background = new BufferedImage(280, 150, BufferedImage.TYPE_INT_ARGB);
        BufferedImage piece = new BufferedImage(pieceW, pieceH, BufferedImage.TYPE_INT_ARGB);
        boolean[][] shape = puzzleShape(pieceW, pieceH);
        for (int y = 0; y < background.getHeight(); y++) {
            for (int x = 0; x < background.getWidth(); x++) {
                int shade = 70 + (x * 3 + y) % 90;
                int rgb = 0xff000000 | (shade << 16) | ((shade + 20) << 8) | (shade + 10);
                if (x % 17 == 0) {
                    rgb = 0xff202020;
                }
                background.setRGB(x, y, rgb);
            }
        }
        // 左侧一条更深的竖线，用来挡住“最暗列就是缺口”的假匹配。
        for (int y = 0; y < background.getHeight(); y++) {
            background.setRGB(28, y, 0xff101010);
            background.setRGB(29, y, 0xff101010);
        }
        for (int y = 0; y < pieceH; y++) {
            for (int x = 0; x < pieceW; x++) {
                if (!shape[y][x]) {
                    continue;
                }
                int px = holeX + x;
                int py = holeY + y;
                int original = background.getRGB(px, py);
                piece.setRGB(x, y, original);
                boolean border = JdPuzzleScenes.isShapeBorder(shape, pieceW, pieceH, x, y);
                int darkened = border ? 0xff404040 : darken(original);
                background.setRGB(px, py, darkened);
            }
        }

        JdPuzzleGap.Match match = JdPuzzleGap.locate(background, piece);

        assertTrue(match.found(), "reason=" + match.reason);
        assertTrue(Math.abs(match.x - holeX) <= 2, "gap x=" + match.x);
        assertTrue(Math.abs(match.y - holeY) <= 1, "gap y=" + match.y);
    }

    @Test
    public void brightMascotBesideTheHoleDoesNotStealTheMatch() {
        JdPuzzleScenes.Scene scene = JdPuzzleScenes.scene(88, 34, Style.MASCOT_ON_DARK_DISK);

        JdPuzzleGap.Match match = JdPuzzleGap.locate(scene.background, scene.piece);

        assertTrue(match.found(), "reason=" + match.reason);
        assertTrue(Math.abs(match.x - 88) <= 2, "gap x=" + match.x);
        assertTrue(Math.abs(match.y - 34) <= 1, "gap y=" + match.y);
        assertTrue(match.coverage >= 0.9, "coverage=" + match.coverage);
    }

    @Test
    public void holeOverABusyMascotIsStillFound() {
        JdPuzzleScenes.Scene scene = JdPuzzleScenes.scene(153, 40, Style.MASCOT_UNDER_HOLE);

        JdPuzzleGap.Match match = JdPuzzleGap.locate(scene.background, scene.piece);

        assertTrue(match.found(), "reason=" + match.reason);
        assertTrue(Math.abs(match.x - 153) <= 2, "gap x=" + match.x);
        assertTrue(Math.abs(match.y - 40) <= 1, "gap y=" + match.y);
    }

    @Test
    public void hollowOutlinePieceIsFound() {
        // 有的拼图块只有描边不透明，中间是透明或半透明的。
        JdPuzzleScenes.Scene scene = JdPuzzleScenes.scene(120, 50, Style.MASCOT_ON_DARK_DISK);
        boolean[][] shape = JdPuzzleScenes.pieceShape();
        BufferedImage hollow = new BufferedImage(PIECE_WIDTH, PIECE_HEIGHT, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < PIECE_HEIGHT; y++) {
            for (int x = 0; x < PIECE_WIDTH; x++) {
                if (shape[y][x] && JdPuzzleScenes.isShapeBorder(shape, PIECE_WIDTH, PIECE_HEIGHT, x, y)) {
                    hollow.setRGB(x, y, scene.piece.getRGB(x, y));
                }
            }
        }

        JdPuzzleGap.Match match = JdPuzzleGap.locate(scene.background, hollow);

        assertTrue(match.found(), "reason=" + match.reason);
        assertTrue(Math.abs(match.x - 120) <= 2, "gap x=" + match.x);
    }

    @Test
    public void blankPieceHasNoGap() {
        BufferedImage background = new BufferedImage(200, 80, BufferedImage.TYPE_INT_RGB);
        BufferedImage piece = new BufferedImage(40, 40, BufferedImage.TYPE_INT_ARGB);
        assertEquals(-1, JdPuzzleGap.locate(background, piece).x);
    }

    @Test
    public void opaquePieceScreenshotIsFoundByItsOutline() {
        // 元素截图没有透明通道：块内颜色和底色相近，最左一列还是页面白底。形状靠描边和「与边缘主色不同」切出来。
        JdPuzzleScenes.Scene scene = JdPuzzleScenes.scene(120, 50, Style.MASCOT_ON_DARK_DISK);
        BufferedImage shot = JdPuzzleScenes.composited(scene, 0xff606880);

        JdPuzzleGap.Match match = JdPuzzleGap.locate(scene.background, shot, 50);

        assertTrue(match.found(), "reason=" + match.reason);
        assertTrue(Math.abs(match.x - 120) <= 2, "gap x=" + match.x);
        assertTrue(Math.abs(match.y - 50) <= 1, "gap y=" + match.y);
    }

    @Test
    public void realAlphaShapeFindsTheHoleWhenTheScreenshotCannotShowIt() {
        // 光芒图里块内外是同一套颜色，只能用拼图块原始图片的透明通道当形状。
        JdPuzzleScenes.Scene scene = JdPuzzleScenes.scene(143, 73, Style.SUNBURST);
        BufferedImage shot = JdPuzzleScenes.composited(scene, 0xff606880);
        boolean[][] shape = JdPuzzleGap.shapeFromAlpha(JdPuzzleScenes.natural(scene, 40),
                shot.getWidth(), shot.getHeight());

        assertTrue(shape != null, "the natural piece carries alpha, so a shape must come out of it");
        JdPuzzleGap.Match match = JdPuzzleGap.locate(scene.background, shape, shot.getWidth(), shot.getHeight(), 73);

        assertTrue(match.found(), "reason=" + match.reason);
        assertTrue(Math.abs(match.x - 143) <= 2, "gap x=" + match.x);
        assertTrue(Math.abs(match.y - 73) <= 1, "gap y=" + match.y);
    }

    @Test
    public void alphaShapeIsNotTakenFromAFullyOpaqueImage() {
        BufferedImage opaque = new BufferedImage(40, 40, BufferedImage.TYPE_INT_RGB);

        assertNull(JdPuzzleGap.shapeFromAlpha(opaque, 50, 49));
        assertNull(JdPuzzleGap.shapeFromAlpha(null, 50, 49));
    }

    @Test
    public void pageTopIsOnlyAPrior() {
        // 量在动画中途时页面给的高度是错的，图像证据要能盖过它。
        JdPuzzleScenes.Scene scene = JdPuzzleScenes.scene(120, 50, Style.MASCOT_ON_DARK_DISK);

        JdPuzzleGap.Match match = JdPuzzleGap.locate(scene.background, scene.piece, 80);

        assertTrue(match.found(), "reason=" + match.reason);
        assertTrue(Math.abs(match.x - 120) <= 2, "gap x=" + match.x);
        assertTrue(Math.abs(match.y - 50) <= 1, "gap y=" + match.y);
    }

    @Test
    public void blankBackgroundIsNotAMatch() {
        JdPuzzleScenes.Scene scene = JdPuzzleScenes.scene(120, 50, Style.MASCOT_ON_DARK_DISK);
        BufferedImage blank = new BufferedImage(JdPuzzleScenes.BG_WIDTH, JdPuzzleScenes.BG_HEIGHT,
                BufferedImage.TYPE_INT_RGB);

        JdPuzzleGap.Match match = JdPuzzleGap.locate(blank, scene.piece);

        assertTrue(!match.found(), "a blank background must not produce a gap, x=" + match.x);
    }


    @Test
    public void contentMatchFindsTheExactOffset() {
        // 拼图块是从背景图上缺口处裁下来的原图，内部像素和背景图逐点对应，位置是精确的。
        for (Style style : Style.values()) {
            if (style == Style.PLAIN || style == Style.REPEATING) {
                continue;
            }
            JdPuzzleScenes.Scene scene = JdPuzzleScenes.scene(143, 73, style);

            JdPuzzleGap.Match match = JdPuzzleGap.matchContent(scene.background, scene.piece, 73);

            assertTrue(match.found(), style + " reason=" + match.reason);
            // 合成场景的背景图最后做了模糊而拼图块没有，纵向允许差一个像素；横向上必须是精确的。
            assertEquals(143, match.x, style + " gap x");
            assertTrue(Math.abs(match.y - 73) <= 1, style + " gap y=" + match.y);
        }
    }

    @Test
    public void contentMatchHasNoFixOnAFlatPiece() {
        // 缺口落在纯色底上时拼图块内部也几乎是纯色，和谁都「相关」，分不出位置。
        JdPuzzleScenes.Scene scene = JdPuzzleScenes.scene(120, 50, Style.PLAIN);

        JdPuzzleGap.Match match = JdPuzzleGap.matchContent(scene.background, scene.piece, 50);

        assertTrue(!match.found(), "a flat piece must not be located by content");
    }

    @Test
    public void contentMatchRefusesRepeatingContent() {
        // 背景图左右两半一样，拼图块的内容出现两次，只靠内容不能决定拖到哪里。
        JdPuzzleScenes.Scene scene = JdPuzzleScenes.scene(60, 50, Style.REPEATING);

        JdPuzzleGap.Match match = JdPuzzleGap.matchContent(scene.background, scene.piece, 50);

        assertTrue(!match.found(), "two identical places must not pass, x=" + match.x);
        assertEquals("content-ambiguous", match.reason);
    }

    @Test
    public void contentMatchIgnoresACopyAtAnotherHeight() {
        // 拖动只能是水平的：缺口内容在别的高度上再出现一次，也不算竞品。
        JdPuzzleScenes.Scene scene = JdPuzzleScenes.scene(143, 73, Style.SUNBURST);
        java.awt.Graphics2D graphics = scene.background.createGraphics();
        try {
            graphics.copyArea(143, 73, 50, 49, 0, -53);
        } finally {
            graphics.dispose();
        }

        JdPuzzleGap.Match match = JdPuzzleGap.matchContent(scene.background, scene.piece, 73);

        assertTrue(match.found(), "reason=" + match.reason);
        assertEquals(143, match.x);
        assertEquals(73, match.y);
    }

    @Test
    public void dragIsClampedToThePieceTravel() {
        assertEquals(120.0, JdPuzzleGap.clampDrag(180, 160, 40), 0.01);
        assertEquals(90.0, JdPuzzleGap.clampDrag(90, 160, 40), 0.01);
        assertEquals(-1, JdPuzzleGap.clampDrag(-1, 160, 40), 0.01);
    }

    private static boolean[][] puzzleShape(int width, int height) {
        boolean[][] shape = new boolean[height][width];
        for (int y = 8; y < height - 8; y++) {
            for (int x = 6; x < width - 16; x++) {
                shape[y][x] = true;
            }
        }
        for (int y = 16; y < 32; y++) {
            for (int x = width - 16; x < width - 4; x++) {
                shape[y][x] = true;
            }
        }
        return shape;
    }

    private static int darken(int argb) {
        int r = (((argb >> 16) & 255) * 45) / 100;
        int g = (((argb >> 8) & 255) * 45) / 100;
        int b = ((argb & 255) * 45) / 100;
        return 0xff000000 | (r << 16) | (g << 8) | b;
    }
}
