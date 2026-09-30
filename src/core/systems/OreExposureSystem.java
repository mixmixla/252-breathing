package core.systems;

import core.rng.SeededRNG;
import core.world.Blocks;
import core.world.World;

/**
 * 矿脉裸露（地质反应）：暴露在空气中的 STONE 以低概率"风化出"煤矿/铁矿（COAL_ORE/IRON_ORE），
 * 让崖壁随时间显出矿脉。用 simStream 抽样 + 低概率，确定；受 STONE 存量天然上界，不会失控。
 */
public final class OreExposureSystem implements System {
    private static final int[][] D = {
            {1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}
    };

    @Override public String name() { return "orexpose"; }

    @Override
    public void update(World w, SeededRNG rng) {
        int samples = 16;
        for (int i = 0; i < samples; i++) {
            int x = rng.nextInt(w.SX), y = rng.nextInt(w.SY), z = rng.nextInt(w.SZ);
            if (w.mat[x][y][z] != Blocks.STONE.index) continue;
            boolean exposed = false;
            for (int[] o : D)
                if (w.getBlock(x + o[0], y + o[1], z + o[2]) == Blocks.AIR.index) { exposed = true; break; }
            if (!exposed) continue;
            if (rng.nextDouble() < 0.01) {
                // 用 setBlock 演化（保持实时空间索引 / 脏区一致，避免直接写 mat 造成索引失同步）
                int ore = (rng.nextDouble() < 0.7)
                        ? Blocks.COAL_ORE.index : Blocks.IRON_ORE.index;
                w.setBlock(x, y, z, ore);
            }
        }
    }
}
