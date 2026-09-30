# 四路源码「最优可学项」清单（MC 1.20 / UE5 / Noita / Terraria）

> 口径：**只选最优优势，不怕困难**。
> 判据三条，缺一不选：① **有源码证据**（文件 + 符号 + 行号）② **对得起改动代价**（落到我们代码里的工作量 ≤ 收益）
> ③ **与铁律相容**（确定性四指纹零漂移，或走「有意演进记账」）。
>
> 本文只做**调研与排序**，**不动代码**（沿用 `TERRARIA_LESSONS.md` §8 的纪律）。
> 已有文档的分工：`NOITA_STUDY/MODULES/LESSONS` 是 Noita 深读，`TERRARIA_STUDY/LESSONS` 是泰拉瑞亚深读，
> `MC_ADOPTION_ROADMAP` 是 MC 已采清单。**本文是四路横向对比后的「下一批做什么」决策文档。**

---

## 0. 一句话结论

四路源码里，**最值得我们冒尖的只有 3 件事**，其余全部是「已学走」或「不该学」：

| 排名 | 学什么 | 来源 | 为什么是它 | 指纹影响 |
|---|---|---|---|---|
| **①** | **Noita 式「时间累积泛光」**（上一帧 glow 缓冲 × 模糊衰减 + 本帧采样） | Noita `post_glow1/2.frag` | 我们现有 bloom 是**无记忆的一次性阈值+模糊**；Noita 是**带记忆的**。观感差别巨大（火光拖尾/余晖），**纯渲染层零漂移**，且我们 FBO 链已就位，只差一个 ping-pong 缓冲 | **零漂移**（渲染层） |
| **②** | **Terraria 式「行/列两扫 + mask + 固定迭代」光照** | Terraria `LightMap.BlurLine` + `LightingEngine.EngineState` | 我们现用 **BFS 逐源**（`World.seedLight`）。Terraria 是**与源数无关**的两次线性扫 + 明暗 mask 分别衰减；**迭代次数固定 → 天然确定性**，且能把「介质衰减」（空气/实心/水）**做成材质数据**——正好接上我们已落地的 `MaterialDef` | **零漂移**（派生缓存，须 `StateCodec.SKIP`） |
| **③** | **Terraria 式「分帧切片状态机」泛化**（一帧只做一件事） | Terraria `EngineState{Minimap,ExportMetrics,Scan,Blur}` | 我们已有分帧流式（M3①），但**只用在流式一处**。Terraria 把「光照 / 小地图 / 场景指标」全部编成分帧环。我们有 `drawMinimap`（每帧全算 48×48）与 `computeLight()`（编辑时全量重建）两个**同型**问题 | **零漂移** |

**必学的「不该学」边界**（已在既有文档成文，本文复核后维持）：MC 的破坏性 RNG 地形生成 / 实体 AI 随机游走 / 红石全量 / 写实材质光影；Terraria 的**信任式联机**（我们 `netHash` 严格得多）；Noita 的**逐像素求解器**（我们 287 万格不可能全网格扫）。

---

## 1. Noita · 时间累积泛光（★★★ 最高性价比）

### 1.1 源码证据

`D:/study/Noita_unpacked/data/shaders/post_glow1.frag`（33 行，全文）：

```glsl
const float BLUR_RADIUS = 5.0;
uniform sampler2D tex_glow_prev_frame;      // ★ 上一帧的 glow 缓冲
uniform vec2      one_per_glow_texture_size;
void main() {
    vec4 decayed = vec4(0.0);
    vec2 offset = one_per_glow_texture_size * 1.5;
    for (float x = -BLUR_RADIUS; x <= BLUR_RADIUS; x += STEP)
        decayed += texture2D(tex_glow_prev_frame, gl_TexCoord[0].xy + vec2(x,0.0)*offset);
    decayed *= 0.1;                          // ★ 一维模糊 + 衰减
    gl_FragColor = decayed;
}
```

