package core.sim;

import core.systems.BiodiversitySystem;
import core.systems.CactusSystem;
import core.systems.DecaySystem;
import core.systems.DroughtSystem;
import core.systems.FireSpreadSystem;
import core.systems.FloodSystem;
import core.systems.FlowerSystem;
import core.systems.IceFormSystem;
import core.systems.MossSystem;
import core.systems.MushroomSystem;
import core.systems.OreExposureSystem;
import core.systems.PlantSpreadSystem;
import core.systems.ProsperitySystem;
import core.systems.RuinsSystem;
import core.systems.SandFallSystem;
import core.systems.SnowCapSystem;
import core.systems.TradeCaravanSystem;
import core.systems.TrampleSystem;
import core.systems.TreeGrowthSystem;
import core.systems.VineSystem;
import core.systems.WaterFlowSystem;
import core.systems.WeatherSystem;
import core.systems.LightningSystem;
import core.systems.SnowMeltSystem;
import core.systems.PollinateSystem;
import core.systems.CanopySystem;
import core.systems.TideSystem;
import core.systems.BeaconSystem;
import core.systems.NestSystem;
import core.systems.ReedSystem;
import core.systems.AshFallSystem;
import core.systems.WildfireSystem;
import core.systems.PondSystem;
import core.systems.LichenSystem;
import core.systems.CoralSystem;
import core.systems.CraftsmanSystem;
import core.systems.FernSystem;
import core.systems.HerdSystem;
import core.systems.BeehiveSystem;
import core.systems.GeyserSystem;
import core.systems.MistSystem;
import core.systems.ClimateSystem;
import core.systems.SeasonSystem;
import core.systems.AuroraSystem;
import core.systems.DustStormSystem;
import core.systems.HailSystem;
import core.systems.HeatwaveSystem;
import core.systems.WindSystem;
import core.systems.PollenSystem;
import core.systems.SmogSystem;
import core.systems.PredatorSystem;
import core.systems.PreySystem;
import core.systems.NestHatchSystem;
import core.systems.SporeSystem;
import core.systems.CoralReefSystem;
import core.systems.MigrationSystem;
import core.systems.ParasiteSystem;
import core.systems.SymbiosisSystem;
import core.systems.SwarmSystem;
import core.systems.MarketSystem;
import core.systems.FestivalSystem;
import core.systems.MonumentSystem;
import core.systems.RoadSystem;
import core.systems.WallSystem;
import core.systems.FarmSystem;
import core.systems.IrrigationSystem;
import core.systems.GuildSystem;
import core.systems.LawSystem;
import core.systems.LavaSystem;
import core.systems.GeodeSystem;
import core.systems.SinkholeSystem;
import core.systems.CrystalSystem;
import core.systems.MagmaChamberSystem;
import core.systems.MineralVeinSystem;
import core.systems.EarthquakeSystem;
import core.systems.VolcanoSystem;
import core.content.Preset;
import core.systems.Phase;
import core.systems.System;
import core.systems.BeastSystem;
import core.systems.BuffSystem;
import core.systems.ShrineSystem;
import core.systems.NpcSystem;
import core.systems.SocialSystem;
import core.systems.StorytellerSystem;
import core.systems.CivilizationSystem;
import core.systems.IndividualSystem;
import core.systems.PolitySystem;
import core.systems.CalamitySystem;
import core.systems.TrialSystem;
import core.systems.AscensionSystem;
import core.systems.ReactionSystem;
import core.systems.CropSystem;
import core.systems.LavaFlowSystem;
import core.systems.FurnaceSystem;
import core.systems.HopperSystem;
import core.systems.RedstoneLogicSystem;
import core.systems.CaptureSystem;
import core.systems.HaulSystem;
import core.systems.HungerSystem;
import core.systems.WireSystem;
import core.systems.MatterSystem;
import core.world.Player;
import core.world.World;

/**
 * 仿真装配：把 World + Player + 系统按固定顺序串成确定性闭环。
 * 对应 Python 的 cli / start_world.py 启动流程的 Java 版骨架。
 */
public final class Simulation {

    public final World world;
    public final Player player;
    /**
     * 状态（buff）运行时 —— Game 层把内容层的 {@code APPLY_BUFF} 落到它身上；
     * 由 {@code World.tick} 驱动（末尾追加注册，不改任何既有系统的相对顺序）。
     */
    public final BuffSystem buffs;
    /** 开局选址落点（F7 困境重生用；读档路径无记录，回落世界中心）。 */
    public final int spawnX, spawnZ;

