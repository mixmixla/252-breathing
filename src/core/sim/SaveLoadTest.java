package core.sim;

import core.world.World;
import core.world.Player;
import core.world.Blocks;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

/**
 * 存档/读档往返测试（GAMEPLAY-SAVE, gate STREAMING）。
 *
 * 流程：建世界 → 编辑（窗口内）→ tick → 流式平移（让编辑离开窗口被快照进 chunkEdits）
 *      → 新窗口再编辑 → 再 tick → 算 hashState → save → load 到新世界 → 再算 hashState。
 * 断言：loaded.hashState() == saved.hashState()（证明存档无损 + 确定性继续）。
 */
public class SaveLoadTest {
    public static void main(String[] args) throws Exception {
        final long SEED = 0x9E3779B9L;
        World w1 = new World(SEED, 96, 48, 96);
        w1.player = new Player();
        w1.player.x = 40f; w1.player.z = 40f;
        w1.player.y = Player.spawnY(w1);

        // 1) 当前窗口内编辑
        w1.editBlock(40, 20, 40, Blocks.STONE.index);
        w1.addSkill("_blueprint_stone");
        w1.addSkill("_blueprint_wood");
        w1.recordMemory("village founded");
        w1.recordMemory("first wall built");
        w1.prosperity = 4;
        w1.builtMass = 1.5f;
        w1.player.setIntent(Player.Intent.repel());
        for (int i = 0; i < 4; i++) w1.tick();

        // 2) 流式平移：窗口移到 (10,10)，原编辑块离开窗口 → 被快照进 chunkEdits（真实持久化路径）
        w1.streamTo(10, 10);

        // 3) 新窗口内再编辑
        w1.editBlock(40, 25, 40, Blocks.WOOD.index);
        w1.player.setIntent(Player.Intent.idle());
        for (int i = 0; i < 4; i++) w1.tick();

        long h1 = w1.hashState();

        // 4) 存档 → 字节 → 读档到全新世界
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        w1.save(baos);
        byte[] data = baos.toByteArray();

        World w2 = World.load(new ByteArrayInputStream(data));
        long h2 = w2.hashState();

        // 立项 F 增强：读档后两世界各再演化 300 tick → hash 仍逐字节一致（证明恢复的是完整演化状态）
        for (int i = 0; i < 300; i++) { w1.tick(); w2.tick(); }
        long h3 = w1.hashState();
        long h4 = w2.hashState();

        // ---------------- 场景 2（N2a 前置回归）：真实玩家路径（Simulation 注册 92 个系统）----------------
        // 旧版本门禁只用裸 World（addSystem 从未被调用 → 系统数为 0），因此掩盖了两个真 bug：
        //   ① TrialSystem 只在 tick==1 布点，而 load 不跑 tick → 读档读 sites.get(i) 直接越界崩溃；
        //   ② Simulation(World)（读档装配路径）漏注册系统 → 读档后世界"冻结"（NPC/文明/试炼全不演化）。
        // 本场景把这两条钉死：没有系统就没有真实存读档。
        Simulation sim = new Simulation(0xB5297A4DL, 160, 112, 160);
        for (int t = 0; t < 60; t++) {
            if (t % 5 == 0) sim.world.player.setIntent(Player.Intent.move(1, 0));
            sim.world.tick();
        }
        final int sitesN = sim.world.trials.sites.size();
        final int cachesN = sim.world.trials.caches.size();
        final int sysN = sim.world.systemCount();
        final long h5 = sim.world.hashState();

        ByteArrayOutputStream b2 = new ByteArrayOutputStream();
        sim.world.save(b2);
        byte[] snap = b2.toByteArray();

        boolean noThrow = true;
        World lw = null;
        try { lw = World.load(new ByteArrayInputStream(snap)); }
        catch (Throwable t) { noThrow = false; System.out.println("  LOAD THREW " + t); }

        boolean guard = false, cont = false;
        if (noThrow) {
            Simulation sim2 = new Simulation(lw);
            final boolean loadEq = lw.hashState() == h5;
            guard = sim2.world.systemCount() == sysN
                    && lw.trials.sites.size() == sitesN
                    && lw.trials.caches.size() == cachesN
                    && loadEq;
            // ★ N2-0 验收判据（真正的「双一致」）：读档后两世界再各演化 120 tick → hash 仍逐字节一致。
            // 这一条一次性覆盖三类故障：快照不完整 / **系统实例状态缺失** / tick==1 自举未重建。
            // 实测战绩：正是它抓出了「WindSystem 持有 private int t 与 gustX/gustZ（不在 World 上）」
            // 这个架构级缺口 —— 那之前，读档瞬间 hash 是**相等**的，只有继续演化才暴露。
            for (int i = 0; i < 120; i++) {
                if (i % 5 == 0) {
                    sim.world.player.setIntent(Player.Intent.move(1, 0));
                    sim2.world.player.setIntent(Player.Intent.move(1, 0));
                }
                sim.world.tick();
                sim2.world.tick();
            }
            cont = sim.world.hashState() == sim2.world.hashState();
            System.out.println("  SNAPSHOT2 bytes=" + snap.length
                    + " sites=" + lw.trials.sites.size() + "/" + sitesN
                    + " caches=" + lw.trials.caches.size() + "/" + cachesN
                    + " systems=" + sim2.world.systemCount() + "/" + sysN
                    + " hashEqOnLoad=" + loadEq
                    + " hashEqAfter120=" + cont
                    + " | 未持久化(有理由): atkAnim / intents");
        }

        boolean pass = (h1 == h2) && (h3 == h4)
                && w2.tick == w1.tick
                && w2.prosperity == w1.prosperity
                && Float.compare(w2.builtMass, w1.builtMass) == 0
                && w2.skills.equals(w1.skills)
                && w2.villageMemory.equals(w1.villageMemory)
                && w2.windowOriginCX() == w1.windowOriginCX()
                && w2.windowOriginCZ() == w1.windowOriginCZ()
                && w2.rng.state() == w1.rng.state()
                && guard && cont;

        System.out.println("SAVEL-GATE  hash0=" + (h1 == h2) + " hash300=" + (h3 == h4)
                + " realPathLoad=" + guard + " doubleConsistency=" + cont
                + "  => " + (pass ? "PASS" : "FAIL"));
        System.out.println("saved  hash = " + Long.toHexString(h1));
        System.out.println("loaded hash = " + Long.toHexString(h2));
        System.out.println("tick=" + w2.tick + " prosperity=" + w2.prosperity
                + " builtMass=" + w2.builtMass + " skills=" + w2.skills.size()
                + " mem=" + w2.villageMemory.size()
                + " win=(" + w2.windowOriginCX() + "," + w2.windowOriginCZ() + ")"
                + " rngState=" + Long.toHexString(w2.rng.state()));
        if (!pass) System.exit(1);
    }
}
