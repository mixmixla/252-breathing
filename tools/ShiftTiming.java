import core.world.World;

/**
 * Headless shift-wallclock profiler (no GPU / OpenGL needed).
 *
 * <p>Purpose (batch 40): locate the true bottleneck of a window pan. Stages timed via the
 * {@code prof*} accumulators in World (nanoTime only, never touches the simulation RNG):
 * <ul>
 *   <li>relocateWindow — bulk arraycopy of mat/mass/surfaceY/surfaceTopY + rotateLight;</li>
 *   <li>generateChunk xN — regenerate the newly-entered strip;</li>
 *   <li>COMMIT — retargetDirtyOnShift + relocateIndex;</li>
 *   <li>computeLight — ran by the renderer after the pan; split into INCREMENTAL (band only)
 *       vs FULL (source-exit fallback) with separate ns + call counts.</li>
 * </ul>
 *
 * <p>Two worlds are measured:
 * <ol>
 *   <li>high world 160x112x160 (SY>=Megalith.MIN_SY) — has temple LAMPs ⇒ mixes incremental & full computeLight;</li>
 *   <li>small world 96x48x96 (SY<MIN_SY) — no光源 ⇒ every overlapping pan takes the pure INCREMENTAL path.</li>
 * </ol>
 * This separates "the incremental path itself is slow (pseudo-incremental full-window sweep)" from
 * "full recompute on source exit (correct, unavoidable)".
 *
 * <p>Run: {@code java -cp "out;libs/..." ShiftTiming [N]}.
 */
public class ShiftTiming {
    static final int[][] DIRS = {{1, 0}, {0, 1}, {1, 1}, {-1, 0}, {0, -1}, {-1, -1}};

    public static void main(String[] args) {
        int N = (args.length > 0) ? Integer.parseInt(args[0]) : 40;
        int[][] worlds = { {160, 112, 160, 20260914}, {96, 48, 96, 20260914} };

        for (int[] wd : worlds) {
            int SX = wd[0], SY = wd[1], SZ = wd[2], seed = wd[3];
            World w = new World(seed, SX, SY, SZ);
            long moveBytes = (long) SX * SY * SZ * 8L + (long) SX * SZ * 8L + (long) SX * SY * SZ * 5L;

            int curX = w.R, curZ = w.R;
            for (int i = 0; i < 30; i++) {                 // warmup
                curX += DIRS[i % DIRS.length][0]; curZ += DIRS[i % DIRS.length][1];
                w.streamToImmediate(curX, curZ);
                if (w.lightDirty) { w.computeLight(); w.lightDirty = false; }
            }

            System.out.println();
            System.out.println("=== World " + SX + "x" + SY + "x" + SZ
                    + "  moveBytes=" + (moveBytes / 1048576) + " MB/shift  N=" + N + " ===");
            System.out.printf("%-8s %9s %9s %9s %9s %11s %11s %7s%n",
                    "dir", "reloc_ms", "gen_ms", "commit_ms", "shift_ms",
                    "cInc_ms[n]", "cFull_ms[n]", "chunks");
            for (int[] d : DIRS) {
                w.resetShiftProfile();
                for (int i = 0; i < N; i++) {
                    curX += d[0]; curZ += d[1];
                    w.streamToImmediate(curX, curZ);
                    if (w.lightDirty) { w.computeLight(); w.lightDirty = false; }
                }
                long reloc = w.profRelocNs / N, gen = w.profGenNs / N, commit = w.profCommitNs / N, shift = w.profShiftNs / N;
                double cInc = w.profComputeIncN > 0 ? (double) w.profComputeIncNs / w.profComputeIncN / 1e6 : 0;
                double cFull = w.profComputeFullN > 0 ? (double) w.profComputeFullNs / w.profComputeFullN / 1e6 : 0;
                long chunks = w.profGenChunks / N;
                String dir = "(" + d[0] + "," + d[1] + ")";
                System.out.printf("%-8s %9.2f %9.2f %9.2f %9.2f %8.2f[%d] %8.2f[%d] %7d%n",
                        dir, reloc / 1e6, gen / 1e6, commit / 1e6, shift / 1e6,
                        cInc, w.profComputeIncN, cFull, w.profComputeFullN, chunks);
            }
        }
    }
}
