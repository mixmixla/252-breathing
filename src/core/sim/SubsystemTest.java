package core.sim;

import core.content.Inventory;
import core.systems.AscensionSystem;
import core.systems.CaptureSystem;
import core.systems.HaulSystem;
import core.systems.HungerSystem;
import core.systems.System;
import core.systems.WireSystem;
import core.world.Beast;
import core.world.Facing;
import core.world.Blocks;
import core.world.Player;
import core.world.World;
import core.world.WorldConfig;

/**
 * SUBSYS 门禁 —— 「7 个空转的预设参数」真的驱动玩法了，且出厂默认下逐字节 no-op。
 *
 * <p><b>为什么需要它</b>：审计 C8 长期登记着 7 个内容键（{@code erosionRate} /
 * {@code ascensionThreshold} / {@code haulRate} / {@code hungerRate} / {@code orbItemCost} /
 * {@code hpThreshold} / {@code wireRange}）—— 它们在预设里被点名、却没有**任何系统**消费。
 * 「接线了」这件事必须被断言，否则它会悄悄退回原状（C11 就抓到过这种「解析器在、访问器在、
 * 运行时没人调」的死路径）。
 *
 * <p><b>两组断言</b>：
 * <ol>
 *   <li><b>DEFAULT_NOOP</b>：出厂默认值下跑 200 tick，7 个子系统的可观测状态全部保持"未发生"
 *       —— 这是四道仿真指纹能不变的前提；</li>
 *   <li><b>每参数一条"有牙"断言 + 负例</b>：把参数调离默认后必须真的改变世界
 *       （侵蚀产沙 / 饥饿掉血 / 电路通电 / 搬运入仓 / 收编野兽 / 飞升）；默认值下必须不发生。</li>
 *   <li><b>PRESET_DELIVERY</b>：内容层 → 玩法层的**投递路径**真的通 —— 预设启用的**模块**各自的
 *       params 要落到 {@code World.config}、预设自身 params 要覆盖模块值、**7 个键每个都能被某个
 *       出厂预设送出一个非出厂值**，且默认预设（breathing_world）叠加后仍 == 出厂值（零漂移）。</li>
 * </ol>
 *
 * <p><b>测试夹具的两个坑（都踩过）</b>：
 * ① 采样型系统（侵蚀/搬运）在小世界里命中率才够 —— 用 32x40x32 的小世界 + 密集矿层，
 *    否则 6 格矿散在 20 万格里命中率 0.07%，断言会"看起来像没接线"；
 * ② 火源必须放在**地表以下的坑道**里 —— {@code WeatherSystem} 降雨只浇"列顶"的火，
 *    放地表会被雨打灭 → 电路测试随机失败。
 */
public final class SubsystemTest {

    private static int fails = 0;

    /** 已断言条数（**计数而不是硬编码**：这个数字曾长期写死在汇总行里，加了用例就变成谎报）。 */
    private static int checked = 0;

    private static void ck(String tag, boolean cond, String detail) {
        checked++;
        java.lang.System.out.println(tag + " " + (cond ? "PASS" : "FAIL") + "  " + detail);
        if (!cond) fails++;
    }

    /** 标准世界（实体类断言用）。 */
    private static Simulation sim(long seed) { return new Simulation(seed, 64, 48, 64); }

    /** 小世界（采样型系统用：命中率足够高）。 */
    private static Simulation simSmall(long seed) { return new Simulation(seed, 32, 40, 32); }

    /**
     * 冻结世界：只保留 {@code wire}/{@code redstone} 两个系统在跑。
     *
     * <p><b>为什么测试需要它</b>：4b/4c 要跑 6 tick 才能让中继器采样到输入，而<b>地形系统会在这 6 tick 里
     * 改掉测试用的导线</b>（首次实测：链上第 13 格被地形演化换成 AIR → 断言莫名失败）。
     * 把无关系统关掉 → 用例测的是"电路模型"本身，不再受地形演化干扰（确定性从"碰运气"变成"必然"）。
     * 这也正是 {@code SystemRegistry.disable} 的用途：按名关闭单个系统。
     */
    private static void freezeExceptCircuit(Simulation s) { freezeExcept(s, "wire", "redstone"); }

    /**
     * 冻结世界，只保留 {@code keep} 里列出的系统在跑（其余一律 {@code disable}）。
     *
     * <p><b>为什么参数化</b>：第十批新增的 {@code HOPPER_PUSH} 用例测的是纯搬运（与电路无关），
     * 若照旧"只留 wire/redstone"就会把被测系统本身关掉。有了这个通用形态，
     * 每个用例都显式声明<b>它到底依赖哪几个系统</b> —— 依赖关系写在用例里，比写在 helper 里更诚实。
     */
    private static void freezeExcept(Simulation s, String... keep) {
        for (String n : s.world.registry.names()) {
            boolean k = false;
            for (String kk : keep) if (kk.equals(n)) { k = true; break; }
            if (!k) s.world.registry.disable(n);
        }
    }

    private static int countLayer(World w, int y, int idx) {
        int n = 0;
        for (int x = 0; x < w.SX; x++)
            for (int z = 0; z < w.SZ; z++)
                if (w.mat[x][y][z] == idx) n++;
        return n;
    }

    private static System sys(World w, String name) { return w.registry.get(name); }

    /** 容器/弹匣里的总件数（HOPPER_PUSH 断言用；null 视为 0）。 */
    private static int bagCount(java.util.LinkedHashMap<String, Integer> m) {
        if (m == null) return 0;
        int t = 0;
        for (Integer v : m.values()) t += v.intValue();
        return t;
    }

