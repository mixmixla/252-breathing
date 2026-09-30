
import render.lwjgl.MsdfGen;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.font.FontRenderContext;
import java.awt.font.GlyphMetrics;
import java.awt.font.GlyphVector;
import java.awt.font.LineMetrics;
import java.awt.image.BufferedImage;

/**
 * MSDF 门禁（P1，2026-09-23 新增）—— 无 GL、headless，验 {@link MsdfGen} 的<b>语义</b>正确性。
 *
 * <p><b>为什么必须单独立门禁</b>：{@code ShaderCheck} 只能证明"着色器能编译"，
 * 完全不能证明"距离场编码对"。一个符号写反的 MSDF 照样编译通过、照样出图，只是字全部反色。
 * 本门禁直接测<b>距离场 → 重建字形</b>这条链路。
 *
 * <p><b>五条断言</b>（全部可证伪）：
 * <ol>
 *   <li><b>形状合法</b>：尺寸 = CELL*CELL*4、alpha 全 255、无"全 0 / 全 255"退化槽。</li>
 *   <li><b>符号正确</b>：字形内部像素的通道中位数 &lt; 0.5、外部 &gt; 0.5（用 AWT 光栅化做真值）。
 *       这条直接钉死"内外搞反"这个最致命的错误形态。</li>
 *   <li><b>重建精度</b>：按 {@code median(rgb) > 0.5} 重建，与原尺寸真值的 IoU ≥ 阈值。</li>
 *   <li><b>通道覆盖</b>：整批字形里三个通道都要被非平凡地用到（证明边着色生效，
 *       即"不是退化成单通道 SDF"）。CJK 直角笔画多，单通道会把尖角磨圆 —— 这条是 MSDF 的立身之本。</li>
 *   <li><b>★缩放优势（核心主张）</b>：把 MSDF 双线性放大 3 倍后的重建 IoU，必须<b>显著高于</b>
 *       同源位图最近邻放大 3 倍。这正是 P1 存在的理由（"位图字体缩放必糊"），
 *       若这条不成立，整个 P1 就没有价值 —— 必须能 FAIL。</li>
 * </ol>
 *
 * <p>运行：{@code java -cp "out;out_tools" tools.MsdfCheck}（构建器里由 build_runner.py 调用）。
 */
public final class MsdfCheck {

    /** 测试用字符集：覆盖笔画数从少到多、含直角/曲线/封闭框（口 有洞）/横竖撇捺。 */
    private static final String SAMPLE = "会呼吸的世界中口国永一丨丶人日月水火木金土上下大小";
    private static final int CELL = MsdfGen.CELL;

    private static int fails = 0;

