package core.content;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 科技定义（Tech）—— 饥荒 / 缺氧式的<b>配方图节点</b>。
 *
 * <p>一个 tech = 「满足前置 → 解锁一批标记」。前置有两类：
 * <ul>
 *   <li>{@link #requires}：其他 tech 的 id，或任意 {@code World.skills} 标记
 *       （世界既有机制，如 {@code _blueprint_palisade}）—— 因此科技图能与既有系统对接；</li>
 *   <li>{@link #minProsperity}：繁荣门槛。它让"科技推进"跟世界发展节奏绑定
 *       （避免开局全解锁，也让科技成为"世界长起来了"的奖励）。</li>
 * </ul>
 *
 * <p>解锁的产物写进 {@code World.skills}（{@link TechDef#unlocks}），并可派发一串
 * {@link Effect}（横幅、粒子、解锁音效…）—— <b>动作不发明新语言</b>，与技能/规则共用同一套原子。
 *
 * <p><b>零漂移说明</b>：{@code World.skills} 在 {@code hashState()} 之内，
 * 所以解锁会<b>有意地</b>改变指纹 —— 这是"解锁真的改变了世界"的正确表现，
 * 而不是漂移。门禁世界里不启用 {@link TechTree}，故既有基线纹丝不动。
 */
public final class TechDef {

    public final String id;
    public final String name;
    public final List<String> requires;      // 前置（tech id 或任意 skill 标记）
    public final float minProsperity;        // 繁荣门槛（0 = 无门槛）
    public final String station;             // 制作站（饥荒式空间约束；展示用，P4 接判定）
    public final Map<String, Float> cost;    // 资源成本（展示用；需背包才参与判定）
    public final List<String> unlocks;       // 解锁时写入 World.skills 的标记
    public final List<Effect> effects;       // 解锁时派发
    public final int order;                  // 注册序（确定性）

    private TechDef(String id, String name, List<String> requires, float minProsperity,
                    String station, Map<String, Float> cost, List<String> unlocks,
                    List<Effect> effects, int order) {
        this.id = id; this.name = name;
        this.requires = Collections.unmodifiableList(requires);
        this.minProsperity = minProsperity;
        this.station = station;
        this.cost = Collections.unmodifiableMap(cost);
        this.unlocks = Collections.unmodifiableList(unlocks);
        this.effects = Collections.unmodifiableList(effects);
        this.order = order;
    }

    public static TechDef parse(String id, JsonObject o, List<String> errors, int order) {
        if (o == null) return null;
        String name = str(o, "name", id);

        List<String> req = strList(o.get("requires"));
        List<String> unl = strList(o.get("unlocks"));
        Map<String, Float> cost = new LinkedHashMap<String, Float>();
        JsonElement ce = o.get("cost");
        if (ce != null && ce.isJsonObject()) {
            for (Map.Entry<String, JsonElement> en : ce.getAsJsonObject().entrySet()) {
                try { cost.put(en.getKey(), en.getValue().getAsFloat()); } catch (RuntimeException ignored) { }
            }
        }

        List<Effect> fx = new ArrayList<Effect>();
        JsonElement fe = o.get("effects");
        if (fe != null && fe.isJsonArray()) {
            int i = 0;
            for (JsonElement e : fe.getAsJsonArray()) {
                if (!e.isJsonObject()) { i++; continue; }
                Effect ef = Effect.parse(e.getAsJsonObject());
                if (ef == null) errors.add("tech " + id + ": unknown effect at effects[" + i + "]");
                else fx.add(ef);
                i++;
            }
        }

        if (unl.isEmpty() && fx.isEmpty()) {
            // 什么都不解锁、也不派发效果 —— 这种 tech 是无意义的（多半是写漏了）
            errors.add("tech " + id + ": nothing to unlock (unlocks[] and effects[] both empty)");
        }

        return new TechDef(id, name, req, num(o, "minProsperity", 0f),
                str(o, "station", ""), cost, unl, fx, order);
    }

    /** 前置的完整清单（requires 去重后的原样）。 */
    public List<String> allRequirements() { return requires; }

    @Override public String toString() {
        return id + "(req=" + requires.size() + ", unlocks=" + unlocks.size() + ")";
    }

    private static List<String> strList(JsonElement e) {
        List<String> out = new ArrayList<String>();
        if (e != null && e.isJsonArray()) {
            for (JsonElement x : e.getAsJsonArray()) if (!x.isJsonNull()) out.add(x.getAsString());
        }
        return out;
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
