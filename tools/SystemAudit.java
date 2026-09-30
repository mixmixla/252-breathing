// 仿真质量审计工具（无头，不需要 GPU/GL）
// 用法:
//   javac -cp out -d out tools/SystemAudit.java
//   java  -cp out SystemAudit mut     [SX SY SZ TICKS SEED]   # 主模式：按系统统计 event + mutation + 冲突（单跑，快）
//   java  -cp out SystemAudit events  [SX SY SZ TICKS SEED]    # 仅事件计数（兼容旧模式）
//   java  -cp out SystemAudit diff    [SX SY SZ TICKS SEED]    # 排除单系统后是否改变终态（慢，82 次重跑）
//   java  -cp out SystemAudit all     [SX SY SZ TICKS SEED]    # 三者都跑
//   （末尾追加 "nointent" 可关闭玩家意图驱动，观察纯自然演化）
//
// 零漂移纪律：完全复用 Simulation.registerDefaultSystems 的固定注册顺序；仅通过 world.log 记录的事件、
// World.sysMut（每系统 setBlock 计数，由 tickAudited 归因）、world.hashState() 做量化。
// 不修改任何生产随机流、不读取 fxRng。deriveStream 为 SHA-256 派生、不消耗父 RNG，
// 故审计的随机序列与生产完全一致。
import core.rng.SeededRNG;
import core.systems.*;
import core.world.Blocks;
import core.world.Player;
import core.world.World;

import java.util.*;

public class SystemAudit {

    static boolean DRIVE = true; // 默认像 DeterminismTest 那样驱动玩家（repel 涨繁荣 + move 踩踏）

    /** 与 Simulation.registerDefaultSystems 完全一致的固定顺序。 */
    static List<core.systems.System> buildSystems(Set<String> exclude) {
        List<core.systems.System> L = new ArrayList<>();
        add(L, exclude, new ProsperitySystem());
        add(L, exclude, new FireSpreadSystem());
        add(L, exclude, new TreeGrowthSystem());
        add(L, exclude, new WaterFlowSystem());
        add(L, exclude, new WeatherSystem());
        add(L, exclude, new SandFallSystem());
        add(L, exclude, new PlantSpreadSystem());
        add(L, exclude, new DecaySystem());
        add(L, exclude, new MushroomSystem());
        add(L, exclude, new TradeCaravanSystem());
        add(L, exclude, new BiodiversitySystem());
        add(L, exclude, new TrampleSystem());
        add(L, exclude, new OreExposureSystem());
        add(L, exclude, new SnowCapSystem());
        add(L, exclude, new IceFormSystem());
        add(L, exclude, new CactusSystem());
        add(L, exclude, new FlowerSystem());
        add(L, exclude, new MossSystem());
        add(L, exclude, new FloodSystem());
        add(L, exclude, new DroughtSystem());
        add(L, exclude, new RuinsSystem());
        add(L, exclude, new VineSystem());
        add(L, exclude, new LightningSystem());
        add(L, exclude, new SnowMeltSystem());
        add(L, exclude, new PollinateSystem());
        add(L, exclude, new CanopySystem());
        add(L, exclude, new TideSystem());
        add(L, exclude, new BeaconSystem());
        add(L, exclude, new NestSystem());
        add(L, exclude, new ReedSystem());
        add(L, exclude, new AshFallSystem());
        add(L, exclude, new WildfireSystem());
        add(L, exclude, new PondSystem());
        add(L, exclude, new LichenSystem());
        add(L, exclude, new CoralSystem());
        add(L, exclude, new FernSystem());
        add(L, exclude, new HerdSystem());
        add(L, exclude, new BeehiveSystem());
        add(L, exclude, new GeyserSystem());
        add(L, exclude, new MistSystem());
        add(L, exclude, new CraftsmanSystem());
        add(L, exclude, new ClimateSystem());
        add(L, exclude, new SeasonSystem());
        add(L, exclude, new AuroraSystem());
        add(L, exclude, new DustStormSystem());
        add(L, exclude, new HailSystem());
        add(L, exclude, new HeatwaveSystem());
        add(L, exclude, new WindSystem());
        add(L, exclude, new PollenSystem());
        add(L, exclude, new SmogSystem());
        add(L, exclude, new PredatorSystem());
        add(L, exclude, new PreySystem());
        add(L, exclude, new NestHatchSystem());
        add(L, exclude, new SporeSystem());
        add(L, exclude, new CoralReefSystem());
        add(L, exclude, new MigrationSystem());
        add(L, exclude, new ParasiteSystem());
        add(L, exclude, new SymbiosisSystem());
        add(L, exclude, new SwarmSystem());
        add(L, exclude, new MarketSystem());
        add(L, exclude, new FestivalSystem());
        add(L, exclude, new MonumentSystem());
        add(L, exclude, new RoadSystem());
        add(L, exclude, new WallSystem());
        add(L, exclude, new FarmSystem());
        add(L, exclude, new IrrigationSystem());
        add(L, exclude, new GuildSystem());
        add(L, exclude, new LawSystem());
        add(L, exclude, new LavaSystem());
        add(L, exclude, new GeodeSystem());
        // 75 流沙（QuicksandSystem）已于 2026-09-18 批 B 删除 —— 见 Simulation.registerDeepGeology 的说明
        add(L, exclude, new SinkholeSystem());
        add(L, exclude, new CrystalSystem());
        add(L, exclude, new MagmaChamberSystem());
        add(L, exclude, new MineralVeinSystem());
        add(L, exclude, new EarthquakeSystem());
        add(L, exclude, new VolcanoSystem());
        // 2026-09-18 批 C：材料反应求解器（表驱动；出厂关闭时零写入）—— 与 Simulation 注册序末尾一致
        add(L, exclude, new ReactionSystem());
        return L;
    }

