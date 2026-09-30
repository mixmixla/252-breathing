package render.lwjgl;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL33;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;

/**
 * 运行期 MSDF 字形图集 + 顶点发射器 —— P1「中文大字号标题」的落地件。
 *
 * <p><b>与 {@link CjkFont} 的分工</b>：{@code CjkFont} 是 16×16 <b>布尔点阵</b>（发射实心像素方块），
 * 便宜、零纹理，但**放大必糊**——它只是把方块像素放大，不产生新的字形细节。本类是**距离场**：
 * 纹理里存的是"到字形边界的距离"，采样后由着色器按屏幕像素换算阈值 → **任意字号都清晰**。
 * 两者并存：小号 HUD 文本继续走 {@code CjkFont}（省纹理带宽），标题/大字号走本类。
 *
 * <p><b>动态图集</b>：不预烘焙整个 CJK 字库（2 万+ 字，几十 MB），而是<b>按需生成</b>：
 * 某个字第一次被绘制时才用 AWT 生成它的 MSDF 并写进下一个空槽，之后缓存命中。
 * 槽位用尽时按 <b>LRU</b> 淘汰；本帧用过的槽被<b>钉住</b>（{@link #beginFrame()} 重置），
 * 保证同一帧内不会把已经发射过顶点的字形覆盖掉（否则画面会出现"半句话是新字、半句话是旧字"）。
 *
 * <p><b>纹理禁忌（两条都会静默出错）</b>：
 * <ol>
 *   <li><b>绝不能走 mipmap</b>：多级平均会破坏距离的线性性，采样出来是"糊块"。
 *       必须 {@code GL_TEXTURE_MAX_LEVEL=0} + 线性过滤（放大用 LINEAR 才有平滑边界）。</li>
 *   <li><b>上传缓冲必须是 direct（{@code MemoryUtil.memAlloc}）</b>：堆 {@code byte[]} 在 LWJGL 下
 *       拿不到有效地址 → 纹理静默上传成全 0。这一条是本项目在 {@link TextureAtlas} 上已经踩过的坑
 *       （见那里的长注释），此处同样规避。</li>
 * </ol>
 *
 * <p><b>零漂移</b>：纯渲染层。不读写任何仿真状态、不进 {@code World.hashState()}、
 * 不消耗 {@code simStream} RNG。生成字形只发生在"某个字第一次上屏"时，与仿真推进无关。
 */
public final class MsdfFont {

    /** 图集边长（像素）。必须能被 {@link MsdfGen#CELL} 整除。 */
    public static final int ATLAS = 1536;
    /** 每行的槽位数。 */
    public static final int COLS = ATLAS / MsdfGen.CELL;          // 32
    /** 总槽位 = 1024 个字形。 */
    public static final int SLOTS = COLS * COLS;
    /** 单槽字节数。 */
    private static final int SLOT_BYTES = MsdfGen.CELL * MsdfGen.CELL * 4;
    /** 单顶点浮点数：pos3 + col4 + uv2 —— 与 HUD 通道<b>同构</b>（见 {@code Game.HUD_VERT_FLOATS}）。 */
    public static final int VERT_FLOATS = 9;

    // ---- 字形缓存 ----
    private static final int[] SLOT_OF = new int[65536];     // char → slot+1（0 = 未生成）
    private static final float[] ADV_OF = new float[65536];  // char → advanceEm
    private static final int[] STAMP = new int[SLOTS];       // LRU 时间戳
    private static final boolean[] PINNED = new boolean[SLOTS];
    private static final boolean[] DIRTY = new boolean[SLOTS];
    private static int usedSlots = 0;
    private static int clock = 0;

    // ---- CPU 侧图集（GL 方向：第 0 行 = 纹理底部，与 MsdfGen 的"顶行在下标 0"相反）----
    private static byte[] atlas = new byte[ATLAS * ATLAS * 4];

    // ---- GL ----
    private static int tex = 0;
    private static ByteBuffer upload = null;                 // direct，单槽大小，复用

