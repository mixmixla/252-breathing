package render.lwjgl;

import core.world.Megalith;
import core.world.World;

import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import static org.lwjgl.opengl.GL33.*;
import static org.lwjgl.system.MemoryUtil.*;

/**
 * 远景巨构剪影（ART_BIBLE.md §9 · 巨物主义 Phase 1，render-only）。
 *
 * <p>分层混合 D 方案的远景层：地平线上 120–220 格的「独塔断拱 / 双柱断跨」剪影（不可及、
 * 只负责召唤），与 Phase 2 的近场可进入神殿（{@link Megalith}，材化进 mat）共用放置函数
 * {@code Megalith.descAt}，保证「剪影 ↔ 真身」一一对应；真身材化世界中相机近于
 * {@link Megalith#SKIP_R} 时剪影停画，由 chunk 网格接管。
 *
 * <p><b>零漂移契约（必须保持）</b>：
 * <ul>
 *   <li>只读 World 标量（seed/SY），不读/不写 mat、chunkEdits 或任何仿真状态 →
 *       四道基线指纹逐字节不变；</li>
 *   <li>不消费任何 RNG 流：全部随机性来自全局坐标整数哈希 {@link #mh}（纯位置函数）；</li>
 *   <li>放置与地形锚定全部委托 {@code Megalith}（单一代码源 —— 放置函数/选址带/地表高度
 *       与真身严格一致，不再维护渲染层副本）；</li>
 *   <li>顶点格式与 Chunk 一致（pos3+col3+normal3+wind1+uv2，stride=48，location 0..4；立项 A 图集），
 *       复用 worldShader → 自动获得昼夜光色 / 雾 / 暗角；wind=0（剪影无风动）。</li>
 * </ul>
 */
public final class Silhouettes {

    // ---- 视觉常量（§9.3 / §9.4 定稿值，实现不得擅改）----
    /** 剪影雾缓解：白天雾约 256 格饱和 → ×0.35 后可读约 730 格（§9.6「剪影要能从远处认出」）。 */
    public static final float FOG_RELIEF = 0.35f;
    private static final int BURY = 4;             // 基座下埋格数（「被吞噬」暗示，§9.2-5）
    private static final int BUILD_BUDGET = 1;     // 每帧最多新建 1 座（防构建卡顿）

    // ---- 色板（渲染层自有，不进 Blocks.java —— §9.4「材质与地形方块区分」）----
    private static final float[] ASHLAR = pal(212, 206, 192);  // 苍白条石（墙体/柱身）
    private static final float[] SHADE  = pal(146, 140, 128);  // 阴面条石（基座/井筒/凹槽）
    private static final float[] VEIN   = pal(188, 182, 170);  // 纹石（点缀打散白墙）
    private static final float[] GOLD   = pal(232, 190, 96);   // 金饰（全场唯一高饱和暖色）

    private static float[] pal(int r, int g, int b) { return new float[]{r / 255f, g / 255f, b / 255f}; }

    private static final class Mon {
        int vao, vbo, verts;
        int gxc, gzc;      // 巨构中心全局坐标（剪影让位判定用）
        boolean phantom;   // AP-PCG：碑林幻影（无真身；近距隐没，保持「远方幻影」语义）
    }

    /** 碑林点位概率（每巨构 cell 10%，与 Megalith.descAt 互斥 → 不挤占真身点位）。 */
    private static final float STEIN_P = 0.10f;
    /** 碑林近距隐没半径（格）：走近即「雾散」——幻影只属于远方，近景舞台留给真身神殿。 */
    private static final float STEIN_FADE_R = 80f;
    private static final long STEIN_SALT = 0x57E0L;

    private final Map<Long, Mon> live = new HashMap<>();

    /** 全局坐标整数哈希 → [0,1)（与 Chunk.hash01 同族；纯位置函数，不碰任何 RNG 流）。 */
    private static float mh(int x, int y, long salt) {
        int h = (int) (x * 374761393L + y * 668265263L + salt * 1274126177L);
        h = (h ^ (h >>> 13)) * 1274126177;
        h = h ^ (h >>> 16);
        return (h & 0x7fffffff) / (float) 0x7fffffff;
    }

    // ---------------------------------------------------------------- 几何（AABB 盒清单）

    private static final class Box {
        float x0, y0, z0, x1, y1, z1, r, g, b;
    }

    /** 盒级明度抖动（哈希打散大色块，复用 §8 每体素颗粒在 FS 的世界坐标上再叠一层）。 */
    private static float jit(int x, int z, int s) { return 0.94f + 0.12f * mh(x + s, z - s, 0x4A49L); }

