package core.anim;

import java.util.HashMap;
import java.util.Map;

/**
 * 骨骼遮罩 + 分层混合（Layer Blend / Bone Mask）—— UE5 对标（C 批③之三）。
 *
 * <p><b>对标来源</b>：UE5 {@code FAnimNode_LayeredBoneBlend}（{@code AnimGraphRuntime}）
 * 与 {@code FAnimationRuntime::CreateMaskWeights / BlendPosesPerBoneFilter}
 * （{@code Engine/Private/Animation/AnimationRuntime.cpp}）。UE5 的两个关键语义：
 * <ol>
 *   <li><b>掩码层级传播</b>（{@code CreateMaskWeights}）：指定一个「掩码骨」（如
 *       {@code spine_01}），从它<b>向下遍历所有后代</b>，按深度递减权重
 *       （{@code IncreaseWeightPerDepth = 1/BlendDepth}）；不在子树内的骨权重为 0。
 *       这就是"上半身遮罩"：mask 从脊椎生效到手指，腿部权重 0。</li>
 *   <li><b>逐骨混合</b>（{@code BlendPosesPerBoneFilter}）：对每根骨，用它的掩码权重把
 *       base 姿态与 overlay 姿态插值（{@code Lerp(base, overlay, boneWeight)}），
 *       于是「上半身播攻击、下半身保持跑动」这种分层组合不需要两条完整动画。</li>
 * </ol>
 *
 * <p><b>本类做什么</b>：{@link Mask} 描述"哪些关节受 overlay 影响"（可指定根骨 + 深度衰减），
 * {@link LayeredBlend} 把 base 骨架与 overlay 骨架按每关节权重混合。overlay 姿态可由任意
 * {@link Animator}/{@link Clip} 采样得到 —— 与 base 解耦。
 *
 * <p><b>确定性</b>：纯函数、零 RNG、零世界写入，不进 {@link core.world.World#hashState()}。
 */
public final class BoneMask {

    private BoneMask() {}

    /** 单关节掩码权重（名字 → [0,1]）。 */
    public static final class Mask {
        private final Map<String, Float> w = new HashMap<String, Float>();
        private final Joint.Skeleton skel;

        public Mask(Joint.Skeleton skel) { this.skel = skel; }

        /** 直接设定某关节权重（钳制到 [0,1]）。 */
        public Mask set(String joint, float weight) {
            w.put(joint, clamp01(weight));
            return this;
        }

        /** 取权重（未设定 = 0）。 */
        public float weight(String joint) { return w.containsKey(joint) ? w.get(joint) : 0f; }

        /**
         * 从 {@code rootBone} 起<b>向下</b>逐代设定权重：本代 = {@code weight}，
         * 每深一代乘 {@code falloff}（对标 UE5 的 {@code IncreaseWeightPerDepth} 深度递减）。
         * falloff=1 → 整棵子树同权重；falloff=0 → 只影响该骨本身。
         */
        public Mask subtree(String rootBone, float weight, float falloff) {
            Joint root = skel.joint(rootBone);
            if (root == null) return this;
            apply(root, clamp01(weight), clamp01(falloff));
            return this;
        }

        private void apply(Joint j, float val, float falloff) {
            w.put(j.name, clamp01(val));
            float childVal = val * falloff;
            for (Joint c : j.children) apply(c, childVal, falloff);
        }

        /** 便捷：上半身遮罩（从脊椎起，整棵子树同权重 1；常见用途）。 */
        public static Mask upperBody(Joint.Skeleton skel, String spineBone, float weight) {
            return new Mask(skel).subtree(spineBone, weight, 1f);
        }

        /** 便捷：下半身遮罩（从骨盆(root) 起，但排除脊椎子树 —— 逐骨排除）。 */
        public static Mask lowerBody(Joint.Skeleton skel, String spineBone, float weight) {
            Mask m = new Mask(skel);
            Joint spine = skel.joint(spineBone);
            for (Joint j : skel.joints()) {
                if (isDescendantOf(j, spine)) continue;   // 上半身不进下半身遮罩
                m.set(j.name, weight);
            }
            return m;
        }

        private static boolean isDescendantOf(Joint j, Joint root) {
            if (root == null) return false;
            Joint p = j;
            while (p != null) { if (p == root) return true; p = p.parent; }
            return false;
        }
    }

    /**
     * 逐骨分层混合：把 {@code overlay} 骨架姿态按 {@code mask} 权重混合进 {@code base} 骨架。
     *
     * <p>对每根骨：{@code result = base + (overlay - base) * maskWeight}（旋转按轴线性插值），
     * 权重 0 的骨完全保留 base，权重 1 的骨完全取自 overlay。混合后自动 {@code updateWorld()}。
     *
     * <p><b>用法</b>：base 用移动动画 {@code apply()} 得到"跑动全身"；overlay 用攻击动画采样
     * 到<b>另一个</b>骨架；再 {@code blendInto(base, overlay, upperBodyMask)} → 上半身挥剑、
     * 下半身继续跑。
     *
     * @param base    基础骨架（会被就地修改为混合结果）
     * @param overlay overlay 骨架（只读；与 base 需同名关节）
     * @param mask    逐关节权重
     */
    public static void blendInto(Joint.Skeleton base, Joint.Skeleton overlay, Mask mask) {
        for (Joint j : base.joints()) {
            Joint o = overlay.joint(j.name);
            if (o == null) continue;
            float mw = mask == null ? 0f : mask.weight(j.name);
            if (mw <= 0f) continue;
            for (int k = 0; k < 3; k++) {
                j.localRot[k] = j.localRot[k] + (o.localRot[k] - j.localRot[k]) * mw;
                j.localPos[k] = j.localPos[k] + (o.localPos[k] - j.localPos[k]) * mw;
            }
        }
        base.updateWorld();
    }

    /**
     * 采样一个片段到独立骨架（overlay 姿态来源；不改 base）。便捷封装。
     * 注意：为免每次分配，调用方最好复用同一个 overlay 骨架/骨架实例。
     */
    public static void sampleOverlay(Clip clip, float time, Joint.Skeleton overlay) {
        overlay.resetAll();
        if (clip != null) clip.sampleAt(time, overlay);
        overlay.updateWorld();
    }

    private static float clamp01(float v) { return v < 0f ? 0f : (v > 1f ? 1f : v); }
}
