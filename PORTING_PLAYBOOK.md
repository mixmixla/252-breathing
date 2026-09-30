# 81 系统机械移植手册（Java 版）

## 目标
把 `systems/` 下 81 个 Python 系统逐一搬到 `src/core/systems/`，使 Java 版仿真在功能上对齐 Python 版"会呼吸的世界"涌现仿真，并守零漂移铁律。

---

## 每个系统的 5 步模板

以 `ProsperitySystem`（已完成的签名系统）作范例，其余系统照抄。

### Step 1 · 读 Python 系统
打开 `systems/<name>.py`，记下：
- 类名、对外依赖（读 world 哪些字段）
- 是否产生/消费事件（写 `world.events` vs 读 `world.events`）
- 是否需要随机（采样分布）
- 是否修改世界状态（写 `mat` / `mass` / `prosperity` / `skills` / `villageMemory`）

### Step 2 · 写 Java 类骨架
```java
package core.systems;

import core.rng.SeededRNG;
import core.world.World;

public final class <Name>System implements System {
    @Override public String name() { return "<name>"; }   // 务必稳定，签名进入 hashState（skills/villageMemory 排除了，但 simStream 名进入 rng 序列）
    @Override
    public void update(World w, SeededRNG rng) {
        // 你的逻辑
    }
}
```

### Step 3 · 把 Python 的 numpy 操作换成 World 数组
- `world.mat[x][y][z]`（int 三维）代替 numpy `mat[y,x]`
- `world.mass[x][y][z]`（float）
- 邻域：`w.getBlock(x,y,z)` / `w.setBlock(x,y,z,idx)` / `w.inBounds(...)`

注意维度顺序：Python 是 `(y, x)` 行主序；Java 这里是 `(x, y, z)`。**移植时务必逐处核对维度映射**，否则会引入隐式 bug。

### Step 4 · 随机走 simStream 子流
凡 Python 用 `rng = world.rng.spawn(f"eco:{tick}")` 的地方，Java 直接使用 `update` 的入参 `rng`（已经由 `World.tick` 用 `world.simStream(name+":"+tick)` 派生了）。

不要在系统里调用 `world.rng.nextDouble()`（那是 master，会破坏流隔离）。**永远只用入参 rng**。

### Step 5 · 注册到 Simulation
在 `Simulation.registerDefaultSystems()` 里追加 `world.addSystem(new <Name>System());`。注意**顺序影响确定性演化结果**（虽然零漂移门禁只检 fingerprint 一致性，但行为差异会改变 fingerprint）。按 Python 注册顺序排。

---

## 现有 34 个已移植系统（作模板）

| 系统 | 文件 | 行为 |
| --- | --- | --- |
| ProsperitySystem | `ProsperitySystem.java` | 读 `world.events` 累计 prosperity；过阈解锁蓝图 + 写 village_memory。签名系统。 |
| FireSpreadSystem | `FireSpreadSystem.java` | FIRE 方块向相邻 WOOD/LEAF 蔓延，自身可熄灭。用 simStream 随机。 |
| TreeGrowthSystem | `TreeGrowthSystem.java` | 抽样地表 GRASS，按低概率长树。用 simStream。 |
| WaterFlowSystem | `WaterFlowSystem.java` | WATER 沉降到下方空腔（确定性，无 rng）。 |
| WeatherSystem | `WeatherSystem.java` | 环境：推进降雨相位，雨时按低概率浇灭地表火焰（天气↔火灾耦合）。仅状态字段，不新增方块。 |
| SandFallSystem | `SandFallSystem.java` | 物理：SAND 下方为 AIR 时下落一格（stride 扫描，确定性无 rng）。 |
| PlantSpreadSystem | `PlantSpreadSystem.java` | 生态：地表 GRASS 以低概率向相邻 DIRT 蔓延，世界变绿。 |
| DecaySystem | `DecaySystem.java` | 生态：四周皆 AIR 的孤立 WOOD/LEAF 按低概率腐朽为 AIR，清理砍伐残块。 |
| ErosionSystem | `ErosionSystem.java` | 反应：WATER 正上方 DIRT 低概率变 SAND（水土流失成岸沙）。 |
| MushroomSystem | `MushroomSystem.java` | 生态：封闭暗穴（AIR 被 STONE 夹住≥5 面）低概率长真菌，复用 LEAF 占位。 |
| TradeCaravanSystem | `TradeCaravanSystem.java` | 经济：prosperity≥2 时按 rng 在村庄附近生成"商队抵达"记忆/事件。 |
| BiodiversitySystem | `BiodiversitySystem.java` | 生态：统计 LEAF/WOOD 体量，空地按"越稀越易种"概率补种新树，森林涨落。 |
| TrampleSystem | `TrampleSystem.java` | 玩家反馈：玩家所站地表 GRASS 低概率被踩成 DIRT，留下小路。 |
| OreExposureSystem | `OreExposureSystem.java` | 地质：暴露空气中的 STONE 低概率风化出 COAL_ORE/IRON_ORE 矿脉。 |
| SnowCapSystem | `SnowCapSystem.java` | 气象：高海拔（雪线以上）地表以低概率在上方空腔积 SNOW（新增 SNOW 方块）。 |
| IceFormSystem | `IceFormSystem.java` | 气象/水文：暴露在空气中的表层 WATER 低概率冻成 ICE（新增 ICE 方块）。 |
| CactusSystem | `CactusSystem.java` | 植物：沙地表面、四周无遮挡时低概率向上长 CACTUS 柱（新增 CACTUS 方块）。 |
| FlowerSystem | `FlowerSystem.java` | 植物：草原地表低概率长 FLOWER 装饰（新增 FLOWER 方块）。 |
| MossSystem | `MossSystem.java` | 地质/生物：暴露空气中的表层 STONE 低概率覆 MOSS（新增 MOSS 方块）。 |
| ClaySystem | `ClaySystem.java` | 地质/水文：临水的 SAND 低概率风化 CLAY（新增 CLAY 方块）。 |
| FloodSystem | `FloodSystem.java` | 水文：WATER 向相邻有支撑的空腔横向漫延，填平盆地（泉涌式，不守恒演示）。 |
| DroughtSystem | `DroughtSystem.java` | 水文/气象：暴露空气中的表层 WATER 极低概率蒸发为 AIR（蒸干水洼）。 |
| RuinsSystem | `RuinsSystem.java` | 社会：prosperity≥4 时按 rng 在草原上生成 SHELTER 暖屋 + 中心 LAMP，写村庄记忆。 |
| VineSystem | `VineSystem.java` | 生物：WOOD/LEAF 正下方为空气时低概率向下垂生 LEAF（藤蔓爬地）。 |
| LightningSystem | `LightningSystem.java` | 气象↔火灾：低频掷骰落雷，命中地表 WOOD/LEAF 即点燃 FIRE（与 Weather 浇灭形成正负反馈）。 |
| SnowMeltSystem | `SnowMeltSystem.java` | 水文/气象：ICE 邻格有非冰非雪暖来源时低概率融为 WATER（与 Frozen 相反）。 |
| FrozenSystem | `FrozenSystem.java` | 水文/气象：WATER 邻 ICE 时低概率冻成 ICE，寒冷沿水面扩散。 |
| PollinateSystem | `PollinateSystem.java` | 生态：地表 FLOWER 低概率向相邻 GRASS 列散播新花，草原繁花。 |
| CanopySystem | `CanopySystem.java` | 生态/林业：WOOD 顶部有空间时低概率长 LEAF 树冠，森林郁闭。 |
| TideSystem | `TideSystem.java` | 水文：固定相位在水面表层 WATER<->AIR 翻转，模拟涨落潮（确定性无 rng）。 |
| BeaconSystem | `BeaconSystem.java` | 社会：prosperity≥6 时村庄中心低概率亮起 LAMP（繁荣灯塔，夜间可见）。 |
| MossSpreadSystem | `MossSpreadSystem.java` | 地质/生物：MOSS 低概率向相邻 STONE 蔓延，阴湿岩壁披苔。 |
| NestSystem | `NestSystem.java` | 生物：WOOD 邻 LEAF 时低概率在邻格 AIR 放 LEAF 巢（栖息痕迹）。 |
| ReedSystem | `ReedSystem.java` | 生态/水生：WATER 正上方为 AIR 时低概率向上长 LEAF 芦苇。 |

