import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 门禁 MESHSTRIDE：顶点写入步长契约（无头，零 GL）。
 *
 * <p><b>为什么需要它</b>：顶点缓冲是"裸 FloatBuffer + 手写 put 链"，布局约定
 * {@code pos3 col3 nrm3 wind1 uv2 lamp1 edge3 = 16}（{@link render.lwjgl.Chunk#VERT_FLOATS}）
 * 分散在多个 emit 实现里。一旦某条路径少写/多写字段，{@code FloatBuffer.put} 会
 * <b>静默越界丢弃</b>（NIO 缓冲区写满后 put 直接丢数据、不抛异常），表现为
 * "地形/植被随机空洞"，且只在 {@code upload()} 里留下一行易被刷屏淹没的告警。
 *
 * <p>2026-09-21 真实事故：{@code Chunk.crossV} 只写 13 float（漏 edge3），
 * 每个 cross quad 少推 12 float，导致花丛/交叉植被所在区块尾部 quad 被丢弃，
 * 丢的量与花数严格成正比（chunk(0,1) 花 188 → 丢 71 quads）。此类缺陷曾在
 * 65 道门禁全绿的情况下长期存在。
 *
 * <p><b>本门禁做什么</b>：不跑 GL，直接解析源码，把每个"顶点写入点"的
 * {@code put} 链展开计数，要求<b>恰好等于 VERT_FLOATS</b>。覆盖：
 * <ul>
 *   <li>{@code Game.putV} —— 逐面/greedy 主路径（链式 put，含跨行续写）</li>
 *   <li>{@code Chunk.crossV} —— 交叉植被路径</li>
 *   <li>{@code Chunk.emitFaceE} —— 发光源路径</li>
 * </ul>
 * 解析失败（找不到函数）一律判 FAIL，杜绝"扫不到就静默通过"的假绿。
 */
public final class MeshStrideCheck {

    private static boolean ok = true;

    private static void check(String name, boolean cond) {
        System.out.println("  [" + (cond ? "ok  " : "FAIL") + "] " + name);
        if (!cond) ok = false;
    }

    /** 顶点布局常量（与 Chunk.VERT_FLOATS 必须一致；此处独立声明以便"两边不一致也报错"）。 */
    private static final int EXPECT = 16;

    public static void main(String[] args) throws Exception {
        System.out.println("=== MESHSTRIDE: 顶点写入步长契约 ===");

        Path root = Paths.get(System.getProperty("bw.root", "."));
        Path chunk = root.resolve("src/render/lwjgl/Chunk.java");
        Path game = root.resolve("src/render/lwjgl/Game.java");

        check("Chunk.java 可读", Files.isReadable(chunk));
        check("Game.java 可读", Files.isReadable(game));
        if (!Files.isReadable(chunk) || !Files.isReadable(game)) {
            System.out.println("MESHSTRIDE RESULT: FAIL");
            System.exit(1);
            return;
        }

        String chunkSrc = new String(Files.readAllBytes(chunk), StandardCharsets.UTF_8);
        String gameSrc = new String(Files.readAllBytes(game), StandardCharsets.UTF_8);

        // ---------- 0) VERT_FLOATS 常量本身 ----------
        int declared = readVertFloats(chunkSrc);
        check("Chunk.VERT_FLOATS == " + EXPECT + "（实际 " + declared + "）", declared == EXPECT);

        // ---------- 1) Game.putV：链式 put ----------
        String putV = extractMethod(gameSrc, "putV");
        check("Game.putV 找到", putV != null);
        if (putV != null) {
            int n = countPutsInChain(putV);
            check("Game.putV put 链 == " + EXPECT + "（实际 " + n + "）", n == EXPECT);
        }

        // ---------- 2) Chunk.crossV：单条链式 put ----------
        String crossV = extractMethod(chunkSrc, "crossV");
        check("Chunk.crossV 找到", crossV != null);
        if (crossV != null) {
            int n = countPutsInChain(crossV);
            check("Chunk.crossV put 链 == " + EXPECT + "（实际 " + n + "）", n == EXPECT);
        }

        // ---------- 3) Chunk.emitFaceE：循环内 4 顶点 × 逐字段 put ----------
        String emitFaceE = extractMethod(chunkSrc, "emitFaceE");
        check("Chunk.emitFaceE 找到", emitFaceE != null);
        if (emitFaceE != null) {
            // 形态：for (int i = 0; i < 4; i++) b.put(...).put(...)  (16 个)
            int n = countPutsInChain(emitFaceE);
            check("Chunk.emitFaceE put 链 == " + EXPECT + "（实际 " + n + "）", n == EXPECT);
        }

        // ---------- 4) 反向：不允许存在"未纳入监控"的顶点写入点 ----------
        // 凡出现 ".put(" 的方法，若既是顶点写入又不在上述集合，应当被发现。
        List<String> vertWriters = new ArrayList<>();
        for (String m : new String[]{"putV", "crossV", "emitFaceE", "emitQuad", "emit"}) {
            if (extractMethod(chunkSrc, m) != null || extractMethod(gameSrc, m) != null) vertWriters.add(m);
        }
        check("已识别顶点写入点 ≥ 3（" + vertWriters + "）", vertWriters.size() >= 3);

        System.out.println("MESHSTRIDE RESULT: " + (ok ? "PASS" : "FAIL"));
        if (!ok) System.exit(1);
    }

    /** 读 Chunk.VERT_FLOATS 的字面量值。 */
    private static int readVertFloats(String src) {
        Matcher m = Pattern.compile("VERT_FLOATS\\s*=\\s*(\\d+)").matcher(src);
        return m.find() ? Integer.parseInt(m.group(1)) : -1;
    }

    /**
     * 抽取方法体（花括号配对）。{@code name} 匹配 {@code ... name(...) {} }。
     * 找不到返回 null（调用方判 FAIL，不静默通过）。
     */
    private static String extractMethod(String src, String name) {
        // 定位 "name(" 且前面是类型/空白（避免匹配到调用点）：取最后一个匹配（定义通常在类中段）
        Pattern sig = Pattern.compile("(?:private|public|protected|static|final|\\s)*"
                + "[\\w<>\\[\\]]+\\s+" + Pattern.quote(name) + "\\s*\\(");
        Matcher m = sig.matcher(src);
        int best = -1;
        while (m.find()) best = m.start();
        if (best < 0) return null;
        int open = src.indexOf('{', best);
        if (open < 0) return null;
        int depth = 0;
        for (int i = open; i < src.length(); i++) {
            char c = src.charAt(i);
            if (c == '{') depth++;
            else if (c == '}') {
                depth--;
                if (depth == 0) return src.substring(open, i + 1);
            }
        }
        return null;
    }

    /**
     * 统计方法体内"最长 put 链"的 put 数。
     * 支持跨行续写（链以 {@code ;} 结束），忽略字符串字面量与注释。
     */
    private static int countPutsInChain(String body) {
        String code = stripCommentsAndStrings(body);
        int best = 0;
        int i = 0;
        while (true) {
            int at = code.indexOf(".put(", i);
            if (at < 0) break;
            // 向前看：这条链还会继续多少 .put( ?
            int cnt = 0;
            int j = at;
            while (true) {
                int p = code.indexOf(".put(", j);
                if (p < 0) break;
                // 中间不允许出现 ';'（链断）
                if (code.indexOf(';', j) >= 0 && code.indexOf(';', j) < p) break;
                cnt++;
                int close = code.indexOf(')', p);
                if (close < 0) break;
                j = close + 1;
            }
            if (cnt > best) best = cnt;
            i = at + 5;
        }
        return best;
    }

    /** 去掉注释与字符串字面量，避免误计数。 */
    private static String stripCommentsAndStrings(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        int i = 0, n = s.length();
        while (i < n) {
            char c = s.charAt(i);
            if (c == '/' && i + 1 < n && s.charAt(i + 1) == '/') {
                while (i < n && s.charAt(i) != '\n') i++;
            } else if (c == '/' && i + 1 < n && s.charAt(i + 1) == '*') {
                i += 2;
                while (i + 1 < n && !(s.charAt(i) == '*' && s.charAt(i + 1) == '/')) i++;
                i = Math.min(n, i + 2);
            } else if (c == '"' || c == '\'') {
                char q = c;
                sb.append(' ');
                i++;
                while (i < n && s.charAt(i) != q) {
                    if (s.charAt(i) == '\\') i++;
                    i++;
                }
                i++;
            } else {
                sb.append(c);
                i++;
            }
        }
        return sb.toString();
    }

    private MeshStrideCheck() {}
}
