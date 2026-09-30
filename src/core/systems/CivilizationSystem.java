package core.systems;

import core.agent.Npc;
import core.rng.SeededRNG;
import core.world.Blocks;
import core.world.Civilization;
import core.world.World;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

/**
 * 文明深度驱动系统（批次 2 · NPC-SOC-PORT 批次2）：把 7 个 Python 文明系统接进 World.tick 主循环。
 *
 * 忠实移植 Python 7 系统（culture / religion / tech / industry / urban / diplomacy / warfare）的
 * 确定性路径为一份 Java 实现（状态落在 {@link Civilization}），固定子步顺序：
 *
 *   urban(10) -> tech(20) -> industry(10) -> religion(10) -> diplomacy(20) -> warfare(10) -> culture(24)
 *
 * 零漂移铁律（与 SocialSystem / StorytellerSystem 同）：
 *  - 只改 {@link Civilization} 开放标量与区划覆盖层 + events（均不进 hashState）；
 *  - 绝不写 mat/mass/prosperity/skills/villageMemory；绝不读 world.rng 主状态或 fxRng；
 *  - 随机只走 world.simStream(...) 派生子流（deriveStream 用主种子而非主状态 → 不推进 rng.state）；
 *  - **材料执行层**（tech 自治修墙/重建、industry 网格磨蚀/铺缆）由 {@link #MATERIAL_WORKS} 开关守护，
 *    默认关 → 网格一字不改 → 四道零漂移门禁指纹与“无本系统”基线逐字节一致。
 *
 * 信号源替换（Java 无 Python 的 trade/market/robots/deepsea，按批次 1 先例改用可得信号合成，已在各方法注明）：
 *  - coins（贸易财富）      = prosperity * 10
 *  - marketSurplus（市场盈余）= min(0.5, prosperity * 0.02)
 *  - pollution/crime        = 0（Java 暂无对应源）
 */
public final class CivilizationSystem implements System {

    /**
     * 材料执行层开关。默认 false —— 让“新增文明”不演进确定性指纹基线（项目铁律）。
     * 置 true 时启用 tech 自治修墙/重建与 industry 网格磨蚀/铺缆（均确定性）。
     * ⚠️ **网格改块并不守恒物质**（早期注释写"1:1 质量守恒"是错的，属"标签说谎"）：按 MaterialBook.density
     * 实测 STONE(26)->SHELTER(14) 每格 -12、WOOD(12)->SHELTER(14) 每格 +2。物质账本门禁 MATLEDGER 钉住这些 Δ；
     * 只有 {@link #mine} 的 mass（矿石量）账本那一项才是 1:1。
     * 届时 hashState 指纹会演进（属预期），需重跑四门禁确认确定性/隔离仍成立。
     * 亦可在批次 4「文明实体化」时统一开启并单独验证。
     */
    public static boolean MATERIAL_WORKS = false;

    // ---- 子步间隔（镜像 Python config 缺省）----
    private static final int I_URBAN = 10, I_TECH = 20, I_IND = 10, I_RELIGION = 10, I_DIPLO = 20, I_WAR = 10, I_CULTURE = 24;

    // ---- 城市（M33 urban 参数）----
    private static final int MAX_AREA = 140, RES_RING = 4, FARM_RING = 7, IND_RING = 4;
    private static final int ROAD_CAP = 24, RES_WANT = 40, IND_WANT = 12;

    // ---- 科技（M24 tech 参数）----
    private static final float RP_FIXED = 0.4f;

    /** 科技表（镜像 Python _TECHES：4 级、成本递增、效果持久）。 */
    private static final class Tech {
        final String id, name, desc; final float cost;
        Tech(String id, String name, float cost, String desc) { this.id = id; this.name = name; this.cost = cost; this.desc = desc; }
    }
    private static final Tech[] TECHES = {
        new Tech("farming2",   "精耕细作",   60f, "农田生长速率 +25%"),
        new Tech("medicine",   "医药智慧",  120f, "疫病康复窗口缩短、更易自疗"),
        new Tech("irrigation", "灌溉工程",  240f, "作物水分因子增强"),
        new Tech("town_hall",  "市政厅",    480f, "解锁城镇自治：外围修墙 + 房屋重建"),
    };
    private static final int AUTONOMY_PER = 2;    // 每自治周期材料重排上限

