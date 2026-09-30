package core.systems;

import core.rng.SeededRNG;
import core.world.Blocks;
import core.world.World;

/**
 * 沙尘暴（49 · 气候与大气）：沙漠(SAND)带沿上风方向（确定性相位/rng 派生方向）把 SAND 颗粒搬到邻格空腔。
 * 质量守恒：搬走一格 SAND 在邻格放一格 SAND（block 计数守恒；setBlock 不污染 mass 账本）。
 * 仅用入参 rng，不破守恒、不进指纹。
 */
public final class DustStormSystem implements System {
    private int t = 0;
    private int dirX = 1, dirZ = 0; // 当前风搬运方向

    @Override public String name() { return "dust"; }

    @Override
    public void update(World w, SeededRNG rng) {
        t++;
        if (t % 16 == 0) { // 每 16 tick 重定风（确定性 rng 派生方向）
            int d = rng.nextInt(4);
            dirX = (d == 0) ? 1 : (d == 1) ? -1 : 0;
            dirZ = (d == 2) ? 1 : (d == 3) ? -1 : 0;
        }
        if (dirX == 0 && dirZ == 0) return;
        int samples = 16;
        for (int i = 0; i < samples; i++) {
            int x = rng.nextInt(w.SX), z = rng.nextInt(w.SZ);
            int top = surfaceY(w, x, z);
            if (top < 0) continue;
            if (w.mat[x][top][z] != Blocks.SAND.index) continue; // 只搬沙漠带表层沙
            int nx = x + dirX, nz = z + dirZ;
            if (!w.inBounds(nx, top, nz)) continue;
            if (w.mat[nx][top][nz] == Blocks.AIR.index && rng.nextDouble() < 0.5) {
                w.setBlock(x, top, z, Blocks.AIR.index);
                w.setBlock(nx, top, nz, Blocks.SAND.index); // 搬运（守恒）
            }
        }
    }

    private static int surfaceY(World w, int x, int z) {
        return w.surfaceY[x][z];
    }
}
