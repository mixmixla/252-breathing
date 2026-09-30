package core.net;

import core.world.Player;

/**
 * N1：一个 tick 的**全部玩家输入**（锁步的基本传输单元）。
 *
 * <p>布局（大端）：
 * <pre>
 *   off+0  tick       int32
 *   off+4  count      uint16
 *   then count × [ playerId uint16 | intent 6 字节 ]
 * </pre>
 * 故 {@code byteSize(n) = 6 + 8n}。3 名玩家 ≈ 30 字节/tick ≈ 600 B/s（未含冗余重发）。
 *
 * <p><b>为什么 playerIds 必须严格升序</b>：锁步里"应用一帧"的顺序必须是**全体一致**的
 * 确定序，不能依赖网络到达顺序。升序 + 唯一性在构造时强校验，把"顺序漂移"这类最难查的
 * desync 直接挡在门外。
 *
 * <p>纯数据 + 纯计算：不碰 {@code World}/RNG/墙钟。
 */
public final class InputFrame {

    /** 帧头长度（tick 4 + count 2）。 */
    public static final int HEADER = 6;

    public final int tick;
    /** 玩家 id，**严格升序**。 */
    public final int[] playerIds;
    /** 与 {@link #playerIds} 一一对应。 */
    public final Player.Intent[] intents;

    public InputFrame(int tick, int[] playerIds, Player.Intent[] intents) {
        if (tick < 0) throw new IllegalArgumentException("tick must be >= 0: " + tick);
        if (playerIds == null || intents == null) throw new IllegalArgumentException("null arrays");
        if (playerIds.length != intents.length)
            throw new IllegalArgumentException("playerIds/intents length mismatch: " + playerIds.length + " vs " + intents.length);
        if (playerIds.length > 65535) throw new IllegalArgumentException("too many players: " + playerIds.length);
        for (int i = 0; i < playerIds.length; i++) {
            if (playerIds[i] < 0 || playerIds[i] > 65535)
                throw new IllegalArgumentException("playerId out of range: " + playerIds[i]);
            if (i > 0 && playerIds[i] <= playerIds[i - 1])
                throw new IllegalArgumentException("playerIds must be strictly ascending: " + playerIds[i - 1] + " -> " + playerIds[i]);
            IntentCodec.validate(intents[i]);
        }
        this.tick = tick;
        this.playerIds = playerIds;
        this.intents = intents;
    }

    /** 编码长度。 */
    public static int byteSize(int players) { return HEADER + 8 * players; }

    /** 本帧编码长度。 */
    public int byteSize() { return byteSize(playerIds.length); }

    /** 取某玩家的意图；不存在返回 null。 */
    public Player.Intent intentOf(int playerId) {
        for (int i = 0; i < playerIds.length; i++) if (playerIds[i] == playerId) return intents[i];
        return null;
    }

    /** 写出到 {@code out[off .. off+byteSize)}。 */
    public void pack(byte[] out, int off) {
        int n = playerIds.length;
        out[off]     = (byte) ((tick >>> 24) & 0xFF);
        out[off + 1] = (byte) ((tick >>> 16) & 0xFF);
        out[off + 2] = (byte) ((tick >>> 8) & 0xFF);
        out[off + 3] = (byte) (tick & 0xFF);
        out[off + 4] = (byte) ((n >>> 8) & 0xFF);
        out[off + 5] = (byte) (n & 0xFF);
        int p = off + HEADER;
        for (int i = 0; i < n; i++) {
            out[p]     = (byte) ((playerIds[i] >>> 8) & 0xFF);
            out[p + 1] = (byte) (playerIds[i] & 0xFF);
            IntentCodec.encode(intents[i], out, p + 2);
            p += 8;
        }
    }

    /** 从 {@code in[off ..]} 读出一帧。缓冲区不足抛 IllegalArgumentException。 */
    public static InputFrame unpack(byte[] in, int off) {
        IntentCodec.require(in, off, HEADER);
        int tick = ((in[off] & 0xFF) << 24) | ((in[off + 1] & 0xFF) << 16)
                 | ((in[off + 2] & 0xFF) << 8) | (in[off + 3] & 0xFF);
        int n = ((in[off + 4] & 0xFF) << 8) | (in[off + 5] & 0xFF);
        IntentCodec.require(in, off + HEADER, 8 * n);
        int[] ids = new int[n];
        Player.Intent[] its = new Player.Intent[n];
        int p = off + HEADER;
        for (int i = 0; i < n; i++) {
            ids[i] = ((in[p] & 0xFF) << 8) | (in[p + 1] & 0xFF);
            its[i] = IntentCodec.decode(in, p + 2);
            p += 8;
        }
        return new InputFrame(tick, ids, its);
    }
}
