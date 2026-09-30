package core.sim;

/**
 * C5 菜单 / 暂停 / 设置 —— 纯逻辑状态机（零 GL、零资产、零世界写入）。
 *
 * <p>把「菜单」从渲染层里剥出来的理由与 {@code Weapons} 一致：一旦逻辑长在 {@code Game} 的
 * 回调里，它就<b>只能靠肉眼验收</b>（本沙箱还跑不起 GPU）。抽成纯模型后：
 *
 * <ul>
 *   <li><b>可 headless 断言</b>：导航环绕、页跳转/回退层级、数值钳制、二次确认默认项，全部能在
 *       无显示器环境用 {@code core.sim.MenuTest}（第 13 道门禁）测。</li>
 *   <li><b>与仿真完全解耦</b>：本类不持有 {@link core.world.World}、不写任何仿真状态。暂停语义由
 *       {@code World.paused} 承担（丢 tick、不改已推进序列），故整层对确定性指纹零影响。</li>
 *   <li><b>零 GL 依赖</b>：只产出字符串，渲染层用 {@code Font}（ASCII）/ {@code CjkFont}（中文提示）画。</li>
 * </ul>
 *
 * <p><b>坐标约定</b>：设置项一律以「可比较整数」保存（如 {@code mouseSensPct=15} 表示 ×0.15，
 * {@code volume=70} 表示 0.70），渲染层按需换算，避免浮点相等判断进入断言。
 *
 * <p>C4 追加 <b>VOLUME / SFX</b> 两行：音量与音效开关与其它设置同构（整数钳制 / 布尔取反），
 * 且同样只是<b>纯数据</b> —— 真正的混音在 {@code core.audio.AudioMixer}，本类不引用它。
 *
 * <p>F 批追加 <b>CHARACTER</b> 页（捏脸）：主菜单第 4 项（index 3），含 8 个子项
 * （PRESET/SKIN/HAIR/HAIR STYLE/BUILD/ACCENT/EYES/BACK）。捏脸属于<b>身份数据</b>，
 * 不进 {@link core.world.World#hashState()}，对四道零漂移门禁指纹零影响。
 */
public final class MenuModel {

    public enum Page { CLOSED, MAIN, SETTINGS, CONTROLS, QUIT_CONFIRM, CHARACTER }

    /** 激活一个条目后，渲染层需要执行的副作用（菜单本身<b>不</b>执行副作用）。 */
    public enum Action { NONE, RESUME, QUIT, BACK }

    /** 按键说明页内容（只读展示）。顺序即上屏顺序。 */
    public static final String[] CONTROLS = {
            "WASD  MOVE", "MOUSE  LOOK", "SPACE  JUMP / GLIDE", "CTRL  SPRINT",
            "SHIFT  SNEAK (EDGE-SAFE)", "Q  DODGE ROLL",
            "F  ATTACK", "R  WEAPON ART", "G  SWITCH ART", "6  BOMB", "7  IGNITE", "J  AQUA (WATER)", "L  THUNDER", "O  SWITCH SKILL", "P  CAST SKILL",
            "Z/X/C/V  SOUL-FORGE", "I  CRAFT (HAND)", "U  FLASK (HEAL / FEED)", "T  TALK", "B  CHRONICLE", "L/R CLICK  DIG / PLACE (HOLD=REPEAT)",
            "1-5  BLOCK", "E  INVENTORY", "TAB  LOCK TARGET", "K  LEARN SKILL", "M  CYCLE WEAPON",
            "R CLICK BED  SET SPAWN (+ SLEEP TO DAWN AT NIGHT)",
            "R CLICK SIGN  EDIT SIGN TEXT",
            "BACKSPACE  IN SIGN EDITOR: DELETE LAST CHAR (ENTER SAVE / ESC CANCEL)",
            "F5  FIRST / THIRD VIEW", "H  HUD TOGGLE", "N  MINIMAP TOGGLE", "F3  DEBUG PANEL", "F6  DEBUG SANDBOX (GRANT ALL)",
            "Y  CAPTURE (TAME BEAST)", "F7  FREE MYSELF (UNSTUCK)", "F8  SUMMON WARDEN (DEBUG)", "F4  PARRY", "F9 / F10  SAVE / LOAD", "F11  EXECUTE", "F12  OPEN CHEST",
            "F1  TORCH RADIUS (TEST LIGHT)", "F2  TORCH ON/OFF (TEST LIGHT)",
            "ESC  PAUSE MENU",
    };

