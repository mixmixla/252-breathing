package core.systems;

import core.rng.SeededRNG;
import core.world.Blocks;
import core.world.World;

/**
 * 聚落扩张系统（社会/世界回响）：繁荣度达阈（>=4）时以低概率在草原上生成一处 SHELTER 暖屋 + 中心 LAMP，
 * 并写入村庄记忆——把“繁荣→实体聚落”的回响链条在 Java 版跑通。
 * 确定性：用 simStream 子流选点、固定建造序、随机走 rng。
 */
public final class RuinsSystem implements System {
    @Override public String name() { return "ruins"; }

    @Override
    public void update(World w, SeededRNG rng) {
        if (w.prosperity < 4) return;
        if (rng.nextDouble() >= 0.01) return;
        int cx = rng.nextInt(w.SX), cz = rng.nextInt(w.SZ);
        int top = SnowCapSystem.surfaceY(w, cx, cz);
        if (top < 0 || w.getBlock(cx, top, cz) != Blocks.GRASS.index) return;
        int base = top + 1;
        if (base >= w.SY) return;
        for (int dx = -1; dx <= 1; dx++)
            for (int dz = -1; dz <= 1; dz++) {
                int lx = cx + dx, lz = cz + dz;
                if (w.inBounds(lx, base, lz) && w.getBlock(lx, base, lz) == Blocks.AIR.index)
                    w.setBlock(lx, base, lz, Blocks.SHELTER.index);
            }
        w.setBlock(cx, base, cz, Blocks.LAMP.index);   // 中心暖炉
        w.recordMemory("聚落扩张：新暖屋落成于 (" + cx + "," + cz + ")");
    }
}
