package core.systems;

import core.rng.SeededRNG;
import core.world.Blocks;
import core.world.World;

/**
 * 浅水珊瑚（生态/水生）：浅水（WATER 邻 SAND/STONE）在水面之上偶尔长出珊瑚。
 * <p>第十二批：**不再用 LEAF 占位**，直接长出 {@link Blocks#CORAL}（对应方块已存在）。
 * 演示“温暖浅滩长珊瑚”这一缓慢涌现，随机走 simStream。
 */
public final class CoralSystem implements System {
    @Override public String name() { return "coral"; }

    @Override
    public void update(World w, SeededRNG rng) {
        int samples = 32;
        for (int i = 0; i < samples; i++) {
            int x = rng.nextInt(w.SX), z = rng.nextInt(w.SZ);
            int wy = waterSurface(w, x, z);
            if (wy < 0) continue;
            // 同深度四邻须有沙/石（浅滩基底）
            boolean shallow = w.getBlock(x - 1, wy, z) == Blocks.SAND.index
                           || w.getBlock(x + 1, wy, z) == Blocks.SAND.index
                           || w.getBlock(x, wy, z - 1) == Blocks.SAND.index
                           || w.getBlock(x, wy, z + 1) == Blocks.SAND.index
                           || w.getBlock(x - 1, wy, z) == Blocks.STONE.index
                           || w.getBlock(x + 1, wy, z) == Blocks.STONE.index
                           || w.getBlock(x, wy, z - 1) == Blocks.STONE.index
                           || w.getBlock(x, wy, z + 1) == Blocks.STONE.index;
            if (shallow && wy + 1 < w.SY && w.mat[x][wy + 1][z] == Blocks.AIR.index
                    && rng.nextDouble() < 0.03) {
                w.setBlock(x, wy + 1, z, Blocks.CORAL.index);  // 珊瑚伸出水面（第十二批：改用 CORAL，不再用 LEAF 占位）
            }
        }
    }

    static int waterSurface(World w, int x, int z) {
        for (int y = 0; y < w.SY - 1; y++)
            if (w.mat[x][y][z] == Blocks.WATER.index && w.mat[x][y + 1][z] == Blocks.AIR.index)
                return y;
        return -1;
    }
}
