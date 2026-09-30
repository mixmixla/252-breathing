package core.sim;

import core.net.LockstepSession;
import core.net.LoopbackTransport;
import core.net.Transport;
import core.world.Player;
import core.world.World;

/**
 * NETLOCK 门禁（N3-1：**锁步会话协议层**，回环传输 —— 不需要 socket 就能测协议）。
 *
 * <p><b>测的是什么</b>：帧序 / 锁步定序（缺帧不推进）/ 输入延迟只是调度旋钮而非语义旋钮 /
 * desync 检测真的能抓到被篡改的输入。**不测** UDP/TCP（后续实现同一 {@code Transport} 即可，
 * 会话代码零改动 —— 这正是本层存在的意义）。
 *
 * <p><b>断言的性质</b>：
 * <ol>
 *   <li><b>收敛</b>：两端经回环线路（有延迟）跑 N tick，逐 checkpoint netHash 一致。</li>
 *   <li><b>输入延迟 ≠ 语义</b>：D=0 与 D=3 跑出**同一个**最终 netHash（延迟只影响"何时发"，不影响"发什么"）。</li>
 *   <li><b>缺帧不推进</b>：掐断一端的输入帧，另一端**必须**停在原地（锁步的根 —— 宁可卡住不可走偏）。</li>
 *   <li><b>补录历史被拒</b>：对已模拟 tick 补录输入必须响亮抛错（静默接受 = 篡改历史 = 悄悄走偏）。</li>
 *   <li><b>desync 可检出</b>：篡改一条输入帧的一个字节（改成**合法但不同**的意图）→ 两端都报 desync。</li>
 *   <li><b>防假绿</b>：两端真的推进到 N tick、checkpoint 真的比对过（计数 &gt; 0）。</li>
 * </ol>
 */
public final class LockstepTest {

    private static final long SEED = 0x10C4E7EL;   // "LOCKS" 谐音的合法十六进制
    private static final int[] PLAYERS = {1, 2};
    private static final int N = 40;
    private static final int CHECK_EVERY = 10;
    private static final long GUARD = 200_000;

    private static int props = 0;
    private static boolean fail = false;

    private static void ok(String name, boolean cond) {
        System.out.println("  " + (cond ? "ok  " : "FAIL") + " " + name);
        if (cond) props++; else fail = true;
    }

    /** 确定性输入：同一 (playerId, tick) 永远同一意图 —— 两端各自独立生成，结果必须一致。 */
    private static Player.Intent intentFor(int pid, int tick) {
        int dx = ((tick * 7 + pid * 3) % 3) - 1;
        int dz = ((tick * 5 + pid) % 3) - 1;
        return Player.Intent.move(dx, dz);
    }

    /** 把「本地玩家在未来 D 个 tick 的输入」喂给会话（幂等：重复喂同一 tick 同一意图无害）。 */
    private static void feed(LockstepSession s, int pid) {
        for (long t = s.simTick() + 1; t <= s.simTick() + 1 + s.inputDelay(); t++)
            s.submitLocal((int) t, intentFor(pid, (int) t));
    }

    /** 应用器：把玩家 1（playerIds[0]）的意图写进世界。两端一致 —— 这是 N4 多玩家 World 的占位。 */
    private static LockstepSession.InputSink sinkOf(final World w) {
        return new LockstepSession.InputSink() {
            @Override public void apply(int tick, Player.Intent[] byPlayerAsc) {
                w.player.setIntent(byPlayerAsc[0]);
            }
        };
    }

    /** 一对会话 + 归属的线路（测试驱动需要 tick 那条线）。 */
    private static final class Pair {
        final LockstepSession a, b;
        final LoopbackTransport wireOwner;
        Pair(LockstepSession a, LockstepSession b, LoopbackTransport w) { this.a = a; this.b = b; this.wireOwner = w; }
    }

    private static Pair make(int inputDelay, long latency, Transport replaceB) {
        LoopbackTransport[] pair = LoopbackTransport.pair(latency);
        World wa = new Simulation(SEED, 96, 112, 96).world;
        World wb = new Simulation(SEED, 96, 112, 96).world;
        LockstepSession a = new LockstepSession(wa, PLAYERS[0], PLAYERS, inputDelay, pair[0], sinkOf(wa));
        LockstepSession b = new LockstepSession(wb, PLAYERS[1], PLAYERS, inputDelay,
                replaceB != null ? replaceB : pair[1], sinkOf(wb));
        return new Pair(a, b, pair[0]);
    }

    /** 把两端推进到 {@code target} tick；返回是否在守卫内完成。 */
    private static boolean driveTo(Pair p, long target) {
        long guard = 0;
        while (p.a.simTick() < target || p.b.simTick() < target) {
            feed(p.a, PLAYERS[0]);
            feed(p.b, PLAYERS[1]);
            p.wireOwner.wire().tick();
            p.a.pump();
            p.b.pump();
            if (p.a.canAdvance() && p.b.canAdvance()) { p.a.advance(); p.b.advance(); }
            if (++guard > GUARD) return false;
        }
        return true;
    }

    /** 在 checkpoint 上互发并比对 netHash（补几轮网络回合，保证哈希包真的送达）。 */
    private static boolean settle(LockstepSession a, LockstepSession b, LoopbackTransport w, int latency) {
        a.exchangeHash();
        b.exchangeHash();                                        // 双方发出
        for (int i = 0; i < latency + 2; i++) { w.wire().tick(); a.pump(); b.pump(); }
        boolean okA = a.exchangeHash();
        boolean okB = b.exchangeHash();                          // 此刻应已收到对端 → 判定
        return okA && okB;
    }

