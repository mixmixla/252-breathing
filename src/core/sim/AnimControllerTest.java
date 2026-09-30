package core.sim;

import core.anim.AnimController;
import core.anim.Clip;

/**
 * 动作打磨门禁（QC 2026-09-13 · C 批 · 战斗动作语义）。
 *
 * <p>断言的是<b>性质</b>而非"能跑"：
 * ① 阶段时序——前摇→判定窗→取消窗→后摇 按时间正确推进；
 * ② 判定帧唯一性——同一次攻击跨过事件帧只发一次伤害（第二次返回 NO_HIT）；
 * ③ 取消窗时序——仅取消窗内 input() 成功接段；前摇/判定窗/硬直内 input() 被忽略；
 * ④ 连招链——连续取消窗 input 推进 comboStep；非取消窗 input 不推进；
 * ⑤ 确定性——同 (combo, dt 序列) 两次推进 phase/comboStep 逐位一致（纯函数、零 RNG）。
 */
public final class AnimControllerTest {

    // 攻击片段：长 1.0s，事件帧（判定帧）在 t=0.3
    // 阶段边界：startupEnd=0.2 / cancelStart=0.6 →
    //   [0,0.2) STARTUP  [0.2,0.6) ACTIVE  [0.6,1.0) CANCEL  [1.0,+∞) RECOVER
    private static Clip attack() {
        Clip c = new Clip("swing", 1.0f, false,
                new Clip.Track("arm", new Clip.Key[]{
                        new Clip.Key(0f, 0f, 0f, 0f),
                        new Clip.Key(1.0f, 90f, 0f, 0f)
                }));
        c.events(new float[]{0.3f}, new int[]{7});
        return c;
    }

    private static Clip comboB() {
        Clip c = new Clip("swing2", 1.0f, false,
                new Clip.Track("arm", new Clip.Key[]{
                        new Clip.Key(0f, 90f, 0f, 0f),
                        new Clip.Key(1.0f, -90f, 0f, 0f)
                }));
        c.events(new float[]{0.3f}, new int[]{8});
        return c;
    }

    private static AnimController fresh() {
        return new AnimController(new Clip[]{attack(), comboB()}, 0.2f, 0.6f);
    }

    public static void main(String[] args) {
        int fails = 0;

        // ---- 1. 阶段时序 ----
        AnimController a = fresh();
        // 推进到 t=0.10 → STARTUP
        a.update(0.10f);
        boolean pStartup = a.phase() == AnimController.PHASE_STARTUP;
        // 到 t=0.40 → ACTIVE（跨过 0.2）
        a.update(0.30f);
        boolean pActive = a.phase() == AnimController.PHASE_ACTIVE;
        // 到 t=0.70 → CANCEL（跨过 0.6）
        a.update(0.30f);
        boolean pCancel = a.phase() == AnimController.PHASE_CANCEL;
        // 到 t=1.10 → RECOVER（跨过 1.0）
        a.update(0.40f);
        boolean pRecover = a.phase() == AnimController.PHASE_RECOVER;
        boolean phaseSeq = pStartup && pActive && pCancel && pRecover;
        System.out.println("PHASE_SEQ " + (phaseSeq ? "PASS" : "FAIL")
                + "  startup=" + pStartup + " active=" + pActive + " cancel=" + pCancel + " recover=" + pRecover);
        if (!phaseSeq) fails++;

        // ---- 2. 判定帧唯一性 ----
        AnimController b = fresh();
        int first = -1, second = -1;
        for (int i = 0; i < 10; i++) {            // dt=0.05，0.3 在 i=6（0.25→0.30）跨过
            int ev = b.update(0.05f);
            if (ev != AnimController.NO_HIT && first == -1) first = ev;
            else if (ev != AnimController.NO_HIT && first != -1 && second == -1) second = ev;
        }
        boolean hitOnce = first == 7 && second == AnimController.NO_HIT;
        System.out.println("HIT_ONCE  " + (hitOnce ? "PASS" : "FAIL")
                + "  first=" + first + " second=" + second + " (want 7 / -1)");
        if (!hitOnce) fails++;

        // ---- 3. 取消窗时序 ----
        AnimController c = fresh();
        // 推到 CANCEL 窗（t≈0.65）
        c.update(0.65f);
        boolean inCancel = c.phase() == AnimController.PHASE_CANCEL;
        boolean okInCancel = inCancel && c.input();      // 取消窗内应成功
        // 推过第 2 段全长（comboB 长 1.0s）到 RECOVER 硬直（t>1.0），再 input 应被忽略
        c.update(1.1f);
        boolean inRecover = c.phase() == AnimController.PHASE_RECOVER;
        boolean ignoredInRecover = inRecover && !c.input();
        // 回到 STARTUP（restart 后 t=0），input 应被忽略
        AnimController d = fresh();
        d.update(0.10f);   // STARTUP
        boolean ignoredInStartup = d.phase() == AnimController.PHASE_STARTUP && !d.input();
        boolean cancelTiming = okInCancel && ignoredInRecover && ignoredInStartup;
        System.out.println("CANCEL_WIN " + (cancelTiming ? "PASS" : "FAIL")
                + "  inCancel=" + inCancel + " ok=" + okInCancel
                + " recoverIgn=" + ignoredInRecover + " startupIgn=" + ignoredInStartup);
        if (!cancelTiming) fails++;

        // ---- 4. 连招链 ----
        AnimController e = fresh();
        e.update(0.65f);                       // 进入 CANCEL
        boolean c1 = e.input();                // 接第 2 段
        int stepAfter1 = e.comboStep();
        // 推过第 2 段到其 CANCEL 窗（第 2 段长 1.0，cancelStart=0.6 → t≈0.65）
        e.update(0.65f);
        boolean c2 = e.input();                // 接回第 1 段（combo 表环绕）
        int stepAfter2 = e.comboStep();
        // 非取消窗（STARTUP）不应推进
        AnimController f = fresh();
        f.update(0.10f);                       // STARTUP
        int before = f.comboStep();
        f.input();                             // 被忽略
        int after = f.comboStep();
        boolean combo = c1 && c2 && stepAfter1 == 1 && stepAfter2 == 2 && before == after;
        System.out.println("COMBO_CHAIN " + (combo ? "PASS" : "FAIL")
                + "  step=" + stepAfter1 + "->" + stepAfter2 + " noskip=" + (before == after));
        if (!combo) fails++;

        // ---- 5. 确定性 ----
        // ---- 4.5 totalLen 访问器（渲染层取挥砍进度用）----
        AnimController h = fresh();
        float tl = h.totalLen();
        boolean totalLenOk = tl > 0f && Math.abs(tl - attack().length) < 1e-5f;
        System.out.println("TOTAL_LEN " + (totalLenOk ? "PASS" : "FAIL") + "  totalLen=" + tl);
        if (!totalLenOk) fails++;

        AnimController g1 = fresh(), g2 = fresh();
        boolean detOk = true;
        for (int i = 0; i < 30; i++) {
            float dt = 0.05f;
            int e1 = g1.update(dt), e2 = g2.update(dt);
            if (g1.phase() != g2.phase() || g1.comboStep() != g2.comboStep() || e1 != e2) { detOk = false; break; }
        }
        System.out.println("DET       " + (detOk ? "PASS" : "FAIL") + "  same-input-same-state");
        if (!detOk) fails++;

        System.out.println(fails == 0 ? "ANIMCTL PASS" : ("ANIMCTL FAIL (" + fails + ")"));
        if (fails > 0) System.exit(1);
    }
}
