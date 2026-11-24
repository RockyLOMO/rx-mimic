package org.rx.crawler.task.jd;

import java.awt.image.BufferedImage;

/**
 * 京东滑动拼图缺口定位。
 * 缺口是把原图压暗后的拼图块形状：沿拼图块轮廓，内侧应一致地比外侧暗。
 * 按这个特征在整张背景图上找最佳位置，吉祥物的强边缘不会把结果吸走。
 * 只返回背景图上拼图块图片左边缘应对齐的像素，乘以「展示宽度 / 截图宽度」就是拼图块应移动的 CSS 像素。
 */
public final class JdPuzzleGap {
    /** 采样点离轮廓内侧、外侧的像素。 */
    private static final int INNER_OFFSET = 2;
    private static final int OUTER_OFFSET = 3;
    /** 单个采样点的亮度差上限，一条强边缘不能主导整圈轮廓。 */
    private static final int CLIP = 80;
    /** 外侧比内侧亮多少才算「这一段对上了」。 */
    private static final int COVER_THRESHOLD = 8;
    private static final double COVER_WEIGHT = 0.5;
    /** 验收：得分、覆盖率的下限，以及相对缺口邻域以外最高分的倍数。 */
    private static final double MIN_SCORE = 14;
    private static final double MIN_COVERAGE = 0.55;
    private static final double MIN_MARGIN = 1.3;
    private static final int RIVAL_RADIUS = 6;
    private static final int MIN_PROBES = 12;
    /**
     * 页面量到的拼图块高度只作弱先验：缺口和拼图块在同一高度，离它远的候选打折，
     * 但图像证据足够强时仍可胜出，所以量在动画中途也不会因此找不到。
     */
    private static final double HINT_BAND = 3;
    private static final double OFF_BAND_FACTOR = 0.8;
    /** 不透明截图按「与底色不同」取形状，底色是边缘两圈里最常见的颜色。 */
    private static final int COLOUR_TOLERANCE = 48;
    /**
     * 内容匹配的验收：相关系数的下限、缺口邻域以外相关系数的上限，以及拼图块内部亮度的最小方差。
     * 实测京东的拼图块和背景图在缺口处相关系数是 1.0，别处不到 0.6。
     */
    private static final double MIN_CONTENT_CORRELATION = 0.95;
    private static final double MAX_CONTENT_RIVAL = 0.8;
    private static final double MIN_CONTENT_VARIANCE = 40;
    /** 相关系数乘它，让内容匹配的得分和轮廓得分量级相近。 */
    private static final double CONTENT_SCALE = 80;

    private JdPuzzleGap() {
    }

    /**
     * 定位结果。没通过验收时 {@link #found()} 为 false，{@link #x} 仍是得分最高的候选，供排查。
     */
    public static final class Match {
        public static final Match NONE = new Match(-1, 0, 0, 0, 0, "no-outline");

        /**
         * 背景图上拼图块图片左边缘应对齐的像素 x；无法采样时为 -1。
         */
        public final int x;
        /**
         * 对齐时拼图块图片顶部的像素 y。
         */
        public final int y;
        /**
         * 轮廓各点「外侧亮度减内侧亮度」的截断均值，再按覆盖率加权，范围约 0 到 80。
         */
        public final double score;
        /**
         * 覆盖率：外侧明显比内侧亮的采样占全部采样的比例，0 到 1。缺口整圈都应对得上，
         * 只蹭到别的暗区一段边界时这个值偏低。
         */
        public final double coverage;
        /**
         * 竞品分：候选位置上下左右 {@value #RIVAL_RADIUS} 像素以外的最高分。
         */
        public final double rival;
        /**
         * 没通过验收的原因；通过时为空串。
         */
        public final String reason;

        private Match(int x, int y, double score, double coverage, double rival, String reason) {
            this.x = x;
            this.y = y;
            this.score = score;
            this.coverage = coverage;
            this.rival = rival;
            this.reason = reason;
        }

