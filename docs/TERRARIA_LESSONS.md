# 泰拉瑞亚 → 本项目：我们能从它身上学到什么

> 前一份：`docs/TERRARIA_STUDY.md`（**怎么拆的**、产物在哪、法律边界、复现命令）。
> 本文是**对照结论**：逐条拿泰拉瑞亚源码里的**真实实现**，对着我们自己的代码比，只保留
> 「有源码证据 + 对我们有真实落点」的项。
>
> **状态：纯研究文档，本轮不动任何代码。**
> （用户指令 2026-09-18：其他 agent 正在参考别的游戏学习优化中，泰拉瑞亚这一路先只做研究。）
>
> 源码根（下文相对路径均基于此）：`252_模拟世界游戏_会呼吸的世界/_study/terraria/tModLoader_proj/`

---

## 0. 一句话结论

泰拉瑞亚值得学的**不是内容量**（4000 物品 / 400 敌人那是结果，不是方法），而是两件工程学：

1. **把「每帧全图计算」换成「活跃集合 + 变更时预计算 + 分帧切片」。**
   它在**四个互不相干的地方**用了同一个手法 —— 液体、光照、自动贴图、特殊方块绘制。
   这不是巧合，是一款 2D 沙盒能跑满屏方块还没卡死的**统一世界观**。
2. **把「跨越所有内容的行为」从代码里抽出来**：`Loader / ModXxx / GlobalXxx` 三层 + 声明式配置
   → 自动生成设置界面。这是它加了十五年内容还不塌方的原因。

还有第三件事，但它是**反面教材**：泰拉瑞亚的联机是"**信任式转发**"——
**没有序列号重排、没有去重、没有方块校验和**（源码里查证过，见 §4.1）。
也就是说，**我们的 `netHash` / 预测回滚比它严格得多**。别被它的轻松带跑，把自己的确定性拆掉。

---

## 1. 边界：先划清"和已学的 MC / Noita / UE5 不重叠"

同一批参考游戏，各学各的，避免重复投入：

| 参考 | 已学走的部分 | 泰拉瑞亚**这一轮独有的** |
|---|---|---|
| **MC**（`MC_ADOPTION_ROADMAP.md`） | auto-step / 疾跑 / 潜行防坠边 / 连续挖放 / 防止嵌入 / HUD 与菜单**风格** | — |
| **Noita**（`NOITA_LESSONS.md`） | 材料规格数据层 / 密度下落 / 反应表 / 逐像素光 | — |
| **UE5**（`UE5_ANIM_BENCHMARK.md`） | 动作六项（取消窗/判定帧/连招/根运动/混合树/遮罩） | — |
| **泰拉瑞亚** | — | ① **方块光传播**（Noita 是逐像素，我们没有）② **UI 框架**（层 / 缩放 / 输入路由，MC 只给了"风格"）③ **自动贴图 autotile** ④ **液体活跃集**的具体实现 ⑤ **绘制层拓扑排序** ⑥ **粒子参数组（可序列化）** ⑦ **声明式配置 → 自动 UI** ⑧ **Loader/Global 三层扩展** ⑨ **TagCompound 段存档 / MOD 打包** |

> **判据**：MC 教的是"**动作手感**"，Noita 教的是"**材料数据模型**"，UE5 教的是"**动画原语**"，
> 泰拉瑞亚教的是"**一个长期内容平台怎么在工程上立住**" —— 四者不冲突。

**法律边界**（详见 `TERRARIA_STUDY.md §0`，此处只重申）：tModLoader 是 **MIT**，可放心参考；
Terraria 本体闭源，**只学思路，代码与素材一行不进项目**。本文只提"机制"，不搬运代码。

---

## 2. A 组 · 世界 / 光照 / 液体 / 碰撞

### 2.1 世界生成：命名有序 pass 列表（我们可学"组织方式"）

| | 内容 |
|---|---|
| **泰拉瑞亚怎么做** | `Terraria/WorldGen.cs` 入口 `GenerateWorld(seed)` → `AddGenerationPass(name, delegate)` 把**约 50 个阶段**顺序注册进有序集合：`Reset` → `TerrainPass` → 洞穴群（`Tunnels`/`Mount Caves`/`Dirt Layer Caves`/…）→ 冰原/丛林/沙漠 → 漂浮岛/大理石/花岗岩 → **矿脉 `Shinies`** → 地狱 → 腐化/湖/地牢 → 宝石/金字塔 → **平整 `Smooth World`** → 宝箱 → 苔藓 → 陷阱 → 出生点 → 树/藤/花。地形塑形在 `GameContent.Biomes/TerrainPass.ApplyPass` 逐列叠加噪声，**只产出 `worldSurface` / `rockLayer` 两条曲线**，后续所有阶段只读这两条、不重算。 |
| **我们怎么做** | 程序化 `biomeAt` + 分带生成（确定性坐标函数 + 滑动窗口），无多 pass 管线。 |
| **差距与可学点** | ① **pass 化组织**：把"噪声→群系→洞穴→矿脉→结构"做成**显式命名、顺序固定**的步骤列表，插入/调试/关停都只需动列表 —— 对我们的"真·无限 chunk 生成器"是现成的骨架。② **只产两条地形曲线供后续读**（避免每阶段重扫全图）—— 与我们"一个 chunk 生成一次、下游只读"一致，可显式化。③ 矿脉/结构 pass **晚于**洞穴 pass（否则被噪声覆盖）—— 顺序即正确性，值得记下。 |

