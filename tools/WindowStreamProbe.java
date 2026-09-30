import core.sim.Simulation;
import core.world.World;

/**
 * 滑动窗口流式探针（headless，无 GL）—— 回答一个架构问题：
 * <b>「无限世界 + 滑动窗口」到底有没有做到「载入量有界」？</b>
 *
 * <p>本项目的地形是全局坐标的确定性函数（{@code generateChunk} 用全局块坐标派生 treeRng），
 * 玩家跨块时窗口平移：{@code streamToSliced} 登记目标 → 每帧 {@code stepShift} 分片重生成。
 * 设计意图（{@code World} 头部注释）是：<b>未编辑的块由地形重生成、不进内存；只有被编辑的块
 * 才快照进 {@code chunkEdits}，于是「无论走多远，RAM 只随被编辑块数增长」</b>。</p>
 *
 * <p>本探针检验这条意图：让玩家长距离行走，观测三件事 ——</p>
 * <ol>
 *   <li><b>已加载窗口的常驻内存</b>（{@code mat}+{@code mass}，只与窗口尺寸有关，与走多远无关）；</li>
 *   <li><b>持久化状态（{@code save()} 字节数）随路程的增长</b> —— 这是"内存是否有界"的判据。
 *       增长的来源是 {@code chunkEdits}：窗口外编辑块<b>整列</b>落盘（16×SY×16 的 int+float）；</li>
 *   <li><b>每次平移的墙钟耗时</b> —— 平移会<b>重生成整个窗口</b>（CX×CZ 块，非仅新进入的块），
 *       这是与 UE World Partition「只加载新格子」的关键差异。</li>
 * </ol>
 *
 * <p>为什么用 {@code save()} 而不是读私有字段：存档是本项目<b>已有的生产路径</b>，它写的正是
 * "持久化状态"的全集 —— 用它量体积既不需要新增 API，量的也恰好是"这局状态下盘/占 RAM 的那部分"。</p>
 *
 * <p>运行：{@code java -cp "out;libs/*" WindowStreamProbe [steps] [ticksPerStep]}</p>
 */
public class WindowStreamProbe {

