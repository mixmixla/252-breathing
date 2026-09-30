package core.systems;

import core.rng.SeededRNG;
import core.world.Blocks;
import core.world.World;

/**
 * 晨雾（气象/纯视觉）：以 tick 相位决定“清晨”窗口，在水面/湿地附近低概率记录起雾事件。
 * sim 仅记状态（写事件日志，不进 hashState 指纹），渲染层可据此读相位做雾效——守住 sim/render 隔离。
 * 确定性：相位来自 w.tick（已进指纹），不消耗 fxRng、不读墙钟。
 */
public final class MistSystem implements System {
    @Override public String name() { return "mist"; }

    @Override
    public void update(World w, SeededRNG rng) {
        if (w.tick % 64 != 0) return;     // 每个周期仅清晨首 tick 记一次，确定性
        int samples = 12;
        boolean wetlands = false;
        for (int i = 0; i < samples; i++) {
            int x = rng.nextInt(w.SX), z = rng.nextInt(w.SZ);
            int top = AshFallSystem.surfaceY(w, x, z);
            if (top < 0) continue;
            if (top + 1 < w.SY && w.mat[x][top + 1][z] == Blocks.WATER.index) { wetlands = true; break; }
            // 邻格有水也算湿地
            if (w.getBlock(x - 1, top, z) == Blocks.WATER.index
                    || w.getBlock(x + 1, top, z) == Blocks.WATER.index
                    || w.getBlock(x, top, z - 1) == Blocks.WATER.index
                    || w.getBlock(x, top, z + 1) == Blocks.WATER.index) { wetlands = true; break; }
        }
        if (wetlands) w.log("mist", "rise", "phase=" + (w.tick % 64), "dawn");
    }
}
