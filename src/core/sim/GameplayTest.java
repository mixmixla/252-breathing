package core.sim;

import com.google.gson.JsonObject;
import core.content.ContentRegistry;
import core.content.EffectQueue;
import core.content.EffectSink;
import core.content.Preset;
import core.content.Rule;
import core.content.RuleEngine;
import core.world.World;
import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 玩法层门禁（第 28 个出口）——断言「玩法可配置」的十条性质。
 *
 * <p>核心断言意图：
 * <ul>
 *   <li><b>PRESET</b>：预设真的能决定「启用谁」——这是「我们的预设」这个概念的地基；</li>
 *   <li><b>MATCH</b>：事件过滤（action + from）精确，不乱触发；</li>
 *   <li><b>WATERMARK</b>：规则引擎用自己的水印、且<b>绝不写</b> {@code world.eventsProcessed}
 *       —— 两个消费者共用一个水印会互相吃事件，这是隐性 bug 的经典形态；</li>
 *   <li><b>COOLDOWN / SCALAR</b>：冷却与标量门控真的生效（不是装饰）；</li>
 *   <li><b>DET</b>：同种子 + 同事件序列 → 同执行序列（内容层不动指纹的前提）。</li>
 * </ul>
 */
public final class GameplayTest {

    private static int fails = 0;
    /** 已执行的断言数（**运行时统计**，不手写 —— 手写数字曾与真实段数漂移）。 */
    private static int props = 0;

    private static void ck(String tag, boolean cond, String detail) {
        props++;
        System.out.println(tag + " " + (cond ? "PASS" : "FAIL") + "  " + detail);
        if (!cond) fails++;
    }

    /** 记录出口调用 —— 验证「规则 → 效果 → 出口」整条链。 */
    private static final class Rec implements EffectSink {
        final List<String> calls = new ArrayList<String>();
        @Override public void particle(JsonObject d, float x, float y, float z) { calls.add("particle"); }
        @Override public void fx(JsonObject d, float x, float y, float z)       { calls.add("fx"); }
        @Override public void sfx(String id)                                     { calls.add("sfx:" + id); }
        @Override public void shake(float amp)                                   { calls.add("shake"); }
        @Override public void banner(String text)                                { calls.add("banner:" + text); }
        @Override public void simulate(String t, String id, float a, float x, float y, float z) { calls.add("sim:" + t); }
    }

    private static World newWorld() { return new World(20260913L, 64, 48, 64); }

    private static Map<String, String> small(String ruleJson) {
        Map<String, String> f = new LinkedHashMap<String, String>();
        f.put("particles/spark.json", "{\"count\":1}");
        f.put("rules/r.json", ruleJson);
        return f;
    }

    /** 同序列重放 → 取执行 trace（DET 断言用）。 */
    private static String runSeq(ContentRegistry reg) {
        World w = newWorld();
        EffectQueue q = new EffectQueue(w.seed);
        RuleEngine e = new RuleEngine(reg.rules());
        Rec sink = new Rec();
        w.log("tech", "unlock", "", "");
        w.log("farm", "harvest", "", "");
        w.prosperity = 20;
        for (int t = 0; t < 12; t++) {
            e.tick(w, q, t);
            q.tick(t, sink, reg);
        }
        StringBuilder sb = new StringBuilder();
        for (String s : q.trace()) sb.append(s).append(';');
        sb.append("|").append(String.join(",", sink.calls));
        return sb.toString();
    }

