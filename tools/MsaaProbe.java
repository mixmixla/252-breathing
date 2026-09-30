import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL33;

/**
 * 隔离探针：这台机器能不能拿到 MSAA 默认帧缓冲？以及**是游戏里哪一条 hint 把它挤成 0 的**？
 *
 * <p>背景（§18）：游戏请求 {@code GLFW_SAMPLES=4}，但 {@code glGetInteger(GL_SAMPLES)} 读回 0
 * ⇒ 那句"MSAA 4x 抗锯齿"在实机上其实**根本没生效**，而"A2C 修镂空边缘"这类只在 MSAA 下存在的
 * 特性也因此**永远测不出效果**（灯下黑）。
 *
 * <p>⚠️ 一次进程只测**一个**用例：初版把多个用例串在一个 JVM 里，结果第二个之后全返回 0
 * —— 说明 **GLFW/驱动状态会跨窗口泄漏**，"同一进程里先后建窗"本身就会污染结论。
 * 故本版改为 argv 驱动，每条用例一个全新 JVM。
 *
 * <p>跑法（每个用例单独一条命令）：
 * {@code java -cp "out;libs/*" MsaaProbe <min|major|full|full-msaa>}
 * 其中 min=SAMPLES=4 单独；major=+VERSION 3.3；full=游戏全部 hint。
 */
public final class MsaaProbe {
    public static void main(String[] args) {
        String mode = args.length > 0 ? args[0] : "min";
        if (!GLFW.glfwInit()) { java.lang.System.out.println("MSAA PROBE: glfwInit FAILED"); return; }
        if (mode.equals("seq")) {
            // 假设检验：**一个进程里"第一个窗口"拿不到 MSAA，后面的才能拿到**？
            for (int i = 0; i < 4; i++) {
                int wnt = i * 2;
                GLFW.glfwDefaultWindowHints();
                GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE);
                GLFW.glfwWindowHint(327681, wnt);
                long w = GLFW.glfwCreateWindow(320, 200, "seq", 0L, 0L);
                if (w == 0L) { java.lang.System.out.println("  #" + i + " FAILED"); continue; }
                GLFW.glfwMakeContextCurrent(w);
                GL.createCapabilities();
                java.lang.System.out.println("  #" + i + " want=" + wnt + " -> GL_SAMPLES=" + GL33.glGetInteger(GL33.GL_SAMPLES));
                GLFW.glfwDestroyWindow(w);
            }
            GLFW.glfwTerminate();
            return;
        }
        if (mode.equals("warm")) {
            // 假设检验：先建一个**丢弃**的窗口，再建目标窗口，MSAA 是否就有了？
            GLFW.glfwDefaultWindowHints();
            GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE);
            GLFW.glfwWindowHint(327681, 0);
            long junk = GLFW.glfwCreateWindow(64, 64, "junk", 0L, 0L);
            if (junk != 0L) { GLFW.glfwMakeContextCurrent(junk); GL.createCapabilities(); GLFW.glfwDestroyWindow(junk); }
            GLFW.glfwDefaultWindowHints();
            GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE);
            GLFW.glfwWindowHint(139266, 3);
            GLFW.glfwWindowHint(139267, 3);
            GLFW.glfwWindowHint(327681, 4);
            long win = GLFW.glfwCreateWindow(1280, 800, "warm", 0L, 0L);
            if (win == 0L) { java.lang.System.out.println("warm -> FAILED"); GLFW.glfwTerminate(); return; }
            GLFW.glfwMakeContextCurrent(win);
            GL.createCapabilities();
            java.lang.System.out.println("warm           want=4 -> GL_SAMPLES=" + GL33.glGetInteger(GL33.GL_SAMPLES));
            GLFW.glfwDestroyWindow(win);
            GLFW.glfwTerminate();
            return;
        }
        GLFW.glfwDefaultWindowHints();
        GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE);
        if (mode.startsWith("major") || mode.startsWith("full")) {
            GLFW.glfwWindowHint(139266, 3);   // CONTEXT_VERSION_MAJOR
            GLFW.glfwWindowHint(139267, 3);   // CONTEXT_VERSION_MINOR
        }
        if (mode.startsWith("full")) {
            GLFW.glfwWindowHint(131075, 0);   // RESIZABLE=false
            GLFW.glfwWindowHint(139272, 204801);  // OPENGL_PROFILE = CORE
            GLFW.glfwWindowHint(139270, 1);       // OPENGL_FORWARD_COMPAT = TRUE
            GLFW.glfwWindowHint(135173, 24);      // DEPTH_BITS
            GLFW.glfwWindowHint(135174, 8);       // STENCIL_BITS
        }
        int want = mode.endsWith("msaa") || mode.startsWith("full") || mode.startsWith("major") || mode.equals("min") ? 4 : 0;
        if (mode.endsWith("no-msaa")) want = 0;
        GLFW.glfwWindowHint(327681, want);
        long win = GLFW.glfwCreateWindow(1280, 800, "msaa-probe", 0L, 0L);
        if (win == 0L) { java.lang.System.out.println(mode + " -> window FAILED"); GLFW.glfwTerminate(); return; }
        GLFW.glfwMakeContextCurrent(win);
        GL.createCapabilities();
        java.lang.System.out.println(String.format("%-14s want=%d -> GL_SAMPLES=%d  GL_MAX_SAMPLES=%d  ver=%s",
                mode, want, GL33.glGetInteger(GL33.GL_SAMPLES), GL33.glGetInteger(0x8D57),
                GL33.glGetString(GL33.GL_VERSION)));
        GLFW.glfwDestroyWindow(win);
        GLFW.glfwTerminate();
    }
}