    // ---- 工业（M25 industry 参数）----
    private static final float POWER_PER_GEN = 2.0f, WIRE_POWER_MULT = 0.01f;
    private static final float MINE_RATE = 1.0f, DRIVER = 4.0f;
    private static final float SMELT_RATE = 1.0f, ASSEMBLE_RATE = 0.8f, ASSEMBLE_POWER = 1.0f, GEAR_TO_WIRE = 6.0f;

    // ---- 宗教（M70 religion 参数）----
    private static final float BELIEVER_RATE = 0.02f, TEMPLE_TH = 5.0f, MIRACLE_TH = 0.18f, MIRACLE_RATE = 0.12f;
    private static final int TEMPLE_CAP = 8;
    private static final float RESEARCH_BOOST = 2.0f, SOOTHE = 0.004f;
    private static final String[] SECTS = {"明月坛", "苍梧阁", "磬音寺", "碧落观", "净火堂", "观星社", "白帆教会", "长明灯会"};

    // ---- 外交（M36 diplomacy 参数）----
    private static final float DRIFT = 0.02f, TENSION = 0.06f, COOP = 0.5f, COOP_CAP = 0.12f, REVERSION = 0.08f;
    private static final float WAR_BORDER = 0.85f, TREATY_COINS = 40f, TREATY_REL = 0.35f, WAR_TH = -0.3f;
    private static final int TREATY_CAP = 3, PEACE_TREATIES = 2;

    // ---- 战争与革命（M71 warfare 参数）----
    private static final float MIL_RATE = 0.03f, TECH_RATE = 0.004f, WAR_RATE = 0.06f, WAR_TH_MIL = 4.0f, SPEND = 0.5f;
    private static final float FAITH_BOOST = 0.3f, REVOLUTION_TH = 0.55f, DYNASTY = 0.4f, ARMY_TH = 2.0f;
    private static final int STAGNATE = 8;

    // ---- 文化（M12 culture 阈值）----
    private static final int FEST_MARRY = 2, FEST_BIRTH = 2, FEST_AWAKEN = 1, NICKNAME_MIN = 4;

    @Override
    public String name() { return "CivilizationSystem"; }

    /** 宗派命名池确定性取值（sects 从 1 起）。 */
    public static String sectNameOf(int sects) { return SECTS[(sects - 1) % SECTS.length]; }

    @Override
    public void update(World w, SeededRNG rng) {
        Civilization c = w.civ;
        if (w.tick % I_URBAN == 0) urban(w, c);
        if (w.tick % I_TECH == 0) tech(w, c);
        if (w.tick % I_IND == 0) industry(w, c);
        if (w.tick % I_RELIGION == 0) religion(w, c);
        if (w.tick % I_DIPLO == 0) diplomacy(w, c);
        if (w.tick % I_WAR == 0) warfare(w, c);
        if (w.tick % I_CULTURE == 0) culture(w, c);
    }

    // ================================================================ 城市（M33 urban）
    private static void urban(World w, Civilization c) {
        if (c.zone == null) c.zone = new int[w.SX][w.SZ];
        center(w, c);
        buildOrder(w, c);
        assignZones(w, c);
        roads(w, c);
        metrics(w, c);
        w.log("urban", "planning", "center=" + c.centerX + "," + c.centerZ,
                "res=" + c.resArea + ",ind=" + c.indArea + ",farm=" + c.farmArea
                        + ",roads=" + c.roads + ",liv=" + c.livability + ",land=" + c.landValue);
    }

