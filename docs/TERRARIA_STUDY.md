# 泰拉瑞亚 / tModLoader 拆解学习笔记

> 生成日期：2026-09-18 ｜ 目的：为《会呼吸的世界》提供**机制参考**（非素材来源）
> 拆解产物目录：`252_模拟世界游戏_会呼吸的世界/_study/terraria/`

---

## 0. 先看这条：法律边界（三档）

| 对象 | 性质 | 能做什么 | 不能做什么 |
|---|---|---|---|
| **tModLoader** | **MIT 开源**（GitHub `tModLoader/tModLoader`，2019-11-27 由 MPL 改 MIT） | 自由阅读、参考、学习；其**自有代码**可按 MIT 复制（保留版权声明） | 别把 `Terraria.*`（游戏本体）代码一起拷走 —— MIT 只覆盖 tModLoader 自有部分 |
| **Terraria 本体** | 闭源商业软件（Re-Logic） | 反编译**仅供个人学习机制**（世界生成 / AI / 光照 / 网络同步思路） | **代码一行都不能进商用产品**（衍生作品侵权）；不得再分发反编译源码 |
| **Terraria 资源（XNB）** | Re-Logic 版权美术/音频 | 解出来**看结构**（图集切法、命名规律、动画帧排布） | **不得复用**任何贴图 / 音效进你的游戏 |

**一句话**：tModLoader 可以放心学甚至抄（MIT）；泰拉瑞亚本体只学**思路**，不碰**代码和素材**。
这与之前 MCP-Reborn 的结论一致：**参考书，不是零件库**。

---

## 1. 已拆解产物清单

| 产物 | 路径（相对 `_study/terraria/`） | 规模 | 说明 |
|---|---|---|---|
| tModLoader 单文件源码 | `tModLoader_src/tModLoader.decompiled.cs` | 904,413 行 / 33.5 MB | 全量反编译，单文件便于 grep |
| **tModLoader 项目源码** ⭐ | `tModLoader_proj/` | 1,907 个 .cs / 48 MB | **按类型分文件 + `tModLoader.csproj`**，IDE 直接打开浏览；含 `Terraria.*` 与 `Terraria.ModLoader.*` 命名空间 |
| Terraria 本体源码 | `Terraria_src/Terraria.decompiled.cs` | 927,462 行 / 34 MB | **未混淆**（`Main`/`Player`/`NPC`/`WorldGen` 等类名完整可读） |
| tModLoader API 文档 | `tModLoader.xml`（原目录） | 2.4 MB / **4,968 个 `<member>`** | 官方 XML 注释，可当 API 手册 |
| 调试符号 | `tModLoader.pdb`（原目录） | 8.3 MB | 反编译时提供行号/局部变量名，可读性大幅提升 |
| 图片资源 ✅ | `unpacked/Images/` | **15,123/15,123 全部解包**（→ 15,123 PNG + 15,123 JSON） | 精灵图 → PNG + 元数据（约 98 MB） |
| 音效资源 ⚠️ | `unpacked/Sounds/`（**目录未生成**，源 863 个 XNB / 179 MB 未解） | — | 见 §2.1 阻塞说明 |
| 字体资源 ⚠️ | `unpacked/Fonts/`（同上，源 5 个 XNB / 26 MB 未解） | — | 见 §2.1 阻塞说明 |

其余源文件：`xnb_convert.cjs`（单文件转换）、`xnb_batch.cjs`（目录批量转换）、`xnb_out/`（单文件试解样例）。

### 2.1 未解出的部分与原因（诚实记录）

| 项 | 报错 | 原因 | 想解怎么办 |
|---|---|---|---|
| **Sounds（863 个）** | `SoundEffectReader` 未实现 | npm `xnb` 库只实现了 Texture2D/Effect/BMFont/SpriteFont 等 reader，**没有 `SoundEffectReader`** | 换 **TExtract**（GitHub `trigger-segfault/TExtract`，专为泰拉瑞亚写，支持音效）或 `xnbcli`；或自写 reader（需改库内部，成本高） |
| **Fonts（5 个）** | `ReLogic.Graphics.DynamicSpriteFontReader` 未实现 | 这是 **Re-Logic 私有 reader**，任何通用 XNB 工具都不会内置 | 基本只能反编译 `ReLogic.dll` 自己实现；**性价比极低**（只有 5 个字体） |

**判断**：音效 179 MB 全部是 Re-Logic 版权音频，**既不能进你的游戏、学习价值也≈0**（你的音频系统是自研的）。
故本轮**不做**，需要时按上表换工具 —— 这一步的价值在"知道怎么解"，不在"解出来"。

---

## 2. 复现命令（换机器照此重跑）

