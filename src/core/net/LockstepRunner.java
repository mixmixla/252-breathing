package core.net;

import core.sim.Simulation;
import core.world.Player;
import core.world.World;

import java.io.PrintStream;

/**
 * N3-2：**无头锁步驱动器** —— `--host` / `--join` 两个启动形态共用的"跑完 N tick"逻辑。
 *
 * <p>它把三件事接起来：{@link Simulation}（确定性仿真）+ {@link LockstepSession}（协议）
 * + {@link Transport}（传输）。**输入来源被抽象成 {@link LocalIntents}** —— 无头测试给
 * 确定性函数，真游戏给键盘/手柄；协议层对此一无所知。
 *
 * <p>输出一条机器可读的最终行，供跨进程门禁断言：
 * <pre>FINAL tick=&lt;n&gt; hash=&lt;hex16&gt; desync=&lt;true|false&gt;</pre>
 */
public final class LockstepRunner {

    /** 本地玩家的输入来源。 */
    public interface LocalIntents { Player.Intent forTick(int tick); }

    /** 测试与 CLI 共用的确定性输入（同 (pid,tick) ⇒ 同意图）—— 跨进程一致性靠它。 */
    public static Player.Intent deterministicIntent(int pid, int tick) {
        int dx = ((tick * 7 + pid * 3) % 3) - 1;
        int dz = ((tick * 5 + pid) % 3) - 1;
        return Player.Intent.move(dx, dz);
    }

    public static final class Result {
        public final long tick;
        public final long netHash;
        public final boolean desynced;
        Result(long t, long h, boolean d) { tick = t; netHash = h; desynced = d; }
    }

    private LockstepRunner() { }

    /** 把「未来 D 个 tick 的本地输入」喂给会话（幂等）。 */
    public static void feed(LockstepSession s, int localId, LocalIntents src) {
        for (long t = s.simTick() + 1; t <= s.simTick() + 1 + s.inputDelay(); t++)
            s.submitLocal((int) t, src.forTick((int) t));
    }

    /**
     * 跑到 {@code ticks} 并返回终态。
     *
     * @param hashEvery 每多少 tick 互发一次 netHash（desync 检测；0 = 不检测）
     */
    public static Result run(World world, LockstepSession session, LocalIntents src,
                             int ticks, int hashEvery, long guardLimit, PrintStream log) throws InterruptedException {
        long lastHashTick = -1;
        long guard = 0;
        while (session.simTick() < ticks) {
            feed(session, session.yourId(), src);
            session.pump();
            if (session.canAdvance()) {
                session.advance();
                long t = session.simTick();
                if (hashEvery > 0 && t % hashEvery == 0 && t != lastHashTick) {
                    lastHashTick = t;
                    session.exchangeHash();
                }
            } else {
                Thread.sleep(1);                                     // 让出 CPU 等网络
            }
            if (++guard > guardLimit)
                throw new IllegalStateException("锁步超时：simTick=" + session.simTick() + "/" + ticks);
        }
        // 收尾：把最后几次 HASH 往返消化掉，确保 desync 判定真的发生
        for (int i = 0; i < 20; i++) { session.pump(); Thread.sleep(5); session.exchangeHash(); }
        Result r = new Result(session.simTick(), world.netHash(), session.desynced());
        if (log != null)
            log.println("FINAL tick=" + r.tick + " hash=" + String.format("%016x", r.netHash)
                    + " desync=" + r.desynced);
        return r;
    }

    /** 统一的输入应用器：两端**必须用同一个函数**，否则必然 desync。
     *  ⚠️ 已知边界：World.player 单数 → 只应用 playerIds[0] 的意图（N4 补多玩家）。 */
    public static LockstepSession.InputSink lowestIdSink(final World w) {
        return new LockstepSession.InputSink() {
            @Override public void apply(int tick, Player.Intent[] byPlayerAsc) {
                w.player.setIntent(byPlayerAsc[0]);
            }
        };
    }

    /** 组装一个已握手的会话（三种启动形态共用）。 */
    public static LockstepSession open(World world, UdpTransport.Joined j, int inputDelay) {
        return open(world, j, inputDelay, w -> w.tick());
    }

    /** N5：组装会话并注入整 tick 推进体（真游戏用含内容层的玩法层）。 */
    public static LockstepSession open(World world, UdpTransport.Joined j, int inputDelay, TickBody tickBody) {
        int[] ids = j.playerIds;
        for (int i = 1; i < ids.length; i++)
            if (ids[i] <= ids[i - 1]) throw new IllegalStateException("WELCOME 的玩家 id 非严格升序");
        return new LockstepSession(world, j.yourId, ids, inputDelay, j.transport, lowestIdSink(world), tickBody);
    }

    /** 无头世界的默认尺寸（比渲染窗口小：门禁/跨进程验证不需要 160³）。 */
    public static Simulation newWorld(long seed) {
        return new Simulation(seed, 96, 112, 96);
    }
}