`post_glow2.frag`（64 行）是它的正交搭档：把**上一帧缓冲沿 y 再模糊一轮**（`*= 1.0/12.2`），
再把**本帧新样本**混进去：

```glsl
vec4 new_tap = texture2D(tex_glow_source, tex_coord_source) * 4.0;   // ★ HDR 重映射（gfx_glow 可达 1000）
new_tap += texture2D(tex_glow_source_particles, tex_coord_source_inv) * 2.5;
...
gl_FragColor = new_tap * 0.125 + vec4(mix(decayed, new_tap, 0.05).rgb, 1.0);
//                                          ^^^^ 只有 5% 追上本帧 → 99% 记忆
```

### 1.2 与我们现状的**结构性差异**（这是关键）

我们 `Game.java:2247-2300 drawBloom()` 是**五步一次性管线**：

```
发光源网格 → bloomFBO(全) → 阈值 → brightFBO(半) → 横模糊 → 纵模糊 → 加性合成
```

**每一帧完全重来，帧与帧之间没有任何记忆。** 后果：

- 火光/熔岩/爆炸**没有余晖**——亮起来就是亮，灭掉就瞬间消失（"灯泡"感，不是"火焰"感）；
- 眼睛对「持续发光体」的感知被严重削弱：真实世界里盯着光源，视网膜会累积（这正是 bloom 说服力的物理来源）；
- 我们的 `emissive` 网格（LAMP/FIRE/GOLD）已经有独立 pass，**天生就是把「谁发光」解耦出来了**——加时间累积不需要动任何仿真代码。

### 1.3 落点（改动量很小）

| 项 | 内容 |
|---|---|
| 新增 GL 对象 | 再加一组 `glowFBO/glowTex`（半分辨率即可）做 ping-pong 双缓冲 |
| 新增 shader | 2 个（横衰减 / 纵衰减+本帧混合），可直接照抄 Noita 的数学（`BLUR_RADIUS=5`、`*0.1`、`1/12.2`、`mix(...,0.05)`） |
| 改动点 | `Game.drawBloom()` 第 5 步之前插入 ping-pong；`initBloom()` 加 FBO |
| 门禁 | `BLOOM` 段加断言：**上一帧缓冲读写目标不同**（防 ping-pong 写回读源）；`glowReady=false` 时逐字节等价旧行为 |
| 指纹 | **零**——纯 `render` 包，`hashState` 一行不碰 |

> **Noita 的额外细节值得抄**：① `new_tap *= 4.0` 是**HDR 重映射**（我们把 `emissive` 写到 >1 就是同一思路）；
> ② 边缘处 `weight` 衰减（`EDGE_AFTER_IMAGE_REDUCTION_*`）——**相机快速移动时**，上一帧的亮区会"贴在屏幕上不动"，
> Noita 用「越靠边越压暗」抑制这种残影。**这条我们必须抄，否则第三人称/骑车时穿帮最明显。**

---

## 2. Terraria · 固定迭代两扫光照（★★★ 观感提升最大）

### 2.1 源码证据：`LightMap.BlurLine`（第 162-302 行）

```csharp
private void BlurLine(int startIndex, int endIndex, int stride) {
    Vector3 zero = Vector3.Zero;
    bool flag=false, flag2=false, flag3=false;      // 三个通道各一个「已进入黑暗」标志
    for (int i = startIndex; i != endIndex + stride; i += stride) {
        if (zero.X < _colors[i].X) { zero.X = _colors[i].X; flag = false; }  // ★ 取历史最大值
        else if (!flag) {
            if (zero.X < 0.0185f) flag = true;        // ★ 低于阈值就停（省算力 + 防长尾）
            else _colors[i].X = zero.X;               // ★ 否则向下写最大值
        }
        ...
        switch (_mask[i]) {                            // ★ 按「介质」选衰减率
        case LightMaskMode.None:   zero.X *= LightDecayThroughAir;   break;  // 0.91
        case LightMaskMode.Solid:  zero.X *= LightDecayThroughSolid; break;  // 0.56
        case LightMaskMode.Water:  zero.X *= LightDecayThroughWater.X * num; break; // (0.88,0.96,1.015)*0.91
        case LightMaskMode.Honey:  zero.X *= LightDecayThroughHoney.X; break; // (0.75,0.7,0.6)*0.91
        }
    }
}
```