    // ---- 状态 ----
    private static java.awt.Font awtFont = null;
    private static boolean ready = false;
    private static String status = "MSDF INIT PENDING";
    /** 描边宽度（屏幕像素 × PX_RANGE 归一）。0 = 关闭。由 {@code Game} 在绘制标题前设置。 */
    private static float outlineWidth = 0f;
    private static float outlineR = 0f, outlineG = 0f, outlineB = 0f;

    static { init(); }

    private MsdfFont() { }

    private static void init() {
        try {
            String[] candidates = {
                    "Microsoft YaHei", "微软雅黑", "SimHei", "黑体", "SimSun", "宋体",
                    "NSimSun", "Microsoft JhengHei", "Dialog"
            };
            for (String name : candidates) {
                java.awt.Font f = new java.awt.Font(name, java.awt.Font.PLAIN, MsdfGen.GLYPH_PX);
                float[] L = MsdfGen.layout(f, '\u4e2d');
                if (L[2] > 1f) {                       // 能排出"中"才算数
                    awtFont = f; ready = true;
                    status = "MSDF OK  font=" + name + "  cell=" + MsdfGen.CELL
                            + "  atlas=" + ATLAS + " slots=" + SLOTS;
                    return;
                }
            }
            java.awt.Font f = new java.awt.Font(java.awt.Font.SANS_SERIF, java.awt.Font.PLAIN, MsdfGen.GLYPH_PX);
            float[] L = MsdfGen.layout(f, '\u4e2d');
            if (L[2] > 1f) {
                awtFont = f; ready = true;
                status = "MSDF OK  font=logical-SansSerif  cell=" + MsdfGen.CELL;
                return;
            }
            status = "MSDF UNAVAILABLE (no CJK-capable AWT font)";
        } catch (Throwable t) {
            ready = false;
            status = "MSDF UNAVAILABLE (" + t.getClass().getSimpleName()
                    + (t.getMessage() == null ? "" : ": " + t.getMessage()) + ")";
        }
    }

    public static String status() { return status; }

    public static boolean available() { return ready; }

    public static int texture() { return tex; }

    /** 设置后续绘制用的描边样式（在 {@link #draw} 之前调用；对所有 MSDF 文本生效）。 */
    public static void setOutline(float widthPx, float r, float g, float b) {
        outlineWidth = widthPx;
        outlineR = r; outlineG = g; outlineB = b;
    }

    public static float outlineWidth() { return outlineWidth; }
    public static float outlineR() { return outlineR; }
    public static float outlineG() { return outlineG; }
    public static float outlineB() { return outlineB; }

    /** 已用槽位 / 总槽位（诊断用）。 */
    public static int usedSlots() { return usedSlots; }

