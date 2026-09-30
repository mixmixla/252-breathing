import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL33;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * 自研艺术 UI 层的<b>等价性仪器</b>（离屏 GL，真驱动）。
 *
 * <p><b>要证的事</b>：把 {@code initHudShader()} 的 FS 从「纯色」升级为「纯色 + 艺术材质层」后，
 * 在<b>默认强度 {@code uArtAmt=0}</b> 下，新 FS 的输出必须与旧 FS <b>逐字节相同</b>——
 * 即"默认关闭 = 零影响"不是靠肉眼，而是靠 GPU 数值比对。</p>
 *
 * <p><b>做法</b>：同一份 VS，两个 FS（旧纯色 / 新艺术层，后者 uArtAmt=0），渲染到两个 FBO，
 * {@code glReadPixels} 读回逐像素比。同时跑一轮 {@code uArtAmt=1} 作为<b>阳性对照</b>——
 * 若非 0 差异，说明对照本身无效（实验没真起作用），等价性结论也就不成立。</p>
 *
 * <p>无 GL 时打印 SKIP 并 exit 0（不算失败）。</p>
 */
public final class UiArtEquiv {

    private static final String VS =
            "#version 330 core\nlayout(location=0) in vec3 aPos;layout(location=1) in vec4 aCol;uniform mat4 uVP;out vec4 vCol;out vec2 vSuv;void main(){vCol=aCol;gl_Position=uVP*vec4(aPos,1);vSuv=gl_Position.xy/max(abs(gl_Position.w),1e-6)*0.5+0.5;}";

    /**
     * 参照 FS = <b>改动前的 HUD FS 原文</b>：{@code fc=vec4(vCol.rgb, vCol.a);}（直通 alpha，无纹理）。
     *
     * <p>这是最强的等价命题：{@code uArtAmt=0} <b>且</b> 顶点 UV 落在严格纯白 tile（{@link
     * render.lwjgl.TextureAtlas#UI_WHITE_TILE}，exact 255/255/255/255）上 ⟹ 新 FS 的输出
     * 必须与这条旧语句<b>逐字节相同</b>。</p>
     *
     * <p>⚠️ 2026-09-21 血泪：初版参照 FS 写成了 {@code vCol.rgb*t.rgb*vCol.a*t.a}（预乘形式），
     * 与当时的新 FS 犯了同一个错 —— 于是"等价性证明"通过，而真实帧却全屏压暗 1.5%。
     * <b>参照系一旦与被测物同源犯错，仪器就失去意义。</b>故参照 FS 必须直接抄旧语句原文。</p>
     *
     * <p>⚠️ 同理：RGB <b>不能</b>乘 {@code vCol.a}。旧式是 straight alpha + blend
     * {@code SRC_ALPHA/ONE_MINUS_SRC_ALPHA}；在 FS 里再预乘会让所有 alpha&lt;1 的 HUD 元素
     * （minimap 面板 a=0.60 等）按自身 alpha 再压一次。</p>
     */
    private static final String FS_OLD =
            "#version 330 core\nin vec4 vCol;out vec4 fc;void main(){fc=vec4(vCol.rgb, vCol.a);}";

    /** 新 FS：从 Game.java 的 initHudShader 抽取（单一真相，不维护第二份副本）。 */
    private static String fsNew;