> ⚠️ **不能照抄的**：泰拉瑞亚世界生成是**破坏性 RNG 顺序**（前一步消耗随机数影响后一步），
> 与我们的**确定性铁律**冲突（`MC_ADOPTION_ROADMAP §4` 已把"破坏性 RNG 地形生成"列为差异化红线）。
> 我们学的是"**pass 列表这个容器**"，pass 内部的随机必须走 `simStream(name+":"+tick)` 派生流。

### 2.2 光照传播：行列滚动扫 + 衰减（**这是我们真正的缺口**）

| | 内容 |
|---|---|
| **泰拉瑞亚怎么做** | 新引擎 `Terraria.Graphics.Light/LightingEngine.cs`。**不是洪水填充**：把区域按行扫一遍、再按列扫一遍，沿线保留当前最大亮度、按介质 mask 相乘衰减（`LightMap.BlurLine()`，⚠️ 该方法在 **`LightMap.cs`** 不在 `LightingEngine.cs`），**迭代 2 次**近似径向扩散。介质衰减写死在 mask：空气 `0.91`、实心 `0.56`、水 ≈`0.88`、蜂蜜 `(0.75,0.7,0.6)`。数据结构 `LightMap`（`Terraria.Graphics.Light/LightMap.cs`）：`Vector3[] _colors` + `LightMaskMode[] _mask`（`LightMaskMode` 是独立枚举文件 `LightMaskMode.cs`），默认 203×203。**分帧状态机**：`EngineState{MinimapUpdate, ExportMetrics, Scan, Blur}` 每帧只跑一个状态，`ProcessArea()` 摊开成本。天光 / 地狱光 / 墙光 / 方块自发光分裂成独立函数叠加（`ApplySurfaceLight`/`ApplyHellLight`/`ApplyWallLight`/`ApplyTileLight`）。动态光源用 `_perFrameLights`（List），**每帧重置**、逐点 `Vector3.Max` 合并后清空 —— 是"每帧重建的列表"，不是"持久光源集合"。 |
| **我们怎么做** | **没有方块光传播系统**（grep `Lighting/LightEngine` 零命中）。现状 = `DayCycle` 全局天光 + 半球光 + 顶点 AO（`Chunk`）。 |
| **差距与可学点** | ① **行列两扫 + 固定迭代次数**替代 BFS 洪水填充：缓存友好、且**迭代次数固定 → 天然可确定性**（这是关键，BFS 的访问顺序依赖队列实现，跨平台难保证）。② **mask 数组把"介质衰减"与"光源"解耦**：改水色只动一格，不动算法。③ **分帧状态机**把一次照明拆多帧 —— 与我们流式的 `SNAPSHOT/GEN/COMMIT/INDEX` 四阶段同一思路，可直接复用。④ 天光/方块光/墙光**分函数独立叠加**，便于按维度开关。 |

> **零漂移路线（关键）**：光照图是**派生缓存**（可每帧重算），若新增字段必须
> `StateCodec.SKIP`（照 MEMORY 铁律："派生脏标记也必须 SKIP"，否则编进 `netHash` → PREDROLLBACK 误报 desync）。
> **只看不写进 sim** 的话，光照层可做到**指纹逐字节不变**。

### 2.3 液体流动：双缓冲活跃集（我们已有同思路，可补"细节"）

| | 内容 |
|---|---|
| **泰拉瑞亚怎么做** | `Terraria/Liquid.cs` + `LiquidBuffer.cs`。**活跃集合** = `Main.liquid[]` 前 `numLiquid` 项（`maxLiquid=25000`）；溢出进 `LiquidBuffer`（容量 50000）。**去重靠 Tile 的一个位标志**（`checkingLiquid()` 已置位就不再入队）—— 不用哈希集。删除用**尾部换入**（`DelBuffer`）→ O(1)。**每帧只跑 `1/cycles` 的一段**（`cycles=10`），把成本切碎摊平。流量模拟用**"7 格窗口求均值"**（取 `x-3..x+3` 求和 `round(num/7)` 把 7 格设为均值）→ 天然产生连通器式水平流动；流量 >250 禁横向、<3 给 -1 偏置。 |
| **我们怎么做** | `PondSystem` / `WaterFlow`，且刚做过 **`CellSet` 增量改「待加/待删覆盖层 + 压实」**（`OVERLAY_LIMIT=1024`）—— **思路和泰拉瑞亚的活跃集高度一致**。 |
| **差距与可学点** | ① **尾部交换删除**（O(1)）—— 比我们契约里可能的移位更省。② **用已存在的位标志去重**，省一个 HashSet——我们方块的元数据里若已有布尔位，可借。③ **`1/cycles` 每帧切片**——我们已用 `MAX_STEPS_PER_FRAME`/`SHIFT_CHUNKS_PER_STEP` 控节奏，可对照其粒度选择。④ **7 格窗口均值**是廉价可靠的"压力"近似，比精确压力模型便宜。 |

