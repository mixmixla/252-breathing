package core.content;

import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * 效果执行队列——内容层的<b>确定性调度器</b>。
 *
 * <p>内容（技能 / 特效 / 状态）产出的 {@link Effect} 先进队列，由本类按
 * {@code (dueTick, seq)} 稳定排序后执行。排序键里的 {@code seq} 是提交顺序，
 * 保证「同一 tick 的多个效果按注入顺序执行」——这是可复现的必要条件。
 *
 * <p><b>零 RNG 纪律</b>：本类<b>不持有也不消耗</b>任何 {@code SeededRNG}。
 * 概率判定（{@code chance}）走 {@link #hash01}——由 {@code (seed, tick, salt)}
 * 派生的纯哈希。因此内容层无论多复杂，都不会扰动仿真 RNG 流，
 * {@code hashState()} 逐字节不变。
 *
 * <p><b>零渲染依赖</b>：所有输出经 {@link EffectSink} 单向喊出，core 层不认识粒子/音频/UI。
 */
public final class EffectQueue {

    /** 仿真频率：20 tick / 秒（与 World 的 SIM_DT 一致）。 */
    public static final int TICKS_PER_SEC = 20;

    private static final class Item {
        final long dueTick;
        final int seq;
        final Effect effect;
        final float x, y, z;
        Item(long dueTick, int seq, Effect effect, float x, float y, float z) {
            this.dueTick = dueTick; this.seq = seq; this.effect = effect;
            this.x = x; this.y = y; this.z = z;
        }
    }

    private final long seed;
    private final List<Item> pending = new ArrayList<Item>();
    private int seqCounter = 0;
    private int executed = 0;
    private int expired = 0;
    private int skippedChance = 0;

    /** 已执行效果的流水（门禁用：断言确定性执行序列）。上限 512 条，超出丢弃最旧。 */
    private final List<String> trace = new ArrayList<String>();
    private static final int TRACE_CAP = 512;

    public EffectQueue(long seed) { this.seed = seed; }

    /** 提交一条效果。{@code delay} 秒后到期（对齐到 tick）。 */
    public void submit(Effect e, float x, float y, float z, long nowTick) {
        if (e == null) return;
        int d = Math.round(e.delay * TICKS_PER_SEC);
        pending.add(new Item(nowTick + d, seqCounter++, e, x, y, z));
    }

    /** 批量提交（顺序即执行顺序）。 */
    public void submitAll(List<Effect> list, float x, float y, float z, long nowTick) {
        if (list == null) return;
        for (Effect e : list) submit(e, x, y, z, nowTick);
    }

    /**
     * 推进到 {@code nowTick}：取出所有到期项，按 (dueTick, seq) 稳定执行。
     *
     * @return 本次执行的效果条数
     */
    public int tick(long nowTick, EffectSink sink, ContentRegistry registry) {
        if (pending.isEmpty()) return 0;
        List<Item> due = new ArrayList<Item>();
        for (int i = pending.size() - 1; i >= 0; i--) {
            if (pending.get(i).dueTick <= nowTick) {
                due.add(pending.remove(i));
            }
        }
        if (due.isEmpty()) return 0;
        Collections.sort(due, new Comparator<Item>() {
            @Override public int compare(Item a, Item b) {
                if (a.dueTick != b.dueTick) return a.dueTick < b.dueTick ? -1 : 1;
                return a.seq < b.seq ? -1 : (a.seq == b.seq ? 0 : 1);
            }
        });
        EffectSink out = (sink == null) ? EffectSink.NONE : sink;
        int n = 0;
        for (Item it : due) {
            if (!roll(it)) { skippedChance++; trace(it, "SKIP"); continue; }
            dispatch(it, out, registry);
            executed++;
            n++;
            trace(it, "RUN");
        }
        return n;
    }

    /** 概率判定：纯哈希，不碰 RNG 流。 */
    private boolean roll(Item it) {
        Effect e = it.effect;
        if (e.chance >= 1f) return true;
        if (e.chance <= 0f) return false;
        return hash01(seed, it.dueTick, it.seq) < e.chance;
    }

    /** 指令分派：表现类走对应回调，模拟类统一走 {@link EffectSink#simulate}。 */
    private void dispatch(Item it, EffectSink sink, ContentRegistry reg) {
        Effect e = it.effect;
        JsonObject def;
        if ("SPAWN_PARTICLE".equals(e.type)) {
            def = (reg == null) ? null : reg.get("particles", e.id);
            sink.particle(def, it.x, it.y, it.z);
        } else if ("SPAWN_FX".equals(e.type)) {
            def = (reg == null) ? null : reg.get("fx", e.id);
            sink.fx(def, it.x, it.y, it.z);
        } else if ("PLAY_SFX".equals(e.type)) {
            sink.sfx(e.id);
        } else if ("SCREEN_SHAKE".equals(e.type)) {
            sink.shake(e.power > 0f ? e.power : e.amount);
        } else if ("DIALOGUE".equals(e.type)) {
            sink.banner(e.id);                       // DIALOGUE 的正文就放在 id 上
        } else {
            sink.simulate(e.type, e.id, e.amount, it.x, it.y, it.z);
        }
    }

    private void trace(Item it, String tag) {
        trace.add(it.dueTick + "|" + it.seq + "|" + tag + "|" + it.effect.type
                + (it.effect.id.isEmpty() ? "" : ":" + it.effect.id));
        while (trace.size() > TRACE_CAP) trace.remove(0);
    }

    // ---------- 确定性哈希随机（内容层专用，绝不碰 simStream） ----------

    /**
     * 三维确定性哈希 → {@code [0,1)}。splitmix64 混合，纯函数。
     *
     * <p>这是内容层的「随机源」：同种子 + 同 tick + 同序号 → 永远同一个值。
     * 因此内容层可以表达概率（暴击、散射、抖动）却完全不扰动仿真 RNG。
     */
    public static float hash01(long seed, long tick, int salt) {
        long z = seed
                + 0x9E3779B97F4A7C15L * (tick + 1L)
                + 0x165667B19E3779F9L * ((long) salt + 1L);
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        z = z ^ (z >>> 31);
        return (float) ((z >>> 40) & 0xFFFFFFL) / 16777216.0f;
    }

    /** 区间取值：{@code [min,max)} 内由 hash 确定取值（粒子生命周期/速度/尺寸用）。 */
    public static float hashRange(long seed, long tick, int salt, float min, float max) {
        return min + (max - min) * hash01(seed, tick, salt);
    }

    // ---------- 观测 ----------

    public int pendingCount()  { return pending.size(); }
    public int executedCount() { return executed; }
    public int skippedCount()  { return skippedChance; }
    public List<String> trace(){ return Collections.unmodifiableList(trace); }
    public void clear()        { pending.clear(); trace.clear(); }
}