`LightingEngine.ProcessBlur()`（153-158 行）+ `LightMap.Blur()`（131-136 行）：

```csharp
_workingLightMap.Blur();     // BlurPass(); BlurPass();  ← 正好 2 次
```

`BlurPass()`（138-160 行）**一次性发起 4 条扫**：上行/下行（按列）、左行/右行（按行）。
`LightingEngine.ProcessArea()`（85-128 行）用 `_state` 四态轮转：

```csharp
private enum EngineState { MinimapUpdate, ExportMetrics, Scan, Blur, Max }
// 每帧只做其中一个 → IncrementState(): _state = (_state+1) % 4
```

### 2.2 与我们现状的**结构性差异**

| 维度 | 我们（`World.computeLight` 1939-1981） | Terraria |
|---|---|---|
| 算法 | **逐源 BFS**（LAMP + FIRE 各做一次 6 邻 BFS） | **行列两扫 × 2 轮**，与源数**无关** |
| 成本 | 源数 × 半径球体（窗口内灯 >30 时数 ms） | 固定 = `2 × 2 × 面积` → **可预测** |
| 介质衰减 | 只有「不透明格挡光」一个布尔（`Blocks.byBlock.opaque`） | **空气 0.91 / 实心 0.56 / 水 0.88/0.96/1.015 / 蜂蜜 0.75/0.7/0.6** → 水下发蓝、蜂蜜发黄 |
| 平滑 | BFS 结果**硬边**（0 衰减过渡） | 两扫 + 2 轮 → **天然柔和的渐变**（Terraria 的标志性光照手感） |
| 确定性 | BFS 顺序确定（自测已证） | **迭代次数固定 → 天生确定**，无队列顺序依赖 |

**Terraria 的关键洞察**：光照不需要"光从源直线传播"的物理正确性，
只需要"看起来像光"。两扫把「上/下/左/右方向的历史最大值 + 介质衰减」叠加两次，
就得到了**连续渐变**——这正是我们 BFS 缺的那一层柔和度。

### 2.3 落点

| 项 | 内容 |
|---|---|
| 数据来源 | `MaterialDef` 已上 6 字段，**加第 7 个 `lightDecay`**（缺省 = 按 `cellType` 给默认：solid→0.56 / liquid→水色 / else→0.91） |
| 新实现 | `World.computeLight()` 改为「扫 `mat` 两轮、每轮行列各一次」；**保留** `lightGrid` 字段名与 `lightAt()` 签名 → 下游零改动 |
| 关键守护 | 光照是**派生缓存**（`lightGrid` 已是 `byte[]`、不在 `hashState`）→ 新字段/新算法**必须确认不进 `StateCodec`**（`StateCodec.SKIP` 已含 `lightGrid`，需复核） |
| 门禁 | `LIGHT` 段加断言：① 源格亮度 = 满级 ② 全不透明墙两侧亮度**不互相渗透**（防两扫串色）③ 水下的亮度**按水色衰减**（B vs R 比值）④ 迭代次数 == 2（硬编码，防"多扫几轮更好看"悄悄改成本） |
| 指纹 | **零漂移**（纯派生，`hashState` 不碰）—— 但**必须跑四指纹确认**，因为我们历史上踩过「派生脏标记进 netHash」的坑 |
| 风险 | 视觉会**明显变化**（所有暗处变柔和）→ 需要 A/B 截图（我们有 `-Dbw.snap` 逐字节仪器）留底 |

> **代价诚实说明**：这是一次**观感级重构**，不是小改。但它是四路里**唯一能同时**改善
> 「画面柔和度 + 水下发色 + 火把光的空间感」的一项，且**确定性天然更强**（固定迭代 vs BFS 队列序）。
> 按"不怕困难"的口径，这是**第二优先**。

