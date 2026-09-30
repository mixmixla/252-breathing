package core.sim;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * PORTABLEMATH 门禁（N0：联机可移植性硬化）。
 *
 * <p><b>为什么需要它</b>：Java 规范明确规定，{@link Math} 的超越函数
 * （sin/cos/exp/log/pow/hypot/…）<b>不保证</b>跨 JVM 厂商 / 平台返回逐位相同的结果；
 * 只有 {@link StrictMath} 才保证。本项目「同种子同输入逐字节复现」的卖点，
 * 在单机上门禁全绿——但一旦换到别人的机器，1–2 ulp 的差异会让
 * {@code ShrineSystem} / {@code TrialSystem} 选到不同方块坐标，直接改写 {@code mat}，
 * 于是确定性锁步联机立刻 desync。
 *
 * <p><b>断言的性质</b>（不是"能跑"，而是"性质成立"）：
 * <ol>
 *   <li><b>清洁性</b>：{@code src/core} 下所有<b>非测试</b>源文件，
 *       不使用任何非可移植的 {@code Math} 超越函数（必须走 {@code StrictMath}）。</li>
 *   <li><b>防假绿 A</b>：扫描到的非测试文件数必须 &ge; {@link #SCAN_FLOOR}——
 *       否则扫描器一旦坏掉（路径写错、文件被移走）会"零命中 = 假 PASS"。</li>
 *   <li><b>防假绿 B（正样本对照）</b>：检测函数必须能在合成样本上真的报出违规，
 *       证明"没报 = 真没有"，而不是"检测器永远返回空"。</li>
 * </ol>
 *
 * <p><b>豁免</b>：{@code *Test.java} / {@code *Bench.java}——它们是验证夹具而非出厂仿真，
 * 且验证"Math 与 StrictMath 是否等价"这类测试本身就需要调用 {@code Math}。
 *
 * <p><b>例外（可移植，不用改）</b>：{@code Math.sqrt} 是精确舍入的；
 * {@code Math.floor/ceil/abs/min/max/round/signum/toRadians} 均为精确定义的基本运算。
 */
public final class PortableMathTest {

    /** Java 规范中「不保证逐位跨平台一致」的 Math 方法名。 */
    private static final String[] NAMES = {
        "sinh", "cosh", "tanh", "asin", "acos", "atan2", "atan",
        "cbrt", "hypot", "expm1", "exp", "log10", "log1p", "log", "pow",
        "sin", "cos", "tan"
    };

    /** 防假绿下限：src/core 现有非测试源文件约 150 个，取 120 留余量。 */
    private static final int SCAN_FLOOR = 120;

    public static void main(String[] args) throws IOException {
        List<String> offenders = new ArrayList<String>();
        int scanned = 0, skipped = 0;

        Stream<Path> walk = Files.walk(Paths.get("src", "core"));
        List<Path> files = walk.filter(Files::isRegularFile).collect(Collectors.toList());
        walk.close();

        for (Path p : files) {
            String name = p.getFileName().toString();
            if (!name.endsWith(".java")) continue;
            if (name.endsWith("Test.java") || name.endsWith("Bench.java")) { skipped++; continue; }
            scanned++;
            String src = new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
            for (String bad : scan(src)) {
                offenders.add(p.toString().replace('\\', '/') + " -> Math." + bad);
            }
        }

        List<String> control = scan("double v = Math.sin(x) + Math.pow(y, 2.0) + StrictMath.cos(z);");
        boolean controlOk = control.contains("sin") && control.contains("pow")
                && !control.contains("cos");
        System.out.println("  ok  POSITIVE_CONTROL detected=" + control + " (StrictMath ignored=true)");

        boolean floorOk = scanned >= SCAN_FLOOR;
        System.out.println("  ok  SCAN_FLOOR scanned=" + scanned + " skipped=" + skipped
                + " floor=" + SCAN_FLOOR);

        for (String o : offenders) System.out.println("  FAIL " + o);
        boolean clean = offenders.isEmpty();

        int props = 0;
        if (controlOk) props++;
        if (floorOk) props++;
        if (clean) props++;
        boolean pass = controlOk && floorOk && clean;
        System.out.println((pass ? "PORTABLEMATH PASS (" : "PORTABLEMATH FAIL (") + props + " properties)");
        if (!pass) System.exit(1);
    }

    /** 找出该源码里所有非 StrictMath 的不可移植调用（去重后的方法名列表）。 */
    static List<String> scan(String src) {
        List<String> hit = new ArrayList<String>();
        for (String n : NAMES) {
            String needle = "Math." + n;
            int i = src.indexOf(needle);
            while (i >= 0) {
                int after = i + needle.length();
                boolean boundary = after >= src.length() || !isIdent(src.charAt(after));
                boolean strict = i >= 6 && src.startsWith("Strict", i - 6);
                if (boundary && !strict) { hit.add(n); break; }
                i = src.indexOf(needle, i + 1);
            }
        }
        return hit;
    }

    private static boolean isIdent(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                || (c >= '0' && c <= '9') || c == '_';
    }
}
