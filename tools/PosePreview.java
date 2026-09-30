import core.anim.AnimJson;
import core.anim.Animator;
import core.anim.Clip;
import core.anim.Joint;
import core.anim.ModelDef;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * 姿态预览 —— C 批「骨骼动画」的<b>无头可视化自检</b>。
 *
 * <p>为什么需要它：本项目渲染层（GL）在无 GPU 环境里跑不起来，而"骨骼动画到底对不对"
 * 是纯几何问题 —— 只要能算出各关节的世界变换并把部件画出来，就能在无 GL 的情况下<b>看见</b>姿态。
 * 于是这里把 {@code core.anim} 的完整链路跑一遍：
 * <pre>
 *   AnimJson.parse   → 骨架 + 动作片段（数据）
 *   Animator(time)   → 采样片段姿态
 *   Animator.apply   → 写入各关节的局部旋转
 *   Skeleton.updateWorld → 逐级累积出世界变换（父→子）
 *   ModelDef.parts   → 在关节处装配方块
 * </pre>
 * 这条链正是渲染层该走的路 —— 本工具就是它的"软件替身"，用来证明**数据和变换是对的**；
 * GL 那边只是把同样的世界变换喂给顶点缓冲。
 *
 * <p><b>已知边界（诚实声明）</b>：本工具用正交投影 + 单色填充 + 画家顺序，不含 GL 的光照/阴影/描边，
 * 因此它验证的是<b>姿态、层级、比例、时序</b>，不是最终观感。
 *
 * <p>用法：{@code java -cp out PosePreview [模型 json] [输出 png]}
 */
public final class PosePreview {

    /** 每格多少像素。 */
    static final float SCALE = 130f;
    /** 视角偏航（3/4 视角：既能看到前后摆动，也能区分左右肢体）。 */
    static final double YAW = Math.toRadians(40);

    static final int FW = 170, FH = 300;      // 单帧尺寸

