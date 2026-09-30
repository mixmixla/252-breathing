package core.sim;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import core.content.ContentRegistry;
import core.content.Effect;
import core.content.EffectQueue;
import core.content.EffectSink;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 内容平台门禁（第 27 个出口）——断言「规范化配置」的九条性质。
 *
 * <p>与其它门禁同一纪律：<b>断言性质，不只断言能跑</b>。这里尤其重要的是三条：
 * <ul>
 *   <li><b>DET</b>：同一份内容、以不同顺序喂进来，注册表快照必须逐字节一致
 *       —— 这是「内容平台」能与「逐字节可复现」共存的前提；</li>
 *   <li><b>REF / CYCLE</b>：悬空引用与循环引用必须在加载期被抓出（铁律 3）；
 *       CYCLE 还必须不栈溢出 —— 坏内容不能把游戏弄崩。</li>
 *   <li><b>QUEUE / ORDER / DELAY</b>：调度层必须确定性 —— 同输入同执行序列，
 *       同 tick 按注入顺序，延迟精确对齐 tick。</li>
 * </ul>
 *
 * <p>无头运行：全部用内存内容 + {@link EffectSink#NONE}，不依赖渲染器与磁盘。
 */
public final class ContentTest {

    private static int fails = 0;
    /** 已执行的断言数（**运行时统计**，不再手写 —— 手写数字曾与真实段数漂移）。 */
    private static int props = 0;

    private static void ck(String tag, boolean cond, String detail) {
        props++;
        System.out.println(tag + " " + (cond ? "PASS" : "FAIL") + "  " + detail);
        if (!cond) fails++;
    }

    /** 记录调用的 sink —— 验证「指令 → 表现」分派是否正确。 */
    private static final class Rec implements EffectSink {
        final List<String> calls = new ArrayList<String>();
        @Override public void particle(JsonObject d, float x, float y, float z) { calls.add("particle:" + (d == null ? "?" : d.get("count"))); }
        @Override public void fx(JsonObject d, float x, float y, float z)       { calls.add("fx"); }
        @Override public void sfx(String id)                                     { calls.add("sfx:" + id); }
        @Override public void shake(float amp)                                   { calls.add("shake:" + amp); }
        @Override public void banner(String text)                                { calls.add("banner:" + text); }
        @Override public void simulate(String t, String id, float a, float x, float y, float z) { calls.add("sim:" + t + ":" + id); }
    }

    private static Map<String, String> sample() {
        Map<String, String> f = new LinkedHashMap<String, String>();
        f.put("particles/spark.json", "{\"shape\":\"point\",\"count\":18}");
        f.put("particles/smoke.json", "{\"shape\":\"sphere\",\"count\":12}");
        f.put("fx/burst.json", "{\"emitters\":[{\"particle\":\"spark\"},{\"particle\":\"smoke\"}]}");
        f.put("buffs/burning.json", "{\"name\":\"Burning\",\"duration\":4.0}");
        f.put("skills/fire.json", "{\"name\":\"Fire\",\"effects\":["
                + "{\"type\":\"PLAY_SFX\",\"id\":\"boom\"},"
                + "{\"type\":\"DAMAGE\",\"amount\":10},"
                + "{\"type\":\"SPAWN_FX\",\"id\":\"burst\",\"delay\":0.1},"
                + "{\"type\":\"APPLY_BUFF\",\"id\":\"burning\"}]}");
        return f;
    }

    private static JsonObject j(String s) { return JsonParser.parseString(s).getAsJsonObject(); }

    public static void main(String[] args) {

        // ---------------- LOAD ----------------
        ContentRegistry r = ContentRegistry.of(sample());
        ck("LOAD", r.ok() && r.total() == 5 && r.size("skills") == 1,
                "total=" + r.total() + " skills=" + r.size("skills") + " fx=" + r.size("fx")
                        + " errors=" + r.errors().size());

        // ---------------- DET：内容顺序无关 → 快照一致 ----------------
        Map<String, String> shuffled = new LinkedHashMap<String, String>();
        List<String> keys = new ArrayList<String>(sample().keySet());
        Collections.reverse(keys);
        for (String k : keys) shuffled.put(k, sample().get(k));
        String s1 = ContentRegistry.of(sample()).snapshot();
        String s2 = ContentRegistry.of(shuffled).snapshot();
        boolean detOk = s1.equals(s2) && !s1.isEmpty();
        ck("DET", detOk, "snapshot bytes=" + s1.length() + " equalUnderShuffle=" + s1.equals(s2));

        // ---------------- WHITELIST：未知指令被拒绝 ----------------
        Effect good = Effect.parse(j("{\"type\":\"DAMAGE\",\"amount\":5}"));
        Effect bad = Effect.parse(j("{\"type\":\"MELT_FACE\",\"amount\":5}"));
        ck("WHITELIST", good != null && bad == null && Effect.WHITELIST.size() == 14,
                "good=" + (good != null) + " bad=" + (bad == null) + " whitelist=" + Effect.WHITELIST.size());

        // ---------------- REF：悬空引用被抓 ----------------
        Map<String, String> dangling = new LinkedHashMap<String, String>();
        dangling.put("particles/spark.json", "{\"count\":1}");
        dangling.put("skills/bad.json", "{\"effects\":[{\"type\":\"SPAWN_FX\",\"id\":\"nope\"}]}");
        ContentRegistry rd = ContentRegistry.of(dangling);
        boolean refOk = !rd.ok();
        String refMsg = rd.errors().isEmpty() ? "(none)" : rd.errors().get(0);
        ck("REF", refOk && refMsg.contains("nope"), "errors=" + rd.errors().size() + " first=\"" + refMsg + "\"");

        // ---------------- CYCLE：循环引用被检测且不崩 ----------------
        Map<String, String> cyc = new LinkedHashMap<String, String>();
        cyc.put("fx/a.json", "{\"emitters\":[{\"fx\":\"b\"}]}");
        cyc.put("fx/b.json", "{\"emitters\":[{\"fx\":\"a\"}]}");
        ContentRegistry rc = ContentRegistry.of(cyc);
        boolean cycOk = false;
        for (String e : rc.errors()) if (e.startsWith("cycle:")) cycOk = true;
        ck("CYCLE", cycOk, "errors=" + rc.errors().size() + " msg=\"" + (rc.errors().isEmpty() ? "(none)" : rc.errors().get(0)) + "\"");

        // ---------------- ORDER：同 tick 按注入顺序执行 ----------------
        Rec sink = new Rec();
        EffectQueue q = new EffectQueue(20260913L);
        q.submit(Effect.parse(j("{\"type\":\"PLAY_SFX\",\"id\":\"a\"}")), 0, 0, 0, 7);
        q.submit(Effect.parse(j("{\"type\":\"PLAY_SFX\",\"id\":\"b\"}")), 0, 0, 0, 7);
        q.submit(Effect.parse(j("{\"type\":\"PLAY_SFX\",\"id\":\"c\"}")), 0, 0, 0, 7);
        q.tick(7, sink, r);
        List<String> calls = new ArrayList<String>(sink.calls);
        boolean orderOk = calls.size() == 3 && calls.get(0).equals("sfx:a")
                && calls.get(1).equals("sfx:b") && calls.get(2).equals("sfx:c");
        ck("ORDER", orderOk, "calls=" + calls);

        // ---------------- DELAY：延迟精确对齐 tick（20 tick/秒） ----------------
        EffectQueue qd = new EffectQueue(1L);
        qd.submit(Effect.parse(j("{\"type\":\"PLAY_SFX\",\"id\":\"late\",\"delay\":1.0}")), 0, 0, 0, 0);
        int before = qd.tick(EffectQueue.TICKS_PER_SEC - 1, EffectSink.NONE, r);   // tick 19 → 还不该执行
        int at     = qd.tick(EffectQueue.TICKS_PER_SEC, EffectSink.NONE, r);       // tick 20 → 应执行
        ck("DELAY", before == 0 && at == 1, "atTick19=" + before + " atTick20=" + at
                + " (TPS=" + EffectQueue.TICKS_PER_SEC + ")");

        // ---------------- QUEUE：同输入 → 同执行序列 ----------------
        StringBuilder t1 = runTrace();
        StringBuilder t2 = runTrace();
        ck("QUEUE", t1.toString().equals(t2.toString()) && t1.length() > 0,
                "traceBytes=" + t1.length() + " identical=" + t1.toString().equals(t2.toString()));

        // ---------------- HASH：确定性 + 落域 [0,1) ----------------
        boolean hashDet = true;
        float min = 2f, max = -1f;
        double sum = 0;
        for (int i = 0; i < 2000; i++) {
            float a = EffectQueue.hash01(999L, i, 3);
            float b = EffectQueue.hash01(999L, i, 3);
            if (a != b) hashDet = false;
            if (a < min) min = a;
            if (a > max) max = a;
            sum += a;
        }
        double mean = sum / 2000.0;
        boolean hashOk = hashDet && min >= 0f && max < 1f && mean > 0.42 && mean < 0.58;
        ck("HASH", hashOk, String.format("det=%s min=%.4f max=%.4f mean=%.4f", hashDet, min, max, mean));

        // ---------------- FILE：磁盘内容目录（若存在）必须零错误 ----------------
        // 护栏意义：任何人（包括 AI）往 assets/content/ 写坏一个 JSON 或写错引用，
        // 这条断言会立刻在构建期报出来 —— 内容可以随便加，但坏内容进不了主干。
        java.io.File dir = new java.io.File("assets/content");
        ContentRegistry rf = ContentRegistry.load(dir);
        if (dir.isDirectory()) {
            String first = rf.errors().isEmpty() ? "" : (" first=\"" + rf.errors().get(0) + "\"");
            ck("FILE", rf.ok() && rf.total() > 0,
                    "total=" + rf.total() + " disk=" + rf.fromDisk() + " errors=" + rf.errors().size() + first);
        } else {
            ck("FILE", true, "assets/content absent -> builtin fallback (skipped)");
        }

        // ---------------- 分派：指令 → 正确出口 ----------------
        Rec s2rec = new Rec();
        EffectQueue q2 = new EffectQueue(5L);
        q2.submit(Effect.parse(j("{\"type\":\"SPAWN_PARTICLE\",\"id\":\"spark\"}")), 0, 0, 0, 0);
        q2.submit(Effect.parse(j("{\"type\":\"SCREEN_SHAKE\",\"power\":0.4}")), 0, 0, 0, 0);
        q2.submit(Effect.parse(j("{\"type\":\"SET_BLOCK\",\"amount\":1}")), 0, 0, 0, 0);
        q2.submit(Effect.parse(j("{\"type\":\"DIALOGUE\",\"id\":\"hello\"}")), 0, 0, 0, 0);
        q2.tick(0, s2rec, r);
        boolean dispOk = s2rec.calls.size() == 4
                && s2rec.calls.get(0).startsWith("particle:")
                && s2rec.calls.get(1).startsWith("shake:")
                && s2rec.calls.get(2).startsWith("sim:SET_BLOCK")
                && s2rec.calls.get(3).equals("banner:hello");
        ck("DISPATCH", dispOk, "calls=" + s2rec.calls);

        // ================= P1 内容层接线：内容兽 + 任务运行时 =================
        // 这一段断言的是 EVAL-3 点名的两个"死尾"已经消失：
        //   ① beasts/*.json 不再只是 schema —— 会被解析、校验、并在刷怪时真的被选中；
        //   ② quests/*.json 不再只是 schema —— 能被事件驱动走完全程，并真的发出奖励效果。
        ContentRegistry disk = ContentRegistry.load(new java.io.File("assets/content"));
        boolean diskOk = disk.ok();
        boolean beastOk = diskOk && disk.beastDefs().size() >= 1;
        core.content.BeastDef wolf = disk.beastDef("ash_wolf");
        boolean wolfOk = wolf != null && wolf.hp > 0 && wolf.atk > 0
                && wolf.speedPerTick > 0f && wolf.archetype >= 0
                && wolf.archetype < core.world.Beast.N_TYPES;
        ck("QUEST-PARSE", beastOk && wolfOk,
                "beasts=" + disk.beastDefs().size() + " quests=" + disk.questDefs().size()
                + " wolf=" + (wolf == null ? "null" : wolf.toString()));

        // 悬空 on.beast 必须在加载期报错（否则就是"接了也完不成"的第二种死法）
        Map<String, String> badQuest = new LinkedHashMap<String, String>();
        badQuest.put("beasts/real.json", "{\"name\":\"Real\",\"hp\":10,\"atk\":2,\"speed\":2.0}");
        badQuest.put("quests/ghost.json", "{\"title\":\"g\",\"nodes\":[{\"id\":\"a\","
                + "\"on\":{\"type\":\"kill\",\"beast\":\"no_such_beast\",\"count\":1},\"next\":\"\"}]}");
        ContentRegistry badQ = ContentRegistry.of(badQuest);
        boolean danglingCaught = !badQ.ok() && badQ.questDefs().isEmpty();
        ck("QUEST-DANGLING", danglingCaught,
                "errors=" + badQ.errors().size() + (badQ.errors().isEmpty() ? "" : " first=\"" + badQ.errors().get(0) + "\""));

        // 走完全程：3 只 ash_wolf → 节点推进 → 终点效果真的发出去
        Simulation qsA = new Simulation(20260916L, 64, 40, 64);
        Simulation qsB = new Simulation(20260916L, 64, 40, 64);
        long rngBefore = qsA.world.rng.state();
        Rec qrec = new Rec();
        core.content.QuestEngine qe = new core.content.QuestEngine(disk.questDefs());
        qe.setSink(qrec);
        qsA.world.beastDefs = disk.beastDefs();
        long hashBefore = qsA.world.hashState();
        int cross = 0;
        for (int k = 0; k < 3; k++) {
            qsA.world.log("combat", "kill", "ash_wolf", "test");
            cross += qe.tick(qsA.world, 0L);
        }
        boolean questRan = cross >= 2 && qsA.world.quests.completedCount() == 1;
        boolean rewardSent = false;
        for (int i = 0; i < qrec.calls.size(); i++) if (qrec.calls.get(i).startsWith("sim:GRANT_ITEM:ember_shard")) rewardSent = true;
        ck("QUEST-RUN", questRan && rewardSent,
                "crossed=" + cross + " " + qsA.world.quests.asciiSummary() + " sink=" + qrec.calls);

        // 水印独立：任务引擎绝不写 world.eventsProcessed（那是 ProsperitySystem 的繁荣链水印）
        int wmBefore = qsA.world.eventsProcessed;
        qsA.world.log("combat", "kill", "ash_wolf", "test");
        qe.tick(qsA.world, 0L);
        boolean wmOk = qsA.world.eventsProcessed == wmBefore && qe.watermark() == qsA.world.events.size();
        ck("QUEST-WATERMARK", wmOk,
                "eventsProcessed=" + qsA.world.eventsProcessed + " (未动) engineWM=" + qe.watermark()
                + " events=" + qsA.world.events.size());

        // 零漂移 + 零 RNG：任务进度不进 hashState，且推进不消耗仿真 RNG
        boolean driftOk = qsA.world.hashState() == hashBefore && qsB.world.hashState() == hashBefore;
        boolean rngOk2 = qsA.world.rng.state() == rngBefore;
        ck("QUEST-NODRIFT", driftOk && rngOk2,
                "hashA=" + Long.toHexString(qsA.world.hashState())
                + " hashB(未接任务)=" + Long.toHexString(qsB.world.hashState())
                + " rngUntouched=" + rngOk2);

        // ================= 材料规格数据层（第 13 类内容，2026-09-18）=================
        // 这一段断言的是 docs/NOITA_LESSONS.md 认定的"最值得学的一条"已经落地：
        // 材料的物理规格（硬度/密度/类别/挖掘粒子/标签）从代码搬到数据表。
        core.content.MaterialBook mbook = disk.materialBook();

        // ① 覆盖：每个方块都要有规格 —— 缺一个就会静默走兜底值，那正是这次要消灭的东西
        boolean coverOk = mbook.missing().isEmpty() && mbook.size() == core.world.Blocks.count();

        // ② 等价性（本段的核心）：表值必须与改造前的硬编码字符串链**逐方块一致**。
        //    这是"这次迁移零行为变化"的可执行证据 —— 有人说"材料表改坏了手感"，这条会先红。
        //    例外只在 MIGRATION_FIXES 里显式登记（每条带精确理由），且数量必须精确匹配 ——
        //    于是"偷偷改一个值"或"白名单腐化"都会立刻红。
        java.util.Map<String, String> fixes = migrationFixes();
        java.util.Set<String> added = addedAfterMigration();
        int hardMismatch = 0, hardFixed = 0, hardNew = 0, addedSeen = 0;
        String hardBad = "";
        StringBuilder fixLog = new StringBuilder();
        for (int i = 0; i < core.world.Blocks.count(); i++) {
            String bid = core.world.Blocks.byIndex(i).id;
            if (added.contains(bid)) addedSeen++;   // 登记项必须是真实方块（防白名单腐化：多写/错写的 id 不会被遍历到）
            float tableV = mbook.hardness(i);
            float legacyV = legacyDigHardness(i);
            if (Math.abs(tableV - legacyV) > 1e-4f) {
                if (added.contains(bid)) { hardNew++; continue; }   // 迁移后新增：无历史实现，不比
                if (fixes.containsKey(bid)) {
                    hardFixed++;
                    if (fixLog.length() > 0) fixLog.append("; ");
                    fixLog.append(bid).append(' ').append(legacyV).append("->").append(tableV);
                } else {
                    hardMismatch++;
                    if (hardBad.isEmpty()) hardBad = bid + " table=" + tableV + " legacy=" + legacyV;
                }
            }
        }
        // 等价性护栏（修正原过严断言）：① 故意修正项全部被演练（无幻影 id）
        //   ② 新增项全部是真实方块（无多余/错写 id） ③ 无任何未登记的不匹配。
        // 注：原 hardNew==added.size() 假设"每个新块硬度都≠旧猜测值"——但新块硬度常与旧猜值巧合相同
        //   （如 COBBLE 旧猜 0.70、新设 0.70），不进 mismatch 分支，会导致误 FAIL；正确不变量是 addedSeen。
        boolean fixOk = hardFixed == fixes.size() && addedSeen == added.size();
        ck("MATERIAL-COVER", coverOk && hardMismatch == 0 && fixOk,
                "specs=" + mbook.size() + "/" + core.world.Blocks.count()
                + " missing=" + mbook.missing() + " hardnessMismatch=" + hardMismatch
                + " intentionalFixes=" + hardFixed + "/" + fixes.size()
                + " addedAfterMigration=" + addedSeen + "/" + added.size() + " (hardnessSkipped=" + hardNew + ")"
                + (fixLog.length() == 0 ? "" : " [" + fixLog + "]")
                + (hardBad.isEmpty() ? "" : " first(" + hardBad + ")"));

        // ③ 物理不变量：类别必须与 Blocks 的 solid/liquid 自洽（表里说固体、代码里能穿过 = 假数据），
        //    密度必须守住 Noita 那条"气体最轻"的序（AIR 最小 / BEDROCK 最大 / gas < liquid,solid）。
        boolean typeOk = true;
        String typeBad = "";
        int minD = Integer.MAX_VALUE, maxD = Integer.MIN_VALUE;
        int airD = -1, bedD = -1;
        int maxGas = Integer.MIN_VALUE, minSolidLiq = Integer.MAX_VALUE;
        for (int i = 0; i < core.world.Blocks.count(); i++) {
            core.world.Blocks.Block b = core.world.Blocks.byIndex(i);
            String ct = mbook.cellType(i);
            int d = mbook.density(i);
            if (b.liquid != ct.equals("liquid")) { typeOk = false; if (typeBad.isEmpty()) typeBad = b.id + " liquid=" + b.liquid + " ct=" + ct; }
            if (ct.equals("solid") && !b.solid) { typeOk = false; if (typeBad.isEmpty()) typeBad = b.id + " ct=solid but Blocks.solid=false"; }
            // gas 一定不挡路；powder 两种都合法 —— 松散层（SNOW，非实心、可穿过）
            // 与可站立的粉堆（SAND / ASH，实心但能被更重的材料压开）。
            if (ct.equals("gas") && (b.solid || b.liquid)) {
                typeOk = false; if (typeBad.isEmpty()) typeBad = b.id + " ct=gas but solid/liquid=true";
            }
            if (d < minD) minD = d;
            if (d > maxD) maxD = d;
            if (b.id.equals("AIR")) airD = d;
            if (b.id.equals("BEDROCK")) bedD = d;
            if (ct.equals("gas")) maxGas = Math.max(maxGas, d);
            if (ct.equals("liquid") || ct.equals("solid")) minSolidLiq = Math.min(minSolidLiq, d);
        }
        boolean densOk = airD == minD && bedD == maxD && maxGas < minSolidLiq;
        ck("MATERIAL-PHYS", typeOk && densOk,
                "cellTypeOk=" + typeOk + (typeBad.isEmpty() ? "" : " bad(" + typeBad + ")")
                + " AIRmin=" + (airD == minD) + " BEDROCKmax=" + (bedD == maxD)
                + " gas<liquid,solid=" + (maxGas < minSolidLiq) + "(" + maxGas + "<" + minSolidLiq + ")");

        // ④ 铁律 3「未知即拒绝」：5 类坏材料（未知方块名 / 悬空粒子 / 未知类别 / 未知标签 / 非法硬度）
        //    必须在**加载期**响亮报错，且一条都不许进书 —— 否则拼错的东西会静默失效。
        Map<String, String> badMat = new LinkedHashMap<String, String>();
        badMat.put("particles/dust.json", "{\"count\":1}");
        badMat.put("materials/NOPE.json", "{\"hardness\":1.0}");
        badMat.put("materials/STONE.json", "{\"digParticle\":\"no_such_particle\"}");
        badMat.put("materials/DIRT.json", "{\"cellType\":\"plasma\"}");
        badMat.put("materials/SAND.json", "{\"tags\":[\"glowy\"]}");
        badMat.put("materials/WOOD.json", "{\"hardness\":0}");
        ContentRegistry badM = ContentRegistry.of(badMat);
        boolean loudOk = !badM.ok() && badM.errors().size() >= 5 && badM.materials().isEmpty();
        ck("MATERIAL-LOUD", loudOk,
                "errors=" + badM.errors().size() + " accepted=" + badM.materials().size()
                + (badM.errors().isEmpty() ? "" : " first=\"" + badM.errors().get(0) + "\""));

        // ⑤ 标签的跨表价值：标签是"一条规则覆盖一整类材料"的钩子（Noita 的核心技巧）。
        //    这里先立一条真实可用的连接：标了 ore 的方块**必须**能掉出物品 —— 否则
        //    "有矿石标签却挖不出东西"就是又一个静默死尾。
        boolean oreOk = true;
        String oreBad = "";
        java.util.List<core.content.MaterialDef> mats = disk.materials();
        for (int i = 0; i < mats.size(); i++) {
            core.content.MaterialDef m = mats.get(i);
            if (m.hasTag("ore") && disk.itemForBlock(m.id) == null) { oreOk = false; oreBad = m.id; }
        }
        ck("MATERIAL-ORE", oreOk && mats.size() >= core.world.Blocks.count(),
                "oreTaggedHaveDrop=" + oreOk + (oreBad.isEmpty() ? "" : " bad(" + oreBad + ")")
                + " specs=" + mats.size());

        // ============ REACTION：材料反应表（2026-09-18 批 C，内容第 14 类）============
        // 学 Noita 的两条：① 「A 挨 B → C」写成一张表，而不是每个材料一套系统；
        // ② 输入支持 "[tag]" —— 一条规则覆盖一整类材料（本项目 melt 的 [ice] 覆盖 SNOW+ICE）。
        core.content.ReactionBook rbook = disk.reactionBook();
        int bFire = core.world.Blocks.FIRE.index, bWater = core.world.Blocks.WATER.index;
        int bSnow = core.world.Blocks.SNOW.index, bIce = core.world.Blocks.ICE.index;
        int bGrass = core.world.Blocks.GRASS.index;

        // ① 解析：规则全部进书、无一条因引用错误被丢弃
        ck("REACTION-PARSE", rbook.size() == disk.reactions().size() && rbook.dropped().isEmpty(),
                "rules=" + rbook.size() + "/" + disk.reactions().size() + " dropped=" + rbook.dropped());

        // ② 标签展开（核心技巧）：[ice] 必须同时命中 SNOW 与 ICE —— 这一条证明了
        //    「一条规则覆盖多种材料」是真的在工作，而不是只解析了一个字面量。
        boolean tagOk = rbook.ruleFor(bSnow, bFire) >= 0
                && rbook.ruleFor(bSnow, bFire) == rbook.ruleFor(bIce, bFire)
                && rbook.ruleFor(bGrass, bFire) >= 0                 // [dry] 命中 GRASS
                && rbook.ruleFor(core.world.Blocks.MOSS.index, bFire) < 0   // MOSS 不带 dry → 不该被引燃
                && rbook.ruleFor(bFire, bWater) >= 0;                // [fire] + [liquid]
        ck("REACTION-TAG", tagOk,
                "snow+fire=" + rbook.ruleFor(bSnow, bFire) + " ice+fire=" + rbook.ruleFor(bIce, bFire)
                        + " grass+fire=" + rbook.ruleFor(bGrass, bFire)
                        + " moss+fire=" + rbook.ruleFor(core.world.Blocks.MOSS.index, bFire));

        // ③ 无序对：材料对的两个方向必须命中同一条规则，且只有 flipped 不同
        int rFW = rbook.ruleFor(bFire, bWater), rWF = rbook.ruleFor(bWater, bFire);
        boolean symOk = rFW >= 0 && rFW == rWF
                && rbook.flippedFor(bFire, bWater) != rbook.flippedFor(bWater, bFire);
        ck("REACTION-SYM", symOk, "fire,water=" + rFW + " water,fire=" + rWF
                + " flip(a,b)=" + rbook.flippedFor(bFire, bWater)
                + " flip(b,a)=" + rbook.flippedFor(bWater, bFire));

        // ④ 响亮：5 类坏规则必须全部报错且不进书（铁律 3「未知即拒绝」）
        boolean rLoudOk = true; String rLoudBad = "";
        String[][] rBadCases = {
                {"r_unknown_block", "{\"in1\":\"NOT_A_BLOCK\",\"in2\":\"WATER\",\"out1\":\"AIR\"}"},
                {"r_unknown_tag",   "{\"in1\":\"[nosuchtag]\",\"in2\":\"WATER\",\"out1\":\"AIR\"}"},
                {"r_missing_in2",   "{\"in1\":\"FIRE\",\"out1\":\"AIR\"}"},                        // 缺 in2
                {"r_bad_prob",      "{\"in1\":\"FIRE\",\"in2\":\"WATER\",\"out1\":\"AIR\",\"probability\":3.0}"},
                {"r_no_out",        "{\"in1\":\"FIRE\",\"in2\":\"WATER\"}"},                       // 无产物 = 死规则
        };
        for (String[] rCase : rBadCases) {
            java.util.Map<String, String> f = sample();
            f.put("reactions/" + rCase[0] + ".json", rCase[1]);
            ContentRegistry badReg = ContentRegistry.of(f);
            boolean errored = !badReg.errors().isEmpty();
            boolean notInBook = badReg.reactionBook().ruleFor(bFire, bWater) < 0;
            if (!(errored && notInBook)) { rLoudOk = false; if (rLoudBad.isEmpty()) rLoudBad = rCase[0]; }
        }
        ck("REACTION-LOUD", rLoudOk, "allRejected=" + rLoudOk + (rLoudBad.isEmpty() ? "" : " first(" + rLoudBad + ")"));

        // ⑤ 空表兜底 ——「出厂关闭 → 裸 new 的世界零影响」的可执行证明。
        //    与 MATERIAL-BARE-SAFE 同一条防线：没有内容时任何材料对都查不到规则。
        core.content.ReactionBook bare = core.content.ReactionBook.empty(core.world.Blocks.count());
        boolean bareOk = true;
        for (int a = 0; a < core.world.Blocks.count() && bareOk; a++)
            for (int b = 0; b < core.world.Blocks.count(); b++)
                if (bare.ruleFor(a, b) >= 0 || bare.reactive(a)) { bareOk = false; break; }
        ck("REACTION-BARE-SAFE", bareOk && bare.isEmpty(), "emptyBookHasNoRule=" + bareOk);

        // ⑥ 掉落覆盖（2026-09-25）：把"挖了什么都不掉"从 **32 个收敛到 5 个环境块** ——
        //    判据是**派生**的（扫全部方块 × itemForBlock），不是手写数字。
        //    为什么必须加：itemForBlock 的兜底是"id.toLowerCase() 存在就拿"，**靠命名巧合**对齐 ——
        //    新增了方块却忘了配物品时，**没有任何东西会报错**，挖下去只是静默没有产出。
        //  ⚠️ 这里必须用**磁盘真内容**（与 CharacterTest/GameplayTest 同一入口），不能复用上面的 `sample()` ——
        //  那是给"效果分派"准备的内存小样本，里面既没有我的物品、方块也没有掉落绑定，
        //  用它跑这段会得到"116 个方块全都无掉落"的**假失败**（本段第一版就是这么翻车的）。
        core.content.ContentRegistry dropReg = core.content.ContentRegistry.load(new java.io.File("assets/content"));
        ck("DROP-REG-LOADED", dropReg != null && dropReg.item("stone") != null,
                "磁盘内容已加载=" + (dropReg != null) + " 样本物品数=" + (dropReg == null ? -1 : dropReg.of("items").size()));
        String[] envNoDrop = { "AIR", "WATER", "LAVA", "BEDROCK", "FIRE" };   // 环境块**故意**不可获得
        java.util.List<String> noDrop = new java.util.ArrayList<String>();
        java.util.List<String> unexpected = new java.util.ArrayList<String>();
        for (int i = 0; i < core.world.Blocks.count(); i++) {
            String bid = core.world.Blocks.byIndex(i).id;
            if (dropReg.itemForBlock(bid) != null) continue;
            noDrop.add(bid);
            boolean env = false;
            for (String e : envNoDrop) if (e.equals(bid)) { env = true; break; }
            if (!env) unexpected.add(bid);
        }
        ck("DROP-COVER", unexpected.isEmpty() && noDrop.size() == envNoDrop.length,
                "无掉落方块=" + noDrop.size() + " (want " + envNoDrop.length + " 环境块) 意外缺口=" + unexpected);

        // ⑦ 跨阶段别名（alsoDropsFrom）：一个物品覆盖同一作物的多个阶段 —— 打掉没熟的小麦也能收回种子。
        //    同时守住"主绑定优先"：WHEAT_3（成熟）仍然是 wheat，不被别名的种子抢走。
        boolean seedAlias = "wheat_seeds".equals(dropReg.itemForBlock("WHEAT_0"))
                && "wheat_seeds".equals(dropReg.itemForBlock("WHEAT_1"))
                && "wheat_seeds".equals(dropReg.itemForBlock("WHEAT_2"))
                && "wheat".equals(dropReg.itemForBlock("WHEAT_3"))
                && dropReg.item("wheat_seeds") != null
                && "WHEAT_0".equals(dropReg.item("wheat_seeds").block);
        ck("DROP-SEED", seedAlias,
                "WHEAT_0/1/2 -> " + dropReg.itemForBlock("WHEAT_0") + "/" + dropReg.itemForBlock("WHEAT_1")
                + "/" + dropReg.itemForBlock("WHEAT_2") + " | WHEAT_3 -> " + dropReg.itemForBlock("WHEAT_3")
                + " | 种子放置的方块=" + (dropReg.item("wheat_seeds") == null ? "?" : dropReg.item("wheat_seeds").block));

        // ⑧ 可种植性：sowOn 里的名字必须是**真实方块**（未知即拒绝 —— 与材料加载期"未知标签报错"同一纪律）。
        //    否则拼错一个方块名 → 那颗种子**永远种不下去**，而且不会报任何错。
        java.util.Map<String, com.google.gson.JsonObject> itemsJson = dropReg.of("items");
        java.util.List<String> badSow = new java.util.ArrayList<String>();
        int sownCount = 0;
        for (String iid : itemsJson.keySet()) {
            core.content.ItemDef it = dropReg.item(iid);
            if (it == null || it.sowOn == null) continue;
            sownCount++;
            for (String bid : it.sowOn) if (core.world.Blocks.byId(bid) == null) badSow.add(iid + ":" + bid);
        }
        ck("SOW-NAMES", badSow.isEmpty() && sownCount >= 4,
                "带 sowOn 的物品=" + sownCount + " 未知方块=" + badSow);

        // ⑨ 可种植性的**纯函数真值表**：有要求就必须匹配；没要求（缺省）恒可种 —— 保证旧玩法不受影响。
        core.content.ItemDef wheatSeed = dropReg.item("wheat_seeds");
        core.content.ItemDef plainStone = dropReg.item("stone");
        boolean sowSem = wheatSeed != null && wheatSeed.canSowOn("FARMLAND")
                && !wheatSeed.canSowOn("STONE") && !wheatSeed.canSowOn(null)
                && plainStone != null && plainStone.canSowOn("STONE") && plainStone.canSowOn(null);
        ck("SOW-SEMANTICS", sowSem,
                "小麦种子: 耕地=" + (wheatSeed != null && wheatSeed.canSowOn("FARMLAND"))
                + " 石头=" + (wheatSeed != null && wheatSeed.canSowOn("STONE"))
                + " null=" + (wheatSeed != null && wheatSeed.canSowOn(null))
                + " | 无要求物(石头): 任意=True null=" + (plainStone != null && plainStone.canSowOn(null)));

        // ⑩ 邻域条件（第十五批）：`sowOn` 只管"基质"，再加两维**环境** —— 邻水（甘蔗）/ 光照（喜光作物）。
        //    判据仍是**纯函数**（`canSow(SowEnv)`），环境被折成 3 个值 ⇒ 真值表可穷举。
        //    ⚠️ 这里只钉**判据**；"渲染层从世界采出来的值对不对"门禁验不了 ——
        //    这正是把 `canSow(SowEnv)` 与 `Game.doPlace` 的采样分开的理由（见 ItemDef.SowEnv 的说明）。
        core.content.ItemDef cane = dropReg.item("sugarcane_cutting");
        core.content.ItemDef.SowEnv wetSand = new core.content.ItemDef.SowEnv("SAND", 1, 15);
        core.content.ItemDef.SowEnv drySand = new core.content.ItemDef.SowEnv("SAND", 0, 15);
        core.content.ItemDef.SowEnv wetStone = new core.content.ItemDef.SowEnv("STONE", 1, 15);
        core.content.ItemDef.SowEnv litFarm = new core.content.ItemDef.SowEnv("FARMLAND", 0, 15);
        core.content.ItemDef.SowEnv darkFarm = new core.content.ItemDef.SowEnv("FARMLAND", 0, 2);
        boolean envOk = cane != null && cane.sowNearWater
                && cane.canSow(wetSand)                       // 沙 + 邻水 → 可
                && !cane.canSow(drySand)                      // 沙 + 无水 → 不可
                && !cane.canSow(wetStone)                     // 水边石头 → 不可（基质先判）
                && wheatSeed != null && wheatSeed.hasSowRule() && wheatSeed.sowMinLight >= 1
                && wheatSeed.canSow(litFarm)                  // 耕地 + 全光 → 可
                && !wheatSeed.canSow(darkFarm)                // 耕地 + 阴影 → 不可
                && !wheatSeed.canSow(null);                   // 拿不到环境 → 不可（有要求者不放行）
        boolean envDefault = plainStone != null && !plainStone.hasSowRule()
                && plainStone.canSow(null)
                && plainStone.canSow(new core.content.ItemDef.SowEnv("STONE", 0, 0));
        ck("SOW-ENV", envOk && envDefault,
                "甘蔗: 邻水沙=" + (cane != null && cane.canSow(wetSand))
                + " 无水沙=" + (cane != null && cane.canSow(drySand))
                + " 邻水石=" + (cane != null && cane.canSow(wetStone))
                + " | 小麦: 全光耕地=" + (wheatSeed != null && wheatSeed.canSow(litFarm))
                + " 阴影耕地=" + (wheatSeed != null && wheatSeed.canSow(darkFarm))
                + " 无环境=" + (wheatSeed != null && wheatSeed.canSow(null))
                + " | 无要求物(石头) 全过=" + envDefault);

        System.out.println("CONTENT " + (fails == 0 ? "PASS" : "FAIL " + fails) + "  (" + props + " properties)");
        if (fails > 0) System.exit(1);
    }

    /**
     * 2026-09-18 从 {@code Game.digHardness()} 抄下的<b>历史实现</b> —— 仅作材料表迁移的
     * 等价性参照。<b>故意不改</b>：它必须冻结在迁移那一刻的样子，否则"等价性"就失去意义。
     */
    private static float legacyDigHardness(int mat) {
        core.world.Blocks.Block b = core.world.Blocks.byIndex(mat);
        if (b == null || !b.solid) return 0.02f;
        String id = b.id == null ? "" : b.id;
        if (id.contains("LEAF") || id.contains("FLOWER") || id.contains("GRASS")
                || id.contains("CACTUS") || id.contains("MOSS") || id.contains("VINE")) return 0.05f;
        if (id.contains("DIRT") || id.contains("SAND") || id.contains("CLAY")
                || id.contains("ASH") || id.contains("SNOW")) return 0.40f;
        if (id.contains("WOOD") || id.contains("SHELTER")) return 0.85f;
        if (id.contains("GLASS") || id.contains("LAMP") || id.contains("GOLD")) return 1.30f;
        if (id.contains("ORE") || id.contains("STONE") || id.contains("ASHLAR")) return 1.05f;
        return 0.70f;
    }

    /**
     * 迁移时<b>故意修正</b>的方块 → 理由（材料表把这些值改成了旧代码"想写但写不到"的意图）。
     *
     * <p><b>被暴露的真 bug</b>：旧链里 {@code contains("ASH") → 0.40f} 排在
     * {@code contains("ASHLAR") → 1.05f} <b>之前</b>，而 {@code "ASHLAR"} 含子串 {@code "ASH"} ——
     * 于是条石家族的 rock 分支是<b>不可达代码</b>：巨构石墙一直被当"软土" 0.4 秒瞬挖，
     * 与作者写在注释和分支里的意图相反。这是"用字符串猜材料属性"的典型代价，
     * 也正是把属性搬进数据表要解决的问题（{@code docs/NOITA_LESSONS.md} §2.1）。
     *
     * <p>白名单必须<b>精确匹配</b>（多一个少一个都 FAIL）→ 防止它腐化成"随便豁免"。
     */
    private static java.util.Map<String, String> migrationFixes() {
        java.util.Map<String, String> m = new LinkedHashMap<String, String>();
        m.put("ASHLAR", "旧链先命中 contains(\"ASH\")=0.40；条石应为石质 1.05（意图分支不可达）");
        m.put("ASHLAR_SHADE", "同上（名为 ASHLAR_SHADE，含 ASH 子串）");
        m.put("ASHLAR_VEIN", "同上（名为 ASHLAR_VEIN，含 ASH 子串）");
        return m;
    }

    /**
     * **迁移之后才新增**的方块 —— 旧实现里<b>根本不存在</b>它们，无所谓"等价"，
     * 故不参与 {@link #legacyDigHardness} 比对（与 {@link #migrationFixes} 的
     * "旧实现有、但值是错的"性质不同，两者分开登记）。
     */
    private static java.util.Set<String> addedAfterMigration() {
        java.util.Set<String> s = new java.util.LinkedHashSet<String>();
        s.add("ASH");   // 2026-09-18 批 B：火山灰专属材质（此前 AshFallSystem 只能借 STONE 占位）
        // ---- 融合百家（2026-09-23）：参考 MC / NOITA / 泰拉瑞亚 新增的 16 方块，旧实现里不存在 ----
        s.add("COBBLE");      s.add("GRANITE");     s.add("OBSIDIAN");    s.add("SLATE");
        s.add("GRAVEL");      s.add("RED_SAND");    s.add("PODZOL");      s.add("SOUL_SAND");
        s.add("BRICK");       s.add("SANDSTONE");   s.add("TERRACOTTA");  s.add("AMETHYST");
        s.add("LANTERN");     s.add("VINES");       s.add("MUSHROOM");    s.add("HAY");
        // ---- 第二批「源源不断」（2026-09-23）：矿石 + 液体 + 作物 ----
        s.add("COPPER_ORE");   s.add("TIN_ORE");    s.add("GOLD_ORE");
        s.add("SILVER_ORE");   s.add("LEAD_ORE");   s.add("EMERALD_ORE");
        s.add("LAVA");         s.add("MUD");
        s.add("WHEAT_0");      s.add("WHEAT_1");    s.add("WHEAT_2");     s.add("WHEAT_3");
        s.add("SUGARCANE_0");  s.add("SUGARCANE_1"); s.add("SUGARCANE_2");
        s.add("CACTUS_FLOWER_0"); s.add("CACTUS_FLOWER_1"); s.add("CACTUS_FLOWER_2");
        // ---- 第三批（2026-09-23）：功能方块 ----
        s.add("DOOR");         s.add("CHEST");      s.add("FURNACE");     s.add("WORKBENCH");
        // ---- 第四批（2026-09-23）：矿物经济 / 金属储块 / 石砖 / 发光装饰 ----
        s.add("DIAMOND_ORE");  s.add("REDSTONE_ORE"); s.add("LAPIS_ORE");  s.add("QUARTZ_ORE");
        s.add("IRON_BLOCK");   s.add("GOLD_BLOCK");  s.add("COPPER_BLOCK");
        s.add("BRONZE_BLOCK"); s.add("EMERALD_BLOCK");
        s.add("STONE_BRICK");  s.add("MOSSY_STONE_BRICK");
        s.add("CRACKED_STONE_BRICK"); s.add("CHISELED_STONE_BRICK");
        s.add("GLOWSTONE");    s.add("SEA_LANTERN"); s.add("BOOKSHELF");
        s.add("SMOOTH_STONE"); s.add("POLISHED_GRANITE");
        // ---- 第五批（2026-09-24）：功能方块之二 + 红石 ----
        s.add("BED");          s.add("LADDER");      s.add("FENCE");
        s.add("LEVER");        s.add("BUTTON");      s.add("WIRE");
        // ---- 第六批（2026-09-24）：活板门 / 漏斗 / 告示牌 ----
        s.add("TRAPDOOR");     s.add("HOPPER");      s.add("SIGN");
        // ---- 第七批（2026-09-24）：红石逻辑三件套 + 生活方块 ----
        s.add("REPEATER");     s.add("COMPARATOR");  s.add("PLATE");
        s.add("CAMPFIRE");     s.add("FARMLAND");
        // ---- 第九批（2026-09-24）：红石驱动的机械 ----
        s.add("DISPENSER");    s.add("PISTON");
        // ---- 第十一批（2026-09-24）：观察者 ----
        s.add("OBSERVER");
        // ---- 第十二批（2026-09-25）：系统之影 + 玩法纵深 ----
        s.add("CORAL");           s.add("BEEHIVE");         s.add("CRYSTAL");
        s.add("CRYSTAL_CLUSTER"); s.add("FERN");            s.add("REED");
        s.add("SPORE_POD");       s.add("DEAD_BUSH");       s.add("SAPLING");
        s.add("BAMBOO");          s.add("KELP");            s.add("BARREL");
        s.add("CAULDRON");        s.add("LECTERN");         s.add("IRON_BARS");
        s.add("CHAIN");           s.add("SCAFFOLDING");
        // ---- 第十六批（2026-09-25）：几何基座 —— 首个**非满格**方块（台阶/半砖）----
        s.add("STONE_SLAB");      s.add("WOOD_SLAB");
        // ---- 第十七批（2026-09-26）：台阶家族（给已有方块族各配一档半砖）----
        s.add("COBBLE_SLAB");     s.add("STONE_BRICK_SLAB");  s.add("SANDSTONE_SLAB");
        s.add("BRICK_SLAB");      s.add("IRON_BLOCK_SLAB");
        // ---- 第十七批（二）：形状二元化（上半砖 / 薄层）----
        s.add("STONE_SLAB_TOP");  s.add("WOOD_SLAB_TOP");     s.add("SNOW_LAYER");
        // ---- 第十七批（三）：台阶（L 形方块）----
        s.add("STONE_STAIRS");
        // ---- 第十八批（2026-09-26）：台阶填表（木 / 红砖 / 砂岩）----
        s.add("WOOD_STAIRS");     s.add("BRICK_STAIRS");      s.add("SANDSTONE_STAIRS");
        // ---- 第十九批（2026-09-26）：轨道（薄片 + 朝向决定走向）----
        s.add("RAIL");
        return s;
    }

    private static StringBuilder runTrace() {
        EffectQueue q = new EffectQueue(424242L);
        EffectQueue q2 = null;
        ContentRegistry r = ContentRegistry.of(sample());
        q.submit(Effect.parse(j("{\"type\":\"DAMAGE\",\"amount\":7,\"chance\":0.6}")), 0, 0, 0, 0);
        q.submit(Effect.parse(j("{\"type\":\"SPAWN_FX\",\"id\":\"burst\",\"delay\":0.35}")), 0, 0, 0, 0);
        q.submit(Effect.parse(j("{\"type\":\"PLAY_SFX\",\"id\":\"z\"}")), 0, 0, 0, 0);
        for (int t = 0; t <= 14; t++) q.tick(t, EffectSink.NONE, r);
        StringBuilder sb = new StringBuilder();
        for (String s : q.trace()) sb.append(s).append(';');
        return sb;
    }
}
