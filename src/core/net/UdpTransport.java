package core.net;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.StandardProtocolFamily;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.util.ArrayList;
import java.util.List;

/**
 * N3-2：**真实 UDP 传输**（客户端侧）+ 握手。
 *
 * <p>拓扑：**星形**。所有客户端把消息发给中继（{@link UdpRelay}），中继转发给其它客户端。
 * 选星形而非全互联：省 N² 打洞、服务端天然知道"谁在线"（锁步必须知道全员到齐才能推进）。
 *
 * <p><b>握手</b>（加入时一次性）：
 * <pre>
 *   客户端 → 中继 : HELLO  { protoVer u8 }
 *   中继 → 客户端 : WELCOME { protoVer u8 | yourId u16 | playerCount u8 | ids u16* | seed i64 }
 * </pre>
 * 客户端拿到自己的 id、全体玩家 id（**严格升序**，{@link InputFrame} 契约）与种子 ——
 * 之后会话所需的全部静态信息都在这里，**世界不需要传**（地形由种子确定性生成）。
 *
 * <p><b>非阻塞</b>：用 NIO {@link DatagramChannel}（{@code configureBlocking(false)}），
 * {@link #recv()} 无包返回 {@code null} —— 会话在主循环轮询，绝不阻塞仿真线程。
 */
public final class UdpTransport implements Transport {

    public static final byte MSG_HELLO = 10;
    public static final byte MSG_WELCOME = 11;
    public static final int PROTO_VERSION = 1;

    private final DatagramChannel ch;
    private final InetSocketAddress server;

    private UdpTransport(DatagramChannel ch, InetSocketAddress server) {
        this.ch = ch; this.server = server;
    }

    // ---------------------------------------------------------------- 握手

    /** 加入结果：传输 + 会话所需的全部静态配置。 */
    public static final class Joined {
        public final UdpTransport transport;
        public final int yourId;
        public final int[] playerIds;   // 严格升序
        public final long seed;

        Joined(UdpTransport t, int yourId, int[] ids, long seed) {
            this.transport = t; this.yourId = yourId; this.playerIds = ids; this.seed = seed;
        }
    }

