package core.systems;

import core.rng.SeededRNG;
import core.world.Blocks;
import core.world.World;

/**
 * 治安（72·law，文明/社会）：草原上“兽群足迹”LEAF 过度密集（连片≥阈值）时，低概率(rng)由“守卫”
 * 清除部分足迹并记 "law"."order" event，表现治安维护。
 * 与 PredatorSystem（仅在植被邻域清除足迹，种群捕食语义）区分：本系统清除“任意位置过密足迹”，语义是“秩序”；
 * 与 RoadSystem（把足迹固化为 DIRT 路）区分：本系统是“清除”而非“保留固化”。
 * 确定性：随机严格走 simStream 入参 rng。
 */
public final class LawSystem implements System {
    @Override public String name() { return "law"; }

    @Override
    public void update(World w, SeededRNG rng) {
        int samples = 24;
        for (int i = 0; i < samples; i++) {
            int x = rng.nextInt(w.SX), z = rng.nextInt(w.SZ);
            int top = AshFallSystem.surfaceY(w, x, z);
            if (top < 0 || top + 1 >= w.SY) continue;
            if (w.mat[x][top][z] != Blocks.GRASS.index) continue;
            if (w.mat[x][top + 1][z] != Blocks.LEAF.index) continue;
            // 统计 5×5 邻域足迹密度（连片程度）
            int count = 0;
            for (int dx = -2; dx <= 2; dx++)
                for (int dz = -2; dz <= 2; dz++) {
                    int nx = x + dx, nz = z + dz;
                    if (!w.inBounds(nx, top, nz)) continue;
                    if (w.mat[nx][top][nz] == Blocks.GRASS.index
                            && w.mat[nx][top + 1][nz] == Blocks.LEAF.index) count++;
                }
            if (count >= 6 && rng.nextDouble() < 0.10) {
                int removed = 0;
                for (int dx = -1; dx <= 1 && removed < 3; dx++)
                    for (int dz = -1; dz <= 1 && removed < 3; dz++) {
                        if (dx == 0 && dz == 0) continue;
                        int nx = x + dx, nz = z + dz;
                        if (!w.inBounds(nx, top, nz)) continue;
                        if (w.mat[nx][top][nz] == Blocks.GRASS.index
                                && w.mat[nx][top + 1][nz] == Blocks.LEAF.index) {
                            w.setBlock(nx, top + 1, nz, Blocks.AIR.index);  // 守卫清除足迹
                            removed++;
                        }
                    }
                w.setBlock(x, top + 1, z, Blocks.AIR.index);   // 连本格一并清除
                w.log("law", "order", "x=" + x + ",z=" + z + ",removed=" + (removed + 1), "guard");
            }
        }
    }
}
