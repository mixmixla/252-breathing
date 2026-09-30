package core.systems;

import core.rng.SeededRNG;
import core.world.Blocks;
import core.world.World;

/**
 * 仙人掌系统（植物/沙漠）：沙地表面、四周无遮挡时以低概率向上长出 CACTUS 柱。
 * 确定性：逐列扫描、固定迭代序、随机走 simStream 子流。
 */
public final class CactusSystem implements System {
    @Override public String name() { return "cactus"; }

    @Override
    public void update(World w, SeededRNG rng) {
        // 批⑨：直读 mat（界内 getBlock ≡ mat 读）。四邻越界时原 getBlock 返 BEDROCK(≠AIR) ⇒ 必 continue，
        // 故先做一次边界短路（x/z 贴界即跳过）——与原逻辑同义，且不改变 rng 抽取点（rng 在四邻判定之后）。
        final int SX = w.SX, SZ = w.SZ, SY = w.SY;
        final int SAND = Blocks.SAND.index, AIR = Blocks.AIR.index, CACTUS = Blocks.CACTUS.index;
        final int[][] sy = w.surfaceY;
        final int[][][] mat = w.mat;
        for (int x = 0; x < SX; x++) {
            final int[] syx = sy[x];
            final int[][] mx = mat[x];
            for (int z = 0; z < SZ; z++) {
                int top = syx[z];
                if (top < 0) continue;
                if (mx[top][z] != SAND) continue;
                // 四周必须是空气（不贴墙长）；贴窗口边 = 有越界邻（BEDROCK）⇒ 原逻辑必 continue。
                if (x == 0 || z == 0 || x == SX - 1 || z == SZ - 1) continue;
                int[] row = mx[top];
                if (row[z - 1] != AIR || row[z + 1] != AIR
                        || mat[x - 1][top][z] != AIR || mat[x + 1][top][z] != AIR) continue;
                if (rng.nextDouble() < 0.01) {
                    int h = 1 + rng.nextInt(3);   // 1-3 高
                    for (int t = 1; t <= h; t++) {
                        int yy = top + t;
                        if (yy < SY && mx[yy][z] == AIR)
                            w.setBlock(x, yy, z, CACTUS);
                    }
                }
            }
        }
    }
}