```bash
# ① 装反编译器（.NET SDK 已装）
dotnet nuget add source https://api.nuget.org/v3/index.json -n nuget.org   # 若源为空
dotnet tool install -g ilspycmd

# ② 反编译（Windows 路径！ilspycmd 不认 /c/... 形式）
ILSPY="$HOME/.dotnet/tools/ilspycmd"
"$ILSPY" -o 'C:\...\_study\terraria\tModLoader_proj' -p 'C:\Program Files (x86)\Steam\steamapps\common\tModLoader\tModLoader.dll'
"$ILSPY" -o 'C:\...\_study\terraria\Terraria_src'    'C:\Program Files (x86)\Steam\steamapps\common\Terraria\Terraria.exe'

# ③ 装 XNB 解包库（含 LZX 解压）
cd "$HOME/.workbuddy/binaries/node/workspace" && npm install xnb

# ④ 解包资源（单文件 / 整目录）
export NODE_PATH='C:\Users\Administrator\.workbuddy\binaries\node\workspace\node_modules'
node xnb_batch.cjs 'C:\...\Terraria\Content\Images' 'C:\...\_study\terraria\unpacked\Images'
```

**踩过的坑（重要）**：
1. `ilspycmd` 是 Windows 程序，**必须传 `C:\...` 路径**，传 `/c/...` 会报 "file does not exist"。
2. NuGet 配置默认是空的 `<packageSources>`，装工具前要先 `dotnet nuget add source`。
3. Node 下 npm `xnb` 库取的是 `file.buffer`（ArrayBuffer）—— Node `Buffer` 是**池化**的，
   必须传 `buf.buffer.slice(buf.byteOffset, buf.byteOffset+buf.byteLength)`，否则读到内存池起点 → magic 校验失败。
4. 该库在 Node 下返回的是浏览器 `Blob`，落盘前要 `Buffer.from(await blob.arrayBuffer())`。
5. 库会按文件打印日志，批量时要临时屏蔽 `console.log/info/warn/debug`。

---

## 3. tModLoader 可学点（MIT，可放心参考）→ 映射到你的项目

tModLoader 是**为一款已存在的游戏加内容层**的框架，和你 `mods/example_mod` + `docs/CONTENT_PLATFORM.md` 要干的事**同构**。

| tModLoader 位置（`tModLoader_proj/Terraria.ModLoader/`） | 学什么 | 对应你的模块 |
|---|---|---|
| `Mod.cs` / `ModContent.cs` / `SystemLoader.cs` | 模组入口、**内容注册表**、加载顺序与生命周期 | `core/content/ContentRegistry`、`ModTest` |
| `ItemLoader.cs`(112KB) / `ModItem.cs` / `GlobalItem.cs` | 内容类型 = 基类 + Loader + Global 三层扩展（**不改原类就能加行为**） | `ItemDef`/`ItemBook` 的扩展点设计 |
| `NPCLoader.cs` / `ModNPC.cs` / `GlobalNPC.cs` | 敌兵 AI 挂载、掉落表 | `Beast`/`BeastSystem` |
| `TileLoader.cs` / `ModTile.cs` | 方块行为注册 | `Blocks` 注册表 |
| `ProjectileLoader.cs` / `ModProjectile.cs` | 投射物 | `Projectile`（若有） |
| `ModPlayer.cs` / `PlayerLoader.cs` | 玩家属性用**扩展表**而非继承（避免存档兼容灾难） | `Player` 属性/装备系统 |
| `ModNet.cs` + `Terraria/NetMessage.cs` | **内容 ID 的网络同步**、版本协商 | 你的 `netHash` / PREDROLLBACK 思路 |
| `LocalizationLoader.cs` | 本地化键值加载（hjson） | CJK HUD / 文案管线 |
| `Config/` + `Config/UI/` | 声明式配置 + 自动生成设置界面 | `MenuModel` 设置页 |
| `IO/`（TagCompound 等） | 存档序列化扩展点 | `StateCodec` / `SAVE_EXT_VERSION` |
| `Templates/` | **官方模组模板**（csproj/build.txt/icon/本地化） | 你的 mod 打包规范 |
| `Engine/` | 运行时打补丁（TerrariaHooks/MonoMod）的接入方式 | —— |

---

## 4. Terraria 本体可学点（只学思路，不抄代码）

源码位于 `tModLoader_proj/Terraria*/`（按类分文件）或 `Terraria_src/Terraria.decompiled.cs`。

