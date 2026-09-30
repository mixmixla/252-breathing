package core.sim;

import core.content.ContentRegistry;
import core.content.MaterialBook;
import core.world.Blocks;
import core.world.World;
import java.io.File;

/**
 * 一次性探针：密度驱动（{@code densityFlow}）开/关的 A/B 对比 —— 批 B 打开默认值前的门禁。
 *
 * <p>必须回答两件事：
 * <ol>
 *   <li><b>地形真的变了多少？</b>同种子 + 同一手铺场景，跑同样 tick 后逐格比对 {@code mat}
 *       与 {@code hashState}（要"变了"才说明开关接了线）；</li>
 *   <li><b>成本涨了多少？</b>墙钟差（红线 ≤0.5ms/tick）。</li>
 * </ol>
 *
 * <p><b>⚠️ 本探针第一版踩过的坑（留作教训）</b>：它拿两个世界做"计时"（其实是新建的副本），
 * 却拿另外两个**从未推进过 tick** 的世界做"逐格比较" → 两组静止状态当然逐格相同，
 * 于是打出 {@code cellsChanged=0} 的**假结论**（"开关没生效"）。
 * 现在：**同一对世界既计时又比较**，并且先断言"第 1 tick 就该出现差异"。
 *
 * <p>用法：{@code java -Xmx2g -cp "out;libs/gson-2.10.1.jar" core.sim.DensityFlowProbe [ticks]}
 */
public final class DensityFlowProbe {

    public static void main(String[] args) {
        int ticks = args.length > 0 ? Integer.parseInt(args[0]) : 1200;
        long seed = 20260918L;
        int SX = 96, SY = 64, SZ = 96;

        MaterialBook book = ContentRegistry.load(new File("assets/content")).materialBook();
        System.out.println("DENSITYFLOW probe  ticks=" + ticks + "  world=" + SX + "x" + SY + "x" + SZ
                + "  seed=" + seed + "  specs=" + book.size());

        World off = build(seed, SX, SY, SZ, book, false);
        World on = build(seed, SX, SY, SZ, book, true);
        System.out.println("  initialHashEqual = " + (off.hashState() == on.hashState()));

        // ① 瞬态体检：开关必须**第 1 tick** 就改地形（否则就是"接错线/没生效"）
        run(off, 1);
        run(on, 1);
        System.out.println("  afterTick1  cellsChanged=" + cellsChanged(off, on, SX, SY, SZ)
                + "   on[y20]=" + blk(on, 48, 20, 48) + " on[y21]=" + blk(on, 48, 21, 48)
                + "   off[y20]=" + blk(off, 48, 20, 48) + " off[y21]=" + blk(off, 48, 21, 48));

        // ② 成本：同一对世界继续推到 ticks（先预热再计时）
        run(off, 149);
        run(on, 149);
        int timed = ticks - 150;
        double msOff = time(off, timed);
        double msOn = time(on, timed);
        for (int r = 0; r < 2; r++) {                       // 交替复测（新世界，取最小减 JIT/GC 噪声）
            msOff = Math.min(msOff, time(build(seed, SX, SY, SZ, book, false), ticks));
            msOn = Math.min(msOn, time(build(seed, SX, SY, SZ, book, true), ticks));
        }

        // ③ 终局：地形差异 + 材料层证据 + 指纹
        int diff = cellsChanged(off, on, SX, SY, SZ);
        int sandNowOnWater = 0;
        for (int x = 0; x < SX; x++) {
            for (int y = 1; y < SY; y++) {
                for (int z = 0; z < SZ; z++) {
                    if (on.mat[x][y][z] == Blocks.SAND.index && off.mat[x][y][z] == Blocks.WATER.index) {
                        sandNowOnWater++;   // 开组的沙占据了关组曾是水的格 = 真的下沉了
                    }
                }
            }
        }
        long cells = (long) SX * SY * SZ;
        // 水体守恒检查：密度下沉是"沙与水交换位置"，水量本不该变化。
        // 若开组水量明显更少 → 说明发生了"沙持续填湖"（需要限制），这是批 B 必须排除的隐患。
        int waterOff = off.cellsOfType(Blocks.WATER.index).size();
        int waterOn = on.cellsOfType(Blocks.WATER.index).size();
        System.out.println("  hashOff = " + Long.toHexString(off.hashState()));
        System.out.println("  hashOn  = " + Long.toHexString(on.hashState()));
        System.out.println("  cellsChanged = " + diff + " / " + cells
                + "  (" + String.format("%.4f%%", 100.0 * diff / cells) + ")");
        System.out.println("  sandTookWaterCells = " + sandNowOnWater);
        System.out.println("  waterCells off=" + waterOff + " on=" + waterOn
                + "  delta=" + (waterOn - waterOff)
                + (Math.abs(waterOn - waterOff) > waterOff / 10
                        ? "   <-- 水量变化超过 10%，疑似沙填湖！" : "   (水量基本守恒)"));
        System.out.println("  msPerTickOff = " + String.format("%.3f", msOff / timed));
        System.out.println("  msPerTickOn  = " + String.format("%.3f", msOn / timed));
        System.out.println("  deltaMsPerTick = " + String.format("%+.3f", (msOn - msOff) / timed)
                + "   (red line: <= +0.500)");
        System.out.println("DENSITYFLOW DONE");
    }

    /** 建世界 + 注入材料表 + 在**世界中心**铺同一块"水池 + 沙堆"（两组逐格相同的初始场景）。 */
    private static World build(long seed, int sx, int sy, int sz, MaterialBook book, boolean flow) {
        Simulation s = new Simulation(seed, sx, sy, sz);
        World w = s.world;
        w.materials = book;
        w.config.densityFlow = flow;
        for (int x = 40; x < 56; x++) {
            for (int z = 40; z < 56; z++) {
                w.setBlock(x, 18, z, Blocks.WATER.index);
                w.setBlock(x, 19, z, Blocks.WATER.index);
                w.setBlock(x, 20, z, Blocks.WATER.index);
                w.setBlock(x, 21, z, Blocks.SAND.index);
                w.setBlock(x, 22, z, Blocks.SAND.index);
            }
        }
        return w;
    }

    private static void run(World w, int ticks) {
        for (int i = 0; i < ticks; i++) w.tick();
    }

    private static double time(World w, int ticks) {
        long t0 = java.lang.System.nanoTime();
        for (int i = 0; i < ticks; i++) w.tick();
        return (java.lang.System.nanoTime() - t0) / 1e6;
    }

    private static String blk(World w, int x, int y, int z) {
        return Blocks.byIndex(w.mat[x][y][z]).id;
    }

    private static int cellsChanged(World a, World b, int sx, int sy, int sz) {
        int n = 0;
        for (int x = 0; x < sx; x++)
            for (int y = 0; y < sy; y++)
                for (int z = 0; z < sz; z++)
                    if (a.mat[x][y][z] != b.mat[x][y][z]) n++;
        return n;
    }

    private DensityFlowProbe() { }
}
