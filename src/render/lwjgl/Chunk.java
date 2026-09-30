package render.lwjgl;

import core.world.Autotile;
import core.world.Blocks;
import core.world.EdgeAtlas;
import core.world.FaceCull;
import core.world.World;

import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.lwjgl.opengl.GL33.*;
import static org.lwjgl.system.MemoryUtil.*;

/**
 * 一个 16×SY×16 的渲染区块。持有独立 VAO/VBO，仅重建本块网格。
 *
 * 块大小 16（XZ 方向），Y 全高。脏区增量由 World.dirtyChunks 驱动：
 * 只有被标记脏的区块才走 {@link #rebuild(World)}，其余帧直接复用既有 VBO。
 *
 * 几何与明暗系数与改造前 Game.uploadMesh 完全一致（air/emit 抽成 Game 的静态辅助），
 * 因此渲染结果与整帧重建逐顶点相同，无任何视觉/物理漂移。
 *
 * PERF-RENDER 第三步：块内 greedy meshing（同材质同朝向共面相邻面合并为大四边形）。
 * 默认关闭（USE_GREEDY=false），关闭时 {@link #rebuild} 完全走旧逐面路径，零行为差异；
 * 开启后仅把「暴露面集合」用更少的大四边形表示，暴露面集合逐面等价（见 GreedyCheck）。
 */
public final class Chunk {

    static final int SIZE = 16;

    /** greedy meshing 总开关（默认关 = 旧逐面路径）。由 Game.GREEDY_MESH 在启动时赋值，可一键回退。 */
    static boolean USE_GREEDY = false;

    /**
     * 本块当前渲染的**窗口本地块坐标**（＝它在 {@code Game.chunks[cx][cz]} 里的下标）。
     *
     * <p>⚠️ 非 final：窗口平移时整块网格会被<b>复用</b>，此时对象在原地"搬到"新槽
     * （新本地 = 旧本地 − 位移），见 {@link #relocateMesh}。{@code buildMesh} 用它决定
     * **从哪读世界**，而增量索引/脏块键也都是这套本地坐标 ⇒ 它与 {@code Game.chunks} 的
     * 下标必须永远相等（{@link #relocateArray} 是唯一同时维护两者的地方）。
     */
    public int cx, cz;
    /**
     * 当前**已上传**顶点所在的"烘焙帧"到当前帧的整数块偏移（渲染时由 {@code uChunkShift} 加回）。
     *
     * <p><b>为什么需要它</b>：顶点坐标是烘焙在**窗口本地**坐标系里的绝对位置。窗口一平移，
     * 留在窗内的块内容都换了本地位置 ⇒ 顶点必须整体平移 −Δ，否则老网格会画在旧位置上。
     * 与其重建（5~15ms/块）不如只记一个整数偏移 —— 顶点数据一个字节都不用动，
     * 且"整数偏移 + 顶点坐标"的浮点加法是**精确**的（见 {@code MeshShiftCheck} 的逐 float 断言）。
     *
     * <p>量级：偏移只在**该网格被复用的期间**累积（一块被复用最多 CX−1 次就滑出窗口被回收），
     * 故 |偏移| ≤ 窗口宽度量级，不存在浮点精度退化。
     *
     * <p>生命周期：{@link #applyMesh}（新网格总是烘焙在当前帧）归零；{@link #resetMeshFrame} 归零。
     */
    public int meshOffX, meshOffZ;
    public int vao, vbo;                // 不透明 pass
    public int vaoT, vboT;              // 半透明 pass（水/玻璃）：后画 + 混合 + 不写深度
    public int ebo, eboT;               // 索引缓冲（每四边形 4 顶点 + 6 索引）
    public int faceCount = 0;           // 不透明 pass 四边形数
    public int faceCountT = 0;          // 半透明 pass 四边形数
    public int maxY = 0;                // 立项 E：列顶包络（遮挡剔除；纯渲染派生，不进指纹）
    // 泰拉瑞亚缺口②：发光源网格（仅 LAMP/FIRE/GOLD 的面），供选择性泛光提取。与上面同 13-float 布局。
    public int vaoE, vboE, eboE;
    public int faceCountE = 0;

    public Chunk(int cx, int cz) {
        this.cx = cx;
        this.cz = cz;
    }

    /**
     * 把本块**连同它的网格**搬到新槽（窗口平移 dcx/dcz 个块时，重叠区里的每一块都走这里）。
     *
     * <p>内容的世界位置没变 ⇒ 顶点数据完全有效，只需：① 下标跟着换（{@code cx -= dcx}）；
     * ② 记下"顶点的坐标系落后了 (dcx,dcz) 个块"，渲染时由 {@code uChunkShift} 补回来。
     *
     * @param dcx 窗口原点的块位移（与 {@code World.stepShift} 的 shiftDCX 同号同值）
     */
    public void relocateMesh(int dcx, int dcz) {
        this.cx -= dcx;
        this.cz -= dcz;
        this.meshOffX -= dcx * SIZE;
        this.meshOffZ -= dcz * SIZE;
    }

    /**
     * 把本块**重新指派**到某个槽，并声明它当前的网格已作废（偏移归零、等重建）。
     *
     * <p>只用于"滑出窗口的块被回收、顶替新进入的条带"这一条路径：顶点即将由
     * {@link #buildMesh} 在**当前帧**重算，故偏移必须归零。⚠️ 调用方必须同时把它标脏，
     * 否则它会带着上一块地形的顶点数据画在新位置。
     */
    public void resetMeshFrame(int cx, int cz) {
        this.cx = cx;
        this.cz = cz;
        this.meshOffX = 0;
        this.meshOffZ = 0;
    }

    /**
     * 窗口平移时按位移**旋转整个块数组**（唯一同时维护 {@code chunks[lx][lz] ↔ chunk.cx/cz}
     * 这条不变量的地方；{@code Game.updateMeshes} 与门禁 {@code MeshShiftCheck} 共用它，
     * 以免"生产代码"与"被证明的代码"是两份实现）。
     *
     * <p>规则：新槽 (lx,lz) 接管旧槽 (lx+dcx, lz+dcz) 的对象（内容正是搬过来的那一块）；
     * 旧槽里那些"源越界"的对象（即滑出窗口的一侧）被回收，顶替新进入的一侧 —— 于是
     * <b>对象总数恒定、不分配也不删除 GL 资源</b>，一次平移只做一次引用置换。
     *
     * <p>⚠️ 前置条件：{@code |dcx| < 列数 且 |dcz| < 行数}（零重叠的平移必须走"全窗重建"路径，
     * 否则这里会算出越界下标）。
     *
     * @return 新的块数组（旧的不要再使用）
     */
    public static Chunk[][] relocateArray(Chunk[][] old, int dcx, int dcz) {
        int nx = old.length, nz = old[0].length;
        Chunk[][] neu = new Chunk[nx][nz];
        java.util.ArrayDeque<Chunk> pool = new java.util.ArrayDeque<Chunk>();
        // ① 先收**滑出窗口**的对象：判据是"它自己的新槽"越界（新槽 = 旧槽 − d），
        //    ⚠️ 不是"某个目标槽的源越界" —— 两者数量相同、成员不同。搞混的后果是同一对象被放进
        //    两个槽（一个来自重叠区、一个来自回收池）⇒ 它的 cx 被改两次、漂到窗口外，
        //    而真正该回收的那个对象被整个丢掉（GL 缓冲泄漏 + 那个槽的地形消失）。
        //    这正是 MESHSHIFT 门禁第一版抓到的错误。
        for (int i = 0; i < nx; i++)
            for (int j = 0; j < nz; j++) {
                int dx = i - dcx, dz = j - dcz;
                if (dx < 0 || dx >= nx || dz < 0 || dz >= nz) pool.add(old[i][j]);
            }
        // ② 重叠区：对象就地"搬到"新槽（含网格帧偏移的累积）
        for (int lx = 0; lx < nx; lx++)
            for (int lz = 0; lz < nz; lz++) {
                int sx = lx + dcx, sz = lz + dcz;
                if (sx >= 0 && sx < nx && sz >= 0 && sz < nz) {
                    Chunk c = old[sx][sz];
                    c.relocateMesh(dcx, dcz);
                    neu[lx][lz] = c;
                }
            }
        // ③ 新进入的槽：用回收对象顶上（清烘焙帧 ⇒ 等重建）
        for (int lx = 0; lx < nx; lx++)
            for (int lz = 0; lz < nz; lz++)
                if (neu[lx][lz] == null) {
                    Chunk c = pool.poll();
                    c.resetMeshFrame(lx, lz);
                    neu[lx][lz] = c;
                }
        return neu;
    }

    /**
     * 创建本块两组 VAO/VBO/EBO（不透明 + 半透明）并配置顶点属性（13 float：pos.xyz + col.rgb + normal.xyz + wind.1 + uv.2 + lamp.1，stride=52；立项 C 块光）。
     *
     * <p><b>索引化绘制（PERF）</b>：每个四边形只发 <b>4 个顶点</b>，用索引 {0,1,2, 0,2,3} 组成两个三角形。
     * 这与旧实现「发 6 个顶点、顺序 (V0,V1,V2)(V0,V2,V3)」是<b>逐三角形完全相同</b>的几何
     * （见 {@code emitQuad}/Game.emit 的 t1/t2 角点序）——所以是<b>零视觉变化</b>的节省：
     * 顶点着色调用 −33%、顶点缓冲字节数 −33%。</p>
     */
    public void init() {
        vao = glGenVertexArrays();  vbo = glGenBuffers();  ebo = glGenBuffers();
        configAttribs(vao, vbo, ebo);
        vaoT = glGenVertexArrays(); vboT = glGenBuffers(); eboT = glGenBuffers();
        configAttribs(vaoT, vboT, eboT);
        vaoE = glGenVertexArrays(); vboE = glGenBuffers(); eboE = glGenBuffers();
        configAttribs(vaoE, vboE, eboE);
    }

