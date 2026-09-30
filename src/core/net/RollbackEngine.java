package core.net;

import core.world.Player;

import java.io.ByteArrayOutputStream;
import java.util.NavigableMap;
import java.util.TreeMap;

/**
 * N4：**回滚缓冲** —— 按 tick 记录「应用前的世界快照 + 所用输入帧」，供预测回滚原地恢复。
 *
 * <p>设计要点：
 * <ul>
 *   <li>记录的是「该 tick <b>应用前</b>」的世界快照（{@link core.world.World#snapshot()}），
 *       故回滚到 tick T 即恢复「T 开始前的状态」，随后用修正输入重跑 T。</li>
 *   <li>输入帧按玩家升序存放（与 {@link InputFrame} 契约一致），便于与权威帧逐位比对。</li>
 *   <li>环形裁剪：{@link #pruneBefore}/{@link #pruneAfter} 把已确认无需再回滚的帧丢弃，
 *       控制内存（预测深度通常只有几 tick）。</li>
 * </ul>
 *
 * <p>不碰 RNG/墙钟/网络；纯内存结构，确定性由调用方保证。
 */
public final class RollbackEngine {

    /** 一 tick 的记录：该 tick 应用前的世界快照 + 所用输入。 */
    public static final class Frame {
        public final int tick;
        public final Player.Intent[] intents;   // 按玩家升序
        public final byte[] snapshot;           // 该 tick 应用前的世界状态
        Frame(int tick, Player.Intent[] intents, byte[] snapshot) {
            this.tick = tick; this.intents = intents; this.snapshot = snapshot;
        }
    }

    private final TreeMap<Integer, Frame> frames = new TreeMap<>();

    /** 记录某 tick 应用前的快照与所用输入（覆盖同名 tick 的旧记录）。 */
    public void record(int tick, Player.Intent[] intents, byte[] snapshot) {
        if (tick <= 0) throw new IllegalArgumentException("tick 必须 >= 1");
        frames.put(tick, new Frame(tick, intents, snapshot));
    }

    /** 取某 tick 的记录（含应用前快照）。 */
    public Frame frameAt(int tick) { return frames.get(tick); }

    /** 回滚到 tick T 所需的世界快照：即 T 应用前的状态（{@link #frameAt} 的快照）。 */
    public byte[] snapshotAt(int tick) {
        Frame f = frames.get(tick);
        if (f == null) throw new IllegalStateException("无回滚锚点: tick=" + tick);
        return f.snapshot;
    }

    public int earliest() { return frames.isEmpty() ? Integer.MAX_VALUE : frames.firstKey(); }
    public int latest()   { return frames.isEmpty() ? Integer.MIN_VALUE : frames.lastKey(); }
    public boolean has(int tick) { return frames.containsKey(tick); }
    public int size() { return frames.size(); }

    /** 丢弃早于（含）tick 的记录（已确认无需再回滚）。 */
    public void pruneBefore(int tick) {
        NavigableMap<Integer, Frame> head = frames.headMap(tick, true);
        head.clear();
    }

    /** 丢弃晚于 tick 的记录（回滚后这些预测帧作废，将由重新模拟覆盖）。 */
    public void pruneAfter(int tick) {
        NavigableMap<Integer, Frame> tail = frames.tailMap(tick + 1, true);
        tail.clear();
    }

    /** 当前所有记录的 tick 列表（升序）。 */
    public java.util.List<Integer> ticks() { return new java.util.ArrayList<>(frames.keySet()); }
}
