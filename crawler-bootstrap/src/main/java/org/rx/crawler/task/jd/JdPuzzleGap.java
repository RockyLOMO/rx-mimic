package org.rx.crawler.task.jd;

import java.awt.image.BufferedImage;

/**
 * 京东滑动拼图缺口定位。
 * 拼图块外轮廓去对齐背景缺口边缘，只返回背景图上拼图块图片左边缘的像素位置。
 * 拖动距离由 {@link #dragCss} 按展示宽度和滑轨宽度换算成 CSS 像素。
 */
public final class JdPuzzleGap {
    private JdPuzzleGap() {
    }

    /**
     * @param pieceTop 拼图块图片顶部相对背景图顶部的像素，与两张图同一像素空间
     * @return 背景图上拼图块图片左边缘应对齐的 x；无法判断时返回 -1
     */
    public static int matchLeft(BufferedImage background, BufferedImage piece, int pieceTop) {
        if (background == null || piece == null) {
            return -1;
        }
        int bgW = background.getWidth();
        int bgH = background.getHeight();
        int pw = piece.getWidth();
        int ph = piece.getHeight();
        if (pw < 8 || ph < 8 || pw >= bgW || ph >= bgH) {
            return -1;
        }
        boolean[][] solid = solidMask(piece, pw, ph);
        int[] edgeX = new int[pw * ph];
        int[] edgeY = new int[pw * ph];
        int edges = 0;
        int solidCount = 0;
        for (int y = 0; y < ph; y++) {
            for (int x = 0; x < pw; x++) {
                if (!solid[y][x]) {
                    continue;
                }
                solidCount++;
                if (isMaskEdge(solid, pw, ph, x, y)) {
                    edgeX[edges] = x;
                    edgeY[edges] = y;
                    edges++;
                }
            }
        }
        if (solidCount < 30 || edges < 12) {
            return -1;
        }

        int[] lum = luminance(background, bgW, bgH);
        int[] grad = gradient(lum, bgW, bgH);
        int maxX = bgW - pw;
        int startX = Math.min(maxX, Math.max(8, pw / 5));
        int yStart = pieceTop;
        if (yStart < 0) {
            yStart = 0;
        }
        if (yStart > bgH - ph) {
            yStart = Math.max(0, bgH - ph);
        }
        int yFrom = Math.max(0, yStart - 3);
        int yTo = Math.min(bgH - ph, yStart + 3);

        int bestX = -1;
        long best = -1;
        long sum = 0;
        int samples = 0;
        int step = Math.max(1, edges / 180);
        for (int y0 = yFrom; y0 <= yTo; y0++) {
            for (int x0 = startX; x0 <= maxX; x0++) {
                long score = scoreAt(grad, lum, bgW, bgH, edgeX, edgeY, edges, solid, pw, ph, x0, y0, step);
                sum += score;
                samples++;
                if (score > best) {
                    best = score;
                    bestX = x0;
                }
            }
        }
        if (bestX < 0 || samples == 0) {
            return -1;
        }
        long average = sum / samples;
        if (best < Math.max(edges, average * 6 / 5)) {
            return -1;
        }
        return bestX;
    }

    /**
     * 把背景图像素上的缺口左边缘换算成滑块应拖动的 CSS 像素。
     * 滑轨行程和图片可移动行程接近时按 1:1；两者明显不一致时按比例缩放。
     * 滑轨宽度远离背景宽度时仍按 1:1，避免把整页容器当成滑轨。
     */
    public static double dragCss(int gapShotX, int bgShotWidth, double bgCssWidth,
            double pieceCssWidth, double trackCssWidth, double handleCssWidth) {
        if (gapShotX < 0 || bgShotWidth < 10 || bgCssWidth < 10) {
            return -1;
        }
        double gapCss = gapShotX * (bgCssWidth / bgShotWidth);
        double travel = trackCssWidth - handleCssWidth;
        double imageTravel = bgCssWidth - pieceCssWidth;
        boolean trackLooksLikeImage = trackCssWidth > bgCssWidth * 0.5 && trackCssWidth < bgCssWidth * 1.5;
        double drag = gapCss;
        double limit = bgCssWidth - pieceCssWidth;
        if (trackLooksLikeImage && travel > 40 && imageTravel > 40 && Math.abs(travel - imageTravel) > 12) {
            drag = gapCss * travel / imageTravel;
            limit = travel;
        }
        if (limit > 20 && drag > limit) {
            drag = limit;
        }
        return drag;
    }