    public static void main(String[] args) {

        // ================= 1) DEFAULT_NOOP：出厂默认 → 7 个子系统全部不动作 =================
        Simulation a = sim(20260917L);
        Simulation b = sim(20260917L);
        a.run(200);
        b.run(200);
        boolean sameHash = a.world.hashState() == b.world.hashState();
        HungerSystem hs = (HungerSystem) sys(a.world, "hunger");
        WireSystem ws = (WireSystem) sys(a.world, "wire");
        HaulSystem ls = (HaulSystem) sys(a.world, "haul");
        CaptureSystem cs = (CaptureSystem) sys(a.world, "capture");
        AscensionSystem as = (AscensionSystem) sys(a.world, "ascension");
        boolean noop = hs != null && ws != null && ls != null && cs != null && as != null
                && a.world.config.erosionRate == WorldConfig.EROSION_RATE_DFLT
                && ws.nodeCount() == 0 && ws.poweredCount() == 0
                && ls.carried() == 0 && cs.tamedAlive() == 0 && cs.captured() == 0
                && !as.ascended() && a.player.hunger == Player.HUNGER_FULL;
        ck("DEFAULT_NOOP", noop && sameHash,
                "erosion=" + a.world.config.erosionRate + " wireNodes=" + ws.nodeCount()
                + " hauled=" + ls.carried() + " tamed=" + cs.tamedAlive()
                + " ascended=" + as.ascended() + " hunger=" + a.player.hunger
                + " sameHash=" + sameHash);

        // ================= 2) EROSION_RATE：rate=0 → 不侵蚀；rate 大 → 真的产沙 =================
        // 侵蚀已收编进反应表（assets/content/reactions/erosion.json，rate=erosionRate），
        // 故需在夹具里挂载反应书（裸 new 的世界反应是空表，求解器首行即返回）。
        final int BED = 20;
        core.content.ReactionBook erosionBook =
                core.content.ContentRegistry.load(new java.io.File("assets/content")).reactionBook();
        Simulation e0 = simSmall(777L);
        erosionBed(e0.world, BED);
        e0.world.reactions = erosionBook;
        e0.world.config.erosionRate = 0f;
        e0.run(200);
        int sand0 = countLayer(e0.world, BED, Blocks.SAND.index);
        Simulation e20 = simSmall(777L);
        erosionBed(e20.world, BED);
        e20.world.reactions = erosionBook;
        e20.world.config.erosionRate = 20f;
        e20.run(200);
        int sand20 = countLayer(e20.world, BED, Blocks.SAND.index);
        ck("EROSION_RATE", sand0 == 0 && sand20 > 0,
                "sand@y" + BED + " rate0=" + sand0 + " rate20=" + sand20
                + " (rate 必须真的改变侵蚀量；rate=0 必须一格都不产)");

        // ================= 3) HUNGER_RATE：默认不掉；启用后掉且会饿死 =================
        Simulation h = sim(4242L);
        h.world.config.hungerRate = 1f;
        float h0 = h.player.hunger;
        h.run(100);
        boolean drained = h.player.hunger < h0;
        Simulation hz = sim(4242L);
        hz.world.config.hungerRate = 0f;
        hz.run(100);
        boolean kept = hz.player.hunger == Player.HUNGER_FULL;
        Simulation hd = sim(99L);
        hd.world.config.hungerRate = 200f;          // 极快：几 tick 见底
        int hp0 = hd.player.hp;
        hd.run(120);                                 // 跨过 STARVE_PERIOD 若干次
        boolean starved = hd.player.hp < hp0;
        ck("HUNGER_RATE", drained && kept && starved,
                "hunger " + h0 + "->" + h.player.hunger + " rate0kept=" + kept
                + " hp " + hp0 + "->" + hd.player.hp);

        // ================= 4) WIRE_RANGE：0 → 不通电；>0 → 火源连通的灯具通电 =================
        Simulation w0 = simSmall(55L);
        placeLampTunnel(w0.world);
        w0.world.config.wireRange = 0f;
        w0.run(1);
        WireSystem ws0 = (WireSystem) sys(w0.world, "wire");
        boolean off = ws0.nodeCount() == 0 && ws0.poweredCount() == 0;   // range=0 → 完全不建网（首行返回）

        Simulation w8 = simSmall(55L);
        placeLampTunnel(w8.world);
        w8.world.config.wireRange = 8f;
        w8.run(1);
        WireSystem ws8 = (WireSystem) sys(w8.world, "wire");
        boolean on = ws8.sourceCount() >= 1 && ws8.poweredCount() >= 2;
        ck("WIRE_RANGE", off && on,
                "range0 powered=" + ws0.poweredCount() + " nodes=" + ws0.nodeCount()
                + " | range8 src=" + ws8.sourceCount() + " powered=" + ws8.poweredCount()
                + "/" + ws8.nodeCount());

        // ================= 4b) WIRE_STRENGTH：第九批的**信号强度模型**（衰减 / 超距灭 / 不穿墙）=================
        //  为什么必须加这一段：4) 的判据是 `poweredCount() >= 2`，它对"方环可达"与"沿导线衰减"**都成立**
        //  —— 也就是说，第九批把传播模型从"切比雪夫方环"换成"沿导线逐格衰减"时，旧判据**根本看不出区别**。
        //  把"模型"本身变成判据，这次改动才算有牙（否则等于"模型换了但没人审"）。
        //  口径：**紧邻电源的那一格导体也是满强度 15**（它是"被直接驱动"的种子），
        //        衰减从它之后开始 → 距离 d(=1..) 的强度 = 16 − d。
        Simulation sd = simSmall(99L);
        World dw = sd.world;
        freezeExceptCircuit(sd);                       // 冻结地形：本用例只测电路模型
        dw.config.wireRange = 20f;                     // 足够远，让"衰减"而非"距离上限"成为主因
        int sy = 20, sx0 = 1, sz0 = 1;
        for (int i = 0; i <= 22; i++) {
            dw.setBlock(sx0 + i, sy, sz0, Blocks.WIRE.index);
            dw.setBlock(sx0 + i, sy + 1, sz0, Blocks.AIR.index);
        }
        dw.setBlock(sx0, sy, sz0, Blocks.LEVER.index);          // 电源（满强度 15）
        dw.setMeta(sx0, sy, sz0, 1);
        sd.run(1);
        int s1 = dw.getMeta(sx0 + 1, sy, sz0);
        int s2 = dw.getMeta(sx0 + 2, sy, sz0);
        int s3 = dw.getMeta(sx0 + 3, sy, sz0);
        boolean decay = (s1 == 15 && s2 == 14 && s3 == 13);      // 紧邻=满，其后每格 −1
        boolean diesOut = dw.getMeta(sx0 + 15, sy, sz0) == 1 && dw.getMeta(sx0 + 16, sy, sz0) == 0;
        // 不穿墙：把中间一格换成石头 → 其后全断（旧"方环"模型下会照旧通电 —— 这正是模型差异）
        dw.setBlock(sx0 + 5, sy, sz0, Blocks.STONE.index);
        sd.run(1);
        boolean blocked = dw.getMeta(sx0 + 6, sy, sz0) == 0;
        ck("WIRE_STRENGTH", decay && diesOut && blocked,
                "s1..s3=" + s1 + "/" + s2 + "/" + s3 + " (want 15/14/13)"
                + " | 15格=1? " + dw.getMeta(sx0 + 15, sy, sz0) + " 16格=0? " + dw.getMeta(sx0 + 16, sy, sz0)
                + " | 断点后(6格)=" + dw.getMeta(sx0 + 6, sy, sz0) + " (want 0)");

        // ================= 4c) REPEAT_REGEN：中继器**再生**信号（把衰减链重新拉满）=================
        //  对照实验最强：**同一布局，只差一个中继器** → 末端一格 6 vs 0。
        //  （注意别把断言写成"中继器旁的导线 < 15"：紧邻电源的导体本来就是满强度种子，
        //    所以"中继器两侧那两格"必然都是 15 —— 要证明再生，必须看**更远的下游**。）
        int ry = 20, rx0 = 1, rz0 = 6;
        Simulation srA = simSmall(99L);
        World rwA = srA.world;
        freezeExceptCircuit(srA);
        rwA.config.wireRange = 20f;
        for (int i = 0; i <= 22; i++) {
            rwA.setBlock(rx0 + i, ry, rz0, Blocks.WIRE.index);
            rwA.setBlock(rx0 + i, ry + 1, rz0, Blocks.AIR.index);
        }
        rwA.setBlock(rx0, ry, rz0, Blocks.LEVER.index);
        rwA.setMeta(rx0, ry, rz0, 1);
        rwA.setBlock(rx0 + 10, ry, rz0, Blocks.REPEATER.index);   // 距离 10 处插一个中继器
        rwA.setFacing(rx0 + 10, ry, rz0, 0);                      // ★ 第十批：中继器是**定向**元件，必须朝 +X（链的方向）
        for (int t = 0; t < 6; t++) srA.run(1);                   // ≥4 tick 让它采样到输入
        int tailA = rwA.getMeta(rx0 + 20, ry, rz0);               // 下游末端：靠中继器再生才有电

        Simulation srB = simSmall(99L);
        World rwB = srB.world;
        freezeExceptCircuit(srB);
        rwB.config.wireRange = 20f;
        for (int i = 0; i <= 22; i++) {
            rwB.setBlock(rx0 + i, ry, rz0, Blocks.WIRE.index);
            rwB.setBlock(rx0 + i, ry + 1, rz0, Blocks.AIR.index);
        }
        rwB.setBlock(rx0, ry, rz0, Blocks.LEVER.index);
        rwB.setMeta(rx0, ry, rz0, 1);
        for (int t = 0; t < 6; t++) srB.run(1);
        int tailB = rwB.getMeta(rx0 + 20, ry, rz0);               // 无中继器：衰减已耗尽 → 0

        // 第三个对照（第十批新增，**方向敏感性的负例**）：同布局、同位置的中继器，但朝向拧成 +Z（垂直于链）。
        //  它的背面朝向 +Z，那里没信号 → 采不到输入 → 末端应为 0。
        //  这条断言是"朝向真的进了语义"的唯一证据：若不看朝向（第九批的旧行为），三种布局的末端都会是 6。
        Simulation srC = simSmall(99L);
        World rwC = srC.world;
        freezeExceptCircuit(srC);
        rwC.config.wireRange = 20f;
        for (int i = 0; i <= 22; i++) {
            rwC.setBlock(rx0 + i, ry, rz0, Blocks.WIRE.index);
            rwC.setBlock(rx0 + i, ry + 1, rz0, Blocks.AIR.index);
        }
        rwC.setBlock(rx0, ry, rz0, Blocks.LEVER.index);
        rwC.setMeta(rx0, ry, rz0, 1);
        rwC.setBlock(rx0 + 10, ry, rz0, Blocks.REPEATER.index);
        rwC.setFacing(rx0 + 10, ry, rz0, 4);                      // +Z：拧 90°
        for (int t = 0; t < 6; t++) srC.run(1);
        int tailC = rwC.getMeta(rx0 + 20, ry, rz0);

        boolean regen = rwA.getMeta(rx0 + 10, ry, rz0) == 1 && tailA == 6 && tailB == 0 && tailC == 0;
        ck("REPEAT_REGEN", regen,
                "对齐(+X) keeper@10=" + rwA.getMeta(rx0 + 10, ry, rz0) + " 末端(20)= " + tailA + " (want 1/6)"
                + " | 无中继器 末端(20)=" + tailB + " (want 0)"
                + " | 中继器拧成 +Z 末端(20)=" + tailC + " (want 0 —— 背面没信号，证明朝向真的进语义)");

        // ================= 4d) FACING：朝向状态层（第十批）=================
        //  「朝向"长在方块上"」这件事在门禁里验不了（要看 GL），但**状态层**可以三条性质证伪：
        //  ① 默认值确定（未设置 / 刚放下 / 换块之后都必须回默认）；
        //  ② 六个方向往返一致；
        //  ③ **写模式不冲掉朝向**（打包成一个 int 的代价就是"读改写"必须保留另一半）。
        Simulation sfc = simSmall(11L);
        World fw0 = sfc.world;
        int qx = 16, qy = 20, qz = 16;
        boolean facDefault = fw0.getFacing(qx, qy, qz) == Facing.DEFAULT;   // 从没设过
        fw0.setBlock(qx, qy, qz, Blocks.COMPARATOR.index);
        boolean facPlaced = fw0.getFacing(qx, qy, qz) == Facing.DEFAULT;    // 放下不带朝向 → 仍默认
        boolean facRound = true;
        for (int d = 0; d < Facing.COUNT; d++) {
            fw0.setFacing(qx, qy, qz, d);
            if (fw0.getFacing(qx, qy, qz) != d) facRound = false;
        }
        fw0.setFacing(qx, qy, qz, 0);
        fw0.setSubtractMode(qx, qy, qz, true);
        boolean facKeep = fw0.getFacing(qx, qy, qz) == 0 && fw0.isSubtractMode(qx, qy, qz);
        fw0.setBlock(qx, qy, qz, Blocks.AIR.index);                          // 换块 → 状态必须清
        boolean facCleared = fw0.getFacing(qx, qy, qz) == Facing.DEFAULT && !fw0.isSubtractMode(qx, qy, qz);

        boolean dirsOk = Facing.opposite(Facing.opposite(3)) == 3;           // 反向幂等
        for (int d = 0; d < Facing.COUNT; d++) if (Facing.opposite(d) == d) dirsOk = false;
        boolean lookOk = Facing.fromLook(0f, 1f, 0f) == 2 && Facing.fromLook(0f, -1f, 0f) == 3
                && Facing.fromLook(1f, 0f, 0f) == 0 && Facing.fromLook(-1f, 0f, 0f) == 1
                && Facing.fromLook(0f, 0f, 1f) == 4 && Facing.fromLook(0f, 0f, -1f) == 5
                && Facing.fromLook(0.3f, 0.9f, 0.2f) == 2                        // 取主轴（偏上 → +Y）
                && Facing.fromLook(0f, 0f, 0f) == Facing.DEFAULT;
        ck("FACING", facDefault && facPlaced && facRound && facKeep && facCleared && dirsOk && lookOk,
                "default=" + facDefault + " placed=" + facPlaced + " round6=" + facRound
                + " modeKeepsFacing=" + facKeep + " clearedOnReplace=" + facCleared
                + " oppositeInvolution=" + dirsOk + " fromLook=" + lookOk);

        // ================= 4e) COMPARATOR_AB：真 A/B 比较器（朝向切分输入口）=================
        //  布局：比较器朝 +X（正面=输出）。
        //    A 路 = 背面（-X）：一条 8 格导线链 → 到背面邻居时强度 8（弱信号）；
        //    B 路 = 侧面（+Z）：一个拉杆 → 15（强信号）；
        //    正面（+X）接导线（下游）。
        //  compare = max(A,B) = 15；subtract = max(0, A−B) = 0。
        //  ⚠️ 这两个数字必须**不同**，否则"真假 A/B"区分不出来 —— 这正是第九批做不到的事
        //    （那时输入取"邻域最高强度"，A/B 根本不存在，compare/subtract 会给出同一个结果）。
        Simulation sc = simSmall(31L);
        freezeExceptCircuit(sc);
        World cw = sc.world;
        cw.config.wireRange = 20f;
        int cy = 20, cz = 6, cx = 10;
        for (int i = 1; i <= 14; i++) {
            cw.setBlock(i, cy, cz, Blocks.AIR.index);
            cw.setBlock(i, cy + 1, cz, Blocks.AIR.index);
        }
        cw.setBlock(cx - 9, cy, cz, Blocks.LEVER.index);         // 链头（全向电源）
        cw.setMeta(cx - 9, cy, cz, 1);
        for (int i = cx - 8; i <= cx - 1; i++) cw.setBlock(i, cy, cz, Blocks.WIRE.index);
        cw.setBlock(cx + 1, cy, cz, Blocks.WIRE.index);          // 正面下游
        cw.setBlock(cx, cy, cz + 1, Blocks.LEVER.index);         // B 路（侧面）
        cw.setMeta(cx, cy, cz + 1, 1);
        cw.setBlock(cx, cy, cz, Blocks.COMPARATOR.index);
        cw.setFacing(cx, cy, cz, 0);                             // 输出朝 +X
        sc.run(2);

        int abA = core.systems.RedstoneLogicSystem.rearStrength(cw, cx, cy, cz, 0);
        int abB = core.systems.RedstoneLogicSystem.sideStrength(cw, cx, cy, cz, 0);
        int abCmp = cw.getMeta(cx, cy, cz);
        boolean abOk1 = abA == 8 && abB == 15 && abCmp == 15;       // compare = max(A,B)

        cw.setSubtractMode(cx, cy, cz, true);
        sc.run(1);
        int abSub = cw.getMeta(cx, cy, cz);
        boolean abOk2 = abSub == 0;                                 // subtract = max(0, 8−15)

        cw.setMeta(cx, cy, cz + 1, 0);                           // 关掉 B
        sc.run(1);
        int abOnlyA = cw.getMeta(cx, cy, cz);
        boolean abOk3 = core.systems.RedstoneLogicSystem.sideStrength(cw, cx, cy, cz, 0) == 0 && abOnlyA == 8;

        // 负例（本段最强的判据）：切回 compare，断掉 A、并在**正面**接一个开着的拉杆。
        //  正面是输出口，**不算输入** → out 必须 0。若实现把"六邻最强"当输入（第九批的旧做法），
        //  这里会得到 15 —— 于是这条断言正好把"真的分了 A/B"与"其实还是取邻域最大"区分开。
        cw.setSubtractMode(cx, cy, cz, false);
        cw.setBlock(cx - 1, cy, cz, Blocks.AIR.index);           // 断 A
        cw.setBlock(cx + 1, cy, cz, Blocks.LEVER.index);         // 正面伪装成电源
        cw.setMeta(cx + 1, cy, cz, 1);
        sc.run(2);
        boolean abOk4 = core.systems.RedstoneLogicSystem.rearStrength(cw, cx, cy, cz, 0) == 0
                && core.systems.RedstoneLogicSystem.sideStrength(cw, cx, cy, cz, 0) == 0
                && cw.getMeta(cx, cy, cz) == 0;
        ck("COMPARATOR_AB", abOk1 && abOk2 && abOk3 && abOk4,
                "A(back)=" + abA + " B(sides)=" + abB + " compare=" + abCmp + " (want 8/15/15)"
                + " | subtract=" + abSub + " (want 0)"
                + " | 去 B 后 forwardA=" + abOnlyA + " (want 8)"
                + " | 正面电源不算输入 out=" + cw.getMeta(cx, cy, cz) + " (want 0)");

        // ================= 4f) PISTON_DIR：活塞沿**正面**推动（含"有状态方块拒推"护栏）=================
        Simulation sp = simSmall(41L);
        freezeExceptCircuit(sp);
        World pw = sp.world;
        pw.config.wireRange = 20f;
        int py = 20, pz = 12;
        for (int i = 14; i <= 26; i++) {
            pw.setBlock(i, py, pz, Blocks.AIR.index);
            pw.setBlock(i, py + 1, pz, Blocks.AIR.index);
        }
        pw.setBlock(20, py, pz, Blocks.PISTON.index);
        pw.setFacing(20, py, pz, 0);                             // 朝 +X 推（第九批只能朝上）
        pw.setBlock(21, py, pz, Blocks.STONE.index);             // 被推的方块
        pw.setBlock(22, py, pz, Blocks.AIR.index);               // 目标位（空）
        pw.setBlock(19, py, pz, Blocks.LEVER.index);             // 全向电源 → 通电上升沿
        pw.setMeta(19, py, pz, 1);
        sp.run(3);
        boolean pushed = pw.getBlock(21, py, pz) == Blocks.AIR.index
                && pw.getBlock(22, py, pz) == Blocks.STONE.index;

        Simulation sp2 = simSmall(41L);
        freezeExceptCircuit(sp2);
        World pw2 = sp2.world;
        pw2.config.wireRange = 20f;
        for (int i = 14; i <= 26; i++) {
            pw2.setBlock(i, py, pz, Blocks.AIR.index);
            pw2.setBlock(i, py + 1, pz, Blocks.AIR.index);
        }
        pw2.setBlock(20, py, pz, Blocks.PISTON.index);
        pw2.setFacing(20, py, pz, 0);
        pw2.setBlock(21, py, pz, Blocks.CHEST.index);            // 带内容的箱子 → 必须拒推
        pw2.ensureChest(World.cellKey(21, py, pz)).put("coal", 5);
        pw2.setBlock(22, py, pz, Blocks.AIR.index);
        pw2.setBlock(19, py, pz, Blocks.LEVER.index);
        pw2.setMeta(19, py, pz, 1);
        sp2.run(3);
        java.util.LinkedHashMap<String, Integer> guardBag = pw2.chestOf(World.cellKey(21, py, pz));
        boolean guarded = pw2.getBlock(21, py, pz) == Blocks.CHEST.index
                && bagCount(guardBag) == 5
                && pw2.getBlock(22, py, pz) == Blocks.AIR.index;
        ck("PISTON_DIR", pushed && guarded,
                "横向推 21→22: " + pushed
                + " | 带内容箱子拒推(仍在原位+内容完整): " + guarded
                + " (facing=" + Facing.name(pw.getFacing(20, py, pz)) + ")");

        // ================= 4g) HOPPER_PUSH：漏斗「抽完就送」（沿正面输出，可串成传送带）=================
        Simulation sh = simSmall(51L);
        freezeExcept(sh, "hopper");                              // 本用例只依赖搬运，与电路无关
        World hw = sh.world;
        int hby = 20, hbz = 16;
        hw.setBlock(16, hby, hbz, Blocks.HOPPER.index);
        hw.setFacing(16, hby, hbz, 0);                             // 朝 +X 送
        hw.setBlock(16, hby + 1, hbz, Blocks.CHEST.index);         // 上方源（5 件）
        hw.ensureChest(World.cellKey(16, hby + 1, hbz)).put("coal", 5);
        hw.setBlock(17, hby, hbz, Blocks.CHEST.index);             // 正前方目标
        sh.run(40);                                              // 2 个节拍（每 20 tick 一次）
        int hopSrc = bagCount(hw.chestOf(World.cellKey(16, hby + 1, hbz)));
        int hopBag = bagCount(hw.chestOf(World.cellKey(16, hby, hbz)));
        int hopDst = bagCount(hw.chestOf(World.cellKey(17, hby, hbz)));
        boolean convey = hopSrc == 3 && hopBag == 0 && hopDst == 2;   // 每周期搬 1 件出链，自身不积压

        Simulation sh2 = simSmall(51L);
        freezeExcept(sh2, "hopper");
        World hw2 = sh2.world;
        hw2.setBlock(16, hby, hbz, Blocks.HOPPER.index);           // 不设朝向 → 默认 +Y（正好指向源格）
        hw2.setBlock(16, hby + 1, hbz, Blocks.CHEST.index);
        hw2.ensureChest(World.cellKey(16, hby + 1, hbz)).put("coal", 5);
        sh2.run(40);
        int loopBag = bagCount(hw2.chestOf(World.cellKey(16, hby, hbz)));
        int loopSrc = bagCount(hw2.chestOf(World.cellKey(16, hby + 1, hbz)));
        boolean noLoop = loopBag == 2 && loopSrc == 3;           // 护栏：不会把刚抽进来的推回原处
        // ④ 串联成**传送带**：h1(+X) → h2(+X) → 箱子。
        //  ⚠️ 这条是"朝向"真正的价值所在，也是本批第一个探针抓到的真 bug：
        //    第一版把"送出去"挂在"刚从上面抽到了"这个条件里 → 中间那个漏斗上方是空的（料从侧面来），
        //    于是永远不送 → 货全卡在中间（实测 src=1 h1=0 h2=3 dst=0）。修法是把 pull / push 解耦。
        //    门禁必须钉住它，否则这个 bug 会以"看起来一切正常"的形式回来。
        Simulation sh3 = simSmall(51L);
        freezeExcept(sh3, "hopper");
        World hw3 = sh3.world;
        hw3.setBlock(8, hby, hbz, Blocks.HOPPER.index); hw3.setFacing(8, hby, hbz, 0);
        hw3.setBlock(8, hby + 1, hbz, Blocks.CHEST.index);
        hw3.ensureChest(World.cellKey(8, hby + 1, hbz)).put("coal", 4);
        hw3.setBlock(9, hby, hbz, Blocks.HOPPER.index); hw3.setFacing(9, hby, hbz, 0);
        hw3.setBlock(10, hby, hbz, Blocks.CHEST.index);
        sh3.run(60);                                             // 3 个节拍
        boolean belt = bagCount(hw3.chestOf(World.cellKey(8, hby + 1, hbz))) == 1
                && bagCount(hw3.chestOf(World.cellKey(8, hby, hbz))) == 0
                && bagCount(hw3.chestOf(World.cellKey(9, hby, hbz))) == 0
                && bagCount(hw3.chestOf(World.cellKey(10, hby, hbz))) == 3;
        ck("HOPPER_PUSH", convey && noLoop && belt,
                "传送到前方: src=" + hopSrc + " hopper=" + hopBag + " dst=" + hopDst + " (want 3/0/2)"
                + " | 默认朝上的漏斗不回推: hopper=" + loopBag + " src=" + loopSrc + " (want 2/3)"
                + " | 两漏斗串联成带 末端=" + bagCount(hw3.chestOf(World.cellKey(10, hby, hbz)))
                + " 中间=" + bagCount(hw3.chestOf(World.cellKey(8, hby, hbz))) + "/"
                + bagCount(hw3.chestOf(World.cellKey(9, hby, hbz))) + " (want 3 / 0/0)");

        // ================= 4h) OBSERVER：观察者「记忆 + 1 tick 脉冲」（第十一批）=================
        //  它是本作第一个有**记忆**的元件（meta = 记忆<<1 | 脉冲）。四条可证伪的性质：
        //  ① 刚放下（无基线）不报；② 被看那格**变了**才报；③ 脉冲只持续 **1 tick**；
        //  ④ **正面**的变化不报（方向敏感性 —— 否则"看背面"就是句空话）。
        Simulation sos = simSmall(61L);
        freezeExceptCircuit(sos);
        World obw = sos.world;
        obw.config.wireRange = 20f;
        int obx = 16, oby = 22, obz = 24;
        for (int i = obx - 2; i <= obx + 2; i++) {
            obw.setBlock(i, oby, obz, Blocks.AIR.index);
            obw.setBlock(i, oby + 1, obz, Blocks.AIR.index);
        }
        obw.setBlock(obx, oby, obz, Blocks.OBSERVER.index);
        obw.setFacing(obx, oby, obz, 0);                       // 朝 +X（输出）→ 看 -X
        obw.setBlock(obx - 1, oby, obz, Blocks.LEVER.index);   // 被观察的方块
        obw.setBlock(obx + 1, oby, obz, Blocks.WIRE.index);    // 正面下游（验证脉冲真的供了电）
        WireSystem obWs = (WireSystem) sys(obw, "wire");

        sos.run(2);                                            // 建立基线（首次记录但不报）
        int obPulse0 = core.systems.RedstoneLogicSystem.sourceStrength(obw, obx, oby, obz);
        obw.setMeta(obx - 1, oby, obz, 1);                     // ★ 被看的那格变了（拉杆打开）
        sos.run(1);
        int obPulse1 = core.systems.RedstoneLogicSystem.sourceStrength(obw, obx, oby, obz);
        sos.run(1);                                            // 下一 tick：WireSystem 读到这一位
        int obWire1 = obWs.strengthAt(obx + 1, oby, obz);
        int obPulse2 = core.systems.RedstoneLogicSystem.sourceStrength(obw, obx, oby, obz);
        sos.run(1);
        int obWire2 = obWs.strengthAt(obx + 1, oby, obz);      // 脉冲已清 → 导线也灭
        boolean obOk = obPulse0 == 0 && obPulse1 == WireSystem.MAX_SIGNAL && obPulse2 == 0
                && obWire1 == WireSystem.MAX_SIGNAL && obWire2 == 0;

        // ④ 方向负例：改**正面**那格（把下游导线换成石头）→ 观察者不该有任何反应。
        obw.setBlock(obx + 1, oby, obz, Blocks.STONE.index);
        sos.run(2);
        boolean obNoFront = core.systems.RedstoneLogicSystem.sourceStrength(obw, obx, oby, obz) == 0;
        ck("OBSERVER", obOk && obNoFront,
                "无基线不报=" + obPulse0 + " 变化后脉冲=" + obPulse1 + "/" + WireSystem.MAX_SIGNAL
                + " 下一 tick 已清=" + obPulse2
                + " | 下游导线 " + obWire1 + "->" + obWire2 + " (want MAX->0，脉冲只 1 tick)"
                + " | 改正面不报=" + obNoFront);

        // ================= 4i) COMPARATOR_FULL：比较器读**容器满度**（第十一批）=================
        //  MC 语义：比较器对着容器时，读的是它的"满度"（1..15）而不是 0/1。
        //  关键的**负例**在末尾：容器**不能**点亮中继器（否则"箱子越满 → 中继器越亮"这种错语义会溜进来）——
        //  这正是把"数字读"与"模拟读"分成两个函数的理由。
        Simulation cfs = simSmall(71L);
        freezeExceptCircuit(cfs);
        World cfw = cfs.world;
        cfw.config.wireRange = 20f;
        int cfy = 24, cfz = 28, cfx = 16;
        for (int i = cfx - 2; i <= cfx + 2; i++) {
            cfw.setBlock(i, cfy, cfz, Blocks.AIR.index);
            cfw.setBlock(i, cfy + 1, cfz, Blocks.AIR.index);
        }
        cfw.setBlock(cfx, cfy, cfz, Blocks.COMPARATOR.index);
        cfw.setFacing(cfx, cfy, cfz, 0);                       // 输出朝 +X → A 路在背面（-X）
        cfw.setBlock(cfx - 1, cfy, cfz, Blocks.CHEST.index);   // 背面：容器
        long cfCell = World.cellKey(cfx - 1, cfy, cfz);
        cfs.run(2);
        int cfEmpty = cfw.getMeta(cfx, cfy, cfz);              // 空箱 → A=0 → out=0

        cfw.ensureChest(cfCell).put("coal", 1);
        cfs.run(1);
        int cfOne = core.systems.RedstoneLogicSystem.containerFullness(cfw, cfx - 1, cfy, cfz);
        int cfOutOne = cfw.getMeta(cfx, cfy, cfz);             // 1 件 → 满度 1

        cfw.ensureChest(cfCell).put("coal", core.systems.RedstoneLogicSystem.CONTAINER_CAPACITY);
        cfs.run(1);
        int cfFull = cfw.getMeta(cfx, cfy, cfz);               // 满箱 → 15

        cfw.chestStore.remove(cfCell);
        cfs.run(1);
        int cfGone = cfw.getMeta(cfx, cfy, cfz);               // 取空 → 回到 0

        // 负例：同样的布局，把比较器换成**中继器** → 容器不该给它任何输入（数字读不认容器）
        Simulation nrs = simSmall(71L);
        freezeExceptCircuit(nrs);
        World nrw = nrs.world;
        nrw.config.wireRange = 20f;
        for (int i = cfx - 2; i <= cfx + 2; i++) {
            nrw.setBlock(i, cfy, cfz, Blocks.AIR.index);
            nrw.setBlock(i, cfy + 1, cfz, Blocks.AIR.index);
        }
        nrw.setBlock(cfx, cfy, cfz, Blocks.REPEATER.index);
        nrw.setFacing(cfx, cfy, cfz, 0);
        nrw.setBlock(cfx - 1, cfy, cfz, Blocks.CHEST.index);
        nrw.ensureChest(World.cellKey(cfx - 1, cfy, cfz)).put("coal", 512);   // 装到 1/3 满
        nrs.run(8);                                            // 中继器每 4 tick 采一次样
        boolean nrQuiet = core.systems.RedstoneLogicSystem.rearStrength(nrw, cfx, cfy, cfz, 0) == 0
                && nrw.getMeta(cfx, cfy, cfz) == 0;

        ck("COMPARATOR_FULL", cfEmpty == 0 && cfOne == 1 && cfFull == WireSystem.MAX_SIGNAL
                        && cfGone == 0 && nrQuiet,
                "空箱 out=" + cfEmpty + " (want 0) | 1 件 满度=" + cfOne + " out=" + cfOutOne
                + " (want 1/1) | 满箱 out=" + cfFull + " (want 15) | 取空后 out=" + cfGone + " (want 0)"
                + " | 负例：中继器读容器=" + (nrQuiet ? "无输入(正确)" : "★被容器点亮了(错)"));

        // ================= 5) HAUL_RATE：无电/rate=0 → 不搬；有电 + 有矿 → 真搬 =================
        Simulation l0 = simSmall(66L);
        placeLampTunnel(l0.world);
        placeOreBed(l0.world);
        l0.world.config.wireRange = 8f;
        l0.world.config.haulRate = 0f;              // 有电但 rate=0
        l0.run(120);
        boolean idle = ((HaulSystem) sys(l0.world, "haul")).carried() == 0;

        Simulation l1 = simSmall(66L);
        placeLampTunnel(l1.world);
        placeOreBed(l1.world);
        l1.world.config.wireRange = 8f;
        l1.world.config.haulRate = 4f;
        float bm0 = l1.world.builtMass;
        l1.run(120);
        HaulSystem ls1 = (HaulSystem) sys(l1.world, "haul");
        boolean hauled = ls1.carried() > 0 && l1.world.builtMass > bm0;

        Simulation l2 = simSmall(66L);
        placeLampTunnel(l2.world);
        placeOreBed(l2.world);
        l2.world.config.wireRange = 0f;             // 无电
        l2.world.config.haulRate = 4f;
        l2.run(120);
        boolean noPower = ((HaulSystem) sys(l2.world, "haul")).carried() == 0;
        ck("HAUL_RATE", idle && hauled && noPower,
                "rate0=" + idle + " hauled=" + ls1.carried() + " builtMass+="
                + (l1.world.builtMass - bm0) + " noPower=" + noPower);

        // ================= 6) HP_THRESHOLD / ORB_ITEM_COST：两段判定 + 负例 =================
        Simulation c = sim(88L);
        c.world.config.hpThreshold = 0.3f;
        c.world.config.orbItemCost = 2;
        CaptureSystem cap = (CaptureSystem) sys(c.world, "capture");
        Inventory inv = new Inventory(null);
        inv.add(CaptureSystem.ORB_ITEM, 5);

        Beast full = spawnTestBeast(c.world, 0f);
        boolean rejectFull = !cap.tryCapture(c.world, inv);          // 满血 → 拒绝
        c.world.beasts.remove(full);                                 // 移走，免得"最近目标"仍是它

        Beast hurt = spawnTestBeast(c.world, 0f);
        hurt.hp = (int) (hurt.maxHp * 0.2f);                          // 削到 20% <= 30%
        boolean okCapture = cap.tryCapture(c.world, inv);
        boolean orbSpent = inv.countOf(CaptureSystem.ORB_ITEM) == 3;  // 5 - 2
        boolean tamedFlag = hurt.tamed && !full.tamed;

        Inventory poor = new Inventory(null);                         // 没球 → 拒绝
        Beast hurt2 = spawnTestBeast(c.world, 0f);
        hurt2.hp = 1;
        boolean rejectPoor = !cap.tryCapture(c.world, poor);
        c.world.beasts.remove(hurt2);                                 // 清场：只留已驯服那只（否则它会打玩家）

        ck("CAPTURE_GATE", rejectFull && okCapture && orbSpent && tamedFlag && rejectPoor,
                "rejectFull=" + rejectFull + " captured=" + okCapture + " orbs="
                + inv.countOf(CaptureSystem.ORB_ITEM) + " tamed=" + tamedFlag
                + " rejectNoOrb=" + rejectPoor);

        // 已驯服不再攻击玩家（BeastSystem 跳过）：跑若干 tick，玩家血量不掉
        c.world.player.hp = c.world.player.maxHp;
        int php = c.world.player.hp;
        c.run(120);
        ck("TAMED_PASSIVE", c.world.player.hp == php && cap.tamedAlive() >= 1,
                "hp=" + php + "->" + c.world.player.hp + " tamedAlive=" + cap.tamedAlive());

        // ================= 7) ASCENSION_THRESHOLD：默认不可达；调低后真飞升 =================
        Simulation a1 = sim(11L);
        Player p1 = a1.player;
        p1.souls = 1000;
        int mh1 = p1.maxHp;                          // 满装 maxHp 不是 30（护甲会加成）→ 不能硬编码
        a1.run(5);                                   // 默认阈值 1e9 → 不飞升
        AscensionSystem as1 = (AscensionSystem) sys(a1.world, "ascension");
        boolean notAscended = !as1.ascended() && p1.maxHp == mh1;

        Simulation a2 = sim(11L);
        a2.world.config.ascensionThreshold = 60f;
        Player p2 = a2.player;
        p2.souls = 100;
        int mh0 = p2.maxHp;
        a2.run(5);
        AscensionSystem as2 = (AscensionSystem) sys(a2.world, "ascension");
        boolean ascended = as2.ascended() && p2.souls == 40
                && p2.maxHp == mh0 + AscensionSystem.ASCEND_HP_BONUS;
        ck("ASCENSION", notAscended && ascended,
                "default=" + as1.ascended() + " | th60 souls 100->" + p2.souls
                + " maxHp " + mh0 + "->" + p2.maxHp);

        // ================= 8) 参数复位：applyPreset(null) 必须把 7 个参数拉回出厂 =================
        Simulation r = sim(3L);
        r.world.config.erosionRate = 9f;
        r.world.config.wireRange = 9f;
        r.world.config.hungerRate = 9f;
        r.world.config.haulRate = 9f;
        r.world.config.hpThreshold = 0.1f;
        r.world.config.orbItemCost = 9;
        r.world.config.ascensionThreshold = 9f;
        r.applyPreset(null);
        WorldConfig cfg = r.world.config;
        boolean reset = cfg.erosionRate == WorldConfig.EROSION_RATE_DFLT
                && cfg.ascensionThreshold == WorldConfig.ASCENSION_THRESHOLD_DFLT
                && cfg.haulRate == WorldConfig.HAUL_RATE_DFLT
                && cfg.hungerRate == WorldConfig.HUNGER_RATE_DFLT
                && cfg.orbItemCost == WorldConfig.ORB_ITEM_COST_DFLT
                && cfg.hpThreshold == WorldConfig.HP_THRESHOLD_DFLT
                && cfg.wireRange == WorldConfig.WIRE_RANGE_DFLT;
        ck("PARAM_RESET", reset, "after applyPreset(null) all 7 back to factory=" + reset);

        // ============ 9) 内容层 → 玩法层 的**投递路径**（模块参数 + 预设覆盖 + 每键可达）============
        // 本轮补的：模块 params 此前**只有解析、没有消费者**（ContentRegistry.modules() 全仓零生产调用）
        // → 只在模块里出现的键（wireRange / hungerRate / orbItemCost）任何预设都送不到 World.config。
        // 表现为"参数接线了但永远送不到"：palworld_like 写了 haulRate 1.5，但 wireRange 恒 0
        // → HaulSystem 永远没电 → 无人搬运从不发生。
        // **「消费端接线了」不等于「送得到」—— 必须有端到端断言。**
        core.content.ContentRegistry creg =
                core.content.ContentRegistry.load(new java.io.File("assets/content"));
        Simulation dv = sim(5L);
        dv.applyPreset(creg.preset("breathing_world"));
        WorldConfig c0 = dv.world.config;
        boolean defaultFactory = c0.erosionRate == WorldConfig.EROSION_RATE_DFLT
                && c0.ascensionThreshold == WorldConfig.ASCENSION_THRESHOLD_DFLT
                && c0.haulRate == WorldConfig.HAUL_RATE_DFLT
                && c0.hungerRate == WorldConfig.HUNGER_RATE_DFLT
                && c0.orbItemCost == WorldConfig.ORB_ITEM_COST_DFLT
                && c0.hpThreshold == WorldConfig.HP_THRESHOLD_DFLT
                && c0.wireRange == WorldConfig.WIRE_RANGE_DFLT;
        // palworld 启用 survival/emergence/capture/automation 四个模块；它只覆盖 haulRate/hpThreshold，
        // 其余（wireRange 8 / hungerRate 1 / orbItemCost 1）应由**模块参数**送进来。
        dv.applyPreset(creg.preset("palworld_like"));
        WorldConfig c1 = dv.world.config;
        boolean modDelivered = c1.wireRange == 8f && c1.hungerRate == 1f && c1.orbItemCost == 1;
        boolean presetWins = c1.haulRate == 1.5f && c1.hpThreshold == 0.35f;
        // 每个键都必须能被**某个出厂预设**送出一个非出厂值（否则等于送不到）
        boolean[] hit = new boolean[7];
        for (core.content.Preset p : creg.presets()) {
            dv.applyPreset(p);
            WorldConfig wc = dv.world.config;      // 注意：main 里已有一个名为 c 的 Simulation
            hit[0] |= wc.erosionRate != WorldConfig.EROSION_RATE_DFLT;
            hit[1] |= wc.ascensionThreshold != WorldConfig.ASCENSION_THRESHOLD_DFLT;
            hit[2] |= wc.haulRate != WorldConfig.HAUL_RATE_DFLT;
            hit[3] |= wc.hungerRate != WorldConfig.HUNGER_RATE_DFLT;
            hit[4] |= wc.orbItemCost != WorldConfig.ORB_ITEM_COST_DFLT;
            hit[5] |= wc.hpThreshold != WorldConfig.HP_THRESHOLD_DFLT;
            hit[6] |= wc.wireRange != WorldConfig.WIRE_RANGE_DFLT;
        }
        int reach = 0;
        for (int i = 0; i < hit.length; i++) if (hit[i]) reach++;
        ck("PRESET_DELIVERY", defaultFactory && modDelivered && presetWins && reach == 7,
                "defaultIsFactory=" + defaultFactory + " modParamsDelivered=" + modDelivered
                + " presetOverridesModule=" + presetWins + " keysReachable=" + reach + "/7");

        java.lang.System.out.println("SUBSYS " + (fails == 0 ? "PASS" : "FAIL " + fails) + "  (" + checked + " properties)");
        if (fails > 0) java.lang.System.exit(1);
    }

