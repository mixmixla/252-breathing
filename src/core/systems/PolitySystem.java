package core.systems;

import core.agent.Npc;
import core.agent.Social;
import core.rng.SeededRNG;
import core.world.Blocks;
import core.world.Polity;
import core.world.World;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * 民政与经济驱动系统（批次 4 · 深度打磨已覆盖系统）：把 Python M11 civilization 与 M34 trade
 * 的确定性路径接进 World.tick 主循环，落在 {@link Polity} 容器。
 *
 * 忠移植对应（Python → 本系统子步）：
 *  - civilization.town_profile   → townProfile（按人口/户数分级：聚落/村落/集镇/堡垒）
 *  - civilization.update_prices  → updatePrices（供给越缺越贵）
 *  - civilization._try_crime     → crime（盗窃/斗殴）
 *  - civilization.judge          → judge（民意+亲疏仲裁）
 *  - trade._mint/_caravans/_inflation/_supply_chain → trade（发币/商队/通胀/供应链）
 *
 * 零漂移铁律（与 SocialSystem/StorytellerSystem/CivilizationSystem 同）：
 *  - 只改 {@link Polity} 开放标量 + NPC 实体态（body/mind/social/inventory）+ events；
 *  - 绝不写 mat/mass/prosperity/skills/villageMemory；绝不读 world.rng 主状态或 fxRng；
 *  - 本系统**不使用随机**（Python M11/M34 亦无 rng），故确定性天然成立。
 *
 * 信号源替换（Java 无 Python 的作物网格 / NPC 采集决策 / hash(fid)），按批次 1–3 先例合成，
 * 并在各方法注明：
 *  - crops（耕地）        = w.civ.farmArea（无作物网格）
 *  - 稀缺判定            = !(farmArea>=5 && prosperity>=3 && !drought)（Python 用 hunger>=85，Java 不可达）
 *  - NPC 库存来源        = labor() 劳作步（Python 来自 decision.GATHER，Java 决策层未实现采集）
 *  - 仇恨/结怨可达性      = 盗窃 -> victim.mind.hurt + social.drift("grudge")（Python 靠 combat，NPC 无战斗）
 */
public final class PolitySystem implements System {

    // ---- 子步间隔（镜像 Python：civilization.update 每 5 tick；trade.interval 缺省 10）----
    private static final int I_CIV = 5, I_TRADE = 10;
    // ---- 犯罪节奏（有界，避免每子步都爆案；确定性相位）----
    private static final int CRIME_THEFT_PHASE = 0, CRIME_FIGHT_PHASE = 20, CRIME_INTERVAL = 40;

    // ---- 集市定价刻度（Java 体素量级按经验常数校准，保持价格落在 0.4–3.0 的可读区间）----
    private static final float SHORTAGE_REF = 2500f, SHORTAGE_EPS = 150f;
    private static final float SHORT_FLOOR = 0.4f, SHORT_CAP = 3.0f;

    // ---- 贸易参数（M34 缺省；Java 无 config，取 Python 缺省常量）----
    private static final float MINT = 1.0f, CARAVAN = 0.4f, DEMAND = 0.02f;
    private static final int TRADE_PERIOD = 200;
    private static final float TRADE_WAVE = 0.15f, INFL_TH = 1.5f;

    // ---- 劳作（NPC 库存来源；替代 Python decision.GATHER）----
    private static final float PRODUCE_BASE = 0.2f, PRODUCE_WOOD = 0.6f, PRODUCE_BERRY = 0.4f;

    // ---- 犯罪阈值 ----
    private static final float STEAL_AMOUNT = 20f, EAT_AMOUNT = 20f;
    private static final float GRUDGE_AFF = -0.45f, GRUDGE_AMOUNT = 0.8f, FIGHT_GRUDGE = 0.7f;
    private static final float BACKER_AFF = 0.45f, PENALTY = 0.15f;
    /** 累犯阈值：前科 >= 此数的犯嫌一律惩戒（保证“惩戒”分支可达，见 Polity.offenderStrikes）。 */
    private static final int RECIDIVISM_LIMIT = 2;

