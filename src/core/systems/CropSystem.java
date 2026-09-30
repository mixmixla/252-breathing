package core.systems;

import core.rng.SeededRNG;
import core.world.Blocks;
import core.world.World;

import java.util.List;

/**
 * 作物生长系统（植物/农业，第二批「源源不断」2026-09-23）：小麦 / 甘蔗 / 仙人掌花 的确定性生长 tick。
 *
 * <p>规则：所在格<b>下方为「土」（DIRT/GRASS；甘蔗另认 SAND）</b>且<b>上方为 AIR（有生长空间）</b>时，
 * 以低概率逐级推进到下一生长阶段：
 * <ul>
 *   <li>小麦：WHEAT_0 → WHEAT_1 → WHEAT_2 → WHEAT_3（成熟，可收获→wheat）</li>
 *   <li>甘蔗：SUGARCANE_0 → SUGARCANE_1 → SUGARCANE_2（成熟，可收获→sugarcane）</li>
 *   <li>仙人掌花：CACTUS_FLOWER_0 → CACTUS_FLOWER_1 → CACTUS_FLOWER_2（绽放，可收获→cactus_flower）</li>
 * </ul>
 *
 * <p>确定性铁律：随机仅走入参 {@code rng}（逐 tick 派生的 simStream 子流）；只用 {@code setBlock} 演化；
 * 旧世界不含任何作物 → 不改变仿真指纹的**确定性**（ZeroDriftTest 同种子两跑一致）。
 */
public final class CropSystem implements System {

    /** 耕地（FARMLAND）相对普通土的生长概率倍数（"精耕细作"，第七批）。 */
    public static final float TILL_BONUS = 1.6f;

    @Override public String name() { return "crop"; }

    @Override
    public void update(World w, SeededRNG rng) {
        grow(w, rng, Blocks.WHEAT_0, Blocks.WHEAT_1, Blocks.WHEAT_2, Blocks.WHEAT_3, 0.05f, false);
        grow(w, rng, Blocks.SUGARCANE_0, Blocks.SUGARCANE_1, Blocks.SUGARCANE_2, null, 0.03f, true);
        grow(w, rng, Blocks.CACTUS_FLOWER_0, Blocks.CACTUS_FLOWER_1, Blocks.CACTUS_FLOWER_2, null, 0.025f, false);
        saplingGrowth(w, rng);                       // 第十二批：树苗 → 小树（排在末尾，见方法注释的零漂移论证）
    }

    /** 树苗成材概率（比作物低：树是"基建"，不该几 tick 就长成）。 */
    private static final float SAPLING_P = 0.02f;

    /**
     * 树苗 → 小树（第十二批）：让玩家第一次能"自己种出一棵树"，闭环既有的 {@code TreeGrowthSystem} 生态。
     *
     * <p><b>零漂移论证（与 {@code promote} 同一套）</b>：本方法按 {@code cellsOfType(SAPLING)} 逐格
     * <b>恰好抽一次</b> {@code nextDouble()}；而<b>门禁世界里没有任何 SAPLING</b> ⇒ 集合为空 ⇒
     * 循环体一次都不进 ⇒ <b>零次抽取</b>。再加上它排在 {@code update} 的<b>末尾</b>，
     * 于是既有三类作物的抽取次数与顺序<b>逐字节不变</b> ⇒ 指纹不变。
     *
     * <p>触发后写死的形状（无额外 RNG）：{@code WOOD} 主干 2 格 + {@code LEAF} 冠两层（3×3 去四角）。
     */
    private static void saplingGrowth(World w, SeededRNG rng) {
        List<int[]> cells = new java.util.ArrayList<int[]>(w.cellsOfType(Blocks.SAPLING.index));
        if (cells.isEmpty()) return;
        for (int[] c : cells) {
            int x = c[0], y = c[1], z = c[2];
            if (y <= 0 || y + 3 >= w.SY) continue;
            int q = soilQuality(w, x, y - 1, z, false);          // 与作物同一口径：耕地也算，且给加成
            if (q == 0) continue;
            float prob = (q == 2) ? Math.min(1f, SAPLING_P * TILL_BONUS) : SAPLING_P;
            if (rng.nextDouble() >= prob) continue;              // 每格恰好一次抽取
            growTree(w, x, y, z);
        }
    }

    /** 在 (x,y,z) 长出一棵缩微树（形状写死 → 确定性，不耗 RNG）。 */
    private static void growTree(World w, int x, int y, int z) {
        for (int i = 0; i < 2; i++) {
            if (y + i >= w.SY) return;
            w.setBlock(x, y + i, z, Blocks.WOOD.index);          // 主干
        }
        int top = y + 2;
        for (int dy = 0; dy <= 1; dy++) {
            int yy = top + dy;
            if (yy >= w.SY) break;
            for (int dx = -1; dx <= 1; dx++)
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dz == 0) continue;            // 主干位置留着
                    if (dx != 0 && dz != 0 && dy == 1) continue; // 顶层的四角去掉（树冠收口）
                    if (w.getBlock(x + dx, yy, z + dz) == Blocks.AIR.index)
                        w.setBlock(x + dx, yy, z + dz, Blocks.LEAF.index);
                }
        }
    }

    private static void grow(World w, SeededRNG rng,
                              Blocks.Block s0, Blocks.Block s1, Blocks.Block s2, Blocks.Block s3,
                              float p, boolean sandOk) {
        promote(w, rng, s0, s1, p, sandOk);
        promote(w, rng, s1, s2, p, sandOk);
        if (s3 != null) promote(w, rng, s2, s3, p, sandOk);
    }

    private static void promote(World w, SeededRNG rng, Blocks.Block from, Blocks.Block to, float p, boolean sandOk) {
        List<int[]> cells = new java.util.ArrayList<int[]>(w.cellsOfType(from.index));
        for (int[] c : cells) {
            int x = c[0], y = c[1], z = c[2];
            if (y <= 0 || y >= w.SY - 1) continue;
            int q = soilQuality(w, x, y - 1, z, sandOk);
            if (q == 0) continue;                                              // 下方须为土（耕地也算）
            if (w.getBlock(x, y + 1, z) != Blocks.AIR.index) continue;        // 上方须有空间
            // 第七批：耕地（FARMLAND）是"精耕细作" → 生长概率提高 TILL_BONUS 倍（真正的农业循环）。
            // 注意**抽取次数不变**（无论 q 取何值都恰好 nextDouble 一次）→ 既有世界的 RNG 流逐字节不变；
            // 且门禁世界不含 FARMLAND → 概率不变 → 零漂移。
            float prob = (q == 2) ? Math.min(1f, p * TILL_BONUS) : p;
            if (rng.nextDouble() < prob) w.setBlock(x, y, z, to.index);
        }
    }

    /**
     * 下方"土"的质量：0 = 不可种，1 = 普通土（DIRT/GRASS，甘蔗另认 SAND），2 = 耕地（FARMLAND，有加成）。
     *
     * <p>把"能不能种"和"种得好不好"合成一个返回值，是为了让调用点只做一次判定 —— 避免出现
     * "先 if 能种、再另写一段判断是不是耕地"的两份名单（那种写法迟早走散）。
     */
    private static int soilQuality(World w, int x, int y, int z, boolean sandOk) {
        int b = w.getBlock(x, y, z);
        if (b == Blocks.FARMLAND.index) return 2;
        if (b == Blocks.DIRT.index || b == Blocks.GRASS.index) return 1;
        return (sandOk && b == Blocks.SAND.index) ? 1 : 0;
    }
}
