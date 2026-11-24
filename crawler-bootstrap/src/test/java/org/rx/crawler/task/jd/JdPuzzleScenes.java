package org.rx.crawler.task.jd;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Random;

/**
 * 合成的京东拼图截图，尺寸和拼图块形状照真实截图：背景图 354x139，拼图块 50x49。
 * 真实截图是京东的素材，不进仓库；这里复现它难认的几种情况。
 */
final class JdPuzzleScenes {
    static final int BG_WIDTH = 354;
    static final int BG_HEIGHT = 139;
    static final int PIECE_WIDTH = 50;
    static final int PIECE_HEIGHT = 49;

    enum Style {
        /**
         * 缺口在红底上，右边一只白色吉祥物踩着一大片暗色圆影，对比度远高于缺口。
         */
        MASCOT_ON_DARK_DISK,
        /**
         * 缺口压在吉祥物的白色脸部和黑色眼睛上，缺口内外既有红色也有白色和黑色。
         */
        MASCOT_UNDER_HOLE,
        /**
         * 橙黄放射光芒加一只白色吉祥物。拼图块内外是同一套颜色，明暗差又大，
         * 不透明截图里没法按「与底色不同」切出形状。
         */
        SUNBURST,
        /**
         * 只有带噪点的红底，没有任何图案。
         */
        PLAIN,
        /**
         * 左右两半完全一样的光芒图。
         */
        REPEATING
    }

    static final class Scene {
        final BufferedImage background;
        final BufferedImage piece;
        final int holeX;
        final int holeY;

        private Scene(BufferedImage background, BufferedImage piece, int holeX, int holeY) {
            this.background = background;
            this.piece = piece;
            this.holeX = holeX;
            this.holeY = holeY;
        }

        byte[] backgroundPng() {
            return png(background);
        }

        byte[] piecePng() {
            return png(piece);
        }
    }

    private JdPuzzleScenes() {
    }

    /**
     * 红底带噪点的背景图，按样式画上吉祥物，再在 (holeX, holeY) 挖出压暗的缺口并切出拼图块。
     * 最后把背景图模糊一下，模拟截图缩放后的软边缘。
     */
    static Scene scene(int holeX, int holeY, Style style) {
        BufferedImage background = new BufferedImage(BG_WIDTH, BG_HEIGHT, BufferedImage.TYPE_INT_ARGB);
        Random random = new Random(holeX * 31 + holeY);
        for (int y = 0; y < BG_HEIGHT; y++) {
            for (int x = 0; x < BG_WIDTH; x++) {
                int noise = random.nextInt(9) - 4;
                background.setRGB(x, y, rgb(196 + noise, 32 + noise / 2, 32 + noise / 2));
            }
        }
        if (style == Style.MASCOT_ON_DARK_DISK) {
            fill(background, 255, 72, 78, 62, rgb(118, 12, 12));
            fill(background, 255, 66, 52, 44, rgb(245, 245, 245));
            fill(background, 240, 58, 7, 11, rgb(25, 20, 20));
            fill(background, 275, 70, 14, 7, rgb(25, 20, 20));
            fill(background, 262, 118, 30, 16, rgb(150, 20, 20));
        } else if (style == Style.MASCOT_UNDER_HOLE) {
            fill(background, 238, 66, 72, 56, rgb(240, 238, 236));
            fill(background, 200, 112, 110, 22, rgb(235, 232, 230));
            fill(background, 176, 52, 6, 9, rgb(20, 18, 18));
            fill(background, 216, 62, 12, 6, rgb(15, 12, 12));
            fill(background, 186, 78, 26, 3, rgb(60, 55, 55));
        } else if (style == Style.SUNBURST) {
            double sector = Math.PI / 14;
            for (int y = 0; y < BG_HEIGHT; y++) {
                for (int x = 0; x < BG_WIDTH; x++) {
                    int ray = (int) (Math.atan2(y - 70, x - 420) / sector) & 1;
                    background.setRGB(x, y, ray == 0 ? rgb(250, 205, 90) : rgb(225, 110, 10));
                }
            }
            fill(background, 150, 60, 55, 42, rgb(245, 243, 240));
            fill(background, 120, 50, 8, 12, rgb(25, 20, 20));
        } else if (style == Style.REPEATING) {
            // 左右两半完全一样：拼图块的内容在背景图上出现两次，只靠内容分不出是哪一处。
            for (int y = 0; y < BG_HEIGHT; y++) {
                for (int x = 0; x < BG_WIDTH / 2; x++) {
                    int stripe = (int) ((x * 0.8 + y * 0.6) / 15) & 1;
                    int rgb = stripe == 0 ? rgb(250, 205, 90) : rgb(225, 110, 10);
                    background.setRGB(x, y, rgb);
                    background.setRGB(x + BG_WIDTH / 2, y, rgb);
                }
            }
        }

        boolean[][] shape = pieceShape();
        BufferedImage piece = new BufferedImage(PIECE_WIDTH, PIECE_HEIGHT, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < PIECE_HEIGHT; y++) {
            for (int x = 0; x < PIECE_WIDTH; x++) {
                if (!shape[y][x]) {
                    continue;
                }
                int px = holeX + x;
                int py = holeY + y;
                int original = background.getRGB(px, py);
                piece.setRGB(x, y, original);
                background.setRGB(px, py, darken(original));
            }
        }
        return new Scene(blur(background), piece, holeX, holeY);
    }

