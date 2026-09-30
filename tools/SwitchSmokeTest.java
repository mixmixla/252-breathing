import core.sim.Simulation;
import core.world.Blocks;
import core.world.World;

/**
 * 休眠开关冒烟测试（第四十九批）：把默认**关**的 {@code MATERIAL_WORKS}（4 处静态开关）打开，
 * 并强制"城镇自治"前置（unlocked+=town_hall, blueprints+=wall/rebuild），跑 N tick：
 *   ① 有没有当场抛异常（数年前的 `new TreeSet<int[]>` 隐患正是这条路上）；
 *   ② `CivilizationSystem.autonomy()` 是否真的执行（SHELTER 计数变化）；
 *   ③ MATERIAL_WORKS 打开后 Calamity（点火/抬水位/蒸发）/ Matter（NPC 受辐射）路径是否也安全。
 *
 * 注意：MATERIAL_WORKS 是**静态**开关 ⇒ 探针结束必须复位（否则污染同 JVM 后续用例）。
 * 用法：{@code javac -cp out -d out tools/SwitchSmokeTest.java && java -cp out SwitchSmokeTest [N]}
 */
public class SwitchSmokeTest {
    public static void main(String[] args) {
        int N = args.length > 0 ? Integer.parseInt(args[0]) : 600;
        boolean ok = true;
        ok &= probe("MATERIAL_WORKS=false (默认)", N, false);
        ok &= probe("MATERIAL_WORKS=true  (+自治前置)", N, true);
        setWorks(false);   // 复位静态开关
        ok &= positiveControl();
        java.lang.System.out.println(ok ? "SMOKE PASS" : "SMOKE FAIL");
        if (!ok) System.exit(1);
    }

    /**
     * 正样本对照：证明"原写法" `new TreeSet<int[]>` 确实会抛 —— 否则批⑪ 的修复可能只是修了个
     * 永不会发生的问题。构造一个含元素的 `TreeSet<int[]>`，首次 add 即应 ClassCastException。
     * （放在 tools/ 下是有意的：审计 C13 只扫 src/，这里刻意保留"反面样本"做对照。）
     */
    private static boolean positiveControl() {
        try {
            new java.util.TreeSet<int[]>(java.util.Arrays.asList(new int[]{1, 2, 3}));
            java.lang.System.out.println("  [正样本] TreeSet<int[]> 未抛异常 —— 隐患是理论性的？需复核");
            return false;
        } catch (ClassCastException e) {
            java.lang.System.out.println("  [正样本] TreeSet<int[]> 确抛 ClassCastException —— 证明批⑪ 修的是真隐患 ✓");
            return true;
        }
    }

    private static void setWorks(boolean v) {
        core.systems.CivilizationSystem.MATERIAL_WORKS = v;
        core.systems.IndividualSystem.MATERIAL_WORKS = v;
        core.systems.MatterSystem.MATERIAL_WORKS = v;
        core.systems.CalamitySystem.MATERIAL_WORKS = v;
    }

    private static boolean probe(String label, int N, boolean works) {
        setWorks(works);
        Simulation sim = new Simulation(20260929L, 96, 48, 96);
        World w = sim.world;
        if (works) {                       // 强制 autonom y 前置（否则要等科研自然解锁 town_hall）
            w.civ.unlocked.add("town_hall");
            w.civ.blueprints.add("wall");
            w.civ.blueprints.add("rebuild");
        }
        int sh0 = w.cellsOfType(Blocks.SHELTER.index).size();
        int wood0 = w.cellsOfType(Blocks.WOOD.index).size();
        try {
            for (int t = 0; t < N; t++) w.tick();
        } catch (Throwable e) {
            java.lang.System.out.println("  " + label + "  ->  THREW  " + e);
            e.printStackTrace();
            return false;
        }
        int sh1 = w.cellsOfType(Blocks.SHELTER.index).size();
        int wood1 = w.cellsOfType(Blocks.WOOD.index).size();
        boolean ran = !works || (sh1 != sh0);   // works=true 时应见到 SHELTER 变化（autonomy 真跑）
        java.lang.System.out.println("  " + label + "  ->  ok  ticks=" + N
                + "   SHELTER " + sh0 + "->" + sh1 + "   WOOD " + wood0 + "->" + wood1
                + (works ? (ran ? "   [autonomy 已执行]" : "   [!! autonomy 未执行]") : ""));
        return ran;
    }
}
