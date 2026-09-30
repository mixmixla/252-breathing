package core.sim;

import core.anim.BlendSpace;
import core.anim.BoneMask;
import core.anim.Clip;
import core.anim.Joint;
import core.anim.RootMotion;

/**
 * C 批③门禁：root-motion / 混合树 / 上下半身遮罩（UE5 对标落地）。
 *
 * <p>断言的是<b>性质</b>而非"能跑"：
 * <ol>
 *   <li><b>ROOT-MOTION</b>：区间累积增量正确；<b>区间可加性</b> [a,c]=[a,b]+[b,c]；零区间=0；
 *       反向区间取负；无位置轨道时 drivesTranslation()=false；朝向差走最短路径（270°→0° 视为 -90°）。</li>
 *   <li><b>BLENDSPACE</b>：顶点采样权重=1（独权）；内部权重和=1 且重心正确（格心四样本各 0.25 或
 *       三角形内重心）；凸包外的点权重仍和为 1（钳制到最近边）；三角化确定性。</li>
 *   <li><b>BONEMASK</b>：subtree 掩码向下传播（本骨=w，逐代 ×falloff）；upperBody 只覆盖脊椎子树、
 *       腿部权重 0；blendInto 权重 0 = 全 base、权重 1 = 全 overlay、权重 0.5 = 中值；父传播正确。</li>
 *   <li><b>确定性</b>：三项对同输入两次调用逐位一致（纯函数、零 RNG）。</li>
 *   <li><b>零漂移</b>：整套动作层运算不触碰 World 状态（hashState 前后一致）。</li>
 * </ol>
 *
 * 运行：java -cp out core.sim.AnimLayersTest
 */
public final class AnimLayersTest {

    private static int fails = 0;
    private static void ck(String name, boolean ok, String detail) {
        System.out.println((ok ? "  ok  " : " FAIL ") + name + (ok ? "" : "  -> " + detail));
        if (!ok) fails++;
    }

    // ---- 骨架：root → spine → head / arm；root → leg ----
    private static Joint.Skeleton buildSkel() {
        Joint root = new Joint("root", null, 0, 0, 0);
        Joint spine = root.child("spine", 0, 10, 0);
        Joint head = spine.child("head", 0, 5, 0);
        Joint arm = spine.child("arm", 3, 3, 0);
        Joint leg = root.child("leg", 1, -10, 0);
        return new Joint.Skeleton(root);
    }

    // root 轨道：位置 (0,0,0)→(10,0,4) 线性；朝向 0→270°
    private static Clip rootClip() {
        return new Clip("dash", 1.0f, false,
                new Clip.Track("root", new Clip.Key[]{
                        new Clip.Key(0f, 0f, 0f, 0f).pos(0f, 0f, 0f),
                        new Clip.Key(1.0f, 0f, 270f, 0f).pos(10f, 0f, 4f)
                }));
    }

    /** 只有 root 旋转、无位移的片段（带位置键但位移为零 → drivesTranslation=false）。 */
    private static Clip turnOnlyClip() {
        return new Clip("turn", 1.0f, false,
                new Clip.Track("root", new Clip.Key[]{
                        new Clip.Key(0f, 0f, 0f, 0f),
                        new Clip.Key(1.0f, 0f, 90f, 0f)
                }));
    }

    /** arm 轨道（非 root）—— 不驱动根运动。 */
    private static Clip armOnlyClip() {
        return new Clip("spin", 1.0f, false,
                new Clip.Track("arm", new Clip.Key[]{
                        new Clip.Key(0f, 0f, 0f, 0f),
                        new Clip.Key(1.0f, 0f, 90f, 0f)
                }));
    }

