package core.agent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * L4 心智：情感向量 + 记忆 + 仇恨表（记仇）（移植自 agents/mind.py）。
 *
 * 设计纪律（零漂移）：
 *  - 情感增量固定，不依赖 rng，种子可复现、可断言；
 *  - 所有方法纯函数式；本类不使用任何随机源，随机只来自调用方。
 *
 * 字段映射：emotion（fear/anger/trust 向量）、short_term 记忆（限容量 List<String>）、
 * grudge（仇恨表 source->愤怒）、traits（人格特质）、goals（目标/意图，GOAP-lite 用）。
 */
public final class Mind {

    public final Map<String, Float> traits;       // 人格特质（cautious/bold...）
    public final Map<String, Float> emotion;      // fear/anger/trust ∈ [0,1]
    public final List<String> memory;             // 短时记忆（有限容量）
    public final Map<String, Float> grudge;        // {source_id: 累积愤怒}
    public final List<String> goals;              // 目标/意图（决策层写）
    public final int capacity;

    public Mind() { this(new HashMap<String, Float>(), 20); }

    public Mind(Map<String, Float> traits, int capacity) {
        this.traits = new HashMap<String, Float>(traits != null ? traits : new HashMap<String, Float>());
        this.emotion = new HashMap<String, Float>();
        this.emotion.put("fear", 0f);
        this.emotion.put("anger", 0f);
        this.emotion.put("trust", 0f);
        this.memory = new ArrayList<String>();
        this.grudge = new HashMap<String, Float>();
        this.goals = new ArrayList<String>();
        this.capacity = capacity;
    }

    /** 短时记忆：只保留最近 capacity 条（有限记忆，会遗忘）。 */
    public void remember(String entry) {
        memory.add(entry);
        while (memory.size() > capacity) memory.remove(0);
    }

    /** 情感衰减（随时间冷静/淡忘）。 */
    public void decay(float k, float amount) {
        for (Map.Entry<String, Float> e : emotion.entrySet()) {
            float v = e.getValue() * k - amount;
            emotion.put(e.getKey(), Math.max(0f, v));
        }
    }

    /** 感知危险 -> 恐惧上升（谨慎者更怕）。 */
    public void seeDanger(float intensity) {
        float cautious = traits.containsKey("cautious") ? traits.get("cautious") : 0.5f;
        float f = emotion.get("fear") + intensity * (0.5f + cautious);
        emotion.put("fear", Math.min(1f, f));
    }

    /** 受伤 -> 愤怒 + 记仇 + 恐惧（被烧到会怕，驱动逃跑保命）。 */
    public void hurt(String source, float angerGain) {
        emotion.put("anger", Math.min(1f, emotion.get("anger") + angerGain));
        emotion.put("fear", Math.min(1f, emotion.get("fear") + angerGain * 0.6f));
        float cur = grudge.containsKey(source) ? grudge.get(source) : 0f;
        grudge.put(source, cur + angerGain);
        remember("hurt:" + source);
    }

    /** 交易成功 -> 信任上升（对交易对象/市场）。 */
    public void tradeSuccess(float gain) {
        emotion.put("trust", Math.min(1f, emotion.get("trust") + gain));
        remember("trade");
    }

    /** 每决策周期情感衰减（恐惧/愤怒随时间缓和）。 */
    public void update() { decay(0.9f, 0.03f); }
}
