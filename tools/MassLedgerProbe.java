import core.sim.Simulation;
import core.world.Blocks;
import core.world.World;

import java.util.ArrayList;
import java.util.List;

/**
 * 物质账本探针（批⑮，2026-09-29）——「先量再改」的第一步。
 *
 * <p>背景：本项目<b>没有质量守恒律</b>，只有若干"散布者"系统从无到有地长出方块（LEAF/MOSS/ICE…），
 * 以及若干"开放源/汇"（降雨加 WATER、蒸发去 WATER）。批⑤/⑥ 给这些系统加了<b>数量</b>上限，
 * 但从未把"这个世界的物质收支账"算出来过。本探针把它显式化：
 *
 * <p>守恒量取 <b>Σ (density(mat) − density(AIR))</b> —— 用每个方块的 {@code MaterialBook.density}
 * 加权。减掉 AIR 基线（AIR 密度 = 1，是"不适用"占位值而非物理量）后，空格贡献 0，
 * 于是该量就是"世界里囤了多少物质"的一个可加总标量。
 *
 * <p>输出：各采样点的 ① 非空气格数 ② 总物质质量 ③ 质量变化率 ④ 质量贡献榜（谁在造物质）。
 * 纯读、不改世界、不消费 RNG。
 *
 * <p>运行：{@code java -cp "out;LIBS" MassLedgerProbe [SX SY SZ TICK STEP SEED]}
 */
public final class MassLedgerProbe {

    /** 值得盯的物质类型（其余仍计入总质量，只是不进"贡献榜"）。 */
    private static final int[] WATCH = {
            Blocks.STONE.index, Blocks.DIRT.index, Blocks.GRASS.index, Blocks.SAND.index,
            Blocks.WATER.index, Blocks.ICE.index, Blocks.SNOW.index, Blocks.WOOD.index,
            Blocks.LEAF.index, Blocks.MOSS.index, Blocks.FLOWER.index, Blocks.ASH.index,
            Blocks.SHELTER.index, Blocks.FIRE.index, Blocks.CACTUS.index, Blocks.CLAY.index,
            Blocks.GRAVEL.index, Blocks.MUD.index, Blocks.PODZOL.index, Blocks.VINES.index,
    };

