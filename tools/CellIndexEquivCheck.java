import core.sim.Simulation;
import core.world.Blocks;
import core.world.World;

import java.util.ArrayList;
import java.util.List;

/**
 * 索引快照 API 等价门禁（批⑧）：断言新 API {@code World.copyOf(idx, int[])} 与
 * {@code new ArrayList<>(cellsOfType(idx))}（原迭代器路径）**逐元素同序同值**。
 *
 * <p>背景：许多系统热路径用 {@code new ArrayList<>(cellsOfType(...))} 快照体素，而 CellSet 迭代器
 * **每格 new int[3]**（~万次/tick）。新 API 提供零每格分配的平铺快照。本门禁在**真实演化状态**上
 * 对多个类型反复比对两条路径，保证"换 API 不改语义"。
 *
 * 用法：{@code javac -cp out -d out tools/CellIndexEquivCheck.java && java -cp out CellIndexEquivCheck [SX SY SZ TICKS SEED]}
 */
public class CellIndexEquivCheck {
    public static void main(String[] a) {
        int SX = a.length > 0 ? Integer.parseInt(a[0]) : 96;
        int SY = a.length > 1 ? Integer.parseInt(a[1]) : 48;
        int SZ = a.length > 2 ? Integer.parseInt(a[2]) : 96;
        int TICKS = a.length > 3 ? Integer.parseInt(a[3]) : 240;
        long SEED = a.length > 4 ? Long.parseLong(a[4]) : 20260909L;

        int[] TYPES = { Blocks.LEAF.index, Blocks.GRASS.index, Blocks.WATER.index, Blocks.SAND.index,
                        Blocks.FLOWER.index, Blocks.MOSS.index, Blocks.ICE.index, Blocks.WOOD.index,
                        Blocks.DIRT.index, Blocks.STONE.index, Blocks.AIR.index };
        String[] NAMES = { "LEAF", "GRASS", "WATER", "SAND", "FLOWER", "MOSS", "ICE", "WOOD", "DIRT", "STONE", "AIR" };

        World w = new Simulation(SEED, SX, SY, SZ).world;
        int mismatches = 0;
        for (int t = 0; t < TICKS; t++) {
            w.tick();
            for (int ti = 0; ti < TYPES.length; ti++) {
                int idx = TYPES[ti];
                List<int[]> byIter = new ArrayList<>(w.cellsOfType(idx));
                int cnt = w.countOf(idx);
                int[] buf = new int[cnt * 3];
                int got = w.copyOf(idx, buf);
                boolean ok = (got == byIter.size()) && (cnt == byIter.size());
                if (ok) {
                    for (int i = 0, b = 0; i < cnt; i++, b += 3) {
                        int[] e = byIter.get(i);
                        if (buf[b] != e[0] || buf[b + 1] != e[1] || buf[b + 2] != e[2]) { ok = false; break; }
                    }
                }
                if (!ok) {
                    if (mismatches < 8)
                        System.out.println("  MISMATCH t=" + t + " type=" + NAMES[ti]
                                + " iter=" + byIter.size() + " copy=" + got + " count=" + cnt);
                    mismatches++;
                }
            }
        }
        System.out.println("ticks=" + TICKS + " mismatches=" + mismatches + " -> "
                + (mismatches == 0 ? "CELLINDEX EQUIVALENT (copyOf == iterator, 11 types)" : "CELLINDEX EQUIV FAILED"));
        if (mismatches != 0) System.exit(1);
    }
}
