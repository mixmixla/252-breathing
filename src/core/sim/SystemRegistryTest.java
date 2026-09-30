package core.sim;

import core.systems.Phase;
import core.systems.SystemRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 系统注册表门禁（第 29 个出口）—— 守住「顺序」这条命脉。
 *
 * <p><b>为什么这个门禁最重要</b>：98 个系统的<b>执行顺序决定演化结果</b>。
 * 任何人（包括 AI）重构注册代码时不小心调换两行，同种子的世界就会长出不同的历史。
 * 而那种漂移极难察觉 —— 世界看起来"还挺正常"，只是不再可复现。
 *
 * <p>所以这里用<b>黄金序列哈希</b>把顺序钉死：{@code name|phase} 全序列的 FNV-1a 值
 * 必须等于基线常量。调换、增删、改名 —— 任何一处都会立刻让哈希失配。
 *
 * <p>另外断言：phase 只是标签（默认全开时 {@code activeNames == names}，
 * 即零漂移前提）、按域开关真的生效、重名注册必须抛异常（静默覆盖是事故之源）。
 */
public final class SystemRegistryTest {

    private static int fails = 0;

    /**
     * 黄金序列哈希基线（2026-09-13 建立；2026-09-16 A 批重锁）。
     *
     * <p>A 批（技能链）在注册顺序<b>末尾追加</b>了第 93 个系统 {@code BuffSystem}（内容层 APPLY_BUFF 的
     * 确定性执行者）。追加在末尾 → 既有 92 个系统的相对顺序不变；且它无 buff 时首行即返回 →
     * 对既有种子零影响。故此处 baseline 属「有意演进」重锁，而非事故漂移。
     *
     * <p><b>2026-09-17 第二次重锁（空转参数落地）</b>：末尾再追加 5 个系统
     * （hunger / wire / haul / capture / ascension，第 94~98 个），把 7 个「空转的预设参数」
     * 真正接到玩法上。同样追加在末尾 → 既有 93 个系统相对顺序不变；且出厂默认值下
     * 每个 update 都首行返回（零 RNG、零写入）→ <b>四道仿真指纹逐字节不变</b>
     * （见 DET/ZD/STREAMING/NPC/MEGALITH 未动）。故这也是「有意演进」重锁。
     *
     * <p>它钉住的是：<b>98 个系统的执行顺序 + 它们的 phase 标签</b>。
     * 调换两行、增删一个系统、改一个标签 —— 哈希立刻失配，门禁报 FAIL。
     * 若要<b>有意</b>变更顺序，必须同时确认四道仿真指纹的变化并重新基线。
     */
    // 2026-09-18 批 B **重锁**：删除 QuicksandSystem（系统数 98 -> 97）后重算。
    // 旧值 9078168720030413075L（批 A 基线）已作废 —— 这是"有意演进"，不是事故漂移。
    // 2026-09-18 批 D **重锁**：把 Erosion/Frozen/MossSpread/Clay 收编进反应表（删 4 个 System 类，
    //   系统数 98 -> 94）。它们原本对裸世界真实写块 → 删除会改变裸世界演化 → 属"有意演进"重锁，
    //   需同步重基线四道仿真指纹（DET/ZD 已变，见 MEMORY.md）。注册顺序末尾减 4 → 哈希必变。
    private static final long GOLDEN_HASH = -4904691948453650891L;   // 第五批追加 RedstoneLogicSystem（98 -> 99，末尾）→ 重算回填

    private static void ck(String tag, boolean cond, String detail) {
        System.out.println(tag + " " + (cond ? "PASS" : "FAIL") + "  " + detail);
        if (!cond) fails++;
    }

    private static long fnv(String s) {
        long h = 0xcbf29ce484222325L;
        for (int i = 0; i < s.length(); i++) { h ^= s.charAt(i); h *= 0x100000001b3L; }
        return h;
    }