        /** 立项 F：从已读档的 World 装配（存档读入路径；World.load 已恢复玩家）。 */
    public Simulation(World w) {
        this.world = w;
        this.player = w.player != null ? w.player : new Player();
        w.player = this.player;
        this.spawnX = w.SX / 2; this.spawnZ = w.SZ / 2;   // 读档无选址记录：回落世界中心
        if (w.chests.isEmpty()) { spawnStarterChests(this.spawnX, this.spawnZ); scatterChests(); }
        this.buffs = new BuffSystem();
        // 立项 F 修复（N2a 前置）：本路径原先漏了注册系统 → 读档后世界"冻结"
        // （NPC/文明/试炼/民政全不演化，只有地形与玩家能动）。World.load 产出的世界系统数为 0，
        // 故在此补注册；systemCount()==0 的判据使它幂等（防重复注册污染顺序）。
        if (w.systemCount() == 0) registerDefaultSystems();
        // N2-0：系统注册完成后，把读档时暂存的**系统实例状态**落位（WindSystem.t/gustX 等）。
        w.applyPendingSystemStates();
    }

public Simulation(long seed, int sx, int sy, int sz) {
        this.world = new World(seed, sx, sy, sz);
        this.player = new Player();
        // LD-2026-09-11 开局选址：不再钉死世界中心（= 涌现系统落块区 + 地形随机凹地），
        // 环形扫描最近的「3×3 开阔平坦」落点——治本「一出生就被困住」。
        int[] sp = findSpawn(sx / 2, sz / 2);
        this.spawnX = sp[0]; this.spawnZ = sp[1];
        world.safeSpawnX = sp[0]; world.safeSpawnZ = sp[1];   // 持久化安全出生点
        player.x = sp[0] + 0.5f;
        player.z = sp[1] + 0.5f;
        player.y = Player.spawnY(world, sp[0], sp[1]);
        world.player = player;            // 玩家回环由 World.tick 始终驱动
        spawnStarterChests(sp[0], sp[1]);
        scatterChests();                  // 内容扩张：世界级撒点（品质分档 + 隐藏宝箱）
        this.buffs = new BuffSystem();
        registerDefaultSystems();
    }

    /** 内容扩张：放三只确定性起始箱。位置不碰 RNG、不写网格、不进 hashState；只作为实体层容器。 */
    private void spawnStarterChests(int sx, int sz) {
        if (!world.chests.isEmpty()) return;
        int[][] off = {{3, 0}, {-3, 0}, {0, 3}};
        String[] loot = {"coal", "ore", "orb"};
        int[] qty = {4, 3, 2};
        for (int i=0; i<off.length; i++) {
            int x = Math.max(1, Math.min(world.SX-2, sx+off[i][0]));
            int z = Math.max(1, Math.min(world.SZ-2, sz+off[i][1]));
            world.chests.add(new World.Chest(x+0.5f, Player.spawnY(world,x,z), z+0.5f, loot[i], qty[i]));
        }
    }

