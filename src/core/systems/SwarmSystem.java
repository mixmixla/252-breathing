package core.systems;

import core.rng.SeededRNG;
import core.world.Blocks;
import core.world.World;

/**
 * 传粉虫群（63 · swarm，生物/种群）：FLOWER 极密处（6 邻 ≥5 个 FLOWER）低概率(rng)出现传粉昆虫聚集，
 * 记为 "swarm"."appear" event（纯事件痕迹，不改方块），表现虫群被繁花吸引。
 * 与 BeehiveSystem 区分：BeehiveSystem 在 FLOWER 密集(≥3)处低概率在花上放 LEAF 蜂巢（改方块）；
 * 本系统阈值更高(≥5)且只记 event、绝不写方块，专门表现“虫群聚集”这一独立涌现，不与蜂巢占位冲突。
 * 纯确定性（随机走 simStream 入参 rng，结果仅记 event 不影响指纹）。
 */
public final class SwarmSystem implements System {
    private static final int[][] N6 = {
        {1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}
    };

    @Override public String name() { return "swarm"; }

    @Override
    public void update(World w, SeededRNG rng) {
        int samples = 16;
        for (int i = 0; i < samples; i++) {
            int x = rng.nextInt(w.SX), y = rng.nextInt(w.SY), z = rng.nextInt(w.SZ);
            if (w.mat[x][y][z] != Blocks.FLOWER.index) continue;
            int n = 0;
            for (int[] o : N6)
                if (w.getBlock(x + o[0], y + o[1], z + o[2]) == Blocks.FLOWER.index) n++;
            if (n >= 5 && rng.nextDouble() < 0.05) {  // 花极密 → 传粉虫群聚集
                w.log("swarm", "appear", "x=" + x + ",z=" + z, "flowers=" + n);
            }
        }
    }
}