    /** 村庄几何中心：取建成物（SHELTER/LAMP）质心，无则场景中心（对齐 Python _center 的替代）。 */
    private static void center(World w, Civilization c) {
        long sx = 0, sz = 0, n = 0;
        for (String b : new String[]{"SHELTER", "LAMP"}) {
            if (!Blocks.has(b)) continue;
            for (int[] p : w.cellsOfType(Blocks.index(b))) { sx += p[0]; sz += p[2]; n++; }
        }
        if (n > 0) { c.centerX = (int) (sx / n); c.centerZ = (int) (sz / n); }
        else { c.centerX = w.SX / 2; c.centerZ = w.SZ / 2; }
    }

    /** 构建“距中心曼哈顿环 + (x,z)”的有序遍历（中心变化才重建，O(N log N)）。 */
    private static void buildOrder(World w, Civilization c) {
        if (c.urbanOrder != null && c.orderCx == c.centerX && c.orderCz == c.centerZ) return;
        final int cx = c.centerX, cz = c.centerZ, n = w.SX * w.SZ;
        int[][] cells = new int[n][2];
        int k = 0;
        for (int x = 0; x < w.SX; x++) for (int z = 0; z < w.SZ; z++) { cells[k][0] = x; cells[k][1] = z; k++; }
        java.util.Arrays.sort(cells, (p, q) -> {
            int dp = Math.abs(p[0] - cx) + Math.abs(p[1] - cz);
            int dq = Math.abs(q[0] - cx) + Math.abs(q[1] - cz);
            if (dp != dq) return Integer.compare(dp, dq);
            if (p[0] != q[0]) return Integer.compare(p[0], q[0]);
            return Integer.compare(p[1], q[1]);
        });
        c.urbanOrder = new int[n * 2];
        for (int i = 0; i < n; i++) { c.urbanOrder[i * 2] = cells[i][0]; c.urbanOrder[i * 2 + 1] = cells[i][1]; }
        c.orderCx = cx; c.orderCz = cz;
    }

    /** 幂等圈定区划（优先生活区、再耕地、最后工业；全局封顶）。 */
    private static void assignZones(World w, Civilization c) {
        int planted = 0;
        for (int x = 0; x < w.SX; x++) for (int z = 0; z < w.SZ; z++) {
            int zz = c.zone[x][z];
            if (zz == Civilization.ZONE_RES || zz == Civilization.ZONE_IND || zz == Civilization.ZONE_FARM) planted++;
        }
        int budget = Math.max(0, MAX_AREA - planted);
        for (int i = 0; budget > 0 && i * 2 + 1 < c.urbanOrder.length; i++) {
            int x = c.urbanOrder[i * 2], z = c.urbanOrder[i * 2 + 1];
            if (c.zone[x][z] != Civilization.ZONE_NONE) continue;
            int top = w.surfaceY[x][z];
            if (top < 0 || !w.inBounds(x, top, z)) continue;
            int m = w.mat[x][top][z];
            if (!undev(m)) continue;
            int d = Math.abs(x - c.centerX) + Math.abs(z - c.centerZ);
            int want;
            if (d <= RES_RING) want = Civilization.ZONE_RES;
            else if (d <= FARM_RING && (m == Blocks.GRASS.index || m == Blocks.DIRT.index)) want = Civilization.ZONE_FARM;
            else if (d > IND_RING && m == Blocks.STONE.index) want = Civilization.ZONE_IND;
            else continue;
            c.zone[x][z] = want;
            budget--;
        }
        c.resArea = 0; c.indArea = 0; c.farmArea = 0;
        for (int x = 0; x < w.SX; x++) for (int z = 0; z < w.SZ; z++) {
            int zz = c.zone[x][z];
            if (zz == Civilization.ZONE_RES) c.resArea++;
            else if (zz == Civilization.ZONE_IND) c.indArea++;
            else if (zz == Civilization.ZONE_FARM) c.farmArea++;
        }
    }

