package core.systems;

import core.agent.Npc;
import core.rng.SeededRNG;
import core.world.Blocks;
import core.world.Calamity;
import core.world.World;

import java.util.ArrayList;
import java.util.List;

/**
 * 灾害驱动系统（批次 4 · 深度打磨已覆盖系统 · 灾害驱动层）：把 Python systems/disasters.py（M21）
 * 的**条件触发 / 预警 / 撤离**路径接进 World.tick 主循环，落在 {@link Calamity} 容器。
 *
 * 与既有“扩散/搬运”系统的分工（不重复造轮子）：
 *  - {@link WildfireSystem} 只做“已有火→邻草蔓延”，**无触发器**；
 *  - {@link FloodSystem}/{@link DroughtSystem} 只做水漫流/水洼蒸发，**不读降雨**；
 *  - {@link EarthquakeSystem} 只做悬空沙塌落，**不针对房屋**；
 *  本系统的职责正是补齐 Python disasters.py 的**触发层**：干燥→点燃、连雨→洪涝、久旱→干旱、
 *  周期→地震，并对 NPC 发出撤离信号。
 *
 * 零漂移铁律（与 SocialSystem/CivilizationSystem/IndividualSystem 同）：
 *  - 状态（dryTicks/rainStreak/flag/计数）+ 预警事件 + NPC 撤离信号（只改 mind 恐惧，实体不进指纹）；
 *  - **网格效果**（点燃/抬水位/房倒）由 {@link #MATERIAL_WORKS} 开关守护，**默认关** → 网格一字不改 →
 *    四道零漂移门禁指纹与“无本系统”基线逐字节一致；
 *  - 绝不写 mat/mass/prosperity/skills/villageMemory；绝不读 world.rng 主状态或 fxRng；
 *  - 本系统**不使用随机**（Python disasters 的落点由 tick 与种子推导，Java 用 tick 相位），确定性天然成立。
 */
public final class CalamitySystem implements System {

    /**
     * 材料执行层开关。默认 false —— 让“新增灾害”不演进确定性指纹基线（项目铁律）。
     * 置 true 时启用真正的网格实体化（点燃易燃格 / 抬高水位 / 房屋→木残骸），均确定性；
     * 届时 hashState 指纹会演进（属预期），需重跑四门禁确认确定性/隔离仍成立。
     */
    public static boolean MATERIAL_WORKS = false;

    // ---- 触发参数（镜像 Python disasters 缺省 config）----
    private static final float DRY_HUMIDITY = 0.35f;      // 湿度低于此值才算“干燥”
    private static final int WILDFIRE_INTERVAL = 120;     // 干燥时每 N tick 点燃一处
    private static final int FLOOD_STREAK = 12;           // 连续降雨达此数触发洪涝
    private static final int DROUGHT_TICKS = 90;          // 连续无雨达此数触发干旱
    private static final int QUAKE_INTERVAL = 600;        // 地震周期
    private static final int QUAKE_DAMAGE = 3;            // 每次摇塌房屋格数上限
    private static final int FLOOD_RAISE_CAP = 40;        // 抬水位格数上限（材料层）

    // ---- 撤离信号 ----
    private static final float EVAC_DANGER = 0.35f;       // 每次 seeDanger 的强度（2 tick 内越过 Decision.fleeThreshold=0.5）

    @Override public String name() { return "CalamitySystem"; }

    @Override
    public void update(World w, SeededRNG rng) {
        Calamity c = w.calamity;
        trackWeather(w, c);
        c.flag = null;                 // 旗标按“当前活跃灾害”逐 tick 重算（事件型只亮触发那一刻，避免永久粘滞）
        wildfire(w, c);
        flood(w, c);
        drought(w, c);
        earthquake(w, c);
        evacuate(w, c);
    }

    // ================================================================ 天气追踪（M21 _track_weather）
    private static void trackWeather(World w, Calamity c) {
        if (w.raining) { c.rainStreak++; c.dryTicks = 0; }
        else { c.rainStreak = 0; c.dryTicks++; }
    }

