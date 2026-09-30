package core.net;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.StandardProtocolFamily;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * N3-2：**UDP 中继**（`--dedicated` 的全部内容，也是 `--host` 的本地服务半边）。
 *
 * <p>职责只有两件事，**刻意不做仿真**：
 * <ol>
 *   <li><b>握手</b>：登记 {@code HELLO}；**等人齐**（{@code expectedPlayers}）后，
 *       才给每个成员发 {@code WELCOME(yourId, 全体 id 升序, seed)}。</li>
 *   <li><b>转发</b>：把任一客户端的 {@code INPUT/HASH} 转发给**其它**所有客户端。</li>
 * </ol>
 *
 * <p><b>为什么"等人齐后才发 WELCOME"</b>：{@link LockstepSession} 的 {@code playerIds}
 * 在构造时固定（且必须是**最终**名单）。若有人边玩边加入，先来的会话拿着旧名单，直接错位。
 * 所以 N3-2 的规则是：**开局前人必须齐**（进房 → 人齐 → 同时开局）；
 * 中途加入/重连属于 N4 的"重同步"，不在本层假装支持。
 *
 * <p><b>为什么中继不跑仿真</b>：锁步下**每个客户端都是权威**（确定性 + 同输入 ⇒ 同世界），
 * 不存在"服务器说了算"的单一权威。"权威进程跑仿真"属于**状态流**模式（方案 B，N5）。
 */
public final class UdpRelay implements AutoCloseable {

    private final DatagramChannel ch;
    private final long seed;
    private final int expectedPlayers;
    private final Map<SocketAddress, Integer> clients = new LinkedHashMap<>();  // addr -> playerId
    private final ByteBuffer buf = ByteBuffer.allocate(1500);
    private int nextId = 0;
    private boolean started = false;
    public long hellos = 0, forwarded = 0, rejects = 0;

    private UdpRelay(DatagramChannel ch, long seed, int expectedPlayers) {
        this.ch = ch; this.seed = seed; this.expectedPlayers = expectedPlayers;
    }

    /** 绑定端口。{@code expectedPlayers} 含所有客户端（锁步下没有"只观战"的座位）。 */
    public static UdpRelay bind(int port, long seed, int expectedPlayers) throws IOException {
        if (expectedPlayers < 1) throw new IllegalArgumentException("expectedPlayers < 1");
        DatagramChannel c = DatagramChannel.open(StandardProtocolFamily.INET);
        c.configureBlocking(false);
        c.bind(new InetSocketAddress(port));
        return new UdpRelay(c, seed, expectedPlayers);
    }

    public int localPort() throws IOException {
        InetSocketAddress a = (InetSocketAddress) ch.getLocalAddress();
        return a == null ? -1 : a.getPort();
    }

    public int clientCount() { return clients.size(); }
    /** 是否已开局（人齐且 WELCOME 已发）。 */
    public boolean started() { return started; }

    /** 全体玩家 id（加入顺序 = id 升序，从 1 开始）。 */
    public synchronized int[] playerIds() {
        int[] ids = new int[clients.size()];
        for (int i = 0; i < ids.length; i++) ids[i] = i + 1;
        return ids;
    }

    /** 非阻塞处理一轮收包。 */
    public void pump() throws IOException {
        for (;;) {
            buf.clear();
            SocketAddress from = ch.receive(buf);
            if (from == null) return;
            if (buf.position() < 1) continue;
            byte type = buf.get(0);
            byte[] msg = new byte[buf.position()];
            buf.flip(); buf.get(msg);

            if (type == UdpTransport.MSG_HELLO) {
                hellos++;
                if (clients.containsKey(from)) {
                    // 同一地址的重复 HELLO：若已开局，重发 WELCOME（客户端丢包后的恢复路径）
                    if (started) ch.send(java.nio.ByteBuffer.wrap(
                            UdpTransport.welcome(clientIdOf(from), playerIds(), seed)), from);
                    continue;
                }
                if (started) { rejects++; continue; }                 // 已开局，不再收人
                clients.put(from, ++nextId);
                if (clients.size() >= expectedPlayers) {
                    started = true;
                    for (Map.Entry<SocketAddress, Integer> e : clients.entrySet())
                        ch.send(java.nio.ByteBuffer.wrap(UdpTransport.welcome(e.getValue(), playerIds(), seed)), e.getKey());
                }
                continue;
            }
            if (!started || !clients.containsKey(from)) continue;     // 未开局 / 陌生来源 → 丢弃
            for (SocketAddress other : clients.keySet())
                if (!other.equals(from)) { ch.send(ByteBuffer.wrap(msg), other); forwarded++; }
        }
    }

    private int clientIdOf(SocketAddress a) {
        Integer id = clients.get(a);
        return id == null ? -1 : id;
    }

    @Override public void close() {
        try { ch.close(); } catch (IOException ignore) { }
    }

    /** 起一个守护线程持续 pump（`--host` 本地服务 / `--dedicated` 用）。 */
    public Thread startBackground() {
        Thread t = new Thread(new Runnable() {
            @Override public void run() {
                while (ch.isOpen()) {
                    try { pump(); Thread.sleep(1); }
                    catch (Exception ignore) { /* 关闭时自然退出 */ }
                }
            }
        }, "udp-relay");
        t.setDaemon(true);
        t.start();
        return t;
    }
}