> 方块现状：初始 15 个，P2 续批追加 **SNOW/ICE/CACTUS/FLOWER/MOSS/CLAY 共 6 个** → 现 **21 个**。
> 索引按注册顺序追加，既有索引不变；渲染层从 `Blocks.byIndex(idx).r/g/b` 取色，新方块自动上色。
> 全部系统随机只走入参 `rng`（即 `world.simStream(name+":"+tick)`），固定迭代顺序，
> `DeterminismTest` / `ZeroDriftTest` / `PhysicsTest` / `StreamingTest` 四道零漂移基线门禁均 PASS（全项目共 **43 道**，以 `build_runner.py` 的 `gate(...)` 为准；指纹随系统增多而变，同种子仍自洽）。

---

## 无限世界流式（P3 · 滑动窗口）

Java 版世界是**有限窗口 + 按全局坐标确定性生成**的无限世界，而不是固定大数组。

### 关键纪律（零漂移核心）
- `World.mat` 仍是固定 `int[SX][SY][SZ]`，但索引的是**窗口本地坐标**；整窗覆盖全局块
  `[winCX0, winCX0+CX) × [winCZ0, winCZ0+CZ)`。**所有 16 个系统、渲染层直接读 `w.mat[x][y][z]` 零改动**——
  它们只在"已加载窗口"内演化，天然等同于"只模拟可见区块"。
- 地形由**全局坐标**的确定性值噪声 `noise(gx, gz, seed)` 生成；树的随机流按**全局块坐标**派生
  `rng.deriveStream("tree:" + gcx + ":" + gcz)`。`deriveStream` 是纯 SHA256 派生（不消耗父状态），
  所以**区块加载顺序不影响结果**——这是流式下仍逐字节复现的关键。
- `streamTo(gcx, gcz)`：玩家所在全局块坐标 → 若需平移窗口（半宽 `R = CX/2`）则 `shiftWindow`：
  1) 离开窗口的块快照进 `chunkEdits`（持久化玩家/系统编辑，MC 建造感）；
  2) 整窗重生成（基岩 + 编辑覆盖）；
  3) 玩家本地坐标按平移量同步移动（物理/渲染连续）；4) `markAllChunksDirty` 触发渲染全量重建。
- `Game.loop` 每帧 `world.streamTo(windowOriginCX + floor(player.x/16), windowOriginCZ + floor(player.z/16))`
  跟随玩家流式；不跨块时 `streamTo` 直接返回，零开销。

### 门禁
- `StreamingTest`（第 4 道）：① 同种子 + 同玩家路径 → `hashState()` 一致；② 玩家放的 LAMP 在区块
  卸载后再重载仍然存在（`chunkEdits` 覆盖生效）。`build.bat` 会跑它。

### 注意
- `SX/SZ` 必须是 `CHUNK(16)` 整数倍（当前 96=6×16）。
- 既有三道门禁**不调用 `streamTo`**，玩家停在窗口中心不触发平移 → 行为与旧版一致，仅树的布局因
  改为按块派生而不同（fingerprint 变化但同种子自洽，属预期）。

---

## 81 系统大致分类与移植优先级

按 Python `systems/` 目录观察大致分为（具体名以你 `ls systems/` 为准）：

1. **环境类**（weather / climate / day-night）→ 移植到 World 状态字段或独立系统；多数用 rng 采样
2. **反应类**（fire / reaction / chemistry）→ 仿照 FireSpreadSystem
3. **生物类**（flora / fauna / ecology）→ 仿照 TreeGrowthSystem
4. **生态/涌现类**（cascade / contagion / economy）→ 多半是事件驱动，仿照 ProsperitySystem 读 events
5. **叙事/AI 类**（villager / dialogue / quest）→ 调 talk()/village_memory

**建议每批移植 5-10 个**，跑一次 `DETERMINISM PASS` + `ZERO-DRRIFT PASS` 确认零回归，再继续。

---

## 移植后必跑的验证（43 道门禁，权威清单见 `build_runner.py` 的 `gate(...)`）

1. `build.bat` / `build_runner.py` 全绿（含下面全部门禁）
2. `java -cp out core.sim.DeterminismTest` → `DETERMINISM PASS`（同种子同输入逐字节一致；基线 `74ad826636fe8eb2`）
3. `java -cp out core.sim.ZeroDriftTest` → `ZERO-DRIFT PASS`（fxRng 乱用不影响 sim 指纹；基线 `5ce9392207387ebf`）
4. `java -cp out core.sim.PhysicsTest` → `PHYSICS PASS`（落地/跳跃/撞墙）
5. `java -cp out core.sim.StreamingTest` → `STREAMING PASS`（流式下确定性 + 建造跨卸载重载持久化；基线 `87d5bf1cecb4c628`）
6. **每加一层就补一道层门禁**（同种子两遍自洽）：`NpcDeterminismTest` / `SocialDeterminismTest` / `StorytellerDeterminismTest` / `CivilizationDeterminismTest` / `IndividualDeterminismTest` / `PolityDeterminismTest` / `TrialDeterminismTest`
7. `java -cp out core.sim.WeaponArtTest` → `WEAPON-ART PASS`（**玩家动作层**行为断言 + 零漂移不变量；见 §O）
8. `java -cp out core.sim.MenuTest` → `MENU PASS`（**UI/流程层**：状态机 + 设置钳制 + **暂停透明性**；见 §P）
9. `java -cp out core.sim.DayNightTest` → `DAYNIGHT PASS`（**天空/昼夜层**：连续性 + 取值域/单调 + **视线射线基** + 纯函数零漂移 + 极光防死分支；见 §Q）
10. `java -cp out core.sim.AudioTest` → `AUDIO PASS`（**音频层**：纯函数 PCM 确定性 + 包络起收为 0 + 声部池上限/抢占/老化 + 静音语义 + 混音契约 + 零漂移不变量；见 §R）
11. `java -cp out core.sim.MatterDeterminismTest` → `MATTER PASS`（**抽象字段层**：同种子自洽 + **可达性**(字段非死/多相) + **指纹隔离证明** + **零 RNG 证明**；见 §T）
12. `java -cp out core.sim.MegalithDeterminismTest`
13. `java -cp out core.sim.SaveLoadTest`          # 门禁 18：存读档（存→读→0-tick 与 300-tick 演化 hash 双一致） → `MEGALITH PASS`（**巨构神殿**：同种子自洽(含材化) + 出生锚点可达 + 结构探针(TOWER 门洞 / GATE 通道) + 滑动复现 + **小世界豁免**(基线零漂移机理)）
14. `java -cp out core.sim.StreamChunkTest` → `STREAMCHUNK PASS`（**M3 规模层**：分帧流式等价 + 推进单调 + 索引惰性等价 + **INDEX 分片等价** + 邻域去 `O(N²)` 等价）
15. `java -cp out core.sim.PortableMathTest` → `PORTABLEMATH PASS`（**N0 联机可移植性层**：src/core 禁非 `StrictMath` 超越函数 + 扫描下限 + 正样本对照）
16. `java -cp out core.sim.NetCodecTest` → `NETCODEC PASS`（**N1 联机输入层**：6 字节协议稳定 + 无损往返 + 拒绝而非截断 + 逐字节确定性 + 惰性证明）
17. `java -cp out core.sim.SnapshotStateTest` → `SNAPSTATE PASS`（**N2-0 快照完备性**：与 `StateCodec` 同源的「跳过字段 + 理由」审查 + 跳过数基线 + 防假绿）

