# 平台蓝图 · Platform Blueprint

> 目标：把《会呼吸的世界》做成一个**平台** —— 内容能加、玩法能加、**系统能加**。
> 不是"做一个游戏"，是"做一个能长出无数游戏的容器"。

---

## 1. 平台 = 四层能力

| 层 | 能力 | 加一样东西的成本 | 现状 |
|---|---|---|---|
| **内容层** | 技能 / 粒子 / 特效 / 状态 / 物品 / 敌人 / 剧情 | **写 JSON** | ✅ P0 完成 |
| **玩法层** | 规则（ECA）/ 玩法模块 / 预设组合 | **写 JSON** | ✅ 本轮完成 |
| **系统层** | 涌现系统（生态/气候/文明/新机制） | **1 个类 + 1 行注册** | ✅ 本轮完成 |
| **引擎层** | 渲染 / 物理 / 存档 / 事件总线 | 改基础设施 | 🔶 进行中（粒子管线已配置化） |

四层都通了，才叫平台。目前**内容与玩法已通，系统层是下一个战场**。

---

## 2. 三种「加东西」的操作手册

### 2.1 加内容 —— 零代码，零编译

```
assets/content/particles/my_spark.json
assets/content/fx/my_burst.json
assets/content/skills/my_spell.json
→ 重启游戏。新魔法存在。
```

### 2.2 加玩法 —— 零代码，如果是已有机制的变体

```
assets/content/rules/my_rule.json     ← ECA：事件 → 条件 → 动作
assets/content/presets/my_mode.json   ← 组合：启用哪些模块与规则
→ 换一个预设 = 换一套玩法
```

**边界**：新**机制**第一次出现要写代码（捕捉判定、产线结算）；写成 `GameplayModule` 之后，
它的所有**变体**（不同的球、不同的产线、不同的触发条件）都是 JSON。**一次编码，无限配置。**

### 2.3 加系统 —— 写一个类，然后注册

```java
public final class MyNewSystem implements System {
    @Override public String name() { return "myNew"; }        // 决定 simStream 子流名
    @Override public void update(World w, SeededRNG rng) { ... } // 只经 rng 取随机
}
```

接口已经统一（`World.tick()` 就是 `for (System s : systems) s.update(this, simStream(s.name()))`），
**问题不在写类，而在注册** —— 见 §4。

---

## 3. 引擎层：站在 MC 肩上（学架构，不搬代码）

**诚实说明**：不能直接搬 Minecraft 源码 —— 版权、语言（Java vs 自有架构）、以及我们的
确定性内核跟 MC 的非确定性设计根本冲突。**能学的是架构模式，不是代码。**

| 已学到的 MC 架构 | 我们对应实现 |
|---|---|
| 区块化世界 + 脏块重建 | `Chunk` / `dirtyChunks` / 预算 4 块/帧 |
| 纹理图集 + mipmap + Dilation | `TextureAtlas`（CPU 烘焙 1152px，含法线图集）|
| 方块光照传播（BFS 半径 14） | `World.computeLight` + LAMP 块光 |
| 生物群系查表 | `World.biomeAt`（温/湿噪声）|
| 遮挡剔除 / 视锥裁剪 | `terrainOccluded` / F3 观测 |
| 存档（区块快照） | `World.save/load` + 门禁 18 黄金断言 |
| 注册表模式（blocks/items/entities） | `ContentRegistry`（内容）+ 本蓝图的系统层目标 |

**待学清单**：实体 tick 分区与预算、区块异步加载、光照增量更新、数据驱动注册表（我们只做了一半）。

**不学**：MC 的非确定性随机地形（会毁掉铁律）、写死的内容耦合。

---

## 4. 系统层注册表化（✅ 2026-09-13 完成）

### 问题（实测，已解决）

`Simulation.java` 里：

```
94 行 import  +  92 个 world.addSystem(new XxxSystem())  +  约 140 行注册代码
```

后果：
- 新增一个系统要改 **3 处**（import / 注册 / 可能调整顺序）
- **顺序敏感但顺序不可见** —— 注释写着"固定顺序，决定确定性演化顺序"，却埋在一个巨型构造函数里
- 无法按玩法模块开关系统（预设想关掉"虫群"就得改代码）

