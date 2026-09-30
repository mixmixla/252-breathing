package core.world;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 民政与经济状态容器（批次 4 · 深度打磨已覆盖系统）。
 *
 * 忠实移植 Python 两系统的“上层建筑”状态为一份可观测容器：
 *  - systems/civilization.py（M11）：城镇等级 town_level + 集市定价 market_price +
 *    犯罪/仲裁 crime/judge；
 *  - systems/trade.py（M34）：货币发行 coins/money_supply + 商队 caravans + 通胀 inflation +
 *    市场周期 cycles + 供应链货流 goods_flow/surplus/deficit。
 *
 * 零漂移纪律（与 npcs/beasts/shrines/social/chronicle/civ/individual 同）：
 *  - 本类是“开放标量 + 实体级账本”，**绝不进** {@link World#hashState()}；
 *  - 绝不写 mat/mass/prosperity/skills/villageMemory；犯罪/仲裁只改 NPC 实体态（body/mind/social/inventory），
 *    实体不进指纹，故四道零漂移门禁指纹零影响；
 *  - 纯确定性推导（本容器不含随机），同种子逐字节可复现。
 */
public final class Polity {

    // ================================================================ 城镇档案（M11 town_profile）
    /** 城镇等级：0=聚落, 1=村落, 2=集镇, 3=堡垒。 */
    public int townLevel = 0;
    /** 城镇等级中文标签（供村志；GL 字体不含 CJK 故不上屏）。 */
    public String townLabel = "聚落";
    public int townAlive = 0, townFams = 0, townCrops = 0;
    public float townStock = 0f;
    /** 已升格次数（每次 level 上升 +1）。 */
    public int townUps = 0;

    // ================================================================ 集市定价（M11 update_prices）
    /** 商品 -> 动态价（供给越缺越贵）。 */
    public final Map<String, Float> marketPrice = new LinkedHashMap<String, Float>();
    public int priceUpdates = 0;

    // ================================================================ 犯罪与仲裁（M11 crime/judge）
    public int crimes = 0, thefts = 0, fights = 0, judges = 0, penalized = 0, shielded = 0;
    /** 事件水印（单调递增；只判新增的 npc/crime 事件，避免重复裁决）。 */
    public int judgedEvents = 0;
    /**
     * 累犯计次（offender id -> 已裁犯罪数）。
     *
     * 可达性设计：Python 的 `judge` 只在“全村无人亲疏 >=0.45 挺他”时惩戒，但 Java 的
     * {@link core.systems.SocialSystem} 会把邻里亲疏整体烘热（为结亲铺路），致几乎所有犯嫌都有
     * 一个 >=0.45 的相护者 → “惩戒”分支**在实践中不可达**（实测 pen=0/shield=36）。故按可达性纪律
     * 增设“累犯”维度：初犯可获人情（有人相护→包庇），但**惯犯失众望**（前科 >= 阈值）一律惩戒 ——
     * 两种裁决因此都可达。本表是实体级账本，不进 hashState。
     */
    public final Map<String, Integer> offenderStrikes = new LinkedHashMap<String, Integer>();
    /** 被判惩戒的累犯数（观察口径；便于门禁断言惩戒分支真的活了）。 */
    public int recidivists = 0;

    // ================================================================ 贸易与货币（M34 trade）
    public float coins = 0f, moneySupply = 0f;
    public float inflation = 0f, cycles = 0f, surplus = 0f, deficit = 0f;
    public int caravans = 0;
    /** 商队小数累积器（避免 (int)rate 恒为 0 的死尾；rate<1 时逐期攒够 1 才 +1）。 */
    public float caravanFrac = 0f;
    /** 五类货的供销累积（赤字=紧缺）。 */
    public final Map<String, Float> goodsFlow = new LinkedHashMap<String, Float>();
    public boolean inflating = false;
    public int ledgers = 0;

    public Polity() {
        // 基准价（镜像 Python _BASE_PRICE；键名映射到 Java 可得资源）
        marketPrice.put("wood", 1.0f);
        marketPrice.put("berry", 1.0f);
        marketPrice.put("iron", 5.0f);
        marketPrice.put("stone", 1.5f);
        marketPrice.put("food", 2.5f);
        for (String g : marketPrice.keySet()) goodsFlow.put(g, 0f);
    }

    /** 城镇等级标签（镜像 Python _TOWN_LEVELS 的 label 列）。 */
    public static String townLabelOf(int lvl) {
        switch (lvl) {
            case 3: return "堡垒";
            case 2: return "集镇";
            case 1: return "村落";
            default: return "聚落";
        }
    }

    /** HUD 一行：ASCII 民政经济摘要（语言无关，GL 字体仅 ASCII 也能上屏）。 */
    public String asciiSummary() {
        return "POLITY LV" + townLevel + "  POP " + townAlive + "  FAM " + townFams
                + "  COIN " + Math.round(coins) + "  INFL " + fmt2(inflation)
                + "  CARAVAN " + caravans + "  CRIME " + crimes + "  JUDGE " + judges;
    }

    /** 可读快照（供门禁/日志；纯派生，不修改状态）。 */
    public String snapshot() {
        return "lv=" + townLevel + "(" + townLabel + "),alive=" + townAlive + ",fams=" + townFams
                + ",crops=" + townCrops + ",stock=" + fmt2(townStock) + ",ups=" + townUps
                + " | price=" + priceStr() + ",pu=" + priceUpdates
                + " | crime=" + crimes + "(theft=" + thefts + ",fight=" + fights + ")"
                + ",judge=" + judges + "(pen=" + penalized + ",shield=" + shielded + ",recid=" + recidivists + ")";
    }

    /** 贸易账本快照（与上层 snapshot 分离，便于门禁分节断言）。 */
    public String tradeSnapshot() {
        return "coins=" + fmt2(coins) + ",supply=" + fmt2(moneySupply)
                + ",infl=" + fmt4(inflation) + ",cyc=" + fmt3(cycles) + ",caravans=" + caravans
                + "(frac=" + fmt2(caravanFrac) + ")"
                + ",surplus=" + fmt2(surplus) + ",deficit=" + fmt2(deficit)
                + ",inflating=" + inflating + ",ledgers=" + ledgers + " | flow=" + flowStr();
    }

    private String priceStr() {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Float> e : marketPrice.entrySet()) {
            if (sb.length() > 0) sb.append(',');
            sb.append(e.getKey()).append('=').append(fmt2(e.getValue()));
        }
        return sb.toString();
    }

    private String flowStr() {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Float> e : goodsFlow.entrySet()) {
            if (sb.length() > 0) sb.append(',');
            sb.append(e.getKey()).append('=').append(fmt1(e.getValue()));
        }
        return sb.toString();
    }

    private static String fmt1(float v) { return String.format(java.util.Locale.US, "%.1f", v); }
    private static String fmt2(float v) { return String.format(java.util.Locale.US, "%.2f", v); }
    private static String fmt3(float v) { return String.format(java.util.Locale.US, "%.3f", v); }
    private static String fmt4(float v) { return String.format(java.util.Locale.US, "%.4f", v); }

    /** 最近若干条犯罪记录的可读列表（供村志/调试；可选）。 */
    public final List<String> crimeLog = new ArrayList<String>();
}
