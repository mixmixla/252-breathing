package core.world;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 文明状态容器（批次 2 · 文明深度）：把 Python 7 个文明系统的“开放标量文明指标 + 区划覆盖层 +
 * 意图队列”集中为一份可观测状态。
 *
 * 忠实移植对应关系（Python → 本类字段）：
 *  - systems/culture.py   -> festivals / nicknames / taboos / legends
 *  - systems/religion.py  -> temples / believers / faith / miracles / sects
 *  - systems/tech.py      -> research / unlocked / techGrowthMult / techWaterMult / blueprints
 *  - systems/industry.py  -> ore / iron / gear / power / produced / gridDegree / extractable
 *  - systems/urban.py     -> zone（区划覆盖层）+ resArea/indArea/farmArea/roads/residents/
 *                            livability/landValue/center
 *  - systems/diplomacy.py -> factions / relations / treaties / conflicts / wars / war
 *  - systems/warfare.py   -> regimes / armies / wfWars / revolutions / unrest / milPower
 *
 * 零漂移纪律（与 npcs/beasts/shrines/social/chronicle 同）：
 *  - 本类是“开放标量 + 覆盖层”状态，**绝不进** {@link World#hashState()}；
 *  - 绝不写 mat/mass/prosperity/skills/villageMemory（材料执行层由
 *    {@link core.systems.CivilizationSystem#MATERIAL_WORKS} 开关守护，默认关）；
 *  - 纯确定性推导（随机只走 simStream 派生子流），故四道零漂移门禁指纹零影响。
 */
public final class Civilization {

    // ================================================================ 文化（M12 culture）
    public final List<String> festivals = new ArrayList<String>();      // 节日名（如“婚嫁节”）
    public final Map<String, String> nicknames = new LinkedHashMap<String, String>(); // npcName -> 绰号
    public final List<String> taboos = new ArrayList<String>();         // 禁忌口讳
    public final List<String> legends = new ArrayList<String>();        // 传说

    // ================================================================ 宗教（M70 religion）
    public int temples = 0;          // 神殿数
    public float believers = 0f;     // 信徒（开放标量，float 累积避免小数被截断）
    public float faith = 0f;         // 虔诚（0..1）
    public int miracles = 0;         // 异象次数
    public int sects = 0;            // 宗派数（命名池按序号确定性取）

    // ================================================================ 科技（M24 tech）
    public float research = 0f;                                  // 研究点（累积）
    public final java.util.Set<String> unlocked = new java.util.LinkedHashSet<String>(); // 已解锁科技 id
    public float techGrowthMult = 1f;                            // 农田生长乘子（精耕细作）
    public float techWaterMult = 1f;                             // 作物水分乘子（灌溉工程）
    public final List<String> blueprints = new ArrayList<String>(); // 自治工程意图（wall / rebuild）

    // ================================================================ 工业（M25 industry）
    public float ore = 0f, iron = 0f, gear = 0f, power = 0f, produced = 0f; // 矿石/生铁/齿轮/电力/累计产出
    public int gridDegree = 0;                                   // 电网里程（铺缆次数）
    public float extractable = 0f;                               // 可萃取矿石量（只读诊断；不磨蚀网格）

    // ================================================================ 城市（M33 urban）
    public int[][] zone;                                         // [SX][SZ] 区划覆盖层（0..4）
    public int resArea = 0, indArea = 0, farmArea = 0, roads = 0, residents = 0;
    public float livability = 0f, landValue = 0f;
    public int centerX = 0, centerZ = 0;
    // 区划有序遍历缓存（按“距中心曼哈顿环 + (x,z)”排序；中心变化时重建）
    public int[] urbanOrder;
    public int orderCx = Integer.MIN_VALUE, orderCz = Integer.MIN_VALUE;

    public static final int ZONE_NONE = 0, ZONE_RES = 1, ZONE_IND = 2, ZONE_FARM = 3, ZONE_ROAD = 4;

    // ================================================================ 外交（M36 diplomacy）
    /** 邻近势力（游牧部族/山间城邦/河谷王国）。 */
    public static final class Faction {
        public final String id, name;
        public float power = 0f, border = 0.5f;
        public String posture = "neutral";   // neutral / friendly / hostile（开放字符串）
        public Faction(String id, String name) { this.id = id; this.name = name; }
    }
    public final List<Faction> factions = new ArrayList<Faction>();
    public final Map<String, Float> relations = new LinkedHashMap<String, Float>();  // fid -> 关系值(-1..1)
    public final Map<String, String> posture = new LinkedHashMap<String, String>();  // fid -> 姿态标签
    /** 已签贸易协定的势力 id（幂等，避免重复签署）。 */
    public final java.util.Set<String> treated = new java.util.HashSet<String>();
    public int treaties = 0, conflicts = 0, wars = 0;
    public boolean war = false;

    // ================================================================ 战争与革命（M71 warfare）
    public int regimes = 1, armies = 0, wfWars = 0, revolutions = 0;
    public float unrest = 0f, milPower = 0f;

    public Civilization() {
        addFaction("nomad", "游牧部族", 0.1f);
        addFaction("hill", "山间城邦", 0.3f);
        addFaction("valley", "河谷王国", 0.2f);
    }

    private void addFaction(String id, String name, float baseline) {
        factions.add(new Faction(id, name));
        relations.put(id, baseline);
        posture.put(id, "neutral");
    }

    /** 当前宗派名（按 sects 序号确定性取，sects<1 则空）。 */
    public String sectName() {
        return sects >= 1 ? core.systems.CivilizationSystem.sectNameOf(sects) : "";
    }

    /** 文化是否为空（对齐 Python Culture.is_empty）。 */
    public boolean cultureEmpty() {
        return festivals.isEmpty() && nicknames.isEmpty() && taboos.isEmpty() && legends.isEmpty();
    }

    /** 距今为止的解锁科技名（按成本表序；供观测）。 */
    public String unlockedNames() {
        StringBuilder sb = new StringBuilder();
        for (String id : unlocked) { if (sb.length() > 0) sb.append('/'); sb.append(id); }
        return sb.toString();
    }

    /** 可读快照（供门禁/日志；纯派生，不修改状态）。 */
    public String snapshot() {
        return "research=" + round1(research) + ",unlocked=[" + unlockedNames() + "]"
                + ",bp=" + blueprints.size()
                + " | temples=" + temples + ",believers=" + round2(believers)
                + ",faith=" + round2(faith) + ",miracles=" + miracles + ",sects=" + sects
                + " | power=" + round2(power) + ",ore=" + round1(ore) + ",iron=" + round1(iron)
                + ",gear=" + round1(gear) + ",grid=" + gridDegree + ",extractable=" + round1(extractable)
                + " | liv=" + round2(livability) + ",land=" + round2(landValue)
                + ",res=" + resArea + "/ind=" + indArea + "/farm=" + farmArea + ",roads=" + roads
                + " | rel=" + relStr() + ",treaties=" + treaties + ",conflicts=" + conflicts + ",war=" + war
                + " | regimes=" + regimes + ",armies=" + armies + ",unrest=" + round2(unrest)
                + ",wfWars=" + wfWars + ",revolt=" + revolutions
                + " | culture=" + (festivals.size() + nicknames.size() + taboos.size() + legends.size());
    }

    private String relStr() {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Float> e : relations.entrySet()) {
            if (sb.length() > 0) sb.append(',');
            sb.append(e.getKey()).append('=').append(round2(e.getValue()));
        }
        return sb.toString();
    }

    private static String round1(float v) { return String.format(java.util.Locale.US, "%.1f", v); }
    private static String round2(float v) { return String.format(java.util.Locale.US, "%.2f", v); }
}
