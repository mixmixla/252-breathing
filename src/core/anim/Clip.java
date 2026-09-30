package core.anim;

/**
 * 动作片段数据（QC 2026-09-13 · 自建可配置动作系统 · 一期）。
 *
 * <p>层级：{@link Clip} 含多 {@link Track}（每轨道针对一个关节）含多 {@link Key}（关键帧）。
 * 全部可从外部 JSON 加载（见 {@link AnimJson}），也可代码构建。
 *
 * <p><b>插值</b>：关键帧之间线性 + 可选 smoothstep 缓入缓出（{@link Key#ease}）；
 * 超出范围取端点（不循环片段播到末帧即静止——由 Animator 决定语义）。
 * <b>确定性</b>：纯函数（time → 姿态），零 RNG，不进指纹。
 */
public final class Clip {

    /** 单关键帧：时间 + 旋转（度, XYZ）+ 可选位置（null = 不驱动位置）。 */
    public static final class Key {
        public float t;
        public final float[] rot = new float[3];
        public float[] pos;
        /** 缓动：0=线性；1=本段 smoothstep 缓入缓出。 */
        public float ease;

        public Key(float t, float rx, float ry, float rz) {
            this.t = t; rot[0] = rx; rot[1] = ry; rot[2] = rz;
        }

        public Key pos(float x, float y, float z) { pos = new float[]{x, y, z}; return this; }
        public Key ease(float e) { ease = e; return this; }
    }

    /** 单关节轨道：关键帧序列 + 采样。 */
    public static final class Track {
        public final String joint;
        public final Key[] keys;

        public Track(String joint, Key[] keys) {
            this.joint = joint;
            this.keys = keys;
            if (keys.length == 0) throw new IllegalArgumentException("track " + joint + " has no keys");
        }

        /** 采样：写 outRot（长度≥3）与 outPos（可 null 或长度≥3；无位置轨道不改 outPos）。 */
        public void sample(float time, float[] outRot, float[] outPos) {
            if (time <= keys[0].t) { copy(keys[0], outRot, outPos); return; }
            Key last = keys[keys.length - 1];
            if (time >= last.t) { copy(last, outRot, outPos); return; }
            for (int i = 0; i < keys.length - 1; i++) {
                Key a = keys[i], b = keys[i + 1];
                if (time < a.t || time > b.t) continue;
                float span = b.t - a.t;
                float u = span <= 1e-6f ? 1f : (time - a.t) / span;
                if (b.ease > 0.5f) u = u * u * (3f - 2f * u);          // 到达帧的缓动（ease 描述"进入本帧"）
                for (int k = 0; k < 3; k++) outRot[k] = a.rot[k] + (b.rot[k] - a.rot[k]) * u;
                if (outPos != null && a.pos != null && b.pos != null)
                    for (int k = 0; k < 3; k++) outPos[k] = a.pos[k] + (b.pos[k] - a.pos[k]) * u;
                return;
            }
        }

        private static void copy(Key k, float[] outRot, float[] outPos) {
            System.arraycopy(k.rot, 0, outRot, 0, 3);
            if (outPos != null && k.pos != null) System.arraycopy(k.pos, 0, outPos, 0, 3);
        }
    }

    public final String name;
    public float length;              // 秒
    public boolean loop;
    public final Track[] tracks;
    /** 动作事件帧：eventTimes[i] 时刻触发 eventIds[i]（如攻击命中判定）。 */
    public float[] eventTimes = new float[0];
    public int[] eventIds = new int[0];

    public Clip(String name, float length, boolean loop, Track... tracks) {
        this.name = name;
        this.length = length;
        this.loop = loop;
        this.tracks = tracks;
    }

    public Clip events(float[] times, int[] ids) {
        this.eventTimes = times; this.eventIds = ids; return this;
    }

    /** 采样整个片段到骨架（不含混合——混合由 Animator 负责）。 */
    public void sampleInto(Joint.Skeleton skel) {
        for (Track tr : tracks) {
            Joint j = skel.joint(tr.joint);
            if (j == null) continue;
            tr.sample(0f, j.localRot, null);        // 先归零该关节旋转（仅用于确定轨道覆盖范围）
            tr.sample(0f, j.localRot, j.localPos);  // 再按 0 时刻采样（由 Animator 用具体时间覆写）
        }
    }

    /** 采样到骨架的指定时间（无混合）。 */
    public void sampleAt(float time, Joint.Skeleton skel) {
        for (Track tr : tracks) {
            Joint j = skel.joint(tr.joint);
            if (j == null) continue;
            tr.sample(time, j.localRot, j.localPos);
        }
    }
}