| 文件 | 大小 | 学什么 | 对应你的模块 |
|---|---|---|---|
| `Terraria/WorldGen.cs` | 2.2 MB | 世界生成管线：噪声 → 生物群系 → 洞穴 → 结构 → 矿物 | **真·无限大世界**（你最大的缺口） |
| `Terraria/Lighting.cs` | 44 KB | 光照传播（天光 + 方块光，洪水填充式扩散） | `Chunk` 顶点 AO / 光照 |
| `Terraria/Wiring.cs` | — | **电路系统**：导线/门/机关的信号传播 | 你的 `WireSystem`（LAMP/发电炉） |
| `Terraria/Recipe.cs` / `RecipeGroup.cs` | 656 KB | 配方与配方组、解锁条件 | 你的 `RecipeBook`（cost/unlocks 契约） |
| `Terraria/NPC.cs` | 4.1 MB | 敌兵 AI 状态机（150+ 种行为分支） | `Beast` 的 `phase()`/原型表 |
| `Terraria/Projectile.cs` | 3.9 MB | 投射物生命周期与命中判定 | 战技/远程 |
| `Terraria/Liquid.cs` / `LiquidBuffer.cs` | — | 液体流动与压力 | `PondSystem` / 水 |
| `Terraria/Collision.cs` | 218 KB | AABB 碰撞、台阶、平台 | `World.floorY` / `solidBox` / `STEP_HEIGHT` |
| `Terraria/NetMessage.cs` / `MessageBuffer.cs` / `Netplay.cs` | — | **确定性网络同步**：消息缓冲、回滚、校验 | 你的 `netHash` / `PREDROLLBACK` / 确定性内核 ⭐ |
| `Terraria/Main.cs` | 3.8 MB | 主循环：更新/绘制分离、时间基、帧预算 | `Game` 主循环 / `MAX_STEPS_PER_FRAME` |
| `Terraria.UI/`（`Interface.cs`/`GameInterfaceLayer.cs`/`ItemSlot.cs`） | — | **UI 框架**：层叠界面、布局、缩放、输入路由 | 你的 HUD / 菜单（**你之前踩过 UI 坑**） |
| `Terraria/DataStructures/PlayerDrawLayers.cs` | 492 KB | 角色分层绘制（头/身/腿/装备/翅膀各自一层） | 你的角色渲染 / `Appearance` 捏脸 |
| `Terraria.GameContent.Drawing/TileDrawing.cs` | 512 KB | 方块绘制：变体、连接纹理、光照混合 | `Chunk` 网格化 / 图集 |
| `Terraria.GameContent.Drawing/ParticleOrchestrator.cs` | 193 KB | 粒子编排（打击感/环境粒子） | 你的 `Particle` |
| `Terraria.GameContent.Biomes/` | — | 生物群系（沙漠/雪地/洞穴小屋…） | 世界生成扩展 |
| `Terraria.ID/ItemID.cs` / `NPCID.cs` | 440/323 KB | **内容 ID 表**（数百条，命名规范参考） | 你的内容 ID / preset |

---

## 5. 建议学习顺序（按你的缺口优先级）

1. **真·无限世界** → `WorldGen.cs` 的分层管线（噪声→群系→结构），注意它**不是**逐字节确定性设计，你只需借"分层职责"的思路。
2. **UI 系统** → `Terraria.UI/Interface.cs` + `GameInterfaceLayer.cs` 的**层叠 + 独立坐标/缩放**设计，直接治你踩过的 HUD/菜单坑。
3. **网络确定性** → `NetMessage.cs` + `MessageBuffer.cs`，对照你的 `netHash`/PREDROLLBACK 看工业级做法。
4. **模组化内容层** → `ModContent` + `*Loader` 三件套，直接喂给你的 `CONTENT_PLATFORM`。
5. **电路 / 配方 / 光照** → `Wiring.cs` / `Recipe.cs` / `Lighting.cs`，都是你已有系统的"成熟版参考答案"。

---

## 6. 备注

- `tModLoader_proj` 是**最容易上手**的入口：IDE 打开 `tModLoader.csproj` 即可按命名空间浏览。
- 项目里还带 `ExampleMod/`（GitHub 仓库内），是最小可运行模组范例 —— 若要学"从零写一个 mod"，去 GitHub 拉 `tModLoader/ExampleMod` 比反编译更清楚。
- **资源解包仅供观察**：`unpacked/Images` 里能看出泰拉瑞亚的精灵命名/切帧规律（如 `Acc_Back_1` 是竖向动画条），但**不要**把这些图接进你的图集。你自己的美术必须自产。
- 本目录整体属于"研究产物"，**不要**纳入游戏构建产物 / 发行包。

---

## 7. 体检记录（2026-09-18，陀螺复核后入库）

| 声称 | 实测 | 判定 |
|---|---|---|
| `tModLoader.decompiled.cs` 904,413 行 | 904,414 行（差 1，末尾换行计数差异） | ✅ |
| `Terraria.decompiled.cs` 927,462 行 | 927,463 行（同上） | ✅ |
| `tModLoader_proj/` 1,907 个 .cs | **1,907**（目录下共 2,240 文件，含 csproj 等） | ✅ |
| `unpacked/Images/` 15,123 PNG + 15,123 JSON | **PNG 15,123 / JSON 15,123** | ✅ |
| `unpacked/Sounds/`、`unpacked/Fonts/` | **目录不存在**（未解出，非"解了一半"） | ⚠️ 已修正表述 |
| 复现命令中的路径/坑 | 未重跑验证（成本高于收益） | ⚠️ 未验 |
