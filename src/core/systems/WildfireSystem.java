package core.systems;

import core.rng.SeededRNG;
import core.world.Blocks;
import core.world.World;

/**
 * 野火蔓延（生态/灾害）：干燥草原（GRASS 连片）若四邻已有 FIRE，则按低概率被引燃成 FIRE，
 * 与既有 FireSpreadSystem 形成“火↔草”耦合——演示干旱季草原火链。随机严格走 simStream。
 */
public final class WildfireSystem implements System {
    @Override public String name() { return "wildfire"; }

    @Override
    public void update(World w, SeededRNG rng) {
        int samples = 24;
        for (int i = 0; i < samples; i++) {
            int x = rng.nextInt(w.SX), z = rng.nextInt(w.SZ);
            int top = AshFallSystem.surfaceY(w, x, z);
            if (top < 0) continue;
            if (w.mat[x][top][z] != Blocks.GRASS.index) continue;   // 仅草原被引燃
            // 检查四邻是否已有火
            boolean near = w.getBlock(x - 1, top, z) == Blocks.FIRE.index
                        || w.getBlock(x + 1, top, z) == Blocks.FIRE.index
                        || w.getBlock(x, top, z - 1) == Blocks.FIRE.index
                        || w.getBlock(x, top, z + 1) == Blocks.FIRE.index;
            if (near && rng.nextDouble() < 0.15) {
                w.setBlock(x, top, z, Blocks.FIRE.index);
                w.log("wildfire", "ignite", "x=" + x + ",z=" + z, "grass");
            }
        }
    }
}
