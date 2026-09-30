package core.sim;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * 门禁 EDGESHADER（四路调研步骤 4 · 路线 A）：跨材质交界<b>叠加层</b>着色器契约的无头守护。
 *
 * <h2>为什么需要它</h2>
 * {@code EdgeAtlas}（纯逻辑）有 {@link EdgeTest} 守护，但"过渡图案到底有没有被 shader 真正
 * 叠加上去"这一步<b>没有任何门禁</b> —— {@code tools/ShaderCheck} 只证明 GLSL 能编译，不证明
 * 语义正确。曾发生过"逻辑正确、shader 里漏了采样"的静默失败风险（渲染层改动本来就难验）。
 *
 * <p>本门禁用<b>纯文本断言</b>锁死这条契约：world VS 必须把 {@code aEdge} 传给 {@code vEdge}、
 * world FS 必须用 {@code uTex} 对 {@code vEdge.xy} 采样并以 {@code vEdge.z} 为权叠加。
 * 改动被破坏时门禁立刻 FAIL，而不是等到"截图看起来没变"再靠人眼发现。</p>
 *
 * <h2>为什么不依赖 render 层</h2>
 * 直接读 {@code src/render/lwjgl/Game.java} <b>源文件文本</b>（不是类），于是本门禁可以放在
 * {@code core.sim}（CORE 编译期），不需要 {@code render.lwjgl} 的 GL 依赖，可在无 GPU 环境跑。
 * 找不到源文件时优雅 SKIP（不算失败）——与 {@code ShaderCheck} 的 no-GL 策略一致。
 *
 * <p>纯 Java（不触碰 GL、不读世界状态、不进指纹）。</p>
 */
public class EdgeShaderTest {

    private static boolean ok = true;

    private static void check(String name, boolean cond) {
        System.out.println("  [" + (cond ? "ok  " : "FAIL") + "] " + name);
        if (!cond) ok = false;
    }

    /** Game.java 里 world VS 的方法签名（与 ShaderCheck.METHODS 保持同一锚点）。 */
    private static final String WORLD_SIG = "private void initWorldShader()";

    public static void main(String[] args) throws Exception {
        File f = new File("src/render/lwjgl/Game.java");
        if (!f.isFile()) {
            System.out.println("EDGESHADER SKIP (Game.java not found; run from project root)");
            return;
        }
        String src = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);

        // 定位 world 方法体（与 ShaderCheck.extractMethodShaders 同法）
        int at = src.indexOf(WORLD_SIG);
        check("world 方法存在且唯一", at >= 0 && src.indexOf(WORLD_SIG, at + 1) < 0);
        if (at < 0) { System.out.println("EDGESHADER RESULT: FAIL"); System.exit(1); }
        int end = src.length();
        for (String pat : new String[]{"\n    private ", "\n    public ", "\n    static "}) {
            int k = src.indexOf(pat, at + WORLD_SIG.length());
            if (k > 0 && k < end) end = k;
        }
        String body = src.substring(at, end);
        check("world 方法体恰含 2 个 #version（VS+FS）", count(body, "#version") == 2);

        // 抽 VS / FS 字面量
        int v1 = body.indexOf("#version");
        String vs = literalAround(body, v1);
        int v2 = body.indexOf("#version", v1 + 8);
        String fs = literalAround(body, v2);
        check("VS 字面量抽取成功", vs != null && vs.length() > 0);
        check("FS 字面量抽取成功", fs != null && fs.length() > 0);
        if (vs == null || fs == null) { System.out.println("EDGESHADER RESULT: FAIL"); System.exit(1); }

        System.out.println("=== EDGESHADER: world VS 属性通道 ===");
        check("VS 声明 aEdge 于 location=6", vs.contains("layout(location=6) in vec3 aEdge;"));
        check("VS 有 out vEdge", vs.contains("out vec3 vEdge;"));
        check("VS 把 aEdge 写入 vEdge", vs.contains("vEdge=aEdge;"));

        System.out.println("=== EDGESHADER: world FS 叠加逻辑 ===");
        check("FS 有 in vEdge", fs.contains("in vec3 vEdge;"));
        check("FS 用 uTex 采样 vEdge.xy", containsCompact(fs, "texture(uTex,vEdge.xy)"));
        check("FS 以 vEdge.z 为叠加权重", fs.contains("vEdge.z>0.0"));
        check("FS 叠加是乘法（col*=）而非替换", containsCompact(fs, "col*=mix(vec3(1.0),vec3(ep*2.0)"));
        check("FS 归一因子为 2.0（图案均值 0.5 → 均值保持）", fs.contains("ep*2.0"));

        System.out.println("=== EDGESHADER: 单行字面量契约（ShaderCheck 抽取前提）===");
        check("FS 是单个字面量（体内无裸双引号）", !fs.contains("\""));
        check("VS 是单个字面量（体内无裸双引号）", !vs.contains("\""));

        EdgeAtlasDependency();
        System.out.println("EDGESHADER RESULT: " + (ok ? "PASS" : "FAIL"));
        if (!ok) System.exit(1);
    }

    /** 交叉引用：core 层层面的 scale() 与 EDGE_SAT 语义必须与 shader 契约一致。 */
    private static void EdgeAtlasDependency() {
        System.out.println("=== EDGESHADER: 与 EdgeAtlas 的契约一致 ===");
        check("scale(0) 精确 1.0f（关闭即逐字节等价的保证）", core.world.EdgeAtlas.scale(0f) == 1.0f);
        check("EDGE_SAT 默认 0（顶点色不降饱和；饱和度交 shader 图案）", core.world.EdgeAtlas.EDGE_SAT == 0f);
    }

    // ---------- 文本工具（与 ShaderCheck 的抽取语义保持一致）----------

    /** 找 mark 所在字符串字面量的内容（mark 前最近的开引号 → 下一个闭引号之前）。 */
    private static String literalAround(String s, int mark) {
        if (mark < 0) return null;
        int q = s.lastIndexOf('"', mark);
        if (q < 0) return null;
        int e = s.indexOf('"', mark);
        if (e < 0) return null;
        return s.substring(q + 1, e);
    }

    /** 忽略空白后的一次"紧凑包含"判定（shader 里空格可能被作者调过）。 */
    private static boolean containsCompact(String hay, String needle) {
        return compact(hay).contains(compact(needle));
    }

    private static String compact(String s) {
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c != ' ' && c != '\n' && c != '\t' && c != '\r') b.append(c);
        }
        return b.toString();
    }

    private static int count(String s, String sub) {
        int n = 0, i = 0;
        while (true) {
            int j = s.indexOf(sub, i);
            if (j < 0) break;
            n++; i = j + sub.length();
        }
        return n;
    }
}
