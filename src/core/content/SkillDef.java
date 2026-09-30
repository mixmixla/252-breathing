package core.content;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import core.world.Beast;
import core.world.World;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * 技能定义（{@code skills/*.json}）—— 解析 + <b>确定性目标选择</b>。
 *
 * <p>这是「技能链」缺失的那一环：内容表里早就有 {@code targeting{shape,radius,maxTargets}}
 * 与 {@code effects[]}，但此前没有任何代码读 targeting —— 以至于 Flame Burst 名义是
 * 「半径 4 打 6 个」，实际走的是 {@code nearestBeast(w,5.0f)} 单目标硬编码。
 *
 * <h3>零 RNG / 零漂移</h3>
 * 目标选择是<b>纯函数</b>：按到原点的距离升序排，距离相同按 {@link World#beasts} 索引升序排，
 * 取前 {@code maxTargets} 个。没有随机、没有 tie-break 抖动 —— 同种子同输入必然选到同一批目标。
 * 敌兵是实体层，不进 {@link World#hashState()}，故本类不改变任何指纹。
 *
 * <h3>targeting 两种形状</h3>
 * <ul>
 *   <li>{@code sphere} —— 以施法原点为球心、{@code radius} 为半径的球内全部敌兵（≤ maxTargets）；</li>
 *   <li>{@code point}  —— 同样按半径筛选，但只取最近的那 {@code maxTargets} 个（单体技）。</li>
 * </ul>
 */
public final class SkillDef {

    public final String id;          // 内容 id（文件名，小写）
    public final String name;        // 显示名
    public final String anim;        // 动作片段提示（attack / roll …）
    public final int cost;           // 体力消耗
    public final float cooldown;     // 冷却（秒）
    public final int tier;           // 技能树层级
    public final int costSouls;      // 学习消耗（魂）
    public final String[] requires;  // 前置技能 id
    public final String art;         // 可选：学会时解锁的战技（Weapons.Art 名）
    public final String shape;       // sphere / point
    public final float radius;       // 作用半径（格）
    public final int maxTargets;     // 命中上限
    public final JsonArray effects;  // 效果链原始 JSON（交给 EffectQueue）

    public static final String SHAPE_SPHERE = "sphere";
    public static final String SHAPE_POINT  = "point";

    private SkillDef(String id, JsonObject o) {
        this.id = id;
        this.name = str(o, "name", id);
        this.anim = str(o, "anim", "attack");
        this.cost = (int) num(o, "cost", 0f);
        this.cooldown = num(o, "cooldown", 0f);
        this.tier = (int) num(o, "tier", 1f);
        this.costSouls = (int) num(o, "costSouls", 0f);
        this.art = str(o, "art", "");

        JsonElement tge = o.get("targeting");
        JsonObject tg = (tge != null && tge.isJsonObject()) ? tge.getAsJsonObject() : new JsonObject();
        this.shape = str(tg, "shape", SHAPE_POINT);
        this.radius = num(tg, "radius", 2.0f);
        this.maxTargets = (int) num(tg, "maxTargets", 1f);

        List<String> req = new ArrayList<String>();
        JsonElement re = o.get("requires");
        if (re != null && re.isJsonArray()) {
            for (JsonElement e : re.getAsJsonArray()) {
                if (e != null && !e.isJsonNull()) req.add(e.getAsString());
            }
        }
        this.requires = req.toArray(new String[0]);

        JsonElement ee = o.get("effects");
        this.effects = (ee != null && ee.isJsonArray()) ? ee.getAsJsonArray() : new JsonArray();
    }

    public static SkillDef parse(String id, JsonObject o) {
        return (o == null) ? null : new SkillDef(id, o);
    }

    public boolean requiresArt() { return art != null && !art.isEmpty(); }

    /**
     * 确定性目标选择（纯函数）。
     *
     * @param w     世界（读 {@link World#beasts}）
     * @param def   技能定义
     * @param x,y,z 施法原点
     * @return 命中目标（按「距原点升序、同距按下标升序」；长度 ≤ maxTargets）
     */
    public static List<Beast> selectTargets(World w, SkillDef def, float x, float y, float z) {
        List<Beast> out = new ArrayList<Beast>();
        if (w == null || def == null || def.maxTargets <= 0) return out;
        final float r2 = def.radius * def.radius;

        final List<Beast> cand = new ArrayList<Beast>();
        final List<Float> dist = new ArrayList<Float>();
        for (int i = 0; i < w.beasts.size(); i++) {
            Beast b = w.beasts.get(i);
            if (b == null || b.hp <= 0) continue;
            float dx = b.x - x, dy = b.y - y, dz = b.z - z;
            float q = dx * dx + dy * dy + dz * dz;
            if (q > r2) continue;
            cand.add(b);                 // cand 的下标序天然 = beasts 下标升序 → 天然 tie-break
            dist.add(q);
        }

        Integer[] order = new Integer[cand.size()];
        for (int i = 0; i < order.length; i++) order[i] = i;
        Arrays.sort(order, new Comparator<Integer>() {
            @Override public int compare(Integer a, Integer b) {
                float da = dist.get(a), db = dist.get(b);
                if (da != db) return da < db ? -1 : 1;
                return a - b;            // 同距：下标升序（确定性 tie-break）
            }
        });

        for (int k = 0; k < order.length && out.size() < def.maxTargets; k++) {
            out.add(cand.get(order[k]));
        }
        return out;
    }

    @Override public String toString() {
        return id + "[" + shape + " r=" + radius + " n<=" + maxTargets + " cost=" + cost + " cd=" + cooldown + "]";
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