    /**
     * 展示坐标下的拖动距离限制在拼图可移动区间内：0 到背景宽减拼图块宽。
     */
    public static double clampDrag(double drag, double bgCssWidth, double pieceCssWidth) {
        if (!Double.isFinite(drag) || drag < 0) {
            return -1;
        }
        double max = bgCssWidth - pieceCssWidth;
        if (max > 20 && drag > max) {
            return max;
        }
        return drag;
    }

    private static long scoreAt(int[] grad, int[] lum, int bgW, int bgH, int[] edgeX, int[] edgeY, int edges,
            boolean[][] solid, int pw, int ph, int x0, int y0, int step) {
        long edgeScore = 0;
        int used = 0;
        for (int i = 0; i < edges; i += step) {
            int gx = x0 + edgeX[i];
            int gy = y0 + edgeY[i];
            if (gx < 0 || gy < 0 || gx >= bgW || gy >= bgH) {
                continue;
            }
            edgeScore += grad[gy * bgW + gx];
            used++;
        }
        if (used == 0) {
            return 0;
        }
        long dark = 0;
        int darkSamples = 0;
        for (int y = 2; y < ph - 2; y += 4) {
            for (int x = 2; x < pw - 2; x += 4) {
                if (!solid[y][x] || isMaskEdge(solid, pw, ph, x, y)) {
                    continue;
                }
                int gx = x0 + x;
                int gy = y0 + y;
                if (gx < 0 || gy < 0 || gx >= bgW || gy >= bgH) {
                    continue;
                }
                dark += 255 - lum[gy * bgW + gx];
                darkSamples++;
            }
        }
        long darkBonus = darkSamples == 0 ? 0 : dark / darkSamples;
        return edgeScore / used * 20 + darkBonus;
    }

    private static boolean[][] solidMask(BufferedImage piece, int pw, int ph) {
        int transparent = 0;
        for (int y = 0; y < ph; y++) {
            for (int x = 0; x < pw; x++) {
                if (((piece.getRGB(x, y) >>> 24) & 0xff) < 250) {
                    transparent++;
                }
            }
        }
        boolean useAlpha = transparent > pw * ph / 10;
        int corner = piece.getRGB(0, 0);
        boolean[][] solid = new boolean[ph][pw];
        for (int y = 0; y < ph; y++) {
            for (int x = 0; x < pw; x++) {
                int argb = piece.getRGB(x, y);
                if (useAlpha) {
                    solid[y][x] = ((argb >>> 24) & 0xff) > 40;
                } else {
                    solid[y][x] = !near(argb, corner);
                }
            }
        }
        return solid;
    }

    private static boolean near(int argb, int corner) {
        return Math.abs(((argb >> 16) & 255) - ((corner >> 16) & 255)) <= 18
                && Math.abs(((argb >> 8) & 255) - ((corner >> 8) & 255)) <= 18
                && Math.abs((argb & 255) - (corner & 255)) <= 18;
    }

    private static boolean isMaskEdge(boolean[][] solid, int pw, int ph, int x, int y) {
        if (x == 0 || y == 0 || x == pw - 1 || y == ph - 1) {
            return true;
        }
        return !solid[y][x - 1] || !solid[y][x + 1] || !solid[y - 1][x] || !solid[y + 1][x];
    }

    private static int[] luminance(BufferedImage image, int width, int height) {
        int[] lum = new int[width * height];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int rgb = image.getRGB(x, y);
                int r = (rgb >> 16) & 255;
                int g = (rgb >> 8) & 255;
                int b = rgb & 255;
                lum[y * width + x] = (r * 299 + g * 587 + b * 114) / 1000;
            }
        }
        return lum;
    }

    private static int[] gradient(int[] lum, int width, int height) {
        int[] grad = new int[width * height];
        for (int y = 1; y < height - 1; y++) {
            for (int x = 1; x < width - 1; x++) {
                int index = y * width + x;
                int dx = Math.abs(lum[index + 1] - lum[index - 1]);
                int dy = Math.abs(lum[index + width] - lum[index - width]);
                grad[index] = dx + dy;
            }
        }
        return grad;
    }
}
