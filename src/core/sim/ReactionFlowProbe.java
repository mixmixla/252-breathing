package core.sim;

import core.content.ContentRegistry;
import core.content.MaterialBook;
import core.content.ReactionBook;
import core.world.Blocks;
import core.world.World;
import java.io.File;

/**
 * 一次性探针：材料反应表（{@code reactionTable}）开/关的 A/B 对比 —— 批 C 打开默认值前的门禁。
 *
 * <p>必须回答三件事：
 * <ol>
 *   <li><b>开关接上没？</b>同种子跑同 tick，逐格比对 {@code mat} 与 {@code hashState}
 *       （要"变了"才说明求解器真的在工作）；</li>
 *   <li><b>变化是不是想要的？</b>开组的火应更少（被水浇灭）、冰/雪应更少（融化）；
 *       同时<b>水不能被大量消耗</b>（灭火不该把河喝干）；</li>
 *   <li><b>成本涨了多少？</b>墙钟差（红线 ≤0.5ms/tick）。</li>
 * </ol>
 *
 * <p>与 {@link DensityFlowProbe} 同一条纪律：<b>同一对世界既计时又比较</b>
 * （它第一版曾拿"从未推进过的世界"做比较，打出 {@code cellsChanged=0} 的假结论）。
 *
 * <p>用法：{@code java -Xmx2g -cp "out;libs/gson-2.10.1.jar" core.sim.ReactionFlowProbe [ticks]}
 */
public final class ReactionFlowProbe {

    public static void main(String[] args) {
        int ticks = args.length > 0 ? Integer.parseInt(args[0]) : 1200;
        long seed = 20260918L;
        int SX = 96, SY = 64, SZ = 96;

        ContentRegistry reg = ContentRegistry.load(new File("assets/content"));
        MaterialBook mbook = reg.materialBook();
        ReactionBook rbook = reg.reactionBook();
        System.out.println("REACTIONFLOW probe  ticks=" + ticks + "  world=" + SX + "x" + SY + "x" + SZ
                + "  seed=" + seed + "  rules=" + rbook.size() + " specs=" + mbook.size());

        World off = build(seed, SX, SY, SZ, mbook, rbook, false);
        World on = build(seed, SX, SY, SZ, mbook, rbook, true);
        System.out.println("  initialHashEqual = " + (off.hashState() == on.hashState()));

        // ① 瞬态体检：第 1 轮（PERIOD=4 tick）就该出现差异
        run(off, 1);
        run(on, 1);
        // 火会自己烧完（FireSpread 每 tick 50% 自熄），所以"灭火"必须**立刻**采样才有意义 ——
        // 跑几 tick 后两组火都归零，那条指标会毫无信息量（第一版就踩了这个坑）。
        int fireOffEarly = off.cellsOfType(Blocks.FIRE.index).size();
        int fireOnEarly = on.cellsOfType(Blocks.FIRE.index).size();
        int iceOffEarly = off.cellsOfType(Blocks.ICE.index).size();
        int iceOnEarly = on.cellsOfType(Blocks.ICE.index).size();
        System.out.println("  afterTick1  cellsChanged=" + cellsChanged(off, on, SX, SY, SZ)
                + "   fire off=" + fireOffEarly + " on=" + fireOnEarly
                + "   ice off=" + iceOffEarly + " on=" + iceOnEarly);
        run(off, 7);
        run(on, 7);

        // ② 成本：同一对世界继续推（先预热再计时）
        run(off, 142);
        run(on, 142);
        int timed = ticks - 150;
        double msOff = time(off, timed);
        double msOn = time(on, timed);
        for (int r = 0; r < 2; r++) {                       // 交替复测，取最小减 JIT/GC 噪声
            msOff = Math.min(msOff, time(build(seed, SX, SY, SZ, mbook, rbook, false), ticks));
            msOn = Math.min(msOn, time(build(seed, SX, SY, SZ, mbook, rbook, true), ticks));
        }

        // ③ 终局：差异 + 材料层证据 + 守恒检查
        int diff = cellsChanged(off, on, SX, SY, SZ);
        int fireOff = off.cellsOfType(Blocks.FIRE.index).size();
        int fireOn = on.cellsOfType(Blocks.FIRE.index).size();
        int iceOff = off.cellsOfType(Blocks.ICE.index).size()
                + off.cellsOfType(Blocks.SNOW.index).size();
        int iceOn = on.cellsOfType(Blocks.ICE.index).size()
                + on.cellsOfType(Blocks.SNOW.index).size();
        int waterOff = off.cellsOfType(Blocks.WATER.index).size();
        int waterOn = on.cellsOfType(Blocks.WATER.index).size();
        long cells = (long) SX * SY * SZ;

        System.out.println("  hashOff = " + Long.toHexString(off.hashState()));
        System.out.println("  hashOn  = " + Long.toHexString(on.hashState()));
        System.out.println("  cellsChanged = " + diff + " / " + cells
                + "  (" + String.format("%.4f%%", 100.0 * diff / cells) + ")");
        System.out.println("  fireCells  off=" + fireOff + " on=" + fireOn
                + "  delta=" + (fireOn - fireOff)
                + "   (终局：两组火都烧完了，此行只作参考；看上面 afterTick8 的早期采样)");
        System.out.println("  iceCells   off=" + iceOff + " on=" + iceOn
                + "  delta=" + (iceOn - iceOff) + "   (期望：开组更少 = 融化)");
        System.out.println("  waterCells off=" + waterOff + " on=" + waterOn
                + "  delta=" + (waterOn - waterOff)
                + (Math.abs(waterOn - waterOff) > Math.max(20, waterOff / 10)
                        ? "   <-- 水量变化过大，检查灭火是否吃水！" : "   (水量基本守恒)"));
        System.out.println("  msPerTickOff = " + String.format("%.3f", msOff / timed));
        System.out.println("  msPerTickOn  = " + String.format("%.3f", msOn / timed));
        System.out.println("  deltaMsPerTick = " + String.format("%+.3f", (msOn - msOff) / timed)
                + "   (red line: <= +0.500)");
        System.out.println("REACTIONFLOW DONE");
    }

    /**
     * 建世界 + 注入材料表与反应表 + 铺同一块"水边有一片火、旁边有冰"的初始场景。
     * 场景对两组逐格相同 —— 于是任何终局差异都只能来自开关。
     */
    private static World build(long seed, int sx, int sy, int sz,
                               MaterialBook mbook, ReactionBook rbook, boolean reactions) {
        Simulation s = new Simulation(seed, sx, sy, sz);
        World w = s.world;
        w.materials = mbook;
        w.reactions = rbook;
        w.config.reactionTable = reactions;
        for (int x = 40; x < 56; x++) {
            for (int z = 40; z < 56; z++) {
                w.setBlock(x, 18, z, Blocks.WATER.index);
                w.setBlock(x, 19, z, Blocks.FIRE.index);       // 火紧贴水面 → 应被浇灭
                w.setBlock(x, 17, z, Blocks.ICE.index);
            }
        }
        for (int x = 60; x < 72; x++) {
            for (int z = 60; z < 72; z++) {
                w.setBlock(x, 18, z, Blocks.SNOW.index);       // 远处一片雪（离火远，作为对照）
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

    private static int cellsChanged(World a, World b, int sx, int sy, int sz) {
        int n = 0;
        for (int x = 0; x < sx; x++)
            for (int y = 0; y < sy; y++)
                for (int z = 0; z < sz; z++)
                    if (a.mat[x][y][z] != b.mat[x][y][z]) n++;
        return n;
    }

    private ReactionFlowProbe() { }
}
