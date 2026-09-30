package core.agent;

import core.rng.SeededRNG;
import core.world.World;

/**
 * L4 决策系统（GOAP-lite）：感知身体/心智状态 -> 选择动作标签（移植自 agents/decision.py 的优先级规则）。
 *
 * 简化版：规则优先级确定，无完整 GOAP。所有随机（仅 WANDER 分支用）只来自传入的 SeededRNG，
 * 调用方必须传入 world.simStream(...) 派生的子流，绝不读 world.rng 主状态或 fxRng。
 *
 * 已接入主循环：{@code core.systems.NpcSystem} 每 tick 调 {@link Npc#decide} → {@link #choose} 取得动作标签，
 * 再由其执行层（移动/进食/采集/交易）消费。本类自身只做"选意图"，不写网格。
 *
 * <p>⚠️ 状态（2026-09-30 核实）：{@link #choose} 当前只产出 FLEE/DRINK/EAT/WANDER/IDLE 五个标签；
 * 其余常量（GATHER/TRADE/CRAFT/STATION/MEDITATE）为执行层扩展预留，**尚无生产者/消费者**。
 */
public final class Decision {

    // 决策意图（action tag）
    public static final String FLEE = "flee";
    public static final String DRINK = "drink";
    public static final String EAT = "eat";
    public static final String FORAGE = "forage";
    public static final String GATHER = "gather";
    public static final String TRADE = "trade";
    public static final String CRAFT = "craft";
    public static final String STATION = "station";
    public static final String WANDER = "wander";
    public static final String IDLE = "idle";
    public static final String MEDITATE = "meditate";

    // 阈值（镜像 Python config；确定性常量）
    public float fleeThreshold = 0.5f;
    public float eatThreshold = 60f;
    public float drinkThreshold = 60f;

    /**
     * 选择意图（action tag）。规则优先级的简化实现：
     *   FLEE    恐惧 >= 阈值（被烧/遇险会逃跑）
     *   DRINK   口渴 >= 阈值
     *   EAT     饥饿 >= 阈值
     *   WANDER  空闲随机漫步（确定性：方向/取舍只走传入 rng）
     *   IDLE    其余（如商人守摊）
     * 返回的动作标签供 NpcSystem 的执行层（移动/进食/采集/交易）消费；本类不写网格。
     */
    public String choose(Npc self, World w, SeededRNG rng) {
        if (self == null || self.mind == null || self.body == null) return IDLE;
        float fear = self.mind.emotion.get("fear");
        if (fear >= fleeThreshold) return FLEE;
        if (self.body.thirst >= drinkThreshold) return DRINK;
        if (self.body.hunger >= eatThreshold) return EAT;
        // 空闲：用确定性 rng 在 WANDER/IDLE 间抉择（纯意图，不改变网格）
        if (rng != null && rng.nextDouble() < 0.5f) return WANDER;
        return IDLE;
    }
}
