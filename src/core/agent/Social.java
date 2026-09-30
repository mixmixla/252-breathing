package core.agent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * L5 深度认知层（M7）：人际关系网 + 长期关联记忆 + 情绪状态机（移植自 agents/social.py）。
 *
 * 设计纪律（零漂移）：
 *  - 所有增量固定、不依赖 rng，种子可复现、可断言；
 *  - social 只改“决策偏好”（mood/affinity），不越界直接改身体/网格；
 *  - 单向驱动、可序列化（AffinityEvent 记录显著恩仇，跨日/存档保留）。
 */
public final class Social {

    // 情绪状态机：离散情绪 -> 中文标签（供叙事）
    public static final Map<String, String> MOOD_LABEL = new HashMap<String, String>();
    static {
        MOOD_LABEL.put("calm", "平和"); MOOD_LABEL.put("wary", "警觉");
        MOOD_LABEL.put("scared", "惊恐"); MOOD_LABEL.put("furious", "暴怒");
        MOOD_LABEL.put("fond", "亲近"); MOOD_LABEL.put("hostile", "敌对");
    }

    // affinity 增减量（固定，确定性）
    public static final Map<String, Float> DELTA = new HashMap<String, Float>();
    static {
        DELTA.put("trade", 0.06f);        // 交易成功 -> 互利好感
        DELTA.put("coexist", 0.006f);     // 长期共处 -> 缓慢升温（同村情谊）
        DELTA.put("aid", 0.15f);          // 危难中互相照应 -> 明显结好
        DELTA.put("grudge", -0.25f);      // 被对方伤害 -> 强烈交恶
        DELTA.put("decay", 0.998f);       // 随时间淡忘（亲疏缓慢回落）
    }

    public final Map<String, Float> traits;
    public final Map<String, Float> affinity;                 // {other_id: float in [-1, 1]}
    public final Map<String, List<AffinityEvent>> longTerm;  // {other_id: [恩仇事件]}
    public String mood = "calm";
    public final int capacity;

    public Social() { this(new HashMap<String, Float>(), 60); }

    public Social(Map<String, Float> traits, int capacity) {
        this.traits = new HashMap<String, Float>(traits != null ? traits : new HashMap<String, Float>());
        this.affinity = new HashMap<String, Float>();
        this.longTerm = new HashMap<String, List<AffinityEvent>>();
        this.capacity = capacity;
    }

    /** 长期关联事件（恩仇来源）；确定性归档结构，可序列化。 */
    public static final class AffinityEvent {
        public final int tick;
        public final String type;
        public final String note;
        public AffinityEvent(int tick, String type, String note) {
            this.tick = tick; this.type = type; this.note = note;
        }
    }

    /** 可见邻人（id + 亲疏），供情绪状态机判定敌对/亲近。 */
    public static final class Neighbor {
        public final String id;
        public final float affinity;
        public Neighbor(String id, float affinity) { this.id = id; this.affinity = affinity; }
    }

    private float clamp(float v) { return Math.max(-1f, Math.min(1f, v)); }

    public float affinityTo(String other) {
        return affinity.containsKey(other) ? affinity.get(other) : 0f;   // 素不相识 = 中性 0
    }

    /**
     * 调整对 other 的亲疏并归档事件（两者分离：关系是数，记忆是事）。
     * 返回调整后的亲疏值。
     */
    public float drift(String other, float delta, String etype, String note, int tick) {
        float cur = clamp(affinityTo(other) + delta);
        affinity.put(other, cur);
        // 归档显著事件（恩仇来源），记人名才归档，防止记忆膨胀
        if ("trade".equals(etype) || "aid".equals(etype) || "grudge".equals(etype)) {
            List<AffinityEvent> rec = longTerm.containsKey(other)
                    ? longTerm.get(other) : new ArrayList<AffinityEvent>();
            rec.add(new AffinityEvent(tick, etype, note));
            while (rec.size() > capacity) rec.remove(0);
            longTerm.put(other, rec);
        }
        return cur;
    }

    /** 随时间淡忘：亲疏向中性缓慢回落（沧海桑田）。 */
    public void decay() {
        for (Map.Entry<String, Float> e : affinity.entrySet()) {
            affinity.put(e.getKey(), clamp(e.getValue() * DELTA.get("decay")));
        }
    }

    /**
     * 把情感向量 + 身边人亲疏 -> 离散情绪（确定性规则）。
     * neighbors: [(other_id, affinity)] 本轮可见/相邻者，用于敌对/亲近判定。
     */
    public String updateMood(float fear, float anger, float trust, List<Neighbor> neighbors) {
        if (anger >= 0.7f) mood = "furious";
        else if (fear >= 0.55f) mood = "scared";
        else if (anyLe(neighbors, -0.45f)) mood = "hostile";
        else if (anyGe(neighbors, 0.45f)) mood = "fond";
        else if (fear >= 0.3f || anger >= 0.3f) mood = "wary";
        else mood = "calm";
        return mood;
    }

    private boolean anyLe(List<Neighbor> ns, float v) {
        for (Neighbor n : ns) if (n.affinity <= v) return true;
        return false;
    }
    private boolean anyGe(List<Neighbor> ns, float v) {
        for (Neighbor n : ns) if (n.affinity >= v) return true;
        return false;
    }

    /** 记录长期共处者：每次在同一社区生活就对邻近者升温（情谊）。 */
    public void setAdjacency(List<String> ids) {
        for (String oid : ids) {
            float cur = affinityTo(oid);
            if (cur >= 0f) affinity.put(oid, clamp(cur + DELTA.get("coexist")));
        }
    }
}