> 判据铁律：**前三道（DETERMINISM / ZERO-DRIFT / STREAMING）的指纹必须逐字节不变**；一旦变了，先按 §A/§G 排查是否误写了 `mat/mass/prosperity/skills/villageMemory/builtMass`。而**玩家动作层 / UI 流程层 / 表现层的退化指纹门禁抓不到**——必须靠 §O/§P/§Q/§R 那种行为/透明性/连续性/契约断言门禁。

---

## 反向参考：Python → Java 命名对照

| Python | Java |
| --- | --- |
| `class XSystem: def update(world, rng): ...` | `class XSystem implements System { void update(World w, SeededRNG rng) }` |
| `self.world.rng.spawn(f"eco:{tick}")` | `rng`（入参，即 `world.simStream(name+":"+tick)`） |
| `self.world.events.append(Event(...))` | `w.log(system, action, params, result)` |
| `self.world.mat[y, x]` | `w.mat[x][y][z]`（注意维序） |
| `self.world.mass[y, x]` | `w.mass[x][y][z]` |
| `self.world.prosperity += 1` | `w.prosperity++` |
| `self.world.skills.add(id)` | `w.addSkill(id)` |
| `self.world.village_memory.append(...)` | `w.recordMemory(...)` |
| `self.world.in_bounds(x,y)` | `w.inBounds(x,y,z)` |
| `self.world.get_block(x,y)` | `w.getBlock(x,y,z)` |
| `self.world.set_block(x,y,idx)` | `w.setBlock(x,y,z,idx)` |

---

## 实体级/社会层系统移植（NPC-SOC 系列，批次 0/1 实战沉淀）

`systems/` 里有一大类（family / emotion / norms / dialogue / civilization / religion…）**依赖 NPC 社会层**，不是体素系统。移植它们走下面的补充纪律。

### A. 状态放哪：实体级状态一律不进 `hashState()`
- `hashState()` 只覆盖 `mat/mass/prosperity/tick/rng.state()/skills/villageMemory/builtMass`。
- NPC 及其社会态（hunger/affinity/mood/家族/情绪/规范）是**实体级开放标量**，与 `beasts/shrines/npcs` 同纪律：**不写上述任何指纹字段**，故对全部门禁（当前 43 道）指纹**零影响**。
- 好处：接入后 `DETERMINISM` 指纹可与接入前**逐字节一致**（本项目把它当"安全新增"铁证）。批次 0/1 接入后指纹仍为 `21a2de200fda8a8b`。
- 集中式容器优先：村庄级标量（family/emotion/norms）放一个 `core.agent.VillageSocial`，`World` 只加一个 `public final VillageSocial social` 字段（比往 `World` 堆十几个字段干净）。

### B. `simStream` 不推进主 rng → 可放心生成/驱动
`SeededRNG.deriveStream` 用**主种子 `seed`**（非 `state`）派生子流，所以系统里用 `w.simStream(name)` 取随机**不会推进 `rng.state()`**。这意味着：即便系统每 tick 生成/驱动实体，`hashState()` 里的 `rng.state()` 也不变。这是"接入却指纹不变"能成立的理论依据。

### C. `events` 是共享总线：写之前先确认没人消费
- `events` 不进指纹，但 **`ProsperitySystem` 会扫它**（只匹配 `system=="combat" && action=="repel"`），并推进 `eventsProcessed` 水印。
- 因此**写 `w.log(...)` 是安全的**——只要不产出 `combat/repel`。social 层写 `npc/marry`、`npc/birth`、`npc/dialogue`、`emotion/*`、`norms/*` 都不被消费。
- ⚠️ 若未来新增会消费 `events` 的系统，**必须复核所有事件写入点**（含渲染层），否则渲染层注入会反向影响仿真（sim↔render 反向耦合）。

### D. 真实随机 vs 确定性：能确定性就别用 rng
`family.py` 的结亲/生育是**纯确定性**的（排序 + 阈值 + 无 rng）。移植时**不要**给它塞 rng——确定性越强，门禁越稳，也越省心。只有 Python 里真正 `rng.xxx()` 的地方才走 `simStream`。

### E. 渲染层可安全驱动实体（但别碰仿真状态）
- 渲染层（`Game.java`）可以调 `Dialogue.talk(...)` 这类"只改实体内部态 + 写 inert events"的接口——对仿真零影响。
- 渲染层**绝不能**写 `mat/mass/prosperity/villageMemory`，也不要推进 `rng`。
- 中文文本：`Font.java` 仅含 ASCII，**中文会静默空白**。中文回话先落 UTF-8 日志（`dialogue.log`）供阅读；上屏需引入 CJK 位图字体（✅ 已由 C1 `CjkFont` 落地，中文气泡 / 村志浮层已上屏）。屏幕标签一律用 ASCII（如 `TALK farmer DILIGENT CALM`）。

### F. JS 侧基线不受影响
Java 版独立守全部门禁（当前 43 道，含四道零漂移基线）；动 Java 不影响浏览器原型 `sim_core.js` 的 75% 胜率基线。**不要**为了对齐 Java 去改 JS。

