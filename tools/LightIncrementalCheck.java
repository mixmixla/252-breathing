import core.world.World;
import core.world.Blocks;

import java.util.Arrays;

/**
 * 无头门禁（不需要 GPU / OpenGL 上下文）：**窗口平移后的增量光照 == 全量重算光照**（第三十九批）。
 *
 * <p><b>为什么必须有它</b>：第三十九批把"每次平移把整窗光场全量重算"改成
 * "光场随 mat 旋转、只重算新进入的条带"（interior 旋转后逐字节正确，带区受限扫描）。
 * 这类"省掉工作"的优化最阴的翻车方式是<b>带区边界算窄了</b> —— 平移后只有条带边缘几格的光照
 * 会"差一点点"（肉眼难察，却破坏了"复用旧光场"的零漂移契约，且会悄悄让复用网格的颜色与新网格不一致）。</p>
 *
 * <p><b>判据</b>：对世界 A 平移后走<b>增量</b>路径（{@code lightDirtyShift} 在 COMMIT 置位），
 * 对世界 B 平移后<b>强制全量</b>重算，比较两者 {@code lightGrid} 逐字节相等。
 * 覆盖：同向连走、斜向、反向、一次走多块、以及"零重叠跳转"（零重叠必须退回全量、且仍逐字节正确）。
 * 另钉住"增量标志只在该走增量时置位"（重叠=true / 零重叠=false）。</p>
 *
 * <p><b>光源退出 ⇒ 全量</b>：若平移时某光源滑出窗口（{@code lightLeaveX/Z}），其原本照亮 interior 的
 * 光被旋转带入新 interior → 残留 stale 亮度，而受限带区扫描读 interior 邻格（仍含 stale 光）→ 离开边错。
 * 光是全局不动点，被污染区可达整窗，带区隔离无效。故 {@code computeLight} 在有光源退出时直接走全量重算
 * （{@code lightDirtyShift} 仍为 true 以保 {@code LIGHT-ARMED} 契约，但内部分支跳到全量）。本门禁的
 * {@code {-1,0}} 等用例正是用来钉这条回退路径。</p>
 *
 * <p>运行：{@code java -cp "out;libs/..." LightIncrementalCheck}。</p>
 */
public class LightIncrementalCheck {

    private static boolean ok = true;

    private static void check(String name, boolean cond, String detail) {
        System.out.println("  [" + (cond ? "ok  " : "FAIL") + "] " + name
                + (detail.isEmpty() ? "" : "  " + detail));
        if (!cond) ok = false;
    }

    /** 在窗口内确定性地放一批光源（含远角，平移后会落入带区，真正锻炼 band 重算）。 */
    private static void placeSources(World w) {
        int y = w.SY - 3;   // 高空空气层，光向下透入地形
        int[][] lamp = {
            {150, y, 150}, {155, y, 30}, {30, y, 150}, {8, y, 8},
            {80, y, 80}, {140, y, 140}, {100, y, 40}, {16, y, 16}, {144, y, 144},
        };
        int[][] fire = { {145, y, 145}, {20, y, 100}, {120, y, 120}, {5, y, 155} };
        for (int[] p : lamp) w.setBlock(p[0], p[1], p[2], Blocks.LAMP.index);
        for (int[] p : fire) w.setBlock(p[0], p[1], p[2], Blocks.FIRE.index);
    }

    public static void main(String[] args) {
        final long seed = args.length > 0 ? Long.parseLong(args[0]) : 20260928L;
        final int SX = 160, SY = 96, SZ = 160;
        final int CX = SX / 16, CZ = SZ / 16;
        System.out.println("=== LightIncrementalCheck (seed=" + seed + ", " + CX + "x" + CZ + " chunks) ===");

        // 初态两世界光场一致（确定性基线）
        World a0 = new World(seed, SX, SY, SZ); placeSources(a0); a0.computeLight();
        World b0 = new World(seed, SX, SY, SZ); placeSources(b0); b0.computeLight();
        check("LIGHT-BASE-EQ  初始光场逐字节一致（确定性）",
                Arrays.equals(a0.lightGrid, b0.lightGrid), "");

        // 覆盖：同向、斜向、反向、多块、零重叠
        final int[][] shifts = {
            {1, 0}, {1, 1}, {0, 1}, {-1, 0}, {0, -2}, {-2, -1}, {1, -1}, {CX + 1, 0},
        };

        for (int[] sh : shifts) {
            int dcx = sh[0], dcz = sh[1];
            boolean overlap = Math.abs(dcx) < CX && Math.abs(dcz) < CZ;

            // 世界 A：平移 → 增量重算（COMMIT 置 lightDirtyShift）
            World wa = new World(seed, SX, SY, SZ); placeSources(wa); wa.computeLight();
            wa.streamTo(wa.windowOriginCX() + wa.R + dcx, wa.windowOriginCZ() + wa.R + dcz);
            boolean armed = wa.lightDirtyShift;
            check("LIGHT-ARMED   d=(" + dcx + "," + dcz + ") 增量标志置位符合预期（重叠=" + overlap + "）",
                    armed == overlap, "armed=" + armed);
            wa.computeLight();   // 增量（重叠）或全量（零重叠）

            // 世界 B：平移 → 强制全量重算（对照）
            World wb = new World(seed, SX, SY, SZ); placeSources(wb); wb.computeLight();
            wb.streamTo(wb.windowOriginCX() + wb.R + dcx, wb.windowOriginCZ() + wb.R + dcz);
            wb.lightDirtyShift = false;   // 强制走全量
            wb.lightDirty = true;
            wb.computeLight();

            boolean eq = Arrays.equals(wa.lightGrid, wb.lightGrid);
            int diff = 0;
            if (!eq) {
                for (int i = 0; i < wa.lightGrid.length; i++)
                    if (wa.lightGrid[i] != wb.lightGrid[i]) diff++;
            }
            check("LIGHT-INCREMENTAL  d=(" + dcx + "," + dcz + ") 增量光场 == 全量重算光场（逐字节）",
                    eq, eq ? "" : "不一致格数=" + diff + "/" + wa.lightGrid.length);
        }

        System.out.println("LIGHTINCREMENTAL RESULT: " + (ok ? "PASS" : "FAIL"));
        if (!ok) System.exit(1);
    }
}