    @Override public String name() { return "PolitySystem"; }

    @Override
    public void update(World w, SeededRNG rng) {
        Polity p = w.polity;
        if (w.tick % I_CIV == 0) {
            townProfile(w, p);
            updatePrices(w, p);
            crime(w, p);
            judge(w, p);
        }
        if (w.tick % I_TRADE == 0) {
            trade(w, p);
        }
    }

    // ================================================================ 城镇档案（M11 town_profile）
    private static void townProfile(World w, Polity p) {
        int alive = alive(w);
        int fams = w.social != null ? w.social.families.size() : 0;
        int crops = w.civ != null ? w.civ.farmArea : 0;       // 合成：无作物网格 → 用耕地面积
        float stock = 0f;
        for (Npc n : w.npcs) if (!n.dead()) for (float v : n.inventory.values()) stock += v;

        int lvl;
        if (alive >= 30 || (alive >= 12 && fams >= 4)) lvl = 3;
        else if (alive >= 12 || (alive >= 8 && fams >= 2)) lvl = 2;
        else if (alive >= 4) lvl = 1;
        else lvl = 0;

        if (lvl > p.townLevel) {
            p.townUps++;
            w.log("polity", "town_up", "lv=" + lvl,
                    "level=" + lvl + ",label=" + Polity.townLabelOf(lvl) + ",alive=" + alive + ",fams=" + fams);
        }
        p.townLevel = lvl;
        p.townLabel = Polity.townLabelOf(lvl);
        p.townAlive = alive; p.townFams = fams; p.townCrops = crops; p.townStock = stock;
    }

    // ================================================================ 集市定价（M11 update_prices）
    private static void updatePrices(World w, Polity p) {
        String[] goods = {"wood", "berry", "iron", "stone", "food"};
        float[] base = {1.0f, 1.0f, 5.0f, 1.5f, 2.5f};
        int[] block = {Blocks.WOOD.index, Blocks.LEAF.index, Blocks.IRON_ORE.index, Blocks.STONE.index, -1};

        for (int i = 0; i < goods.length; i++) {
            float supply;
            if (block[i] >= 0) supply = w.cellsOfType(block[i]).size();       // 网格存量（格数）
            else supply = w.civ != null ? w.civ.farmArea : 0;                 // 食物：耕地面积
            for (Npc n : w.npcs) {
                if (n.dead()) continue;
                Float inv = n.inventory.get(goods[i]);
                if (inv != null) supply += inv;                              // 村民库存折算
            }
            float shortage = clamp(SHORT_FLOOR, SHORT_CAP, SHORTAGE_REF / (supply + SHORTAGE_EPS));
            p.marketPrice.put(goods[i], round2(base[i] * shortage));
        }
        p.priceUpdates++;
        if (w.tick % 40 == 0) {   // 有界记录（避免事件日志膨胀）
            w.log("polity", "market_price", "market", "prices=" + priceStr(p));
        }
    }

