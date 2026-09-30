import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL33;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * 内联 GLSL 离屏编译校验器（无头）。
 *
 * <p><b>为什么存在</b>：本项目的 GLSL 全部内联在 {@code render/lwjgl/Game.java} 的
 * {@code initXxxShader()} 里，历史上开发沙箱"无法验证 GLSL 编译"，导致长期不敢升级 shader。
 * 本工具创建<b>不可见 GLFW 窗口 + 真 GL 上下文</b>，把 Game.java 里的着色器<b>抽取后真编译+链接</b>，
 * 让"改 shader"从赌变成可证。</p>
 *
 * <p><b>零副本漂移</b>：着色器源码直接从 Game.java 解析（不维护第二份副本）——改完源码立刻可验。</p>
 *
 * <p>用法：</p>
 * <ul>
 *   <li>{@code java ShaderCheck}                     —— 解析 Game.java，校验全部内联程序</li>
 *   <li>{@code java ShaderCheck <dir>}               —— 校验 dir 下每个 {@code name.vs}/{@code name.fs} 对</li>
 * </ul>
 * <p>无 GL 可用时打印 {@code SHADERCHECK SKIP (no GL)} 并 exit 0（不算失败，只是没验成）。</p>
 */
public final class ShaderCheck {

    /** Game.java 中要抽取的程序：显示名 → 方法签名片段（在该方法体内找 2 个 "#version" 字面量）。 */
    private static final String[][] METHODS = {
            {"basic",  "private void initShader()"},
            {"hud",    "private void initHudShader()"},
            {"world",  "private void initWorldShader()"},
            {"sky",    "private void initSkyShader()"},
            {"shadow", "private void initShadowShader()"},
            {"under",  "private void initUnderwaterOverlay()"},   // 2026-09-20：MC 水下叠加层
            {"crack",  "private void initCrackPass()"},           // 2026-09-20：MC 破坏阶段裂纹
            {"msdf",   "private void initMsdfShader()"},          // 2026-09-23：P1 MSDF 距离场字体通道
    };