### 方案：`SystemRegistry` + 阶段（phase）

```
engine  →  world  →  matter  →  life  →  society  →  entity  →  meta
（基础）   （地形）   （物相）    （生态）   （社会文明）  （实体）   （叙事/审判）
```

- 每个系统声明自己的 **phase**（`System.phase()` 默认值 + 覆盖）
- 注册表按 `(phase, 注册序)` 稳定排序 → **顺序显式、可审计、可断言**
- 预设可**开关**某个系统（"关掉虫群"= 配置，不是改代码）
- 门禁可断言「排序稳定」「开关后其余系统顺序不变」

**收益**：新增系统 = 1 个类 + 1 行注册；玩法组合从"内容级"升级到"**系统级**"。

### 落地结果

- `Phase`（8 个职责域）+ `SystemRegistry`（顺序单一真相 / 黄金序列 / 按域开关）
- `World.addSystem(s, phase)` —— 标签与注册一体；`tick` 内多一次空集短路判定（零开销）
- `Simulation` 的 92 行手工注册 → **10 个域方法**（同名域语义），顺序逐行保持
- 门禁 `SYSTEMREG`（第 29 出口，10 项）：**黄金序列哈希**钉死顺序 + 域分布 + 开关语义
- 域分布实测：signature 1 / terrain 14 / vegetation 33 / weather 13 / geology 9 / society 15 / entity 4 / meta 3
- **四道仿真指纹逐字节不变**（43723456791b5364 / 34c8bb722c5a1a6f / 8560321416dc6ac5 / 3a08e840e0d16524）

> 关键纪律：**phase 只是标签，绝不参与排序**。顺序即指纹，重排就是灾难。
> 黄金序列哈希（`-6666983850743452865`）会在任何人调换两行时立刻报 FAIL。

---

## 5. 铁律（任何一层都不许破）

| # | 铁律 | 对平台化的含义 |
|---|---|---|
| 1 | 同种子同输入 → 逐字节复现 | 新增内容/玩法/系统都不能改既有指纹 |
| 2 | 内容/玩法层零 RNG | 概率走 `hash01`，不碰 `simStream` |
| 3 | 未知即拒绝 | 坏 JSON / 悬空引用 / 未知指令 → 构建期报错 |
| 4 | 模拟层不配置化（法则硬，内容软） | 涌现系统写代码；内容与玩法写 JSON |
| 5 | 全部可门禁 | 每加一层能力，就多一组性质断言 |

---

## 6. 当前门禁面板（29 出口）

`SYSTEMREG`（系统层 10 项）· `GAMEPLAY`（玩法层 10 项）· `CONTENT`（内容层 10 项）·
加上确定性/物理/流式/存档/菜单/音频/选址/潜行/庇护所/动作等 26 项。

**平台每长一层，门禁就多一出口。** 这是"能无限加"而不塌的唯一保障。

---

## 7. 内容链路已贯通（2026-09-13）

从「一个 JSON」到「屏幕上的东西」现在是一条完整的链：

```
assets/content/particles/spark.json
        │ 加载期
        ▼
ContentRegistry（校验 + 注册）
        │
        ▼
Rule / Effect（玩法与效果的统一原子）
        │ 运行期（每 tick）
        ▼
EffectQueue（确定性调度）→ Game implements EffectSink
        │
        ▼
ParticleSim（core 层纯模型，可门禁）→ 渲染成方块
        │ 每帧
        ▼
屏幕
```

**你现在改 `assets/content/particles/spark.json` 里的 `gravity`，游戏里火花的下坠就变了。**
不用重编译、不用改代码、不用重启编译器 —— 只要改文件重启游戏。

已接通的：
- 预设 `disablePhases` → 真的关掉一整套系统域（`peaceful_valley` 关掉 GEOLOGY 9 个系统）
- 规则引擎订阅 `World.events` → 命中规则 → 效果入队 → 粒子/横幅真的出现
- 4 个旧硬编码发射点（翻滚扬尘 / 魂光 / 扬尘 / 火花）全部换成配置驱动的 `particles.spawn(def,…)`

尚待接（P2）：`sfx` 音效名映射、`shake` 屏震合流、`simulate` 模拟类指令（伤害/给物品/传送）。
