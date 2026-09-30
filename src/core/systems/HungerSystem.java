package core.systems;

import core.rng.SeededRNG;
import core.world.Player;
import core.world.World;

/**
 * 饥饿生存子系统（第 95 个系统）—— 消费预设参数 {@code hungerRate}。
 *
 * <p><b>它解决什么</b>：{@code survival} 模块与 {@code palworld_like} 预设写了
 * {@code hungerRate}，但此前**背后没有系统**（审计 C8 的 7 个「空转参数」之一）。
 *
 * <p><b>玩法</b>：饱食度按 {@code hungerRate × DRAIN_PER_TICK} 递减；归零后每
 * {@link #STARVE_PERIOD} tick 扣 {@link #STARVE_DMG} 点气血（走
 * {@link Player#hurtByHazard}——与野兽伤害同一条唯一伤害规则的「危险源」分支）。
 * 药瓶补给会同时回满饱食（见 {@link Player#useFlask}）。
 *
 * <p><b>零漂移</b>：出厂默认 {@code hungerRate = 0} → 首行返回，不写任何状态。
 * 且饱食度活在 {@link Player}（实体层）—— 而 {@code hashState()} 只盖
 * mat/mass/prosperity/tick/rng/skills/villageMemory/builtMass，<b>不含 Player 任何字段</b>
 * → 即使启用饥饿也不动四道指纹。
 */
public final class HungerSystem implements System {

    /**
     * 每 tick 的饱食度消耗系数：rate=1.0 时 100 点 ≈ **500 秒（约 8 分钟）**（20 tick/s）。
     *
     * <p>2026-09-17 由 0.05 调为 0.01：0.05 意味着**100 秒就饿到扣血**，一旦某个预设
     * （如 {@code palworld_like} 启用 {@code survival} 模块）真把饥饿打开，玩家会以为游戏坏了。
     * 生存压力应该是"记得吃东西"，不是"两分钟暴毙"。
     */
    public static final float DRAIN_PER_TICK = 0.01f;

    /** 饥饿扣血的节拍（tick）：2 秒一次。 */
    public static final int STARVE_PERIOD = 40;

    /** 饥饿每次扣血。 */
    public static final int STARVE_DMG = 1;

    @Override public String name() { return "hunger"; }

    @Override
    public void update(World w, SeededRNG rng) {
        float rate = w.config.hungerRate;
        Player p = w.player;
        if (rate <= 0f || p == null || !p.alive) return;      // 出厂默认（0）→ 零写入
        p.hunger = Math.max(0f, p.hunger - rate * DRAIN_PER_TICK);
        if (p.hunger <= 0f && (w.tick % STARVE_PERIOD) == 0L) {
            p.hurtByHazard(STARVE_DMG, w);
        }
    }
}