    static void add(List<core.systems.System> L, Set<String> ex, core.systems.System s) {
        if (ex != null && ex.contains(s.name())) return;
        L.add(s);
    }

    static World buildWorld(long seed, int sx, int sy, int sz, Set<String> exclude) {
        World w = new World(seed, sx, sy, sz);
        Player p = new Player();
        p.x = sx / 2f; p.z = sz / 2f; p.y = Player.spawnY(w);
        w.player = p;
        for (core.systems.System s : buildSystems(exclude)) w.addSystem(s);
        return w;
    }

    static void drivePlayer(World w, int t) {
        if (!DRIVE) return;
        if (t % 7 == 0) w.player.setIntent(Player.Intent.repel());
        if (t % 3 == 0) w.player.setIntent(Player.Intent.move(1, 0));
        if (t % 11 == 0) w.player.setIntent(Player.Intent.move(0, 1));
    }

    static int[] tally(World w) {
        int[] c = new int[Blocks.count()];
        for (int x = 0; x < w.SX; x++)
            for (int y = 0; y < w.SY; y++)
                for (int z = 0; z < w.SZ; z++)
                    c[w.mat[x][y][z]]++;
        return c;
    }

    // ---------------- 主模式：每系统 event + mutation + 冲突 ----------------
    static void runMut(int sx, int sy, int sz, int T, long seed) {
        World w = buildWorld(seed, sx, sy, sz, null);
        int[] before = tally(w);
        long h0 = w.hashState();
        for (int t = 0; t < T; t++) { drivePlayer(w, t); w.tickAudited(); }
        int[] after = tally(w);
        long h1 = w.hashState();

        Map<String, Integer> ev = new HashMap<>();
        for (World.Event e : w.events) ev.merge(e.system, 1, Integer::sum);
        Map<String, Integer> mut = new HashMap<>(w.sysMut);

        java.lang.System.out.println("=== SystemAudit MUT (drive=" + DRIVE + ") ===");
        java.lang.System.out.println("world=" + sx + "x" + sy + "x" + sz + " ticks=" + T + " seed=" + seed
                + " systems=" + w.systemCount() + " totalEvents=" + w.events.size()
                + " memories=" + w.villageMemory.size() + " prosperity=" + w.prosperity
                + " skills=" + w.skills);
        java.lang.System.out.println("hash0=" + Long.toHexString(h0) + " hash1=" + Long.toHexString(h1)
                + (h0 == h1 ? " [NO-CHANGE!]" : " [evolved]"));

        int active = 0, silent = 0, dormant = 0;
        java.lang.System.out.println("--- per-system: events | mutations | status ---");
        for (core.systems.System s : buildSystems(null)) {
            int n = ev.getOrDefault(s.name(), 0);
            int m = mut.getOrDefault(s.name(), 0);
            String status;
            if (m > 0 || n > 0) {
                if (n == 0 && m > 0) { status = "ACTIVE(silent)"; silent++; active++; }
                else if (m == 0 && n > 0) { status = "ACTIVE(events-only)"; active++; }
                else { status = "ACTIVE"; active++; }
            } else { status = "DORMANT"; dormant++; }
            java.lang.System.out.printf("  %-13s ev=%6d mut=%7d  %s%n", s.name(), n, m, status);
        }
        java.lang.System.out.println("--- conflict pairs (异系统短期反复改写同格，top 12) ---");
        List<Map.Entry<String, Integer>> cf = new ArrayList<>(w.dbgConflict.entrySet());
        cf.sort((a, b) -> b.getValue().compareTo(a.getValue()));
        int shown = 0;
        for (Map.Entry<String, Integer> e : cf) {
            if (shown++ >= 12) break;
            java.lang.System.out.printf("  %-26s %d%n", e.getKey(), e.getValue());
        }
        if (cf.isEmpty()) java.lang.System.out.println("  (none)");
        java.lang.System.out.println("--- block count delta (after-before) ---");
        String[] names = {"AIR","GRASS","DIRT","STONE","WATER","SAND","WOOD","LEAF","BEDROCK","LAMP",
                "SHELTER","COAL_ORE","IRON_ORE","FIRE","GLASS","SNOW","ICE","CACTUS","FLOWER","MOSS",
                "CLAY","VINE","REED","FUNGUS(LEAF)","CORAL","GEYSER(WATER)","ASH(SAND)","HAIL(ICE)","SMOG(SAND)"};
        for (int i = 0; i < names.length && i < after.length; i++) {
            int d = after[i] - before[i];
            if (d != 0) java.lang.System.out.printf("  %-14s %+d%n", names[i], d);
        }
        java.lang.System.out.println("SUMMARY active=" + active + " (silent=" + silent + ") dormant=" + dormant
                + " conflicts=" + cf.size());
    }

