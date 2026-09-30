import core.sim.Simulation;
import core.world.Blocks;
import core.world.World;

/**
 * 落沙逐 tick 行为等价门禁（第五刀 / 第五批 SIM-PERF）：断言"新实现（只遍历 SAND 体素）"
 * 与"原实现（全窗 stride-2 扫描 + prev 缓存）"在同种子、同初始世界下**逐 tick 逐字节**一致。
 *
 * <p>做法：两个同种子世界 A/B，各关掉全部系统；A 只保留注册的 {@code sand}（新），
 * B 全关、每 tick 手动调用本文件内的**原实现逐字拷贝** {@link #referenceSand}。
 * 每 tick 全量比对两侧 mat（报告首个不一致的坐标）。
 *
 * <p>覆盖两种开关：{@code densityFlow} 关（出厂）与开（密度驱动会让沙沉入水）。
 *
 * 用法：{@code javac -cp out -d out tools/SandFallEquivCheck.java && java -cp out SandFallEquivCheck [SX SY SZ TICKS SEED]}
 */
public class SandFallEquivCheck {
    public static void main(String[] a) {
        int SX = a.length > 0 ? Integer.parseInt(a[0]) : 96;
        int SY = a.length > 1 ? Integer.parseInt(a[1]) : 48;
        int SZ = a.length > 2 ? Integer.parseInt(a[2]) : 96;
        int TICKS = a.length > 3 ? Integer.parseInt(a[3]) : 600;
        long SEED = a.length > 4 ? Long.parseLong(a[4]) : 20260909L;

        boolean okOff = compare(SX, SY, SZ, TICKS, SEED, false);
        boolean okOn = compare(SX, SY, SZ, TICKS, SEED, true);
        boolean ok = okOff && okOn;
        System.out.println("SANDFALL-EQUIV -> " + (ok ? "EQUIVALENT (per-tick byte-identical; densityFlow off+on)"
                : "FAILED"));
        if (!ok) System.exit(1);
    }

    private static boolean compare(int SX, int SY, int SZ, int TICKS, long SEED, boolean dens) {
        World wa = new Simulation(SEED, SX, SY, SZ).world;
        World wb = new Simulation(SEED, SX, SY, SZ).world;
        wa.config.densityFlow = dens;
        wb.config.densityFlow = dens;
        for (String nm : wa.registry.names()) wa.registry.disable(nm);
        for (String nm : wb.registry.names()) wb.registry.disable(nm);
        wa.registry.enable("sand");           // A：只跑"新" sand

        int mismatches = 0;
        for (int t = 0; t < TICKS; t++) {
            wa.tick();                        // 新实现
            referenceSand(wb);                // 原实现（逐字拷贝）
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
                    System.out.println("  MISMATCH dens=" + dens + " t=" + t + " cells=" + bad + " first=" + firstKey);
                mismatches++;
            }
        }
        boolean ok = mismatches == 0;
        System.out.println("  densityFlow=" + dens + "  worlds=" + SX + "x" + SY + "x" + SZ
                + " ticks=" + TICKS + "  mismatches=" + mismatches + "  " + (ok ? "OK" : "FAIL"));
        return ok;
    }

    /** 原实现（第五刀之前）逐字拷贝：全窗 stride-2 扫描 + prev 缓存。 */
    static void referenceSand(World w) {
        final int step = 2;
        final boolean dens = w.config != null && w.config.densityFlow;
        final int[] prev = new int[w.SZ];
        for (int x = 0; x < w.SX; x += step) {
            for (int z = 0; z < w.SZ; z += step) prev[z] = w.mat[x][0][z];
            for (int y = 1; y < w.SY; y++) {
                for (int z = 0; z < w.SZ; z += step) {
                    int cur = w.mat[x][y][z];
                    boolean canFall = cur == Blocks.SAND.index
                            && (prev[z] == Blocks.AIR.index
                                || (dens && w.materials.sinksInto(cur, prev[z])));
                    if (canFall) {
                        int below = prev[z];
                        w.setBlock(x, y - 1, z, Blocks.SAND.index);
                        w.setBlock(x, y, z, below);
                        prev[z] = below;
                    } else {
                        prev[z] = cur;
                    }
                }
            }
        }
    }
}
