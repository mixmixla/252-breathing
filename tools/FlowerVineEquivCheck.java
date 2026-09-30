import core.rng.SeededRNG;
import core.sim.Simulation;
import core.world.Blocks;
import core.world.World;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 花 / 藤蔓逐 tick 行为等价门禁（批⑧）：断言"新实现（平铺快照 / 线性归并）"与
 * "原实现（ArrayList+迭代器 / 合并后 Collections.sort）"在同种子、同初始世界下**逐 tick 逐字节**一致。
 *
 * <p>做法：两世界 A/B，各关掉全部系统；A 只保留被测系统（走注册的新实现，`tick()` 驱动），
 * B 全关、每 tick 先 `tick()` 推进 tick（保证 simStream 派生的子流一致）再手动调用本文件内的
 * **原实现逐字拷贝**并传入 {@code wb.simStream(name)}。每 tick 全量比对 mat。
 *
 * 用法：{@code javac -cp out -d out tools/FlowerVineEquivCheck.java && java -cp out FlowerVineEquivCheck [SX SY SZ TICKS SEED]}
 */
public class FlowerVineEquivCheck {
    public static void main(String[] a) {
        int SX = a.length > 0 ? Integer.parseInt(a[0]) : 96;
        int SY = a.length > 1 ? Integer.parseInt(a[1]) : 48;
        int SZ = a.length > 2 ? Integer.parseInt(a[2]) : 96;
        int TICKS = a.length > 3 ? Integer.parseInt(a[3]) : 400;
        long SEED = a.length > 4 ? Long.parseLong(a[4]) : 20260909L;

        boolean okF = compare("flower", SX, SY, SZ, TICKS, SEED, true);
        boolean okV = compare("vine", SX, SY, SZ, TICKS, SEED, false);
        boolean ok = okF && okV;
        System.out.println("FLOWERVINE-EQUIV -> " + (ok ? "EQUIVALENT (per-tick byte-identical)" : "FAILED"));
        if (!ok) System.exit(1);
    }

    private static boolean compare(String which, int SX, int SY, int SZ, int TICKS, long SEED, boolean flower) {
        World wa = new Simulation(SEED, SX, SY, SZ).world;   // A：跑新实现（经 tick）
        World wb = new Simulation(SEED, SX, SY, SZ).world;   // B：全关 + 手动跑原实现
        for (String nm : wa.registry.names()) wa.registry.disable(nm);
        for (String nm : wb.registry.names()) wb.registry.disable(nm);
        wa.registry.enable(which);

        int mismatches = 0;
        for (int t = 0; t < TICKS; t++) {
            wa.tick();
            wb.tick();                                        // 只推进 tick（系统全禁）
            SeededRNG rng = wb.simStream(which);              // 与 A 同 tick ⇒ 同子流
            if (flower) referenceFlower(wb, rng); else referenceVine(wb, rng);
            int bad = 0;
            long firstKey = -1;
            for (int x = 0; x < SX; x++)
                for (int y = 0; y < SY; y++)
                    for (int z = 0; z < SZ; z++)
                        if (wa.mat[x][y][z] != wb.mat[x][y][z]) {
                            bad++;
                            if (firstKey < 0) firstKey = World.cellKey(x, y, z);
                            if (bad > 16) { x = SX; y = SY; z = SZ; }
                        }
            if (bad != 0) {
                if (mismatches < 5)
                    System.out.println("  MISMATCH " + which + " t=" + t + " cells=" + bad + " first=" + firstKey);
                mismatches++;
            }
        }
        boolean ok = mismatches == 0;
        System.out.println("  " + which + "  worlds=" + SX + "x" + SY + "x" + SZ + " ticks=" + TICKS
                + "  mismatches=" + mismatches + "  " + (ok ? "OK" : "FAIL"));
        return ok;
    }

    /** 原 FlowerSystem（批⑧之前）：ArrayList 快照 + getBlock。 */
    static void referenceFlower(World w, SeededRNG rng) {
        java.util.List<int[]> grass = new java.util.ArrayList<>(w.cellsOfType(Blocks.GRASS.index));
        for (int[] c : grass) {
            int x = c[0], y = c[1], z = c[2];
            if (y + 1 >= w.SY) continue;
            if (w.getBlock(x, y + 1, z) == Blocks.AIR.index && rng.nextDouble() < 0.02) {
                w.setBlock(x, y + 1, z, Blocks.FLOWER.index);
            }
        }
    }

    /** 原 VineSystem（批⑧之前）：合并 WOOD/LEAF 后 Collections.sort。 */
    static void referenceVine(World w, SeededRNG rng) {
        List<int[]> cells = new ArrayList<>(w.cellsOfType(Blocks.WOOD.index));
        cells.addAll(w.cellsOfType(Blocks.LEAF.index));
        Collections.sort(cells, (p, q) -> {
            if (p[0] != q[0]) return Integer.compare(p[0], q[0]);
            if (p[1] != q[1]) return Integer.compare(p[1], q[1]);
            return Integer.compare(p[2], q[2]);
        });
        for (int[] c : cells) {
            int x = c[0], y = c[1], z = c[2];
            if (y >= w.SY - 1) continue;
            int b = w.getBlock(x, y, z);
            if (b != Blocks.WOOD.index && b != Blocks.LEAF.index) continue;
            int below = w.getBlock(x, y - 1, z);
            if (below == Blocks.AIR.index && rng.nextDouble() < 0.02) {
                w.setBlock(x, y - 1, z, Blocks.LEAF.index);
            }
        }
    }
}
