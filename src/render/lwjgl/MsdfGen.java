package render.lwjgl;

import java.awt.Shape;
import java.awt.font.FontRenderContext;
import java.awt.font.GlyphMetrics;
import java.awt.font.GlyphVector;
import java.awt.font.LineMetrics;
import java.awt.geom.PathIterator;
import java.util.ArrayList;
import java.util.List;

/**
 * MSDF（Multi-channel Signed Distance Field）字形生成器 —— 把 AWT 字形轮廓变成
 * {@link #CELL}×{@link #CELL} 的三通道有符号距离场位图。
 *
 * <p><b>为什么需要它</b>：旧字体是位图（{@link Font} 5×7 ASCII / {@link CjkFont} 16×16 布尔点阵），
 * 放大必然锯齿/糊边（只放大方形像素，不产生新的字形细节）。距离场存的是"到字形边界的距离"，
 * 与分辨率解耦 → <b>任意字号都清晰</b>，且天然支持描边/辉光/阴影（对距离阈值做偏移即可，
 * 不必重新光栅化）。CJK 有大量直角笔画，"单通道 SDF 会把尖角磨圆"，故用三通道 MSDF。
 *
 * <p><b>算法</b>（对标 Chlumský 2015《Shape Decomposition for Multi-channel Distance Fields》，
 * 与 {@code msdfgen} 的 {@code edgeColoringSimple} + 逐通道最近边同构）：
 * <ol>
 *   <li><b>轮廓提取</b>：{@code GlyphVector.getGlyphOutline(0)} → {@code PathIterator}
 *       （用双参构造器，AWT 自己把贝塞尔曲线按平坦度离散成折线）。</li>
 *   <li><b>归一化到 cell 坐标</b>：以行盒（advance × (ascent+descent)）做<b>统一</b>映射，
 *       保证同字体所有字形基线与字面框对齐（不用各自 bbox，否则汉字会大小不一）。</li>
 *   <li><b>边着色（逐轮廓）</b>：在轮廓的"角点"处切分，沿该轮廓按 CYAN/MAGENTA/YELLOW 轮转
 *       （三色两两互补，保证任一通道都不会拿到"整条光滑轮廓"）。无角点的轮廓（圆/椭圆）
 *       给 WHITE（三通道全给）。<b>必须逐轮廓做</b> —— 把多个轮廓拍平成一个边表再检测角点，
 *       会在轮廓交界处产生假角点并让配色跨轮廓乱串（2026-09-23 首版实测 bug，MSDF-COLOR 门禁抓到）。</li>
 *   <li><b>逐通道有符号距离</b>：符号取<b>全局内外判定</b>（非零绕数），幅值取该通道边集的
 *       <b>最小距离</b>：{@code sd_ch = (inside ? +1 : -1) × min_{e∈ch} |dist(p,e)|}。
 *       <p><b>为什么符号不能用"该边的半平面"</b>（2026-09-23 实测踩坑，门禁 MSDF-RECON 抓到）：
 *       半平面符号要求点落在边的<b>无限延长线</b>的哪一侧。对斜笔画，把一条边的延长线拉远，
 *       字形外部很远的点会落到"内侧"半平面 → 距离被钳到 +PX_RANGE → 编码成 255 → 读作<内部> →
 *       <b>重建出沿延长线延伸的假墨迹</b>。实测：直角字（口/国/日）全部 1.0，斜笔画字
 *       （丶 0.64 / 人 0.73 / 火 0.81 / 大 0.84）被拉低；换成全局符号后全线 → 1.0。
 *       <p>全局符号不损失 MSDF 的尖角特性：边界附近"最近边"必然属于 ≥2 个通道组
 *       （每条边覆盖 2 个通道），故 median 必等于真 SDF —— 零交叉面精确。而三通道各自
 *       只看到<b>边集的一个子集</b>，它们的插值场互不相同，median 的零集仍是"尖"的。
 *       （msdfgen 用逐边叉积只是因为"对最近边而言，在凹侧 ⟺ 在形内"局部成立，是省算力的近似；
 *       我们的钳位会把远场的近似误差放大成可见假墨迹，所以必须用精确的全局判定。）</li>
 * </ol>
 *
 * <p><b>符号约定（正 = 内部）</b>：输出 0.5 = 字形边界，&gt;0.5 = 内部。<b>不是</b>数学上常见的
 * "负 = 内部"。这样选是为了让渲染端能用 msdfgen 官方那段众所周知的公式
 * {@code opacity = clamp(screenPxRange * (median(rgb) - 0.5) + 0.5, 0, 1)} 原样成立。
 * 2026-09-23 首版按数学约定写，被 {@code MsdfCheck} 的 MSDF-SIGN 断言直接抓出（最差一致率 0.0000）。
 *
 * <p><b>零漂移</b>：本类是纯函数（输入 AWT 字形 → 输出字节数组），不读写世界状态、不碰 GL、
 * 不消耗任何 RNG，对四道仿真指纹零影响。可 headless 运行（{@code MsdfCheck} 门禁就是这么验的）。
 *
 * <p><b>纹理禁忌</b>：MSDF 位图<b>绝不能走 mipmap</b>（多级平均会破坏距离线性性，采样出"糊块"），
 * 图集纹理必须 {@code GL_TEXTURE_MAX_LEVEL=0} + 线性过滤。见 {@link MsdfFont}。
 */
