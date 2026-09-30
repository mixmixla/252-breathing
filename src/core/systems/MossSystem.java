package core.systems;

import core.rng.SeededRNG;
import core.world.Blocks;
import core.world.World;

/**
 * 苔藓系统（地质/生物）：暴露空气中的表层 STONE 以低概率覆一层 MOSS（地衣）。
 * 确定性：逐列扫描、固定迭代序、随机走 simStream 子流。
 */
public final class MossSystem implements System {
    @Override public String name() { return "moss"; }

    @Override
    public void update(World w, SeededRNG rng) {
        // 批⑩：直读 mat + 扁平化 surfaceY[x] / mat[x] 行（原每列 2 次 getBlock）。
        // 界内 getBlock ≡ mat 读；`top+1` 越界时原 getBlock 返 BEDROCK(≠AIR) ⇒ 加 `top+1 < SY &&`
        // 保留同义且不多抽 rng。条件序 / rng 抽取点不变 ⇒ 行为逐字节等价。
        // 批⑤：苔藓全局上限（派生式）。判据置于 rng 抽取之后 ⇒ 不位移本系统随机流。
        final int STONE = Blocks.STONE.index, AIR = Blocks.AIR.index, MOSS = Blocks.MOSS.index;
        final int SY = w.SY;
        final int[][] sy = w.surfaceY;
        final int[][][] mat = w.mat;
        int remain = w.spreadHeadroom(MOSS);
        for (int x = 0; x < w.SX; x++) {
            final int[] syx = sy[x];
            final int[][] mx = mat[x];
            for (int z = 0; z < w.SZ; z++) {
                int top = syx[z];
                if (top < 0) continue;
                if (mx[top][z] == STONE
                        && top + 1 < SY && mx[top + 1][z] == AIR
                        && rng.nextDouble() < 0.03 && remain > 0) {
                    w.setBlock(x, top + 1, z, MOSS);
                    remain--;
                }
            }
        }
    }
}
