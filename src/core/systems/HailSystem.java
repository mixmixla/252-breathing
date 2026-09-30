package core.systems;

import core.rng.SeededRNG;
import core.world.Blocks;
import core.world.World;

/**
 * 冰雹（50 · 气候与大气）：雨云相位（本系统自维护的阴云窗口，对应 WeatherSystem 降雨相位）时，
 * 在地表上方空腔低概率落 ICE 碎块（setBlock ICE）。纯网格演化，确定性，随机走 simStream。
 */
public final class HailSystem implements System {
    private int t = 0;
    private boolean cloudy = false;
    private int cloudTicks = 0;

    @Override public String name() { return "hail"; }

    @Override
    public void update(World w, SeededRNG rng) {
        t++;
        if (!cloudy) {
            if (t % 64 == 0 && rng.nextDouble() < 0.3) {
                cloudy = true; cloudTicks = 24 + rng.nextInt(32);
            }
        } else {
            cloudTicks--;
            // 批⑤：结冰全局上限（派生式）。判据置于 rng 抽取之后 ⇒ 不位移本系统随机流。
            int remain = w.spreadHeadroom(Blocks.ICE.index);
            int samples = 16;
            for (int i = 0; i < samples; i++) {
                int x = rng.nextInt(w.SX), z = rng.nextInt(w.SZ);
                int top = surfaceY(w, x, z);
                if (top < 0 || top + 1 >= w.SY) continue;
                if (w.mat[x][top + 1][z] == Blocks.AIR.index && rng.nextDouble() < 0.1 && remain > 0) {
                    w.setBlock(x, top + 1, z, Blocks.ICE.index); // 冰雹碎块
                    remain--;
                }
            }
            if (cloudTicks <= 0) cloudy = false;
        }
    }

    private static int surfaceY(World w, int x, int z) {
        return w.surfaceY[x][z];
    }
}
