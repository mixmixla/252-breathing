package core.systems;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import core.content.ContentRegistry;
import core.content.Effect;
import core.content.EffectQueue;
import core.content.EffectSink;
import core.content.SkillDef;
import core.rng.SeededRNG;
import core.world.Beast;
import core.world.Player;
import core.world.World;

import java.util.List;

/**
 * 内容系统 —— 把「配置驱动的内容」接进游戏循环的<b>唯一一座桥</b>。
 *
 * <p>本类实现 {@link System} 接口，但<b>不注册进 {@code World.systems}</b>：
 * 它由 Game 层在固定步长处驱动，与 {@code RuleEngine} / {@code TechTree} / {@code QuestEngine}
 * 并列（内容层引擎）。理由：{@link EffectQueue} 必须与仿真 tick 一一对应地推进，
 * 而 Game 的固定步长块正好是唯一保证这一点的地方。若同时注册进 World，队列会被推进两次
 * （效果执行两遍）—— 所以这里刻意只留一个驱动者。
 *
 * <p><b>零 RNG 纪律（关键）</b>：{@code update} 拿到的 {@code rng} 参数刻意<b>完全不使用</b>。
 * 内容层的概率（暴击 / 散射 / 抖动）一律走 {@link EffectQueue#hash01}——由
 * {@code (seed, tick, salt)} 派生的纯哈希。因此：
 * <ul>
 *   <li>内容层无论多复杂，都不消耗、不扰动仿真 RNG 流；</li>
 *   <li>{@code World.hashState()} 里的 {@code rng.state()} 逐字节不变；</li>
 *   <li>各类零漂移门禁对「内容层做了什么」完全无感。</li>
 * </ul>
 *
 * <p><b>零渲染依赖</b>：所有表现类结果经 {@link EffectSink} 单向喊给上层（Game 层实现），
 * core 因此不认识粒子 / 音频 / UI。
 */
public final class ContentSystem implements System {

    private final ContentRegistry registry;
    private final EffectQueue queue;
    private EffectSink sink = EffectSink.NONE;

    public ContentSystem(ContentRegistry registry, long seed) {
        this.registry = registry;
        this.queue = new EffectQueue(seed);
    }

    /** 独立子流名——与 93 个既有系统的子流互不干扰。 */
    @Override public String name() { return "content"; }

    /** 上层（Game）注入表现层出口；无头环境保持 {@link EffectSink#NONE}。 */
    public void setSink(EffectSink sink) { this.sink = (sink == null) ? EffectSink.NONE : sink; }

    public EffectQueue queue() { return queue; }
    public ContentRegistry registry() { return registry; }

    /** 取技能定义（未配置 → null）。 */
    public SkillDef skill(String skillId) {
        return SkillDef.parse(skillId, registry == null ? null : registry.get("skills", skillId));
    }

    /**
     * <b>施放技能</b> —— 技能链的入口（此前本方法缺失，导致 {@code skills/*.json} 全部是死数据）。
     *
     * <p>流程：① 校验（已学 / 冷却 / 体力）→ ② {@link SkillDef#selectTargets 确定性选目标}
     * → ③ 按每条效果的锚点（{@code at} 优先，其次 {@code target}）展开为若干队列项。
     *
     * <p>锚点语义：
     * <ul>
     *   <li>{@code TARGET} —— 对每个命中目标各提交一份（位置 = 目标坐标）→ 范围技真的打多个；</li>
     *   <li>{@code SELF} / {@code ORIGIN} —— 只提交一份（位置 = 玩家）→ 位移/自增益类。</li>
     * </ul>
     * 若 {@code TARGET} 锚点但一个目标都没有（空放），仍以玩家位置提交一份 —— 空放也要有观感，
     * 而不是"按键毫无反应"。消耗与冷却照样生效（空放是有代价的，这很关键）。
     *
     * @return 是否真的放出（false = 未学 / 冷却中 / 体力不足 / 无此技能）
     */
    public boolean cast(World w, Player p, String skillId, long nowTick) {
        if (w == null || p == null || skillId == null || skillId.isEmpty()) return false;
        JsonObject def = (registry == null) ? null : registry.get("skills", skillId);
        if (def == null) return false;
        if (!w.hasSkill(skillId)) return false;              // 未学会
        SkillDef sd = SkillDef.parse(skillId, def);
        if (sd == null) return false;
        if (p.skillCd > 0f) return false;                    // 冷却中
        if (p.stamina < sd.cost) return false;               // 体力不足

        // 施法原点取玩家胸口高度：半径判定用 3D 距离，站姿下与「脚下半径」体感接近
        float ox = p.x, oy = p.y + 1f, oz = p.z;
        List<Beast> targets = SkillDef.selectTargets(w, sd, ox, oy, oz);

        for (int i = 0; i < sd.effects.size(); i++) {
            JsonElement ee = sd.effects.get(i);
            if (ee == null || !ee.isJsonObject()) continue;
            JsonObject eo = ee.getAsJsonObject();
            Effect e = Effect.parse(eo);
            if (e == null) continue;
            String anchor = anchorOf(eo);
            if ("TARGET".equals(anchor) && !targets.isEmpty()) {
                for (int k = 0; k < targets.size(); k++) {
                    Beast b = targets.get(k);
                    queue.submit(e, b.x, b.y, b.z, nowTick);
                }
            } else {
                queue.submit(e, ox, oy, oz, nowTick);
            }
        }

        p.stamina -= sd.cost;
        p.skillCd = sd.cooldown;
        return true;
    }

    /** 锚点：{@code at}（SPAWN_FX/SPAWN_PARTICLE 用）优先，其次 {@code target}，最后 ORIGIN。 */
    private static String anchorOf(JsonObject eo) {
        JsonElement at = eo.get("at");
        if (at != null && !at.isJsonNull()) return at.getAsString();
        JsonElement tg = eo.get("target");
        if (tg != null && !tg.isJsonNull()) return tg.getAsString();
        return "ORIGIN";
    }

    /**
     * 每 tick 推进效果队列。{@code rng} 刻意未使用——见类注释「零 RNG 纪律」。
     * 若将来有人想在这里取随机，请先读 {@link EffectQueue#hash01} 的注释。
     */
    @Override public void update(World w, SeededRNG rng) {
        queue.tick(w.tick, sink, registry);
    }
}