    /** 只规划未开发空地（草/泥/石/木）——对齐 Python undev 集（不含沙）。 */
    private static boolean undev(int m) {
        return m == Blocks.GRASS.index || m == Blocks.DIRT.index
                || m == Blocks.STONE.index || m == Blocks.WOOD.index;
    }

    /** 沿中心十字轴铺位道路（线性、有上限）。 */
    private static void roads(World w, Civilization c) {
        int want = Math.min(ROAD_CAP, c.resArea / 2 + 2);
        int added = 0;
        for (int x = 0; x < w.SX && added < want; x++)
            if (c.zone[x][c.centerZ] == Civilization.ZONE_NONE) { c.zone[x][c.centerZ] = Civilization.ZONE_ROAD; added++; }
        for (int z = 0; z < w.SZ && added < want; z++)
            if (c.zone[c.centerX][z] == Civilization.ZONE_NONE) { c.zone[c.centerX][z] = Civilization.ZONE_ROAD; added++; }
        c.roads = Math.min(c.roads + added, ROAD_CAP);
    }

    /** 宜居度 / 地价 / 人口集聚（道路 + 食物安全 + 洁净 + 治安 + 居住规模联合派生）。 */
    private static void metrics(World w, Civilization c) {
        int alive = alive(w);
        float roadK = Math.min(1f, c.roads / (float) ROAD_CAP);
        float foodK = clamp01(w.prosperity / 10f);   // 合成：繁荣=食物安全（Java 无市场均价）
        float cleanK = 1f;                           // 合成：无污染源
        float safetyK = 1f;                          // 合成：无 crime 事件
        float resK = Math.min(1f, c.resArea / (float) RES_WANT);
        c.livability = round3(0.35f * roadK + 0.25f * foodK + 0.2f * cleanK + 0.1f * safetyK + 0.1f * resK);
        float indK = Math.min(1f, c.indArea / (float) IND_WANT);
        c.landValue = round3(c.livability * (0.5f + 0.5f * roadK) * (0.6f + 0.4f * indK));
        c.residents = (int) (alive * (0.5f + c.livability * 2.0f));
    }

    // ================================================================ 科技（M24 tech）
    private static void tech(World w, Civilization c) {
        int alive = alive(w);
        float surplus = Math.min(0.5f, w.prosperity * 0.02f);   // 合成市场盈余
        c.research += RP_FIXED + 0.06f * alive + surplus;
        for (Tech t : TECHES) {
            if (c.unlocked.contains(t.id)) continue;
            if (c.research >= t.cost) {
                c.unlocked.add(t.id);
                applyTech(c, t.id);
                w.log("tech", "unlock", "village", "tech=" + t.name);
            }
        }
        autonomy(w, c);
    }

    private static void applyTech(Civilization c, String id) {
        if ("farming2".equals(id)) c.techGrowthMult = 1.25f;
        else if ("irrigation".equals(id)) c.techWaterMult = 1.2f;
        else if ("town_hall".equals(id)) { c.blueprints.add("wall"); c.blueprints.add("rebuild"); }
        // medicine：由受信乘子/消费者读取“已解锁”，此处不落效
    }