    /**
     * 50x49 的拼图块：40x40 的主体，上方正中一个圆凸头，右侧一个圆凸头，左侧下方一个圆凹口。
     */
    static boolean[][] pieceShape() {
        boolean[][] shape = new boolean[PIECE_HEIGHT][PIECE_WIDTH];
        for (int y = 0; y < PIECE_HEIGHT; y++) {
            for (int x = 0; x < PIECE_WIDTH; x++) {
                boolean body = x >= 4 && x <= 45 && y >= 9 && y <= 48;
                boolean topTab = distance2(x, y, 22, 9) <= 9 * 9;
                boolean rightTab = distance2(x, y, 45, 28) <= 9 * 9;
                boolean leftNotch = distance2(x, y, 4, 37) <= 8 * 8;
                shape[y][x] = (body || topTab || rightTab) && !leftNotch;
            }
        }
        return shape;
    }

    static boolean isShapeBorder(boolean[][] shape, int width, int height, int x, int y) {
        if (x == 0 || y == 0 || x == width - 1 || y == height - 1) {
            return true;
        }
        return !shape[y][x - 1] || !shape[y][x + 1] || !shape[y - 1][x] || !shape[y + 1][x];
    }

    static byte[] png(BufferedImage image) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            ImageIO.write(image, "png", out);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toByteArray();
    }

    /**
     * 拼图块的元素截图：页面把拼图块叠在背景图左端，所以截图不带透明通道，
     * 块内是缺口处的原图，描边围在四周，最左一列还会露出页面白底。
     *
     * @param ringColour 描边颜色
     */
    static BufferedImage composited(Scene scene, int ringColour) {
        boolean[][] shape = pieceShape();
        BufferedImage out = new BufferedImage(PIECE_WIDTH, PIECE_HEIGHT, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < PIECE_HEIGHT; y++) {
            for (int x = 0; x < PIECE_WIDTH; x++) {
                int rgb = rgb(255, 255, 255);
                if (x > 0) {
                    int bgX = x;
                    int bgY = scene.holeY + y;
                    rgb = bgY < BG_HEIGHT ? scene.background.getRGB(bgX, bgY) : rgb(255, 255, 255);
                    if (shape[y][x]) {
                        rgb = scene.piece.getRGB(x, y);
                    }
                    for (int dy = -1; dy <= 1 && rgb != ringColour; dy++) {
                        for (int dx = -1; dx <= 1; dx++) {
                            int nx = x + dx;
                            int ny = y + dy;
                            if (nx >= 0 && ny >= 0 && nx < PIECE_WIDTH && ny < PIECE_HEIGHT
                                    && shape[ny][nx] && isShapeBorder(shape, PIECE_WIDTH, PIECE_HEIGHT, nx, ny)) {
                                rgb = ringColour;
                                break;
                            }
                        }
                    }
                }
                out.setRGB(x, y, rgb);
            }
        }
        return out;
    }

    /**
     * 照真实尺寸缩放后的拼图块原始图片：带透明通道，是页面 src 里的那张。
     */
    static BufferedImage natural(Scene scene, int size) {
        boolean[][] shape = pieceShape();
        BufferedImage out = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                int sx = Math.min(PIECE_WIDTH - 1, x * PIECE_WIDTH / size);
                int sy = Math.min(PIECE_HEIGHT - 1, y * PIECE_HEIGHT / size);
                if (shape[sy][sx]) {
                    out.setRGB(x, y, scene.piece.getRGB(sx, sy));
                }
            }
        }
        return out;
    }

    /**
     * 画一个实心椭圆，超出边界的部分丢弃。
     */
    private static void fill(BufferedImage image, int centerX, int centerY, int radiusX, int radiusY, int rgb) {
        for (int y = Math.max(0, centerY - radiusY); y <= Math.min(image.getHeight() - 1, centerY + radiusY); y++) {
            for (int x = Math.max(0, centerX - radiusX); x <= Math.min(image.getWidth() - 1, centerX + radiusX); x++) {
                double dx = (x - centerX) / (double) radiusX;
                double dy = (y - centerY) / (double) radiusY;
                if (dx * dx + dy * dy <= 1) {
                    image.setRGB(x, y, rgb);
                }
            }
        }
    }

    private static BufferedImage blur(BufferedImage image) {
        BufferedImage out = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                int r = 0, g = 0, b = 0, n = 0;
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dx = -1; dx <= 1; dx++) {
                        int xx = x + dx;
                        int yy = y + dy;
                        if (xx < 0 || yy < 0 || xx >= image.getWidth() || yy >= image.getHeight()) {
                            continue;
                        }
                        int rgb = image.getRGB(xx, yy);
                        r += (rgb >> 16) & 255;
                        g += (rgb >> 8) & 255;
                        b += rgb & 255;
                        n++;
                    }
                }
                out.setRGB(x, y, rgb(r / n, g / n, b / n));
            }
        }
        return out;
    }

    private static int distance2(int x1, int y1, int x2, int y2) {
        int dx = x1 - x2;
        int dy = y1 - y2;
        return dx * dx + dy * dy;
    }

    private static int rgb(int r, int g, int b) {
        return 0xff000000 | (r << 16) | (g << 8) | b;
    }

    private static int darken(int argb) {
        int r = (((argb >> 16) & 255) * 45) / 100;
        int g = (((argb >> 8) & 255) * 45) / 100;
        int b = ((argb & 255) * 45) / 100;
        return 0xff000000 | (r << 16) | (g << 8) | b;
    }
}
