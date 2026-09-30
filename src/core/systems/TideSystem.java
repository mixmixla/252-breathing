package core.systems;

import core.rng.SeededRNG;
import core.world.Blocks;
import core.world.World;

/**
 * 潮汐（水文）：固定相位在“水面表层”处 WATER<->AIR 翻转，模拟涨落潮。
 * 涨潮：水面之上若两层皆 AIR 则灌一层水；低潮：水面之上为 AIR 则退一层。
 * 确定性（相位由 tick 决定，无随机）。
 */
public final class TideSystem implements System {
    private int phase = 0;

    @Override public String name() { return "tide"; }

    @Override
    public void update(World w, SeededRNG rng) {
        phase++;
        if (phase < 96) return;
        phase = 0;
        boolean high = (w.tick % 192) < 96;
        int samples = 40;
        for (int i = 0; i < samples; i++) {
            int x = rng.nextInt(w.SX), z = rng.nextInt(w.SZ);
            int wy = -1;
            for (int y = w.SY - 1; y >= 0; y--) {
                if (w.mat[x][y][z] == Blocks.WATER.index) { wy = y; break; }
            }
            if (wy < 0) continue;
            if (high) {
                if (w.getBlock(x, wy + 1, z) == Blocks.AIR.index
                        && w.getBlock(x, wy + 2, z) == Blocks.AIR.index) {
                    w.setBlock(x, wy + 1, z, Blocks.WATER.index);
                    w.markDirty(x, wy + 1, z);
                }
            } else {
                if (w.getBlock(x, wy + 1, z) == Blocks.AIR.index) {
                    w.setBlock(x, wy, z, Blocks.AIR.index);
                    w.markDirty(x, wy, z);
                }
            }
        }
    }
}
