package org.rx.crawler.task.jd;

import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class JdPuzzleGapTest {
    @Test
    public void matchLeftFindsTheDarkenedPuzzleHole() {
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
                boolean border = isShapeBorder(shape, pieceW, pieceH, x, y);
                int darkened = border ? 0xff404040 : darken(original);
                background.setRGB(px, py, darkened);
            }
        }

        int found = JdPuzzleGap.matchLeft(background, piece, holeY);
        assertTrue(found >= 0 && Math.abs(found - holeX) <= 4, "gap x=" + found);
    }

    @Test
    public void blankPieceHasNoGap() {
        BufferedImage background = new BufferedImage(200, 80, BufferedImage.TYPE_INT_RGB);
        BufferedImage piece = new BufferedImage(40, 40, BufferedImage.TYPE_INT_ARGB);
        assertEquals(-1, JdPuzzleGap.matchLeft(background, piece, 10));
    }

    @Test
    public void dragCssUsesImageScaleAndTrackRatio() {
        assertEquals(80.0, JdPuzzleGap.dragCss(160, 320, 160, 40, 0, 0), 0.01);
        assertEquals(106.666, JdPuzzleGap.dragCss(160, 320, 160, 40, 200, 40), 0.1);
        assertEquals(80.0, JdPuzzleGap.dragCss(160, 320, 160, 40, 800, 40), 0.01);
        assertEquals(-1, JdPuzzleGap.dragCss(-1, 320, 160, 40, 0, 0), 0.01);
        assertEquals(120.0, JdPuzzleGap.clampDrag(180, 160, 40), 0.01);
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

    private static boolean isShapeBorder(boolean[][] shape, int width, int height, int x, int y) {
        if (x == 0 || y == 0 || x == width - 1 || y == height - 1) {
            return true;
        }
        return !shape[y][x - 1] || !shape[y][x + 1] || !shape[y - 1][x] || !shape[y + 1][x];
    }

    private static int darken(int argb) {
        int r = (((argb >> 16) & 255) * 45) / 100;
        int g = (((argb >> 8) & 255) * 45) / 100;
        int b = ((argb & 255) * 45) / 100;
        return 0xff000000 | (r << 16) | (g << 8) | b;
    }
}