    // ================================================================ 犯罪与仲裁（M11 crime/judge）
    /**
     * 犯罪：饥饿至极或暴怒结仇者铤而走险（盗窃/斗殴）。
     *
     * 可达性设计（关键——避免“死尾”）：Python 用 hunger>=85 触发盗窃，但 Java 的
     * {@link core.agent.Decision#eatThreshold}=60 使饥饿永远到不了 85（NPC 到 60 即进食回落），
     * 且 NPC 无战斗、anger/grudge/inventory 皆空 —— 照搬必成死分支。故按“信号源替换”纪律改为：
     *  - 盗窃：**食物不安全**（耕地不足/繁荣过低/正在干旱）时，最穷者向最富者伸手（可达）；
     *  - 结怨：盗窃令受害者 hurt（anger/grudge↑）+ 亲疏下调（可达地造出负亲疏）；
     *  - 斗殴：宿怨（grudge>=0.7）且亲疏 <= -0.45 者出手（前一步造出的信号，可达）。
     */
    private static void crime(World w, Polity p) {
        List<Npc> sorted = aliveSorted(w);
        if (sorted.size() < 2) return;

        boolean drought = w.calamity != null && "drought".equals(w.calamity.flag);
        boolean foodSecure = w.civ != null && w.civ.farmArea >= 5 && w.prosperity >= 3 && !drought;

        // ---- 盗窃（相位 0）----
        if (!foodSecure && w.tick % CRIME_INTERVAL == CRIME_THEFT_PHASE) {
            Npc thief = sorted.get(0), victim = null;
            for (Npc n : sorted) if (stock(n) < stock(thief)) thief = n;          // 最穷（tie → id 序靠前）
            for (Npc n : sorted) {
                if (n == thief) continue;
                if (victim == null || stock(n) > stock(victim)) victim = n;        // 最富
            }
            if (victim != null && steal(w, p, thief, victim)) {
                p.crimes++; p.thefts++;
                w.log("npc", "crime", "{kind=theft,offender=" + thief.id + ",target=" + victim.id + "}", "eaten");
            }
        }

        // ---- 斗殴（相位 20）----
        if (w.tick % CRIME_INTERVAL == CRIME_FIGHT_PHASE) {
            for (Npc n : sorted) {
                String foe = worstFoe(n);
                if (foe == null) continue;
                Float g = n.mind.grudge.get(foe);
                if (g == null || g < FIGHT_GRUDGE) continue;
                n.social.drift(foe, Social.DELTA.get("grudge") * 0.8f, "grudge", "一言不合动了手", w.tick);
                Npc fo = byId(w, foe);
                if (fo != null && !fo.dead()) fo.social.drift(n.id, Social.DELTA.get("grudge") * 0.8f, "grudge", "挨了一拳", w.tick);
                p.crimes++; p.fights++;
                w.log("npc", "crime", "{kind=fight,offender=" + n.id + ",target=" + foe + "}", "rage");
                break;   // 每相位至多一起，避免集中爆发
            }
        }
    }

    /** 盗窃：从 victim 最大存量且 >= STEAL_AMOUNT 的商品里偷一份给 thief，thief 进食，victim 记仇。返回是否发生。 */
    private static boolean steal(World w, Polity p, Npc thief, Npc victim) {
        String good = null; float best = 0f;
        for (java.util.Map.Entry<String, Float> e : victim.inventory.entrySet()) {
            if (e.getValue() != null && e.getValue() >= STEAL_AMOUNT && e.getValue() > best) { best = e.getValue(); good = e.getKey(); }
        }
        if (good == null) return false;
        victim.inventory.put(good, victim.inventory.get(good) - STEAL_AMOUNT);
        Float cur = thief.inventory.get(good);
        thief.inventory.put(good, (cur == null ? 0f : cur) + STEAL_AMOUNT);
        thief.body.eat(EAT_AMOUNT);
        // 受害者的反应：受伤（愤怒 / 记仇）+ 亲疏下调 —— 造出可达的负亲疏与宿怨
        victim.mind.hurt(thief.id, GRUDGE_AMOUNT);
        victim.social.drift(thief.id, Social.DELTA.get("grudge"), "grudge", "被偷了东西", w.tick);
        p.crimeLog.add("t" + w.tick + " theft " + thief.id + "<-" + victim.id);
        return true;
    }

    /** 仲裁：对新增的 npc/crime 事件做“民意 + 亲疏 + 累犯”裁决（有人相护→包庇；无人或累犯→惩戒）。 */
    private static void judge(World w, Polity p) {
        for (int i = p.judgedEvents; i < w.events.size(); i++) {
            World.Event e = w.events.get(i);
            if (!"npc".equals(e.system) || !"crime".equals(e.action)) continue;
            String off = between(e.params, "offender");
            Npc offender = byId(w, off);
            if (offender == null || offender.dead()) continue;

            // 累犯计次（初犯可获人情；惯犯失众望 → 惩戒可达）
            Integer pri = p.offenderStrikes.get(off);
            int prior = pri == null ? 0 : pri;
            p.offenderStrikes.put(off, prior + 1);
            boolean recidivist = prior >= RECIDIVISM_LIMIT;

            boolean backed = false;
            if (!recidivist) {   // 未成惯犯者，方有人相护
                for (float a : offender.social.affinity.values()) if (a >= BACKER_AFF) { backed = true; break; }
            }
            if (backed) {
                p.shielded++;
                w.log("npc", "judge", "{offender=" + off + "}", "包庇（有人相护，从轻）");
            } else {
                List<String> ids = new ArrayList<String>(offender.social.affinity.keySet());
                Collections.sort(ids);
                for (String oid : ids) {
                    Float a = offender.social.affinity.get(oid);
                    if (a != null && a > 0f) offender.social.drift(oid, -PENALTY, "penalty", "众叛亲离", w.tick);
                }
                p.penalized++;
                if (recidivist) p.recidivists++;
                w.log("npc", "judge", "{offender=" + off + "}",
                        recidivist ? "惩戒（惯犯失众望）" : "惩戒（无人相护，众叛亲离）");
            }
            p.judges++;
        }
        p.judgedEvents = w.events.size();
    }

