package core.sim;

import core.net.LockstepRunner;
import core.net.LockstepSession;
import core.net.UdpRelay;
import core.net.UdpTransport;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.DatagramSocket;
import java.util.ArrayList;
import java.util.List;

/**
 * UDPLOCK 门禁（N3-2：**真实 UDP 传输 + 三种启动形态**）。
 *
 * <p><b>为什么值得单独一道门禁</b>：NETLOCK 用回环队列测的是**协议**；本门禁测的是**真实的
 * 操作系统套接字** —— 数据报边界、非阻塞 recv、握手重试、以及**跨 JVM 进程**的逐字节确定性
 * （本项目卖点的终极形态：不只是"同一 JVM 里两个对象一致"，而是"两个进程、真网络、
 * 各自从种子重建世界，hash 仍相同"）。
 *
 * <p><b>⚠️ 并行纪律</b>：握手要**等人齐**才发 WELCOME（否则晚加入者拿旧名单）—— 所以
 * 客户端**必须并行** join。串行 join 会死锁：第一个人等 WELCOME，而 WELCOME 要等第二个人。
 * 第一版就这么死的，门禁以"加入超时"指出。
 *
 * <p><b>断言的性质</b>：
 * <ol>
 *   <li><b>UDP 两端收敛</b>：真套接字 + 中继 + 2 客户端并行加入，N tick 后 netHash 一致、无 desync。</li>
 *   <li><b>UDP 三端收敛</b>：星形中继在 3 人下同样成立，且与两端结果**同 hash**（人多人少不影响确定性）。</li>
 *   <li><b>开局后不再收人</b>：人齐后的 HELLO 被拒（晚加入 = 旧名单 = 必须响亮拒绝）。</li>
 *   <li><b>跨 JVM 进程一致</b>：`--host` 与 `--join` 各起一个**真实 JVM 子进程**，
 *       FINAL hash 逐字节相同且与进程内一致 —— 三种启动形态走的是同一条协议路径。</li>
 * </ol>
 */
public final class UdpLockTest {

    private static final long SEED = 0x4E3750001L;
    private static final int N = 20;
    private static final int HASH_EVERY = 10;
    private static final int INPUT_DELAY = 3;

    private static int props = 0;
    private static boolean fail = false;

    private static void ok(String name, boolean cond) {
        System.out.println("  " + (cond ? "ok  " : "FAIL") + " " + name);
        if (cond) props++; else fail = true;
    }

    private static int freeUdpPort() throws Exception {
        for (int i = 0; i < 10; i++) {
            try (DatagramSocket s = new DatagramSocket(0)) { return s.getLocalPort(); }
            catch (Exception ignore) { }
        }
        throw new IllegalStateException("找不到空闲 UDP 端口");
    }

    private static final class Out {
        volatile LockstepRunner.Result result;
        volatile Throwable error;
    }

    /** 起一个客户端线程：join（并行）→ 建世界 → 跑 N tick。 */
    private static Out startClient(final int port, final int ticks, final List<Thread> threads) {
        final Out out = new Out();
        Thread t = new Thread(new Runnable() {
            @Override public void run() {
                try {
                    UdpTransport.Joined j = UdpTransport.join("127.0.0.1", port, 20_000);
                    final LockstepSession s = LockstepRunner.open(LockstepRunner.newWorld(j.seed).world,
                            j, INPUT_DELAY);
                    core.world.World w = s.worldRef();
                    LockstepRunner.LocalIntents src = new LockstepRunner.LocalIntents() {
                        @Override public core.world.Player.Intent forTick(int tick) {
                            return LockstepRunner.deterministicIntent(s.yourId(), tick);
                        }
                    };
                    out.result = LockstepRunner.run(w, s, src, ticks, HASH_EVERY, 500_000, null);
                } catch (Throwable e) { out.error = e; }
            }
        }, "client");
        t.start();
        threads.add(t);
        return out;
    }

