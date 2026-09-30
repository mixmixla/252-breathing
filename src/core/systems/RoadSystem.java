package core.systems;

import core.rng.SeededRNG;
import core.world.Blocks;
import core.world.World;

/**
 * 道路（67·road，文明/社会）：把草原上的“兽群足迹”（GRASS 正上方的 LEAF，来自 Herd/Prey）以低概率
 * 固化为 DIRT 路（将该 LEAF 下方 GRASS 改 DIRT，并清掉 LEAF 标记），连成路网；记 "road"."pave"。
 * 只动“GRASS 上方 LEAF”的足迹，不误伤蜂巢/巢 LEAF（其下方非 GRASS）。
 * 与 TrampleSystem（玩家踩踏 GRASS→DIRT）区分：本系统固化的是兽群足迹而非玩家脚印；
 * 与 PredatorSystem（仅把足迹抹回 AIR）区分：本系统把足迹所在格升级为 DIRT 路；
 * 与 LawSystem（清除过密足迹）区分：本系统是“保留并固化”而非“清除”。
 * 确定性：随机严格走 simStream 入参 rng。
 */
public final class RoadSystem implements System {
    @Override public String name() { return "road"; }

    @Override
    public void update(World w, SeededRNG rng) {
        int samples = 24;
        for (int i = 0; i < samples; i++) {
            int x = rng.nextInt(w.SX), z = rng.nextInt(w.SZ);
            int top = AshFallSystem.surfaceY(w, x, z);
            if (top < 0 || top + 1 >= w.SY) continue;
            // 仅“GRASS 上方 LEAF”的兽群足迹（蜂巢/巢 LEAF 下方非 GRASS，自然排除）
            if (w.mat[x][top][z] != Blocks.GRASS.index) continue;
            if (w.mat[x][top + 1][z] != Blocks.LEAF.index) continue;
            if (rng.nextDouble() < 0.20) {
                w.setBlock(x, top, z, Blocks.DIRT.index);       // 足迹固化为路
                w.setBlock(x, top + 1, z, Blocks.AIR.index);     // 清掉足迹标记
                w.log("road", "pave", "x=" + x + ",z=" + z, "footprint");
            }
        }
    }
}
