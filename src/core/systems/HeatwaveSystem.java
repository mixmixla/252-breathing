package core.systems;

import core.rng.SeededRNG;
import core.world.Blocks;
import core.world.World;

/**
 * 热浪（51 · 气候与大气）：低频 rng 触发升温窗口；窗口内暴露空气中的表层 WATER 低概率蒸发为 AIR
 * （与 DroughtSystem 同类，但由热浪 event 驱动），记 "heatwave"."event"。
 * 不新增方块、不破守恒，纯确定性。
 */
public final class HeatwaveSystem implements System {
    private int t = 0;
    private int heatTicks = 0;

    @Override public String name() { return "heatwave"; }

    @Override
    public void update(World w, SeededRNG rng) {
        t++;
        if (heatTicks <= 0 && rng.nextDouble() < 0.01) {
            heatTicks = 40 + rng.nextInt(60);
            w.log("heatwave", "event", "dur=" + heatTicks, "onset");
        }
        if (heatTicks > 0) {
            heatTicks--;
            int samples = 20;
            for (int i = 0; i < samples; i++) {
                int x = rng.nextInt(w.SX), z = rng.nextInt(w.SZ);
                // 找最上 WATER 且其上方为 AIR 的暴露水面
                int wy = -1;
                for (int y = 0; y < w.SY; y++) {
                    if (w.mat[x][y][z] == Blocks.WATER.index && y + 1 < w.SY
                            && w.mat[x][y + 1][z] == Blocks.AIR.index) wy = y;
                }
                if (wy >= 0 && rng.nextDouble() < 0.05) {
                    w.setBlock(x, wy, z, Blocks.AIR.index); // 蒸发
                }
            }
        }
    }
}
