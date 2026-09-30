package render.lwjgl;

/**
 * 体素世界的方向光阴影（P4，2026-09-23）——<b>纯计算、无 GL、可 headless 验证</b>。
 *
 * <p><b>为什么用「体素光线步进」而不是传统阴影贴图</b>：本项目是流式窗口体素世界
 * （{@code 160×112×160}），把占用状态上传成一张 {@code GL_R8} 的 <b>3D 纹理</b>只有
 * <b>2.87 MB</b>；world VS 又已经输出世界坐标 {@code vWorld}（与 {@code mat} 同一坐标系，
 * 因为世界按窗口本地坐标索引）。于是"从片元沿太阳方向步进、查占用"就能直接实现 ——
 * 既没有阴影贴图的分辨率/份额化问题，也没有 acne 对 bias 的敏感依赖，
 * 而且对<b>悬垂、洞穴、树冠全部天然正确</b>（高度场做法会把悬崖下方错误地全黑）。
 *
 * <p><b>软阴影怎么来的</b>：单条硬光线只能给出 0/1。这里用 <b>PCSS 的精神但更便宜的形式</b>：
 * 一旦命中遮挡物，就用「遮挡物到当前片元的距离 {@code t}」连续地给出半影宽度 ——
 * {@code vis = 1 − soft / (1 + t·fall)}。物理直觉是太阳有约 0.53° 的视直径，
 * 于是遮挡物越远、半影在接收面上摊得越宽、阴影越浅；遮挡物贴着接收面时则近乎全黑（硬边）。
 * 再叠一个 <b>沿光线起点的抖动</b>（per-pixel hash）把步进台阶打散成颗粒状过渡。
 *
 * <p><b>⚠️ 与 GLSL 的一致性</b>：{@code Game.initWorldShader} 的 world FS 里有一份
 * <b>逐句同构</b>的实现。两份代码必须同步 —— {@code tools/ShadowCheck} 会解析 FS 源码
 * 断言步数/分段/步长/回退公式这些常量没有漂移（这正是 {@code EDGESHADER} 的纪律：
 * 编译通过 ≠ 语义正确）。
 *
 * <p><b>坐标系</b>：全部用「世界窗口本地坐标」，与 {@code World.mat} 和顶点 {@code aPos} 同一套。
 * 窗口滑动时 {@code mat} 内容整体重排，纹理与调用方一起重传即可，无需任何偏移换算。
 */
public final class VoxelShadow {

    private VoxelShadow() { }

    // ------------------------------------------------------------------
    // 步进参数（GLSL 侧必须逐项一致；ShadowCheck 会断言）
    // ------------------------------------------------------------------

    /** 光线最大步数。GLSL 侧写法 {@code for(int i=0;i<14;i++)}。 */
    public static final int STEPS = 14;
    /**
     * 前 {@code NEAR_STEPS} 步用细步长（贴着体素走），其后用粗步长（覆盖更远）。
     *
     * <p>⚠️ <b>细步长必须 &lt; 1 格</b>。首版用 1.0，结果会**跨过只有 1 格厚的遮挡物** ——
     * 实测同一个探针位置：CPU 在 t=3.15 命中，GPU 的采样序列 (2.6, 3.6) 恰好跳过，
     * 于是出现"门禁全绿、纹理也确认传上去了、但画面上就是没有阴影"这种最难查的形态。
     * 0.7 &lt; 1 保证任何非零厚度的遮挡物至少被采到一次。
     */
    public static final int NEAR_STEPS = 8;
    /** 细步长（格）。**必须 &lt; 1**：体素的最小厚度就是 1 格。 */
    public static final double NEAR_STEP = 0.7;
    /** 粗步长（格）。 */
    public static final double FAR_STEP = 3.8;
    /** 光线起点沿光方向的初始偏移（格）：跳过贴着表面的那一层，配合 normal offset 一起防自遮挡。 */
    public static final double START = 0.6;
    /** 起点抖动的幅度（格）：起点 = START + jitter * JITTER。把步进台阶打散成颗粒状半影。 */
    public static final double JITTER = 1.1;
    /** 沿法线抬起采样起点的距离（格）：normal offset，防斜面上出现自遮挡条纹。 */
    public static final double NORMAL_OFFSET = 0.10;
    /** 太阳低于该仰角（{@code L.y}）时不产生阴影：此时直射项本身已为 0，再算只是白烧 GPU。 */
    public static final double MIN_SUN_Y = 0.02;

