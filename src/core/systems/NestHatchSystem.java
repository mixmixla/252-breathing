package core.systems;

import core.rng.SeededRNG;
import core.world.Blocks;
import core.world.World;

/**
 * 巢窝孵化（57 · nesthatch，生物/种群）：复用 NestSystem 已筑的 LEAF 巢——当某 LEAF 处于“已建立的巢群”
 * （6 邻含 ≥3 个 LEAF，即密集巢簇）时，低概率(rng)在邻格空腔孵化出新的 LEAF 痕迹（小鸟/新巢），表现繁殖扩散。
 * 与 NestSystem 区分：NestSystem 是“初次筑巢”——以 WOOD 为锚、在 WOOD 邻 LEAF 的空腔放巢；
 * 本系统是“已有巢簇的孵化蔓延”，触发条件是 LEAF 密集而非 WOOD 锚点，二者触发条件互补不重叠。
 * 与 ParasiteSystem 区分：Parasite 把密集 LEAF 染病变 DIRT（消亡），本系统把密集 LEAF 旁空腔补新 LEAF（增殖）。
 * 随机严格走 simStream 入参 rng。
 */
public final class NestHatchSystem implements System {
    private static final int[][] N6 = {
        {1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}
    };

    @Override public String name() { return "nesthatch"; }

    @Override
    public void update(World w, SeededRNG rng) {
        int samples = 16;
        for (int i = 0; i < samples; i++) {
            int x = rng.nextInt(w.SX), y = rng.nextInt(w.SY), z = rng.nextInt(w.SZ);
            if (w.mat[x][y][z] != Blocks.LEAF.index) continue;
            // 已建立的巢群（LEAF 密集）才孵化扩散
            int n = 0;
            for (int[] o : N6)
                if (w.getBlock(x + o[0], y + o[1], z + o[2]) == Blocks.LEAF.index) n++;
            if (n < 3) continue;
            // 在邻格空腔孵化新巢/小鸟痕迹
            for (int[] o : N6) {
                int nx = x + o[0], ny = y + o[1], nz = z + o[2];
                if (w.inBounds(nx, ny, nz) && w.mat[nx][ny][nz] == Blocks.AIR.index
                        && rng.nextDouble() < 0.05) {
                    w.setBlock(nx, ny, nz, Blocks.LEAF.index);
                    w.log("nesthatch", "hatch", "x=" + nx + ",z=" + nz, "brood");
                    break;
                }
            }
        }
    }
}