---

## 3. Terraria · 分帧切片状态机（★★★ 泛化，几乎零风险）

### 3.1 源码证据

`LightingEngine.EngineState`（12-19 行）+ `ProcessArea`（85-128 行）：
**四个互斥阶段每帧只跑一个**，靠 `_state = (_state+1)%4` 轮转。
另有 `Main.renderCount = (Main.renderCount + 1) % 4`（90 行）——**渲染计数也分帧**。

Terraria 里同一个手法用了**四处**（`TERRARIA_LESSONS.md` 已归纳）：液体、光照、autotile、特殊方块绘制。

### 3.2 我们的两个同型问题

| 位置 | 现状 | 问题 |
|---|---|---|
| `Game.drawMinimap` | 每帧全算 48×48 格地表俯视图 | 纯浪费——地表在玩家不动时**几秒才变一次** |
| `World.computeLight()` | 编辑（挖/放灯）时**全量重建** | 抖动累积：连续挖 10 格 = 重建 10 次（我们已有"编辑时偶发可接受"的注释，但那是妥协） |

### 3.3 落点

- 抽出一个**极小的分帧环**：`enum FrameTask { MINIMAP, LIGHT, ... }` + 每帧推进一格（**与 Terraria 同构**）；
- 小地图改为**脏标记 + 分帧刷新**（玩家跨格才置脏）；
- 光照重建改为**分帧**（编辑后 4 帧内收敛，玩家无感）；
- **门禁**：新增断言「分帧路径与即时路径结果**逐元素等价**」——这正是我们 `STREAMCHUNK` 已有第 ⑤ 段 `scanTruth` 的成熟套路，**直接复用**。
- **指纹**：零。**这是四路里风险最低的一项**，因为 `STREAMCHUNK` 的等价性护栏已经跑通过一次同型改造（`CellSet`）。

---

## 4. 次优先（值得做，但排在 ①②③ 之后）

| # | 学什么 | 来源+证据 | 落点 | 指纹 |
|---|---|---|---|---|
| ④ | **材料交界过渡图**（叠加在材质边缘的过渡纹理） | Noita `materials_gfx/edge_files/` **158 张**（实引 120/63 宿主），`materials.xml` 64 处 `EdgeGraphics` **全部 `overwrite=0`** | 我们 `Autotile` 已在做**同材质边缝暗化**；Noita 是**跨材质过渡**（沙/水交界、雪/石交界有专门图案）。我们的 `assets/content/materials/*.json` 已有 25 材料 → 已在 `TextureAtlas` 加一层 edge 图集（**叠加层，非替换**） | 零（渲染层） | ✅ 已落地 |
| ⑤ | **Noita 式「温度相变字段化」** | `warmth_melts_to_material` / `cold_freezes_to_material`（`NOITA_STUDY` §高价值字段） | 我们 `melt.json`（冰+火→水，prob 0.60）已是雏形。字段化后 = **反应表由数据长出，不用为每种材料写 json** | 零（内容层，已落地 3 条规则） |
| ⑥ | **Noita 式 `lifetime`（瞬态材料自消亡）** | `req_lifetime` / `lifetime` 字段 | 我们的 FIRE 靠 `FireSpreadSystem` 的 50% 概率熄灭（**这曾害我们踩坑**：见 `UNIMPLEMENTED` §一.2，火源活不过 2 tick）；`lifetime` 是**干净的显式语义** | 需重锁（改了 FIRE 行为） |
| ⑦ | **UE `HeightFogCommon.ush` 的解析积分** | 已落（我们雾已用 `(1-2^-A)/A` 归一化形状因子） | 已完成 | — |
| ⑧ | **MC `PostChain` 的「JSON 声明式后处理链」** | `PostChain.java:28-60`（`load()` 读 JSON 定义 pass） | 我们后处理是**硬编码 Java**（`drawBloom` 五步写死）。声明式化收益低（我们只有一条链），**暂不学** | — |

### ④ 的命名族证据（Noita edge_files 82 个基名 / 158 张）

