package core.systems;

import core.rng.SeededRNG;
import core.world.Blocks;
import core.world.World;

/**
 * 腐朽（生态涌现）：四周皆 AIR 的孤立 WOOD/LEAF（砍伐后被打飞、悬空的残块）按低概率转 AIR，
 * 让"砍伐痕迹"自然消散；完整树木因邻居非空不会被误清。用 simStream 抽样 + 低概率，确定。
 */
public final class DecaySystem implements System {
    private static final int[][] D = {
            {1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}
    };
    private int decayed = 0;   // 涌现计数（仅统计）

    @Override public String name() { return "decay"; }

    @Override
    public void update(World w, SeededRNG rng) {
        int samples = 20;
        for (int i = 0; i < samples; i++) {
            int x = rng.nextInt(w.SX), y = rng.nextInt(w.SY), z = rng.nextInt(w.SZ);
            int b = w.mat[x][y][z];
            if (b != Blocks.WOOD.index && b != Blocks.LEAF.index) continue;
            if (!isFloating(w, x, y, z)) continue;
            if (rng.nextDouble() < 0.25) {
                w.setBlock(x, y, z, Blocks.AIR.index);
                decayed++;
            }
        }
    }

    private static boolean isFloating(World w, int x, int y, int z) {
        for (int[] o : D) {
            // getBlock 越界返回 BEDROCK（非 AIR）→ 边界悬块不会被误判为浮空
            if (w.getBlock(x + o[0], y + o[1], z + o[2]) != Blocks.AIR.index) return false;
        }
        return true;
    }
}
