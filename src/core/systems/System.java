package core.systems;

import core.rng.SeededRNG;
import core.world.World;

/**
 * 涌现系统接口（移植自 systems/ 下的各系统骨架）。
 * 每个系统 update 时拿到的 rng 是 world.simStream(name) 派生的逐 tick 子流，
 * 因此同种子同 tick 永远得到同一随机序列 —— 确定性由这里保证。
 */
public interface System {
    String name();
    void update(World w, SeededRNG rng);
}