### G. 派生只读层（叙事/记忆/统计）——「不进 hashState」的叙事能力
像 `storyteller` / `village_memory` / `dashboard` 这类**把既有状态翻译成人能读的文本**的系统，走独立纪律：
- **放独立容器、绝不进 `hashState()`**：本项目 `core.world.Chronicle`（村志档案库 + 字符级 bigram Jaccard 检索 + 叙述生成）。`World` 只加一个 `public final Chronicle chronicle` 字段。
- **只读不写**：只读 `events` / `npcs` / `social`，生成文本；**绝不写** `mat/mass/prosperity/skills/villageMemory`。故即便每 tick 生成叙述，`DETERMINISM` 指纹也与接入前**逐字节一致**（本项目实测 `21a2de200fda8a8b` 不变）。
- **用独立水印，别抢别人的**：`ProsperitySystem` 用 `world.eventsProcessed` 消费事件；派生层用**自己的** `absorbed` 水印（`Chronicle.absorbed`）扫 `events`，互不干扰。
- **别 `w.log`**：派生层若再写事件，会形成「归档事件→产生新事件→再归档」的回馈环。派生层**只读 events，不写 events**。
- **长跑要有上限**：档案列表加 `EPISODE_CAP`（本项目 600）截断（保留最近 N 条），计数 `counts` 累积不回落——避免超长跑无界增长。
- **有随机才走 simStream**：模板拼装是纯确定性，别塞 rng。
- **消费端**：给 UI 时中文先落 UTF-8 日志（`chronicle.log`），屏幕只放 ASCII 摘要（`CHRONICLE D… REPEL/MARRY/BIRTH`）；再补一个 `XxxDeterminismTest` 断言「档案序列 + 全文」同种子逐字节一致（本项目第 7 道门禁 `StorytellerDeterminismTest`）。

### H. 材料执行层开关（写 mat/mass 的系统）——批次 2 文明深度实战沉淀
像 `tech`（自治修墙/重建）、`industry`（网格磨蚀/铺缆）这类系统**会写 `mat`/`mass`**，而 `hashState()` 恰含 `mat/mass` —— 照搬会让指纹基线演进。采取**决策层与材料执行层分离**：
- **决策/数字层完整移植并激活**：研究点/解锁/乘子/蓝图/账本/区划覆盖层/外交/军政/文化 = 开放标量 + `int[][]` 覆盖层 + `events`，**不进 `hashState`** → 指纹与接入前逐字节一致（本项目实测 `21a2de200fda8a8b` 不变）。
- **材料执行层收进静态开关**：如 `CivilizationSystem.MATERIAL_WORKS`（默认 `false`）。关时**网格一字不改**；开时按 **1:1 质量守恒**做材料重排（`stone→墙`、`ledger→wire`），并**按 (x,y,z) 有序遍历**（用 `world.cellsOfType(idx)` 的 TreeSet）保证确定性。开启会演进指纹（属预期），需重跑四门禁确认 `hashA==hashB` 与 `ZERO-DRIFT`。
- **区划是覆盖层不是 mat**：Python `world._zone` 用独立 `int[SX][SZ]` 落 `Civilization.zone`，绝不写 mat。
- **信号源替换**：Java 无 Python 的 `_trade/_market/_robots/_deepsea` 时，用可得信号合成（如 `coins = prosperity×1`）并在类注释注明；量级要对齐 Python（曾因 ×10 致外交关系全部顶到 1.0 → 重调）。**伪噪声别用 `hash()`**：Python `hash(str)` 每进程随机不可复现，Java 用**固定盐值常量**。
- **跨系统联动**：Python `link.couple` 改为**同容器直写**（如 `religion` 直接改 `Civilization.unrest`），避免引入新耦合。
- **契约**：新增 `XxxDeterminismTest` 断言「文明态签名」同种子逐字节一致（本项目第 8 道门禁 `CivilizationDeterminismTest`）。

### I. 跨层共享池 → 信号源分离（批次 3 个体成长实战沉淀）
Python 常有一个**全局共享池**（如 `world._research`）被多个系统同时读/写。照搬进 Java 会引发**跨层饥饿**：某层消费过快会把另一层抽干。
- **症状**：批次 3 接入后 `civ.research` 从 175 掉到 **6.4**，`tech` 永久停解锁（`medicine` 科技永不出，连带医学加速痊愈的分支变死代码）。
- **根因**：`firearms`（消费 8/次）与 `myth/dreamscape/learning`（供养）共用一个池；消费速率 > 供养 → 池被抽干，且被抽的是**文明科技层**的池。
- **正解**：给每层**各自独立**的开放标量池（`Individual.research` vs `civ.research`），供养/消费闭环在层内；跨层只保留**单向且非拮抗**的联动（如 `myth` 单向加 `civ.faith`）。→ 两层互不饥饿（`civ.research` 复原 108.6）。
- **自检**：移植任一新层后，**回看上一层的关键标量是否异常跌落**（本例：若 `civ.unlocked` 变空即报警）。

### J. 「死尾」自检：新层激活后，旧层的下游是否真在动（批次 3 补修批次 2 遗留）
批次 2 留下两个**死尾**，直到批次 3 才暴露（因为批次 3 的 `robotics` 依赖它）：
- **供电死尾**：`power()` 只取 `GENERATOR/WIRE` 方块（Java 无此块 → `power=0`）→ `assemble()`（需 `ASSEMBLE_POWER=1`）**永不启动** → `gear=0`。
- **开采死尾**：`mine()` 首行 `if (!MATERIAL_WORKS) return;` → 连**数字层**的 ore 产出都跳过 → `ore=0`。
- **正解**：把「数字账本产出」与「材料执行（磨蚀网格）」**解耦**——数字层始终按速率计入账本（不碰网格，守指纹）；材料层才受 `MATERIAL_WORKS` 守护。另给「外部替代」的缺失输入（无方块 → 加**畜力/水力供电基线** `max(1.5, 0.1×alive)`）。
- **纪律**：开关（`MATERIAL_WORKS`）只应关「写 mat/mass」的**副作用**，绝不连**只读/纯账本**的推进一起关掉——否则系统表面在跑、实则输出恒为 0。
- **自检**：新层跑完 1200 tick 后，逐项看证据行里**是否有一整段恒为 0/常量**（如 `ore/iron/gear/robots` 全 0、`aliens=0`）——恒 0 多为死分支，别放过。

### K. 别把 Python 的「忠实饱和」当 bug（数值校准前先读源）
证据行里某些标量会顶到 `cap`（如 `myth_value=1.0`、`dreaminess=1.0`）。**校准前先读 Python 源确认**：若 Python 用的是同一公式与同一 `cap`（本例 `myth.py`/`dreamscape.py` 均为 `alive×0.00x − 0.00y` 且 `cap=1.0`），则**饱和是原设计**，勿乱调。只有「因信号源替换导致的异常饱和」（如批次 2 `coins=prosperity×10` 使外交关系全顶 1.0）才需重调。

### L. 「分支可达性」自检：先问「这个 else 在 Java 里到得了吗」（批次 4 民政/灾害实战沉淀）
批次 4 一次抓出**三处**同类暗坑——系统「在跑」，但关键分支**永不触发**。三条可复用规则：
1. **`(int)` 截断型死尾**：`p.caravans += (int) rate;` 当 `rate≈0.88`（<1）时**恒 +0**。凡「每期加 (int) 小量」的计数，一律改**小数累积器**（`frac += rate; i=(int)frac; if(i>0){acc+=i; frac-=i;}`）。
2. **全局旗标型死尾**：撤离原按「全局灾害旗标」见谁吓谁 → 久旱旗标**长期滞留** → 全村永久 `scared`、不再漫步（`evac=877`）。判据：**若某输入会长期为真，就别用它做「瞬时惊扰」的触发**；改用**局部/瞬时**感知（本处：邻近 5×5 有火/淹才逃）。
3. **阈值被上游烘热型死尾**：Python `judge` 在「无人亲疏 ≥0.45 相护」时惩戒；但 Java 的 `SocialSystem` 会把邻里亲疏**整体烘热** → 几乎人人都有相护者 → `pen=0`（惩戒分支不可达）。判据：**移植阈值型二分支时，先确认本项目的信号分布是否已把某支推到极端**；若单调偏向一支，补一个**可达的二级维度**（本处：**累犯**——初犯可获人情、惯犯失众望）。
> 通用做法：门禁证据行**逐字段读**，凡「应为非零却恒 0」「应双向却单边」的字段都当死尾嫌疑，回源比对公式与信号分布。**别放过 `pen=0` 这种安静的死分支**。