    /**
     * 加入一个本地 AABB（lx/lz 相对巨构中心，ly 相对地表顶面），按 rot 旋转 90° 增量落到世界轴。
     * GOLD 烘焙 ×1.8（顶点色 float 允许 &gt;1）：夜环境光 0.26 时仍 ≈0.47 可见 → 「金饰夜里仍亮」。
     */
    private static void box(List<Box> l, int rot, int cx, int cz, int gy,
                            float lx0, float lx1, float ly0, float ly1, float lz0, float lz1,
                            float[] col, float jitv) {
        float mul = (col == GOLD) ? 1.8f : 1f;
        float r = Math.min(1.6f, col[0] * mul * jitv);
        float g = Math.min(1.6f, col[1] * mul * jitv);
        float b = Math.min(1.6f, col[2] * mul * jitv);
        float ax0 = lx0, az0 = lz0, ax1 = lx1, az1 = lz1;
        for (int i = 0; i < rot; i++) {          // rot90: (x,z) → (z,-x)
            float nx0 = az0, nz0 = -ax0, nx1 = az1, nz1 = -ax1;
            ax0 = nx0; az0 = nz0; ax1 = nx1; az1 = nz1;
        }
        Box o = new Box();
        o.x0 = cx + Math.min(ax0, ax1); o.x1 = cx + Math.max(ax0, ax1);
        o.z0 = cz + Math.min(az0, az1); o.z1 = cz + Math.max(az0, az1);
        o.y0 = gy + ly0; o.y1 = gy + ly1;
        o.r = r; o.g = g; o.b = b;
        l.add(o);
    }

    /** 生成一座剪影的全部盒（纯函数，无 GL）。远景 120–220 格 × 近场神殿 48 格 = intentional 10× 尺度差（§9.3）。 */
    private static List<Box> geometry(Megalith.Desc d, World w) {
        List<Box> l = new ArrayList<>();
        int gy = Megalith.groundY(w, d.gxc, d.gzc) + 1;
        if (d.variant == 0) tower(l, d, gy); else gate(l, d, gy);
        return l;
    }

