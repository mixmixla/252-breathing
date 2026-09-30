package core.sim;

import core.world.Beast;
import core.world.Blocks;
import core.world.Player;
import core.world.World;

/**
 * 涌现杠杆门禁（P2-2）：玩家 FLINT 能力把「火」从被动灾害变成可主动驱动的涌现杠杆。
 *
 * 五证（determinism-gated-port 纪律）：
 *  1) 指纹隔离：玩家 ignite 走 World.setBlock 写路径（与 bomb 同），是输入驱动的实体层写，
 *     不推进主 rng；同种子同输入 → 两遍跑 hashState 逐字节一致（确定性自洽）。
 *  2) 零 RNG：ignite 自身不取任何随机；火蔓延的随机只来自 FireSpreadSystem 的 simStream 子流。
 *  3) 可达性（蔓延）：火真的会蔓延——峰值 FIRE 体素数 > 1。
 *  4) 有牙·敌兵：身处火中的敌兵 HP 下降（或烧死被移除）。
 *  5) 有牙·玩家（双向）：玩家站进火里也会掉血 —— 走 hurtByHazard（i 帧免伤但不误发完美闪避）。
 *
 * 运行：java -cp out core.sim.FireLeverTest
 */
public final class FireLeverTest {
    public static void main(String[] args) {
        final int SX = 64, SY = 40, SZ = 64, T = 240;
        final long SEED = 20260916L;

        Simulation a = new Simulation(SEED, SX, SY, SZ);
        Simulation b = new Simulation(SEED, SX, SY, SZ);

        // 在两遍里做完全相同的确定性布fuel + 引火 + 布敌 + 玩家入火（同种子同输入）
        Beast victimA = setupFuelAndIgnite(a.world);
        Beast victimB = setupFuelAndIgnite(b.world);
        int vMaxA = victimA.maxHp, vMaxB = victimB.maxHp;
        int initHpA = a.world.player.hp, initHpB = b.world.player.hp;

        int peakA = 0, peakB = 0;
        boolean hurtA = false, hurtB = false;      // 敌兵被烧
        boolean hazA = false, hazB = false;        // 玩家被烧
        for (int t = 0; t < T; t++) {
            a.world.tick();
            b.world.tick();
            int fa = a.world.cellsOfType(Blocks.FIRE.index).size();
            int fb = b.world.cellsOfType(Blocks.FIRE.index).size();
            if (fa > peakA) peakA = fa;
            if (fb > peakB) peakB = fb;
            if (!a.world.beasts.contains(victimA) || victimA.hp < vMaxA) hurtA = true;
            if (!b.world.beasts.contains(victimB) || victimB.hp < vMaxB) hurtB = true;
            if (a.world.player.hp < initHpA) hazA = true;
            if (b.world.player.hp < initHpB) hazB = true;
        }

        long ha = a.world.hashState();
        long hb = b.world.hashState();

        boolean det = ha == hb;
        boolean spread = peakA > 1 && peakB > 1;      // 火真的蔓延
        boolean teeth = hurtA && hurtB;               // 火真的伤敌
        boolean hazard = hazA && hazB;                // 火真的伤玩家（双向）
        boolean pass = det && spread && teeth && hazard;

        System.out.printf("FIRELEVER  hashA=%016x hashB=%016x  peakFireA=%d peakFireB=%d  burnA=%b burnB=%b  hazardA=%b hazardB=%b%n",
                ha, hb, peakA, peakB, hurtA, hurtB, hazA, hazB);
        System.out.println(pass ? "FIRELEVER PASS" : "FIRELEVER FAIL");
        if (!pass) System.exit(1);
    }

    /**
     * 在玩家正前方铺 WOOD 燃料、用 FLINT 引火、放一只敌兵站进火里、并让玩家自身站进火里
     * （四项均为确定性输入）。
     * @return 放进火中的敌兵（供调用方断言它掉血/被烧死）
     */
    private static Beast setupFuelAndIgnite(World w) {
        Player p = w.player;
        p.grantAbility("FLINT");

        // 先推进一 tick：让世界完成首 tick 布点（祭坛/试炼），玩家落到稳定站立位
        w.tick();

        // 用落定后的玩家坐标铺燃料，确保 ignite 落点一定落在 WOOD 上
        int px = (int) Math.floor(p.x);
        int pz = (int) Math.floor(p.z);
        int py = (int) Math.floor(p.y + 0.6f);
        int pFootY = (int) Math.floor(p.y);       // 与 FireSpreadSystem 判定玩家"是否在火中"的口径一致

        // 在正前方 +z 方向铺 8x8 的 WOOD 燃料墙（含上下两层）
        for (int dz = 1; dz <= 8; dz++) {
            for (int dx = -3; dx <= 4; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    int x = px + dx, y = py + dy, z = pz + dz;
                    if (w.inBounds(x, y, z)) w.setBlock(x, y, z, Blocks.WOOD.index);
                }
            }
        }

        p.ignite(w, 0f, 1f);           // 面朝 +z 引火 → 落点即 WOOD → FIRE 点燃

        // 放一只 GRUNT 站进引火格（(px,py,pz+1) 正是 ignite 的落点）→ 验证「火伤敌」
        Beast victim = new Beast(px + 0.5f, py, pz + 1.5f, 0);
        w.beasts.add(victim);

        // 让玩家自身也站进火里 → 验证「火伤玩家（双向）」；用 pFootY 对齐系统判定口径
        if (w.inBounds(px, pFootY, pz)) w.setBlock(px, pFootY, pz, Blocks.FIRE.index);
        return victim;
    }
}
