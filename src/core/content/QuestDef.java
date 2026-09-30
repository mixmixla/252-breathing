package core.content;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 任务定义（{@code assets/content/quests/*.json}）。
 *
 * <p><b>为什么需要它</b>：EVAL-3 实测「quests 有校验、没有运行时」—— 任务 JSON 里写的内容
 * 在游戏里既接不到也完不成。本类把任务解析成<b>可执行的目标链</b>：
 * {@code 触发条件(on) → 达成 → 下一个节点}，最后落到 {@link Effect} 指令（与技能/规则共用同一套原子）。
 *
 * <p><b>语法</b>（与 JSON 一一对应）：
 * <pre>
 *   { "title": "...",
 *     "nodes": [ { "id": "start",
 *                  "text": "村北的田又被糟蹋了。",       // 中文正文（HUD 用 CjkFont 渲染）
 *                  "on": { "type": "kill", "beast": "ash_wolf", "count": 3 },
 *                  "next": "done" },
 *                { "id": "done", "text": "...", "effects": [ {...Effect...} ], "next": null } ] }
 * </pre>
 *
 * <p>节点的 {@code text} 是<b>完成该节点时</b>说的话（即"交任务"台词），因此它同时是
 * 玩家的目标提示与达成反馈 —— 少一份要维护的文案。
 *
 * <p><b>零漂移</b>：纯解析。任务进度是实体层开放状态（{@link core.world.QuestLog}），
 * 不进 {@code hashState()}；效果里唯一会进指纹的是 {@code SET_BLOCK} / {@code GRANT_SKILL}
 * （前者改网格、后者写 {@code World.skills}），本类不擅自执行任何指令。
 */
public final class QuestDef {

    /** 触发类型白名单。不在其中的 {@code on.type} 在加载期被拒绝。 */
    public static final String ON_KILL = "kill";

    /** 一个任务节点。 */
    public static final class Node {
        public final String id;
        public final String text;        // 中文正文（完成该节点时显示）
        public final String onType;      // kill / ""（无触发 = 立即达成）
        public final String onBeast;     // kill 的目标内容兽 id（"" = 任意兽）
        public final int onCount;        // kill 的需求数量
        public final String next;        // 下一个节点 id（"" = 终点）
        public final List<Effect> effects;
        public final int index;          // 在 nodes 中的下标（进度用）

        private Node(String id, String text, String onType, String onBeast, int onCount,
                     String next, List<Effect> effects, int index) {
            this.id = id; this.text = text; this.onType = onType; this.onBeast = onBeast;
            this.onCount = onCount; this.next = next;
            this.effects = Collections.unmodifiableList(effects);
            this.index = index;
        }
    }

    public final String id;          // 文件名
    public final String title;
    public final List<Node> nodes;

    private QuestDef(String id, String title, List<Node> nodes) {
        this.id = id; this.title = title;
        this.nodes = Collections.unmodifiableList(nodes);
    }

    /** 按 id 取节点；不存在返回 {@code null}。 */
    public Node node(String nid) {
        if (nid == null) return null;
        for (Node n : nodes) if (n.id.equals(nid)) return n;
        return null;
    }

    public Node first() { return nodes.isEmpty() ? null : nodes.get(0); }

    /**
     * 解析一个任务。任一硬性缺失（无 nodes / 节点 id 重复 / next 指向不存在的节点 /
     * on.type 不在白名单）→ 记入 {@code errors} 并返回 {@code null}。
     *
     * @param knownBeasts 已知内容兽 id 集合（校验 {@code on.beast} 不悬空）；可空 = 跳过该校验
     */
    public static QuestDef parse(String id, JsonObject o, java.util.Set<String> knownBeasts,
                                 List<String> errors) {
        if (o == null) { errors.add("quest " + id + ": not an object"); return null; }
        String title = str(o, "title", id);
        JsonElement ns = o.get("nodes");
        if (ns == null || !ns.isJsonArray() || ns.getAsJsonArray().size() == 0) {
            errors.add("quest " + id + ": missing nodes[]");
            return null;
        }
        List<Node> nodes = new ArrayList<Node>();
        java.util.Set<String> ids = new java.util.HashSet<String>();
        int i = 0;
        for (JsonElement e : ns.getAsJsonArray()) {
            if (!e.isJsonObject()) { errors.add("quest " + id + ": node[" + i + "] not an object"); i++; continue; }
            JsonObject no = e.getAsJsonObject();
            String nid = str(no, "id", "");
            if (nid.isEmpty()) { errors.add("quest " + id + ": node[" + i + "] missing id"); i++; continue; }
            if (!ids.add(nid)) { errors.add("quest " + id + ": duplicate node id " + nid); i++; continue; }

            String onType = "", onBeast = "";
            int onCount = 0;
            JsonObject on = obj(no, "on");
            if (on != null) {
                onType = str(on, "type", "");
                if (!ON_KILL.equals(onType)) {
                    errors.add("quest " + id + "/" + nid + ": unknown on.type '" + onType + "'");
                    i++; continue;
                }
                onBeast = str(on, "beast", "");
                onCount = (int) num(on, "count", 0);
                if (onCount <= 0) { errors.add("quest " + id + "/" + nid + ": on.count must be > 0"); i++; continue; }
                if (!onBeast.isEmpty() && knownBeasts != null && !knownBeasts.contains(onBeast)) {
                    errors.add("quest " + id + "/" + nid + ": on.beast '" + onBeast + "' is not a known beast");
                    i++; continue;
                }
            }

            List<Effect> fx = new ArrayList<Effect>();
            JsonElement es = no.get("effects");
            if (es != null && es.isJsonArray()) {
                int j = 0;
                for (JsonElement fe : es.getAsJsonArray()) {
                    if (!fe.isJsonObject()) { errors.add("quest " + id + "/" + nid + ": effect[" + j + "] not an object"); j++; continue; }
                    Effect ef = Effect.parse(fe.getAsJsonObject());
                    if (ef == null) errors.add("quest " + id + "/" + nid + ": unknown effect at [" + j + "]");
                    else fx.add(ef);
                    j++;
                }
            }
            nodes.add(new Node(nid, str(no, "text", ""), onType, onBeast, onCount,
                    str(no, "next", ""), fx, nodes.size()));
            i++;
        }
        if (nodes.isEmpty()) { errors.add("quest " + id + ": no valid node"); return null; }

        // 引用完整性：next 必须存在（或为空 = 终点）
        for (Node n : nodes) {
            if (n.next != null && !n.next.isEmpty()) {
                boolean found = false;
                for (Node m : nodes) if (m.id.equals(n.next)) { found = true; break; }
                if (!found) errors.add("quest " + id + "/" + n.id + ": next '" + n.next + "' does not exist");
            }
        }
        return new QuestDef(id, title, nodes);
    }

    @Override public String toString() { return id + "(" + title + "/" + nodes.size() + " nodes)"; }

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
