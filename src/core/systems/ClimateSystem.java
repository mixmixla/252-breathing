package core.systems;

import core.rng.SeededRNG;
import core.world.Blocks;
import core.world.World;

/**
 * 气候纪元（46 · 气候与大气）：慢速余弦周期驱动长期温度偏置（实例状态，对应 Python world.temp 的全局偏置）；
 * 低频确定性 rng 触发"火山"——压降温并落灰（灰=在 STONE/SAND 顶上方空腔概率置 SNOW，不新增质量）；
 * 太阳辐照缓慢波动（rng.uniform）作为状态字段；记 "climate"."volcano"/"solar" event。
 * 严格确定性：仅用入参 rng，不碰 world.rng / Math.random / 墙钟。
 */
public final class ClimateSystem implements System {
    private int phase = 0;            // 余弦相位
    private double tempBias = 0.0;    // 长期温度偏置（状态）
    private double solar = 1.0;       // 太阳辐照因子
    private double volcanoCool = 0.0; // 火山降温偏置（缓慢回升）
    private int volcanoCd = 0;        // 火山冷却计时

    @Override public String name() { return "climate"; }

    @Override
    public void update(World w, SeededRNG rng) {
        phase++;
        // 慢速余弦周期：约 512 tick 一轮，±1.5 度偏置
        double cyc = StrictMath.sin((phase / 512.0) * 2 * Math.PI);
        if (volcanoCool < 0) volcanoCool += 0.01;   // 缓慢回升到 0
        tempBias = cyc * 1.5 + volcanoCool;

        // 太阳辐照缓慢波动（纯状态字段）
        if (phase % 32 == 0) {
            solar = 1.0 + rng.uniform(-0.2, 0.2);
            w.log("climate", "solar", "irr=" + fmt(solar), "fluctuate");
        }

        // 低频火山：约每 400 tick 冷却期内一骰
        if (volcanoCd > 0) volcanoCd--;
        if (volcanoCd == 0 && rng.nextDouble() < 0.01) {
            volcanoCd = 400;
            volcanoCool -= 2.0;                       // 累积压降温
            int samples = 12, fallen = 0;
            for (int i = 0; i < samples; i++) {
                int x = rng.nextInt(w.SX), z = rng.nextInt(w.SZ);
                int top = surfaceY(w, x, z);
                if (top < 0) continue;
                int b = w.mat[x][top][z];
                if ((b == Blocks.STONE.index || b == Blocks.SAND.index)
                        && top + 1 < w.SY
                        && w.mat[x][top + 1][z] == Blocks.AIR.index
                        && rng.nextDouble() < 0.5) {
                    w.setBlock(x, top + 1, z, Blocks.SNOW.index); // 灰=雪层占位，不新增质量
                    fallen++;
                }
            }
            w.log("climate", "volcano", "cool=" + fmt(volcanoCool) + ",ash=" + fallen, "erupt");
        }
    }

    private static int surfaceY(World w, int x, int z) {
        return w.surfaceY[x][z];
    }

    private static String fmt(double v) {
        return String.format("%.3f", v);
    }
}
