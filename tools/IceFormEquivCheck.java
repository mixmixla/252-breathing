import core.world.World;
import core.world.Blocks;

import java.util.HashSet;
import java.util.Set;

/**
 * IceForm 逐 tick 行为等价探针（SIM-PERF-4）：每 tick 分别用"原 topWaterY 扫描法"与"waterSurfaceCells 桶排法"
 * 计算"各自会 setBlock 成 ICE 的格集合"（即头顶为空气的表层水格），断言两集合逐 tick 恒等。
 * 只比较候选格集合（rng 概率仅决定其中哪些真正结冰；两法在同列同序消费同一 rng，故实际结冰格也恒等——
 * 该点由 DETERMINISM 指纹门禁最终证明）。
 *
 * 用法：javac -cp out -d out tools/IceFormEquivCheck.java && java -cp out IceFormEquivCheck
 *
 * <p>⚠️ 批⑯修正：原用**裸 `new World(...)`** ⇒ 世界无系统（`World.systems` 空，仅 `Simulation.reg()` 填充）
 * ⇒ "600 tick" 期间**世界一格没变**，本探针实际只测了"生成后一瞬"。改为真 `Simulation` 驱动。
 */
public class IceFormEquivCheck {
    public static void main(String[] a) {
        int SX = a.length > 0 ? Integer.parseInt(a[0]) : 96;
        int SY = a.length > 1 ? Integer.parseInt(a[1]) : 48;
        int SZ = a.length > 2 ? Integer.parseInt(a[2]) : 96;
        int TICKS = a.length > 3 ? Integer.parseInt(a[3]) : 600;
        long SEED = a.length > 4 ? Long.parseLong(a[4]) : 20260909L;

        World w = new core.sim.Simulation(SEED, SX, SY, SZ).world;
        int mismatches = 0;

        for (int t = 0; t < TICKS; t++) {
            w.tick();
            // ---- 原 topWaterY 扫描法：每列最顶 WATER，其头顶为空气则候选结冰 ----
            Set<Long> oldSet = new HashSet<>();
            for (int x = 0; x < SX; x++)
                for (int z = 0; z < SZ; z++) {
                    int top = -1;
                    for (int y = 0; y < SY; y++)
                        if (w.mat[x][y][z] == Blocks.WATER.index) top = y;
                    if (top < 0) continue;
                    if (w.getBlock(x, top + 1, z) == Blocks.AIR.index)
                        oldSet.add(World.cellKey(x, top, z));
                }
            // ---- 新 waterSurfaceCells 法：桶排每列最大 y 水面，头顶为空气则候选结冰 ----
            int[][] surf = new int[SX][SZ];
            for (int x = 0; x < SX; x++) for (int z = 0; z < SZ; z++) surf[x][z] = -1;
            for (long key : w.waterSurfaceCells) {
                int x = (int) ((key >> 20) & 0x3FFL);
                int y = (int) ((key >> 10) & 0x3FFL);
                int z = (int) (key & 0x3FFL);
                if (y > surf[x][z]) surf[x][z] = y;
            }
            Set<Long> newSet = new HashSet<>();
            for (int x = 0; x < SX; x++)
                for (int z = 0; z < SZ; z++) {
                    int top = surf[x][z];
                    if (top < 0) continue;
                    if (w.getBlock(x, top + 1, z) == Blocks.AIR.index)
                        newSet.add(World.cellKey(x, top, z));
                }
            if (!oldSet.equals(newSet)) {
                Set<Long> onlyOld = new HashSet<>(oldSet); onlyOld.removeAll(newSet);
                Set<Long> onlyNew = new HashSet<>(newSet); onlyNew.removeAll(oldSet);
                if (mismatches < 5)
                    System.out.println("MISMATCH t=" + t + " old=" + oldSet.size()
                            + " new=" + newSet.size()
                            + " onlyOld(size)=" + onlyOld.size()
                            + " onlyNew(size)=" + onlyNew.size());
                mismatches++;
            }
        }
        System.out.println("ticks=" + TICKS + " mismatches=" + mismatches
                + " -> " + (mismatches == 0 ? "ICEFORM EQUIVALENT (per-tick)" : "ICEFORM EQUIV FAILED"));
        if (mismatches != 0) System.exit(1);
    }
}