    /**
     * 加入一场对局：发 HELLO 并等待 WELCOME。
     *
     * @param timeoutMs 等待握手的总时长（中继可能还没起来，客户端要**重试**而不是一击即溃）
     */
    public static Joined join(String host, int port, long timeoutMs) throws IOException {
        // ⚠️ channel 必须在重试循环**外**创建：每次重试换一个本地端口，中继按**地址**去重，
        //    会把同一个客户端当成多个玩家（实测：host 拿到 id=2，第一次 HELLO 成了幽灵 id=1，
        //    人齐开局后真正的 join 被拒 → 永远超时）。
        DatagramChannel ch = DatagramChannel.open(StandardProtocolFamily.INET);
        ch.configureBlocking(false);
        ch.bind(null);                                       // 本地临时端口（整个 join 期间不变）
        InetSocketAddress server = new InetSocketAddress(host, port);
        long deadline = System.currentTimeMillis() + timeoutMs;
        long nextHello = 0;
        try {
            while (System.currentTimeMillis() < deadline) {
                long now = System.currentTimeMillis();
                if (now >= nextHello) {                       // 周期性重发 HELLO（丢包/重试都靠它）
                    ch.send(ByteBuffer.wrap(hello()), server);
                    nextHello = now + 250;
                }
                ByteBuffer buf = ByteBuffer.allocate(1024);
                SocketAddress from = ch.receive(buf);
                if (from != null) {
                    Joined j = parseWelcome(buf);
                    if (j != null) {
                        // 握手用无连接的 send/receive；会话用 write/read —— 后者要求通道已 connect
                        ch.connect(server);
                        return new Joined(new UdpTransport(ch, server), j.yourId, j.playerIds, j.seed);
                    }
                }
                try { Thread.sleep(5); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
        } finally {
            // 只有失败才关（成功时通道要继续用）
            if (!ch.isOpen() || ch.isConnected()) { /* no-op */ }
        }
        ch.close();
        throw new IOException("加入超时（" + timeoutMs + "ms）：" + host + ":" + port);
    }

    private static byte[] hello() throws IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream(2);
        DataOutputStream d = new DataOutputStream(bo);
        d.writeByte(MSG_HELLO); d.writeByte(PROTO_VERSION); d.flush();
        return bo.toByteArray();
    }

    /**
     * 解析一条 WELCOME；不是合法的 WELCOME 返回 null。
     *
     * <p><b>⚠️ 第一版的 bug</b>：先用 {@code position(0)} 探测类型，成功后**没有把读指针挪到
     * 消息头之后**，于是 {@code yourId} 读到的是 [MSG_WELCOME, PROTO_VERSION] 两个字节，
     * 后面全部错位 → 玩家数解析成 0 → 会话构造时报「playerIds 为空」。
     * 教训：**探测和解析必须一次完成**（flip 后顺序读），不要"探完再读"。
     */
    private static Joined parseWelcome(ByteBuffer buf) {
        if (buf.position() < 2) return null;
        buf.flip();                                              // limit=已读长度, position=0
        try {
            if (buf.remaining() < 13) return null;               // 最短：2 头 + 2 id + 1 count + 8 seed
            if (buf.get() != MSG_WELCOME || buf.get() != PROTO_VERSION) return null;
            int yourId = buf.getShort() & 0xFFFF;
            int n = buf.get() & 0xFF;
            if (n < 1 || buf.remaining() != 2 * n + 8) return null;   // 长度必须恰好对上（畸形包响亮拒绝）
            int[] ids = new int[n];
            for (int i = 0; i < n; i++) {
                ids[i] = buf.getShort() & 0xFFFF;
                if (i > 0 && ids[i] <= ids[i - 1]) return null;   // 升序契约
            }
            long seed = buf.getLong();
            return new Joined(null, yourId, ids, seed);
        } finally {
            buf.clear();
        }
    }

    /** 组一条 WELCOME（中继用）。 */
    public static byte[] welcome(int yourId, int[] allIds, long seed) throws IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream(4 + 2 * allIds.length + 8);
        DataOutputStream d = new DataOutputStream(bo);
        d.writeByte(MSG_WELCOME); d.writeByte(PROTO_VERSION);
        d.writeShort(yourId);
        d.writeByte(allIds.length);
        for (int id : allIds) d.writeShort(id);
        d.writeLong(seed);
        d.flush();
        return bo.toByteArray();
    }

    // ---------------------------------------------------------------- Transport

    @Override public void send(byte[] msg) {
        if (msg == null || msg.length == 0) throw new IllegalArgumentException("空消息");
        try {
            if (ch.write(ByteBuffer.wrap(msg)) != msg.length)
                throw new IllegalStateException("UDP 欠写（不该发生：数据报要么全发要么抛错）");
        } catch (IOException e) { throw new IllegalStateException("UDP 发送失败", e); }
    }

    @Override public byte[] recv() {
        ByteBuffer buf = ByteBuffer.allocate(1500);              // 一条锁步消息远小于 MTU
        int r;
        try { r = ch.read(buf); }                                // connected 通道：只收中继的包
        catch (IOException e) { throw new IllegalStateException("UDP 接收失败", e); }
        if (r <= 0) return null;                                  // 非阻塞：无包（0）/ 通道关闭（-1）
        buf.flip();
        byte[] out = new byte[buf.remaining()];
        buf.get(out);
        return out;
    }

    @Override public void close() {
        try { ch.close(); } catch (IOException ignore) { }
    }

    /** 本地临时端口（诊断/测试用；未绑定返回 -1）。 */
    public int localPort() throws IOException {
        InetSocketAddress a = (InetSocketAddress) ch.getLocalAddress();
        return a == null ? -1 : a.getPort();
    }

    /** 顺手工具：把本机回环地址写死（测试用）。 */
    public static List<String> loopbackArgs(String host, int port) {
        List<String> l = new ArrayList<String>();
        l.add(host); l.add(String.valueOf(port));
        return l;
    }
}
