package core.systems;

import core.rng.SeededRNG;
import core.world.Blocks;
import core.world.World;

/**
 * 地震塌落（80 · 地质/深地）：低频 rng 触发“地震”——随机选若干悬空 SAND（下方为 AIR）塌落
 * （setBlock 搬运，质量守恒），记 "earthquake"."shake" event。
 * 与 SinkholeSystem（特定隧道空腔上方 SAND 塌）区分：本系统是“全局低频随机塌落”事件（带 shake 事件 + 多格批量），
 * 不要求隧道结构；与 SandFallSystem（每 tick 单格确定性下落）区分：本系统仅在地震触发时才以概率批量崩落。
 * 零漂移：随机仅走入参 rng；用 setBlock 搬运。
 */
public final class EarthquakeSystem implements System {
    @Override public String name() { return "earthquake"; }

    @Override
    public void update(World w, SeededRNG rng) {
        if (rng.nextDouble() >= 0.01) return;             // 低频触发
        int samples = 30, collapsed = 0;
        for (int i = 0; i < samples; i++) {
            int x = rng.nextInt(w.SX), y = rng.nextInt(w.SY - 1), z = rng.nextInt(w.SZ);
            if (w.mat[x][y][z] != Blocks.SAND.index) continue;
            if (w.mat[x][y + 1][z] == Blocks.AIR.index && rng.nextDouble() < 0.5) {
                w.setBlock(x, y + 1, z, Blocks.SAND.index);   // 质量守恒搬运
                w.setBlock(x, y,     z, Blocks.AIR.index);
                collapsed++;
            }
        }
        if (collapsed > 0) w.log("earthquake", "shake", "n=" + collapsed, "collapse");
    }
}