    // ================================================================ 贸易与货币（M34 trade）
    private static void trade(World w, Polity p) {
        labor(w);                         // 劳作：村民库存来源（替代 Python decision.GATHER）

        // --- 货币发行（M34 _mint）---
        float surplus = 0f;
        for (float v : p.marketPrice.values()) surplus += Math.max(0f, v);
        p.surplus = round2(surplus);
        float mint = MINT * (0.2f + surplus * 0.05f);
        p.coins += mint; p.moneySupply += mint;
        float lv = w.civ != null ? w.civ.landValue : 0f;
        p.coins += lv * 0.02f; p.moneySupply += lv * 0.02f;

        // --- 商队物流（M34 _caravans；rate<1 时用小数累积器攒够 1 才 +1，避免 (int) 死尾）---
        int roads = w.civ != null ? w.civ.roads : 0;
        float rate = CARAVAN * (1f + roads * 0.05f + p.moneySupply * 1e-4f);
        p.caravanFrac += rate;
        int whole = (int) p.caravanFrac;
        if (whole > 0) { p.caravans += whole; p.caravanFrac -= whole; }
        for (String g : p.goodsFlow.keySet()) {
            float inc = rate * (("wood".equals(g) || "berry".equals(g)) ? 0.3f : 0.2f);
            p.goodsFlow.put(g, p.goodsFlow.get(g) + inc);
        }

        // --- 通胀（M34 _inflation）---
        float goodsGrowth = 0f;
        for (float v : p.goodsFlow.values()) goodsGrowth += v;
        float base = p.moneySupply / Math.max(1e-9f, 1f + goodsGrowth);
        float cycle = (float) StrictMath.sin(2.0 * Math.PI * w.tick / (double) TRADE_PERIOD);
        p.cycles = round3(cycle);
        p.inflation = round4(base * (1f + cycle * TRADE_WAVE));
        if (p.inflation > INFL_TH && !p.inflating) {
            p.inflating = true;
            w.log("trade", "inflation", "market", "infl=" + round3(p.inflation));
        }

        // --- 供应链（M34 _supply_chain）：货差记账 + 反向扰动集市定价 ---
        int alive = alive(w);
        float deficit = 0f;
        for (String g : p.goodsFlow.keySet()) {
            float supply = p.goodsFlow.get(g);
            float demand = alive * DEMAND;
            float short_ = demand - supply;
            if (short_ > 0 && p.marketPrice.containsKey(g)) {
                float k = Math.min(1.5f, 1f + short_ / Math.max(1e-9f, supply + 1f));
                p.marketPrice.put(g, round2(p.marketPrice.get(g) * k));
                deficit += short_;
            }
        }
        p.deficit = round2(deficit);
        p.ledgers++;
        w.log("trade", "ledger", "market", "coins=" + round1(p.coins) + ",infl=" + round3(p.inflation)
                + ",caravans=" + p.caravans + ",deficit=" + round2(p.deficit));
    }