> ⚠️ **高风险**：`CellSet` 有**最强的等价性门禁** `STREAMCHUNK`（钉死"元素集合 + 迭代序"）。
> 动 `CellSet` **前必须先有此等价性断言**（MEMORY 教训：没有等价性断言的模块别先动）。
> 因此这条**排后**，且必须"改前后逐元素等价"。

### 2.4 碰撞 / 台阶 / 斜坡（我们缺斜坡与平台）

| | 内容 |
|---|---|
| **泰拉瑞亚怎么做** | `Terraria/Collision.cs`。AABB 只遍历**玩家所在及邻近格**（`x-1..x+2, y-1..y+2`），不扫全行。`halfBrick()`（半砖）= 格顶下移 8px；`slope()` 1–4 = 四方 45° 斜面，**用枚举位而非额外几何**，按 slope 值与速度方向决定穿/贴，并检查相邻格拼接。**平台**靠 `TileID.Sets.Platforms` 标记"**仅自上而下才碰撞**"。上台阶 `StepUp` 只查**前方一列**，且**先验证头顶整段空**再抬升（上限约 16px）。**逻辑瞬时、视觉渐变**：抬升量与 `gfxOffY` 分开。 |
| **我们怎么做** | `World.floorY` + `solidBox` + `World.STEP_HEIGHT`（唯一来源），MC 已学 auto-step（`tryStepUp`）。**没有斜坡/半砖/平台**（grep `slope/halfBrick/platform` 在我们 `src/` 零命中）。 |
| **差距与可学点** | ① **斜坡用"格内枚举 + 源矩形细分条带"**实现，不需要额外几何体 —— 对体素游戏是"伪斜坡"的低成本做法。② **平台 = "仅自上而下碰撞"标记** —— 一格布尔换一个可穿透地板，性价比极高。③ **逻辑瞬时 / 视觉渐变分离**（`gfxOffY`）—— 我们 MC auto-step 已有同款，可对照推广到斜坡。④ 碰撞只查邻近格（我们已如此）。 |

---

## 3. B 组 · UI / 方块绘制 / 角色分层 / 粒子

### 3.1 UI 框架：**缩放挂在"层"上**（我们踩过 UI 坑的那一块）

| | 内容 |
|---|---|
| **泰拉瑞亚怎么做** | `Terraria.UI/GameInterfaceLayer.cs`：全部界面是 `List<GameInterfaceLayer>`，**注册顺序 = 绘制顺序**，`Draw()` **返回 false 即短路**后续层。**坐标系不是每个 UI 独立，而是每层声明 `ScaleType`**（枚举 `Game`/`UI`/`None`），一个枚举切三套矩阵（世界缩放 / UI 缩放 / 单位矩阵）。**鼠标路由是单目标递归**：每帧只调 `GetElementAt(MousePosition)` 取**唯一最上层**目标（从 `Elements.Count-1` 倒序，后加者为上层），事件沿 `Parent` **冒泡**；`IgnoresMouseInteraction` 可穿透。`UserInterface.SetState` 生命周期 = `Deactivate → ResetState → Activate → Recalculate`，**历史栈上限 32**（做返回）。`ItemSlot.Context` 用**整数枚举**编码 34 种槽位语义，`Handle` 统一分派左右键/悬停。 |
| **我们怎么做** | `render/lwjgl/HudText.java`（HUD 文本）+ `core/sim/MenuModel.java`（菜单模型，行数被审计 **C6** 锁死：`SETTINGS_ROWS` ↔ `MenuTest.itemCount()`）。**没有 UI 层/缩放/输入路由框架**。 |
| **差距与可学点** | ① **缩放挂在"层"而非"UI"** —— 一个枚举解决"HUD 用像素坐标、世界标记用世界坐标"两类需求，正是我们 HUD/菜单踩坑的根因所在。② **单目标路由 + 冒泡** —— 避免"多个元素同时响应一次点击"。③ **历史栈做返回**（32 深度）—— 菜单/背包/设置层级的天然实现。④ **整数 Context 编码槽位行为**，不靠子类化。 |

> **零漂移路线**：UI 是**纯渲染层** → 指纹无关。但改菜单结构**必须同步 `MenuTest` 的行数断言**
> （审计 C6 会红）。建议"**加段而非加道**"，且新增 UI 文案只能用已注册字形（审计 C5）。

### 3.2 方块绘制：自动贴图在"变更时"预计算（我们缺连接纹理）

