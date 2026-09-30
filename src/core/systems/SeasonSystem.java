package core.systems;

import core.rng.SeededRNG;
import core.world.Blocks;
import core.world.World;

/**
 * 四季（47 · 气候与大气）：按 tick 相位分 4 季（暖/和/寒/复苏）。
 * 冬季地表 GRASS 上方空腔低概率积 SNOW；春/暖/复苏地表 GRASS 上方空腔低概率绽放 FLOWER（花季）。
 * 纯状态 + 轻量网格演化，确定性（随机走 simStream）。不新增方块。
 */
public final class SeasonSystem implements System {
    private int t = 0;
    private static final int SEASON_LEN = 64; // 4 季 × 64 = 256 tick 一轮

    @Override public String name() { return "season"; }

    @Override
    public void update(World w, SeededRNG rng) {
        t++;
        int season = (t / SEASON_LEN) % 4; // 0 暖 1 和 2 寒 3 复苏
        int samples = 20;
        for (int i = 0; i < samples; i++) {
            int x = rng.nextInt(w.SX), z = rng.nextInt(w.SZ);
            int top = surfaceY(w, x, z);
            if (top < 0) continue;
            if (w.mat[x][top][z] != Blocks.GRASS.index) continue;
            if (top + 1 >= w.SY) continue;
            int above = w.mat[x][top + 1][z];
            if (season == 2) { // 冬季：积 SNOW
                if (above == Blocks.AIR.index && rng.nextDouble() < 0.15)
                    w.setBlock(x, top + 1, z, Blocks.SNOW.index);
            } else if ((season == 0 || season == 3) && above == Blocks.AIR.index) {
                // 春/暖/复苏：花季绽放（概率上调）
                double p = (season == 3) ? 0.02 : 0.01;
                if (rng.nextDouble() < p) w.setBlock(x, top + 1, z, Blocks.FLOWER.index);
            }
        }
    }

    private static int surfaceY(World w, int x, int z) {
        return w.surfaceY[x][z];
    }
}