### M. HUD 文本渲染自检：先证明「顶点真的发出去了」（C1 CJK 上屏实战沉淀）
C1 做 CJK 字体时顺带发现：**整个 ASCII HUD 其实从未渲染**——`Font.draw` 一个顶点都没发射。两处 bug 都在，症状是「不报错、不崩溃、就是看不见」，极难从截图或日志发现。
1. **无头验证优先**：写一个 `main` 直接调渲染函数，把顶点追加进 `FloatBuffer`，`flip()` 后打印 `limit()/7`（顶点数）。**`verts==0` 就是铁证**——比在 GPU 上瞪屏幕快得多。
2. **像素步长要落在「像素」量级**：本处 HUD 坐标是**屏幕像素**，`PX=1/14 (≈0.071)` 会让字形整体亚像素化（宽度不足 1px）。**凡是「字看不见/细如发丝」，先查步长单位是不是写成了 clip-space 比例**。
3. **点阵表的字符编码要一致**：字表里存 `'0'/'1'` 而判定查 `'#'` → 一个像素都不亮。**改字表格式时，判定条件必须同步**；稳妥写法是判定 `(c=='1' || c=='#')` 兼容两种。
4. **顶点预算要留余量**：CJK 字比 ASCII 密得多（本处 16×16 点阵、同行连续亮像素合并后仍约 160 顶点/字）。HUD 缓冲从 40 万浮点提到 160 万，避免长文本溢出 `BufferOverflowException`。
5. **零资产中文方案**：不必预烘焙字形或上纹理——用 JDK 自带 `java.awt` 把字符光栅化成布尔点阵（惰性缓存），再走**与 ASCII 完全相同的顶点管线**即可；字体缺失时优雅降级（`status()`/`available()`），不崩溃。

### N. 新增玩法层（非移植）：实体级状态 + 只写 events + 门禁测「可达性」（C2 塞尔达后半段实战沉淀）
C2（试炼点/补给箱/世界之心/世界目标链）不是搬 Python 系统，而是**凭空加一整套玩法**——却仍做到了 DETERMINISM 指纹逐字节不变。可复用配方：
1. **状态放实体级**：新建一个 `Xxx.java` 开放状态容器（如 `Trials`），字段全是普通标量/列表，挂到 `World.xxx`。**绝不进 `hashState()`**——与 npcs/beasts/shrines/social/chronicle/civ/individual/polity/calamity 同纪律（见 §A/§G）。
2. **认取只改三样**：`容器自身` + `Player`（souls 之类）+ 追加 `events`。**绝不写** `mat/mass/prosperity/skills/villageMemory`。于是「加一大套玩法」而指纹恒定。
3. **布点用 `simStream`**：首 tick（`w.tick==1 && 列表空`）经 `w.simStream("xxx:place")` 确定性生成，天然与主 rng 隔离（见 §B）。
4. **「选最大/最优」必须定义确定性平局规则**：C2 的「世界之心置于全图最高峰」若并列不加约束，两遍运行的坐标会漂移。**并列时取最小 x、再最小 z**（或等价全序），否则门禁必红。
5. **门禁要测「可达性」而不只是「自洽」**：`XxxDeterminismTest` 除了断言「同种子两遍 hash 一致 + 快照一致」，还要**脚本化走完全流程**（C2：授力 → 依次走访 3 试炼点/4 补给箱 → 登顶认取），断言终态 `relics=3 caches=4 heartClaimed=true`。这条直接防 §J/§L 那种「系统在跑、分支永不触发」的死尾——**新玩法最容易死于"门永远打不开"**。
6. **顺手查旧死分支**：C2 时发现 shrine 横幅 `bannerText` 被设置却**从无 `draw` 调用**（祭坛觉醒横幅其实一直没显示过）。新增 UI 复用旧字段前，先确认它真的被绘制（见 §M）。

### O. 给「玩家动作层」补门禁：四道零漂移门禁**看不见玩家**（C3 多武器战技实战沉淀）
C3 暴露了一个结构性盲区，值得写进纪律：

> **四道零漂移门禁只跑 `World.tick()`，而玩家的动作（攻击/战技/翻滚/炸弹/建造）全在渲染输入层触发 —— 所以玩家动作层即使整体打空、范围写错、甚至顺手写了网格，指纹门禁也照绿。**

因此凡是"给玩家加新动作/新形态"，都要额外补一道**行为断言**门禁（C3 的 `WeaponArtTest` 是模板）：
1. **不断言指纹，断言行为差异**：C3 断言的是「CLEAVE 一次命中 3 只且 4.6 格外那只不受影响」「SHOT 命中 12 格外、18 格外不受影响」「LUNGE 打远不打近邻」。**只断言"能施放/有伤害"是不够的**——那种断言对"三种形态其实同一份逻辑"照样通过。
2. **用高血量假兽承接伤害**：`b.maxHp = b.hp = 9999`。既能精确读伤害，又**不会触发击杀**，从而避免掉落公式改写 `weapon/currentArt` 污染后续用例（C3 实测踩过：第一次写 1000 血，CLEAVE 一次三杀，`onKill` 掉出猎弓把 `currentArt` 改成 SHOT，后续断言全错）。必要时再固定 `killCount` 把掉落档位钉死。
3. **每段用例前重置**：显式设 `p.x/p.z/p.y`、`p.stamina`、`p.artCd=0`、`w.beasts.clear()`。战技会**位移玩家**（LUNGE 冲 2.4 格），不重置就会把位置误差累积到下一段。
4. **必须含一条零漂移不变量**：整套动作跑完前后断言 `w.hashState()` 逐字节不变。这条把"新动作顺手写了网格/繁荣"这类事故直接钉死在门禁里。
5. **纯玩家/渲染层数据不要进 `hashState`**：C3 的 `arts/currentArt/artCd` 全部是 `Player` 字段，`Player` 本就豁免（与 `Beast`/`Shrine`/`Trials` 同纪律），所以"加一整套 build 分歧"而指纹恒定。
6. **别让武器表与战技逻辑分家**：C3 把武器定义抽成 `Weapons` 原型表（`Def` 带 `art/cost/range/mult/cd`），`Player.weaponArt()` 只做「按原型分派」。若把"斧=回旋斩"这类映射写死在 if-else 里，第三次加武器就会开始漏改。
7. **解锁类玩法走"集合 + 幂等 + 保序"**：`arts` 用 `LinkedHashSet`（到达顺序 = 轮换顺序），`unlockArt()` 返回「是否首次」以便上层播报；重复解锁必须无副作用。
8. **远程/条件战技要防"空放惩罚"**：SHOT 无目标时 `return false`（不耗体力、不进 CD）。"按了键扣了体力却什么都没发生"是最容易漏的手感坑。

---