| | 内容 |
|---|---|
| **泰拉瑞亚怎么做** | **autotile 不在绘制期算**：世界/方块变动时由 `WorldGen.TileFrame` 依 **8 邻域**写入每格的 `tile.frameX/frameY`；绘制循环只逐格取 `(type, frameX, frameY)` 当源矩形用（**绘制期零邻域查询**）。**光照 = 每格颜色乘源贴图**（`Lighting.GetColor(x,y)` 作乘色，`spriteBatch.Draw`）—— **不是 shader pass，是顶点色乘法**。相邻同化（merge）用 **`bool[type][type]` 二维表**（`Main.tileMerge`，启动期 `SetupTileMerge` 生成）。**特殊方块**（风摆/发光/树）不攒批，另存坐标到 `_specialPositions[type]`（`MAX_SPECIALS=9000`），**延后到独立通道**绘制。斜坡/半砖用**源矩形细分成 8 条 2px 条带**画，不需要单独贴图。 |
| **我们怎么做** | 按方块类型选图集 tile；光照走 shader（world 程序 6 属性：`aPos/aCol/aNormal/aWind/aUv/aLamp`）；无 autotile、无 merge 表。 |
| **差距与可学点** | ① **autotile 预计算**：把"连接形态"在 `editBlock` 时算好存起来，绘制期零邻域查询 —— 直接可移植到我们的 3D 版本（8 邻域 → 6 面邻域）。② **merge 用预生成二维表**，启动期一次，运行期 O(1) 查。③ **顶点色乘法** vs shader：我们已有 shader，**不必降级**，但"每格乘色"的思路可用于 `aLamp` 通道语义。④ **特殊方块延迟绘制**：把风摆/发光类方块抽成坐标列表，与主循环解耦 —— 我们可有条件使用。 |

> **零漂移路线**：连接状态是**渲染层派生**（我们已有 `FaceCull` 同款思路），**不写进 `mat` 数组** →
> 指纹零影响。若要在 `editBlock` 时更新，注意这属**渲染侧 dirty 标记**，不进 `hashState`。

### 3.3 角色分层绘制：拓扑排序的绘制层（我们捏脸的天然升级）

| | 内容 |
|---|---|
| **泰拉瑞亚怎么做** | 确有**绘制层注册机制**：`PlayerDrawLayer` 用 `Position`（`Between(Layer1,Layer2)` / `BeforeParent` / `AfterParent` / `Multiple`）声明**相对位置**，启动期 `TopoSort` 拓扑排序成最终绘制序（从 `JimsCloak`/`Wings`/`HairBack` 到 `Torso`/`Head`/`FaceAcc`/`Shield`）。每层**只往 `DrawDataCache` 追加 `DrawData` 值对象**，不直接发绘制调用；收集完一次性批渲染。变换（`TorsoGroup`/`MountGroup`）是**可叠加的父子链**。装备外观 = "槽位 → `ArmorID` 贴图"映射；**染色不换贴图**，而是把染料打包成 `shader` 整数写进每笔 `DrawData` 的 `shader` 字段。 |
| **我们怎么做** | `core/world/Appearance.java`（捏脸，F 批：4 预设 + 菜单调参），参数化单层。 |
| **差距与可学点** | ① **层只声明"相对谁前谁后"，拓扑排序求序** → 添加一层（翅膀/披风/坐骑）**零改引擎**，这正是我们"加内容零改引擎"目标在渲染侧的镜像。② **层只生产值对象，收集完一次批渲染** → 减少状态切换。③ **父子链变换**可叠加，而非写死坐标。④ **染色走 shader 通道、不换贴图** → 省大量贴图变体。 |

### 3.4 粒子编排：**参数组可序列化 → 天然联机同步**

| | 内容 |
|---|---|
| **泰拉瑞亚怎么做** | 用 **`ParticleOrchestraType` 枚举（33 种）+ `ParticleOrchestraSettings` 结构体（仅 4 字段）** 取代"每种特效写一个类"。`Settings` 只有 `PositionInWorld / MovementVector / UniqueInfoPiece / IndexOfPlayerWhoInvoked` 四个通用字段，`SerializationSize=21`，**自带 `Serialize/Deserialize`**。`RequestParticleSpawn` 里：本地则 `SpawnParticlesDirect`（一张巨型 `switch(type)` 分派到各"编排函数"，只做"填参数 + 入池"），否则经网络发送 —— **同一参数组既可本地实例化、也可走网络**。粒子按种类**分池**（7 个池，初值 200），`RequestParticle` 复用 `IsRestingInPool` 的实例。 |
| **我们怎么做** | `core/content/ParticleSim.java`。演出随机走 `fxRng`；粒子是**本地演出**，未进网络。 |
| **差距与可学点** | ① **(枚举 + 4 字段参数组) 取代每特效一个类** —— 加特效 = 加一个枚举值 + 一个编排函数，不改框架。② **参数组实现 `Serialize`** → 特效**可网络同步、可回放**（泰拉瑞亚用它把打击特效同步给所有客户端）。③ 编排函数"只填参数+入池"，粒子自身负责更新/绘制 —— 职责单一。④ **按种类分池**，容量可预估。 |

