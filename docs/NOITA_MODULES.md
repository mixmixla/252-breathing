# Noita 数据模块地图（解包结果说明）

> 配套文档：`docs/NOITA_STUDY.md`（材料/化学引擎的数据模型深读）。
> 本文回答的是"**解包出来的每个模块代表什么**"，供后续参考学习。

## 0. 解包结果与位置

| 项 | 值 |
|---|---|
| 解包工具 | 本仓库 `tools/noita_unpak.py`（自解析，不依赖 `noita.exe`） |
| 来源 | `C:\Program Files (x86)\Steam\steamapps\common\Noita\data\data.wak`（40.50 MB） |
| 输出 | **`D:\study\Noita_unpacked\`**（14,745 文件 / 39.72 MB） |
| 校验 | 目录表 14,745 条全部解析；相邻条目连续性 **14,744/14,744 吻合**；0 个非法文件名 |

**未改动游戏安装**：官方解包器是 `tools_modding\data_wak_unpack.bat`（内容仅 `noita.exe -wizard_unpak`），
它要求写入游戏根目录（Program Files 需管理员、会改动游戏本体）→ 本仓库工具改为**只读 wak、写到外部目录**。

### wak 格式（实测确定，可直接复现）

```
header 16B = [u32 pad=0][u32 count=14745][u32 dirEnd=797859][u32 0]
之后 count 条   = [u32 dataOffset][u32 size][u32 nameLen][name]
数据区紧跟目录区；相邻数据块间隔 1 字节
校验式: off[i+1] == off[i] + size[i] + 1      ← 14744 对全部成立
```

> ⚠️ 起初把字段顺序记成 `[nameLen][name][off][size]` 导致"偏移指到下一个文件"。
> 正确顺序是 **偏移/大小在前、名字在后**，且表从**第 16 字节**开始（`dirEnd` 正好等于表结束位置，可自校验）。

### 文件构成

| 扩展名 | 数量 | 体积 | 说明 |
|---|---|---|---|
| `.png` | 9,030 | 23.90 MB | 精灵/背景/图标/材料纹理 |
| `.xml` | 4,325 | 7.29 MB | 材料 / 实体 / biome / 粒子 / 投射物定义 |
| `.lua` | 1,077 | 3.33 MB | **游戏逻辑层** |
| `.txt` | 162 | 0.06 MB | 骨架/说明/列表 |
| `.plz` | 97 | 2.70 MB | 二进制"像素场景"（`biome_impl/spliced/`，未解码） |
| `.frag` / `.vert` | 22 / 2 | 0.05 MB | **可读 GLSL 着色器** |
| `.bat` / `.psd` / `.bmp` / `.csv` | 12 / 6 / 6 / 2 | 2.35 MB | 杂项 |

---

## 1. `data/` 根文件（7 个）

| 文件 | 大小 | 代表什么 |
|---|---|---|
| **`materials.xml`** | 408.91 KB | **材料 + 化学反应总表**（466 材料 / 328 反应）→ 深读见 `NOITA_STUDY.md` |
| `magic_numbers.xml` | 7.80 KB | 全局平衡数值（"魔法数字"集中表） |
| `magic_numbers_disable_debug.xml` | 0.48 KB | 调试用的数值覆盖（关闭调试项） |
| `genome_relations.csv` | 4.04 KB | 生物"种族/阵营关系"表（谁恨谁 → 敌友判定） |
| `debug_keys.txt` | 3.35 KB | 调试键位表 |
| `credits.txt` | 2.67 KB | 制作名单 |
| `icon.bmp` | 12.50 KB | 窗口图标 |

> 参考价值：**"全局平衡数值集中到一个文件"** 是个好习惯（我们的对应物是 `World.config` / 预设 JSON）。

---

## 2. 23 个目录模块总览（按文件数排序）

| # | 模块 | 文件数 | 体积 | 这个模块代表什么 |
|---|---|---|---|---|
| 1 | `entities/` | 3,432 | 5.77 MB | **实体定义**（3,030 XML + 284 PNG + 116 Lua）：动物/首领/物品/建筑/投射物/玩家。含 `base_*.xml` 继承模板 |
| 2 | `biome_impl/` | 2,232 | 14.72 MB | **每个 biome 的视觉积木**（背景板/墙体/装饰图）＋ `spliced/` 的 `.plz` 像素场景 |
| 3 | `items_gfx/` | 1,435 | 0.57 MB | **物品与法杖图标**（`wands/` 独占 1,026 张） |
| 4 | `ui_gfx/` | 1,291 | 1.75 MB | **整个 UI 素材**（法术图标 638 / 动物图标 186 / perk 图标 124 / 状态图标 93 …） |
| 5 | `ragdolls/` | 1,157 | 0.34 MB | **尸体碎块骨架**（1,009 PNG 部件 + 148 TXT 骨架定义），每个怪物一个目录 |
| 6 | `scripts/` | 1,032 | 3.17 MB | **游戏逻辑层**（952 Lua）：法术 72 文件 1.22 MB 是最大块 |
| 7 | `particles/` | 898 | 1.03 MB | **粒子定义**（381 XML + 517 PNG）：枪口焰/血溅/图片发射器 |
| 8 | `enemies_gfx/` | 665 | 1.75 MB | **敌人精灵 + 同名 XML 元数据**（425 + 234） |
| 9 | `generated/` | 628 | 0.75 MB | **生成产物**：每个材料一个图标（468）＋ 精灵 UV 蒙版（159） |
| 10 | `materials_gfx/` | 316 | 1.03 MB | **材料像素纹理**（315）＋ **材料交界边缘图** `edge_files/`（158） |
| 11 | `props_gfx/` | 315 | 0.52 MB | 场景道具（桶/箱/招牌/雕像/火把） |
| 12 | `projectiles_gfx/` | 299 | 0.37 MB | 投射物精灵 + 定义（148 + 150） |
| 13 | `vegetation/` | 229 | 0.17 MB | 植被精灵 + 定义（172 + 57） |
| 14 | `weather_gfx/` | 213 | 3.02 MB | **天气与背景层**：每 biome 大背景图 ＋ `edges/` 128 张过渡图 |
| 15 | `biome/` | 173 | 0.88 MB | **关卡生成配置（全 XML）**：`_biomes_all.xml` 总表 + 每 biome 一份 |
| 16 | `temp/` | 158 | 0.97 MB | 开发遗留/临时资源（`building/`、`spells/`） |
| 17 | `buildings_gfx/` | 86 | 0.07 MB | 建筑与机关（箭陷阱、箱子、门） |
| 18 | `procedural_gfx/` | 56 | 0.72 MB | **程序化特效帧序列**：崩塌（small/big/huge/lavabridge） |
| 19 | `debug/` | 43 | 0.03 MB | 调试几何体（圆/方/空） |
| 20 | `wang_tiles/` | 33 | 0.87 MB | **Wang tile 地形瓦片集**（世界生成的拼块蓝图） |
| 21 | `shaders/` | 27 | 0.60 MB | **22 个可读 GLSL + 2 顶点着色器 + 3 张噪声贴图**（渲染管线线索） |
| 22 | `props_breakable_gfx/` | 17 | 0.19 MB | 可破坏物分段图（链/木板/金属杆） |
| 23 | `global/` | 3 | 0.00 MB | `player_stats.xml` / `weather_config.xml` / `test_weather.xml` |

---

## 3. 逐模块详解（重点模块）

### 3.1 `entities/` —— 实体的一切（ECS 的数据面）

顶层同时存在**两类东西**：
- **`base_*.xml` 继承模板**（共 22 个）：`base_enemy_basic` / `base_enemy_flying` / `base_enemy_robot` /
  `base_humanoid` / `base_helpless_animal` / `base_item` / `base_item_physics` / `base_item_projectile` /
  `base_projectile` / `base_prop_crystal` / `base_shop_item` / `base_torch` / `base_wand` / `base_jetpack` /
  `base_apparition` / `base_chain_torch` / `base_custom_card` / `base_dripping_liquid` / `base_jetpacknew` …
- **具体实体**：`player.xml` + `player_base.xml`、`decoy.xml`，以及 `animals/`、`items/`、`building/`、`buildings/`、
  `misc/`、`particles/`、`intro/`、`_debug/`、`_workdir/`

子目录示例：
- `animals/`：**每个怪一个 XML**（`acidshooter.xml`、`alchemist.xml`、`ant.xml`、`assassin.xml`、`bat.xml`、`bigzombie.xml`…）
- `animals/<boss>/`：首领拆成多个子目录（`boss_centipede/` 176 文件、`boss_wizard/` 37、`boss_limbs/` 36…），
  里面有 `limbs/`、`tail/`、`rewards/`、`verlet_chains/`、`ending/` —— **首领 = 多部件 + 链条物理 + 掉落**
- `items/`：`orbs/`、`pickup/`、`books/`、`easter/`，以及 `starting_wand*.xml`（开局法杖三档：普通/RNG/每日）

> **参考价值**：① 用 `base_*` **模板继承**做实体族谱（与材料表的 `_parent` 同一套思路，全项目统一）；
> ② 首领按"部件/链条/掉落"分子目录，而不是塞进一个巨大 XML。

### 3.2 `scripts/` —— 逻辑层（Lua）

顶层文件就是"引擎钩子"：`init.lua`（总入口）、`game_helpers.lua`、`biome_map.lua`、`biome_scripts.lua`、
`biome_modifiers.lua`、`director_helpers.lua` / `director_helpers_design.lua`（导演系统）、
`item_spawnlists.lua`（掉落表）、`persistent_flags.lua`（跨局持久标记）、`newgame_plus.lua`、
`wang_scripts.csv`（Wang tile → 脚本映射表）、`empty.lua`、`debug_biomes.lua`。

子模块（按体积/数量）：
| 子模块 | 文件 | 体积 | 职责 |
|---|---|---|---|
| `gun/` | 72 | **1.22 MB** | 法术/法杖系统核心（最大单块） |
| `biomes/` | 155 | 0.73 MB | 各 biome 的运行期脚本（含 `sun/`） |
| `projectiles/` | 200 | 0.18 MB | 投射物行为 |
| `buildings/` | 114 | 0.11 MB | 建筑/机关行为 |
| `animals/` | 94 | 0.09 MB | 敌人 AI 与特殊行为 |
| `perks/` | 78 | 0.23 MB | 天赋效果 |
| `items/` | 69 | 0.11 MB | 物品使用逻辑 |
| `props/` `magic/` `status_effects/` `essences/` `particles/` `lib/` `audio/` `debug/` | 其余 | — | 道具/魔法/状态/精华/粒子/公共库 |

> **参考价值**：逻辑与数据**分离**——XML 声明"是什么"，Lua 写"怎么动"。
> 我们 Java 版没有脚本层，对应物是"系统类 + 内容 JSON"，可借鉴的是**分域目录 + 公共库 `lib/`**。

### 3.3 `biome/` —— 世界生成配置（173 个 XML）

- 总表：`_biomes_all.xml`（所有 biome 的登记表）
- 像素场景：`_pixel_scenes.xml` / `_pixel_scenes_laboratory.xml` / `_pixel_scenes_newgame_plus.xml`
- 每 biome 一份：`coalmine.xml`、`coalmine_alt.xml`、`crypt.xml`、`desert.xml`、`forest.xml`、`fungicave.xml`、
  `fungiforest.xml`、`excavationsite.xml`、`snowcastle.xml`(见 biome_impl)、`laboratory`、`pyramid`、
  `mountain`、`overworld`、`clouds.xml`、`gold.xml`、`end_wall.xml`、`essenceroom*.xml`、`boss_*_arena*.xml`、
  以及彩蛋房间 `friend_1..6.xml`、`funroom.xml`、`greed_room.xml`、`gun_room.xml`、`ghost_secret.xml`

> **参考价值**：**"一个 biome 一个配置文件 + 一张总表"** 的扁平结构，比"把世界写死在代码里"好扩展。
> 我们的 `biomeAt()` 是单纯分带，可以学它把 biome 参数外置。

### 3.4 `biome_impl/` —— biome 的视觉积木（最大美术模块）

按 biome 分目录：`caves/`(1014) `coalmine/`(75) `crypt/`(62) `excavationsite/`(84) `mountain/`(57)
`overworld/`(65) `pillars/`(85) `pyramid/` `rainforest/` `snowcastle/`(78) `snowcave/`(59) `liquidcave/` `hidden/`
+ 特殊场景 `boss_arena/` `tree/`(24, 1.53MB) `moon/` `moon_dark/` `skull/` `gourd_room/` `lake_statue/` `lavalake*`
- 命名后缀揭示用途：`*_visual.png`（视觉）、`*_background.png`（背景）、`*_background_panel_big_material.png`（背景板）
- **`spliced/<scene>/N.plz`：97 个二进制"像素场景"**（头部疑似 `版本=1 / 512 / 512`；体积很小 → 应为压缩的 cell 网格）。
  这是 Noita 把"手绘的像素房间"烘焙成可拼接数据块的方式。**未解码**（要逆向格式，本次未做）。

### 3.5 `materials_gfx/` —— 材料纹理 + 材料交界

- 315 张 PNG：**每种材料一张像素纹理**（`sand.png`、`bone.png`、`brass.png`、`bluefungi.png`…）
- `edge_files/` 158 张：**两种不同材料相接处的过渡图**（配合 `materials.xml` 里的 `<Edge>`/`<EdgeGraphics>`
  与 `pixel_top/pixel_left/pixel_all_around/pixel_lonely` 等按邻居形态选图的字段）

> **参考价值（与我们高度同构）**：我们也做"图集 + 按邻居形态选 tile + 边缘过渡"。
> Noita 的差别在于**边缘图是按"材料对"组织的**（任意两种材料相接都有专属过渡），比"按方块类型"更细。

### 3.6 `shaders/` —— 渲染管线线索（22 个可读 GLSL）

| 着色器 | 推断用途 |
|---|---|
| `process_cellgrid_expand_colors.frag` | 把 cell 网格（每格一个材料）展开成彩色像素图 |
| `sprite_cellgrid.frag` / `sprite_cellgrid_preprocessed.frag` | 直接把 cellgrid 当精灵画 |
| `sprite_default` / `sprite_smooth` / `sprite_stains` / `sprite_stains_no_fade` | 精灵渲染；**stains = 染色层**（材料溅到实体身上的污渍） |
| `sprite_damage_highlight` / `sprite_damage_critical_hit_highlight` | 受击/暴击高亮 |
| `sprite_static_tile_bg` / `sprite_temple_rock` / `sprite_wand_shot` / `sprite_invisible` | 特例精灵 |
| `post_cell_res_blit` | 把"cell 分辨率"的缓冲放大到屏幕（**像素艺术的关键一步**） |
| `post_glow1` / `post_glow2` | **两级泛光（bloom）** |
| `post_final` + `post_final.vert` | 最终合成（色调/分辨率输出） |
| `sky_gradient.frag` | 天空渐变 |
| `imgui_text` / `imgui_potion_icon` / `imgui_status_icon` | UI 文字与图标 |
| `common.frag` / `default.vert` | 公共函数 / 默认顶点着色器 |
| `noise_perlin.png` + `noise_perlin16x16.png` + `noise_triangular.png` | **自带噪声贴图** |

> **参考价值（直接印证我们上一轮的做法）**：
> ① Noita 也用 **Perlin 噪声贴图**而不是逐像素哈希（与我们给天空做 `NoiseTex` 的结论一致）；
> ② 它的管线是 `cellgrid → expand_colors → sprite 绘制 → post_glow1/2(bloom) → post_final`，
> 其中 **`post_cell_res_blit` 先把像素缓冲放大**——这是"像素游戏要保住像素感"的通用做法；
> ③ `post_glow` 两步泛光是我们目前**没有**的（我们只有内联在 shader 里的近似）。

### 3.7 `generated/` —— 生成产物（不是手画素材）

- `material_icons/` 468 张：**每种材料一个图标**（与 469 个材料定义几乎 1:1）→ 游戏内"看材料"的 UI 用
- `sprite_uv_maps/` 159 张：精灵的 UV 蒙版图（把伤害/染色对齐到 sprite 的哪个部位）

> 参考价值：**能程序化生成的就生成**（图标、蒙版），手画只留给真正需要艺术的部分。

### 3.8 `ragdolls/` —— 尸块（1,009 PNG + 148 TXT，约 140 个怪物目录）

每怪物一个目录：PNG = 各部件（头/躯干/四肢），TXT = 骨架与关节定义（连接关系/质量）。

> 参考价值：**"死亡后变成物理碎块"是廉价但极有效的表现力放大器**（我们的 `World.Corpse` 还很简陋）。

### 3.9 其余模块一句话

- `items_gfx/`：物品图标；**`wands/` 1,026 张**说明法杖系统是 Noita 的第一大内容域（每个部件一张图）
- `ui_gfx/`：UI 素材全集；`gun_actions/` 638 = 法术图标库，`animal_icons/` 186 = 图鉴
- `particles/`：粒子用 XML 声明（`muzzle_flashes/` 170、`bloodsplatters/` 126、`image_emitters/` 76）
- `enemies_gfx/`：PNG + 同名 XML 成套（XML 提供 UV/蒙版等元数据）
- `props_gfx/` + `props_breakable_gfx/` + `buildings_gfx/`：道具、可破坏物、机关
- `weather_gfx/`：**背景大图 + 128 张边缘过渡图**（biome 之间的视觉过渡）
- `vegetation/`：植被；`procedural_gfx/`：崩塌特效帧序列
- `wang_tiles/`：Wang tile 拼块（世界生成的"蓝图"）
- `temp/` `debug/`：开发遗留与调试资源（结构上可忽略）
- `global/`：少量全局配置（玩家数值、天气）

---

## 4. 未包含在 `data.wak` 里的部分（在安装目录别处）

| 位置 | 内容 |
|---|---|
| `Noita.exe` / `noita_dev.exe` | 引擎本体（C++，闭源）→ **模拟内核与渲染实现不可得** |
| `tools_modding/` | 官方 modding 文档：`component_documentation.txt`（实体组件全集，0.34 MB）、`lua_api_documentation.txt/html`、`data_wak_unpack.bat`、Noita Modding Agreement |
| `data/schemas/*.xml` | 159 个按哈希命名的 **schema 文档**（20 MB）——数据结构的形式化说明（辅助理解 XML 字段） |
| `data/audio/`、`fonts/`、`translations/`、`video/` | 未打包的音频工程、字体、本地化、视频 |
| `licenses/` | 第三方库许可 |
| `mods/` | 官方示例 mod（`example` / `nightmare` / `starting_loadouts` / `daily_practice`）→ **改数据的最佳入门样例** |

---

## 5. 版权与使用边界（重要）

- `data.wak` 内**全部内容**（XML/Lua/PNG/着色器）版权归 Noita 版权方（Nolla Games）。
- 本项目（`会呼吸的世界`）**只借鉴架构与设计模式**，**不复制任何数据/代码/美术**：
  - ✅ 可以学：模块划分、`base_*`/`_parent` 继承模式、材料/反应 schema、标签间接层、
    "cellgrid → blit → glow" 管线、"噪声贴图代替逐像素哈希"、"能生成的就生成"。
  - ❌ 不要做：把 Noita 的 XML/Lua/PNG 拷进本项目仓库；把它的数值表当自己的平衡表；
    分发解包出来的资源。
- 解包目录 `D:\study\Noita_unpacked\` 仅供**本机学习参考**，不属于本项目仓库（也未纳入 git）。

---

## 6. 与本项目（`java/breathing-world`）的取用清单

| 优先 | 学什么 | 我们的对应物 | 备注 |
|---|---|---|---|
| ★★★ | 材料表 + 标签查询 + 反应表（见 `NOITA_STUDY.md`） | `Blocks` / 各 Fall·Pond·Fire 系统 | 可先零漂移落"数据层 + 空表 no-op" |
| ★★★ | 一套 `density` 驱动的分层求解（粉末=高密度液体） | `SandFall` / `Pond` / `AshFall` | 合并系统、提升涌现性 |
| ★★☆ | `lifetime`（瞬态材料自动消亡）+ 温度相变字段 | 无 | 省掉专门系统 |
| ★★☆ | 渲染管线"低分辨率 cell 缓冲 → blit → 泛光" | 我们直接全分辨率绘制 + shader 内联暗角 | `post_glow` 是我们缺的一环 |
| ★★☆ | 噪声**贴图**（不要逐像素哈希） | 已建立（`NoiseTex`） | 与本轮结论一致，互相印证 |
| ★☆☆ | 实体 `base_*` 继承 + 首领部件目录化 | `Beast` / `world.enemies` | 内容扩张时再上 |
| ★☆☆ | 尸块（ragdoll）碎块化 | `World.Corpse`（简陋） | 廉价表现力放大器 |
| ★☆☆ | biome 配置外置（一 biome 一文件 + 总表） | `World.biomeAt`（硬编码分带） | 与内容层改造一起做 |
| ✗ | 逐像素硬扫求解器 | — | 我们 287 万格，性能不可行（见 NOITA_STUDY §4.3-C） |
