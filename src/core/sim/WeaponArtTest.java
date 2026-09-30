package core.sim;

import core.world.Beast;
import core.world.Player;
import core.world.Weapons;
import core.world.World;

/**
 * 多武器 · 多战技 门禁（C3，第 12 道）。
 *
 * <p>此前的「单战技」版本无法被任何门禁发现退化：战技即使永远打空、范围写错、或者顺手改了网格，
 * 四道零漂移门禁也照绿（因为门禁只跑 World.tick，玩家输入不在其中）。所以本门禁不查指纹，
 * 而是<b>直接驱动玩家战技</b>并断言行为差异 —— 补上「玩家动作层」的覆盖盲区。
 *
 * <p>断言三类：
 * <ol>
 *   <li><b>原型差异（真的不一样）</b>：LUNGE 单体重击近身；CLEAVE 一次命中半径内<b>全体</b>
 *       （第 4 只在范围外不受影响）；SHOT 命中 12 格外目标（远超近战），18 格外不受影响。</li>
 *   <li><b>门控与边界</b>：SHOT 无目标时<b>不施放</b>（返回 false、不耗体力、不进 CD），
 *       与「空放也扣体力」的错误实现区分开；Lv&lt;2 时战技锁定。</li>
 *   <li><b>零漂移不变量</b>：整套战技流程（位移/命中/记 combat.repel/击杀结算）前后
 *       {@link World#hashState()} <b>逐字节不变</b> —— 证明战技层只读实体、只追加 events，
 *       不写 mat/mass/prosperity/skills/villageMemory。</li>
 * </ol>
 *
 * 运行：java -cp out core.sim.WeaponArtTest
 */
public final class WeaponArtTest {

    /** 用高血量假兽（hp 巨额）承接伤害：既能读伤害，又不会触发击杀掉落污染后续用例。 */
    private static Beast dummy(int type, float x, float y, float z) {
        Beast b = Beast.make(type, x, y, z);
        b.maxHp = 9999; b.hp = 9999;
        return b;
    }

