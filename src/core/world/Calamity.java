package core.world;

/**
 * 灾害状态容器（批次 4 · 深度打磨已覆盖系统 · 灾害驱动层）。
 *
 * 忠实移植 Python systems/disasters.py（M21）的“触发/驱动层”状态：
 *  - 天气追踪：连续无雨天数 dryTicks + 连续降雨数 rainStreak；
 *  - 灾害旗标 flag：wildfire / flood / drought / earthquake / null（供观测与看板）；
 *  - 计数：各灾害触发次数 + 预警次数 + 撤离信号次数。
 *
 * 与既有“扩散/搬运”系统（{@link core.systems.WildfireSystem} 草原火蔓延 /
 * {@link core.systems.FloodSystem} 水漫流 / {@link core.systems.DroughtSystem} 水洼蒸发 /
 * {@link core.systems.EarthquakeSystem} 悬空沙塌落）分工：
 *  本容器驱动的 {@link core.systems.CalamitySystem} 负责 **条件触发 + 预警 + NPC 撤离信号**，
 *  网格实体化（点燃/抬水位/房倒）由 CalamitySystem.MATERIAL_WORKS 开关守护，默认关。
 *
 * 零漂移纪律（与 npcs/social/chronicle/civ/individual 同）：本类是开放标量，**绝不进**
 * {@link World#hashState()}，也绝不写 mat/mass/prosperity/skills/villageMemory，故指纹零影响。
 */
public final class Calamity {

    /** 连续无雨 tick 数（干旱指标）。 */
    public int dryTicks = 0;
    /** 连续降雨 tick 数（洪涝指标）。 */
    public int rainStreak = 0;

    /** 当前灾害旗标：wildfire / flood / drought / earthquake / null。 */
    public String flag = null;

    /** 预警 / 撤离信号 / 各灾种触发计数（仅统计，不进指纹）。 */
    public int alerts = 0, evacuations = 0;
    public int wildfires = 0, floods = 0, droughts = 0, quakes = 0;

    /** HUD 一行：ASCII 灾害摘要。 */
    public String asciiSummary() {
        return "CALAMITY " + (flag == null ? "CALM" : flag.toUpperCase())
                + "  DRY " + dryTicks + "  RAIN " + rainStreak
                + "  FIRE " + wildfires + "  FLOOD " + floods
                + "  DROUGHT " + droughts + "  QUAKE " + quakes + "  EVAC " + evacuations;
    }

    /** 可读快照（供门禁/日志；纯派生，不修改状态）。 */
    public String snapshot() {
        return "flag=" + (flag == null ? "-" : flag) + ",dry=" + dryTicks + ",rain=" + rainStreak
                + ",alerts=" + alerts + ",evac=" + evacuations
                + ",fire=" + wildfires + ",flood=" + floods + ",drought=" + droughts + ",quake=" + quakes;
    }
}