```
edge_normal_{top,bottom,left,right}.png       ← 基础四向
edge_corner{,2,3,4}_{0,1}.png                 ← 四类角（按连通度分级）
edge_crack_{0,1} / edge_c3_{2,4,5}            ← 裂纹 / 三连
edge_soil_lush{,_dark} / edge_soil_dead       ← 按地貌（不是按材料！）
edge_rock{,_hard,_cursed,_purple,_fun}        ← 石族变体
edge_snow{rock,_rock_bright} / edge_ice       ← 雪/冰
edge_{steel*,steelfrost,steelmoss,steelpanel}_{hor,ver}
edge_meat{,_hor,_ver} / edge_skull_{hor,ver}  ← 有机
edge_earth_rainforest{,_dark}_{hor,ver}       ← 生物群系
```

**洞察（2026-09-21 用一手 `materials.xml` 修正）**：早期从文件名推测 Noita 边缘图「按材质对组织」——
**这个判断被一手证据推翻**。`data/materials.xml` 中 64 处 `<Edge><EdgeGraphics>` 的统计是：
**`overwrite="0"` 占 64/64**（过渡图是**叠加 blend**，不是替换 albedo）、
**`require_same_material="0"` 占 57/64**、**`require_same_material_type="1"` 占 58/64**
—— 即边缘图**按「本材质自身」挂载**（`edge_rock` 挂在 `rock_static` 上），只有当邻居是**同类材质**
（如 rock↔rock_low）时才不上；**它是"材质自己的边缘花边"，不是"材质对的专属图案"**。
另：158 张里 **38 张在 materials.xml 里未被引用**（`edge_corner*`/`edge_normal*`/`edge_crack*` 等），
实际在用的是 120 张 / 63 个宿主材质；图案尺寸远小于一个 tile（20×16 / 2×42 细条），
是**沿边界条纹重复**而非整面铺满；且是 **8-bit 覆盖度/亮度图**（meanR 26~45）而非颜色图。

**我们的落地**（步骤 4，路线 A）：
- `core/world/EdgeAtlas.java` —— 纯逻辑层（材质对强度表 + 4-bit 边掩码 + 6 族选择 + 并集式角点合成 + `scale()` 压暗）；
- `render/lwjgl/TextureAtlas.java` —— 程序化烘焙 **6 族 × 3 段 = 18 张**灰度图案（零外部素材），
  落在保留槽 78..95；**不照抄 158 张**（理由见 `EdgeAtlas` 类注释：本作身份是"不加载外部素材"，
  tile 数由**本作实际方块对**推导）；
- **叠加层而非换 tile**（对齐 Noita `overwrite=0` 原意）：world shader 在 albedo 之外**第二次采样**
  过渡图案并乘上（`vEdge.xy`=UV、`vEdge.z`=权重，归一因子 `1/EDGE_CENTER_F`），
  交界压暗交给顶点色 `scale()` —— 实测**整体亮度仅 +0.13%**（初版"换 tile"方案是 **−39%**，已废）；
- 门禁：`EDGE`（纯逻辑 59 断言）+ **`EDGESHADER`**（新，锁 world shader 叠加契约 11 断言，无 GL 可跑）。
- **默认关闭**（`EdgeAtlas.STRENGTH=0`）：关闭时逐字节等价（已证），`-Dbw.edge=<0..1>` 开启验收。

**性价比重估**：视觉收益中等偏上（交界质感确有提升），成本比预估低——因为**不需要新增顶点属性道数之外的
任何图集/FBO**，且图案可完全程序化。是否出厂打开留待视觉验收后单独提交决定。

---

## 5. 明确「不学」的边界（复核既有红线，维持）