### P. UI/流程层（菜单/暂停/设置）——「逻辑抽模型」+「暂停=丢 tick」（C5 菜单暂停实战沉淀）
C5 表明 UI 层也能被门禁覆盖，但有两条必须先立的规矩：

> **① 只要 UI 逻辑里含"状态机/数值规则"，就把它从 GLFW 回调里抽成纯模型类**（C5 的 `core/sim/MenuModel` 是模板）。留着不抽就**只能靠肉眼验收**——本沙箱无显示器，等于零覆盖。抽出来后导航环绕、页跳转回退落点、设置钳制、二次确认默认项全都能 headless 断言。

> **② 暂停必须实现为「丢 tick」，不能是「冻结时钟」。** 「冻结时钟」= 暂停期间仍推进 `tick` 只是不渲染/不输入 → 恢复后世界已经偷偷走了 N 步，存档/回放/多人同步**静默错位**。正确做法（C5）：`World.paused` 作为**不进 `hashState`** 的开关，`tick()` 首行 `if (paused) return;` —— 不推进 tick 计数、不耗 RNG、不改网格。

具体纪律：
1. **门禁要断言"透明性"而不是"能暂停"**：C5 的 `MenuTest` 核心用例是「中途暂停（每 1/3/7 tick 停一次）跑满 300 次有效 tick」与「从未暂停跑 300 次」的 `hashState` **逐字节一致**。"能暂停"这种弱断言对"暂停其实改了 RNG 流"照样通过。
2. **暂停开关放仿真核，输入/光标/绘制放渲染层**：`World.paused` 唯一真相 = `menu.isOpen()`，在 `updateInput` 里一行同步；渲染层只负责路由按键、切光标、画浮层。
3. **暂停要"清空待处理动作"**：暂停期间按下的攻击/战技/建造键会滞留在队列里，恢复瞬间**一起补放**（C5 内 `clearQueuedActions()`）。同理"游戏中的回车"不该在下次开菜单时被补放。
4. **暂停要"丢弃累积时间、不追帧"**：主循环 `acc` 在暂停帧清零（`if (paused) acc = 0;`），否则恢复瞬间会 `while(acc>=SIM_DT)` 连跑几十 tick 补时间 —— 表现为"一恢复村民瞬移"。
5. **设置项即时应用要"值变了才重算"**：FOV/视距改动才重建投影矩阵，别每帧重建；灵敏度直接进鼠标回调（C5 用 `mouseSensPct/100`，**默认 0.15 恰好等于旧硬编码** → 默认手感零变化）。
6. **光标模式切换要重置视角基准**：菜单放开指针、游戏锁回时置 `firstMouse = true`，否则会出现视角瞬跳（鼠标从菜单位置跳到中心）。
7. **新写的 `Font.width()` 这类小助手要"与绘制前进量严格一致"**：右对齐/框宽都依赖它，不一致就会串位。

### Q. 视觉/表现层也要抽纯模型；且「同一个概念全项目只能有一个定义」（D3 昼夜视觉实战沉淀）
D3 看似纯美术（改个天色），实际暴露了两个结构性问题，都值得沉淀：

> **① 表现层的"世界状态映射"同样要抽成纯模型。** 昼夜不是"随便调几个颜色"，它是 `tick → 相位 → 天色/光照/雾` 的**函数**。把它写成 `Game` 里散落的 `Math.cos(...)` 就只能肉眼验收；抽成 `core/world/DayCycle`（纯函数、零状态、零 RNG）后，**连续性/取值域/单调性**全都能 headless 断言。判据：**凡是"输入是仿真状态、输出要影响画面"的映射，都值得一个纯函数类**。

> **② 同一个概念（"夜"、"清晨"、"一天"）全项目只能有一个定义。** D3 之前，"夜"在 `AuroraSystem` 里是私有计数器 `t%128>=64`，在渲染层根本不存在，在 `WeatherSystem` 里又是另一个 `skyTime` —— 三处各不相同，属**隐性不一致**：极光可能出现在正午，天色也不影响任何系统。D3 把"夜"收敛到 `DayCycle.isNight(tick)`，`AuroraSystem` 改为读它。

具体纪律：
1. **纯函数模型只读已进指纹的时基**：`DayCycle` 只读 `w.tick`（已进 `hashState`），**不新增任何状态字段**、不吃 RNG、不写网格 → 对四道基线门禁**逐字节零影响**（第 14 道门禁直接断言这点）。这样"暂停（丢 tick）"时天色自然静止、恢复后自然接续，与 C5 语义天然一致，**不需要额外状态**。
2. **连续性要断言，不能靠肉眼**：调色板按档位硬切在代码里看不出来，画面上却是"天色啪一下换色"。`DayNightTest` 以 1 tick 为步长扫 2 个整周期，断言所有色/光/雾量的**逐 tick 变化量 ≤0.06**（实测最大 0.0269）。
3. **表现层的数学也要可断言**：`SkyBasis`（相机基 + 半 FOV → 屏幕 NDC 反解世界视线方向）做成纯数据类，于是"屏幕中心 == 相机前向"、"fovY=90° 时屏幕上边缘**恰好**上仰 45°"、"沿屏幕 Y 扫描视线高度**严格单调**"都能 assert。**修 bug 前先想清楚该 bug 对应哪条可断言性质** —— 本批修的是"天空用屏幕 Y 取色 → 抬头天色不动"，对应的正是"沿屏幕 Y 扫描视线高度必须单调"。
4. **改了"共享定义"后必须防死分支**：把 `AuroraSystem` 的判据换成 `DayCycle.isNight` 时，若写反（或误差导致永不满足），极光会**静默变成永不触发的死代码**。故门禁逐 tick 推进并给每条极光事件标注它**出现时**的 tick，断言 `events > 0 && 全部落在夜里`。**换判据 = 换可达性，必须补可达性断言**（呼应 §J/§L）。
5. **未修的不一致要显式记账，别默默放过**：`MistSystem` 的"清晨窗口"仍是 `w.tick % 64 == 0`（每 3.2 秒一次、合每天 8 次），与 `DayCycle` 的"每日一次拂晓"不同调。它只写日志事件、无渲染消费者，改动超本批范围 → **不改，但写进本手册与 `VALIDATION_CHECKLIST.md` §6**，留给后续统一。半修比不修更危险。
6. **矩阵里取相机基，别自己推角度**：`Game` 直接从 JOML 的 `view` 矩阵提取 right/up/forward（`right=(m00,m10,m20)`、`up=(m01,m11,m21)`、`forward=-(m02,m12,m22)`）——这是 `setLookAt` 的**同一个**基，保证 shader 里的取色方向与实际渲染严格一致，避免"角度约定反了导致天空左右镜像"。

---

---

## §R · 音频层移植（C4 实战沉淀）

> **① "没有音频库"不等于"做不了音频"。** `libs/` 里确实没有 OpenAL，但 **JDK 自带 `javax.sound.sampled`（自 Java 1.3 起就在 rt.jar）**；配上**程序化合成**（不加载任何 `.wav`/`.ogg`），整条音频链**一个新依赖都不加**。这与 C1 用 `java.awt` 光栅化代替字体文件是同一个取舍：**宁可算，不背资产**。查清单前先问"JDK 里有没有"，再决定要不要引依赖。

