import core.world.Blocks;
import core.world.World;
import render.lwjgl.Chunk;

import java.util.Set;

/**
 * 无头几何验证（不需要 GPU / OpenGL 上下文）——P2-3「水/玻璃透明」的网格分工正确性。
 *
 * <p>验证两件事：</p>
 * <ol>
 *   <li><b>两条路径等价</b>：改造剔除规则后，逐面路径 {@code collectPerFace} 与贪婪路径
 *       {@code collectGreedyQuads} 的暴露面集合仍必须逐面一致（两条路径共用 {@code FaceCull}）。</li>
 *   <li><b>核心语义</b>：池底石头<b>透水可见</b>（其顶面在暴露面集合里）——这正是改造的目的；
 *       同时水-水内部面无重复。</li>
 * </ol>
 *
 * <p>运行：<br>
 *   java -cp "out;libs/..." MeshPassCheck</p>
 */
public class MeshPassCheck {

    private static boolean ok = true;

    private static void check(String name, boolean cond) {
        System.out.println("  [" + (cond ? "ok  " : "FAIL") + "] " + name);
        if (!cond) ok = false;
    }

    /** 与 Chunk.expandQuad 同编码：((x*1000+y)*1000+z)*10 + dir。 */
    private static long key(int x, int y, int z, int dir) {
        return (((long) x * 1000 + y) * 1000 + z) * 10 + dir;
    }

    public static void main(String[] args) {
        long seed = args.length > 0 ? Long.parseLong(args[0]) : 20260916L;
        final int SX = 48, SY = 24, SZ = 48;
        World w = new World(seed, SX, SY, SZ);
        final int y = 20;                                   // 高空：隔离地形
        final int S = Blocks.STONE.index, WA = Blocks.WATER.index, GL = Blocks.GLASS.index;

        // 0) 先把试验区清空成空气（世界生成的山体可能顶到 y=20 → 会污染邻居判定）
        for (int x = 0; x < 24; x++)
            for (int z = 0; z < 24; z++)
                for (int yy = y - 1; yy < SY; yy++) w.setBlock(x, yy, z, Blocks.AIR.index);

        // 一方水池：池底石 + 一层水；旁边一堵玻璃墙；另一处两格深的水柱；再来一处「玻璃后的石头」
        for (int x = 3; x <= 10; x++)
            for (int z = 3; z <= 10; z++) {
                w.setBlock(x, y, z, S);
                w.setBlock(x, y + 1, z, WA);
            }
        for (int z = 3; z <= 10; z++) w.setBlock(12, y + 1, z, GL);
        w.setBlock(20, y, 20, S);
        w.setBlock(20, y + 1, 20, WA);
        w.setBlock(20, y + 2, 20, WA);                      // 两格深 → 存在水-水内部面
        w.setBlock(11, y, 11, S);                           // 玻璃后的石头
        w.setBlock(11, y + 1, 11, GL);

        System.out.println("=== MeshPassCheck (seed=" + seed + ") ===");

        boolean setsEqual = true;
        long opaqueFaces = 0, transFaces = 0;
        Set<Long> pf00 = null, gr00 = null, pf11 = null, gr11 = null;
        for (int cx = 0; cx < SX / 16; cx++)
            for (int cz = 0; cz < SZ / 16; cz++) {
                Set<Long> pf = Chunk.exposedFaces(w, cx, cz, false);
                Set<Long> gr = Chunk.exposedFaces(w, cx, cz, true);
                if (!pf.equals(gr)) { setsEqual = false; System.out.println("    mismatch chunk (" + cx + "," + cz + ")"); }
                int[] pc = Chunk.passCounts(w, cx, cz, false);
                int[] gc = Chunk.passCounts(w, cx, cz, true);
                if (pc[0] + pc[1] != pf.size()) ok = false;                        // 逐面：两个 pass 的分区 == 全部暴露面
                if (gc[0] + gc[1] <= 0 || gc[0] + gc[1] > gr.size()) ok = false;   // 贪婪：合并四边形数 ≤ 单位面数
                opaqueFaces += pc[0];
                transFaces += pc[1];
                if (cx == 0 && cz == 0) { pf00 = pf; gr00 = gr; }
                if (cx == 1 && cz == 1) { pf11 = pf; gr11 = gr; }
            }

        check("逐面路径 == 贪婪路径（新剔除规则下）", setsEqual);
        check("分区完备：不透明 + 透明 == 暴露面总数", ok);
        System.out.println("    不透明面 = " + opaqueFaces + " / 透明面 = " + transFaces);
        check("确实存在透明面（水/玻璃被路由进透明 pass）", transFaces > 0);

        // 核心语义：池底(5,y,5) 的顶面(dir=2) 必须暴露（透水可见）；贪婪路径同样
        long stoneTop = key(5, y, 5, 2);
        check("池底石头顶面透水可见（逐面）", pf00 != null && pf00.contains(stoneTop));
        check("池底石头顶面透水可见（贪婪）", gr00 != null && gr00.contains(stoneTop));

        // 水-水内部面必须被剔除：水(20,y+1,20) 朝上、水(20,y+2,20) 朝下（该柱位于 chunk(1,1)）
        check("水-水内部面已剔除（逐面）", pf11 != null && !pf11.contains(key(20, y + 1, 20, 2)) && !pf11.contains(key(20, y + 2, 20, 3)));
        check("水-水内部面已剔除（贪婪）", gr11 != null && !gr11.contains(key(20, y + 1, 20, 2)) && !gr11.contains(key(20, y + 2, 20, 3)));

        // 玻璃：玻璃(12,y+1,3) 朝向同列上方的面应存在（玻璃-空气）；玻璃后石块顶面暴露
        check("玻璃墙面朝空气可见", pf00 != null && pf00.contains(key(12, y + 1, 3, 2)));
        check("玻璃后石头顶面可见", pf00 != null && pf00.contains(key(11, y, 11, 2)));

        // 索引化绘制（PERF）：索引模式必须与「每四边形 4 顶点」匹配，否则 GPU 会读到越界索引
        int[] idx = Chunk.quadIndices(7);
        boolean idxOk = (idx.length == 42);
        for (int v : idx) if (v < 0 || v >= 28) idxOk = false;
        check("quadIndices(7)：长度 42 且全部索引 ∈ [0,28)", idxOk);
        check("quadIndices 首四边形模式 == {0,1,2, 0,2,3}",
                idx[0] == 0 && idx[1] == 1 && idx[2] == 2 && idx[3] == 0 && idx[4] == 2 && idx[5] == 3);
        check("quadIndices 第二四边形基址 == 4（即每四边形 4 顶点）", idx[6] == 4 && idx[11] == 7);

        System.out.println("MeshPassCheck RESULT: " + (ok ? "PASS" : "FAIL"));
        if (!ok) System.exit(1);
    }
}
