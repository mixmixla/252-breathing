package core.sim;

import core.audio.AudioMixer;
import core.audio.AudioSynth;
import core.audio.Sfx;
import core.world.World;

/**
 * 音频层门禁（C4，第 15 道）。
 *
 * <p>为什么音频也要一道门禁：音频逻辑若长在 {@code SourceDataLine} 的写回调里，就<b>只能插着耳机靠耳朵验收</b>
 * ——本沙箱既无显示器也无音频设备。抽成 {@code Sfx}（参数表）+ {@code AudioSynth}（纯函数合成）+
 * {@code AudioMixer}（纯逻辑混音）之后，全部可 headless 断言。
 *
 * <p>断言五类：
 * <ol>
 *   <li><b>纯函数 / 确定性</b>：同 {@code (音效, 变体)} 渲染两遍 → 逐样点相同；整段脚本经混音器两遍 → 逐字节相同。
 *       含噪声型音效（DIG/SWING/BOMB），以证明噪声发生器也是 {@code (variant,i)} 的纯函数而非隐藏 RNG。</li>
 *   <li><b>包络起收为零</b>：每个音效首样点必须<b>恰为 0</b>、末样点必须归零（防"咔哒"爆音），且峰值不为 0
 *       （防"其实是静音所以当然没爆音"的假绿）。</li>
 *   <li><b>声部池</b>：上限 8 硬约束；高优先级抢占低优先级；<b>同优先级可抢占最旧者</b>（老化，防饿死）；
 *       低优先级在满池高优先级时被<b>丢弃</b>并计数。</li>
 *   <li><b>静音 / 余量</b>：禁用或零音量 → 不发声、返回 false、输出全零；过载时只<b>削顶</b>不回卷
 *       （回卷会把爆音变成刺耳"啪"声，是钳制写错的典型症状）。</li>
 *   <li><b>零漂移不变量</b>：同一脚本下，「跑 tick 且每 tick 播不同音频」与「完全不播音频」的
 *       {@link World#hashState()} <b>逐字节一致</b> —— 这是"音频绝不碰 sim RNG"的最终证据。</li>
 * </ol>
 *
 * 运行：java -cp out core.sim.AudioTest
 */
public final class AudioTest {

    private static final long SEED = 20260910L;
    private static final int T = 260;

    /** 一段固定的"演奏脚本"（音效 + 变体），用于比对两遍渲染。 */
    private static final Sfx[] SCRIPT = {
            Sfx.DIG, Sfx.PLACE, Sfx.SWING, Sfx.HIT, Sfx.ROLL, Sfx.JUMP, Sfx.LAND, Sfx.HURT,
            Sfx.KILL, Sfx.LOOT, Sfx.ART_LUNGE, Sfx.ART_CLEAVE, Sfx.ART_SHOT, Sfx.BOMB,
            Sfx.TALK, Sfx.DENY, Sfx.LEVELUP, Sfx.UNLOCK, Sfx.RELIC, Sfx.HEART,
            Sfx.MENU_MOVE, Sfx.MENU_SELECT, Sfx.MENU_BACK,
    };

    private static final int MIX_FRAMES = 4096;

    /** 把一段脚本灌进混音器并混出定长 PCM（同一脚本 → 同一字节）。 */
    private static short[] renderScript(AudioMixer mx, float volume) {
        mx.setVolume(volume);
        for (int i = 0; i < SCRIPT.length; i++) mx.play(SCRIPT[i], 1 + i * 7);
        short[] out = new short[MIX_FRAMES];
        mx.mix(out, MIX_FRAMES);
        return out;
    }

    private static boolean same(short[] a, short[] b) {
        if (a.length != b.length) return false;
        for (int i = 0; i < a.length; i++) if (a[i] != b[i]) return false;
        return true;
    }

    private static int peak(short[] buf) {
        int p = 0;
        for (short v : buf) p = Math.max(p, Math.abs(v));
        return p;
    }

    /** 跑 {@code ticks} 次 tick 的终态指纹；{@code audio!=null} 时每 tick 播一个不同音效。 */
    private static long hashWithAudio(int ticks, AudioMixer mx) {
        Simulation sim = new Simulation(SEED, 64, 40, 64);
        short[] out = new short[512];
        for (int i = 0; i < ticks; i++) {
            sim.world.tick();
            if (mx != null) {
                mx.play(SCRIPT[i % SCRIPT.length], i);
                mx.mix(out, 512);
            }
        }
        return sim.world.hashState();
    }

