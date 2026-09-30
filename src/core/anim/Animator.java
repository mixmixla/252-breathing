package core.anim;

/**
 * 动画播放器（QC 2026-09-13 · 自建可配置动作系统 · 一期）。
 *
 * <p>职责：播放/切换片段、时长推进、过渡混合（blend）、动作事件帧派发。
 * <b>纯逻辑、零 RNG、零渲染调用</b>——渲染层调 {@link #apply} 取姿态后自行绘制，
 * 因此本类对四道基线指纹零影响，且可被 headless 门禁完整验证。
 */
public final class Animator {

    public Clip clip;                 // 当前片段
    public float time;                // 当前片段内时间（秒）
    public float speed = 1f;          // 播放速率（可做慢动作/加速）

    private Clip blendFrom;           // 过渡来源片段（null = 无过渡）
    private float blendFromTime;      // 来源片段推进到的时间
    private float blendElapsed;
    private float blendDur;

    private float lastTime;           // 上一帧时间（事件帧跨越检测）

    public Animator(Clip initial) {
        this.clip = initial;
        this.time = 0f;
        this.lastTime = 0f;
    }

    /** 切换动作（无过渡，硬切）。 */
    public void play(Clip c) { play(c, 0f); }

    /**
     * 切换动作（带 blendSec 秒过渡）。同 clip 不重播（防止每帧调用导致停帧）；
     * 需要重播请用 {@link #restart}。
     */
    public void play(Clip c, float blendSec) {
        if (c == null || c == clip) return;
        blendFrom = clip;
        blendFromTime = time;
        blendElapsed = 0f;
        blendDur = Math.max(0f, blendSec);
        clip = c;
        time = 0f;
        lastTime = 0f;
    }

    /** 重播同一片段（可从 0 开始 + 过渡）。攻击连段等高频动作使用。 */
    public void restart(Clip c, float blendSec) {
        if (c == null) return;
        blendFrom = (blendSec > 0f && clip != null && clip != c) ? clip : null;
        blendFromTime = time;
        blendElapsed = 0f;
        blendDur = blendFrom == null ? 0f : blendSec;
        clip = c;
        time = 0f;
        lastTime = 0f;
    }

    /** 是否播完（非循环片段到末帧）。 */
    public boolean finished() {
        return !clip.loop && time >= clip.length - 1e-5f;
    }

    /** 推进一帧。 */
    public void update(float dt) {
        lastTime = time;
        time += dt * speed;
        if (clip.loop) {
            if (clip.length > 1e-6f) time = time % clip.length;
        } else if (time > clip.length) {
            time = clip.length;
        }
        if (blendFrom != null) {
            blendFromTime += dt * speed;
            blendElapsed += dt;
            if (blendElapsed >= blendDur) blendFrom = null;
        }
    }

    /** 采样当前姿态到骨架（含过渡混合），并更新世界变换。 */
    public void apply(Joint.Skeleton skel) {
        skel.resetAll();
        clip.sampleAt(time, skel);
        if (blendFrom != null && blendDur > 1e-6f) {
            float w = Math.min(1f, blendElapsed / blendDur);      // 新动作权重
            for (Clip.Track tr : blendFrom.tracks) {
                Joint j = skel.joint(tr.joint);
                if (j == null) continue;
                float[] r = new float[3], p = new float[3];
                float[] curR = j.localRot.clone(), curP = j.localPos.clone();
                System.arraycopy(curR, 0, r, 0, 3);
                System.arraycopy(curP, 0, p, 0, 3);
                float[] oldR = new float[3], oldP = new float[3];
                tr.sample(blendFromTime, oldR, oldP);
                for (int k = 0; k < 3; k++) j.localRot[k] = oldR[k] + (r[k] - oldR[k]) * w;
                // 位置：仅当旧轨道驱动位置时才混合位置
                boolean oldHasPos = tr.keys.length > 0 && tr.keys[0].pos != null;
                if (oldHasPos) for (int k = 0; k < 3; k++) j.localPos[k] = oldP[k] + (p[k] - oldP[k]) * w;
            }
        }
        skel.updateWorld();
    }

    /**
     * 本帧跨过的动作事件（如攻击命中帧）。返回事件 id；无则 -1。
     * 循环片段跨越 0 点也能正确触发。
     */
    public int pollEvent() {
        int found = -1;
        for (int i = 0; i < clip.eventTimes.length; i++) {
            float et = clip.eventTimes[i];
            boolean crossed = (lastTime < time)
                    ? (et > lastTime && et <= time)
                    : (et > lastTime || et <= time);      // 循环回绕
            if (crossed) found = clip.eventIds[i];
        }
        return found;
    }

    public boolean isBlending() { return blendFrom != null; }
}
