package core.systems;

import core.rng.SeededRNG;
import core.world.Blocks;
import core.world.World;

/**
 * 黏土系统（地质/水文）：临水的 SAND 以低概率风化为 CLAY（河边泥岸）。
 * 确定性：逐列扫描、固定迭代序、随机走 simStream 子流。
 */
public final class ClaySystem implements System {
    @Override public String name() { return "clay"; }

    @Override
    public void update(World w, SeededRNG rng) {
        for (int x = 0; x < w.SX; x++)
            for (int z = 0; z < w.SZ; z++) {
                int top = SnowCapSystem.surfaceY(w, x, z);
                if (top < 0) continue;
                if (w.getBlock(x, top, z) != Blocks.SAND.index) continue;
                if (touchesWater(w, x, top, z) && rng.nextDouble() < 0.02) {
                    w.setBlock(x, top, z, Blocks.CLAY.index);
                }
            }
    }

    static boolean touchesWater(World w, int x, int y, int z) {
        return w.getBlock(x - 1, y, z) == Blocks.WATER.index
            || w.getBlock(x + 1, y, z) == Blocks.WATER.index
            || w.getBlock(x, y, z - 1) == Blocks.WATER.index
            || w.getBlock(x, y, z + 1) == Blocks.WATER.index;
    }
}
