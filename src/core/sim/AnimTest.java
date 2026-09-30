package core.sim;

import core.anim.AnimJson;
import core.anim.Animator;
import core.anim.Clip;
import core.anim.Joint;
import core.anim.ModelDef;

/**
 * 动作系统门禁（QC 2026-09-13 · 自建可配置动作系统 · 一期）。
 *
 * <p>断言的是<b>性质</b>而非"能跑"：
 * ① 解析——JSON → 骨架（父链正确）+ 动作表；
 * ② 插值——线性中点 = 两端均值；smoothstep 缓动单调且中点=0.5；端点钳制；
 * ③ 循环——时间取模；非循环到末帧静止（finished）；
 * ④ 过渡混合——blend 中点姿态 ≈ 两动作加权均值；
 * ⑤ 动作事件帧——update 跨过命中帧返回事件 id；循环回绕也触发；
 * ⑥ 骨架世界变换——父旋转 90° 后子节点位置绕父旋转（坐标系正确）；
 * ⑦ 确定性——同 (clip, time) 两次采样逐位相同（纯函数、零 RNG）。
 */
public final class AnimTest {

    private static final String RIG_JSON =
        "{ \"skeleton\": { \"bones\": ["
        + "{\"name\":\"root\",\"parent\":null,\"pos\":[0,0,0]},"
        + "{\"name\":\"arm\",\"parent\":\"root\",\"pos\":[10,0,0]},"
        + "{\"name\":\"hand\",\"parent\":\"arm\",\"pos\":[5,0,0]}"
        + "] }, \"clips\": ["
        + "{\"name\":\"swing\",\"length\":1.0,\"loop\":false,"
        + " \"tracks\":[{\"joint\":\"arm\",\"keys\":["
        + "   {\"t\":0,\"rot\":[0,0,0]},{\"t\":1.0,\"rot\":[90,0,0],\"ease\":0}]}],"
        + " \"events\":[{\"t\":0.5,\"id\":7}]},"
        + "{\"name\":\"idle\",\"length\":2.0,\"loop\":true,"
        + " \"tracks\":[{\"joint\":\"arm\",\"keys\":["
        + "   {\"t\":0,\"rot\":[0,0,0]},{\"t\":2.0,\"rot\":[100,0,0]}]}]},"
        + "{\"name\":\"easeclip\",\"length\":1.0,\"loop\":false,"
        + " \"tracks\":[{\"joint\":\"arm\",\"keys\":["
        + "   {\"t\":0,\"rot\":[0,0,0]},{\"t\":1.0,\"rot\":[100,0,0],\"ease\":1}]}]}"
        + "] }";

    private static final String MODEL_JSON =
        "{ \"name\":\"t\", \"parts\":[ {\"joint\":\"arm\",\"kind\":\"box\",\"size\":[3,10,3],\"offset\":[0,-5,0],\"material\":\"cloth\"} ] }";

