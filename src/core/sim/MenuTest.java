package core.sim;

/**
 * 菜单 / 暂停 / 设置 门禁（C5，第 13 道）。
 *
 * <p>为什么菜单层也值得一道门禁：菜单逻辑若长在 {@code Game} 的 GLFW 回调里，就<b>只能靠肉眼验收</b>
 * ——而本沙箱无显示器。抽成 {@link MenuModel} 后，导航/层级/钳制/二次确认都能 headless 断言。
 *
 * <p>更关键的是<b>暂停的核心不变量</b>：暂停被定义为「丢弃 tick」。本门禁证明
 * 「中途任意暂停/恢复」与「从未暂停」跑同样多次 tick 后 {@link core.world.World#hashState()}
 * <b>逐字节一致</b> —— 即暂停<b>不会</b>在确定性内核里留下任何痕迹。否则一旦暂停扰动了 RNG 流，
 * 存档/回放/多人同步都会静默错位。
 *
 * <p>断言四类：
 * <ol>
 *   <li><b>状态机</b>：开→MAIN；上下环绕；SETTINGS/CONTROLS 进出落点稳定；MAIN 上返回=继续。</li>
 *   <li><b>QUIT 二次确认</b>：默认光标落在 CANCEL（防一击误退）；YES 才返回 QUIT 动作。</li>
 *   <li><b>设置钳制</b>：FOV∈[50,100] / SENS∈[5,40] / VIEWDIST∈[100,800] / VOLUME∈[0,100] 步进与边界；
 *       HUD / SFX / SHADOWS 等布尔取反；ECLIPSE / PRESET 档位环绕；仅末行 BACK 不可编辑；
 *       adjust(0)/非设置页 adjust 为 no-op。</li>
 *   <li><b>暂停透明性 + 零漂移</b>：暂停丢 tick 后指纹与基线一致；暂停中世界冻结；
 *       菜单导航（含反复开关/调参）不触碰世界状态。</li>
 * </ol>
 *
 * 运行：java -cp out core.sim.MenuTest
 */
public final class MenuTest {

    private static final int SX = 64, SY = 40, SZ = 64;
    private static final long SEED = 20260910L;
    private static final int T = 300;

    /** 跑 {@code ticks} 次有效 tick 的终态指纹；{@code pauseEvery>0} 时每跑满该数就插入 3 次“暂停 tick”。 */
    private static long hashAfter(int ticks, int pauseEvery) {
        Simulation s = new Simulation(SEED, SX, SY, SZ);
        int done = 0;
        while (done < ticks) {
            if (pauseEvery > 0) {
                s.world.paused = true;
                for (int k = 0; k < 3; k++) s.world.tick();   // 必须全部被丢弃（no-op）
            }
            s.world.paused = false;
            int batch = pauseEvery > 0 ? pauseEvery : ticks;
            for (int k = 0; k < batch && done < ticks; k++) { s.world.tick(); done++; }
        }
        s.world.paused = false;
        return s.world.hashState();
    }

