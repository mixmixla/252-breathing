package render.lwjgl;

/**
 * HUD 布局引擎（P3，2026-09-23）。
 *
 * <p><b>为什么需要它</b>：在此之前 HUD 全部坐标是「手写绝对式」——面板位置散落在
 * {@code drawHud()} 里，形如 {@code n7 = this.W - 220}、{@code f32 = ((float) this.W - f29) / 2.0f}、
 * {@code n3 = this.H - 104}。这带来三个具体后果：
 * <ol>
 *   <li><b>改一处要手算一堆</b>：调整任一元素尺寸，其后所有元素的 y 偏移都得人工重算；</li>
 *   <li><b>小窗口直接爆</b>：{@code W = 320} 时 {@code W - 220 = 100}，面板互相压在一起且右侧出屏，
 *       而没有任何机制会报错；</li>
 *   <li><b>重叠无人可查</b>：没有任何东西能回答「HUD 元素有没有压在一起」。</li>
 * </ol>
 *
 * <p><b>它做什么</b>（刻意保持小）：
 * <ul>
 *   <li>{@link #solve}：把「9 宫格锚点 + 偏移 + 尺寸」求解成像素矩形；</li>
 *   <li>{@link #clamp}：溢出时把矩形推回屏内（推不进去就收缩）；</li>
 *   <li>{@link Stack}：竖向堆叠，自动累加游标（消掉手写的 y 步进）；</li>
 *   <li>{@link Rect#contains}：命中测试（为后续拖拽 / 点击留接口）。</li>
 * </ul>
 *
 * <p><b>它刻意不做什么</b>：不做 flexbox 式约束求解、不做权重分配、不做自动换行、不做文本度量。
 * 这是一个 HUD，不是网页。需要的是「锚点算得对 + 溢出不出屏 + 重叠可查」，不是通用布局语言。
 * 尺寸（尤其内容自适应尺寸）由调用方算好后传入 —— 只有调用方知道自己的行数与字号。
 *
 * <p><b>等价性</b>：{@link #solve} 在无溢出时与原手写公式<b>逐位等价</b>，
 * 见 {@code tools/UiLayoutCheck}（对 11 个面板 × 8 种分辨率逐项比对）。
 * 所以本次迁移不改变任何正常分辨率下的画面 —— 无头截图逐字节对比已验证。
 * {@link #fit} 的钳制只在溢出时生效，正常分辨率下是恒等变换。
 */
public final class UiLayout {

    // ---- 锚点：9 宫格 ----
    /** 左上：{@code x = ox}, {@code y = oy}。 */
    public static final int TL = 0;
    /** 顶中：{@code x} 水平居中 + {@code ox}, {@code y = oy}。 */
    public static final int TC = 1;
    /** 右上：{@code x = W - w - ox}, {@code y = oy}。 */
    public static final int TR = 2;
    /** 左中：{@code x = ox}, {@code y} 垂直居中 + {@code oy}。 */
    public static final int CL = 3;
    /** 正中。 */
    public static final int CC = 4;
    /** 右中。 */
    public static final int CR = 5;
    /** 左下：{@code x = ox}, {@code y = H - h - oy}（{@code oy} = 底边距）。 */
    public static final int BL = 6;
    /** 底中。 */
    public static final int BC = 7;
    /** 右下。 */
    public static final int BR = 8;

    /** {@link #fit} 的默认屏内容许外边距。 */
    public static final float MARGIN = 2f;

    private UiLayout() { }

    /** 求解结果矩形。坐标单位与画布一致（HUD 用像素，原点左上、y 向下）。 */
    public static final class Rect {
        public final String id;
        public float x, y, w, h;

        Rect(String id, float x, float y, float w, float h) {
            this.id = id;
            this.x = x;
            this.y = y;
            this.w = w;
            this.h = h;
        }

        public float x2() { return this.x + this.w; }
        public float y2() { return this.y + this.h; }
        public float area() { return this.w * this.h; }

        /** 命中测试（左闭右开，与常规 UI 命中语义一致）。 */
        public boolean contains(float px, float py) {
            return px >= this.x && px < this.x2() && py >= this.y && py < this.y2();
        }

        /** 交集面积（无交集为 0）。 */
        public float overlapArea(Rect o) {
            float ox = Math.min(this.x2(), o.x2()) - Math.max(this.x, o.x);
            float oy = Math.min(this.y2(), o.y2()) - Math.max(this.y, o.y);
            return (ox <= 0f || oy <= 0f) ? 0f : ox * oy;
        }

        /** 是否完整落在画布内。 */
        public boolean inside(float W, float H) {
            return this.x >= 0f && this.y >= 0f && this.x2() <= W && this.y2() <= H;
        }

        @Override public String toString() {
            return String.format(java.util.Locale.US, "%-16s x=%7.2f y=%7.2f w=%7.2f h=%7.2f",
                    this.id, this.x, this.y, this.w, this.h);
        }
    }

