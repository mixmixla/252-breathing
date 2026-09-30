package core.systems;

import core.rng.SeededRNG;
import core.world.Blocks;
import core.world.World;

/**
 * 城墙（68·wall，文明/社会）：当 w.prosperity 达到 >=7，在村庄中心一定半径边缘确定性立 STONE 墙环
 * （沿半径 r 的方环置 STONE），记 "wall"."raised" event + 记忆。一次性建造（繁荣只升，幂等）。
 * 与 MonumentSystem（中心高塔）区分：本系统是“外围防御环”；
 * 与 RuinsSystem（内部聚落 SHELTER 簇）区分：本系统在村庄外围。
 * 确定性：随机严格走 simStream 入参 rng；环位置由中心坐标 + 半径确定性推导。
 */
public final class WallSystem implements System {
    private boolean built = false;   // 一次性建造水印

    @Override public String name() { return "wall"; }

    @Override
    public void update(World w, SeededRNG rng) {
        if (built || w.prosperity < 7) return;
        int cx = w.SX / 2, cz = w.SZ / 2;
        int r = 4;   // 方环半径
        for (int dx = -r; dx <= r; dx++)
            for (int dz = -r; dz <= r; dz++) {
                if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue; // 仅环上格
                int x = cx + dx, z = cz + dz;
                if (!w.inBounds(x, 1, z)) continue;
                int top = AshFallSystem.surfaceY(w, x, z);
                if (top < 0 || top + 1 >= w.SY) continue;
                if (w.mat[x][top + 1][z] == Blocks.AIR.index)
                    w.setBlock(x, top + 1, z, Blocks.STONE.index);
            }
        built = true;
        w.log("wall", "raised", "cx=" + cx + ",cz=" + cz + ",r=" + r, "prosperity=" + w.prosperity);
        w.recordMemory("城墙环立：村周筑起石墙，御外守内，繁荣=" + w.prosperity);
    }
}
