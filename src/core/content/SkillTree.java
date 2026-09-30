package core.content;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import core.world.Player;
import core.world.Weapons;
import core.world.World;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 技能树（DNF 式）—— 技能不再是"捡到就能用"，而是**按前置与点数解锁**。
 *
 * <p>与科技链（{@link TechTree}）的区别：
 * <ul>
 *   <li>科技链解锁的是 <b>世界</b>能力（写 {@code World.skills}，进指纹）；</li>
 *   <li>技能树解锁的是 <b>玩家</b>能力（写 {@code Player.abilities}，**不进指纹**）。</li>
 * </ul>
 * 这个分工很重要：把科技当"世界进程"，把技能当"角色养成"，两者互不干扰。
 *
 * <p>节点字段（写在 {@code assets/content/skills/*.json} 里）：
 * <pre>
 *   "tier": 1,                    // 层级（UI 分列用）
 *   "requires": ["flame_burst"],  // 前置技能 id（同一张表内 → 参与环检测）
 *   "costSouls": 25,              // 学习消耗（魂）
 *   "art": "CLEAVE"               // 可选：解锁的战技原型（LUNGE/CLEAVE/SHOT）
 * </pre>
 *
 * <p><b>零漂移</b>：只读 {@code Player.souls}，只写 {@code Player.abilities / arts}
 * —— 全是玩家实体状态，{@code hashState()} 根本不看它们。
 */
public final class SkillTree {

    /** 技能树节点。 */
    public static final class Node {
        public final String id;
        public final String name;
        public final List<String> requires;
        public final int tier;
        public final int costSouls;
        public final String art;

        Node(String id, String name, List<String> requires, int tier, int costSouls, String art) {
            this.id = id; this.name = name;
            this.requires = Collections.unmodifiableList(requires);
            this.tier = tier; this.costSouls = costSouls; this.art = art;
        }

        @Override public String toString() {
            return id + "(t" + tier + ", " + costSouls + "souls" + (requires.isEmpty() ? "" : ", req=" + requires) + ")";
        }
    }

    private final List<Node> nodes;
    private final Map<String, Node> byId = new LinkedHashMap<String, Node>();

    public SkillTree(ContentRegistry reg) {
        List<Node> out = new ArrayList<Node>();
        if (reg != null) {
            for (Map.Entry<String, JsonObject> en : reg.of("skills").entrySet()) {
                JsonObject o = en.getValue();
                out.add(new Node(en.getKey(), str(o, "name", en.getKey()),
                        strList(o.get("requires")),
                        (int) num(o, "tier", 1f),
                        (int) num(o, "costSouls", 0f),
                        str(o, "art", "")));
            }
        }
        this.nodes = Collections.unmodifiableList(out);
        for (int i = 0; i < out.size(); i++) byId.put(out.get(i).id, out.get(i));
    }

    public List<Node> nodes() { return nodes; }
    public int size()         { return nodes.size(); }
    public Node node(String id) { return byId.get(id); }

    // ------------------------------------------------------------------
    // 学习
    // ------------------------------------------------------------------

    /** 是否可以学习（未学过 + 前置满足 + 魂足够）。 */
    public boolean canLearn(Node n, Player p) {
        if (n == null || p == null) return false;
        if (p.abilities.contains(n.id)) return false;
        if (p.souls < n.costSouls) return false;
        for (int i = 0; i < n.requires.size(); i++) {
            if (!p.abilities.contains(n.requires.get(i))) return false;
        }
        return true;
    }

    /** 学习：扣魂 → 写能力标记 →（可选）解锁战技原型。返回是否真的学到了。 */
    public boolean learn(Node n, Player p, World w) {
        if (!canLearn(n, p)) return false;
        p.souls -= n.costSouls;
        p.abilities.add(n.id);
        if (n.art != null && !n.art.isEmpty()) {
            try { p.arts.add(Weapons.Art.valueOf(n.art.trim().toUpperCase())); }
            catch (RuntimeException ignored) { }
        }
        if (w != null) w.log("player", "learn", n.id, "skill_tree:t" + n.tier);
        return true;
    }

    /** 当前<b>可学</b>的技能 id（前置与魂都够，但还没学）—— UI 的"现在能点"。 */
    public List<String> availableIds(Player p) {
        List<String> out = new ArrayList<String>();
        if (p == null) return out;
        for (int i = 0; i < nodes.size(); i++) {
            Node n = nodes.get(i);
            if (canLearn(n, p)) out.add(n.id);
        }
        return out;
    }

    /** 已学个数。 */
    public int learnedCount(Player p) {
        if (p == null) return 0;
        int n = 0;
        for (int i = 0; i < nodes.size(); i++) if (p.abilities.contains(nodes.get(i).id)) n++;
        return n;
    }

    /** 技能图边表（供环检测）。 */
    public Map<String, List<String>> edges() {
        Map<String, List<String>> e = new LinkedHashMap<String, List<String>>();
        for (int i = 0; i < nodes.size(); i++) e.put(nodes.get(i).id, nodes.get(i).requires);
        return e;
    }

    public List<String> ids() {
        List<String> out = new ArrayList<String>();
        for (int i = 0; i < nodes.size(); i++) out.add(nodes.get(i).id);
        return out;
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