> **零漂移路线**：粒子若走网络，必须走 `Intent`/net 通道而**不进 `hashState`**；本地粒子继续用 `fxRng`。
> 关键是**绝不把粒子状态写进 `World` 的可持久化字段**。

---

## 4. C 组 · 网络 / 内容平台 / 配置 / 存档

### 4.1 网络同步：**它是"信任式转发"，我们比它严格**（反向证据，最重要）

| | 内容 |
|---|---|
| **泰拉瑞亚怎么做** | 收发各一个 `131070` 字节缓冲。拆帧靠**2 字节小端长度前缀**（`[uint16 长度][1 字节消息 ID][负载]`），残帧 memmove 回缓冲头部。**查证结果：没有序列号、没有时间戳重排、没有去重**（`PacketHistory` 的时间戳仅 `[Conditional("DEBUG")]` 转储用）；**没有方块校验和**（未能证实存在）；一致性靠"**服务端收到即转发广播**"（`TrySendData`）。世界同步按**区段**（`SendSection` 按 200×150 分块，`TileSections[x,y]` 布尔数组惰性去重）；单格改动用 **1 字节 opcode**（0–23，含 KillTile/PlaceTile/Slope）。防刷靠独立高频计数（`SpamDeleteBlock`/`SpamProjectile`）。 |
| **我们怎么做** | `core/net/`：`IntentCodec`（6B `Intent`，**显式 switch 不依赖枚举 ordinal**）、`netHash`、`PREDROLLBACK` 预测回滚、快照/回滚三件套（稀疏差分 317KB / 原地恢复 / 黄金判据）、真实 UDP 锁步（跨 JVM hash 逐字节一致）。 |
| **差距与可学点** | ① **结论：我们的路线更强** —— 泰拉瑞亚是"信任客户端"，我们做的是**确定性校验 + 回滚**。**不要因为"泰拉瑞亚这么做"就删掉我们的 netHash/校验**。② **可学的只有工程细节**：长度前缀自定界拆帧（我们可对照）、**区段惰性发送 + bool 去重**（对应我们的 chunk 流式）、**1 字节 opcode 紧凑编码**（对照我们 6B Intent）、**独立计数限流**（防刷）。③ 它的"收到即转发"是**低延迟但弱一致**；我们若要在"非关键表现层"（如纯视觉特效）用类似"广播即达成"来省回滚成本，**是可选优化，但别碰仿真态**。 |

### 4.2 内容注册表：确定性排序 + 数组自增 ID

| | 内容 |
|---|---|
| **泰拉瑞亚怎么做** | `Mod.Autoload` 反射扫程序集所有类型，**`orderby type.FullName`** 排序后实例化 → **加载顺序确定性**。每 mod 一个 `List<ILoadable>`；ID 分配 = **"数组 + 自增"**（模组 ID 从 `ItemID.Count` 起，`Register` 返回 `ItemCount++`），加载完 **`ResizeArrays` 一次性 `Array.Resize` 到最终长度**，运行期不再扩容。**字符串 ID 与整数 ID 分离**：`ModTypeLookup<T>` 键 `ModName/Name` 查名，数组按整数取用。 |
| **我们怎么做** | `core/content/ContentRegistry`，以**文件名作 ID**（已具备）。 |
| **差距与可学点** | ① **按 FullName/文件名排序保证加载顺序可复现** —— 与我们确定性铁律一致，可显式化并在门禁里断言。② **加载完统一 Resize 一次**，避免运行期扩容。③ 字符串 ID / 整数 ID 分离存放。④ ⚠️ 我们踩过 `ContentRegistry.modules()`**参数送不到 config** 的坑（MEMORY：投递端缺失）—— 泰拉瑞亚的 `AddContent → Load → ContentInstance.Register` 有**明确的注册-加载-实例化三段**，值得对照检查我们的投递链是否完整。 |

### 4.3 三层扩展 Loader / Mod / Global（我们 MOD 平台的下一级）

