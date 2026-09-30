package core.systems;

import core.rng.SeededRNG;
import core.world.World;

/**
 * 雾霾（54 · 气候与大气）：低频 rng 在"工业/火山"活跃时积累灰（状态字段 smog），
 * 降低日照因子 sunFactor（影响 TREE/FLOWER 生长概率的潜在耦合状态），记 "smog"."accumulate" event。
 * 不新增固体质量、不改网格、不进指纹，严格确定性。
 */
public final class SmogSystem implements System {
    private int t = 0;
    private double smog = 0.0;       // 霾浓度 [0,1]
    private double sunFactor = 1.0;  // 日照因子（被霾压低；latent 耦合 TREE/FLOWER 生长）

    @Override public String name() { return "smog"; }

    @Override
    public void update(World w, SeededRNG rng) {
        t++;
        if (rng.nextDouble() < 0.005) { // 低频排放
            smog = Math.min(1.0, smog + 0.3);
            sunFactor = 1.0 - 0.5 * smog;
            w.log("smog", "accumulate", "smog=" + fmt(smog), "haze");
        }
        if (smog > 0) { // 缓慢消散
            smog = Math.max(0, smog - 0.002);
            sunFactor = 1.0 - 0.5 * smog;
        }
    }

    private static String fmt(double v) {
        return String.format("%.3f", v);
    }
}
