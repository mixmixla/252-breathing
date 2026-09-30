package core.systems;

import core.rng.SeededRNG;
import core.world.Blocks;
import core.world.World;

/**
 * 天气（环境系统）：实例字段推进昼夜/降雨相位，每若干 tick 用 simStream 决定下雨/晴；
 * 降雨时按低概率浇灭地表火焰——建立"天气↔火灾"的可见耦合，且严格确定。
 * 不新增方块（不碰 Blocks.java），仅状态字段 + 事件记忆。
 */
public final class WeatherSystem implements System {
    private int skyTime = 0;     // 自增相位
    private boolean raining = false;
    private int rainTicks = 0;
    private int totalRain = 0;   // 涌现计数（仅统计，不进指纹）
    private float humidity = 0.5f;   // 湿度 0..1（无雨渐干、有雨渐湿；派生状态，供灾害系统读取，不进指纹）

    @Override public String name() { return "weather"; }

    @Override
    public void update(World w, SeededRNG rng) {
        skyTime++;
        if (skyTime >= 64) {                 // 固定周期重新掷骰，确定性
            skyTime = 0;
            raining = rng.nextDouble() < 0.4;
            if (raining) {
                rainTicks = 32 + rng.nextInt(64);
                totalRain++;
                w.recordMemory("天气：降雨开始");
            } else {
                rainTicks = 0;
            }
        }
        if (raining && rainTicks > 0) {
            rainTicks--;
            int samples = 16;                // 抽样地表，避免全扫
            for (int i = 0; i < samples; i++) {
                int x = rng.nextInt(w.SX), z = rng.nextInt(w.SZ);
                int top = surfaceY(w, x, z);
                if (top < 0) continue;
                if (w.mat[x][top][z] == Blocks.FIRE.index && rng.nextDouble() < 0.5) {
                    w.setBlock(x, top, z, Blocks.AIR.index);
                    w.log("weather", "extinguish", "x=" + x + ",z=" + z, "rain");
                }
            }
            if (rainTicks == 0) { raining = false; w.recordMemory("天气：雨停"); }
        }
        // ---- 镜像到 World（供 CalamitySystem 读取）；湿度：有雨渐湿、无雨渐干（确定性，不进指纹）----
        humidity = raining ? Math.max(0f, humidity - 0.01f) : Math.min(1f, humidity + 0.002f);
        w.raining = raining;
        w.humidity = humidity;
    }

    private static int surfaceY(World w, int x, int z) {
        return w.surfaceY[x][z];
    }
}
