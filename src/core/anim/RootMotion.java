package core.anim;

/**
 * 根运动（Root Motion）区间累积 —— UE5 对标（C 批③之一）。
 *
 * <p><b>对标来源</b>：UE5 {@code UAnimMontage::ExtractRootMotionFromTrackRange(StartTrackPosition,
 * EndTrackPosition, Context)}（{@code Engine/Private/Animation/AnimMontage.cpp}）——把「根骨在
 * [start, end] 时间区间内的位移/旋转增量」抽成一次独立的运动参数，交给上层（CharacterMovement）
 * 去驱动角色本体，而<b>不是</b>让动画姿态自己乱跑。UE5 的原注释：
 * <i>"assume Root Motion only comes from first track ... deal with looping animations ... break those up
 * into sequential operations"</i>。
 *
 * <p><b>本类做什么</b>：给定片段与时间区间，累积根关节（默认 {@code "root"}）的<b>位置与旋转增量</b>：
 * <ul>
 *   <li>位置增量 = root 世界/local 位置在区间两端的差（沿轨道采样）；</li>
 *   <li>旋转增量 = root 绕 Y 轴的朝向差（欧拉角展开，取连续分支，避免 ±180° 跳变）；</li>
 *   <li><b>区间可加性</b>：[a,c] 的增量 == [a,b] 增量 + [b,c] 增量（顺序累积语义，对标 UE5 分段累积）。</li>
 * </ul>
 *
 * <p><b>确定性</b>：纯函数（(clip, t0, t1) → 增量），零 RNG、零世界写入、不持有任何 sim 状态，
 * 不进 {@link core.world.World#hashState()}。可被 headless 门禁完整验证。
 *
 * <p><b>为什么不直接照搬 UE5</b>：UE5 的 RootMotion 依赖 {@code FTransform} 四元数与
 * {@code CharacterMovementComponent} 的复杂积分（重力/台阶/墙体滑动）。本项目是体素方块世界，
 * 采用<b>简化的欧拉角 + Y 轴朝向差</b>已足够表达"挥砍时上半身前冲半步"这类位移；
 * 真正的墙体碰撞仍交给既有的玩家移动代码，本类只产出"动画想走多远"。
 */
public final class RootMotion {

    /** 根关节名（按此从骨架取根运动轨道）。 */
    public static final String DEFAULT_ROOT = "root";

    private final Clip clip;
    private final String rootJoint;

    public RootMotion(Clip clip) { this(clip, DEFAULT_ROOT); }

    public RootMotion(Clip clip, String rootJoint) {
        if (clip == null) throw new IllegalArgumentException("clip null");
        this.clip = clip;
        this.rootJoint = rootJoint == null ? DEFAULT_ROOT : rootJoint;
    }

    /** 一次性提取增量（不消费、不改任何状态）。返回 {dx, dy, dz, dyawDeg}（4 元）。 */
    public float[] extract(float t0, float t1) {
        Clip.Track tr = track();
        if (tr == null) return new float[4];
        float lo = Math.min(t0, t1), hi = Math.max(t0, t1);
        boolean back = t1 < t0;
        float[] rotA = new float[3], posA = new float[3];
        float[] rotB = new float[3], posB = new float[3];
        // 采样两端（无位置轨道时 pos 保持 0，增量也为 0 —— 该片段不驱动位移）
        tr.sample(lo, rotA, posA);
        tr.sample(hi, rotB, posB);
        float dx = posB[0] - posA[0], dy = posB[1] - posA[1], dz = posB[2] - posA[2];
        float dyaw = shortestYawDelta(rotA[1], rotB[1]);
        if (back) { dx = -dx; dy = -dy; dz = -dz; dyaw = -dyaw; }   // 反向区间取负（可加性）
        return new float[]{dx, dy, dz, dyaw};
    }

    /**
     * 累积一段（消费式）：把 [{@code fromTime}, {@code toTime}] 的增量加到 {@code outAccum} 上，
     * 返回实际消费到的时间 {@code toTime}。上层在攻击/翻滚推进时逐帧调用即可把位移攒起来。
     *
     * @param outAccum 长度≥4 的累加缓冲（dx,dy,dz,dyaw），会被就地累加
     */
    public float accumulate(float fromTime, float toTime, float[] outAccum) {
        if (outAccum == null || outAccum.length < 4) throw new IllegalArgumentException("accum len>=4");
        float[] d = extract(fromTime, toTime);
        for (int i = 0; i < 4; i++) outAccum[i] += d[i];
        return toTime;
    }

    /** 该片段是否真正驱动根位移（无 root 轨道或无位置键 → false，上层应跳过 root-motion 分支）。 */
    public boolean drivesTranslation() {
        Clip.Track tr = track();
        if (tr == null || tr.keys.length == 0) return false;
        for (Clip.Key k : tr.keys) if (k.pos != null) return true;
        return false;
    }

    /** 该片段是否驱动根朝向（有 root 轨道且旋转键非全零）。 */
    public boolean drivesRotation() {
        Clip.Track tr = track();
        if (tr == null) return false;
        for (Clip.Key k : tr.keys) if (Math.abs(k.rot[1]) > 1e-6f) return true;
        return false;
    }

    private Clip.Track track() {
        for (Clip.Track tr : clip.tracks) if (rootJoint.equals(tr.joint)) return tr;
        return null;
    }

    /**
     * 最短路径朝向差（度，结果落在 (-180, 180]）。UE5 用四元数 {@code DeltaRotator()} 得同样语义：
     * 270°→0° 应视为 -90°（逆时针抄近路），而非 +270°。
     */
    static float shortestYawDelta(float from, float to) {
        float d = (to - from) % 360f;
        if (d > 180f) d -= 360f;
        if (d <= -180f) d += 360f;
        return d;
    }
}