    public static void main(String[] args) {

        Simulation sim = new Simulation(20260913L, 64, 48, 64);
        if (sim.world.registry.size() == 0) sim.registerDefaultSystems();
        SystemRegistry reg = sim.world.registry;

        // ---------------- COUNT ----------------
        List<String> names = reg.names();
        ck("COUNT", reg.size() == 99, "systems=" + reg.size());   // 98 -> 99：第五批追加 RedstoneLogicSystem（末尾）

        // ---------------- GOLDEN：顺序哈希（命脉断言） ----------------
        String golden = reg.golden();
        long h = fnv(golden);
        boolean goldenOk = (GOLDEN_HASH == 0L) || (h == GOLDEN_HASH);
        ck("GOLDEN", goldenOk, "hash=" + h + " (baseline=" + GOLDEN_HASH + ") first=\""
                + (names.isEmpty() ? "" : names.get(0)) + "\" last=\""
                + (names.isEmpty() ? "" : names.get(names.size() - 1)) + "\"");

        // ---------------- UNIQUE：无重名 ----------------
        List<String> uniq = new ArrayList<String>();
        boolean dupFound = false;
        for (String n : names) { if (uniq.contains(n)) dupFound = true; else uniq.add(n); }
        ck("UNIQUE", !dupFound && uniq.size() == names.size(), "distinct=" + uniq.size());

        // ---------------- PHASES：每个域都有系统 + 分组总量守恒 ----------------
        Map<Phase, List<String>> plan = reg.plan();
        int sum = 0;
        StringBuilder dist = new StringBuilder();
        boolean everyPhaseUsed = true;
        for (Phase p : Phase.values()) {
            int n = plan.get(p).size();
            sum += n;
            if (n == 0) everyPhaseUsed = false;
            dist.append(p.label()).append('=').append(n).append(' ');
        }
        ck("PHASES", everyPhaseUsed && sum == reg.size(), dist.toString().trim());

        // ---------------- NODRIFT：默认全开（零漂移前提） ----------------
        boolean same = reg.activeNames().equals(names);
        ck("NODRIFT", same && !reg.hasDisabled(),
                "active=" + reg.activeNames().size() + " all=" + names.size() + " disabled=" + reg.disabledCount());

        // ---------------- DISABLE：单个开关只影响自己、其余保序 ----------------
        String victim = names.get(10);
        reg.disable(victim);
        List<String> after = reg.activeNames();
        boolean subseq = (after.size() == names.size() - 1) && isSubsequence(after, names);
        ck("DISABLE", subseq && reg.isDisabled(victim), "removed=" + victim + " active=" + after.size());
        reg.enable(victim);

        // ---------------- DISABLE_PHASE：按域开关 ----------------
        int k = reg.disablePhase(Phase.WEATHER);
        List<String> afterPhase = reg.activeNames();
        boolean phaseOk = k > 0 && afterPhase.size() == names.size() - k;
        for (String n : afterPhase) { if (reg.phaseOf(n) == Phase.WEATHER) phaseOk = false; }
        if (afterPhase.size() != names.size() - k && afterPhase.size() > names.size() - k) phaseOk = false;
        ck("DISABLE_PHASE", phaseOk && isSubsequence(afterPhase, names),
                "disabled=" + k + " active=" + afterPhase.size() + " (weather)");
        reg.enableAll();

        // ---------------- ENABLE：恢复后与原始序列完全一致 ----------------
        boolean restored = reg.activeNames().equals(names) && !reg.hasDisabled();
        ck("ENABLE", restored, "restored=" + restored + " disabled=" + reg.disabledCount());

        // ---------------- DUPLICATE：重名必须抛异常 ----------------
        boolean threw = false;
        try {
            // 用全限定名：本类要用 java.lang.System 打印，不能 import core.systems.System
            core.systems.System s = names.isEmpty() ? null : reg.get(names.get(0));
            reg.add(s, Phase.META);
        } catch (RuntimeException ex) { threw = true; }
        ck("DUPLICATE", threw, "duplicate rejected=" + threw);

        // ---------------- PRESET_APPLY：玩法层真的能关掉一整套系统 ----------------
        core.content.ContentRegistry cr = core.content.ContentRegistry.load(new java.io.File("assets/content"));
        core.content.Preset bw = cr.preset("breathing_world");
        core.content.Preset pv = cr.preset("peaceful_valley");
        sim.applyPreset(bw);                                   // 我们的预设：不该关任何东西
        boolean defaultNoop = reg.activeNames().equals(names) && !reg.hasDisabled();
        String rPeace = sim.applyPreset(pv);                   // 关掉 GEOLOGY 域（含 LavaFlowSystem → 现 9 个系统）
        boolean peaceOn = reg.activeNames().size() == names.size() - 9 && reg.hasDisabled();
        sim.applyPreset(null);                                 // 清空
        boolean restored2 = reg.activeNames().equals(names) && !reg.hasDisabled();
        ck("PRESET_APPLY", defaultNoop && peaceOn && restored2,
                "breathingNoop=" + defaultNoop + " [" + rPeace + "] restored=" + restored2);

        // ---------------- PRESET_PARAMS：内容层 params 真的驱动玩法层 ----------------
        //   历史问题：Preset.params 早已解析、却从未被消费（审计 C8 记的「死配置」）。
        //   现在 applyPreset 消费 beastCap / dayLengthMinutes / erosionRate / ascensionThreshold /
        //   haulRate / hungerRate / orbItemCost / hpThreshold / wireRange（共 9 个，全部接线）。
        //   纪律不变：**未接线的参数必须被点名**（?param=…）—— 2026-09-17 之后已无未接线项，
        //   故断言反转为「报告里不得再出现 ?param=」，同时参数必须真的落到 WorldConfig
        //   （防"只改了个没人读的字段"这种假接线）。
        //   可达性：上限不是只改了个没人读的字段 —— 实测野兽数量真的被它卡住。
        final int capFactory = core.world.World.BEAST_CAP, dayFactory = core.world.DayCycle.DEFAULT_DAY_LEN;
        boolean factoryLocked = capFactory == 4 && dayFactory == 12000;    // 基线锚点：改这里=有意改玩法
        core.content.Preset my = cr.preset("mythic_sandbox");
        core.content.Preset pw = cr.preset("palworld_like");
        String rMy = sim.applyPreset(my);
        boolean capApplied = sim.world.beastCap == 40;
        boolean allWired = !rMy.contains("?param=") && sim.world.config.ascensionThreshold == 60f;
        String rPw = sim.applyPreset(pw);
        boolean dayApplied = core.world.DayCycle.DAY_LEN == core.world.DayCycle.minutesToTicks(14f);
        boolean halfSynced = core.world.DayCycle.HALF == core.world.DayCycle.DAY_LEN / 2;
        boolean subSysApplied = sim.world.config.haulRate == 1.5f
                && sim.world.config.hpThreshold == 0.35f
                && !rPw.contains("?param=");
        // 选择器（P1 续）：菜单侧只保存选择，施加只发生在 Game.applySelectedPreset 一处。
        // 这里断言「施加」这一半的两条性质：① 同一预设重施是幂等的；② 切走再切回**逐项还原**
        //   （域开关 / beastCap / 昼夜长度）。缺任一条，玩家就会遇到「切回来世界没完全复原」。
        core.content.Preset bw2 = cr.preset("breathing_world");
        sim.applyPreset(bw2);
        java.util.List<String> act0 = new java.util.ArrayList<String>(reg.activeNames());
        int pCap0 = sim.world.beastCap, pDay0 = core.world.DayCycle.DAY_LEN;
        sim.applyPreset(bw2);                                             // 重施同一预设
        boolean presetIdem = reg.activeNames().equals(act0)
                && sim.world.beastCap == pCap0 && core.world.DayCycle.DAY_LEN == pDay0;
        sim.applyPreset(pw);                                             // 切到另一套（改昼夜、不关系统）
        boolean presetMoved = core.world.DayCycle.DAY_LEN != pDay0;   // palworld = 14 分钟 != 出厂 10 分钟
        sim.applyPreset(bw2);                                            // 切回
        boolean presetRestored = reg.activeNames().equals(act0)
                && sim.world.beastCap == pCap0 && core.world.DayCycle.DAY_LEN == pDay0;
        // 内容层真的给出了可选列表（菜单才有东西可显示）：≥4 套，且含出厂预设。
        java.util.List<core.content.Preset> plist = cr.presets();
        core.sim.MenuModel sel = new core.sim.MenuModel();
        String[] sIds = new String[plist.size()], sNames = new String[plist.size()];
        for (int i = 0; i < plist.size(); i++) { sIds[i] = plist.get(i).id; sNames[i] = plist.get(i).name; }
        sel.setPresets(sIds, sNames);
        sel.selectPreset("breathing_world");
        boolean listed = sel.presetCount() >= 4 && "breathing_world".equals(sel.presetId())
                && !sel.presetName().isEmpty();
        String firstId = sel.presetId();
        for (int i = 0; i < sIds.length; i++) sel.cyclePreset(1);        // 转一圈必须回到原处
        boolean cycleOk = firstId.equals(sel.presetId());
        sim.applyPreset(null);                                           // 复位，让下面的断言从干净状态起

        Simulation s2 = new Simulation(20260916L, 64, 40, 64);
        s2.world.beastCap = 2;
        s2.run(400);                                                        // 跨过 GRACE_TICKS(120) 让刷怪发生
        boolean capEnforced = s2.world.beasts.size() <= 2;
        sim.applyPreset(null);
        boolean resetOk = sim.world.beastCap == capFactory
                && core.world.DayCycle.DAY_LEN == dayFactory
                && core.world.DayCycle.HALF == dayFactory / 2
                && sim.world.config.ascensionThreshold == core.world.WorldConfig.ASCENSION_THRESHOLD_DFLT
                && sim.world.config.haulRate == core.world.WorldConfig.HAUL_RATE_DFLT
                && sim.world.config.hpThreshold == core.world.WorldConfig.HP_THRESHOLD_DFLT
                && sim.world.config.wireRange == core.world.WorldConfig.WIRE_RANGE_DFLT
                && reg.activeNames().equals(names);
        ck("PRESET_PARAMS", factoryLocked && capApplied && allWired && dayApplied
                && halfSynced && subSysApplied && capEnforced && resetOk
                && presetIdem && presetMoved && presetRestored && listed && cycleOk,
                "factory=" + factoryLocked + " capApplied=" + capApplied
                + " allWired=" + allWired + " subSys=" + subSysApplied + " day14min=" + dayApplied
                + " halfSynced=" + halfSynced + " capEnforced=" + capEnforced + " reset=" + resetOk
                + " selector[idem=" + presetIdem + " moved=" + presetMoved
                + " restored=" + presetRestored + " listed=" + listed + " cycle=" + cycleOk + "]");

        // ---------------- REPORT：报告稳定（两次调用逐字节一致） ----------------
        String r1 = reg.report();
        String r2 = reg.report();
        ck("REPORT", r1.equals(r2) && r1.contains("systems=99"), "bytes=" + r1.length());   // 同 COUNT

        System.out.println("SYSTEMREG " + (fails == 0 ? "PASS" : "FAIL " + fails) + "  (12 properties)");
        if (GOLDEN_HASH == 0L) System.out.println("GOLDEN-BASELINE-TO-FILL " + h);
        if (fails > 0) System.exit(1);
    }

    /** a 是否为 b 的子序列（保序）。 */
    private static boolean isSubsequence(List<String> a, List<String> b) {
        int j = 0;
        for (int i = 0; i < b.size() && j < a.size(); i++) if (b.get(i).equals(a.get(j))) j++;
        return j == a.size();
    }
}