public final class MsdfGen {

    /** 点阵边长（像素）。同时是纹理里一个槽位的边长。 */
    public static final int CELL = 48;
    /** 行盒映射到的实际像素（其余是给距离场扩散用的 padding）。 */
    public static final int GLYPH_PX = 40;
    /**
     * 距离场范围（单位 = 点阵像素，也就等于纹理 texel）：距离被钳制到 [-PX_RANGE, +PX_RANGE]。
     * 必须等于 (CELL - GLYPH_PX) / 2，否则 padding 会被钳掉、字形边缘出现"平顶"。
     * 4 是 msdfgen 的缺省值。
     */
    public static final float PX_RANGE = (CELL - GLYPH_PX) / 2f;

    // ---- 通道位掩码（MSDF 的三色分解）----
    private static final int R = 1, G = 2, B = 4;
    private static final int CYAN = G | B;
    private static final int MAGENTA = R | B;
    private static final int YELLOW = R | G;
    private static final int WHITE = R | G | B;

    /** 角点判定阈值：相邻折线方向夹角正弦 < sin(3°) 视作"光滑延续"，不算角点。 */
    private static final float CORNER_SIN = 0.0523f;
    /** 轮廓离散平坦度（像素）。越小越贴近真实曲线、边数越多。 */
    private static final double FLATNESS = 0.22;

    private MsdfGen() { }

    /** 一条折线边（已变换到 cell 像素坐标），带通道色与 bbox（剪枝用）。 */
    private static final class Edge {
        float ax, ay, bx, by;
        int color;
        float minX, minY, maxX, maxY;
    }

