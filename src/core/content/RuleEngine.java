package core.content;

import core.world.World;
import java.util.Collections;
import java.util.List;

/**
 * 规则引擎 —— 订阅 {@link World#events} 事件总线，把「玩法」跑起来。
 *
 * <p>每个 tick：从自己的水印开始扫描新事件 → 匹配规则 → 求值条件 → 命中则把
 * {@link Rule#effects} 提交进 {@link EffectQueue}。效果由队列按 {@code (dueTick, seq)}
 * 确定性执行，因此<b>整条链路都是可复现的</b>。
 *
 * <p><b>水印独立性</b>：本引擎用<b>自己的</b> {@code watermark}，绝不写
 * {@link World#eventsProcessed} —— 那个水印属于 {@code StorytellerSystem}。
 * 两个消费者共享一个水印会互相吃掉对方的事件，这是隐性 bug 的经典来源。
 *
 * <p><b>零 RNG</b>：条件里的概率走 {@link EffectQueue#hash01}，不消耗 {@code simStream}。
 * 规则数量多少，仿真指纹都不动。
 *
 * <p><b>为什么不是「零编码万物」</b>：新机制（捕捉判定、产线结算）第一次出现仍需代码
 * —— 本引擎负责的是「机制的<b>参数、组合、触发条件</b>」全部可配置。
 * 机制写一次，之后所有变体都是 JSON。这是「一次编码，无限配置」。
 */
public final class RuleEngine {

    private final List<Rule> rules;
    private final long[] lastFire;      // 每规则上次触发 tick（冷却用）
    private int watermark = 0;          // 自有水印（不碰 world.eventsProcessed）
    private int fired = 0;

    public RuleEngine(List<Rule> rules) {
        this.rules = Collections.unmodifiableList(rules);
        this.lastFire = new long[rules.size()];
        for (int i = 0; i < lastFire.length; i++) lastFire[i] = -1000000L;
    }

    /** 推进一 tick。返回值 = 本 tick 命中的规则条数（诊断用，不进指纹）。 */
    public int tick(World w, EffectQueue queue, long nowTick) {
        if (w == null || queue == null || rules.isEmpty()) return 0;
        List<World.Event> evs = w.events;
        int size = evs.size();
        if (watermark < 0 || watermark > size) watermark = 0;   // 事件表被裁剪 → 安全重置

        int hits = 0;
        for (int i = watermark; i < size; i++) {
            World.Event ev = evs.get(i);
            if (ev == null) continue;
            for (int r = 0; r < rules.size(); r++) {
                Rule rule = rules.get(r);
                if (!rule.matches(ev.system, ev.action, ev.params)) continue;
                if (rule.cooldown > 0f) {
                    long cd = Math.round(rule.cooldown * EffectQueue.TICKS_PER_SEC);
                    if (nowTick - lastFire[r] < cd) continue;
                }
                if (!eval(rule, w, nowTick, r)) continue;
                queue.submitAll(rule.effects, 0f, 0f, 0f, nowTick);
                lastFire[r] = nowTick;
                hits++;
            }
        }
        watermark = size;
        fired += hits;
        return hits;
    }

    /** 条件求值：全部通过才触发动作为。 */
    private boolean eval(Rule rule, World w, long nowTick, int salt) {
        for (Rule.Check c : rule.checks) {
            if ("HAS_SKILL".equals(c.kind)) {
                if (!w.hasSkill(c.id)) return false;
            } else if ("HAS_ITEM".equals(c.kind)) {
                // P2 会接真实背包查询；在此之前恒真（不假装有背包）
            } else if ("SCALAR_AT_LEAST".equals(c.kind)) {
                if (scalar(w, c.name) < c.value) return false;
            } else if ("CHANCE".equals(c.kind)) {
                if (EffectQueue.hash01(w.seed, nowTick, salt * 31 + 7) >= c.value) return false;
            } else if ("EVENT_FIELD".equals(c.kind)) {
                // 事件字段过滤在 Rule.matches 的 paramsEq 阶段完成
            }
        }
        return true;
    }

    /** 世界标量取值（规则条件可读的"传感器"）。未知名字 → 0（保守）。 */
    public static float scalar(World w, String name) {
        if (w == null || name == null) return 0f;
        if ("prosperity".equals(name)) return w.prosperity;
        if ("tick".equals(name))       return (float) w.tick;
        if ("builtMass".equals(name))  return (float) w.builtMass;
        if ("beasts".equals(name))     return w.beasts.size();
        return 0f;
    }

    public List<Rule> rules()   { return rules; }
    public int firedTotal()     { return fired; }
    public int watermark()      { return watermark; }
}