    /** 设置页条目数（FOV / MOUSE SENS / VIEW DIST / HUD / VOLUME / SFX / DAY LENGTH / GAME SPEED / SHADOWS / ECLIPSE / PRESET / BACK）。 */
    private static final int SETTINGS_ROWS = 12;
    /** 设置页中可左右调节的行数（末行 BACK 不可编辑）。 */
    private static final int SETTINGS_EDITABLE = 11;

    private Page page = Page.CLOSED;
    private int sel = 0;

    /**
     * 捏脸预设提供方（F 批）。纯函数接口：渲染层把内容注册表包一层喂进来，
     * 本模型不持有任何内容/RNG/世界引用，保持 headless 可测。
     */
    public interface CharacterProvider {
        /** 解析预设 id → 外观（含颜色）；找不到返回 null。 */
        core.world.Appearance preset(String id);
        /** 全部预设 id（字典序，确定性）。 */
        String[] ids();
    }

    /** 捏脸编辑中的工作副本（实时预览用；提交即拷回 Player.appearance）。 */
    public final core.world.Appearance working = new core.world.Appearance();
    private CharacterProvider charProvider = null;

    /** 进入捏脸页：用玩家当前外观初始化工作副本。 */
    public void openCharacter(core.world.Appearance current, CharacterProvider p) {
        this.charProvider = p;
        this.working.copyFrom(current);
    }

    // ---- 设置（渲染层读取并即时生效；不进任何仿真状态、不进 hashState）----
    public int fov = 70;              // 50..100，步进 5（投影视野角，度）
    public int mouseSensPct = 15;     // 5..40，步进 5（鼠标灵敏度 = 该值 / 100）
    public int viewDist = 320;        // 100..800，步进 50（相机远裁剪面）
    public boolean showHud = true;    // HUD 显隐
    public int volume = 70;           // 0..100，步进 5（C4 主音量 = 该值 / 100）
    public boolean sfxOn = true;    // C4 音效总开关
    /** QA 2026-09-15：实体接触影总开关。关闭可彻底消除夜间地面/方块边缘的黑色影块。 */
    public boolean shadowsOn = true;
    /** 时间倍率档位：0.5x / 1x / 2x / 4x。主循环 acc += f * gameSpeed()，tick 步长仍是 0.05s，
     *  只改变真实时间->模拟的映射，不进任何仿真状态 / hashState（零漂移）。 */
    public static final float[] GAME_SPEEDS = {0.5f, 1.0f, 2.0f, 4.0f};
    public int gameSpeedIdx = 1;   // 默认 1x（= GAME_SPEEDS[1]）
    public float gameSpeed() { return GAME_SPEEDS[clamp(gameSpeedIdx, 0, GAME_SPEEDS.length - 1)]; }

    // ---- 预设选择器（P1 续 2026-09-16）----
    /**
     * 玩法预设（{@code assets/content/presets/*.json}）—— 内容层提供的「整套玩法组合」。
     *
     * <p>与 {@link CharacterProvider} 同构：**渲染层把内容注册表的 id 与显示名喂进来**，
     * 本模型只保存列表与下标，不持有任何内容 / RNG / 世界引用 —— 菜单因此仍然 headless 可测。
     *
     * <p>为什么需要一个「选中的预设」而不是当场改几个静态量：预设是**一整套配置**
     * （系统域开关 + 参数），必须能在读档后**重新施加** —— {@code World.registry} 的开关状态
     * 不进快照（见 {@code StateCodec} 的 SKIP 清单），否则存档一读，
     * 「关掉地质域」就静默失效：菜单里还写着 PEACEFUL VALLEY，系统却全在跑。
     */
    private String[] presetIds = new String[0];
    private String[] presetNames = new String[0];
    private int presetIdx = 0;

    /** 内容加载后由渲染层调用（与捏脸 provider 同一条纪律：内容只喂进来，不反向依赖）。 */
    public void setPresets(String[] ids, String[] names) {
        this.presetIds = (ids == null) ? new String[0] : ids.clone();
        this.presetNames = (names == null) ? new String[0] : names.clone();
        this.presetIdx = clamp(this.presetIdx, 0, Math.max(0, this.presetIds.length - 1));
    }

    /** 按 id 选中预设；不在列表里则保持当前下标（默认预设缺失时退回第 0 项）。 */
    public void selectPreset(String id) {
        if (id == null) return;
        for (int i = 0; i < this.presetIds.length; i++) {
            if (id.equals(this.presetIds[i])) { this.presetIdx = i; return; }
        }
    }

