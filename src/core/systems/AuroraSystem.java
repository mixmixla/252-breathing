package core.systems;

import core.rng.SeededRNG;
import core.world.DayCycle;
import core.world.World;

/**
 * 极光（48 · 气候与大气）：低频（rng）在"夜间/高纬"记 "aurora"."appear" event。
 * 纯视觉叙事，不改网格、不破守恒、不进指纹。严格确定性。
 *
 * <p>D3 修正：本系统原先自带私有相位 {@code t%128>=64} 表示"夜"，与其它任何系统（以及渲染层天色）
 * 的定义都不一致 —— 属隐性不一致。现改为采用全项目唯一时基 {@link DayCycle#isNight(long)}（读 w.tick），
 * 于是"极光只在夜里出现"与"天色变黑"是同一件事。仍只用入参 rng，指纹不受影响。
 */
public final class AuroraSystem implements System {

    @Override public String name() { return "aurora"; }

    @Override
    public void update(World w, SeededRNG rng) {
        boolean night = DayCycle.isNight(w.tick);   // 「夜」= DayCycle 唯一定义（同渲染层天色）
        if (night && rng.nextDouble() < 0.02) {
            int z = rng.nextInt(w.SZ);
            boolean highLat = z < w.SZ * 0.2 || z > w.SZ * 0.8;
            if (highLat) w.log("aurora", "appear", "z=" + z, "shimmer");
        }
    }
}