    public static void main(String[] args) {
        StringBuilder ev = new StringBuilder();

        // ---------- 1) 状态机：页 / 条目 / 环绕 / 层级回退 ----------
        MenuModel m = new MenuModel();
        boolean closedInit = !m.isOpen() && m.page() == MenuModel.Page.CLOSED && m.itemCount() == 0;
        m.open();
        boolean openMain = m.page() == MenuModel.Page.MAIN && m.selected() == 0
                && m.itemCount() == 5 && "PAUSED".equals(m.title());
        m.up();
        boolean wrapUp = m.selected() == 4;          // 0 上翻 → 末项（QUIT，第 5 项）
        m.down();
        boolean wrapDown = m.selected() == 0;        // 末项下翻 → 0
        boolean stateOk = closedInit && openMain && wrapUp && wrapDown;

        m.open();
        m.down();
        boolean toSettings = m.activate() == MenuModel.Action.NONE
                && m.page() == MenuModel.Page.SETTINGS && m.selected() == 0 && m.itemCount() == 12;
        boolean backSettings = m.back() == MenuModel.Action.BACK
                && m.page() == MenuModel.Page.MAIN && m.selected() == 1;
        m.down();
        boolean toControls = m.activate() == MenuModel.Action.NONE
                && m.page() == MenuModel.Page.CONTROLS && m.itemCount() == MenuModel.CONTROLS.length + 1;
        boolean backControls = m.back() == MenuModel.Action.BACK
                && m.page() == MenuModel.Page.MAIN && m.selected() == 2;
        boolean resumeMain = m.back() == MenuModel.Action.RESUME && !m.isOpen() && m.itemCount() == 0;
        boolean navOk = toSettings && backSettings && toControls && backControls && resumeMain;
        ev.append("STATE init=").append(closedInit).append(" openMain=").append(openMain)
          .append(" wrap=").append(wrapUp && wrapDown)
          .append(" | NAV set=").append(toSettings).append(" backS=").append(backSettings)
          .append(" ctrl=").append(toControls).append(" backC=").append(backControls)
          .append(" resume=").append(resumeMain);

        // ---------- 2) QUIT 二次确认（默认落 CANCEL） ----------
        m.open();
        m.up();                                       // sel 0 → 4 (QUIT，第 5 项)
        boolean toConfirm = m.activate() == MenuModel.Action.NONE
                && m.page() == MenuModel.Page.QUIT_CONFIRM && m.selected() == 1
                && "CANCEL".equals(m.label(1));
        boolean cancelOk = m.activate() == MenuModel.Action.BACK
                && m.page() == MenuModel.Page.MAIN && m.selected() == 4;   // 退回 MAIN 的 QUIT 项（第 5 项）
        boolean reConfirm = m.activate() == MenuModel.Action.NONE && m.page() == MenuModel.Page.QUIT_CONFIRM;
        m.up();                                       // CANCEL → YES
        boolean quitOk = m.activate() == MenuModel.Action.QUIT
                && m.page() == MenuModel.Page.QUIT_CONFIRM;   // 菜单不自关：副作用由渲染层执行
        boolean quitFlowOk = toConfirm && cancelOk && reConfirm && quitOk;
        ev.append(" | QUIT confirmDefaultCancel=").append(toConfirm).append(" cancel=").append(cancelOk)
          .append(" reConfirm=").append(reConfirm).append(" yes=").append(quitOk);

        // ---------- 3) 设置项钳制 / 步进 / 可编辑性 ----------
        MenuModel s = new MenuModel();
        s.open(); s.down(); s.activate();             // → SETTINGS
        boolean inSettings = s.page() == MenuModel.Page.SETTINGS && s.selected() == 0;
        boolean editable = s.editable(0) && s.editable(3) && s.editable(4) && s.editable(5)
                && s.editable(6) && s.editable(7) && s.editable(8) && s.editable(9) && s.editable(10)
                && "ECLIPSE".equals(s.label(9)) && "PRESET".equals(s.label(10))
                && !s.editable(11) && "BACK".equals(s.label(11));   // 6..10 可编辑；11=BACK 不可编辑
        int fovDefault = s.fov;
        for (int i = 0; i < 20; i++) s.adjust(1);
        boolean fovHi = s.fov == 100;
        for (int i = 0; i < 40; i++) s.adjust(-1);
        boolean fovLo = s.fov == 50;
        for (int i = 0; i < 4; i++) s.adjust(1);      // 50 → 70（步进 5）
        boolean fovBack = s.fov == 70 && fovDefault == 70;
        s.down();                                     // MOUSE SENS
        for (int i = 0; i < 20; i++) s.adjust(1);
        boolean sensHi = s.mouseSensPct == 40 && Math.abs(s.mouseSens() - 0.40f) < 1e-6f;
        for (int i = 0; i < 40; i++) s.adjust(-1);
        boolean sensLo = s.mouseSensPct == 5;
        s.down();                                     // VIEW DIST
        for (int i = 0; i < 20; i++) s.adjust(1);
        boolean vdHi = s.viewDist == 800;
        for (int i = 0; i < 40; i++) s.adjust(-1);
        boolean vdLo = s.viewDist == 100;
        s.down();                                     // HUD
        boolean hud0 = s.showHud;
        s.adjust(1);
        boolean hudFlip = s.showHud != hud0;
        s.adjust(0);
        boolean adjustZeroNoop = s.showHud != hud0;
        s.down();                                     // VOLUME（C4）
        int volDefault = s.volume;
        for (int i = 0; i < 40; i++) s.adjust(1);
        boolean volHi = s.volume == 100 && Math.abs(s.volumeF() - 1f) < 1e-6f;
        for (int i = 0; i < 60; i++) s.adjust(-1);
        boolean volLo = s.volume == 0 && s.volumeF() == 0f;
        for (int i = 0; i < 14; i++) s.adjust(1);     // 0 → 70（步进 5，回到默认）
        boolean volBack = s.volume == 70 && volDefault == 70;
        s.down();                                     // SFX（C4）
        boolean sfx0 = s.sfxOn;
        s.adjust(1);
        boolean sfxFlip = s.sfxOn != sfx0;
        s.down();                                     // DAY LENGTH（新项，QA 2026-09-13）
        boolean dayLenEditable = s.editable(6) && s.value(6).contains("MIN");
        int dayLen0 = core.world.DayCycle.DAY_LEN;
        s.adjust(1);
        boolean dayLenStep = core.world.DayCycle.DAY_LEN != dayLen0;
        s.adjust(-1);
        boolean dayLenBack = core.world.DayCycle.DAY_LEN == dayLen0;
        s.down();                                     // GAME SPEED 行（新项，索引 7）
        boolean gameSpeedEditable = s.editable(7) && s.value(7).contains("x");
        s.adjust(1);                                  // 1x -> 2x
        boolean gameSpeedStep = Math.abs(s.gameSpeed() - 2.0f) < 1e-6f;
        s.adjust(1); s.adjust(1);                      // 2x -> 4x -> 0.5x（循环回绕）
        boolean gameSpeedWrap = Math.abs(s.gameSpeed() - 0.5f) < 1e-6f;
        s.adjust(-1); s.adjust(-1); s.adjust(-1);      // 0.5x -> 4x -> 2x -> 1x（回到默认）
        boolean gameSpeedBack = Math.abs(s.gameSpeed() - 1.0f) < 1e-6f;
        s.down();                                     // SHADOWS 行（新项，索引 8）
        boolean shadowsEditable = s.editable(8) && s.value(8).contains("ON");
        boolean shadow0 = s.shadowsOn;
        s.adjust(1);
        boolean shadowsFlip = s.shadowsOn != shadow0;
        s.adjust(1);
        boolean shadowsBack = s.shadowsOn == shadow0;
        s.down();                                     // ECLIPSE 行（新项，索引 9）
        boolean eclipseEditable = s.editable(9) && "NORMAL".equals(s.eclipseRateName());
        core.world.Celestial.Rate er0 = core.world.Celestial.rate;
        s.adjust(1);
        boolean eclipseStep = core.world.Celestial.rate != er0;          // 真的切了档（NORMAL -> OFTEN）
        s.adjust(-1);
        boolean eclipseBack = core.world.Celestial.rate == er0;          // 切回原档（OFTEN -> NORMAL）
        s.down();                                     // PRESET 行（索引 10）：内容层玩法预设
        s.setPresets(new String[]{"breathing_world", "peaceful_valley", "mythic_sandbox"},
                     new String[]{"Breathing World", "Peaceful Valley", "Mythic Sandbox"});
        boolean presetEditable = s.editable(10) && "breathing_world".equals(s.presetId())
                && s.value(10).contains("Breathing World");
        s.selectPreset("no_such_preset");             // 不在列表里 → 下标不变（内容变少时不越界）
        boolean presetUnknownKeeps = "breathing_world".equals(s.presetId());
        s.cyclePreset(1);
        boolean presetStep = "peaceful_valley".equals(s.presetId());
        s.cyclePreset(-1);
        boolean presetBack = "breathing_world".equals(s.presetId());
        s.cyclePreset(-1);
        boolean presetWrap = "mythic_sandbox".equals(s.presetId());     // 从首项往回 = 环绕到末项
        s.cyclePreset(1);
        boolean presetWrapBack = "breathing_world".equals(s.presetId());
        MenuModel selEmpty = new MenuModel();          // 无内容（assets 缺失）→ 出厂配置，且切换是 no-op
        selEmpty.setPresets(new String[0], new String[0]);
        selEmpty.cyclePreset(1);
        boolean presetEmptyOk = selEmpty.presetId() == null && "DEFAULT".equals(selEmpty.presetName());
        s.down();                                     // BACK 行（索引 11）：不可编辑
        boolean backRowNotEditable = !s.editable(11) && s.value(11).isEmpty();
        s.adjust(1);
        boolean adjustBackNoop = Math.abs(s.gameSpeed() - 1.0f) < 1e-6f;  // BACK 行未被改动（gameSpeed 仍是 1x）
        boolean valueTextOk = s.value(0).startsWith("< ") && s.value(3).contains("OFF")
                && s.value(5).contains("OFF") && s.value(11).isEmpty() && s.value(6).contains("MIN")
                && s.value(7).contains("x") && s.value(8).contains("ON") && s.value(9).contains("NORMAL")
                && s.value(10).contains("Breathing World");
        MenuModel t = new MenuModel();                // 非设置页 adjust 为 no-op
        t.open();
        int fovBefore = t.fov;
        t.adjust(1);
        boolean adjustOutsideNoop = t.fov == fovBefore;
        boolean settingsOk = inSettings && editable && fovHi && fovLo && fovBack && sensHi && sensLo
                && vdHi && vdLo && hudFlip && adjustZeroNoop && volHi && volLo && volBack && sfxFlip
                && dayLenEditable && dayLenStep && dayLenBack
                && gameSpeedEditable && gameSpeedStep && gameSpeedWrap && gameSpeedBack
                && shadowsEditable && shadowsFlip && shadowsBack
                && eclipseEditable && eclipseStep && eclipseBack
                && presetEditable && presetUnknownKeeps && presetStep && presetBack
                && presetWrap && presetWrapBack && presetEmptyOk
                && backRowNotEditable && adjustBackNoop
                && valueTextOk && adjustOutsideNoop;
        ev.append(" | SETTINGS hi/lo FOV=").append(fovHi).append("/").append(fovLo)
          .append(" SENS=").append(sensHi).append("/").append(sensLo)
          .append(" VD=").append(vdHi).append("/").append(vdLo)
          .append(" VOL=").append(volHi).append("/").append(volLo)
          .append(" hudFlip=").append(hudFlip).append(" sfxFlip=").append(sfxFlip)
          .append(" editable=").append(editable)
          .append(" preset=").append(presetEditable && presetStep && presetBack && presetWrap && presetWrapBack)
          .append(" noop=").append(adjustZeroNoop && adjustBackNoop && adjustOutsideNoop);

        // ---------- 4) 暂停透明性：丢 tick 不改终态 ----------
        long base = hashAfter(T, 0);
        long p7 = hashAfter(T, 7);
        long p3 = hashAfter(T, 3);
        long p1 = hashAfter(T, 1);
        boolean transparent = base == p7 && base == p3 && base == p1;
        ev.append(" | PAUSE-TRANSPARENT ").append(Long.toHexString(base))
          .append("==").append(Long.toHexString(p7)).append("/").append(Long.toHexString(p3))
          .append("/").append(Long.toHexString(p1));

        // ---------- 5) 暂停中世界冻结 + 菜单零漂移 ----------
        Simulation sim = new Simulation(SEED, SX, SY, SZ);
        for (int i = 0; i < T; i++) sim.world.tick();
        long h1 = sim.world.hashState();
        sim.world.paused = true;
        for (int i = 0; i < 10; i++) sim.world.tick();
        long h2 = sim.world.hashState();
        boolean frozenWhilePaused = h1 == h2;
        sim.world.paused = false;
        sim.world.tick();
        boolean movesAfterResume = sim.world.hashState() != h2;

        MenuModel mm = new MenuModel();
        long h3 = sim.world.hashState();
        for (int i = 0; i < 60; i++) {                 // 反复开关 + 导航 + 调参
            mm.open(); mm.down(); mm.up(); mm.activate(); mm.adjust(1); mm.adjust(-1); mm.back();
        }
        long h4 = sim.world.hashState();
        boolean menuNoDrift = h3 == h4;
        boolean pauseOk = frozenWhilePaused && movesAfterResume && menuNoDrift;
        ev.append(" | FROZEN hashEq=").append(frozenWhilePaused).append(" resumeAdvances=").append(movesAfterResume)
          .append(" menuNoDrift=").append(menuNoDrift);

        boolean pass = stateOk && navOk && quitFlowOk && settingsOk && transparent && pauseOk;

        System.out.println("MENU  " + ev);
        System.out.println("PAUSE  semantics=DROP-TICK  simTransparent=" + transparent
                + "  fov=" + sim.world.paused);
        System.out.println(pass ? "MENU PASS" : "MENU FAIL");
        if (!pass) System.exit(1);
    }
}