    /** 当前选中的预设 id；无内容（未加载 / 空列表）时返回 null = 出厂配置。 */
    public String presetId() {
        return (this.presetIdx >= 0 && this.presetIdx < this.presetIds.length) ? this.presetIds[this.presetIdx] : null;
    }

    /** 当前预设显示名（菜单值文本 / HUD）。 */
    public String presetName() {
        if (this.presetIdx >= 0 && this.presetIdx < this.presetNames.length) return this.presetNames[this.presetIdx];
        return "DEFAULT";
    }

    public int presetCount() { return this.presetIds.length; }

    /** 左右切换预设（环绕）。真正**施加**由渲染层做 —— 本模型不碰世界。 */
    public void cyclePreset(int dir) {
        if (this.presetIds.length == 0) return;
        this.presetIdx = (this.presetIdx + dir + this.presetIds.length) % this.presetIds.length;
    }

    public Page page() { return page; }

    public boolean isOpen() { return page != Page.CLOSED; }

    public int selected() { return sel; }

    public void open() { page = Page.MAIN; sel = 0; }

    public void close() { page = Page.CLOSED; sel = 0; }

    public int itemCount() {
        switch (page) {
            case MAIN: return 5;                         // + F 批 CHARACTER（第 4 项）
            case SETTINGS: return SETTINGS_ROWS;
            case CONTROLS: return CONTROLS.length + 1;   // + BACK
            case QUIT_CONFIRM: return 2;
            case CHARACTER: return 20;                   // 19 可调(阶段1脸型/五官/年龄/身高/纹身/胡须 + 阶段B armor/helmet/cloak) + BACK
            default: return 0;
        }
    }

    public String title() {
        switch (page) {
            case MAIN: return "PAUSED";
            case SETTINGS: return "SETTINGS";
            case CONTROLS: return "CONTROLS";
            case QUIT_CONFIRM: return "QUIT GAME?";
            case CHARACTER: return "CHARACTER";
            default: return "";
        }
    }

    /** 条目左侧标签（ASCII，保证 {@code Font} 能画）。 */
    public String label(int i) {
        if (i < 0 || i >= itemCount()) return "";
        switch (page) {
            case MAIN: return new String[]{"RESUME", "SETTINGS", "CONTROLS", "CHARACTER", "QUIT"}[i];
            case SETTINGS: return new String[]{"FOV", "MOUSE SENS", "VIEW DIST", "HUD",
                                               "VOLUME", "SFX", "DAY LENGTH", "GAME SPEED", "SHADOWS", "ECLIPSE",
                                               "PRESET", "BACK"}[i];
            case CONTROLS: return i < CONTROLS.length ? CONTROLS[i] : "BACK";
            case QUIT_CONFIRM: return new String[]{"YES, QUIT", "CANCEL"}[i];
            case CHARACTER: return new String[]{"PRESET", "SKIN", "HAIR", "HAIR STYLE", "BUILD", "ACCENT", "EYES",
                    "FACE SHAPE", "EYE SHAPE", "BROW", "NOSE", "MOUTH", "AGE", "HEIGHT", "TATTOO", "BEARD",
                    "ARMOR", "HELMET", "CLOAK", "BACK"}[i];
            default: return "";
        }
    }

