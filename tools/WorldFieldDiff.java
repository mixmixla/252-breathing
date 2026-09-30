import core.net.StateCodec;
import core.sim.Simulation;
import core.world.World;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.List;

/**
 * World 字段级分叉定位：同种子两世界跑完 T tick 后，逐个 writable 字段比对（数组/集合走 StateCodec.encode）。
 * 用途：netHash 宽哈希分叉的精确定位（world.encode 不等但 hashState 相等时）。
 * 用法：{@code java -cp out WorldFieldDiff [T]}
 */
public class WorldFieldDiff {
    public static void main(String[] args) throws Exception {
        int T = args.length > 0 ? Integer.parseInt(args[0]) : 30;
        Simulation sa = new Simulation(0xD3511C0DL, 160, 112, 160);
        Simulation sb = new Simulation(0xD3511C0DL, 160, 112, 160);
        for (int t = 0; t < T; t++) sa.world.tick();
        for (int t = 0; t < T; t++) sb.world.tick();
        World wa = sa.world, wb = sb.world;

        List<Field> fs = StateCodec.writable(World.class);
        int diff = 0;
        for (Field f : fs) {
            f.setAccessible(true);
            Object va = f.get(wa), vb = f.get(wb);
            if (!eq(va, vb)) {
                java.lang.System.out.println("DIFF  " + f.getName()
                        + "   A=" + brief(va) + "   B=" + brief(vb));
                diff++;
            }
        }
        java.lang.System.out.println("world fields differing = " + diff + " / " + fs.size());
    }

    private static boolean eq(Object a, Object b) {
        if (a == null || b == null) return a == b;
        if (a instanceof Number || a instanceof Boolean || a instanceof String || a instanceof Character) return a.equals(b);
        try { return Arrays.equals(StateCodec.encode(a), StateCodec.encode(b)); }
        catch (Exception e) { return a.equals(b); }
    }

    private static String brief(Object o) {
        if (o == null) return "null";
        try { return o.getClass().getSimpleName() + "(len=" + StateCodec.encode(o).length + ")"; }
        catch (Exception e) { return o.getClass().getSimpleName() + "?"; }
    }
}
