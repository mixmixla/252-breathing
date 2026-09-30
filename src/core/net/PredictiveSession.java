package core.net;

import core.world.Player;
import core.world.World;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;

/**
 * N4：**预测回滚会话**（GGPO 式客户端模型）。
 *
 * <p><b>核心思想</b>：本地玩家意图即时可知；远端玩家意图延迟到达。在权威帧到达前，
 * 客户端用「重复上次确认的远端意图」作预测，先把仿真跑在前面（低延迟手感）；一旦收到的
 * 权威帧与当初预测不符，就<b>原地回滚</b>到那个 tick、用正确输入重演到当前前沿 ——
 * 最终态与「等齐所有输入再跑」（纯锁步）逐字节一致，但过程中玩家几乎不卡。
 *
 * <p><b>与 N3 锁步的关系</b>：本类复用 {@link RollbackEngine}（N2-2/2-3 已证明的原地恢复原语）
 * 与 {@link World#snapshot()}/{@link World#restoreInPlace}。它<b>不关心传输</b>（帧由
 * {@link #receiveFrame} 注入，便于测试与未来接 {@link Transport}），只负责「预测 → 模拟 →
 * 权威到达 → 回滚重演」这层确定性逻辑。
 *
 * <p><b>正确性不变量</b>（门禁据此断言）：当所有权威帧到齐且前沿重演完毕，
 * {@code world.netHash()} 必须等于「同种子 + 同序权威帧 + 纯锁步」跑出的 hash——
 * 预测/回滚只是<b>到达顺序</b>不同，不改<b>已确认历史</b>的演化结果。
 */
public final class PredictiveSession {

    /** 把一帧意图写进世界（tick 语义由实现自证；随后 {@code world.tick()}）。 */
    public interface InputSink { void apply(int tick, Player.Intent[] byPlayerAsc); }

    /** 本地玩家的即时意图来源（同 (tick) ⇒ 同意图；预测/回滚都不改它）。 */
    public interface LocalIntentSource { Player.Intent forTick(int tick); }

    private final World world;
    private final int[] playerIds;          // 严格升序
    private final int localIdx;
    private final InputSink sink;
    private final LocalIntentSource localSrc;
    private final RollbackEngine engine = new RollbackEngine();
    /** N5：整 tick 推进体（默认 = {@code world.tick()}，向后兼容无头门禁）。 */
    private final TickBody tickBody;

    /** 默认推进体：与应用 {@code world.tick()} 行为逐字节一致。 */
    private static final TickBody DEFAULT_TICK = w -> w.tick();

    /** tick → 全玩家权威意图（按 playerIds 顺序）。仅存「已收到的完整帧」。 */
    private final TreeMap<Integer, Player.Intent[]> authoritative = new TreeMap<>();

    private int predictedTick = 0;         // 已模拟到的最高 tick（含预测）
    private int confirmedTick = 0;         // 已确认（权威齐备且连续）的最高 tick
    private int rollbackCount = 0;         // 发生过几次回滚（门禁据此断言"预测真错→真回滚"）

    public PredictiveSession(World world, int localId, int[] playerIds,
                            InputSink sink, LocalIntentSource localSrc) {
        this(world, localId, playerIds, sink, localSrc, DEFAULT_TICK);
    }

    /** N5：注入整 tick 推进体（如含内容层的玩法层）。 */
    public PredictiveSession(World world, int localId, int[] playerIds,
                            InputSink sink, LocalIntentSource localSrc, TickBody tickBody) {
        if (playerIds == null || playerIds.length == 0) throw new IllegalArgumentException("playerIds 为空");
        for (int i = 1; i < playerIds.length; i++)
            if (playerIds[i] <= playerIds[i - 1]) throw new IllegalArgumentException("playerIds 必须严格升序");
        boolean has = false;
        for (int id : playerIds) if (id == localId) { has = true; break; }
        if (!has) throw new IllegalArgumentException("localId 不在 playerIds 中");
        this.world = world; this.playerIds = playerIds.clone(); this.sink = sink; this.localSrc = localSrc;
        this.tickBody = tickBody;
        int idx = 0;
        for (int i = 0; i < playerIds.length; i++) if (playerIds[i] == localId) { idx = i; break; }
        this.localIdx = idx;
    }

    // ---------------------------------------------------------------- 查询
    public World worldRef() { return world; }
    public int predictedTick() { return predictedTick; }
    public int confirmedTick() { return confirmedTick; }
    public int rollbackCount() { return rollbackCount; }
    public int localIdx() { return localIdx; }