    /** 条目右侧值文本（设置页非 BACK 行与捏脸行有值；其它返回 ""）。 */
    public String value(int i) {
        if (page == Page.CHARACTER) {
            switch (i) {
                case 0: return "< " + safeName() + " >";
                case 1: return "< #" + hex(working.skin) + " >";
                case 2: return "< #" + hex(working.hair) + " >";
                case 3: return "< " + HAIR_STYLE_NAMES[clamp(working.hairStyle, 0, 5)] + " >";
                case 4: return "< " + BUILD_NAMES[clampB(working.build)] + " >";
            case 5: return "< #" + hex(working.accent) + " >";
            case 6: return "< #" + hex(working.eye) + " >";
            case 7:  return "< " + FACE_SHAPE_NAMES[clamp(working.faceShape, 0, 3)] + " >";
            case 8:  return "< " + EYE_SHAPE_NAMES[clamp(working.eyeShape, 0, 2)] + " >";
            case 9:  return "< " + BROW_NAMES[clamp(working.browStyle, 0, 2)] + " >";
            case 10: return "< " + NOSE_NAMES[clamp(working.noseStyle, 0, 2)] + " >";
            case 11: return "< " + MOUTH_NAMES[clamp(working.mouthStyle, 0, 2)] + " >";
            case 12: return "< " + AGE_NAMES[clamp(working.age, 0, 2)] + " >";
            case 13: return "< " + HEIGHT_NAMES[clamp(working.height, 0, 2)] + " >";
            case 14: return "< " + TATTOO_NAMES[clamp(working.tattoo, 0, 3)] + " >";
            case 15: return "< " + BEARD_NAMES[clamp(working.beard, 0, 3)] + " >";
            case 16: return "< " + ARMOR_NAMES[clamp(working.armor, 0, 3)] + " >";
            case 17: return "< " + HELMET_NAMES[clamp(working.helmet, 0, 3)] + " >";
            case 18: return "< " + CLOAK_NAMES[clamp(working.cloak, 0, 2)] + " >";
            default: return "";
            }
        }
        if (page != Page.SETTINGS) return "";
        switch (i) {
            case 0: return "< " + fov + " >";
            case 1: return "< " + mouseSensPct + "% >";
            case 2: return "< " + viewDist + " >";
            case 3: return showHud ? "< ON >" : "< OFF >";
            case 4: return "< " + volume + " >";
            case 5: return sfxOn ? "< ON >" : "< OFF >";
            case 6: return "< " + (core.world.DayCycle.DAY_LEN / core.world.DayCycle.TICKS_PER_MINUTE) + " MIN >";
            case 7: return "< " + gameSpeed() + "x >";
            case 8: return shadowsOn ? "< ON >" : "< OFF >";
            case 9: return "< " + eclipseRateName() + " >";
            case 10: return "< " + presetName() + " >";
            default: return "";
        }
    }

    /** 该条目是否可左右调节（设置页前 {@value #SETTINGS_EDITABLE} 行；捏脸前 7 行）。 */
    public boolean editable(int i) {
        if (page == Page.CHARACTER) return i >= 0 && i < 19;   // 前 19 项可左右调（含 armor/helmet/cloak）；BACK(索引19)不可
        return page == Page.SETTINGS && i >= 0 && i < SETTINGS_EDITABLE;
    }

    public void up() { int n = itemCount(); if (n > 0) sel = (sel - 1 + n) % n; }

    public void down() { int n = itemCount(); if (n > 0) sel = (sel + 1) % n; }

    /**
     * 激活当前条目：返回渲染层需执行的副作用。菜单自身无副作用。
     * 回退到上级页时会把光标停在「刚才进来的那一行」上（返回时的落点稳定、可断言）。
     */
    public Action activate() {
        switch (page) {
            case MAIN:
                if (sel == 0) { close(); return Action.RESUME; }
                if (sel == 1) { page = Page.SETTINGS; sel = 0; return Action.NONE; }
                if (sel == 2) { page = Page.CONTROLS; sel = 0; return Action.NONE; }
                if (sel == 3) { page = Page.CHARACTER; sel = 0; return Action.NONE; }  // F 批：进捏脸
                page = Page.QUIT_CONFIRM; sel = 1; return Action.NONE;   // sel==4（QUIT）默认落在 CANCEL（防误触）
            case SETTINGS:
                if (sel == SETTINGS_ROWS - 1) { page = Page.MAIN; sel = 1; return Action.BACK; }
                adjust(1); return Action.NONE;
            case CONTROLS:
                if (sel == CONTROLS.length) { page = Page.MAIN; sel = 2; return Action.BACK; }
                return Action.NONE;
            case QUIT_CONFIRM:
                if (sel == 0) return Action.QUIT;
                page = Page.MAIN; sel = 4; return Action.BACK;   // 退回 MAIN 的 QUIT 项（第 5 项）
            case CHARACTER:
                if (sel == 19) { page = Page.MAIN; sel = 3; return Action.BACK; }  // 回到 MAIN 的 CHARACTER 项（BACK 现居索引19）
                return Action.NONE;
            default:
                return Action.NONE;
        }
    }

    /** ESC / 返回：逐层回退；在 MAIN 上等价 RESUME（关菜单并继续）。 */
    public Action back() {
        switch (page) {
            case MAIN: close(); return Action.RESUME;
            case SETTINGS: page = Page.MAIN; sel = 1; return Action.BACK;
            case CONTROLS: page = Page.MAIN; sel = 2; return Action.BACK;
            case QUIT_CONFIRM: page = Page.MAIN; sel = 4; return Action.BACK;   // 退回 QUIT 项（第 5 项）
            case CHARACTER: page = Page.MAIN; sel = 3; return Action.BACK;       // 退回 CHARACTER 项
            default: return Action.NONE;
        }
    }