    /**
     * 生成单个字形的 MSDF 位图。
     *
     * @param font 已按 {@link #GLYPH_PX} 尺寸 deriveFont 过的 AWT 字体
     * @param c    要生成的字
     * @return {@link #CELL}×{@link #CELL} 的 RGBA 字节（长度 CELL*CELL*4，<b>行主序、第 0 行 = 字形顶部</b>，
     *         alpha 恒 255）。字形为空（空格 / 缺字）时返回全零距离场（重建为空 = 不显示），不抛异常。
     */
    public static byte[] generate(java.awt.Font font, char c) {
        byte[] out = new byte[CELL * CELL * 4];
        try {
            // FRC：(antialias=on, fractionalMetrics=on)。对 getGlyphOutline 而言本机 AWT（1.8.0_502）
            // 在 4 种 FRC 组合下返回的轮廓 bbox 完全一致（实测），所以这里只是一个"取理想几何轮廓"的
            // 声明式选择，不是行为依赖。（曾误判 hinting 是偏胖根因，实测 bbox 逐位相同 → 不是。）
            FontRenderContext frc = new FontRenderContext(null, true, true);
            GlyphVector gv = font.createGlyphVector(frc, new char[]{c});
            Shape outline = gv.getGlyphOutline(0);
            List<float[]> pts = flatten(outline);
            if (pts.isEmpty()) { fillAlpha(out); return out; }

            GlyphMetrics gm = gv.getGlyphMetrics(0);
            LineMetrics lm = font.getLineMetrics(new char[]{c}, 0, 1, frc);
            float ascent = lm.getAscent();
            float emH = lm.getAscent() + lm.getDescent();
            float emW = gm.getAdvanceX();
            if (emH < 1e-3f) emH = GLYPH_PX;
            if (emW < 1e-3f) emW = emH;

            // 统一映射：行盒 → GLYPH_PX，居中于 CELL。用户空间 y 向下、基线在 y=0、字形顶在 y=-ascent。
            float s = GLYPH_PX / emH;
            float pad = (CELL - GLYPH_PX) / 2f;
            float padX = pad + (GLYPH_PX - emW * s) / 2f;
            float padY = pad + ascent * s;

            List<List<Edge>> contours = buildEdges(pts, s, padX, padY);
            for (List<Edge> contour : contours) colorContour(contour);

            List<Edge> all = new ArrayList<Edge>();
            for (List<Edge> contour : contours) all.addAll(contour);
            rasterize(all, out);
        } catch (Throwable t) {
            // 任何异常都退化为"空字形"，绝不把异常抛进主循环（丢字总比崩帧好）
            for (int i = 3; i < out.length; i += 4) out[i] = (byte) 255;
        }
        return out;
    }

    private static void fillAlpha(byte[] out) {
        for (int i = 3; i < out.length; i += 4) out[i] = (byte) 255;
    }

    /**
     * 字形在 cell 里的排版信息 —— 供 {@link MsdfFont} 计算四边形与前进量，
     * 保证<b>生成端与绘制端用的是同一套映射</b>（两份实现会漂移，这是唯一真相点）。
     *
     * @return 长度 5 的数组：{@code padX, boxTop, boxW, boxH, advanceEm}
     *         <ul>
     *           <li>{@code padX}：内容盒左边（cell 像素坐标，y 向下）；</li>
     *           <li>{@code boxTop}：内容盒<b>顶边</b>的 cell y。⚠️ <b>不是基线</b>——
     *               基线在 {@code boxTop + ascent·s}。2026-09-23 首版把基线当成了盒顶，
     *               导致盒底落到 {@code 76 > CELL(48)}、UV 采到图集边缘（全 0），标题完全不显示。
     *               {@code MsdfCheck} 的 MSDF-LAYOUT 断言现在钉死"内容盒必须完全落在 cell 内"。</li>
     *           <li>{@code boxW, boxH}：内容盒尺寸（cell 像素）—— 行盒映射过来的实际宽度与高度；</li>
     *           <li>{@code advanceEm}：前进宽度 / 行盒高（无量纲）—— 屏幕前进量 = 字号 × 它。</li>
     *         </ul>
     *         字体不可用时返回全零（调用方据此跳过绘制）。
     */
    public static float[] layout(java.awt.Font font, char c) {
        float[] r = new float[5];
        try {
            FontRenderContext frc = new FontRenderContext(null, true, true);
            GlyphVector gv = font.createGlyphVector(frc, new char[]{c});
            GlyphMetrics gm = gv.getGlyphMetrics(0);
            LineMetrics lm = font.getLineMetrics(new char[]{c}, 0, 1, frc);
            float emH = lm.getAscent() + lm.getDescent();
            float emW = gm.getAdvanceX();
            if (emH < 1e-3f) emH = GLYPH_PX;
            if (emW < 1e-3f) emW = emH;
            float s = GLYPH_PX / emH;
            float pad = (CELL - GLYPH_PX) / 2f;
            r[0] = pad + (GLYPH_PX - emW * s) / 2f;   // padX：内容盒左边
            r[1] = pad;                                // boxTop：内容盒顶边（= 基线 − ascent·s）
            r[2] = emW * s;                            // boxW
            r[3] = GLYPH_PX;                           // boxH（行盒高映射结果）
            r[4] = emW / emH;                          // advanceEm
        } catch (Throwable ignored) { }
        return r;
    }

