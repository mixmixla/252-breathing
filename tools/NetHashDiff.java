import core.net.StateCodec;
import core.sim.Simulation;
import core.systems.System;
import core.world.Player;
import core.world.World;

import java.util.Arrays;
import java.util.List;

/**
 * 同种子两世界"宽哈希分叉"定位探针：把 netHash 的三个来源分别比对，
 * 找出到底是谁不一致（world 编码 / 哪个系统编码）。
 *
 * 复现 NetDesyncTest 的次序：a 先跑完 T 再跑 b（同 JVM）。
 * 用法：{@code javac -cp out -d out tools/NetHashDiff.java && java -cp out NetHashDiff [T)}
 */
public class NetHashDiff {
    public static void main(String[] args) throws Exception {
        int T = args.length > 0 ? Integer.parseInt(args[0]) : 30;
        long SEED = 0xD3511C0DL;
        boolean drive = args.length > 1 && args[1].equals("drive");

        Simulation sa = new Simulation(SEED, 160, 112, 160);
        Simulation sb = new Simulation(SEED, 160, 112, 160);
        for (int t = 0; t < T; t++) { if (drive && t % 5 == 0) sa.world.player.setIntent(Player.Intent.move(1, 0)); sa.world.tick(); }
        for (int t = 0; t < T; t++) { if (drive && t % 5 == 0) sb.world.player.setIntent(Player.Intent.move(1, 0)); sb.world.tick(); }
        World wa = sa.world, wb = sb.world;

        java.lang.System.out.println("T=" + T + " drive=" + drive);
        java.lang.System.out.println("hashState equal   = " + (wa.hashState() == wb.hashState())
                + "   A=" + String.format("%016x", wa.hashState()) + " B=" + String.format("%016x", wb.hashState()));
        byte[] ea = StateCodec.encode(wa), eb = StateCodec.encode(wb);
        java.lang.System.out.println("world.encode eq   = " + Arrays.equals(ea, eb) + "   lenA=" + ea.length + " lenB=" + eb.length);
        List<System> A = wa.registry.ordered(), B = wb.registry.ordered();
        int diff = 0;
        for (int i = 0; i < A.size(); i++) {
            byte[] x = StateCodec.encode(A.get(i));
            byte[] y = StateCodec.encode(B.get(i));
            if (!Arrays.equals(x, y)) {
                java.lang.System.out.println("  DIFF system #" + i + "  " + A.get(i).name() + "   lenA=" + x.length + " lenB=" + y.length);
                diff++;
            }
        }
        java.lang.System.out.println("systems differing = " + diff + " / " + A.size());
        java.lang.System.out.println("netHash equal     = " + (wa.netHash() == wb.netHash()));
    }
}