    /** 左右调节当前设置项（dir=-1 / +1）。数值钳制在合法区间；BOOL 项取反。 */
    public void adjust(int dir) {
        if (page == Page.CHARACTER) { adjustCharacter(dir); return; }
        if (page != Page.SETTINGS || dir == 0) return;
        switch (sel) {
            case 0: fov = clamp(fov + dir * 5, 50, 100); break;
            case 1: mouseSensPct = clamp(mouseSensPct + dir * 5, 5, 40); break;
            case 2: viewDist = clamp(viewDist + dir * 50, 100, 800); break;
            case 3: showHud = !showHud; break;
            case 4: volume = clamp(volume + dir * 5, 0, 100); break;
            case 5: sfxOn = !sfxOn; break;
            case 6: setDayLenStep(dir); break;
            case 7: gameSpeedIdx = (gameSpeedIdx + dir + GAME_SPEEDS.length) % GAME_SPEEDS.length; break;
            case 8: shadowsOn = !shadowsOn; break;
            case 9: eclipseRateStep(dir); break;
            case 10: cyclePreset(dir); break;
            default: break;
        }
    }

    /** 鼠标灵敏度（rad/px 系数），渲染层直接用。 */
    public float mouseSens() { return mouseSensPct / 100f; }

    /** 主音量（0..1），渲染层直接喂给 {@code AudioMixer}。 */
    public float volumeF() { return volume / 100f; }

    /** DAY LENGTH 档位：2.5 / 5 / 10 / 20 分钟（tick = 分钟*1200）。 */
    private static final int[] DAY_STEPS = {3000, 6000, 12000, 24000};
    private void setDayLenStep(int dir) {
        int cur = core.world.DayCycle.DAY_LEN;
        int idx = 2;
        for (int i = 0; i < DAY_STEPS.length; i++) if (Math.abs(DAY_STEPS[i] - cur) < Math.abs(DAY_STEPS[idx] - cur)) idx = i;
        idx = clamp(idx + dir, 0, DAY_STEPS.length - 1);
        core.world.DayCycle.setDayLen(DAY_STEPS[idx]);
    }

    /** ECLIPSE 行的档位文本（顺序 = {@link core.world.Celestial.Rate} 的声明顺序）。 */
    private static final String[] ECLIPSE_NAMES = {"RARE", "NORMAL", "OFTEN"};

    /** 当前食的频率档位名（渲染层直接显示）。 */
    public String eclipseRateName() {
        return ECLIPSE_NAMES[clamp(core.world.Celestial.rate.ordinal(), 0, ECLIPSE_NAMES.length - 1)];
    }

    /** 左右切换食的频率档位（环绕）。和 DAY LENGTH 一样：设置项直接改静态表现参数，不进仿真状态。 */
    private void eclipseRateStep(int dir) {
        core.world.Celestial.Rate[] all = core.world.Celestial.Rate.values();
        int i = (core.world.Celestial.rate.ordinal() + dir + all.length) % all.length;
        core.world.Celestial.setRate(all[i]);
    }

