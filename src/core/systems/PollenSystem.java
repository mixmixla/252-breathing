package core.systems;

import core.rng.SeededRNG;
import core.world.Blocks;
import core.world.World;

/**
 * 花粉（53 · 气候与大气）：FLOWER 邻格低概率向空中（上方空腔）散播花粉痕迹
 * （复用 FLOWER 在受风偏置的邻空落一朵，或记 event）。方向受 WindSystem 当前风矢量偏置
 * （读 World.windX/windZ）。确定性，随机走 simStream。
 */
public final class PollenSystem implements System {
    private int t = 0;

    @Override public String name() { return "pollen"; }

    @Override
    public void update(World w, SeededRNG rng) {
        t++;
        int samples = 16;
        for (int i = 0; i < samples; i++) {
            int x = rng.nextInt(w.SX), z = rng.nextInt(w.SZ);
            int top = surfaceY(w, x, z);
            if (top < 0 || top + 1 >= w.SY) continue;
            // 花位于草面之上一格（FlowerSystem 在 top+1 落花），故在 top+1 读源花
            if (w.mat[x][top + 1][z] != Blocks.FLOWER.index) continue;
            // 花上方的空腔（top+2）才能承接散播的花粉
            if (w.mat[x][top + 2][z] != Blocks.AIR.index) continue;
            // 受风偏置：按风矢量选一个偏移方向落花粉
            int dx = 0, dz = 0;
            if (w.windX > 0.2) dx = 1; else if (w.windX < -0.2) dx = -1;
            if (w.windZ > 0.2) dz = 1; else if (w.windZ < -0.2) dz = -1;
            int px = x + dx, pz = z + dz;
            if (!w.inBounds(px, top + 2, pz)) { px = x; pz = z; }
            if (w.mat[px][top + 2][pz] == Blocks.AIR.index && rng.nextDouble() < 0.08) {
                w.setBlock(px, top + 2, pz, Blocks.FLOWER.index); // 花粉痕迹复用 FLOWER
            } else {
                w.log("pollen", "drift", "from=(" + x + "," + z + ")", "carry");
            }
        }
    }

    private static int surfaceY(World w, int x, int z) {
        return w.surfaceY[x][z];
    }
}
