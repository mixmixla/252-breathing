package render.lwjgl;

/**
 * 方块级<b>自发差异</b>：让"同一种方块的不同个体"看起来不一样。
 *
 * <p><b>为什么需要</b>：贴图是每材质一张 tile，于是同材质的每个方块<b>逐像素完全相同</b> ——
 * 一整片石砌地面读起来像"瓷砖墙"（复制粘贴感），而这正是"方块缺少自己个性"的根源。
 * 现实里没有两块完全一样的石头：每块的风化程度、矿物杂质、受潮情况都不同。
 *
 * <p><b>为什么用顶点色实现</b>（而不是多变体贴图 / UV 旋转）：
 * <ul>
 *   <li>{@code putV} 手里本来就有方块坐标 {@code (bx,by,bz)}，且同一面的 4 个角点共享它
 *       —— 乘上去天然是"整块色调一致"，正是要的粒度；</li>
 *   <li>不动顶点布局（{@code VERT_FLOATS} 仍 16）、不加图集槽位、不改 shader
 *       —— 三种"改错会静默失效"的地方全都绕开了；</li>
 *   <li>顶点色本来就承担 AO × 方向明度 × 拼贴缝，加一层同量级的乘性差异不会破坏它们。</li>
 * </ul>
 *
 * <p><b>纪律</b>：纯函数、零状态、零 RNG、确定性 —— 同坐标恒同值，因此不进 {@code hashState}、
 * 不影响逐字节可复现（无头截图 A/B 仪器照常工作）。
 */
public final class VoxelVariety {

    private VoxelVariety() { }

    /**
     * 强度倍率（0 = 完全关闭，顶点色逐字节等价改造前）。由系统属性 {@code -Dbw.variety} 设定。
     *
     * <p>存在的理由：这类"乘在顶点色上的细微偏移"极难靠肉眼判断<b>是否真的接上了</b>
     * （幅度 ±6% 在远景里几乎看不出来）。有个 0/1 开关就能做 A/B 数值对比 ——
     * 本项目对"看起来生效了"的验证纪律是：**要有数字**。
     */
    private static final float STRENGTH = readStrength();

    private static float readStrength() {
        String s = System.getProperty("bw.variety");
        if (s == null) return 1f;
        try {
            float v = Float.parseFloat(s.trim());
            if (Float.isNaN(v)) return 1f;
            return Math.max(0f, Math.min(1f, v));
        } catch (NumberFormatException e) {
            return 1f;
        }
    }

    /**
     * 坐标 → [0,1) 的确定性哈希（Wang/Murmur 风格整数位混合）。
     *
     * <p>⚠️ <b>不用</b> {@code fract(sin(dot(p, ...)))} 那一族：P4 已实测大参数下
     * {@code sin} 的精度不可靠（当时是 GPU 侧；这里虽是 CPU，但保持整数哈希可以彻底免掉
     * "换平台/换 JIT 后低位不一致"的风险，而本项目的立身之本就是逐字节复现）。
     */
    public static float of(int bx, int by, int bz) {
        int h = bx * 0x1f1f1f1f ^ by * 0x27d4eb2d ^ bz * 0x165667b1;
        h ^= h >>> 15;
        h *= 0x2545f491;
        h ^= h >>> 13;
        h *= 0x27d4eb2d;
        h ^= h >>> 16;
        return (h & 0xFFFFFF) / 16777215f;
    }

    /**
     * 明度系数，范围 {@code [0.94, 1.06]}（±6%）。
     *
     * <p>幅度取 ±6% 的依据：AO 的四档是 1.00/0.80/0.62/0.42（相邻档差 ≥18%），
     * 方向明度差 ≥10% —— 取 ±6% 才能"看得出来"又不会盖过它们。再大就会让 AO 的
     * 细腻过渡被淹没，画面反而变脏。
     */
    public static float bright(int bx, int by, int bz) {
        return 1f + (0.94f + 0.12f * of(bx, by, bz) - 1f) * STRENGTH;
    }

    /**
     * 色相偏移系数，范围 {@code [-0.05, +0.05]}（>0 偏暖，即 R↑ B↓）。
     *
     * <p>作用：让一堵石墙出现"青石 / 黄石混砌"的观感，而不是同一个灰。
     * 幅度压在 ±5% 以内 —— 超过约 8% 就会读成"染色"而不是"材质不均"。
     */
    public static float hue(int bx, int by, int bz) {
        return (of(bx, by, bz) - 0.5f) * 0.10f * STRENGTH;
    }
}