    /** TOWER · 三段式独塔 + 断拱（§9.5-1/3）：基座收分 1.6:1 → 柱廊段 → 冠顶塔尖。 */
    private static void tower(List<Box> l, Megalith.Desc d, int gy) {
        int cx = d.gxc, cz = d.gzc, rot = d.rot;
        int H = 120 + (int) (mh(d.gxc, d.gzc, 0x4845L) * 100f);   // 远景总高 120..219（§9.3）
        // 基座三阶收分（Entasis）：明度水平分层做收分，不靠形状（§9.4 ASHLAR_SHADE）
        box(l, rot, cx, cz, gy, -36, 36, -BURY, 8, -36, 36, SHADE, jit(cx, cz, 1));
        box(l, rot, cx, cz, gy, -30, 30, 8, 16, -30, 30, ASHLAR, jit(cx, cz, 2));
        box(l, rot, cx, cz, gy, -24, 24, 16, 22, -24, 24, SHADE, jit(cx, cz, 3));
        // 柱廊段：井筒暗面 + 每面 5 根凸肋（4 面 20 根）→ 远读即柱列韵律（§9.2-2 重复产生体量）
        int shaftTop = 22 + (H - 48);
        box(l, rot, cx, cz, gy, -18, 18, 22, shaftTop, -18, 18, SHADE, jit(cx, cz, 4));
        for (int o = -14; o <= 14; o += 7) {
            float jj = 0.96f + 0.08f * mh(o, 7, 0x5249L);
            box(l, rot, cx, cz, gy, 18, 20, 22, shaftTop, o, o + 3, ASHLAR, jj);
            box(l, rot, cx, cz, gy, -20, -18, 22, shaftTop, o, o + 3, ASHLAR, jj);
            box(l, rot, cx, cz, gy, o, o + 3, 22, shaftTop, 18, 20, ASHLAR, jj);
            box(l, rot, cx, cz, gy, o, o + 3, 22, shaftTop, -20, -18, ASHLAR, jj);
        }
        // 檐口金环（全场唯一高饱和暖色 → 唯一视觉焦点，§9.2-4）
        box(l, rot, cx, cz, gy, -20, 20, shaftTop, shaftTop + 2, 18, 20, GOLD, 1f);
        box(l, rot, cx, cz, gy, -20, 20, shaftTop, shaftTop + 2, -20, -18, GOLD, 1f);
        box(l, rot, cx, cz, gy, 18, 20, shaftTop, shaftTop + 2, -20, 20, GOLD, 1f);
        box(l, rot, cx, cz, gy, -20, -18, shaftTop, shaftTop + 2, -20, 20, GOLD, 1f);
        // 冠顶四阶 + 塔尖 + 金冠
        int y = shaftTop + 2;
        int[] cw = {30, 24, 18, 12};
        for (int i = 0; i < 4; i++) {
            int hw = cw[i] / 2;
            box(l, rot, cx, cz, gy, -hw, hw, y, y + 4, -hw, hw, i % 2 == 0 ? ASHLAR : SHADE, jit(cx, cz, 10 + i));
            y += 4;
        }
        box(l, rot, cx, cz, gy, -3, 3, y, y + 8, -3, 3, ASHLAR, jit(cx, cz, 14));
        box(l, rot, cx, cz, gy, -2, 2, y + 8, y + 10, -2, 2, GOLD, 1f);
        // 断拱（§9.2-3「未接上的跨」）：自 +X 面悬出、末端金饰；其下断柱到不了拱端 → 断裂的跨
        int a = (int) (H * 0.52f);
        box(l, rot, cx, cz, gy, 18, 26, a, a + 13, -5, 5, ASHLAR, jit(cx, cz, 20));
        box(l, rot, cx, cz, gy, 26, 32, a - 4, a + 11, -4, 4, ASHLAR, jit(cx, cz, 21));
        box(l, rot, cx, cz, gy, 32, 36, a - 9, a + 8, -4, 4, ASHLAR, jit(cx, cz, 22));
        box(l, rot, cx, cz, gy, 36, 38, a - 12, a + 4, -3, 3, GOLD, 1f);
        box(l, rot, cx, cz, gy, 32, 38, -BURY - 12, a - 22, -4, 4, SHADE, jit(cx, cz, 23));
        // 基座外圈半埋碎块（被吞噬，§9.2-5）
        for (int i = 0; i < 8; i++) {
            float f1 = mh(i, 91, 0x5255L), f2 = mh(i, 92, 0x5256L), f3 = mh(i, 93, 0x5257L);
            int sx = (int) ((f1 - 0.5f) * 90), sz = (int) ((f2 - 0.5f) * 90);
            int s = 3 + (int) (f3 * 5);
            box(l, rot, cx, cz, gy, sx, sx + s, -BURY - 4, s - 2, sz, sz + s, VEIN, 0.9f + 0.2f * f3);
        }
    }

    /** GATE · 双柱断跨（§9.5-1）：两根收分巨柱 + 相向悬臂（中段缺口）+ 半埋断龙骨。 */
    private static void gate(List<Box> l, Megalith.Desc d, int gy) {        int cx = d.gxc, cz = d.gzc, rot = d.rot;
        int H = 88 + (int) (mh(d.gxc, d.gzc, 0x4846L) * 24f);     // 柱高 88..111（§9.3）
        int gap = 20 + (int) (mh(d.gxc, d.gzc, 0x4741L) * 20f);   // 缺口 20..39
        for (int side = -1; side <= 1; side += 2) {
            int pc = side * (gap / 2 + 17);                        // 柱心（本地 x）
            float j1 = jit(cx + side, cz, 1), j2 = jit(cx + side, cz, 2);
            box(l, rot, cx, cz, gy, pc - 17, pc + 17, -BURY - 8, 30, -17, 17, SHADE, j1);
            box(l, rot, cx, cz, gy, pc - 13, pc + 13, 30, 62, -13, 13, ASHLAR, j1);
            box(l, rot, cx, cz, gy, pc - 10, pc + 10, 62, H, -10, 10, SHADE, j2);
            // 柱帽 + 金带 + 小尖顶（剪影节奏点）
            box(l, rot, cx, cz, gy, pc - 12, pc + 12, H, H + 3, -12, 12, ASHLAR, j2);
            box(l, rot, cx, cz, gy, pc - 9, pc + 9, H + 3, H + 5, -9, 9, GOLD, 1f);
            box(l, rot, cx, cz, gy, pc - 5, pc + 5, H + 5, H + 13, -5, 5, ASHLAR, j2);
            box(l, rot, cx, cz, gy, pc - 2, pc + 2, H + 13, H + 15, -2, 2, GOLD, 1f);
            // 相向悬臂（断拱两段）：各覆盖缺口 ~42%，中段永远差一口气
            int segs = Math.max(1, (int) (gap * 0.42f / 7f));
            for (int i = 0; i < segs; i++) {
                int d0 = i * 7, d1 = d0 + 7;
                int x0 = side * (gap / 2 - d1), x1 = side * (gap / 2 - d0);
                float xa = Math.min(x0, x1), xb = Math.max(x0, x1);
                box(l, rot, cx, cz, gy, xa, xb, H - 2 + i * 3, H + 2 + i * 3, -6, 6, ASHLAR, 0.95f + 0.1f * i);
            }
            int dEnd = segs * 7;
            int xe0 = side * (gap / 2 - dEnd - 2), xe1 = side * (gap / 2 - dEnd);
            box(l, rot, cx, cz, gy, Math.min(xe0, xe1), Math.max(xe0, xe1), H + segs * 3 - 4, H + segs * 3 + 2, -4, 4, GOLD, 1f);
            // 柱脚碎块
            box(l, rot, cx, cz, gy, pc - 22, pc - 18, -BURY - 4, 5, -14, -10, VEIN, 0.9f);
            box(l, rot, cx, cz, gy, pc + 18, pc + 22, -BURY - 4, 4, 10, 14, VEIN, 1.05f);
        }
        // 半埋断龙骨（掉落的拱心石 → 「曾经横跨过」的物证）
        box(l, rot, cx, cz, gy, -4, 4, -BURY - 4, 4, -4, 4, VEIN, 1.1f);
    }