    // ---------------- 测试夹具 ----------------

    /**
     * 在地表以下的坑道里放「发电炉（煤矿）+ 一排灯具」。
     *
     * <p>电源用**煤矿**而不是火：{@code FireSpreadSystem} 每 tick 有 50% 概率把火熄灭，
     * 火源平均活不过 2 tick（第一版就是这么失败的）。
     * 放地下则是为了避开 {@code WeatherSystem} 的降雨（只浇列顶），保证夹具长稳。
     */
    private static void placeLampTunnel(World w) {
        int x = 3, z = 3;
        int y = w.surfaceY[x][z] - 3;
        if (y < 1) y = 1;
        for (int i = 0; i <= 6; i++) w.setBlock(x + i, y, z, Blocks.AIR.index);   // 挖巷道
        w.setBlock(x, y, z, Blocks.COAL_ORE.index);                                // 煤矿 = 发电炉
        for (int i = 1; i <= 5; i++) w.setBlock(x + i, y, z, Blocks.LAMP.index);   // 导线节点
    }

    /** 在地下铺一层密集矿石（采样型搬运系统的货源；稀疏 6 格在小世界里命中率仍不足）。 */
    private static void placeOreBed(World w) {
        for (int x = 0; x < w.SX; x++)
            for (int z = 0; z < w.SZ; z++)
                for (int y = 5; y <= 9; y++)
                    w.setBlock(x, y, z, ((x + z + y) % 2 == 0)
                            ? Blocks.COAL_ORE.index : Blocks.IRON_ORE.index);
    }

    /** 在水下铺一层 DIRT（侵蚀床）：y 层 DIRT、y+1 层 WATER。
     *  并把水层正上方的方块清成空气——反应表只在「头顶是空气的地表方块」触发，
     *  故必须保证水暴露，否则侵蚀被收编进反应表后不触发（地表近似收编的代价）。 */
    private static void erosionBed(World w, int y) {
        for (int x = 0; x < w.SX; x++)
            for (int z = 0; z < w.SZ; z++) {
                w.setBlock(x, y, z, Blocks.DIRT.index);
                if (y + 1 < w.SY) w.setBlock(x, y + 1, z, Blocks.WATER.index);
                if (y + 2 < w.SY) w.setBlock(x, y + 2, z, Blocks.AIR.index);
                if (y + 3 < w.SY) w.setBlock(x, y + 3, z, Blocks.AIR.index);
            }
    }

    /** 在玩家附近造一只可控敌兵（type 0，非守卫）。 */
    private static Beast spawnTestBeast(World w, float dist) {
        Beast b = Beast.make(0, w.player.x + dist + 1f, w.player.y, w.player.z);
        w.beasts.add(b);
        return b;
    }
}