    /**
     * 城镇自治（材料执行层，默认 MATERIAL_WORKS=false 关闭）：
     * 沿外围把满质 STONE 砌成 SHELTER（抗火防洪）、把 WOOD 残骸砌回 SHELTER（房屋重建）；
     * 确定性按 (x,y,z) 升序遍历，每周期上限 AUTONOMY_PER。
     * ⚠️ **并非"1:1 质量不变"**（早期注释错误）：density(STONE)=26 -> SHELTER=14 每格 -12；
     * density(WOOD)=12 -> SHELTER=14 每格 +2。MATLEDGER 已把这两条 Δ 钉住。
     * 注：Java 体素质量为 1/voxel，Python 的“mass>=100 才算大石”条件在 Java 尺度下天然不触发，
     * 故当前按“意图 + 有界重排”预置，材料实体化统一留待批次 4。
     */
    private static void autonomy(World w, Civilization c) {
        if (!MATERIAL_WORKS || !c.unlocked.contains("town_hall")) return;
        if (c.blueprints.contains("wall")) {
            int per = 0;
            // ⚠️ 原为 `new TreeSet<int[]>(...)` —— `int[]` 非 Comparable，add() 必抛 ClassCastException
            // （一旦 MATERIAL_WORKS=true 即触发）。而 cellsOfType 本就按 (x,y,z) 升序返回 ⇒ 直接快照即可，
            // 排序包装纯属多余。审计不变式 C13 守住此类隐患不再复发。
            for (int[] p : new java.util.ArrayList<int[]>(w.cellsOfType(Blocks.STONE.index))) {
                if (per >= AUTONOMY_PER) break;
                if (w.mass[p[0]][p[1]][p[2]] >= 100f) { w.setBlock(p[0], p[1], p[2], Blocks.SHELTER.index); per++; }
            }
        }
        if (c.blueprints.contains("rebuild")) {
            int per = 0;
            for (int[] p : new java.util.ArrayList<int[]>(w.cellsOfType(Blocks.WOOD.index))) {
                if (per >= AUTONOMY_PER) break;
                w.setBlock(p[0], p[1], p[2], Blocks.SHELTER.index); per++;
            }
            if (per > 0) w.log("tech", "rebuild", "village", "houses=" + per);
        }
    }

    // ================================================================ 工业（M25 industry）
    private static void industry(World w, Civilization c) {
        power(w, c);
        mine(w, c);
        smelt(c);
        assemble(c);
        wireExtend(w, c);
    }

    /** 供电 = 发电机*基础 + 电网里程加成 + 畜力/水力基线。
     *  Java 暂无 GENERATOR/WIRE 方块 → 前两项为 0；以「人口驱动的畜力/水力基线」外部替代，
     *  使机械加工（需 ASSEMBLE_POWER）具备启动条件。加装方块后自动叠加（公式保留）。 */
    private static void power(World w, Civilization c) {
        int gen = countBlock(w, "GENERATOR"), wire = countBlock(w, "WIRE");
        float base = Math.max(1.5f, 0.1f * alive(w));   // 外部替代：村庄畜力/水力基线
        c.power = gen * POWER_PER_GEN + wire * WIRE_POWER_MULT + base;
    }

    /** 开采：报告可萃取矿石量；**数字层**按速率把矿石计入账本（不磨蚀网格 → 守指纹）；
     *  仅当 MATERIAL_WORKS 时才额外磨蚀网格（1:1 转入工业账本，属批次 4 材料实体化）。
     *  ⚠️ 这里的"1:1"指 <b>{@code World.mass}（矿石量）字段</b>与 {@code c.ore} 账本之间的守恒，
     *  <b>不是</b>方块密度账本 —— 方块 {@code mat} 本身没被改写，故与 {@link #autonomy} 的"改块"不同源。 */
    private static void mine(World w, Civilization c) {
        float avail = 0f;
        java.util.Collection<int[]> ore = w.cellsOfType(Blocks.IRON_ORE.index);
        for (int[] p : ore) avail += w.mass[p[0]][p[1]][p[2]];
        c.extractable = avail;
        if (avail <= 0f) return;
        int workers = Math.max(1, alive(w));
        float rate = MINE_RATE * (0.5f + 0.1f * workers) * (0.5f + Math.min(1f, c.power));
        float out = rate * DRIVER;
        if (MATERIAL_WORKS) {
            // 材料执行层（批次 4）：真正磨蚀网格矿量，产出以网格矿量为界（mass 字段与 ore 账本 1:1）
            float amt = Math.min(avail, out);
            c.ore += amt;
            float keep = 1f - amt / Math.max(1e-9f, avail);
            for (int[] p : ore) w.mass[p[0]][p[1]][p[2]] *= keep;
        } else {
            // 数字层：按速率计入账本，网格一字不动（守四门禁指纹）
            c.ore += out;
        }
    }