        public boolean found() {
            return x >= 0 && reason.isEmpty();
        }
    }

    /**
     * 内容匹配。拼图块是从背景图上缺口处裁下来的原图，它的内部像素和背景图上同一位置的像素
     * 只差一个压暗系数，相关系数在缺口处接近 1，别处接近 0。
     * 用页面给的拼图块高度限定纵坐标：拖动只能是水平的，缺口必然和拼图块在同一高度。
     *
     * @param piece 带透明通道的原始拼图块图片
     * @return x、y 为拼图块图片左上角在背景图上的像素；score、rival 是相关系数乘 80。
     *         拼图块内部是纯色或背景图上找不到唯一位置时 x 为 -1
     */
    public static Match matchContent(BufferedImage background, BufferedImage piece, double pieceTopHint) {
        if (background == null || piece == null) {
            return Match.NONE;
        }
        int bgW = background.getWidth();
        int bgH = background.getHeight();
        int pw = piece.getWidth();
        int ph = piece.getHeight();
        if (pw < 8 || ph < 8 || pw >= bgW || ph >= bgH) {
            return Match.NONE;
        }
        // 内部采样要离开描边：描边是拼图块自带的，背景图上没有。
        boolean[][] inner = new boolean[ph][pw];
        for (int y = 0; y < ph; y++) {
            for (int x = 0; x < pw; x++) {
                inner[y][x] = ((piece.getRGB(x, y) >>> 24) & 0xff) >= 250;
            }
        }
        for (int i = 0; i < 3; i++) {
            boolean[][] next = new boolean[ph][pw];
            for (int y = 1; y < ph - 1; y++) {
                for (int x = 1; x < pw - 1; x++) {
                    next[y][x] = inner[y][x] && inner[y - 1][x] && inner[y + 1][x] && inner[y][x - 1] && inner[y][x + 1];
                }
            }
            inner = next;
        }
        int[] sampleX = new int[pw * ph];
        int[] sampleY = new int[pw * ph];
        int count = 0;
        for (int y = 0; y < ph; y++) {
            for (int x = 0; x < pw; x++) {
                if (inner[y][x]) {
                    sampleX[count] = x;
                    sampleY[count] = y;
                    count++;
                }
            }
        }
        if (count < MIN_PROBES) {
            return Match.NONE;
        }
        // 采样点太多没有好处，隔一个取一个。
        int step = Math.max(1, count / 400);
        int[] px = new int[(count + step - 1) / step];
        int[] py = new int[px.length];
        int[] pv = new int[px.length];
        int[] lum = luminance(piece, pw, ph);
        for (int i = 0, k = 0; i < count; i += step, k++) {
            px[k] = sampleX[i];
            py[k] = sampleY[i];
            pv[k] = lum[py[k] * pw + px[k]];
        }
        double pMean = 0;
        for (int v : pv) {
            pMean += v;
        }
        pMean /= pv.length;
        double pVar = 0;
        for (int v : pv) {
            pVar += (v - pMean) * (v - pMean);
        }
        // 拼图块内部几乎是纯色时，和谁都能「相关」，没有定位能力。
        if (pVar / pv.length < MIN_CONTENT_VARIANCE) {
            return new Match(-1, 0, 0, 0, 0, "flat-content");
        }

        int[] bgLum = luminance(background, bgW, bgH);
        int startX = Math.min(bgW - pw, 8);
        int cols = bgW - pw + 1 - startX;
        int fromY = 0;
        int toY = bgH - ph;
        if (Double.isFinite(pieceTopHint)) {
            fromY = Math.max(0, (int) Math.floor(pieceTopHint - HINT_BAND));
            toY = Math.min(bgH - ph, (int) Math.ceil(pieceTopHint + HINT_BAND));
        }
        if (fromY > toY || cols <= 0) {
            return Match.NONE;
        }
        double[] score = new double[(toY - fromY + 1) * cols];
        int best = -1;
        for (int y0 = fromY; y0 <= toY; y0++) {
            for (int x0 = 0; x0 < cols; x0++) {
                double qMean = 0;
                for (int k = 0; k < pv.length; k++) {
                    qMean += bgLum[(y0 + py[k]) * bgW + startX + x0 + px[k]];
                }
                qMean /= pv.length;
                double qVar = 0;
                double cov = 0;
                for (int k = 0; k < pv.length; k++) {
                    double dp = pv[k] - pMean;
                    double dq = bgLum[(y0 + py[k]) * bgW + startX + x0 + px[k]] - qMean;
                    qVar += dq * dq;
                    cov += dp * dq;
                }
                // 背景图上这块几乎是纯色时，相关系数只是噪声。缺口是压暗过的，起伏本来就比拼图块小，
                // 所以按比例判断：方差不到拼图块的 1/25 才算纯色。
                double r = qVar * 25 < pVar ? 0 : cov / Math.sqrt(pVar * qVar);
                int index = (y0 - fromY) * cols + x0;
                score[index] = r;
                if (best < 0 || r > score[best]) {
                    best = index;
                }
            }
        }
        if (best < 0) {
            return Match.NONE;
        }
        int bestY = fromY + best / cols;
        int bestX = best % cols + startX;
        double rival = 0;
        for (int i = 0; i < score.length; i++) {
            int y = fromY + i / cols;
            int x = i % cols + startX;
            if (Math.abs(x - bestX) > RIVAL_RADIUS || Math.abs(y - bestY) > RIVAL_RADIUS) {
                rival = Math.max(rival, score[i]);
            }
        }
        String reason = "";
        if (score[best] < MIN_CONTENT_CORRELATION) {
            reason = "content-mismatch";
        } else if (rival > MAX_CONTENT_RIVAL) {
            reason = "content-ambiguous";
        }
        return new Match(bestX, bestY, score[best] * CONTENT_SCALE, 1.0, rival * CONTENT_SCALE, reason);
    }

