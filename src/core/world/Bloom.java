package core.world;

/**
 * 泰拉瑞亚缺口②：选择性泛光（bloom）的纯数学常量与函数（零 GL 依赖，可无头门禁）。
 *
 * <p>作为<b>单一真相</b>：渲染层（{@code render.lwjgl.Game}）的阈值/强度取自这里，
 * 9-tap 高斯权重 {@link #WEIGHTS} 与 GLSL 内联权重保持一致（GLSL 无法调用 Java，故为文档化副本）。
 * 本类自身可被 {@code core.sim.BloomTest} 在无 GPU 环境守护：权重归一、阈值/强度合法、亮部函数单调截断。</p>
 *
 * <p>泛光是<b>纯渲染后处理</b>，不读不写任何仿真状态、不进指纹，符合零漂移铁律。</p>
 */
public final class Bloom {

    private Bloom() { }

    /** 亮部阈值：场景像素亮度 &gt; 此值才进入辉光（LAMP/FIRE/GOLD 的 emissive 已 &gt;1，必过）。 */
    public static final float THRESHOLD = 0.72f;
    /** 合成强度：模糊结果加性混合到画面的系数。 */
    public static final float INTENSITY = 0.95f;
    /** 9-tap 高斯权重（中心 + 四对对称偏移）；与 {@code Game.initBloom} 的 blurFS 内联权重一致。 */
    public static final float[] WEIGHTS = {0.227027f, 0.1945946f, 0.1216216f, 0.054054f, 0.016216f};

    // ---- 时间累积泛光（Noita post_glow1/2.frag，2026-09-21）----
    // 现有 9-tap 模糊是「每帧完全重来」——帧间零记忆，火光没有余晖（"灯泡感"而非"火焰感"）。
    // Noita 的做法：保持一张「上一帧 glow 缓冲」ping-pong，横/纵各做一轮一维模糊并衰减，
    // 再只用很小的权重追上本帧新样本 → 99% 记忆。这条链对「持续发光体」的感知提升极大。
    // 全部为纯渲染常量；不读不写任何仿真状态、不进指纹（零漂移）。

    /** 时间累积先做横扫、再做纵扫，两轮都用这个半宽（Noita BLUR_RADIUS=5，采 11 个 tap）。 */
    public static final int GLOW_BLUR_RADIUS = 5;
    /** 每轮一维模糊后乘的衰减系数（Noita post_glow1 的 {@code decayed *= 0.1}）。 */
    public static final float GLOW_DECAY_ONE_D = 0.1f;
    /** 纵扫（第二轮）的归一化（Noita post_glow2 的 {@code * 1.0/12.2}）。 */
    public static final float GLOW_DECAY_TWO_D = 1.0f / 12.2f;
    /** 本帧新样本追赶旧缓冲的权重（Noita 的 {@code mix(decayed, new_tap, 0.05)} → 只有 5% 追新）。 */
    public static final float GLOW_NEW_TAP = 0.05f;
    /** 本帧新样本自身的直接亮度增益（Noita 的 {@code new_tap * 0.125}）。 */
    public static final float GLOW_DIRECT_GAIN = 0.125f;
    /**
     * 边缘残影抑制（Noita {@code EDGE_AFTER_IMAGE_REDUCTION_*}）。
     * 相机快速移动时，上一帧的亮区会"贴在屏幕上不动"——越靠近屏幕边缘越压暗累积值来抑制。
     * {@code GLOW_EDGE_SIZE} 是受影响的边缘带宽（UV 比例），{@code GLOW_EDGE_AMOUNT} 是压暗斜率。
     */
    public static final float GLOW_EDGE_SIZE = 0.05f;
    public static final float GLOW_EDGE_AMOUNT = 2.0f;

    /** 一维模糊（沿 {@code dir}）每个 tap 的权重：均匀 1/(2R+1)，R={@link #GLOW_BLUR_RADIUS} 时 = 1/11。 */
    public static float glowTapWeight() {
        return 1.0f / (2 * GLOW_BLUR_RADIUS + 1);
    }

    /**
     * 时间累积的一轮结果（<b>纯函数</b>，与 GLSL 内联数学同源，供无头门禁断言）。
     *
     * @param prevDecayed 上一帧缓冲经本向模糊并衰减后的值（GLSL 里由 11 个 tap 求和 × 衰减系数得到）
     * @param newTap      本帧新样本（GLSL 里来自亮部提取纹理）
     * @return 写回 ping-pong 缓冲的值
     */
    public static float glowAccumulate(float prevDecayed, float newTap) {
        return newTap * GLOW_DIRECT_GAIN + (prevDecayed * (1f - GLOW_NEW_TAP) + newTap * GLOW_NEW_TAP);
    }

    /**
     * 边缘残影抑制权重（<b>纯函数</b>）：UV 越靠边越小，中段恒为 1。
     * 与 Noita 一致：只有 1D 坐标越界时才压暗，且两侧各只覆盖 {@link #GLOW_EDGE_SIZE} 带宽。
     */
    public static float glowEdgeWeight(float u) {
        float inv = 1.0f - GLOW_EDGE_SIZE;
        if (u > inv) return 1.0f - (u - inv) * GLOW_EDGE_AMOUNT;
        if (u < GLOW_EDGE_SIZE) return 1.0f - (GLOW_EDGE_SIZE - u) * GLOW_EDGE_AMOUNT;
        return 1.0f;
    }

    /** 权重和（应 ≈ 1，保证模糊不增不减能量）。 */
    public static float weightsSum() {
        float s = WEIGHTS[0];
        for (int i = 1; i < WEIGHTS.length; i++) s += 2f * WEIGHTS[i];
        return s;
    }

    /** 亮部提取：亮度低于阈值返回 0；高于则在 [阈值, 阈值+0.35] 区间平滑过渡到原亮度（截断上限）。 */
    public static float bright(float lum) {
        if (lum < THRESHOLD) return 0f;
        float t = (lum - THRESHOLD) / 0.35f;
        if (t > 1f) t = 1f;
        return lum * t;
    }
}