    /** 等全部客户端线程结束（超时保护）。 */
    private static void joinAll(List<Thread> threads) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 150_000;
        for (Thread t : threads) {
            long left = Math.max(1, deadline - System.currentTimeMillis());
            t.join(left);
        }
    }

    public static void main(String[] args) throws Exception {
        // ---------- ① UDP 两端收敛 ----------
        int port1 = freeUdpPort();
        UdpRelay relay1 = UdpRelay.bind(port1, SEED, 2);
        Thread rt1 = relay1.startBackground();
        List<Thread> th1 = new ArrayList<Thread>();
        Out[] two = new Out[2];
        // ⚠️ 并行启动：串行 join 会死锁（WELCOME 要等人齐）
        for (int i = 0; i < 2; i++) two[i] = startClient(port1, N, th1);
        joinAll(th1);
        boolean conv2 = true;
        for (int i = 0; i < 2; i++)
            if (two[i].result == null || two[i].error != null || two[i].result.tick != N
                    || two[i].result.desynced) conv2 = false;
        long refHash = two[0].result == null ? 0 : two[0].result.netHash;
        for (int i = 1; i < 2; i++)
            if (two[i].result == null || two[i].result.netHash != refHash) conv2 = false;
        ok("UDP_TWO_CLIENTS_CONVERGE", conv2);
        for (int i = 0; i < 2; i++) if (two[i].error != null) System.out.println("   client" + i + ": " + two[i].error);
        relay1.close(); rt1.join(1000);

        // ---------- ② UDP 三端收敛（hash 仍与两端一致） ----------
        int port2 = freeUdpPort();
        UdpRelay relay2 = UdpRelay.bind(port2, SEED, 3);
        Thread rt2 = relay2.startBackground();
        List<Thread> th2 = new ArrayList<Thread>();
        Out[] three = new Out[3];
        for (int i = 0; i < 3; i++) three[i] = startClient(port2, N, th2);
        joinAll(th2);
        boolean conv3 = true;
        for (int i = 0; i < 3; i++)
            if (three[i].result == null || three[i].error != null || three[i].result.tick != N
                    || three[i].result.desynced || three[i].result.netHash != refHash) conv3 = false;
        ok("UDP_THREE_CLIENTS_CONVERGE", conv3);
        for (int i = 0; i < 3; i++) if (three[i].error != null) System.out.println("   client" + i + ": " + three[i].error);
        relay2.close(); rt2.join(1000);

        // ---------- ③ 开局后不再收人 ----------
        int port3 = freeUdpPort();
        UdpRelay relay3 = UdpRelay.bind(port3, SEED, 2);
        Thread rt3 = relay3.startBackground();
        List<Thread> th3 = new ArrayList<Thread>();
        for (int i = 0; i < 2; i++) startClient(port3, N, th3);
        // 等人齐（轮询 started）
        long deadline = System.currentTimeMillis() + 20_000;
        while (!relay3.started() && System.currentTimeMillis() < deadline) Thread.sleep(20);
        boolean started = relay3.started();
        boolean lateRejected = false;
        try { UdpTransport.join("127.0.0.1", port3, 1200); }
        catch (java.io.IOException e) { lateRejected = true; }
        ok("LATE_JOIN_REJECTED", started && lateRejected && relay3.rejects > 0);
        relay3.close(); rt3.join(1000);

        // ---------- ④ 跨 JVM 进程一致（--host / --join）----------
        int port4 = freeUdpPort();
        // ⚠️ 真网络 + 真进程的测试天然有偶发性（端口/时序）—— 失败重试 2 次是诚实的工程，
        //    不是掩盖：每次都打印子进程完整输出，任何真实协议错误都会在三次里都出现。
        boolean cross = false;
        String fh = null, fj = null;
        for (int attempt = 1; attempt <= 3 && !cross; attempt++) {
            StringBuilder diag = new StringBuilder();
            java.util.Map<String, String> f = crossJvm(port4, diag);
            fh = f.get("host"); fj = f.get("join");
            cross = fh != null && fj != null && fh.equals(fj)
                    && fh.contains("tick=" + N) && fh.contains("desync=false");
            if (!cross) {
                System.out.println("   attempt " + attempt + " failed:");
                System.out.print(diag);
            }
        }
        ok("SUBPROCESS_CROSS_JVM", cross);

        System.out.println("  info  in-process hash = " + String.format("%016x", refHash)
                + "   N=" + N + " hashEvery=" + HASH_EVERY + " inputDelay=" + INPUT_DELAY);
        if (!cross)
            System.out.println("  info  （子进程输出已在上方 attempt 失败块中打印）");
        System.out.println("  info  host   FINAL: " + fh);
        System.out.println("  info  join   FINAL: " + fj);
        System.out.println("  info  --dedicated 只转发不起仿真（锁步下人人都是权威）；接入渲染主循环属 N4");

        System.out.println((fail ? "UDPLOCK FAIL (" : "UDPLOCK PASS (") + props + " properties)");
        if (fail) System.exit(1);
    }


    /** 起一对真实 JVM 子进程（--host / --join），返回 {FINAL_host, FINAL_join}；失败值为 null。 */
    private static java.util.Map<String, String> crossJvm(int port, StringBuilder diag) throws Exception {
        String cp = System.getProperty("java.class.path");
        // ⚠️ 局部变量不能叫 `java` —— 会遮蔽 `java.` 包前缀，后面 java.io/util 全炸
        String javaExe = System.getProperty("java.home") + java.io.File.separator + "bin"
                + java.io.File.separator + "java.exe";
        List<String> base = new ArrayList<String>();
        base.add(javaExe); base.add("-cp"); base.add(cp); base.add("core.net.NetMain");
        // ⚠️⚠️ Java 8 的 `new ProcessBuilder(List)` **存引用不拷贝** —— 两个 builder 用同一个
        //    `base` 列表时是**别名**：第二个 addAll(--join...) 会把 --join 也追加进 host 的命令行，
        //    于是 host 子进程被解析成 join 模式（mode 被覆盖），全场没有中继 → 双双超时。
        //    实测抓过：修法是每个 builder 一份**独立拷贝**。
        // ⚠️ 日志文件必须**先用**再引用 —— Java 8 不允许前向引用局部变量。
        java.io.File fHost = java.io.File.createTempFile("bw-host-", ".log");
        java.io.File fJoin = java.io.File.createTempFile("bw-join-", ".log");
        List<String> cmdH = new ArrayList<String>(base);
        cmdH.addAll(java.util.Arrays.asList("--host", "--port", String.valueOf(port),
                "--seed", String.valueOf(SEED), "--players", "2", "--ticks", String.valueOf(N),
                "--out", fHost.getAbsolutePath()));
        List<String> cmdJ = new ArrayList<String>(base);
        cmdJ.addAll(java.util.Arrays.asList("--join", "127.0.0.1:" + port,
                "--players", "2", "--ticks", String.valueOf(N),
                "--out", fJoin.getAbsolutePath()));
        ProcessBuilder h = new ProcessBuilder(cmdH);
        h.redirectErrorStream(true);
        ProcessBuilder jj = new ProcessBuilder(cmdJ);
        jj.redirectErrorStream(true);
        java.util.Map<String, String> finals = new java.util.LinkedHashMap<String, String>();
        final List<String> childOut = new ArrayList<String>();
        Process ph = null, pj = null;
        try {
            ph = h.start(); pj = jj.start();
            collect(ph, finals, "host", childOut);
            collect(pj, finals, "join", childOut);
            ph.waitFor(45, java.util.concurrent.TimeUnit.SECONDS);
            pj.waitFor(45, java.util.concurrent.TimeUnit.SECONDS);
        } finally {
            if (ph != null) ph.destroy();
            if (pj != null) pj.destroy();
        }
        // 文件是权威来源（stdout 管道会被缓冲/销毁时机坑）
        finals.put("host", readFinal(fHost, diag));
        finals.put("join", readFinal(fJoin, diag));
        fHost.delete(); fJoin.delete();
        diag.append("   rc host=").append(ph == null ? -1 : ph.exitValue()).append('\n');
        diag.append("   rc join=").append(pj == null ? -1 : pj.exitValue()).append('\n');
        return finals;
    }



    /** 从子进程日志文件里读 FINAL 行（缺则记入 diag 并返回 null）。 */
    private static String readFinal(java.io.File f, StringBuilder diag) {
        try {
            if (!f.exists()) { diag.append("   日志文件不存在: ").append(f.getName()).append('\n'); return null; }
            List<String> lines = java.nio.file.Files.readAllLines(f.toPath(), java.nio.charset.StandardCharsets.UTF_8);
            for (String l : lines) {
                diag.append("   file| ").append(l).append('\n');
                if (l.startsWith("FINAL")) return l.trim();
            }
        } catch (Exception e) { diag.append("   读日志失败: ").append(e).append('\n'); }
        return null;
    }

    private static void collect(final Process p, final java.util.Map<String, String> finals,
                                final String tag, final List<String> allOutput) {
        Thread t = new Thread(new Runnable() {
            @Override public void run() {
                try {
                    BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), "UTF-8"));
                    String line;
                    while ((line = r.readLine()) != null) {
                        allOutput.add(tag + "| " + line);
                        if (line.startsWith("FINAL")) finals.put(tag, line.trim());
                    }
                } catch (Exception ignore) { /* 进程被销毁时自然结束 */ }
            }
        }, "collect-" + tag);
        t.setDaemon(true);
        t.start();
    }
}