> **② 混音逻辑必须抽纯模型，否则只剩耳朵验收。** 声部池上限、优先级抢占、老化、静音语义若长在 `SourceDataLine` 的写回调里，本沙箱（无显示器、无音频设备）就是**零覆盖**。抽成 `core/audio/{Sfx,AudioSynth,AudioMixer}` 后，第 15 道门禁 `AudioTest` 可 headless 断言。

> **③ 音频绝不吃任何 RNG —— 连 `simStream` 都不吃。** 音高微扰与噪声全部来自 `(variant, i)` 的**整数位混洗**（`AudioSynth.detune/noiseAt`），故"播不播音频"对 `hashState` 完全无影响。门禁的最终证据就是这条：同一段脚本下「跑 tick 且每 tick 播不同音频」与「完全不播音频」指纹**逐字节一致**。

具体纪律：
1. **包络必须起收都为 0**：线性起振（`env(0)=0`）+ 尾部线性释放（末样点归 0）。缺任一端，每次发声都会带一声"咔哒"（click）。门禁对全部音效逐个断言 `out[0]==0` 且末样点 ≤ 峰值 2%（现 25 个：`Sfx.values().length` 动态取，新增音效自动纳入）。
2. **"老化"要按播放序号，不能按"已混音帧数"**：`AudioMixer` 最初用 `clock`（混音帧计数）当年龄基准，于是"连续 play 而不 mix"时 8 个声部年龄**全相同**，同优先级抢占退化成"永远抢 0 号槽"——LRU 名存实亡。第 15 道门禁直接抓到（`lruOldest=false`）。修正：`play()` 里 `vStart = clock++`，让"年龄"= 播放先后。
3. **回卷检测不能用"相邻样点跳变 > 满量程"**：饱和波形本来就在 `+32767`/`-32768` 两轨之间大幅跳变，这么写会**大面积误报**（本批首跑 `wrap=630`，是测试自身的判据错）。正确判据是**逐样点比对混音契约** `clamp(Σ round(sample·32767))` —— 一次覆盖"定点和累加是否回卷"与"取整/钳制边界是否错位"。
4. **断言要防空洞**：只断言"没削顶"是空洞的（静音永远不削顶）。故同时断言"两条轨都真的压过"（`max==32767 && min<=-30000`）与"峰值非 0"。
5. **喂音频绝不能阻塞渲染**：写设备前先看 `line.available()`，缓冲满就少喂或跳过本帧——宁可音频轻微迟滞，也不让游戏掉帧（渲染帧率是玩家直接感知的）。无设备/无线/被占用/驱动异常一律 `try/catch` 降级静音，**不许异常冒泡到主循环**。
6. **`SourceDataLine` 只有 `write(byte[],off,len)`**：没有 `short[]` 重载。混音出 `short[]` 后必须自己摊平成小端 16bit 字节（与 `AudioFormat(bigEndian=false)` 严格对应）。
7. **菜单新增设置项要同步改门禁**：C4 往 SETTINGS 加了 VOLUME/SFX 两行（行数 5→7、可编辑行 4→6、BACK 行索引 4→6），`MenuTest` 的 `itemCount`/`editable`/`down()` 步进**全部要跟着改**，否则门禁会因"行号错位"而红而非因功能错。

---

## §S · 去重 / 收敛类改动（D2 实战沉淀）

> **去重不只是"删重复"。它会同时动到两条看不见的链：可达性 与 可见性。** 动手前必须问两句：

> **①「谁在按条数计阈值？」** 本项目 `CivilizationSystem.incubate` 用**村志档案条数**解锁节日/绰号（`marry>=2` → 婚嫁节、`birth>=2` → 添丁节）。若去重把 35 条重句收成 1 条，节日就可能**静默变不可达**（特性死亡，且**门禁不报**——因为门禁只查"同种子两遍一致"）。解法：阈值改按**发生次数**（`Chronicle.count`）而非**档案条数**，语义更对，数值也与去重前**完全一致**（实测 `festivals` 一字未变）。

> **②「谁在靠条数当证据？」** HUD 的 `REPEL n` 是 A 类验收里"世界因你而变"的**可见证据**。若把计数挂在"入库条数"上，去重会**顺手把玩家能看见的反馈也削掉**——功能没坏，但"因你而变"再也看不出来。解法：**计数与归档解耦** —— `counts` 每次发生都计（含被去重者），`episodes` 只留不同档案。

具体纪律：
1. **去重键要去掉"时刻"**：`Episode.text` 内嵌「第N时辰：」→ 按全文去重只能挡住同一天内的重复（Python 原版就是这样，日号一变就失效）。正确做法是取**骨架**（去前缀）作 key，`action|骨架`，与发生时刻无关 —— 才是真正的**长程**去重。
2. **别用"模糊相似"去重**：曾想用 bigram Jaccard ≥ 阈值判"同义"，实测会**把不同主体的事件误并**（`crime` 的"X困顿难耐，偷走了邻家的存粮"与"Y…"骨架相似度很高，却是不同人做的不同事）。**精确骨架判等**才是安全下界。
3. **门禁要断言"去重真的发生"，而不是"没崩"**："档案非空"这类空洞断言在**完全没去重**时也绿。要断言：① 计数 > 档案数（差值即去重掉的量）；② 真实跑中"N 次同义事件 → 1 条档案"；③ **不误并**（不同夫妻 → 2 条）；④ 叙事层无雷同句且计数用真实发生数；⑤ `absorb` **幂等**（水印保证重复调用不重复入库）。
4. **改完先 diff 整份门禁报告**：把改动前的 `build_report.txt` 存一份，改后**逐行 diff** —— 本次仅 2 行变化（storyteller 两行），其余门禁输出**逐字节一致**。这才是"外科手术式改动"的证据；反过来说，若某次 diff 出一大片变化，就是在悄悄改别的东西。

---


### T. 抽象字段层（非体素/非实体：把 Python 的"世界状态字段"落成开放标量）——批次5 实战沉淀
Python `systems/` 里有一类系统（`phases`/`entropy`/`fusion`/`deepsea`…）**不产生实体、不写网格**，只维护若干"世界状态的读数"。移植它们走下面的三问 + 三证。

> **前置第一问：它是「有世界状态可演化的量」，还是「无世界状态的粘合/配置」？**
> 前者才移植。本项目里 `link.py`（用 `getattr`/反射做跨系统鸭子类型耦合）与 `portal.py`（CLI/Web 配置中枢）**不产出任何世界状态** → **显式排除**并记录在案（别默默漏掉，也别硬塞进 `World`）。判据很简单：**跑一遍它，`hashState` 覆盖的字段里有没有任何东西变了？** 没有就是"粘合/配置"。

> **前置第二问：缺网格时，能否用既有真实量做「代理映射」？**
> Java 世界没有 Python 的 `temp`/`radiation` 网格。正解不是硬造一张网格（那会牵动指纹），而是**从既有真实量派生**并在类注释里**写明理由**：本项目 温度←`FIRE`/`LAMP` 方块数 + 昼夜气候；熵←`civ.power` + 日照；聚变←`civ.research`/`civ.power`；深海←`WATER` 方块数 + `civ.research`。**代理量是"读数"，不反写世界** —— 与 §H「材料执行层默认关」同思路：读是安全的，写才动指纹。

