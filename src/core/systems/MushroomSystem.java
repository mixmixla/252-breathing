package core.systems;

import core.rng.SeededRNG;
import core.world.Blocks;
import core.world.World;

/**
 * 洞穴真菌（生态涌现）：在封闭暗穴（AIR 被 STONE 从 5/6 面夹住）以低概率"长"出真菌。
 * 不新增方块（尊重"不碰 Blocks.java"硬约束），复用 LEAF 作为真菌/苔藓的视觉占位。
 * 用 simStream 抽样 + 低概率，确定；洞穴随 tick 缓慢"长毛"。
 */
public final class MushroomSystem implements System {
    private static final int[][] D = {
            {1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}
    };
    private int grown = 0;   // 涌现计数（仅统计）

    @Override public String name() { return "fungus"; }

    @Override
    public void update(World w, SeededRNG rng) {
        int samples = 16;
        for (int i = 0; i < samples; i++) {
            int x = rng.nextInt(w.SX), y = rng.nextInt(w.SY), z = rng.nextInt(w.SZ);
            if (w.mat[x][y][z] != Blocks.AIR.index) continue;
            int stoneN = 0;
            for (int[] o : D)
                if (w.getBlock(x + o[0], y + o[1], z + o[2]) == Blocks.STONE.index) stoneN++;
            if (stoneN >= 5 && rng.nextDouble() < 0.05) {
                w.setBlock(x, y, z, Blocks.MUSHROOM.index);   // 真菌（第十二批：MUSHROOM 方块已存在，不再用 LEAF 占位）
                grown++;
            }
        }
    }
}