    /**
     * 创建 GL 纹理并分配存储。必须在 GL 上下文就绪后调用一次。
     *
     * <p>存储一次性用 {@code glTexImage2D(..., null)} 全量定义，之后每个字形只走
     * {@code glTexSubImage2D} 写自己那一块。{@code MAX_LEVEL=0} 明确关掉 mipmap
     * （MSDF 的距离在 mip 平均下会失真；且这样也避免"未定义层级"陷阱）。
     */
    public static void glInit() {
        if (!ready || tex != 0) return;
        tex = GL11.glGenTextures();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, tex);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL33.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL33.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL33.GL_TEXTURE_MAX_LEVEL, 0);   // 禁用 mipmap
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA, ATLAS, ATLAS, 0,
                GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, (ByteBuffer) null);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
        upload = MemoryUtil.memAlloc(SLOT_BYTES);
    }

    /** 每帧开头调用：重置"钉住"标记（本帧用到的字形不再可被淘汰）。 */
    public static void beginFrame() {
        clock++;
        java.util.Arrays.fill(PINNED, false);
    }

    /** 把本帧新生成/更新的槽上传到 GPU。放在绘制调用之前。 */
    public static void flushUploads() {
        if (tex == 0) return;
        boolean any = false;
        for (int s = 0; s < SLOTS; s++) if (DIRTY[s]) { any = true; break; }
        if (!any) return;
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, tex);
        for (int s = 0; s < SLOTS; s++) {
            if (!DIRTY[s]) continue;
            DIRTY[s] = false;
            upload.clear();
            int sx = (s % COLS) * MsdfGen.CELL;
            int sy = (s / COLS) * MsdfGen.CELL;
            // 从 CPU 图集里把这一块拷进 direct buffer（图集已是 GL 方向，逐行拷即可）
            for (int row = 0; row < MsdfGen.CELL; row++) {
                int off = ((sy + row) * ATLAS + sx) * 4;
                for (int i = 0; i < MsdfGen.CELL * 4; i++) upload.put(atlas[off + i]);
            }
            upload.flip();
            GL33.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, sx, sy, MsdfGen.CELL, MsdfGen.CELL,
                    GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, upload);
        }
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
    }

    // ------------------------------------------------------------------
    // 排版
    // ------------------------------------------------------------------

    /** 一行文本的像素宽度（与 {@link #draw} 的前进量严格一致）。 */
    public static float width(String text, float size) {
        if (text == null) return 0f;
        float w = 0f;
        for (int i = 0; i < text.length(); i++) w += advanceOf(text.charAt(i)) * size;
        return w;
    }

    private static float advanceOf(char c) {
        if (c > 65535) return 0.5f;
        float a = ADV_OF[c];
        if (a > 0f) return a;
        if (!ready) return 0.5f;
        float[] L = MsdfGen.layout(awtFont, c);
        a = L[4] > 0f ? L[4] : 0.5f;
        ADV_OF[c] = a;
        return a;
    }

    /**
     * 把文本作为 MSDF 四边形追加进 {@code buf}。
     *
     * @param x,y  文本左上角（HUD 像素坐标）
     * @param size 字号 = 行盒高度（像素）。CJK 字形实际墨迹约为它的 0.76
     */
    public static void draw(FloatBuffer buf, float x, float y, float size,
                            float r, float g, float b, float a, String text, float W, float H) {
        if (!ready || text == null || tex == 0) return;
        if (text.isEmpty()) return;
        int len = text.length();
        // 预留空间：每字 6 顶点 × VERT_FLOATS。容量不足时静默丢弃剩余字符（不越界、不刷屏）
        int need = len * 6 * VERT_FLOATS;
        if (buf.remaining() < need) len = Math.max(0, buf.remaining() / (6 * VERT_FLOATS));
        float cx = x;
        for (int i = 0; i < len; i++) {
            char c = text.charAt(i);
            if (c == '\n') continue;
            if (c != ' ') {
                int slot = acquire(c);
                if (slot >= 0) emit(buf, slot, c, cx, y, size, r, g, b, a, W, H);
            }
            cx += advanceOf(c) * size;
        }
    }

    // ------------------------------------------------------------------
    // 图集槽位管理
    // ------------------------------------------------------------------

    /** 取得字形槽位（必要时生成）。返回 -1 = 本帧无槽可用（全被钉住）。 */
    private static int acquire(char c) {
        if (c > 65535) return -1;
        int h = SLOT_OF[c];
        if (h != 0) {
            int s = h - 1;
            PINNED[s] = true;
            STAMP[s] = clock;
            return s;
        }
        int slot = findSlot();
        if (slot < 0) return -1;
        byte[] glyph = MsdfGen.generate(awtFont, c);
        blit(glyph, slot);
        SLOT_OF[c] = slot + 1;
        PINNED[slot] = true;
        STAMP[slot] = clock;
        DIRTY[slot] = true;
        return slot;
    }

    /** 找空槽；满了就淘汰"本帧未钉住且时间戳最旧"的那一个。 */
    private static int findSlot() {
        if (usedSlots < SLOTS) return usedSlots++;              // 直接线性分配，天然不重复
        int victim = -1, oldest = Integer.MAX_VALUE;
        for (int s = 0; s < SLOTS; s++) {
            if (PINNED[s]) continue;
            if (STAMP[s] < oldest) { oldest = STAMP[s]; victim = s; }
        }
        if (victim < 0) return -1;
        // 反向索引要一并清掉，否则被淘汰的字还会命中旧槽（读到别人的字形）
        for (int ch = 0; ch < 65536; ch++) if (SLOT_OF[ch] == victim + 1) { SLOT_OF[ch] = 0; break; }
        return victim;
    }

    /**
     * 把 {@link MsdfGen} 的"顶行在下标 0"数据写进 GL 方向的图集（第 0 行 = 纹理底部），
     * 因此逐行<b>上下翻转</b>。漏掉这一步字形会上下颠倒。
     */
    private static void blit(byte[] glyph, int slot) {
        int sx = (slot % COLS) * MsdfGen.CELL;
        int sy = (slot / COLS) * MsdfGen.CELL;
        for (int row = 0; row < MsdfGen.CELL; row++) {
            int dstRow = sy + (MsdfGen.CELL - 1 - row);
            System.arraycopy(glyph, row * MsdfGen.CELL * 4, atlas, (dstRow * ATLAS + sx) * 4, MsdfGen.CELL * 4);
        }
    }

    // ------------------------------------------------------------------
    // 顶点发射
    // ------------------------------------------------------------------

    /**
     * 发射一个字形四边形。UV 只覆盖槽位里的<b>内容盒</b>（不含距离场 padding），
     * 内容盒 → 屏幕 size×size 的映射由 {@link MsdfGen#layout} 给出，与生成端同源。
     */
    private static void emit(FloatBuffer buf, int slot, char c, float x, float y, float size,
                             float r, float g, float b, float a, float W, float H) {
        float[] L = MsdfGen.layout(awtFont, c);
        if (L[2] <= 0f || L[3] <= 0f) return;
        float s = size / L[3];                     // cell 像素 → 屏幕像素
        int sx = (slot % COLS) * MsdfGen.CELL;
        int sy = (slot / COLS) * MsdfGen.CELL;

        float lx = x, rx = x + L[2] * s;
        float ty = y, by = y + L[3] * s;
        // u 覆盖内容盒的横向范围；v 从"内容盒顶"到"内容盒底"。
        // 图集 y 向上、cell 内 y 向下 → 距槽底的高度 = CELL − (cell 内 y)。
        // L[1] 是内容盒**顶边**的 cell y（不是基线！见 MsdfGen.layout 的注释）。
        float u0 = (sx + L[0]) / ATLAS;
        float u1 = (sx + L[0] + L[2]) / ATLAS;
        float vTop = (sy + MsdfGen.CELL - L[1]) / ATLAS;
        float vBot = (sy + MsdfGen.CELL - L[1] - L[3]) / ATLAS;

        float clx = lx / W * 2f - 1f, crx = rx / W * 2f - 1f;
        float cty = 1f - ty / H * 2f, cby = 1f - by / H * 2f;

        buf.put(clx).put(cty).put(0).put(r).put(g).put(b).put(a).put(u0).put(vTop);
        buf.put(crx).put(cty).put(0).put(r).put(g).put(b).put(a).put(u1).put(vTop);
        buf.put(crx).put(cby).put(0).put(r).put(g).put(b).put(a).put(u1).put(vBot);
        buf.put(clx).put(cty).put(0).put(r).put(g).put(b).put(a).put(u0).put(vTop);
        buf.put(crx).put(cby).put(0).put(r).put(g).put(b).put(a).put(u1).put(vBot);
        buf.put(clx).put(cby).put(0).put(r).put(g).put(b).put(a).put(u0).put(vBot);
    }

    /** 诊断：字形缓存里的字符数（接进 HUD 心跳日志，用于观察图集占用增长）。 */
    public static int cachedChars() {
        int n = 0;
        for (int i = 0; i < 65536; i++) if (SLOT_OF[i] != 0) n++;
        return n;
    }
}