    // ================================================================ 山火触发（M21 _wildfire）
    private static void wildfire(World w, Calamity c) {
        if (w.humidity > DRY_HUMIDITY || w.tick == 0) return;
        if (w.tick % WILDFIRE_INTERVAL != 0) return;
        List<int[]> flam = flammable(w);
        if (flam.isEmpty()) return;
        int pick = w.tick % flam.size();
        int[] p = flam.get(pick);
        c.flag = "wildfire"; c.wildfires++; c.alerts++;
        w.log("disaster", "wildfire_ignite", "x=" + p[0] + ",z=" + p[2], "humidity=" + round2(w.humidity));
        if (MATERIAL_WORKS) w.setBlock(p[0], p[1], p[2], Blocks.FIRE.index);   // 材料层：真正点燃（后续由既有火系统自发蔓延）
    }

    /** 当前可被点着的易燃固体表层格（GRASS/WOOD），按 (x,z) 确定性收集。 */
    private static List<int[]> flammable(World w) {
        List<int[]> out = new ArrayList<int[]>();
        for (int x = 0; x < w.SX; x++)
            for (int z = 0; z < w.SZ; z++) {
                int top = w.surfaceY[x][z];
                if (top < 0) continue;
                int m = w.mat[x][top][z];
                if (m == Blocks.GRASS.index || m == Blocks.WOOD.index || m == Blocks.LEAF.index)
                    out.add(new int[]{x, top, z});
            }
        return out;
    }

    // ================================================================ 洪涝（M21 _flood）
    private static void flood(World w, Calamity c) {
        if (c.rainStreak < FLOOD_STREAK) return;
        if (c.rainStreak == FLOOD_STREAK) {           // 上升沿：记一次触发 + 预警
            c.floods++; c.alerts++;
            w.log("disaster", "flood", "lowland", "streak=" + c.rainStreak);
        } else if (c.rainStreak % 10 == 0) {          // 持续期周期预警
            c.alerts++;
            w.log("disaster", "flood_alert", "lowland", "streak=" + c.rainStreak);
        }
        c.flag = "flood";
        if (MATERIAL_WORKS) raiseWater(w);
    }

    /** 材料层：低洼处已有明显积水者，向邻格 AIR 抬水位（开放外源，最多 FLOOD_RAISE_CAP 格）。 */
    private static void raiseWater(World w) {
        int level = w.SY / 3, raised = 0;
        for (int y = 1; y <= level && raised < FLOOD_RAISE_CAP; y++)
            for (int x = 0; x < w.SX && raised < FLOOD_RAISE_CAP; x++)
                for (int z = 0; z < w.SZ && raised < FLOOD_RAISE_CAP; z++) {
                    if (w.mat[x][y][z] != Blocks.WATER.index) continue;
                    if (neighborWater(w, x, y, z) < 2) continue;
                    for (int d = 0; d < 4; d++) {
                        int nx = x + (d == 0 ? 1 : d == 1 ? -1 : 0);
                        int nz = z + (d == 2 ? 1 : d == 3 ? -1 : 0);
                        if (w.inBounds(nx, y, nz) && w.mat[nx][y][nz] == Blocks.AIR.index
                                && Blocks.byIndex(w.getBlock(nx, y - 1, nz)).solid) {
                            w.setBlock(nx, y, nz, Blocks.WATER.index);
                            raised++;
                            break;
                        }
                    }
                }
    }

    private static int neighborWater(World w, int x, int y, int z) {
        int n = 0;
        if (w.getBlock(x - 1, y, z) == Blocks.WATER.index) n++;
        if (w.getBlock(x + 1, y, z) == Blocks.WATER.index) n++;
        if (w.getBlock(x, y, z - 1) == Blocks.WATER.index) n++;
        if (w.getBlock(x, y, z + 1) == Blocks.WATER.index) n++;
        return n;
    }

    // ================================================================ 干旱（M21 _drought）
    private static void drought(World w, Calamity c) {
        if (c.dryTicks < DROUGHT_TICKS) return;
        if (c.dryTicks == DROUGHT_TICKS) {            // 上升沿
            c.droughts++; c.alerts++;
            w.log("disaster", "drought_begin", "farmland", "dry=" + c.dryTicks);
        } else if (c.dryTicks % 30 == 0) {            // 持续期周期记录
            w.log("disaster", "drought_alert", "farmland", "dry=" + c.dryTicks);
        }
        if (c.flag == null) c.flag = "drought";       // 不覆盖更靠前的山火/洪涝/地震旗标
        if (MATERIAL_WORKS) evaporate(w);
    }

