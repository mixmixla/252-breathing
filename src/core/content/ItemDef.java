package core.content;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * 物品定义（{@code assets/content/items/*.json}）—— 内容平台第 12 类的解析形态。
 *
 * <p>schema（全部可选，缺省即合理值）：
 * <pre>
 * {
 *   "name":       "Stone Pickaxe",   // 显示名（默认 id）
 *   "stack":      1,                 // 堆叠上限（默认 64）
 *   "durability": 120,               // 最大耐久（默认 0 = 无耐久概念）
 *   "block":      "STONE",           // 可放置的方块 id（缺省 = 不可放置）
 *   "alsoDropsFrom": ["WHEAT_1","WHEAT_2"],  // 挖这些方块**也**掉我（缺省 = 只掉 block 那一项）
 *   "sowOn":      ["FARMLAND"],      // 可种植基质：只有下方是这些方块时才能放下（缺省 = 无要求）
 *   "sowNearWater": true,            // 还要求**水平四邻至少一格水**（缺省 = false，即无要求）
 *   "sowMinLight":  9,               // 还要求**最低天空光照** 0..15（缺省 = 0，即无要求）
 *   "tool":       true,              // 是工具：使用时消耗耐久
 *   "color":      "9AA0A8"           // HUD 色块（hex RRGGBB；缺省 = 灰）
 * }
 * </pre>
 *
 * <p><b>确定性</b>：纯数据解析，无 RNG；{@link Inventory} 的守恒律与耐久语义全部可在
 * 无头门禁中断言（INVENTORY 出口）。
 */
public final class ItemDef {

    public final String id;
    public final String name;
    public final int stack;        // 堆叠上限（>=1）
    public final int durability;   // 最大耐久（0 = 无耐久概念）
    public final String block;     // 可放置方块 id（null = 不可放置）
    public final boolean tool;     // 工具标记
    public final int color;        // HUD 色块（0xRRGGBB，0 = 默认灰）
    /** 挖这些方块**也**掉我（null/空 = 只掉 {@link #block} 那一项）。用于"一个物品覆盖同一作物的多个阶段"。 */
    public final String[] alsoDropsFrom;
    /** 可种植基质（方块 id 列表；null/空 = 无要求，随处可放）。 */
    public final String[] sowOn;
    /** 还要求水平四邻至少一格水（缺省 false = 无要求）。甘蔗的"必须长在水边"。 */
    public final boolean sowNearWater;
    /** 还要求最低天空光照 0..15（缺省 0 = 无要求）。喜光作物的"必须见得着天"。 */
    public final int sowMinLight;

    private ItemDef(String id, String name, int stack, int durability, String block, boolean tool, int color,
                    String[] alsoDropsFrom, String[] sowOn, boolean sowNearWater, int sowMinLight) {
        this.id = id; this.name = name; this.stack = stack;
        this.durability = durability; this.block = block; this.tool = tool; this.color = color;
        this.alsoDropsFrom = alsoDropsFrom; this.sowOn = sowOn;
        this.sowNearWater = sowNearWater; this.sowMinLight = sowMinLight;
    }

    /**
     * 该物品能否种在 {@code belowBlockId} 之上 —— <b>纯函数</b>（无 RNG / 不读世界），故可在无头门禁里断言真值表。
     *
     * <p>缺省语义刻意选"**宽松**"：{@code sowOn == null} ⇒ 恒 true。
     * 于是既有的花/蕨/蘑菇/藤蔓等**随处可摆**的玩法逐字不变，只有显式声明了基质的（种子/树苗）才受约束。
     * 这条"缺省不改旧行为"的取舍，与 {@code Facing.DEFAULT=+Y}、{@code seedLight} 新增种子是同一套纪律。
     *
     * <p><b>只管基质</b>。邻水 / 光照这两个**邻域**维度见 {@link #canSow(SowEnv)}；
     * 二者分开是为了让"只关心基质"的老调用方（与老门禁）逐字不变。
     */
    public boolean canSowOn(String belowBlockId) {
        if (sowOn == null || sowOn.length == 0) return true;
        if (belowBlockId == null) return false;
        for (String s : sowOn) if (s.equals(belowBlockId)) return true;
        return false;
    }

    /**
     * 放置时的**环境采样**（纯数据）—— 把"世界"折叠成 3 个可枚举的值，
     * 于是种植判据能在无头门禁里被**穷举断言**（第十五批）。
     *
     * <p><b>为什么不是把 World 传进来</b>：判据一旦读世界就不再是纯函数，
     * 也就再也没法被门禁钉住（"渲染层采样对不对"只能靠跑游戏看，这正是本项目
     * 反复吃过的"门禁验不了"的亏）。折成值对象后，**采样**（渲染层，读世界）与
     * **判据**（内容层，纯函数）彻底分离 —— 后者可 100% 覆盖。
     *
     * <p>{@code skyLight} 不是渲染管线里的光照贴图，而是**"见天程度"**：
     * 从目标格向上数连续空气格的个数（封顶 15）。它的语义代理是"能不能晒到太阳"，
     * 因为天空光在世界模型里本来就只由"上方有没有遮挡"决定。
     */
    public static final class SowEnv {
        /** 下方方块 id（null = 世界外 / 未知）。 */
        public final String below;
        /** 水平四邻中水的格数 0..4。 */
        public final int waterAdj;
        /** 见天程度 0..15（上方连续空气格数，封顶 15）。 */
        public final int skyLight;

        public SowEnv(String below, int waterAdj, int skyLight) {
            this.below = below;
            this.waterAdj = waterAdj;
            this.skyLight = skyLight;
        }

        @Override public String toString() {
            return "below=" + below + " waterAdj=" + waterAdj + " skyLight=" + skyLight;
        }
    }

    /**
     * 完整的种植判据（基质 + 邻水 + 光照）—— <b>纯函数</b>，缺省全无要求。
     *
     * <p>三个维度按"便宜的先判"排：基质是查表，邻水/光照是已采样的整数比较。
     * {@code env == null} 表示"拿不到环境"：只有<b>完全无要求</b>的物品才允许通过
     * （否则"环境未知"会变成"条件全过"的后门）。
     */
    public boolean canSow(SowEnv env) {
        if (!canSowOn(env == null ? null : env.below)) return false;
        if (env == null) return !sowNearWater && sowMinLight <= 0;
        if (sowNearWater && env.waterAdj <= 0) return false;
        if (sowMinLight > 0 && env.skyLight < sowMinLight) return false;
        return true;
    }

    /** 是否有**任何**种植要求（无要求者可跳过整个环境采样，热路径省事）。 */
    public boolean hasSowRule() {
        return (sowOn != null && sowOn.length > 0) || sowNearWater || sowMinLight > 0;
    }

    /** 需求描述（放置失败时的提示文案；纯函数 → 门禁可读）。例：{@code FARMLAND+LIGHT>=9}。 */
    public String sowNeed() {
        StringBuilder sb = new StringBuilder();
        if (sowOn != null) {
            for (String s : sowOn) { if (sb.length() > 0) sb.append('/'); sb.append(s); }
        }
        if (sowNearWater) { if (sb.length() > 0) sb.append('+'); sb.append("WATER-ADJ"); }
        if (sowMinLight > 0) { if (sb.length() > 0) sb.append('+'); sb.append("LIGHT>=").append(sowMinLight); }
        return sb.length() == 0 ? "(none)" : sb.toString();
    }

    /** 从注册表条目解析。{@code o} 为 null 时返回 null（调用方决定如何处理未知物品）。 */
    public static ItemDef from(String id, JsonObject o) {
        if (o == null) return null;
        String name = str(o, "name", id);
        int stack = Math.max(1, (int) num(o, "stack", 64f));
        int dur = Math.max(0, (int) num(o, "durability", 0f));
        String block = str(o, "block", null);
        boolean tool = bool(o, "tool", false);
        int color = 0;
        String cs = str(o, "color", null);
        if (cs != null) {
            try { color = Integer.parseInt(cs.replace("#", ""), 16) & 0xFFFFFF; }
            catch (RuntimeException ignored) { color = 0; }   // 坏色值 → 默认灰，不让一个 mod 拖垮加载
        }
        // 光照钳到 0..15（与世界光照量化同级）：写 99 的 mod 不该得到一个"永远种不下"的种子，
        // 也不该因为超界值而变得比"全光"还苛刻 —— 钳位是这里唯一合理的容错。
        int minLight = (int) num(o, "sowMinLight", 0f);
        if (minLight < 0) minLight = 0;
        if (minLight > 15) minLight = 15;
        return new ItemDef(id, name, stack, dur, block, tool, color,
                strArr(o, "alsoDropsFrom"), strArr(o, "sowOn"),
                bool(o, "sowNearWater", false), minLight);
    }

    /** HUD/日志摘要行。 */
    public String summary() {
        StringBuilder sb = new StringBuilder(name).append(" stack=").append(stack);
        if (durability > 0) sb.append(" dur=").append(durability);
        if (block != null) sb.append(" block=").append(block);
        if (tool) sb.append(" [tool]");
        if (hasSowRule()) sb.append(" sow=").append(sowNeed());
        return sb.toString();
    }

    // ---- 小工具（与 Effect/Rule 的解析风格一致）----

    private static String str(JsonObject o, String k, String def) {
        JsonElement e = o.get(k);
        return e != null && e.isJsonPrimitive() ? e.getAsString() : def;
    }
    private static float num(JsonObject o, String k, float def) {
        JsonElement e = o.get(k);
        return e != null && e.isJsonPrimitive() ? e.getAsFloat() : def;
    }
    /** 字符串数组字段；缺省 / 非数组 → null（调用方按"无此约束"处理）。 */
    private static String[] strArr(JsonObject o, String k) {
        JsonElement e = o.get(k);
        if (e == null || !e.isJsonArray()) return null;
        java.util.List<String> out = new java.util.ArrayList<String>();
        for (JsonElement x : e.getAsJsonArray())
            if (x != null && x.isJsonPrimitive()) out.add(x.getAsString());
        return out.isEmpty() ? null : out.toArray(new String[out.size()]);
    }

    private static boolean bool(JsonObject o, String k, boolean def) {
        JsonElement e = o.get(k);
        return e != null && e.isJsonPrimitive() ? e.getAsBoolean() : def;
    }
}