    // ------------------------------------------------------------------
    // 1. 轮廓提取 + 扁平化
    // ------------------------------------------------------------------

    /** 把一个 {@link Shape} 拆成闭合折线；返回每个轮廓的 {x0,y0,x1,y1,...}。 */
    private static List<float[]> flatten(Shape s) {
        List<float[]> out = new ArrayList<float[]>();
        // 双参 PathIterator：AWT 自行把 SEG_QUADTO/SEG_CUBICTO 按 flatness 离散为 SEG_LINETO
        PathIterator pi = s.getPathIterator(null, FLATNESS);
        List<Float> cur = new ArrayList<Float>();
        double[] co = new double[6];
        boolean open = false;
        while (!pi.isDone()) {
            int type = pi.currentSegment(co);
            switch (type) {
                case PathIterator.SEG_MOVETO:
                    if (open && cur.size() >= 6) out.add(toArr(cur));
                    cur.clear();
                    cur.add((float) co[0]); cur.add((float) co[1]);
                    open = true;
                    break;
                case PathIterator.SEG_LINETO:
                    if (open) { cur.add((float) co[0]); cur.add((float) co[1]); }
                    break;
                case PathIterator.SEG_CLOSE:
                    if (open && cur.size() >= 6) out.add(toArr(cur));
                    cur.clear();
                    open = false;
                    break;
                default:
                    break;
            }
            pi.next();
        }
        if (open && cur.size() >= 6) out.add(toArr(cur));
        return out;
    }

    private static float[] toArr(List<Float> l) {
        float[] a = new float[l.size()];
        for (int i = 0; i < a.length; i++) a[i] = l.get(i);
        return a;
    }

    // ------------------------------------------------------------------
    // 2. 构建边（保留轮廓分组）
    // ------------------------------------------------------------------

    /**
     * 把轮廓点列变换到 cell 坐标并连成边，<b>按轮廓分组</b>返回。
     *
     * <p>分组是必须的：角点检测与配色都只能在轮廓<b>内部</b>沿环走，一旦把多个轮廓拍成一个
     * 边表，第 i 条边的"下一条"会跨到另一个轮廓上（真实字形的第 0 条边与第 n-1 条边未必相接），
     * 于是产生假角点、配色跨轮廓乱串，三通道退化成两通道甚至单通道。
     *
     * <p>退化边（相邻点重合，离散化常见产物）被丢弃 —— 它们法线未定义，会污染着色；
     * 丢掉后剩余边仍按原顺序构成闭合环。
     */
    private static List<List<Edge>> buildEdges(List<float[]> pts, float s, float padX, float padY) {
        List<List<Edge>> out = new ArrayList<List<Edge>>();
        for (float[] p : pts) {
            int n = p.length / 2;
            List<Edge> contour = new ArrayList<Edge>();
            for (int i = 0; i < n; i++) {
                int j = (i + 1) % n;
                float ax = padX + p[i * 2] * s, ay = padY + p[i * 2 + 1] * s;
                float bx = padX + p[j * 2] * s, by = padY + p[j * 2 + 1] * s;
                if (Math.hypot(bx - ax, by - ay) < 1e-3f) continue;   // 退化边
                Edge e = new Edge();
                e.ax = ax; e.ay = ay; e.bx = bx; e.by = by;
                e.minX = Math.min(ax, bx); e.maxX = Math.max(ax, bx);
                e.minY = Math.min(ay, by); e.maxY = Math.max(ay, by);
                contour.add(e);
            }
            if (contour.size() >= 2) out.add(contour);
        }
        return out;
    }

