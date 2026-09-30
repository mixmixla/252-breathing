package core.sim;

import core.world.Chronicle;
import core.world.Player;
import core.world.World;

import java.util.HashSet;
import java.util.Set;

/**
 * 说书人 / 村志门禁（批次 1 余项 · storyteller；D2 加固）。
 *
 * 断言三类性质（这是"只断言指纹相等"抓不到的东西）：
 *  ① DETERMINISM 同种子两遍 → 档案序列 + 村志全文逐字节一致，且 hashState 逐字节一致（只读不写、零漂移）；
 *  ② DEDUP       同义（去「第N时辰：」前缀后相同）的<b>长程重复</b>只入库一条，
 *                且<b>绝不误并</b>参数不同的事件（不同夫妻 / 不同新生儿）；
 *  ③ DECOUPLE    "计数"（每一次发生）与"归档"（去重后条数）解耦 —— HUD「REPEL n」仍随每次击退增长，
 *                否则去重会顺手把「世界因你而变」的可见证据也一起削掉。
 *
 * 运行：java -cp out core.sim.StorytellerDeterminismTest
 */
public final class StorytellerDeterminismTest {
    public static void main(String[] args) {
        final int SX = 64, SY = 40, SZ = 64, T = 240;
        final long SEED = 123456789L;

        // ---------- ① DETERMINISM：同种子两遍 ----------
        Simulation a = new Simulation(SEED, SX, SY, SZ);
        Simulation b = new Simulation(SEED, SX, SY, SZ);
        for (int t = 0; t < T; t++) {
            // 脚本化“守护”意图 → 制造 combat/repel 转折事件，使村志确有内容
            if (t % 7 == 0) { a.world.player.setIntent(Player.Intent.repel());
                              b.world.player.setIntent(Player.Intent.repel()); }
            a.world.tick();
            b.world.tick();
        }
        long ha = a.world.hashState();
        long hb = b.world.hashState();
        boolean hashOk = ha == hb;
        boolean chrOk = sig(a.world).equals(sig(b.world));
        boolean nonEmpty = !a.world.chronicle.episodes.isEmpty()
                && a.world.chronicle.story != null && !a.world.chronicle.story.isEmpty();

        // ---------- ② DEDUP（真实跑）：REPEL 发生多次但只留 1 条档案；且档案内无重复骨架 ----------
        Chronicle ca = a.world.chronicle;
        int repelOccur = ca.count("REPEL");
        int repelEps = ca.distinctOf("repel");
        boolean dedupLive = repelOccur >= 10 && repelEps == 1;   // 真实跑至少出了 10 次击退（否则这条断言无意义）
        boolean noDup = true;
        Set<String> sigs = new HashSet<String>();
        for (Chronicle.Episode e : ca.episodes) {
            if (!sigs.add(e.action + "|" + Chronicle.skeleton(e.text))) noDup = false;
        }
        // lastOk（D2 收敛）：计数与档案解耦 + 档案非空。原断言 "lastAscii==REPEL" 把「末 tick 末事件
        // 恰为击退」这一地形耦合的事件时序写死（2026-09-11 地形振幅调整后，末事件不再是击退）→
        // 收敛为解耦+非空；REPEL 骨架唯一性已由 dedupLive(repelEps==1) 覆盖，无重复骨架由 noDup 覆盖。
        boolean lastOk = ca.countsTotal() > ca.episodes.size() && !ca.episodes.isEmpty();

        // ---------- ③ DECOUPLE + 不误并：合成事件精确验证 ----------
        Simulation c = new Simulation(SEED, SX, SY, SZ);
        World w = c.world;
        Chronicle ch = w.chronicle;
        int base = w.events.size();                 // 构造期既有事件（若有）不参与本次断言
        for (int i = 0; i < 40; i++) w.log("combat", "repel", "attacker=player,victim=beast", "ok");
        w.log("npc", "marry", "actor=甲,spouse=乙", "ok");
        w.log("npc", "marry", "actor=丙,spouse=丁", "ok");   // 另一对夫妻：骨架不同，不得被并
        w.log("npc", "birth", "name=戊,parent=甲", "ok");
        w.log("npc", "birth", "name=己,parent=丙", "ok");    // 另一个新生儿：不得被并
        ch.absorbed = base;
        int added = ch.absorb(w.events, 1000);
        int n1 = ch.episodes.size();
        ch.absorb(w.events, 1000);                            // 幂等：水印保证不重复扫描
        int n2 = ch.episodes.size();

        boolean countOk  = ch.count("REPEL") == 40;           // 40 次发生全部计数
        boolean repelOne = ch.distinctOf("repel") == 1;       // 40 次同义 → 只留 1 条
        boolean marryTwo = ch.distinctOf("marry") == 2;       // 不同夫妻 → 各自保留
        boolean birthTwo = ch.distinctOf("birth") == 2;       // 不同新生儿 → 各自保留
        boolean totalOk  = n1 == 5 && added == 5;             // 1 repel + 2 marry + 2 birth
        boolean idemOk   = n1 == n2;

        // 叙事层：计数用真实发生数（40）；“近日事迹”不再出现雷同句
        String story = ch.generateStory(w, "测试");
        boolean storyCount = story.contains("40 次击退");
        int repelLines = 0;
        for (String ln : story.split("\n")) if (ln.contains("击退了来犯之敌")) repelLines++;
        boolean storyNoRepeat = repelLines == 1;              // 恰好一条，不再复读

        boolean dedupOk = dedupLive && noDup && lastOk && countOk && repelOne && marryTwo
                && birthTwo && totalOk && idemOk && storyCount && storyNoRepeat;

        boolean pass = hashOk && chrOk && nonEmpty && dedupOk;
        System.out.printf("STORYTELLER-DETERMINISM  hashA=%016x hashB=%016x%n", ha, hb);
        System.out.println("CHRONICLE A = " + ca.asciiHeader());
        System.out.println("episodes=" + ca.episodes.size()
                + "  days=" + ca.days + "  occur=" + ca.countsTotal()
                + "  last=" + ca.asciiLast());
        System.out.println("CHRONICLE-DEDUP  live(occur=" + repelOccur + " eps=" + repelEps + ")"
                + " noDup=" + noDup + " lastOk=" + lastOk
                + " | synth(count=" + ch.count("REPEL") + " repel=" + ch.distinctOf("repel")
                + " marry=" + ch.distinctOf("marry") + " birth=" + ch.distinctOf("birth")
                + " total=" + n1 + ")"
                + " storyCount=" + storyCount + " storyRepeat=" + repelLines
                + " idem=" + idemOk);
        System.out.println(pass ? "STORYTELLER-DETERMINISM PASS" : "STORYTELLER-DETERMINISM FAIL");
        if (!pass) System.exit(1);
    }

    /** 村志签名：档案序列 + 全文（逐字节可比的确定性代表）。 */
    private static String sig(World w) {
        StringBuilder sb = new StringBuilder();
        sb.append(w.chronicle.asciiHeader()).append(";eps=").append(w.chronicle.episodes.size()).append(';');
        for (int i = 0; i < w.chronicle.episodes.size(); i++) {
            sb.append(i).append(':').append(w.chronicle.episodes.get(i).text).append('|');
        }
        sb.append("story{").append(w.chronicle.story).append('}');
        return sb.toString();
    }
}
