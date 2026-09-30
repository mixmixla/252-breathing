package core.sim;

import core.world.Blocks;
import core.world.Player;
import core.world.World;

/**
 * 无限世界流式门禁（P3）：
 *  ① 流式下确定性——同种子 + 同玩家路径 → world.hashState() 逐字节一致；
 *  ② 建造持久化——玩家放的方块在区块卸载后再重载仍然存在（chunkEdits 覆盖生效）。
 *
 * 与既有三道门禁互补：本测试显式驱动 {@link World#streamTo}，验证滑动窗口平移后
 * 地形仍由全局坐标确定性函数生成、且编辑按块保留。
 *
 * 运行：java -cp out core.sim.StreamingTest
 */
public final class StreamingTest {
    public static void main(String[] args) {
        // 小到故意有界的已加载窗口：48×48 → CX=CZ=3, R=1。
        // 这证明“已加载窗口”可远小于概念上的无限世界；无论走到多大全局坐标，mat 至多 48×SY×48。
        final int SX = 48, SY = 48, SZ = 48;
        final long SEED = 555L;

        // ---- ① 流式下确定性（走到很大的全局坐标 200,200，仍逐字节一致）----
        long h1 = runPath(SEED, SX, SY, SZ);
        long h2 = runPath(SEED, SX, SY, SZ);
        boolean deterministic = h1 == h2;

        // ---- ② 建造跨卸载/重载持久化（小窗口下走到远程再走回原点）----
        World w = new World(SEED, SX, SY, SZ);
        w.streamTo(3, 3);                            // 先把窗口居中到块 (3,3)
        int mx = SX / 2, mz = SZ / 2;                // 本地中心 = 此时全局块 (3,3)
        int my = -1;
        for (int y = SY - 1; y >= 0; y--) {           // 找脚下一格实心放标记灯
            if (Blocks.byIndex(w.getBlock(mx, y, mz)).solid) { my = y; break; }
        }
        w.editBlock(mx, my, mz, Blocks.LAMP.index);
        boolean before = w.getBlock(mx, my, mz) == Blocks.LAMP.index;

        w.streamTo(200, 200);                         // 走到很远全局块，标记块卸载（仅编辑块进 chunkEdits）
        boolean gone = w.getBlock(mx, my, mz) != Blocks.LAMP.index;

        w.streamTo(3, 3);                             // 走回原点，标记块重载并应用覆盖
        boolean back = w.getBlock(mx, my, mz) == Blocks.LAMP.index;

        // ---- ③ 不可破坏：世界底层外壳（BEDROCK）不许被玩家写路径改掉 ----
        //    来由：真实事故 —— 挖方块路径没有过滤，玩家可从地底挖穿掉出世界（"里世界"）。
        //    判据含**负例**：普通方块必须仍可编辑（防「一律拒绝」把功能改死）。
        World wb = new World(SEED, SX, SY, SZ);
        int bx = SX / 2, bz = SZ / 2;
        wb.setBlock(bx, 0, bz, Blocks.BEDROCK.index);          // 生成路径可写（不受约束）
        boolean bedrockKept = wb.getBlock(bx, 0, bz) == Blocks.BEDROCK.index
                && !wb.editBlock(bx, 0, bz, Blocks.AIR.index)   // 玩家路径被拒
                && wb.getBlock(bx, 0, bz) == Blocks.BEDROCK.index;
        boolean normalEditable = wb.editBlock(bx, 1, bz, Blocks.STONE.index)
                && wb.getBlock(bx, 1, bz) == Blocks.STONE.index;
        boolean bedrockOk = bedrockKept && normalEditable;

        boolean pass = deterministic && before && gone && back && bedrockOk;
        System.out.printf("STREAMING  deterministic=%b before=%b gone=%b back=%b bedrockKept=%b normalEditable=%b hash=%016x%n",
                deterministic, before, gone, back, bedrockKept, normalEditable, h1);
        System.out.println(pass ? "STREAMING PASS" : "STREAMING FAIL");
        if (!pass) System.exit(1);
    }

    /** 沿一条确定性路径驱动流式 + tick，走到很大全局坐标，返回终态指纹。 */
    private static long runPath(long seed, int SX, int SY, int SZ) {
        World w = new World(seed, SX, SY, SZ);
        for (int step = 0; step < 800; step++) {
            int cx = step / 4;      // 全局块坐标（确定性路径，走到 ~200）
            int cz = 200 - (step % 4);
            w.streamTo(cx, cz);
            w.tick();               // 系统在流式窗口上继续演化
        }
        return w.hashState();
    }
}