| 不学 | 来源 | 理由 |
|---|---|---|
| 破坏性 RNG 地形生成 | MC | 直接砸碎「同种子逐字节复现」的 USP |
| 实体 AI 随机游走 | MC | 同上；我们 NPC 全走 `simStream` |
| 红石全量 | MC | 我们 `WireSystem` 的「切比雪夫半径通电」已够用，全量红石=图灵完备=不可确定性 |
| 写实材质光影 | MC | 我们已**有意偏离**（ACES + 方向光/高光/边缘光），且对齐了 MC 颜色模型 |
| **信任式联机** | Terraria | Terraria 转发无序列号/无校验和；我们 `netHash` + 预测回滚**严格得多**（`TERRARIA_LESSONS` §4 反面教材） |
| **逐像素求解器** | Noita | 我们 16.6 万列 / 287 万格，Noita 的 `cellgrid` 是全画布逐像素 → 规模不同 |
| **`Global` 横切层全套** | Terraria | 我们 99 个 System 是**显式注册序**（`SystemRegistry` 黄金哈希锁死），Terraria 的自动横切适合 MOD 生态，对我们收益低 |

---

## 6. 一个跨来源的架构级观察

**四路源码在「如何避免全网格扫描」上给出了同一个答案，但手法各不相同：**

| 来源 | 手法 | 我们是否已用 |
|---|---|---|
| Terraria 液体 | **活跃集**（`Liquid.cs`：`maxLiquid=25000` + `LiquidBuffer`） | ✅ 已用（`CellSet` 覆盖层，`OVERLAY_LIMIT=1024`） |
| Terraria 光照 | **固定迭代 + 分帧** | ❌ 现用 BFS（本文 §2/§3 建议改） |
| Noita 材料 | **活动区 chunk 网格**（`cellgrid` 的 `w`/`h` 分块） | ✅ 等价（我们的 `Chunk` 16³ 网格） |
| MC 光照 | **分区块 light engine + 队列**（`ThreadedLevelLightEngine`） | ~ 部分（我们 BFS 但不分块） |
| UE 雾 | **解析积分**（不用数值求解） | ✅ 已用 |

**结论**：我们的「增量覆盖层」路线与三家**同源**，是正确的。
唯一**结构性落后**的是**光照**（§2）——这是四路里唯一一处「别人比我们先进」的地方。

---

## 7. 建议执行顺序（含工作量与风险）

**状态（2026-09-21 更新）**：**步骤 1 / 2 / 3 / 4 已全部落地并通过全部门禁** —— 详见下表「状态」列，
落地细节记在 `.workbuddy/memory/2026-09-21.md`。步骤 5–6 未做。

| 步 | 做什么 | 工作量 | 指纹 | 风险 | 验收 | 状态 |
|---|---|---|---|---|---|---|
| **1** | Noita 式时间累积泛光（§1） | 小（2 shader + 1 FBO 组 + ping-pong） | 零 | 低 | `BLOOM` 段 + A/B 截图 | ✅ **已落地**（13 program，+13 断言） |
| **2** | Terraria 式分帧切片泛化（§3） | 小-中（抽环 + 改 2 个调用点） | 零 | 低（`STREAMCHUNK` 同型护栏可直接复用） | 等价性断言 | ✅ **已落地**（`FrameSlicer`，FX 20→27） |
| **3** | Terraria 式两扫光照（§2） | **大**（重写 `computeLight`） | 零（派生） | 中（视觉全变，需 A/B 留底） | `LIGHT` 段 4 断言 + 四指纹 | ✅ **已落地**（`LIGHT` 27→28 断言；A/B 27.6%） |
| 4 | Noita 材料交界过渡图（§4④） | 中（图集 + 逐边选图） | 零 | 低 | 视觉 A/B | ✅ **已落地**（路线 A 叠加层；`EDGE` 55→59 断言 + 新 `EDGESHADER` 11 断言） |
| 5 | Noita 温度相变字段化（§4⑤） | 小（已在数据层） | 零 | 低 | `REACTION` 段 | ⬜ 未做 |
| 6 | Noita `lifetime` 瞬态材料（§4⑥） | 小 | **需重锁** | 中 | `SYSTEMREG` + 基线重锁记账 | ⬜ 未做 |

### 步骤 1–3 落地实录（2026-09-21）