    /**
     * 锚定求解（<b>不做溢出钳制</b>）。
     *
     * <p>语义关键点：{@code ox/oy} 是「锚点到该边的距离」，不是「偏移量」。
     * 所以 {@code BL} + {@code oy = 78} 表示「底边距屏幕底 78」，{@code h} 变化时底边不动、顶边移动。
     * 这正好对应原手写写法 {@code y = H - h - 78}（例如左下 SOUL 条原来的 {@code y = this.H - 104}，
     * 因其 {@code h = 26}，等价于底边距 78）。
     */
    public static Rect solve(String id, int anchor, float ox, float oy,
                             float w, float h, float W, float H) {
        float x;
        float y;
        switch (anchor) {
            case TL: x = ox;                    y = oy;                    break;
            case TC: x = (W - w) * 0.5f + ox;   y = oy;                    break;
            case TR: x = W - w - ox;            y = oy;                    break;
            case CL: x = ox;                    y = (H - h) * 0.5f + oy;   break;
            case CC: x = (W - w) * 0.5f + ox;   y = (H - h) * 0.5f + oy;   break;
            case CR: x = W - w - ox;            y = (H - h) * 0.5f + oy;   break;
            case BL: x = ox;                    y = H - h - oy;            break;
            case BC: x = (W - w) * 0.5f + ox;   y = H - h - oy;            break;
            case BR: x = W - w - ox;            y = H - h - oy;            break;
            default: x = ox;                    y = oy;                    break;
        }
        return new Rect(id, x, y, w, h);
    }

    /**
     * {@link #solve} + {@link #clamp}：求解后保证不溢出。
     * 正常分辨率下钳制不触发 → 与 {@link #solve} 结果逐位相同。
     */
    public static Rect fit(String id, int anchor, float ox, float oy,
                           float w, float h, float W, float H) {
        Rect r = solve(id, anchor, ox, oy, w, h, W, H);
        clamp(r, W, H, MARGIN);
        return r;
    }

    /**
     * 溢出钳制：把矩形推回 {@code [margin, W-margin] × [margin, H-margin]}。
     *
     * <p>顺序是刻意的：<b>先收缩、再平移、最后二次钳位</b>。
     * 尺寸超出画布时先收缩（否则平移无意义）；平移后若左/上边越界再钳一次
     * （此时盒子已 ≤ 画布，二次钳位保证收敛，不会来回弹）。
     *
     * <p>⚠️ 这是本次唯一会<b>改变画面</b>的代码路径，且只在溢出时触发。
     * 它的价值是「小窗口下 HUD 不再糊成一团」，代价是那些分辨率下布局与旧版不同。
     */
    public static void clamp(Rect r, float W, float H, float margin) {
        float maxW = Math.max(0f, W - 2f * margin);
        float maxH = Math.max(0f, H - 2f * margin);
        if (r.w > maxW) r.w = maxW;
        if (r.h > maxH) r.h = maxH;
        if (r.x + r.w > W - margin) r.x = W - margin - r.w;
        if (r.y + r.h > H - margin) r.y = H - margin - r.h;
        if (r.x < margin) r.x = margin;
        if (r.y < margin) r.y = margin;
    }

    /**
     * 竖向堆叠容器：游标从 {@code oy} 沿屏幕 y 方向累加，每次 {@link #next} 自动落到下一个位置。
     *
     * <p>用途：左上角的状态块 + 村志面板，原来是手写的 {@code y = 14} 与 {@code y = 88}
     * （间距 8 靠人工保证）；改成 {@code Stack(TL, 14, 14, 8)} 后，任一面板改高度，
     * 下面的自动跟着走。
     *
     * <p>限制：只支持顶边系锚点（{@link #TL}/{@link #TC}/{@link #TR}），
     * 因为底边系需要反向累加、语义容易混淆 —— 需要时请直接调 {@link #solve}。
     */
    public static final class Stack {
        private final int anchor;
        private final float ox;
        private final float oy;
        private final float gap;
        private final float W;
        private final float H;
        private float cursor;

        public Stack(int anchor, float ox, float oy, float gap, float W, float H) {
            if (anchor != TL && anchor != TC && anchor != TR) {
                throw new IllegalArgumentException("Stack 仅支持 TL/TC/TR（顶边锚），收到 anchor=" + anchor);
            }
            this.anchor = anchor;
            this.ox = ox;
            this.oy = oy;
            this.gap = gap;
            this.W = W;
            this.H = H;
            this.cursor = 0f;
        }

        /** 取下一个槽位并推进游标。 */
        public Rect next(String id, float w, float h) {
            Rect r = fit(id, this.anchor, this.ox, this.oy + this.cursor, w, h, this.W, this.H);
            this.cursor += h + this.gap;
            return r;
        }
    }
}
