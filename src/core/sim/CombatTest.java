package core.sim;

import core.world.Beast;
import core.world.Player;
import core.world.World;

/**
 * 战斗动作门禁（QC 2026-09-13 · C 批② · 攻击状态机接线到 Player）。
 *
 * <p>锁死的是<b>性质</b>而非"能跑"：
 * ① 判定帧驱动——单次攻击意图注入后，伤害在起手后若干 tick（非同 tick）才发生；
 * ② 伤害唯一性——一次攻击（无取消窗连段）只造成一次伤害（判定帧只发一次）；
 * ③ 确定性——同种子两遍运行，beast 终态（命中 tick / 伤害次数 / 剩余 hp）逐位一致。
 * <p>不触发任何指纹门禁路径（attacker intent 仅 Game 注入），全套既存指纹零漂移。
 */
public final class CombatTest {

    private static final int SX = 64, SY = 40, SZ = 64;

    static final class BeastState {
        int hitTick = -1;     // 首次受到伤害的 tick（-1 = 未命中）
        int strikes = 0;      // 整个周期内受伤次数
        int finalHp;
        boolean removed;
        BeastState(int hitTick, int strikes, int finalHp, boolean removed) {
            this.hitTick = hitTick; this.strikes = strikes; this.finalHp = finalHp; this.removed = removed;
        }
        @Override public boolean equals(Object o) {
            if (!(o instanceof BeastState)) return false;
            BeastState b = (BeastState) o;
            return hitTick == b.hitTick && strikes == b.strikes && finalHp == b.finalHp && removed == b.removed;
        }
    }

    /** 跑一遍：t=0 注入一次攻击意图，锁定 beast 位置防止 SI 移动跑出范围，tick 30 帧。 */
    private static BeastState run(long seed) {
        Simulation sim = new Simulation(seed, SX, SY, SZ);
        World w = sim.world;
        Player p = w.player;
        float px = p.x, py = p.y, pz = p.z;
        Beast b = new Beast(px + 0.5f, py, pz, 0);   // 紧贴玩家（< ATK_RANGE=2.6）
        b.hp = 100;                                   // 高血量防致死，便于统计伤害次数
        w.beasts.add(b);

        int hitTick = -1, strikes = 0;
        for (int t = 0; t < 30; t++) {
            b.x = px + 0.5f; b.y = py; b.z = pz;       // 锁位置：隔离 BeastSystem 移动，保证命中
            if (t == 0) w.player.setIntent(Player.Intent.attack());  // 仅 t=0 注入一次攻击
            int before = b.hp;
            w.tick();
            if (before > b.hp) { if (hitTick < 0) hitTick = t; strikes++; }
        }
        return new BeastState(hitTick, strikes, b.hp, !w.beasts.contains(b));
    }

