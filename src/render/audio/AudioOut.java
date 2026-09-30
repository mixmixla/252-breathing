package render.audio;

import core.audio.AudioMixer;
import core.audio.AudioSynth;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.SourceDataLine;

/**
 * C4 音频输出层 —— {@code javax.sound.sampled} 的<b>极薄</b>封装（零新依赖：该 API 自 Java 1.3 起就在 rt.jar 里）。
 *
 * <p>{@code libs/} 里没有 OpenAL，本来要走"新增依赖"；但 JDK 自带 Java Sound，配合
 * {@link AudioSynth} 的<b>程序化合成</b>（无需解码任何音频文件），整条音频链<b>一个 jar 都不必加</b>。
 * 这也与 {@code CjkFont} 用 {@code java.awt} 光栅化代替字体文件是同一取舍：<b>宁可算，不背资产</b>。
 *
 * <p><b>本层要做的只有四件事</b>：
 * <ol>
 *   <li>开线：22050Hz / 16bit / 单声道 / 小端，留 ~200ms 缓冲吸收帧抖动。</li>
 *   <li>喂帧：每渲染帧把 {@code dt} 折算成样点数交给 {@link AudioMixer} 混音后写入。</li>
 *   <li><b>绝不阻塞渲染</b>：写之前先看 {@code line.available()}；缓冲满就<b>少喂或跳过本帧</b>，
 *       宁可让音频轻微迟滞也不让游戏掉帧（渲染帧率是玩家能直接感知的）。</li>
 *   <li><b>优雅降级</b>：无音频设备/无线/被占用/驱动异常 → {@link #available()} 返回 false，
 *       {@link #frame(float)} 变空操作，游戏照常运行（本沙箱正是这种情况）。任何异常都不许冒泡到主循环。</li>
 * </ol>
 *
 * <p>本类不持有 {@code World}，也不写任何仿真状态 —— 音频永远在确定性内核之外。
 */
public final class AudioOut {

    /** 目标缓冲 = 200ms（吸收帧抖动，同时把"醒来时已积压"控制在可接受范围）。 */
    private static final int BUFFER_FRAMES = AudioSynth.SR / 5;

    private SourceDataLine line;
    private final AudioMixer mixer = new AudioMixer();
    private final short[] buf = new short[AudioSynth.SR / 10];   // 混音缓冲（单帧上限 100ms，够覆盖一次长卡顿）
    private final byte[] pcm = new byte[buf.length * 2];         // 设备字节视图：16bit 小端 → 每帧 2 字节
    private boolean available;
    private String status = "not started";
    private long framesWritten;

    public AudioMixer mixer() { return mixer; }

    public boolean available() { return available; }

    /** 供 {@code render_diag.log} 记录的人类可读状态。 */
    public String status() { return status; }

    public long framesWritten() { return framesWritten; }

    /** 尝试打开输出线；任何失败都降级为静音（不抛出）。 */
    public void start() {
        try {
            AudioFormat fmt = new AudioFormat(AudioSynth.SR, 16, 1, true, false);
            DataLine.Info info = new DataLine.Info(SourceDataLine.class, fmt);
            if (!AudioSystem.isLineSupported(info)) {
                status = "UNAVAILABLE (no line for " + fmt.getSampleRate() + "Hz s16 mono)";
                return;
            }
            line = (SourceDataLine) AudioSystem.getLine(info);
            line.open(fmt, BUFFER_FRAMES * 2);      // 参数是字节数：16bit 单声道 → 每帧 2 字节
            line.start();
            available = true;
            status = "ON " + (int) AudioSynth.SR + "Hz mono s16  buf=" + (BUFFER_FRAMES / AudioSynth.SR * 1000)
                    + "ms  mixers=" + AudioSystem.getMixerInfo().length;
        } catch (Throwable t) {
            line = null;
            available = false;
            status = "UNAVAILABLE (" + t.getClass().getSimpleName()
                    + (t.getMessage() == null ? "" : ": " + t.getMessage()) + ")";
        }
    }

    /**
     * 推进一帧音频：按 {@code dt} 混出对应样点并写入设备。
     * 缓冲将满时自动少喂（不阻塞渲染）；设备故障时自动关闭并降级为静音。
     */
    public void frame(float dt) {
        if (!available || line == null) return;
        int want = (int) (dt * AudioSynth.SR);
        if (want < 1) want = 1;
        if (want > buf.length) want = buf.length;
        try {
            int room = line.available() >> 1;      // 字节 → 帧（16bit 单声道 = 2 字节/帧）
            if (room <= 0) return;                 // 缓冲已满：本帧不喂，避免 write 阻塞主循环
            if (want > room) want = room;
            mixer.mix(buf, want);
            // SourceDataLine 只有 write(byte[],off,len) 一个写入重载（没有 short[] 版），
            // 故这里手动摊平成小端 16bit —— 与 AudioFormat(bigEndian=false) 严格对应。
            for (int i = 0; i < want; i++) {
                int v = buf[i];
                pcm[2 * i] = (byte) (v & 0xFF);
                pcm[2 * i + 1] = (byte) ((v >> 8) & 0xFF);
            }
            line.write(pcm, 0, want * 2);
            framesWritten += want;
        } catch (Throwable t) {
            available = false;
            status = "STOPPED (" + t.getClass().getSimpleName() + ")";
            close();
        }
    }

    /** 释放设备（幂等；退出时调用）。 */
    public void close() {
        if (line != null) {
            try { line.drain(); } catch (Throwable ignored) { }
            try { line.stop(); } catch (Throwable ignored) { }
            try { line.close(); } catch (Throwable ignored) { }
            line = null;
        }
        available = false;
    }
}
