# 内容平台规范 · Content Platform Spec

> 一句话：**游戏 = 引擎（代码）+ 内容（JSON）**。技能、魔法、粒子、特效、状态、物品、剧情、敌人
> 全部走 JSON，改文件即改游戏，不需要重编译、不需要改一行代码。

---

## 1. 五条铁律（违反即打回）

| # | 铁律 | 原因 |
|---|---|---|
| 1 | **加载期解析，运行时零解析** | JSON 只在启动/热重载时读一次，转成不可变对象。绝不在 tick 里 `new Gson()` |
| 2 | **内容层零 RNG** | 内容层的随机一律用 `hash01(seed, tick, salt)` 派生，**绝不碰 `simStream`** |
| 3 | **未知即拒绝** | 未知效果类型 / 悬空引用 → 加载期报错并列出，**绝不静默忽略** |
| 4 | **模拟层不配置化** | 93 个 System 保留代码（见 §5），涌现是护城河 |
| 5 | **指纹隔离** | 不碰网格的内容天生不进 `hashState()`；需要碰网格的走 `setBlock()`（自然进指纹） |

铁律 2 和 5 是"内容平台"能和"逐字节可复现"共存的关键——**内容再多，指纹也不动**。

---

## 2. 目录规范

```
assets/content/
  particles/   *.json   粒子发射器（形状 / 生命周期 / 力场 / 色带）
  fx/          *.json   复合特效（多发射器 + 时序 + 音效 + 屏震）
  skills/      *.json   技能与魔法（触发 + 效果链 + 消耗 + 动画 + 特效）
  buffs/       *.json   状态（属性修正 + 周期效果 + 时长）
  items/       *.json   物品（堆叠 / 用途 / 效果）
  quests/      *.json   剧情（状态机：条件 → 动作 → 对话）
  beasts/      *.json   敌人（属性 / 行为参数 / 掉落）
  rules/       *.json   玩法规则（ECA：事件 → 条件 → 动作）
  presets/     *.json   玩法预设（模块开关 + 规则开关 + 系统域开关）
  modules/     *.json   玩法模块元数据（可插拔玩法的身份与参数）
  techs/       *.json   科技链（配方图：前置 → 解锁 → 效果）
```

**ID = 文件名（去掉 `.json`）**，必须全局唯一，且**不要重命名**——所有引用都靠它。
加载顺序按文件名字典序，与操作系统的目录枚举顺序无关（确定性要求）。

---

## 3. Schema

### 3.1 `particles/*.json` — 粒子发射器

```json
{
  "shape": "point | sphere | box | ring",
  "count": 24,
  "life":    [0.4, 1.2],
  "speed":   [1.0, 3.0],
  "gravity": -6.0,
  "drag":    0.4,
  "size":    [0.08, 0.22],
  "sizeEnd": [0.02, 0.05],
  "color0":  [1.0, 0.85, 0.30, 1.0],
  "color1":  [1.0, 0.20, 0.05, 0.0],
  "fade": true,
  "collide": false
}
```

- `[min, max]` 形式的字段 = 区间，具体值由 `hash01` 在区间内插值（确定性）
- `color0 → color1` 是整条生命周期的色带插值（alpha 收到 0 即自然淡出）
- `size` = **初始尺寸**区间；`sizeEnd`（可选）= **终末尺寸**区间，尺寸沿生命进度插值
- `shape` 决定初速分布：`point` 水平四散+上抛 / `sphere` 球面均匀 / `ring` 水平环 / `box` 盒内随机

> 物理模型在 `src/core/content/ParticleSim.java`（**core 层，零 GL 依赖**）——
> 所以粒子能被无头门禁完整断言（`FX` 出口 10 项）。渲染器只负责按索引画出来。

### 3.2 `skills/*.json` — 技能 / 魔法

```json
{
  "name": "Flame Burst",
  "anim": "attack",
  "cost": 22,
  "cooldown": 1.4,
  "targeting": { "shape": "sphere", "radius": 4.0, "maxTargets": 6 },
  "effects": [
    { "type": "PLAY_SFX",   "id": "charge" },
    { "type": "DAMAGE",     "amount": 26, "target": "TARGET" },
    { "type": "SPAWN_FX",   "id": "flame_burst", "at": "TARGET", "delay": 0.12 },
    { "type": "APPLY_BUFF", "id": "burning", "duration": 4.0 }
  ]
}
```