    public static void main(String[] args) {
        int SX = args.length > 0 ? Integer.parseInt(args[0]) : 96;
        int SY = args.length > 1 ? Integer.parseInt(args[1]) : 48;
        int SZ = args.length > 2 ? Integer.parseInt(args[2]) : 96;
        int TICKS = args.length > 3 ? Integer.parseInt(args[3]) : 20000;
        int STEP = args.length > 4 ? Integer.parseInt(args[4]) : 2000;
        long SEED = args.length > 5 ? Long.parseLong(args[5]) : 20260929L;

        Simulation sim = new Simulation(SEED, SX, SY, SZ);
        World w = sim.world;

        // ---- ⚠️ 关键：headless Simulation 的 World.materials 默认是 empty 存根（全体 density=20 兜底）----
        // 真实材料表在 ContentRegistry（解析 assets/content/materials/*.json）里构建，由 Game 挂到 World。
        // 不接这一步，账本会算出一个"所有方块同密度"的假账（本探针首跑即踩到：AIR=WATER=STONE=20）。
        core.content.ContentRegistry reg = core.content.ContentRegistry.load(new java.io.File("assets/content"));
        if (reg != null) {
            w.materials = reg.materialBook();
            w.reactions = reg.reactionBook();
        }

        // ---- 密度 API 自检（防止 materials 未加载 ⇒ 全是 20 的假账）----
        int dAir = w.materials.density(Blocks.AIR.index);
        int dWater = w.materials.density(Blocks.WATER.index);
        int dStone = w.materials.density(Blocks.STONE.index);
        java.lang.System.out.println("MATLEDGER probe  world=" + SX + "x" + SY + "x" + SZ
                + "  seed=" + SEED + "  ticks=" + TICKS + "  step=" + STEP);
        java.lang.System.out.println("  density API 自检: AIR=" + dAir + " WATER=" + dWater + " STONE=" + dStone
                + "  (AIR 基线=" + dAir + ")");
        if (reg == null || (dWater == dStone && dWater == dAir)) {
            java.lang.System.out.println("  !! materials 仍是 empty 存根（density 全体同值）⇒ 账本无意义，中止");
            return;
        }

        long m0 = mass(w, dAir);
        int n0 = nonAir(w);
        java.lang.System.out.println();
        java.lang.System.out.printf("  %7s %10s %14s %14s   %s%n",
                "tick", "nonAir", "massΣ", "Δmass/1k", "Δmass/tick");

        List<int[]> snaps = new ArrayList<int[]>();
        List<Long> masses = new ArrayList<Long>();
        snap(w, snaps); masses.add(m0);
        print(0, n0, m0, m0, 0);

        for (int t = STEP; t <= TICKS; t += STEP) {
            for (int i = 0; i < STEP; i++) w.tick();
            long m = mass(w, dAir);
            int n = nonAir(w);
            snap(w, snaps); masses.add(m);
            long d = m - m0;
            print(t, n, m, d, (long) (d / (t / 1000.0)));
        }

        // ---- 质量贡献榜（各类型的质量变化，从大到小）----
        int LAST = snaps.size() - 1;
        int[] a = snaps.get(0), b = snaps.get(LAST);
        List<long[]> rank = new ArrayList<long[]>();
        for (int i = 0; i < WATCH.length; i++) {
            long dm = (long) (b[i] - a[i]) * (w.materials.density(WATCH[i]) - dAir);
            rank.add(new long[]{WATCH[i], b[i] - a[i], dm});
        }
        rank.sort((p, q) -> Long.compare(Math.abs(q[2]), Math.abs(p[2])));
        java.lang.System.out.println();
        java.lang.System.out.println("  质量贡献榜（t=0 → t=" + TICKS + "，Δ质量 = Δ格数 × (density−基线)）");
        java.lang.System.out.printf("    %-12s %10s %8s %14s%n", "type", "Δcells", "density", "Δmass");
        long sum = 0;
        for (long[] r : rank) {
            if (r[1] == 0) continue;
            sum += r[2];
            java.lang.System.out.printf("    %-12s %+10d %8d %+14d%n",
                    Blocks.byIndex((int) r[0]).id, r[1], w.materials.density((int) r[0]), r[2]);
        }
        java.lang.System.out.printf("    %-12s %10s %8s %+14d   <- 榜内合计%n", "Σ", "", "", sum);
        java.lang.System.out.println();
        java.lang.System.out.println("  总账: massΣ " + m0 + " -> " + masses.get(LAST)
                + "  (Δ=" + (masses.get(LAST) - m0) + ", " + pct(masses.get(LAST) - m0, m0) + ")   nonAir "
                + n0 + " -> " + nonAir(w));
    }

    private static void print(int t, int n, long m, long d, long perK) {
        java.lang.System.out.printf("  %7d %10d %14d %14d   %+d%n", t, n, m, perK, d);
    }

    /** Σ (density(mat) − airBaseline) over all cells。 */
    private static long mass(World w, int airBase) {
        long s = 0;
        for (int x = 0; x < w.SX; x++)
            for (int y = 0; y < w.SY; y++)
                for (int z = 0; z < w.SZ; z++)
                    s += w.materials.density(w.mat[x][y][z]) - airBase;
        return s;
    }

    private static int nonAir(World w) {
        int n = 0;
        for (int x = 0; x < w.SX; x++)
            for (int y = 0; y < w.SY; y++)
                for (int z = 0; z < w.SZ; z++)
                    if (w.mat[x][y][z] != Blocks.AIR.index) n++;
        return n;
    }

    private static void snap(World w, List<int[]> out) {
        int[] c = new int[WATCH.length];
        for (int i = 0; i < WATCH.length; i++) c[i] = w.countOf(WATCH[i]);
        out.add(c);
    }

    private static String pct(long d, long base) {
        return String.format("%+.2f%%", 100.0 * d / base);
    }
}