    /** 冶炼：ore -> iron（1:1，账本内转移）。 */
    private static void smelt(Civilization c) {
        float amt = Math.min(c.ore, SMELT_RATE * DRIVER);
        c.ore -= amt; c.iron += amt; c.produced += amt;
    }

    /** 机械加工：iron -> gear（1:1），需电力达阈值。 */
    private static void assemble(Civilization c) {
        if (c.power < ASSEMBLE_POWER) return;
        float amt = Math.min(c.iron, ASSEMBLE_RATE * DRIVER);
        c.iron -= amt; c.gear += amt; c.produced += amt;
    }

    /** 铺缆扩容：整批 gear -> 电网里程（材料执行层，默认关；Java 无 WIRE 方块，故加块后才活化）。 */
    private static void wireExtend(World w, Civilization c) {
        if (!MATERIAL_WORKS) return;
        while (c.gear >= GEAR_TO_WIRE) {
            c.gear -= GEAR_TO_WIRE;
            c.gridDegree++;
            w.log("industry", "wire_extend", "factory", "gear=" + GEAR_TO_WIRE + ",grid=" + c.gridDegree);
        }
    }

    // ================================================================ 宗教（M70 religion）
    private static void religion(World w, Civilization c) {
        int alive = alive(w) + (w.player != null ? 1 : 0);
        // 跨系统联动：虔诚立起便抚慰民心，压低“战争·革命”的民怨（同容器直写，对齐 Python link.couple）
        if (c.faith >= 0f) c.unrest = round4(Math.max(0f, c.unrest - SOOTHE));
        // 信徒：人口 × 传教强度（float 累积）
        float growth = Math.max(0f, alive * BELIEVER_RATE - c.believers * 0.01f);
        c.believers = round3(Math.max(0f, c.believers + growth));
        // 神殿：信众到规模即建殿
        if (c.believers >= TEMPLE_TH && c.temples < TEMPLE_CAP) {
            c.temples++;
            if (c.temples <= 3) w.log("religion", "build", "temple", "believers=" + c.believers + ",temple=" + c.temples);
        }
        // 虔诚：神殿聚人心
        c.faith = round4(Math.min(1f, c.faith + c.temples * 0.002f - c.temples * 0.0004f));
        // 异象：极虔诚时偶发神迹（科研灵感 + 狂热 + 立宗派）
        SeededRNG r = w.simStream("religion:70");
        if (c.faith >= MIRACLE_TH && r.nextDouble() < MIRACLE_RATE) {
            c.miracles++;
            c.faith = round4(Math.max(0f, c.faith - 0.2f));
            c.sects++;
            c.research += RESEARCH_BOOST;
            if (c.miracles <= 4) w.log("religion", "miracle", "temple", "faith=" + c.faith + ",sect=" + sectNameOf(c.sects));
        }
    }

