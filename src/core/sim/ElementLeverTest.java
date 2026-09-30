package core.sim;

import core.world.Beast;
import core.world.Blocks;
import core.world.Player;
import core.world.World;

/**
 * 元素杠杆门禁（P2-2 水/雷）：与 FIRELEVER 同纪律。
 *
 * 水（AQUA，引水灌田）：① 确定性自洽 ② 可达——引水后邻水 DIRT 上真的长出 LEAF 作物。
 * 雷（THUNDER，雷击导电）：① 确定性自洽 ② 可达——引雷电击落点附近敌兵
 *                          ③ **导电可达（负例护栏）**——远离落点（> 电击半径）的敌兵，
 *                             仅当它站在连通水域上时才被击中 → 证明"电流沿水传导"真的生效，
 *                             而不是靠落点半径。
 *
 * 运行：java -cp out core.sim.ElementLeverTest
 */
public final class ElementLeverTest {
    public static void main(String[] args) {
        final int SX = 64, SY = 40, SZ = 64, T = 40;
        final long SEED = 20260916L;

        Simulation a = new Simulation(SEED, SX, SY, SZ);
        Simulation b = new Simulation(SEED, SX, SY, SZ);

        boolean[] ra = runLevers(a.world);
        boolean[] rb = runLevers(b.world);

        for (int t = 0; t < T; t++) { a.world.tick(); b.world.tick(); }

        long ha = a.world.hashState();
        long hb = b.world.hashState();

        boolean det = ha == hb;
        boolean watered = ra[0] && rb[0];        // 水：真长出作物
        boolean shocked = ra[1] && rb[1];        // 雷：落点附近敌兵被电击
        boolean conducted = ra[2] && rb[2];      // 雷：电流沿水传导到远端敌兵（负例护栏）
        boolean pass = det && watered && shocked && conducted;

        System.out.printf("ELEMENTLEVER  hashA=%016x hashB=%016x  wateredA=%b wateredB=%b  shockedA=%b shockedB=%b  conductedA=%b conductedB=%b%n",
                ha, hb, ra[0], rb[0], ra[1], rb[1], ra[2], rb[2]);
        System.out.println(pass ? "ELEMENTLEVER PASS" : "ELEMENTLEVER FAIL");
        if (!pass) System.exit(1);
    }

    /** @return [watered, shocked, conducted] —— 水/雷两杠杆的可达性证据。 */
    private static boolean[] runLevers(World w) {
        Player p = w.player;
        p.grantAbility("AQUA");
        p.grantAbility("THUNDER");
        w.tick();   // 让世界完成首 tick 布点、玩家落到站立位

        int px = (int) Math.floor(p.x);
        int pz = (int) Math.floor(p.z);
        int py = (int) Math.floor(p.y + 0.6f);

        // ---------- 水杠杆：铺一块 DIRT 田，引水到它旁边 → 应立即长出 LEAF 作物 ----------
        w.setBlock(px + 2, py, pz, Blocks.DIRT.index);       // 田（DIRT）
        w.setBlock(px + 2, py + 1, pz, Blocks.AIR.index);    // 上方空地
        int leaf0 = w.cellsOfType(Blocks.LEAF.index).size();
        p.divert(w, 1f, 0f);                                 // 面朝 +x 引水 → 水落 (px+1,py,pz)，邻田
        int leaf1 = w.cellsOfType(Blocks.LEAF.index).size();
        Boolean watered = leaf1 > leaf0;

        // ---------- 雷杠杆：沿 +z 铺水链，最远端放敌 → 引雷 → 远端敌兵应被"导电"击中 ----------
        int wz0 = pz + 2;
        for (int k = 0; k < 10; k++) w.setBlock(px, py, wz0 + k, Blocks.WATER.index);   // (px,py,pz+2..pz+11)
        Beast near = new Beast(px + 0.5f, py, wz0 + 0.5f, 0);   // 落点附近（≈1 格）
        Beast far  = new Beast(px + 0.5f, py, wz0 + 9.5f, 0);   // 远端（距落点 ≈9 格，远超电击半径）
        w.beasts.add(near);
        w.beasts.add(far);
        int nearHp0 = near.hp, farHp0 = far.hp;

        p.thunder(w, 0f, 1f);                                // 面朝 +z 引雷 → 落点在 +z 水链上

        Boolean shocked = !w.beasts.contains(near) || near.hp < nearHp0;
        Boolean conducted = !w.beasts.contains(far) || far.hp < farHp0;

        return new boolean[]{watered, shocked, conducted};
    }
}
