package core.content;

/**
 * 帧切片任务环（Terraria {@code LightingEngine.EngineState} 的泛化，2026-09-21）。
 *
 * <p><b>学的是什么</b>：Terraria 的 {@code EngineState{MinimapUpdate, ExportMetrics, Scan, Blur}}
 * 每帧<b>只做其中一个</b>，靠 {@code (_state+1) % 4} 轮转 —— 把"每帧全图重算"换成
 * "每几帧各做一件事"。同一手法在 Terraria 里用于四处（液体 / 光照 / autotile / 特殊方块绘制）。
 *
 * <p><b>我们已有的同型物</b>：M3① 的跨图流式分帧（{@code World.stepShift} + INDEX 阶段）——
 * 但那只用在流式一处。本类把它抽成一个可复用的<b>极小环</b>，供小地图/光照等一起用。
 *
 * <p><b>为什么放 core</b>：与 {@link MapField} / {@link RainField} 同一理由 ——
 * 渲染表现里"是数学的那部分"放 core，才能在无头环境断言（分帧不丢帧、顺序可复现、能收敛）。
 *
 * <p><b>确定性</b>：纯计数器，无 RNG、不读世界状态。任务推进顺序固定 → 同帧号同状态，
 * 逐字节可复现（{@code bw.snap} A/B 仪器依赖这一点）。
 */
public final class FrameSlicer {

    /** 默认环内任务数（Terraria 也是 4）。 */
    public static final int DEFAULT_SLOTS = 4;

    /** 任务总数（构建期固定）。 */
    private final int slots;
    /** 当前游标 ∈ [0, slots)。 */
    private int cursor = 0;
    /** 已完成的轮数（诊断/dedup 用）。 */
    private int rounds = 0;

    public FrameSlicer() { this(DEFAULT_SLOTS); }

    public FrameSlicer(int slots) {
        if (slots < 1) throw new IllegalArgumentException("slots must be >= 1");
        this.slots = slots;
    }

    /**
     * 推进一帧：返回本帧应执行的任务槽位。
     *
     * <p>调用方按返回值 switch：{@code case 0: 做光照; case 1: 做小地图; ...}。
     * 保证 N 帧内每个任务恰好各被轮到一次（无饥饿、无重复）。
     */
    public int next() {
        int s = cursor;
        cursor++;
        if (cursor >= slots) { cursor = 0; rounds++; }
        return s;
    }

    /** 当前游标（不推进）。 */
    public int cursor() { return cursor; }

    /** 已完成的整轮数。 */
    public int rounds() { return rounds; }

    /** 槽位数。 */
    public int slots() { return slots; }

    /**
     * 本帧是否轮到 {@code slot}（<b>只读</b>，不推进游标）。
     *
     * <p>用于"某任务可在环里占多个槽位"的场景：{@code if (slicer.isDue(frameSlot, 1)) { ... }}
     * 不必为它单独开一套计数器。
     */
    public boolean isDue(int slot) {
        return cursor == slot;
    }

    /** 距 {@code slot} 下次轮到还有几帧（0 = 本帧；用于"每 N 帧一次"的节流判断）。 */
    public int framesUntil(int slot) {
        int d = slot - cursor;
        if (d < 0) d += slots;
        return d;
    }

    /** 重置游标（不改变槽位数）。 */
    public void reset() { cursor = 0; rounds = 0; }
}