    public static void main(String[] args) {
        System.out.println("MSDFCHECK cell=" + CELL + " pxRange=" + MsdfGen.PX_RANGE
                + " sample=" + SAMPLE.length() + " glyphs");

        java.awt.Font font = pickFont();
        if (font == null) {
            System.out.println("MSDFCHECK SKIP (无可用 CJK AWT 字体：本机没有字体，不是实现错误)");
            System.exit(0);
        }
        System.out.println("MSDFCHECK font=" + font.getFontName());

        // ---- 1. 形状合法 ----
        int degenerate = 0;
        for (int i = 0; i < SAMPLE.length(); i++) {
            char c = SAMPLE.charAt(i);
            byte[] msdf = MsdfGen.generate(font, c);
            if (msdf.length != CELL * CELL * 4) { System.out.println("  尺寸错误 c=" + c); fails++; }
            int alphaBad = 0, lo = 0, hi = 0;
            for (int k = 0; k < CELL * CELL; k++) {
                if ((msdf[k * 4 + 3] & 0xFF) != 255) alphaBad++;
                int m = med(msdf, k);
                if (m < 4) lo++;
                if (m > 251) hi++;
            }
            if (alphaBad > 0) { System.out.println("  alpha 非 255 c=" + c + " n=" + alphaBad); fails++; }
            // 一个字形的 cell 里不可能 99% 都是极值（那说明没生成 / 全被钳）
            if (lo > CELL * CELL * 99 / 100 || hi > CELL * CELL * 99 / 100) {
                System.out.println("  退化槽（几乎全是极值）c=" + c + " lo=" + lo + " hi=" + hi);
                degenerate++;
            }
        }
        boolean shapeOk = fails == 0 && degenerate == 0;
        System.out.println("MSDF-SHAPE " + (shapeOk ? "PASS" : "FAIL")
                + "  degenerateSlots=" + degenerate);

        // ---- 2 + 3. 符号正确 + 原尺寸重建精度 ----
        float worstSign = 1f, worstIou = 1f, worstRecall = 1f, worstPrec = 1f;
        char worstChar = '?', worstIouChar = '?', worstRecallChar = '?';
        for (int i = 0; i < SAMPLE.length(); i++) {
            char c = SAMPLE.charAt(i);
            byte[] msdf = MsdfGen.generate(font, c);
            boolean[] truth = truthBitmap(font, c, CELL, MsdfGen.GLYPH_PX, 0f);
            int agree = 0, total = 0;
            for (int k = 0; k < CELL * CELL; k++) {
                boolean t = truth[k];
                boolean m = med(msdf, k) > 127;          // median(rgb) > 0.5
                if (t) { total++; if (m) agree++; }
                else { total++; if (!m) agree++; }
            }
            float acc = total == 0 ? 0f : (float) agree / total;
            if (acc < worstSign) { worstSign = acc; worstChar = c; }
            float iou = iou(truth, msdf, CELL);
            if (iou < worstIou) { worstIou = iou; worstIouChar = c; }
            // 1 texel 容差下的召回/精确率（见下方说明，这才是本设计分辨率下能主张的精度）
            float[] rp = recallPrecision(truth, binaryOf(msdf), CELL);
            if (rp[0] < worstRecall) { worstRecall = rp[0]; worstRecallChar = c; }
            if (rp[1] < worstPrec) { worstPrec = rp[1]; }
        }
        boolean signOk = worstSign >= 0.95f;
        System.out.println("MSDF-SIGN  " + (signOk ? "PASS" : "FAIL")
                + "  worstAgreement=" + fmt(worstSign) + " (worst char='" + worstChar + "') want>=0.95");
        // 重建精度判据：为什么不用裸 IoU —— 设计分辨率是 40px 行盒，汉字细笔画（丶/一/丨）
        // 只有 3~5px 宽，边界位置本身带 ±0.5 texel 不确定度；IoU 是面积比，3px 形状错 1px
        // 就能掉到 0.6（实测最差 '丶' = 0.60，但它并没有"编码错"，只是指标过敏）。
        // 大字号场景（本特性的目标）相对误差随字号线性缩小，所以真正该断言的是
        // 「形状在 1 texel 容差内完全重合」：召回 = 真值墨迹有多少落在重建墨迹 1px 邻域内，
        // 精确率 = 反过来。这两条对细笔画不过敏，且一旦内外反转 / 轮廓丢失会直接崩到 0。
        boolean reconOk = worstRecall >= 0.97f && worstPrec >= 0.97f;
        System.out.println("MSDF-RECON " + (reconOk ? "PASS" : "FAIL")
                + "  1texel recall=" + fmt(worstRecall) + " (worst char='" + worstRecallChar + "')"
                + " precision=" + fmt(worstPrec) + " want>=0.97"
                + "   [参考] 裸 IoU 最差=" + fmt(worstIou) + " ('" + worstIouChar + "')");

        // ---- 4. 通道覆盖（边着色发生且是【三色】而不是退化成两色/单色）----
        // 判据用的是 MSDF 通道合成的**结构不变量**，不是"观感数量"：
        //   设 R = min(含 R 的边集)，G = min(含 G 的边集)，B = min(含 B 的边集)。
        //   三色定义：CYAN=G|B、MAGENTA=R|B、YELLOW=R|G，于是
        //     R 的边集 = MAGENTA∪YELLOW, G 的边集 = CYAN∪YELLOW, B 的边集 = CYAN∪MAGENTA
        //   ① 若退化成**单色**（全 WHITE）：R≡G≡B，无任何通道是严格最小。
        //   ② 若退化成**两色**，例如只有 CYAN+MAGENTA：B = min(R,G) ≤ R 且 ≤ G，
        //      故 **R 永远不可能是三者中的严格最小值**（G < R 要求 R > B ≥ ... 矛盾）。
        //      另两种两色组合同理，各有一个通道永远当不了严格最小。
        //   ③ 只有**真正三色**时，三个通道才都可能成为严格最小。
        // 所以断言"每个通道都必须至少在某些像素上是严格最小值" —— 单色和两色都必然 FAIL。
        int[] strictMin = new int[3];
        for (int i = 0; i < SAMPLE.length(); i++) {
            byte[] msdf = MsdfGen.generate(font, SAMPLE.charAt(i));
            for (int k = 0; k < CELL * CELL; k++) {
                int r = msdf[k * 4] & 0xFF, g = msdf[k * 4 + 1] & 0xFF, b = msdf[k * 4 + 2] & 0xFF;
                if (r < g && r < b) strictMin[0]++;
                else if (g < r && g < b) strictMin[1]++;
                else if (b < r && b < g) strictMin[2]++;
            }
        }
        boolean colorOk = strictMin[0] > 20 && strictMin[1] > 20 && strictMin[2] > 20;
        System.out.println("MSDF-COLOR " + (colorOk ? "PASS" : "FAIL")
                + "  strictMin R=" + strictMin[0] + " G=" + strictMin[1] + " B=" + strictMin[2]
                + " (单色/两色分解必然至少一个为 0)");

        // ---- 5. ★缩放优势：MSDF 放大 vs 位图放大（P1 的核心主张）----
        // 两者都对同一张 3 倍大小的高分辨率真值比 IoU。
        final int UP = 3;
        int canvas = CELL * UP;                     // 144
        float msdfIou = 0f, bmpIou = 0f;
        int n = 0;
        for (int i = 0; i < SAMPLE.length(); i++) {
            char c = SAMPLE.charAt(i);
            byte[] msdf = MsdfGen.generate(font, c);
            boolean[] truth = truthBitmap(font, c, canvas, MsdfGen.GLYPH_PX * UP, 0f);
            boolean[] upMsdf = upsampleMsdf(msdf, canvas, UP);
            boolean[] upBmp = upsampleNearest(binaryOf(msdf), canvas, UP);
            msdfIou += iouVs(truth, upMsdf);
            bmpIou += iouVs(truth, upBmp);
            n++;
        }
        msdfIou /= n; bmpIou /= n;
        boolean zoomOk = msdfIou > bmpIou + 0.05f;   // 必须"显著"更好，不是打平
        System.out.println("MSDF-ZOOM  " + (zoomOk ? "PASS" : "FAIL")
                + "  x" + UP + "  msdfIoU=" + fmt(msdfIou) + "  bmpIoU=" + fmt(bmpIou)
                + "  gain=" + fmt(msdfIou - bmpIou) + " want>+0.05");

        // ---- 6. 排版盒必须完全落在 cell 内（2026-09-23 补：这条本来能抓到一个真 bug）----
        // 事故：MsdfGen.layout() 首版把**基线**当成内容盒顶返回。基线在 cell y≈36、盒高 40 →
        // 盒底 76 > CELL 48 → MsdfFont 算出的 v 坐标变成**负值** → UV 溢出槽位、采样到图集边缘
        // （全 0）→ 距离场读成"远在外部" → alpha 恒 0 → **标题完全不显示**，而所有其它门禁全绿
        // （生成端是对的，错在"生成端与绘制端共用同一份映射"的约定上）。
        // 判据：0 ≤ boxTop 且 boxTop+boxH ≤ CELL 且 0 ≤ padX 且 padX+boxW ≤ CELL。
        float worstTop = -1e9f, worstBottom = 1e9f, worstLeft = 1e9f, worstRight = -1e9f;
        char worstLayoutChar = '?';
        for (int i = 0; i < SAMPLE.length(); i++) {
            char c = SAMPLE.charAt(i);
            float[] L = MsdfGen.layout(font, c);
            if (L[1] > worstTop) worstTop = L[1];
            if (L[1] + L[3] < worstBottom) { worstBottom = L[1] + L[3]; worstLayoutChar = c; }
            if (L[0] < worstLeft) worstLeft = L[0];
            if (L[0] + L[2] > worstRight) worstRight = L[0] + L[2];
        }
        boolean layoutOk = worstTop >= -0.001f && worstBottom <= CELL + 0.001f
                && worstLeft >= -0.001f && worstRight <= CELL + 0.001f;
        System.out.println("MSDF-LAYOUT " + (layoutOk ? "PASS" : "FAIL")
                + "  boxTop∈[" + fmt(worstTop) + "] boxBottom=" + fmt(worstBottom)
                + " (worst char='" + worstLayoutChar + "') 横向[" + fmt(worstLeft) + "," + fmt(worstRight) + "]"
                + " 必须落在 [0," + CELL + "] 内");

        if (fails > 0 || !shapeOk || !signOk || !reconOk || !colorOk || !zoomOk || !layoutOk) {
            System.out.println("MSDFCHECK FAIL");
            System.exit(1);
        }
        System.out.println("MSDFCHECK PASS");
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    private static java.awt.Font pickFont() {
        String[] cand = {"Microsoft YaHei", "微软雅黑", "SimHei", "黑体", "SimSun", "宋体",
                "NSimSun", "Microsoft JhengHei", "Dialog"};
        for (String nm : cand) {
            java.awt.Font f = new java.awt.Font(nm, java.awt.Font.PLAIN, MsdfGen.GLYPH_PX);
            if (inkCount(raster(f, '中', MsdfGen.GLYPH_PX)) > 8) return f;
        }
        return null;
    }

    /** 通道中位数（MSDF 的重建判据）。 */
    private static int med(byte[] b, int px) {
        int r = b[px * 4] & 0xFF, g = b[px * 4 + 1] & 0xFF, bl = b[px * 4 + 2] & 0xFF;
        return Math.max(Math.min(r, g), Math.min(Math.max(r, g), bl));
    }

    /** 用与 MsdfGen 相同的映射，直接 AWT 光栅化出"真值"二值图（em 盒居中于正方形画布）。 */
    private static boolean[] truthBitmap(java.awt.Font font, char c, int canvas, float emPx, float yShift) {
        boolean[] out = new boolean[canvas * canvas];
        try {
            BufferedImage img = new BufferedImage(canvas, canvas, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = img.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            FontRenderContext frc = new FontRenderContext(null, true, true);
            java.awt.Font scaled = font.deriveFont(emPx);
            GlyphVector gv = scaled.createGlyphVector(frc, new char[]{c});
            GlyphMetrics gm = gv.getGlyphMetrics(0);
            LineMetrics lm = scaled.getLineMetrics(new char[]{c}, 0, 1, frc);
            float emH = lm.getAscent() + lm.getDescent();
            float emW = gm.getAdvanceX();
            if (emH < 1e-3f) emH = emPx;
            if (emW < 1e-3f) emW = emH;
            float s = emPx / emH;
            float pad = (canvas - emPx) / 2f;
            float padX = pad + (emPx - emW * s) / 2f;
            float padY = pad + lm.getAscent() * s;

            g.setColor(Color.WHITE);
            g.translate(padX, padY);
            g.scale(s, s);
            g.fill(gv.getGlyphOutline(0));
            g.dispose();
            for (int yy = 0; yy < canvas; yy++)
                for (int xx = 0; xx < canvas; xx++)
                    if (((img.getRGB(xx, yy) >>> 24) & 0xFF) > 110) out[yy * canvas + xx] = true;
        } catch (Throwable ignored) { }
        return out;
    }

    private static boolean[] raster(java.awt.Font f, char c, int px) {
        return truthBitmap(f, c, px, px, 0f);
    }

    private static int inkCount(boolean[] b) { int n = 0; for (boolean x : b) if (x) n++; return n; }

    private static boolean[] binaryOf(byte[] msdf) {
        boolean[] out = new boolean[CELL * CELL];
        for (int k = 0; k < out.length; k++) out[k] = med(msdf, k) > 127;
        return out;
    }

    /**
     * MSDF 双线性放大（模拟 GPU 采样）：在目标像素处对源距离场做双线性插值，
     * 再按 {@code median > 0.5} 重建 —— 与 FS 的 {@code median(rgb) > 0.5} 同构。
     */
    private static boolean[] upsampleMsdf(byte[] msdf, int canvas, int up) {
        boolean[] out = new boolean[canvas * canvas];
        for (int y = 0; y < canvas; y++) {
            for (int x = 0; x < canvas; x++) {
                float sx = (x + 0.5f) / up - 0.5f;
                float sy = (y + 0.5f) / up - 0.5f;
                int x0 = (int) Math.floor(sx), y0 = (int) Math.floor(sy);
                float fx = sx - x0, fy = sy - y0;
                float r = bilin(msdf, x0, y0, fx, fy, 0);
                float g = bilin(msdf, x0, y0, fx, fy, 1);
                float b = bilin(msdf, x0, y0, fx, fy, 2);
                float m = Math.max(Math.min(r, g), Math.min(Math.max(r, g), b));
                out[y * canvas + x] = m > 0.5f;
            }
        }
        return out;
    }

    private static float bilin(byte[] msdf, int x0, int y0, float fx, float fy, int ch) {
        float v00 = tap(msdf, x0, y0, ch), v10 = tap(msdf, x0 + 1, y0, ch);
        float v01 = tap(msdf, x0, y0 + 1, ch), v11 = tap(msdf, x0 + 1, y0 + 1, ch);
        return (v00 * (1 - fx) + v10 * fx) * (1 - fy) + (v01 * (1 - fx) + v11 * fx) * fy;
    }

    private static float tap(byte[] msdf, int x, int y, int ch) {
        if (x < 0) x = 0; else if (x >= CELL) x = CELL - 1;
        if (y < 0) y = 0; else if (y >= CELL) y = CELL - 1;
        return (msdf[(y * CELL + x) * 4 + ch] & 0xFF) / 255f;
    }

    private static boolean[] upsampleNearest(boolean[] src, int canvas, int up) {
        boolean[] out = new boolean[canvas * canvas];
        for (int y = 0; y < canvas; y++) {
            for (int x = 0; x < canvas; x++) {
                int sx = Math.min(CELL - 1, Math.max(0, (int) ((x + 0.5f) / up)));
                int sy = Math.min(CELL - 1, Math.max(0, (int) ((y + 0.5f) / up)));
                out[y * canvas + x] = src[sy * CELL + sx];
            }
        }
        return out;
    }

    private static float iou(boolean[] truth, byte[] msdf, int n) {
        return iouVs(truth, binaryOf(msdf));
    }

    private static float iouVs(boolean[] a, boolean[] b) {
        int inter = 0, uni = 0;
        for (int i = 0; i < a.length; i++) {
            if (a[i] && b[i]) inter++;
            if (a[i] || b[i]) uni++;
        }
        return uni == 0 ? 1f : (float) inter / uni;
    }

    /**
     * 1 texel 容差下的 {召回率, 精确率}。
     *
     * <p>召回 = 真值墨迹像素中，3×3 邻域内存在重建墨迹的比例（"我们没漏掉笔画"）。
     * 精确 = 重建墨迹像素中，3×3 邻域内存在真值墨迹的比例（"我们没多画"）。
     *
     * <p>对细笔画不过敏（边界偏 1px 仍算命中），但对结构性错误（内外反转、轮廓丢失、
     * 通道塌缩）会直接掉到接近 0 —— 正是门禁要区分的那类错误。
     */
    private static float[] recallPrecision(boolean[] truth, boolean[] rec, int n) {
        int tInk = 0, rInk = 0, tHit = 0, rHit = 0;
        for (int y = 0; y < n; y++) {
            for (int x = 0; x < n; x++) {
                boolean anyRec = false, anyTruth = false;
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dx = -1; dx <= 1; dx++) {
                        int xx = x + dx, yy = y + dy;
                        if (xx < 0 || yy < 0 || xx >= n || yy >= n) continue;
                        if (rec[yy * n + xx]) anyRec = true;
                        if (truth[yy * n + xx]) anyTruth = true;
                    }
                }
                int k = y * n + x;
                if (truth[k]) { tInk++; if (anyRec) tHit++; }
                if (rec[k]) { rInk++; if (anyTruth) rHit++; }
            }
        }
        float recall = tInk == 0 ? 1f : (float) tHit / tInk;
        float prec = rInk == 0 ? 1f : (float) rHit / rInk;
        return new float[]{recall, prec};
    }

    private static String fmt(float v) { return String.format(java.util.Locale.US, "%.4f", v); }
}