    // ================================================================ 外交（M36 diplomacy）
    private static void diplomacy(World w, Civilization c) {
        float coins = coins(w);
        float power = alive(w) * 0.5f + coins * 0.002f + c.research * 0.02f;   // robots=0
        for (Civilization.Faction f : c.factions) {
            float base = baseline(f.id);
            float rel = c.relations.get(f.id);
            f.power = round3(rel * alive(w) * 0.1f + coins * 1e-3f);
            f.border = round3(Math.min(1f, 0.5f + (power - f.power) * 0.05f));
            float noise = noise(w.tick, saltOf(f.id));      // [0,1) 确定性伪噪声
            float tension = -(noise - 0.5f) * TENSION;
            float coop = Math.min(COOP_CAP, coins * 1e-3f * COOP);
            float reversion = (base - rel) * REVERSION;
            float nr = round3(Math.max(-1f, Math.min(1f, rel + DRIFT + tension + coop + reversion)));
            c.relations.put(f.id, nr);
            String post = nr < -0.3f ? "hostile" : nr > 0.3f ? "friendly" : "neutral";
            c.posture.put(f.id, post); f.posture = post;
            if (f.border > WAR_BORDER) {
                c.conflicts++;
                w.log("diplomacy", "conflict", f.name, "border=" + round2(f.border));
            }
        }
        // 贸易协定：货币丰盈且关系趋暖
        if (coins > TREATY_COINS) {
            for (Civilization.Faction f : c.factions) {
                if (c.relations.get(f.id) > TREATY_REL && !c.treated.contains(f.id) && c.treaties < TREATY_CAP) {
                    c.treated.add(f.id); c.treaties++;
                    c.relations.put(f.id, round3(c.relations.get(f.id) + 0.2f));
                    w.log("diplomacy", "treaty", f.name, "treaties=" + c.treaties);
                }
            }
        }
        // 战争与和平
        float avg = 0f; for (float v : c.relations.values()) avg += v;
        avg /= Math.max(1, c.relations.size());
        if (avg < WAR_TH && !c.war) { c.wars++; c.war = true; w.log("diplomacy", "war", "border", "avg=" + round2(avg)); }
        if (c.treaties >= PEACE_TREATIES && c.war) { c.war = false; w.log("diplomacy", "peace", "diplomacy", "treaties=" + c.treaties); }
    }

    private static float baseline(String fid) {
        if ("nomad".equals(fid)) return 0.1f;
        if ("hill".equals(fid)) return 0.3f;
        return 0.2f;
    }

    /** 势力固定盐值（Python hash(fid) 进程内随机；Java 取稳定常量以保证跨进程可复现）。 */
    private static int saltOf(String fid) {
        if ("nomad".equals(fid)) return 101;
        if ("hill".equals(fid)) return 211;
        return 307;
    }

    /** 确定性伪噪声 [0,1)（随 tick 稳定漂移；不占 world rng 源）。 */
    private static float noise(int tick, int salt) {
        double x = tick * 3.0 + salt;
        double v = StrictMath.sin(x * 12.9898 + salt * 78.233) * 43758.5453;
        return (float) (v - Math.floor(v));
    }

    // ================================================================ 战争与革命（M71 warfare）
    private static void warfare(World w, Civilization c) {
        int alive = alive(w) + (w.player != null ? 1 : 0);
        // 政权与军势：人口/科研供养
        c.milPower = round2(c.milPower + alive * MIL_RATE + c.research * TECH_RATE);
        if (c.milPower >= ARMY_TH) c.armies = (int) c.milPower;
        // 军势庞大反噬：僵化 -> 不满累积
        if (c.armies > STAGNATE) c.unrest = round4(Math.min(1f, c.unrest + 0.01f));
        // 征伐：地缘张力越阈
        SeededRNG r = w.simStream("warfare:71");
        if (r.nextDouble() < WAR_RATE && c.milPower >= WAR_TH_MIL) {
            c.wfWars++;
            c.milPower = round2(c.milPower * SPEND);
            c.unrest = round4(Math.min(1f, c.unrest + 0.06f));
            c.believers = round3(c.believers + FAITH_BOOST);   // 联动：苦难凝聚信仰
            if (c.wfWars <= 4) w.log("warfare", "war", "frontier", "power=" + c.milPower);
        }
        // 革命：民怨积压过久 -> 朝代更迭
        if (c.unrest >= REVOLUTION_TH) {
            c.revolutions++;
            c.unrest = 0f;
            c.regimes++;
            c.milPower = round2(c.milPower * DYNASTY);
            if (c.revolutions <= 3) w.log("warfare", "revolution", "capital", "regimes=" + c.regimes);
        }
    }

