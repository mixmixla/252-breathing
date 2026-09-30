package core.systems;

import core.rng.SeededRNG;
import core.world.Blocks;
import core.world.World;

/**
 * 冻结（水文/气象）：WATER 邻 ICE 时低概率冻成 ICE，寒冷沿水面扩散。
 * 与 SnowMeltSystem 相反，二者共存形成冷热涨落。确定性。
 */
public final class FrozenSystem implements System {
    @Override public String name() { return "frozen"; }

    @Override
    public void update(World w, SeededRNG rng) {
        int samples = 24;
        // 批⑤：结冰全局上限（派生式）。判据置于 rng 抽取之后 ⇒ 不位移本系统随机流。
        int remain = w.spreadHeadroom(Blocks.ICE.index);
        for (int i = 0; i < samples; i++) {
            int x = rng.nextInt(w.SX), y = rng.nextInt(w.SY), z = rng.nextInt(w.SZ);
            if (w.mat[x][y][z] != Blocks.WATER.index) continue;
            if (touchIce(w, x, y, z) && rng.nextDouble() < 0.3 && remain > 0) {
                w.setBlock(x, y, z, Blocks.ICE.index);
                w.markDirty(x, y, z);
                remain--;
            }
        }
    }

    private static boolean touchIce(World w, int x, int y, int z) {
        return w.getBlock(x + 1, y, z) == Blocks.ICE.index
            || w.getBlock(x - 1, y, z) == Blocks.ICE.index
            || w.getBlock(x, y + 1, z) == Blocks.ICE.index
            || w.getBlock(x, y - 1, z) == Blocks.ICE.index
            || w.getBlock(x, y, z + 1) == Blocks.ICE.index
            || w.getBlock(x, y, z - 1) == Blocks.ICE.index;
    }
}
