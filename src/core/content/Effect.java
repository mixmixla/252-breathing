package core.content;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * 内容层效果指令（Effect）——「万物效果」的统一原子。
 *
 * <p>技能、粒子、状态、剧情动作，最终都归约成一串 {@code Effect}。指令集是<b>封闭白名单</b>：
 * 白名单之外的类型在加载期被拒绝（铁律 3「未知即拒绝」），绝不静默忽略。
 *
 * <p>本类只做<b>解析与承载</b>，不做执行——执行归 {@link EffectQueue}。这样解析可在加载期
 * 一次完成（铁律 1），运行时零解析。
 *
 * <p><b>零漂移</b>：本类不持有任何 RNG，不读写网格。执行时是否改世界由 {@link EffectQueue}
 * 按指令类型决定，{@code SET_BLOCK} 是唯一会进指纹的指令。
 */
public final class Effect {

    /** 指令白名单（14 种）。任何不在其中的 type 都会被拒绝。 */
    public static final Set<String> WHITELIST = Collections.unmodifiableSet(
            new HashSet<String>(Arrays.asList(
                    "DAMAGE", "HEAL", "KNOCKBACK", "APPLY_BUFF", "SPAWN_PARTICLE", "SPAWN_FX",
                    "SUMMON", "TELEPORT", "SET_BLOCK", "PLAY_SFX", "SCREEN_SHAKE",
                    "GRANT_ITEM", "DIALOGUE", "GRANT_SKILL")));

    /**
     * 指令 → 它引用的内容表；返回 null 表示该指令不引用内容表。
     *
     * <p>{@code GRANT_SKILL} 的 {@code id} 是<b>技能/科技标记名</b>（写入 {@code World.skills}），
     * 不引用内容表 —— 它是「科技链解锁」与「用中学成长」共用的出口。
     */
    public static String refTable(String effectType) {
        if ("SPAWN_PARTICLE".equals(effectType)) return "particles";
        if ("SPAWN_FX".equals(effectType))       return "fx";
        if ("APPLY_BUFF".equals(effectType))     return "buffs";
        if ("SUMMON".equals(effectType))         return "beasts";
        if ("GRANT_ITEM".equals(effectType))     return "items";
        return null;
    }

    public final String type;      // 白名单指令名
    public final String id;        // 内容引用 id / 音效名 / 对白正文
    public final String target;    // SELF / TARGET / ORIGIN
    public final float amount;     // 伤害/治疗量
    public final float delay;      // 延迟秒数（调度用）
    public final float duration;   // 持续时间（状态类）
    public final float chance;     // 触发概率（hash 判定，不耗 RNG）
    public final float power;      // 击退/屏震强度

    private Effect(String type, String id, String target, float amount,
                   float delay, float duration, float chance, float power) {
        this.type = type; this.id = id; this.target = target;
        this.amount = amount; this.delay = delay; this.duration = duration;
        this.chance = chance; this.power = power;
    }

    /**
     * 解析一条效果指令。类型不在白名单 → 返回 {@code null}（由调用方记入加载报告）。
     * 缺省值：target=ORIGIN、amount=0、delay=0、duration=0、chance=1、power=0。
     */
    public static Effect parse(JsonObject o) {
        if (o == null) return null;
        String t = str(o, "type", "");
        if (!WHITELIST.contains(t)) return null;
        return new Effect(
                t,
                str(o, "id", ""),
                str(o, "target", "ORIGIN"),
                num(o, "amount", 0f),
                num(o, "delay", 0f),
                num(o, "duration", 0f),
                num(o, "chance", 1f),
                num(o, "power", 0f));
    }

    /** 指令是否引用内容表（用于加载期引用完整性校验）。 */
    public String refTable() { return refTable(type); }

    @Override public String toString() {
        return type + (id.isEmpty() ? "" : ":" + id) + (delay > 0f ? "@+" + delay : "");
    }

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
