package core.systems;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import core.rng.SeededRNG;
import core.world.Beast;
import core.world.World;

import java.util.ArrayList;
import java.util.List;

/**
 * 状态（buff）运行时 —— 内容层 {@code APPLY_BUFF} 的<b>确定性执行者</b>。
 *
 * <p>这是「技能链」的最后一环：{@code buffs/burning.json} 里写着
 * {@code duration 4.0 / tickEvery 0.5 / modifiers.speed -0.15 / effects[DAMAGE 3 SELF]}，
 * 但此前 {@code APPLY_BUFF} 落到 {@link core.content.EffectSink#simulate} 后没有任何分支承接，
 * 被<b>静默丢弃</b> —— 燃烧从未生效。本类把它接上。
 *
 * <h3>与火杠杆的分工（有意为之，不是重复）</h3>
 * <ul>
 *   <li>{@link FireSpreadSystem} —— <b>站在火里</b>：环境伤害，离开火格立刻停止（及时、空间性）；</li>
 *   <li>{@link BuffSystem} —— <b>被点燃之后</b>：持续灼烧，离开火源仍在燃烧（延迟、时间性）。</li>
 * </ul>
 * 两者语义互补：一个是"火在那儿"，一个是"你身上着了"。因此都保留，但都只经
 * {@link core.world.Player#hitBeast} 这一条伤害入口。
 *
 * <h3>零 RNG / 零漂移</h3>
 * 本类不持有也不消耗任何 RNG（{@code update} 的 rng 参数刻意不使用）—— 每 {@code tickEvery}
 * 定时触发、整数递减，全是确定性整数运算。副作用只落在实体层（敌兵 HP / {@link Beast#slowMul}），
 * 不进 {@link World#hashState()}。且<b>无 buff 时 update 提前返回</b>，故注册本系统
 * 不会改变任何既有测试的指纹。
 */
public final class BuffSystem implements System {

    private static final int TICKS_PER_SEC = 20;

    /** 一个生效中的状态实例。 */
    public static final class Active {
        public final Beast target;
        public final String id;
        public final int tickEvery;     // 触发间隔（tick，≥1）
        public final int dot;           // 每次触发的伤害（0 = 无伤害型状态）
        public final float slowMul;     // 移速倍率（1 = 不影响）
        public int remain;              // 剩余 tick
        public int acc;                 // 距下次触发的累积

        Active(Beast target, String id, int remain, int tickEvery, int dot, float slowMul) {
            this.target = target; this.id = id;
            this.remain = remain; this.tickEvery = tickEvery;
            this.dot = dot; this.slowMul = slowMul;
        }
    }

    private final List<Active> actives = new ArrayList<Active>();
    private int appliedTotal = 0;       // 诊断：累计施加次数
    private int dotTickTotal = 0;       // 诊断：累计 DOT 触发次数

    @Override public String name() { return "buffs"; }

    /**
     * 施加一个状态（{@code buffs/*.json} 的定义）。
     *
     * <p>同目标同 id 再施加 → <b>刷新剩余时长</b>（不叠加 DOT，避免"多段技能瞬间烧穿"）。
     *
     * @param def     buff 定义（来自 ContentRegistry 的 "buffs" 表）
     * @param id      buff id
     * @param target  目标敌兵
     * @return 是否成功施加
     */
    public boolean apply(JsonObject def, String id, Beast target) {
        if (def == null || target == null || id == null || id.isEmpty()) return false;
        float durSec = num(def, "duration", 0f);
        int remain = Math.round(durSec * TICKS_PER_SEC);
        if (remain <= 0) return false;

        float everySec = num(def, "tickEvery", 0.5f);
        int every = Math.max(1, Math.round(everySec * TICKS_PER_SEC));

        int dot = 0;
        JsonElement ee = def.get("effects");
        if (ee != null && ee.isJsonArray()) {
            JsonArray arr = ee.getAsJsonArray();
            for (int i = 0; i < arr.size(); i++) {
                if (!arr.get(i).isJsonObject()) continue;
                JsonObject e = arr.get(i).getAsJsonObject();
                String t = str(e, "type", "");
                if ("DAMAGE".equals(t)) dot += (int) num(e, "amount", 0f);
            }
        }

        float slowMul = 1f;
        JsonElement me = def.get("modifiers");
        if (me != null && me.isJsonObject()) {
            JsonObject mods = me.getAsJsonObject();
            if (mods.has("speed")) slowMul = clampMul(1f + mods.get("speed").getAsFloat());
        }

        // 同目标同 id → 刷新（确定性：找到第一条即刷新，顺序无关）
        for (int i = 0; i < actives.size(); i++) {
            Active a = actives.get(i);
            if (a.target == target && a.id.equals(id)) {
                a.remain = remain;
                return true;
            }
        }
        actives.add(new Active(target, id, remain, every, dot, slowMul));
        appliedTotal++;
        return true;
    }

    /**
     * 每 tick 推进：刷新增益 → 到期结算 DOT → 回收失效项。
     * {@code rng} 刻意不使用（见类注释「零 RNG」）。
     */
    @Override public void update(World w, SeededRNG rng) {
        // 移速倍率先统一复位，再按生效状态重算 —— 于是「buff 到期」自动恢复原速，
        // 且 BeastSystem 只需读 Beast.slowMul（不必认识 BuffSystem，避免系统间循环依赖）。
        for (int i = 0; i < w.beasts.size(); i++) w.beasts.get(i).slowMul = 1f;
        if (actives.isEmpty()) return;

        for (int i = actives.size() - 1; i >= 0; i--) {
            Active a = actives.get(i);
            Beast b = a.target;

            // 目标已死/已被移出世界 → 直接回收
            if (b == null || b.hp <= 0 || !w.beasts.contains(b)) {
                actives.remove(i);
                continue;
            }

            if (a.dot > 0 && w.player != null) {
                a.acc++;
                if (a.acc >= a.tickEvery) {
                    a.acc = 0;
                    w.player.hitBeast(w, b, a.dot, "buff");   // 唯一伤害入口
                    dotTickTotal++;
                }
            }
            if (a.slowMul != 1f && b.hp > 0) {
                b.slowMul *= a.slowMul;
            }
            a.remain--;
            if (a.remain <= 0) actives.remove(i);
        }
    }

    // ---------- 观测（门禁 / HUD） ----------

    public int activeCount() { return actives.size(); }
    public int appliedTotal() { return appliedTotal; }
    public int dotTickTotal() { return dotTickTotal; }

    /** 目标身上是否有某状态生效（渲染层做"燃烧中"外观）。 */
    public boolean has(Beast b, String id) {
        if (b == null) return false;
        for (int i = 0; i < actives.size(); i++) {
            Active a = actives.get(i);
            if (a.target == b && a.id.equals(id)) return true;
        }
        return false;
    }

    /** 清除全部状态（测试/重开世界用）。 */
    public void clear() { actives.clear(); }

    private static float clampMul(float v) { return v < 0.1f ? 0.1f : (v > 3f ? 3f : v); }

    private static String str(JsonObject o, String k, String dflt) {
        JsonElement e = o.get(k);
        return (e == null || e.isJsonNull()) ? dflt : e.getAsString();
    }

    private static float num(JsonObject o, String k, float dflt) {
        JsonElement e = o.get(k);
        if (e == null || e.isJsonNull()) return dflt;
        try { return e.getAsFloat(); } catch (RuntimeException ex) { return dflt; }
    }
}