    // ================================================================ 文化（M12 culture）
    private static void culture(World w, Civilization c) {
        int added = incubate(w, c);
        if (added > 0) w.log("culture", "incubate", "village",
                "added=" + added + ",festivals=" + c.festivals.size() + ",taboos=" + c.taboos.size() + ",legends=" + c.legends.size());
    }

    /** 从村志档案催化文化（节日/绰号/禁忌/传说）；只读，返回新增条数。 */
    private static int incubate(World w, Civilization c) {
        List<core.world.Chronicle.Episode> eps = w.chronicle.episodes;
        if (eps.isEmpty()) return 0;
        int added = 0;

        // --- 节日：按转折类型的**发生次数**计数，达标即立节 ---
        // 用 Chronicle.counts（每一次发生都计）而非 episodes 条数：节日纪念的是“事情发生了多少次”，
        // 不是“留下了多少条不同档案”。这样 D2 长程去重既不会削弱节日可达性，也与去重前的数值完全一致。
        int marry = w.chronicle.count("MARRY"), birth = w.chronicle.count("BIRTH");
        int awaken = w.chronicle.count("AWAKEN"), despair = w.chronicle.count("DESPAIR");
        if (marry >= FEST_MARRY && !c.festivals.contains("婚嫁节")) { c.festivals.add("婚嫁节"); added++; }
        if (birth >= FEST_BIRTH && !c.festivals.contains("添丁节")) { c.festivals.add("添丁节"); added++; }
        if (awaken >= FEST_AWAKEN && !c.festivals.contains("启灵节")) { c.festivals.add("启灵节"); added++; }

        // --- 绰号：某村民在“婚娶/添丁/觉醒”类事件里被反复提及 ---
        List<String> names = new ArrayList<String>();
        for (Npc n : w.npcs) if (!n.dead() && n.name != null && !n.name.isEmpty()) names.add(n.name);
        java.util.Collections.sort(names);
        for (String nm : names) {
            if (c.nicknames.containsKey(nm)) continue;
            int cnt = 0;
            for (core.world.Chronicle.Episode e : eps) {
                if (("marry".equals(e.action) || "birth".equals(e.action) || "awaken".equals(e.action))
                        && e.text != null && e.text.contains(nm)) cnt++;
            }
            if (cnt >= NICKNAME_MIN) { c.nicknames.put(nm, cnt >= 6 ? "村中老友" : "常走动的人"); added++; }
        }

        // --- 禁忌：曾逢低迷之世 -> 传成口讳 ---
        if (despair > 0 && c.taboos.isEmpty()) {
            c.taboos.add("自打经历过那阵低迷，村里便隐隐传着：那样的时节不祥，人皆避讳。");
            added++;
        }
        // --- 传说：祭坛觉醒的出处 ---
        if (awaken > 0 && c.legends.isEmpty()) {
            c.legends.add("灵脉深处有祭坛，村人称那处是得了造化的风水宝地。");
            added++;
        }
        return added;
    }

    // ================================================================ 工具
    private static int alive(World w) {
        int c = 0; for (Npc n : w.npcs) if (!n.dead()) c++;
        return c;
    }

    /** 合成“贸易财富”（Java 无 _trade.coins；取繁荣 x1 替代，量级对齐 Python trade.coins 以避关系饱和）。 */
    private static float coins(World w) { return w.prosperity * 1f; }

    /** 统计某方块类型格数（方块不存在则 0）。 */
    private static int countBlock(World w, String id) {
        if (!Blocks.has(id)) return 0;
        return w.cellsOfType(Blocks.index(id)).size();
    }

    private static float clamp01(float v) { return Math.max(0f, Math.min(1f, v)); }
    private static float round2(float v) { return Math.round(v * 100f) / 100f; }
    private static float round3(float v) { return Math.round(v * 1000f) / 1000f; }
    private static float round4(float v) { return Math.round(v * 10000f) / 10000f; }
}