| | 内容 |
|---|---|
| **泰拉瑞亚怎么做** | **`ModXxx`**（单个内容的定义）+ **`GlobalXxx`**（**跨所有内容**的横切行为，作用于原版 + 所有 mod）+ **`Loader`**（纯静态聚合与分派）。`Loader` 用**表达式树** `AddHook((GlobalItem g) => g.Method)` 只把**真正 override 了的实例**收集进 `defaultInstances[]`/`indices[]`（**避免遍历全集**）。`Global` 可 `InstancePerEntity` + `PerEntityIndex`，在实体上存状态。**"不能继承原版类"正是三层的根因**：`ModXxx` 管单内容、`GlobalXxx` 做横切、`Loader` 做静态聚合。加载期用反射 `ValidateType` 强制约束并立即报错。 |
| **我们怎么做** | `GameplayModule`（代码机制扩展）+ JSON 内容层 + `mods/` 加载（G 批已完成：目录/zip、覆盖优先级、冲突报告）。 |
| **差距与可学点** | ① **`Global` 横切层**是我们目前最缺的一层：若要在"**所有** beast 上加一个通用行为"，现在可能得改 `Beast`；泰拉瑞亚的 `GlobalNPC` 是干净解法。② **表达式树只收集 override 者** → 钩子分派不遍历全集（性能）。③ **加载期反射校验 + 立即报错**，别把错误留到运行期。④ 三层职责划分（单内容 / 横切 / 静态聚合）可作为我们 `GameplayModule` 的演进参考。 |

> ⚠️ 这是**引擎层架构改动**，会碰核心 → 属**专项**，不宜与其他优化混做。

### 4.4 配置系统：声明式 → 自动生成设置界面

| | 内容 |
|---|---|
| **泰拉瑞亚怎么做** | 配置 = 普通 C# 属性 + JSON（`[JsonIgnore]` 排除元数据、`[DefaultValue]` 给默认）。**UI 自动生成**：`UIModConfig.WrapIt` 按**成员类型 → 控件**映射（bool→开关、float→滑条、int→输入或范围、string→输入或选项、`List<>`/`Color`/`Vector2` 各有专类），`[Slider]`/`[OptionStrings]` 微调，`[CustomModConfigItem]` 可指定自定义控件。特性解析取"**成员级优先于类型级**"。`ConfigScope` 分 Client/Server，服务端配置走包同步；`[ReloadRequired]` 让"是否需重载"**自动可判**。 |
| **我们怎么做** | `MenuModel` 硬编码设置行（`SETTINGS_ROWS`），审计 **C6** 锁死行数 ↔ `MenuTest.itemCount()`。已有的 7 个"有牙预设参数"落在 `WorldConfig`（`StateCodec.SKIP`）。 |
| **差距与可学点** | ① **"类型 → 控件"映射表 + 特性微调 = 全自动设置界面** —— 对我们 7 个 `WorldConfig` 参数是天然升级：加参数不再手写菜单行。② **成员级优先于类型级**的特性解析规则。③ Client/Server 双作用域（我们联机可用）。④ `[ReloadRequired]` 自动判定需否重载。 |

> ⚠️ **高风险**：自动生成的 UI 会**破坏审计 C6**（行数硬编码）。要走这条路必须**同步改 C6 的判据**
> （从"行数"改为"参数集合 ↔ 控件集合"），属专门改动，不能顺手做。

### 4.5 存档 / 本地化 / 打包

| | 内容 |
|---|---|
| **泰拉瑞亚怎么做** | **`TagCompound` = `Dictionary<string,object>`**（NBT 式命名键值），支持基本类型/数组/`List<T>`/嵌套；**缺键返回类型默认值** → 新字段可加、老档不崩。每个 mod/实体用独立 `TagCompound` **段注入**（`HookSaveData`/`HookNetSend`）。本地化扫 `.hjson`，按 `{culture}_{prefix}.hjson` 命名自动识别语言（如 `en-US_Mods.X.hjson`），构建后**自动补全本地化键**（键 = `Mods.{ModName}.{Category}.{Entry}`）。官方模板 11 个文件：`--ModName--.cs` + `.csproj`（导入 `tModLoader.targets`）+ `Content/Items/*.cs` + `.png` + `Localization/*.hjson` + `build.txt` + `icon.png` + `launchSettings.json`。 |
| **我们怎么做** | `StateCodec`（**显式 SKIP** + `SKIP_LIST_DOCUMENTED` + `SKIP_COUNT_BASELINE`，现行 SKIP **30** 项）；`SAVE_EXT_VERSION`。CJK HUD 已做。 |
| **差距与可学点** | ① **我们更严格**：显式 SKIP 避免"幽灵条目"，泰拉瑞亚的"命名键值 + 缺键默认"更宽松但有腐化风险。**这是取舍，不是落后** —— 我们的方案在确定性/可审计上更强。② **可学的**：**段注入式扩展序列化** —— 为**MOD 内容**提供独立存档段，不动主 `StateCodec`（这对我们 mod 存档兼容有真实价值）。③ **本地化按文件名自动识别文化 + 构建后自动补键** —— 对我们 CJK/多语言文案管线有参考。④ **MOD 打包模板**（csproj 导入 targets + `build.txt` 元数据 + 资源直接入包）—— 对照我们 `mods/` 打包规范。 |

---

## 5. 能力缺口清单（泰拉瑞亚有、我们没有，按"补上就能玩到"排序）

