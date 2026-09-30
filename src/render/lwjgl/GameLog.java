package render.lwjgl;

import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * 运行时诊断日志（QA 2026-09-12）：统一落 {@code game_diag.log}，供 AI 直接读文件排障。
 *
 * <p>记录内容：主循环/回调未捕获异常（含堆栈）、关键状态切换（聚焦/暂停/HUD 开关）、
 * 受困现场快照。追加式写入工作目录，与 render_diag.log 同位。
 *
 * <p>开关：{@link #ENABLED}——正式发布改为 false 即全静默（一次编译级常量，非运行时按钮，
 * 避免 UI 与持久化成本）。所有写入内部自吞异常（日志失败绝不影响游戏）。
 */
public final class GameLog {

    public static final boolean ENABLED = true;   // 正式发布：改 false 即关闭
    private static PrintWriter out;
    // T0 已移除：时间戳直接用钟表时间（排障时与真实时间对齐）
    private static final SimpleDateFormat TS = new SimpleDateFormat("HH:mm:ss.SSS");

    static { init(); }

    private GameLog() { }

    private static void init() {
        if (!ENABLED) return;
        try {
            out = new PrintWriter(new java.io.FileWriter("game_diag.log", false));
            log("BOOT", "game_diag.log opened (ENABLED=true)");
        } catch (Throwable ignored) {
            out = null;   // 日志不可用 → 全静默，绝不影响游戏
        }
    }

    /** 普通事件：tag 为短分类（FOCUS/HUD/STUCK/RESPAWN...）。 */
    public static synchronized void log(String tag, String msg) {
        if (out == null) return;
        try {
            out.println("[" + TS.format(new Date()) + "] [" + tag + "] " + msg);
            out.flush();
        } catch (Throwable ignored) { }
    }

    private static long lastHb = 0L;

    /** 心跳（5 秒节流）：主循环存活证明 + 关键状态快照——挂起/卡死立刻可辨。 */
    public static synchronized void hb(String msg) {
        if (out == null) return;
        long now = System.currentTimeMillis();
        if (now - lastHb < 5000L) return;
        lastHb = now;
        log("HB", msg);
    }

    /** 异常：类名 + 消息 + 堆栈前 12 行（避免刷爆文件）。 */
    public static synchronized void err(String where, Throwable t) {
        if (out == null) return;
        try {
            out.println("[" + TS.format(new Date()) + "] [ERR:" + where + "] "
                    + t.getClass().getName() + ": " + t.getMessage());
            StackTraceElement[] st = t.getStackTrace();
            for (int i = 0; i < st.length && i < 12; i++) out.println("    at " + st[i]);
            out.flush();
        } catch (Throwable ignored) { }
    }
}