    public static void main(String[] args) throws Exception {
        // ---------- ① 收敛（有延迟） ----------
        Pair p1 = make(3, 2, null);
        int checkpoints = 0;
        boolean converged = true;
        for (int target = 1; target <= N; target++) {
            if (!driveTo(p1, target)) { converged = false; break; }
            if (target % CHECK_EVERY == 0) {
                if (!settle(p1.a, p1.b, p1.wireOwner, 2)) { converged = false; break; }
                checkpoints++;
            }
        }
        ok("CONVERGENCE_WITH_LATENCY", converged && checkpoints > 0
                && p1.a.simTick() == N && p1.b.simTick() == N && !p1.a.desynced() && !p1.b.desynced());
        long hA = p1.a.worldRef().netHash(), hB = p1.b.worldRef().netHash();

        // ---------- ② 输入延迟 ≠ 语义（D=0 与 D=3 同终态） ----------
        Pair p2 = make(0, 2, null);
        boolean conv0 = true;
        for (int target = 1; target <= N && conv0; target++)
            if (!driveTo(p2, target)) conv0 = false;
        ok("INPUT_DELAY_IS_SCHEDULING_ONLY", conv0
                && p2.a.worldRef().netHash() == hA && p2.b.worldRef().netHash() == hB);

        // ---------- ③ 缺帧不推进 ----------
        // B 的传输是"孤儿线路"（收不到 A 的任何帧）→ A 永远等不到玩家 2 的输入
        LockstepSession orphanA = make(3, 1, orphanB()).a;
        boolean stuck = true;
        for (int i = 0; i < 60 && stuck; i++) {
            feed(orphanA, PLAYERS[0]);
            orphanA.pump();
            if (orphanA.canAdvance()) stuck = false;
        }
        ok("MISSING_FRAME_BLOCKS", stuck && orphanA.simTick() == 0);

        // ---------- ④ 补录历史被拒 ----------
        boolean rejected = false;
        try { p1.a.submitLocal(0, Player.Intent.idle()); }
        catch (IllegalArgumentException e) { rejected = true; }
        ok("STALE_INPUT_REJECTED", rejected && p1.a.simTick() > 0);

        // ---------- ⑤ desync 可检出（篡改一个字节 → 合法但不同的意图） ----------
        // ⚠️ 故障注入必须包在**真实对端**上 —— 包在一个孤儿线路上，B 就什么都收不到，
        //    那测的是"缺帧"而不是"篡改"（实测第一版就犯了这个错）。
        LoopbackTransport[] pair3 = LoopbackTransport.pair(2);
        final boolean[] armed = {true};
        // 包装 **pair3[1] 本身**（B 的真实对端）：send 原样转发，recv 时注入故障。
        // ⚠️ 第一版误包 pair3[0]（A 的发送队列）→ B 的消息进了自己的收件箱，B 什么都收不到，
        //    测的其实是"缺帧"而不是"篡改"——门禁以 FAIL 的方式把这个错误指了出来。
        Transport corrupting = new Transport() {
            @Override public void send(byte[] m) { pair3[1].send(m); }
            @Override public byte[] recv() {
                byte[] m = pair3[1].recv();
                // 消息布局：[type u8][tick i32][count u16][pid u16][itype u8][dx i8]...
                // dx 在消息内偏移 10 —— 它是 i8，任意值都合法 → 必然产生「合法但不同」的输入而非解析错
                if (m != null && armed[0] && m.length > 10 && m[0] == LockstepSession.MSG_INPUT) {
                    armed[0] = false;
                    m[10] ^= 0x01;
                }
                return m;
            }
            @Override public void close() { }
        };
        World wa3 = new Simulation(SEED, 96, 112, 96).world;
        World wb3 = new Simulation(SEED, 96, 112, 96).world;
        LockstepSession ba = new LockstepSession(wa3, PLAYERS[0], PLAYERS, 3, pair3[0], sinkOf(wa3));
        LockstepSession bb = new LockstepSession(wb3, PLAYERS[1], PLAYERS, 3, corrupting, sinkOf(wb3));
        Pair bad = new Pair(ba, bb, pair3[0]);
        boolean desync = false;
        for (int target = 1; target <= N && !desync; target++) {
            if (!driveTo(bad, target)) break;
            if (target % CHECK_EVERY == 0) {
                settle(bad.a, bad.b, bad.wireOwner, 2);
                if (bad.a.desynced() || bad.b.desynced()) desync = true;
            }
        }
        ok("DESYNC_DETECTED", desync && bad.a.desynced() && bad.b.desynced());

        System.out.println("  info  players=" + PLAYERS.length + " N=" + N
                + " checkpoints=" + checkpoints + " inputDelay=3 latency=2rounds");
        System.out.println("  info  netHash@N = " + String.format("%016x", hA)
                + " / " + String.format("%016x", hB) + (hA == hB ? "  (一致)" : "  (不一致!)"));
        System.out.println("  info  边界：World.player 单数，多玩家意图的应用由 InputSink 注入（N4 补）");
        System.out.println("  info  边界：本层不测 UDP/TCP —— 后续实现同一 Transport 即可，会话零改动");

        System.out.println((fail ? "NETLOCK FAIL (" : "NETLOCK PASS (") + props + " properties)");
        if (fail) System.exit(1);
    }

    /** 造一个"永远收不到对端"的传输（缺帧测试用）。 */
    private static Transport orphanB() {
        return LoopbackTransport.pair(1)[1];   // 只取对端一侧，另一侧没人发 → 永远收不到
    }
}