    /** 材料层：地表水加速蒸发（开放外源出账，最多 200 格/tick）。 */
    private static void evaporate(World w) {
        int done = 0;
        for (int[] cell : new ArrayList<int[]>(w.cellsOfType(Blocks.WATER.index))) {
            if (done >= 200) break;
            int x = cell[0], y = cell[1], z = cell[2];
            if (w.mat[x][y][z] != Blocks.WATER.index) continue;
            if (y + 1 < w.SY && w.mat[x][y + 1][z] != Blocks.AIR.index) continue;
            w.setBlock(x, y, z, Blocks.AIR.index);
            done++;
        }
    }

    // ================================================================ 地震（M21 _earthquake）
    private static void earthquake(World w, Calamity c) {
        if (w.tick == 0 || w.tick % QUAKE_INTERVAL != 0) return;
        List<int[]> houses = new ArrayList<int[]>(w.cellsOfType(Blocks.SHELTER.index));
        if (houses.isEmpty()) return;
        int n = Math.min(QUAKE_DAMAGE, houses.size());
        c.flag = "earthquake"; c.quakes++; c.alerts++;
        w.log("disaster", "earthquake", "houses", "shaken=" + n + ",to=wood");
        if (MATERIAL_WORKS) {
            for (int i = 0; i < n; i++) {
                int[] p = houses.get((w.tick + i) % houses.size());
                // 房屋 -> 木残骸。⚠️ 早期注释写"材料重排，质量不变"是**错的**（属"标签说谎"）：
                // density(SHELTER)=14 ≠ density(WOOD)=12 ⇒ 每格实为 -2。物质账本门禁 MATLEDGER 已钉住该 Δ。
                w.setBlock(p[0], p[1], p[2], Blocks.WOOD.index);
            }
        }
    }

    // ================================================================ 撤离（M21 danger 感知）
    /**
     * 本地危险感知：仅当村民所在列**附近真有火/洪水**时才撤离（恐惧上升 → Decision 选 FLEE 逃跑）。
     *
     * 关键（避免永久恐慌）：Python 的 danger 感知是**局部**的（NPC 看到眼前的火/水才逃），
     * 而非“全局旗标一置就全村慌”。若按全局旗标见谁吓谁，久旱旗标会长期滞留 → 全村永久 scared、
     * 不再漫步（NpcSystem 的 WANDER 永不触发），世界被冻住。故此处改为按 NPC 邻近 5×5 列扫描 FIRE/淹没。
     */
    private static void evacuate(World w, Calamity c) {
        boolean any = false;
        boolean quake = "earthquake".equals(c.flag);   // 地震是全域摇晃，所有村民受惊
        for (Npc n : w.npcs) {
            if (n.dead()) continue;
            if (quake) { n.mind.seeDanger(EVAC_DANGER); any = true; continue; }
            int x = (int) Math.floor(n.x), z = (int) Math.floor(n.z);
            if (!w.inBounds(x, 0, z)) continue;
            if (nearHazard(w, x, z)) { n.mind.seeDanger(EVAC_DANGER); any = true; }
        }
        if (any) c.evacuations++;    // 按“撤离波次”计数（非按 NPC 逐 tick，避免计数虚高）
    }

    /** 该列邻近 5×5 范围内是否有火，或村民站立处被水淹。 */
    private static boolean nearHazard(World w, int x, int z) {
        for (int dx = -2; dx <= 2; dx++)
            for (int dz = -2; dz <= 2; dz++) {
                int nx = x + dx, nz = z + dz;
                if (!w.inBounds(nx, 0, nz)) continue;
                int top = w.surfaceY[nx][nz];
                if (top < 0) continue;
                if (w.mat[nx][top][nz] == Blocks.FIRE.index) return true;
                if (top + 1 < w.SY && w.mat[nx][top + 1][nz] == Blocks.WATER.index) return true;
            }
        return false;
    }

    private static float round2(float v) { return Math.round(v * 100f) / 100f; }
}