**步骤 1 · Noita 时间累积泛光**
- `core/world/Bloom.java` +8 常量 +3 纯函数（`GLOW_BLUR_RADIUS=5`、`GLOW_NEW_TAP=0.05`（99% 记忆）、
  `GLOW_EDGE_*`（相机移动残影抑制）、`glowAccumulate/glowEdgeWeight`）。
- `Game.java`：ping-pong `glowFBOA/B` + `BLOOM_GLOW_FS`（一轮内同时做"上一帧衰减"与"本帧追赶"：
  `uPass<0.5` 横扫 ×0.1 / else 纵扫 ×1/12.2）；插在 `drawBloom` 第 4b 步。
- **观感变化**：火光**帧间有记忆** → 有余晖，不再是"每帧重来的灯泡"。
- 验收：`BLOOM` 24 断言全 PASS（+13 新）；`ShaderCheck` 12 → **13** programs。

**步骤 2 · Terraria 分帧切片泛化**
- 新建 `core/content/FrameSlicer.java`（纯逻辑、无头可测：`next/isDue/framesUntil/reset`）。
- `Game.java`：`slicer = new FrameSlicer(2)`（0=光照、1=小地图）；`drawMinimap` 改**脏标记 + `isDue(1)`**；
  `World.computeLight` 调用点改 `isDue(0)` 槽位。
- 验收：`FxTest` 20 → **27** properties（SLICE_FAIR/ORDER/ROUNDS/DET/DUE/READONLY/SINGLE）。

**步骤 3 · Terraria 两扫光照（本次最大改动）**
- `MaterialDef` 新增第 7 字段 `lightDecay`（缺省按 `cellType` 推导：气体 0.91 / 液体 0.78 /
  植物 0.82 / 固体·粉末 0.56），含值域校验 `(0,1]`；`MaterialBook` 预展开成 `float[]` 供热路径直索引。
- `World.computeLight()` 从「逐源 BFS」重写为**固定两轮扫描**（`LIGHT_PASSES=2`，每轮 ±x/±y/±z 共 6 趟），
  每格 `reach = 上游 × lightDecay(本格材料)`，低于 `LIGHT_CUTOFF=0.0185` 截断；删除 BFS 队列与 `tryLight`。
  **成本与光源数无关**（旧 BFS 是 源数 × 半径球体），且天然确定性（无队列/无松弛/无顺序依赖）。
- **量化精度修复（关键）**：扫描全程走 `float[] lightWork`，**最后一次性量化**到 `byte lightGrid`。
  若中途就 round 会**逐格复利放大误差**（实测 0.56 链 14→8→4→2→1，光"提前死掉"）；
  且 `LIGHT_R` 由 14 提到 **64**，否则指数长尾在 14 级量化下出现肉眼可见的色阶环。
  同时把 `FIRE_LIGHT_R=9`（"半径"语义，两扫下已无意义）改为 `FIRE_LIGHT_FRACTION=0.62`（"起点亮度"语义）。
- **观感变化（实测量化）**：

  | 指标 | 旧 BFS | 两扫光照 |
  |---|---|---|
  | 衰减律 | 每格 −1/14（线性） | 每格 ×0.91（指数） |
  | 8bit 可见半径 | **14 格硬截断** | **42 格渐变到不可见** |
  | 边界形态 | 硬边圆盘 | 平滑长尾，无硬边 |
  | 成本 | 源数 × 半径球体 | 2 × 全网格（与源数无关） |
  | A/B 像素差 | — | **27.6%（夜）/ 25.1%（正午）** |

- 验收：`LIGHT` **27 → 28 断言全 PASS**（含"迭代次数==2"硬编码、"空气/实体/液体衰减常量"、
  "指数长尾 vs 旧硬截断"）；`ZERO-DRIFT simHash=707ea72a82e0c797` **逐字节不变**；
  62 处 `*_EXIT` 全 0；审计 FAIL 0/WARN 0；A/B 截图留底 `proof/light_ab/`。

