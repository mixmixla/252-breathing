package core.systems;

import core.rng.SeededRNG;
import core.world.Blocks;
import core.world.World;

import java.util.ArrayList;
import java.util.List;

/**
 * 熔岩涌流（73 · 地质/深地）：从地底岩浆源头发起——近 BEDROCK 的封闭空腔以低概率涌出 FIRE 熔岩
 * （无 LAVA 方块，熔岩用 FIRE 占位），并沿邻腔缓慢爬流。
 * 与 FireSpreadSystem（仅由 WOOD/LEAF 起火、向可燃物蔓延）区分：本系统是“地底岩浆源 + 深层层流”，
 * 源头不是地表植被，而是底层岩体空腔；爬流只作用于深层 FIRE 向邻 AIR 扩散。
 * 零漂移：随机仅走入参 rng（逐 tick 派生子流）；用 setBlock 演化。
 */
public final class LavaSystem implements System {
    private static final int[] DX = {1, -1, 0, 0, 0, 0};
    private static final int[] DY = {0, 0, 1, -1, 0, 0};
    private static final int[] DZ = {0, 0, 0, 0, 1, -1};

    @Override public String name() { return "lava"; }

    @Override
    public void update(World w, SeededRNG rng) {
        // 源头：近 BEDROCK 的封闭空腔低概率涌出岩浆
        int samples = 12;
        for (int i = 0; i < samples; i++) {
            int x = rng.nextInt(w.SX);
            int y = 1 + rng.nextInt(4);          // 近底层 y∈[1,4]
            int z = rng.nextInt(w.SZ);
            if (w.mat[x][y][z] != Blocks.AIR.index) continue;
            boolean embedded = false;            // 须嵌入岩体（邻 BEDROCK/STONE）方成腔
            for (int k = 0; k < 6; k++) {
                int b = w.getBlock(x + DX[k], y + DY[k], z + DZ[k]);
                if (b == Blocks.BEDROCK.index || b == Blocks.STONE.index) { embedded = true; break; }
            }
            if (!embedded) continue;
            if (rng.nextDouble() < 0.03) {
                w.setBlock(x, y, z, Blocks.FIRE.index);
                w.log("lava", "erupt", "at=" + x + "," + y + "," + z, "source");
            }
        }
        // 熔岩缓慢向邻空腔爬（仅处理深层 FIRE，区别于 FireSpread 的 WOOD/LEAF 点燃）
        List<int[]> lava = new ArrayList<>(w.cellsOfType(Blocks.FIRE.index));
        for (int[] c : lava) {
            if (c[1] > 6) continue;              // 只处理深层层流
            for (int k = 0; k < 6; k++) {
                int nx = c[0] + DX[k], ny = c[1] + DY[k], nz = c[2] + DZ[k];
                if (!w.inBounds(nx, ny, nz)) continue;
                if (w.mat[nx][ny][nz] == Blocks.AIR.index && rng.nextDouble() < 0.12) {
                    w.setBlock(nx, ny, nz, Blocks.FIRE.index);
                }
            }
        }
    }
}