    // ------------------------------------------------------------------
    // 3. 内外判定（全局符号的唯一来源）
    // ------------------------------------------------------------------

    /**
     * 非零绕数规则的内外判定，直接在 cell 坐标上做（边已变换过，不必反算回用户空间）。
     *
     * <p>调用方按"行"预筛候选边（见 {@link #spanningRows}），故这里不做 y 跨度检查。
     * 环形子集（洞）会自动抵消绕数 —— 这正是非零绕数相对奇偶规则的优势。
     */
    private static boolean insideCell(float px, float py, List<Edge> spanning) {
        int wind = 0;
        for (int i = 0, n = spanning.size(); i < n; i++) {
            Edge e = spanning.get(i);
            if ((e.ay > py) == (e.by > py)) continue;              // 不跨该水平线
            float xint = e.ax + (e.bx - e.ax) * (py - e.ay) / (e.by - e.ay);
            if (px < xint) wind += (e.ay > e.by) ? 1 : -1;
        }
        return wind != 0;
    }

    /**
     * 预筛：第 r 行的像素中心 y = r+0.5，只有 y 区间覆盖它的边才可能参与绕数判定。
     * 一次 O(CELL×E) 的预备，把每像素的绕数判定从 O(E) 降到 O(跨该行的少数边)。
     */
    @SuppressWarnings("unchecked")
    private static List<Edge>[] spanningRows(List<Edge> edges) {
        List<Edge>[] rows = new List[CELL];
        for (int r = 0; r < CELL; r++) {
            float py = r + 0.5f;
            List<Edge> lst = new ArrayList<Edge>();
            for (Edge e : edges) {
                if (e.minY <= py && py <= e.maxY) lst.add(e);
            }
            rows[r] = lst;
        }
        return rows;
    }

    // ------------------------------------------------------------------
    // 4. 边着色（单个轮廓内三色轮转）
    // ------------------------------------------------------------------

    /**
     * 单轮廓配色：角点检测 → 按 CYAN/MAGENTA/YELLOW 轮转。
     *
     * <p>无角点的轮廓给 WHITE：此时轮廓处处光滑（圆/椭圆），单通道 SDF 就足够，
     * 给三通道反而会在通道交界处产生接缝。
     */
    private static void colorContour(List<Edge> e) {
        int n = e.size();
        if (n == 0) return;
        boolean[] cornerAt = new boolean[n];      // 第 i 条边"末端"是否为角点
        int corners = 0;
        for (int i = 0; i < n; i++) {
            if (isCorner(e.get(i), e.get((i + 1) % n))) { cornerAt[i] = true; corners++; }
        }
        if (corners == 0) {
            for (Edge x : e) x.color = WHITE;
            return;
        }
        // 从第一个角点之后开始轮转（相位不影响重建质量，只求稳定可复现）
        int start = 0;
        for (int i = 0; i < n; i++) if (cornerAt[i]) { start = (i + 1) % n; break; }
        int spline = 0;
        for (int i = 0; i < n; i++) {
            int idx = (start + i) % n;
            e.get(idx).color = (spline % 3 == 0) ? CYAN : (spline % 3 == 1) ? MAGENTA : YELLOW;
            if (cornerAt[idx]) spline++;          // 末端是角点 → 下一条换色
        }
    }

    /** 两条相邻折线的方向叉积（归一化）是否超过阈值。 */
    private static boolean isCorner(Edge a, Edge b) {
        float ax = a.bx - a.ax, ay = a.by - a.ay;
        float bx = b.bx - b.ax, by = b.by - b.ay;
        float la = (float) Math.hypot(ax, ay), lb = (float) Math.hypot(bx, by);
        if (la < 1e-6f || lb < 1e-6f) return false;
        float cross = (ax * by - ay * bx) / (la * lb);
        return !(cross > -CORNER_SIN && cross < CORNER_SIN);
    }

