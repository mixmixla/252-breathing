import core.world.World;
import render.lwjgl.Chunk;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 无头几何等价验证（不需要 GPU / OpenGL 上下文）。
 *
 * 直接复用 Chunk 里真实渲染用的同一套合并代码：
 *  - 逐面路径 collectPerFace 展开为「单位面集合 (x,y,z,dir)」；
 *  - greedy 路径 collectGreedyQuads 合并后再展开为同样的「单位面集合」。
 * 两者集合必须完全一致（无丢面/多面/错位），且 greedy 顶点数 <= 逐面顶点数。
 *
 * 运行：
 *   cd breathing-world
 *   javac -cp "out;libs/lwjgl-3.3.3.jar;libs/lwjgl-glfw-3.3.3.jar;libs/lwjgl-opengl-3.3.3.jar;libs/joml-1.10.5.jar" -d out tools/GreedyCheck.java
 *   java -cp out GreedyCheck
 */
public class GreedyCheck {
    public static void main(String[] args) {
        long seed = args.length > 0 ? Long.parseLong(args[0]) : 20260908L;
        int SX = 96, SY = 48, SZ = 96;
        World w = new World(seed, SX, SY, SZ);
        int CX = SX / 16, CZ = SZ / 16;

        long perFaceFaces = 0;     // 逐面路径暴露单位面总数（= 逐面四边形数）
        long greedyFaces = 0;      // greedy 路径展开后覆盖的单位面总数
        long greedyQuads = 0;      // greedy 合并后四边形数
        boolean setsEqual = true;
        long firstMismatch = -1;

        for (int cx = 0; cx < CX; cx++)
            for (int cz = 0; cz < CZ; cz++) {
                Set<Long> pf = Chunk.exposedFaces(w, cx, cz, false);
                Set<Long> gr = Chunk.exposedFaces(w, cx, cz, true);
                if (!pf.equals(gr)) {
                    setsEqual = false;
                    if (firstMismatch < 0) {
                        firstMismatch = ((long) cx << 32) | cz;
                        Set<Long> onlyPF = new LinkedHashSet<>(pf); onlyPF.removeAll(gr);
                        Set<Long> onlyGR = new LinkedHashSet<>(gr); onlyGR.removeAll(pf);
                        System.out.println("MISMATCH chunk (" + cx + "," + cz + "): perFace=" + pf.size() + " greedy=" + gr.size());
                        System.out.println("  only-in-perFace(count=" + onlyPF.size() + ") only-in-greedy(count=" + onlyGR.size() + ")");
                    }
                }
                perFaceFaces += pf.size();
                greedyFaces += gr.size();
                greedyQuads += Chunk.quadCount(w, cx, cz);
            }

        long perFaceVerts = perFaceFaces * 4;   // 索引化后：每四边形 4 顶点（旧为 6）
        long greedyVerts = greedyQuads * 4;
        double reduction = perFaceVerts == 0 ? 0.0 : (1.0 - (double) greedyVerts / perFaceVerts) * 100.0;

        System.out.println("================ Greedy Meshing Geometry Equivalence Check ================");
        System.out.println("seed                       : " + seed + "  (world " + SX + "x" + SY + "x" + SZ + ", chunks " + CX + "x" + CZ + ")");
        System.out.println("exposed-face sets equal    : " + setsEqual + (setsEqual ? "" : "  firstMismatch=" + firstMismatch));
        System.out.println("total exposed unit faces   : " + perFaceFaces + "  (greedy covers same: " + greedyFaces + ")");
        System.out.println("per-face quads/faces       : " + perFaceFaces + "  -> verts=" + perFaceVerts);
        System.out.println("greedy quads               : " + greedyQuads + "  -> verts=" + greedyVerts);
        System.out.println("vertex reduction           : " + String.format("%.1f", reduction) + "%  (greedy<=perFace: " + (greedyVerts <= perFaceVerts) + ")");
        boolean pass = setsEqual && greedyVerts <= perFaceVerts && perFaceFaces > 0;
        System.out.println("RESULT                     : " + (pass ? "PASS" : "FAIL"));
        if (!pass) System.exit(1);
    }
}
