package core.content;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import core.content.Inventory;

/**
 * 配方 / 冶炼链的**显式契约** —— 内容键 → 真实物品。
 *
 * <p><b>它解决什么</b>：内容层 {@code techs/*.json} 的 {@code cost} / {@code unlocks} 用的是
 * **资源键**（{@code ore} / {@code coal} / {@code iron_bar}），但 {@link TechTree} 原先
 * 把它们当**不透明字符串**处理 —— 「采集 → 冶炼 → 成品」这条产业链只是"碰巧能用"：
 * 键名写错、物品不存在、数量不足，全都没有任何提示（静默跳过）。
 *
 * <p>这正是审计 C8 把 {@code coal}/{@code ore}/{@code iron_bar} 记成「死内容键」的原因：
 * 运行时确实读了它们，但代码里<b>连一次字面引用都没有</b> —— 没有第二个人能从源码里看出
 * 这三个键的语义。本类把语义写死在源码里：
 * <ul>
 *   <li>哪些键是<b>资源</b>（且必须对应真实物品）；</li>
 *   <li>标准配方是什么（{@code ore 3 + coal 2 → iron_bar 1}）；</li>
 *   <li>未知键<b>响亮记录</b>，绝不静默忽略（与 SKIP 清单同一条纪律）。</li>
 * </ul>
 *
 * <p><b>零 RNG / 零副作用</b>：本类是纯查表 + 背包结算，不碰世界、不碰随机、不进指纹。
 */
public final class RecipeBook {

    // ---- 资源键（内容层用这些键名；同时必须是真实物品 id）----
    /** 矿石输入（{@code items/ore.json}）。 */
    public static final String ORE = "ore";
    /** 燃料 / 还原剂（{@code items/coal.json}）。 */
    public static final String COAL = "coal";
    /** 冶炼成品（{@code items/iron_bar.json}）。 */
    public static final String IRON_BAR = "iron_bar";

    /** 一条配方。 */
    public static final class Recipe {
        public final String id;                    // 配方 id（= 解锁它的科技 id）
        public final Map<String, Integer> inputs;  // 输入：资源键 → 数量
        public final String output;                // 输出物品 id
        public final int outputCount;

        Recipe(String id, Map<String, Integer> inputs, String output, int outputCount) {
            this.id = id;
            this.inputs = Collections.unmodifiableMap(inputs);
            this.output = output;
            this.outputCount = outputCount;
        }
    }

    private static final List<Recipe> ALL = new ArrayList<Recipe>();
    private static final Map<String, Recipe> BY_ID = new LinkedHashMap<String, Recipe>();
    /** 全部已知资源键（= 有语义、且必须对应真实物品的那些键）。 */
    private static final Map<String, String> RESOURCES = new LinkedHashMap<String, String>();