    /**
     * STEIN · 碑林（AP-PCG 远景地标扩展）：3–5 根风化断柱 + 一根半埋倒柱。
     * 「曾经矗立过」的低语群——比塔/门矮一档（22..51 vs 88..219），无金饰
     * （金是塔与门的唯一焦点色，§9.2-4 纪律不被稀释）；全部随机性来自
     * {@link #mh} 位置哈希（零 RNG 流，与类契约一致）。
     */
    private static List<Box> steinGeometry(World w, int gxc, int gzc) {
        List<Box> l = new ArrayList<>();
        int gy = Megalith.groundY(w, gxc, gzc) + 1;
        int n = 3 + (int) (mh(gxc, gzc, 0x57E1L) * 3f);          // 3..5 根
        for (int i = 0; i < n; i++) {
            float f1 = mh(i + 1, gxc, 0x57E2L), f2 = mh(i + 1, gzc, 0x57E3L);
            float f3 = mh(i + 1, gxc ^ gzc, 0x57E4L), f4 = mh(gxc + i, gzc - i, 0x57E5L);
            int px = (int) ((f1 - 0.5f) * 56), pz = (int) ((f2 - 0.5f) * 56);
            int hw = 3 + (int) (f3 * 3f);                         // 半宽 3..5
            int H = 22 + (int) (f4 * 30f);                        // 柱高 22..51
            int brk = H - 6 - (int) (f1 * 6f);                    // 断口高度（顶部残缺）
            float[] col = (i % 2 == 0) ? ASHLAR : SHADE;
            box(l, 0, gxc + px, gzc + pz, gy, -hw, hw, -BURY - 2, brk, -hw, hw, col, jit(gxc + px, gzc + pz, i));
            // 残顶窄芯（断口上仅存的中芯 → 「被啃噬过」的轮廓）
            box(l, 0, gxc + px, gzc + pz, gy, -hw + 2, hw - 2, brk, H, -hw + 2, hw - 2, SHADE, jit(gxc + px, gzc + pz, i + 9));
        }
        // 半埋倒柱（横卧的巨柱 → 「倒塌的物证」，与 GATE 断龙骨同一叙事语汇）
        int lx = (int) ((mh(gzc, gxc, 0x57E6L) - 0.5f) * 40), lz = (int) ((mh(gxc, gzc, 0x57E7L) - 0.5f) * 40);
        box(l, 0, gxc, gzc, gy, lx - 14, lx + 14, -BURY - 3, 3, lz - 4, lz + 4, VEIN, 0.95f);
        return l;
    }

    // ---------------------------------------------------------------- GL 上传与生命周期

    private Mon build(Megalith.Desc d, World w) {
        return buildBoxes(geometry(d, w), d.gxc, d.gzc, false);
    }

    /** AP-PCG：碑林（STEIN）——无真身的远景幻影地标，盒清单独立于 Megalith 放置序列。 */
    private Mon buildStein(World w, int gxc, int gzc) {
        return buildBoxes(steinGeometry(w, gxc, gzc), gxc, gzc, true);
    }

