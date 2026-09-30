package core.audio;

/**
 * C4 声部混音器 —— 有限声部池 + 优先级抢占 + 定点和混音（纯逻辑、零 GL、零 RNG、零世界写入）。
 *
 * <p>它存在的意义与 {@code MenuModel} 相同：<b>把"能不能响、该不该抢、混出来会不会爆"从音频设备里剥出来</b>。
 * 一旦这段逻辑长在 {@code SourceDataLine} 的写回调里，就只能插着耳机靠耳朵验收；抽出来后
 * 第 15 道门禁 {@code core.sim.AudioTest} 能直接断言池上限、抢占次序、静音语义与"不产生环绕回卷爆音"。
 *
 * <p><b>确定性保证</b>：
 * <ul>
 *   <li>声部用<b>定长数组</b>保存（无 {@code HashMap} 迭代序依赖）；混音走 {@code int} 累加，
 *       只到最后一步做一次钳制 —— 避免"边加边截"造成的顺序敏感。</li>
 *   <li>{@link #play(Sfx, int)} 的 {@code variant} 显式传入 → 同脚本必然同 PCM；
 *       便利重载 {@link #play(Sfx)} 用内部计数器自动递增（游戏用，不进任何断言）。</li>
 *   <li>本类<b>不含任何 RNG</b>，故放在渲染循环里对仿真指纹零影响。</li>
 * </ul>
 */
public final class AudioMixer {

    /** 同时发声上限。设为 8：够盖住"命中+击杀+受伤+菜单"这类叠音，又不至于混音糊成一团。 */
    public static final int MAX_VOICES = 8;

    /** 单样点满量程（16bit 有符号）。 */
    private static final int FULL = 32767;

    private final Sfx[] vSfx = new Sfx[MAX_VOICES];
    private final int[] vVar = new int[MAX_VOICES];
    private final int[] vPos = new int[MAX_VOICES];
    private final long[] vStart = new long[MAX_VOICES];

    private long clock;            // 单调事件序（混音推进 + 每次成功播放）—— 声部"年龄"的基准，越小越旧
    private long played, dropped;
    private int nextVariant;
    private float volume = 0.75f;
    private boolean enabled = true;

    private int[] acc = new int[0];   // 混音累加缓冲（按需增长，避免每帧分配）

    // ---------- 配置 ----------

    public void setVolume(float v) { volume = v < 0f ? 0f : (v > 1f ? 1f : v); }

    public float volume() { return volume; }

    public void setEnabled(boolean e) { enabled = e; if (!e) clear(); }

    public boolean enabled() { return enabled; }

    // ---------- 观测（门禁断言用）----------

    /** 当前在发声的声部数（即时重数，不维护易错的计数器）。 */
    public int activeVoices() {
        int c = 0;
        for (int i = 0; i < MAX_VOICES; i++) if (vSfx[i] != null) c++;
        return c;
    }

    /** 指定音效当前的活跃声部数。 */
    public int activeOf(Sfx s) {
        int c = 0;
        for (int i = 0; i < MAX_VOICES; i++) if (vSfx[i] == s) c++;
        return c;
    }

    /**
     * 指定音效<b>最旧</b>活跃声部的变体号（无则 {@link Integer#MIN_VALUE}）。
     * 供调试与门禁观测「老化抢占到底抢了谁」——否则 LRU 次序只存在于实现里、无法断言。
     */
    public int activeVariant(Sfx s) {
        int best = -1;
        for (int i = 0; i < MAX_VOICES; i++)
            if (vSfx[i] == s && (best < 0 || vStart[i] < vStart[best])) best = i;
        return best < 0 ? Integer.MIN_VALUE : vVar[best];
    }

    public long playedCount() { return played; }

    public long droppedCount() { return dropped; }

    /** 主时钟：已混音的帧数。 */
    public long clock() { return clock; }

    // ---------- 播放 ----------

