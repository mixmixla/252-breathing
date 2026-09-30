package core.audio;

/**
 * C4 音效原型表 —— 「一种声音」的纯数据定义（零依赖、零资产、零 RNG、确定性）。
 *
 * <p>设计理由与 {@code Weapons} / {@code DayCycle} / {@code MenuModel} 一致：把音效的
 * <b>声学参数</b>抽成不可变表，合成器只做纯函数运算。这样：
 * <ul>
 *   <li><b>零资产</b>：不加载任何 {@code .wav}/{@code .ogg}，全部由参数实时合成（与 {@code CjkFont}
 *       用 JDK 光栅化代替字体文件同思路）。</li>
 *   <li><b>可 headless 断言</b>：同参数 → 逐字节同 PCM，可用第 15 道门禁 {@code core.sim.AudioTest} 验证。</li>
 *   <li><b>绝不触碰 sim RNG</b>：每个参数都是常量，播放时只用<b>整数哈希</b>做微小的音高/噪声扰动
 *       （见 {@link AudioSynth#detune(int)} / {@link AudioSynth#noiseAt(int, int)}），<b>不调用任何
 *       {@code SeededRNG}</b>，故对四道基线指纹零影响。</li>
 * </ul>
 *
 * <p><b>字段含义</b>（构造参数按序，列对齐见下方常量表）：
 * <table border="1"><tr><th>字段</th><th>含义</th></tr>
 * <tr><td>{@code wave}</td><td>振荡器波形；{@link Wave#NOISE} 时 {@code f0/f1} 改作<b>亮度扫描</b>（Hz 量纲）</td></tr>
 * <tr><td>{@code f0→f1}</td><td>起始→结束频率（线性扫频，积分得相位，闭式无状态）</td></tr>
 * <tr><td>{@code dur}</td><td>时长（秒）</td></tr>
 * <tr><td>{@code gain}</td><td>峰值增益（&lt;1，留混音余量）</td></tr>
 * <tr><td>{@code atk}</td><td>起振时间（秒）：包络从 <b>0</b> 线性升到 1，<b>这是"起音不爆</b>的保证</td></tr>
 * <tr><td>{@code decay}</td><td>衰减指数，越大越"打击感"（{@code env = (1-u)^decay}，终点恰为 0）</td></tr>
 * <tr><td>{@code rel}</td><td>尾部线性释放时长（秒）：强制把最后 1 跳压到 0，<b>这是"收音不爆</b>的保证</td></tr>
 * <tr><td>{@code prio}</td><td>声部优先级 0..3：池满时低优先级被抢占（0 环境/UI · 3 关键反馈）</td></tr>
 * <tr><td>{@code nMix}</td><td>噪声混合比 0..1（0=纯振荡器，1=纯噪声）</td></tr>
 * <tr><td>{@code h2r}/{@code h2l}</td><td>第二分音频率比 / 电平（做出铃音、金属感泛音）</td></tr>
 * </table>
 */
public enum Sfx {

