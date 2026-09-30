package core.sim;

import core.net.*;
import core.world.Player;
import core.world.World;
import core.sim.Simulation;

/**
 * N5：联机会话接入渲染主循环的<b>确定性内核门禁</b>（第 46 道）。
 *
 * <p>验证三件事（性质断言，不依赖 socket）：
 * <ol>
 *   <li><b>CROSS_INSTANCE</b>：两个回环节话（id 0 / id 1，共享总线）跑满 N tick 后
 *       {@code netHash} 逐字节一致 —— 证明会话驱动路径在实例间确定性。</li>
 *   <li><b>WRAPPER_FAITHFUL</b>：会话驱动的世界演化 == 直接 {@code world.tick()}（同意图序列）演化，
 *       <b>终态</b> hash 相等且两者都恰好推进 N tick —— 证明会话是"逐字节忠实的整 tick 包装"（无多/少 tick、
 *       无相位错开）。注意：逐 tick 采样会因回环投递时序而相位错开，必须用<b>终态</b>比。</li>
 *   <li><b>TICKBODY_INJECTED</b>：注入自定义整 tick 推进体（{@code world.tick()} + 确定性副作用）
 *       后两端仍一致，且与默认推进体 hash 不同 —— 证明 N5 的 {@link TickBody} 注入真实生效且确定性。</li>
 * </ol>
 *
 * <p>本门禁不触真实网络，是 N5「把 {@code --host}/{--join} 接进 {@code render/lwjgl/Game} 主循环」
 * 的<b>逻辑前提</b>：会话既能驱动完整整 tick，又与该 tick 的规范定义逐字节等价。
 */
public class NetIntegTest {

    static final long SEED = 0x5187A9L;
    static final int SX = 96, SY = 112, SZ = 96;
    static final int N = 30;
    static final int[] PLAYER_IDS = {0, 1};

    static Player.Intent det(int pid, int t) { return LockstepRunner.deterministicIntent(pid, t); }

    static LockstepSession.InputSink sinkFor(World w) {
        return (t, arr) -> w.player.setIntent(arr[0]);   // 单数 World.player：应用最低 id 意图（与无头路径同约定）
    }

    public static void main(String[] args) {
        try {
            boolean ok = run();
            System.out.println("NETINTEG " + (ok ? "PASS" : "FAIL"));
            System.exit(ok ? 0 : 1);
        } catch (Throwable e) {
            System.out.println("NETINTEG FAIL " + e);
            e.printStackTrace();
            System.exit(1);
        }
    }

    static boolean run() throws InterruptedException {
        boolean cross = crossInstanceAndFaithful();
        boolean injected = tickBodyInjected();
        return cross && injected;
    }

    /**
     * 两回环节话跨实例收敛 + 与直接 world.tick() 演化逐字节等价（终态比对）。
     * 会话侧用「while (simTick < N)」驱动，确保<b>恰好</b>推进 N tick（回环投递时序不造成少 tick）。
     */
    static boolean crossInstanceAndFaithful() throws InterruptedException {
        LoopbackTransport[] tp = LoopbackTransport.pair(0);
        World wA = new Simulation(SEED, SX, SY, SZ).world;
        World wB = new Simulation(SEED, SX, SY, SZ).world;
        LockstepSession sA = new LockstepSession(wA, 0, PLAYER_IDS, 0, tp[0], sinkFor(wA));
        LockstepSession sB = new LockstepSession(wB, 1, PLAYER_IDS, 0, tp[1], sinkFor(wB));

        // 会话侧驱动到恰好 N tick
        long guard = 0;
        while (sA.simTick() < N) {
            sA.submitLocal((int) sA.simTick() + 1, det(0, (int) sA.simTick() + 1));
            sB.submitLocal((int) sB.simTick() + 1, det(1, (int) sB.simTick() + 1));
            sA.pump(); sB.pump();
            if (sA.canAdvance()) sA.advance();
            if (sB.canAdvance()) sB.advance();
            tp[0].wire().tick();
            if (++guard > (N + 5) * 8)
                throw new IllegalStateException("会话驱动未收敛到 N=" + N + " (simTick=" + sA.simTick() + ")");
        }
        // 收尾：消化剩余投递，确保两端都停在 N
        for (int i = 0; i < 10; i++) { sA.pump(); sB.pump(); tp[0].wire().tick(); }

        long hashA = wA.netHash();
        long hashB = wB.netHash();

        // 直接对照：同意图序列 + 默认整 tick（= world.tick），恰好 N tick
        World wD = new Simulation(SEED, SX, SY, SZ).world;
        for (int t = 1; t <= N; t++) {
            Player.Intent[] frame = new Player.Intent[]{ det(0, t), det(1, t) };
            sinkFor(wD).apply(t, frame);
            wD.tick();
        }
        long hashD = wD.netHash();

        boolean crossOk = hashA == hashB;
        boolean faithfulOk = hashA == hashD;
        // 相位纪律：两者必须恰好推进 N tick，否则比对无意义
        boolean phaseOk = (sA.simTick() == N) && (wD.tick == N);
        System.out.println("  CROSS_INSTANCE   hashA=" + hex(hashA) + " hashB=" + hex(hashB)
                + " simTick=" + sA.simTick() + " -> " + (crossOk ? "PASS" : "FAIL"));
        System.out.println("  WRAPPER_FAITHFUL hashA=" + hex(hashA) + " hashD=" + hex(hashD)
                + " wD.tick=" + wD.tick + " -> " + ((faithfulOk && phaseOk) ? "PASS" : "FAIL"));
        return crossOk && faithfulOk && phaseOk;
    }

