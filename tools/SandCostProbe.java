import core.rng.SeededRNG;
import core.sim.Simulation;
import core.systems.SandFallSystem;
import core.world.Blocks;
import core.world.World;

/**
 * SandFallSystem 成本 + SAND 规模探针（headless，只读/测量）。
 *
 * <p>AblationProbe 在此不可用：关掉任一系统都会改变世界演化轨迹，世界"更满/更空"的代价
 * 远大于单个系统自身的成本（实测关沙反而更慢）⇒ 归因失真。本探针改为**直接计时**：
 *   · world A：全系统跑 WARM tick 后，测 300 tick 的整 tick 墙钟 → fullMs；
 *   · world B：同种子跑 WARM tick（状态与 A 相同），随后**只**调 SandFallSystem.update() 300 次 → sandMs；
 *   · 再打印 SAND 体素数（决定"按体素索引迭代"能否取代"全窗扫描"）。
 *
 * 用法：{@code java -cp out;LIBS SandCostProbe [SX SY SZ WARM MEASURE seed]}
 */
public final class SandCostProbe {
    public static void main(String[] args) {
        int SX = a(args, 0, 160), SY = a(args, 1, 112), SZ = a(args, 2, 160);
        int WARM = a(args, 3, 200), MEASURE = a(args, 4, 300);
        long SEED = args.length > 5 ? Long.parseLong(args[5]) : 20260917L;

        // A：整 tick 墙钟
        World wa = new Simulation(SEED, SX, SY, SZ).world;
        for (int i = 0; i < WARM; i++) wa.tick();
        long t0 = java.lang.System.nanoTime();
        for (int i = 0; i < MEASURE; i++) wa.tick();
        double fullMs = (java.lang.System.nanoTime() - t0) / 1e6 / MEASURE;

        // B：同种子同 WARM（状态一致），只计时 sand.update()
        World wb = new Simulation(SEED, SX, SY, SZ).world;
        for (int i = 0; i < WARM; i++) wb.tick();
        SandFallSystem sand = new SandFallSystem();
        SeededRNG rng = wb.simStream("sand:probe");
        long t1 = java.lang.System.nanoTime();
        for (int i = 0; i < MEASURE; i++) sand.update(wb, rng);
        double sandMs = (java.lang.System.nanoTime() - t1) / 1e6 / MEASURE;

        int sandCells = wb.cellsOfType(Blocks.SAND.index).size();
        long vol = (long) SX * SY * SZ;
        System.out.printf("SANDPROBE world=%dx%dx%d vol=%d warm=%d measure=%d%n", SX, SY, SZ, vol, WARM, MEASURE);
        System.out.printf("  full tick      = %8.3f ms%n", fullMs);
        System.out.printf("  sand.update()  = %8.3f ms   (%.1f%% of tick)%n", sandMs, 100.0 * sandMs / fullMs);
        System.out.printf("  SAND cells     = %d   (scan visits %d cells = vol/4)%n", sandCells, vol / 4);
        System.out.printf("  scan/sand ratio= %.1fx%n", (double)(vol / 4) / Math.max(1, sandCells));
    }

    private static int a(String[] x, int i, int d) { return x.length > i ? Integer.parseInt(x[i]) : d; }
}