    // ---- F 批：捏脸调参（纯数据，确定性；不进任何仿真状态 / hashState）----
    private static final int[] SKIN_PALETTE   = {0xE0AC69, 0xC68642, 0xFFDBAC, 0x8D5524, 0xF1C27D, 0xA86B3C};
    private static final int[] HAIR_PALETTE   = {0x3B2A1A, 0x1A1A1A, 0x8B5A2B, 0x0B0B0B, 0xB5651D, 0xD9C39A};
    private static final int[] ACCENT_PALETTE = {0x6FA8DC, 0xB0413E, 0x93C47D, 0xD9A441, 0x9B59B6, 0xE67E22};
    private static final int[] EYE_PALETTE    = {0x4B3621, 0x2E2E2E, 0x6B4423, 0x1C1C1C, 0x3A6EA5, 0x6B8E23};
    private static final String[] BUILD_NAMES = {"SLIM", "NORMAL", "BROAD"};
    private static final String[] HAIR_STYLE_NAMES = {"SHORT", "MEDIUM", "LONG", "BALD", "BRAID", "BUN"};
    private static final String[] FACE_SHAPE_NAMES = {"ROUND", "SQUARE", "POINTY", "LONG"};
    private static final String[] EYE_SHAPE_NAMES  = {"ROUND", "ALMOND", "UP-TILT"};
    private static final String[] BROW_NAMES       = {"FLAT", "THICK", "ARCHED"};
    private static final String[] NOSE_NAMES       = {"NONE", "SMALL", "STRAIGHT"};
    private static final String[] MOUTH_NAMES      = {"NEUTRAL", "SMILE", "PRESSED"};
    private static final String[] AGE_NAMES        = {"YOUTH", "MIDDLE", "ELDER"};
    private static final String[] HEIGHT_NAMES     = {"SHORT", "STD", "TALL"};
    private static final String[] TATTOO_NAMES     = {"NONE", "CHEEK", "ARM", "FOREHEAD"};
    private static final String[] BEARD_NAMES      = {"NONE", "MUSTACHE", "FULL", "GOATEE"};
    private static final String[] ARMOR_NAMES      = {"NONE", "LEATHER", "CHAIN", "PLATE"};
    private static final String[] HELMET_NAMES     = {"NONE", "LEATHER", "IRON", "HORNED"};
    private static final String[] CLOAK_NAMES      = {"NONE", "SHORT", "LONG"};

    private static int clampB(int v) { return v < 0 ? 0 : (v > 2 ? 2 : v); }
    private static String hex(int c) { return String.format("%06X", c & 0xFFFFFF); }
    private String safeName() {
        return working.name != null && !working.name.isEmpty()
                ? working.name : (working.presetId != null ? working.presetId : "");
    }

    private void adjustCharacter(int dir) {
        if (dir == 0) return;
        switch (sel) {
            case 0:   // PRESET：在预设间循环；切到即套用该预设外观
                if (charProvider != null) {
                    String[] ids = charProvider.ids();
                    if (ids.length > 0) {
                        int idx = 0;
                        for (int k = 0; k < ids.length; k++) if (ids[k].equals(working.presetId)) { idx = k; break; }
                        idx = (idx + dir + ids.length) % ids.length;
                        core.world.Appearance a = charProvider.preset(ids[idx]);
                        if (a != null) working.copyFrom(a);
                    }
                }
                break;
            case 1: working.skin = stepColor(working.skin, dir, SKIN_PALETTE); break;
            case 2: working.hair = stepColor(working.hair, dir, HAIR_PALETTE); break;
            case 3:  working.hairStyle = (working.hairStyle + dir + 6) % 6; break;   // 0..5
            case 4:  working.build = (working.build + dir + 3) % 3; break;
            case 5:  working.accent = stepColor(working.accent, dir, ACCENT_PALETTE); break;
            case 6:  working.eye = stepColor(working.eye, dir, EYE_PALETTE); break;
            case 7:  working.faceShape = (working.faceShape + dir + 4) % 4; break;     // 0..3
            case 8:  working.eyeShape = (working.eyeShape + dir + 3) % 3; break;       // 0..2
            case 9:  working.browStyle = (working.browStyle + dir + 3) % 3; break;     // 0..2
            case 10: working.noseStyle = (working.noseStyle + dir + 3) % 3; break;     // 0..2
            case 11: working.mouthStyle = (working.mouthStyle + dir + 3) % 3; break;   // 0..2
            case 12: working.age = (working.age + dir + 3) % 3; break;                 // 0..2
            case 13: working.height = (working.height + dir + 3) % 3; break;           // 0..2
            case 14: working.tattoo = (working.tattoo + dir + 4) % 4; break;           // 0..3
            case 15: working.beard = (working.beard + dir + 4) % 4; break;             // 0..3
            case 16: working.armor = (working.armor + dir + 4) % 4; break;             // 0..3
            case 17: working.helmet = (working.helmet + dir + 4) % 4; break;           // 0..3
            case 18: working.cloak = (working.cloak + dir + 3) % 3; break;             // 0..2
            default: break;   // BACK 行(索引19)：不可调
        }
    }

    private static int stepColor(int cur, int dir, int[] pal) {
        int idx = 0;
        for (int k = 0; k < pal.length; k++) if (pal[k] == cur) { idx = k; break; }
        return pal[(idx + dir + pal.length) % pal.length];
    }

    static int clamp(int v, int lo, int hi) { return v < lo ? lo : (v > hi ? hi : v); }
}