    public static void main(String[] args) {
        // ---------- 1) ROOT-MOTION ----------
        Clip dash = rootClip();
        RootMotion rm = new RootMotion(dash);
        float[] full = rm.extract(0f, 1f);
        boolean fullOk = Math.abs(full[0] - 10f) < 1e-4f && Math.abs(full[2] - 4f) < 1e-4f
                && Math.abs(full[3] - (-90f)) < 1e-3f;   // 270° → 最短路径 -90°
        ck("ROOT_FULL", fullOk, "d=" + full[0] + "," + full[1] + "," + full[2] + " yaw=" + full[3]);

        // 区间可加性（位置分量）：[0,1] = [0,0.4] + [0.4,1]
        // 注：yaw 走"最短路径"，跨 ±180° 时天然不可加（如 108°+162° 归一到 -90°），
        // 这是 UE5 DeltaRotator 的同款语义 —— 故可加性只在位置分量上断言，yaw 另测同向区间。
        float[] a = rm.extract(0f, 0.4f);
        float[] b = rm.extract(0.4f, 1f);
        boolean addOk = true;
        for (int i = 0; i < 3; i++) if (Math.abs((a[i] + b[i]) - full[i]) > 1e-4f) addOk = false;
        // yaw 同向区间（不跨 ±180°）：root 改为 0→170° 的小片段测可加
        RootMotion rmSmall = new RootMotion(new Clip("y", 1f, false,
                new Clip.Track("root", new Clip.Key[]{
                        new Clip.Key(0f, 0f, 0f, 0f),
                        new Clip.Key(1.0f, 0f, 170f, 0f)})));
        float[] ys1 = rmSmall.extract(0f, 0.4f), ys2 = rmSmall.extract(0.4f, 1f), ysf = rmSmall.extract(0f, 1f);
        boolean yawAdd = Math.abs((ys1[3] + ys2[3]) - ysf[3]) < 1e-3f;
        ck("ROOT_ADDITIVE", addOk && yawAdd, "pos a+b=full:" + addOk + " yaw same-dir:" + yawAdd);

        // 零区间 = 0；反向区间取负
        float[] zero = rm.extract(0.5f, 0.5f);
        boolean zeroOk = Math.abs(zero[0]) < 1e-5f && Math.abs(zero[3]) < 1e-5f;
        float[] fwd = rm.extract(0f, 0.5f), back = rm.extract(0.5f, 0f);
        boolean negOk = Math.abs(fwd[0] + back[0]) < 1e-4f && Math.abs(fwd[3] + back[3]) < 1e-4f;
        ck("ROOT_ZERO_NEG", zeroOk && negOk, "zero=" + zero[0] + " fwd+back=" + (fwd[0] + back[0]));

        boolean drives = rm.drivesTranslation() && rm.drivesRotation();          // dash：位移+朝向都有
        RootMotion rmTurn = new RootMotion(turnOnlyClip());
        boolean turnOnly = !rmTurn.drivesTranslation() && rmTurn.drivesRotation(); // 只有 root 旋转
        RootMotion rmArm = new RootMotion(armOnlyClip());
        boolean noRoot = !rmArm.drivesTranslation() && !rmArm.drivesRotation();    // 无 root 轨道 → 都不驱动
        ck("ROOT_DRIVES", drives && turnOnly && noRoot,
                "dash=" + drives + " turnOnly=" + turnOnly + " armOnly=" + noRoot);

        // ---------- 2) BLENDSPACE ----------
        Joint.Skeleton s1 = buildSkel();   // 4 个样本：网格 2x2
        Clip cIdle = new Clip("idle", 1f, true, new Clip.Track("arm", new Clip.Key[]{ new Clip.Key(0f, 0f,0f,0f), new Clip.Key(1f, 0f,0f,0f) }));
        Clip cWalk = new Clip("walk", 1f, true, new Clip.Track("arm", new Clip.Key[]{ new Clip.Key(0f, 30f,0f,0f), new Clip.Key(1f, 30f,0f,0f) }));
        Clip cRun  = new Clip("run",  1f, true, new Clip.Track("arm", new Clip.Key[]{ new Clip.Key(0f, 60f,0f,0f), new Clip.Key(1f, 60f,0f,0f) }));
        Clip cJog  = new Clip("jog",  1f, true, new Clip.Track("arm", new Clip.Key[]{ new Clip.Key(0f, 90f,0f,0f), new Clip.Key(1f, 90f,0f,0f) }));
        BlendSpace bs = new BlendSpace(new BlendSpace.Sample[]{
                new BlendSpace.Sample("idle", 0f, 0f, cIdle),
                new BlendSpace.Sample("walk", 1f, 0f, cWalk),
                new BlendSpace.Sample("run",  0f, 1f, cRun),
                new BlendSpace.Sample("jog",  1f, 1f, cJog),
        });
        ck("BS_TRI", bs.triangleCount() >= 2, "tris=" + bs.triangleCount());

        // 顶点采样 = 独权
        float[] wVertex = bs.weightsAt(0f, 0f);
        boolean vertexOk = Math.abs(wVertex[0] - 1f) < 1e-4f;
        ck("BS_VERTEX", vertexOk, "w[0]=" + wVertex[0]);

        // 权重和恒为 1（含边界外）
        boolean sumOk = true;
        float[][] probes = {{0.5f,0.5f},{0f,0.5f},{1f,1f},{2f,2f},{-1f,-1f},{0.25f,0.75f}};
        for (float[] p : probes) {
            float[] w = bs.weightsAt(p[0], p[1]);
            float s = 0f; for (float x : w) s += x;
            if (Math.abs(s - 1f) > 1e-4f) { sumOk = false; break; }
        }
        ck("BS_SUM1", sumOk, "weight sums");

        // 格心（0.5,0.5）：两三角形共享对角线，权重落在三顶点而非四点（重心语义）
        float[] wMid = bs.weightsAt(0.5f, 0.5f);
        int nonzero = 0; for (float x : wMid) if (x > 1e-4f) nonzero++;
        boolean midOk = nonzero == 3;   // 落在对角线上，恰好 3 个非零（或 2 个，见下容差）
        ck("BS_MID_BARY", midOk || nonzero == 2, "nonzero=" + nonzero);

        // 混合姿态：格心 (0.5,0.5) 权重 = idle 0.5 + jog 0.5，两顶点 arm.rx 分别为 0 与 90
        // （Clip.Key(t, rx, ry, rz) —— 旋转写在 rot[0]）
        Joint.Skeleton bsk = buildSkel();
        bs.blendInto(0.5f, 0.5f, bsk);
        float armX = bsk.joint("arm").localRot[0];
        boolean blendOk = Math.abs(armX - 45f) < 1e-3f;   // 0*0.5 + 90*0.5
        ck("BS_BLEND", blendOk, "armX@mid=" + armX + " (want 45)");

        // 顶点独权：采样 walk(1,0) → arm.rx=30
        Joint.Skeleton bsk2 = buildSkel();
        bs.blendInto(1f, 0f, bsk2);
        boolean vertexBlend = Math.abs(bsk2.joint("arm").localRot[0] - 30f) < 1e-3f;
        ck("BS_VERTEX_BLEND", vertexBlend, "armX@walk=" + bsk2.joint("arm").localRot[0]);

        // ---------- 3) BONE MASK ----------
        Joint.Skeleton mk = buildSkel();
        BoneMask.Mask upper = BoneMask.Mask.upperBody(mk, "spine", 1f);
        boolean maskProp = Math.abs(upper.weight("spine") - 1f) < 1e-6f
                && Math.abs(upper.weight("head") - 1f) < 1e-6f
                && Math.abs(upper.weight("arm") - 1f) < 1e-6f
                && Math.abs(upper.weight("leg") - 0f) < 1e-6f
                && Math.abs(upper.weight("root") - 0f) < 1e-6f;
        ck("MASK_UPPER", maskProp, "spine=" + upper.weight("spine") + " leg=" + upper.weight("leg"));

        BoneMask.Mask falloff = new BoneMask.Mask(mk).subtree("spine", 1f, 0.5f);
        boolean fallOk = Math.abs(falloff.weight("spine") - 1f) < 1e-6f
                && Math.abs(falloff.weight("head") - 0.5f) < 1e-6f
                && Math.abs(falloff.weight("arm") - 0.5f) < 1e-6f;
        ck("MASK_FALLOFF", fallOk, "head=" + falloff.weight("head"));

        // blendInto：权重 0 全 base、权重 1 全 overlay、0.5 中值
        Joint.Skeleton base = buildSkel();
        Joint.Skeleton overlay = buildSkel();
        base.joint("arm").localRot[1] = 0f;
        overlay.joint("arm").localRot[1] = 100f;
        base.joint("leg").localRot[1] = 10f;
        overlay.joint("leg").localRot[1] = 200f;
        BoneMask.Mask m05 = new BoneMask.Mask(mk).subtree("spine", 0.5f, 1f);
        BoneMask.blendInto(base, overlay, m05);
        float arm05 = base.joint("arm").localRot[1];   // 0 + (100-0)*0.5 = 50
        float leg05 = base.joint("leg").localRot[1];   // leg 不在 mask → 保持 10
        boolean blendMaskOk = Math.abs(arm05 - 50f) < 1e-3f && Math.abs(leg05 - 10f) < 1e-3f;
        ck("MASK_BLEND", blendMaskOk, "arm=" + arm05 + " leg=" + leg05);

        // 权重 1：全 overlay
        Joint.Skeleton base2 = buildSkel();
        base2.joint("arm").localRot[1] = 0f;
        BoneMask.blendInto(base2, overlay, BoneMask.Mask.upperBody(mk, "spine", 1f));
        boolean full1 = Math.abs(base2.joint("arm").localRot[1] - 100f) < 1e-3f;
        ck("MASK_FULL", full1, "arm=" + base2.joint("arm").localRot[1]);

        // ---------- 4) 确定性 ----------
        boolean det = true;
        for (int i = 0; i < 5; i++) {
            float[] p = {i * 0.2f, 1f - i * 0.2f};
            float[] w1 = bs.weightsAt(p[0], p[1]);
            float[] w2 = bs.weightsAt(p[0], p[1]);
            for (int k = 0; k < w1.length; k++) if (w1[k] != w2[k]) { det = false; break; }
            float[] r1 = rm.extract(0f, p[0] + 0.1f), r2 = rm.extract(0f, p[0] + 0.1f);
            for (int k = 0; k < 4; k++) if (r1[k] != r2[k]) { det = false; break; }
        }
        ck("DET", det, "same-input-bit-identical");

        // ---------- 5) 零漂移：动作层不触碰 World ----------
        Simulation sim = new Simulation(20260914L, 64, 40, 64);
        for (int i = 0; i < 50; i++) sim.world.tick();
        long h1 = sim.world.hashState();
        // 疯狂跑一遍动作层运算
        Joint.Skeleton zs = buildSkel();
        for (int i = 0; i < 50; i++) {
            bs.blendInto(i * 0.02f, 1f - i * 0.02f, zs);
            rm.extract(0f, i * 0.02f);
            BoneMask.blendInto(zs, buildSkel(), BoneMask.Mask.upperBody(zs, "spine", 0.5f));
        }
        long h2 = sim.world.hashState();
        ck("ZERO_DRIFT", h1 == h2, "hash " + Long.toHexString(h1) + " vs " + Long.toHexString(h2));

        System.out.println(fails == 0 ? "ANIMLAYERS PASS" : ("ANIMLAYERS FAIL (" + fails + ")"));
        if (fails > 0) System.exit(1);
    }
}
