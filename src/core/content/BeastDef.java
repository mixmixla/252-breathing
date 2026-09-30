package core.content;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * 内容兽定义（{@code assets/content/beasts/*.json}）。
 *
 * <p><b>为什么需要它</b>：EVAL-3 实测「内容层的怪永远不会刷」—— 运行时 {@code Beast} 只有
 * 4 个硬编码原型、没有内容 id，于是 {@code beasts/ash_wolf.json} 里的 {@code hp/atk/drop}
 * 全是<b>死配置</b>。本类把那 4 个原型降级为「AI 形状」，把<b>数值与身份</b>交给内容：
 * 同一个 {@code archetype}（0..3）可以挂多只不同内容兽，各自有自己的血量、伤害、掉落。
 *
 * <p><b>与 archetype 的关系</b>：{@code archetype} 决定移动/攻击的<b>形状</b>
 * （近战/远程、前摇长度、接触半径），内容只覆盖<b>数值</b>。这样"加一只新怪"不需要写代码。
 *
 * <p>单位：{@code speed} 是<b>格/秒</b>（内容侧对人类友好），落到 {@code Beast.speed}
 * 时除以 {@link #TICKS_PER_SEC} 变成"每 tick 步长"。
 *
 * <p><b>零漂移</b>：纯解析，不碰 RNG / 网格。
 */
public final class BeastDef {

    /** 仿真步频（20 Hz）—— 内容侧的"格/秒"与实体的"格/tick"之间的唯一换算口径。 */
    public static final int TICKS_PER_SEC = 20;

    public final String id;        // 文件名
    public final String name;      // 显示名（HUD / 日志）
    public final int archetype;    // 0..Beast.N_TYPES-1：AI 形状
    public final int hp;
    public final int atk;
    public final float speedPerTick;   // 已换算：speed/20
    public final float aggroRange;
    public final int packMin, packMax;
    public final String dropItem;      // 可空
    public final float dropChance;     // 0..1

    private BeastDef(String id, String name, int archetype, int hp, int atk, float speedPerTick,
                     float aggroRange, int packMin, int packMax, String dropItem, float dropChance) {
        this.id = id; this.name = name; this.archetype = archetype;
        this.hp = hp; this.atk = atk; this.speedPerTick = speedPerTick;
        this.aggroRange = aggroRange; this.packMin = packMin; this.packMax = packMax;
        this.dropItem = dropItem; this.dropChance = dropChance;
    }

    /**
     * 解析一只内容兽。任一必填项非法 → 返回 {@code null} 并记入 {@code errors}
     * （铁律「未知即拒绝」：宁可加载期报错，也不要运行时静默出一只 0 血怪）。
     *
     * @param maxArchetype 合法 archetype 上界（= {@code Beast.N_TYPES - 1}），由调用方传入以免本类依赖实体层
     */
    public static BeastDef parse(String id, JsonObject o, int maxArchetype, java.util.List<String> errors) {
        if (o == null) { errors.add("beast " + id + ": not an object"); return null; }
        String name = str(o, "name", id);
        int archetype = (int) num(o, "archetype", 0);
        if (archetype < 0 || archetype > maxArchetype) {
            errors.add("beast " + id + ": archetype " + archetype + " out of range 0.." + maxArchetype);
            return null;
        }
        int hp = (int) num(o, "hp", 0);
        int atk = (int) num(o, "atk", 0);
        float speed = num(o, "speed", 0f);
        if (hp <= 0)  { errors.add("beast " + id + ": hp must be > 0"); return null; }
        if (atk <= 0) { errors.add("beast " + id + ": atk must be > 0"); return null; }
        if (speed <= 0f) { errors.add("beast " + id + ": speed must be > 0"); return null; }

        float aggro = num(o, "aggroRange", 0f);
        int packMin = 1, packMax = 1;
        JsonObject beh = obj(o, "behavior");
        if (beh != null) {
            if (aggro <= 0f) aggro = num(beh, "aggroRange", 0f);
            JsonElement ps = beh.get("packSize");
            if (ps != null && ps.isJsonArray()) {
                JsonArray a = ps.getAsJsonArray();
                if (a.size() >= 1) packMin = (int) a.get(0).getAsFloat();
                if (a.size() >= 2) packMax = (int) a.get(1).getAsFloat();
                if (packMin < 1) packMin = 1;
                if (packMax < packMin) packMax = packMin;
            }
        }
        String dropItem = "";
        float dropChance = 0f;
        JsonElement dr = o.get("drop");
        if (dr != null && dr.isJsonArray() && dr.getAsJsonArray().size() > 0) {
            JsonElement first = dr.getAsJsonArray().get(0);
            if (first != null && first.isJsonObject()) {
                dropItem = str(first.getAsJsonObject(), "item", "");
                dropChance = num(first.getAsJsonObject(), "chance", 0f);
            }
        }
        if (dropChance < 0f) dropChance = 0f;
        if (dropChance > 1f) dropChance = 1f;

        return new BeastDef(id, name, archetype, hp, atk, speed / (float) TICKS_PER_SEC,
                aggro > 0f ? aggro : 999f, packMin, packMax, dropItem, dropChance);
    }

    /** 是否由内容驱动掉落（有 item 且概率 > 0）。 */
    public boolean drops() { return dropChance > 0f && dropItem != null && !dropItem.isEmpty(); }

    @Override public String toString() {
        return id + "(" + name + "/arch" + archetype + "/hp" + hp + ")"
                + (drops() ? "+drop:" + dropItem + "@" + dropChance : "");
    }

    private static JsonObject obj(JsonObject o, String k) {
        JsonElement e = o.get(k);
        return (e != null && e.isJsonObject()) ? e.getAsJsonObject() : null;
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
