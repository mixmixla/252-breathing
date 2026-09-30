package core.systems;

import core.rng.SeededRNG;
import core.world.Blocks;
import core.world.World;

/**
 * 农田（69·farm，文明/社会）：被踩踏成 DIRT 的田（来自 TrampleSystem）若邻 WATER，以低概率(rng)
 * 在上方空地长出 LEAF 作物，表现耕种收获；记 "farm"."harvest"。
 * 与 IrrigationSystem（只记 watered 前置态、不改地形）区分：本系统“长作物”（改地形）；
 * 与 PlantSpreadSystem（GRASS 向 DIRT 蔓延）区分：本系统只在邻水 DIRT 上长 LEAF 作物。
 * 确定性：随机严格走 simStream 入参 rng。
 */
public final class FarmSystem implements System {
    @Override public String name() { return "farm"; }

    @Override
    public void update(World w, SeededRNG rng) {
        int samples = 24;
        for (int i = 0; i < samples; i++) {
            int x = rng.nextInt(w.SX), z = rng.nextInt(w.SZ);
            int top = AshFallSystem.surfaceY(w, x, z);
            if (top < 0 || top + 1 >= w.SY) continue;
            if (w.mat[x][top][z] != Blocks.DIRT.index) continue;     // 踩踏成田的 DIRT
            if (w.mat[x][top + 1][z] != Blocks.AIR.index) continue;  // 空地长作物
            if (!waterNear(w, x, top, z)) continue;
            if (rng.nextDouble() < 0.10) {
                w.setBlock(x, top + 1, z, Blocks.LEAF.index);        // 长出作物
                w.log("farm", "harvest", "x=" + x + ",z=" + z, "crop");
            }
        }
    }

    /** 同层四邻或下方一格是否为 WATER（临水田）。越界由 getBlock 返回 BEDROCK，安全。 */
    private static boolean waterNear(World w, int x, int y, int z) {
        return w.getBlock(x - 1, y, z) == Blocks.WATER.index
            || w.getBlock(x + 1, y, z) == Blocks.WATER.index
            || w.getBlock(x, y, z - 1) == Blocks.WATER.index
            || w.getBlock(x, y, z + 1) == Blocks.WATER.index
            || w.getBlock(x, y - 1, z) == Blocks.WATER.index;
    }
}
