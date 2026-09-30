package core.sim;

import core.world.Blocks;
import core.world.Megalith;
import core.world.World;

/**
 * 第 17 道门禁：巨构 Phase 2（近场神殿材化进 mat，ART_BIBLE §9.7）。
 *
 * <p>断言的是<b>性质</b>而非「能跑起来」：
 * <ol>
 *   <li><b>det</b>：同种子双世界（SY=112，神殿会材化）逐 tick 自洽，且窗口平移到神殿后再自洽；</li>
 *   <li><b>anchor</b>：出生锚点存在且距原点 ≤ 260 格（P3「远方有未知」可达性）；</li>
 *   <li><b>materialized</b>：神殿方块真实写进 mat（条石家族计数 &gt; 5000）；</li>
 *   <li><b>lamp/interior/door/wall</b>：结构探针 —— 祭坛长明灯 / 中庭空腔 / 门洞（可达性：玩家
 *       能走进去）/ 环墙实体（随 rot 旋转变换探针坐标）；</li>
 *   <li><b>slide</b>：同一神殿在两个窗口位置逐块一致（无限流式滑入滑出确定性复现）；</li>
 *   <li><b>exempt</b>：门禁小世界（SY=40）零神殿方块 —— 四道基线逐字节不变的机理证明
 *       （材化被 MIN_SY=96 门控，门禁世界走不到）。</li>
 * </ol>
 */
public class MegalithDeterminismTest {