    //  名称          波形          f0      f1      dur    gain   atk     decay  rel     prio  nMix  h2r    h2l   用途
    DIG        (Wave.NOISE,   900f,   260f, 0.105f, 0.50f, 0.004f, 2.4f, 0.006f,  1, 0.55f, 1.60f, 0.30f), // 挖掉方块
    PLACE      (Wave.SQUARE,  190f,   150f, 0.085f, 0.40f, 0.003f, 3.0f, 0.006f,  1, 0.15f, 1.00f, 0.18f), // 放置方块
    SWING      (Wave.NOISE,  1500f,   420f, 0.115f, 0.26f, 0.022f, 1.7f, 0.012f,  1, 1.00f, 1.00f, 0.00f), // 挥空
    HIT        (Wave.NOISE,   700f,   240f, 0.095f, 0.58f, 0.002f, 3.4f, 0.006f,  2, 0.62f, 2.10f, 0.34f), // 命中
    ROLL       (Wave.NOISE,   800f,   160f, 0.210f, 0.30f, 0.030f, 1.5f, 0.014f,  2, 1.00f, 1.00f, 0.00f), // 翻滚
    JUMP       (Wave.SINE,    300f,   520f, 0.100f, 0.30f, 0.006f, 1.8f, 0.010f,  1, 0.10f, 1.00f, 0.12f), // 起跳
    LAND       (Wave.SINE,    120f,    62f, 0.120f, 0.48f, 0.002f, 3.2f, 0.008f,  1, 0.45f, 1.50f, 0.22f), // 落地
    HURT       (Wave.SAW,     210f,    88f, 0.240f, 0.46f, 0.004f, 1.7f, 0.010f,  2, 0.18f, 1.00f, 0.28f), // 玩家受伤
    KILL       (Wave.SINE,    740f,  1180f, 0.340f, 0.52f, 0.003f, 2.4f, 0.020f,  3, 0.10f, 1.50f, 0.55f), // 击杀敌兵
    LOOT       (Wave.SINE,    900f,  1340f, 0.200f, 0.38f, 0.004f, 2.2f, 0.014f,  2, 0.05f, 2.00f, 0.35f), // 掉落/魂锻
    ART_LUNGE  (Wave.NOISE,   620f,  1900f, 0.220f, 0.52f, 0.008f, 2.6f, 0.010f,  3, 0.45f, 1.60f, 0.40f), // 战技·突刺
    ART_CLEAVE (Wave.SQUARE,  105f,   320f, 0.300f, 0.50f, 0.010f, 2.0f, 0.014f,  3, 0.22f, 1.00f, 0.42f), // 战技·回旋斩
    ART_SHOT   (Wave.SINE,   1150f,   380f, 0.190f, 0.46f, 0.003f, 2.8f, 0.010f,  3, 0.22f, 1.00f, 0.30f), // 战技·远射
    BOMB       (Wave.NOISE,   480f,    90f, 0.360f, 0.62f, 0.002f, 2.0f, 0.018f,  3, 0.78f, 1.50f, 0.30f), // 炸弹能力
    TALK       (Wave.SINE,    520f,   585f, 0.075f, 0.24f, 0.020f, 1.4f, 0.010f,  1, 0.12f, 1.00f, 0.15f), // 与村民交谈
    DENY       (Wave.SQUARE,  300f,   170f, 0.150f, 0.34f, 0.004f, 1.4f, 0.008f,  2, 0.10f, 1.00f, 0.20f), // 无效/资源不足
    LEVELUP    (Wave.SINE,    523f,   784f, 0.520f, 0.46f, 0.008f, 1.3f, 0.030f,  3, 0.02f, 2.00f, 0.40f), // 升级
    UNLOCK     (Wave.SINE,    392f,   784f, 0.900f, 0.48f, 0.030f, 1.1f, 0.050f,  3, 0.04f, 1.50f, 0.50f), // 觉醒/新战技
    RELIC      (Wave.SINE,    587f,   880f, 0.620f, 0.44f, 0.012f, 1.4f, 0.040f,  3, 0.03f, 1.50f, 0.45f), // 遗物破印
    HEART      (Wave.SINE,    261f,   523f, 1.600f, 0.50f, 0.080f, 0.9f, 0.080f,  3, 0.03f, 1.50f, 0.40f), // 世界之心
    MENU_MOVE  (Wave.SQUARE,  820f,   820f, 0.030f, 0.22f, 0.002f, 2.0f, 0.006f,  0, 0.08f, 1.00f, 0.00f), // 菜单移动
    MENU_SELECT(Wave.SINE,    880f,  1140f, 0.100f, 0.34f, 0.003f, 2.2f, 0.008f,  0, 0.05f, 1.50f, 0.25f), // 菜单确认
    MENU_BACK  (Wave.SINE,    640f,   430f, 0.090f, 0.28f, 0.003f, 2.0f, 0.008f,  0, 0.05f, 1.00f, 0.20f), // 菜单返回
    PARRY      (Wave.SQUARE, 1200f,  2400f, 0.160f, 0.44f, 0.002f, 2.6f, 0.008f,  3, 0.18f, 2.20f, 0.45f), // 招架成功（金属脆响）
    EXECUTE    (Wave.NOISE,   420f,    70f, 0.340f, 0.62f, 0.002f, 2.2f, 0.016f,  3, 0.72f, 1.30f, 0.28f); // 处决（沉重闷响）

    /** 振荡器波形。{@code NOISE} 不使用相位，{@code f0/f1} 改作亮度扫描。 */
    public enum Wave { SINE, SQUARE, SAW, TRI, NOISE }

    final Wave wave;
    final float f0, f1, dur, gain, atk, decay, rel;
    final int prio;
    final float nMix, h2r, h2l;
    final int frames;

    Sfx(Wave wave, float f0, float f1, float dur, float gain,
        float atk, float decay, float rel, int prio, float nMix, float h2r, float h2l) {
        this.wave = wave; this.f0 = f0; this.f1 = f1; this.dur = dur; this.gain = gain;
        this.atk = atk; this.decay = decay; this.rel = rel; this.prio = prio;
        this.nMix = nMix; this.h2r = h2r; this.h2l = h2l;
        this.frames = Math.max(1, Math.round(dur * AudioSynth.SR));
    }

    /** 时长对应的采样帧数（{@code AudioSynth.SR} 下）。 */
    public int frames() { return frames; }

    /** 声部优先级 0..3（池满时被抢占的次序；越大越不可被抢）。 */
    public int prio() { return prio; }

    /** 时长（秒）。 */
    public float dur() { return dur; }
}
