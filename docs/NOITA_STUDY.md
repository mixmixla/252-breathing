# Noita 材料/化学引擎研究（2026-09-18）

> 研究目的：Noita 的"逐像素物理 + 化学反应"是同类作品的天花板。本文记录**可复用的数据架构**（不是抄数据），
> 以及映射到本项目的分阶段方案。
> **版权边界（重要）**：Noita 的 `data.wak` 内所有数据属于 Noita 版权方。本项目**只借鉴架构与设计模式**，
> 所有材料/反应数据一律自行编写、自行取值。本文件不含 Noita 的数据文件内容（只引用字段名/标签名/schema 形状）。

## 1. 怎么读到它的数据（只读，不动游戏安装）

> **已完整解包**：14,745 文件 / 39.72 MB → `D:\study\Noita_unpacked\`。
> **模块地图见 `docs/NOITA_MODULES.md`**（每个模块代表什么）。解包器：`tools/noita_unpak.py`。

- 安装目录：`C:\Program Files (x86)\Steam\steamapps\common\Noita`
- 官方解包器是 `tools_modding\data_wak_unpack.bat`（内容就一行：`noita.exe -wizard_unpak`），
  但它**要求把工具复制到游戏根目录并写入安装目录**（Program Files 需管理员，且会改动游戏本体）→ 本次没用它。
- 改走**只读自解析**（`data/data.wak`，40.50 MB）：
  ```
  header 16B = [u32 pad=0][u32 count=14745][u32 dirEnd=797859][u32 0]
  count 条   = [u32 dataOffset][u32 size][u32 nameLen][name]
  数据区紧跟目录；相邻数据块间隔 1 字节
  校验: off[i+1] == off[i] + size[i] + 1   ← 14744 对全部成立
  ```
  ⚠️ 初次误把字段顺序记成 `[namelen][name][off][size]`，于是"偏移指到下一个文件"（取 `materials.xml` 那条得到 Biome 文档）。
  正确顺序是**偏移/大小在前、名字在后**，表从**第 16 字节**开始（`dirEnd` 正好等于表结束位置 → 可自校验）。
- 还读到的官方资料：`tools_modding/component_documentation.txt`（0.34 MB，实体组件全集）、
  `lua_api_documentation.txt`、`data/schemas/*.xml`（159 个按哈希命名的 schema 文档，20 MB）。
- 结论：**能拿到的是"数据层"**（材料表/反应表/实体定义/Lua 脚本/着色器）；模拟内核是 C++，不可得 → 只能学架构，不能抄实现。

## 2. 材料模型（这是最值得学的一层）

一个材料 = 一个 `CellData`（子类 `CellDataChild` 用 `_parent` + `_inherit_reactions` 继承父材料）。

**规模**：224 个 `CellData` + 245 个 `CellDataChild` = 469 条定义 / 466 个唯一名；328 条 `Reaction`。
**仅 6 条 `_parent` 用得最少、`_inherit_reactions=1` 用了 189 次** → 材料表是**类继承树**，不是扁平列表。

### 2.1 物理类别只有 4 种（`cell_type`）

| cell_type | 条数 | 含义 |
|---|---|---|
| `liquid` | 156 | **包含粉末！** |
| `solid` | 42 | 不流动的方块 |
| `gas` | 21 | 上升、会扩散 |
| `fire` | 4 | 火（特殊：有 `temperature_of_fire`） |

**⚡ 最大的架构惊喜：粉末（sand）就是 `cell_type="liquid"` + `liquid_sand="1"`。**
即 Noita 只有**一套流体求解器**，"粉末"只是"高密度 + 不横向铺开"的液体。
`liquid_sand=1` 阻止横向铺散（所以沙堆成锥形而不是摊平），`liquid_gravity` 控制下落步进。

→ 对本项目的直接启示：我们现在的 `SandFallSystem` / `AshFallSystem` / `PondSystem` / `IceFormSystem` 是**每个材料一套系统**；
Noita 的做法是**一套密度驱动的流体 + 一张数据表**。合并后系统数下降、涌现性上升（沙会沉进水里、油浮在水上、烟上升）。

### 2.2 决定"会不会浮/沉"的只有 `density` 一个标量

实测取值（越小越轻）：`smoke 1` < `acid 2.9` < `water 4` < `sand 6`。
浮沉/换位就是**两个相邻格子的密度比较**。石头的密度更高 → 砂沉石上、水在石上、烟在水里上浮。零额外逻辑。

### 2.3 高价值字段（按本项目可用性排序）

| 字段 | 出现次数 | 作用 |
|---|---|---|
| `density` | 219 | 浮沉/换位（见上） |
| `cell_type` | 219 | 4 类物理行为分发 |
| `on_fire` / `burnable` / `fire_hp` / `autoignition_temperature` / `temperature_of_fire` | 211/210/50/96/181 | 燃烧模型（`fire_hp` = 烧多久才烧完；`autoignition_temperature` = 自燃点） |
| `generates_smoke` / `requires_oxygen` | 203/201 | 烟/氧气依赖（**水能灭火因为火 `requires_oxygen`**） |
| `liquid_sand` / `liquid_gravity` / `liquid_viscosity` / `liquid_static` / `liquid_flow_speed` | 180/175/31/37/9 | 流体手感（粘稠度 = 流动概率） |
| `hp` / `durability` / `crackability` | 122/21/9 | 可挖掘性（与**挖掘进度**天然对接） |
| `lifetime` | 31 | **瞬态材料的自动消亡**（烟/蒸汽/火自己会消失，不需要专门系统） |
| `warmth_melts_to_material` / `cold_freezes_to_material` | 10/11 | **温度相变**（水↔蒸汽/冰、岩浆↔石头）——**与反应表并列的第二套机制** |
| `solid_static_type` | 36 | 6 值枚举：动态 / 永远静态 / 可破坏 / 树可破坏 / 破坏后变另一种材料 / 冰破碎 |
| `solid_friction` / `solid_restitution` / `slippery` / `stickyness` | 64/10/10/7 | 表面手感 |
| `electrical_conductivity` | 14 | 与我们的 `WireSystem` 天然对接 |
| `liquid_stains` / `liquid_stains_self` / `stainable` + `<Stains><StatusEffect type="WET"/>` | 62/28/6 | **染色系统**：走过水洼 → 身上 WET 状态（视觉 + 后续反应） |
| `status_effects` / `<Ingestion>` | 45/133 | 接触/吃下 → 状态效果（酸→中毒等） |
| `danger_fire` / `danger_radioactive` / `danger_poison` / `danger_water` | 9/5/3/1 | 接触伤害 |
| `wang_color` | 469 | 世界生成的像素色 |
| `gfx_glow` / `<Graphics color texture_file normal_mapped fire_colors_index>` | 91/388/313 | 视觉 |
| `<Edge><EdgeGraphics pixel_top/pixel_left/pixel_all_around/pixel_lonely …>` | 64/29 | **按邻居形态切换的像素边缘**（我们已有类似思路的 FaceCull/边缘纹理） |
| `vegetation_*` / `grows_grass` | 9 | 植被生长 |

### 2.4 标签体系（77 个标签）—— 让 328 条反应覆盖"几乎所有材料对"的关键

标签分四类用途：
1. **物理族**：`[solid] [liquid] [gas] [static] [box2d] [earth] [fire]`
2. **反应族**（驱动通用反应，最重要的设计）：`[corrodible]`(172 个材料有!) `[meltable] [meltable_to_lava/water/blood/slime/acid/poison/cold/radioactive]`
   `[molten] [soluble] [evaporable] [evaporable_fast] [frozen] [freezable] [burnable] [burnable_fast]`
   `[requires_air] [requires_water] [water] [snow] [ice] [meltable_by_fire] [meltable_to_lava_fast]`
3. **语义/危险**：`[hot] [cold] [acid] [lava] [slime] [blood] [meat] [plant] [fungus] [food] [radioactive] [magic_*] [regenerative] [indestructible]`
4. **系统适配钩子**：`[alchemy] [chaotic_transmutation] [fungal_shift] [NO_FUNGAL_SHIFT] [sunbaby_ignore_list] [rust] [rust_oxide]`

**关键机制**：标签既可写在材料上（`tags="[liquid],[corrodible],[water]"`），也可写在**反应里当查询**：
`input_cell1="[corrodible]"` 一次命中 172 个材料。**这就是"328 条规则 = 全组合化学"的全部秘密。**

## 3. 反应模型（第二值得学的一层）

```
[in1] + [in2] (+ [in3]) --probability%--> [out1] + [out2] (+ [out3])
```

- in/out 可以是**具体材料名**，也可以是**标签查询**（`[lava] [fire] [static] [water] [corrodible]`）。
- `probability`（0–100，49 种取值）——**概率化**是"活着"的来源（不是每次接触都反应）。
- 可选修饰符（出现次数很少，但每个都解决一类问题）：

| 字段 | 次数 | 解决的什么问题 |
|---|---|---|
| `blob_radius1/2` + `blob_restrict_to_input_material1/2` | 44/27 | **反应会"蔓延"**（火/腐蚀按半径扩散），且限制只蔓延在指定材料上 → 腐蚀不会串到别的材料 |
| `req_lifetime` | 5 | **输入必须存在够久才反应**（如水泥泡水 600 帧才硬化成混凝土）→ 天然做出"过程感" |
| `input_cell3` / `output_cell3` | 33/16 | 三元反应 |
| `destroy_horizontally_lonely_pixels` | 7 | 清掉孤立像素（防锯齿残留） |
| `entity` | 5 | **反应生成实体**（材料变怪物 —— Noita 的经典恶趣味） |
| `fast_reaction` | 20 | 优先级（快速反应先跑） |
| `direction` | 17 | 方向性反应 |
| `cosmetic_particle` / `audio_fx_volume_1` | 11/26 | 纯观感 |
| `ReqReaction`（独立标签，5 条） | 5 | **"必需反应"：不可被其它规则覆盖** |

## 4. 映射到本项目（`java/breathing-world`）

### 4.1 我们已有什么（避免重复造）
`SandFallSystem` / `AshFallSystem` / `PondSystem` / `FireSpreadSystem` / `ErosionSystem` / `IceFormSystem`
+ `Blocks`（index/r/g/b/solid/translucent/wind）+ `World.lightAt` 块光 + `FaceCull` + 挖掘进度 + `HungerSystem` + `WireSystem`。
**缺口正是"统一流体 + 数据驱动反应 + 温度相变"。**

### 4.2 值得搬的三件事（按性价比排序）

1. **`density` 驱动的统一分层**（最高性价比，改动可控）
   把"沙下落 / 灰下落 / 水填洼 / 冰生成"合并为**一套按 `cell_type` + `density` 分发的求解**：
   粉末=高密度液体（不横向铺）、液体=密度比较+横向流、气体=反向。**立刻获得**：沙沉水底、油浮水面、烟上升、岩浆灌水。
2. **数据驱动反应表 + 标签查询**
   新增 `assets/content/materials.json` + `reactions.json`（沿用本项目内容层裸 JSON 惯例）。
   `[tag]` 查询在**加载期**展开成材料 id 集合（运行期只做 id 比较，零字符串开销）。
3. **`lifetime` + `warmth_melts_to` / `cold_freezes_to`**
   瞬态材料自动消亡（省掉专门系统）；温度相变做成**材料字段**而不是反应规则（数据量小得多）。

### 4.3 零漂移落地路线（本项目的铁律：`mat` 进 `hashState`）

**阶段 A（可零漂移完成，无需重锁基线）**
- 只做**数据层 + 加载器 + 展开器 + 门禁**：`materials.json` / `reactions.json` 解析、标签展开、
  合法性校验（未知材料名/未知标签必须**响亮报错**，对齐本项目 `RecipeBook`/"未知键响亮"惯例）。
- **出厂 `reactions.json` 为空 → 新系统逐字节 no-op → 全部指纹不变。**
- 完全对齐已有先例：`World.config`（SKIP）+ `SystemRegistry` 末尾追加 + `simStream` 派生流（不消耗主 rng）。

**阶段 B（需重锁 DETERMINISM，须用户批准）**
- 打开反应表 + 密度分层；反应随机只走 `simStream("reaction:"+tick)`；
- 用 `tools/ShaderPreview` 思路的**离屏/无头等价性门禁**证明"表为空 == 不开系统"。

**阶段 C（性能红线）**
- **绝不能全网格扫描**：本项目 160×112×160 = 287 万格；Noita 是 C++ + 逐像素 GPU 才敢硬扫。
  必须复用既有 **`surfaceCells` / `CellSet` 增量覆盖层 + 活跃集**，每 tick 只处理"有邻居变化"的格子。
  现状基线 tick median 2.01ms / p95 4.43ms → 新系统预算先定 **≤0.5ms**，用 `AblationProbe` 归因。

### 4.4 明确**不**搬的东西
- Noita 的 `<ParticleEffect>` 内联粒子参数、`wang_color` 世界生成、box2d 实体物理、132 条 `Ingestion`（吃下效果）
  —— 与本项目现有粒子/世界生成/实体层重复或无关。
- 逐像素硬扫的求解器（性能不可行，见 4.3-C）。
- **Noita 的任何具体数值/名单**（版权 + 设计取向不同）。

## 5. 一句话结论

Noita 值得学的**不是"怎么写出百万像素模拟"，而是"怎么用一张数据表 + 一层标签查询，把少数物理规则放大成海量涌现"**：
- 4 个 `cell_type` + 1 个 `density` 撑起全部分层；
- 77 个标签让 328 条反应覆盖全组合；
- `lifetime` / `warmth_melts_to` 把"过程"变成数据而不是代码。

这套思路与本项目"确定性涌现"的 USP 完全同向，且能靠"空表 no-op"做到**先零漂移落地架构**。
