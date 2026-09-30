package core.systems;

import core.rng.SeededRNG;
import core.world.Blocks;
import core.world.World;

/**
 * 珊瑚礁连片扩展（59 · coralreef，生物/种群）：在“已存在的珊瑚”（LEAF 且 6 邻含 WATER）基础上，
 * 低概率(rng)向邻格 SAND/WATER 的上方空腔蔓延出新的 LEAF 珊瑚，表现礁体连片生长。
 * 与 CoralSystem 区分：CoralSystem 是“初次生成”——浅水(WATER 邻 SAND/STONE)水面之上随机长珊瑚；
 * 本系统只做“已有珊瑚向邻 SAND/WATER 蔓延”，以现有珊瑚为前置条件，二者生长/扩散互补、不重复。
 * 随机严格走 simStream 入参 rng。
 */
public final class CoralReefSystem implements System {
    private static final int[][] D6 = {
        {1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}
    };
    private static final int[] DX = {1, -1, 0, 0};
    private static final int[] DZ = {0, 0, 1, -1};

    @Override public String name() { return "coralreef"; }

    @Override
    public void update(World w, SeededRNG rng) {
        int samples = 20;
        for (int i = 0; i < samples; i++) {
            int x = rng.nextInt(w.SX), y = rng.nextInt(w.SY), z = rng.nextInt(w.SZ);
            if (w.mat[x][y][z] != Blocks.CORAL.index) continue;   // 第十二批：珊瑚的实体是 CORAL（原为 LEAF 占位）
            // 已存在的珊瑚 = 邻域含 WATER（以现有珊瑚为前置，区别于 CoralSystem 初次生成）
            boolean coral = false;
            for (int[] o : D6)
                if (w.getBlock(x + o[0], y + o[1], z + o[2]) == Blocks.WATER.index) { coral = true; break; }
            if (!coral) continue;
            // 向邻 SAND/WATER 的上方空腔蔓延
            for (int k = 0; k < 4; k++) {
                int nx = x + DX[k], nz = z + DZ[k];
                int b = w.getBlock(nx, y, nz);
                if ((b == Blocks.SAND.index || b == Blocks.WATER.index)
                        && w.inBounds(nx, y + 1, nz)
                        && w.mat[nx][y + 1][nz] == Blocks.AIR.index
                        && rng.nextDouble() < 0.04) {
                    w.setBlock(nx, y + 1, nz, Blocks.CORAL.index);
                    w.log("coralreef", "spread", "x=" + nx + ",z=" + nz, "reef");
                }
            }
        }
    }
}