    private Mon buildBoxes(List<Box> l, int gxc, int gzc, boolean phantom) {
        FloatBuffer buf = memAllocFloat(l.size() * 36 * Chunk.VERT_FLOATS);
        for (Box o : l) emit(buf, o);
        buf.flip();
        Mon m = new Mon();
        m.gxc = gxc; m.gzc = gzc; m.phantom = phantom;
        m.vao = glGenVertexArrays();
        m.vbo = glGenBuffers();
        glBindVertexArray(m.vao);
        glBindBuffer(GL_ARRAY_BUFFER, m.vbo);
        glBufferData(GL_ARRAY_BUFFER, buf, GL_STATIC_DRAW);
        int stride = Chunk.VERT_FLOATS * 4;
        glVertexAttribPointer(0, 3, GL_FLOAT, false, stride, 0L); glEnableVertexAttribArray(0);
        glVertexAttribPointer(1, 3, GL_FLOAT, false, stride, 3L * 4); glEnableVertexAttribArray(1);
        glVertexAttribPointer(2, 3, GL_FLOAT, false, stride, 6L * 4); glEnableVertexAttribArray(2);
        glVertexAttribPointer(3, 1, GL_FLOAT, false, stride, 9L * 4); glEnableVertexAttribArray(3);
        glVertexAttribPointer(4, 2, GL_FLOAT, false, stride, 10L * 4); glEnableVertexAttribArray(4);   // 立项 A：图集 UV
        glVertexAttribPointer(5, 1, GL_FLOAT, false, stride, 12L * 4); glEnableVertexAttribArray(5);   // 立项 C：块光（剪影恒 0）
        glVertexAttribPointer(6, 3, GL_FLOAT, false, stride, 13L * 4); glEnableVertexAttribArray(6);   // 步骤 4：交界过渡（剪影恒 0）
        glBindVertexArray(0);
        m.verts = buf.remaining();
        memFree(buf);
        return m;
    }

