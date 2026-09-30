package core.systems;

import core.rng.SeededRNG;
import core.world.Blocks;
import core.world.World;

/**
 * 积雪系统（气象）：高海拔地表（草/泥/石）以低概率在上方空腔积一层 SNOW。
 * 确定性：逐列扫描、固定迭代序、随机走 simStream 子流。
 */
public final class SnowCapSystem implements System {
    @Override public String name() { return "snowcap"; }

    @Override
    public void update(World w, SeededRNG rng) {
        // 批⑨：直读 mat / 扁平化 surfaceY 行（原每列 2 次 getBlock）。界内 getBlock ≡ mat 读；
        // `top+1` 越界时原 getBlock 返 BEDROCK(≠AIR) ⇒ 加 `top+1 < SY &&` 保留同义且不多抽 rng。
        // 条件序 / rng 抽取点不变 ⇒ 行为逐字节等价。
        final int SY = w.SY;
        final int snowLine = (int) (SY * 0.68);   // 雪线（窗口顶部 1/3 开始积雪山峰）
        final int GRASS = Blocks.GRASS.index, DIRT = Blocks.DIRT.index, STONE = Blocks.STONE.index;
        final int AIR = Blocks.AIR.index, SNOW = Blocks.SNOW.index;
        final int[][] sy = w.surfaceY;
        final int[][][] mat = w.mat;
        for (int x = 0; x < w.SX; x++) {
            final int[] syx = sy[x];
            final int[][] mx = mat[x];
            for (int z = 0; z < w.SZ; z++) {
                int top = syx[z];
                if (top < 0) continue;
                int surface = mx[top][z];
                if ((surface == GRASS || surface == DIRT || surface == STONE)
                        && top >= snowLine
                        && top + 1 < SY && mx[top + 1][z] == AIR
                        && rng.nextDouble() < 0.02) {
                    w.setBlock(x, top + 1, z, SNOW);
                }
            }
        }
    }

    static int surfaceY(World w, int x, int z) {
        return w.surfaceY[x][z];
    }
}