    /** 自动变体（游戏用）：内部计数器递增，保证连发同种音效不会完全同相。 */
    public boolean play(Sfx s) { return play(s, nextVariant++); }

    /**
     * 播放一个音效。池满时按优先级抢占；抢不到则丢弃（计入 {@link #droppedCount()}）。
     *
     * @return 是否成功入池（false = 静音/池满且优先级不足）
     */
    public boolean play(Sfx s, int variant) {
        if (s == null) return false;
        if (!enabled || volume <= 0f) { dropped++; return false; }

        int slot = freeSlot();
        if (slot < 0) {
            int victim = lowestPrioritySlot();
            // 同优先级也允许抢占（抢最旧的）：这是"老化"存在的意义 —— 否则同优先级的
            // 长音会把后续请求<b>永久饿死</b>（例如连续回旋斩时新的命中音永远进不来）。
            if (vSfx[victim] != null && s.prio >= vSfx[victim].prio) slot = victim;
            else { dropped++; return false; }
        }
        vSfx[slot] = s; vVar[slot] = variant; vPos[slot] = 0; vStart[slot] = clock++;
        played++;
        return true;
    }

    public void clear() {
        for (int i = 0; i < MAX_VOICES; i++) { vSfx[i] = null; vPos[i] = 0; }
    }

    private int freeSlot() {
        for (int i = 0; i < MAX_VOICES; i++) if (vSfx[i] == null) return i;
        return -1;
    }

    /** 抢占候选：优先级最低者；同级取<b>更早开始</b>的（老化，防新声部被反复饿死）。 */
    private int lowestPrioritySlot() {
        int best = -1;
        for (int i = 0; i < MAX_VOICES; i++) {
            Sfx a = vSfx[i];
            if (a == null) return i;
            if (best < 0) { best = i; continue; }
            Sfx b = vSfx[best];
            if (a.prio < b.prio) best = i;
            else if (a.prio == b.prio && vStart[i] < vStart[best]) best = i;
        }
        return best < 0 ? 0 : best;
    }

    // ---------- 混音 ----------

    /**
     * 混出 {@code frames} 个样点到 {@code out[0..frames-1]}。
     * 定点累加 → 一次性钳制 → 乘主音量 → 再钳制。声部播完自动回收。
     */
    public void mix(short[] out, int frames) {
        if (frames > acc.length) acc = new int[Math.max(frames, acc.length * 2 + 64)];

        if (!enabled || volume <= 0f) {
            for (int i = 0; i < frames; i++) out[i] = 0;
            advance(frames);                       // 静音期间声部照常老化/回收，恢复时不"接播"
            return;
        }
        for (int i = 0; i < frames; i++) acc[i] = 0;

        for (int vi = 0; vi < MAX_VOICES; vi++) {
            Sfx s = vSfx[vi];
            if (s == null) continue;
            int pos = vPos[vi];
            int n = s.frames;
            for (int i = 0; i < frames; i++) {
                int p = pos + i;
                if (p >= n) break;
                acc[i] += Math.round(AudioSynth.sampleAt(s, vVar[vi], p) * FULL);
            }
            pos += frames;
            if (pos >= n) { vSfx[vi] = null; vPos[vi] = 0; }
            else vPos[vi] = pos;
        }
        for (int i = 0; i < frames; i++) {
            int v = Math.round(acc[i] * volume);
            out[i] = (short) (v > FULL ? FULL : (v < -FULL - 1 ? -FULL - 1 : v));
        }
        clock += frames;
    }

    /** 静音推进：只老化声部、不写输出（供禁用/零音量时保持声部生命周期一致）。 */
    private void advance(int frames) {
        for (int vi = 0; vi < MAX_VOICES; vi++) {
            Sfx s = vSfx[vi];
            if (s == null) continue;
            int pos = vPos[vi] + frames;
            if (pos >= s.frames) { vSfx[vi] = null; vPos[vi] = 0; }
            else vPos[vi] = pos;
        }
        clock += frames;
    }
}