    /**
     * 内容扩张：世界级撒点 —— 品质分档（COMMON / RARE / HIDDEN）+ 隐藏宝箱。
     *
     * <p><b>确定性</b>：随机只走 {@code world.simStream("chest:scatter")} —— 那是
     * {@code rng.deriveStream} 派生流，<b>不消耗主 rng.state</b>，故同种子逐字节同布置、
     * 对四道基线指纹零影响。箱子属实体层（不进窄 {@code hashState}），但进 StateCodec 快照
     * （联机两端一致，这是对的）。
     *
     * <p>落点：COMMON/RARE 放地表（{@code Player.spawnY}）；HIDDEN 优先钻进洞穴空气袋
     * （自地表向下找「脚下实心 + 本身与头顶皆空气」的格），找不到才回落地表。
     */
    private void scatterChests() {
        core.rng.SeededRNG r = world.simStream("chest:scatter");
        final int N = 14;
        final String[] COMMON = {"coal", "ore", "grass"};
        for (int i = 0; i < N; i++) {
            int tier = i < 9 ? World.Chest.TIER_COMMON
                    : (i < 13 ? World.Chest.TIER_RARE : World.Chest.TIER_HIDDEN);
            int x = 3 + r.nextInt(Math.max(1, world.SX - 6));
            int z = 3 + r.nextInt(Math.max(1, world.SZ - 6));
            float y = (tier == World.Chest.TIER_HIDDEN) ? findHiddenY(x, z) : -1f;
            if (y < 0f) y = Player.spawnY(world, x, z);            // 地表（或隐藏箱找不到洞穴时的回落）
            String item; int qty;
            if (tier == World.Chest.TIER_COMMON) {                 // 常见：基础资源 3..6
                item = COMMON[r.nextInt(COMMON.length)]; qty = 3 + r.nextInt(4);
            } else if (tier == World.Chest.TIER_RARE) {            // 稀有：成品/球 2..4
                item = (r.nextInt(3) == 0) ? "orb" : "iron_bar"; qty = 2 + r.nextInt(3);
            } else {                                              // 隐藏：最稀有的余烬碎片 1..2
                item = "ember_shard"; qty = 1 + r.nextInt(2);
            }
            world.chests.add(new World.Chest(x + 0.5f, y, z + 0.5f, item, qty, tier));
        }
    }

    /** 隐藏宝箱落点：自地表向下找第一个「脚下实心、本身与头顶均空气」的洞穴空气袋；无则 -1。 */
    private float findHiddenY(int x, int z) {
        int top = world.surfaceY[x][z];
        for (int y = top - 2; y >= 2; y--) {
            if (world.mat[x][y][z] == core.world.Blocks.AIR.index
                    && world.mat[x][y - 1][z] != core.world.Blocks.AIR.index
                    && world.mat[x][y + 1][z] == core.world.Blocks.AIR.index) {
                return y;
            }
        }
        return -1f;
    }

