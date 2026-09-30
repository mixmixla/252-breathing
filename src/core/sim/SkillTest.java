package core.sim;

import core.content.ContentRegistry;
import core.content.GraphCheck;
import core.content.SkillTree;
import core.world.Beast;
import core.world.Player;
import core.world.Weapons;
import core.world.World;
import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 技能树 + 完美闪避门禁（第 32 个出口）—— B 批与 D 批十条性质。
 *
 * <p>B（技能树）要守住的是：<b>前置与点数真的构成门槛</b>（不是装饰）、
 * 技能图<b>不能成环</b>（成环 = 谁都学不了）、学习是<b>幂等</b>的（重复点不扣两次魂）。
 *
 * <p>D（完美闪避）要守住的是塞尔达那条手感闭环：<b>无敌帧内被攻击 → 记成功 + 开反击窗口
 * → 反击伤害翻倍</b>。这三步任何一环断了，"精准闪避"就退化成"随便翻滚"。
 *
 * <p><b>零漂移</b>：技能与闪避都只写 {@code Player} 实体状态（abilities / arts / souls /
 * perfectDodges / riposteTimer），{@code hashState()} 不覆盖它们。
 */
public final class SkillTest {

    private static int fails = 0;

    private static void ck(String tag, boolean cond, String detail) {
        System.out.println(tag + " " + (cond ? "PASS" : "FAIL") + "  " + detail);
        if (!cond) fails++;
    }

    private static World newWorld() { return new World(20260913L, 64, 48, 64); }

    private static Map<String, String> files(String... kv) {
        Map<String, String> m = new LinkedHashMap<String, String>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
        return m;
    }

