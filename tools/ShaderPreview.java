import core.world.Celestial;
import core.world.DayCycle;
import render.lwjgl.NoiseTex;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL33;
import org.lwjgl.system.MemoryUtil;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.ByteBuffer;

/**
 * 天空着色器离屏预览器（无头出 PNG）。
 *
 * <p><b>为什么存在</b>：画面改动过去只能"本机双击看"，无法在开发环境自检。本工具建立
 * <b>不可见 GL 窗口</b>，用<b>真实的 sky 着色器源码 + 真实的 DayCycle/Celestial 模型</b>
 * 渲染天空并写出 PNG —— 于是「调色/加云/改雾」变成可目视迭代的事。</p>
 *
 * <p>忠实性：着色器从 {@code Game.java} 解析（同 {@link ShaderCheck}），天空色/太阳方向取自
 * {@code DayCycle} / {@code Celestial}，相机基走 {@code DayCycle.SkyBasis} —— 与游戏内同一套代码。</p>
 *
 * <p>用法：{@code java ShaderPreview <outDir> [phaseCsv] [--rain] [--w 640 --h 360] [--pitch 8]}</p>
 * <p>默认 phaseCsv = {@code 0.00,0.25,0.33,0.50,0.75,0.83}（覆盖正午/黄昏/夜/黎明等）。</p>
 */
public final class ShaderPreview {