技能 = **触发条件 + 有序效果链**。加一个新魔法 = 写一个 JSON，不改代码。

### 3.3 `fx/*.json` — 复合特效

```json
{
  "emitters": [
    { "particle": "spark", "at": [0, 0, 0], "delay": 0.0, "burst": true },
    { "particle": "smoke", "at": [0, 0.5, 0], "delay": 0.1, "burst": true }
  ],
  "sfx": "explosion",
  "shake": 0.35,
  "duration": 0.8
}
```

### 3.4 `quests/*.json` — 剧情（状态机）

```json
{
  "title": "被烧毁的田地",
  "nodes": [
    { "id": "start",
      "text": "村北的田被野兽糟蹋了。",
      "on": { "type": "kill", "beast": "wolf", "count": 3 },
      "next": "done" },
    { "id": "done",
      "text": "村子欠你一份人情。",
      "effects": [ { "type": "GRANT_ITEM", "id": "seed", "count": 5 } ],
      "next": null }
  ]
}
```

`next: null` = 任务线终止。一条剧情线 = 一条状态链，可无限延伸。

### 3.5 `buffs/*.json` — 状态

```json
{
  "name": "Burning",
  "duration": 4.0,
  "tickEvery": 0.5,
  "modifiers": { "speed": -0.15 },
  "effects": [ { "type": "DAMAGE", "amount": 3, "target": "SELF" } ]
}
```


### 3.2b `skills/*.json` 的树字段（DNF 式）

在技能定义上追加四个字段即可组成技能树：

```json
{
  "name": "Ember Harvest",
  "tier": 2,                      // 层级（UI 分列）
  "requires": ["flame_burst"],    // 前置技能 id（同表内 → 参与环检测）
  "costSouls": 25,                // 学习消耗
  "art": "CLEAVE"                 // 可选：学会后解锁的战技原型
}
```

> **与科技链的分工**：技能树写 `Player.abilities`（**玩家**养成，不进指纹）；
> 科技链写 `World.skills`（**世界**进程，进指纹）。两者互不干扰。

### 3.6 `techs/*.json` — 科技链（配方图）

```json
{
  "name": "Smelting",
  "requires": ["mining", "kiln"],
  "minProsperity": 12,
  "station": "kiln",
  "cost": { "ore": 3, "coal": 2 },
  "unlocks": ["iron_bar", "smelting"],
  "effects": [ { "type": "PLAY_SFX", "id": "levelup" },
               { "type": "DIALOGUE", "id": "Smelting unlocked" } ]
}
```

- `requires` 可以是**另一个 tech 的 id**，也可以是任意 `World.skills` 标记（世界既有机制）——
  于是科技图能与既有系统对接，而不是一座孤岛
- `minProsperity` 是**繁荣门槛**：让科技推进跟世界发展绑定（避免开局全解锁）
- 科技图**成环 = 加载期报错**（环意味着"谁也解锁不了谁都等对方"的死锁）
- 解锁是**幂等**的：标记写进 `World.skills`，重复检查不会重复解锁

> 科技链的节奏：每 20 tick（1 秒）检查一次，不是每 tick 全扫 —— 图再大也不拖累 tick。

---

## 4. Effect 指令集（白名单 · 14 种）

