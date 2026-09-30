package core.systems;

import core.rng.SeededRNG;
import core.world.Blocks;
import core.world.World;

/**
 * 林地蕨类（生态）：树冠阴影下的草地/泥土缓慢长出蕨（复用 MOSS 占位），演示“郁闭林下分层”的慢涌现。
 * 与 CanopySystem 形成“树冠↔林下植被”耦合，随机走 simStream。
 */
public final class FernSystem implements System {
    @Override public String name() { return "fern"; }

    @Override
    public void update(World w, SeededRNG rng) {
        int samples = 32;
        for (int i = 0; i < samples; i++) {
            int x = rng.nextInt(w.SX), z = rng.nextInt(w.SZ);
            int top = AshFallSystem.surfaceY(w, x, z);
            if (top < 0) continue;
            //  ⚠️ 第十二批修掉第 3 个潜伏 bug：原先直接取 `mat[x][surfaceY][z]` 当"地面"，
            //  可 `surfaceY` 是**最高的实心块** —— 树冠是实心 LEAF，会把 surfaceY 顶到叶子上，
            //  于是读到的是 LEAF 而不是草/土 ⇒ 与"同列上方要有 LEAF"**互斥** ⇒ 永远长不出蕨。
            //  修正：从 surfaceY **往下**找第一个真正的草/土（跳过 AIR/LEAF/WOOD，遇到别的实心块就放弃），
            //  再以这个地面为基准做"上方 1..4 格有 LEAF"的阴影判定。
            int ground = -1;
            for (int y = top; y >= 0 && y > top - 8; y--) {
                int b = w.mat[x][y][z];
                if (b == Blocks.GRASS.index || b == Blocks.DIRT.index) { ground = y; break; }
                if (b != Blocks.AIR.index && b != Blocks.LEAF.index && b != Blocks.WOOD.index) break;
            }
            if (ground < 0 || ground + 1 >= w.SY) continue;
            if (w.mat[x][ground + 1][z] != Blocks.AIR.index) continue;
            boolean shade = false;
            for (int dy = 1; dy <= 4 && ground + dy < w.SY; dy++)
                if (w.mat[x][ground + dy][z] == Blocks.LEAF.index) { shade = true; break; }
            if (shade && rng.nextDouble() < 0.02) {
                w.setBlock(x, ground + 1, z, Blocks.FERN.index);   // 蕨（第十二批：改用 FERN，不再用 MOSS 占位）
            }
        }
    }
}
