package core.net;

import core.world.World;

/**
 * N5：可注入的"整 tick 推进体"。
 *
 * <p>会话（{@link LockstepSession} / {@link PredictiveSession}）的 {@code advance()} 只负责
 * 「定序输入 + 调用本推进体」，把"这一 tick 到底跑哪些系统"完全交给注入方。这是让
 * <b>单机与联机演化逐字节一致</b>的关键：两种形态必须共用同一个推进体。
 *
 * <ul>
 *   <li><b>默认实现</b> = {@code world::tick()}（无头门禁 / 纯仿真用，与 N0–N4 既有的
 *       {@code world.tick()} 行为完全一致，向后兼容）。</li>
 *   <li><b>真游戏实现</b> = {@code world.tick()} + 内容层（{@code RuleEngine}/{@code TechTree}/
 *       {@code EffectQueue}）——即 {@code render/lwjgl/Game} 固定步长里那 4 行，使联机也推进
 *       完整玩法层。</li>
 * </ul>
 *
 * <p>门禁据此断言：注入任意确定性推进体，两端（或"会话 vs 直接 tick"）终态 hash 必一致。
 */
@FunctionalInterface
public interface TickBody {
    /** 推进世界一 tick（应用输入已由 {@code InputSink} 完成）。 */
    void tick(World world);
}
