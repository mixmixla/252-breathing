# Noita → 本项目：哪些值得学（对照我们自己的代码）

> 前两份文档：`docs/NOITA_STUDY.md`（材料/化学数据模型）、`docs/NOITA_MODULES.md`（解包模块地图）。
> **本文是审计结论**：逐条对照本项目源码，只保留"**有代码证据、且对得起改动代价**"的项。
> 解包结果：`D:\study\Noita_unpacked\`。

---

## 0. 一句话结论

**Noita 最值得学的不是"百万像素模拟"，而是"把材料的仿真属性从代码里搬进数据表"**：
具体物理量（密度/硬度/寿命/相变目标）声明在数据里，少量通用规则 + 标签把它们组合成海量涌现。

我们现在的形态正好是它的反面：**`Block` 只有 10 个字段**（且几乎都是渲染标记），
**99 个 `System` 实现里 82 个硬编码具体方块**，于是"同一个机制"被按材料重写了一二十遍。

> 口径（本机实测）：`src/core/systems/` 共 102 个 `.java` = **99 个 `implements System`** + 3 个框架类
>（`System` / `Phase` / `SystemRegistry`）。其中**引用 `Blocks.` 的 82 个**，不引用的 20 个（含 3 个框架类）。

---

## 1. 证据一：材料表字段对比

我们的 `src/core/world/Blocks.java`（25 个方块，含 AIR）——`Block` 的全部字段：

`id, index, r, g, b, solid, opaque, liquid, wind, translucent`

**全部是"渲染/碰撞"标记，唯一带数值的是 RGB。没有任何仿真属性。**

| 能力 | 我们 | Noita（`materials.xml` 对应字段） |
|---|---|---|
| 浮沉分层 | ❌ 无 | ✅ `density`（smoke 1 < acid 2.9 < water 4 < sand 6） |
| 物理类别 | ⚠️ 只有 `solid/liquid` 两个布尔 | ✅ `cell_type`（liquid / solid / gas / fire，且**粉末 = liquid + `liquid_sand=1`**） |
| 硬度/可挖性 | ❌ 无字段 → **在渲染层用 id 子串猜**（见 §2） | ✅ `hp` / `durability` / `crackability` |
| 燃烧 | ❌ 无字段 → 靠 `FireSpreadSystem` 里 50% 概率熄灭 | ✅ `burnable` / `fire_hp` / `autoignition_temperature` / `requires_oxygen` |
| 瞬态消亡 | ❌ 无字段 | ✅ `lifetime`（烟/蒸汽/火自己会消失） |
| 温度相变 | ❌ 无字段 → 靠 `FrozenSystem` + `SnowMeltSystem` 两个专门系统 | ✅ `warmth_melts_to_material` / `cold_freezes_to_material` |
| 流动手感 | ❌ 无 | ✅ `liquid_gravity` / `liquid_viscosity` / `liquid_static` / `liquid_flow_speed` |
| 导电 | ❌ 无字段 → `WireSystem` 硬编码 `COAL_ORE`/`LAMP` | ✅ `electrical_conductivity` |
| 染色/状态 | ❌ 无 | ✅ `liquid_stains` / `stainable` + `<Stains><StatusEffect type="WET"/>` |
| 接触伤害 | ❌ 无 | ✅ `danger_fire` / `danger_poison` / `danger_radioactive` |
| 材质音效 | ❌ 无（Sfx 是事件驱动） | ✅ `audio_physics_material_solid/wall` |
| 材质分组 | ❌ 无 | ✅ **77 个标签**（`[corrodible]` 命中 172 个材料） |
| 字段总数 | **10** | **60+**（`CellData` 属性名全集实测） |

---

## 2. 证据二：属性靠"字符串猜"（最典型的痛）

`src/render/lwjgl/Game.java:1437` `digHardness()`——**挖掘硬度用 id 子串判 8 档**：

```java
if (id.contains("LEAF") || id.contains("FLOWER") || id.contains("GRASS") || ...) return 0.05f;
if (id.contains("DIRT") || id.contains("SAND") || id.contains("CLAY")
        || id.contains("ASH") || id.contains("SNOW")) return 0.40f;
