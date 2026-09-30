package core.sim;

import core.world.Autotile;
import core.world.Blocks;
import core.world.World;

/**
 * 门禁 AUTOTILE：泰拉瑞亚缺口①（自动拼贴边缝）的无头守护。
 *
 * <p>守护 {@link Autotile} 的两条不变量：
 * <ol>
 *   <li><b>连通性正确</b>：同组 3×3×3 实心块内部每块每面掩码=15（全连、无缝）；
 *       孤立块掩码=0（四边皆缝）；两相邻同组块在共享轴面的掩码含对应连接位。</li>
 *   <li><b>确定性 / 零漂移</b>：掩码与缝系数只取决于世界状态（邻居 autotileGroup），
 *       纯函数、无 RNG、不写 hashState；非拼贴方块（LAMP/FIRE/GOLD/水/玻璃）seam 恒 1.0。</li>
 * </ol>
 *
 * <p>纯 Java（不触碰 GL）。网格发射两路径（逐面 / 贪婪）都调用 {@code Autotile.seam}，故结果一致。</p>
 */
public class AutotileTest {

    private static boolean ok = true;

    private static void check(String name, boolean cond) {
        System.out.println("  [" + (cond ? "ok  " : "FAIL") + "] " + name);
        if (!cond) ok = false;
    }

    public static void main(String[] args) {
        final int SX = 32, SY = 24, SZ = 32, y = 12;
        World w = new World(20260918L, SX, SY, SZ);
        final int ST = Blocks.STONE.index, LA = Blocks.LAMP.index;

        System.out.println("=== AUTOTILE: 边掩码连通性 ===");

        // 1) 3×3×3 STONE 立方体：内部块每面掩码=15（全连）
        for (int x = 14; x <= 16; x++)
            for (int yy = 11; yy <= 13; yy++)
                for (int z = 14; z <= 16; z++) w.setBlock(x, yy, z, ST);
        int c = Autotile.edgeMask(w, 15, 12, 15, 0);
        check("3x3x3 内部块 +x 面掩码=15", c == 15);
        check("3x3x3 内部块 +y 面掩码=15", Autotile.edgeMask(w, 15, 12, 15, 1) == 15);
        check("3x3x3 内部块 +z 面掩码=15", Autotile.edgeMask(w, 15, 12, 15, 2) == 15);
        check("3x3x3 内部块缝系数=1.0(无暗化)", Math.abs(Autotile.seam(w, Blocks.STONE, 15, 12, 15, 2, 0f, 0f) - 1.0f) < 1e-6f);

        // 2) 孤立 STONE：掩码=0，缝系数=SEAM
        World w2 = new World(20260918L, SX, SY, SZ);
        w2.setBlock(15, y, 15, ST);
        check("孤立块 +x 面掩码=0", Autotile.edgeMask(w2, 15, y, 15, 0) == 0);
        check("孤立块 +y 面掩码=0", Autotile.edgeMask(w2, 15, y, 15, 2) == 0);
        // 孤立块四边皆缝：角(0,0) 两条边都不连 → 暗化 SEAM^2（角点接缝最暗，符合 autotile 行为）
        check("孤立块角(0,0) 两边皆缝 → SEAM^2", Math.abs(Autotile.seam(w2, Blocks.STONE, 15, y, 15, 2, 0f, 0f) - Autotile.SEAM * Autotile.SEAM) < 1e-6f);

        // 3) 两相邻 STONE（沿 +x）：共享面含连接位，正交面无连接
        World w3 = new World(20260918L, SX, SY, SZ);
        w3.setBlock(15, y, 15, ST);
        w3.setBlock(16, y, 15, ST);
        check("相邻块(15) +z 面掩码含+x连接位(=1)", Autotile.edgeMask(w3, 15, y, 15, 2) == 1);
        check("相邻块(15) +x 面掩码=0(正交无关)", Autotile.edgeMask(w3, 15, y, 15, 0) == 0);
        check("相邻块(16) -z 面掩码含-x连接位(=2)", Autotile.edgeMask(w3, 16, y, 15, 2) == 2);
        // 单边连接块(15)：角(1,0) 仅 -va 边缝、+ua 边连 → 恰好 SEAM（一条边暗化）
        check("单边连接块角(1,0) 仅-va缝 → SEAM", Math.abs(Autotile.seam(w3, Blocks.STONE, 15, y, 15, 2, 1f, 0f) - Autotile.SEAM) < 1e-6f);

        // 4) 异组不连：STONE 旁放 DIRT（不同组）→ 不连
        World w4 = new World(20260918L, SX, SY, SZ);
        w4.setBlock(15, y, 15, ST);
        w4.setBlock(16, y, 15, Blocks.DIRT.index);   // DIRT 组=1，STONE 组=2
        check("异组相邻(STONE-DIRT)不连：+z 面掩码=0", Autotile.edgeMask(w4, 15, y, 15, 2) == 0);

        // 5) 确定性：同世界两次计算一致
        check("edgeMask 确定性(两次相等)", Autotile.edgeMask(w, 15, 12, 15, 0) == Autotile.edgeMask(w, 15, 12, 15, 0));

        // 6) 零漂移：autotile 只读世界，不改 mat/tick
        long tickBefore = w4.tick;
        Autotile.seam(w4, Blocks.STONE, 15, y, 15, 2, 1f, 1f);   // na=2(法线轴 z) 合法；仅验证无副作用
        check("autotile 不改 tick", w4.tick == tickBefore);
        check("autotile 不改 mat", w4.mat[15][y][15] == ST);

        // 7) 组分配正确 + 非拼贴方块 seam 恒 1
        check("GRASS 与 DIRT 同组(泥土族)", Blocks.GRASS.autotileGroup == Blocks.DIRT.autotileGroup && Blocks.GRASS.autotileGroup > 0);
        check("STONE/COAL_ORE/IRON_ORE 同组(石族)", Blocks.STONE.autotileGroup == Blocks.COAL_ORE.autotileGroup
                && Blocks.COAL_ORE.autotileGroup == Blocks.IRON_ORE.autotileGroup);
        check("LAMP 不参与拼贴(group=0)", Blocks.LAMP.autotileGroup == 0);
        check("FIRE 不参与拼贴(group=0)", Blocks.FIRE.autotileGroup == 0);
        check("GOLD 不参与拼贴(group=0)", Blocks.GOLD.autotileGroup == 0);
        check("非拼贴方块 seam 恒 1.0", Math.abs(Autotile.seam(w, Blocks.byIndex(LA), 15, 12, 15, 2, 0f, 0f) - 1.0f) < 1e-6f);

        System.out.println("AUTOTILE RESULT: " + (ok ? "PASS" : "FAIL"));
        if (!ok) System.exit(1);
    }
}
