package core.systems;

import core.rng.SeededRNG;
import core.world.Blocks;
import core.world.World;

import java.util.ArrayList;
import java.util.List;

/**
 * 岩浆流动系统（地质/流体，第二批「源源不断」2026-09-23）：作用于真正的 {@code LAVA} 流体方块。
 *
 * <p>与 {@link LavaSystem}（#73，仅用 FIRE 占位的地底熔岩源）区分：本系统负责地表 / 洞穴里
 * {@code LAVA} 方块的<b>流动</b>与<b>引燃</b>。
 * <ul>
 *   <li>引燃：相邻可燃物（WOOD/LEAF/植物系/作物）低概率被点成 FIRE。</li>
 *   <li>流动：向<b>下方快、水平慢</b>低概率蔓延进 AIR；岩浆比水黏，<b>不向上爬升</b>。</li>
 * </ul>
 *
 * <p>确定性铁律：随机仅走入参 {@code rng}（逐 tick 派生的 simStream 子流）；只用 {@code setBlock}
 * 演化 LAVA / FIRE 格；旧世界不含 LAVA → 不改变仿真指纹的**确定性**（同种子两跑一致）。
 */
public final class LavaFlowSystem implements System {

    private static final int[] DX = {1, -1, 0, 0, 0, 0};
    private static final int[] DY = {0, 0, 1, -1, 0, 0};
    private static final int[] DZ = {0, 0, 0, 0, 1, -1};

    @Override public String name() { return "lava_flow"; }

    @Override
    public void update(World w, SeededRNG rng) {
        List<int[]> lava = new ArrayList<int[]>(w.cellsOfType(Blocks.LAVA.index));
        for (int[] c : lava) {
            int x = c[0], y = c[1], z = c[2];
            for (int k = 0; k < 6; k++) {
                int nx = x + DX[k], ny = y + DY[k], nz = z + DZ[k];
                if (!w.inBounds(nx, ny, nz)) continue;
                int b = w.getBlock(nx, ny, nz);
                if (isFlammable(b)) {
                    if (rng.nextDouble() < 0.25) w.setBlock(nx, ny, nz, Blocks.FIRE.index);
                } else if (b == Blocks.AIR.index) {
                    if (k == 4) continue;                       // +Y：岩浆不向上爬
                    double pp = (k == 5) ? 0.10 : 0.05;         // -Y（下）更快
                    if (rng.nextDouble() < pp) w.setBlock(nx, ny, nz, Blocks.LAVA.index);
                }
            }
        }
    }

    private static boolean isFlammable(int b) {
        return b == Blocks.WOOD.index || b == Blocks.LEAF.index || b == Blocks.CACTUS.index
                || b == Blocks.FLOWER.index || b == Blocks.VINES.index || b == Blocks.MUSHROOM.index
                || b == Blocks.WHEAT_0.index || b == Blocks.WHEAT_1.index || b == Blocks.WHEAT_2.index
                || b == Blocks.WHEAT_3.index
                || b == Blocks.SUGARCANE_0.index || b == Blocks.SUGARCANE_1.index || b == Blocks.SUGARCANE_2.index
                || b == Blocks.CACTUS_FLOWER_0.index || b == Blocks.CACTUS_FLOWER_1.index
                || b == Blocks.CACTUS_FLOWER_2.index;
    }
}
