package core.systems;

import core.rng.SeededRNG;
import core.world.Blocks;
import core.world.World;

/**
 * 灌溉（70·irrigation，文明/社会）：WATER 邻格的 DIRT 以低概率(rng)被“灌溉”，记 "irrigation"."watered"
 * 事件作为 FarmSystem 的前置状态。保持 DIRT 不变（不风化、不破守恒），纯状态/事件，无地形改动。
 * 与 FarmSystem（邻水 DIRT 长 LEAF 作物）区分：本系统只记“已灌溉”前置态，不动方块。
 * 确定性：随机严格走 simStream 入参 rng；不新增 World 字段、不改 Blocks。
 */
public final class IrrigationSystem implements System {
    @Override public String name() { return "irrigation"; }

    @Override
    public void update(World w, SeededRNG rng) {
        int samples = 24;
        for (int i = 0; i < samples; i++) {
            int x = rng.nextInt(w.SX), z = rng.nextInt(w.SZ);
            int top = AshFallSystem.surfaceY(w, x, z);
            if (top < 0) continue;
            if (w.mat[x][top][z] != Blocks.DIRT.index) continue;
            if (!waterNear(w, x, top, z)) continue;
            if (rng.nextDouble() < 0.10) {
                w.log("irrigation", "watered", "x=" + x + ",z=" + z, "dirt");
            }
        }
    }

    private static boolean waterNear(World w, int x, int y, int z) {
        return w.getBlock(x - 1, y, z) == Blocks.WATER.index
            || w.getBlock(x + 1, y, z) == Blocks.WATER.index
            || w.getBlock(x, y, z - 1) == Blocks.WATER.index
            || w.getBlock(x, y, z + 1) == Blocks.WATER.index
            || w.getBlock(x, y - 1, z) == Blocks.WATER.index;
    }
}
