// 行为等价校验工具（无头）：用固定种子/尺寸/玩家脚本跑 N tick，打印 world.hashState()。
// 用途：优化前后两次运行若得到【完全相同】的 hash，则整条演化（含每个 RNG 抽取）逐字节一致
//       —— 比四门禁更严格（四门禁只比"同种子同代码的两次运行"，不比新旧代码差异）。
// 用法:
//   javac -cp out -d out tools/HashCheck.java
//   java  -cp out HashCheck [SX SY SZ TICKS SEED]
import core.sim.Simulation;
import core.world.Player;
import core.world.World;

public class HashCheck {
    public static void main(String[] args) {
        int SX = args.length > 0 ? Integer.parseInt(args[0]) : 96;
        int SY = args.length > 1 ? Integer.parseInt(args[1]) : 48;
        int SZ = args.length > 2 ? Integer.parseInt(args[2]) : 96;
        int TICKS = args.length > 3 ? Integer.parseInt(args[3]) : 600;
        long SEED = args.length > 4 ? Long.parseLong(args[4]) : 20260909L;

        Simulation sim = new Simulation(SEED, SX, SY, SZ);
        World w = sim.world;
        for (int t = 0; t < TICKS; t++) {
            if (t % 7 == 0) w.player.setIntent(Player.Intent.repel());
            if (t % 3 == 0) w.player.setIntent(Player.Intent.move(1, 0));
            if (t % 11 == 0) w.player.setIntent(Player.Intent.move(0, 1));
            w.tick();
        }
        long h = w.hashState();
        System.out.printf("HASHCHECK seed=%d world=%dx%dx%d ticks=%d%n", SEED, SX, SY, SZ, TICKS);
        System.out.printf("hashState = %016x%n", h);
        System.out.printf("prosperity=%d skills=%d memories=%d rngState=%016x%n",
                w.prosperity, w.skills.size(), w.villageMemory.size(), w.rng.state());
    }
}
