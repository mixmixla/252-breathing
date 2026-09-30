package core.sim;

import core.content.ContentRegistry;
import core.content.EffectQueue;
import core.content.TechTree;
import core.world.World;
import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 科技链门禁（第 31 个出口）—— 断言「配方图」的十条性质。
 *
 * <p>科技链是<b>图</b>，图会出两类致命问题：<b>成环</b>（谁也解锁不了谁都等对方 → 死锁）
 * 与<b>门槛失效</b>（开局全解锁，科技就没有意义）。这两条在这里被钉死。
 *
 * <p>另外断言：解锁是<b>幂等</b>的（重复 tick 不重复解锁）、效果真的派发、
 * 节流真的生效（非检查 tick 不动作）、链式前置真的按序。
 *
 * <p><b>零漂移</b>：{@code World.skills} 在指纹之内，所以解锁会改变指纹 —— 那是
 * 「解锁真的改变了世界」的正确表现。门禁世界不启用 TechTree，故既有基线不动。
 */
public final class TechTest {

    private static int fails = 0;

    private static void ck(String tag, boolean cond, String detail) {
        System.out.println(tag + " " + (cond ? "PASS" : "FAIL") + "  " + detail);
        if (!cond) fails++;
    }

    private static Map<String, String> files(String... kv) {
        Map<String, String> m = new LinkedHashMap<String, String>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
        return m;
    }

    private static final String T1 = "{\"name\":\"T1\",\"requires\":[],\"minProsperity\":10,"
            + "\"unlocks\":[\"m1\"],\"effects\":[{\"type\":\"SPAWN_PARTICLE\",\"id\":\"spark\"}]}";
    private static final String T2 = "{\"name\":\"T2\",\"requires\":[\"t1\"],\"minProsperity\":0,"
            + "\"unlocks\":[\"m2\"]}";

    public static void main(String[] args) {

        // ---------------- LOAD：磁盘科技链 ----------------
        ContentRegistry reg = ContentRegistry.load(new File("assets/content"));
        ck("LOAD", reg.techs().size() >= 4 && reg.ok(),
                "techs=" + reg.techs().size() + " ok=" + reg.ok()
                        + (reg.errors().isEmpty() ? "" : " first=\"" + reg.errors().get(0) + "\""));

        // ---------------- GRAPH：磁盘图无环 ----------------
        List<String> cyc = new ArrayList<String>();
        boolean acyclic = !TechTree.hasCycle(reg.techs(), cyc);
        ck("GRAPH", acyclic, "acyclic=" + acyclic + " techs=" + reg.techs().size());

        // ---------------- CYCLE：构造环路必须被抓 ----------------
        ContentRegistry rc = ContentRegistry.of(files(
                "techs/a.json", "{\"name\":\"A\",\"requires\":[\"b\"],\"unlocks\":[\"a\"]}",
                "techs/b.json", "{\"name\":\"B\",\"requires\":[\"a\"],\"unlocks\":[\"b\"]}"));
        boolean cycleCaught = !rc.ok();
        for (String e : rc.errors()) if (e.contains("cycle")) cycleCaught = cycleCaught && true;
        String cycleMsg = rc.errors().isEmpty() ? "(none)" : rc.errors().get(0);
        ck("CYCLE", cycleCaught && cycleMsg.contains("cycle"),
                "errors=" + rc.errors().size() + " msg=\"" + cycleMsg + "\"");

        // ---------------- 构造小树用于行为断言 ----------------
        ContentRegistry rt = ContentRegistry.of(files(
                "particles/spark.json", "{\"count\":1}",
                "techs/t1.json", T1,
                "techs/t2.json", T2,
                "techs/t3.json", "{\"name\":\"T3\",\"requires\":[\"never_exists\"],\"unlocks\":[\"m3\"]}"));
        TechTree tt = new TechTree(rt);
        World w = new World(20260913L, 64, 48, 64);
        EffectQueue q = new EffectQueue(w.seed);

        // ---------------- GATE_PROSPERITY：繁荣不足 → 一个都不解锁 ----------------
        w.prosperity = 0;
        int n0 = tt.tick(w, q, 20L);
        ck("GATE_PROSPERITY", n0 == 0 && !w.hasSkill("t1"),
                "prosperity=0 unlocked=" + n0 + " hasT1=" + w.hasSkill("t1"));

        // ---------------- GATE_REQUIRES：前置永不满足 → 永不解锁 ----------------
        w.prosperity = 100;
        boolean t3Locked = true;
        for (long t = 20L; t <= 200L; t += 20L) { tt.tick(w, q, t); if (w.hasSkill("t3")) t3Locked = false; }
        ck("GATE_REQUIRES", t3Locked && !w.hasSkill("m3"),
                "t3 locked forever=" + t3Locked + " skills=" + w.hasSkill("t3"));

        // ---------------- UNLOCK + CHAIN：满足前置 → 解锁；链式前置按序成立 ----------------
        boolean unlocked = w.hasSkill("t1") && w.hasSkill("m1");
        boolean chained  = w.hasSkill("t2") && w.hasSkill("m2");       // t2 依赖 t1，t1 解锁后同 tick 满足
        ck("UNLOCK", unlocked, "t1=" + w.hasSkill("t1") + " m1=" + w.hasSkill("m1"));
        ck("CHAIN", chained, "t2=" + w.hasSkill("t2") + " m2=" + w.hasSkill("m2")
                + " (requires t1 -> satisfied in same pass)");

        // ---------------- EFFECTS：解锁派发的效果真的进了队列 ----------------
        int pending = q.pendingCount();
        ck("EFFECTS", pending > 0, "pendingEffects=" + pending);

        // ---------------- IDEMPOTENT：重复 tick 不重复解锁 ----------------
        int again = 0;
        for (long t = 220L; t <= 400L; t += 20L) again += tt.tick(w, q, t);
        ck("IDEMPOTENT", again == 0, "extraUnlocks=" + again + " unlockedTotal=" + tt.unlockedTotal());

        // ---------------- PACING：非检查 tick 不动作 ----------------
        TechTree tp = new TechTree(rt);
        World wp = new World(20260913L, 64, 48, 64);
        wp.prosperity = 100;
        int offBeat = tp.tick(wp, null, 21L);           // 21 不是 20 的倍数
        int onBeat  = tp.tick(wp, null, 40L);           // 40 是
        ck("PACING", offBeat == 0 && onBeat > 0,
                "tick21=" + offBeat + " tick40=" + onBeat + " (interval=" + TechTree.CHECK_INTERVAL + ")");

        // ---------------- AVAILABLE：UI 可读的"现在能做什么" ----------------
        TechTree ta = new TechTree(rt);
        World wa = new World(20260913L, 64, 48, 64);
        wa.prosperity = 100;
        List<String> avail = ta.availableIds(wa);
        List<String> locked = ta.lockedIds(wa);
        boolean availOk = avail.contains("t1") && locked.contains("t2")
                && ta.size() == 3 && !avail.contains("t3");
        ck("AVAILABLE", availOk, "available=" + avail + " locked=" + locked);

        System.out.println("TECH " + (fails == 0 ? "PASS" : "FAIL " + fails) + "  (10 properties)");
        if (fails > 0) System.exit(1);
    }
}
