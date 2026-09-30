package core.systems;

import core.rng.SeededRNG;
import core.world.Blocks;
import core.world.World;

/**
 * 筑巢（生物活动痕迹）：WOOD 邻 LEAF（树上）时低概率在邻格 AIR 放 LEAF 巢，
 * 把“有鸟兽栖息”翻译成可见的小巢块。确定性（固定采样 + rng）。
 */
public final class NestSystem implements System {
    @Override public String name() { return "nest"; }

    @Override
    public void update(World w, SeededRNG rng) {
        int samples = 20;
        for (int i = 0; i < samples; i++) {
            int x = rng.nextInt(w.SX), y = rng.nextInt(w.SY), z = rng.nextInt(w.SZ);
            if (w.mat[x][y][z] != Blocks.WOOD.index) continue;
            boolean leafy = w.getBlock(x, y + 1, z) == Blocks.LEAF.index
                || w.getBlock(x, y - 1, z) == Blocks.LEAF.index
                || w.getBlock(x + 1, y, z) == Blocks.LEAF.index
                || w.getBlock(x - 1, y, z) == Blocks.LEAF.index
                || w.getBlock(x, y, z + 1) == Blocks.LEAF.index
                || w.getBlock(x, y, z - 1) == Blocks.LEAF.index;
            if (!leafy) continue;
            int dx = rng.nextInt(3) - 1, dz = rng.nextInt(3) - 1;
            if (dx == 0 && dz == 0) continue;
            int nx = x + dx, ny = y, nz = z + dz;
            if (w.inBounds(nx, ny, nz) && w.mat[nx][ny][nz] == Blocks.AIR.index
                    && rng.nextDouble() < 0.08) {
                w.setBlock(nx, ny, nz, Blocks.LEAF.index);
                w.markDirty(nx, ny, nz);
            }
        }
    }
}
