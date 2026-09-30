package core.systems;

import core.rng.SeededRNG;
import core.world.World;

/**
 * 风（52 · 气候与大气）：维护一个世界风矢量状态（确定性：按 tick 余弦 + 低频 rng 微调方向）。
 * 写入 World.windX/windZ，供 PollenSystem（及可选的 DustStormSystem）读取偏置。
 * 纯状态，不改网格、不进指纹，严格确定性。
 */
public final class WindSystem implements System {
    private int t = 0;
    private float gustX = 0f, gustZ = 0f; // 低频 rng 阵风偏置（持久于两次刷新之间）

    @Override public String name() { return "wind"; }

    @Override
    public void update(World w, SeededRNG rng) {
        t++;
        double ang = StrictMath.cos(t / 200.0) * Math.PI; // 缓慢摆动的基础风向
        float baseX = (float) StrictMath.cos(ang);
        float baseZ = (float) StrictMath.sin(ang);
        if (t % 50 == 0) { // 低频刷新阵风
            gustX = (float) rng.uniform(-0.5, 0.5);
            gustZ = (float) rng.uniform(-0.5, 0.5);
            w.log("wind", "shift", "x=" + fmt(baseX + gustX) + ",z=" + fmt(baseZ + gustZ), "gust");
        }
        w.windX = baseX + gustX;
        w.windZ = baseZ + gustZ;
    }

    private static String fmt(float v) {
        return String.format("%.3f", v);
    }
}