    // ------------------------------------------------------------------
    // 5. 光栅化（逐通道有符号距离）
    // ------------------------------------------------------------------

    /**
     * 逐像素求三通道有符号距离。
     *
     * <p><b>符号</b>：由 {@link #insideCell} 的全局非零绕数判定给出 —— 内部为正、外部为负
     * （"正 = 内部"的 MSDF 约定，见类注释）。<b>幅值</b>：该通道边集的最小距离。
     * 二者相乘即每通道值；不再使用逐边半平面符号（原因与实测数据见类注释）。
     *
     * <p><b>剪枝</b>：先用边的 bbox 求出"到该边的最小可能距离"下界，若下界已 ≥ 该边覆盖的
     * 通道里最宽松的当前最优幅值，直接跳过（连开方都省）。CJK 一个字 ~200 条边、2304 像素，
     * 无剪枝是 46 万次距离计算，有剪枝通常降到 1/5~1/10。
     */
    private static void rasterize(List<Edge> edges, byte[] out) {
        final int ne = edges.size();
        List<Edge>[] rows = spanningRows(edges);       // 绕数判定用的按行候选
        for (int row = 0; row < CELL; row++) {
            float py = row + 0.5f;
            List<Edge> spanning = rows[row];
            for (int col = 0; col < CELL; col++) {
                float px = col + 0.5f;
                float sign = insideCell(px, py, spanning) ? 1f : -1f;
                float bestR = Float.MAX_VALUE, bestG = Float.MAX_VALUE, bestB = Float.MAX_VALUE;
                for (int k = 0; k < ne; k++) {
                    Edge e = edges.get(k);
                    // bbox 下界剪枝
                    float lx = Math.max(e.minX - px, px - e.maxX);
                    float ly = Math.max(e.minY - py, py - e.maxY);
                    float lb = Math.max(0f, Math.max(lx, ly));
                    if (lb >= worstOf(bestR, bestG, bestB, e.color)) continue;

                    float dx = e.bx - e.ax, dy = e.by - e.ay;
                    float wx = px - e.ax, wy = py - e.ay;
                    float l2 = dx * dx + dy * dy;
                    float t = l2 > 1e-12f ? (wx * dx + wy * dy) / l2 : 0f;
                    if (t < 0f) t = 0f; else if (t > 1f) t = 1f;
                    float ex = wx - dx * t, ey = wy - dy * t;
                    float d = (float) Math.sqrt(ex * ex + ey * ey);

                    if ((e.color & R) != 0 && d < bestR) bestR = d;
                    if ((e.color & G) != 0 && d < bestG) bestG = d;
                    if ((e.color & B) != 0 && d < bestB) bestB = d;
                }
                int o = (row * CELL + col) * 4;
                out[o] = enc(bestR * sign);
                out[o + 1] = enc(bestG * sign);
                out[o + 2] = enc(bestB * sign);
                out[o + 3] = (byte) 255;
            }
        }
    }

    /** 边色掩码覆盖的通道里，最大的当前最优幅值（保守下界，用于剪枝）。 */
    private static float worstOf(float r, float g, float b, int color) {
        float w = -Float.MAX_VALUE;
        if ((color & R) != 0) w = Math.max(w, r);
        if ((color & G) != 0) w = Math.max(w, g);
        if ((color & B) != 0) w = Math.max(w, b);
        return w;
    }

    /** 有符号距离 → 8bit。[-PX_RANGE, +PX_RANGE] 线性映射到 [0,255]（0.5 = 边界），超出钳制。 */
    private static byte enc(float sd) {
        float v = sd / PX_RANGE * 0.5f + 0.5f;
        if (v < 0f) v = 0f; else if (v > 1f) v = 1f;
        int i = (int) (v * 255f + 0.5f);
        if (i < 0) i = 0; else if (i > 255) i = 255;
        return (byte) i;
    }
}