if (id.contains("WOOD") || id.contains("SHELTER")) return 0.85f;
if (id.contains("GLASS") || id.contains("LAMP") || id.contains("GOLD")) return 1.30f;
if (id.contains("ORE") || id.contains("STONE") || id.contains("ASHLAR")) return 1.05f;
```

后果：**加一个新方块（如"玄武岩"）必须回来改这个字符串链**，忘了就默默掉进兜底 0.70s。
Noita 里这是材料表里的一个 `durability="4"`。

同类还有 `Game.java:1444` 把 `ASH` 当一种材料看待——**但 `Blocks.java` 里根本没有 ASH**（见下条）。

---

## 3. 证据三：99 个系统、82 个硬编码方块、机制大量重复

`src/core/systems/` 共 102 个 `.java`（**99 个 `implements System`** + 3 个框架类），
其中 **82 个引用了具体 `Blocks.X`**，17 个 System 实现完全不碰方块（社会/叙事层）。

把"其实是同一个机制"的系统归并一下：

| 机制 | 被谁各写了一遍 |
|---|---|
| 下落 / 沉降 | `SandFall` · `Quicksand` · `Sinkhole` · `AshFall` · `DustStorm` · `Earthquake` · `Volcano` |
| 水的涨落 | `Pond` · `Flood` · `WaterFlow` · `Tide` · `Drought` · `Heatwave` · `Irrigation` · `Geyser` · `Mist` |
| 冰 / 雪 | `Frozen` · `SnowMelt` · `IceForm` · `Hail` · `SnowCap` · `Climate` |
| 火 / 熔岩 | `FireSpread` · `Wildfire` · `Lava` · `MagmaChamber` · `Volcano` · `Weather` |
| 苔 / 植被蔓延 | `Moss` · `MossSpread` · `Lichen` · `Spore` · `Mushroom` · `Vine` · `Canopy` · `Fern` |

两个最说明问题的实例：

**① `AshFallSystem.java:22`** 注释原话：
> 「不复用新方块（**硬约束：不碰 Blocks.java**），仍以 STONE 占位。」

于是"火山灰"在地图上**就是石头**——玩家看不出、系统也区分不了。这不是设计选择，是**材料表贫瘠导致的妥协**。

**② `SandFallSystem.java:20`** 判据是"下方为 **AIR**"：
```java
if (cur == Blocks.SAND.index && prev[z] == Blocks.AIR.index) { ...下落... }
```
→ **沙子不会沉进水里**（它会停在水面之上）。Noita 里 sand(6) > water(4)，下沉是密度比较的自然结果。
我们为了补这个洞又写了 `QuicksandSystem`（"沙邻水时 50% 概率往下方空腔陷落"）——**用第二个系统缝补第一个系统缺的物理量**。

---

## 4. 能力缺口清单（Noita 有、我们完全没有）

按"补上之后立刻能玩到"排序：

| # | 缺口 | 直接后果（今天就能观察到） |
|---|---|---|
| 1 | **`density` 与分层** | 沙浮在水面；没有"油浮在水上""烟往上升" |
| 2 | **气体类材料**（GAS/SMOKE/STEAM） | `Blocks` 里没有气体 → **"蒸发"无处可去**；水永远不会变蒸汽 |
| 3 | **`lifetime`** | 烟/蒸汽/火没有"自然消亡"，`FIRE` 只能靠 `FireSpreadSystem` 的 50% 概率熄火 hack |
| 4 | **`hp`/`durability`** | 硬度在渲染层用 id 子串猜（§2） |
| 5 | **温度相变的"字段化"** | `Frozen`/`SnowMelt` 两个系统只做 WATER↔ICE，**没有蒸汽、没有岩石↔岩浆** |
| 6 | **标签 + 反应表** | 77 个硬编码系统（§3） |
| 7 | **`requires_oxygen`** | 火不需要氧气 → "用水灭火"只能靠概率 hack，而不是物理 |
| 8 | **染色/污渍** | 走过水洼身上不会湿；没有"酸溅到身上" |
| 9 | **`electrical_conductivity`** | 导电只有 `COAL_ORE`/`LAMP` 两种（`WireSystem` 硬编码） |
| 10 | **接触伤害数据** | 熔岩/酸不会持续伤害（只有战斗伤害） |
| 11 | 材质音效 | 挖沙和挖石头是同一个音效 |
| 12 | 泛光（渲染） | `post_glow1/2` 我们缺 → 光源/魔法没有辉光溢出 |
| 13 | 材料**交界**边缘图 | 我们是"按方块类型"选 tile；Noita 是"按材料对"（158 张专属过渡） |
| 14 | ragdoll 尸块 | `World.Corpse` 很简陋；Noita 1009 张部件 + 骨架定义 |

---

## 5. 最值得学的三件事（按性价比排序，含落点与零漂移路线）

### ★★★ ① 给材料加"规格数据层"（**不动既有索引与行为** → 零漂移）

> **✅ 已落地（2026-09-18）** —— 见 `assets/content/materials/*.json`（25 个方块各一份）、
> `core/content/MaterialDef.java`、`core/content/MaterialBook.java`；消费端 `Game.digHardness` /
> `Game.digTick` / `Game.breakTarget`；门禁 `ContentTest.MATERIAL-*`（4 段）。
> **落地时实际只上了 6 个字段**（`name` / `cellType` / `hardness` / `density` / `digParticle` / `tags`），
> 而不是本节最初设想的 11 个 —— 纪律是「新字段必须有真实消费者」。下表是当时的设想，保留作后续路线。
>
> **顺带抓到一个真 bug**：旧 `digHardness()` 里 `contains("ASH")` 排在 `contains("ASHLAR")` 之前，
> 而 `"ASHLAR"` 含子串 `"ASH"` → **条石家族的 rock 分支是不可达代码**，巨构石墙一直被当软土 0.4s 瞬挖。
> 已在数据表里改成意图值 1.05，并在门禁的 `migrationFixes()` 白名单里**逐条登记理由**
> （白名单数量必须精确匹配 → 不许腐化）。

- **做法**：新增 `assets/content/materials.json`（沿用我们内容层裸 JSON 惯例），
  为每个 **已有** 方块 id 补：`density` / `cellType` / `hp` / `lifetime` / `liquidSand` / `liquidGravity` /
  `warmthMeltsTo` / `coldFreezesTo` / `requiresOxygen` / `conductivity` / `tags`。
- **关键**：**不新增方块、不改 `Blocks` 索引、不改任何系统行为** → 只是把"散落在代码里的知识"收进一张表。
  `mat` 数组与 `hashState()` 一字不动 → **指纹必不变**（构造保证，无需重锁）。
- **立刻收益**：`digHardness()` 的字符串链可以被字段取代（渲染层读表）；`AshFall` 可以有自己的 ASH 材质；
  `WireSystem` 的导电可以查表。
- **代价**：一个 JSON + 一个加载器 + 一个门禁（未知方块 id 必须响亮报错，对齐 `RecipeBook` 惯例）。

### ★★★ ② 一套"密度驱动"的统一下落/分层（替代 7 个系统）

> **✅ 批 B 已完成并打开出厂默认值（2026-09-18）** —— 求解器的**最小切片**：
> `SandFallSystem` 的判据从「下方是**空气**」升级为「下方是空气 **或** 更轻的**可位移**材料」，
> 由 `WorldConfig.densityFlow` 控制、**出厂关闭**（关闭时短路，逐字节等价于历史 → 四基线指纹未动，**无需重锁**）。
>
> **数据通路（首次让 `density` 有了仿真层消费者）**：
> `assets/content/materials/*.json` → `MaterialBook.sinksInto(a,b)`（= a 更重 且 b 可位移）
> → `World.materials`（加载期注入，`StateCodec.SKIP`）→ `SandFallSystem`。
>
> **门禁已验证**（`GameplayTest`）：`sand(20)>water(10)` 沉 · `water>sand` 不成立 · `sand>stone` 不沉
> （石头不可位移）· `ice(9)>water(10)` **不**沉（该浮起来）· `gold(45)` 沉；
> 关时沙停水面 / 开时沙沉底并把水顶上来。
>
> **口径澄清**：本次现数到 **17 个**与下落/沉降/流体/相变相关的系统（`SandFall`/`Quicksand`/`Sinkhole`/
> `AshFall`/`Pond`/`WaterFlow`/`Frozen`/`SnowMelt`/`IceForm`/`SnowCap`/`FireSpread`/`Lava`/`Moss`/
> `MossSpread`/`Erosion`/`Clay`/`Flower`）；本节的"7 个"指其中最核心的**下落类**。
>
> **已完成（批 B）**：打开 `densityFlow` 出厂值（A/B 门禁：1200tick 成本 −0.078ms、
> 水量 857→840 **守恒**，故不存在"沙填湖"）、**删掉 `QuicksandSystem`**（它是"缺 `density`"的
> 缝补产物，判据同为「下方是 AIR」，且它记的 `quicksand.form` event 全仓零消费者）、
> `AshFall` 换上真 `ASH` 材质。本次**首次重锁基线**（GOLDEN / COUNT / REPORT 三处硬编码常量）。
>
> **剩余"收敛 17 个系统"要甄别，不能硬塞**：`Frozen`/`SnowMelt` 是**温度相变**、
> `FireSpread` 是**蔓延**、`Moss`/`MossSpread` 是**扩散**、`Pond`/`WaterFlow` 是**流体** ——
> 它们都不是"密度分层"。真正属于密度判据的只有下落/沉降类；相变与扩散应归**批 C（反应表）**。
> 另：**上浮**（轻固体在重液体中上升）需要独立的 `floatsIn` 判据，目前**未实现**。

- **做法**：把 `SandFall`/`Quicksand`/`Sinkhole`/`AshFall` 里"谁往下走"的逻辑，统一成
  **按 `cellType` + `density` 判定的一个求解器**：粉末下沉（可穿液体）、液体按密度换位、气体反向。
- **收益**：沙沉水底、油浮水面、烟上升 —— 全部是**同一段代码**；
  `QuicksandSystem` 这种"缝补系统"可以直接删掉（它是缺 `density` 的产物）。
- **零漂移路线**：新求解器**追加在注册序末尾**，出厂默认关闭（`GREEDY_MESH=false` 式开关）；
  用现有 `AblationProbe` 量成本，预算 **≤0.5ms/tick**（当前全 tick 中位 2.01ms）。
- **风险**：这**会改**地形演化 → 开的时候需要**重锁 DETERMINISM**（与上次"7 个空转参数"同一手续）。

### ★★☆ ③ 标签 + 反应表（替代"材料转换"类系统）

> **✅ 已落地（2026-09-18 批 C）** —— 见 `assets/content/reactions/*.json`（3 条）、
> `core/content/ReactionDef.java`（解析 + 校验）、`core/content/ReactionBook.java`
> （**加载期把 `[tag]` 展开成 id 集合**，运行期 `n*n` 的 `int[]` 查表，O(1) 零字符串）、
> `core/systems/ReactionSystem.java`（唯一求解器，追加在注册序末尾）、
> 门禁 `ContentTest.REACTION-*`（5 段）+ `GameplayTest.REACTION-EFFECT`。
>
> **初始 3 条规则全部选的是"此前完全没有实现"的物理**（避开与既存系统重复造成双重效果）：
> | 规则 | 内容 | 标签覆盖 |
> |---|---|---|
> | `quench` | 火挨着水 → 火灭 | `[fire]` + `[liquid]` |
> | `melt` | 冰/雪挨着火 → 化成水 | **`[ice]` 一句覆盖 SNOW + ICE 两种材料** |
> | `ignite_dry` | 干枯植被挨着火 → 引燃 | `[dry]`（草/花/仙人掌；苔藓不带该标签 → 不燃） |
>
> **实测 A/B（`ReactionFlowProbe`，1200 tick / 96³ 同种子）**：第 1 tick 即产生差异；
> 终局 19,923 格 = 3.38% 不同；开组火 **88** vs 关组 **118**（灭火生效）、冰雪 −11；
> 水量 296→308（**守恒**，灭火不吃水）；成本 **+0.117 ms/tick**（红线 ≤0.5）。
>
> **重锁范围的诚实修正**：本节原写"开表需重锁 DET/ZD"，**实测并未发生** ——
> 确定性门禁用的都是**裸 `new Simulation`**（不加载内容 → 空反应表 → 零写入），
> 故 DET `5440589e88f8134a` / ZD `60c286817cd5f8e2` **与批 B 完全相同**。
> 本批真正重锁的只有 `SYSTEMREG` 三处（新增系统 → `COUNT` 97→98、`GOLDEN`、`REPORT`）。
> 这正是 `REACTION-BARE-SAFE` 断言要固化的性质（与 `MATERIAL-BARE-SAFE` 同一条防线）。

- **做法**：`reactions/*.json`：`{in1, in2, out1, out2, probability}`；
  `in/out` 支持 `[tag]` 查询，**加载期展开成 id 集合**（运行期只做 id 比较，零字符串开销）。
- **收益**：把 `Erosion`（DIRT→SAND）/`Frozen`/`SnowMelt`/`MossSpread`/`Clay` 这类"材料 A 接触 B 变 C"
  收敛到一张表；反应随机走 `simStream("reaction:"+tick)`（非消耗派生流 → 不扰动主 rng）。
- **门槛**：同 ②，开表需重锁；**建议先只收编"材料转换"类系统，别动社会/文明层**（见 §6）。

---

## 6. 边界：**不能拿材料引擎替代的东西**（避免过度承诺）

- 我们的 99 个 System 实现里，**17 个完全不碰方块**：`Social`(15KB) / `Individual`(32KB) / `Polity`(16KB) /
  `Civilization`(23KB，也改地形但主体是叙事) / `Law` / `Market` / `Guild` / `TradeCaravan` / `Storyteller` /
  `Ascension` / `Prosperity` / `Hunger` / `Beast` / `Npc` / `Buff` / `Shrine` / `Trial` / `Capture` / `Content` / `Wind`。
  这些是**社会/文明/叙事层**——Noita **没有**对应物，**学不到，也不该学**。
- `CivilizationSystem`/`IndividualSystem`/`PolitySystem` 虽然也改地形，但主体是"村庄记忆/繁荣度"等宏观演化
  ——**不能因为学 Noita 就把它们改写成"材料反应"**。
- 所以：Noita 能替我们省掉的是**"材料/地形机制"那一层**（82 个引用方块的系统里的一部分），不是全部。

**另外三件明确不该学**：
| 不该学 | 为什么 |
|---|---|
| 逐像素硬扫求解器 | 我们 160×112×160 = **287 万格**；Noita 是 C++ + GPU 且只有百万像素。`SandFall` 一个全扫就占消融 ~30%，硬扫必崩帧率 |
| 466 种材料的内容厚度 | 不是架构问题，是内容量；我们需要的是**留出扩展位**，不是照抄数量 |
| `.plz` 像素场景 / Wang tile 世界生成 | 与我们"程序化 biome 分带"是不同路线，收益不对等（我们已有 `biomeAt` + 分带生成） |

---

## 7. 建议的实施顺序

| 步 | 内容 | 指纹 | 需你批 |
|---|---|---|---|
| **1** | 材料规格数据层（§5①）——只加表、只加加载器与门禁，**不改行为** | **不变** | 不需要 | ✅ 已完成 |
| **2** | 把 `digHardness` 的 id 字符串链换成读表；`AshFall` 用自己的 ASH 材质 | 不变 | 不需要 | ✅ 已完成 |
| **3** | 密度分层求解器（§5②）——默认关闭，探针量到 ≤0.5ms 再开 | 开时重锁 DET/ZD | 已批（批 B） | ✅ 已完成 |
| **4** | 标签 + 反应表（§5③）——先只上"此前没有实现"的规则 | 仅 SYSTEMREG 重锁 | 已批（批 C） | ✅ 已完成 |
| 5 | 补渲染缺口：泛光（`post_glow1/2`）、材料交界边缘图 | 不变（渲染层） | 不需要 | 待做 |
| 6 | 用反应表**收编**既有"材料转换"系统（Erosion/Frozen/MossSpread/Clay） | 需重锁 | **需要** | 待做 |

**结论**：第 1、2、5 步**现在就能做且零漂移**；第 3、4 步是"真正的大菜"，但需要你先点头重锁基线。