| # | 缺口 | 直接后果（今天就能观察到） | 我们的对应模块 |
|---|---|---|---|
| 1 | **方块光传播** | 洞里白天也是亮的（只有天光）；火把/发光方块不照亮周围 | 渲染层（`Chunk`/`Game`）；`aLamp` 通道已预留 |
| 2 | **UI 框架（层/缩放/输入路由/历史栈）** | HUD 与世界标记用同一套坐标；层级切换靠手写；曾出 UI 坑 | `HudText` + `MenuModel` |
| 3 | **声明式配置 → 自动设置 UI** | 加一个 `WorldConfig` 参数要手写菜单行 + 改测试 | `MenuModel` + `WorldConfig` |
| 4 | **自动贴图 autotile** | 相邻同类方块看不出"连成一片"，边缘生硬 | `Chunk` 网格化 |
| 5 | **`Global` 横切扩展层** | 给"所有 beast"加通用行为要动 `Beast` | `GameplayModule` + `Beast` |
| 6 | **斜坡 / 半砖 / 平台** | 地形只有立方体，走不出缓坡、没有可穿透地板 | `World.floorY`/`solidBox` |
| 7 | **粒子参数组可序列化** | 打击特效无法同步给其他玩家 | `ParticleSim` |
| 8 | 绘制层拓扑排序 | 加外观层（翅膀/披风/坐骑）要改渲染代码 | `Appearance` |
| 9 | 世界生成 pass 化容器 | 生成逻辑散落，插入/调试步骤不直观 | chunk 生成器 |
| 10 | 段注入式 MOD 存档 | mod 内容存档扩展可能要动主 `StateCodec` | `StateCodec` |
| 11 | 长度前缀拆帧 / 区段惰性发送 | （我们已有锁步，属"打包方式"可优化项） | `core/net/` |
| 12 | 本地化文件名自动识别 + 自动补键 | CJK 之外的多语言要手工挂接 | 文案管线 |

> **注意**：这里**没有列**"内容量"（4000 物品）和"敌人 AI 分支"（150+ if）——
> 前者是结果不是方法，后者与我们 `Decision/Mind` 涌现护城河冲突（MC 已列同款红线）。

---

## 6. 最值得学的 6 件事（按性价比排序，含落点与零漂移路线）

| 序 | 学什么 | 落点 | 收益 | 指纹影响 |
|---|---|---|---|---|
| **①** | **声明式配置 → 自动设置 UI**（§4.4） | `MenuModel` + `WorldConfig` | 加参数零手写，直接服务已有 7 个有牙参数 | ⚠️ 需同步改审计 C6 判据（行数→集合） |
| **②** | **UI 框架：缩放挂层 + 单目标路由 + 历史栈**（§3.1） | `render/lwjgl/`（新 UI 层） | 治"UI 坑"的根因，HUD/世界标记/菜单统一 | **渲染层，零影响**（须同步 `MenuTest` 行数） |
| **③** | **方块光传播（行列扫 + mask + 分帧）**（§2.2） | `Chunk`/`Game` 渲染侧 | 洞里会黑、火把会亮——**观感提升最大** | **派生缓存必须 SKIP**；只看不写 sim → 零影响 |
| **④** | **autotile 变更时预计算**（§3.2） | `Chunk`（`editBlock` dirty） | 相邻方块连成整片，边缘自然 | **渲染层派生，零影响** |
| **⑤** | **`Global` 横切扩展层**（§4.3） | `GameplayModule` 体系 | MOD 平台下一级；通用行为不用改核心类 | 引擎层，**专项**，慎做 |
| **⑥** | **粒子参数组（枚举 + 可序列化 4 字段）**（§3.4） | `ParticleSim` | 加特效不改框架；特效**可同步/可回放** | 本地走 `fxRng`，**零影响**；联网走 net 通道 |

**排后的两项**（不是不做，是依赖前置）：
- **液体活跃集细节**（§2.3）——必须先有 `CellSet` 等价性断言（`STREAMCHUNK` 已是最强，但改动前要复验）。
- **段注入 MOD 存档**（§4.5）—— 碰 `StateCodec`，需与存档方案一起设计。

---

## 7. 边界：**不该学**的（防止过度承诺）

| 不该学 | 为什么 |
|---|---|
| **信任式联机（无校验/无重排）** | 我们的 `netHash` + 预测回滚**更严格**；照抄等于自毁确定性护城河（§4.1） |
| **破坏性 RNG 世界生成** | 与"同种子逐字节复现"铁律冲突；只学"pass 列表"这个**容器**（§2.1） |
| **150+ NPC 的硬编码 AI 分支** | 我们的 `Decision/Mind` 涌现是护城河（MC 已列同款红线） |
| **4000 物品 / 400 敌人的内容量** | 那是**结果**；我们要的是"扩展位"（我们 `ContentRegistry` 已具备） |
| **2D 16px 瓦片的 autotile 表** | 我们是 3D 体素，需改成 **6 面邻域**版本，不能照搬 8 邻域 |
| **Terraria 本体代码 / 美术 / 音频** | 闭源 + 版权；tModLoader 才是 MIT 可参考部分 |
| **每帧全图光照重算** | 我们 160×112×160 = **287 万格**，必须"分帧状态机 + 活跃区域"，不能全图 |