    /**
     * 拼图块形状取自截图本身：有透明通道按 alpha，没有则按「与底色不同」。
     */
    public static Match locate(BufferedImage background, BufferedImage piece) {
        return locate(background, piece, Double.NaN);
    }

    /**
     * @param pieceTopHint 页面量到的拼图块图片顶部相对背景图顶部的像素，只作弱先验；没有传 NaN
     */
    public static Match locate(BufferedImage background, BufferedImage piece, double pieceTopHint) {
        if (background == null || piece == null) {
            return Match.NONE;
        }
        int pw = piece.getWidth();
        int ph = piece.getHeight();
        return locate(background, screenshotMask(piece, pw, ph), pw, ph, pieceTopHint);
    }

    /**
     * 用给定的形状定位。拼图块截图不透明、块内颜色又和底色相近时，截图里切不出形状，
     * 应改用原始拼图块图片的 alpha（{@link #shapeFromAlpha}）。
     *
     * @param solid 拼图块实心区域，下标 [y][x]，与拼图块截图同尺寸
     */
    public static Match locate(BufferedImage background, boolean[][] solid, int pw, int ph, double pieceTopHint) {
        if (background == null || solid == null || ph != solid.length) {
            return Match.NONE;
        }
        int bgW = background.getWidth();
        int bgH = background.getHeight();
        if (pw < 8 || ph < 8 || pw >= bgW || ph >= bgH) {
            return Match.NONE;
        }
        int[][] probe = probe(solid, pw, ph);
        int count = probe[0].length;
        if (count < MIN_PROBES) {
            return Match.NONE;
        }
        int[] lum = blurredLuminance(background, bgW, bgH);
        // 拼图块起初贴着背景左缘，缺口不会在它自己身下；背景截图里那一块就是拼图块本身。
        int startX = Math.min(bgW - pw, 8);
        int rows = bgH - ph + 1;
        int cols = bgW - pw + 1 - startX;
        double[] sum = new double[rows * cols];
        int[] hits = new int[rows * cols];
        int[] covered = new int[rows * cols];
        for (int k = 0; k < count; k++) {
            int inX = probe[0][k];
            int inY = probe[1][k];
            int outX = probe[2][k];
            int outY = probe[3][k];
            // 候选位置的左边缘是 x0 + startX，两个采样点都要落在背景图内。
            int fromX = Math.max(0, Math.max(-inX, -outX) - startX);
            int toX = Math.min(cols - 1, Math.min(bgW - 1 - inX, bgW - 1 - outX) - startX);
            int fromY = Math.max(0, Math.max(-inY, -outY));
            int toY = Math.min(rows - 1, Math.min(bgH - 1 - inY, bgH - 1 - outY));
            if (fromX > toX || fromY > toY) {
                continue;
            }
            for (int y0 = fromY; y0 <= toY; y0++) {
                int inBase = (y0 + inY) * bgW + inX + startX;
                int outBase = (y0 + outY) * bgW + outX + startX;
                int row = y0 * cols;
                for (int x0 = fromX; x0 <= toX; x0++) {
                    int d = lum[outBase + x0] - lum[inBase + x0];
                    if (d > CLIP) {
                        d = CLIP;
                    } else if (d < -CLIP) {
                        d = -CLIP;
                    }
                    int index = row + x0;
                    sum[index] += d;
                    hits[index]++;
                    if (d > COVER_THRESHOLD) {
                        covered[index]++;
                    }
                }
            }
        }

        double[] score = new double[sum.length];
        double[] coverage = new double[sum.length];
        boolean useHint = Double.isFinite(pieceTopHint) && pieceTopHint >= 0 && pieceTopHint <= rows - 1;
        int best = -1;
        for (int i = 0; i < sum.length; i++) {
            if (hits[i] == 0) {
                score[i] = -Double.MAX_VALUE;
                continue;
            }
            coverage[i] = (double) covered[i] / hits[i];
            score[i] = sum[i] / hits[i] * (1.0 - COVER_WEIGHT + COVER_WEIGHT * coverage[i]);
            if (useHint && Math.abs(i / cols - pieceTopHint) > HINT_BAND) {
                score[i] *= OFF_BAND_FACTOR;
            }
            if (best < 0 || score[i] > score[best]) {
                best = i;
            }
        }
        if (best < 0) {
            return Match.NONE;
        }
        int bestY = best / cols;
        int bestX = best % cols + startX;
        double rival = 0;
        for (int i = 0; i < score.length; i++) {
            int y = i / cols;
            int x = i % cols + startX;
            if (Math.abs(x - bestX) > RIVAL_RADIUS || Math.abs(y - bestY) > RIVAL_RADIUS) {
                rival = Math.max(rival, score[i]);
            }
        }
        String reason = "";
        if (score[best] < MIN_SCORE) {
            reason = "weak-contrast";
        } else if (coverage[best] < MIN_COVERAGE) {
            reason = "outline-mismatch";
        } else if (score[best] < MIN_MARGIN * rival) {
            reason = "ambiguous";
        }
        return new Match(bestX, bestY, score[best], coverage[best], rival, reason);
    }

