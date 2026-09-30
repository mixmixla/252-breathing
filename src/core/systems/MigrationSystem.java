package core.systems;

import core.rng.SeededRNG;
import core.world.Blocks;
import core.world.World;

/**
 * 兽群迁徙（60 · migration，生物/种群）：兽群随季节沿固定方向迁移——季节相位由 tick 周期派生
 * （确定性，无需 World 季节字段，与 SeasonSystem 同周期 256=4×64），方向按季节在 +x/+z/-x/-z 间轮换。
 * 每 tick 以低概率(rng)把一处旧足迹（GRASS 上方 LEAF）抹除、在前方 GRASS 上方空腔铺新足迹，留迁徙踪迹。
 * 与 HerdSystem 区分：HerdSystem 记记忆+加 prosperity（静态繁盛），本系统表现足迹随季节移动（动态迁徙）。
 * 随机严格走 simStream 入参 rng；方向纯由 tick 派生。
 */
public final class MigrationSystem implements System {
    @Override public String name() { return "migration"; }

    @Override
    public void update(World w, SeededRNG rng) {
        int season = (w.tick / 64) % 4;   // 0→+x 1→+z 2→-x 3→-z
        int dx = (season == 0) ? 1 : (season == 2) ? -1 : 0;
        int dz = (season == 1) ? 1 : (season == 3) ? -1 : 0;
        int samples = 16;
        for (int i = 0; i < samples; i++) {
            int x = rng.nextInt(w.SX), z = rng.nextInt(w.SZ);
            int top = AshFallSystem.surfaceY(w, x, z);
            if (top < 0 || top + 1 >= w.SY) continue;
            if (w.mat[x][top][z] != Blocks.GRASS.index) continue;
            if (w.mat[x][top + 1][z] != Blocks.LEAF.index) continue;  // 旧足迹
            int nx = x + dx, nz = z + dz;
            if (nx < 0 || nz < 0 || nx >= w.SX || nz >= w.SZ) continue;
            int ntop = AshFallSystem.surfaceY(w, nx, nz);
            if (ntop < 0 || ntop + 1 >= w.SY) continue;
            if (w.mat[nx][ntop][nz] != Blocks.GRASS.index) continue;
            if (w.mat[nx][ntop + 1][nz] != Blocks.AIR.index) continue;
            if (rng.nextDouble() < 0.1) {
                w.setBlock(x, top + 1, z, Blocks.AIR.index);       // 抹除旧足迹
                w.setBlock(nx, ntop + 1, nz, Blocks.LEAF.index);   // 前方铺新足迹
                w.log("migration", "move", "from=" + x + "," + z + " to=" + nx + "," + nz, "season=" + season);
            }
        }
    }
}