    // ---------------- 事件模式 ----------------
    static void runEvents(int sx, int sy, int sz, int T, long seed) {
        World w = buildWorld(seed, sx, sy, sz, null);
        int[] before = tally(w);
        long h0 = w.hashState();
        for (int t = 0; t < T; t++) { drivePlayer(w, t); w.tick(); }
        int[] after = tally(w);
        long h1 = w.hashState();

        Map<String, Integer> ev = new HashMap<>();
        for (World.Event e : w.events) ev.merge(e.system, 1, Integer::sum);

        java.lang.System.out.println("=== SystemAudit EVENTS (drive=" + DRIVE + ") ===");
        java.lang.System.out.println("world=" + sx + "x" + sy + "x" + sz + " ticks=" + T + " seed=" + seed
                + " systems=" + w.systemCount() + " totalEvents=" + w.events.size()
                + " memories=" + w.villageMemory.size() + " prosperity=" + w.prosperity);
        java.lang.System.out.println("hash0=" + Long.toHexString(h0) + " hash1=" + Long.toHexString(h1)
                + (h0 == h1 ? " [NO-CHANGE!]" : " [evolved]"));
        java.lang.System.out.println("--- per-system event counts (registered order) ---");
        int active = 0, dormant = 0;
        for (core.systems.System s : buildSystems(null)) {
            int n = ev.getOrDefault(s.name(), 0);
            if (n > 0) active++; else dormant++;
            java.lang.System.out.printf("  %-13s %6d  %s%n", s.name(), n, n > 0 ? "ACTIVE" : "DORMANT");
        }
        java.lang.System.out.println("SUMMARY active=" + active + " dormant=" + dormant);
    }

    // ---------------- 差分贡献模式 ----------------
    static void runDiff(int sx, int sy, int sz, int T, long seed) {
        java.lang.System.out.println("=== SystemAudit DIFF (exclude-one contribution, drive=" + DRIVE + ") ===");
        java.lang.System.out.println("world=" + sx + "x" + sy + "x" + sz + " ticks=" + T + " seed=" + seed);
        World full = buildWorld(seed, sx, sy, sz, null);
        for (int t = 0; t < T; t++) { drivePlayer(full, t); full.tick(); }
        long fullHash = full.hashState();
        Map<String, Integer> ev = new HashMap<>();
        for (World.Event e : full.events) ev.merge(e.system, 1, Integer::sum);

        List<core.systems.System> all = buildSystems(null);
        int active = 0, dormant = 0, conflict = 0, silent = 0;
        int total = all.size();
        for (core.systems.System s : all) {
            Set<String> ex = new HashSet<>();
            ex.add(s.name());
            World w = buildWorld(seed, sx, sy, sz, ex);
            for (int t = 0; t < T; t++) { drivePlayer(w, t); w.tick(); }
            long h = w.hashState();
            int n = ev.getOrDefault(s.name(), 0);
            boolean changed = (h != fullHash);
            String cls;
            if (n > 0 && changed) { cls = "ACTIVE"; active++; }
            else if (n > 0 && !changed) { cls = "SUSPECT-CONFLICT"; conflict++; }
            else if (n == 0 && changed) { cls = "ACTIVE(silent)"; silent++; active++; }
            else { cls = "DORMANT"; dormant++; }
            java.lang.System.out.printf("  %-13s ev=%6d %-16s %s%n", s.name(), n, cls,
                    changed ? "hash-diff" : "hash-same");
        }
        java.lang.System.out.println("SUMMARY total=" + total + " active=" + active
                + " (incl silent=" + silent + ") dormant=" + dormant + " suspectConflict=" + conflict);
    }

    public static void main(String[] args) {
        List<String> a = new ArrayList<>(Arrays.asList(args));
        if (a.remove("nointent")) DRIVE = false;
        String mode = a.isEmpty() ? "mut" : a.get(0);
        int sx = a.size() > 1 ? Integer.parseInt(a.get(1)) : 96;
        int sy = a.size() > 2 ? Integer.parseInt(a.get(2)) : 48;
        int sz = a.size() > 3 ? Integer.parseInt(a.get(3)) : 96;
        int T  = a.size() > 4 ? Integer.parseInt(a.get(4)) : 800;
        long seed = a.size() > 5 ? Long.parseLong(a.get(5)) : 20260909L;
        if ("events".equals(mode)) runEvents(sx, sy, sz, T, seed);
        else if ("diff".equals(mode)) runDiff(sx, sy, sz, T, seed);
        else if ("all".equals(mode)) { runMut(sx, sy, sz, T, seed); runEvents(sx, sy, sz, T, seed); runDiff(sx, sy, sz, T, seed); }
        else runMut(sx, sy, sz, T, seed);
    }
}