    public static void main(String[] args) {
        int fails = 0;

        BeastState s = run(987654321L);
        boolean delayed = s.hitTick > 0;               // 非同 tick 命中 → 判定帧驱动（非瞬间命中）
        System.out.println("HIT_DELAY " + (delayed ? "PASS" : "FAIL") + "  hitTick=" + s.hitTick);
        if (!delayed) fails++;

        boolean once = s.strikes == 1;                 // 一次攻击只发一次伤害（判定帧唯一性）
        System.out.println("HIT_ONCE_PLAYER " + (once ? "PASS" : "FAIL") + "  strikes=" + s.strikes);
        if (!once) fails++;

        BeastState a = run(111L), b = run(111L);
        boolean det = a.equals(b);
        System.out.println("DET " + (det ? "PASS" : "FAIL")
                + "  a.hit=" + a.hitTick + " a.strk=" + a.strikes + " a.hp=" + a.finalHp
                + " | b.hit=" + b.hitTick + " b.strk=" + b.strikes + " b.hp=" + b.finalHp);
        if (!det) fails++;

        // ---------- 4) 读招（TELEGRAPH）：伤害离散，且必须晚于前摇 ----------
        Simulation st = new Simulation(4242L, SX, SY, SZ);
        World wt = st.world; Player pt = wt.player;
        Beast gr = new Beast(pt.x + 1.0f, pt.y, pt.z, 0);      // GRUNT：windup 14 / recover 18
        wt.beasts.add(gr);
        int tgHits = 0, firstHit = -1, prevHit = -99, minGap = 9999;
        for (int t = 0; t < 70; t++) {
            gr.x = pt.x + 1.0f; gr.z = pt.z;                   // 锁位置：只测出招节奏，隔离移动
            int before = pt.hp;
            wt.tick();
            if (before > pt.hp) {
                tgHits++;
                if (firstHit < 0) firstHit = t;
                if (prevHit > -50) minGap = Math.min(minGap, t - prevHit);
                prevHit = t;
            }
        }
        boolean telegraphOk = tgHits >= 1 && firstHit >= 10 && tgHits <= 3 && minGap >= 20;
        System.out.println("TELEGRAPH " + (telegraphOk ? "PASS" : "FAIL")
                + "  hits=" + tgHits + " firstTick=" + firstHit + " minGap=" + minGap);
        if (!telegraphOk) fails++;

        // ---------- 5) 招式有承诺（COMMIT）：起手锁方向后侧移可躲 ----------
        Simulation sc = new Simulation(777L, SX, SY, SZ);
        World wc = sc.world; Player pc = wc.player;
        Beast k = new Beast(pc.x + 1.0f, pc.y, pc.z, 0);
        wc.beasts.add(k);
        for (int t = 0; t < 10; t++) { k.x = pc.x + 1.0f; k.z = pc.z; wc.tick(); }   // 起手并锁 +X
        boolean lockedWindup = k.atkPhase == Beast.PHASE_WINDUP && Math.abs(k.lockX) > 0.9f;
        pc.x = k.x; pc.z = k.z + 0.5f;                          // 挪到正侧方（仍在接触距离内）
        int hpBefore2 = pc.hp;
        for (int t = 0; t < 20; t++) wc.tick();                 // 覆盖到出手那一刻
        boolean whiff = pc.hp == hpBefore2;
        boolean commitOk = lockedWindup && whiff;
        System.out.println("COMMIT " + (commitOk ? "PASS" : "FAIL")
                + "  locked=" + lockedWindup + " sideStepWhiff=" + whiff);
        if (!commitOk) fails++;

        // ---------- 6) 破势（PUNISH）：打在敌兵前摇上 → 加成 + 打断 ----------
        Simulation sp = new Simulation(31337L, SX, SY, SZ);
        World wp = sp.world; Player pp = wp.player;
        Beast idle = Beast.make(0, pp.x + 1f, pp.y, pp.z); wp.beasts.add(idle);
        int hp0 = idle.hp; pp.hitBeast(wp, idle, 10, "t"); int plain = hp0 - idle.hp;
        Beast wind = Beast.make(0, pp.x + 1f, pp.y, pp.z); wp.beasts.add(wind);
        wind.atkPhase = Beast.PHASE_WINDUP; wind.atkTimer = 7;
        int hp1 = wind.hp; pp.hitBeast(wp, wind, 10, "t"); int broke = hp1 - wind.hp;
        boolean punishOk = plain == 10 && broke == 15
                && wind.atkPhase == Beast.PHASE_IDLE && wind.stagger >= Player.STANCE_BREAK_STAGGER;
        System.out.println("PUNISH " + (punishOk ? "PASS" : "FAIL")
                + "  plain=" + plain + " onWindup=" + broke + " stagger=" + wind.stagger);
        if (!punishOk) fails++;

        // ---------- 7) 药瓶（FLASK）：有限次数 / 回血 / 不浪费 / 补满 ----------
        Player pf = new Player();
        pf.hp = 10;
        boolean use1 = pf.useFlask(wp) && pf.hp == 30 && pf.flask == Player.FLASK_MAX - 1;
        pf.hp = pf.maxHp;
        boolean noWaste = !pf.useFlask(wp) && pf.flask == Player.FLASK_MAX - 1;
        pf.hp = 5;
        pf.useFlask(wp); pf.useFlask(wp);
        boolean empty = pf.flask == 0 && !pf.useFlask(wp);
        pf.refillFlask();
        boolean refill = pf.flask == Player.FLASK_MAX;
        boolean flaskOk = use1 && noWaste && empty && refill;
        System.out.println("FLASK " + (flaskOk ? "PASS" : "FAIL")
                + "  hp=" + pf.hp + " charges=" + pf.flask + " noWaste=" + noWaste);
        if (!flaskOk) fails++;

        // ---------- 8) 血渍回收 + 重生复位（旧实现漏了 alive=true → 死后被永久钉在出生点） ----------
        Player pb = new Player();
        pb.souls = 40; pb.hp = 5;
        pb.hitByBeast(999, wp);
        boolean dropped = !pb.alive && pb.souls == 0 && pb.lostSouls == 40 && pb.hasLost;
        pb.x = pb.lostX; pb.y = pb.lostY; pb.z = pb.lostZ;      // 走回血渍
        pb.alive = true; pb.hp = pb.maxHp;
        pb.tick(wp);
        boolean recovered = pb.souls == 40 && !pb.hasLost;
        Player pv = new Player();
        pv.hp = 1; pv.hitByBeast(5, wp);
        boolean died = !pv.alive;
        pv.tick(wp);
        boolean revived = pv.alive && pv.hp == pv.maxHp && pv.flask == Player.FLASK_MAX;
        boolean bloodOk = dropped && recovered && died && revived;
        System.out.println("BLOODSTAIN " + (bloodOk ? "PASS" : "FAIL")
                + "  dropped=" + dropped + " recovered=" + recovered + " revive=" + revived);
        if (!bloodOk) fails++;

        // ---------- 9) 守卫 Boss（P0-4）：尺寸表自洽 / 二阶段可达 / 破势僵直减半 ----------
        // 尺寸表唯一来源：Beast.HW[0]/HH[0] 必须等于 BeastSystem 的别名常量（防"两处各写一份 0.35"）。
        boolean boxConsistent = Beast.hw(0) == core.systems.BeastSystem.BODY_HW
                && Beast.h(0) == core.systems.BeastSystem.BODY_H
                && Beast.hw(Beast.TYPE_BOSS) >= 0.96f && Beast.h(Beast.TYPE_BOSS) >= 2.85f;
        Beast wd = Beast.make(Beast.TYPE_BOSS, 0f, 0f, 0f);
        boolean bossBase = wd.hp == 420 && wd.dmg == 20 && wd.windupT == 30
                && !Beast.isRanged(Beast.TYPE_BOSS) && Beast.isBoss(Beast.TYPE_BOSS);
        boolean enrageOff = !wd.enraged() && wd.effectiveWindup() == wd.windupT
                && wd.effectiveDmg() == wd.dmg;
        wd.hp = (int) (wd.maxHp * Beast.BOSS_ENRAGE_PCT);        // 恰在阈值上 → 应已激怒
        boolean enrageOn = wd.enraged() && wd.effectiveWindup() < wd.windupT
                && wd.effectiveDmg() > wd.dmg;
        wd.hp = wd.maxHp;                                        // 回满 → 必须退回一阶段
        boolean enrageBack = !wd.enraged() && wd.effectiveWindup() == wd.windupT;
        // 破势僵直减半（守卫不该被"每击都打前摇"永久锁住）
        Simulation sbz = new Simulation(5150L, SX, SY, SZ);
        World wbz = sbz.world;
        Player pbz = wbz.player;
        Beast bossB = Beast.make(Beast.TYPE_BOSS, pbz.x + 2f, pbz.y, pbz.z);
        wbz.beasts.add(bossB);
        bossB.atkPhase = Beast.PHASE_WINDUP; bossB.atkTimer = 9;
        int hb0 = bossB.hp;
        pbz.hitBeast(wbz, bossB, 10, "t");
        int bossBroke = hb0 - bossB.hp;
        boolean bossPunish = bossBroke == 15 && bossB.stagger == Player.STANCE_BREAK_STAGGER / 2;
        boolean wardenOk = boxConsistent && bossBase && enrageOff && enrageOn && enrageBack && bossPunish;
        System.out.println("WARDEN " + (wardenOk ? "PASS" : "FAIL")
                + "  box=" + boxConsistent + " base=" + bossBase + " enrage=" + enrageOn
                + " dmg=" + bossBroke + " stagger=" + bossB.stagger);
        if (!wardenOk) fails++;

        Beast phases = Beast.make(Beast.TYPE_BOSS, 0f, 0f, 0f);
        boolean phase1 = phases.phase() == 1 && phases.effectiveDmg() == 20;
        phases.hp = (int)(phases.maxHp * 0.30f);
        boolean phase2 = phases.phase() == 2 && phases.effectiveWindup() < phases.windupT && phases.effectiveDmg() == 30;
        phases.hp = (int)(phases.maxHp * 0.10f);
        boolean phase3 = phases.phase() == 3 && phases.effectiveDmg() == 40 && phases.effectiveSpeed() > phases.speed;
        boolean phaseOk = phase1 && phase2 && phase3;
        System.out.println("BOSS_PHASES " + (phaseOk ? "PASS" : "FAIL") + " p1="+phase1+" p2="+phase2+" p3="+phase3);
        if (!phaseOk) fails++;
        Simulation paSim = new Simulation(9090L, SX, SY, SZ); World paw=paSim.world; Player pap=paw.player;
        Beast par=Beast.make(0,pap.x+1f,pap.y,pap.z); paw.beasts.add(par); par.atkPhase=Beast.PHASE_STRIKE; par.atkTimer=1;
        boolean ps=pap.tryParry(paw); int php=pap.hp; pap.hitByBeast(99,paw);
        boolean po=ps && pap.hp==php && par.atkPhase==Beast.PHASE_IDLE && pap.riposteTimer>0f;
        System.out.println("PARRY " + (po ? "PASS" : "FAIL") + " start="+ps+" riposte="+pap.riposteTimer); if(!po)fails++;
        paw.beasts.remove(par);                                      // 隔离招架样本，处决只测目标选择/阈值
        Beast ex=Beast.make(0,pap.x+1f,pap.y,pap.z); ex.hp=5; ex.stagger=8; paw.beasts.add(ex);
        boolean eo=pap.executeNearest(paw) && !paw.beasts.contains(ex);
        System.out.println("EXECUTE " + (eo ? "PASS" : "FAIL") + " removed="+(!paw.beasts.contains(ex))); if(!eo)fails++;
        System.out.println(fails == 0 ? "COMBAT PASS" : ("COMBAT FAIL (" + fails + ")"));
        if (fails > 0) System.exit(1);
    }
}