    // ---------------------------------------------------------------- 推进（预测）
    /**
     * 预测并模拟一 tick（predictedTick + 1）。远端玩家意图：若该 tick 已有权威帧则用权威，
     * 否则用「重复上次确认的远端意图」预测；本地玩家意图始终来自 {@link LocalIntentSource}。
     * 返回实际应用的帧。
     */
    public Player.Intent[] advance() {
        int t = predictedTick + 1;
        Player.Intent[] frame = buildFrame(t);
        byte[] snap = world.snapshot();                 // 该 tick 应用前的状态
        engine.record(t, frame, snap);
        sink.apply(t, frame);
        tickBody.tick(world);                                 // N5：整 tick 推进体（默认 world.tick，向后兼容）
        predictedTick = t;
        recomputeConfirmed();
        return frame;
    }

    /** 收到一帧权威输入（可能迟到）。与已记录的预测帧不符则触发回滚重演。 */
    public void receiveFrame(InputFrame f) {
        if (f.tick <= 0) throw new IllegalArgumentException("tick 必须 >= 1: " + f.tick);
        Player.Intent[] arr = new Player.Intent[playerIds.length];
        for (int i = 0; i < playerIds.length; i++) {
            Player.Intent it = f.intentOf(playerIds[i]);
            if (it == null) throw new IllegalArgumentException("权威帧缺玩家 " + playerIds[i]);
            arr[i] = it;
        }
        authoritative.put(f.tick, arr);
        reconcile();
        recomputeConfirmed();
    }

    /** 直接注入某 tick 的权威帧（已知全玩家意图时；测试用）。 */
    public void setAuthoritative(int tick, Player.Intent[] byPlayerAsc) {
        if (byPlayerAsc.length != playerIds.length)
            throw new IllegalArgumentException("意图数组长度不符");
        authoritative.put(tick, byPlayerAsc.clone());
        reconcile();
        recomputeConfirmed();
    }

    // ---------------------------------------------------------------- 内部

    private Player.Intent[] buildFrame(int t) {
        Player.Intent[] f = new Player.Intent[playerIds.length];
        Player.Intent[] auth = authoritative.get(t);
        for (int i = 0; i < playerIds.length; i++) {
            if (i == localIdx) {
                f[i] = localSrc.forTick(t);                 // 本地即时
            } else if (auth != null) {
                f[i] = auth[i];                             // 权威已到
            } else {
                f[i] = predictRemote(i, t);                 // 预测
            }
        }
        return f;
    }

    /** 远端玩家 i 在 tick t 的预测：重复上次确认的远端意图，无记录则 idle。 */
    private Player.Intent predictRemote(int i, int t) {
        int bestTick = -1;
        for (Integer tk : authoritative.keySet())
            if (tk < t && tk > bestTick) bestTick = tk;
        if (bestTick >= 0) return authoritative.get(bestTick)[i];
        return Player.Intent.idle();
    }

    /** 找最早的「已权威齐备但记录帧与权威不同」的 tick，回滚重演。 */
    private void reconcile() {
        for (int t = 1; t <= predictedTick; t++) {
            if (!authoritative.containsKey(t)) continue;
            RollbackEngine.Frame rec = engine.frameAt(t);
            if (rec == null) continue;
            if (!framesEqual(rec.intents, authoritative.get(t))) {
                rollbackTo(t);
                return;                                      // 重演由后续 advance() 继续
            }
        }
    }

    private void rollbackTo(int t) {
        rollbackCount++;
        byte[] snap = engine.snapshotAt(t);                 // t 应用前的世界状态
        try {
            world.restoreInPlace(snap);
        } catch (IOException e) {
            throw new IllegalStateException("回滚恢复失败（不该发生）", e);
        }
        predictedTick = t - 1;
        engine.pruneAfter(t - 1);                          // 丢弃 t 之后的预测帧（将重演覆盖）
    }

    private void recomputeConfirmed() {
        int c = 0;
        while (authoritative.containsKey(c + 1)) c++;
        confirmedTick = c;
    }

    private static boolean framesEqual(Player.Intent[] a, Player.Intent[] b) {
        if (a.length != b.length) return false;
        for (int i = 0; i < a.length; i++) if (!intentEq(a[i], b[i])) return false;
        return true;
    }

    private static boolean intentEq(Player.Intent a, Player.Intent b) {
        if (a == b) return true;
        if (a == null || b == null) return false;
        byte[] ba = new byte[6], bb = new byte[6];
        IntentCodec.encode(a, ba, 0);
        IntentCodec.encode(b, bb, 0);
        for (int i = 0; i < 6; i++) if (ba[i] != bb[i]) return false;
        return true;
    }
}