    static {
        RESOURCES.put(ORE, "矿石：冶炼的原料输入");
        RESOURCES.put(COAL, "煤：冶炼的燃料/还原剂");
        RESOURCES.put(IRON_BAR, "铁锭：冶炼的成品");
        // 新矿链（2026-09-23「源源不断」）：每种金属矿 ×3 + 煤 ×2 → 对应锭 ×1
        RESOURCES.put("copper_ore", "铜矿：冶炼原料");
        RESOURCES.put("tin_ore", "锡矿：冶炼原料");
        RESOURCES.put("gold_ore", "金矿：冶炼原料");
        RESOURCES.put("silver_ore", "银矿：冶炼原料");
        RESOURCES.put("lead_ore", "铅矿：冶炼原料");
        RESOURCES.put("copper_bar", "铜锭：冶炼成品");
        RESOURCES.put("tin_bar", "锡锭：冶炼成品");
        RESOURCES.put("gold_bar", "金锭：冶炼成品");
        RESOURCES.put("silver_bar", "银锭：冶炼成品");
        RESOURCES.put("lead_bar", "铅锭：冶炼成品");
        RESOURCES.put("emerald", "绿宝石：宝石矿直接掉落（不冶炼）");
        // 第四批「源源不断」（2026-09-23）：合金 / 金属储块 / 建材（均为**配方输入或输出**键）
        RESOURCES.put("stone", "石头：建材原料（挖 STONE 得，用于合成石砖）");
        RESOURCES.put("stone_brick", "石砖：建材（4 石头 → 4 石砖）");
        RESOURCES.put("bronze_bar", "青铜锭：铜锭 + 锡锭 合金");
        RESOURCES.put("iron_block", "铁块：铁锭储块（9 锭 → 1 块）");
        RESOURCES.put("gold_block", "金块：金锭储块");
        RESOURCES.put("copper_block", "铜块：铜锭储块");
        RESOURCES.put("bronze_block", "青铜块：青铜锭储块");
        RESOURCES.put("emerald_block", "绿宝石块：绿宝石储块");
        // 第十五批（2026-09-25）「资源回路」：把第十三批补的**只能捡/只能放**的采集物接上用途。
        //  这些键此前不在任何配方里 ⇒ 玩家拿到珊瑚/晶石/蘑菇只能当装饰摆着（"半截内容"）。
        RESOURCES.put("crystal_cluster", "晶簇：稀有晶矿（晶洞产出），可分解为晶石");
        RESOURCES.put("crystal", "晶石：晶簇分解 / 晶石矿掉落；与煤冶炼成紫水晶");
        RESOURCES.put("amethyst", "紫水晶：晶石提纯产物（高级装饰/建材）");
        RESOURCES.put("coral", "珊瑚：海洋采集物；与玻璃合成海晶灯");
        RESOURCES.put("glass", "玻璃：建材；海晶灯的透光基材");
        RESOURCES.put("sea_lantern", "海晶灯：珊瑚 4 + 玻璃 1 → 发光建材");
        RESOURCES.put("mushroom", "蘑菇：与泥土合成灰化土");
        RESOURCES.put("dirt", "泥土：灰化土的基质");
        RESOURCES.put("podzol", "灰化土：蘑菇腐殖产物（建材/农艺基质）");
        RESOURCES.put("spore_pod", "孢子荚：发光植物；与煤焙烧成萤石");
        RESOURCES.put("glowstone", "萤石：发光建材");
        // 标准配方（与 techs/smelting.json 的 cost/unlocks 同源）
        Map<String, Integer> smelt = new LinkedHashMap<String, Integer>();
        smelt.put(ORE, 3);
        smelt.put(COAL, 2);
        add(new Recipe("smelting", smelt, IRON_BAR, 1));
        for (String[] mk : new String[][]{
                {"copper_ore", "copper_bar"}, {"tin_ore", "tin_bar"},
                {"gold_ore", "gold_bar"}, {"silver_ore", "silver_bar"}, {"lead_ore", "lead_bar"}}) {
            Map<String, Integer> r = new LinkedHashMap<String, Integer>();
            r.put(mk[0], 3); r.put(COAL, 2);
            add(new Recipe("smelt_" + mk[1].replace("_bar", ""), r, mk[1], 1));
        }
        // ---- 第四批「源源不断」：合金 / 储块 / 建材（**纯代码配方**，无对应 tech —— 由 I 键/工作台结算）----
        // 合金：铜锭 1 + 锡锭 1 → 青铜锭 1（本项目第一组"两种金属→合金"配方）
        Map<String, Integer> bronze = new LinkedHashMap<String, Integer>();
        bronze.put("copper_bar", 1); bronze.put("tin_bar", 1);
        add(new Recipe("smelt_bronze", bronze, "bronze_bar", 1));
        // 储块：9 锭/宝石 → 1 块（建材兼仓储）
        for (String[] kb : new String[][]{
                {"iron_bar", "iron_block"}, {"gold_bar", "gold_block"},
                {"copper_bar", "copper_block"}, {"bronze_bar", "bronze_block"},
                {"emerald", "emerald_block"}}) {
            Map<String, Integer> r = new LinkedHashMap<String, Integer>();
            r.put(kb[0], 9);
            add(new Recipe("craft_" + kb[1], r, kb[1], 1));
        }
        // 建材：4 石头 → 4 石砖
        Map<String, Integer> sbrick = new LinkedHashMap<String, Integer>();
        sbrick.put("stone", 4);
        add(new Recipe("craft_stone_brick", sbrick, "stone_brick", 4));

        // ---- 第十五批（2026-09-25）「资源回路」：5 条把新采集物接进产业链的配方 ----
        // 全部**产出已有物品**（amethyst/sea_lantern/podzol/glowstone/crystal 都已存在）
        // ⇒ 零新方块、零新纹理、零图集占用；链条两端都已在内容层有定义。
        // ① 分解：晶簇 1 → 晶石 3（晶簇是"富矿"，砸开得到三块晶石）
        Map<String, Integer> splitCrystal = new LinkedHashMap<String, Integer>();
        splitCrystal.put("crystal_cluster", 1);
        add(new Recipe("split_crystal", splitCrystal, "crystal", 3));
        // ② 提纯：晶石 4 + 煤 2 → 紫水晶 1
        Map<String, Integer> amethystR = new LinkedHashMap<String, Integer>();
        amethystR.put("crystal", 4); amethystR.put(COAL, 2);
        add(new Recipe("smelt_amethyst", amethystR, "amethyst", 1));
        // ③ 海洋建材：珊瑚 4 + 玻璃 1 → 海晶灯 1
        Map<String, Integer> lanternR = new LinkedHashMap<String, Integer>();
        lanternR.put("coral", 4); lanternR.put("glass", 1);
        add(new Recipe("craft_sea_lantern", lanternR, "sea_lantern", 1));
        // ④ 农艺基质：蘑菇 3 + 泥土 1 → 灰化土 1
        Map<String, Integer> podzolR = new LinkedHashMap<String, Integer>();
        podzolR.put("mushroom", 3); podzolR.put("dirt", 1);
        add(new Recipe("craft_podzol", podzolR, "podzol", 1));
        // ⑤ 发光：孢子荚 3 + 煤 1 → 萤石 1（焙烧发光荚果）
        Map<String, Integer> glowR = new LinkedHashMap<String, Integer>();
        glowR.put("spore_pod", 3); glowR.put(COAL, 1);
        add(new Recipe("smelt_glowstone", glowR, "glowstone", 1));
    }

