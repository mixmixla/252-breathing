package core.sim;

import com.google.gson.JsonParser;
import core.content.ContentRegistry;
import core.content.EffectQueue;
import core.content.Inventory;
import core.content.RecipeBook;
import core.content.TechDef;
import core.content.TechTree;
import core.world.Blocks;
import core.world.World;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * RECIPE 门禁 —— 「采集 → 冶炼 → 成品」产业链的显式契约。
 *
 * <p><b>为什么需要它</b>：内容层 {@code techs/*.json} 的 {@code cost}/{@code unlocks} 用的是
 * 资源键 {@code ore} / {@code coal} / {@code iron_bar}，而旧实现把它们当**不透明字符串**：
 * 键名写错、物品不存在、数量不足都没有任何提示（静默跳过）。审计 C8 因此把这三个键记成
 * 「死内容键」—— 运行时确实读了，但源码里连一次字面引用都没有，没人能从代码看出语义。
 *
 * <p><b>断言</b>：
 * <ol>
 *   <li>三个资源键 + 捕捉球都是**真实物品**（内容层真的定义了它们）；</li>
 *   <li>标准配方 {@code ore 3 + coal 2 -> iron_bar 1} 可结算，且**不足则整条不结算**（不部分扣料）；</li>
 *   <li>真实内容里的 {@code smelting} 科技与代码契约**逐项一致**（数量/产出都对得上）；</li>
 *   <li>未知资源键必须**响亮记录**（进 recipeIssues），绝不静默跳过 —— 与 SKIP 清单同一条纪律；</li>
 *   <li><b>RECIPE_REACHABLE</b>（第十五批）：<b>整张配方表**遍历**</b>，每条配方的每个输入键都必须
 *       "挖得到或由其它配方产出"，每个产出都必须是真实物品。手写输入键清单等于把被测对象抄一遍，
 *       永远追不上新增次数 —— 所以这条断言必须**派生**。</li>
 * </ol>
 */
public final class RecipeTest {

    private static int fails = 0;
    /** 已执行的断言数（**运行时统计** —— 手写的总数会与真实段数漂移，本条纪律见 ContentTest）。 */
    private static int props = 0;

    private static void ck(String tag, boolean cond, String detail) {
        props++;
        java.lang.System.out.println(tag + " " + (cond ? "PASS" : "FAIL") + "  " + detail);
        if (!cond) fails++;
    }

    public static void main(String[] args) {

        ContentRegistry reg = ContentRegistry.load(new File("assets/content"));

        // ---------------- ITEMS：资源键必须是真实物品 ----------------
        boolean items = reg.item(RecipeBook.ORE) != null
                && reg.item(RecipeBook.COAL) != null
                && reg.item(RecipeBook.IRON_BAR) != null
                && reg.item("orb") != null;
        ck("ITEMS", items, "ore=" + (reg.item(RecipeBook.ORE) != null)
                + " coal=" + (reg.item(RecipeBook.COAL) != null)
                + " iron_bar=" + (reg.item(RecipeBook.IRON_BAR) != null)
                + " orb=" + (reg.item("orb") != null));

        // ---------------- RESOURCES：键集合固定，且每键都有语义说明 ----------------
        List<String> keys = RecipeBook.resourceKeys();
        boolean res = keys.size() == 33
                && keys.contains(RecipeBook.ORE) && keys.contains(RecipeBook.COAL)
                && keys.contains(RecipeBook.IRON_BAR)
                && RecipeBook.isResource(RecipeBook.ORE)
                && !RecipeBook.isResource("smelting")          // 科技标记不是资源
                && RecipeBook.byId("no_such_recipe") == null;
        ck("RESOURCES", res, "keys=" + keys + " smeltingIsResource="
                + RecipeBook.isResource("smelting"));

        // ---------------- TECH_CONTRACT：真实内容与代码契约逐项一致 ----------------
        World w = new World(20260917L, 64, 48, 64);
        TechTree tree = new TechTree(reg);
        TechDef smelting = reg.tech("smelting");
        Inventory tInv = new Inventory(reg.itemBook());
        tInv.add(RecipeBook.ORE, 3);
        tInv.add(RecipeBook.COAL, 2);
        boolean unlocked = tree.unlock(w, smelting, new EffectQueue(0L), 0L, tInv);
        boolean contract = unlocked
                && tInv.countOf(RecipeBook.ORE) == 0          // cost 真扣
                && tInv.countOf(RecipeBook.COAL) == 0
                && tInv.countOf(RecipeBook.IRON_BAR) == 1     // unlocks 真产出
                && tree.recipeIssues().isEmpty();             // 契约零问题
        ck("TECH_CONTRACT", contract, "unlocked=" + unlocked
                + " ore=" + tInv.countOf(RecipeBook.ORE) + " coal=" + tInv.countOf(RecipeBook.COAL)
                + " iron_bar=" + tInv.countOf(RecipeBook.IRON_BAR)
                + " issues=" + tree.recipeIssues());

        // ---------------- UNKNOWN_LOUD：未知资源键必须响亮记录（不静默跳过） ----------------
        List<String> errors = new ArrayList<String>();
        TechDef bogus = TechDef.parse("bogus_recipe", JsonParser.parseString(
                "{\"name\":\"Bogus\",\"cost\":{\"unobtainium\":1},\"unlocks\":[]}").getAsJsonObject(),
                errors, 99);
        TechTree tree2 = new TechTree(reg);
        Inventory bInv = new Inventory(reg.itemBook());
        bInv.add("unobtainium", 5);
        tree2.unlock(w, bogus, new EffectQueue(0L), 0L, bInv);
        boolean loud = tree2.recipeIssues().size() == 1
                && tree2.recipeIssues().get(0).contains("unknown-item")
                && tree2.recipeIssues().get(0).contains("unobtainium");
        ck("UNKNOWN_LOUD", loud, "issues=" + tree2.recipeIssues());

        // ---------------- CHAIN_REACHABLE：配方输入必须**挖得到** ----------------
        // 这条断言如果早就在，本轮那个 bug 会被当场抓住：挖矿靠 blockId.toLowerCase() 猜物品 id，
        // 于是 COAL_ORE 掉 coal_ore、IRON_ORE 掉 iron_ore；而 smelting 的成本要 ore/coal
        // —— 这两个物品没有 block 字段、玩家永远拿不到 → **整条冶炼链的成本从来没被支付过**。
        // 现在改由内容的 item.block 反向索引决定"挖什么掉什么"，这里断言闭环成立。
        java.util.Set<String> obtainable = new java.util.HashSet<String>();
        for (int t = 1; t < Blocks.count(); t++) {
            String it = reg.itemForBlock(Blocks.byIndex(t).id);
            if (it != null) obtainable.add(it);
        }
        boolean reachable = true;
        StringBuilder miss = new StringBuilder();
        // resourceKeys 还包含 iron_bar（成品），成品本来就不是挖矿输入；这里只断言**所有输入键**。
        String[] inputKeys = {RecipeBook.ORE, RecipeBook.COAL};
        for (String key : inputKeys) {
            if (!obtainable.contains(key)) { reachable = false; miss.append(key).append(' '); }
        }
        boolean mapping = RecipeBook.ORE.equals(reg.itemForBlock("IRON_ORE"))
                && RecipeBook.COAL.equals(reg.itemForBlock("COAL_ORE"));
        ck("CHAIN_REACHABLE", reachable && mapping,
                "obtainable=" + obtainable.size() + " 个物品；ore/coal 映射=" + mapping
                + "；缺失输入键=" + (miss.length() == 0 ? "(无)" : miss.toString()));

        // ---------------- RECIPE_REACHABLE：每条配方的**每个输入**都必须拿得到 ----------------
        // 与 CHAIN_REACHABLE 同一条纪律，但覆盖面从手写的 {ore,coal} 扩到**整张配方表**：
        // 让"写了一条新配方、输入却永远挖不到（或做不出）"变成构建期失败。
        // 这正是第十四批发现的"回路另一端断了"（32 个方块无掉落）在**配方链**上的翻版 ——
        // 而手写输入键清单等于把被测对象抄一遍，永远追不上新增次数，所以这里**遍历** RecipeBook.all()。
        // 可达 = 可挖 ∪ 由其它配方产出（成品可以是下游配方的输入，如 copper_bar）。
        java.util.Set<String> reach = new java.util.HashSet<String>(obtainable);
        for (int i = 0; i < RecipeBook.all().size(); i++) reach.add(RecipeBook.all().get(i).output);
        List<String> deadIn = new ArrayList<String>();
        StringBuilder rmiss = new StringBuilder();
        for (int i = 0; i < RecipeBook.all().size(); i++) {
            RecipeBook.Recipe r = RecipeBook.all().get(i);
            for (String k : r.inputs.keySet()) {
                if (!reach.contains(k)) { rmiss.append(r.id).append("<-").append(k).append(' '); if (!deadIn.contains(k)) deadIn.add(k); }
            }
            if (reg.item(r.output) == null) rmiss.append(r.id).append("->?").append(r.output).append(' ');
        }
        boolean reachOk = deadIn.isEmpty() && rmiss.indexOf("->?") < 0;
        ck("RECIPE_REACHABLE", reachOk,
                "配方=" + RecipeBook.all().size() + " 条；可达物品=" + reach.size()
                + "；不可达输入=" + (deadIn.isEmpty() ? "(无)" : deadIn.toString())
                + (rmiss.length() == 0 ? "" : " 明细:" + rmiss));

        // ---------------- CRAFT_PAYS_ALL：不足则整条不结算（一格都不扣） ----------------
        RecipeBook.Recipe smelt2 = RecipeBook.byId("smelting");
        Inventory rich = new Inventory(reg.itemBook());
        rich.add(RecipeBook.ORE, 3);
        rich.add(RecipeBook.COAL, 2);
        boolean paid = RecipeBook.craft(smelt2, rich, reg)
                && rich.countOf(RecipeBook.ORE) == 0
                && rich.countOf(RecipeBook.COAL) == 0
                && rich.countOf(RecipeBook.IRON_BAR) == 1;
        Inventory poor2 = new Inventory(reg.itemBook());
        poor2.add(RecipeBook.ORE, 2);          // 差 1 个
        poor2.add(RecipeBook.COAL, 9);
        boolean refused = !RecipeBook.craft(smelt2, poor2, reg)
                && poor2.countOf(RecipeBook.ORE) == 2      // 一格都没被扣
                && poor2.countOf(RecipeBook.COAL) == 9
                && poor2.countOf(RecipeBook.IRON_BAR) == 0;
        ck("CRAFT_PAYS_ALL", paid && refused,
                "rich→iron_bar=" + rich.countOf(RecipeBook.IRON_BAR) + " poor→ore="
                + poor2.countOf(RecipeBook.ORE) + "(未被扣)");

        // ---------------- CRAFT_SELECTS：craftable 选得对（穷 → null；够 → 该配方） ----------------
        Inventory none = new Inventory(reg.itemBook());
        boolean sel = RecipeBook.craftable(reg, none) == null
                && RecipeBook.craftable(reg, rich) == null            // rich 刚被扣空 → 又不满足了
                && RecipeBook.craftable(reg, poor2) == null;          // 差 1 个矿 → 不算可做
        Inventory ok2 = new Inventory(reg.itemBook());
        ok2.add(RecipeBook.ORE, 3);
        ok2.add(RecipeBook.COAL, 2);
        sel = sel && RecipeBook.craftable(reg, ok2) == smelt2;
        ck("CRAFT_SELECTS", sel, "empty/poor→null 且 够料→smelting=" + sel);

        java.lang.System.out.println("RECIPE " + (fails == 0 ? "PASS" : "FAIL " + fails) + "  (" + props + " properties)");
        if (fails > 0) java.lang.System.exit(1);
    }
}
