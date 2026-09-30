package core.world;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 试炼 · 遗物 · 世界之心（C2 · 塞尔达循环后半段：「能力 → 解谜点 → 回报 → 终局」）。
 *
 * <p>祭坛（{@link World.Shrine}）负责循环的<b>前半段</b>：授予能力。本类负责<b>后半段</b>：
 * 让世界出现「只有用这项能力才过得去」的<b>解谜点</b>、值得一探的<b>可交互物</b>、
 * 以及把一切串起来的<b>世界目标链</b>。
 *
 * <h3>三类可交互目标</h3>
 * <ul>
 *   <li>{@link Site}  <b>试炼点（解谜点）</b>：三座，各以一项能力为“印”（GLIDE/DASH/BOMB）。
 *       未具能力时只见封印；具能力且靠近方可破印取<b>遗物</b>。</li>
 *   <li>{@link Cache} <b>补给箱（可交互物）</b>：散落各处，靠近即取，给魂 —— 让探索本身即时回报。</li>
 *   <li><b>世界之心</b>：集齐三枚遗物后于全图最高峰显现；三力齐备 + 登顶方可认取（终局）。</li>
 * </ul>
 *
 * <h3>零漂移铁律</h3>
 * 本类是<b>实体级开放状态</b>，与 npcs/beasts/shrines/social/chronicle/civ/individual/polity/calamity 同纪律：
 * <b>不进</b> {@link World#hashState()}，且认取时<b>不写</b> mat/mass/prosperity/skills/villageMemory。
 * 于是「新增一整套塞尔达后半段玩法」而四道零漂移门禁的 DETERMINISM 指纹<b>逐字节不变</b>——
 * 这正是把「世界因你而变」做成可见目标链（CD-PILLARS P1）却零漂移的关键。
 *
 * 与 {@link Chronicle} 的接口：认取只写 {@code events}（trial/relic、trial/heart 等），由说书人归档进村志，
 * 使玩家的探索被“世界记得”——而 events 与 Chronicle 同样不进指纹。
 */
public final class Trials {

    /** 一座试炼点（解谜点）：以能力为印；破印得遗物。 */
    public static final class Site {
        public final float x, y, z;
        public final String ability;   // GLIDE / DASH / BOMB —— 破印所需能力
        public final String name;      // ASCII 名（HUD/日志）
        public boolean claimed = false;
        public Site(float x, float y, float z, String ability, String name) {
            this.x = x; this.y = y; this.z = z; this.ability = ability; this.name = name;
        }
    }

    /** 一处补给箱（可交互物）：靠近即取，给魂（探索的即时回报）。 */
    public static final class Cache {
        public final float x, y, z;
        public final int souls;
        public boolean claimed = false;
        public Cache(float x, float y, float z, int souls) {
            this.x = x; this.y = y; this.z = z; this.souls = souls;
        }
    }

    public final List<Site> sites = new ArrayList<Site>();
    public final List<Cache> caches = new ArrayList<Cache>();

    // ---- 世界之心（终局目标）----
    public float heartX, heartY, heartZ;
    public boolean heartRevealed = false;   // 集齐遗物后显现
    public boolean heartClaimed = false;    // 已认取

    // ---- P0-4：世界之心守卫（唯一 Boss）----
    /**
     * 守卫是否已苏醒（世界之心揭晓的那一刻）。与 {@link #heartClaimed} 一同入档 ——
     * 读档后 {@code TrialSystem.placeOnLoad} 会按它把守卫重新放回峰顶
     * （实体层 beasts 不持久化，所以必须靠这个标志重建）。
     */
    public boolean bossSpawned = false;
    /** 守卫是否已被击败。{@link #heartClaimed} 的前置条件 —— 没打死守卫就认取不了世界之心。 */
    public boolean bossSlain = false;

    /** 已取遗物数（与 {@link #claimedSites()} 恒等；保留冗余便于 HUD/日志直读）。 */
    public int relics = 0;

    public int claimedSites() {
        int n = 0; for (Site s : sites) if (s.claimed) n++; return n;
    }
    public int claimedCaches() {
        int n = 0; for (Cache c : caches) if (c.claimed) n++; return n;
    }
    /** 是否三印皆破（遗物集齐）。 */
    public boolean allRelics() { return !sites.isEmpty() && claimedSites() >= sites.size(); }

    /** 破印时效窗口（tick）：破印要求“刚刚用过”对应能力，而不是身上挂着钥匙。 */
    public static final int GATE_WINDOW = 100;      // 5 s
    /** 炸弹的窗口放宽（要留出走到封印前的时间）。 */
    public static final int BOMB_WINDOW = 200;      // 10 s

    /**
     * 塞尔达式封印判据：该能力是否<b>刚刚被真的用出来</b>。
     * <p>这一条把“三试炼”从“走到地砖上”变成“用能力开门”：
     * GLIDE = 滑翔着落到印上、DASH = 冲刺（带 i 帧）撞进印里、BOMB = 炸开封印再进。
     * 判据只读玩家实体上的“最近使用时刻”，不写任何世界状态 → 零漂移。
     */
    public static boolean abilityUsed(Player p, String ability, long tick) {
        if (p == null) return false;
        if ("GLIDE".equals(ability)) return tick - p.lastGlideTick <= GATE_WINDOW;
        if ("DASH".equals(ability)) return tick - p.lastDashTick <= GATE_WINDOW;
        if ("BOMB".equals(ability)) return tick - p.lastBombTick <= BOMB_WINDOW;
        return true;
    }

    /**
     * 封印提示（纯读，渲染层用）：已具能力、已走到近处、但还没把能力用出来 → 给一句怎么破印。
     * <p>没有这条提示，“走过去没反应”会被读成 bug —— 门必须当场说明自己需要什么。
     */
    public String sealHint(Player p, long tick) {
        if (p == null) return "";
        for (Site s : sites) {
            if (s.claimed || !p.abilities.contains(s.ability)) continue;
            float dx = p.x - s.x, dz = p.z - s.z;
            if (dx * dx + dz * dz > 24f * 24f) continue;      // 只在封印近处提示
            if (abilityUsed(p, s.ability, tick)) continue;    // 已满足：下一 tick 就会认取
            if ("GLIDE".equals(s.ability)) return "SEALED - GLIDE DOWN ONTO THE SEAL";
            if ("DASH".equals(s.ability)) return "SEALED - DASH INTO THE SEAL";
            if ("BOMB".equals(s.ability)) return "SEALED - BOMB THE SEAL OPEN";
            return "SEALED - USE " + s.ability + " HERE";
        }
        return "";
    }

    /** 该能力是否已破印（供渲染层着色）。 */
    public boolean siteClaimed(String ability) {
        for (Site s : sites) if (s.ability.equals(ability)) return s.claimed;
        return false;
    }

    // ---------------------------------------------------------------- 目标链
    /**
     * 当前<b>世界目标</b>（ASCII，渲染层直接上屏）。有序、单条、随进度推进：
     * 觉醒能力 → 逐一破印取遗物 → 峰顶认取世界之心 → 终局。
     */
    public String objective(Set<String> abilities) {
        int have = (abilities == null) ? 0 : abilities.size();
        if (have == 0) return "OBJ  FIND A SHRINE - AWAKEN AN ABILITY  (0/3)";
        if (!allRelics()) return "OBJ  PASS THE TRIALS  RELIC " + claimedSites() + "/" + sites.size()
                + "  NEXT " + nextHint(abilities);
        if (!heartRevealed) return "OBJ  ALL RELICS GATHERED - THE WORLD HEART STIRS";
        // P0-4：终局不再只是"走过去" —— 峰顶有守卫。目标文案必须把这件事说出来，
        // 否则玩家站在心前没反应会被读成 bug（与 SEALED 提示同一纪律）。
        if (!bossSlain) return "OBJ  THE WARDEN GUARDS THE HEART - SLAY IT AT THE SUMMIT";
        if (!heartClaimed) return "OBJ  SEEK THE WORLD HEART AT THE SUMMIT";
        return "OBJ  THE WORLD HEART IS YOURS - THE LAND REMEMBERS";
    }

    /** 下一处未破印试炼的提示：具能力 → 可直接去；否则标注 LOCKED（需先觉醒）。 */
    private String nextHint(Set<String> abilities) {
        for (Site s : sites) {
            if (s.claimed) continue;
            boolean can = abilities != null && abilities.contains(s.ability);
            return s.ability + (can ? "" : "(LOCKED)");
        }
        return "DONE";
    }

    // ---------------------------------------------------------------- HUD / 签名
    /** 试炼进度摘要（ASCII）。 */
    public String asciiSummary() {
        StringBuilder sb = new StringBuilder();
        sb.append("TRIALS ");
        String[] order = {"GLIDE", "DASH", "BOMB"};
        for (String ab : order) {
            Site s = site(ab);
            if (s == null) continue;
            sb.append(ab.charAt(0));
            sb.append(s.claimed ? '+' : '-');
            sb.append(' ');
        }
        sb.append(" RELIC ").append(claimedSites()).append('/').append(sites.size());
        sb.append("  CACHE ").append(claimedCaches()).append('/').append(caches.size());
        sb.append("  HEART ").append(heartClaimed ? "TAKEN" : (heartRevealed ? "OPEN" : "HIDDEN"));
        sb.append("  WARDEN ").append(bossSlain ? "SLAIN" : (bossSpawned ? "ALIVE" : "ASLEEP"));
        return sb.toString();
    }

    private Site site(String ability) {
        for (Site s : sites) if (s.ability.equals(ability)) return s;
        return null;
    }

    /**
     * 确定性签名（判据用，非指纹）：位置 + 认取标志 + 世界之心状态。
     * 供 {@code TrialDeterminismTest} 断言「同种子两遍逐字节一致」。
     */
    public String snapshot() {
        StringBuilder sb = new StringBuilder();
        for (Site s : sites) {
            sb.append(s.ability).append(s.claimed ? '1' : '0')
              .append('@').append(f2(s.x)).append(',').append(f2(s.z)).append(';');
        }
        for (Cache c : caches) {
            sb.append("c").append(c.claimed ? '1' : '0')
              .append('@').append(f2(c.x)).append(',').append(f2(c.z)).append(',')
              .append(c.souls).append(';');
        }
        sb.append("HEART").append(heartRevealed ? '1' : '0').append(heartClaimed ? '1' : '0')
          .append('@').append(f2(heartX)).append(',').append(f2(heartZ));
        sb.append(";WARDEN").append(bossSpawned ? '1' : '0').append(bossSlain ? '1' : '0');
        return sb.toString();
    }

    private static String f2(float v) { return String.format(Locale.US, "%.2f", v); }
}