    /** 每帧调用：按相机位置装载/卸载剪影（纯定位数学 + 少量 GL 上传，预算 1 座/帧）。 */
    public void update(World w, float camX, float camZ, float range) {
        boolean materialized = w.SY >= Megalith.MIN_SY;   // 真身材化世界：近距剪影让位
        float skip2 = Megalith.SKIP_R * Megalith.SKIP_R;
        int cell = Megalith.cellSize();
        int c0x = Math.floorDiv((int) Math.floor(camX - range), cell);
        int c1x = Math.floorDiv((int) Math.floor(camX + range), cell);
        int c0z = Math.floorDiv((int) Math.floor(camZ - range), cell);
        int c1z = Math.floorDiv((int) Math.floor(camZ + range), cell);
        int budget = BUILD_BUDGET;
        float lim = (range + cell) * (range + cell);
        for (int ccx = c0x; ccx <= c1x; ccx++)
            for (int ccz = c0z; ccz <= c1z; ccz++) {
                long key = ((long) ccx << 32) | (ccz & 0xffffffffL);
                if (live.containsKey(key)) continue;
                float mx = ccx * cell + cell * 0.5f, mz = ccz * cell + cell * 0.5f;
                float dx = mx - camX, dz = mz - camZ;
                if (dx * dx + dz * dz > lim) continue;
                Megalith.Desc d = Megalith.descAt(w, ccx, ccz, materialized);
                if (d == null) {
                    // AP-PCG：无巨构格 → 碑林幻影点位（低频、与真身互斥、纯渲染层）
                    if (budget > 0 && mh(ccx, ccz, STEIN_SALT) < STEIN_P) {
                        budget--;
                        int sx = ccx * cell + cell / 2, sz = ccz * cell + cell / 2;
                        live.put(key ^ (1L << 62), buildStein(w, sx, sz));
                    }
                    continue;
                }
                if (materialized) {
                    float ddx = d.gxc - camX, ddz = d.gzc - camZ;
                    if (ddx * ddx + ddz * ddz < skip2) continue;   // 真身已接管 → 不建剪影
                }
                if (budget <= 0) continue;
                budget--;
                live.put(key, build(d, w));
            }
        // 卸载远去剪影
        float lim2 = (range + cell * 2f) * (range + cell * 2f);
        Iterator<Map.Entry<Long, Mon>> it = live.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Long, Mon> e = it.next();
            Mon m = e.getValue();
            float dx = m.gxc - camX, dz = m.gzc - camZ;
            if (dx * dx + dz * dz > lim2) {
                glDeleteVertexArrays(m.vao);
                glDeleteBuffers(m.vbo);
                it.remove();
            }
        }
    }

    /**
     * 在 worldShader 状态下绘制全部已装载剪影（调用方负责 uFogScale=FOG_RELIEF）。
     * @param materialized 真身材化世界：让位半径内的剪影停画（真身由 chunk 网格接管，防重叠）
     */
    public void draw(float camX, float camZ, boolean materialized) {
        float skip2 = Megalith.SKIP_R * Megalith.SKIP_R;
        float fade2 = STEIN_FADE_R * STEIN_FADE_R;
        for (Mon m : live.values()) {
            if (m.vao == 0) continue;
            if (materialized) {
                float dx = m.gxc - camX, dz = m.gzc - camZ;
                if (dx * dx + dz * dz < skip2) continue;
            }
            if (m.phantom) {                                  // 碑林：近距隐没（雾散幻影）
                float dx = m.gxc - camX, dz = m.gzc - camZ;
                if (dx * dx + dz * dz < fade2) continue;
            }
            glBindVertexArray(m.vao);
            glDrawArrays(GL_TRIANGLES, 0, m.verts);
        }
        glBindVertexArray(0);
    }

    public boolean hasGeometry() { return !live.isEmpty(); }

    // ---------------------------------------------------------------- 顶点发射（与 Chunk 同格式）

    private static void vert(FloatBuffer b, float x, float y, float z,
                             float nx, float ny, float nz, float r, float g, float bl, float u, float v) {
        b.put(x).put(y).put(z).put(r).put(g).put(bl).put(nx).put(ny).put(nz).put(0f).put(u).put(v).put(0f)
         .put(0f).put(0f).put(0f); // wind=0, lamp=0, edge=(0,0,0) 不参与交界过渡
    }

    private static void quad(FloatBuffer b,
                             float ax, float ay, float az, float bx, float by, float bz,
                             float cx, float cy, float cz, float dx, float dy, float dz,
                             float nx, float ny, float nz, float r, float g, float bl) {
        // 立项 A：WHITE_TILE（纯白微噪）——颜色全由顶点色承担（金饰 ×1.8 夜辉烘焙不变）
        float u0 = TextureAtlas.uOf(TextureAtlas.WHITE_TILE, 0f), v0 = TextureAtlas.vOf(TextureAtlas.WHITE_TILE, 0f);
        float u1 = TextureAtlas.uOf(TextureAtlas.WHITE_TILE, 1f), v1 = TextureAtlas.vOf(TextureAtlas.WHITE_TILE, 1f);
        vert(b, ax, ay, az, nx, ny, nz, r, g, bl, u0, v0);
        vert(b, bx, by, bz, nx, ny, nz, r, g, bl, u1, v0);
        vert(b, cx, cy, cz, nx, ny, nz, r, g, bl, u1, v1);
        vert(b, ax, ay, az, nx, ny, nz, r, g, bl, u0, v0);
        vert(b, cx, cy, cz, nx, ny, nz, r, g, bl, u1, v1);
        vert(b, dx, dy, dz, nx, ny, nz, r, g, bl, u0, v1);
    }

    private static void emit(FloatBuffer b, Box o) {
        float r = o.r, g = o.g, bl = o.b;
        quad(b, o.x0, o.y1, o.z1, o.x1, o.y1, o.z1, o.x1, o.y1, o.z0, o.x0, o.y1, o.z0, 0, 1, 0, r, g, bl);
        quad(b, o.x0, o.y0, o.z0, o.x1, o.y0, o.z0, o.x1, o.y0, o.z1, o.x0, o.y0, o.z1, 0, -1, 0, r, g, bl);
        quad(b, o.x1, o.y0, o.z1, o.x1, o.y0, o.z0, o.x1, o.y1, o.z0, o.x1, o.y1, o.z1, 1, 0, 0, r, g, bl);
        quad(b, o.x0, o.y0, o.z0, o.x0, o.y0, o.z1, o.x0, o.y1, o.z1, o.x0, o.y1, o.z0, -1, 0, 0, r, g, bl);
        quad(b, o.x0, o.y0, o.z1, o.x1, o.y0, o.z1, o.x1, o.y1, o.z1, o.x0, o.y1, o.z1, 0, 0, 1, r, g, bl);
        quad(b, o.x1, o.y0, o.z0, o.x0, o.y0, o.z0, o.x0, o.y1, o.z0, o.x1, o.y1, o.z0, 0, 0, -1, r, g, bl);
    }
}