    /** 覆盖范围（格）= 细段 + 粗段，仅用于文档/门禁自检。 */
    public static final double REACH = NEAR_STEPS * NEAR_STEP + (STEPS - NEAR_STEPS) * FAR_STEP;

    // ------------------------------------------------------------------

    /**
     * 占用值的"是"标记。
     *
     * <p>⚠️ <b>必须是 255 而不是 1</b>（2026-09-23 血案）。纹理是 {@code GL_R8}（归一化格式），
     * 上传的字节会被除以 255 变成 [0,1]：写 1 得到的采样值是 <b>0.0039</b>，
     * 而 GLSL 侧判据是 {@code voxOcc(p) > 0.5} ⇒ <b>恒判为空</b> ⇒ 阴影全灭。
     * 更坑的是 {@code glGetTexImage} 读回的是<b>原始字节</b>（1），与 CPU 数组比对<b>完全一致</b>，
     * 于是运行时不变量报 {@code readbackDiff=0}「一切正常」—— 一个完美的假绿。
     *
     * <p>教训：归一化纹理里的"布尔量"必须写成<b>量程端值</b>；而且校验必须覆盖
     * 「采样之后的数值量级」，不能只比原始字节。门禁 {@code SHADOW-SCALE} 现在锁死这一点。
     */
    public static final byte OCCUPIED = (byte) 0xFF;

    /** 占用查询的最小平局：与 3D 纹理 {@code GL_R8} 同布局（x 最快、y 次之、z 最慢）。 */
    public static int indexOf(int x, int y, int z, int sx, int sy) {
        return x + sx * (y + sy * z);
    }

    /**
     * 从世界 {@link core.world.World} 生成占用数组（1 = 不透明、挡光）。
     *
     * <p>判据用 {@code Block.opaque} —— 与块光 BFS（{@code World.computeLight}）<b>同一个字段</b>。
     * 这一点很重要：如果阴影用一个自定义的"实心"判据而光照用 {@code opaque}，
     * 就会出现"墙挡住了阳光但块光穿过去了"这种自相矛盾的画面。
     *
     * @return 长度 {@code sx*sy*sz} 的字节数组
     */
    public static byte[] buildOcc(core.world.World w) {
        byte[] occ = new byte[w.SX * w.SY * w.SZ];
        fillOcc(w, occ);
        return occ;
    }

    /** 同上，但只取世界的一个子范围（供门禁构造测试场景）。 */
    public static byte[] buildOcc(core.world.World w, int sx, int sy, int sz) {
        byte[] occ = new byte[sx * sy * sz];
        fillOcc(w, occ, sx, sy, sz);
        return occ;
    }

    /**
     * 把占用写进<b>调用方提供的数组</b>（长度须为 {@code sx*sy*sz}）。
     *
     * <p>渲染层用它复用同一块缓冲：世界每次编辑都要重传纹理，若每次都新分配 2.87 MB
     * 就是纯 GC 压力（而且大对象直接进老年代）。门禁则用它做"与 {@code mat} 逐格一致"的比对。
     */
    public static void fillOcc(core.world.World w, byte[] out) {
        fillOcc(w, out, w.SX, w.SY, w.SZ);
    }

    public static void fillOcc(core.world.World w, byte[] out, int sx, int sy, int sz) {
        int n = core.world.Blocks.count();
        boolean[] opq = new boolean[n];                    // 按 mat 索引的查表，避免逐格走 List.get
        for (int i = 0; i < n; i++) {
            core.world.Blocks.Block b = core.world.Blocks.byIndex(i);
            opq[i] = (b != null) && b.opaque;
        }
        for (int z = 0; z < sz; z++) {
            int base = sx * sy * z;
            for (int y = 0; y < sy; y++) {
                int row = base + sx * y;
                for (int x = 0; x < sx; x++) {
                    int m = w.mat[x][y][z];
                    out[row + x] = (m > 0 && m < n && opq[m]) ? OCCUPIED : 0;
                }
            }
        }
    }

