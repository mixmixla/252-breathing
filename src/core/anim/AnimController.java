package core.anim;

/**
 * 战斗动作状态机（C 批 · 动作打磨 · 纯模型）。
 *
 * <p>建在 {@link Animator}（插值/缓动/事件帧）之上，补<b>战斗意图层</b>三块现有缺口：
 * <ul>
 *   <li><b>判定帧唯一性</b>：一次攻击内跨过事件帧只发一次伤害（{@code hitConsumed} 双保险）；</li>
 *   <li><b>取消窗</b>：仅后摇的可取消段（{@code [cancelStart, length)}）接受 {@link #input()} 接下一击/闪避；
 *       前摇、判定窗、硬直段输入被忽略（艾尔登法环式"硬直卡手"语义）；</li>
 *   <li><b>连招链</b>：取消窗内连续输入推进 {@code comboStep}，越段切到连招表下一击。</li>
 * </ul>
 *
 * <p><b>确定性</b>：纯逻辑、零 RNG、零渲染调用——只驱动动画时间，不进世界指纹，可被 headless 门禁完整验证。
 * 真实伤害仍由上层（Game）在 {@link #update} 返回判定事件 id 时走 {@link core.content.EffectSink#simulate} 发出，
 * 本类只负责"何时该发一次"，不持有任何世界/玩家状态。
 *
 * <p>阶段划分（按片段时间，非循环攻击片段）：
 * <pre>
 *   [0, startupEnd)      STARTUP  前摇，不可取消，无判定
 *   [startupEnd, cancelStart)  ACTIVE  判定窗，事件帧落于此
 *   [cancelStart, length) CANCEL  可取消窗，input() 接连段
 *   [length, +∞)         RECOVER 后摇硬直，input() 被忽略
 * </pre>
 */
public final class AnimController {

    public static final int PHASE_STARTUP = 0;
    public static final int PHASE_ACTIVE  = 1;
    public static final int PHASE_CANCEL  = 2;
    public static final int PHASE_RECOVER = 3;
    public static final int NO_HIT = -1;

    private final Animator anim;
    private final Clip[] combo;          // 连招表（按段）；段间可不同 clip
    private final float startupEnd;      // 前摇结束 / 判定窗起点
    private final float cancelStart;     // 取消窗起点（>= startupEnd）

    private int phase = PHASE_STARTUP;
    private int comboStep = 0;
    private boolean hitConsumed = false; // 本次攻击判定帧是否已消费

    /**
     * @param combo       连招表（至少 1 段）；第 0 段为起手
     * @param startupEnd  前摇结束时刻（秒），判定窗起点
     * @param cancelStart 取消窗起点（秒），必须 >= startupEnd
     */
    public AnimController(Clip[] combo, float startupEnd, float cancelStart) {
        if (combo == null || combo.length == 0) throw new IllegalArgumentException("combo empty");
        this.combo = combo;
        this.startupEnd = Math.max(0f, startupEnd);
        this.cancelStart = Math.max(this.startupEnd, cancelStart);
        this.anim = new Animator(combo[0]);
    }

    public Animator animator() { return anim; }
    /** 当前段总长（秒）。渲染层用 animator().time / totalLen() 求挥砍进度。 */
    public float totalLen() { return currentLen(); }
    public int phase() { return phase; }
    public int comboStep() { return comboStep; }
    public Clip currentClip() { return combo[comboStep % combo.length]; }
    public boolean finished() { return phase == PHASE_RECOVER; }

    /** 当前段长度（用于 RECOVER 判定）。 */
    private float currentLen() { return currentClip().length; }

    /**
     * 推进一帧。返回本帧"应发出"的判定事件 id（{@link #NO_HIT} 表示无）。
     * 判定帧只在一次攻击内发一次。
     */
    public int update(float dt) {
        anim.update(dt);
        float t = anim.time;
        if (t < startupEnd) phase = PHASE_STARTUP;
        else if (t < cancelStart) phase = PHASE_ACTIVE;
        else if (t < currentLen() - 1e-5f) phase = PHASE_CANCEL;
        else phase = PHASE_RECOVER;

        int ev = anim.pollEvent();
        if (ev != NO_HIT && !hitConsumed) {
            hitConsumed = true;
            return ev;
        }
        return NO_HIT;
    }

    /**
     * 试图输入（下一击 / 闪避）。仅在取消窗（{@link #PHASE_CANCEL}）内有效。
     * 成功则推进连招段并复位判定消费；其余阶段返回 false（输入被硬直吞掉）。
     */
    public boolean input() {
        if (phase != PHASE_CANCEL) return false;
        comboStep++;
        int next = comboStep % combo.length;
        anim.restart(combo[next], 0f);   // 连段硬切（干脆，不带 blend）
        hitConsumed = false;
        phase = PHASE_STARTUP;
        return true;
    }

    /** 复位到起手（用于被击/打断场景，强制回到第 0 段）。 */
    public void reset() {
        comboStep = 0;
        hitConsumed = false;
        phase = PHASE_STARTUP;
        anim.restart(combo[0], 0f);
    }
}
