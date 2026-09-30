package core.systems;

import core.rng.SeededRNG;
import core.world.Blocks;
import core.world.World;

/**
 * 花粉传播（生态）：地表 FLOWER 以低概率向相邻 GRASS 列散播新花，草原逐渐繁花。
 * 确定性（固定采样 + rng，迭代序稳定）。
 */
public final class PollinateSystem implements System {
    @Override public String name() { return "pollinate"; }

    @Override
    public void update(World w, SeededRNG rng) {
        int samples = 20;
        for (int i = 0; i < samples; i++) {
            int x = rng.nextInt(w.SX), z = rng.nextInt(w.SZ);
            int top = surfaceY(w, x, z);
            if (top < 0 || top + 1 >= w.SY) continue;
            // 花位于草面之上一格（FlowerSystem 在 top+1 落花），故在 top+1 读源花
            if (w.mat[x][top + 1][z] != Blocks.FLOWER.index) continue;
            int dx = rng.nextInt(3) - 1, dz = rng.nextInt(3) - 1;
            if (dx == 0 && dz == 0) continue;
            int nx = x + dx, nz = z + dz;
            if (!w.inBounds(nx, 0, nz)) continue;
            int nt = surfaceY(w, nx, nz);
            // 邻格草面之上为空才落新花（与 FlowerSystem 同 convention，保证下游系统可见）
            if (nt >= 0 && nt + 1 < w.SY && w.mat[nx][nt][nz] == Blocks.GRASS.index
                    && w.mat[nx][nt + 1][nz] == Blocks.AIR.index && rng.nextDouble() < 0.2) {
                w.setBlock(nx, nt + 1, nz, Blocks.FLOWER.index);
            }
        }
    }

    private static int surfaceY(World w, int x, int z) {
        return w.surfaceY[x][z];
    }
}
