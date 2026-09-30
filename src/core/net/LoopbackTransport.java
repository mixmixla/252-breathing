package core.net;

import java.util.ArrayDeque;

/**
 * N3：回环传输 —— 一对**交叉**队列（A.send → B.recv；B.send → A.recv）+ 可调延迟。
 *
 * <p>用途：**不需要 socket 就能测协议层**（帧序 / 锁步定序 / desync 检测）。
 * 真正的 UDP 传输（N3 后续）实现同一个 {@link Transport} 即可，会话代码零改动。
 *
 * <p><b>延迟模型</b>：消息带上「投递时钟」信封进队列；{@link LoopbackWire#tick()} 每推进一步
 * 「网络回合」时钟，{@code recv()} 只取 {@code deliverAt <= clock} 的最旧一条。
 * 延迟单位 = 网络回合，与仿真 tick 无关 —— 两者的换算由调用方决定。
 */
public final class LoopbackTransport implements Transport {

    /** 一对会话共享的线路：时钟 + 两个方向的队列。 */
    public static final class Wire {
        long clock = 0;
        private final ArrayDeque<Env> aToB = new ArrayDeque<>();
        private final ArrayDeque<Env> bToA = new ArrayDeque<>();

        /** 推进一步网络回合（两侧的 recv 可见性同时前进）。 */
        public void tick() { clock++; }

        public long clock() { return clock; }
    }

    private static final class Env {
        final byte[] msg;
        final long deliverAt;
        Env(byte[] m, long t) { msg = m; deliverAt = t; }
    }

    /** 单方向队列容量上限（模拟拥塞；超限**响亮抛错**而非静默丢弃）。 */
    public static final int MAX_INFLIGHT = 4096;

    private final Wire wire;
    private final ArrayDeque<Env> out;
    private final ArrayDeque<Env> in;
    private final long latency;

    private LoopbackTransport(Wire w, ArrayDeque<Env> out, ArrayDeque<Env> in, long latency) {
        this.wire = w; this.out = out; this.in = in; this.latency = latency;
    }

    /** 造一对交叉的回环传输（返回 {A, B}），延迟 = {@code latencyRounds} 个网络回合。 */
    public static LoopbackTransport[] pair(long latencyRounds) {
        if (latencyRounds < 0) throw new IllegalArgumentException("latencyRounds < 0");
        Wire w = new Wire();
        return new LoopbackTransport[] {
                new LoopbackTransport(w, w.aToB, w.bToA, latencyRounds),
                new LoopbackTransport(w, w.bToA, w.aToB, latencyRounds),
        };
    }

    public Wire wire() { return wire; }

    @Override public void send(byte[] msg) {
        if (msg == null || msg.length == 0)
            throw new IllegalArgumentException("空消息（静默丢包会把上层 bug 变成莫名卡顿）");
        if (out.size() >= MAX_INFLIGHT)
            throw new IllegalStateException("回环线路拥塞：inflight=" + out.size());
        out.addLast(new Env(msg, wire.clock + latency));
    }

    @Override public byte[] recv() {
        Env e = in.peekFirst();
        if (e == null || e.deliverAt > wire.clock) return null;   // 非阻塞：未到投递时刻
        in.pollFirst();
        return e.msg;
    }

    @Override public void close() { /* 回环：无资源 */ }
}