| 指令 | 参数 | 说明 | 进指纹 |
|---|---|---|---|
| `DAMAGE` | `amount, target` | 伤害 | 否 |
| `HEAL` | `amount, target` | 治疗 | 否 |
| `KNOCKBACK` | `power, dir` | 击退 | 否 |
| `APPLY_BUFF` | `id, duration` | 附加状态 | 否 |
| `SPAWN_PARTICLE` | `id, count, at` | 粒子爆发 | 否 |
| `SPAWN_FX` | `id, at` | 复合特效 | 否 |
| `SUMMON` | `id, count` | 召唤敌人 | 否 |
| `TELEPORT` | `dx, dy, dz` | 位移 | 否 |
| `SET_BLOCK` | `block, radius, at` | 改方块 | **是** |
| `PLAY_SFX` | `id` | 音效 | 否 |
| `SCREEN_SHAKE` | `amp` | 屏震 | 否 |
| `GRANT_ITEM` | `id, count` | 给物品 | 否 |
| `DIALOGUE` | `text` | 对白 / 横幅 | 否 |
| `GRANT_SKILL` | `id` | 写入 `World.skills` 标记（科技解锁 / 用中学成长共用出口） | **是** |

13 种之外的任何 `type` → **加载期报错**（铁律 3）。
`SET_BLOCK` 是唯一进指纹的指令——因为它真的改世界，这符合纪律而非破坏。

---

## 5. 为什么 93 个 System 不配置化（诚实边界）

先泼冷水：**不是所有系统都该变成 JSON**。

| 类别 | 例子 | 处理 |
|---|---|---|
| **涌现系统** | 生态、气候、文明、灾害、火烧草、水流 | **保留代码** |
| **内容系统** | 技能、粒子、特效、状态、物品、敌人、剧情 | **配置化** |

理由：涌现系统**改网格**（进 `hashState` 指纹）、**互相耦合**（火 ↔ 草 ↔ 雨 ↔ 旱）、
**依赖顺序**。把它们拖进 JSON 会有三个后果：确定性调试变成配置地狱、
每加一个字段要写三份校验、性能从"字段访问"退化成"map 查表"。
它们是这个项目的护城河，应该更硬而不是更软。

**配置化的是"内容"，不是"法则"。** 加魔法、加粒子、加剧情、加敌人 = JSON；
改生态耦合、改气候模型、改文明演化 = 代码。

---

## 6. 加载与执行流程

```
启动
 └─ ContentRegistry.load(assets/content/)
      ├─ 扫描 7 类目录（文件名字典序 → 确定性顺序）
      ├─ Gson 解析每个 *.json
      ├─ 校验：ID 唯一 / 效果类型白名单 / 引用完整性 / 循环引用
      ├─ 注册：Map<type, Map<id, Def>>（不可变）
      └─ 产出：加载报告（错误 + 警告 + 孤儿统计）

运行时（每 tick）
 └─ ContentSystem.update()  ← 已挂进 World.systems
      └─ EffectQueue.tick(world)
           ├─ 取出 dueTick <= now 的待执行项
           ├─ 按 (dueTick, seq) 稳定排序 → 确定性
           ├─ 执行：模拟类效果直接改 World；表现类效果经 EffectSink 转给渲染层
           └─ chance 判定用 hash01（不耗 RNG 流）
```

`EffectSink` 是内核层与渲染层的唯一接口——core 因此**零渲染依赖**：

```java
public interface EffectSink {
    void particle(String id, float x, float y, float z);
    void fx(String id, float x, float y, float z);
    void sfx(String id);
    void shake(float amp);
    void banner(String text);
}
```

---

## 7. 门禁（CONTENT · 第 27 个出口）

| 断言 | 内容 |
|---|---|
| `LOAD` | 内置内容加载成功，ID 集合非空 |
| `DET` | 两次加载得到**逐字节一致**的注册表快照 |
| `WHITELIST` | 未知效果类型被拒绝 |
| `REF` | 悬空引用（技能引用不存在的粒子）被抓出 |
| `CYCLE` | 循环引用（fx A → fx B → fx A）被检测且不栈溢出 |
| `QUEUE` | 同输入 → 同执行序列（确定性） |
| `DELAY` | `delay=1.0s` 的效果在第 20 tick（20 tick/s）执行 |
| `HASH` | `hash01` 确定性 + 落在 [0,1) |
| `ORDER` | 同 tick 的多个效果按注入顺序执行（稳定） |

此外 **`FX` 门禁（第 30 出口，10 项）** 覆盖粒子：SPAWN / DET / GRAVITY / DRAG / LIFE / RAMP / CAP / SHAPE / CLEAR / HASH。