> **落地后三证（缺一不可）：**
> **① 指纹隔离证明**：主动改该层各字段，断言 `hashState()` 逐字节不变。这是"该层在指纹之外"的**构造性证明**，比"反正都 PASS"强。
> **② 零 RNG 证明**：跑一次 `update()` 前后断言 `rng.state()` 不变 —— 证明新增系统**不借用主 RNG**，也就不可能扰动其它已锁定的层。抽象字段层应做到**连 `simStream` 都不吃**。
> **③ 可达性证明**：断言每个字段**非死**（多相/多次跃迁/计数增长），防"系统在跑、字段恒 0"的安静死尾（呼应 §J/§L/§O）。

具体纪律：
1. **状态放开放标量容器、绝不进 `hashState`**：新建一个 `Xxx.java`（本项目 `core/world/Matter`），`World` 只加一个 `public final Matter matter` 字段。与 npcs/beasts/shrines/social/chronicle/civ/individual/polity/calamity 同纪律 → 接入后 `DETERMINISM` 指纹与接入前**逐字节一致**。
2. **材料执行层一律开关默认关**：凡"要写 mat/mass/temp"的副作用（辐射扩散/作物变异/海洋遮罩/潜艇布点），收进 `XxxSystem.MATERIAL_WORKS=false`。关时网格一字不改、守指纹；开启属预期演进（需重跑四基线确认 `hashA==hashB` 与 `ZERO-DRIFT`）。**这与 §H/§J 是同一把刀**：开关只关"写副作用"，别连只读/纯账本的推进一起关掉。
3. **代理映射的"门"要挑在 Java 里真能开的那一把**：Python `deepsea` 用「科技+齿轮」门，但 Java `civ.gear` **恒为 0** → 照搬会**永不可达且门禁不报**。改门前**先在 Java 里实测该信号是否可达**（本项目用一次性探针脚本打印关键标量），否则再正确的公式也是死代码。
4. **门禁按"性质"分层断言，别只断言"自洽"**："同种子两遍一致"在"字段其实是常量死值"时照样通过。要同时断言**可达性**（`transitions>0 && phasesSeen≥2`）、**隔离性**（改字段→指纹不变）、**纯净性**（`rng.state` 不变）。
5. **改动仍要"外科手术式"验收**：把改前的 `build_report.txt` 存一份，改后**逐行 diff** —— 本项目 10 处抽样非 MATTER 行逐字节一致、仅新增 MATTER 3 行。若 diff 出一大片变化，就是在悄悄动别的东西。
---

## 工具链踩坑（省时间）

1. **新增 core 子包/世界实体后，`build.bat` 与 `run-game.bat` 两处 javac 源清单都要同步**——否则会出现"本机 `run-game.bat` 编译断、`build.bat`/CI 绿"的分叉（曾因 `run-game.bat` 漏 `src/core/agent/*.java` 导致 `core.agent` 包不存在）。
2. **`.bat` 改动必须保持 CRLF**，否则 cmd 分词跑飞。
3. **`javac -encoding UTF-8`**：中文注释在默认 GBK 下会报错。
4. **Python 子进程捕获 Java 输出要 `encoding="gbk", errors="replace"`**：本机 JVM 中文走 GBK，默认 utf-8 解码会 `UnicodeDecodeError` 崩。验证脚手架见 `build_runner.py`（产出 `build_report.txt`）。
5. **`SeededRNG.nextDouble()` 返回 `double`**：赋给 `float` 要强转 `(float)`（`nextFloat()` 无此坑）。
6. **在 `bash -c "…"`／PowerShell 里写含反引号或 `$` 的脚本要小心**：双引号内的反引号会被 shell 当命令替换执行，静默吞掉内容。落盘长文本优先用文件/Edit 工具，别塞进 `-c` 字符串。
7. **同一文件绝不能在同一轮里并发（并行）发起多个 Edit** —— 两个 Edit 并发读改写同一文件会互相覆盖：**工具会各自回报 "success"，但只有一个落盘，另一个被静默丢弃**。C3 时因此白跑一轮编译（`import` 与 `ART_COST` 清理同时发起，结果只剩一个）。纪律：**同文件的多处改动必须串行**；改完用 `grep`/`Read` 回读复核落盘，别只信工具回执（与 §"Edit 报成功但磁盘未变"同源）。
8. **「连续性/增量」断言必须先把基线初始化到"同一个量"**：D3 的 `DayNightTest` 首跑报 `tint=0.795` 连续失败，排查后是**测试自己的 bug** —— 我把上一帧的光色基线变量复用了 `skyTop(0)` 的值，于是第一帧等于拿"天顶色"减"光色"，差异自然巨大。教训：**做 `max|cur - prev|` 类断言时，`prev` 必须由被测函数在同一起点初始化**，别复用别的数组；否则门禁会报一个指向错误位置的假故障（真故障反而被掩盖）。顺带说明这道门禁的设计是对的：它把"天上掉下一个 0.795 的跳变"当回事 —— 这正是它要抓的东西。
9. **`.bat` 修改脚本必须能安全重跑，且要写 CRLF**：`build.bat`/`run-game.bat` 在 Windows 下必须 CRLF（LF 会让 cmd 分词跑飞）。用 Python 落盘时注意：**以二进制读入的文本里换行是 `\r\n`，若替换锚点写的是 `\n` 就永远匹配不上** —— 必须先 `.replace("\r\n", "\n")` 归一化再匹配，最后按原换行风格写回。另外沙箱命令在**权限升级时会重跑一次**（日志里会看到 `⚠️ Sandbox bypassed`）：第一次（沙箱内）可能已经改成功，第二次就报 `count=0`。所以"锚点计数必须为 1"的断言要理解成**幂等检查** —— 看到 `count=0` 先回读文件确认是否已生效，别急着改锚点。
10. **一次性多处改同一文件时，优先用一个"带计数断言的脚本"而不是 N 个 Edit**：把 `(old, new, tag)` 列表交给脚本，逐个 `S.count(old) == 1` 断言后再统一落盘 —— 既天然规避"同文件并发 Edit 互相覆盖"（踩坑 7），又能在任何一个锚点漂移时**零写入**退出并报出是哪一个。用**二进制读写**（`open(p,'rb')` / `wb`）以免顺手把 `\r\n` 洗成 `\n`。
11. **沙箱对"一轮内删除 >50 个文件"会拦截**：`build_runner.py` 开头的 `shutil.rmtree(out)` 会触发 `SAFE_DELETE_BULK_CONFIRM_REQUIRED`，**中断后续编译并把 `build_report.txt` 截断成 3 行**（报告被清空，看着像"门禁全没了"）。修法：给 build_runner 加 `BW_KEEP_OUT=1` 跳过清理 —— javac 本就会全量重编我们显式列出的源，`out/` 里残留的 `.class` 无影响（增量复跑约 40 秒）。

> **基线指纹演进（2026-09-11）**：（2026-09-11 地形可玩性调参后基线指纹有意演进：振幅减半 + 尺度 18/6，修复"卡在冲沟"）。旧值 ad9e7b31ed47ec45 / c9e1d98362321283 / e722b7f80ffc8317 / 0a8533fd5eb2ec6b 为演进前记录（event_log 历史条目保留）。门禁为同种子自洽断言，18 道仍全绿。