    public static void main(String[] args) throws Exception {
        String path = args.length > 0 ? args[0] : "assets/models/humanoid.json";
        String out = args.length > 1 ? args[1] : "proof/pose_preview.png";

        String json = new String(Files.readAllBytes(new File(path).toPath()), StandardCharsets.UTF_8);
        AnimJson.Rig rig = AnimJson.parse(json);
        ModelDef model = ModelDef.parse(json);

        System.out.println("RIG bones=" + rig.skeleton.joints().size() + " clips=" + rig.clips.size()
                + " parts=" + model.parts.length);

        // ---------- 数值自检 1：walk 的左右腿在相位上真的反号 ----------
        Animator aw = new Animator(rig.clip("walk"));
        rig.skeleton.resetAll(); aw.time = 0f;   aw.apply(rig.skeleton);
        float l0 = rig.skeleton.joint("legL").localRot[0], r0 = rig.skeleton.joint("legR").localRot[0];
        rig.skeleton.resetAll(); aw.time = 0.5f; aw.apply(rig.skeleton);
        float l1 = rig.skeleton.joint("legL").localRot[0], r1 = rig.skeleton.joint("legR").localRot[0];
        boolean alt = (l0 > 0f && r0 < 0f) && (l1 < 0f && r1 > 0f);
        System.out.println("WALK_ALT   " + (alt ? "PASS" : "FAIL")
                + "  t=0 legL=" + l0 + " legR=" + r0 + " | t=0.5 legL=" + l1 + " legR=" + r1);

        // ---------- 数值自检 2：层级 —— 旋转 armR 后，子关节 handR 的世界位置必须跟着动 ----------
        Animator as = new Animator(rig.clip("swing"));
        rig.skeleton.resetAll(); as.time = 0f;   as.apply(rig.skeleton); rig.skeleton.updateWorld();
        float hy0 = rig.skeleton.joint("handR").worldPos[1];
        rig.skeleton.resetAll(); as.time = 0.5f; as.apply(rig.skeleton); rig.skeleton.updateWorld();
        float hy1 = rig.skeleton.joint("handR").worldPos[1];
        boolean hier = Math.abs(hy1 - hy0) > 0.2f;
        System.out.println("HIERARCHY  " + (hier ? "PASS" : "FAIL")
                + "  handR.worldY " + round(hy0) + " -> " + round(hy1));

        // ---------- 数值自检 3：确定性（同 (clip,time) 两次采样逐位相同）----------
        rig.skeleton.resetAll(); as.time = 0.3f; as.apply(rig.skeleton); rig.skeleton.updateWorld();
        float[] a1 = rig.skeleton.joint("handR").worldPos.clone();
        rig.skeleton.resetAll(); as.time = 0.3f; as.apply(rig.skeleton); rig.skeleton.updateWorld();
        float[] a2 = rig.skeleton.joint("handR").worldPos;
        boolean det = a1[0] == a2[0] && a1[1] == a2[1] && a1[2] == a2[2];
        System.out.println("DET        " + (det ? "PASS" : "FAIL")
                + "  handR=" + round(a1[0]) + "," + round(a1[1]) + "," + round(a1[2]));

        // ---------- 出图：idle 3 帧 + walk 4 帧 + swing 4 帧 ----------
        String[] clips = {"idle", "idle", "idle", "walk", "walk", "walk", "walk", "swing", "swing", "swing", "swing"};
        float[] times = {0f, 0.5f, 1.0f, 0f, 0.25f, 0.5f, 0.75f, 0f, 0.15f, 0.30f, 0.45f};

        BufferedImage img = new BufferedImage(FW * clips.length, FH, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(new Color(18, 21, 27));
        g.fillRect(0, 0, img.getWidth(), img.getHeight());

        for (int f = 0; f < clips.length; f++) {
            Clip c = rig.clip(clips[f]);
            if (c == null) continue;
            Animator an = new Animator(c);
            rig.skeleton.resetAll();
            an.time = times[f];
            an.apply(rig.skeleton);
            rig.skeleton.updateWorld();          // 必须自根向下累积，父先于子
            drawFrame(g, f, clips[f], times[f], rig, model);
        }

        File of = new File(out);
        if (of.getParentFile() != null) of.getParentFile().mkdirs();
        ImageIO.write(img, "png", of);
        g.dispose();
        System.out.println("WROTE " + out + "  (" + img.getWidth() + "x" + img.getHeight() + ")");
    }

    // ------------------------------------------------------------------

    private static void drawFrame(Graphics2D g, int idx, String clipName, float t,
                                  AnimJson.Rig rig, ModelDef model) {
        int ox = idx * FW, oy = 0;
        g.setColor(new Color(26, 30, 38));
        g.fillRect(ox, oy, FW, FH);
        g.setColor(new Color(46, 54, 66));
        g.drawRect(ox, oy, FW - 1, FH - 1);

        int groundY = oy + FH - 46;
        g.setColor(new Color(40, 46, 56));
        g.drawLine(ox + 10, groundY, ox + FW - 10, groundY);

        // 画家顺序：先画"远"侧（投影后 x 更小的先画），近似处理肢体遮挡
        ModelDef.Part[] parts = model.parts.clone();
        final AnimJson.Rig rr = rig;
        java.util.Arrays.sort(parts, new java.util.Comparator<ModelDef.Part>() {
            @Override public int compare(ModelDef.Part a, ModelDef.Part b) {
                return Float.compare(projX(rr, a), projX(rr, b));
            }
        });

        for (ModelDef.Part p : parts) {
            Joint j = rig.skeleton.joint(p.joint);
            if (j == null) continue;
            float[] off = Joint.rotate(p.offset, j.worldRot[0], j.worldRot[1], j.worldRot[2]);
            float wx = j.worldPos[0] + off[0];
            float wy = j.worldPos[1] + off[1];
            float wz = j.worldPos[2] + off[2];

            int cx = ox + FW / 2 + Math.round((float) (wz * Math.cos(YAW) + wx * Math.sin(YAW)) * SCALE);
            int cy = groundY - Math.round(wy * SCALE);

            // 水平切面在 3/4 视角下的投影宽度；高度不变
            float w = (float) (Math.abs(p.size[0] * Math.sin(YAW)) + Math.abs(p.size[2] * Math.cos(YAW))) * SCALE;
            float h = p.size[1] * SCALE;

            drawRotRect(g, cx, cy, w, h, -j.worldRot[0], materialColor(p.material));
        }

        g.setColor(new Color(210, 218, 230));
        g.setFont(new Font("Monospaced", Font.PLAIN, 12));
        g.drawString(clipName + "  t=" + t, ox + 8, oy + 18);
    }

    private static float projX(AnimJson.Rig rig, ModelDef.Part p) {
        Joint j = rig.skeleton.joint(p.joint);
        if (j == null) return 0f;
        return (float) (j.worldPos[2] * Math.cos(YAW) + j.worldPos[0] * Math.sin(YAW));
    }

    /** 绕屏幕法线旋转的矩形（体素部件在侧视图里的样子）。 */
    private static void drawRotRect(Graphics2D g, int cx, int cy, float w, float h, float rotDeg, Color col) {
        java.awt.geom.AffineTransform old = g.getTransform();
        g.translate(cx, cy);
        g.rotate(Math.toRadians(rotDeg));
        int wi = Math.max(2, Math.round(w)), hi = Math.max(2, Math.round(h));
        g.setColor(col);
        g.fillRect(-wi / 2, -hi / 2, wi, hi);
        g.setColor(col.darker().darker());
        g.drawRect(-wi / 2, -hi / 2, wi, hi);
        g.setTransform(old);
    }

    private static Color materialColor(String m) {
        if ("skin".equals(m))  return new Color(219, 178, 143);
        if ("cloth".equals(m)) return new Color(78, 108, 158);
        return new Color(158, 158, 158);
    }

    private static String round(float v) { return String.format(java.util.Locale.ROOT, "%.3f", v); }
}