    /**
     * 开局选址：从中心（≥4 格起，避让中心列堆塔带）向外逐环扫描，
     * 取第一个环带内最靠近中心的合格点。确定性纯扫描（零 RNG、同种子同结果）；
     * 找不到合格点回落中心（与旧行为一致，保证总能出生）。
     */
    private int[] findSpawn(int cx, int cz) {
        for (int r = 4; r <= 24; r++) {
            int bestX = -1, bestZ = -1, bestD = Integer.MAX_VALUE;
            for (int dx = -r; dx <= r; dx++)
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;   // 只扫当前环带
                    int x = cx + dx, z = cz + dz;
                    if (!spawnFlatClear(x, z)) continue;
                    int d = dx * dx + dz * dz;
                    if (d < bestD) { bestD = d; bestX = x; bestZ = z; }
                }
            if (bestX >= 0) return new int[]{bestX, bestZ};
        }
        return new int[]{cx, cz};
    }

    /** 落点合格：3×3 邻列地表互差 ≤1 格（无 2+ 格墙围困）、且本列上方 2 格净空（不嵌进树冠/悬石）。 */
    private boolean spawnFlatClear(int x, int z) {
        World w = world;
        if (!w.inBounds(x, 0, z)) return false;
        int h0 = w.surfaceY[x][z];
        for (int dx = -1; dx <= 1; dx++)
            for (int dz = -1; dz <= 1; dz++) {
                int nx = x + dx, nz = z + dz;
                if (!w.inBounds(nx, 0, nz)) return false;
                int h = w.surfaceY[nx][nz];
                if (Math.abs(h - h0) > 1) return false;
                for (int y = h + 1; y <= h + 2; y++)
                    if (w.getBlock(nx, y, nz) != core.world.Blocks.AIR.index) return false;
            }
        return true;
    }

    /**
     * 注册全部涌现系统（92 个，分 10 个域）。
     *
     * <p><b>顺序即指纹</b>：这个顺序决定演化顺序，改动会改变同种子的演化结果 ——
     * 门禁 {@code SYSTEMREG} 用黄金序列断言守住它。
     * <p>新增系统：在对应域的方法里加一行 {@code reg(Phase.X, new YourSystem())} 即可。
     */
    public void registerDefaultSystems() {
        registerSignatures();      //  0- 3  世界签名 + 涌现象板
        registerEnvironment();     //  4-13  环境与生态基础
        registerClimate();         // 14-23  气候 / 地质 / 水文
        registerHydrosphere();     // 24-33  水文与生态扩散
        registerBiomes();          // 34-44  生物群系与灾害
        registerEpoch();           // 45-53  纪元与气象
        registerBiota();           // 54-62  生物链
        registerSociety();         // 63-71  社会建设
        registerDeepGeology();     // 72-80  深部地质
        registerAgents();          // 81-91  实体与高层系统
    }

    /** 注册一行：{@code phase} 只是标签，不参与排序（顺序 = 注册序 = 指纹）。 */
    private void reg(Phase p, System s) { world.addSystem(s, p); }

    /** 世界签名 + 涌现象板（第 0-3 个）。 */
    private void registerSignatures() {
        reg(Phase.SIGNATURE,  new ProsperitySystem());   // 签名：世界回响
        reg(Phase.TERRAIN,    new FireSpreadSystem());   // 涌现样板
        reg(Phase.VEGETATION, new TreeGrowthSystem());   // 涌现样板
        reg(Phase.TERRAIN,    new WaterFlowSystem());   // 涌现样板
    }

    /** 环境与生态基础（第 4-13 个）。 */
    private void registerEnvironment() {
        reg(Phase.WEATHER,    new WeatherSystem());   // 环境：降雨相位 + 浇灭地表火
        reg(Phase.TERRAIN,    new SandFallSystem());   // 物理：落沙
        reg(Phase.VEGETATION, new PlantSpreadSystem());   // 生态：草蔓延
        reg(Phase.VEGETATION, new DecaySystem());   // 生态：孤立木/叶腐朽
        reg(Phase.VEGETATION, new MushroomSystem());   // 生态：洞穴真菌（复用 LEAF）
        reg(Phase.SOCIETY,    new TradeCaravanSystem());   // 经济：繁荣过阈生成商队
        reg(Phase.VEGETATION, new BiodiversitySystem());   // 生态：森林涨落播种
        reg(Phase.VEGETATION, new TrampleSystem());   // 玩家：踩踏出小路
        reg(Phase.GEOLOGY,    new OreExposureSystem());   // 地质：崖壁矿脉裸露
    }

    /** 气候 / 地质 / 水文（第 14-23 个）。 */
    private void registerClimate() {
        reg(Phase.WEATHER,    new SnowCapSystem());   // 气象：高山积雪
        reg(Phase.WEATHER,    new IceFormSystem());   // 气象/水文：表层水结冰
        reg(Phase.VEGETATION, new CactusSystem());   // 植物：沙漠仙人掌
        reg(Phase.VEGETATION, new FlowerSystem());   // 植物：草原野花
        reg(Phase.VEGETATION, new MossSystem());   // 地质/生物：岩石覆苔
        reg(Phase.TERRAIN,    new FloodSystem());   // 水文：盆地横向漫流
        reg(Phase.TERRAIN,    new DroughtSystem());   // 水文/气象：水洼蒸发
        reg(Phase.SOCIETY,    new RuinsSystem());   // 社会：繁荣→聚落扩张
        reg(Phase.VEGETATION, new VineSystem());
    }

    /** 水文与生态扩散（第 24-33 个）。 */
    private void registerHydrosphere() {
        reg(Phase.WEATHER,    new LightningSystem());   // 气象<->火灾：落雷点燃树木
        reg(Phase.TERRAIN,    new SnowMeltSystem());   // 水文/气象：冰遇暖融为水
        reg(Phase.VEGETATION, new PollinateSystem());   // 生态：野花向邻格草丛散播
        reg(Phase.VEGETATION, new CanopySystem());   // 生态/林业：树冠郁闭
        reg(Phase.TERRAIN,    new TideSystem());   // 水文：水面潮汐涨落
        reg(Phase.SOCIETY,    new BeaconSystem());   // 社会：繁荣>=6 村庄中心亮灯
        reg(Phase.VEGETATION, new NestSystem());   // 生物：树上筑巢痕迹
        reg(Phase.VEGETATION, new ReedSystem());   // 生态/水生：水边芦苇          // 生物：藤蔓下垂
    }

    /** 生物群系与灾害（第 34-44 个）。 */
    private void registerBiomes() {
        reg(Phase.GEOLOGY,    new AshFallSystem());   // 地质/气象：空中火山灰沉降成层
        reg(Phase.VEGETATION, new WildfireSystem());   // 生态/灾害：草原邻火低概率蔓延
        reg(Phase.TERRAIN,    new PondSystem());   // 水文：洼地积水成塘
        reg(Phase.VEGETATION, new LichenSystem());   // 地质/生物：暴露石壁覆地衣
        reg(Phase.VEGETATION, new CoralSystem());   // 生态/水生：浅水沙滩长珊瑚
        reg(Phase.VEGETATION, new FernSystem());   // 生态：林下阴影长蕨
        reg(Phase.VEGETATION, new HerdSystem());   // 生物/回响：草原兽群足迹→繁荣反馈
        reg(Phase.VEGETATION, new BeehiveSystem());   // 生物：花丛密集生蜂巢迹象
        reg(Phase.TERRAIN,    new GeyserSystem());   // 地质/水文：石环浅水喷发水柱
        reg(Phase.WEATHER,    new MistSystem());   // 气象/纯视觉：清晨湿地起雾（只记状态）
        reg(Phase.SOCIETY,    new CraftsmanSystem());   // 社会/智能工匠：繁荣过阈合成工艺结构并续写记忆
    }

    /** 纪元与气象（第 45-53 个）。 */
    private void registerEpoch() {
        reg(Phase.WEATHER,    new ClimateSystem());   // 46 气候纪元：余弦温度漂移 + 低频火山落灰
        reg(Phase.WEATHER,    new SeasonSystem());   // 47 四季：冬雪/春花
        reg(Phase.WEATHER,    new AuroraSystem());   // 48 极光：夜间高纬记 event（纯视觉）
        reg(Phase.WEATHER,    new DustStormSystem());   // 49 沙尘暴：沙漠带沿风向搬运 SAND
        reg(Phase.WEATHER,    new HailSystem());   // 50 冰雹：阴云相位落 ICE 碎块
        reg(Phase.WEATHER,    new HeatwaveSystem());   // 51 热浪：窗口内 WATER 蒸发
        reg(Phase.WEATHER,    new WindSystem());   // 52 风：维护世界风矢量状态
        reg(Phase.VEGETATION, new PollenSystem());   // 53 花粉：受风偏置散播
        reg(Phase.WEATHER,    new SmogSystem());   // 54 雾霾：积累灰、压低日照因子
    }

    /** 生物链（第 54-62 个）。 */
    private void registerBiota() {
        reg(Phase.VEGETATION, new PredatorSystem());   // 55 捕食：植被邻域抹除草原兽群足迹
        reg(Phase.VEGETATION, new PreySystem());   // 56 增殖：草原低概率留新兽群足迹
        reg(Phase.VEGETATION, new NestHatchSystem());   // 57 孵化：密集巢簇邻腔补新 LEAF
        reg(Phase.VEGETATION, new SporeSystem());   // 58 孢子：真菌/苔藓上方空腔散播 LEAF
        reg(Phase.VEGETATION, new CoralReefSystem());   // 59 珊瑚礁：已有珊瑚向邻沙/水蔓延
        reg(Phase.VEGETATION, new MigrationSystem());   // 60 迁徙：足迹随季节相位沿方向迁移
        reg(Phase.VEGETATION, new ParasiteSystem());   // 61 病害：密集 LEAF 染病变 DIRT
        reg(Phase.VEGETATION, new SymbiosisSystem());   // 62 共生：树旁花/花旁树 mutual boost
        reg(Phase.VEGETATION, new SwarmSystem());   // 63 虫群：极密花丛记传粉虫群 event
    }

    /** 社会建设（第 63-71 个）。 */
    private void registerSociety() {
        reg(Phase.SOCIETY,    new MarketSystem());   // 64 集市：繁荣>=5 商队常态化往来
        reg(Phase.SOCIETY,    new FestivalSystem());   // 65 节庆：低频周期于村心点灯
        reg(Phase.SOCIETY,    new MonumentSystem());   // 66 纪念碑：繁荣>=8 村心立 SHELTER 高塔
        reg(Phase.SOCIETY,    new RoadSystem());   // 67 道路：兽群足迹固化为 DIRT 路
        reg(Phase.SOCIETY,    new WallSystem());   // 68 城墙：繁荣>=7 村周立 STONE 石环
        reg(Phase.VEGETATION, new FarmSystem());   // 69 农田：邻水 DIRT 长 LEAF 作物
        reg(Phase.VEGETATION, new IrrigationSystem());   // 70 灌溉：邻水 DIRT 记 watered 前置态
        reg(Phase.SOCIETY,    new GuildSystem());   // 71 行会：繁荣>=5 成立叙事
        reg(Phase.SOCIETY,    new LawSystem());   // 72 治安：清除过密兽群足迹
    }

    /** 深部地质（第 72-80 个）。<b>编号是"加入序号"</b>：75 号流沙已于 2026-09-18 删除，故此处缺号。 */
    private void registerDeepGeology() {
        reg(Phase.GEOLOGY,    new LavaSystem());   // 73 熔岩：近 BEDROCK 涌 FIRE 源并爬流
        reg(Phase.GEOLOGY,    new GeodeSystem());   // 74 晶洞：封闭 STONE 空腔填 COAL/IRON_ORE
        // 75 流沙（QuicksandSystem）—— 2026-09-18 批 B **删除**。它当年是为"沙落不到水下面"打的补丁：
        //   判据同样是「下方是 AIR」，只是采样式重实现，外加记一条 `quicksand.form` event ——
        //   而那条 event 全仓**零消费者**（只有产生方，没有读取方）。
        //   密度分支落地后（SandFallSystem 现在会比对 density 判断"能否沉入更轻的可位移材料"），
        //   这个补丁成为冗余，且它自己的判据永远无法表达"沙沉进水里"。
        reg(Phase.TERRAIN,    new SinkholeSystem());   // 76 陷坑：地下隧道空腔上方 SAND 塌落
        reg(Phase.GEOLOGY,    new CrystalSystem());   // 77 水晶：深岩 STONE 邻腔析出 LAMP 发光矿
        reg(Phase.GEOLOGY,    new MagmaChamberSystem());   // 78 岩浆房：近 BEDROCK 低频触发热源点 FIRE
        reg(Phase.GEOLOGY,    new MineralVeinSystem());   // 79 矿脉：STONE 邻 ORE 向邻石扩展同矿
        reg(Phase.TERRAIN,    new EarthquakeSystem());   // 80 地震：低频随机批量塌落悬空 SAND
        reg(Phase.GEOLOGY,    new VolcanoSystem());   // 81 火山：顶层低频喷灰落高处 + 冷却期
    }

    /** 实体与高层系统（第 81-91 个）。 */
    private void registerAgents() {
        reg(Phase.ENTITY,     new BeastSystem());   // 敌兵生成/移动/攻击玩家（实体，不写网格，零漂移安全）
        reg(Phase.ENTITY,     new ShrineSystem());   // 确定性祭坛 + 邻近觉醒能力（实体，不写网格，零漂移安全）
        reg(Phase.ENTITY,     new NpcSystem());   // 首 tick 确定性生成聚落 + 每 tick 驱动 body/mind/social/decision
        reg(Phase.ENTITY,     new SocialSystem());   // 邻里升温/情绪状态机 + 村庄情绪/规范 + 结亲生育
        reg(Phase.META,       new StorytellerSystem());   // 每 tick 归档转折事件 + 周期生成“当日村志”
        reg(Phase.SOCIETY,    new CivilizationSystem());   // 开放标量 + 区划覆盖层 + 事件（材料执行层由 MATERIAL_WORKS 关闭）
        reg(Phase.SOCIETY,    new IndividualSystem());   // 开放标量 + 只读网格普查 + 事件（零漂移安全）
        reg(Phase.SOCIETY,    new PolitySystem());   // 民政经济层：开放标量 + NPC 实体态（不写网格，零漂移安全）
        reg(Phase.SOCIETY,    new CalamitySystem());   // 山火/洪涝/干旱/地震 触发 + NPC 撤离信号（零漂移安全）
        reg(Phase.META,       new TrialSystem());   // 能力门解谜点 + 可交互物 + 世界目标链 + 终局
        reg(Phase.META,       new MatterSystem());   // 物相 + 熵 + 聚变辐射 + 深海（空间执行层由 MATERIAL_WORKS 关闭）
        // A 批（技能链）：状态运行时。末尾追加 → 不改变既有系统相对顺序；
        // 无 buff 时 update 首行即返回，故对一切既有种子的演化零影响。
        reg(Phase.ENTITY,     buffs);   // APPLY_BUFF 的 DOT / 减速（实体层，不进 hashState）
        // ---- 空转参数落地（2026-09-17）：7 个预设参数背后原本没有任何系统 ----
        // 全部**追加在末尾** → 既有 93 个系统的相对顺序不变；
        // 且出厂默认值下每个 update 都首行返回（零 RNG、零写入）→ 既有种子的演化逐字节不变。
        // 顺序有依赖：wire（电路）必须先于 haul（搬运）—— 搬运读同 tick 的通电节点数。
        reg(Phase.SOCIETY,    new HungerSystem());     // hungerRate：饱食度 / 饥饿惩罚（实体层）
        reg(Phase.SOCIETY,    new WireSystem());       // wireRange：电路导通（纯派生，不写网格）
        reg(Phase.SOCIETY,    new HaulSystem());       // haulRate：无人搬运（需通电；写 mat/builtMass）
        reg(Phase.ENTITY,     new CaptureSystem());    // hpThreshold + orbItemCost：捕捉驯养 / 协战
        reg(Phase.META,       new AscensionSystem());  // ascensionThreshold：修仙飞升
        // ---- 批 C（2026-09-18）：材料反应表求解器 ----
        // 追加在注册序**末尾** → 既有 97 个系统的相对顺序不变。
        // 出厂 reactionTable=false 时 update 首行即返回（零 RNG、零写入）→ 演化逐字节不变。
        reg(Phase.META,       new ReactionSystem());   // 材料反应：表驱动（reactions/*.json + 标签查询）

        // ---- 第二批「源源不断」（2026-09-23）：矿石+冶炼链 / 液体 / 作物（均免新状态层，零漂移安全）----
        // 追加在注册序**末尾** → 既有 94 个系统相对顺序不变。旧世界无作物/岩浆 → 初始 simHash 不变。
        reg(Phase.VEGETATION, new CropSystem());        // 作物：小麦/甘蔗/仙人掌花确定性生长 tick
        reg(Phase.GEOLOGY,    new LavaFlowSystem());    // 岩浆：LAVA 流体蔓延 + 引燃相邻可燃物

        // ---- 第三批「源源不断」（2026-09-23）：功能方块（门/箱/熔炉/工作台）----
        // 门/箱/工作台是**瞬时玩家动作**（渲染层 USE），无需系统；唯有"冶炼"是跨 tick 过程 → 落一个系统。
        // 追加在注册序**末尾** → 既有 96 个系统相对顺序不变；门禁世界不含熔炉 → 初始 simHash 不变。
        reg(Phase.SOCIETY,    new FurnaceSystem());     // 熔炉：相邻矿石+燃料 → 消耗 → 出锭（计数驱动，零 RNG）

        // ---- 第四批「源源不断」（2026-09-24）：漏斗定时自动抽取 ----
        // 第六批的漏斗只有"玩家右键手动抽一次"（瞬时动作）；"自动持续抽"是**跨 tick 过程** →
        // 按"瞬时 vs 跨 tick"二分类必须落系统层。追加在注册序**末尾** → 既有 97 个系统相对顺序不变；
        // 门禁世界不含漏斗 → 初始 simHash 与四道仿真指纹不变。
        reg(Phase.SOCIETY,    new HopperSystem());      // 漏斗：每 20 tick 把上方容器抽 1 件进自身（零 RNG）

        // ---- 第五批「源源不断」（2026-09-24）：红石逻辑三件套（压力板/中继器/比较器）----
        // 注册在 WireSystem **之后**：本系统读到的是本 tick 刚算出的导线通电态，而它写的三件套带电态
        // 下一 tick 才被 WireSystem 当电源消费 → 形成"每过一级延迟 1 tick"的确定流水线。
        // 追加在注册序**末尾** → 既有 98 个系统相对顺序不变；门禁世界不含这三类方块 → 指纹不变。
        reg(Phase.SOCIETY,    new RedstoneLogicSystem()); // 红石逻辑：踩踏 / 采样保持中继 / 多路符合门
    }

    /**
     * 应用玩法预设 —— <b>玩法层接通系统层的开关</b>。
     *
     * <p>流程：先把系统开关复位（{@code enableAll}），再按预设关掉整域
     * （{@code disablePhases}）或单个系统（{@code disableSystems}）。
     * 于是「换一个 JSON 文件」可以关掉一整套系统 —— 这是系统级的玩法组合。
     *
     * <p><b>零漂移</b>：默认预设（breathing_world）不含任何 disable 项，
     * 应用后 {@code activeNames == names}，演化逐字节不变。
     * 只有<b>显式</b>关掉系统的预设才会改变世界 —— 那是意图，不是事故。
     *
     * @return 人类可读报告（未知域名/系统名会标出 {@code ?}）
     */
    public String applyPreset(core.content.Preset p) {
        core.systems.SystemRegistry reg = world.registry;
        reg.enableAll();                       // 先复位（传 null 也要复位 —— 它表示"回到全开"）
        // 参数也先复位到出厂值：否则上一套预设的数值会**跨门禁/跨世界**留下来（全局可变量的经典事故）。
        world.beastCap = core.world.World.BEAST_CAP;
        core.world.DayCycle.setDayLen(core.world.DayCycle.DEFAULT_DAY_LEN);
        world.config.reset();                  // 7 个子系统参数同样复位（跨预设泄漏的经典事故）
        if (p == null) return "preset=null";
        StringBuilder sb = new StringBuilder("preset=").append(p.id);
        int n = 0;
        for (String ph : p.disablePhases) {
            try {
                n += reg.disablePhase(core.systems.Phase.valueOf(ph.trim().toUpperCase()));
            } catch (RuntimeException ex) {
                sb.append(" ?phase=").append(ph);
            }
        }
        for (String sys : p.disableSystems) {
            if (reg.disable(sys)) n++; else sb.append(" ?sys=").append(sys);
        }
        // ---- 参数消费（内容层 → 玩法层）----
        // **两层**：① 预设启用的**模块**各自的 params（模块默认值）；② 预设自身 params（覆盖模块值）。
        // 未接线的参数**一律点名**（?param= / ?modparam=）—— 与「SKIP 清单必须显式」同一纪律：
        // 静默忽略一个参数，等于让内容作者以为它生效了（审计 C8 存在的理由）。
        for (java.util.Map.Entry<String, Float> me : p.moduleParams().entrySet()) {
            if (!applyParam(me.getKey(), me.getValue().floatValue())) {
                sb.append(" ?modparam=").append(me.getKey());
            }
        }
        int consumed = 0;
        for (String k : p.params.keySet()) {
            if (applyParam(k, p.param(k, 0f))) consumed++;
            else sb.append(" ?param=").append(k);
        }
        return sb.append(" disabled=").append(n).append('/').append(reg.size())
                 .append(" params=").append(consumed).append('/').append(p.params.size()).toString();
    }

    /**
     * 应用**一个**内容层参数到玩法层；返回是否已接线（{@code false} = 未知键，调用方须点名）。
     *
     * <p>抽成单点是因为参数现在有**两个来源**（模块默认值 / 预设覆盖），必须走同一套
     * 键名映射与钳制规则 —— 两处各写一份必然漂移。
     */
    private boolean applyParam(String k, float v) {
        if ("beastCap".equals(k)) { world.beastCap = Math.max(0, Math.round(v)); return true; }
        if ("dayLengthMinutes".equals(k)) {
            core.world.DayCycle.setDayLen(core.world.DayCycle.minutesToTicks(v)); return true;
        }
        if ("erosionRate".equals(k)) { world.config.erosionRate = Math.max(0f, v); return true; }
        if ("ascensionThreshold".equals(k)) { world.config.ascensionThreshold = v; return true; }
        if ("haulRate".equals(k)) { world.config.haulRate = Math.max(0f, v); return true; }
        if ("hungerRate".equals(k)) { world.config.hungerRate = Math.max(0f, v); return true; }
        if ("orbItemCost".equals(k)) { world.config.orbItemCost = Math.max(1, Math.round(v)); return true; }
        if ("hpThreshold".equals(k)) {
            world.config.hpThreshold = Math.min(1f, Math.max(0f, v)); return true;
        }
        if ("wireRange".equals(k)) { world.config.wireRange = Math.max(0f, v); return true; }
        return false;
    }

    /** 固定步长推进一 tick（渲染只读快照，不在此驱动）。 */
    public void step() { world.tick(); }

    public void run(int ticks) { for (int i = 0; i < ticks; i++) world.tick(); }
}
