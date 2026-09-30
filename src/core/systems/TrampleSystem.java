package core.systems;

import core.rng.SeededRNG;
import core.world.Blocks;
import core.world.World;

/**
 * 踩踏（玩家↔世界反馈）：玩家站立的地表 GRASS 以低概率被踩成 DIRT，留下"小路"痕迹。
 * 用 simStream 抽样邻居格判概率，确定（玩家位置由脚本/输入逐 tick 注入，同种子同结果）。
 */
public final class TrampleSystem implements System {
    private int trampled = 0;   // 涌现计数（仅统计）

    @Override public String name() { return "trample"; }

    @Override
    public void update(World w, SeededRNG rng) {
        if (w.player == null) return;
        int x = clamp((int) Math.floor(w.player.x), w.SX);
        int z = clamp((int) Math.floor(w.player.z), w.SZ);
        int top = surfaceY(w, x, z);
        if (top < 0) return;
        for (int dx = -1; dx <= 1; dx++)
            for (int dz = -1; dz <= 1; dz++) {
                int lx = x + dx, lz = z + dz;
                if (!w.inBounds(lx, top, lz)) continue;
                if (w.mat[lx][top][lz] == Blocks.GRASS.index && rng.nextDouble() < 0.15) {
                    w.setBlock(lx, top, lz, Blocks.DIRT.index);
                    trampled++;
                }
            }
    }

    private static int surfaceY(World w, int x, int z) {
        return w.surfaceY[x][z];
    }

    private static int clamp(int v, int max) {
        return v < 0 ? 0 : (v >= max ? max - 1 : v);
    }
}
