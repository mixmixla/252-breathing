package core.net;

import core.world.Player;
import core.world.World;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * N3：**确定性锁步会话**（协议层；不含 socket —— 传输由 {@link Transport} 注入）。
 *
 * <p><b>协议</b>：
 * <ol>
 *   <li><b>锁步</b>：要模拟 tick T，必须先拿到**所有**玩家在 T 的输入帧。缺任何一个就不推进 ——
 *       这保证两端永远对「第 T tick 用什么输入」达成一致，是逐字节同构的唯一来源。</li>
 *   <li><b>输入延迟 D</b>：本地玩家在 simTick = S 时生成的输入供 tick {@code S+1+D} 使用；
 *       只要网络单向延迟 &lt; D 个回合就永不卡顿。D 越大手感越延迟，越小越容易卡 —— 这是
 *       「锁步 = 输入延迟」那盆冷水的具体旋钮。</li>
 *   <li><b>desync 检测</b>：定期互发 {@link World#netHash()}（**宽哈希**，覆盖全部持久化状态），
 *       同 tick 不一致即为 desync。</li>
 * </ol>
 *
 * <p><b>消息</b>（单字节类型前缀 + 载荷）：
 * <pre>
 *   MSG_INPUT = 1 : InputFrame.pack()          —— 一 tick 的全玩家输入（N1 编解码）
 *   MSG_HASH  = 2 : tick i32 + netHash i64     —— desync 检测
 * </pre>
 *
 * <p><b>已知边界（记录为 N4 待办，不在本层假装解决）</b>：{@code World.player} 是**单数**，
 * 多玩家输入的"应用"由 {@link InputSink} 注入 —— 本层只负责「把一帧按玩家升序定序、
 * 保证全员拿到同一帧、缺帧就不推进」，不负责把第 2 个玩家的意图写进世界。
 */
public final class LockstepSession {

    /** 把一帧的意图写进世界（tick 语义由实现自证；随后会话统一 {@code world.tick()}）。 */
    public interface InputSink { void apply(int tick, Player.Intent[] byPlayerAsc); }

    /** 消息类型（public：测试需要按类型识别消息以做故障注入）。 */
    public static final byte MSG_INPUT = 1;
    public static final byte MSG_HASH = 2;

    private final World world;
    private final int localId;
    private final int[] playerIds;      // 严格升序（InputFrame 契约）
    private final int localIdx;
    private final int inputDelay;
    private final Transport tp;
    private final InputSink sink;
    /** N5：整 tick 推进体（默认 = {@code world.tick()}，向后兼容无头门禁）。 */
    private final TickBody tickBody;

    /** 默认推进体：与应用 {@code world.tick()} 行为逐字节一致。 */
    private static final TickBody DEFAULT_TICK = w -> w.tick();

    /** tick → 按玩家升序的意图（null = 还没到）。 */
    private final Map<Long, Player.Intent[]> frames = new TreeMap<>();
    /** 本地帧是否已发送（防重复发）。 */
    private final Map<Long, Boolean> localSent = new HashMap<>();
    /** 收到的远端 netHash：tick → hash。 */
    private final Map<Long, Long> remoteHash = new HashMap<>();
    /** 已发送过 netHash 的 tick。 */
    private final List<Long> hashSent = new ArrayList<>();

    private long simTick = 0;
    private final List<Long> desyncAt = new ArrayList<>();

    /**
     * @param playerIds  全体玩家 id，**严格升序**（与 {@link InputFrame} 契约一致）
     * @param inputDelay 输入延迟（tick 数）；0 = 无延迟（同机回环测试用）
     */
    public LockstepSession(World world, int localId, int[] playerIds, int inputDelay,
                           Transport tp, InputSink sink) {
        this(world, localId, playerIds, inputDelay, tp, sink, DEFAULT_TICK);
    }

    /** N5：注入整 tick 推进体（如含内容层的玩法层）。 */
    public LockstepSession(World world, int localId, int[] playerIds, int inputDelay,
                           Transport tp, InputSink sink, TickBody tickBody) {
        if (playerIds == null || playerIds.length == 0) throw new IllegalArgumentException("playerIds 为空");
        for (int i = 1; i < playerIds.length; i++)
            if (playerIds[i] <= playerIds[i - 1]) throw new IllegalArgumentException("playerIds 必须严格升序");
        boolean has = false;
        for (int id : playerIds) if (id == localId) { has = true; break; }
        if (!has) throw new IllegalArgumentException("localId 不在 playerIds 中");
        if (inputDelay < 0) throw new IllegalArgumentException("inputDelay < 0");
        this.world = world; this.localId = localId; this.playerIds = playerIds.clone();
        this.inputDelay = inputDelay; this.tp = tp; this.sink = sink; this.tickBody = tickBody;
        int idx = 0;
        for (int i = 0; i < playerIds.length; i++) if (playerIds[i] == localId) { idx = i; break; }
        this.localIdx = idx;
    }

    // ---------------------------------------------------------------- 对外查询

    /** 已模拟的 tick 数。 */
    public long simTick() { return simTick; }
    /** 输入延迟（tick 数）—— 供测试/诊断读。 */
    public int inputDelay() { return inputDelay; }
    /** 本会话的世界（诊断/测试用；协议本身只经 {@link InputSink} 与 {@code tick} 触碰它）。 */
    public World worldRef() { return world; }
    /** 本地玩家 id。 */
    public int yourId() { return localId; }
    /** 全体玩家 id（严格升序）。 */
    public int[] playerIds() { return playerIds.clone(); }
    /** 是否在某个 tick 检出 desync。 */
    public boolean desynced() { return !desyncAt.isEmpty(); }
    /** desync 发生在哪些 tick（供诊断）。 */
    public List<Long> desyncTicks() { return desyncAt; }
    public int playerCount() { return playerIds.length; }

    // ---------------------------------------------------------------- 输入

    /**
     * 记录本地玩家在 {@code tick} 的意图。只许对未来 tick 记录 ——
     * 对已模拟 tick 补录输入是"篡改历史"，静默接受会让两端悄悄走偏。
     */
    public void submitLocal(int tick, Player.Intent it) {
        if (tick <= simTick) throw new IllegalArgumentException("补录已模拟 tick 的输入: " + tick);
        if (it == null) throw new IllegalArgumentException("null intent");
        frames.computeIfAbsent((long) tick, k -> new Player.Intent[playerIds.length])[localIdx] = it;
    }

    /**
     * 收发一轮：把「已到期该发」的本地帧发出（tick ≤ simTick+1+D），并收取远端消息。**非阻塞**。
     */
    public void pump() {
        long deadline = simTick + 1 + inputDelay;
        for (long t = simTick + 1; t <= deadline; t++) {
            Player.Intent[] arr = frames.get(t);
            if (arr == null || arr[localIdx] == null) continue;      // 本地还没生成
            if (localSent.containsKey(t)) continue;                   // 已发过
            tp.send(packInput((int) t, arr[localIdx]));
            localSent.put(t, Boolean.TRUE);
        }
        byte[] m;
        while ((m = tp.recv()) != null) handleMessage(m);
    }

    /** 是否具备推进条件（下一 tick 的全玩家输入已齐）。 */
    public boolean canAdvance() {
        Player.Intent[] arr = frames.get(simTick + 1);
        if (arr == null) return false;
        for (Player.Intent it : arr) if (it == null) return false;
        return true;
    }

    /** 推进一 tick（应用输入 + world.tick()）。不具备条件返回 false。 */
    public boolean advance() {
        long t = simTick + 1;
        Player.Intent[] arr = frames.get(t);
        if (arr == null) return false;
        for (Player.Intent it : arr) if (it == null) return false;
        if (world.paused) return false;                              // 暂停须全局一致：缺帧/暂停都不推进
        sink.apply((int) t, arr);
        tickBody.tick(world);                                 // N5：整 tick 推进体（默认 world.tick，向后兼容）
        frames.remove(t);
        simTick = t;
        return true;
    }

    // ---------------------------------------------------------------- desync 检测

    /**
     * 互发一次 {@link World#netHash()} 并比对。**双方必须都在同一 tick 上调用**；
     * 已有结果缓存在 {@link #remoteHash}。返回是否一致（还没有远端结果时返回 true —— 还没到判定时机）。
     */
    public boolean exchangeHash() {
        long t = simTick;
        long mine = world.netHash();                       // 只算一次（成本 ~几十 ms）
        if (!hashSent.contains(t)) {
            hashSent.add(t);
            tp.send(packHash(t, mine));
        }
        byte[] m;
        while ((m = tp.recv()) != null) handleMessage(m);
        Long remote = remoteHash.get(t);
        if (remote == null) return true;                   // 对端还没发到 → 本轮无法判定
        boolean ok = remote.longValue() == mine;
        if (!ok) desyncAt.add(t);
        return ok;
    }

    // ---------------------------------------------------------------- 消息

    private void handleMessage(byte[] m) {
        if (m == null || m.length < 1) throw new IllegalStateException("空消息");
        byte type = m[0];
        switch (type) {
            case MSG_INPUT: {
                InputFrame f = InputFrame.unpack(m, 1);
                if (f.tick <= simTick) return;                       // 重复/过期投递（UDP 常态）→ 忽略
                Player.Intent[] arr = frames.computeIfAbsent((long) f.tick,
                        k -> new Player.Intent[playerIds.length]);
                for (int i = 0; i < f.playerIds.length; i++) {
                    int idx = indexOf(f.playerIds[i]);
                    if (idx < 0) throw new IllegalStateException("未知玩家 id " + f.playerIds[i]);
                    arr[idx] = f.intents[i];
                }
                return;
            }
            case MSG_HASH: {
                if (m.length < 1 + 4 + 8) throw new IllegalStateException("HASH 消息截断");
                int t = ((m[1] & 0xFF) << 24) | ((m[2] & 0xFF) << 16) | ((m[3] & 0xFF) << 8) | (m[4] & 0xFF);
                long h = 0;
                for (int i = 5; i < 13; i++) h = (h << 8) | (m[i] & 0xFF);
                remoteHash.put((long) t, h);
                return;
            }
            default:
                throw new IllegalStateException("未知消息类型 " + type);
        }
    }

    private int indexOf(int pid) {
        for (int i = 0; i < playerIds.length; i++) if (playerIds[i] == pid) return i;
        return -1;
    }

    private byte[] packInput(int tick, Player.Intent it) {
        InputFrame f = new InputFrame(tick, new int[]{localId}, new Player.Intent[]{it});
        byte[] body = new byte[f.byteSize()];
        f.pack(body, 0);
        byte[] out = new byte[1 + body.length];
        out[0] = MSG_INPUT;
        java.lang.System.arraycopy(body, 0, out, 1, body.length);
        return out;
    }

    private byte[] packHash(long tick, long hash) {
        ByteArrayOutputStream bo = new ByteArrayOutputStream(1 + 4 + 8);
        DataOutputStream d = new DataOutputStream(bo);
        try {
            d.writeByte(MSG_HASH);
            d.writeInt((int) tick);
            d.writeLong(hash);
            d.flush();
        } catch (IOException e) { throw new IllegalStateException(e); }
        return bo.toByteArray();
    }
}
