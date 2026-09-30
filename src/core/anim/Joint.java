package core.anim;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 骨架节点（QC 2026-09-13 · 自建可配置动作系统 · 一期）。
 *
 * <p>层级 TRS 结构：每个 Joint 有父节点、静息位置（restPos，父空间）、以及被动画驱动的
 * 局部位置/旋转（localPos/localRot，欧拉角 XYZ 度）。{@code updateWorld()} 自根向下把
 * 局部变换累积为世界变换（worldPos/worldRot），供渲染层按关节装配方块/网格。
 *
 * <p><b>确定性边界</b>：本包（core.anim）是<b>纯函数 + 零 RNG + 零网格写入</b>——
 * 给定 (骨架, 时间) 必得同姿态；不进 hashState。渲染层消费姿态，因此动作系统对四道
 * 基线指纹逐字节零影响。</p>
 */
public final class Joint {

    public final String name;
    public final Joint parent;
    /** 静息位置（父空间，块单位）。 */
    public final float[] restPos = new float[3];
    /** 动画驱动的局部位置（父空间；默认 = restPos）。 */
    public final float[] localPos = new float[3];
    /** 动画驱动的局部旋转（欧拉角 XYZ，度）。 */
    public final float[] localRot = new float[3];
    /** 世界位置/旋转（updateWorld 计算）。 */
    public final float[] worldPos = new float[3];
    public final float[] worldRot = new float[3];

    public Joint(String name, Joint parent, float x, float y, float z) {
        this.name = name;
        this.parent = parent;
        restPos[0] = x; restPos[1] = y; restPos[2] = z;
        reset();
    }

    /** 回到静息姿态（局部 = 静息，旋转归零）。 */
    public void reset() {
        localPos[0] = restPos[0]; localPos[1] = restPos[1]; localPos[2] = restPos[2];
        localRot[0] = 0f; localRot[1] = 0f; localRot[2] = 0f;
    }

    /** 自根向下更新世界变换（父的世界变换 ∘ 局部变换）并递归子节点。
     *  <b>调用约定</b>：必须从根调用（{@code Skeleton.updateWorld()}），父先于子计算。数据驱动，无 RNG。 */
    public void updateWorld() {
        if (parent == null) {
            worldPos[0] = localPos[0]; worldPos[1] = localPos[1]; worldPos[2] = localPos[2];
        } else {
            float[] p = parent.worldPos;   // 约定：自根向下调用（Skeleton.updateWorld），父必已算好
            float[] lr = localRot;
            float[] pr = parent.worldRot;
            float[] rotated = Joint.rotate(localPos, pr[0], pr[1], pr[2]);
            worldPos[0] = p[0] + rotated[0];
            worldPos[1] = p[1] + rotated[1];
            worldPos[2] = p[2] + rotated[2];
        }
        worldRot[0] = (parent == null ? 0f : parent.worldRot[0]) + localRot[0];
        worldRot[1] = (parent == null ? 0f : parent.worldRot[1]) + localRot[1];
        worldRot[2] = (parent == null ? 0f : parent.worldRot[2]) + localRot[2];
        for (Joint c : children) c.updateWorld();   // 自根向下必须递归子节点（门禁 XFORM 抓出的实现缺口）
    }

    /** 欧拉角（度, XYZ 顺序）旋转一个点（不修改入参）。 */
    public static float[] rotate(float[] v, float rx, float ry, float rz) {
        float x = v[0], y = v[1], z = v[2];
        double cx = StrictMath.cos(Math.toRadians(rx)), sx = StrictMath.sin(Math.toRadians(rx));
        double cy = StrictMath.cos(Math.toRadians(ry)), sy = StrictMath.sin(Math.toRadians(ry));
        double cz = StrictMath.cos(Math.toRadians(rz)), sz = StrictMath.sin(Math.toRadians(rz));
        double y1 = y * cx - z * sx, z1 = y * sx + z * cx;                 // X
        double x2 = x * cy + z1 * sy, z2 = -x * sy + z1 * cy;              // Y
        double x3 = x2 * cz - y1 * sz, y3 = x2 * sz + y1 * cz;             // Z
        return new float[]{(float) x3, (float) y3, (float) z2};
    }

    /** 骨架：根 + 名字索引 + 便捷查找（收集时自根遍历）。 */
    public static final class Skeleton {
        public final Joint root;
        private final Map<String, Joint> byName = new HashMap<String, Joint>();
        private final List<Joint> all = new ArrayList<Joint>();

        public Skeleton(Joint root) {
            this.root = root;
            collect(root);
        }

        private void collect(Joint j) {
            byName.put(j.name, j);
            all.add(j);
            for (Joint c : j.children) collect(c);
        }

        public Joint joint(String name) { return byName.get(name); }
        public List<Joint> joints() { return all; }

        /** 重置全部关节到静息姿态。 */
        public void resetAll() { for (Joint j : all) j.reset(); }

        /** 更新世界变换（调 root.updateWorld，children 递归）。 */
        public void updateWorld() { root.updateWorld(); }
    }

    // ---- 子节点（链式构建用）----
    public final List<Joint> children = new ArrayList<Joint>();

    /** 链式：添加子关节（父空间偏移）。 */
    public Joint child(String childName, float x, float y, float z) {
        Joint c = new Joint(childName, this, x, y, z);
        children.add(c);
        return c;
    }
}
