package core.systems;

import core.rng.SeededRNG;
import core.world.Player;
import core.world.World;

/**
 * 修仙 / 飞升子系统（第 94 个系统）—— 消费预设参数 {@code ascensionThreshold}。
 *
 * <p><b>它解决什么</b>：{@code mythic} 模块与 {@code mythic_sandbox} 预设早就写了
 * {@code ascensionThreshold}，但此前**背后没有任何系统**——滑块改了什么都不发生
 * （审计 C8 的 7 个「空转参数」之一）。
 *
 * <p><b>玩法</b>：道行（{@code Player.souls}，击杀敌兵累积）越过 {@code ascensionThreshold}
 * 即「飞升」：气血上限提升、道行归零重头修、村庄记下一段回响。
 *
 * <p><b>零漂移</b>：出厂默认 {@link core.world.WorldConfig#ASCENSION_THRESHOLD_DFLT} = {@code 1e9}
 * —— 不可达 → {@link #update} 首行即返回，不消费 RNG、不写任何状态。只有预设<b>显式</b>
 * 把阈值调到可达（如 {@code mythic_sandbox} 的 60）时才会发生，那是意图不是事故。
 *
 * <p><b>不进 hashState 的部分</b>：{@code Player.*} 与 {@code events} 本就不进窄哈希；
 * 唯一会进指纹的是 {@code World.recordMemory}（villageMemory）—— 它只在真正飞升那一刻写一次，
 * 即「世界因你而变」的可见痕迹。
 */
public final class AscensionSystem implements System {

    /** 出厂默认阈值（不可达）：与 {@link core.world.WorldConfig#ASCENSION_THRESHOLD_DFLT} 同源。 */
    public static final float UNREACHABLE = 1e9f;

    /** 飞升奖励：气血上限提升（气血属实体层，不进 hashState）。 */
    public static final int ASCEND_HP_BONUS = 40;

    /** 飞升是否已发生（系统实例状态 → 随快照持久化；默认 false）。 */
    private boolean ascended = false;
    private int ascensions = 0;

    @Override public String name() { return "ascension"; }

    @Override
    public void update(World w, SeededRNG rng) {
        float th = w.config.ascensionThreshold;
        Player p = w.player;
        // 不可达（出厂默认）→ 立即返回：零 RNG、零写入 → 既有世界演化逐字节不变。
        if (th >= UNREACHABLE || p == null) return;
        if (p.souls < (int) th) return;
        ascended = true;
        ascensions++;
        p.souls -= (int) th;                 // 道行归零，从头再修（可多次飞升）
        p.maxHp += ASCEND_HP_BONUS;
        p.hp = p.maxHp;
        w.log("ascension", "ascended", "n=" + ascensions + ",threshold=" + ((int) th),
                "maxHp=" + p.maxHp);
        w.recordMemory("飞升：道行越 " + ((int) th) + " 而蜕，气血上限 " + p.maxHp
                + "（第 " + ascensions + " 次）");
    }

    /** 是否已飞升过（HUD / 门禁观测用）。 */
    public boolean ascended() { return ascended; }
}
