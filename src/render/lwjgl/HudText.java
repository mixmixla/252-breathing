package render.lwjgl;

import java.nio.FloatBuffer;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import static org.lwjgl.system.MemoryUtil.*;

/**
 * HUD 文本 quad 缓存（AP-TX 性能轮）。
 *
 * <p>动机：{@link Font#draw}/{@link CjkFont#draw} 是立即模式——每帧逐字符查字形点阵、
 * 逐亮像素行合并发射顶点。中文一行 ≈ 数千顶点，操作卡 9 行每帧重发射 ≈ 数万顶点的
 * CPU 开销，而内容帧帧相同。本类把「参数组合 → 顶点数组」做成缓存：命中时只做一次
 * {@code buf.put(data)}，字形光栅化与顶点生成全部跳过。</p>
 *
 * <p>缓存 key = 全参数拼接（文本/位置/缩放/颜色/屏幕尺寸）。动态文本（数值跳动、alpha
 * 渐隐）key 每帧变化 → 自然 miss → 与旧路径开销持平，无劣化；两帧未使用的条目在
 * {@link #frameEnd()} 清除，缓存规模恒定收敛到「静态文本集合 + 当帧动态文本」。</p>
 *
 * <p>零漂移：纯渲染层，不读写仿真状态、不碰任何 RNG 流、不进指纹。
 * CJK 不可用时与 {@link CjkFont#draw} 同样优雅降级（不发射、不崩溃）。</p>
 */
public final class HudText {

    private static final Map<String, float[]> CACHE = new HashMap<>();
    private static final Map<String, Integer> AGE = new HashMap<>();  // 帧龄：0=本帧新建/命中
    private static final StringBuilder KB = new StringBuilder(256);
    /** 单次文本发射的堆外 scratch（一次 draw 一个文本块，CJK 单行上限 ≈ 5376 float/字，2M float 足够）。 */
    private static final FloatBuffer SCRATCH = memAllocFloat(1 << 21);

    private HudText() { }

    /** 缓存版 {@link Font#draw}（签名一致）。 */
    public static void ascii(FloatBuffer buf, float x, float y, float scale,
                             float r, float g, float b, float a, String text, float W, float H) {
        emit(buf, 'A', x, y, scale, r, g, b, a, text, W, H);
    }

    /** 缓存版 {@link CjkFont#draw}（签名一致）。 */
    public static void cjk(FloatBuffer buf, float x, float y, float scale,
                           float r, float g, float b, float a, String text, float W, float H) {
        if (!CjkFont.available()) return;
        emit(buf, 'C', x, y, scale, r, g, b, a, text, W, H);
    }

    private static void emit(FloatBuffer buf, char kind, float x, float y, float scale,
                             float r, float g, float b, float a, String text, float W, float H) {
        if (buf == null || text == null || text.isEmpty()) return;
        String key = key(kind, x, y, scale, r, g, b, a, text, W, H);
        float[] data = CACHE.get(key);
        if (data != null) {
            AGE.remove(key);
            buf.put(data);
            return;
        }
        SCRATCH.clear();
        if (kind == 'A') Font.draw(SCRATCH, x, y, scale, r, g, b, a, text, W, H);
        else CjkFont.draw(SCRATCH, x, y, scale, r, g, b, a, text, W, H);
        int n = SCRATCH.position();
        if (n <= 0) return;
        data = new float[n];
        SCRATCH.flip();
        SCRATCH.get(data);
        CACHE.put(key, data);
        AGE.put(key, 0);
        buf.put(data);
    }

    private static String key(char kind, float x, float y, float scale,
                              float r, float g, float b, float a, String text, float W, float H) {
        KB.setLength(0);
        KB.append(kind).append('|').append(x).append('|').append(y).append('|').append(scale)
          .append('|').append(r).append('|').append(g).append('|').append(b).append('|').append(a)
          .append('|').append(W).append('|').append(H).append('|').append(text);
        return KB.toString();
    }

    /** 每帧渲染末调用一次：两帧未被使用的条目移出缓存（动态文本旧值不堆积）。 */
    public static void frameEnd() {
        Iterator<Map.Entry<String, Integer>> it = AGE.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, Integer> e = it.next();
            if (e.getValue() >= 1) { CACHE.remove(e.getKey()); it.remove(); }
            else e.setValue(e.getValue() + 1);
        }
    }
}