    public static void main(String[] args) {
        final long SEED = 987654321L;
        final int SX = 96, SY = 112, SZ = 96, T = 240;

        // ---- 1) 确定性（含神殿材化）----
        Simulation A = new Simulation(SEED, SX, SY, SZ);
        Simulation B = new Simulation(SEED, SX, SY, SZ);
        for (int i = 0; i < T; i++) { A.world.tick(); B.world.tick(); }
        boolean det = A.world.hashState() == B.world.hashState();

        // ---- 2) 出生锚点 ----
        Megalith.Desc d = Megalith.descAt(A.world, 0, 0, true);
        boolean anchor = d != null && Math.sqrt((double) d.gxc * d.gxc + (double) d.gzc * d.gzc) <= 260.0;

        // ---- 3) 材化 + 结构探针（平移窗口覆盖神殿）----
        A.world.streamTo(d.gxc / World.CHUNK, d.gzc / World.CHUNK);
        B.world.streamTo(d.gxc / World.CHUNK, d.gzc / World.CHUNK);
        for (int i = 0; i < 30; i++) { A.world.tick(); B.world.tick(); }
        det &= A.world.hashState() == B.world.hashState();

        int winX0 = A.world.windowOriginCX() * World.CHUNK;
        int winZ0 = A.world.windowOriginCZ() * World.CHUNK;
        int gy = Megalith.groundY(A.world, d.gxc, d.gzc) + 1;
        int lx = d.gxc - winX0, lz = d.gzc - winZ0;

        int ash = 0;
        for (int x = 0; x < SX; x++)
            for (int y = 0; y < SY; y++)
                for (int z = 0; z < SZ; z++) {
                    int b = A.world.getBlock(x, y, z);
                    if (b == Blocks.ASHLAR.index || b == Blocks.ASHLAR_SHADE.index
                            || b == Blocks.ASHLAR_VEIN.index || b == Blocks.GOLD.index) ash++;
                }
        boolean materialized = ash > 5000;

        boolean structOk;
        String structDetail;
        if (d.variant == 0) {
            // TOWER：祭坛长明灯 / 中庭空腔 / 门洞（可达性）/ 环墙实体（门洞与环墙随 rot 旋转）
            boolean lamp = A.world.getBlock(lx, gy + 13, lz) == Blocks.LAMP.index;
            boolean interior = A.world.getBlock(lx, gy + 15, lz + 10) == Blocks.AIR.index;
            int ddx = 21, ddz = 0, wdx = 21, wdz = 8;
            for (int i = 0; i < d.rot; i++) {
                int t = ddx; ddx = ddz; ddz = -t;
                t = wdx; wdx = wdz; wdz = -t;
            }
            boolean door = A.world.getBlock(lx + ddx, gy + 15, lz + ddz) == Blocks.AIR.index;
            int wb = A.world.getBlock(lx + wdx, gy + 15, lz + wdz);
            boolean wall = wb == Blocks.ASHLAR.index || wb == Blocks.ASHLAR_VEIN.index;
            structOk = lamp && interior && door && wall;
            structDetail = String.format("lamp=%b interior=%b door=%b wall=%b", lamp, interior, door, wall);
        } else {
            // GATE：拱下通道可走（可达性）/ 半埋断龙骨 / 柱体实体（沿世界 x/z 两轴扫描，覆盖任意 rot）
            boolean passage = A.world.getBlock(lx, gy + 10, lz) == Blocks.AIR.index;
            int kb = A.world.getBlock(lx, gy - 3, lz);   // 断龙骨在风化带内：VEIN 或苔藓侵蚀后的 MOSS
            boolean keystone = kb == Blocks.ASHLAR_VEIN.index || kb == Blocks.MOSS.index;
            int pierHits = 0;
            for (int r = -30; r <= 30; r++) {
                int bx = A.world.getBlock(lx + r, gy + 10, lz);
                int bz = A.world.getBlock(lx, gy + 10, lz + r);
                if (bx >= Blocks.ASHLAR.index && bx <= Blocks.GOLD.index) pierHits++;
                if (bz >= Blocks.ASHLAR.index && bz <= Blocks.GOLD.index) pierHits++;
            }
            boolean pier = pierHits >= 30;
            structOk = passage && keystone && pier;
            structDetail = String.format("passage=%b keystone=%b pier=%b(hits=%d)", passage, keystone, pier, pierHits);
        }

        // ---- 4) 滑动复现：神殿柱在两个窗口位置逐块一致 ----
        int[] col1 = new int[30];
        for (int i = 0; i < 30; i++) col1[i] = A.world.getBlock(lx, gy + i, lz);
        A.world.streamTo((d.gxc + 40) / World.CHUNK, d.gzc / World.CHUNK);
        int winX1 = A.world.windowOriginCX() * World.CHUNK;
        boolean slide = true;
        for (int i = 0; i < 30; i++)
            if (A.world.getBlock(d.gxc - winX1, gy + i, d.gzc - winZ0) != col1[i]) slide = false;

        // ---- 5) 小世界豁免（基线零漂移的机理证明）----
        Simulation S = new Simulation(SEED, 64, 40, 64);
        int ashSmall = 0;
        for (int x = 0; x < 64; x++)
            for (int y = 0; y < 40; y++)
                for (int z = 0; z < 64; z++) {
                    int b = S.world.getBlock(x, y, z);
                    if (b >= Blocks.ASHLAR.index && b <= Blocks.GOLD.index) ashSmall++;   // 追加注册 → 索引连续
                }
        boolean exempt = ashSmall == 0;

        boolean pass = det && anchor && materialized && structOk && slide && exempt;

        System.out.printf("MEGALITH-DETERMINISM  hashA=%016x hashB=%016x  ash=%d%n",
                A.world.hashState(), B.world.hashState(), ash);
        System.out.printf("MEGALITH anchor=%s @( %d, %d ) rot=%d variant=%d gy=%d%n",
                anchor, d.gxc, d.gzc, d.rot, d.variant, gy);
        System.out.printf("MEGALITH-GATE  det=%b anchor=%b materialized=%b struct=%b slide=%b exempt=%b  [%s]%n",
                det, anchor, materialized, structOk, slide, exempt, structDetail);
        System.out.println(pass ? "MEGALITH-DETERMINISM PASS" : "MEGALITH-DETERMINISM FAIL");
        if (!pass) System.exit(1);
    }
}