    public static void main(String[] args) {

        // ---------------- LOAD：磁盘技能树 ----------------
        ContentRegistry reg = ContentRegistry.load(new File("assets/content"));
        SkillTree st = new SkillTree(reg);
        ck("LOAD", st.size() >= 3 && reg.ok(),
                "nodes=" + st.size() + " ok=" + reg.ok()
                        + (reg.errors().isEmpty() ? "" : " first=\"" + reg.errors().get(0) + "\""));

        // ---------------- GRAPH：磁盘技能图无环 ----------------
        List<String> cyc = new ArrayList<String>();
        boolean acyclic = !GraphCheck.hasCycle(st.ids(), st.edges(), cyc);
        ck("GRAPH", acyclic, "acyclic=" + acyclic + " nodes=" + st.size());

        // ---------------- CYCLE：构造环必须被抓 ----------------
        ContentRegistry rc = ContentRegistry.of(files(
                "skills/a.json", "{\"effects\":[{\"type\":\"DIALOGUE\",\"id\":\"a\"}],\"requires\":[\"b\"]}",
                "skills/b.json", "{\"effects\":[{\"type\":\"DIALOGUE\",\"id\":\"b\"}],\"requires\":[\"a\"]}"));
        boolean cycleCaught = !rc.ok();
        String cycleMsg = rc.errors().isEmpty() ? "(none)" : rc.errors().get(0);
        ck("CYCLE", cycleCaught && cycleMsg.contains("cycle"),
                "errors=" + rc.errors().size() + " msg=\"" + cycleMsg + "\"");

        // ---------------- GATE_SOULS：魂不够学不了 ----------------
        ContentRegistry rs = ContentRegistry.of(files(
                "particles/spark.json", "{\"count\":1}",
                "skills/t1.json", "{\"name\":\"T1\",\"tier\":1,\"costSouls\":0,"
                        + "\"effects\":[{\"type\":\"SPAWN_PARTICLE\",\"id\":\"spark\"}]}",
                "skills/t2.json", "{\"name\":\"T2\",\"tier\":2,\"costSouls\":30,\"requires\":[\"t1\"],"
                        + "\"effects\":[{\"type\":\"SPAWN_PARTICLE\",\"id\":\"spark\"}],\"art\":\"CLEAVE\"}"));
        SkillTree ts = new SkillTree(rs);
        World w = newWorld();
        Player p = new Player();

        SkillTree.Node n2 = ts.node("t2");
        p.souls = 0;
        boolean gateSouls = !ts.canLearn(n2, p);                 // 前置也没满足
        p.souls = 100;
        boolean laterOk = ts.canLearn(n2, p);                    // 魂够但前置没学 → 仍不能
        ck("GATE_SOULS", gateSouls && !laterOk,
                "souls0=" + gateSouls + " souls100_butNoPrereq=" + !laterOk + " souls=" + p.souls);

        // ---------------- GATE_REQUIRES：前置未学学不了 ----------------
        SkillTree.Node n1 = ts.node("t1");
        p.souls = 0;
        boolean freeOk = ts.canLearn(n1, p);                     // t1 无前置、0 魂 → 可学
        boolean blocked = !ts.canLearn(n2, p);
        ck("GATE_REQUIRES", freeOk && blocked,
                "t1Learnable=" + freeOk + " t2BlockedByPrereq=" + blocked);

        // ---------------- LEARN：扣魂 + 写标记 + 幂等 ----------------
        boolean learned = ts.learn(n1, p, w);
        boolean idempotent = !ts.learn(n1, p, w);                // 再学一次必须失败
        boolean marked = p.abilities.contains("t1");
        ck("LEARN", learned && idempotent && marked,
                "learned=" + learned + " reLearnRejected=" + idempotent + " marked=" + marked);

        // ---------------- ART：学技能解锁战技原型 ----------------
        p.souls = 100;
        boolean beforeArt = p.arts.contains(Weapons.Art.CLEAVE);
        boolean learned2 = ts.learn(n2, p, w);
        boolean afterArt = p.arts.contains(Weapons.Art.CLEAVE);
        ck("ART", learned2 && !beforeArt && afterArt && p.souls == 70,
                "learned=" + learned2 + " artBefore=" + beforeArt + " artAfter=" + afterArt
                        + " souls=" + p.souls + " (30 spent)");

        // ---------------- AVAILABLE：可学清单 ----------------
        Player p2 = new Player();
        List<String> avail0 = ts.availableIds(p2);
        ck("AVAILABLE", avail0.contains("t1") && !avail0.contains("t2")
                        && ts.learnedCount(p) == 2,
                "fresh=" + avail0 + " learned=" + ts.learnedCount(p));

        // ---------------- DODGE：无敌帧内受击 → 记成功 + 开反击窗 ----------------
        Player pd = new Player();
        World wd = newWorld();
        int hp0 = pd.hp;
        pd.invuln = 0.5f;
        pd.hitByBeast(5, wd);
        boolean dodgeOk = pd.perfectDodges == 1 && pd.riposteTimer > 0f && pd.hp == hp0;
        pd.invuln = 0f;
        pd.hitByBeast(5, wd);
        boolean hurtOk = pd.hp == hp0 - 5;                       // 没无敌帧就该挨打
        ck("DODGE", dodgeOk && hurtOk,
                "perfect=" + pd.perfectDodges + " riposte>0=" + (pd.riposteTimer > 0f)
                        + " hpAfterDodge=" + hp0 + " hpAfterHit=" + pd.hp);

        // ---------------- RIPOSTE：反击伤害翻倍且窗口被消耗 ----------------
        Player pr = new Player();
        World wr = newWorld();
        Beast b = Beast.make(0, pr.x + 1f, pr.y, pr.z);
        wr.beasts.add(b);
        int bhp0 = b.hp;
        pr.riposteTimer = Player.RIPOSTE_WINDOW;
        pr.hitBeast(wr, b, 10, "test");
        int dealt = bhp0 - b.hp;
        boolean riposteOk = dealt == 10 * Player.RIPOSTE_MULT && pr.riposteTimer == 0f;
        ck("RIPOSTE", riposteOk, "dealt=" + dealt + " (expect " + (10 * Player.RIPOSTE_MULT)
                + ") windowConsumed=" + (pr.riposteTimer == 0f));

        System.out.println("SKILL " + (fails == 0 ? "PASS" : "FAIL " + fails) + "  (10 properties)");
        if (fails > 0) System.exit(1);
    }
}
