package core.systems;

import core.rng.SeededRNG;
import core.world.Blocks;
import core.world.World;

/**
 * 地热喷泉（地质/水文）：STONE 环绕的浅水偶发喷发，复用 WATER 在水面上方竖起一道“喷泉痕”，
 * 演示“地热活动”的间歇涌现。随机走 simStream。
 */
public final class GeyserSystem implements System {
    @Override public String name() { return "geyser"; }

    @Override
    public void update(World w, SeededRNG rng) {
        int samples = 24;
        for (int i = 0; i < samples; i++) {
            int x = rng.nextInt(w.SX), z = rng.nextInt(w.SZ);
            int wy = CoralSystem.waterSurface(w, x, z);
            if (wy < 0) continue;
            // 同深度四邻须有 STONE（地热岩环）
            boolean geo = w.getBlock(x - 1, wy, z) == Blocks.STONE.index
                       || w.getBlock(x + 1, wy, z) == Blocks.STONE.index
                       || w.getBlock(x, wy, z - 1) == Blocks.STONE.index
                       || w.getBlock(x, wy, z + 1) == Blocks.STONE.index;
            if (geo && wy + 1 < w.SY && w.mat[x][wy + 1][z] == Blocks.AIR.index
                    && rng.nextDouble() < 0.02) {
                w.setBlock(x, wy + 1, z, Blocks.WATER.index);   // 喷泉（竖起水柱）
                w.log("geyser", "erupt", "x=" + x + ",z=" + z, "water");
            }
        }
    }
}
