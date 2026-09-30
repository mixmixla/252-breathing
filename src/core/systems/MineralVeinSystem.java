package core.systems;

import core.rng.SeededRNG;
import core.world.Blocks;
import core.world.World;

/**
 * 矿脉蔓延（79 · 地质/深地）：STONE 邻 COAL_ORE/IRON_ORE 时低概率向该 STONE 扩展矿脉（置同 ORE），
 * 记 "mineralvein"."extend" event。
 * 与 OreExposureSystem（暴露 STONE 表面从零风化出矿，不依赖既有矿）区分：本系统要求“已有矿脉邻格”才蔓延；
 * 与 GeodeSystem（封闭空腔生成 ORE 晶簇）区分：本系统沿岩体把矿脉向邻石推进，不发生在空腔。
 * 零漂移：随机仅走入参 rng；用 setBlock 演化（STONE↔ORE 总量守恒）。
 */
public final class MineralVeinSystem implements System {
    private static final int[][] D = {
            {1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}
    };

    @Override public String name() { return "mineralvein"; }

    @Override
    public void update(World w, SeededRNG rng) {
        int samples = 16;
        for (int i = 0; i < samples; i++) {
            int x = rng.nextInt(w.SX), y = rng.nextInt(w.SY), z = rng.nextInt(w.SZ);
            if (w.mat[x][y][z] != Blocks.STONE.index) continue;
            int ore = -1;                                  // 邻格已有矿脉则向邻 STONE 扩展
            for (int[] o : D) {
                int b = w.getBlock(x + o[0], y + o[1], z + o[2]);
                if (b == Blocks.COAL_ORE.index) { ore = Blocks.COAL_ORE.index; break; }
                if (b == Blocks.IRON_ORE.index) { ore = Blocks.IRON_ORE.index; break; }
            }
            if (ore < 0) continue;
            if (rng.nextDouble() < 0.05) {
                w.setBlock(x, y, z, ore);
                w.log("mineralvein", "extend", "at=" + x + "," + y + "," + z, "vein");
            }
        }
    }
}
