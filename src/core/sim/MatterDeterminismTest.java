package core.sim;

import core.systems.MatterSystem;
import core.world.Blocks;
import core.world.Matter;
import core.world.World;

/**
 * 物质·能量域 确定性 + 可达性 门禁（批次 5 · 抽象系统，<b>第 16 道</b>）。
 *
 * <p>接入 {@link core.systems.MatterSystem}（phases 物相 / entropy 能量梯级 / fusion 聚变 /
 * deepsea 深海；均为开放标量字段）后，本门禁断言四件事：
 * <ol>
 *   <li><b>确定性</b>：同种子两遍跑 → {@code world.hashState()} 与
 *       {@code world.matter.snapshot()} 均逐字节一致；</li>
 *   <li><b>指纹隔离</b>：直接改写 {@code matter} 的字段后 {@code hashState()} 不变
 *       —— 证明该层确实<b>不在</b>确定性指纹内（本层的存在不改变任何已定基线）；</li>
 *   <li><b>不耗随机</b>：单独调用一次 {@code MatterSystem.update()} 后 {@code rng.state()} 不变
 *       —— 证明"加一个系统"不会挪用主 RNG，故四道零漂移基线指纹不受影响；</li>
 *   <li><b>可达性（防死代码）</b>：物相真的发生过相变（≥2 种物相被观测到），且 12 个字段
 *       全部为非死值（alloy/exergy/entropy/supply/work/reactors/radiation/subs/pressure/mineral/
 *       pollution/colonies 均 &gt; 0）—— 一旦某个字段恒为 0，说明驱动映射失效，门禁必须翻红。</li>
 * </ol>
 *
 * <p><b>⚠️ 第三十五批（可达性必须跑在"有代表性的世界"上）</b>：本门禁原来把 ①②③④ 全放在
 * 64×40×64 的世界里。但 `deepsea` 的驱动是<b>全局水体格数</b>（{@code SEA_MIN_WATER}），
 * 而水域占比 ≈ <b>0.5% 且随世界面积线性</b> ⇒ 64×40×64（4096 列）里只有 ~21 格水，
 * 阈值 50 成了<b>刀刃</b>：任何全局改动（例如给 ASH 加厚度上限）都会把它推过/推回。
 * 实测（{@code core.sim.MatterWaterProbe}，seed 987654321 / 1200 tick）：
 * <pre>
 *   64x40x64    water= 21  → 不可达（原门禁：FAIL）
 *   96x48x96    water= 39  → 不可达
 *  128x56x128   water= 92  → 可达
 *  160x64x160   water=195  → 可达
 *  160x112x160  water=1388 → 可达（**游戏实际尺寸**）
 * </pre>
 * 故本批把<b>① 确定性</b>留在 64×40×64（与其余三道指纹门禁同尺寸，且 T 由 1200 降到 240 ⇒ 更快），
 * 把<b>④ 可达性</b>移到**与游戏同尺寸**的世界（160×112×160）上判定 —— 语义更对（"deepsea 在游戏里活着"），
 * 且不"挪门柱"（阈值仍是 50）。
 *
 * <p>注：四道零漂移基线门禁（DETERMINISM/ZERO-DRIFT/PHYSICS/STREAMING）的指纹在接入本层前后
 * <b>逐字节不变</b>，那才是"零漂移"的权威证据；本门禁负责证明本层自身确定且不空转。
 * 本门禁的哈希是<b>自比对</b>（绝不与存储常量比）⇒ 换世界尺寸/时长<b>不需要重锁任何基线</b>。
 *
 * 运行：java -cp out core.sim.MatterDeterminismTest
 */
