package core.sim;

import core.world.Bloom;

/**
 * 门禁 BLOOM：泰拉瑞亚缺口②（选择性泛光）的纯数学守护（无头，零 GL）。
 *
 * <p>守护 {@link Bloom} 的两条不变量：
 * <ol>
 *   <li><b>权重守恒</b>：9-tap 高斯权重和 ≈ 1，保证模糊不增不减能量（不爆白也不吃暗）。</li>
 *   <li><b>亮部提取正确</b>：低于阈值返回 0；阈值以上在 [thr, thr+0.35] 平滑过渡到原亮度（单调截断）。</li>
 *   <li><b>零漂移</b>：纯常量/纯函数，不读不改任何仿真状态、不进 hashState；渲染层仅取其阈值/强度。</li>
 * </ol>
 *
 * <p>GLSL 内联权重（{@code Game.initBloom} 的 blurFS）是 {@link Bloom#WEIGHTS} 的文档化副本，
 * 二者必须一致——本门禁锁死 Java 侧真相，渲染层照抄即可。</p>
 */
public class BloomTest {

    private static boolean ok = true;

    private static void check(String name, boolean cond) {
        System.out.println("  [" + (cond ? "ok  " : "FAIL") + "] " + name);
        if (!cond) ok = false;
    }

    public static void main(String[] args) {
        System.out.println("=== BLOOM: 泛光数学常量 ===");

        // 1) 阈值/强度合法
        check("THRESHOLD 在 (0,1]", Bloom.THRESHOLD > 0f && Bloom.THRESHOLD <= 1f);
        check("INTENSITY > 0", Bloom.INTENSITY > 0f);

        // 2) 权重守恒（≈1）
        float ws = Bloom.weightsSum();
        check("9-tap 权重和≈1 (=" + ws + ")", Math.abs(ws - 1.0f) < 1e-3f);

        // 3) 亮部提取：低于阈值=0
        check("bright(0)=0", Bloom.bright(0f) == 0f);
        check("bright(阈值下)=0", Bloom.bright(Bloom.THRESHOLD - 0.01f) == 0f);
        check("bright(阈值)=0", Bloom.bright(Bloom.THRESHOLD) == 0f);

        // 4) 亮部提取：阈值以上单调上升、并在 [thr, thr+0.35] 内过渡到原亮度
        float a = Bloom.bright(Bloom.THRESHOLD + 0.10f);
        float b = Bloom.bright(Bloom.THRESHOLD + 0.20f);
        check("bright 阈值以上单调上升", b > a && a > 0f);
        check("bright(远高于阈值)→原亮度(不爆白)", Math.abs(Bloom.bright(2.0f) - 2.0f) < 1e-5f);
        check("bright 上限截断=原亮度(阈值+0.35 处)", Math.abs(Bloom.bright(Bloom.THRESHOLD + 0.35f) - (Bloom.THRESHOLD + 0.35f)) < 1e-5f);

        // 5) 零漂移：纯函数确定性、不碰任何状态
        check("bright 确定性(两次相等)", Bloom.bright(0.9f) == Bloom.bright(0.9f));
        check("weightsSum 确定性", Bloom.weightsSum() == ws);

        // 6) 时间累积泛光（Noita post_glow1/2）：一维 tap 权重 + 记忆 + 边缘抑制
        check("glow 一维 tap 权重 = 1/11", Math.abs(Bloom.glowTapWeight() - 1f / 11f) < 1e-6f);
        check("glow 边缘尺寸/斜率合法", Bloom.GLOW_EDGE_SIZE > 0f && Bloom.GLOW_EDGE_SIZE < 0.5f
                && Bloom.GLOW_EDGE_AMOUNT > 0f);
        check("glow 新样本权重在 (0,1)", Bloom.GLOW_NEW_TAP > 0f && Bloom.GLOW_NEW_TAP < 1f);
        check("glow 直接增益在 (0,1]", Bloom.GLOW_DIRECT_GAIN > 0f && Bloom.GLOW_DIRECT_GAIN <= 1f);
        // 记忆：新样本为 0 时，累积值只按 (1-NEW_TAP) 衰减 → 不会瞬间清零（这是"余晖"的数学本质）
        float prevStill = Bloom.glowAccumulate(1.0f, 0f);
        check("glow 有记忆(输入归零仍有残值)", prevStill > 0f);
        check("glow 记忆系数 = 1-GLOW_NEW_TAP", Math.abs(prevStill - (1f - Bloom.GLOW_NEW_TAP)) < 1e-6f);
        // 单调：新样本越亮 → 累积结果越大（永不反转，防"越亮反而越暗"的符号错误）
        check("glow 对新样本单调不减",
                Bloom.glowAccumulate(0.5f, 0.9f) > Bloom.glowAccumulate(0.5f, 0.1f));
        // 稳态：持续输入 1.0 时，累积值收敛到有限值（不无限增长爆白）
        float s = 0f;
        for (int i = 0; i < 400; i++) s = Bloom.glowAccumulate(s, 1.0f);
        check("glow 稳态有界(持续亮不爆白, 终值=" + s + ")", s > 0f && s < 8f);
        s = 0f;
        for (int i = 0; i < 400; i++) s = Bloom.glowAccumulate(s, 1.0f);
        float s2 = 0f;
        for (int i = 0; i < 400; i++) s2 = Bloom.glowAccumulate(s2, 1.0f);
        check("glow 收敛确定性(两次相同)", s == s2);
        // 边缘抑制：中段恒 1；靠边 < 1 且越靠边越小（抑制相机移动残影）
        check("glow 边缘权重中段=1", Bloom.glowEdgeWeight(0.5f) == 1.0f);
        check("glow 边缘权重靠边<1", Bloom.glowEdgeWeight(0.01f) < 1.0f
                && Bloom.glowEdgeWeight(0.99f) < 1.0f);
        check("glow 边缘权重越靠边越小",
                Bloom.glowEdgeWeight(0.005f) < Bloom.glowEdgeWeight(0.03f));
        check("glow 边缘权重确定性", Bloom.glowEdgeWeight(0.01f) == Bloom.glowEdgeWeight(0.01f));

        System.out.println("BLOOM RESULT: " + (ok ? "PASS" : "FAIL"));
        if (!ok) System.exit(1);
    }
}
