package core.systems;

import core.rng.SeededRNG;
import core.world.Blocks;
import core.world.World;

/**
 * 融雪/融冰（水文/气象）：ICE 邻格有“非冰非雪”的暖来源时，低概率融为 WATER。
 * 与反应表 freeze（水遇冰冻结，assets/content/reactions/frozen.json）形成冷热平衡。确定性（固定采样 + rng）。
 */
public final class SnowMeltSystem implements System {
    @Override public String name() { return "snowmelt"; }

    @Override
    public void update(World w, SeededRNG rng) {
        int samples = 24;
        for (int i = 0; i < samples; i++) {
            int x = rng.nextInt(w.SX), y = rng.nextInt(w.SY), z = rng.nextInt(w.SZ);
            if (w.mat[x][y][z] != Blocks.ICE.index) continue;
            if (warmNeighbor(w, x, y, z) && rng.nextDouble() < 0.25) {
                w.setBlock(x, y, z, Blocks.WATER.index);
                w.markDirty(x, y, z);
            }
        }
    }

    private static boolean warmNeighbor(World w, int x, int y, int z) {
        int[] d = { w.getBlock(x + 1, y, z), w.getBlock(x - 1, y, z),
                    w.getBlock(x, y + 1, z), w.getBlock(x, y - 1, z),
                    w.getBlock(x, y, z + 1), w.getBlock(x, y, z - 1) };
        for (int b : d) {
            if (b != Blocks.ICE.index && b != Blocks.SNOW.index) return true;
        }
        return false;
    }
}
