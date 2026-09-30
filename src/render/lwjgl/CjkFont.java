package render.lwjgl;

import java.awt.Color;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 运行期 CJK 位图字体（零资产、零纹理）——C1：让中文村志 / NPC 回话“上屏”。
 *
 * <p>设计：用 JDK 自带的 {@code java.awt} 把每个字符光栅化成 {@link #CELL}×{@link #CELL} 的布尔点阵
 * （惰性 + 缓存），再按“同行连续亮像素合并为一条”的方式，向 HUD 的 {@link FloatBuffer} 发射与
 * {@link Font#draw} 完全一致的 7 浮点顶点（pos3 + rgba4，clip-space）。因此**不需要**把汉字预做成
 * 资产、也不占用 OpenGL 纹理，和项目“零依赖自包含”的取向一致。
 *
 * <p>零漂移：本类纯渲染层（仅由 {@code Game.drawHud} 调用），不读写任何仿真状态、不进
 * {@code World.hashState()}，对四道门禁指纹零影响。字体缺失 / 无 AWT 时优雅降级：
 * {@link #status()} 报 {@code UNAVAILABLE}，{@link #draw} 直接不发射（不崩溃、不上屏）。
 */
public final class CjkFont {

    public static final int CELL = 16;        // 单字点阵边长（像素）
    private static final int GAP = 1;         // 字间距（像素）
    private static final int ALPHA_MIN = 48;  // 光栅化 alpha 阈值

    private static final Map<Integer, boolean[]> CACHE = new HashMap<Integer, boolean[]>();
    private static java.awt.Font awtFont = null;
    private static boolean ready = false;
    private static String status = "CJK INIT PENDING";

    static { init(); }

    private static void init() {
        try {
            String[] candidates = {
                    "Microsoft YaHei", "微软雅黑", "SimHei", "黑体", "SimSun", "宋体",
                    "NSimSun", "Microsoft JhengHei", "Dialog"
            };
            for (String name : candidates) {
                java.awt.Font f = new java.awt.Font(name, java.awt.Font.PLAIN, CELL);
                if (count(rasterize(f, '\u4e2d')) > 4) {   // 能画出“中”才算数
                    awtFont = f; ready = true;
                    status = "CJK OK  font=" + name + "  cell=" + CELL;
                    return;
                }
            }
            java.awt.Font f = new java.awt.Font(java.awt.Font.SANS_SERIF, java.awt.Font.PLAIN, CELL);
            if (count(rasterize(f, '\u4e2d')) > 4) {
                awtFont = f; ready = true;
                status = "CJK OK  font=logical-SansSerif  cell=" + CELL;
                return;
            }
            status = "CJK UNAVAILABLE (no CJK-capable AWT font found)";
        } catch (Throwable t) {
            ready = false;
            status = "CJK UNAVAILABLE (" + t.getClass().getSimpleName()
                    + (t.getMessage() == null ? "" : ": " + t.getMessage()) + ")";
        }
    }

    public static String status() { return status; }

    public static boolean available() { return ready; }

    private static int count(boolean[] b) { int n = 0; for (boolean x : b) if (x) n++; return n; }

    /** 把单字光栅化成 CELL×CELL 布尔点阵（全角居中、半角左对齐）。 */
    private static boolean[] rasterize(java.awt.Font f, char c) {
        boolean[] bits = new boolean[CELL * CELL];
        try {
            BufferedImage img = new BufferedImage(CELL, CELL, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = img.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
            g.setFont(f);
            FontMetrics fm = g.getFontMetrics();
            boolean wide = isWide(c);
            int gw = fm.charWidth(c);
            int gx = wide ? Math.max(0, (CELL - gw) / 2) : 1;
            int gy = (CELL - fm.getHeight()) / 2 + fm.getAscent();
            g.setColor(Color.WHITE);
            g.drawString(String.valueOf(c), gx, gy);
            g.dispose();
            for (int yy = 0; yy < CELL; yy++)
                for (int xx = 0; xx < CELL; xx++) {
                    int argb = img.getRGB(xx, yy);
                    if (((argb >>> 24) & 0xFF) > ALPHA_MIN) bits[yy * CELL + xx] = true;
                }
        } catch (Throwable ignored) { /* 返回空点阵（该字不显示） */ }
        return bits;
    }

    private static boolean[] glyph(char c) {
        boolean[] g = CACHE.get((int) c);
        if (g == null) { g = rasterize(awtFont, c); CACHE.put((int) c, g); }
        return g;
    }

    /** 东亚全角字符（CJK 汉字/假名/谚文/全角标点）。 */
    static boolean isWide(char c) {
        return c >= 0x1100 && (
                (c <= 0x115F)
                        || c == 0x2329 || c == 0x232A
                        || (c >= 0x2E80 && c <= 0xA4CF)
                        || (c >= 0xAC00 && c <= 0xD7A3)
                        || (c >= 0xF900 && c <= 0xFAFF)
                        || (c >= 0xFE30 && c <= 0xFE4F)
                        || (c >= 0xFF00 && c <= 0xFF60)
                        || (c >= 0xFFE0 && c <= 0xFFE6));
    }

    /** 单字前进宽度（HUD 像素）。 */
    private static float advance(char c, float scale) {
        return (isWide(c) ? (CELL + GAP) : (CELL / 2f + GAP)) * scale;
    }

    /** 估算一行文本的像素宽度（用于居中对齐 / 截断判断）。 */
    public static float width(String text, float scale) {
        if (text == null) return 0f;
        float w = 0f;
        for (int i = 0; i < text.length(); i++) w += advance(text.charAt(i), scale);
        return w;
    }

    /** 按像素宽度换行（CJK 无空格 → 逐字符切分；半角按 0.5 全角计）。 */
    public static List<String> wrap(String text, float scale, float maxPx) {
        List<String> lines = new ArrayList<String>();
        if (text == null || text.isEmpty()) return lines;
        float perLine = maxPx / Math.max(1f, (CELL + GAP) * scale);   // 以全角为单位
        StringBuilder sb = new StringBuilder();
        float w = 0f;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\n') { lines.add(sb.toString()); sb.setLength(0); w = 0f; continue; }
            float cost = isWide(c) ? 1f : 0.5f;
            if (w + cost > perLine && sb.length() > 0) { lines.add(sb.toString()); sb.setLength(0); w = 0f; }
            sb.append(c);
            w += cost;
        }
        if (sb.length() > 0) lines.add(sb.toString());
        return lines;
    }

    /**
     * 把文本光栅化为 2D 三角形追加进 {@code buf}（clip-space，与 {@link Font#draw} 同格式）。
     * (x,y) 为文本左上角（HUD 像素坐标）；scale 缩放；颜色 (r,g,b,a) 0..1。
     */
    public static void draw(FloatBuffer buf, float x, float y, float scale,
                            float r, float g, float b, float a, String text, float W, float H) {
        if (!ready || text == null) return;
        float cx = x;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\n') continue;                       // 调用方已按行切分
            if (c != ' ') emitGlyph(buf, glyph(c), cx, y, scale, r, g, b, a, W, H);
            cx += advance(c, scale);
        }
    }

    /** 把单字点阵按“同行连续亮像素合并为一条”发射（大幅压缩 CJK 的顶点数）。 */
    private static void emitGlyph(FloatBuffer buf, boolean[] bits, float x, float y, float scale,
                                  float r, float g, float b, float a, float W, float H) {
        float step = scale;   // 1 字形像素 = scale 屏幕像素
        for (int row = 0; row < CELL; row++) {
            int col = 0;
            while (col < CELL) {
                if (!bits[row * CELL + col]) { col++; continue; }
                int start = col;
                while (col < CELL && bits[row * CELL + col]) col++;
                float lx = x + start * step, rx = x + col * step;
                float ty = y + row * step, by = ty + step;
                float clx = lx / W * 2f - 1f, crx = rx / W * 2f - 1f;
                float cty = 1f - ty / H * 2f, cby = 1f - by / H * 2f;
                buf.put(clx).put(cty).put(0).put(r).put(g).put(b).put(a).put(TextureAtlas.uOf(TextureAtlas.UI_WHITE_TILE,0.5f)).put(TextureAtlas.vOf(TextureAtlas.UI_WHITE_TILE,0.5f));
                buf.put(crx).put(cty).put(0).put(r).put(g).put(b).put(a).put(TextureAtlas.uOf(TextureAtlas.UI_WHITE_TILE,0.5f)).put(TextureAtlas.vOf(TextureAtlas.UI_WHITE_TILE,0.5f));
                buf.put(crx).put(cby).put(0).put(r).put(g).put(b).put(a).put(TextureAtlas.uOf(TextureAtlas.UI_WHITE_TILE,0.5f)).put(TextureAtlas.vOf(TextureAtlas.UI_WHITE_TILE,0.5f));
                buf.put(clx).put(cty).put(0).put(r).put(g).put(b).put(a).put(TextureAtlas.uOf(TextureAtlas.UI_WHITE_TILE,0.5f)).put(TextureAtlas.vOf(TextureAtlas.UI_WHITE_TILE,0.5f));
                buf.put(crx).put(cby).put(0).put(r).put(g).put(b).put(a).put(TextureAtlas.uOf(TextureAtlas.UI_WHITE_TILE,0.5f)).put(TextureAtlas.vOf(TextureAtlas.UI_WHITE_TILE,0.5f));
                buf.put(clx).put(cby).put(0).put(r).put(g).put(b).put(a).put(TextureAtlas.uOf(TextureAtlas.UI_WHITE_TILE,0.5f)).put(TextureAtlas.vOf(TextureAtlas.UI_WHITE_TILE,0.5f));
            }
        }
    }
}
