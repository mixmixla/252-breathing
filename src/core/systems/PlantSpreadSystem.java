package core.systems;

import core.rng.SeededRNG;
import core.world.Blocks;
import core.world.World;

/**
 * 草蔓延（生态涌现）：地表 GRASS 以低概率向相邻 DIRT 蔓延，世界随时间变绿。
 * 用 simStream 抽样地表列 + 随机选一个水平邻居，仅触发一次随机转换。
 */
public final class PlantSpreadSystem implements System {
    private static final int[][] D = {{1, 0}, {0, 1}, {-1, 0}, {0, -1}};

    @Override public String name() { return "plant"; }

    @Override
    public void update(World w, SeededRNG rng) {
        int samples = 30;
        for (int i = 0; i < samples; i++) {
            int x = rng.nextInt(w.SX), z = rng.nextInt(w.SZ);
            int top = surfaceY(w, x, z);
            if (top < 0) continue;
            if (w.mat[x][top][z] != Blocks.GRASS.index) continue;
            int k = rng.nextInt(4);
            int nx = x + D[k][0], nz = z + D[k][1];
            if (!w.inBounds(nx, top, nz)) continue;
            if (w.mat[nx][top][nz] == Blocks.DIRT.index && rng.nextDouble() < 0.06) {
                w.setBlock(nx, top, nz, Blocks.GRASS.index);
            }
        }
    }

    private static int surfaceY(World w, int x, int z) {
        return w.surfaceY[x][z];
    }
}