    /**
     * 把带透明通道的原始拼图块图片缩放到截图尺寸，得到实心区域。
     * 页面把拼图块叠在背景图上，元素截图不带透明通道；原始图片的 alpha 才是形状。
     *
     * @return 下标 [y][x] 的实心区域；图片没有透明像素时返回 null
     */
    public static boolean[][] shapeFromAlpha(BufferedImage natural, int width, int height) {
        if (natural == null || width < 8 || height < 8) {
            return null;
        }
        int nw = natural.getWidth();
        int nh = natural.getHeight();
        if (nw < 8 || nh < 8) {
            return null;
        }
        boolean[][] solid = new boolean[height][width];
        int count = 0;
        for (int y = 0; y < height; y++) {
            int ny = Math.min(nh - 1, (int) ((y + 0.5) * nh / height));
            for (int x = 0; x < width; x++) {
                int nx = Math.min(nw - 1, (int) ((x + 0.5) * nw / width));
                if (((natural.getRGB(nx, ny) >>> 24) & 0xff) >= 128) {
                    solid[y][x] = true;
                    count++;
                }
            }
        }
        // 全不透明说明这不是带形状的图片，截图里的形状更可信。
        if (count < MIN_PROBES || count >= width * height) {
            return null;
        }
        return tidy(solid, width, height);
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

    /**
     * 拼图块截图的实心区域。透明像素超过一成按 alpha 判断；元素截图叠在背景图上没有透明通道，
     * 这时以边缘两圈里最常见的颜色为底色，取与它不同的像素。
     */
    private static boolean[][] screenshotMask(BufferedImage piece, int pw, int ph) {
        int transparent = 0;
        int frame = 0;
        int[] bins = new int[4096];
        int[] binR = new int[4096];
        int[] binG = new int[4096];
        int[] binB = new int[4096];
        for (int y = 0; y < ph; y++) {
            for (int x = 0; x < pw; x++) {
                int argb = piece.getRGB(x, y);
                if (((argb >>> 24) & 0xff) < 250) {
                    transparent++;
                }
                if (x >= 2 && x < pw - 2 && y >= 2 && y < ph - 2) {
                    continue;
                }
                frame++;
                int bin = ((argb >> 20) & 0xf) << 8 | ((argb >> 12) & 0xf) << 4 | ((argb >> 4) & 0xf);
                bins[bin]++;
                binR[bin] += (argb >> 16) & 255;
                binG[bin] += (argb >> 8) & 255;
                binB[bin] += argb & 255;
            }
        }
        boolean[][] solid = new boolean[ph][pw];
        if (transparent > pw * ph / 10) {
            for (int y = 0; y < ph; y++) {
                for (int x = 0; x < pw; x++) {
                    solid[y][x] = ((piece.getRGB(x, y) >>> 24) & 0xff) > 40;
                }
            }
            return tidy(solid, pw, ph);
        }
        if (frame == 0) {
            return solid;
        }
        int common = 0;
        for (int bin = 1; bin < bins.length; bin++) {
            if (bins[bin] > bins[common]) {
                common = bin;
            }
        }
        if (bins[common] == 0) {
            return solid;
        }
        int refR = binR[common] / bins[common];
        int refG = binG[common] / bins[common];
        int refB = binB[common] / bins[common];
        for (int y = 0; y < ph; y++) {
            for (int x = 0; x < pw; x++) {
                int argb = piece.getRGB(x, y);
                if (Math.max(Math.abs(((argb >> 16) & 255) - refR),
                        Math.max(Math.abs(((argb >> 8) & 255) - refG), Math.abs((argb & 255) - refB)))
                        > COLOUR_TOLERANCE) {
                    solid[y][x] = true;
                }
            }
        }
        return tidy(solid, pw, ph);
    }

    /**
     * 只留最大的一块；描边可能被阈值断开，先膨胀把断口接上、补上围住的内部，再缩回原宽度。
     * 形态学操作在四周补了一圈空白的副本上做：贴着截图边缘的描边膨胀后才缩得回来。
     */
    private static boolean[][] tidy(boolean[][] solid, int pw, int ph) {
        boolean[][] mask = largest(solid, pw, ph);
        int ww = pw + 2;
        int hh = ph + 2;
        boolean[][] padded = new boolean[hh][ww];
        for (int y = 0; y < ph; y++) {
            System.arraycopy(mask[y], 0, padded[y + 1], 1, pw);
        }
        padded = fillHoles(dilate(padded, ww, hh), ww, hh);
        padded = erode(padded, ww, hh);
        for (int y = 0; y < ph; y++) {
            System.arraycopy(padded[y + 1], 1, mask[y], 0, pw);
        }
        return fillHoles(mask, pw, ph);
    }

    private static boolean[][] largest(boolean[][] solid, int pw, int ph) {
        boolean[][] best = new boolean[ph][pw];
        boolean[][] seen = new boolean[ph][pw];
        int[] queue = new int[pw * ph];
        int bestSize = 0;
        for (int y = 0; y < ph; y++) {
            for (int x = 0; x < pw; x++) {
                if (!solid[y][x] || seen[y][x]) {
                    continue;
                }
                boolean[][] component = new boolean[ph][pw];
                int head = 0;
                int tail = 0;
                seen[y][x] = true;
                component[y][x] = true;
                queue[tail++] = y * pw + x;
                while (head < tail) {
                    int cell = queue[head++];
                    int cx = cell % pw;
                    int cy = cell / pw;
                    for (int dy = -1; dy <= 1; dy++) {
                        for (int dx = -1; dx <= 1; dx++) {
                            int nx = cx + dx;
                            int ny = cy + dy;
                            if (nx < 0 || ny < 0 || nx >= pw || ny >= ph || seen[ny][nx] || !solid[ny][nx]) {
                                continue;
                            }
                            seen[ny][nx] = true;
                            component[ny][nx] = true;
                            queue[tail++] = ny * pw + nx;
                        }
                    }
                }
                if (tail > bestSize) {
                    bestSize = tail;
                    best = component;
                }
            }
        }
        return best;
    }

    private static boolean[][] dilate(boolean[][] solid, int pw, int ph) {
        return spread(solid, pw, ph, true);
    }

    private static boolean[][] erode(boolean[][] solid, int pw, int ph) {
        return spread(solid, pw, ph, false);
    }

    /**
     * 八邻域膨胀或腐蚀。边界外按空白处理。
     */
    private static boolean[][] spread(boolean[][] solid, int pw, int ph, boolean grow) {
        boolean[][] out = new boolean[ph][pw];
        for (int y = 0; y < ph; y++) {
            for (int x = 0; x < pw; x++) {
                // 膨胀：邻域里有实心就实心。腐蚀：邻域里有空白就空白。
                boolean value = !grow;
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dx = -1; dx <= 1; dx++) {
                        int nx = x + dx;
                        int ny = y + dy;
                        boolean filled = nx >= 0 && ny >= 0 && nx < pw && ny < ph && solid[ny][nx];
                        if (filled == grow) {
                            value = grow;
                        }
                    }
                }
                out[y][x] = value;
            }
        }
        return out;
    }

    private static boolean[][] fillHoles(boolean[][] solid, int pw, int ph) {
        boolean[][] outside = new boolean[ph][pw];
        int[] queue = new int[pw * ph];
        int tail = 0;
        for (int x = 0; x < pw; x++) {
            tail = seed(solid, outside, queue, tail, x, 0);
            tail = seed(solid, outside, queue, tail, x, ph - 1);
        }
        for (int y = 0; y < ph; y++) {
            tail = seed(solid, outside, queue, tail, 0, y);
            tail = seed(solid, outside, queue, tail, pw - 1, y);
        }
        for (int head = 0; head < tail; head++) {
            int cell = queue[head];
            int x = cell % pw;
            int y = cell / pw;
            if (x > 0) {
                tail = seed(solid, outside, queue, tail, x - 1, y);
            }
            if (x < pw - 1) {
                tail = seed(solid, outside, queue, tail, x + 1, y);
            }
            if (y > 0) {
                tail = seed(solid, outside, queue, tail, x, y - 1);
            }
            if (y < ph - 1) {
                tail = seed(solid, outside, queue, tail, x, y + 1);
            }
        }
        for (int y = 0; y < ph; y++) {
            for (int x = 0; x < pw; x++) {
                if (!solid[y][x] && !outside[y][x]) {
                    solid[y][x] = true;
                }
            }
        }
        return solid;
    }

    private static int seed(boolean[][] solid, boolean[][] outside, int[] queue, int tail, int x, int y) {
        if (solid[y][x] || outside[y][x]) {
            return tail;
        }
        outside[y][x] = true;
        queue[tail++] = y * solid[0].length + x;
        return tail;
    }

    /**
     * 轮廓上每个采样点朝内、朝外的取样位置，相对拼图块图片左上角。
     * 内外方向取轮廓点周围 7x7 内实心像素质心所指的方向。
     */
    private static int[][] probe(boolean[][] solid, int pw, int ph) {
        int capacity = pw * ph;
        int[] inX = new int[capacity];
        int[] inY = new int[capacity];
        int[] outX = new int[capacity];
        int[] outY = new int[capacity];
        int count = 0;
        for (int y = 0; y < ph; y++) {
            for (int x = 0; x < pw; x++) {
                if (!solid[y][x] || !isMaskEdge(solid, pw, ph, x, y)) {
                    continue;
                }
                int dxSum = 0;
                int dySum = 0;
                int mass = 0;
                for (int dy = -3; dy <= 3; dy++) {
                    int yy = y + dy;
                    if (yy < 0 || yy >= ph) {
                        continue;
                    }
                    for (int dx = -3; dx <= 3; dx++) {
                        int xx = x + dx;
                        if (xx < 0 || xx >= pw || !solid[yy][xx]) {
                            continue;
                        }
                        dxSum += dx;
                        dySum += dy;
                        mass++;
                    }
                }
                if (mass == 0) {
                    continue;
                }
                double cx = (double) dxSum / mass;
                double cy = (double) dySum / mass;
                double length = Math.sqrt(cx * cx + cy * cy);
                if (length < 0.3) {
                    continue;
                }
                cx /= length;
                cy /= length;
                inX[count] = x + (int) Math.rint(cx * INNER_OFFSET);
                inY[count] = y + (int) Math.rint(cy * INNER_OFFSET);
                outX[count] = x - (int) Math.rint(cx * OUTER_OFFSET);
                outY[count] = y - (int) Math.rint(cy * OUTER_OFFSET);
                count++;
            }
        }
        return new int[][]{copy(inX, count), copy(inY, count), copy(outX, count), copy(outY, count)};
    }

    private static int[] copy(int[] source, int count) {
        int[] target = new int[count];
        System.arraycopy(source, 0, target, 0, count);
        return target;
    }

    /**
     * 形状掩膜的轮廓：实心，且四邻域里有空白或贴着图片边缘。
     */
    public static boolean isOutline(boolean[][] solid, int x, int y) {
        if (!solid[y][x]) {
            return false;
        }
        int pw = solid[0].length;
        int ph = solid.length;
        if (x == 0 || y == 0 || x == pw - 1 || y == ph - 1) {
            return true;
        }
        return !solid[y][x - 1] || !solid[y][x + 1] || !solid[y - 1][x] || !solid[y + 1][x];
    }

    private static boolean isMaskEdge(boolean[][] solid, int pw, int ph, int x, int y) {
        return isOutline(solid, x, y);
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

    /**
     * 3x3 均值后的亮度，压掉缩放采样带来的锯齿噪声。
     */
    private static int[] blurredLuminance(BufferedImage image, int width, int height) {
        int[] lum = luminance(image, width, height);
        int[] out = new int[width * height];
        for (int y = 0; y < height; y++) {
            int up = Math.max(0, y - 1) * width;
            int mid = y * width;
            int down = Math.min(height - 1, y + 1) * width;
            for (int x = 0; x < width; x++) {
                int left = Math.max(0, x - 1);
                int right = Math.min(width - 1, x + 1);
                out[mid + x] = (lum[up + left] + lum[up + x] + lum[up + right]
                        + lum[mid + left] + lum[mid + x] + lum[mid + right]
                        + lum[down + left] + lum[down + x] + lum[down + right]) / 9;
            }
        }
        return out;
    }
}