---

## 8. 建议顺序（**本轮不执行，等统一排期**）

> 用户指令：其他 agent 正在参考别的游戏学习优化中，泰拉瑞亚这一路**先出文档、不动代码**。

若后续排期，建议：

1. **先做零漂移的三件**（渲染层，不动 sim）：② UI 框架 → ③ 方块光 → ④ autotile。
2. **再评估两件需同步门禁的**：① 配置自动 UI（改 C6）、⑥ 粒子参数组（加 net 通道）。
3. **最后是引擎层专项**：⑤ `Global` 横切层、液体活跃集、段注入存档 —— 各自独立立项，不与前面混做。

**验收纪律（沿用既有铁律）**：
- 渲染层改动 → **四道指纹逐字节不变**（`DET`/`ZD`/`STREAMING`/`NPC` 等）。
- 若碰仿真/派生字段 → 新字段必 `StateCodec.SKIP` + 同步 `SKIP_LIST_DOCUMENTED` / `SKIP_COUNT_BASELINE`。
- 给既有门禁**加段而非加道**；门禁数**现数**（勿凭印象）。
- 任何"看起来像没接线"的改动，跑 `python audit_consumers.py` 确认有真实消费者。

---

## 9. 一句话收口

**泰拉瑞亚教会我们的不是"做什么内容"，而是"怎么组织一个能长期长内容的系统"**：
活跃集合 + 变更预计算 + 分帧切片（四个地方同一手法），
Loader/Global 三层 + 声明式配置自动 UI（两个平台级抽象）。
而它的**联机与存档策略比我们宽松** —— 那部分我们不学，甚至要反着来。

真要看代码，从这三个文件切入最省力：
`Terraria.Graphics.Light/LightingEngine.cs`（光照，对我们缺口最大）、
`Terraria.UI/UserInterface.cs` + `GameInterfaceLayer.cs`（UI 框架）、
`Terraria.ModLoader/ModContent.cs` + `Config/UI/UIModConfig.cs`（内容平台 + 自动配置）。

---

## 10. 体检记录（2026-09-18，陀螺复核后入库）

**方法**：只读脚本核对，不重跑反编译。三查 —— ① 引用的源码文件/关键字是否存在；
② 文档中"我们怎么做"的断言是否与实际代码相符；③ 交叉引用的 docs 是否悬挂。

| 类别 | 项 | 实测 | 判定 |
|---|---|---|---|
| **源码证据** | `LightingEngine.cs` 的 `ProcessArea`/`EngineState`/`ProcessBlur` | 全部存在 | ✅ |
| | `BlurLine` / `LightMaskMode` | 存在，但**在 `LightMap.cs` / `LightMaskMode.cs`**，不在 `LightingEngine.cs` | ⚠️ 已修正归属 |
| | `Liquid.cs` 的 `numLiquid`/`maxLiquid`/`cycles`/`DelBuffer` | 全部存在 | ✅ |
| | `Collision.cs` 的 `halfBrick`/`slope`/`StepUp` | 全部存在 | ✅ |
| | `TileDrawing.cs` 的 `MAX_SPECIALS`、`Main.cs` 的 `tileMerge` | 存在 | ✅ |
| | `ParticleOrchestrator.cs` 的 `ParticleOrchestraType`/`Settings` | 存在 | ✅ |
| | `UIModConfig.cs` 的 `WrapIt`/`CustomModConfigItem`/`OptionStrings` | 存在；`ReloadRequired` 在 `ConfigElement.cs`/`Interface.cs` | ✅（概念成立） |
| **我们自己的代码** | 「没有方块光传播系统」（grep `Lighting`/`LightEngine` = 0） | **0 命中** | ✅ |
| | 「没有斜坡/半砖/平台」（grep `slope(`/`halfBrick`/`Sets.Platforms` = 0） | **0 命中** | ✅ |
| | world shader 有 `aLamp` 通道 | 命中 2 处 | ✅ |
| | `StateCodec` 现行 SKIP **30** 项 | **30**（`RECORDED_SKIPPED = 30`） | ✅ |
| | `STREAMCHUNK` 门禁存在、`SETTINGS_ROWS` 被 C6 锁 | 均命中（各 3 处） | ✅ |
| | `audit_consumers.py` 存在 | 存在 | ✅ |
| **交叉引用** | `MC_ADOPTION_ROADMAP` / `NOITA_LESSONS` / `NOITA_STUDY` / `UE5_ANIM_BENCHMARK` / `CONTENT_PLATFORM` | 全部存在 | ✅ 无悬挂引用 |

**未验项（诚实标注）**：§4.1「没有序列号/没有方块校验和」是**否定性断言**，
本文按原文"查证过 / 未能证实存在"保留，**未独立复核**；
§2.1 世界生成 50 个 pass 的具体清单未逐条数。若要落地请先自行复核这两条。
