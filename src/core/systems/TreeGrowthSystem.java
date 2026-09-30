package core.systems;

import core.rng.SeededRNG;
import core.world.Blocks;
import core.world.World;

/**
 * 树木生长（涌现系统样板）：地表 GRASS 顶上为 AIR 且有空间时，按极低概率长出一棵小树。
 * 演示“慢涌现”：世界随时间自己变得更绿，且确定（概率走 simStream）。
 */
public final class TreeGrowthSystem implements System {
    @Override public String name() { return "tree"; }

    @Override
    public void update(World w, SeededRNG rng) {
        // 每 tick 抽样少量候选列，避免全量扫描（确定性由 rng 序列保证）
        int samples = 24;
        for (int i = 0; i < samples; i++) {
            int x = rng.nextInt(w.SX), z = rng.nextInt(w.SZ);
            int top = surfaceY(w, x, z);
            if (top < 0) continue;
            if (w.mat[x][top][z] != Blocks.GRASS.index) continue;
            if (top + 5 >= w.SY) continue;
            if (rng.nextDouble() < 0.003) growTree(w, x, top, z);
        }
    }

    private static int surfaceY(World w, int x, int z) {
        return w.surfaceY[x][z];
    }

    private static void growTree(World w, int x, int base, int z) {
        for (int t = 1; t <= 4; t++) w.setBlock(x, base + t, z, Blocks.WOOD.index);
        int ty = base + 5;
        for (int dx = -1; dx <= 1; dx++)
            for (int dz = -1; dz <= 1; dz++)
                for (int dy = -1; dy <= 1; dy++) {
                    int lx = x + dx, ly = ty + dy, lz = z + dz;
                    if (w.inBounds(lx, ly, lz) && w.mat[lx][ly][lz] == Blocks.AIR.index
                            && (Math.abs(dx) + Math.abs(dz) + Math.abs(dy)) <= 2)
                        w.setBlock(lx, ly, lz, Blocks.LEAF.index);
                }
    }
}
