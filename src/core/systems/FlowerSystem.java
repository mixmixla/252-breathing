package core.systems;

import core.rng.SeededRNG;
import core.world.Blocks;
import core.world.World;

/**
 * 野花系统（植物/装饰）：草原地表以低概率长出 FLOWER（非实心装饰方块）。
 * 确定性：逐列扫描、固定迭代序、随机走 simStream 子流。
 */
public final class FlowerSystem implements System {
    @Override public String name() { return "flower"; }

    @Override
    public void update(World w, SeededRNG rng) {
        // PERF-SIM：只迭代 GRASS 方块（表层草=上方为空气者），替代全列 surfaceY 扫描。
        // 上方为空气 ⟺ 该草即最顶实心（无更上实心）→ 与原"surface==GRASS && 上方 AIR"逐列等价。
        //
        // 批⑧（第五刀的姊妹刀）：把 `new ArrayList<>(cellsOfType(GRASS))` 换成**零每格分配**的平铺快照
        // （countOf + copyOf）——原路每 tick 为每格 new int[3]（~8.8k 格 ⇒ ~8.8k 分配/tick）。
        // 迭代序、(x,y,z) 取值序、rng 抽取点与原实现**逐元素一致** ⇒ 行为不变。
        // 直读 mat 取代 getBlock：x/z 取自合法体素、y+1<SY 已判 ⇒ 必在界内（getBlock 界内即 mat 读）。
        final int AIR = Blocks.AIR.index, GRASS = Blocks.GRASS.index, FLOWER = Blocks.FLOWER.index;
        final int SY = w.SY;
        final int[][][] mat = w.mat;
        final int cnt = w.countOf(GRASS);
        int[] buf = new int[cnt * 3];
        w.copyOf(GRASS, buf);
        // 批⑤：野花全局上限（派生式）。判据置于 rng 抽取之后 ⇒ 不位移本系统随机流。
        int remain = w.spreadHeadroom(FLOWER);
        for (int i = 0, b = 0; i < cnt; i++, b += 3) {
            int x = buf[b], y = buf[b + 1], z = buf[b + 2];
            if (y + 1 >= SY) continue;
            if (mat[x][y + 1][z] == AIR && rng.nextDouble() < 0.02 && remain > 0) {
                w.setBlock(x, y + 1, z, FLOWER);
                remain--;
            }
        }
    }
}