    public static void main(String[] args) throws Exception {
        File outDir = new File(args.length > 0 ? args[0] : "proof/preview");
        String csv = "0.00,0.25,0.33,0.50,0.75,0.83";
        boolean rain = false;
        int W = 640, H = 360;
        float pitchDeg = 8f;
        int bench = 0;
        String fsOverride = null;   // --fs <file>：用外部 FS 替代 Game.java 里的天空 FS（A/B 对照用）

        for (int i = 1; i < args.length; i++) {
            if (args[i].startsWith("--phase=")) csv = args[i].substring(8);
            else if ("--rain".equals(args[i])) rain = true;
            else if ("--w".equals(args[i]) && i + 1 < args.length) W = Integer.parseInt(args[++i]);
            else if ("--h".equals(args[i]) && i + 1 < args.length) H = Integer.parseInt(args[++i]);
            else if ("--pitch".equals(args[i]) && i + 1 < args.length) pitchDeg = Float.parseFloat(args[++i]);
            else if ("--bench".equals(args[i]) && i + 1 < args.length) bench = Integer.parseInt(args[++i]);
            else if ("--fs".equals(args[i]) && i + 1 < args.length) fsOverride = args[++i];
        }
        outDir.mkdirs();

        // 从 Game.java 解析出真实的 sky 着色器
        String src = new String(java.nio.file.Files.readAllBytes(new File("src/render/lwjgl/Game.java").toPath()),
                java.nio.charset.StandardCharsets.UTF_8);
        String[] sky = ShaderCheck.extractMethodShaders(src, "private void initSkyShader()");
        if (sky == null) { System.out.println("SHADERPREVIEW FAIL: 解析不到 sky 着色器"); System.exit(1); }
        if (fsOverride != null) {
            // trim() 而非 strip()：本项目工具链跑 JDK 8（build_runner 自动选 corretto-1.8），strip 是 Java 11+ API
            sky[1] = new String(java.nio.file.Files.readAllBytes(new File(fsOverride).toPath()),
                    java.nio.charset.StandardCharsets.UTF_8).trim();
            System.out.println("FS override = " + fsOverride);
        }

        long win;
        try {
            if (!GLFW.glfwInit()) { System.out.println("SHADERPREVIEW SKIP (glfwInit failed)"); System.exit(0); }
            GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE);
            GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR, 3);
            GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR, 3);
            GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_PROFILE, GLFW.GLFW_OPENGL_CORE_PROFILE);
            win = GLFW.glfwCreateWindow(W, H, "shaderpreview", 0L, 0L);
            if (win == 0L) { System.out.println("SHADERPREVIEW SKIP (no GL)"); System.exit(0); }
            GLFW.glfwMakeContextCurrent(win);
            GL.createCapabilities();
        } catch (Throwable t) {
            System.out.println("SHADERPREVIEW SKIP (GL unavailable: " + t + ")"); System.exit(0); return;
        }

        int prog = GL33.glCreateProgram();
        int vs = GL33.glCreateShader(GL33.GL_VERTEX_SHADER);
        GL33.glShaderSource(vs, sky[0]); GL33.glCompileShader(vs);
        if (GL33.glGetShaderi(vs, GL33.GL_COMPILE_STATUS) == 0) {
            System.out.println("VS: " + GL33.glGetShaderInfoLog(vs)); System.exit(1);
        }
        int fs = GL33.glCreateShader(GL33.GL_FRAGMENT_SHADER);
        GL33.glShaderSource(fs, sky[1]); GL33.glCompileShader(fs);
        if (GL33.glGetShaderi(fs, GL33.GL_COMPILE_STATUS) == 0) {
            System.out.println("FS: " + GL33.glGetShaderInfoLog(fs)); System.exit(1);
        }
        GL33.glAttachShader(prog, vs); GL33.glAttachShader(prog, fs); GL33.glLinkProgram(prog);
        if (GL33.glGetProgrami(prog, GL33.GL_LINK_STATUS) == 0) {
            System.out.println("LINK: " + GL33.glGetProgramInfoLog(prog)); System.exit(1);
        }

        int vao = GL33.glGenVertexArrays();
        int vbo = GL33.glGenBuffers();
        GL33.glBindVertexArray(vao);
        GL33.glBindBuffer(GL33.GL_ARRAY_BUFFER, vbo);
        float[] tri = {-1f, -1f, 3f, -1f, -1f, 3f};
        ByteBuffer bb = MemoryUtil.memAlloc(tri.length * 4);
        for (float f : tri) bb.putFloat(f);
        bb.flip();
        GL33.glBufferData(GL33.GL_ARRAY_BUFFER, bb, GL33.GL_STATIC_DRAW);
        GL33.glVertexAttribPointer(0, 2, GL33.GL_FLOAT, false, 8, 0L);
        GL33.glEnableVertexAttribArray(0);
        MemoryUtil.memFree(bb);

        int uTop = GL33.glGetUniformLocation(prog, "uSkyTop");
        int uHor = GL33.glGetUniformLocation(prog, "uSkyHorizon");
        int uFwd = GL33.glGetUniformLocation(prog, "uSkyFwd");
        int uRight = GL33.glGetUniformLocation(prog, "uSkyRight");
        int uUp = GL33.glGetUniformLocation(prog, "uSkyUp");
        int uTan = GL33.glGetUniformLocation(prog, "uSkyTan");
        int uSunDir = GL33.glGetUniformLocation(prog, "uSunDir");
        int uSunCol = GL33.glGetUniformLocation(prog, "uSunColor");
        int uStar = GL33.glGetUniformLocation(prog, "uStar");
        int uRain = GL33.glGetUniformLocation(prog, "uRain");
        int uRes = GL33.glGetUniformLocation(prog, "uRes");
        int uTime = GL33.glGetUniformLocation(prog, "uTime");
        int uNoise = GL33.glGetUniformLocation(prog, "uNoise");
        int noiseTex = NoiseTex.bake();          // 与游戏同一条噪声纹理（云层采样用）
        GL33.glUseProgram(prog);                 // 设 uniform 前必须先 useProgram，否则设到 0 号程序上
        if (uNoise != -1) {
            GL33.glUniform1i(uNoise, 2);
            GL33.glActiveTexture(33986);
            GL33.glBindTexture(3553, noiseTex);
            GL33.glActiveTexture(33984);
        }

        System.out.println("GL_RENDERER = " + GL33.glGetString(GL33.GL_RENDERER)
                + "  size=" + W + "x" + H + "  rain=" + rain);

        int dayLen = DayCycle.DEFAULT_DAY_LEN;
        for (String tok : csv.split(",")) {
            float phase = Float.parseFloat(tok.trim());
            long tick = Math.round(((phase % 1f) + 1f) % 1f * dayLen);

            float[] top = new float[3], hor = new float[3], tint = new float[3];
            DayCycle.skyTop(phase, top);
            DayCycle.skyHorizon(phase, hor);
            DayCycle.lightTint(phase, tint);
            float star = DayCycle.starAlpha(phase);

            float[] sun = new float[3], moon = new float[3];
            Celestial.sunDir(tick, sun);
            Celestial.moonDir(tick, moon);

            // 相机：对准太阳（夜里对准月亮），微微上仰，FOV 70（与游戏默认一致）
            float[] aim = (sun[1] > -0.05f) ? sun : moon;
            float az = (float) Math.atan2(aim[0], aim[2]);
            double p = Math.toRadians(pitchDeg), a = az;
            float[] fwd = {(float) (Math.sin(a) * Math.cos(p)), (float) Math.sin(p), (float) (Math.cos(a) * Math.cos(p))};
            // right = normalize(cross(fwd, worldUp)) ; up = cross(right, fwd)
            float[] ru = {0f, 1f, 0f};
            float[] right = cross(fwd, ru);
            float[] up = cross(right, fwd);
            DayCycle.SkyBasis b = new DayCycle.SkyBasis(fwd, right, up, 70f, (float) W / (float) H);

            GL33.glUseProgram(prog);
            GL33.glUniform3f(uTop, top[0], top[1], top[2]);
            GL33.glUniform3f(uHor, hor[0], hor[1], hor[2]);
            GL33.glUniform3f(uFwd, b.fx, b.fy, b.fz);
            GL33.glUniform3f(uRight, b.rx, b.ry, b.rz);
            GL33.glUniform3f(uUp, b.ux, b.uy, b.uz);
            GL33.glUniform2f(uTan, b.tanX, b.tanY);
            GL33.glUniform3f(uSunDir, sun[0], sun[1], sun[2]);
            GL33.glUniform3f(uSunCol, tint[0], tint[1], tint[2]);
            GL33.glUniform1f(uStar, star);
            GL33.glUniform1f(uRain, rain ? 1f : 0f);
            GL33.glUniform2f(uRes, (float) W, (float) H);
            GL33.glUniform1f(uTime, 42f);

            GL33.glViewport(0, 0, W, H);
            GL33.glDisable(GL33.GL_DEPTH_TEST);
            GL33.glDisable(GL33.GL_BLEND);
            GL33.glBindVertexArray(vao);
            GL33.glDrawArrays(GL33.GL_TRIANGLES, 0, 3);
            GL33.glFinish();

            ByteBuffer px = MemoryUtil.memAlloc(W * H * 4);
            GL33.glReadPixels(0, 0, W, H, GL33.GL_RGBA, GL33.GL_UNSIGNED_BYTE, px);
            BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
            for (int y = 0; y < H; y++) {
                int srcY = H - 1 - y;
                for (int x = 0; x < W; x++) {
                    int o = (srcY * W + x) * 4;
                    int r = px.get(o) & 0xFF, g = px.get(o + 1) & 0xFF, bl = px.get(o + 2) & 0xFF;
                    img.setRGB(x, y, (r << 16) | (g << 8) | bl);
                }
            }
            MemoryUtil.memFree(px);
            String tag = String.format("sky_p%03d%s", Math.round(phase * 100), rain ? "_rain" : "");
            File out = new File(outDir, tag + ".png");
            ImageIO.write(img, "png", out);
            System.out.println("  wrote " + out.getName() + "  phase=" + phase + " " + DayCycle.label(phase)
                    + " star=" + String.format("%.2f", star) + " sunY=" + String.format("%.2f", sun[1]));
        }

        // ---- 耗时基准（防"美化把帧率吃掉"）：正午最重（云最密），全屏三角形逐帧重画 ----
        if (bench > 0) {
            float phase = 0.5f;
            long tick = Math.round(phase * dayLen);
            float[] top = new float[3], hor = new float[3], tint = new float[3];
            DayCycle.skyTop(phase, top);
            DayCycle.skyHorizon(phase, hor);
            DayCycle.lightTint(phase, tint);
            float[] sun = new float[3];
            Celestial.sunDir(tick, sun);
            float az = (float) Math.atan2(sun[0], sun[2]);
            double p = Math.toRadians(pitchDeg);
            float[] fwd = {(float) (Math.sin(az) * Math.cos(p)), (float) Math.sin(p), (float) (Math.cos(az) * Math.cos(p))};
            float[] right = cross(fwd, new float[]{0f, 1f, 0f});
            float[] up = cross(right, fwd);
            DayCycle.SkyBasis b = new DayCycle.SkyBasis(fwd, right, up, 70f, (float) W / (float) H);
            GL33.glUseProgram(prog);
            GL33.glUniform3f(uTop, top[0], top[1], top[2]);
            GL33.glUniform3f(uHor, hor[0], hor[1], hor[2]);
            GL33.glUniform3f(uFwd, b.fx, b.fy, b.fz);
            GL33.glUniform3f(uRight, b.rx, b.ry, b.rz);
            GL33.glUniform3f(uUp, b.ux, b.uy, b.uz);
            GL33.glUniform2f(uTan, b.tanX, b.tanY);
            GL33.glUniform3f(uSunDir, sun[0], sun[1], sun[2]);
            GL33.glUniform3f(uSunCol, tint[0], tint[1], tint[2]);
            GL33.glUniform1f(uStar, 0f);
            GL33.glUniform1f(uRain, 0f);
            GL33.glUniform2f(uRes, (float) W, (float) H);
            GL33.glViewport(0, 0, W, H);
            GL33.glDisable(GL33.GL_DEPTH_TEST);
            GL33.glDisable(GL33.GL_BLEND);
            GL33.glBindVertexArray(vao);
            int warm = Math.max(20, bench / 5);
            for (int i = 0; i < warm; i++) { GL33.glUniform1f(uTime, 1f + i * 0.01f); GL33.glDrawArrays(GL33.GL_TRIANGLES, 0, 3); }
            GL33.glFinish();
            long t0 = System.nanoTime();
            for (int i = 0; i < bench; i++) { GL33.glUniform1f(uTime, 1f + i * 0.01f); GL33.glDrawArrays(GL33.GL_TRIANGLES, 0, 3); }
            GL33.glFinish();
            double ms = (System.nanoTime() - t0) / 1e6 / bench;
            System.out.println(String.format("BENCH sky %dx%d  %.3f ms/frame  (=%.0f fps 上限)", W, H, ms, 1000.0 / ms));
        }

        GLFW.glfwDestroyWindow(win);
        GLFW.glfwTerminate();
        System.out.println("SHADERPREVIEW PASS -> " + outDir.getPath());
    }

    private static float[] cross(float[] a, float[] b) {
        return new float[]{a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]};
    }
}
