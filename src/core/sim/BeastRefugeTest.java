package core.sim;

import core.systems.BeastSystem;
import core.systems.NpcSystem;
import core.world.Beast;
import core.world.Player;
import core.world.World;

/**
 * 敌兵生成性质测试（QA 2026-09-12 · TEST_STRATEGY P0）。
 *
 * <p>断言的是<b>性质</b>：
 * ① 出生宽限——GRACE_TICKS 内 beasts 恒空（落地先看世界，不再开局被围殴）；
 * ② 村庄庇护所——玩家待在村心，任何生成的 beast 与村心距离必 > VILLAGE_RADIUS
 *    （选址器把出生地搬进村后，兽群不再屠村；空集同样满足性质）；
 * ③ 正常生成不被避让误杀——玩家在村外时 beast 照常生成且仍在村外；
 * ④ 不叠身——持续逼近后 beast 与玩家的水平距离 ≥ contact×0.8（近战停在贴脸前一格）。
 */
public final class BeastRefugeTest {
    public static void main(String[] args) {
        int fails = 0;
        World w = new World(20260912L, 96, 48, 96);
        Player p = new Player();
        p.hp = 999999;                                   // 隔离攻击干扰（本测试不验证伤害）
        w.player = p;
        BeastSystem bs = new BeastSystem();
        float vcx = w.SX / 2f, vcz = w.SZ / 2f;
        float vR2 = NpcSystem.VILLAGE_RADIUS * NpcSystem.VILLAGE_RADIUS;

        // ---- 1. 出生宽限：GRACE 内无 beast ----
        p.x = vcx; p.z = vcz; p.y = 30f;                 // 玩家放村心
        for (w.tick = 0; w.tick < 120; w.tick++) bs.update(w, null);
        boolean graceOk = w.beasts.isEmpty();
        System.out.println("GRACE  " + (graceOk ? "PASS" : "FAIL") + "  beasts=0 within 120 ticks");
        if (!graceOk) fails++;

        // ---- 2. 村庄庇护所：村内挂机，任何 beast 都在村外 ----
        boolean refugeOk = true;
        for (w.tick = 120; w.tick <= 336; w.tick++) {
            bs.update(w, null);
            for (Beast b : w.beasts) {
                float vx = b.x - vcx, vz = b.z - vcz;
                if (vx * vx + vz * vz <= vR2) refugeOk = false;
            }
        }
        System.out.println("REFUGE " + (refugeOk ? "PASS" : "FAIL") + "  no beast inside village radius (player in village)");
        if (!refugeOk) fails++;

        // ---- 3. 正常生成：玩家在村外，beast 照常生成且也在村外 ----
        p.x = 90f; p.z = 48f; p.y = 30f;
        w.tick = 360;
        bs.update(w, null);
        boolean spawnOk = !w.beasts.isEmpty();
        for (Beast b : w.beasts) {
            float vx = b.x - vcx, vz = b.z - vcz;
            if (vx * vx + vz * vz <= vR2) spawnOk = false;
        }
        System.out.println("SPAWN  " + (spawnOk ? "PASS" : "FAIL") + "  beasts=" + w.beasts.size() + " (player outside village)");
        if (!spawnOk) fails++;

        // ---- 4. 不叠身：持续逼近后水平距离 ≥ 0.5（近战 contact×0.8 停步，实测更远）----
        w.beasts.clear();
        p.x = 90f; p.z = 48f;
        w.tick = 384;
        bs.update(w, null);                              // 生成一批
        float minD = Float.MAX_VALUE;
        for (int i = 0; i < 300; i++) {
            bs.update(w, null);                          // spawn 按 tick 节流；move 每 tick 逼近
            for (Beast b : w.beasts) {
                float dx = b.x - p.x, dz = b.z - p.z;
                float d = (float) Math.sqrt(dx * dx + dz * dz);
                if (d < minD) minD = d;
            }
        }
        boolean noOverlap = minD > 0.5f && minD < 20f;   // 既不叠身、也确实在逼近（move 没被弄死）
        System.out.println("NO-OVERLAP " + (noOverlap ? "PASS" : "FAIL") + "  minDist=" + minD);
        if (!noOverlap) fails++;

        System.out.println(fails == 0 ? "REFUGE PASS" : ("REFUGE FAIL (" + fails + ")"));
        if (fails > 0) System.exit(1);
    }
}