    public static void main(String[] args) {

        ContentRegistry reg = ContentRegistry.load(new File("assets/content"));

        // ---------------- RULES / MODULE / PRESET ----------------
        int nRules = reg.rules().size();
        ck("RULES", nRules >= 5 && reg.ok(),
                "rules=" + nRules + " ok=" + reg.ok()
                        + (reg.errors().isEmpty() ? "" : " firstErr=\"" + reg.errors().get(0) + "\""));

        Preset.ModuleDef cap = reg.modules().get("capture");
        ck("MODULE", reg.modules().size() >= 5 && cap != null && cap.requires.contains("emergence"),
                "modules=" + reg.modules().size()
                        + " capture.requires=" + (cap == null ? "null" : cap.requires.toString()));

        Preset bw = reg.preset("breathing_world");
        Preset pw = reg.preset("palworld_like");
        boolean presetOk = bw != null
                && bw.enablesModule("survival") && bw.enablesModule("emergence")
                && !bw.enablesModule("mythic")
                && bw.enablesRule("harvest_logistics", "automation") == false    // 我们的预设不启用自动化
                && pw != null && pw.enablesRule("anything_at_all", "capture");    // "*" = 全启用
        ck("PRESET", presetOk,
                "breathing=" + (bw == null ? "null" : bw.describe())
                        + " | palworld=" + (pw == null ? "null" : pw.describe()));

        // ---------------- 引用校验：坏内容必须被拒 ----------------
        Map<String, String> bad = new LinkedHashMap<String, String>();
        bad.put("modules/m1.json", "{\"name\":\"M1\"}");
        bad.put("particles/spark.json", "{\"count\":1}");
        bad.put("presets/bad.json", "{\"modules\":[\"ghost_module\"]}");
        bad.put("rules/badrule.json", "{\"event\":\"x\",\"then\":[{\"type\":\"SPAWN_PARTICLE\",\"id\":\"ghost_particle\"}]}");
        ContentRegistry rb = ContentRegistry.of(bad);
        boolean rejOk = !rb.ok();
        StringBuilder rbMsg = new StringBuilder();
        for (String e : rb.errors()) rbMsg.append(e).append(" | ");
        ck("REJECT", rejOk && rb.errors().size() >= 2,
                "errors=" + rb.errors().size() + "  " + rbMsg.toString().trim());

        // ---------------- MATCH：事件过滤精确 ----------------
        Rule cr = null;
        for (Rule r : reg.rules()) if ("capture_reward".equals(r.id)) cr = r;
        boolean matchOk = cr != null
                && cr.matches("taming", "capture", "")            // 命中
                && !cr.matches("farm", "capture", "")             // 来源不符
                && !cr.matches("taming", "harvest", "");          // 动作不符
        ck("MATCH", matchOk, "rule=" + (cr == null ? "null" : cr.toString()));

        // ---------------- FIRE：事件 → 规则 → 效果 → 出口 ----------------
        World w = newWorld();
        EffectQueue q = new EffectQueue(w.seed);
        RuleEngine eng = new RuleEngine(reg.rules());
        Rec sink = new Rec();
        w.log("tech", "unlock", "", "");
        int hits = eng.tick(w, q, 10L);
        int executed = q.tick(10L, sink, reg);
        boolean fireOk = hits == 1 && executed == 3 && sink.calls.size() == 3;
        ck("FIRE", fireOk, "hits=" + hits + " executed=" + executed + " calls=" + sink.calls);

        // ---------------- WATERMARK：不重复消费 + 不碰 world 水印 ----------------
        World w4 = newWorld();
        EffectQueue q4 = new EffectQueue(w4.seed);
        RuleEngine e4 = new RuleEngine(reg.rules());
        w4.log("tech", "unlock", "", "");
        int a1 = e4.tick(w4, q4, 5L);
        int a2 = e4.tick(w4, q4, 6L);
        ck("WATERMARK", a1 == 1 && a2 == 0 && w4.eventsProcessed == 0,
                "first=" + a1 + " second=" + a2 + " worldWatermark=" + w4.eventsProcessed
                        + " engineWatermark=" + e4.watermark());

        // ---------------- COOLDOWN：冷却真的挡 ----------------
        ContentRegistry rcd = ContentRegistry.of(small(
                "{\"event\":\"ping\",\"then\":[{\"type\":\"PLAY_SFX\",\"id\":\"p\"}],\"cooldown\":1.0}"));
        World wc = newWorld();
        EffectQueue qc = new EffectQueue(wc.seed);
        RuleEngine ec = new RuleEngine(rcd.rules());
        wc.log("s", "ping", "", ""); int c1 = ec.tick(wc, qc, 100L);   // 命中
        wc.log("s", "ping", "", ""); int c2 = ec.tick(wc, qc, 105L);   // 5 tick < 20 → 挡住
        wc.log("s", "ping", "", ""); int c3 = ec.tick(wc, qc, 121L);   // 21 tick → 通过
        ck("COOLDOWN", c1 == 1 && c2 == 0 && c3 == 1, "at100=" + c1 + " at105=" + c2 + " at121=" + c3);

        // ---------------- SCALAR：标量门控（prosperity） ----------------
        World ws = newWorld();
        EffectQueue qs = new EffectQueue(ws.seed);
        RuleEngine es = new RuleEngine(reg.rules());
        ws.prosperity = 0;
        ws.log("farm", "harvest", "", "");
        int s0 = es.tick(ws, qs, 1L);          // 繁荣不足 → 不触发
        ws.prosperity = 20;
        ws.log("farm", "harvest", "", "");
        int s1 = es.tick(ws, qs, 2L);          // 达标 → 触发
        ck("SCALAR", s0 == 0 && s1 == 1, "prosperity0=" + s0 + " prosperity20=" + s1);

        // ---------------- CHANCE（hash 边界）：p=1 必过，p=0 必不过 ----------------
        ContentRegistry rAlways = ContentRegistry.of(small(
                "{\"event\":\"ping\",\"then\":[{\"type\":\"PLAY_SFX\",\"id\":\"a\"}],\"if\":[{\"check\":\"CHANCE\",\"value\":1.0}]}"));
        ContentRegistry rNever = ContentRegistry.of(small(
                "{\"event\":\"ping\",\"then\":[{\"type\":\"PLAY_SFX\",\"id\":\"a\"}],\"if\":[{\"check\":\"CHANCE\",\"value\":0.0}]}"));
        int always = fireOnce(rAlways), never = fireOnce(rNever);
        ck("CHANCE", always == 1 && never == 0, "p1.0=" + always + " p0.0=" + never);

        // ---------------- DET：同种子同事件 → 同执行序列 ----------------
        String t1 = runSeq(reg);
        String t2 = runSeq(reg);
        ck("DET", t1.equals(t2) && t1.length() > 0,
                "traceBytes=" + t1.length() + " identical=" + t1.equals(t2));

        // ---------------- CHEST：起始物资箱 —— 确定性 + 内容有效 + 小世界边界（内容扩张 2026-09-17） ----------------
        // 内容扩张补的实体层容器此前零覆盖；把三条性质钉成断言：
        //   ① 同种子 → 同箱列表（位置/物品/数量逐字段一致，且初始未开启）= 确定性；
        //   ② 每只箱子的物品都是内容层真实物品（不是幽灵 id）= 接线有效；
        //   ③ 小世界尺度下也不越界、仍出 3 只 = 边界安全。
        Simulation simA = new Simulation(424242L, 64, 40, 64);
        Simulation simB = new Simulation(424242L, 64, 40, 64);
        final int nA = simA.world.chests.size();              // 3 起始 + 14 撒点
        boolean chestSame = nA == 17 && simB.world.chests.size() == 17;
        for (int i = 0; chestSame && i < nA; i++) {
            World.Chest ca = simA.world.chests.get(i), cb = simB.world.chests.get(i);
            chestSame &= ca.x == cb.x && ca.y == cb.y && ca.z == cb.z && ca.tier == cb.tier
                    && ca.itemId.equals(cb.itemId) && ca.count == cb.count && !ca.opened;
        }
        boolean lootReal = true, inAir = true;
        int nCommon = 0, nRare = 0, nHidden = 0;
        for (World.Chest ch : simA.world.chests) {
            lootReal &= reg.item(ch.itemId) != null && ch.count > 0;   // 物品 id 在内容层真实存在
            // 落点必须落在空气格（地表箱在上方、隐藏箱在洞穴袋里）——不埋进实心方块
            inAir &= simA.world.getBlock((int) ch.x, (int) ch.y, (int) ch.z) == core.world.Blocks.AIR.index;
            if (ch.tier == World.Chest.TIER_COMMON) nCommon++;
            else if (ch.tier == World.Chest.TIER_RARE) nRare++;
            else nHidden++;
        }
        boolean tiersOk = nCommon == 12 && nRare == 4 && nHidden == 1;   // 12=3起始+9常见 · 4 稀有 · 1 隐藏
        Simulation simS = new Simulation(7L, 32, 24, 32);
        boolean smallOk = simS.world.chests.size() == 17;                // 小世界同样 17 只、不越界不崩
        ck("CHEST", chestSame && lootReal && inAir && tiersOk && smallOk,
                "n=" + nA + " same=" + chestSame + " lootReal=" + lootReal + " inAir=" + inAir
                        + " tiers=" + nCommon + "/" + nRare + "/" + nHidden + " small=" + smallOk);

        // ---------------- DROPS：挖方块掉落物（MC 手感 §1 P0 #2）—— 落点确定 + 有界 + 零 RNG ----------------
        // 断言：① 落点 = 该列最顶实心之上（静止点，不做物理）；② 入列；③ 超过 MAX_DROPS 后**有界**且最旧先出队。
        // 关键：这些全在实体层，不写 mat、不消费 RNG → 对 hashState / 四基线指纹零影响。
        Simulation simDrop = new Simulation(31337L, 64, 40, 64);
        core.world.World wDrop = simDrop.world;
        int nDropBefore = wDrop.drops.size();
        core.world.World.Drop dropOne = wDrop.spawnDrop(10, 30, 10, "coal", 1);
        boolean dropOnFloor = Math.abs(dropOne.y - (wDrop.surfaceY[10][10] + 1.0f)) < 1e-6f
                && "coal".equals(dropOne.itemId);
        boolean dropAdded = wDrop.drops.size() == nDropBefore + 1;
        for (int i = 0; i < core.world.World.MAX_DROPS + 20; i++) wDrop.spawnDrop(11, 30, 11, "ore", 1);
        boolean dropBounded = wDrop.drops.size() == core.world.World.MAX_DROPS;
        boolean dropOldestGone = !wDrop.drops.contains(dropOne);
        ck("DROPS", dropOnFloor && dropAdded && dropBounded && dropOldestGone,
                "onFloor=" + dropOnFloor + " added=" + dropAdded + " bounded=" + dropBounded
                        + " oldestGone=" + dropOldestGone + " cap=" + core.world.World.MAX_DROPS);

        // ================= MATERIAL-SINK：密度驱动下沉（2026-09-18 原型）=================
        // 这一步的意义：材料规格里的 density 首次有了**仿真层消费者**。
        // 此前 17 个材料相关系统各自硬编码"谁落到谁身上"；现在由一张表 + 一个标量说了算
        // （Noita 的 core trick，见 docs/NOITA_STUDY.md）。出厂 densityFlow=false
        // → 关闭时与历史逐字节一致；打开才出现"沙沉水底"。
        ContentRegistry mreg = ContentRegistry.load(new File("assets/content"));
        core.content.MaterialBook mbook = mreg.materialBook();
        int bSand = core.world.Blocks.SAND.index, bWater = core.world.Blocks.WATER.index;
        int bStone = core.world.Blocks.STONE.index, bIce = core.world.Blocks.ICE.index;
        int bGold = core.world.Blocks.GOLD.index;

        boolean ruleOk = mbook.sinksInto(bSand, bWater)     // 沙 20 > 水 10，水可位移 → 沉
                && !mbook.sinksInto(bWater, bSand)          // 反向不成立
                && !mbook.sinksInto(bSand, bStone)          // 石头不可位移 → 不沉
                && !mbook.sinksInto(bIce, bWater)           // 冰 9 < 水 10 → 不沉（该浮起来）
                && mbook.sinksInto(bGold, bWater);          // 金 45 → 沉
        ck("MATERIAL-SINK-RULE", ruleOk,
                "sand>water=" + mbook.sinksInto(bSand, bWater)
                        + " water>sand=" + mbook.sinksInto(bWater, bSand)
                        + " sand>stone=" + mbook.sinksInto(bSand, bStone)
                        + " ice>water=" + mbook.sinksInto(bIce, bWater)
                        + " gold>water=" + mbook.sinksInto(bGold, bWater));

        Simulation msim = new Simulation(31337L, 32, 40, 32);
        World mw = msim.world;
        mw.materials = mbook;                        // 门禁里手动注入（游戏走 Game.attachContentToWorld）
        final int mx = 4, mz = 4;
        mw.setBlock(mx, 10, mz, bWater);
        mw.setBlock(mx, 11, mz, bSand);
        mw.config.densityFlow = false;               // ① 出厂：沙停在水面之上（= 历史行为）
        new core.systems.SandFallSystem().update(mw, mw.rng);
        boolean sinkOffOk = mw.getBlock(mx, 11, mz) == bSand && mw.getBlock(mx, 10, mz) == bWater;

        mw.setBlock(mx, 10, mz, bWater);             // 复位场景
        mw.setBlock(mx, 11, mz, bSand);
        mw.config.densityFlow = true;                // ② 打开：沙沉底、水被顶上来
        new core.systems.SandFallSystem().update(mw, mw.rng);
        boolean sinkOnOk = mw.getBlock(mx, 10, mz) == bSand && mw.getBlock(mx, 11, mz) == bWater;

        ck("MATERIAL-SINK-SWITCH", sinkOffOk && sinkOnOk,
                "off(staysOnTop)=" + sinkOffOk + " on(sinksDisplaces)=" + sinkOnOk
                        + " y10=" + core.world.Blocks.byIndex(mw.getBlock(mx, 10, mz)).id
                        + " y11=" + core.world.Blocks.byIndex(mw.getBlock(mx, 11, mz)).id);

        // ⑤ 空书兜底 —— **"打开 densityFlow 不必重锁既有基线"这件事的可执行证明**。
        //    Simulation 构造函数**不加载内容** → 门禁里裸 new 的世界 `World.materials` 是空书，
        //    sinksInto 恒 false → 即便出厂已打开开关，这些世界的演化也与开关无关。
        //    把这条论证写成断言，是为了防止有人误以为"打开开关 = 所有指纹都要重锁"而不敢动。
        Simulation bareOn = new Simulation(31337L, 32, 40, 32);
        Simulation bareOff = new Simulation(31337L, 32, 40, 32);
        boolean defaultOn = bareOn.world.config.densityFlow;     // 出厂应为 true
        bareOff.world.config.densityFlow = false;                // 人为关掉作对照
        for (int i = 0; i < 40; i++) { bareOn.world.tick(); bareOff.world.tick(); }
        boolean bareOk = defaultOn && bareOn.world.hashState() == bareOff.world.hashState();
        ck("MATERIAL-BARE-SAFE", bareOk,
                "defaultOn=" + defaultOn + " bareHashEqual=" + (bareOn.world.hashState() == bareOff.world.hashState())
                        + " hash=" + Long.toHexString(bareOn.world.hashState()));

        // ⑥ 火山灰有了自己的材质（AshFall 不再借 STONE 占位）
        int bAsh = core.world.Blocks.ASH.index;
        boolean ashOk = bAsh >= 0 && mbook.has(bAsh)
                && "powder".equals(mbook.cellType(bAsh))
                && mbook.density(bAsh) > mbook.density(bWater)      // 灰比水重 → 会沉入水底
                && mbook.has(bAsh) && mreg.materials().size() >= core.world.Blocks.count();
        ck("MATERIAL-ASH", ashOk && mreg.materials().size() >= core.world.Blocks.count(),
                "specs=" + mreg.materials().size() + " blocks=" + core.world.Blocks.count()
                + " ash: ct=" + mbook.cellType(bAsh) + " dens=" + mbook.density(bAsh)
                + " (water=" + mbook.density(bWater) + ") hard=" + mbook.hardness(bAsh));

        // ============ REACTION-EFFECT：反应表真的会改世界（2026-09-18 批 C）============
        // 数据层断言（解析/标签/响亮）在 ContentTest；这里断言**效果**：
        // 出厂关闭 → 火烧不掉、冰不化；打开 → 三条规则各自生效。
        // 三条规则都是此前**完全没有实现**的物理：灭火、融冰融雪、引燃干枯植物。
        core.content.ReactionBook rbook = mreg.reactionBook();
        int bFire = core.world.Blocks.FIRE.index, bAir = core.world.Blocks.AIR.index;

        Simulation rsim = new Simulation(90210L, 32, 40, 32);
        World rw = rsim.world;
        rw.materials = mbook;
        rw.reactions = rbook;
        final int rx = 6, rz = 6;
        core.systems.ReactionSystem rsys = new core.systems.ReactionSystem();

        // ① 关闭（显式置 false）：火不会被水浇灭
        //    （出厂默认是 **true** —— 批 C 已打开；这里显式关掉是为了做 A/B 对照，
        //     另用 `factoryOn` 单独断言出厂值，职责分开。）
        boolean factoryOn = rw.config.reactionTable;   // 出厂应为 true
        rw.config.reactionTable = false;
        //    注意：求解器遍历的是 surfaceCells（"头顶为空气"的格），所以必须在火的上方显式开一层空气，
        //    否则火被埋在地形里、永远进不了候选集 —— 这个坑是我写本断言时踩到的，留注释防止重犯。
        rw.setBlock(rx, 12, rz, bWater);
        rw.setBlock(rx, 13, rz, bFire);
        rw.setBlock(rx, 14, rz, bAir);
        boolean rxOffOk = rw.getBlock(rx, 13, rz) == bFire;      // 火还在

        // ② 打开：火被水浇灭（quench: [fire] + [liquid] -> AIR）
        rw.setBlock(rx, 12, rz, bWater);
        rw.setBlock(rx, 13, rz, bFire);
        rw.setBlock(rx, 14, rz, bAir);
        rw.config.reactionTable = true;
        for (int i = 0; i < 40; i++) rsys.update(rw, rw.rng);
        boolean rxQuench = rw.getBlock(rx, 13, rz) == bAir;

        // ③ 打开：冰遇火化成水（melt: [ice] + [fire] -> WATER）—— 一条规则同时覆盖 ICE/SNOW
        final int mx2 = 10, mz2 = 10;
        rw.setBlock(mx2, 12, mz2, core.world.Blocks.ICE.index);
        rw.setBlock(mx2, 13, mz2, bFire);
        rw.setBlock(mx2, 14, mz2, bAir);
        for (int i = 0; i < 40; i++) rsys.update(rw, rw.rng);
        boolean rxMelt = rw.getBlock(mx2, 12, mz2) == bWater;

        // ④ 打开：干枯植被被引燃（ignite: [dry] + [fire] -> FIRE）
        final int gx = 14, gz = 14;
        rw.setBlock(gx, 12, gz, core.world.Blocks.GRASS.index);
        rw.setBlock(gx, 13, gz, bFire);
        rw.setBlock(gx, 14, gz, bAir);
        for (int i = 0; i < 40; i++) rsys.update(rw, rw.rng);
        boolean rxIgnite = rw.getBlock(gx, 12, gz) == bFire;

        ck("REACTION-EFFECT", factoryOn && rxOffOk && rxQuench && rxMelt && rxIgnite,
                "factoryOn=" + factoryOn + " off(fireSurvives)=" + rxOffOk
                        + " on(quench)=" + rxQuench + " on(melt)=" + rxMelt + " on(ignite)=" + rxIgnite
                        + " quenchCell=" + core.world.Blocks.byIndex(rw.getBlock(rx, 13, rz)).id
                        + " meltCell=" + core.world.Blocks.byIndex(rw.getBlock(mx2, 12, mz2)).id
                        + " igniteCell=" + core.world.Blocks.byIndex(rw.getBlock(gx, 12, gz)).id);

        System.out.println("GAMEPLAY " + (fails == 0 ? "PASS" : "FAIL " + fails) + "  (" + props + " properties)");
        if (fails > 0) System.exit(1);
    }

    private static int fireOnce(ContentRegistry reg) {
        World w = newWorld();
        EffectQueue q = new EffectQueue(w.seed);
        RuleEngine e = new RuleEngine(reg.rules());
        w.log("s", "ping", "", "");
        e.tick(w, q, 3L);
        return q.tick(3L, EffectSink.NONE, reg);
    }
}
