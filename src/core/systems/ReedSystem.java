package core.systems;

import core.rng.SeededRNG;
import core.world.Blocks;
import core.world.World;

/**
 * 水边芦苇（生态/水生）：WATER 正上方为 AIR 时低概率向上长 LEAF 芦苇，
 * 让水岸有植物感。确定性（固定采样 + rng）。
 */
public final class ReedSystem implements System {
    @Override public String name() { return "reed"; }

    @Override
    public void update(World w, SeededRNG rng) {
        int samples = 20;
        for (int i = 0; i < samples; i++) {
            int x = rng.nextInt(w.SX), z = rng.nextInt(w.SZ);
            int wy = -1;
            for (int y = w.SY - 1; y >= 0; y--) {
                if (w.mat[x][y][z] == Blocks.WATER.index) { wy = y; break; }
            }
            if (wy < 0) continue;
            if (w.getBlock(x, wy + 1, z) != Blocks.AIR.index) continue;
            if (rng.nextDouble() < 0.12) {
                w.setBlock(x, wy + 1, z, Blocks.REED.index);    // 第十二批：改用 REED（原为 LEAF 占位）
                w.markDirty(x, wy + 1, z);
            }
        }
    }
}
