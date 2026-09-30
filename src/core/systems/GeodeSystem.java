package core.systems;

import core.rng.SeededRNG;
import core.world.Blocks;
import core.world.World;

/**
 * 晶洞结晶（74 · 地质/深地）：封闭 STONE 空腔（AIR 被 STONE 从 ≥5 面夹住）以低概率生成
 * COAL_ORE/IRON_ORE 晶簇（把该 AIR 置 ORE）。
 * 与 OreExposureSystem（暴露空气中的 STONE 表面风化出矿脉）区分：本系统是“封闭晶洞内部结晶”，
 * 不要求暴露，只要求被岩壁严实包裹的空腔；与 MushroomSystem（同结构空腔长 LEAF）区分：本系统填 ORE 且记 geode 事件。
 * 零漂移：随机仅走入参 rng；用 setBlock 演化。
 */
public final class GeodeSystem implements System {
    private static final int[][] D = {
            {1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}
    };

    @Override public String name() { return "geode"; }

    @Override
    public void update(World w, SeededRNG rng) {
        int samples = 16;
        for (int i = 0; i < samples; i++) {
            int x = rng.nextInt(w.SX), y = rng.nextInt(w.SY), z = rng.nextInt(w.SZ);
            if (w.mat[x][y][z] != Blocks.AIR.index) continue;
            int stoneN = 0;
            for (int[] o : D)
                if (w.getBlock(x + o[0], y + o[1], z + o[2]) == Blocks.STONE.index) stoneN++;
            if (stoneN >= 5 && rng.nextDouble() < 0.04) {
                // 第十二批：晶洞内部结晶 —— 改用 CRYSTAL（原来在煤/铁矿里二选一）。
                //  ⚠️ 同时**去掉**了原来那次 `nextDouble() < 0.5` 的抽取：它是"二选一"的掷币，
                //  目标改成单一材质后就是死逻辑。这会平移本系统的 RNG 流 —— 属于本次"接受新基线"的范围。
                w.setBlock(x, y, z, Blocks.CRYSTAL.index);
                w.log("geode", "form", "at=" + x + "," + y + "," + z, "cluster");
            }
        }
    }
}