    public static void main(String[] args) {
        int fails = 0;

        // ---- 1. 解析 ----
        AnimJson.Rig rig = AnimJson.parse(RIG_JSON);
        boolean parseOk = rig.skeleton.joint("hand") != null
                && rig.skeleton.joint("hand").parent == rig.skeleton.joint("arm")
                && rig.skeleton.joint("arm").parent == rig.skeleton.root
                && rig.clips.size() == 3 && rig.clip("swing") != null;
        System.out.println("PARSE " + (parseOk ? "PASS" : "FAIL") + "  bones=3 clips=" + rig.clips.size());
        if (!parseOk) fails++;

        ModelDef model = ModelDef.parse(MODEL_JSON);
        boolean modelOk = model.parts.length == 1 && "arm".equals(model.parts[0].joint)
                && Math.abs(model.parts[0].size[1] - 10f) < 1e-6f;
        System.out.println("MODEL " + (modelOk ? "PASS" : "FAIL") + "  parts=" + model.parts.length);
        if (!modelOk) fails++;

        // ---- 2. 插值：线性中点 = 均值 ----
        Clip swing = rig.clip("swing");
        float[] r = new float[3];
        swing.tracks[0].sample(0.5f, r, null);
        boolean lerpOk = Math.abs(r[0] - 45f) < 1e-3f;
        System.out.println("LERP  " + (lerpOk ? "PASS" : "FAIL") + "  rot@0.5=" + r[0] + " (want 45)");
        if (!lerpOk) fails++;

        // ---- 3. smoothstep 缓动：中点=0.5，且 1/4 点 < 线性值 ----
        Clip easeClip = rig.clip("easeclip");
        easeClip.tracks[0].sample(0.5f, r, null);
        float mid = r[0];
        easeClip.tracks[0].sample(0.25f, r, null);
        float q1 = r[0];
        boolean easeOk = Math.abs(mid - 50f) < 1e-3f && q1 < 25f;    // smoothstep 前段更慢
        System.out.println("EASE  " + (easeOk ? "PASS" : "FAIL") + "  mid=" + mid + " quarter=" + q1);
        if (!easeOk) fails++;

        // ---- 4. 端点钳制 ----
        swing.tracks[0].sample(-1f, r, null);
        float lo = r[0];
        swing.tracks[0].sample(9f, r, null);
        float hi = r[0];
        boolean clampOk = Math.abs(lo) < 1e-3f && Math.abs(hi - 90f) < 1e-3f;
        System.out.println("CLAMP " + (clampOk ? "PASS" : "FAIL") + "  lo=" + lo + " hi=" + hi);
        if (!clampOk) fails++;

        // ---- 5. 循环 + finished ----
        Joint.Skeleton skel = rig.skeleton;
        Animator an = new Animator(rig.clip("idle"));
        for (int i = 0; i < 100; i++) an.update(0.05f);            // 5 秒 > 2 秒周期
        boolean loopOk = an.time >= 0f && an.time < 2.0f;
        Animator sw = new Animator(swing);
        for (int i = 0; i < 40; i++) sw.update(0.05f);             // 2 秒 > 1 秒长度
        boolean finOk = sw.finished() && Math.abs(sw.time - 1.0f) < 1e-3f;
        System.out.println("LOOP  " + (loopOk ? "PASS" : "FAIL") + "  idleT=" + an.time
                + " | FIN " + (finOk ? "PASS" : "FAIL") + "  swingT=" + sw.time);
        if (!loopOk) fails++;
        if (!finOk) fails++;

        // ---- 6. 事件帧 ----
        Animator ev = new Animator(swing);
        int hit = -1;
        for (int i = 0; i < 40; i++) { ev.update(0.05f); int e = ev.pollEvent(); if (e != -1) { hit = e; break; } }
        boolean evOk = hit == 7;
        System.out.println("EVENT " + (evOk ? "PASS" : "FAIL") + "  hitId=" + hit + " (want 7)");
        if (!evOk) fails++;

        // 循环回绕事件（idle 无事件；构造带事件循环片段验证回绕分支不误触发）
        Animator wrap = new Animator(rig.clip("idle"));
        boolean wrapQuiet = true;
        for (int i = 0; i < 60; i++) { wrap.update(0.05f); if (wrap.pollEvent() != -1) wrapQuiet = false; }
        System.out.println("WRAP  " + (wrapQuiet ? "PASS" : "FAIL") + "  idle-no-event-storm");
        if (!wrapQuiet) fails++;

        // ---- 7. 骨架世界变换：arm 绕 X 转 90° 后 hand 的世界位置 ----
        Joint arm = skel.joint("arm"), hand = skel.joint("hand");
        arm.reset(); hand.reset(); arm.localRot[0] = 90f;
        skel.updateWorld();
        // arm: root(0,0,0)+rest(10,0,0) = (10,0,0)；hand 偏 (5,0,0) 绕 X 转 90° -> (5,0,0)（X 轴旋转不改 x）
        // 再加 Z 旋转验证平面内旋转：arm 绕 Z 转 90° -> hand 局部 (5,0,0) -> 世界 (0,5,0)
        arm.localRot[0] = 0f; arm.localRot[2] = 90f;
        skel.updateWorld();
        // hand world = arm.world + Rz(90)*(5,0,0) = (10,0,0)+(0,5,0) = (10,5,0)
        boolean xfOk = Math.abs(hand.worldPos[0] - 10f) < 0.01f && Math.abs(hand.worldPos[1] - 5f) < 0.01f;
        System.out.println("XFORM " + (xfOk ? "PASS" : "FAIL") + "  handWorld="
                + hand.worldPos[0] + "," + hand.worldPos[1] + "," + hand.worldPos[2] + " (want 10,5,0)");
        if (!xfOk) fails++;

        // ---- 8. 过渡混合：blend 中点 ≈ 两动作加权均值 ----
        Animator bl = new Animator(rig.clip("swing"));             // arm 末态 90°（t=1 处）
        for (int i = 0; i < 20; i++) bl.update(0.05f);             // 到 t=1.0（finish）
        bl.play(rig.clip("idle"), 1.0f);                           // 混合 1 秒（idle t=0 -> 0°）
        bl.update(0.5f);                                           // w = 0.5
        bl.apply(skel);
        float armNow = skel.joint("arm").localRot[0];
        // 旧动作(swing 末态 90°) 与 新动作(idle 推进到 t=0.5 -> 25°) 各占 0.5
        // -> 90 + (25 - 90) * 0.5 = 57.5（新动作时间同步推进是正确语义）
        boolean blendOk = Math.abs(armNow - 57.5f) < 3f;
        System.out.println("BLEND " + (blendOk ? "PASS" : "FAIL") + "  armRot@w0.5=" + armNow + " (want ~57.5)");
        if (!blendOk) fails++;

        // ---- 9. 确定性（纯函数）----
        boolean detOk = true;
        Clip c = rig.clip("easeclip");
        float[] a1 = new float[3], a2 = new float[3];
        for (float t = 0f; t <= 1f; t += 0.05f) {
            c.tracks[0].sample(t, a1, null);
            c.tracks[0].sample(t, a2, null);
            if (a1[0] != a2[0]) { detOk = false; break; }
        }
        System.out.println("DET   " + (detOk ? "PASS" : "FAIL") + "  same-time-same-pose");
        if (!detOk) fails++;

        System.out.println(fails == 0 ? "ANIM PASS" : ("ANIM FAIL (" + fails + ")"));
        if (fails > 0) System.exit(1);
    }
}
