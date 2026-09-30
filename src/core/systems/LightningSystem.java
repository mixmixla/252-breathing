package core.systems;

import core.rng.SeededRNG;
import core.world.Blocks;
import core.world.World;

/**
 * 雷暴（气象↔火灾耦合）：低频掷骰模拟落雷，命中地表 WOOD/LEAF 即点燃 FIRE。
 * 与 WeatherSystem（降雨浇灭）形成正负反馈，让“天气影响世界”可见。确定性。
 */
public final class LightningSystem implements System {
    @Override public String name() { return "lightning"; }

    @Override
    public void update(World w, SeededRNG rng) {
        if (rng.nextDouble() >= 0.02) return;          // 约每 50 tick 一次机会
        int x = rng.nextInt(w.SX), z = rng.nextInt(w.SZ);
        int top = surfaceY(w, x, z);
        if (top < 0) return;
        int b = w.mat[x][top][z];
        if (b == Blocks.WOOD.index || b == Blocks.LEAF.index) {
            w.setBlock(x, top, z, Blocks.FIRE.index);
            w.markDirty(x, top, z);
            w.log("lightning", "strike", "x=" + x + ",z=" + z, "ignite");
            w.recordMemory("雷暴：落雷点燃树木");
        }
    }

    private static int surfaceY(World w, int x, int z) {
        return w.surfaceY[x][z];
    }
}