public final class MatterDeterminismTest {
    public static void main(String[] args) {
        // ---- ①②③ 确定性 / 指纹隔离 / 不耗随机：小世界（与其余三道指纹门禁同尺寸）----
        final int SX = 64, SY = 40, SZ = 64, T = 240;
        final long SEED = 987654321L;

        Simulation a = new Simulation(SEED, SX, SY, SZ);
        Simulation b = new Simulation(SEED, SX, SY, SZ);
        for (int t = 0; t < T; t++) {
            a.world.tick();
            b.world.tick();
        }

        World wa = a.world, wb = b.world;
        boolean detOk = wa.hashState() == wb.hashState();
        boolean layerOk = wa.matter.snapshot().equals(wb.matter.snapshot());

        // 指纹隔离：改写 matter 字段 → hashState 不变（该层不在指纹内）
        Matter m0 = wa.matter;
        long h0 = wa.hashState();
        float e0 = m0.entropy, mi0 = m0.mineral, c0 = m0.colonies;
        int ph0 = m0.phase;
        m0.entropy = 999f; m0.mineral = 12345f; m0.colonies = 7f; m0.phase = Matter.GAS;
        long h1 = wa.hashState();
        m0.entropy = e0; m0.mineral = mi0; m0.colonies = c0; m0.phase = ph0;
        boolean isolated = (h0 == h1);

        // 不耗随机：单跑一次 update，rng.state() 不变
        long r0 = wa.rng.state();
        new core.systems.MatterSystem().update(wa, wa.simStream("MatterSystem"));
        long r1 = wa.rng.state();
        boolean noRng = (r0 == r1);

        // ---- ④ 可达性：必须在**有代表性的世界**上判定（见类注释的尺寸表）----
        final int RX = 160, RY = 112, RZ = 160, RT = 1200;
        Simulation r = new Simulation(SEED, RX, RY, RZ);
        boolean[] seenPhase = new boolean[3];
        for (int t = 0; t < RT; t++) {
            r.world.tick();
            seenPhase[r.world.matter.phase] = true;
        }
        int phasesSeen = (seenPhase[0] ? 1 : 0) + (seenPhase[1] ? 1 : 0) + (seenPhase[2] ? 1 : 0);

        Matter m = r.world.matter;
        boolean transOk = m.transitions >= 1 && phasesSeen >= 2;
        boolean liveOk = m.alloy > 0f && m.exergy > 0f && m.entropy > 0f && m.supply > 0f && m.work > 0f
                && m.reactors >= 1 && m.radiation > 0f
                && m.subs >= 1 && m.pressure > 0f && m.mineral > 0f && m.pollution > 0f && m.colonies > 0f;

        boolean pass = detOk && layerOk && transOk && liveOk && isolated && noRng;

        System.out.printf("MATTER-DETERMINISM  hashA=%016x hashB=%016x  npcs=%d%n",
                wa.hashState(), wb.hashState(), wa.npcs.size());
        System.out.println("MATTER A = " + m.snapshot());
        System.out.println("HUD = " + m.asciiSummary() + "   |   " + m.deepSummary());
        System.out.printf("MATTER-GATE  det=%b layer=%b trans=%d phasesSeen=%d live=%b isolated=%b noRng=%b%n",
                detOk, layerOk, m.transitions, phasesSeen, liveOk, isolated, noRng);
        // 可达性世界的驱动量（自证"为什么可达"：低于门就是真 FAIL，不是尺寸假象）
        int rWater = r.world.cellsOfType(Blocks.WATER.index).size();
        System.out.printf("MATTER-REACH  world=%dx%dx%d t=%d  water=%d/%d %s  research=%.2f/%.0f %s%n",
                RX, RY, RZ, RT, rWater, MatterSystem.SEA_MIN_WATER,
                rWater >= MatterSystem.SEA_MIN_WATER ? "[OK]" : "[BELOW]",
                r.world.civ.research, 60f, r.world.civ.research >= 60f ? "[OK]" : "[BELOW]");
        // 诊断：pollution 的驱动是 `0.03*civ.power - 0.05` → 只有 civ.power > 1.667 才净增。
        // 把 power 打出来，才能判断 live 失败是"经济真的垮了"还是"卡在阈值刀刃上"。
        int liveN = 0;
        for (core.agent.Npc npc : r.world.npcs) if (!npc.dead()) liveN++;
        System.out.printf("MATTER-DIAG  civ.power=%.4f aliveNpc=%d prosperity=%d research=%.1f iron=%.2f%n",
                r.world.civ.power, liveN, r.world.prosperity, r.world.civ.research, r.world.civ.iron);
        System.out.println(pass ? "MATTER-DETERMINISM PASS" : "MATTER-DETERMINISM FAIL");
        if (!pass) System.exit(1);
    }
}
