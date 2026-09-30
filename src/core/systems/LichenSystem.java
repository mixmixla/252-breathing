package core.systems;

import core.rng.SeededRNG;
import core.world.Blocks;
import core.world.World;

/**
 * 岩石覆地衣（地质/生物）：暴露空气中的 STONE 表面缓慢被地衣侵蚀，复用 MOSS 占位。
 * 与 MossSystem（石顶长苔）互补——本系统作用于“侧面暴露的石壁”。随机走 simStream。
 */
public final class LichenSystem implements System {
    @Override public String name() { return "lichen"; }

    @Override
    public void update(World w, SeededRNG rng) {
        int samples = 200;
        // 批⑤：苔藓全局上限（派生式）。判据置于 rng 抽取之后 ⇒ 不位移本系统随机流。
        int remain = w.spreadHeadroom(Blocks.MOSS.index);
        for (int i = 0; i < samples; i++) {
            int x = rng.nextInt(w.SX), y = rng.nextInt(w.SY), z = rng.nextInt(w.SZ);
            if (w.mat[x][y][z] != Blocks.STONE.index) continue;
            // 任一侧邻空 → 暴露面
            boolean exposed = w.getBlock(x - 1, y, z) == Blocks.AIR.index
                           || w.getBlock(x + 1, y, z) == Blocks.AIR.index
                           || w.getBlock(x, y - 1, z) == Blocks.AIR.index
                           || w.getBlock(x, y + 1, z) == Blocks.AIR.index
                           || w.getBlock(x, y, z - 1) == Blocks.AIR.index
                           || w.getBlock(x, y, z + 1) == Blocks.AIR.index;
            if (exposed && rng.nextDouble() < 0.02 && remain > 0) {
                w.setBlock(x, y, z, Blocks.MOSS.index);     // 地衣壳
                remain--;
            }
        }
    }
}
