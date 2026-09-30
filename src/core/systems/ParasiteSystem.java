package core.systems;

import core.rng.SeededRNG;
import core.world.Blocks;
import core.world.World;

/**
 * 病害传播（61 · parasite，生物/种群）：LEAF 密集处（6 邻 ≥4 个 LEAF）低概率(rng)染病，
 * 把部分 LEAF 变成 DIRT（枯黄凋零），表现病害在郁闭植丛中蔓延。
 * 与 DecaySystem 区分：DecaySystem 处理“孤立”WOOD/LEAF（四周皆 AIR）腐朽为 AIR；
 * 本系统处理“密集”LEAF 簇染病变 DIRT，二者一疏一密、一空一实，方向相反不重叠。
 * 与 NestHatchSystem 区分：NestHatch 把密集 LEAF 旁空腔补新 LEAF（繁殖），本系统把密集 LEAF 变 DIRT（消亡）。
 * 随机严格走 simStream 入参 rng。
 */
public final class ParasiteSystem implements System {
    private static final int[][] N6 = {
        {1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}
    };

    @Override public String name() { return "parasite"; }

    @Override
    public void update(World w, SeededRNG rng) {
        int samples = 20;
        for (int i = 0; i < samples; i++) {
            int x = rng.nextInt(w.SX), y = rng.nextInt(w.SY), z = rng.nextInt(w.SZ);
            if (w.mat[x][y][z] != Blocks.LEAF.index) continue;
            int n = 0;
            for (int[] o : N6)
                if (w.getBlock(x + o[0], y + o[1], z + o[2]) == Blocks.LEAF.index) n++;
            if (n >= 4 && rng.nextDouble() < 0.02) {  // LEAF 密集 → 染病凋零
                w.setBlock(x, y, z, Blocks.DIRT.index);
                w.log("parasite", "infect", "x=" + x + ",z=" + z, "leaf=" + n);
            }
        }
    }
}
