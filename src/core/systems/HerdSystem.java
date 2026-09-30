package core.systems;

import core.rng.SeededRNG;
import core.world.Blocks;
import core.world.World;

/**
 * 草原兽群（生物/世界回响）：连片草原（GRASS 密集）偶尔浮现“兽群足迹”记忆，写入事件/村庄记忆，
 * 并作为“繁荣反馈”小幅提升 w.prosperity——把草原生态与文明繁荣连起来。随机严格走 simStream。
 */
public final class HerdSystem implements System {
    @Override public String name() { return "herd"; }

    @Override
    public void update(World w, SeededRNG rng) {
        int samples = 16;
        for (int i = 0; i < samples; i++) {
            int x = rng.nextInt(w.SX), z = rng.nextInt(w.SZ);
            int top = AshFallSystem.surfaceY(w, x, z);
            if (top < 0) continue;
            if (w.mat[x][top][z] != Blocks.GRASS.index) continue;
            int g = 0;
            for (int dx = -1; dx <= 1; dx++)
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dz == 0) continue;
                    if (w.getBlock(x + dx, top, z + dz) == Blocks.GRASS.index) g++;
                }
            if (g >= 6 && rng.nextDouble() < 0.01) {
                w.recordMemory("兽群足迹: x=" + x + ",z=" + z + " 草原繁盛");
                w.log("herd", "graze", "x=" + x + ",z=" + z, "plains");
                w.prosperity++;   // 繁荣反馈：兽群→世界更兴旺
            }
        }
    }
}
