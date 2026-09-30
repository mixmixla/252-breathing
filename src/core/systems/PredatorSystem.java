package core.systems;

import core.rng.SeededRNG;
import core.world.Blocks;
import core.world.World;

/**
 * 捕食压力（55 · predator，生物/种群）：扫描草原 GRASS 正上方的“兽群足迹”LEAF 标记，
 * 仅当该足迹位于植被（WOOD/LEAF）邻域时，以低概率(rng)把足迹抹回 AIR（表示被捕食）。
 * 与 HerdSystem 区分：HerdSystem 在连片草原记录“兽群足迹”记忆并加 prosperity（种群繁盛正反馈），
 * 本系统是其“反向”——表现生态捕食压力清除痕迹；不写 prosperity、不写 memory。
 * 随机严格走 simStream 入参 rng。
 */
public final class PredatorSystem implements System {
    @Override public String name() { return "predator"; }

    @Override
    public void update(World w, SeededRNG rng) {
        int samples = 16;
        for (int i = 0; i < samples; i++) {
            int x = rng.nextInt(w.SX), z = rng.nextInt(w.SZ);
            int top = AshFallSystem.surfaceY(w, x, z);
            if (top < 0 || top + 1 >= w.SY) continue;
            // 兽群足迹 = GRASS 正上方的稀疏 LEAF 标记（下方是 GRASS，区别于蜂巢 LEAF 下方是 FLOWER）
            if (w.mat[x][top][z] != Blocks.GRASS.index) continue;
            if (w.mat[x][top + 1][z] != Blocks.LEAF.index) continue;
            // 只捕食靠近植被（森林/灌木边缘）的足迹：开阔草原的痕迹保留
            if (!vegetationNear(w, x, top + 1, z)) continue;
            if (rng.nextDouble() < 0.04) {
                w.setBlock(x, top + 1, z, Blocks.AIR.index);   // 足迹被抹除＝被捕食
                w.log("predator", "hunt", "x=" + x + ",z=" + z, "graze");
            }
        }
    }

    /** 3×3×3 邻域（排除中心）是否含 WOOD/LEAF（捕食者活动的植被掩体）。 */
    private static boolean vegetationNear(World w, int x, int y, int z) {
        for (int dx = -1; dx <= 1; dx++)
            for (int dy = -1; dy <= 1; dy++)
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dy == 0 && dz == 0) continue;
                    int b = w.getBlock(x + dx, y + dy, z + dz);
                    if (b == Blocks.WOOD.index || b == Blocks.LEAF.index) return true;
                }
        return false;
    }
}