    public static void main(String[] args) {
        StringBuilder ev = new StringBuilder();

        // ---------- 1) 纯函数 / 确定性 ----------
        int pureOk = 0;
        for (Sfx s : Sfx.values()) {
            short[] a = AudioSynth.render(s, 3);
            short[] b = AudioSynth.render(s, 3);
            if (same(a, b) && a.length == s.frames()) pureOk++;
        }
        short[] burstA = renderScript(new AudioMixer(), 0.75f);
        short[] burstB = renderScript(new AudioMixer(), 0.75f);
        boolean burstEq = same(burstA, burstB) && peak(burstA) > 0;
        boolean pureOkAll = pureOk == Sfx.values().length;
        ev.append("PURE ").append(pureOk).append("/").append(Sfx.values().length)
          .append(" pcmEq=").append(pureOkAll)
          .append(" | DET burstEq=").append(burstEq).append(" bytes=").append(burstA.length * 2);

        // ---------- 2) 包络：首样点为 0 / 末样点归零 / 峰值非 0 ----------
        int envOk = 0;
        StringBuilder bad = new StringBuilder();
        for (Sfx s : Sfx.values()) {
            short[] b = AudioSynth.render(s, 5);
            int p = peak(b);
            boolean startZero = b[0] == 0;
            boolean endSilent = Math.abs(b[b.length - 1]) <= Math.max(1, p / 50);   // 末样点 ≤ 峰值的 2%
            boolean notSilent = p > 0;
            if (startZero && endSilent && notSilent && p <= 32767) envOk++;
            else bad.append(s.name()).append(",");
        }
        ev.append(" | ENVELOPE ").append(envOk).append("/").append(Sfx.values().length)
          .append(" bad=[").append(bad).append("]");

        // ---------- 3) 声部池：上限 / 抢占 / 老化 / 饿死 ----------
        AudioMixer cap = new AudioMixer();
        for (int i = 0; i < 32; i++) cap.play(Sfx.MENU_MOVE, i);          // 全为 prio 0
        // 池上限是硬约束（active 永不超 8）；同优先级走老化抢占，故 32 次请求一个不丢、只是不断换掉最旧的
        boolean capOk = cap.activeVoices() == AudioMixer.MAX_VOICES
                && cap.playedCount() == 32 && cap.droppedCount() == 0;

        AudioMixer st = new AudioMixer();
        for (int i = 0; i < 7; i++) st.play(Sfx.KILL, i);                 // prio 3
        st.play(Sfx.MENU_MOVE, 100);                                      // prio 0（占满第 8 位，最该被抢）
        boolean stealLow = st.activeOf(Sfx.MENU_MOVE) == 1;
        st.play(Sfx.HIT, 200);                                            // prio 2 > 0 → 应抢走 MENU_MOVE
        boolean stealOk = stealLow && st.activeOf(Sfx.MENU_MOVE) == 0
                && st.activeOf(Sfx.HIT) == 1 && st.activeVoices() == AudioMixer.MAX_VOICES;
        st.play(Sfx.DIG, 300);                                            // prio 1 < 全池最低 2 → 丢弃
        boolean starveOk = st.activeOf(Sfx.DIG) == 0 && st.droppedCount() == 1;

        AudioMixer lru = new AudioMixer();
        for (int i = 0; i < AudioMixer.MAX_VOICES; i++) lru.play(Sfx.HIT, 10 + i);   // 全 prio 2，变体 10..17
        int oldestBefore = lru.activeVariant(Sfx.HIT);
        boolean lruAccepted = lru.play(Sfx.HIT, 99);                      // 同优先级 → 抢最旧
        int oldestAfter = lru.activeVariant(Sfx.HIT);
        boolean lruOk = lruAccepted && oldestBefore == 10 && oldestAfter == 11
                && lru.activeOf(Sfx.HIT) == AudioMixer.MAX_VOICES && lru.droppedCount() == 0;
        ev.append(" | VOICES cap=").append(AudioMixer.MAX_VOICES)
          .append(" fill32 played=").append(cap.playedCount()).append(" dropped=").append(cap.droppedCount())
          .append(" active=").append(cap.activeVoices())
          .append(" | STEAL hi>lo=").append(stealOk).append(" starveLo=").append(starveOk)
          .append(" lruOldest=").append(lruOk);

        // ---------- 4) 静音 / 余量（削顶不回卷）----------
        AudioMixer mute = new AudioMixer();
        mute.setEnabled(false);
        boolean muteBlocks = !mute.play(Sfx.BOMB, 1) && mute.activeVoices() == 0;
        short[] silent = new short[256];
        for (int i = 0; i < silent.length; i++) silent[i] = 1234;         // 预置脏数据：必须被覆写
        mute.mix(silent, silent.length);
        boolean muteSilent = peak(silent) == 0;
        AudioMixer zero = new AudioMixer();
        zero.setVolume(0f);
        boolean zeroBlocks = !zero.play(Sfx.BOMB, 1) && zero.activeOf(Sfx.BOMB) == 0;

        AudioMixer loud = new AudioMixer();
        loud.setVolume(1f);
        for (int i = 0; i < AudioMixer.MAX_VOICES; i++) loud.play(Sfx.BOMB, i);   // 最响音效 × 8
        short[] hot = new short[2048];
        loud.mix(hot, hot.length);
        int pos = 0, neg = 0;
        for (short v : hot) { if (v > pos) pos = v; if (v < neg) neg = v; }
        boolean railsOk = pos == 32767 && neg <= -30000;   // 正负两条轨都真的压过（否则断言是空洞的）

        // 混音契约：输出必须<b>逐样点</b>等于 clamp(Σ 各声部 round(sample·32767))。
        // 这一条同时覆盖"定点和累加是否回卷"与"取整/钳制边界是否错位"——比"看相邻跳变"可靠得多：
        // 饱和波形本来就在 ±轨之间大幅跳变，把那种跳变当回卷会大面积误报。
        AudioMixer sum = new AudioMixer();
        sum.setVolume(1f);
        sum.play(Sfx.KILL, 1);
        sum.play(Sfx.BOMB, 2);
        short[] got = new short[1024];
        sum.mix(got, got.length);
        int differ = 0;
        for (int i = 0; i < got.length; i++) {
            long e = Math.round(AudioSynth.sampleAt(Sfx.KILL, 1, i) * 32767f)
                   + Math.round(AudioSynth.sampleAt(Sfx.BOMB, 2, i) * 32767f);
            if (e > 32767) e = 32767; else if (e < -32768) e = -32768;
            if (got[i] != e) differ++;
        }
        boolean mixExact = differ == 0;
        ev.append(" | MUTE silent=").append(muteSilent).append(" blocked=").append(muteBlocks)
          .append(" zeroVol=").append(zeroBlocks)
          .append(" | MIX rails=").append(railsOk).append(" exact=").append(mixExact)
          .append(" differ=").append(differ);

        // ---------- 5) 零漂移不变量 ----------
        long hNoAudio = hashWithAudio(T, null);
        long hAudioA = hashWithAudio(T, new AudioMixer());
        AudioMixer mb = new AudioMixer();
        mb.setVolume(1f);
        long hAudioB = hashWithAudio(T, mb);
        boolean zeroDrift = hNoAudio == hAudioA && hNoAudio == hAudioB;
        ev.append(" | ZERO-DRIFT ").append(Long.toHexString(hNoAudio))
          .append("==").append(Long.toHexString(hAudioA))
          .append("==").append(Long.toHexString(hAudioB));

        boolean pass = pureOkAll && burstEq && envOk == Sfx.values().length
                && capOk && stealOk && starveOk && lruOk
                && muteBlocks && muteSilent && zeroBlocks && railsOk && mixExact && zeroDrift;

        System.out.println("AUDIO  " + ev);
        System.out.println("SYNTH  sr=" + AudioSynth.SR + " Hz mono s16  voices=" + AudioMixer.MAX_VOICES
                + "  sfx=" + Sfx.values().length + "  zeroAsset=true zeroRng=true");
        System.out.println(pass ? "AUDIO PASS" : "AUDIO FAIL");
        if (!pass) System.exit(1);
    }
}