---

## 8. 分期路线

| 期 | 交付 | 你能立刻看到 |
|---|---|---|
| **P0** ✅ | 内核：Registry / Effect / EffectQueue / EffectSink / ContentSystem / 门禁 / 示例 JSON | `assets/content/` 目录可读可改 |
| **P1** ✅ | 粒子与特效配置化（替换 Game.java 里硬编码的 `Particle`）+ 渲染接入 | 改 JSON 出新粒子，立刻可见 |
| **P2** | 技能 / 魔法（SkillDef 接 `Weapons.Art`、`Soul-forge`）+ 效果链执行 | 万物技能，写 JSON 就能放 |
| **P3** | 剧情系统（QuestGraph 状态机 + 条件触发器 + 对话树，接 `Dialogue`） | 任务线 |
| **P4** | MOD 支持（zip 加载 + 覆盖优先级 + 冲突报告 + 热重载） | 真·mod 平台 |

---

## 9. 加一个新技能（5 步，全程不碰代码）

1. 写 `assets/content/particles/flame.json`（新粒子）
2. 写 `assets/content/fx/flame_burst.json`（复合特效，引用上面那个粒子）
3. 写 `assets/content/buffs/burning.json`（灼烧状态）
4. 写 `assets/content/skills/flame_burst.json`（技能，把上面三个串起来）
5. 重启游戏 → 新魔法已经存在

**加内容的成本 = 写 4 个 JSON 文件。这就是"源源不断加入"的意思。**

---

## 10. MOD 支持（内容可被替换与扩展）

### 10.1 目录约定

```
mods/
  example_mod/
    content/                 ← mod 的内容根（下面就是 particles/ skills/ techs/ ...）
      skills/ember_harvest.json
      particles/ember.json
  another_mod.zip            ← 也可以打成压缩包（内含 content/...）
```

### 10.2 合并规则（覆盖优先级）

| 来源 | priority | 说明 |
|---|---|---|
| `base`（官方） | 0 | `assets/content/` |
| mod（目录或 zip） | 10 | `mods/*/content/` 或 `mods/*.zip` |

- 按 priority **从低到高**放置 → **后放者覆盖同名 ID**（即高优先胜）
- **同 priority 按来源名字典序**决定次序 —— 与文件系统枚举顺序无关，保证确定性
- 于是「改官方的一个技能」只需要在 mod 里放一个**同名文件**

### 10.3 覆盖报告

启动时 `game_diag.log` 会列出：

```
[MOD] sources=2 (base + 1 mod)
[MOD] override skills/ember_harvest.json: base -> example_mod
```

最后一行就是「这个 mod 改了官方哪些内容」的清单 —— 排查 mod 冲突时直接看它。

### 10.4 为什么新机制仍然要写代码

**内容可以被替换，机制不能。** 要加一个全新机制（比如"捕捉判定""产线结算"），
仍然需要写 `GameplayModule`（Java）。这是三层分工的最后一块：

```
数据层   assets/content/**    ← mod 作者改这里（含覆盖官方内容）
模块层   GameplayModule       ← mod 作者需要新机制时写这里（一次编码）
引擎层   core/ + render/      ← 我们维护
```

> **"万物可声明；机制需编码一次，之后无限复用。"**

### 10.5 门禁（MOD · 第 33 出口）

| 断言 | 内容 |
|---|---|
| `DISCOVER` | 扫描 `mods/` 能发现目录 mod 与 zip mod |
| `BASE_INTACT` | 不装 mod 时官方内容不被污染（官方值原样） |
| `LOADALL` | base + mod 合并后总量与新增内容正确 |
| `OVERRIDE` | mod 真的改掉了官方内容（25 → 10） |
| `REPORT` | 覆盖被记录成清单 |
| `PRIORITY` | 高优先级来源胜 |
| `DET` | 同输入同结果，与来源顺序无关 |
| `ZIP` | 压缩包形式可用 |
| `ISOLATION` | 坏 mod 的悬空引用会被报出（不静默） |
| `CONFLICT` | 同优先级争同一 ID → 后者胜且被记录 |

