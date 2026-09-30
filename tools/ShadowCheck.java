import render.lwjgl.VoxelShadow;

/**
 * SHADOW 门禁（P4，2026-09-23）：体素方向光阴影的**几何语义**门禁（纯计算、无 GL、headless）。
 *
 * <p>为什么必须有它：阴影是"看起来差不多就行"的重灾区。一个方向反了、步长漏检、或者
 * 半影公式写错的阴影，在截图里**依然像阴影**（尤其有雾和内散射兜底时），肉眼极难判伪。
 * 所以这里不测"好不好看"，只测<b>可预测的几何</b>：
 *
 * <ol>
 *   <li>{@code SHADOW-FLAT}   空世界任何点都必须全亮（防止"平地也发黑"）</li>
 *   <li>{@code SHADOW-SIDE}   阴影必须落在<b>背光侧</b>（顺光侧不受影响）—— 方向反了会 FAIL</li>
 *   <li>{@code SHADOW-LEN}    阴影长度 ≈ {@code h / tan(仰角)}：45° 光下 8 格高的墙，
 *       距墙 4 格处必被遮、距墙 8 格处必暴露 —— 这是**像素级可计算的几何判据**</li>
 *   <li>{@code SHADOW-ZENITH} 太阳在正上方时，只有"正被遮住"的点变暗（头顶的板）</li>
 *   <li>{@code SHADOW-ELEV}   仰角单调：仰角越低越容易被遮（30° 必被遮、70° 必暴露）</li>
 *   <li>{@code SHADOW-PENUMBRA} 半影连续：遮挡物越远，阴影越浅（{@code 0 < vis < 1} 且随距离递增）
 *       —— 这条保证"软"是真的软，而不是硬边 0/1</li>
 *   <li>{@code SHADOW-OOB}    世界之外不产生遮挡（否则视口边缘会莫名发黑）</li>
 *   <li>{@code SHADOW-BUILD}  {@code buildOcc(World)} 与直接遍历 {@code mat} 结果一致，
 *       且判据用的是 {@code Block.opaque}（与块光 BFS 同一个字段）</li>
 * </ol>
 *
 * <p><b>⚠️ 与 GLSL 的双实现漂移</b>：world FS 里有一份逐句同构的 GLSL 实现。
 * {@code SHADOW-GLSL} 直接解析 {@code Game.java} 的 world FS 文本，断言步数/分段/步长/
 * 回退公式/合成方式这些**常量与结构**没有漂移 —— 编译通过完全证明不了这些
 * （同 {@code EDGESHADER} 的纪律）。
 */
public class ShadowCheck {

    private static int fails = 0;

    // 测试场景：32×16×32，一堵厚 4、高 8 的墙（沿 z 全跨度）
    private static final int SX = 32, SY = 16, SZ = 32;
    private static final int WALL_X0 = 16, WALL_X1 = 20, WALL_Y1 = 8;

    /** 建造测试占用：{@code z} 全跨度的一堵墙。 */
    private static byte[] wallWorld() {
        byte[] occ = new byte[SX * SY * SZ];
        for (int z = 0; z < SZ; z++)
            for (int y = 0; y < WALL_Y1; y++)
                for (int x = WALL_X0; x < WALL_X1; x++)
                    occ[VoxelShadow.indexOf(x, y, z, SX, SY)] = 1;
        return occ;
    }

    private static byte[] emptyWorld() {
        return new byte[SX * SY * SZ];
    }

    /** 头顶悬浮板：{@code y = 10} 上一层，x∈[16,20)。 */
    private static byte[] roofWorld() {
        byte[] occ = new byte[SX * SY * SZ];
        for (int z = 0; z < SZ; z++)
            for (int x = WALL_X0; x < WALL_X1; x++)
                occ[VoxelShadow.indexOf(x, 10, z, SX, SY)] = 1;
        return occ;
    }

    private static final double SOFT = 0.85, FALL = 0.22;
    /** 门禁用固定 jitter —— 抖动是 GLSL 侧 per-pixel 的，门禁必须可复现。 */
    private static final double NOJIT = 0.0;

    /** 便捷：法线朝上的采样。 */
    private static float vis(byte[] occ, double wx, double wy, double wz, double lx, double ly, double lz) {
        double[] L = VoxelShadow.unit(lx, ly, lz);
        return VoxelShadow.sunVisibility(occ, SX, SY, SZ, wx, wy, wz, 0, 1, 0,
                L[0], L[1], L[2], SOFT, FALL, NOJIT);
    }