    /** 判断采样点是否落在纹理覆盖范围之外（闭区间 [0,sx]×[0,sy]×[0,sz]）。 */
    public static boolean outside(double px, double py, double pz, int sx, int sy, int sz) {
        return px < 0.0 || py < 0.0 || pz < 0.0 || px > sx || py > sy || pz > sz;
    }

    /** 某点的占用值（越界返回 0 = 不遮挡）。 */
    public static int occupied(byte[] occ, int sx, int sy, int sz,
                               double px, double py, double pz) {
        if (outside(px, py, pz, sx, sy, sz)) return 0;
        int xi = (int) Math.floor(px), yi = (int) Math.floor(py), zi = (int) Math.floor(pz);
        // px == sx 恰好落在边界上 → 钳到最后一格（与 GL_CLAMP_TO_EDGE 一致）
        if (xi >= sx) xi = sx - 1;
        if (yi >= sy) yi = sy - 1;
        if (zi >= sz) zi = sz - 1;
        if (xi < 0 || yi < 0 || zi < 0) return 0;
        return occ[indexOf(xi, yi, zi, sx, sy)] & 0xFF;
    }

    /**
     * 太阳可见度：1 = 完全被照亮，0 = 完全在阴影里。
     *
     * <p><b>与 GLSL 的对应</b>（{@code Game.initWorldShader} 的 world FS，
     * 函数 {@code sunShadow} / 内联在 {@code key} 那一行）：
     * <pre>
     *   if (ly &lt;= 0.02) return 1;
     *   o   = wp + n * 0.10;
     *   t   = 0.6 + jitter * 1.1;
     *   loop i in [0,14):
     *     p = o + L * t;
     *     越界 -&gt; break;
     *     命中 -&gt; return 1 - soft / (1 + t * fall);
     *     t += (i &lt; 5) ? 1.0 : 3.0;
     *   return 1;
     * </pre>
     *
     * @param soft 半影基准深度（GLSL 默认 0.85）：贴近接收面的遮挡物把阴影压到 {@code 1-soft}
     * @param fall 半影距离衰减（GLSL 默认 0.22）：越大 → 远处遮挡物投下的阴影越快变浅
     * @param jitter 起点抖动 [0,1)；GLSL 用 {@code h31(gl_FragCoord.xy)}，门禁传定值以保证可复现
     */
    public static float sunVisibility(byte[] occ, int sx, int sy, int sz,
                                      double wx, double wy, double wz,
                                      double nx, double ny, double nz,
                                      double lx, double ly, double lz,
                                      double soft, double fall, double jitter) {
        if (ly <= MIN_SUN_Y) return 1f;
        double ox = wx + nx * NORMAL_OFFSET;
        double oy = wy + ny * NORMAL_OFFSET;
        double oz = wz + nz * NORMAL_OFFSET;
        double t = START + jitter * JITTER;
        for (int i = 0; i < STEPS; i++) {
            double px = ox + lx * t;
            double py = oy + ly * t;
            double pz = oz + lz * t;
            if (outside(px, py, pz, sx, sy, sz)) break;
            if (occupied(occ, sx, sy, sz, px, py, pz) > 0) {
                double hard = 1.0 - soft / (1.0 + t * fall);
                return (float) (hard < 0.0 ? 0.0 : hard);
            }
            t += (i < NEAR_STEPS) ? NEAR_STEP : FAR_STEP;
        }
        return 1f;
    }

    // ------------------------------------------------------------------
    // 门禁/调试辅助
    // ------------------------------------------------------------------

    /** 便利：{@code L} 未归一化时先归一化（GLSL 侧 {@code main} 里对 {@code uLightDir} 做了 normalize）。 */
    public static double[] unit(double x, double y, double z) {
        double n = Math.sqrt(x * x + y * y + z * z);
        if (n < 1e-12) return new double[]{0, 1, 0};
        return new double[]{x / n, y / n, z / n};
    }
}