    /** 劳作：每个存活 NPC 按食物安全度产出木/莓入库（确定性；由 trade 间隔驱动）。 */
    private static void labor(World w) {
        float foodK = w.civ != null ? clamp01(w.civ.farmArea / 10f) : 0f;
        for (Npc n : w.npcs) {
            if (n.dead()) continue;
            addInv(n, "wood", PRODUCE_BASE + PRODUCE_WOOD * foodK);
            addInv(n, "berry", PRODUCE_BASE * 0.5f + PRODUCE_BERRY * foodK);
        }
    }

    private static void addInv(Npc n, String k, float v) {
        Float cur = n.inventory.get(k);
        n.inventory.put(k, (cur == null ? 0f : cur) + v);
    }

    // ================================================================ 工具
    private static int alive(World w) {
        int c = 0; for (Npc n : w.npcs) if (!n.dead()) c++;
        return c;
    }

    /** 存活 NPC 按 id 升序（确定性遍历序）。 */
    private static List<Npc> aliveSorted(World w) {
        List<Npc> list = new ArrayList<Npc>();
        for (Npc n : w.npcs) if (!n.dead()) list.add(n);
        Collections.sort(list, new Comparator<Npc>() {
            @Override public int compare(Npc a, Npc b) { return a.id.compareTo(b.id); }
        });
        return list;
    }

    private static Npc byId(World w, String id) {
        if (id == null || id.isEmpty()) return null;
        for (Npc n : w.npcs) if (id.equals(n.id)) return n;
        return null;
    }

    private static float stock(Npc n) {
        float s = 0f;
        for (float v : n.inventory.values()) s += v;
        return s;
    }

    /**
     * 宿敌判定：优先取**累积仇恨最高者**（mind.grudge，持久不衰减 —— 由盗窃的 hurt 造出，可达），
     * 其次取亲疏极差者（affinity<=-0.45）。返回 null 表示无宿敌。
     *
     * 可达性说明：Python 用 “anger>=0.7 且存在 affinity<=-0.45 的仇人”，但 Java 的 anger 衰减快、
     * 单次盗窃只把 affinity 压到 -0.25（未达 -0.45），照搬必成死分支。故改用持久 grudge 作主判据。
     */
    private static String worstFoe(Npc n) {
        List<String> gids = new ArrayList<String>(n.mind.grudge.keySet());
        Collections.sort(gids);
        String foe = null; float worst = 0f;
        for (String oid : gids) {
            Float g = n.mind.grudge.get(oid);
            if (g == null) continue;
            if (foe == null || g > worst) { foe = oid; worst = g; }
        }
        if (foe != null && worst >= FIGHT_GRUDGE) return foe;
        // 备选：亲疏极差者
        List<String> aids = new ArrayList<String>(n.social.affinity.keySet());
        Collections.sort(aids);
        foe = null; worst = 0f;
        for (String oid : aids) {
            Float a = n.social.affinity.get(oid);
            if (a == null || a > GRUDGE_AFF) continue;
            if (foe == null || a < worst) { foe = oid; worst = a; }
        }
        return foe;
    }

    private static String priceStr(Polity p) {
        StringBuilder sb = new StringBuilder();
        for (java.util.Map.Entry<String, Float> e : p.marketPrice.entrySet()) {
            if (sb.length() > 0) sb.append(',');
            sb.append(e.getKey()).append('=').append(e.getValue());
        }
        return sb.toString();
    }

    /** 从 "{actor=X,spouse=Y}" 里取 key 的值（到 ',' 或 '}' 止）。 */
    private static String between(String s, String key) {
        if (s == null) return "";
        int i = s.indexOf(key + "=");
        if (i < 0) return "";
        int j = i + key.length() + 1, k = j;
        while (k < s.length()) { char c = s.charAt(k); if (c == ',' || c == '}') break; k++; }
        return s.substring(j, k);
    }

    private static float clamp01(float v) { return Math.max(0f, Math.min(1f, v)); }
    private static float clamp(float lo, float hi, float v) { return Math.max(lo, Math.min(hi, v)); }
    private static float round1(float v) { return Math.round(v * 10f) / 10f; }
    private static float round2(float v) { return Math.round(v * 100f) / 100f; }
    private static float round3(float v) { return Math.round(v * 1000f) / 1000f; }
    private static float round4(float v) { return Math.round(v * 10000f) / 10000f; }
}
