package core.sim;

import core.world.Beast;
import core.world.Player;
import core.world.Trials;
import core.world.World;

/**
 * 试炼/遗物/世界之心 确定性门禁（C2 · 塞尔达循环后半段）。
 *
 * <p>本门禁做两件事：
 * <ol>
 *   <li><b>不变量</b>：接入 TrialSystem 后，同种子两遍运行 → {@link World#hashState()} <b>逐字节一致</b>
 *       （证明试炼层不写 mat/mass/prosperity/skills/villageMemory），且 {@link Trials#snapshot()} 一致。</li>
 *   <li><b>可达性</b>：以脚本化“玩家巡礼”（确定性输入：直接设定位置 + 授力 + <b>把对应能力真的用出来</b>）
 *       依次走完三座试炼点、四处补给箱、峰顶世界之心，断言 <b>relics=3、caches=4、heartClaimed=true</b> ——
 *       证明目标链真的能从头走到尾（而不是像批次 3/4 那种“系统在跑、分支永不触发”的死尾）。</li>
 *   <li><b>封印有效</b>（2026-09-16 新增）：只走近、不把能力用出来 → <b>一个遗物也拿不到</b> ——
 *       证明“塞尔达式以能力破印”是真的门，而不是可以无视的装饰。</li>
 * </ol>
 *
 * 运行：java -cp out core.sim.TrialDeterminismTest
 */
public final class TrialDeterminismTest {

    /**
     * 一趟巡礼的观测量（P0-4：世界之心守卫）。
     * <p>守卫必须真的「挡住终局」：走到心前但守卫未死 → 拿不到；击杀守卫后才拿得到。
     * 这条断言防的是"守卫生成了、但根本没参与判定"这种死尾。
     */
    static final class Report {
        boolean wardenSpawned, blocked, slain, enraged, enragedWindup, enragedDmg;
        int soulsGained;
    }
    /** 最近一趟巡礼的观测量（main 在调用后立即取走；本门禁是单线程脚本）。 */
    private static Report LAST = new Report();

    public static void main(String[] args) {
        final int SX = 64, SY = 40, SZ = 64;
        final long SEED = 20260910L;

        World a = run(SEED, SX, SY, SZ);
        Report ra = LAST;
        World b = run(SEED, SX, SY, SZ);

        long ha = a.hashState(), hb = b.hashState();
        boolean hashOk = ha == hb;
        boolean trialsOk = a.trials.snapshot().equals(b.trials.snapshot());
        boolean reachable = a.trials.claimedSites() == 3
                && a.trials.claimedCaches() == 4
                && a.trials.heartClaimed;
        // 门有效性：同样授力、同样走近，但“不用出能力” → 必须一个遗物都拿不到
        World c = run(SEED, SX, SY, SZ, false);
        boolean gateReal = c.trials.claimedSites() == 0 && !c.trials.heartClaimed;
        // P0-4：守卫必须真的挡住终局（生成 → 挡住 → 击杀后放行 → 魂到账）
        boolean wardenReal = ra.wardenSpawned && ra.blocked && ra.slain
                && ra.enraged && ra.enragedWindup && ra.enragedDmg && ra.soulsGained >= 260;
        boolean pass = hashOk && trialsOk && reachable && gateReal && wardenReal;

        Trials t = a.trials;
        System.out.printf("TRIAL-DETERMINISM  hashA=%016x hashB=%016x  sites=%d caches=%d%n",
                ha, hb, t.sites.size(), t.caches.size());
        System.out.println("TRIALS A = " + t.snapshot());
        System.out.println("HUD = " + t.asciiSummary());
        System.out.println("OBJ = " + t.objective(a.player.abilities));
        System.out.printf("REACH relics=%d caches=%d heartRevealed=%b heartClaimed=%b souls=%d%n",
                t.claimedSites(), t.claimedCaches(), t.heartRevealed, t.heartClaimed, a.player.souls);
        System.out.println("SEAL  gateReal=" + gateReal + "  (不用出能力 → 遗物 " + c.trials.claimedSites() + "/3)");
        System.out.println("WARDEN  spawned=" + ra.wardenSpawned + " blockedHeart=" + ra.blocked
                + " slain=" + ra.slain + " souls+=" + ra.soulsGained
                + " | enrage=" + ra.enraged + " windupShortened=" + ra.enragedWindup
                + " dmgUp=" + ra.enragedDmg);
        System.out.println(pass ? "TRIAL-DETERMINISM PASS" : "TRIAL-DETERMINISM FAIL");
        if (!pass) System.exit(1);
    }

