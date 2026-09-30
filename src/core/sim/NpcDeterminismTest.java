package core.sim;

import core.agent.Npc;
import core.world.World;

/**
 * NPC 确定性门禁（批次 0 · NPC-SOC-WIRE · 第二道）：
 * 生成 NPC 后，同种子两遍运行 → world.hashState() 逐字节一致 且 NPC 内部态逐字节一致。
 *
 * 第一道（空列表指纹不变）由既有四门禁覆盖（NpcSystem 不写网格、simStream 不推进主 rng.state，
 * 故指纹与“无 NPC”基线 ad9e7b31ed47ec45 一致）。本测试补强“有 NPC 时”的自洽性。
 *
 * 运行：java -cp out core.sim.NpcDeterminismTest
 */
public final class NpcDeterminismTest {
    public static void main(String[] args) {
        final int SX = 64, SY = 40, SZ = 64, T = 240;
        final long SEED = 123456789L;

        Simulation a = new Simulation(SEED, SX, SY, SZ);
        Simulation b = new Simulation(SEED, SX, SY, SZ);

        // WALK（2026-09-16 新增）：村民每 tick 位移必须有上界。
        //   旧实现每 tick 取随机方向 ±0.3 格 + 回家引力 → 峰值 8.4 格/s（比玩家 4.5 还快），
        //   观感是“闪现 / 飞着走”。现在恒定 NPC_WALK = 0.09 格/tick（1.8 格/s）。
        float[] px = new float[16], pz = new float[16];
        int n0 = 0;
        float maxStep = 0f;
        for (int t = 0; t < T; t++) {
            a.world.tick();
            b.world.tick();
            if (a.world.npcs.size() != n0) {                  // 首 tick 生成 / 列表变动 → 重置采样基准
                n0 = Math.min(16, a.world.npcs.size());
                for (int i = 0; i < n0; i++) { px[i] = a.world.npcs.get(i).x; pz[i] = a.world.npcs.get(i).z; }
                continue;
            }
            for (int i = 0; i < n0; i++) {
                Npc npc = a.world.npcs.get(i);
                float d = (float) Math.sqrt((npc.x - px[i]) * (npc.x - px[i]) + (npc.z - pz[i]) * (npc.z - pz[i]));
                if (d > maxStep) maxStep = d;
                px[i] = npc.x; pz[i] = npc.z;
            }
        }
        boolean walkOk = maxStep <= 0.101f;                   // ≤0.1 格/tick = 2 格/s（玩家走路 4.5）

        // BODY_CLEAR（2026-09-16 新增）：村民 / 野兽的身体盒不得与 solid 方块相交。
        //   旧实现直接改 x/z（只夹世界边界）、高度只取**中心列**地表 → 身体一侧会埋进相邻的更高方块里，
        //   被深度测试裁掉 = 观感"模型一半没了 / 进墙里了"。这条锁的是**性质**而不是"能跑"：
        //   跑满 T tick 后，任何一个实体的身体盒都不允许和 solid 相交。
        int npcBuried = 0, beastBuried = 0;
        for (Npc npc : a.world.npcs) {
            if (a.world.solidBox(npc.x, npc.y + 0.02f, npc.z,
                    core.systems.NpcSystem.NPC_HW, core.systems.NpcSystem.NPC_H)) npcBuried++;
        }
        for (core.world.Beast beast : a.world.beasts) {
            if (a.world.solidBox(beast.x, beast.y + 0.02f, beast.z,
                    core.systems.BeastSystem.BODY_HW, core.systems.BeastSystem.BODY_H)) beastBuried++;
        }
        // 野兽那半条必须真的跑到：NpcDeterminismTest 的主世界 T=240 tick 内 beasts 可能为 0，
        //   于是另起一个世界，手动把 4 种原型摆到玩家四周（落点用"足迹最高地面"保证初始不埋），
        //   再跑 200 tick —— 期间它们会朝玩家穿越起伏地形，正好检验 step/settle 不埋人。
        Simulation bSim = new Simulation(SEED, SX, SY, SZ);
        for (int i = 0; i < 4; i++) {
            float ang = (float) (i * Math.PI / 2.0);
            float bx = bSim.world.player.x + (float) StrictMath.cos(ang) * 6f;
            float bz = bSim.world.player.z + (float) StrictMath.sin(ang) * 6f;
            float by = bSim.world.floorY(bx, bz, core.systems.BeastSystem.BODY_HW, (float) bSim.world.SY);
            bSim.world.beasts.add(core.world.Beast.make(i, bx, by, bz));
        }
        int beastTicks = 0;
        for (int t = 0; t < 200; t++) {
            bSim.world.tick();
            for (core.world.Beast bst : bSim.world.beasts) {
                beastTicks++;
                if (bSim.world.solidBox(bst.x, bst.y + 0.02f, bst.z,
                        core.systems.BeastSystem.BODY_HW, core.systems.BeastSystem.BODY_H)) beastBuried++;
            }
        }
        boolean bodyClearOk = npcBuried == 0 && beastBuried == 0 && beastTicks > 0;
        System.out.println("BODY_CLEAR  npcBuried=" + npcBuried + " beastBuried=" + beastBuried
                + "  beastSamples=" + beastTicks + " (want 0/0，且 beastSamples>0) —— 身体盒不与 solid 相交");

        long ha = a.world.hashState();
        long hb = b.world.hashState();
        boolean hashOk = ha == hb;

        // NPC 内部态签名（按列表序；spawn 确定性 → 顺序一致）
        String sa = npcSig(a.world);
        String sb = npcSig(b.world);
        boolean npcOk = sa.equals(sb);

        boolean pass = hashOk && npcOk && walkOk && bodyClearOk && !a.world.npcs.isEmpty();
        System.out.printf("NPC-DETERMINISM  hashA=%016x hashB=%016x  npcCount=%d%n",
                ha, hb, a.world.npcs.size());
        System.out.println("NPC signature A = " + sa);
        System.out.println("WALK  maxStepPerTick=" + maxStep
                + "  (want <= 0.101 = 2 格/s；玩家走路 4.5、冲刺 6.1)");
        System.out.println(pass ? "NPC-DETERMINISM PASS" : "NPC-DETERMINISM FAIL");
        if (!pass) System.exit(1);
    }

    private static String npcSig(World w) {
        StringBuilder sb = new StringBuilder();
        sb.append("n=").append(w.npcs.size()).append(";");
        for (Npc npc : w.npcs) {
            sb.append('[').append(npc.id)
              .append(",h=").append(fp(npc.body.hunger))
              .append(",th=").append(fp(npc.body.thirst))
              .append(",mood=").append(npc.social.mood)
              .append(",x=").append(fp(npc.x))
              .append(",z=").append(fp(npc.z))
              .append(']');
        }
        return sb.toString();
    }

    /** 截断到 2 位小数，避免浮点打印噪声干扰比较。 */
    private static float fp(float v) { return Math.round(v * 100f) / 100f; }
}