> **附带修好的既有缺陷（本轮暴露）**：`-Dbw.snap` 无头截图**曾不可复现**（同版本两跑 93% 像素不同）。
> 根因不是光照，而是 **`glfwSetCursorPosCallback` 在 QA 下仍消费真实 OS 鼠标事件** →
> `yaw/pitch` 随物理鼠标位置漂移。已修（QA 下彻底忽略鼠标视角输入）+ 顺手冻结所有"用真实帧 dt
> 积分"的演出量（`walkAnim`/各 flash/timer）；新增 QA 旋钮 `-Dbw.yaw/-Dbw.pitch/-Dbw.lamp`。
> 现在 3 连拍 md5 逐字节一致，且截图日志会打出 `SNAPDIGEST`（fbHash/camHash）自证。

**纪律**（沿用既有）：每步独立提交；改前先跑四指纹留底；改后跑 `python build_runner.py` 看 62 处 `*_EXIT`；
渲染层改动用 `-Dbw.snap` 做逐字节 A/B；仿真层改动先跑 `python audit_consumers.py`。

> **一句话给决策者**：如果只做一件，**做 §1（Noita 时间累积泛光）**——成本最小、观感提升最立竿见影、零风险。
> 如果要做「对得起这次调研」的事，**做 §2+§3（光照重构）**——这是四路里唯一「我们确实落后」的结构性差距。

---

## 附：本文引用的证据索引

| 文件 | 关键符号/行 |
|---|---|
| `D:/study/Noita_unpacked/data/shaders/post_glow1.frag` | `BLUR_RADIUS`、`decayed *= 0.1` |
| `D:/study/Noita_unpacked/data/shaders/post_glow2.frag` | `mix(decayed,new_tap,0.05)`、`EDGE_AFTER_IMAGE_REDUCTION_*`、`new_tap*4.0`（HDR） |
| `D:/study/Noita_unpacked/data/shaders/post_cell_res_blit.frag` | `pixel_art_filter_uv`（像素艺术友好过滤） |
| `D:/study/Noita_unpacked/data/materials_gfx/edge_files/` | 158 张，82 基名，按材质对 + `_hor/_ver` |
| `_study/terraria/tModLoader_proj/Terraria.Graphics.Light/LightMap.cs` | `BlurLine`(162) `BlurPass`(138) `Blur()`(131) `LightDecayThrough*`(93-96) `0.0185f` 阈值 |
| `.../Terraria.Graphics.Light/LightingEngine.cs` | `EngineState`(12) `ProcessArea`(85) `ProcessBlur`(153) `UpdateLightDecay`(166) `AREA_PADDING=28` |
| `.../Terraria.Graphics.Light/LightMaskMode.cs` | `None/Solid/Water/Honey` |
| `refs/MCP-Reborn-1.20/.../PostChain.java` | `passes`/`customRenderTargets`/`load()` JSON 链 |
| `refs/MCP-Reborn-1.20/.../LightTexture.java` | （已学走，见 ART_BIBLE） |
| `D:/study/开源项目/Engine/Shaders/Private/Bloom/BloomCommon.ush` | `FBloomKernelInfo{CenterEnergy,ScatterDispersionEnergy}` |
| `D:/study/开源项目/Engine/Shaders/Private/HeightFogCommon.ush` | （已学走） |
| `src/core/world/World.java` | `computeLight`(1939) `seedLight`(1952) `tryLight`(1974) |
| `src/render/lwjgl/Game.java` | `drawBloom`(2248) `initBloom`(2188) `drawMinimap` |
| `src/render/lwjgl/TextureAtlas.java` | `GUTTER=8` `CELL_PX=80` `bakeMipChainOffscreen` |
| `src/core/world/Blocks.java` | `Block{autotileGroup, emissive}` `WATER_ALPHA` |
| `src/core/content/MaterialDef.java` | `CELL_TYPES = {solid,liquid,gas,plant,powder}` |
| `assets/content/reactions/*.json` | 7 条（quench/melt/ignite_dry/erosion/frozen/mossspread/clay） |