    /** 一趟确定性巡礼：首 tick 布点 → 授力 → 依次走访试炼点/补给箱 → 登顶认取世界之心。 */
    private static World run(long seed, int sx, int sy, int sz) { return run(seed, sx, sy, sz, true); }

    /** useAbilities=false 时：只授力 + 走近，不把能力用出来（用于证明封印是真门）。 */
    private static World run(long seed, int sx, int sy, int sz, boolean useAbilities) {
        Simulation sim = new Simulation(seed, sx, sy, sz);
        World w = sim.world;
        w.tick();                                   // tick 1：TrialSystem 确定性布点

        Player p = w.player;
        p.invuln = 1_000_000f;                      // 免于野兽干扰（脚本化输入的一部分）
        p.grantAbility("GLIDE");
        p.grantAbility("DASH");
        p.grantAbility("BOMB");

        for (Trials.Site s : w.trials.sites) {
            if (useAbilities) {
                // 新规则：破印要求“刚刚真的用出该能力”——脚本里以“设置最近使用时刻”等价表达
                if ("GLIDE".equals(s.ability)) p.lastGlideTick = w.tick;
                else if ("DASH".equals(s.ability)) p.lastDashTick = w.tick;
                else if ("BOMB".equals(s.ability)) p.lastBombTick = w.tick;
            }
            p.x = s.x; p.y = s.y; p.z = s.z;
            w.tick();
        }
        for (Trials.Cache c : w.trials.caches) { p.x = c.x; p.y = c.y; p.z = c.z; w.tick(); }
        LAST = new Report();                        // 每趟新建：否则前一趟会被后一趟覆写
        Report rep = LAST;
        w.tick();                                   // 过阈：世界之心显现 → 守卫苏醒
        Trials tr = w.trials;
        rep.wardenSpawned = tr.bossSpawned && anyWarden(w);
        // 站在心前：守卫未死 → 必须认取不到（终局不再是"走到最高峰站一下"）
        p.x = tr.heartX; p.y = tr.heartY; p.z = tr.heartZ;
        for (int i = 0; i < 4; i++) w.tick();
        rep.blocked = !tr.heartClaimed;
        // 击杀守卫：走玩家同一套结算（Player.hitBeast），顺带观察二阶段
        int soulsBefore = p.souls;
        for (Beast bb : new java.util.ArrayList<Beast>(w.beasts)) {
            if (bb.type != Beast.TYPE_BOSS) continue;
            bb.hp = (int) (bb.maxHp * 0.2f);        // 压到激怒区
            rep.enraged = bb.enraged();
            rep.enragedWindup = bb.effectiveWindup() < bb.windupT;
            rep.enragedDmg = bb.effectiveDmg() > bb.dmg;
            p.hitBeast(w, bb, 99999, "test");
        }
        w.tick();                                   // 观察销毁 → bossSlain + 魂结算
        rep.slain = tr.bossSlain;
        rep.soulsGained = p.souls - soulsBefore;
        // 守卫已死 → 登顶认取
        for (int i = 0; i < 3; i++) { p.x = tr.heartX; p.y = tr.heartY; p.z = tr.heartZ; w.tick(); }
        return w;
    }

    /** 场上是否还有守卫活着。 */
    private static boolean anyWarden(World w) {
        for (Beast b : w.beasts) if (b.type == Beast.TYPE_BOSS) return true;
        return false;
    }
}