    public static void main(String[] args) {
        final int SX = 160, SY = 112, SZ = 160;              // 与 Game 的 new Simulation(seed,160,112,160) 同尺寸
        final int STEPS = args.length > 0 ? Integer.parseInt(args[0]) : 80;
        final int TICKS_PER_STEP = args.length > 1 ? Integer.parseInt(args[1]) : 5;
        final long SEED = 20260928L;

        Simulation sim = new Simulation(SEED, SX, SY, SZ);
        World w = sim.world;

        System.out.println("=== WindowStreamProbe  world=" + SX + "x" + SY + "x" + SZ
                + "  chunks=" + w.CX + "x" + w.CZ + "  steps=" + STEPS
                + "  ticksPerStep=" + TICKS_PER_STEP + "  seed=" + SEED + " ===");

        long loadedBytes = (long) SX * SY * SZ * (4 + 4);     // mat(int) + mass(float)
        System.out.println("已加载窗口常驻: mat+mass = " + (loadedBytes / 1048576) + " MB"
                + "（只与窗口尺寸有关，走多远都不变）");
        System.out.println("地形每块生成成本参照：一次平移重生成整窗 = " + (w.CX * w.CZ) + " 块");

        for (int i = 0; i < 100; i++) w.tick();               // 让系统改动地形（产生"被编辑的块"）
        long save0 = saveBytes(w);
        System.out.println();
        System.out.println("--- 行走前 ---");
        System.out.println("  winOrigin=(" + w.windowOriginCX() + "," + w.windowOriginCZ() + ")"
                + "  窗口内已编辑块=" + w.windowEditedChunkCount()
                + "  save=" + save0 + " B");

        double shiftSum = 0, shiftMax = 0;
        long saveEnd = save0;
        long tAll0 = System.nanoTime();

        // ⚠️ save() 每次要重建整窗基线（captureWindowDiffs 会重算 pristine 世界，较贵），
        //    故只在**稀疏检查点**量体积 —— 单点收益不值得把探针跑成分钟级。
        final int CP = Math.max(1, STEPS / 4);                 // 每 1/4 路程取一个检查点

        System.out.println();
        System.out.println("--- 行走（每步 +1 块，步间跑 " + TICKS_PER_STEP + " tick）---");
        for (int i = 0; i < STEPS; i++) {
            // 平移 +1 块：streamTo 以"玩家块坐标"为参数，窗口中心因此随玩家走。
            int targetCx = w.windowOriginCX() + w.R + 1;
            int targetCz = w.windowOriginCZ() + w.R;

            long s0 = System.nanoTime();
            w.streamTo(targetCx, targetCz);                    // 阻塞式平移（与分帧路径结果逐字节一致）
            double msShift = (System.nanoTime() - s0) / 1000000.0;
            shiftSum += msShift;
            if (msShift > shiftMax) shiftMax = msShift;

            for (int k = 0; k < TICKS_PER_STEP; k++) w.tick();  // 系统继续改地形

            if ((i + 1) % CP == 0 || i + 1 == STEPS) {
                saveEnd = saveBytes(w);
                System.out.println("  步 " + (i + 1) + ": winOrigin=(" + w.windowOriginCX() + "," + w.windowOriginCZ()
                        + ")  窗口内已编辑块=" + w.windowEditedChunkCount()
                        + "  save=" + (saveEnd / 1024) + " KB"
                        + "  Δ/步=" + ((saveEnd - save0) / (i + 1)) + " B");
            }
        }
        double totalSec = (System.nanoTime() - tAll0) / 1e9;

        long delta = saveEnd - save0;
        System.out.println();
        System.out.println("--- 结论 ---");
        System.out.println("  行走步数          : " + STEPS + " 块（水平 " + STEPS * 16 + " 格）");
        System.out.println("  平移耗时          : 平均 " + fmt(shiftSum / STEPS) + " ms / 最坏 " + fmt(shiftMax) + " ms"
                + "（重生成整窗 " + (w.CX * w.CZ) + " 块）");
        System.out.println("  行走段总墙钟      : " + fmt(totalSec) + " s");
        System.out.println("  快照字节增长      : " + save0 + " -> " + saveEnd + " B  (delta+" + delta + " B)");
        System.out.println("  每走 1 块增长     : " + (delta / Math.max(1, STEPS)) + " B/块"
                + "（~ " + (delta / Math.max(1, STEPS) / 1024) + " KB/块）");
        System.out.println("  外推(快照)        : 走 1000 块 ~ " + ((delta / Math.max(1, STEPS)) * 1000L / 1048576L) + " MB"
                + "；走 10000 块 ~ " + ((delta / Math.max(1, STEPS)) * 10000L / 1048576L) + " MB"
                + "  （上界 = 整窗约 21.9 MB，故外推只在前段有意义）");
        System.out.println("  最终 winOrigin=(" + w.windowOriginCX() + "," + w.windowOriginCZ() + ")"
                + "  窗口内已编辑块=" + w.windowEditedChunkCount());
        System.out.println();
        System.out.println("判读：窗口常驻（" + (loadedBytes / 1048576) + " MB）恒定 = 滑动加载本身是对的；");
        System.out.println("      **用户不要**把上面的\"快照字节\"当 RAM —— save() 量的是快照（窗口稀疏差分 + 卸载覆盖），");
        System.out.println("      而窗口差分是**现算不驻留**的。**RAM 的代理是\"窗口内已编辑块\"**：");
        System.out.println("      它 > 0 才可能有块被快照进 chunkEdits（229 KB/整列，P0 前是主要增长源）；");
        System.out.println("      它 = 0 => 没有玩家建造 => chunkEdits 不增长（P0 生效）。本探针不建造，故应为 0。");
    }

    /** 存档字节数（不保留内容，只计数）—— 存档是"持久化状态"的全集，故直接量它。 */
    private static long saveBytes(World w) {
        CountingOutputStream c = new CountingOutputStream();
        try {
            w.save(c);
        } catch (java.io.IOException e) {
            throw new RuntimeException(e);
        }
        return c.n;
    }

    private static String fmt(double v) {
        return String.format(java.util.Locale.US, "%.2f", v);
    }

    private static final class CountingOutputStream extends java.io.OutputStream {
        long n = 0;
        @Override public void write(int b) { n++; }
        @Override public void write(byte[] b, int off, int len) { n += len; }
    }
}