    private static void configAttribs(int vao, int vbo, int ebo) {
        glBindVertexArray(vao);
        glBindBuffer(GL_ARRAY_BUFFER, vbo);
        glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, ebo);   // 记进 VAO：绘制时只需 bindVertexArray
        int stride = VERT_FLOATS * 4;
        glVertexAttribPointer(0, 3, GL_FLOAT, false, stride, 0L); glEnableVertexAttribArray(0);
        glVertexAttribPointer(1, 3, GL_FLOAT, false, stride, 3L * 4); glEnableVertexAttribArray(1);
        glVertexAttribPointer(2, 3, GL_FLOAT, false, stride, 6L * 4); glEnableVertexAttribArray(2);
        glVertexAttribPointer(3, 1, GL_FLOAT, false, stride, 9L * 4); glEnableVertexAttribArray(3);
        glVertexAttribPointer(4, 2, GL_FLOAT, false, stride, 10L * 4); glEnableVertexAttribArray(4);   // 立项 A：图集 UV
        glVertexAttribPointer(5, 1, GL_FLOAT, false, stride, 12L * 4); glEnableVertexAttribArray(5);   // 立项 C：块光
        glVertexAttribPointer(6, 3, GL_FLOAT, false, stride, 13L * 4); glEnableVertexAttribArray(6);   // 步骤 4：交界过渡（edgeU, edgeV, infl）
        glBindVertexArray(0);
    }

    /** 顶点布局 float 数：pos3+col3+normal3+wind1+uv2+lamp1+edge3 = 16（stride 64）。 */
    public static final int VERT_FLOATS = 16;

    /**
     * 重建本块网格。按 {@link #USE_GREEDY} 分发：
     *  - false（默认）：逐面路径 {@link #rebuildPerFace}，与改造前完全一致；
     *  - true：贪婪网格 {@link #rebuildGreedy}，同类型同朝向共面相邻面合并为大四边形。
     */
    public void rebuild(World w) {
        if (USE_GREEDY) { this.maxY = computeMaxY(w); rebuildGreedy(w); return; }   // greedy 路径未拆分（默认关闭）
        // 第三十一批（A1，为"worker 构建网格"铺路）：把「**纯计算**」与「**GL 上传**」彻底切开 ——
        //   ① buildMesh 只读世界、只写 direct 缓冲，**绝不碰 GL** ⇒ 将来可整段搬到 worker 线程；
        //   ② applyMesh 只做 glBufferData/glVertexAttribPointer ⇒ 必须留在主线程。
        // 本批**仍同步调用**（行为与改造前逐字节相同），只是把接缝切出来 ——
        // 这一步属于技能规则 12 的"可证明等价"那一半，下一步才引入并发。
        applyMesh(buildMesh(w));
    }

    /**
     * 一帧网格的<b>纯计算结果</b>：三个 pass 的 direct 顶点缓冲 + 各自四边形数 + 列顶包络。
     *
     * <p>由 {@link #buildMesh}（任意线程）产出、由 {@link #applyMesh}（<b>主线程</b>）消费；
     * 消费后必须 {@link #release()} —— 缓冲是 {@code memAlloc} 的<b>非 GC 内存</b>，
     * 且任务被丢弃（如世界平移）时也要 release，否则泄漏。
     */
    public static final class MeshData {
        public FloatBuffer opaque, trans, emissive;
        public int quadsO, quadsT, quadsE, maxY;

        /** 释放三个 direct 缓冲（重复调用安全）。 */
        public void release() {
            if (opaque != null) { memFree(opaque); opaque = null; }
            if (trans != null) { memFree(trans); trans = null; }
            if (emissive != null) { memFree(emissive); emissive = null; }
        }
    }

    /**
     * 纯计算：读世界 → 生成三个 pass 的顶点缓冲（<b>绝不调用 GL</b>，将来跑在 worker 线程）。
     *
     * <p><b>并发前提（第三十二批逐条核实）</b>：本方法<b>只读</b>——{@code w.mat[x][y][z]} 直读、
     * {@code w.getBlock}（实测是<b>纯数组读</b>，不触发惰性索引重建）、{@code FaceCull}/{@code Autotile}/
     * {@code EdgeAtlas} 的纯函数；且<b>不再写任何实例字段</b>（maxY 改为返回值，见下）。
     * ⇒ 与主线程的 sim 写入之间最多读到"稍旧"的地形（视觉无害），<b>不会污染世界状态</b>。
     * 网格是渲染层派生量，本就不进 {@code hashState}。
     *
     * <p>⚠️ 唯一<b>刻意接受</b>的近似：{@code World.meta} / {@code World.blockState}（LinkedHashMap）
     * 由主线程写、此处由 worker 读 —— 最坏读到 null ⇒ "某帧某台阶朝向画错/某导线不亮"，下一帧重建自愈。
     * 详见 {@link MeshBuilder} 的并发安全论证。
     */
    public MeshData buildMesh(World w) {
        MeshData m = new MeshData();
        // ⚠️ 第三十二批 A2：`computeMaxY` 改成**返回值**而不是写 `this.maxY` ——
        // 因为本方法现在跑在 worker 上，而 maxY 是 Chunk 的实例字段：若 worker 直接写它，
        // 同一块被重复提交（提交后在飞期间又被编辑）时两个线程会互相覆盖，可能把一个**偏小**的
        // 包络应用上去 → 遮挡剔除会误剔掉本该画的一块（看得见的空洞）。
        // 改成"算完随 MeshData 一起交给主线程，再由 applyMesh 落进字段" ⇒ worker 全程只读实例状态。
        m.maxY = computeMaxY(w);
        int x0 = cx * SIZE, x1 = x0 + SIZE;
        int z0 = cz * SIZE, z1 = z0 + SIZE;
        int nO = 0, nT = 0;
        for (int x = x0; x < x1; x++)
            for (int y = 0; y < w.SY; y++)
                for (int z = z0; z < z1; z++) {
                    int bi = w.mat[x][y][z];
                    if (bi == Blocks.AIR.index) continue;
                    boolean tr = Blocks.byIndex(bi).translucent;
                    if (bi == Blocks.FLOWER.index) { if (tr) nT += 2; else nO += 2; continue; }   // 装饰植被：交叉面（2 quads）
                    int c = 0;
                    for (int d = 0; d < 6; d++)
                        if (FaceCull.visible(w, bi, x + FaceCull.DX[d], y + FaceCull.DY[d], z + FaceCull.DZ[d])) c++;
                    if (tr) nT += c; else nO += c;
                }
        FloatBuffer bO = memAllocFloat(Math.max(1, nO * 4 * VERT_FLOATS));   // 索引化：每四边形 4 顶点
        FloatBuffer bT = memAllocFloat(Math.max(1, nT * 4 * VERT_FLOATS));
        for (int x = x0; x < x1; x++)
            for (int y = 0; y < w.SY; y++)
                for (int z = z0; z < z1; z++) {
                    int bi = w.mat[x][y][z];
                    if (bi == Blocks.AIR.index) continue;
                    Blocks.Block blk = Blocks.byIndex(bi);
                    FloatBuffer buf = blk.translucent ? bT : bO;   // 分区：水/玻璃 → 透明 pass
                    if (blk == Blocks.FLOWER) { emitCross(buf, w, x, y, z, blk); continue; }   // 植被交叉面（MC 样式 X 草丛）
                    if (FaceCull.visible(w, bi, x + 1, y, z)) Game.emit(buf, w, x, y, z, +1, 0, 0, blk, 0.80f);
                    if (FaceCull.visible(w, bi, x - 1, y, z)) Game.emit(buf, w, x, y, z, -1, 0, 0, blk, 0.70f);
                    if (FaceCull.visible(w, bi, x, y + 1, z)) Game.emit(buf, w, x, y, z,  0, 1, 0, blk, 1.00f);
                    if (FaceCull.visible(w, bi, x, y - 1, z)) Game.emit(buf, w, x, y, z,  0,-1, 0, blk, 0.55f);
                    if (FaceCull.visible(w, bi, x, y, z + 1)) Game.emit(buf, w, x, y, z,  0, 0, 1, blk, 0.85f);
                    if (FaceCull.visible(w, bi, x, y, z - 1)) Game.emit(buf, w, x, y, z,  0, 0,-1, blk, 0.65f);
                }
        bO.flip(); bT.flip();
        m.opaque = bO; m.quadsO = nO;
        m.trans = bT; m.quadsT = nT;
        m.emissive = buildEmissiveBuf(w, m);   // 泰拉瑞亚缺口②：逐面路径也要建发光源网格
        return m;
    }

    /**
     * 应用：把纯计算好的网格上传到 GL（<b>必须主线程</b>），并更新三个面数计数，最后释放缓冲。
     *
     * <p>与改造前的 {@code rebuildPerFace} 尾部<b>逐语句等价</b> —— 只是消费的是外部传入的 MeshData。
     */
    public void applyMesh(MeshData m) {
        upload(vao, vbo, ebo, m.opaque, m.quadsO);
        upload(vaoT, vboT, eboT, m.trans, m.quadsT);
        upload(vaoE, vboE, eboE, m.emissive, m.quadsE);
        faceCount = m.quadsO; faceCountT = m.quadsT; faceCountE = m.quadsE;
        this.maxY = m.maxY;
        // 新网格总是用**当前**的 cx/cz 烘焙的（提交到应用之间窗口原点不可能变 —— 变了会 bump epoch
        // 把结果丢弃）⇒ 它的坐标系就是当前帧，偏移必须归零，否则会被二次平移。
        this.meshOffX = 0;
        this.meshOffZ = 0;
        m.release();
    }

    /** 第五批：通电导线（WIRE, meta=1）的发光色 —— 红辉（导线本身无静态 emissive，靠 meta 点亮）。 */
    private static final float[] WIRE_GLOW = { 0.95f * 1.5f, 0.10f * 1.5f, 0.08f * 1.5f };

    /**
     * 该格是否是"<b>通电的红石元件</b>"—— 应<b>顶点提亮</b>且加入<b>发光网格</b>。
     *
     * <p><b>唯一定义处</b>：逐面路径（{@code Game.putV}）与贪婪路径（{@link #putV}）必须用同一谓词，
     * 否则两条路径的观感会走散。⚠️ 第六批曾把提亮只写进 {@link #putV}（贪婪专用、默认关闭）
     * → 默认路径下导线只泛光不提亮；第九批顺手修掉。
     *
     * <p>判据走 {@link core.systems.RedstoneLogicSystem#strengthAt}（"一个格有多强"的唯一来源）——
     * 于是导体读 WireSystem 写进 {@code meta} 的强度、元件读自身电源强度，无需在这里各写一份名单。
     */
    static boolean isRedstoneLit(World w, int bi, int x, int y, int z) {
        return isRedstoneElement(bi)
                && core.systems.RedstoneLogicSystem.strengthAt(w, x, y, z) > 0;
    }

    // ================================================================ 平滑块光（2026-09-30）
    /**
     * 块光平滑量：**0 = 退回旧的「单格采样」（逐字节等价）**，1 = 全平滑。由 {@code -Dbw.smoothlight} 设定。
     *
     * <p>本作的光照贴图由两块拼成（见 world FS 的 {@code skyTerm} / {@code lampV}）：天光那一半是
     * 逐顶点 AO + 方向明度（本就有渐变），**块光那一半原先却直接取单格光场** ⇒ 顶点一跨格就阶跃。
     * 实测灯下 6 格内光场为 1.0→0.563→0.313→0.172→0.094→0.063→0.031（≈×0.55/格）⇒ 顶点色台阶
     * 0.1~0.25，一眼是**方块状照明**而非"光晕"。
     */
    public static float SMOOTH_LIGHT = 1f;

    /**
     * 采样顶点处的**块光**（世界光场 {@link World#lightAt}）—— 两条 emit 路径的**唯一定义处**。
     *
     * <p><b>算法</b>（MC Smooth Lighting 的等价形式）：顶点落在面外层平面上、且面内两轴位于**格边界**
     * ⇒ 它同时接触该两轴上的 {@code {k-1, k}} 两格 ⇒ 共 <b>2×2 格等权平均</b>。
     *
     * <p><b>为什么不取"最近格"</b>：那正是旧行为，会给出方块硬边。<b>为什么不加权</b>：顶点恰在四格
     * 交点、四格等距 ⇒ 等权；加权反而引入不属于本尺度的人为梯度。
     *
     * <p><b>越界</b>：{@code lightAt} 对窗口外返回 0（光场是窗口本地的）⇒ 窗口边缘顶点略暗，与旧实现
     * 行为一致（旧版同样返回 0），且该处本就有雾与边缘饱和度兜底。
     *
     * <p><b>零漂移</b>：{@code lightGrid} 是渲染派生缓存（不进 {@code hashState}）。采样点随法线偏移，
     * 平移后光场随 {@code mat} 旋转（增量光照），2×2 均值也随之一致 ⇒ **复用网格仍与全量重建逐字节
     * 等价**（{@code MeshShiftCheck} 守这条）。{@code amt=0} 时直接返回单格值 ⇒ 与改动前等价。
     */
    static float blockLight(World w, float vx, float vy, float vz,
                            float nx, float ny, float nz, float amt) {
        float ox = vx + nx * 0.5f, oy = vy + ny * 0.5f, oz = vz + nz * 0.5f;
        int ix = (int) Math.floor(ox), iy = (int) Math.floor(oy), iz = (int) Math.floor(oz);
        float single = w.lightAt(ix, iy, iz);
        if (amt <= 0.002f) return single;
        float quad;
        if (Math.abs(nx) > 0.5f) {          // ±X 面：面内轴 = y, z
            quad = 0.25f * (single + w.lightAt(ix, iy - 1, iz)
                    + w.lightAt(ix, iy, iz - 1) + w.lightAt(ix, iy - 1, iz - 1));
        } else if (Math.abs(ny) > 0.5f) {   // ±Y 面：面内轴 = x, z
            quad = 0.25f * (single + w.lightAt(ix - 1, iy, iz)
                    + w.lightAt(ix, iy, iz - 1) + w.lightAt(ix - 1, iy, iz - 1));
        } else {                            // ±Z 面：面内轴 = x, y
            quad = 0.25f * (single + w.lightAt(ix - 1, iy, iz)
                    + w.lightAt(ix, iy - 1, iz) + w.lightAt(ix - 1, iy - 1, iz));
        }
        return single + (quad - single) * amt;
    }

    /** 是否属于"红石元件"（通电时要提亮/发光的一类方块）。 */
    static boolean isRedstoneElement(int bi) {
        return bi == Blocks.WIRE.index || bi == Blocks.LAMP.index
                || bi == Blocks.LEVER.index || bi == Blocks.BUTTON.index
                || bi == Blocks.PLATE.index || bi == Blocks.REPEATER.index
                || bi == Blocks.COMPARATOR.index || bi == Blocks.DISPENSER.index
                || bi == Blocks.PISTON.index || bi == Blocks.OBSERVER.index;
    }

    /**
     * 该格是否是「装了水的坩埚」（{@code CAULDRON} 且 {@code meta > 0}）—— 应<b>偏蓝提亮</b>。
     *
     * <p>第十二批：坩埚的水位住在 {@code meta}（0..3），但 <b>tile 是烘好的、不随 meta 变</b>。
     * 最省的可视化就是<b>顶点色偏移</b>（与通电元件的提亮同一套手法）——玩家一眼能看出"这口锅有水"。
     *
     * <p>与 {@link #isRedstoneLit} 一样是<b>唯一定义处</b>：逐面路径（{@code Game.putV}）与贪婪路径
     * （{@link #putV}）都必须调它，否则两条路径的观感会走散（第八批踩过这个坑）。
     */
    static boolean isFilledCauldron(World w, int bi, int x, int y, int z) {
        return bi == Blocks.CAULDRON.index && w.getMeta(x, y, z) > 0;
    }

    /**
     * 通电提亮系数（1.0 = 不提亮）。<b>亮度随信号强度变化</b> → 导线能一眼读出"几级信号"
     * （第九批引入强度后才有意义）：满强度 = 1.9×（与第六批的观感一致），强度 1 ≈ 1.06×。
     */
    static float redstoneBrightness(World w, int bi, int x, int y, int z) {
        if (!isRedstoneElement(bi)) return 1f;
        int st = core.systems.RedstoneLogicSystem.strengthAt(w, x, y, z);
        if (st <= 0) return 1f;
        int ms = core.systems.WireSystem.MAX_SIGNAL;
        float k = st >= ms ? 1f : st / (float) ms;
        return 1f + 0.9f * k;
    }

    /**
     * 该格是否发光、以及发光色（不发光返回 null）。
     * 静态发光源走 {@link Blocks#isEmissive}；**通电的红石元件**（{@link #isRedstoneLit}）也算发光
     * → 元件会随电路亮/灭。
     */
    private static float[] emissiveOf(World w, int bi, int x, int y, int z) {
        if (Blocks.isEmissive(bi)) return Blocks.byIndex(bi).emissive;
        if (isRedstoneLit(w, bi, x, y, z)) return WIRE_GLOW;
        return null;
    }

    /** 泰拉瑞亚缺口②：收集本块内发光源（LAMP/FIRE/GOLD + 通电导线）的暴露面，写入发光网格（无 AO/无光）。 */
    // （第三十一批 A1：原 `rebuildPerFace` 已拆成 `buildMesh`（纯计算）+ `applyMesh`（GL 上传），
    //   两者均在文件上方；此处不再保留旧实现，避免同一段发射逻辑出现两份。）

    /**
     * <b>贪婪路径专用</b>（{@code USE_GREEDY} 默认关闭）：就地构建并上传发光网格 —— 保持改造前行为。
     * 贪婪路径本批**未拆分**（它默认关闭、且不是热点），故这里保留"算完就传"的原样式。
     * ⚠️ 它必须与 {@link #buildEmissiveBuf} 同源：否则两条路径的发光网格会走散。
     */
    private void buildEmissive(World w) {
        MeshData m = new MeshData();
        m.emissive = buildEmissiveBuf(w, m);
        upload(vaoE, vboE, eboE, m.emissive, m.quadsE);
        faceCountE = m.quadsE;
        m.release();
    }

    /**
     * 纯计算：生成<b>发光 pass</b> 的顶点缓冲（原 `buildEmissive`，第三十一批改为不碰 GL）。
     * {@code m.quadsE} 就地回填；返回的 direct 缓冲由调用方负责释放（见 {@link MeshData#release()}）。
     */
    private FloatBuffer buildEmissiveBuf(World w, MeshData m) {
        int x0 = cx * SIZE, x1 = x0 + SIZE;
        int z0 = cz * SIZE, z1 = z0 + SIZE;
        int nE = 0;
        for (int x = x0; x < x1; x++)
            for (int y = 0; y < w.SY; y++)
                for (int z = z0; z < z1; z++) {
                    int bi = w.mat[x][y][z];
                    if (emissiveOf(w, bi, x, y, z) == null) continue;
                    for (int d = 0; d < 6; d++)
                        if (FaceCull.visible(w, bi, x + FaceCull.DX[d], y + FaceCull.DY[d], z + FaceCull.DZ[d])) nE++;
                }
        FloatBuffer bE = memAllocFloat(Math.max(1, nE * 4 * VERT_FLOATS));
        for (int x = x0; x < x1; x++)
            for (int y = 0; y < w.SY; y++)
                for (int z = z0; z < z1; z++) {
                    int bi = w.mat[x][y][z];
                    float[] e = emissiveOf(w, bi, x, y, z);
                    if (e == null) continue;
                    if (FaceCull.visible(w, bi, x + 1, y, z)) emitFaceE(bE, x, y, z, +1, 0, 0, e);
                    if (FaceCull.visible(w, bi, x - 1, y, z)) emitFaceE(bE, x, y, z, -1, 0, 0, e);
                    if (FaceCull.visible(w, bi, x, y + 1, z)) emitFaceE(bE, x, y, z,  0, 1, 0, e);
                    if (FaceCull.visible(w, bi, x, y - 1, z)) emitFaceE(bE, x, y, z,  0,-1, 0, e);
                    if (FaceCull.visible(w, bi, x, y, z + 1)) emitFaceE(bE, x, y, z,  0, 0, 1, e);
                    if (FaceCull.visible(w, bi, x, y, z - 1)) emitFaceE(bE, x, y, z,  0, 0,-1, e);
                }
        bE.flip();
        m.quadsE = nE;
        return bE;
    }

    /** 发射单个发光面（4 顶点，索引化）；颜色 = emissive（>1 以过亮部阈值），其余属性占位 0。 */
    private static void emitFaceE(FloatBuffer b, int x, int y, int z, int dx, int dy, int dz, float[] e) {
        float x0 = x, x1 = x + 1, y0 = y, y1 = y + 1, z0 = z, z1 = z + 1;
        float[][] v4;
        if (dx == 1)       v4 = new float[][]{{x1,y0,z0},{x1,y0,z1},{x1,y1,z1},{x1,y1,z0}};
        else if (dx == -1) v4 = new float[][]{{x0,y0,z1},{x0,y0,z0},{x0,y1,z0},{x0,y1,z1}};
        else if (dy == 1)  v4 = new float[][]{{x0,y1,z1},{x1,y1,z1},{x1,y1,z0},{x0,y1,z0}};
        else if (dy == -1) v4 = new float[][]{{x0,y0,z0},{x1,y0,z0},{x1,y0,z1},{x0,y0,z1}};
        else if (dz == 1)  v4 = new float[][]{{x0,y0,z1},{x1,y0,z1},{x1,y1,z1},{x0,y1,z1}};
        else               v4 = new float[][]{{x1,y0,z0},{x0,y0,z0},{x0,y1,z0},{x1,y1,z0}};
        for (int i = 0; i < 4; i++)
            b.put(v4[i][0]).put(v4[i][1]).put(v4[i][2]).put(e[0]).put(e[1]).put(e[2])
             .put(0f).put(1f).put(0f).put(0f).put(0f).put(0f).put(0f)
             .put(0f).put(0f).put(0f);   // 步骤 4：edge 属性占位（发光面不参与交界过渡）
    }

    /** 本块 16×16 列的最高非空格（遮挡剔除的地形包络；保守方向：列内有高物即取高值 → 少剔不误剔）。
     *  <b>返回</b>而不是写字段 —— 本方法现在由 worker 调用，见 {@link #buildMesh} 的说明。 */
    private int computeMaxY(World w) {
        int x0 = cx * SIZE, z0 = cz * SIZE, top = 0;
        for (int x = x0; x < x0 + SIZE; x++)
            for (int z = z0; z < z0 + SIZE; z++)
                for (int y = w.SY - 1; y > top; y--) {
                    if (w.mat[x][y][z] != Blocks.AIR.index) { if (y > top) top = y; break; }
                }
        return top;
    }

    /**
     * 逐面路径（默认与回退）：遍历本块范围，对每个「面可见」的朝向 emit。
     * 面可见性由 {@link FaceCull#visible} 判定（邻居 AIR/越界 → 可见；邻居半透明且与本类不同 → 可见，看穿）。
     * 并按方块是否半透明<b>分区</b>写入两个缓冲：不透明 pass 与透明 pass（水/玻璃）。
     * 跨块相邻面仍读全局 world 判定，保证边界无缝。
     */
    // （第三十一批 A1：原 `rebuildPerFace` 已整体拆成 `buildMesh`（纯计算）+ `applyMesh`（GL 上传），
    //   两者都在文件上方。此处不再保留旧实现 —— 否则同一段发射逻辑会有两份，
    //   而"零调用成员"审计会把这份死代码抓出来。）

    /**
     * 无头可测：索引缓冲内容 —— 每四边形 6 个索引 {4q, 4q+1, 4q+2, 4q, 4q+2, 4q+3}。
     * 顶点已按 V0..V3 顺序发出 → 与旧「6 顶点发射序 (V0,V1,V2)(V0,V2,V3)」<b>逐三角形一致</b>。
     */
    public static int[] quadIndices(int quads) {
        int[] idx = new int[quads * 6];
        for (int q = 0, o = 0; q < quads; q++) {
            int v = q * 4;
            idx[o++] = v; idx[o++] = v + 1; idx[o++] = v + 2;
            idx[o++] = v; idx[o++] = v + 2; idx[o++] = v + 3;
        }
        return idx;
    }

    /**
     * 上传顶点缓冲 + 索引缓冲到 (vao,vbo,ebo)。{@code quads} = 本 pass 的四边形数（索引数 = quads*6）。
     *
     * <p><b>运行时不变量</b>：顶点缓冲内的顶点数必须恰为 {@code quads*4}。若某条发射路径忘了去掉重复顶点
     * （旧的 6 顶点写法），会立刻在这里报错，而不是让 GPU 读到越界索引——把这类错误变成"当场可见"。</p>
     */
    private static void upload(int vao, int vbo, int ebo, FloatBuffer buf, int quads) {
        if (buf.remaining() / VERT_FLOATS != quads * 4) {
            // 响亮报错（2026-09-21 事故复盘）：症状是"地形/花丛随机出现空洞"，而唯一的证据就是本行。
            // 历史上它只是 System.err 一行、且不区分 pass，被刷屏淹没后长期无人发现。
            // 现在补上诊断信息，并给出最可能的成因。
            int verts = buf.remaining() / VERT_FLOATS;
            System.err.println("[MESH] 顶点/索引不匹配：verts=" + verts
                    + " quads=" + quads + "（期望 " + (quads * 4) + "）"
                    + " 丢失=" + (quads * 4 - verts) + " verts");
            if (verts < quads * 4) {
                System.err.println("[MESH] ↑ 顶点数<b>少于</b>声明值 → 某条发射路径的步长不是 " + VERT_FLOATS
                        + " float（多为漏写/多写属性字段）。请检查 Game.putV 与 Chunk.crossV 的 put 链长度。");
            }
        }
        glBindVertexArray(vao);
        glBindBuffer(GL_ARRAY_BUFFER, vbo);
        glBufferData(GL_ARRAY_BUFFER, buf, GL_DYNAMIC_DRAW);
        if (quads > 0) {
            java.nio.IntBuffer ib = memAllocInt(quads * 6);
            ib.put(quadIndices(quads)).flip();
            glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, ebo);
            glBufferData(GL_ELEMENT_ARRAY_BUFFER, ib, GL_DYNAMIC_DRAW);
            memFree(ib);
        }
        glBindVertexArray(0);
    }

    // ---------- greedy meshing（块内合并）----------

    /** 6 个朝向的法线：+x,-x,+y,-y,+z,-z（顺序须与 SHADE / AAX / BAX 对应）。 */
    private static final int[][] N = {
        { 1, 0, 0}, {-1, 0, 0},
        { 0, 1, 0}, { 0,-1, 0},
        { 0, 0, 1}, { 0, 0,-1}
    };
    /** 各朝向的固定明度系数（程序化方向光改由 worldShader 按法线做 lambert，此处统一 1.0 透传基础色）。 */
    private static final float[] SHADE = {1.00f, 1.00f, 1.00f, 1.00f, 1.00f, 1.00f};
    /**
     * 各朝向的两个共面轴：AAX=第一个延展轴（对应 quad.du），BAX=第二个延展轴（对应 quad.dv）。
     * 选择使 AAX/BAX 与 {@link #emitQuad} 的角点布局一致：
     *  +x/-x → a=y(1), b=z(2)；+y/-y → a=x(0), b=z(2)；+z/-z → a=x(0), b=y(1)。
     */
    private static final int[] AAX = {1, 1, 0, 0, 0, 0};
    private static final int[] BAX = {2, 2, 2, 2, 1, 1};

    /** 一个（可能合并的）四边形：基底体素 (bx,by,bz)、朝向 dir、沿 AAX 延展 du、沿 BAX 延展 dv、方块类型。 */
    public static final class Quad {
        final int bx, by, bz, dir, du, dv, type;
        Quad(int bx, int by, int bz, int dir, int du, int dv, int type) {
            this.bx = bx; this.by = by; this.bz = bz;
            this.dir = dir; this.du = du; this.dv = dv; this.type = type;
        }
    }

    /**
     * 贪婪网格路径：合并后发射大四边形，暴露面集合与逐面路径逐面等价；
     * <b>同样按半透明分区</b>进两个缓冲（否则开启贪婪时水会落进不透明 pass —— 该分区此前漏改，
     * 因 USE_GREEDY 默认关而未被编译期发现）。
     */
    private void rebuildGreedy(World w) {
        List<Quad> quads = new ArrayList<>();
        collectGreedyQuads(w, cx, cz, quads);
        int nO = 0, nT = 0;
        for (Quad q : quads) { if (Blocks.byIndex(q.type).translucent) nT++; else nO++; }
        FloatBuffer bO = memAllocFloat(Math.max(1, nO * 4 * VERT_FLOATS));   // 索引化：每四边形 4 顶点
        FloatBuffer bT = memAllocFloat(Math.max(1, nT * 4 * VERT_FLOATS));
        for (Quad q : quads) emitQuad(Blocks.byIndex(q.type).translucent ? bT : bO, w, q);
        bO.flip(); bT.flip();
        upload(vao, vbo, ebo, bO, nO);
        upload(vaoT, vboT, eboT, bT, nT);
        memFree(bO); memFree(bT);
        faceCount = nO; faceCountT = nT;   // 每个四边形 = 1 个 GL 绘制面（4 顶点 + 6 索引）
        this.meshOffX = 0; this.meshOffZ = 0;   // 同步重建同理：顶点即当前帧（见 applyMesh）
        buildEmissive(w);                   // 泰拉瑞亚缺口②：发光源网格（贪婪路径同样构建）
    }

    /**
     * 标准体素 greedy meshing：对 6 个朝向分别处理；对每一层（沿法线方向）扫描该层 2D 网格，
     * 把同方块类型、同朝向、连续且颜色一致的暴露面合并成尽可能大的单矩形。
     * 合并后的「暴露面集合」与逐面路径完全一致——只是用更少的大四边形表示。
     */
    static void collectGreedyQuads(World w, int cx, int cz, List<Quad> out) {
        int x0 = cx * SIZE, x1 = x0 + SIZE;
        int z0 = cz * SIZE, z1 = z0 + SIZE;
        int sy = w.SY;
        for (int di = 0; di < 6; di++) {
            int[] n = N[di];
            int dAxis = n[0] != 0 ? 0 : (n[1] != 0 ? 1 : 2);   // 法线轴
            int aAxis = AAX[di], bAxis = BAX[di];               // 两个共面轴
            int dMin, dMaxEx, aMin, aSize, bMin, bSize;
            if (dAxis == 0)      { dMin = x0; dMaxEx = x1; } else if (dAxis == 1) { dMin = 0;  dMaxEx = sy; } else { dMin = z0; dMaxEx = z1; }
            if (aAxis == 0)      { aMin = x0; aSize = SIZE; } else if (aAxis == 1) { aMin = 0; aSize = sy; } else { aMin = z0; aSize = SIZE; }
            if (bAxis == 0)      { bMin = x0; bSize = SIZE; } else if (bAxis == 1) { bMin = 0; bSize = sy; } else { bMin = z0; bSize = SIZE; }

            int[][] mask = new int[aSize][bSize];   // -1 表示无面；否则为方块类型（=颜色）

            for (int L = dMin; L < dMaxEx; L++) {
                // 1) 构建本层 mask
                for (int ia = 0; ia < aSize; ia++)
                    for (int ib = 0; ib < bSize; ib++) {
                        int av = aMin + ia, bv = bMin + ib;
                        int[] c = coordOf(dAxis, aAxis, bAxis, L, av, bv);
                        int bt = w.mat[c[0]][c[1]][c[2]];
                        if (bt == Blocks.AIR.index) { mask[ia][ib] = -1; continue; }
                        int[] nb = { c[0] + n[0], c[1] + n[1], c[2] + n[2] };
                        if (!FaceCull.visible(w, bt, nb[0], nb[1], nb[2])) { mask[ia][ib] = -1; continue; }
                        mask[ia][ib] = bt;
                    }
                // 2) 贪婪扫描：同类型连续矩形合并
                boolean[][] used = new boolean[aSize][bSize];
                for (int ia = 0; ia < aSize; ia++)
                    for (int ib = 0; ib < bSize; ib++) {
                        if (used[ia][ib] || mask[ia][ib] < 0) continue;
                        int type = mask[ia][ib];
                        // 第七批：导线**不参与贪婪合并** —— 它的 tile 由"本格的面内四邻连通性"决定，
                        // 一旦合并成跨格大四边形，就只能取其中一个格的掩码 → 导线网络会画错。
                        // 逐格成 quad 是这条依赖的代价（默认 USE_GREEDY=false，故生产路径无影响）。
                        boolean mergeable = (type != Blocks.WIRE.index);
                        int ww = 1;
                        while (mergeable && ib + ww < bSize && mask[ia][ib + ww] == type && !used[ia][ib + ww]) ww++;
                        int hh = 1;
                        expand: while (mergeable && ia + hh < aSize) {
                            for (int t = 0; t < ww; t++)
                                if (mask[ia + hh][ib + t] != type || used[ia + hh][ib + t]) break expand;
                            hh++;
                        }
                        for (int i = 0; i < hh; i++)
                            for (int j = 0; j < ww; j++) used[ia + i][ib + j] = true;
                        int av = aMin + ia, bv = bMin + ib;
                        int[] c = coordOf(dAxis, aAxis, bAxis, L, av, bv);
                        out.add(new Quad(c[0], c[1], c[2], di, hh, ww, type));
                    }
            }
        }
    }

    /** 由 (法线轴 L, a 轴值, b 轴值) 重组全局 (x,y,z)。三轴在 d/a/b 中各出现一次。 */
    private static int[] coordOf(int dAxis, int aAxis, int bAxis, int L, int av, int bv) {
        int[] c = new int[3];
        c[dAxis] = L;
        c[aAxis] = av;
        c[bAxis] = bv;
        return c;
    }

    /**
     * 发射一个四边形（可能合并）。角点布局与改造前 {@link Game#emit} 对 1×1 的情形逐顶点一致
     * （du=dv=1 时退化为原 emit），故合并前后几何逐面等价、颜色一致（同类型固定色）。
     * 每个顶点额外烘焙顶点级 AO（邻接实心度 → 4 级亮度）并写入 第 10 个 float = aWind（植被=1）。
     */
    static void emitQuad(FloatBuffer b, World w, Quad q) {
        Blocks.Block blk = Blocks.byIndex(q.type);
        float k = SHADE[q.dir];
        float nx = N[q.dir][0], ny = N[q.dir][1], nz = N[q.dir][2];
        float wind = blk.wind ? 1f : 0f;
        // 第十六/十七批：上边收 y+height、下边抬 y+base —— 与逐面路径 Game.emit 同构（两条路径不许走散）。
        // （贪婪路径默认关闭，但必须同步，否则一旦有人打开 USE_GREEDY 就会"半格塌回整格"。）
        float x0 = q.bx, x1 = q.bx + 1,
              y0 = q.by + blk.shapeBase, y1 = q.by + blk.shapeBase + blk.shapeHeight,
              z0 = q.bz, z1 = q.bz + 1;
        float[][] v4;
        switch (q.dir) {
            case 0: v4 = new float[][]{{x1,y0,z0+q.dv},{x1,y0,z0},{x1,y0+q.du,z0},{x1,y0+q.du,z0+q.dv}}; break;
            case 1: v4 = new float[][]{{x0,y0,z0},{x0,y0,z0+q.dv},{x0,y0+q.du,z0+q.dv},{x0,y0+q.du,z0}}; break;
            case 2: v4 = new float[][]{{x0,y1,z0+q.dv},{x0+q.du,y1,z0+q.dv},{x0+q.du,y1,z0},{x0,y1,z0}}; break;
            case 3: v4 = new float[][]{{x0,y0,z0},{x0+q.du,y0,z0},{x0+q.du,y0,z0+q.dv},{x0,y0,z0+q.dv}}; break;
            case 4: v4 = new float[][]{{x0,y0,z1},{x0+q.du,y0,z1},{x0+q.du,y0+q.dv,z1},{x0,y0+q.dv,z1}}; break;
            default: v4 = new float[][]{{x1,y0,z0},{x0,y0,z0},{x0,y0+q.dv,z0},{x1,y0+q.dv,z0}}; break;
        }
        // 索引化（PERF）：只发 4 个顶点；索引 {0,1,2, 0,2,3} 复现与旧 6 顶点（V0,V1,V2,V0,V2,V3）完全相同的两个三角形。
        for (int i = 0; i < 4; i++) putV(b, w, q.dir, v4[i], blk, k, nx, ny, nz, wind, q.bx, q.by, q.bz, CU[i][0], CU[i][1]);
    }

    /** 四边形角点 → tile 内 UV 参数（(0,0)(1,0)(1,1)(0,1)）。贪婪合并大四边形按整面拉伸（默认关闭，可接受）。 */
    static final float[][] CU = {{0f, 0f}, {1f, 0f}, {1f, 1f}, {0f, 1f}};

    /**
     * 导线「面内四臂」的世界方向表（第七批）：对每个面，列出 4 个局部边缘
     * （cu=0 / cu=1 / cv=0 / cv=1）各自朝向的<b>邻居偏移</b>（x,y,z 平铺）。
     *
     * <p><b>来源不是猜的</b>：由 {@code Game.emit} 的 4 个角点顺序 + {@link #CU} 逐面反推得到
     * （例如 +x 面的角点 0 = (x1,y0,z1) 携带 cu=0,cv=0 ⇒ cu=0 那侧对应 <b>+z</b>）。
     * 若将来改了 emit 的顶点序，<b>这张表必须同步改</b>，否则臂会指向错误的方向
     * —— 这正是"把不可见的约定写成可见常量"的目的。
     *
     * <p>面的编号与 {@link #N} / {@code Game.emit} 一致：0=+x 1=-x 2=+y 3=-y 4=+z 5=-z。
     */
    static final int[][] WIRE_ARMDIR = {
            {  0, 0, 1,   0, 0,-1,   0,-1, 0,   0, 1, 0 },   // 0  +x 面：cu0=+z cu1=-z cv0=-y cv1=+y
            {  0, 0,-1,   0, 0, 1,   0,-1, 0,   0, 1, 0 },   // 1  -x 面：cu0=-z cu1=+z cv0=-y cv1=+y
            { -1, 0, 0,   1, 0, 0,   0, 0, 1,   0, 0,-1 },   // 2  +y 面：cu0=-x cu1=+x cv0=+z cv1=-z
            { -1, 0, 0,   1, 0, 0,   0, 0,-1,   0, 0, 1 },   // 3  -y 面：cu0=-x cu1=+x cv0=-z cv1=+z
            { -1, 0, 0,   1, 0, 0,   0,-1, 0,   0, 1, 0 },   // 4  +z 面：cu0=-x cu1=+x cv0=-y cv1=+y
            {  1, 0, 0,  -1, 0, 0,   0,-1, 0,   0, 1, 0 },   // 5  -z 面：cu0=+x cu1=-x cv0=-y cv1=+y
    };

    /**
     * 该导线格在指定面上的 4 bit 连通掩码（bit0=cu0 / bit1=cu1 / bit2=cv0 / bit3=cv1）。
     * <b>纯只读</b>（只 {@code w.getBlock}）→ 不改世界、不进指纹。
     *
     * <p>「算连通」的邻居 = 电路元件集合 {WIRE, LAMP, LEVER, BUTTON, REPEATER, COMPARATOR, PLATE} —— 与
     * {@code WireSystem} 的「导体 ∪ 外部电源」语义同源，于是"画出来的臂"与"真的能导通的边"是同一套判据
     * （不会出现"看着连上了、其实不通电"的误导）。第七批补齐三件套。
     */
    static int wireArmMask(World w, int bx, int by, int bz, int face) {
        int[] a = WIRE_ARMDIR[face];
        int m = 0;
        if (isCircuitPart(w.getBlock(bx + a[0], by + a[1], bz + a[2]))) m |= 1;
        if (isCircuitPart(w.getBlock(bx + a[3], by + a[4], bz + a[5]))) m |= 2;
        if (isCircuitPart(w.getBlock(bx + a[6], by + a[7], bz + a[8]))) m |= 4;
        if (isCircuitPart(w.getBlock(bx + a[9], by + a[10], bz + a[11]))) m |= 8;
        return m;
    }

    /** 是否属于电路元件（导体 / 开关 / 触发 / 中继 / 符合门 / 机械）—— 决定导线是否朝它出臂。 */
    private static boolean isCircuitPart(int b) {
        return b == Blocks.WIRE.index || b == Blocks.LAMP.index
                || b == Blocks.LEVER.index || b == Blocks.BUTTON.index
                || b == Blocks.REPEATER.index || b == Blocks.COMPARATOR.index
                || b == Blocks.PLATE.index
                || b == Blocks.DISPENSER.index || b == Blocks.PISTON.index   // 第九批：红石驱动的机械
                || b == Blocks.OBSERVER.index;                               // 第十一批：观察者
    }

    /**
     * 「这个面用哪张 tile」的<b>唯一解析口</b>（第十批收敛）。
     *
     * <p>三条分支，按优先级：
     * <ol>
     *   <li>{@code WIRE} → 按面内四邻连通性选臂形 tile（第七批）；</li>
     *   <li><b>可定向方块</b>且该面正是它的朝向 → 端口 tile（第十批，朝向因此"看得见"）；</li>
     *   <li>其余 → 常规 {@code (blockIndex, dir)} 寻址。</li>
     * </ol>
     *
     * <p><b>为什么要收敛成一个函数</b>：本项目有<b>两条</b> emit 路径（逐面 {@code Game.putV} 与
     * 贪婪 {@code Chunk.putV}，后者默认关闭）。第六批的"通电提亮"只写进了贪婪那条 →
     * 默认路径下完全没效果（编译通过、门禁全绿、{@code git diff} 也像对的）。所以凡是
     * "面 → tile"的判断都必须走这里，让两条路径不可能走散。
     *
     * <p>{@code frontTileFor} 对不可定向方块返回 -1 → <b>只有可定向方块才做朝向查询</b>，
     * 热路径上其余方块零额外开销。
     */
    static int tileForFace(World w, Blocks.Block blk, int dir, int bx, int by, int bz) {
        if (blk == Blocks.WIRE) return TextureAtlas.wireTile(wireArmMask(w, bx, by, bz, dir));
        // 第二十五批：轨道**顶面**按四邻掩码选形状 tile（直线 / 弯 / 十字 / T 字共用 16 张库）。
        // 只改顶面 —— 轨道是 1/16 薄片，侧面只是几像素高的窄条，用母题纹即可。
        // （第十九批那套"整张 UV 转 90°"因此被取代：它只能表达"直线沿哪个轴"，表达不了弯。）
        if (blk.rail && dir == 2) return TextureAtlas.railTile(w.railMask(bx, by, bz));
        int front = TextureAtlas.frontTileFor(blk.index);
        if (front >= 0 && w.getFacing(bx, by, bz) == dir) return front;
        return TextureAtlas.tileFor(blk.index, dir);
    }

    /**
     * 发射一个顶点（12 float：pos3 + col3 + normal3 + wind1 + uv2）。
     * {@code (bx,by,bz)} = 该面所属的<b>基础体素</b>坐标，仅用于植被着色的位置哈希（ART-PROG-3）。
     * <p>立项 A 配色分工：实心方块顶点色退化为灰度（AO×方向明度，纹理承担颜色）；
     * 风动植被 / FLOWER 顶点色保留彩色（纹理为灰度图案，花田五色盘/树冠抖动保留）。</p>
     */
    private static void putV(FloatBuffer b, World w, int dir, float[] v, Blocks.Block blk, float k,
                             float nx, float ny, float nz, float wind, int bx, int by, int bz,
                             float cu, float cv) {
        // 第三十八批修正：与 Game.putV 同构 —— 逐块色差锚定绝对世界坐标（否则平移闪烁）。
        int gwX = w.windowOriginCX() * World.CHUNK + bx;
        int gwZ = w.windowOriginCZ() * World.CHUNK + bz;
        float ao = vertexAO(w, v[0], v[1], v[2], dir);
        // 第七批（第十批收敛到 tileForFace）：导线按连通掩码、可定向方块朝的那面走端口 tile，其余常规寻址。
        int tile = tileForFace(w, blk, dir, bx, by, bz);
        float u = TextureAtlas.uOf(tile, cu);
        // 第二十批：侧壁竖直 UV 跟随实心区间（与 Game.putV 同构 —— 两条 emit 路径不许走散）。
        float vv = TextureAtlas.vOf(tile, (ny == 0f) ? core.world.ShapeUV.sideV(blk, cv) : cv);
        // 第十九批：轨道的走向靠**贴图转 90°** 表达（tile 画的是"沿 X 的直轨"）——
        // 只读 per-block 朝向 ⇒ 零漂移。⚠️ 与 Game.putV 同构，两条 emit 路径不许走散。
        if (blk.rail) {
            int rd = core.world.Facing.horizontal(w.getFacing(bx, by, bz));
            if (rd == 4 || rd == 5) { float ru = u; u = vv; vv = ru; }
        }
        float r, g, bl;
        if (TextureAtlas.vertexColored(blk)) {
            float[] t = vegTint(blk, gwX, by, gwZ);
            r = t[0] * k * ao; g = t[1] * k * ao; bl = t[2] * k * ao;
        } else {
            float m = k * ao;
            r = m; g = m; bl = m;
        }
        // 立项 C：采样面外侧半格的块光（面在体素边界上，光在空气侧）。
        // 2026-09-30：改走 blockLight（2×2 格平滑）—— 旧的单格采样在灯下呈方块硬边。
        float lamp = blockLight(w, v[0], v[1], v[2], nx, ny, nz, SMOOTH_LIGHT);
        // 泰拉瑞亚缺口①：自动拼贴边缝暗化（纯渲染，零漂移；非拼贴方块 seam=1）。
        // na 由法线推导（0=x,1=y,2=z）；cu/cv 为四边形角点参数。
        int na = Autotile.normalAxis(nx, ny, nz);
        float seam = Autotile.seam(w, blk, bx, by, bz, na, cu, cv);
        r *= seam; g *= seam; bl *= seam;
        // 方块级自发差异（与 Game.putV 同构；见 VoxelVariety）：同材质的不同方块不该逐像素相同。
        // 这里同样只对不透明方块做色相（水/玻璃的顶点色承担材质本色）。
        float vb = VoxelVariety.bright(gwX, by, gwZ);
        if (!blk.isFullShape()) {
            // 第十六批：非满格方块（台阶/半砖）**不做逐格随机色差** —— 与 Game.putV 同一条判据（两条
            // emit 路径不许走散）。硬理由：面剔除不知道方向 ⇒ 并排/贴合的半砖之间会各画一张
            // **共面四边形**；两边若各带 ±6% 随机明度，重叠处就显出一条接缝。关掉 ⇒ 两面色逐位相同 ⇒ 不可见。
        } else if (blk.translucent) {
            r *= vb; g *= vb; bl *= vb;
        } else {
            float vh = VoxelVariety.hue(gwX, by, gwZ);
            r *= vb * (1f + vh);
            g *= vb;
            bl *= vb * (1f - vh);
        }
        // 第五批·红石可视化（第九批收敛为统一谓词 + 随强度提亮）：见 isRedstoneLit / redstoneBrightness。
        // 纯渲染层（meta 不进 hashState）→ 零漂移；配合 buildEmissive 的红辉，元件随电路亮/灭。
        float litK = redstoneBrightness(w, blk.index, bx, by, bz);
        if (litK != 1f) { r *= litK; g *= litK; bl *= litK; }
        // 第十二批：装了水的坩埚偏蓝提亮（水位住 meta、tile 不随 meta 变 → 靠顶点色表达）
        if (isFilledCauldron(w, blk.index, bx, by, bz)) {
            r *= 1.25f; g *= 1.35f; bl *= 1.90f;
        }
        // 步骤 4（Noita edge_files）· 路线 A「叠加层」：跨材质交界过渡。
        //   - edgeInfl 只用来压暗顶点色（scale），**不换 albedo tile**（换 tile 会丢材质身份）；
        //   - 过渡图案 UV + 强度作为第 6 个顶点属性传给 shader，由 FS 做第二次采样后叠加；
        //   - STRENGTH=0（出厂默认）时 edgeInfluence 恒返 0 → scale 精确 1.0f、edge 属性恒 0
        //     → 与本改动前逐字节等价。
        float edgeInfl = EdgeAtlas.edgeInfluence(w, blk, bx, by, bz, na, cu, cv);
        float eU = 0f, eV = 0f;
        if (edgeInfl > 0f) {
            float sc = EdgeAtlas.scale(edgeInfl);
            r *= sc; g *= sc; bl *= sc;
            float[] et = edgeUV(w, blk, bx, by, bz, na, cu, cv);
            eU = et[0]; eV = et[1];
        }
        b.put(v[0]).put(v[1]).put(v[2]).put(r).put(g).put(bl).put(nx).put(ny).put(nz).put(wind).put(u).put(vv).put(lamp)
         .put(eU).put(eV).put(edgeInfl);
    }

    /**
     * 面级过渡图案 UV（第 3 个属性另有 {@code edgeInfl} 承载强度）。
     *
     * <p>关键点：<b>只要该面存在过渡（哪怕当前角点不碰交界），四个角就一起取同一个族 + 同一个
     * 段索引</b>。这样 4 个顶点的边缘 UV 全部落在同一个 80px cell 内，插值路径不可能离开本 cell
     * —— 从根本上杜绝"跨 tile 渗色"（实测穿过 11 个 tile 长草绿/土黄那次事故）。</p>
     *
     * <p>角点是否受交界影响只由 {@code edgeInfluence}（→ 顶点色 scale）与 {@code edgeInfl} 属性表达；
     * UV 本身只负责"图案沿交界连续"，所以用世界坐标取模（{@code wx % TILE_PX}），跨方块自然拼接。</p>
     */
    static float[] edgeUV(World w, Blocks.Block blk, int bx, int by, int bz, int na, float cu, float cv) {
        if (EdgeAtlas.STRENGTH <= 0f) return ZERO_UV;
        int fam = EdgeAtlas.faceFamily(w, blk, bx, by, bz, na);
        if (fam < 0) return ZERO_UV;
        // 第三十八批修正：段索引 / 面内两轴必须锚定**绝对世界坐标**（否则窗口平移后同世界面的纹样相位跳变 =
        // 平移闪烁）。bx,by,bz 是窗口本地坐标，窗口每平移一次都会变。
        int gwX = w.windowOriginCX() * World.CHUNK + bx;
        int gwZ = w.windowOriginCZ() * World.CHUNK + bz;
        // 段索引：整面共用（由面所在的**世界**坐标决定，确定性；只影响"纹样的相位"不影响族）
        int seg = Math.floorMod((gwX * 3 + by * 5 + gwZ * 7), TextureAtlas.EDGE_TILES_PER_FAM);
        // 面内两轴的世界坐标 → 取模，保证图案跨方块连续
        int au = (na == 0) ? by : gwX;
        int av = (na == 2) ? by : gwZ;
        EDGE_UV[0] = TextureAtlas.edgeU(fam, seg, au);
        EDGE_UV[1] = TextureAtlas.edgeV(fam, seg, av);
        return EDGE_UV;
    }

    /** 面级过渡 UV 暂存（单线程渲染，避免逐顶点分配）。 */
    static final float[] EDGE_UV = new float[2];
    /** 无过渡时的中性返回值（UV 无意义，因为 edgeInfl=0 时 FS 不采样）。 */
    static final float[] ZERO_UV = new float[2];

    // ---------- ART-PROG-3：植被着色抖动（纯渲染层，零 RNG、不进指纹）----------

    /** 花田色盘：玫瑰 / 金盏 / 白 / 紫 / 珊瑚。让花海成“点点碎花”而非单色洋红地毯。 */
    private static final float[][] BLOOM = {
        {0.90f, 0.50f, 0.56f},
        {0.95f, 0.80f, 0.42f},
        {0.95f, 0.95f, 0.93f},
        {0.70f, 0.58f, 0.92f},
        {0.93f, 0.60f, 0.38f},
    };

    private static final float[] TINT = new float[3];   // 单线程渲染用暂存（避免逐顶点分配）

    /** 体素坐标 → [0,1) 的确定性哈希（纯位置函数；不用任何仿真 RNG 流）。 */
    /**
     * 装饰植被交叉面（本机验收修复：野花原按整格立方体渲染 → 花田读作"满地彩色方块像缺纹理"；
     * 改为 MC 植被样式的 X 形双 Quad，风动、无 AO、双面可见（世界关闭背面剔除））。渲染层改动，零漂移。
     */
    private static void emitCross(FloatBuffer b, World w, int x, int y, int z, Blocks.Block blk) {
        float[] tint = vegTint(blk, w.windowOriginCX() * World.CHUNK + x, y, w.windowOriginCZ() * World.CHUNK + z);
        float r = tint[0], g = tint[1], bl = tint[2];
        float a = 0.15f, q = 0.85f, h = 0.75f;      // 底面内缩 15%，高 0.75
        float nx = 0.7071f;
        int tile = TextureAtlas.tileFor(blk.index, 0);
        float u0 = TextureAtlas.uOf(tile, 0f), v0 = TextureAtlas.vOf(tile, 0f);
        float u1 = TextureAtlas.uOf(tile, 1f), v1 = TextureAtlas.vOf(tile, 1f);
        float lamp = w.lightAt(x, y, z);            // 立项 C：花丛吃自身格的光
        crossQuad(b, x + a, y, z + a, x + q, y + h, z + q,  nx, -nx, r, g, bl, u0, v0, u1, v1, lamp);
        crossQuad(b, x + a, y, z + q, x + q, y + h, z + a,  nx,  nx, r, g, bl, u0, v0, u1, v1, lamp);
    }

    private static void crossQuad(FloatBuffer b, float x0, float y0, float z0, float x1, float y1, float z1,
                                  float nx, float nz, float r, float g, float bl,
                                  float u0, float v0, float u1, float v1, float lamp) {
        // 索引化：4 个角点 V0,V1,V2,V3；索引 {0,1,2, 0,2,3} 与旧的 6 顶点序完全等价。
        crossV(b, x0, y0, z0, nx, nz, r, g, bl, u0, v0, lamp);
        crossV(b, x1, y0, z1, nx, nz, r, g, bl, u1, v0, lamp);
        crossV(b, x1, y1, z1, nx, nz, r, g, bl, u1, v1, lamp);
        crossV(b, x0, y1, z0, nx, nz, r, g, bl, u0, v1, lamp);
    }

    private static void crossV(FloatBuffer b, float px, float py, float pz,
                               float nx, float nz, float r, float g, float bl, float u, float v, float lamp) {
        // 顶点布局必须与 Chunk.VERT_FLOATS(=16) / Game.putV 逐字段一致：
        //   pos3 col3 nrm3 wind1 uv2 lamp1 edge3
        // 历史缺陷（2026-09-21 修）：旧实现只写 13 float，漏掉末尾 edge3 →
        // 每个 cross quad 只推进 52 float 而非 64，FloatBuffer 提前写满，
        // 尾部 N 个 quad 被 put() 静默丢弃（越界不抛异常）→ 地形/花丛出现空洞，
        // 并触发 [MESH] 顶点/索引不匹配（丢的 quad 数与 flower 数成正比）。
        b.put(px).put(py).put(pz).put(r).put(g).put(bl).put(nx).put(0f).put(nz).put(1f).put(u).put(v).put(lamp)
         .put(0f).put(0f).put(0f);   // edge 属性占位：交叉植被不参与交界过渡
    }

    static float hash01(int x, int y, int z) {
        int h = x * 374761393 + y * 668265263 + z * 1274126177;
        h = (h ^ (h >>> 13)) * 1274126177;
        h = h ^ (h >>> 16);
        return (h & 0x7fffffff) / (float) 0x7fffffff;
    }

    /**
     * 植被基础色（写入顶点色前调用）：FLOWER 按体素从 {@link #BLOOM} 取一色，
     * 其他风动植被（LEAF）只做轻微明度抖动（树冠有层次、但不“秋化”）。非植被原样返回。
     * <p>返回内部复用数组 {@link #TINT}，调用方须立即使用（单线程渲染）。
     */
    static float[] vegTint(Blocks.Block blk, int bx, int by, int bz) {
        TINT[0] = blk.r / 255f; TINT[1] = blk.g / 255f; TINT[2] = blk.b / 255f;
        if (blk == Blocks.FLOWER) {
            int i = (int) (hash01(bx, by, bz) * BLOOM.length);
            if (i >= BLOOM.length) i = BLOOM.length - 1;
            TINT[0] = BLOOM[i][0]; TINT[1] = BLOOM[i][1]; TINT[2] = BLOOM[i][2];
        } else if (blk.wind) {
            float d = 0.88f + 0.24f * hash01(bx, by, bz);
            TINT[0] *= d; TINT[1] *= d; TINT[2] *= d;
        }
        return TINT;
    }

    /** 顶点级体素 AO：按 +n 层该顶点相邻两侧 + 对角共 3 个方块的实心度计算 0..3 遮蔽，映射 4 级亮度。 */
    static float vertexAO(World w, float vx, float vy, float vz, int dir) {
        int[] n = N[dir];
        int uAxis, vAxis;
        if (n[0] != 0) { uAxis = 1; vAxis = 2; }
        else if (n[1] != 0) { uAxis = 0; vAxis = 2; }
        else { uAxis = 0; vAxis = 1; }
        int bx = (int) Math.floor(vx - n[0] * 0.5 + 1e-4);
        int by = (int) Math.floor(vy - n[1] * 0.5 + 1e-4);
        int bz = (int) Math.floor(vz - n[2] * 0.5 + 1e-4);
        int nxB = bx + n[0], nyB = by + n[1], nzB = bz + n[2];   // 该顶点所在的 +n 层方块
        float cu = (uAxis == 0 ? nxB : uAxis == 1 ? nyB : nzB) + 0.5f;
        float cv = (vAxis == 0 ? nxB : vAxis == 1 ? nyB : nzB) + 0.5f;
        float vu = (uAxis == 0 ? vx : uAxis == 1 ? vy : vz);
        float vv = (vAxis == 0 ? vx : vAxis == 1 ? vy : vz);
        int su = (vu < cu) ? -1 : 1;
        int sv = (vv < cv) ? -1 : 1;
        int s1 = solid(w, nxB + su * (uAxis == 0 ? 1 : 0), nyB + su * (uAxis == 1 ? 1 : 0), nzB + su * (uAxis == 2 ? 1 : 0)) ? 1 : 0;
        int s2 = solid(w, nxB + sv * (vAxis == 0 ? 1 : 0), nyB + sv * (vAxis == 1 ? 1 : 0), nzB + sv * (vAxis == 2 ? 1 : 0)) ? 1 : 0;
        int cc = solid(w, nxB + su * (uAxis == 0 ? 1 : 0) + sv * (vAxis == 0 ? 1 : 0),
                          nyB + su * (uAxis == 1 ? 1 : 0) + sv * (vAxis == 1 ? 1 : 0),
                          nzB + su * (uAxis == 2 ? 1 : 0) + sv * (vAxis == 2 ? 1 : 0)) ? 1 : 0;
        int occ = s1 + s2 + cc;
        if (s1 == 1 && s2 == 1) occ = 3;   // 相邻两侧皆实心 → 整角满遮蔽
        float[] T = {1.0f, 0.80f, 0.62f, 0.42f};
        return T[occ];
    }

    /** 邻接方块是否实心（用于 AO）；越界按 air 处理（Game.air 返回 true）。 */
    static boolean solid(World w, int x, int y, int z) { return !Game.air(w, x, y, z); }

    // ---------- 几何等价验证辅助（无头，不触碰 GL）----------

    /** 逐面路径：对每个暴露的 (体素,朝向) 产出 1×1 四边形。 */
    static void collectPerFace(World w, int cx, int cz, List<Quad> out) {
        int x0 = cx * SIZE, x1 = x0 + SIZE;
        int z0 = cz * SIZE, z1 = z0 + SIZE;
        for (int x = x0; x < x1; x++)
            for (int y = 0; y < w.SY; y++)
                for (int z = z0; z < z1; z++) {
                    int bi = w.mat[x][y][z];
                    if (bi == Blocks.AIR.index) continue;
                    if (FaceCull.visible(w, bi, x + 1, y, z)) out.add(new Quad(x, y, z, 0, 1, 1, bi));
                    if (FaceCull.visible(w, bi, x - 1, y, z)) out.add(new Quad(x, y, z, 1, 1, 1, bi));
                    if (FaceCull.visible(w, bi, x, y + 1, z)) out.add(new Quad(x, y, z, 2, 1, 1, bi));
                    if (FaceCull.visible(w, bi, x, y - 1, z)) out.add(new Quad(x, y, z, 3, 1, 1, bi));
                    if (FaceCull.visible(w, bi, x, y, z + 1)) out.add(new Quad(x, y, z, 4, 1, 1, bi));
                    if (FaceCull.visible(w, bi, x, y, z - 1)) out.add(new Quad(x, y, z, 5, 1, 1, bi));
                }
    }

    /** 把四边形展开为它所覆盖的全部「单位面」键 (x,y,z,dir)。 */
    private static void expandQuad(Quad q, Set<Long> out) {
        int aAxis = AAX[q.dir], bAxis = BAX[q.dir];
        for (int i = 0; i < q.du; i++)
            for (int j = 0; j < q.dv; j++) {
                int x = q.bx, y = q.by, z = q.bz;
                if (aAxis == 0) x += i; else if (aAxis == 1) y += i; else z += i;
                if (bAxis == 0) x += j; else if (bAxis == 1) y += j; else z += j;
                out.add((((long) x * 1000 + y) * 1000 + z) * 10 + q.dir);
            }
    }

    /** 暴露面集合：greedy=true 走合并路径后展开，false 走逐面路径。两者应完全一致。 */
    public static Set<Long> exposedFaces(World w, int cx, int cz, boolean greedy) {
        List<Quad> qs = new ArrayList<>();
        if (greedy) collectGreedyQuads(w, cx, cz, qs);
        else        collectPerFace(w, cx, cz, qs);
        Set<Long> s = new LinkedHashSet<>();
        for (Quad q : qs) expandQuad(q, s);
        return s;
    }

    /** 块内合并后的四边形数量（用于顶点数统计）。 */
    public static int quadCount(World w, int cx, int cz) {
        List<Quad> qs = new ArrayList<>();
        collectGreedyQuads(w, cx, cz, qs);
        return qs.size();
    }

    /**
     * 无头（不触碰 GL）：本块「不透明 pass / 透明 pass」的四边形数（分类与 {@link #rebuild} 完全一致）。
     * 供 tools/MeshPassCheck 校验分区完备性（两 pass 之和 == 暴露面总数）。
     */
    public static int[] passCounts(World w, int cx, int cz, boolean greedy) {
        List<Quad> qs = new ArrayList<>();
        if (greedy) collectGreedyQuads(w, cx, cz, qs);
        else        collectPerFace(w, cx, cz, qs);
        int o = 0, t = 0;
        for (Quad q : qs) { if (Blocks.byIndex(q.type).translucent) t++; else o++; }
        return new int[]{o, t};
    }

    public void dispose() {
        glDeleteBuffers(vbo);
        glDeleteBuffers(ebo);
        glDeleteVertexArrays(vao);
        glDeleteBuffers(vboT);
        glDeleteBuffers(eboT);
        glDeleteVertexArrays(vaoT);
        glDeleteBuffers(vboE);
        glDeleteBuffers(eboE);
        glDeleteVertexArrays(vaoE);
    }
}