    /** 注入自定义 TickBody 后两端仍一致，且与默认推进体 hash 不同（证明注入真实生效）。 */
    static boolean tickBodyInjected() throws InterruptedException {
        TickBody custom = w -> { w.tick(); w.prosperity++; };   // 确定性副作用：每 tick +1

        LoopbackTransport[] tp = LoopbackTransport.pair(0);
        World wA = new Simulation(SEED, SX, SY, SZ).world;
        World wB = new Simulation(SEED, SX, SY, SZ).world;
        LockstepSession sA = new LockstepSession(wA, 0, PLAYER_IDS, 0, tp[0], sinkFor(wA), custom);
        LockstepSession sB = new LockstepSession(wB, 1, PLAYER_IDS, 0, tp[1], sinkFor(wB), custom);

        long guard = 0;
        while (sA.simTick() < N) {
            sA.submitLocal((int) sA.simTick() + 1, det(0, (int) sA.simTick() + 1));
            sB.submitLocal((int) sB.simTick() + 1, det(1, (int) sB.simTick() + 1));
            sA.pump(); sB.pump();
            if (sA.canAdvance()) sA.advance();
            if (sB.canAdvance()) sB.advance();
            tp[0].wire().tick();
            if (++guard > (N + 5) * 8)
                throw new IllegalStateException("TICKBODY 驱动未收敛 (simTick=" + sA.simTick() + ")");
        }
        for (int i = 0; i < 10; i++) { sA.pump(); sB.pump(); tp[0].wire().tick(); }

        long hashA = wA.netHash();
        long hashB = wB.netHash();

        long hashDefault = defaultHash();

        boolean injectedConsistent = hashA == hashB;
        boolean differs = hashA != hashDefault;
        boolean phaseOk = sA.simTick() == N;
        System.out.println("  TICKBODY_INJECTED hashA=" + hex(hashA) + " hashB=" + hex(hashB)
                + " default=" + hex(hashDefault) + " simTick=" + sA.simTick()
                + " -> " + ((injectedConsistent && differs && phaseOk) ? "PASS" : "FAIL"));
        return injectedConsistent && differs && phaseOk;
    }

    static long defaultHash() throws InterruptedException {
        LoopbackTransport[] tp = LoopbackTransport.pair(0);
        World wA = new Simulation(SEED, SX, SY, SZ).world;
        World wB = new Simulation(SEED, SX, SY, SZ).world;
        LockstepSession sA = new LockstepSession(wA, 0, PLAYER_IDS, 0, tp[0], sinkFor(wA));
        LockstepSession sB = new LockstepSession(wB, 1, PLAYER_IDS, 0, tp[1], sinkFor(wB));
        long guard = 0;
        while (sA.simTick() < N) {
            sA.submitLocal((int) sA.simTick() + 1, det(0, (int) sA.simTick() + 1));
            sB.submitLocal((int) sB.simTick() + 1, det(1, (int) sB.simTick() + 1));
            sA.pump(); sB.pump();
            if (sA.canAdvance()) sA.advance();
            if (sB.canAdvance()) sB.advance();
            tp[0].wire().tick();
            if (++guard > (N + 5) * 8)
                throw new IllegalStateException("defaultHash 驱动未收敛 (simTick=" + sA.simTick() + ")");
        }
        for (int i = 0; i < 10; i++) { sA.pump(); sB.pump(); tp[0].wire().tick(); }
        return wA.netHash();
    }

    static String hex(long h) { return String.format("%016x", h); }
}
