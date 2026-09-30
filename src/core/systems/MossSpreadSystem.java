package core.systems;

import core.rng.SeededRNG;
import core.world.Blocks;
import core.world.World;

/**
 * 苔藓蔓延（地质/生物）：MOSS 以低概率向相邻 STONE 蔓延，阴湿岩壁逐渐披苔。
 * 与 MossSystem（初始覆苔）互补——前者播种、本系统扩散。确定性。
 */
public final class MossSpreadSystem implements System {
    @Override public String name() { return "mossspread"; }

    @Override
    public void update(World w, SeededRNG rng) {
        int samples = 20;
        // 批⑤：苔藓全局上限（派生式）。判据置于 rng 抽取之后 ⇒ 不位移本系统随机流。
        int remain = w.spreadHeadroom(Blocks.MOSS.index);
        for (int i = 0; i < samples; i++) {
            int x = rng.nextInt(w.SX), y = rng.nextInt(w.SY), z = rng.nextInt(w.SZ);
            if (w.mat[x][y][z] != Blocks.MOSS.index) continue;
            int dx = rng.nextInt(3) - 1, dy = rng.nextInt(3) - 1, dz = rng.nextInt(3) - 1;
            if (dx == 0 && dy == 0 && dz == 0) continue;
            int nx = x + dx, ny = y + dy, nz = z + dz;
            if (!w.inBounds(nx, ny, nz)) continue;
            if (w.mat[nx][ny][nz] == Blocks.STONE.index && rng.nextDouble() < 0.1 && remain > 0) {
                w.setBlock(nx, ny, nz, Blocks.MOSS.index);
                w.markDirty(nx, ny, nz);
                remain--;
            }
        }
    }
}
