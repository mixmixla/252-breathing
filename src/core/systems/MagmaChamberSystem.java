package core.systems;

import core.rng.SeededRNG;
import core.world.Blocks;
import core.world.World;

/**
 * 岩浆房（78 · 地质/深地）：深层（近 BEDROCK）低频 rng 触发“热源升温”——在底腔（被岩体包裹的 AIR）
 * 置 FIRE 并记 "magma"."chamber" event，作为 LavaSystem 的“源”。
 * 与 LavaSystem（熔岩流动表现：从空腔涌出 + 深层爬流）区分：本系统是“深层热源触发”（更稀有、更靠底、只点源），
 * 不负责爬流；与 VolcanoSystem（地表主动喷发 + 冷却期）区分：本系统在地下、只产熔岩源、无冷却期、不经地表。
 * 零漂移：随机仅走入参 rng；用 setBlock 演化。
 */
public final class MagmaChamberSystem implements System {
    private static final int[] DX = {1, -1, 0, 0, 0, 0};
    private static final int[] DY = {0, 0, 1, -1, 0, 0};
    private static final int[] DZ = {0, 0, 0, 0, 1, -1};

    @Override public String name() { return "magmachamber"; }

    @Override
    public void update(World w, SeededRNG rng) {
        if (rng.nextDouble() >= 0.01) return;            // 极低频热源触发
        int x = rng.nextInt(w.SX);
        int y = 1 + rng.nextInt(3);                       // 近 BEDROCK 底腔
        int z = rng.nextInt(w.SZ);
        if (w.mat[x][y][z] != Blocks.AIR.index) return;
        boolean chamber = false;                          // 须被岩体（BEDROCK/STONE）包裹成腔
        for (int k = 0; k < 6; k++) {
            int b = w.getBlock(x + DX[k], y + DY[k], z + DZ[k]);
            if (b == Blocks.BEDROCK.index || b == Blocks.STONE.index) { chamber = true; break; }
        }
        if (chamber) {
            w.setBlock(x, y, z, Blocks.FIRE.index);
            w.log("magma", "chamber", "at=" + x + "," + y + "," + z, "erupt");
        }
    }
}
