package core.audio;

/**
 * C4 程序化合成器 —— {@link Sfx} 参数 → 16bit 单声道 PCM 的<b>纯函数</b>（零状态、零依赖、零 RNG）。
 *
 * <p>关键性质（第 15 道门禁 {@code core.sim.AudioTest} 逐条断言）：
 * <ol>
 *   <li><b>纯函数</b>：{@link #sampleAt(Sfx, int, int)} 只依赖 {@code (音效, 变体, 样点号)}，无内部缓冲、
 *       无调用顺序依赖 → 同参数必然逐样点相同，「可从头再来、可跳播、可分包流式」都天然成立。</li>
 *   <li><b>无 RNG</b>：音高微扰与噪声都来自<b>整数位混洗</b>（{@link #detune(int)} / {@link #noiseAt(int, int)}），
 *       不碰 {@code SeededRNG}——这是音频能进游戏主循环却<b>不漂移指纹</b>的根本原因。</li>
 *   <li><b>起收音都为 0</b>：包络 = 线性起振 × {@code (1-u)^decay} × 尾部线性释放，
 *       首样点必为 0、末样点必归 0 → 不会出现"咔哒"爆音（click）。</li>
 *   <li><b>闭式相位</b>：线性扫频 { f0→f1 } 的相位积分为
 *       {@code φ(t) = 2π(f0·t + (f1-f0)·t²/(2·dur))}，故可 O(1) 取任意样点，无需存储历史。</li>
 * </ol>
 */
public final class AudioSynth {

    /** 采样率（Hz）。单声道 16bit → 无重采样、无声道交织，最小化"平台相关字节差异"的表面。 */
    public static final int SR = 22050;

    private static final double TWO_PI = Math.PI * 2.0;

    private AudioSynth() { }

    /**
     * 取 {@link Sfx} 在给定变体下的第 {@code i} 个样点（−1.0 .. 1.0 的浮点）。
     *
     * @param s       音效原型
     * @param variant 变体号（同一音效每次播放递增 → 微小的音高/噪声抖动，避免"机关枪式"完全重复）
     * @param i       样点序号（&lt;0 或 ≥{@link Sfx#frames()} 返回 0）
     */
    public static float sampleAt(Sfx s, int variant, int i) {
        int n = s.frames;
        if (i < 0 || i >= n) return 0f;

        double t = i / (double) SR;              // 秒
        double u = i / (double) n;               // 归一化进度 0..1
        double det = detune(variant);            // ±2.5% 音高微扰（确定性）

        double f = (s.f0 + (s.f1 - s.f0) * u) * det;
        // 闭式相位：∫f dt = f0·t + (f1−f0)·t²/(2·dur)
        double ph = TWO_PI * (s.f0 * det * t + (s.f1 - s.f0) * det * t * t / (2.0 * s.dur));

        double osc = (s.wave == Sfx.Wave.NOISE) ? 0.0 : osc(s.wave, ph);
        double noise = 0.0;
        if (s.wave == Sfx.Wave.NOISE || s.nMix > 0f) noise = noise(s, variant, i, f);

        double v = osc * (1.0 - s.nMix) + noise * s.nMix;
        if (s.h2l > 0f) {
            Sfx.Wave hw = (s.wave == Sfx.Wave.NOISE) ? Sfx.Wave.SINE : s.wave;
            v += s.h2l * osc(hw, ph * s.h2r);
            v /= (1.0 + s.h2l);
        }
        return (float) (v * env(s, t, u, n, i));
    }

    /** 渲染整段到 {@code out[off .. off+frames-1]}，返回写入样点数。 */
    public static int render(Sfx s, int variant, short[] out, int off) {
        int n = s.frames;
        for (int i = 0; i < n; i++) {
            float v = sampleAt(s, variant, i);
            int q = Math.round(v * 32767f);
            out[off + i] = (short) (q > 32767 ? 32767 : (q < -32768 ? -32768 : q));
        }
        return n;
    }

    /** 渲染整段到新数组（门禁用，避免共享缓冲带来的"其实没在比"假绿）。 */
    public static short[] render(Sfx s, int variant) {
        short[] buf = new short[s.frames];
        render(s, variant, buf, 0);
        return buf;
    }

    // ---------- 包络 ----------

    /**
     * 幅度包络：线性起振 → 幂衰减 → 尾部线性释放。
     * 首样点为 0（{@code t=0} 时起振系数为 0），末样点归 0（尾部释放把最后 1 跳压到 0）。
     */
    static double env(Sfx s, double t, double u, int n, int i) {
        double a = s.atk <= 0f ? 1.0 : Math.min(1.0, t / s.atk);
        double d = StrictMath.pow(1.0 - u, s.decay);
        double e = a * d;
        int release = Math.max(1, (int) (s.rel * SR));
        int remain = n - i;
        if (remain < release) e *= remain / (double) release;
        return e;
    }

    // ---------- 振荡器 ----------

    private static double osc(Sfx.Wave w, double ph) {
        switch (w) {
            case SINE:   return StrictMath.sin(ph);
            case SQUARE: return StrictMath.sin(ph) >= 0.0 ? 1.0 : -1.0;
            case SAW: {
                double x = ph / TWO_PI;
                return 2.0 * (x - Math.floor(x)) - 1.0;
            }
            case TRI: {
                double x = ph / TWO_PI;
                double f = x - Math.floor(x);
                return 2.0 * Math.abs(2.0 * f - 1.0) - 1.0;
            }
            default:     return 0.0;   // NOISE 不走此路
        }
    }

    /**
     * 噪声：白噪 + 一阶高通混合，混合比由扫频值扮演"亮度"。
     * 高通写成 {@code nz(i) − nz(i−1)}——因为噪声是 {@code (variant,i)} 的纯函数，
     * 所以差分也是纯函数（无需存上一个样点），这是本合成器能保持无状态的关键。
     */
    private static double noise(Sfx s, int variant, int i, double f) {
        double bright = clamp01((f - 120.0) / 2200.0);
        double nz = noiseAt(variant, i);
        double hp = nz - noiseAt(variant, i - 1);
        // 归一化，避免高通项把幅度顶出 ±1
        return (nz + hp * bright * 2.0) / (1.0 + bright * 2.0);
    }

    private static double clamp01(double v) { return v < 0 ? 0 : (v > 1 ? 1 : v); }

    // ---------- 确定性"伪随机"（整数位混洗，非 SeededRNG）----------

    /** 白噪样点 ∈ [−1,1)，{@code (variant,i)} 的纯函数。 */
    static double noiseAt(int variant, int i) {
        long h = (i * 0x9E3779B97F4A7C15L) ^ ((long) variant * 0xBF58476D1CE4E5B9L);
        h ^= h >>> 29; h *= 0xBF58476D1CE4E5B9L; h ^= h >>> 32;
        return (h >>> 11) * (1.0 / 9007199254740992.0) * 2.0 - 1.0;
    }

    /** 音高微扰系数 ∈ [0.975, 1.025)，{@code variant} 的纯函数。 */
    static double detune(int variant) {
        long h = variant * 0xD6E8FEB86659FD93L;
        h ^= h >>> 31; h *= 0xC2B2AE3D27D4EB4FL; h ^= h >>> 27;
        double r = (h >>> 40) / (double) (1L << 24);      // [0,1)
        return 1.0 + (r - 0.5) * 0.05;
    }
}