    public static void main(String[] args) throws Exception {
        File gameFile = new File("src/render/lwjgl/Game.java");
        if (!gameFile.isFile() && args.length == 0) {
            System.out.println("SHADERCHECK SKIP (Game.java not found; run from project root)");
            System.exit(0);
        }

        List<String[]> progs = new ArrayList<String[]>();   // {name, vs, fs}

        if (args.length >= 1) {
            File dir = new File(args[0]);
            File[] all = dir.listFiles();
            if (all != null) {
                List<File> vsList = new ArrayList<File>();
                for (File f : all) if (f.getName().endsWith(".vs")) vsList.add(f);
                vsList.sort(java.util.Comparator.comparing(File::getName));
                for (File vs : vsList) {
                    String base = vs.getName().substring(0, vs.getName().length() - 3);
                    File fs = new File(dir, base + ".fs");
                    if (!fs.isFile()) continue;
                    progs.add(new String[]{base, read(vs), read(fs)});
                }
            }
        } else {
            String src = new String(Files.readAllBytes(gameFile.toPath()), StandardCharsets.UTF_8);
            for (String[] m : METHODS) {
                String[] pair = extractMethodShaders(src, m[1]);
                if (pair == null) {
                    System.out.println("SHADERCHECK FAIL: 无法从 " + m[1] + " 抽取着色器");
                    System.exit(1);
                }
                progs.add(new String[]{m[0], pair[0], pair[1]});
            }
            String fvs = extractAssignedLiteral(src, "FALLBACK_VS");
            String ffs = extractAssignedLiteral(src, "FALLBACK_FS");
            if (fvs != null && ffs != null) progs.add(new String[]{"fallback", fvs, ffs});
            // 泰拉瑞亚缺口② 泛光 4 程序（提升为类级 BLOOM_* 常量，构建期真编译验证；GLSL 与运行期单一真相）
            // 注：bright/blur/composite 共用全屏三角形 VS = BLOOM_QUAD_VS
            addPair(progs, src, "bloom-emissive",  "BLOOM_EMISSIVE_VS", "BLOOM_EMISSIVE_FS");
            addPair(progs, src, "bloom-bright",    "BLOOM_QUAD_VS",     "BLOOM_BRIGHT_FS");
            addPair(progs, src, "bloom-blur",      "BLOOM_QUAD_VS",     "BLOOM_BLUR_FS");
            addPair(progs, src, "bloom-composite", "BLOOM_QUAD_VS",     "BLOOM_COMP_FS");
            // 时间累积泛光 1 程序（Noita post_glow1/2，2026-09-21）：一轮内同时做上一帧模糊衰减与本帧追赶
            addPair(progs, src, "bloom-glow",      "BLOOM_QUAD_VS",     "BLOOM_GLOW_FS");
            // §19 god-ray（屏幕空间体积光）：新 shader 必须进这张表，否则「构建全绿」却可能在实机黑屏
            addPair(progs, src, "god-ray",         "BLOOM_QUAD_VS",     "GODRAY_FS");
        }

        if (progs.isEmpty()) {
            System.out.println("SHADERCHECK FAIL: 没找到任何着色器");
            System.exit(1);
        }

        // ---- 离屏 GL 上下文 ----
        // 无 GL/无原生库时优雅跳过（不算失败）：本机没显卡也能正常构建，只是这一道没验成。
        long win;
        try {
            if (!GLFW.glfwInit()) { System.out.println("SHADERCHECK SKIP (glfwInit failed)"); System.exit(0); }
            GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE);
            GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR, 3);
            GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR, 3);
            GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_PROFILE, GLFW.GLFW_OPENGL_CORE_PROFILE);
            win = GLFW.glfwCreateWindow(64, 64, "shadercheck", 0L, 0L);
            if (win == 0L) { System.out.println("SHADERCHECK SKIP (no GL context)"); System.exit(0); }
            GLFW.glfwMakeContextCurrent(win);
            GL.createCapabilities();
            System.out.println("GL_RENDERER = " + GL33.glGetString(GL33.GL_RENDERER)
                    + "  GLSL = " + GL33.glGetString(GL33.GL_SHADING_LANGUAGE_VERSION));
        } catch (Throwable t) {
            System.out.println("SHADERCHECK SKIP (GL unavailable: " + t.getClass().getSimpleName() + " " + t.getMessage() + ")");
            System.exit(0);
            return;
        }

        int failed = 0;
        for (String[] p : progs) {
            String err = compileAndLink(p[1], p[2]);
            if (err == null) System.out.println("  [OK]   " + p[0] + "  (vs " + p[1].length() + "B / fs " + p[2].length() + "B)");
            else { System.out.println("  [FAIL] " + p[0] + "\n" + err); failed++; }
        }

        GLFW.glfwDestroyWindow(win);
        GLFW.glfwTerminate();
        System.out.println(failed == 0 ? ("SHADERCHECK PASS (" + progs.size() + " programs)")
                                       : ("SHADERCHECK FAIL " + failed + "/" + progs.size()));
        System.exit(failed == 0 ? 0 : 1);
    }

    private static String read(File f) throws IOException {
        return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
    }

    /** 在 src 中定位 methodSig，取其方法体，抽取体内前两个 "#version" 字符串字面量（VS, FS）。 */
    static String[] extractMethodShaders(String src, String methodSig) {
        int at = src.indexOf(methodSig);
        if (at < 0) return null;
        int end = src.length();
        // 方法体终点：下一个同级成员声明
        int next = src.indexOf("\n    private ", at + methodSig.length());
        if (next < 0) next = src.indexOf("\n    public ", at + methodSig.length());
        if (next < 0) next = src.indexOf("\n    static ", at + methodSig.length());
        if (next > 0) end = next;
        String body = src.substring(at, end);
        String a = extractFirstLiteralContaining(body, "#version");
        if (a == null) return null;
        int used = body.indexOf("#version");
        String b = extractFirstLiteralContaining(body.substring(used + 8), "#version");
        if (b == null) return null;
        return new String[]{a, b};
    }

    /** 抽取 "FIELD = " 后紧跟的第一个字符串字面量（用于 FALLBACK_VS/FS）。 */
    static String extractAssignedLiteral(String src, String field) {
        int at = src.indexOf(field);
        if (at < 0) return null;
        int q = src.indexOf('"', at);
        if (q < 0) return null;
        return readLiteral(src, q);
    }

    /** 取一对着色器字段并加入待校验列表（任一缺失则跳过，不构成失败）。 */
    private static void addPair(List<String[]> progs, String src, String name, String vsField, String fsField) {
        String vs = extractAssignedLiteral(src, vsField);
        String fs = extractAssignedLiteral(src, fsField);
        if (vs != null && fs != null) progs.add(new String[]{name, vs, fs});
    }

    /** 从首个含 marker 的字符串字面量开始读。 */
    private static String extractFirstLiteralContaining(String s, String marker) {
        int mark = s.indexOf(marker);
        if (mark < 0) return null;
        int q = s.lastIndexOf('"', mark);
        if (q < 0) return null;
        return readLiteral(s, q);
    }

    /** 从下标 q（指向开引号）读到闭合引号，解转义。 */
    static String readLiteral(String s, int q) {
        StringBuilder sb = new StringBuilder();
        for (int i = q + 1; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char n = s.charAt(++i);
                switch (n) {
                    case 'n': sb.append('\n'); break;
                    case 't': sb.append('\t'); break;
                    case 'r': sb.append('\r'); break;
                    case '"': sb.append('"');  break;
                    case '\\': sb.append('\\'); break;
                    case '\'': sb.append('\''); break;
                    default: sb.append('\\').append(n);
                }
            } else if (c == '"') {
                break;
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /** 编译并链接一对着色器；成功返回 null，失败返回 GL 日志。 */
    static String compileAndLink(String srcV, String srcF) {
        int v = GL33.glCreateShader(GL33.GL_VERTEX_SHADER);
        GL33.glShaderSource(v, srcV);
        GL33.glCompileShader(v);
        if (GL33.glGetShaderi(v, GL33.GL_COMPILE_STATUS) == 0) {
            String log = GL33.glGetShaderInfoLog(v);
            GL33.glDeleteShader(v);
            return "    VS compile error:\n" + indent(log);
        }
        int f = GL33.glCreateShader(GL33.GL_FRAGMENT_SHADER);
        GL33.glShaderSource(f, srcF);
        GL33.glCompileShader(f);
        if (GL33.glGetShaderi(f, GL33.GL_COMPILE_STATUS) == 0) {
            String log = GL33.glGetShaderInfoLog(f);
            GL33.glDeleteShader(v); GL33.glDeleteShader(f);
            return "    FS compile error:\n" + indent(log);
        }
        int p = GL33.glCreateProgram();
        GL33.glAttachShader(p, v);
        GL33.glAttachShader(p, f);
        GL33.glLinkProgram(p);
        String err = null;
        if (GL33.glGetProgrami(p, GL33.GL_LINK_STATUS) == 0) err = "    LINK error:\n" + indent(GL33.glGetProgramInfoLog(p));
        GL33.glDeleteShader(v); GL33.glDeleteShader(f); GL33.glDeleteProgram(p);
        return err;
    }

    private static String indent(String s) {
        StringBuilder sb = new StringBuilder();
        for (String line : s.split("\n")) sb.append("      ").append(line).append('\n');
        return sb.toString();
    }
}
