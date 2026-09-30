package core.net;

import core.sim.Simulation;
import core.world.Player;
import core.world.World;

import java.io.PrintStream;
import java.io.PrintWriter;

/**
 * N3-2：联机启动入口 —— **三种形态，同一份代码**（`docs/NETPLAY_READINESS.md` §6 的落地）。
 *
 * <pre>
 *   --host     --port P [--seed S] --players N [--ticks T]   本地服务：本进程起中继线程 + 自己也是客户端
 *   --join     HOST:PORT            [--ticks T]              纯客户端
 *   --dedicated --port P [--seed S] --players N [--seconds S] 纯中继（无仿真、无窗口）
 * </pre>
 *
 * <p>三者只在"**要不要起中继** / **要不要跑仿真**"上有差别，握手/协议/输入路径完全一致。
 *
 * <p><b>用途边界（诚实清单）</b>：本入口是**无头**驱动器（无 GL 窗口），用于协议验证与
 * 跨进程门禁。把它接进 {@code render.lwjgl.Game} 的主循环（`--host` 时渲染窗口照常开，
 * tick 由会话推进）属于 N4 的"接入渲染层"，不在本层假装已完成。
 */
public final class NetMain {

    public static void main(String[] args) {
        try {
            run(args);
        } catch (Exception e) {
            log("ERROR " + e);
            System.exit(1);
        }
    }

    static PrintWriter logFile = null;

    /** 双写日志：stdout（人看）+ 文件（跨进程门禁读 —— 管道会被缓冲/销毁时机坑，文件不会）。 */
    static void log(String s) {
        System.out.println(s);
        if (logFile != null) { logFile.println(s); logFile.flush(); }
    }

    static void run(String[] args) throws Exception {
        String mode = null, joinTarget = null, outFile = null;
        int port = 27700, players = 2, ticks = 25, seconds = 60, hashEvery = 10, inputDelay = 3;
        long seed = 0x4E3750001L;
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            switch (a) {
                case "--out": outFile = args[++i]; break;
                case "--host": mode = "host"; break;
                case "--join": mode = "join"; joinTarget = args[++i]; break;
                case "--dedicated": mode = "dedicated"; break;
                case "--port": port = Integer.parseInt(args[++i]); break;
                case "--players": players = Integer.parseInt(args[++i]); break;
                case "--seed": seed = Long.parseLong(args[++i]); break;
                case "--ticks": ticks = Integer.parseInt(args[++i]); break;
                case "--seconds": seconds = Integer.parseInt(args[++i]); break;
                case "--hash-every": hashEvery = Integer.parseInt(args[++i]); break;
                case "--input-delay": inputDelay = Integer.parseInt(args[++i]); break;
                default: throw new IllegalArgumentException("未知参数 " + a);
            }
        }
        if (mode == null) throw new IllegalArgumentException("需要 --host / --join / --dedicated 之一");
        if (outFile != null) logFile = new PrintWriter(new java.io.OutputStreamWriter(
                new java.io.FileOutputStream(outFile), "UTF-8"));
        final PrintStream out = System.out;

        if ("dedicated".equals(mode)) {
            UdpRelay relay = UdpRelay.bind(port, seed, players);
            log("DEDICATED port=" + relay.localPort() + " seed=" + seed + " players=" + players);
            long deadline = System.currentTimeMillis() + seconds * 1000L;
            while (System.currentTimeMillis() < deadline && !relay.started()) {
                relay.pump(); Thread.sleep(2);
            }
            log("DEDICATED started=" + relay.started() + " hellos=" + relay.hellos
                    + " forwarded=" + relay.forwarded);
            relay.close();
            return;
        }

        if ("host".equals(mode)) {
            UdpRelay relay = UdpRelay.bind(port, seed, players);
            relay.startBackground();
            log("HOST relay port=" + relay.localPort());
            runClient("127.0.0.1", relay.localPort(), seed, players, ticks, hashEvery, inputDelay, out);
            relay.close();
            return;
        }

        // join
        int colon = joinTarget.indexOf(':');
        if (colon < 0) throw new IllegalArgumentException("--join 需要 HOST:PORT");
        runClient(joinTarget.substring(0, colon), Integer.parseInt(joinTarget.substring(colon + 1)),
                0L, players, ticks, hashEvery, inputDelay, out);
    }

    private static void runClient(String host, int port, long seedHint, int expectedPlayers,
                                  int ticks, int hashEvery, int inputDelay, PrintStream out) throws Exception {
        UdpTransport.Joined j = UdpTransport.join(host, port, 20_000);
        if (j.playerIds.length != expectedPlayers)
            throw new IllegalStateException("WELCOME 玩家数 " + j.playerIds.length + " != " + expectedPlayers);
        long seed = seedHint != 0 ? seedHint : j.seed;
        if (seed != j.seed) throw new IllegalStateException("seed 与 WELCOME 不一致");
        log("JOINED id=" + j.yourId + " players=" + java.util.Arrays.toString(j.playerIds)
                + " seed=" + seed);

        Simulation sim = LockstepRunner.newWorld(seed);
        World w = sim.world;
        LockstepSession session = LockstepRunner.open(w, j, inputDelay);

        LockstepRunner.LocalIntents src = new LockstepRunner.LocalIntents() {
            @Override public Player.Intent forTick(int tick) {
                return LockstepRunner.deterministicIntent(session.yourId(), tick);
            }
        };
        LockstepRunner.Result res = LockstepRunner.run(w, session, src, ticks, hashEvery, 500_000, out);
        // FINAL 双写到日志文件（跨进程门禁从文件读；stdout 管道会被缓冲/销毁时机坑）
        log("FINAL tick=" + res.tick + " hash=" + String.format("%016x", res.netHash)
                + " desync=" + res.desynced);
        System.exit(res.desynced ? 2 : 0);
    }
}
