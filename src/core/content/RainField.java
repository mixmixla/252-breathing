package core.content;

/**
 * 雨幕的**纯几何模型**（零 GL 依赖）—— 与 {@link ParticleSim} 同一套理由：
 * 渲染表现里"是数学的那部分"放 core，才能在无头环境里被门禁完整断言。
 *
 * <p><b>为什么需要它</b>：雨幕层（{@code Game.drawRainOverlay}）此前**零验证** ——
 * 它只在有 GL 的机器上才看得见，而软件渲染器不含 HUD/叠加层。于是一旦写错
 * （雨丝全跑到屏外、alpha 不随雨量、长会话里 {@code time} 变大后位置溢出/抖动）
 * 没有任何东西会发现。把几何抽出来之后，这些性质都能在无头门禁里断言。
 *
 * <p><b>零 RNG</b>：所有随机量走 {@link EffectQueue#hash01} 纯哈希派生
 * （不碰 {@code fxRng}、更不碰 {@code simStream}）→ 对仿真指纹零影响。
 *
 * <p><b>零分配</b>：{@link #streak} 把结果写进调用方给的 {@code float[6]}，
 * 240 条雨丝每帧不产生任何对象。
 *
 * <p><b>与 Game 的契约</b>：{@code Game} 只在 {@code rainSmooth > 0.01} 时调用
 * （{@link #alphaMain} / {@link #alphaSecond} 在 {@code rainSmooth = 0} 时正好为 0，
 * 即"不可见"，两边一致）。
 */
public final class RainField {

    /** 雨丝条数（**固定** → 每帧顶点量恒定，不随雨量增长）。 */
    public static final int STREAKS = 240;

    /** 每条雨丝的输出槽位数（x, y, len, slant, alphaMain, alphaSecond）。 */
    public static final int FIELDS = 6;

    /** 主丝 / 副丝的基准不透明度（乘 {@code rainSmooth}）。 */
    public static final float ALPHA_MAIN = 0.30f;
    public static final float ALPHA_SECOND = 0.20f;

    /** 长度取值域（像素）。 */
    public static final float LEN_MIN = 22f, LEN_MAX = 56f;
    /** 下落速度取值域（像素/秒）。 */
    public static final float SPD_MIN = 900f, SPD_MAX = 1800f;
    /** 斜度取值域（像素，副丝相对主丝的水平偏移）。 */
    public static final float SLANT_MIN = 2f, SLANT_MAX = 6f;
    /** 水平溢出（左右各留一点，避免雨丝在屏幕边缘突然出现/消失）。 */
    public static final float X_PAD = 80f;
    /** 纵向跨度余量（让雨丝从屏幕上方之外开始落）。 */
    public static final float Y_PAD = 140f;

    private RainField() { }

    /** 纵向跨度（屏幕高 + 余量）。 */
    public static float spanY(float h) { return h + Y_PAD; }

    /**
     * 取第 {@code i} 条雨丝，写入 {@code out[0..5]}。
     *
     * <p>纯函数：只依赖入参，同入参必得同输出（门禁断言 {@code RAIN_DET}）。
     *
     * @param i          雨丝序号，∈ [0, {@link #STREAKS})
     * @param w          屏幕宽（像素）
     * @param h          屏幕高（像素）
     * @param time       渲染层动画钟（秒）
     * @param rainSmooth 雨量平滑值 ∈ [0,1]
     * @param out        长度 ≥ {@link #FIELDS} 的输出缓冲
     */
    public static void streak(int i, float w, float h, float time, float rainSmooth, float[] out) {
        float h1 = EffectQueue.hash01(11L, i, 7);
        float h2 = EffectQueue.hash01(11L, i, 19);
        float h3 = EffectQueue.hash01(11L, i, 31);
        float span = spanY(h);
        float len = LEN_MIN + (LEN_MAX - LEN_MIN) * h2;
        float spd = SPD_MIN + (SPD_MAX - SPD_MIN) * h3;
        out[0] = h1 * (w + 2f * X_PAD) - X_PAD;                  // x（含左右溢出）
        out[1] = (h2 * span + time * spd) % span - len;          // y（循环下落；% 保证非负）
        out[2] = len;
        out[3] = SLANT_MIN + (SLANT_MAX - SLANT_MIN) * h3;       // 副丝水平偏移（伪造斜度）
        out[4] = alphaMain(rainSmooth);
        out[5] = alphaSecond(rainSmooth);
    }

    /** 主丝不透明度（随雨量线性）。 */
    public static float alphaMain(float rainSmooth) { return ALPHA_MAIN * rainSmooth; }

    /** 副丝不透明度（随雨量线性）。 */
    public static float alphaSecond(float rainSmooth) { return ALPHA_SECOND * rainSmooth; }

    /** 本层每帧提交的顶点数（2 个四边形 / 条 × 6 顶点）—— 供渲染预算门禁核算。 */
    public static int vertexCount() { return STREAKS * 2 * 6; }
}
