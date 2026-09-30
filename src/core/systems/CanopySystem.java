package core.systems;

import core.rng.SeededRNG;
import core.world.Blocks;
import core.world.World;

/**
 * 树冠郁闭（生态/林业）：WOOD 顶部有空间时低概率长 LEAF 树冠，森林逐渐合拢。
 * 确定性（固定采样 + rng）。
 */
public final class CanopySystem implements System {
    @Override public String name() { return "canopy"; }

    @Override
    public void update(World w, SeededRNG rng) {
        int samples = 20;
        // 批⑤：树冠全局上限（派生式）。判据置于 rng 抽取之后 ⇒ 不位移本系统随机流。
        int remain = w.spreadHeadroom(Blocks.LEAF.index);
        for (int i = 0; i < samples; i++) {
            int x = rng.nextInt(w.SX), y = rng.nextInt(w.SY), z = rng.nextInt(w.SZ);
            if (w.mat[x][y][z] != Blocks.WOOD.index) continue;
            int above = y + 1;
            if (above >= w.SY) continue;
            if (w.mat[x][above][z] == Blocks.AIR.index && rng.nextDouble() < 0.15 && remain > 0) {
                w.setBlock(x, above, z, Blocks.LEAF.index);
                w.markDirty(x, above, z);
                remain--;
            }
        }
    }
}