    public static void main(String[] args) throws Exception {
        String gamePath = "src/render/lwjgl/Game.java";
        if (!Files.isRegularFile(Paths.get(gamePath))) {
            System.out.println("UIARTEQUIV SKIP (Game.java not found; run from project root)");
            System.exit(0);
        }
        String src = new String(Files.readAllBytes(Paths.get(gamePath)), StandardCharsets.UTF_8);
        fsNew = extractHudFs(src);
        if (fsNew == null) {
            System.out.println("UIARTEQUIV FAIL: 无法从 initHudShader 抽取 FS");
            System.exit(1);
        }
        // 抽取器把 \n 解成真换行；GLSL 需要它。这里检查抽取完整（含函数体）。
        boolean complete = fsNew.contains("vnoise") && fsNew.contains("fc=c") && fsNew.contains("uArtAmt");
        System.out.println("UIARTEQUIV: hud FS 抽取 " + fsNew.length() + "B  complete=" + complete);
        if (!complete) {
            System.out.println("UIARTEQUIV FAIL: FS 抽取不完整（+ 拼接截断？）");
            System.exit(1);
        }

        long win;
        try {
            if (!GLFW.glfwInit()) { System.out.println("UIARTEQUIV SKIP (glfwInit failed)"); System.exit(0); }
            GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE);
            GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR, 3);
            GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR, 3);
            GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_PROFILE, GLFW.GLFW_OPENGL_CORE_PROFILE);
            win = GLFW.glfwCreateWindow(64, 64, "uiequiv", 0L, 0L);
            if (win == 0L) { System.out.println("UIARTEQUIV SKIP (no GL context)"); System.exit(0); }
            GLFW.glfwMakeContextCurrent(win);
            GL.createCapabilities();
            System.out.println("GL_RENDERER = " + GL33.glGetString(GL33.GL_RENDERER));
        } catch (Throwable t) {
            System.out.println("UIARTEQUIV SKIP (GL unavailable: " + t.getClass().getSimpleName() + ")");
            System.exit(0);
            return;
        }

        final int W = 256, H = 256;
        // 三种 alpha 场景：① a=1（不透明，最宽松）② a=0.60（minimap 面板）③ a=0.25（焦点遮罩）。
        // ⚠️ a<1 的场景是**必须**的：直通 alpha vs 预乘的差异<b>只在 alpha<1 时显现</b>。
        //    2026-09-21 全屏压暗 bug 正是漏了这一场景才让仪器假绿。
        float[] alphas = {1.0f, 0.60f, 0.25f};
        int allDiff = 0, allMax = 0;
        for (float al : alphas) {
            ByteBuffer a = render(W, H, FS_OLD, 0f, al);
            ByteBuffer b = render(W, H, fsNew, 0f, al);
            int diff = 0, maxd = 0;
            for (int i = 0; i < W * H * 4; i++) {
                int d = Math.abs((a.get(i) & 255) - (b.get(i) & 255));
                if (d > 0) diff++;
                if (d > maxd) maxd = d;
            }
            System.out.println(String.format("UIARTEQUIV [default off alpha=%.2f] diff_bytes=%d/%d max_delta=%d",
                    al, diff, W * H * 4, maxd));
            allDiff += diff;
            if (maxd > allMax) allMax = maxd;
        }

        // 阳性对照：新 FS 在 uArtAmt=1 时必须与 uArtAmt=0 有显著差异（否则"艺术层"根本没生效）。
        ByteBuffer a0 = render(W, H, fsNew, 0f, 0.60f);
        ByteBuffer c = render(W, H, fsNew, 1f, 0.60f);
        int diffOn = 0, maxOn = 0;
        double sumOld = 0, sumOn = 0;
        for (int i = 0; i < W * H * 4; i++) {
            if (i % 4 == 3) continue;
            int va = a0.get(i) & 255, vc = c.get(i) & 255;
            int d = Math.abs(va - vc);
            if (d > 0) diffOn++;
            if (d > maxOn) maxOn = d;
            sumOld += va; sumOn += vc;
        }
        double n = W * H * 3.0;
        System.out.println("UIARTEQUIV [positive ctrl uArtAmt=1] diff_bytes=" + diffOn + "/" + (W * H * 3)
                + " max_delta=" + maxOn
                + String.format(" meanOld=%.3f meanOn=%.3f ratio=%.5f", sumOld / n, sumOn / n, sumOn / Math.max(1e-9, sumOld)));

        GLFW.glfwDestroyWindow(win);
        GLFW.glfwTerminate();

        boolean ok = (allDiff == 0) && (allMax == 0) && (diffOn > 0);
        System.out.println("UIARTEQUIV RESULT: " + (ok ? "PASS" : "FAIL"));
        System.exit(ok ? 0 : 1);
    }

    /** 渲染一帧到 FBO 并读回 RGBA（{@code alpha} 控制顶点色 alpha，用于覆盖 a&lt;1 场景）。 */
    private static ByteBuffer render(int W, int H, String fs, float artAmt) {
        return render(W, H, fs, artAmt, 1.0f);
    }

    private static ByteBuffer render(int W, int H, String fs, float artAmt, float alpha) {
        int prog = makeProgram(VS, fs);
        int fbo = GL33.glGenFramebuffers();
        int tex = GL33.glGenTextures();
        GL33.glBindTexture(GL33.GL_TEXTURE_2D, tex);
        GL33.glTexImage2D(GL33.GL_TEXTURE_2D, 0, GL33.GL_RGBA, W, H, 0, GL33.GL_RGBA, GL33.GL_UNSIGNED_BYTE, (ByteBuffer) null);
        GL33.glTexParameteri(GL33.GL_TEXTURE_2D, GL33.GL_TEXTURE_MIN_FILTER, GL33.GL_NEAREST);
        GL33.glTexParameteri(GL33.GL_TEXTURE_2D, GL33.GL_TEXTURE_MAG_FILTER, GL33.GL_NEAREST);
        GL33.glBindFramebuffer(GL33.GL_FRAMEBUFFER, fbo);
        GL33.glFramebufferTexture2D(GL33.GL_FRAMEBUFFER, GL33.GL_COLOR_ATTACHMENT0, GL33.GL_TEXTURE_2D, tex, 0);
        GL33.glViewport(0, 0, W, H);

        int vao = GL33.glGenVertexArrays();
        GL33.glBindVertexArray(vao);
        int vbo = GL33.glGenBuffers();
        GL33.glBindBuffer(GL33.GL_ARRAY_BUFFER, vbo);
        // 全屏两三角：pos3 + col4 + uv2（与升级后 HUD 布局一致）。
        // UV 取**严格纯白 tile 的中心**（identity：纹理采样返回白 → col*white == col）。
        // 顶点 alpha 可调：a<1 是暴露"直通 alpha vs 预乘"差异的关键场景。
        float[] wuv = whiteTileUV();
        float u = wuv[0], v = wuv[1];
        float al = alpha;
        float[] v6 = {
                -1f, -1f, 0f, 0.62f, 0.48f, 0.31f, al, u, v,
                 1f, -1f, 0f, 0.62f, 0.48f, 0.31f, al, u, v,
                 1f,  1f, 0f, 0.62f, 0.48f, 0.31f, al, u, v,
                -1f, -1f, 0f, 0.62f, 0.48f, 0.31f, al, u, v,
                 1f,  1f, 0f, 0.62f, 0.48f, 0.31f, al, u, v,
                -1f,  1f, 0f, 0.62f, 0.48f, 0.31f, al, u, v,
        };
        java.nio.FloatBuffer fb = org.lwjgl.system.MemoryUtil.memAllocFloat(v6.length);
        fb.put(v6).flip();
        GL33.glBufferData(GL33.GL_ARRAY_BUFFER, fb, GL33.GL_STATIC_DRAW);
        org.lwjgl.system.MemoryUtil.memFree(fb);
        GL33.glVertexAttribPointer(0, 3, GL33.GL_FLOAT, false, 36, 0L);
        GL33.glEnableVertexAttribArray(0);
        GL33.glVertexAttribPointer(1, 4, GL33.GL_FLOAT, false, 36, 12L);
        GL33.glEnableVertexAttribArray(1);
        GL33.glVertexAttribPointer(2, 2, GL33.GL_FLOAT, false, 36, 28L);
        GL33.glEnableVertexAttribArray(2);

        // 上传真正的图集（含 WHITE_TILE），让"纹理恒等"也进入数值证明范围。
        int atlasTex = uploadAtlas();
        GL33.glActiveTexture(GL33.GL_TEXTURE0);
        GL33.glBindTexture(GL33.GL_TEXTURE_2D, atlasTex);

        GL33.glUseProgram(prog);
        // ⚠️ uVP 必须显式设为单位矩阵：默认 uniform 是**全 0 矩阵** → gl_Position 恒 (0,0,0,0)
        //    → w=0 → 整块被裁剪 → 读回全 0（本次实测踩到：FBO COMPLETE、glErr=0、像素全 0）。
        float[] ident = {1,0,0,0, 0,1,0,0, 0,0,1,0, 0,0,0,1};
        java.nio.FloatBuffer mb = org.lwjgl.system.MemoryUtil.memAllocFloat(16);
        mb.put(ident).flip();
        int locVP = GL33.glGetUniformLocation(prog, "uVP");
        if (locVP != -1) GL33.glUniformMatrix4fv(locVP, false, mb);
        org.lwjgl.system.MemoryUtil.memFree(mb);
        int locAmt = GL33.glGetUniformLocation(prog, "uArtAmt");
        if (locAmt != -1) GL33.glUniform1f(locAmt, artAmt);
        int locRes = GL33.glGetUniformLocation(prog, "uArtRes");
        if (locRes != -1) GL33.glUniform2f(locRes, (float) W, (float) H);
        int locTex = GL33.glGetUniformLocation(prog, "uTex");
        if (locTex != -1) GL33.glUniform1i(locTex, 0);
        GL33.glDisable(GL33.GL_BLEND);
        GL33.glClearColor(0f, 0f, 0f, 0f);
        GL33.glClear(GL33.GL_COLOR_BUFFER_BIT);
        GL33.glDrawArrays(GL33.GL_TRIANGLES, 0, 6);

        ByteBuffer px = org.lwjgl.system.MemoryUtil.memAlloc(W * H * 4);
        GL33.glReadPixels(0, 0, W, H, GL33.GL_RGBA, GL33.GL_UNSIGNED_BYTE, px);

        GL33.glBindFramebuffer(GL33.GL_FRAMEBUFFER, 0);
        GL33.glDeleteFramebuffers(fbo);
        GL33.glDeleteTextures(tex);
        GL33.glDeleteVertexArrays(vao);
        GL33.glDeleteBuffers(vbo);
        GL33.glDeleteProgram(prog);
        GL33.glDeleteTextures(atlasTex);
        return px;
    }

    /**
     * HUD 恒等纹理的 UV：严格纯白 tile（{@link render.lwjgl.TextureAtlas#UI_WHITE_TILE}）中心。
     * 与 {@code Game.HUD_WHITE_U/V} 同一算法，单一真相来自 {@link render.lwjgl.TextureAtlas}。
     */
    private static float[] whiteTileUV() {
        return new float[]{
                render.lwjgl.TextureAtlas.uOf(render.lwjgl.TextureAtlas.UI_WHITE_TILE, 0.5f),
                render.lwjgl.TextureAtlas.vOf(render.lwjgl.TextureAtlas.UI_WHITE_TILE, 0.5f)};
    }

    /** 烘焙并上传真正的图集到 GL（含 WHITE_TILE），返回纹理 id。 */
    private static int uploadAtlas() {
        java.nio.ByteBuffer px = render.lwjgl.TextureAtlas.bakeAlbedoOffscreen();   // 堆缓冲（纯 CPU 烘焙）
        int AP = render.lwjgl.TextureAtlas.ATLAS_PX;
        java.nio.ByteBuffer up = org.lwjgl.system.MemoryUtil.memAlloc(AP * AP * 4);
        up.clear(); up.put(px); up.flip();
        int tex = GL33.glGenTextures();
        GL33.glBindTexture(GL33.GL_TEXTURE_2D, tex);
        GL33.glPixelStorei(GL33.GL_UNPACK_ALIGNMENT, 1);
        GL33.glTexParameteri(GL33.GL_TEXTURE_2D, GL33.GL_TEXTURE_MIN_FILTER, GL33.GL_NEAREST);
        GL33.glTexParameteri(GL33.GL_TEXTURE_2D, GL33.GL_TEXTURE_MAG_FILTER, GL33.GL_NEAREST);
        GL33.glTexParameteri(GL33.GL_TEXTURE_2D, GL33.GL_TEXTURE_WRAP_S, GL33.GL_CLAMP_TO_EDGE);
        GL33.glTexParameteri(GL33.GL_TEXTURE_2D, GL33.GL_TEXTURE_WRAP_T, GL33.GL_CLAMP_TO_EDGE);
        GL33.glTexImage2D(GL33.GL_TEXTURE_2D, 0, GL33.GL_RGBA, AP, AP, 0, GL33.GL_RGBA, GL33.GL_UNSIGNED_BYTE, up);
        org.lwjgl.system.MemoryUtil.memFree(up);
        return tex;
    }

    private static int makeProgram(String vs, String fs) {        int v = GL33.glCreateShader(GL33.GL_VERTEX_SHADER);
        GL33.glShaderSource(v, vs); GL33.glCompileShader(v);
        if (GL33.glGetShaderi(v, GL33.GL_COMPILE_STATUS) == 0)
            throw new IllegalStateException("VS: " + GL33.glGetShaderInfoLog(v));
        int f = GL33.glCreateShader(GL33.GL_FRAGMENT_SHADER);
        GL33.glShaderSource(f, fs); GL33.glCompileShader(f);
        if (GL33.glGetShaderi(f, GL33.GL_COMPILE_STATUS) == 0)
            throw new IllegalStateException("FS: " + GL33.glGetShaderInfoLog(f));
        int p = GL33.glCreateProgram();
        GL33.glAttachShader(p, v); GL33.glAttachShader(p, f); GL33.glLinkProgram(p);
        if (GL33.glGetProgrami(p, GL33.GL_LINK_STATUS) == 0)
            throw new IllegalStateException("LINK: " + GL33.glGetProgramInfoLog(p));
        return p;
    }

    /** 与 ShaderCheck.extractMethodShaders 同纪律：按方法签名锚定，取体内第二个含 #version 的字面量。 */
    static String extractHudFs(String src) {
        int at = src.indexOf("private void initHudShader()");
        if (at < 0) return null;
        int end = src.length();
        int next = src.indexOf("\n    private ", at + 30);
        if (next < 0) next = src.indexOf("\n    public ", at + 30);
        if (next < 0) next = src.indexOf("\n    static ", at + 30);
        if (next > 0) end = next;
        String body = src.substring(at, end);
        int first = body.indexOf("#version");
        if (first < 0) return null;
        int mark = body.indexOf("#version", first + 8);
        if (mark < 0) return null;
        int q = body.lastIndexOf('"', mark);
        if (q < 0) return null;
        return readLiteral(body, q);
    }

    static String readLiteral(String s, int q) {
        StringBuilder sb = new StringBuilder();
        for (int i = q + 1; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == '\\' && i + 1 < s.length()) {
                char n = s.charAt(++i);
                switch (n) {
                    case 'n': sb.append('\n'); break;
                    case 't': sb.append('\t'); break;
                    case 'r': sb.append('\r'); break;
                    case '"': sb.append('"'); break;
                    case '\\': sb.append('\\'); break;
                    case '\'': sb.append('\''); break;
                    default: sb.append('\\').append(n);
                }
            } else if (ch == '"') {
                break;
            } else {
                sb.append(ch);
            }
        }
        return sb.toString();
    }
}