    public static void main(String[] args) {
        final int SX = 64, SY = 40, SZ = 64;
        final long SEED = 20260910L;

        Simulation sim = new Simulation(SEED, SX, SY, SZ);
        World w = sim.world;
        Player p = w.player;
        w.beasts.clear();

        float px = w.SX / 2f, pz = w.SZ / 2f;
        float py = Player.spawnY(w);
        p.level = 2; p.recompute();          // Lv2 = 战技解锁线；本关不追求升级（避免掉落改写 currentArt）
        p.killCount = 1;                     // 让 wT 恒 ≤ 2，杜绝测试中途换武器
        long hash0 = w.hashState();

        StringBuilder ev = new StringBuilder();

        // ---------- 1) LUNGE（铁剑）：单体、近身 ----------
        p.weapon = 1; p.currentArt = Weapons.Art.LUNGE; p.recompute();
        p.x = px; p.z = pz; p.y = py; p.stamina = 500; p.artCd = 0f;
        Beast near1 = dummy(2, px, py, pz + 2.0f);   // 近（BRUTE，厚血）
        Beast far1  = dummy(2, px, py, pz + 8.0f);   // 远（超出 3.6 射程）
        w.beasts.clear(); w.beasts.add(near1); w.beasts.add(far1);
        boolean lungeCast = p.weaponArt(w, 0f, 1f);
        int lungeDmg = 9999 - near1.hp;
        boolean lungeOk = lungeCast && lungeDmg > 0 && far1.hp == 9999;
        ev.append("LUNGE cast=").append(lungeCast).append(" dmg=").append(lungeDmg)
          .append(" farUntouched=").append(far1.hp == 9999);

        // ---------- 2) CLEAVE（战斧）：AoE 横扫 + 击退 ----------
        p.weapon = 2; p.currentArt = Weapons.Art.CLEAVE; p.recompute();
        p.x = px; p.z = pz; p.y = py; p.stamina = 500; p.artCd = 0f;
        Beast c1 = dummy(0, px + 1.4f, py, pz);
        Beast c2 = dummy(0, px - 1.8f, py, pz + 0.6f);
        Beast c3 = dummy(1, px, py, pz - 2.6f);
        Beast outside = dummy(0, px + 4.6f, py, pz);      // 超出 3.2 → 不受影响
        float c2x0 = c2.x;
        w.beasts.clear(); w.beasts.add(c1); w.beasts.add(c2); w.beasts.add(c3); w.beasts.add(outside);
        boolean cleaveCast = p.weaponArt(w, 0f, 1f);
        int hitCount = (9999 - c1.hp > 0 ? 1 : 0) + (9999 - c2.hp > 0 ? 1 : 0) + (9999 - c3.hp > 0 ? 1 : 0);
        boolean knockback = Math.abs(c2.x - c2x0) > 0.5f;
        boolean cleaveOk = cleaveCast && hitCount == 3 && outside.hp == 9999 && knockback;
        ev.append(" | CLEAVE cast=").append(cleaveCast).append(" hit=").append(hitCount)
          .append("/3 outUntouched=").append(outside.hp == 9999).append(" knock=").append(knockback);

        // ---------- 3) SHOT（猎弓）：超远射程单体重击 ----------
        p.weapon = 3; p.currentArt = Weapons.Art.SHOT; p.recompute();
        p.x = px; p.z = pz; p.y = py; p.stamina = 500; p.artCd = 0f;
        Beast s12 = dummy(3, px, py, pz + 12f);   // 12 格（近战绝对够不到）
        Beast s18 = dummy(3, px, py, pz + 18f);   // 18 格（超出 16 射程）
        w.beasts.clear(); w.beasts.add(s12); w.beasts.add(s18);
        boolean shotCast = p.weaponArt(w, 0f, 1f);
        int shotDmg = 9999 - s12.hp;
        boolean shotOk = shotCast && shotDmg > 0 && s18.hp == 9999;
        ev.append(" | SHOT cast=").append(shotCast).append(" dmg@12=").append(shotDmg)
          .append(" outRankUntouched@18=").append(s18.hp == 9999);

        // ---------- 4) 边界：SHOT 无目标不空放（不耗体力/不进 CD） ----------
        w.beasts.clear(); p.stamina = 500; p.artCd = 0f;
        boolean noTargetCast = p.weaponArt(w, 0f, 1f);
        boolean noTargetNoCost = (p.stamina == 500) && (p.artCd == 0f);
        boolean noTargetOk = !noTargetCast && noTargetNoCost;
        // 边界：Lv<2 战技锁定
        p.level = 1; p.recompute();
        Beast lv1t = dummy(0, px, py, pz + 1.5f); w.beasts.add(lv1t);
        boolean lockedCast = p.weaponArt(w, 0f, 1f);
        boolean lockedOk = !lockedCast && lv1t.hp == 9999;
        p.level = 2; p.recompute();
        ev.append(" | NO_TARGET noCast=").append(!noTargetCast)
          .append(" noCost=").append(noTargetNoCost).append(" lockedLv1=").append(lockedOk);

        // ---------- 5) 解锁 / 轮换语义 ----------
        Player q = new Player();
        boolean onlyLunge = q.arts.size() == 1 && q.currentArt == Weapons.Art.LUNGE;
        boolean firstUnlock = q.unlockArt(Weapons.Art.CLEAVE) && q.currentArt == Weapons.Art.CLEAVE;
        boolean dupUnlock = !q.unlockArt(Weapons.Art.CLEAVE);     // 幂等：不重复解锁
        boolean cycled = q.cycleArt() == Weapons.Art.LUNGE;       // 轮换回突刺
        boolean fullChain = Weapons.COUNT == 6
                && Weapons.def(0).art == Weapons.Art.LUNGE
                && Weapons.def(2).art == Weapons.Art.CLEAVE
                && Weapons.def(3).art == Weapons.Art.SHOT
                && Weapons.def(5).art == Weapons.Art.CHARGE
                && Weapons.def(3).range > Weapons.def(1).range * 3f;   // 远射射程远大于近战
        boolean unlockOk = onlyLunge && firstUnlock && dupUnlock && cycled && fullChain;

        // ---------- 5b) NO_DOMINANCE：没有任何武器"全面强于"另一把（P0-3 的核心性质） ----------
        //   EVAL-3 实测旧表是纯线性上位替代（符文刃在 atk/倍率/CD/射程上全面碾压战斧）
        //   → "要不要换武器"不是问题。维度：atk↑ 倍率↑ 射程↑ 耗体↓ 冷却↓。
        boolean noDominance = true;
        StringBuilder dom = new StringBuilder();
        for (int i = 0; i < Weapons.COUNT; i++) {
            for (int k = 0; k < Weapons.COUNT; k++) {
                if (i == k) continue;
                Weapons.Def x = Weapons.def(i), y = Weapons.def(k);
                boolean weak = x.atk <= y.atk && x.mult <= y.mult && x.range <= y.range
                        && x.cost >= y.cost && x.cd >= y.cd;                       // 各维不劣（含相等）
                boolean strict = x.atk < y.atk || x.mult < y.mult || x.range < y.range
                        || x.cost > y.cost || x.cd > y.cd;                         // 至少一维更差
                if (weak && strict) { noDominance = false; dom.append(" ").append(y.name).append(">").append(x.name); }
            }
        }
        ev.append(" | NO_DOMINANCE ok=").append(noDominance).append(dom);

        // ---------- 5c) PICKUP_NO_AUTO：掉落只进背包，不自动换装 ----------
        Simulation sim2 = new Simulation(SEED, SX, SY, SZ);
        Player r = sim2.world.player;
        r.weaponOwned = 1; r.weapon = 0; r.level = 6; r.killCount = 0;
        int masked0 = r.weaponOwned;
        for (int i = 0; i < 40; i++) r.onKill(sim2.world);           // 打满掉落链
        boolean pickupNoAuto = r.weapon == 0 && r.weaponOwned != masked0 && r.ownsWeapon(3) && r.ownsWeapon(4);
        // 轮换只在已获得集合内，且能遍历全部已获得（并回到起点）
        int visited = 0, guard = 0;
        boolean onlyOwned = true;
        int start = r.weapon;
        do {
            if (!r.ownsWeapon(r.weapon)) onlyOwned = false;
            visited++;
            r.cycleWeapon();
        } while (r.weapon != start && ++guard < 16);
        int ownedN = Integer.bitCount(r.weaponOwned);
        boolean cycleOk = onlyOwned && visited == ownedN && ownedN >= 3;
        ev.append(" | PICKUP_NO_AUTO keepFist=").append(r.weapon == start && r.weapon == 0)
          .append(" owned=").append(Integer.toBinaryString(r.weaponOwned))
          .append(" cycleVisit=").append(visited).append("/").append(ownedN)
          .append(" onlyOwned=").append(onlyOwned);
        ev.append(" | UNLOCK baseOnly=").append(onlyLunge).append(" first=").append(firstUnlock)
          .append(" idempotent=").append(dupUnlock).append(" cycle=").append(cycled)
          .append(" chain5=").append(fullChain);

        // ---------- 5d) CHARGE（巨槌）：同范围骨架，但击退更远 ----------
        p.weapon = 5; p.currentArt = Weapons.Art.CHARGE; p.recompute();
        p.x = px; p.z = pz; p.y = py; p.stamina = 500; p.artCd = 0f;
        Beast g1 = dummy(0, px + 1.6f, py, pz);
        Beast g2 = dummy(0, px, py, pz - 2.2f);
        Beast gOut = dummy(0, px + 6.0f, py, pz);                 // 超出 3.2 → 不受影响
        float g1x0 = g1.x;
        w.beasts.clear(); w.beasts.add(g1); w.beasts.add(g2); w.beasts.add(gOut);
        boolean chargeCast = p.weaponArt(w, 0f, 1f);
        int chargeHits = (9999 - g1.hp > 0 ? 1 : 0) + (9999 - g2.hp > 0 ? 1 : 0);
        float gKnock = Math.abs(g1.x - g1x0);
        boolean chargeOk = chargeCast && chargeHits == 2 && gOut.hp == 9999 && gKnock > 1.5f;
        ev.append(" | CHARGE cast=").append(chargeCast).append(" hit=").append(chargeHits)
          .append("/2 outUntouched=").append(gOut.hp == 9999).append(" knock=").append(gKnock);

        // ---------- 6) 零漂移不变量：战技全程不改指纹 ----------
        long hash1 = w.hashState();
        boolean zeroDrift = hash0 == hash1;
        ev.append(" | ZERO-DRIFT ").append(Long.toHexString(hash0)).append("==").append(Long.toHexString(hash1));

        boolean pass = lungeOk && cleaveOk && shotOk && noTargetOk && lockedOk && unlockOk && zeroDrift
                && noDominance && pickupNoAuto && cycleOk && chargeOk;

        System.out.println("WEAPON-ART  " + ev);
        System.out.println("ARTS  " + Weapons.def(1).name + "=" + Weapons.artName(Weapons.def(1).art)
                + " / " + Weapons.def(2).name + "=" + Weapons.artName(Weapons.def(2).art)
                + " / " + Weapons.def(3).name + "=" + Weapons.artName(Weapons.def(3).art)
                + " / " + Weapons.def(5).name + "=" + Weapons.artName(Weapons.def(5).art));
        System.out.println(pass ? "WEAPON-ART PASS" : "WEAPON-ART FAIL");
        if (!pass) System.exit(1);
    }
}