    private static void add(Recipe r) { ALL.add(r); BY_ID.put(r.id, r); }

    private RecipeBook() {}

    /**
     * 结算一条配方：输入足量才**整条**结算（不足则一格都不扣），产出写回背包。
     *
     * <p><b>消费者</b>：{@code Game} 的 {@code I} 键（手搓合成）。此前这个 API 只有门禁在调，
     * 属"门禁测的路径生产不走"，2026-09-17 被删过一次；现在有了**真实玩家入口**才加回来。
     * 划界：**操作类 API 必须有生产调用方；谓词/校验才可以只被门禁调。**
     *
     * @return 是否真的结算了
     */
    public static boolean craft(Recipe r, Inventory inv, ContentRegistry reg) {
        if (r == null || inv == null || reg == null) return false;
        for (Map.Entry<String, Integer> e : r.inputs.entrySet()) {
            if (inv.countOf(e.getKey()) < e.getValue().intValue()) return false;   // 不足 → 整条不结算
        }
        for (Map.Entry<String, Integer> e : r.inputs.entrySet()) {
            inv.remove(e.getKey(), e.getValue().intValue());
        }
        inv.add(r.output, r.outputCount);
        return true;
    }

    /** 当前背包能**立刻**结算的第一条配方（注册序）；没有 → {@code null}。 */
    public static Recipe craftable(ContentRegistry reg, Inventory inv) {
        if (reg == null || inv == null) return null;
        for (int i = 0; i < ALL.size(); i++) {
            Recipe r = ALL.get(i);
            boolean ok = true;
            for (Map.Entry<String, Integer> e : r.inputs.entrySet()) {
                if (inv.countOf(e.getKey()) < e.getValue().intValue()) { ok = false; break; }
            }
            if (ok) return r;
        }
        return null;
    }

    /** 按 id 取配方；未知 → null。 */
    public static Recipe byId(String id) { return BY_ID.get(id); }

    /**
     * 全部配方（**注册序** = 确定性顺序）。
     *
     * <p>给门禁遍历用（{@code RECIPE_REACHABLE}：每条配方的每个输入都必须是"挖得到或做得出的"）。
     * 这条断言只能靠遍历整张表来写 —— 手写输入键清单等于把被测对象抄一遍，永远追不上新增次数。
     */
    public static List<Recipe> all() { return Collections.unmodifiableList(ALL); }

    /** 该键是否是**已知资源键**（有语义、须对应真实物品）。 */
    public static boolean isResource(String key) { return RESOURCES.containsKey(key); }

    /** 已知资源键集合（诊断 / 门禁用）。 */
    public static List<String> resourceKeys() {
        return Collections.unmodifiableList(new ArrayList<String>(RESOURCES.keySet()));
    }
}