    public static void main(String[] args) {
        System.out.println("SHADOWCHECK  体素方向光阴影几何门禁（无 GL / headless）");

        // ---- 1. 空世界：处处全亮 ----
        byte[] empty = emptyWorld();
        float minVis = 1f;
        for (int i = 0; i < 200; i++) {
            double x = 1.0 + (i * 7 % 30), y = 0.5 + (i % 12), z = 1.0 + (i * 11 % 30);
            double[] L = VoxelShadow.unit(1, 1, 0.3);
            float v = VoxelShadow.sunVisibility(empty, SX, SY, SZ, x, y, z, 0, 1, 0,
                    L[0], L[1], L[2], SOFT, FALL, NOJIT);
            if (v < minVis) minVis = v;
        }
        boolean flatOk = minVis > 0.999f;
        System.out.println("SHADOW-FLAT     " + (flatOk ? "PASS" : "FAIL")
                + "  空世界 200 点最暗 vis=" + fmt(minVis) + "  want=1.0");
        if (!flatOk) fails++;

        // ---- 2. 阴影必须落在背光侧 ----
        byte[] wall = wallWorld();                       // 光来自 +X（L.x>0）
        float back = vis(wall, 12.5, 1.0, 16.0, 1, 1, 0);   // 墙的 -X 侧（背光）
        float front = vis(wall, 24.0, 1.0, 16.0, 1, 1, 0);  // 墙的 +X 侧（顺光）
        boolean sideOk = back < 0.999f && front > 0.999f;
        System.out.println("SHADOW-SIDE     " + (sideOk ? "PASS" : "FAIL")
                + "  背光侧 vis=" + fmt(back) + " 应<1 ；顺光侧 vis=" + fmt(front) + " 应=1");
        if (!sideOk) fails++;

        // ---- 3. 阴影长度 ≈ h / tan(仰角)：45° 光、墙高 8、采样面 y=1.1 ----
        //     光线上从 (x0,1.1) 到墙左面 x=16 恰好越过墙顶 y=8 的边界是 x0 = 16 - 6.9 = 9.1
        float inside = vis(wall, 12.5, 1.0, 16.0, 1, 1, 0);  // 距墙 3.5 格 → 落在阴影内
        float beyond = vis(wall, 8.0, 1.0, 16.0, 1, 1, 0);   // 距墙 8.0 格 → 越过墙顶
        boolean lenOk = inside < 0.999f && beyond > 0.999f;
        System.out.println("SHADOW-LEN      " + (lenOk ? "PASS" : "FAIL")
                + "  45度光/墙高8：距墙3.5格 vis=" + fmt(inside) + " 应<1 ；距墙8格 vis=" + fmt(beyond) + " 应=1");
        if (!lenOk) fails++;

        // ---- 4. 太阳在正上方：只有头顶的板投影 ----
        byte[] roof = roofWorld();
        float under = vis(roof, 18.0, 8.0, 16.0, 0, 1, 0);   // 板正下方
        float beside = vis(roof, 25.0, 8.0, 16.0, 0, 1, 0);  // 板外侧
        boolean zenOk = under < 0.999f && beside > 0.999f;
        System.out.println("SHADOW-ZENITH   " + (zenOk ? "PASS" : "FAIL")
                + "  天顶光：板正下方 vis=" + fmt(under) + " 应<1 ；板外 vis=" + fmt(beside) + " 应=1");
        if (!zenOk) fails++;

        // ---- 5. 仰角单调：仰角越低越容易被遮 ----
        //     (12,0.5) 距墙 4 格、墙高 8 ⇒ 到墙面时 y = 0.6 + 4*tan(θ)；θ<61.6° 才被遮
        double tanLo = Math.tan(Math.toRadians(30.0));
        double tanHi = Math.tan(Math.toRadians(70.0));
        float low = vis(wall, 12.0, 0.5, 16.0, 1, tanLo, 0);
        float high = vis(wall, 12.0, 0.5, 16.0, 1, tanHi, 0);
        boolean elevOk = low < 0.999f && high > 0.999f;
        System.out.println("SHADOW-ELEV     " + (elevOk ? "PASS" : "FAIL")
                + "  仰角30度 vis=" + fmt(low) + " 应<1 ；仰角70度 vis=" + fmt(high) + " 应=1");
        if (!elevOk) fails++;

        // ---- 6. 半影连续：遮挡物越远 → 阴影越浅（且始终在开区间内）----
        //     水平偏一点的光（仰角约 26.6°），采样沿 -X 逐步远离墙面
        double[] L = VoxelShadow.unit(1, 0.5, 0);
        float[] v = new float[5];
        double[] xs = {15.0, 13.0, 11.0, 10.0, 9.0};
        for (int i = 0; i < xs.length; i++) {
            v[i] = VoxelShadow.sunVisibility(wall, SX, SY, SZ, xs[i], 0.5, 16.0, 0, 1, 0,
                    L[0], L[1], L[2], SOFT, FALL, NOJIT);
        }
        boolean penOk = true;
        for (int i = 0; i < v.length; i++) {
            if (!(v[i] > 0.0f && v[i] < 1.0f)) penOk = false;      // 必须"部分遮挡"
            // 非递减（不是严格递增）：粗步长会把两个相邻距离**量化到同一个采样步**，
            // 于是出现相等的相邻值。要求严格递增等于要求步长无限细 —— 那不是这个方案。
            if (i > 0 && !(v[i] >= v[i - 1] - 1e-6f)) penOk = false;
        }
        // 但整体必须真的"越远越浅"：首尾要有显著差（否则半影退化成了常数）
        if (!(v[v.length - 1] - v[0] > 0.15f)) penOk = false;
        StringBuilder vs = new StringBuilder();
        for (float f : v) vs.append(fmt(f)).append(' ');
        System.out.println("SHADOW-PENUMBRA " + (penOk ? "PASS" : "FAIL")
                + "  距墙 1→7 格 vis = [" + vs.toString().trim()
                + "]  want 非递减且 ∈(0,1)、首尾差>0.15");
        if (!penOk) fails++;

        // ---- 7. 世界之外不遮挡 ----
        float oob = vis(wall, -3.0, 1.0, 16.0, 1, 1, 0);
        boolean oobOk = oob > 0.999f;
        System.out.println("SHADOW-OOB      " + (oobOk ? "PASS" : "FAIL")
                + "  世界外采样 vis=" + fmt(oob) + "  want=1.0（否则视口边缘发黑）");
        if (!oobOk) fails++;

        // ---- 8. buildOcc 与 mat 一致 + 用的是 opaque 判据 ----
        try {
            core.world.World w = new core.world.World(20260923L, 32, 16, 32);
            byte[] built = VoxelShadow.buildOcc(w);
            int mism = 0, solids = 0;
            for (int z = 0; z < w.SZ; z++)
                for (int y = 0; y < w.SY; y++)
                    for (int x = 0; x < w.SX; x++) {
                        int m = w.mat[x][y][z];
                        boolean opq = m > 0 && core.world.Blocks.byIndex(m) != null
                                && core.world.Blocks.byIndex(m).opaque;
                        byte got = built[VoxelShadow.indexOf(x, y, z, w.SX, w.SY)];
                        if ((got != 0) != opq) mism++;
                        if (got != 0) solids++;
                    }
            // 真实世界生成得有地形（否则这条断言等于没测）
            boolean buildOk = mism == 0 && solids > 1000;
            System.out.println("SHADOW-BUILD    " + (buildOk ? "PASS" : "FAIL")
                    + "  32x16x32 世界：不一致 " + mism + " 格 / 遮挡体素 " + solids + " 个 (want 0 且 >1000)");
            if (!buildOk) fails++;
        } catch (Throwable t) {
            System.out.println("SHADOW-BUILD    FAIL  World 构造异常：" + t);
            fails++;
        }

        // ---- 8b. 细步长必须 < 1 格（体素的最小遮挡厚度就是 1 格）----
        // 血案（2026-09-23）：首版细步长用 1.0，会**跨过只有 1 格厚的遮挡物**。
        // 症状是最难查的那一种：门禁全绿、readback 确认纹理真的传上去了、Java 侧 CPU 探针
        // 也命中（返回 0.498），但 GPU 上就是"没有阴影" —— 因为采样点恰好落在实体两侧。
        // 定位靠"CPU 与 GPU 逐步对照"（cpuSteps p0/p1/p2 vs shader 里同样的三次采样）。
        boolean stepOk = VoxelShadow.NEAR_STEP < 1.0 && VoxelShadow.NEAR_STEP > 0.05
                && VoxelShadow.REACH > 20.0;
        System.out.println("SHADOW-STEP     " + (stepOk ? "PASS" : "FAIL")
                + "  细步长=" + fmt((float) VoxelShadow.NEAR_STEP) + " (必须<1) ；总覆盖="
                + fmt((float) VoxelShadow.REACH) + " 格 (必须>20)");
        if (!stepOk) fails++;

        // ---- 8c. 占用值的量程必须远高于 GLSL 的 0.5 阈值 ----
        // 血案（2026-09-23）：首版把占用写成字节 1，而纹理是 GL_R8（归一化）⇒ 采样值 1/255=0.0039
        // ⇒ GLSL 的 (voxOcc>0.5) 恒判为空 ⇒ 阴影全灭。更坑的是 glGetTexImage 读回的是**原始字节**，
        // 与 CPU 数组比对完全一致 ⇒ 运行时不变量报 readbackDiff=0「一切正常」。
        // 所以这里断言"占用值换算成归一化后必须高于判据阈值"，而不是比对原始字节。
        float occupiedNorm = (VoxelShadow.OCCUPIED & 0xFF) / 255.0f;
        boolean scaleOk = occupiedNorm > 0.5f;
        System.out.println("SHADOW-SCALE    " + (scaleOk ? "PASS" : "FAIL")
                + "  占用值 " + (VoxelShadow.OCCUPIED & 0xFF) + "/255 = " + fmt(occupiedNorm)
                + "  必须 >0.5（GLSL 判据阈值）");
        if (!scaleOk) fails++;

        // ---- 9. GLSL 双实现不漂移（解析源码，不抄副本）----
        int drift = 0;
        StringBuilder driftMsg = new StringBuilder();
        try {
            String root = System.getProperty("bw.root", ".");
            String src = new String(java.nio.file.Files.readAllBytes(
                    java.nio.file.Paths.get(root, "src/render/lwjgl/Game.java")),
                    java.nio.charset.StandardCharsets.UTF_8);
            // 只取 initWorldShader 之后的那一大段（world FS 是其中的第二个字面量）
            int at = src.indexOf("private void initWorldShader()");
            String seg = at < 0 ? "" : src.substring(at, Math.min(src.length(), at + 40000));
            String[][] need = {
                {"采样 3D 占用纹理",        "sampler3D uShadowTex"},
                {"按步数循环（14）",        "i<14"},
                {"近段分界（8）",           "i<8"},
                {"细/粗步长 0.7/3.8",       "?0.7:3.8"},
                {"起点固定 0.6（去抖动=确定性）", "float t=0.6;"},
                {"normal offset 0.10",      "n*0.10"},
                {"仰角门限 0.02",           "<=0.02"},
                {"半影回退公式",            "uShadowSoft/(1.0+t*uShadowFall)"},
                {"占用采样绕过 filter",     "texelFetch(uShadowTex,ip,0)"},
                {"越界即返回空",            "greaterThanEqual(ip,ivec3(uShadowSize))"},
                {"阴影乘到直射项 key",      "*0.78)*sh;"},
                {"高光也乘阴影",            "uLightTint*sh;"},
            };
            for (String[] pair : need) {
                if (seg.indexOf(pair[1]) < 0) {
                    drift++;
                    driftMsg.append("\n      ✗ 缺少／漂移：").append(pair[0])
                            .append("  期望片段 [").append(pair[1]).append(']');
                }
            }
        } catch (Exception ex) {
            drift = -1;
            driftMsg.append("  解析失败（防假绿，判 FAIL）：").append(ex);
        }
        boolean glslOk = drift == 0;
        System.out.println("SHADOW-GLSL     " + (glslOk ? "PASS" : "FAIL")
                + "  world FS 与 Java 侧常量/结构一致（" + (drift < 0 ? "解析失败" : (drift + " 处漂移）"))
                + driftMsg);
        if (!glslOk) fails++;

        if (fails > 0) {
            System.out.println("SHADOWCHECK FAIL  (" + fails + " 项失败)");
            System.exit(1);
        }
        System.out.println("SHADOWCHECK PASS");
    }

    private static String fmt(float v) {
        return String.format(java.util.Locale.US, "%.3f", v);
    }
}
