package core.sim;

import core.world.World;
import core.world.Player;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

/**
 * NETSNAP 门禁（N2-1：**紧致快照**）。
 *
 * <p><b>为什么需要它</b>：N2 的原方案「复用 {@code save/load} 当内存快照」被实测三刀砍死
 * （22 MB / 实体层缺失 / 继续演化分叉）。修完完备性（N2-0）后仍剩 22 MB，根因是
 * {@code save()} 把每个被编辑块**整块**落盘（16×112×16×(int+float) = 229 KB/块）。
 *
 * <p><b>本门禁断言的性质</b>（不是"能跑"，而是"紧凑且等价"）：
 * <ol>
 *   <li><b>字节预算</b>：真实 92 系统世界 60 tick 后，快照 ≤ {@link #BUDGET_BYTES}。</li>
 *   <li><b>压缩比</b>：相对"整块窗口落盘"（21.9 MB 量级）≥ {@link #MIN_RATIO}x。</li>
 *   <li><b>稀疏性</b>：窗口差分格数占窗口比例 ≤ 5%（证明"稀疏"是真的，不是把全窗当差异）。</li>
 *   <li><b>结构等价</b>：{@code 原始基线 + 差分} 逐格复原原世界（用 {@code diffAgainst} 自校验为空）。</li>
 *   <li><b>双一致</b>：save→load→再演化 120 tick，hash 逐字节一致（真·验收判据）。</li>
 *   <li><b>防假绿</b>：差分格数 &gt; 0、涉及块数 &gt; 0、窗口被编辑块数 &gt; 0 —— 防扫描器走空。</li>
 * </ol>
 */
public final class NetSnapTest {

    private static final long SEED = 0x5EEDC0DEL;
    private static final int TICKS = 60;

    /** 快照字节预算（实测 ~575 KB；给 1 MB 余量，任何"整块落盘回归"都会立刻破线）。 */
    private static final int BUDGET_BYTES = 1_048_576;
    /** 相对整块窗口落盘的最小压缩比。 */
    private static final double MIN_RATIO = 8.0;
    /** 窗口差分格数占比上限。 */
    private static final double MAX_DIFF_FRACTION = 0.05;

    private static int props = 0;
    private static boolean fail = false;

    private static void ok(String name, boolean cond) {
        System.out.println("  " + (cond ? "ok  " : "FAIL") + " " + name);
        if (cond) props++; else fail = true;
    }

    public static void main(String[] args) throws Exception {
        Simulation sim = new Simulation(SEED, 160, 112, 160);
        for (int t = 0; t < TICKS; t++) {
            if (t % 5 == 0) sim.world.player.setIntent(Player.Intent.move(1, 0));
            sim.world.tick();
        }
        World w = sim.world;

        // P0（第三十六批）后「窗口内被编辑块」只统计**玩家建造**（系统写入不再标记）
        // ⇒ 本测试显式放一块（editBlock 一定会标记），否则防假绿断言 `windowEditedChunkCount() > 0`
        //    会因为"这个测试里玩家只走路、不建造"而误红。
        w.editBlock(w.SX / 2, 1, w.SZ / 2, core.world.Blocks.STONE.index);

        // ---- 1) 快照 ----
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        w.save(bo);
        byte[] snap = bo.toByteArray();

        long fullChunkBytes = (long) w.CX * w.CZ * World.CHUNK * w.SY * World.CHUNK * 8L;
        double ratio = fullChunkBytes / (double) snap.length;

        ok("BUDGET_BYTES", snap.length <= BUDGET_BYTES);
        ok("COMPRESSION_RATIO", ratio >= MIN_RATIO);

        // ---- 2) 稀疏性 ----
        java.util.List<core.net.ChunkDiff> diffs = w.captureWindowDiffs();
        long diffCells = 0;
        for (core.net.ChunkDiff d : diffs) diffCells += d.n;
        double frac = diffCells / (double) ((long) w.SX * w.SY * w.SZ);

        ok("DIFF_IS_SPARSE", frac <= MAX_DIFF_FRACTION && frac > 0.0);
        ok("NOTHING_EMPTY", diffCells > 0 && diffs.size() > 0 && w.windowEditedChunkCount() > 0);

        // ---- 3) 结构等价：原始基线 + 差分 == 原世界 ----
        World baseline = new World(SEED, w.SX, w.SY, w.SZ);
        baseline.applyWindowDiffs(diffs);
        boolean exact = w.diffAgainst(baseline).isEmpty();
        ok("BASELINE_PLUS_DIFF_EXACT", exact);

        // ---- 4) 双一致（真·验收判据）----
        World loaded = World.load(new ByteArrayInputStream(snap));
        boolean loadEq = loaded.hashState() == w.hashState();
        Simulation lsim = new Simulation(loaded);
        for (int i = 0; i < 120; i++) {
            if (i % 5 == 0) {
                w.player.setIntent(Player.Intent.move(1, 0));
                loaded.player.setIntent(Player.Intent.move(1, 0));
            }
            w.tick();
            loaded.tick();
        }
        boolean contEq = loaded.hashState() == w.hashState();
        ok("HASH_EQ_ON_LOAD", loadEq);
        ok("HASH_EQ_AFTER_120", contEq);

        System.out.println("  info  snapshot      = " + snap.length + " B ("
                + String.format("%.0f", snap.length / 1024.0) + " KB)   预算 "
                + BUDGET_BYTES + " B");
        System.out.println("  info  fullChunkRef  = " + fullChunkBytes + " B ("
                + (fullChunkBytes / 1048576L) + " MB)   压缩比 " + String.format("%.1fx", ratio));
        System.out.println("  info  windowDiff    = " + diffs.size() + "/" + (w.CX * w.CZ)
                + " 块, " + diffCells + " 格 (" + String.format("%.4f", frac * 100.0) + "%)");
        System.out.println("  info  systems       = " + w.systemCount()
                + "  windowEditedChunks=" + w.windowEditedChunkCount());

        System.out.println((fail ? "NETSNAP FAIL (" : "NETSNAP PASS (") + props + " properties)");
        if (fail) System.exit(1);
    }
}
