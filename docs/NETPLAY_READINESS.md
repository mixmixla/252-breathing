# 联机就绪度审计 & 架构方案（Netplay Readiness）

> 状态锚点（**2026-09-14 侦察**）：**尚无任何联机代码**（`src/` 内 `Socket|Netty|Address|network` 零命中）。
> 本文是「做联机服务」的**决策前置文档**：先给结论、再泼冷水、再给路线。
> 基线：36 道门禁 / 40 处 `*_EXIT` 全 0；四基线指纹 DET `43723456791b5364` / ZD `34c8bb722c5a1a6f` / PHYSICS PASS / STREAMING `87d5bf1cecb4c628`。

---

## 0. 结论先行

**这个项目天生适合确定性锁步（deterministic lockstep）——比绝大多数项目都适合。** 三条硬证据是现成的，不需要我造：

| # | 证据 | 代码位置 | 为什么关键 |
|---|---|---|---|
| 1 | **唯一输入边界已存在** | `Player.setIntent(Intent)` / `pollIntent()`（Player.java:114-119） | 联机 = 同步指令流，不传世界。`Intent` = `{Type(MOVE/REPEL/ATTACK/BUILD/IDLE), dx, dz, blockIdx}`，**6 字节可编码** |
| 2 | **RNG 不累积漂移** | `World.simStream(name)` = `rng.deriveStream(name + ":" + tick)`（World.java:649-651） | 每系统随机数是 `(seed, name, tick)` 的**纯函数** → 误差不会随时间累积，锁步极稳 |
| 3 | **地形生成可移植** | `cellHash` / `cellHash3`（纯 `long` 乘/异或/移位）+ `valueNoise`（`Math.floor` + IEEE-754 基本运算 + smoothstep） | **全平台逐位一致** → 世界无需传输，两端各自生成 |

→ 所以：**同一 seed + 同一 Intent 序列 ⇒ 全平台逐字节同一世界**。这是 netcode 里最贵的那部分，你们已经付过了。

---

## 1. 先泼三盆冷水（不解决，联机必炸）

### 冷水 1：`Math.sin/cos/...` **不是**跨平台逐位一致的 ⚠️⚠️⚠️

Java 规范原文：`Math` 的初等函数**不保证** bit-for-bit 相同结果（只有 `StrictMath` 保证）。
你们在同一台机器上跑，所以门禁全绿——**一旦换 JVM 厂商 / OS / CPU，`sin` 可能差 1–2 ulp**，坐标一偏，`mat` 就分叉。

`src/core` 审计结果：

| 位置 | 调用 | 是否影响 `hashState` | 风险 |
|---|---|---|---|
| `ShrineSystem:38-39` | `cos/sin` + `floor` → 祭坛坐标 | **写 `mat`** | 🔴 高 |
| `TrialSystem:102-110` | `cos/sin` + `floor` → 试炼点/补给箱坐标 | **写 `mat`** | 🔴 高 |
| `CivilizationSystem:409` | `sin` 伪噪声 hash | civ 标量（派生层） | 🟡 中 |
| `BeastSystem:62-82` | `cos/sin/hypot` → 敌兵位置 | 位置不入指纹，但**可能改变 RNG 抽数** → 间接污染 `rng.state` | 🟡 中 |
| `NpcSystem:75-102` | `cos/sin` → NPC 位置 | 同上 | 🟡 中 |
| `IndividualSystem:658` | `sqrt/log/cos`（Box–Muller） | 突变/适应度，可能间接写 `mat` | 🟡 中 |
| `MatterSystem:83,109` | `sin` | 派生层，且 `MATERIAL_WORKS` 默认关 | 🟢 低 |
| `PolitySystem:262` / `SocialSystem:174` / `WindSystem` / `ClimateSystem` | `sin` | 派生层 | 🟢 低 |
| `DayCycle:84,108,230` / `Player.hypot` | `cos/tan/hypot` | 渲染层 / 玩家位置（不入指纹） | 🟢 低 |

> **状态（2026-09-14 更新）**：上表**全部行**已在 **N0 落地修复**——`src/core` 内 56 处超越函数统一走 `StrictMath`（16 文件），并由**第 37 道门禁 `PORTABLEMATH`** 防复发；四基线指纹**逐字节未变**（本机 JVM 上 `Math` 与 `StrictMath` 对这些输入结果一致 → 行为保持）。

**修法（已执行）**：把**会写 `mat`/`mass` 的路径**上的 `Math.*` 全部换成 `StrictMath.*`（`StrictMath` 全平台逐位一致；多数 JVM 上 `Math` 本就委派给它，性能差异可忽略）。
**护栏**：新增门禁 `PORTABLEMATH`——扫 `src/core/**` 关键路径，命中 `Math.(sin|cos|tan|atan2|exp|log|pow|hypot|asin|acos|cbrt|sinh|cosh|tanh)` 白名单外即 FAIL。

> 注意 `Math.sqrt` 是**精确舍入**的（可移植），`Math.floor/abs/min/max` 也是。**不用动**。

### 冷水 2：JDK 版本漂移

当前 `javac` 实为 **corretto-1.8.0_502**（class 版本 52）。Java 8 **默认非 `strictfp`**。
x86-64 上实际走 SSE2、结果通常一致，但**没有规范保证**。

**修法**：① 给仿真核心加 `strictfp`；或 ② 统一要求 **JDK 17+**（JEP 306 起 FP 默认严格，`strictfp` 冗余）。同时**锁定客户端 JRE 版本**并在握手时交换版本号，不一致直接拒绝。

> **✅ 已实测解决（2026-09-14，见 §5.1）**：Corretto **8** 与 OpenJDK **26** 两套 JDK 各跑一遍全量 41 处门禁，**所有指纹逐字节相同** → 该风险在本项目上**实测不存在**（Java 17+ 起 FP 默认严格，且与 Java 8 结果一致）。建议统一到 Java 25 LTS。

### 冷水 3：设计层——锁步 = 输入延迟，对 ARPG 手感不友好

锁步要求所有客户端在同一 tick 上有一致输入。只传本机输入 ⇒ 网络 RTT 直接变成**输入延迟**（≈RTT/2）。

- 当前是 **20 tick/s**（50ms/帧）。同城 RTT 20ms → 1 帧延迟（可接受）；跨省 60ms → 3 帧，**挥砍 / 招架 / 完美闪避会明显发飘**。
- 缓解三条：① **客户端预测 + 回滚**（本机玩家立即响应，权威到达后校正）；② 提高 tick 率到 30–60（代价：CPU 与确定性验证成本上升）；③ **接受"合作探索向"定位**——你们本来就是涌现/世界模拟，不是竞技 PvP。
- **建议**：第一版做 **2–4 人合作 + 主机权威锁步 + 本地预测**，**不做 PvP**。

---

## 2. 路线对比（三选一）

| | **A. 主机权威锁步**（推荐） | **B. 权威服务器 + 状态流** | **C. 仅会话/存档服务**（最小） |
|---|---|---|---|
| 形态 | 每端跑完整仿真，只同步 `Intent`；主机定 tick 节奏 | 服务端跑仿真，客户端只渲染 + 插值 | 只做大厅/房间/云存档/异步共玩 |
| 带宽 | **极低**（≈百字节/秒/人） | 高（要传实体/方块增量） | 极低 |
| 一致性 | 完全一致（可逐字节校验） | 客户端不必确定性 | 无关 |
| 工作量 | **中** | 大（渲染层要改成"远端世界"） | 小 |
| 适合 | **2–4 人合作** | 8+ 人 / 强反作弊 | 先验证需求 |

**推荐 A。** 因为 `Intent` 边界、`simStream` 无累积漂移、地形可移植这三条——**路线 A 的成本在这个项目里已经预付了约 80%**。路线 B 要把 `Game` 的渲染从"本地 `World`"改成"远端镜像"，那是伤筋动骨的重构。

---

## 3. 推荐架构（路线 A）

```
        ┌──────────── 主机 Host（权威时钟，20Hz 固定步长）────────────┐
        │  World.tick()                                              │
        │   ├ 收集本机 Intent                                        │
        │   ├ 收集各客户端 Intent（到达即入队，按 tick 定序）          │
        │   ├ 打包 InputFrame{tick, [playerId → Intent]}             │
        │   ├ 广播 InputFrame                                        │
        │   └ 每 60 tick 广播 netHash()（desync 检测）               │
        └───────────────────────┬───────────────────────────────────┘
                                │ UDP（可丢包/乱序）
                 ┌──────────────┴──────────────┐
                 ▼                             ▼
          客户端 A（本地预测自己）        客户端 B（本地预测自己）
          收到权威 tick → 回滚重演        每 tick 消费同一 InputFrame
```

四条契约（必须显式设计，不然必踩）：

1. **只传 `Intent`**：世界由 `seed` 两端各自生成。
2. **冗余发送**：客户端把自己的 `Intent` 连发 K 帧（防丢包）；主机按「首个到达者」定序，重复帧丢弃。
3. **desync 检测与重同步**：定期比 `netHash()`；不一致 → 拉主机的存档快照重载（`World.save/load` **已有**，含 `chunkEdits` 流式持久化）。
4. **世界平移必须同步**：M3 留下的新契约是「`isShifting()` 期间渲染层暂停 tick」。**联机时若一端在 shift、另一端没 shift，就会立刻分叉** → 平移窗口必须由主机统一下发。

---

## 4. 三个必须先补的**缺口**（侦察发现，非猜测）

### 缺口 1：`hashState()` 太窄，不够做 desync 检测

当前 `hashState()`（World.java:710-727）只盖 `mat / mass / prosperity / tick / rng.state / skills / villageMemory / builtMass`——
这是**故意做窄**以保零漂移。但联机要抓的漂移可能在 **`npcs[]` / `beasts[]` / `Player` / `civilization`** 里，这些**全在指纹之外，分叉了也检测不到**。

**修法**：新增一个**独立**的 `netHash()`（超集，含实体层），**绝不改动 `hashState()`** → 现有 36 道门禁零回归。

### 缺口 2：`Intent` 缺 `yaw`，朝向现在活在渲染层

`Intent` 只有 `{type, dx, dz, blockIdx}`，**没有朝向**。但攻击方向、别人看到的"你在看哪"都依赖 yaw。
当前 yaw 大概在 `Game`/相机里 → **联机必须把朝向推进 sim**：`Player.yaw` + `Intent.yaw`（建议 1 字节，360/256 分辨率）。

### 缺口 3：暂停语义要与锁步对齐

`World.tick()` 开头 `if (paused) return;`（丢 tick 语义）。联机时"暂停"必须**全体一致**，否则各端 tick 数不同 → 分叉。→ 暂停改为**主机专属**动作，或干脆联机模式禁用暂停。

---

## 5. 落地路线图（切 5 步，每步可独立验证）

| 步 | 内容 | 产出 | 新增门禁 |
|---|---|---|---|
| **N0** ✅ | **可移植性硬化**（2026-09-14 **已落地**）：`src/core` 内 56 处非 `StrictMath` 超越函数 → `StrictMath`（16 文件）；新增第 37 道门禁 `PORTABLEMATH` | 代码 | `PORTABLEMATH` **PASS**；全 37 道不回归、四基线指纹**逐字节未变** |
| **N0b** ✅ | **跨 JDK 实证 + 工具链统一**（2026-09-14 **已验证**）：本机 `~/.jdks` 内有 Corretto 8 与 **OpenJDK 26**，两套 JDK **各跑一遍全量 41 处门禁 → 所有指纹逐字节相同**（证据见 §5.1） | 构建约定 | 建议客户端/服务器统一 **Java 25 LTS**；握手校验 JRE 版本 |
| **N1** ✅ | **输入编码**（2026-09-14 **已落地**）：`core/net/IntentCodec.java`（`Intent` ⇄ **6 字节**，显式大端）+ `core/net/InputFrame.java`（一 tick 全玩家输入，`playerIds` **严格升序** = 确定应用序）；`Player.Intent` 新增 `yaw` **线路字段**（量化 uint16，仿真不读 → 零漂移）；`build_runner` 新增 `src/core/net` 编译段 | `core/net/*` | `NETCODEC` **PASS**（14 项）；全 38 道不回归、四基线指纹**逐字节未变** |
| **N2** | **回放/录像**：录 `(seed, Intent 流)`，回放必须逐字节复现 `netHash` | `core/net/Replay.java` | `REPLAY`：录→放 hash 全等 |
| **N3** | **主机权威锁步**：UDP 收发 + tick 定序 + 输入冗余 + desync 检测 + 平移同步 | `core/net/LockstepHost.java` / `LockstepClient.java` | `NETLOOP`：两实例本地回环 500 tick 逐字节一致 |
| **N4** ✅ | **预测回滚**（GGPO 式客户端预测 + 权威帧到达回滚重演）：基线缓存（`ensureBaseline`）+ `RollbackEngine`（应用前快照环形缓冲）+ `PredictiveSession`（本地即时 / 远端延迟时重复上次确认意图 / 权威不符原地回滚重演）；手感零延迟且终态 == 纯锁步逐字节 | `core/net/RollbackEngine.java` + `core/net/PredictiveSession.java` | `PREDROLLBACK` **PASS**（5 项）；全 46 道不回归、四基线指纹**逐字节未变**（N5 已接渲染主循环 + 大厅/房间 UI 按需） |
| **N5** ✅ | **联机会话接入渲染主循环**：抽象 `core/net/TickBody` 整 tick 推进体（含 `rules/techs/effects` 内容层，与单机演化逐字节等价）；`LockstepSession`/`PredictiveSession` 新增带 `TickBody` 构造（默认 `world::tick` 向后兼容 `NetMain`）；`render/lwjgl/Game` 解析 `--host`/`--join` 由会话驱动完整整 tick、本地移动按 tick 喂 `Intent.move`，单机分支逐字不变 | `core/net/TickBody.java` + `core/sim/NetIntegTest.java` + `Game.java` 接线 | `NETINTEG` **PASS**（3 项）；全 46 道不回归、四基线指纹**逐字节未变** |

**N2 是关键枢纽**：它把"联机"还原成"**可复现回放**"——一个你们已经具备 ~90% 条件的问题，而且 **N2 完全不需要网络**，立刻就能回答「跨进程的确定性到底成不成立」。**强烈建议 N0→N1→N2 先做，再碰 socket。**

### 5.1 N0b 实证：跨 JVM 大版本逐字节一致（2026-09-14）

本机 `~/.jdks`（IntelliJ 管理的 JDK 仓库）里有两个 JDK：

| JDK | 版本 |
|---|---|
| `corretto-1.8.0_502` | Amazon Corretto **8**（当前 `JAVA_HOME` 与 PATH 首选，项目规范工具链） |
| `openjdk-26.0.1` | OpenJDK **26.0.1**（2026-04-21） |

分别用两套 JDK 编译并运行**全量 41 处门禁**（`JAVA`/`JAVAC` 均确认取自各自 JDK）：

| 指纹 | Corretto 8 | OpenJDK 26 | |
|---|---|---|---|
| DET | `43723456791b5364` | `43723456791b5364` | ✅ |
| ZERO-DRIFT | `34c8bb722c5a1a6f` | `34c8bb722c5a1a6f` | ✅ |
| STREAMING | `87d5bf1cecb4c628` | `87d5bf1cecb4c628` | ✅ |
| NPC / SOCIAL / STORYTELLER | `8560321416dc6ac5` / `ed4b43107588cb41` / `1ea5d40a3c17c732` | 同 | ✅ |
| CIV·IND·POLITY·MATTER / TRIAL / MEGA | `1610d5235a5300db` / `202b17462c8791d4` / `3a08e840e0d16524` | 同 | ✅ |
| 退出码 | 41 处全 0 | 41 处全 0 | ✅ |

**Java 8 → Java 26（相隔 18 个大版本）逐字节一致。** 意义：

1. 这是**联机方案 A 的实证地基**——不是"理论上可移植"，而是**已经跨两代 JVM 实测成立**。两端只要同 seed + 同 Intent，世界就是同一个。
2. 它同时验证了 N0 的 StrictMath 迁移**没有改变任何行为**（否则这里必然分叉）。
3. `strictfp` 的担忧（冷水 2）**自动消失**：Java 17+ 起浮点默认严格（JEP 306），且实测与 Java 8 结果一致。

**建议**：客户端与服务器统一到 **Java 25 LTS**（26 非 LTS）；握手时交换 JRE 版本号，不一致直接拒绝。本项目仍以 Corretto 8 为规范工具链（不改基线），但 N3 起应把"目标 JRE"写进构建约定。

### 5.2 「一开始就上回滚」对后续步骤的硬性要求

回滚（client prediction + rollback）的本质是**反复回到过去**，比"回放"要求高得多：

| 要求 | 为什么 | 现状 |
|---|---|---|
| **内存快照 / 恢复 ≤ 1ms** | 每个迟到的权威包都可能要求回滚 2–3 帧，每帧都要先存后取 | ❌ `World.save/load` 是**文件 IO**（`DataOutput`），不可用 |
| 快照须**覆盖全部仿真状态** | 漏一项 → 回滚后分叉，且**只在"曾经回滚过"时才复现**（最难查的一类 bug） | ⚠️ `hashState()` 是**故意做窄**的，**不能**当快照定义用 |
| `tick` 必须**可重入指定 tick** | 重演要按原 tick 号跑 RNG 派生 | ✅ 已具备 —— `simStream(name) = deriveStream(name + ":" + tick)` 是纯函数，这是 RNG 派生方式白送的礼物 |
| 输入可按 tick 索引 | 重演时取"该 tick 的正确输入" | ✅ N1 的 `InputFrame{tick, ...}` 已是这个形状 |
| 世界平移也要能回滚 | M3 的 `stepShift` 会换窗口原点 | ⚠️ 快照必须覆盖 `windowOrigin` |

**结论：N2 的交付物应重新定义为「`World` 内存快照 + 按 tick 重演」**，而不是"录像文件"——录像只是它顺带的一个场景（把 `InputFrame` 流落盘）。

**N2 拆解（修订）**：

| 子步 | 内容 | 门禁断言 |
|---|---|---|
| **N2a 快照** | `World` 内存快照 / 恢复（复用现有 `save/load` 序列化逻辑，但走 `ByteArrayOutputStream`；补上窗口原点等缺口） | 快照→恢复后 `hashState` 一致 **且** 再跑 N tick 的 hash 也一致（**双一致**） |
| **N2b 重演** | `Replay(seed, 快照, InputFrame 流)` 逐字节复现 | 录→放 hash 全等 |
| **N2c 回滚** | `rollbackTo(tick)` = 恢复快照 + 重演 | **黄金判据**：「跑 100 tick → 回滚到 90 → 用修正输入跑到 100」与「一开始就用修正输入跑到 100」结果**完全一致** |

> N2c 的黄金判据是整套回滚机制唯一的正确性证明 —— 它同时覆盖"快照不完整"「重演不等价」「输入定序错误」三类故障。

### 5.3 实测修正：N2 的「复用 `save/load`」方案 **已被证伪**（2026-09-14）

按「**先探针量化，再动手**」实测（真实 Game 尺寸 160×112×160 + `Simulation`，即 92 个系统全开）：

| 指标 | 实测值 | 结论 |
|---|---|---|
| **快照尺寸** | **≈ 22.3 MB**（tick 60–120，且**无玩家编辑**） | ❌ 每帧 22 MB @20Hz = **440 MB/s** → 回滚绝不可用 |
| 为何这么大 | `save()` 先调 `snapshotInWindowEdits()`，把**所有被系统写过的块**全量落盘（≈229 KB/块） | 系统会大量写块（巨构材化 / 植被 / 神殿…），真实世界里 `chunkEdits` 极大 |
| 读档后的实体层 | `npcs=0`、`shrines=0` | ❌ **不持久化** |
| 读档后继续演化 | hash **分叉**：`95febe9ac33b24d3` vs `4539c8fe5a1a2ad8` | ❌ **存档→读档 ≠ 等价** |

**结论：N2 不能建立在 `World.save/load` 之上。** 它是为"断点续玩"设计的（不要求逐字节等价、也不要求快）；回滚要求的是「**完整 + 紧致 + 快**」——目标不同。

**顺带修掉两个真 bug**（这次实测暴露，均已加回归护栏）：

1. **读档崩溃**：`TrialSystem` 只在 `tick == 1` 布点，而 `World.load` 只恢复状态、不跑 tick → ext 段读 `sites.get(i)` **越界崩溃**。也就是说，**只要试炼点已布点，读档必崩** —— 即真实世界里的**每一次**存读档。
   修：`load` 内若存档有布点（`ns > 0`）则以规范的 `tick = 1` 子流重建（`deriveStream` 只依赖 seed 与名字、与 `rng.state` 无关 → 可逐位复现）。
2. **读档后世界「冻结」**：`Simulation(World)`（**读档装配路径**）**漏注册 92 个系统** → 读档后 NPC / 文明 / 试炼 / 民政**全不演化**，只剩地形与玩家能动。
   修：`systemCount() == 0` 时补注册（幂等，防重复注册污染顺序）。

> 这两个 bug 此前**被门禁完全掩盖**：旧 `SaveLoadTest` 用**裸 `World`**（`addSystem` 从未被调用 → 系统数 0），走的是"无系统"降级路径。已补**场景 2**（真实 `Simulation` 路径）钉住它们。

### 5.4 修订后的 N2（回滚优先）

| 子步 | 内容 | 判据 |
|---|---|---|
| **N2-0 完备性** ✅ **已完成（2026-09-14）** | 反射式 `StateCodec` 覆盖 **World 63 + Player 38** 个字段 + **92 个系统实例的全部私有状态**；tick==1 自举全部改为读档可重建 | **双一致已达成**：跨进程实测「存档→读档→再演化 120 tick」hash **逐字节相同** |
| **N2-1 紧致快照** ✅ **已完成（2026-09-14）** | 窗口内编辑不再整块落盘，改为**与 seed 确定性地形基线的稀疏差分** | ✅ 实测 **22.25 MB → 317 KB（70x）**；`NETSNAP` 门禁（第 40 道） |
| **N2-2 原地恢复** ✅ **已完成（2026-09-14）** | `restoreInPlace(byte[])` 写回**同一个 `World` 实例**（不 `new World`、不重注册系统），先把窗口地形**强制重生成**为原始基线再叠差分 | ✅ 恢复后 hash 与快照时**逐字节一致**、`systemCount` 不变、系统状态立即落位 |
| **N2-3 回滚** ✅ **已完成（2026-09-14）** | `restoreInPlace` + 重演 | ✅ **黄金判据通过**：`h@rollback == h@direct`（且恢复前世界已**确实跑偏**）；`ROLLBACK` 门禁（第 41 道） |

> **教训**：N2 的原方案（"复用 `save/load` 当内存快照"）看起来天经地义 —— 实测三刀全废（22 MB、实体层缺失、继续演化分叉）。**架构级方案必须先探针量化再定**，这正是本项目自己的「规则 0」。
>
> **附带收获**：这次量化顺手挖出并修掉了两个**真实的、影响玩家的**立项 F bug（读档必崩 + 读档后世界冻结）。**它们此前一直是"门禁全绿"状态**——因为门禁走的是无系统的降级路径。

### 5.5 N2-0 完备性清单（机械化，2026-09-14）

"完整"最怕的不是难写，是**沉默**：加一个字段忘了序列化不会有任何症状，直到某次回滚后世界悄悄走偏，而 `hashState()` 又因**故意做窄**而看不见。所以第 39 道门禁 **`SNAPSTATE` 用反射强制分类**——`World`(72 字段) 与 `Player`(40 字段) 的**每一个**实例字段必须落进且仅落进一个桶，**新增字段不分类即 FAIL**。

**实测结果**：

| 桶 | World | Player | 含义 |
|---|---|---|---|
| `PERSISTED` | 16 | 11 | `save/load` 已覆盖 |
| **`PARTIAL`** | **8** | — | **最危险**：对象只存了部分标量，**看起来已覆盖** |
| **`GAP`** | **21** | **28** | 完全未持久化 |
| `DERIVED` | 22 | — | 可由 seed/地形/索引重建，**无需**持久化 |
| `DIAG` / `CONST` | 5 / — | — / 1 | 诊断统计 / 不可变常量 |

**未持久化字段合计 = 57。**

- `World.GAP`(21)：`npcs`、`beasts`、`shrines`、`chronicle`、`fxRng`、`windX/windZ`、`builtCells`、`events`/`eventsProcessed`、`paused`、`raining`/`humidity`、`shifting` + 7 个 `shift*`（M3 流式中间态）
- `World.PARTIAL`(8)：`civ`、`individual`、`polity`、`social`、`calamity`、`trials`、`matter`、`player` —— 例如 `individual` 只存了 10 个标量，类里有约 100 个字段
- `Player.GAP`(28)：`vy`/`stamina`/`alive`/`xp`/`atk`/各冷却/`killCount`/`arts`/`currentArt`/`atkAnim`/`intents`/装备槽/属性点 …

**这一步的价值不是"存好了"，而是把"还剩多少没存"从"未知"变成了"57 项、可枚举、且不会再悄悄增长"** —— 因为从今往后，任何人加字段都会被门禁当场拦下要求分类。

---

## 6. 决策记录：A 与 B **不是二选一**

> 用户决策（2026-09-14）：**「A 和 B 都可以，同时支持本地服务，也可以使用专用服务器。」**

关键洞察：这里其实是**两个正交的维度**，被「A/B」这个命名混在一起了。

| 维度 | 选项 | 差异在哪 |
|---|---|---|
| **客户端模型** | 锁步（A） / 状态流（B） | 客户端**跑不跑仿真** |
| **权威进程住哪** | 本地服务（listen server） / 专用服务器（dedicated） | 权威**跑在谁的进程里** |

两者自由组合，且**权威层完全共享**：

| | 本地服务（一人开房，既是权威也是玩家） | 专用服务器（无头常驻进程） |
|---|---|---|
| **锁步客户端** | ✅ 局域网合作首选 | ✅ 公网合作首选 |
| **状态流客户端** | 少见 | 面向低端/防作弊/大量玩家 |

### 统一实现：权威层做成 transport-agnostic 的 headless 核心

```
  SimAuthority（无 GL、无窗口、纯 core + rng）
     ├─ Transport: Loopback   ← 进程内，本地服务用（权威与渲染同进程）
     ├─ Transport: Udp        ← 局域网 / 互联网
     └─ Transport: Tcp        ← 中继 / 回退 / 掉线重连
```

三种启动形态，**同一份权威代码**：

| 命令 | 形态 |
|---|---|
| `run-game.bat --host` | 本地服务：自己即权威 + 玩家 |
| `java -cp out net.ServerMain --dedicated --port 7777` | 专用服务器：无头常驻，可挂机/重连 |
| `run-game.bat --join <ip>:7777` | 客户端 |

### 一句必须说的冷水

**B 的客户端侧**（只渲染、不仿真）需要把 `Game` 从"直读本地 `World`"改成"读远端镜像 + 插值"——这是全项目**唯一伤筋动骨**的地方。
但注意：**A 与 B 的权威层 100% 共享**，所以先做 A 的客户端**不浪费任何东西**；B 只是**多一个客户端实现**，不是重写。→ **B 放最后做。**

---

## 7. 决策记录（已全部拍板）

1. ~~形态~~ → **已定：A+B 统一，权威层可部署**（见 §6）。
2. ~~拓扑~~ → **已定：本地服务 + 专用服务器都要**（同一份权威代码，两种启动参数）。
3. ~~手感底线~~ → **已定（2026-09-14）：一开始就上预测回滚**，不走"先忍延迟、以后再补"的路线。

> **「一开始就上回滚」的实际含义**（重要）：它把 **N2 从"回放功能"升级为"回滚原语"**。
> 回滚 = 「回到过去某个 tick 的快照 → 用修正后的输入重演」。所以 N2 必须交付的不是"能录像"，
> 而是 **快速、精确的内存快照 / 恢复**。现有 `World.save/load` 是**文件 IO**（`DataOutput` + `chunkEdits`），
> 每帧用会要命 —— **必须另做内存快照**。详见 §5.2。



### 5.6 N2-0 落地：双一致达成 + 一个架构级发现（2026-09-14）

**成果**（门禁 `SAVEL-GATE` 的 `doubleConsistency=true` 永久守住）：

| 项 | 结果 |
|---|---|
| 字段覆盖 | **World 63/73 + Player 38/40** 全部持久化；仅 12 个字段被显式跳过（**每条都有理由**，见 `SNAPSTATE` 输出） |
| 系统实例状态 | **92 个系统的私有字段全部纳入快照** |
| 读档瞬间 | `hashEqOnLoad=true` |
| **再演化 120 tick** | **`hashEqAfter120=true`（双一致达成）** |
| 跨进程隔离实验 | dump 进程写快照 → load 进程读快照 → 两进程 `h240` **完全相同**（排除"同 JVM 跨实例干扰"这一伪因） |

**架构级发现（本轮最大收获）——「状态不只在 `World` 上」**：

```
public final class WindSystem implements System {
    private int t = 0;                      // ← 系统实例自己的可变状态
    private float gustX = 0f, gustZ = 0f;   // ← 持久于两次刷新之间
```

`t` 决定基础风向角。读档后系统是**新注册**的 → `t` 从 0 重来 → 风向立刻不同 → 生态/天气/方块写入全偏。
**而读档瞬间的 `hashState` 是相等的**，只有继续演化才暴露。

> 这打破了「World 的字段 = 全部仿真状态」这个看似显然的假设。**凡"系统对象持有跨 tick 状态"的架构，快照都必须包含系统实例本身**，否则快照是"看起来完整"的。

**落地方式（与 `World` 同策略：反射、自动覆盖）**：快照按**系统名**记录每个系统的实例状态字节；`World.load` 时系统尚未注册（注册属 `Simulation(World)` 职责），故先暂存，注册完成后由 `World.applyPendingSystemStates()` 按名字对位写入。按名字而非顺序 → 系统列表顺序变化也不会错位。

**方法论战绩（写下来给下一次用）**：
1. **`hashEq` 相等 ≠ 快照完整**。验收必须是「读档后**继续演化** N tick 仍相等」（双一致）。
2. **同 JVM 双世界 ≠ 跨进程**。先用**双进程隔离实验**排除"跨实例静态干扰"这个伪因，再去找真因。
3. **逐字段字节 diff** 是最锋利的工具（`StateCodec.writeAny` 暴露的逐字段编码 + 按名字排序 → 直接给出"哪些字段不同"）。
4. **逐 tick 找第一个分叉点**（desync hunting）把 120 tick 的模糊分叉收敛到 **tick 121**，再在该 tick 做字段 diff → 一次命中 `humidity`/`windX`/`windZ`。

### 5.7 N2 完成：紧致快照 + 原地恢复 + 回滚黄金判据（2026-09-14）

N2 三个子步全部落地，**N2 计划收口**。三件事都按「先探针量化，再动手」做，每步都有门禁钉住。

#### (a) N2-1 紧致快照：22.25 MB → 317 KB（70x）

**第一步仍是量化**（160×112×160 / 92 系统 / 120 tick，量"当前状态 vs seed 确定性地形基线"的差分密度）：

| 指标 | 实测 | 结论 |
|---|---|---|
| 与基线不同的格数 | **28,699 / 2,867,200 = 1.0009%** | 差异**高度稀疏** |
| 含差异的块 | **100 / 100**（每块 18~406 格，无"整块大改"离群） | **均匀**分布 → 不能"只存少数块" |
| 全窗口重生成基线 | 204 ms | 只适合 save/restore，不适合每帧 |
| 全窗口内存拷贝 | 15.2 ms（21 MB） | 回滚若走全拷贝，ring 会吃 1 GB+ |

→ 结论：**稀疏差分**是最优解。新存档格式 `BWORLD2`：

```
long  key      全局块坐标 (gcx<<32 | gcz)
int   n        差异格数
int   idx[n]   块内线性下标 (lx*SY + y)*CHUNK + lz
byte  mat[n]   方块索引（uint8；超 255 **响亮报错**而非静默截断）
float mass[n]  质量
```

**基线契约**：`baseline + diff == 存档时的真实状态`；基线由 `generateWindowTerrain()` 在**同一窗口原点全窗生成**，与读档路径 `new World(...)` + `restoreWindowOrigin(...)` **逐字节同源**。

> ⚠️ **为什么不能"就地为单个块重生成基线"**：`Megalith.fillChunk` 的选址/地面高度（`groundY`/`steep`）会**读取邻列** `mat`，所以"原始地形"只在整个窗口**按固定序（cx 外层 / cz 内层）全量生成**时才可复现。单独重生一块时邻列可能已被系统改写 → 得到的是**另一个**基线，差异集就会错漏 → 读档后静默走偏。故基线一律取**全窗**生成结果。这条也是"窗口**外**的编辑块仍整块落盘"的原因。

**实测结果**：`22,250,720 B → 317,330 B`（**70x**）。`NETSNAP`（第 40 道）断言七件事全部 PASS：字节预算 ≤1 MB / 压缩比 ≥8x（实测 68x）/ 稀疏性 ≤5%（实测 0.65%）/ **结构等价**（`原始基线 + 差分` 用 codec 自己的比较器自校验为空）/ **双一致** / 防假绿三项。最终 317 KB 的构成**大致对半**：窗口稀疏差分 ≈156 KB + v5 反射段 ≈155 KB。

#### (b) 途中挖出的两个"不会让门禁变红"的坑

**坑 1：1.33 MB 的 v5 反射段里，69% 是"派生状态被当成状态写进去"。**

逐字段量了一遍（`StateCodec.writeAny` 逐字段编码）：

| 字段 | 字节 | 占比 | 性质 |
|---|---|---|---|
| `surfaceCells` | 651,011 | 48.9% | **派生**（`rebuildIndex()` 重算） |
| `civ` | 386,214 | 29.0% | 真状态（`urbanOrder` 256 KB + `zone` 128 KB） |
| `surfaceY` | 129,609 | 9.7% | **派生** |
| `surfaceTopY` | 129,609 | 9.7% | **派生**（M3 的临时暂存缓冲） |
| 其余 ~30 个字段 | ~36,000 | 2.7% | 真状态 |

**派生字段合计 916,629 B（69%）**，而它们在 `load` 里紧跟 `StateCodec.read` 之后就被一行 `rebuildIndex()` 全量重算 —— **写了也白写**。

> **这是门禁体系的一个真实盲区**：`SNAPSTATE` 原本只断言"字段**要么持久化、要么有理由**"，所以派生字段**被持久化**是"合法"的 —— 它不会让任何门禁变红，只会让快照悄悄膨胀。已补第 ⑥ 条断言 `DERIVED_FIELDS_NOT_PERSISTED`：**"派生"必须是可断言的性质，不能靠"写的人记得"。**

**坑 2：`SKIP` 按**纯字段名**匹配 = 全局匹配，会静默跳掉别类的同名字段。**

实测撞到：`ContentSystem.registry`（内容注册表，装配句柄）与 `World.registry` 同名而被"顺带"跳过；`ChunkDiff.mat/mass`、`InputFrame.intents` 同理。那种"因重名而恰好正确"是**偶然** —— 一旦有人给字段改名，跳过就无声消失，且**没有任何症状**。

→ `SKIP` 改为**类限定名**（`World.mat` / `ContentSystem.registry` / `Civilization.urbanOrder` …），每个跳过都成了**针对某一个类的显式决定**；并补 `NO_PHANTOM_SKIP` 断言（每条 SKIP 必须是该类的真实实例字段 —— 字段改名后条目不会永远留着）。顺带把 `Civilization.urbanOrder`（`int[51200]` = 256 KB，可由 `(SX,SZ,centerX,centerZ)` 确定性排序重建，`null` 时 `buildOrder()` 自动重建）纳入跳过。

#### (c) N2-2 原地恢复 + N2-3 回滚黄金判据

`World.restoreInPlace(byte[])`：不 `new World`、不重注册系统，只把状态写回去（保留索引热态与系统实例引用）。

关键细节 —— **必须先把窗口地形强制重生成**为原始基线再叠稀疏差分；**不能复用** `restoreWindowOrigin`（它在窗口原点相同时会提前返回、不重生成，差分就会叠在"已被系统改过的"数组上）。另：`dirtyChunks`/`lightDirty` 等渲染派生状态不进快照，恢复后显式整体标脏。seed / 尺寸不符**响亮抛错**。

`RollbackTest`（第 41 道）的黄金判据比字面判据更狠一层：回滚世界在取完快照后**先故意用错误输入跑偏**，再原地恢复，所以它同时证明"恢复能把已经走歪的世界**拽回**快照状态"。

```
seed=5a17c0de  160x112x160  N=70 RB=60  systems=92   snapshot = 334 KB
h@snapshot = d860636cd93f2f96
h@diverged = e3db35fba4090c41   （恢复前确实已走偏）
h@restored = d860636cd93f2f96   （== h@snapshot ✔）
h@rollback = d385c4c267719ea1
h@direct   = d385c4c267719ea1   （完全相同 ✔ 黄金判据）
```

#### (d) 尚未做到的（诚实清单）

| 项 | 现状 | 为什么可以接受 / 下一步 |
|---|---|---|
| **每 tick 取快照仍不可行** | `captureWindowDiffs()` 内含一次**全窗基线重生成（204 ms）** | 数据量不是瓶颈（317 KB × 60 帧 ring = 19 MB，可行性没问题），**时间**才是。要进实时回滚需：① 缓存基线（窗口不动时基线不变）+ ② 把"全窗差分扫描（~56 ms）"换成增量脏格记录。**列为 N3 前置**。 |
| 窗口**外**的编辑块仍整块落盘（229 KB/块） | 未压缩 | 原因是上述"Megalith 读邻列 → 异窗基线不可复现"。块数只随"玩家实际走过的路径"增长（非全窗），是**有界的尾项**。 |
| `Player.intents`（意图队列）不进快照 | 靠"每 tick 恰注入一条"保证队列为空 | 语义上它属于**外部输入**（N1 `InputFrame`），回滚时本就该由输入层重新注入 —— 但**这是必须写明的契约**：若某端一 tick 注入多条，队列会残留。 |

**方法论沉淀（三条，已进技能）**：
1. **"不完备"与"膨胀"是两种沉默**：前者让回滚走偏，后者让快照涨回去 —— 都要用**静态断言**钉住，不能靠"写的人记得"。
2. **按名字匹配的开关表是全局的**：任何"按名字跳过/启用"的机制，都必须限定作用域，否则会因**重名**而静默生效或静默失效。
3. **"派生"是可断言的性质**：`x 能被一行代码重算` ⇒ `x 不该进快照`，且这条关系应该写成门禁，而不是注释。

### 5.8 N3-0/N3-1 落地：desync 检测 + 锁步协议层（2026-09-14）

N3 的两块地基落地。**关键取舍：协议层不碰 socket** —— `Transport` 抽象 + `LoopbackTransport`
（一对交叉队列 + 可调延迟）就能把帧序、锁步定序、desync 检测全部测掉；真正的 UDP 之后实现
同一接口即可，会话代码**零改动**。这正是 §6 那个"客户端模型与权威进程住哪是两个正交维度"决策的兑现。

#### (a) N3-0 `netHash()`：desync 检测的宽哈希（第 42 道门禁 `NETDESYNC`，7 项断言）

`hashState()` 是**故意做窄**的（为了零漂移门禁稳定）—— 实体层、系统标量、系统实例私有状态都不在里面。
联机时两端在这些字段上分叉，它看不见。

**实现选择：与快照同源。**

```
netHash = hashState()                      // 已含最贵的 2.87M 格 mat/mass 扫描
        + FNV(StateCodec.encode(world))    // 与快照同一条编码路径
        + Σ FNV(系统名 + StateCodec.encode(系统))
```

**编码覆盖什么，检测就覆盖什么 —— 新增字段自动纳入，不存在第二份名单。**
偷懒写"再挑几个字段哈希一下"必然重蹈 hashState 做窄的覆辙。

实测：单次 **21 ms**（预算 300），**每 20~40 tick 校验一次即可**（desync 晚 1~2 秒发现完全可接受）。

> **测试写法教训（值得单独记）**：做"改完再改回"的往返断言必须用**整数**字段。
> 第一版用 `civ.research += 1f; -= 1f`，结果 `RESTORED` 断言红了 —— 逐字段 diff 定位：
> `0.7f + 1f - 1f = 0.70000005f`，因为 **0.7 本就不能被 float 精确表示**。
> 这不是 netHash 的 bug，恰恰证明它**连 1 ulp 的浮点漂移都抓得到**。

#### (b) N3-1 `LockstepSession`：锁步协议层（第 43 道门禁 `NETLOCK`，5 项断言）

协议三条：
1. **锁步**：要模拟 tick T，必须先拿到**所有**玩家在 T 的输入帧；缺任何一个就不推进。
2. **输入延迟 D**：本地在 simTick=S 生成的输入供 `S+1+D` 使用；网络单向延迟 < D 个回合就永不卡顿。
3. **desync 检测**：定期互发 `netHash`，同 tick不一致即报。

消息两条：`MSG_INPUT`（`InputFrame.pack()`，N1 编解码）+ `MSG_HASH`（tick i32 + netHash i64）。

实测断言：**收敛**（两端经 2 回合延迟的回环跑 40 tick，逐 checkpoint hash 一致）；
**输入延迟 ≠ 语义**（D=0 与 D=3 跑出**同一个**最终 netHash —— 延迟只影响"何时发"，不影响"发什么"）；
**缺帧不推进**（掐断一端输入，另一端停在原地 —— 宁可卡住不可走偏，这是锁步的根）；
**补录历史被拒**（对已模拟 tick 补录输入响亮抛错）；**desync 可检出**（篡改一条输入帧的**一个字节**，
改成"合法但不同"的意图 → 两端都报）。

> **两个被门禁抓出来的测试错误（写下来，下次不再犯）**：
> 1. **故障注入必须包在真实对端上**。第一版把 corrupting wrapper 包在一个**新建的孤儿线路**上，
>    B 什么都收不到 → 测成了"缺帧"而不是"篡改"，门禁以 FAIL 指出。
> 2. 同上条的浮点教训。

#### (c) 边界（诚实清单）

| 项 | 现状 | 下一步 |
|---|---|---|
| **World.player 是单数** | 多玩家意图的"应用"由 `InputSink` 注入（本层只管定序与齐帧） | N4：`World` 支持 `remotePlayers[]`，或引入 `players[]` |
| **UDP 已实现 / TCP 未实现** | N3-2 `UdpTransport`（`DatagramChannel` 非阻塞）+ `UdpRelay`（星形中继，人齐才发 WELCOME、晚加入响亮拒）已落地，第 44 道门禁 `UDPLOCK` 跨 JVM 子进程 hash 逐字节一致 | TCP 需长度前缀帧器（同一 `Transport` 接口，会话代码零改动）；需重连/可靠序时再加 |
| **暂停语义** | `advance()` 在 `world.paused` 时不推进（暂停须全局一致） | 正式暂停控制消息（谁有权发起、如何同步） |
| **快照每 tick 取仍不可行** | 204 ms 基线重生成 | 预测回滚（N4）前必须：缓存基线 + 增量脏格记录 |

### 5.9 N3-2 落地：真实 UDP 传输 + 三启动形态（2026-09-14）

N3 收口。协议层（N3-1 的 `LockstepSession`）接到**真操作系统套接字**上，并用**一份权威代码**撑起三种启动形态。整套联机路线（N0→N3）至此闭环；四基线指纹逐字节不变。

**关键取舍：协议层零改动。** `UdpTransport` 与 `LoopbackTransport` 实现同一个 `Transport` 接口，`LockstepSession` 对此一无所知——N3-1 测过的"收敛/缺帧不推进/输入延迟仅调度旋钮/篡改一字节两端都报"在真网络上**逐字成立**。这正是 §6「客户端模型 × 权威进程住哪是两个正交维度」决策的兑现。

#### (a) 三形态同码

```
java -cp out core.net.NetMain --host      --port P [--seed S] --players N   # 本地服务：自己即权威 + 玩家
java -cp out core.net.NetMain --dedicated --port P [--seed S] --players N   # 纯中继（无头常驻，锁步下人人都是权威）
java -cp out core.net.NetMain --join 127.0.0.1:P                          # 纯客户端
```

三者只在「要不要起中继 / 要不要跑仿真」上有差别，握手/协议/输入路径完全一致。

#### (b) 新文件

| 文件 | 角色 |
|---|---|
| `core/net/UdpTransport.java` | UDP `Transport`：`DatagramChannel` 非阻塞 recv + 握手 `join`（并行连、人齐才 `connect` 收 WELCOME、重试超时响亮抛 `IOException`）+ `parseWelcome`（正确偏移读 `yourId`/`playerIds`/`seed`） |
| `core/net/UdpRelay.java` | 星形中继：`bind(port, seed, players)` 起后台线程收 HELLO，人齐一次发 WELCOME；重复 HELLO **幂等重发**（避免幽灵玩家）；人齐后 HELLO 计数 `rejects++` |
| `core/net/LockstepRunner.java` | 无头锁步驱动器（`--host`/`--join` 共用）；`run` 收尾打印 `FINAL tick=.. hash=.. desync=..` 供跨进程门禁断言 |
| `core/net/NetMain.java` | 联机入口（`--host`/`--dedicated`/`--join` + `--out` 双写日志文件） |
| `core/sim/UdpLockTest.java` | 第 44 道门禁 `UDPLOCK`（4 项断言） |

#### (c) 实测（第 44 道门禁 `UDPLOCK`）

| 断言 | 结果 |
|---|---|
| `UDP_TWO_CLIENTS_CONVERGE` | ✅ 真 UDP + 中继 + 2 客户端并行加入，N=20 tick 后 netHash 一致、无 desync |
| `UDP_THREE_CLIENTS_CONVERGE` | ✅ 星形中继在 3 人下同样成立，且与两端**同 hash**（人多人少不影响确定性） |
| `LATE_JOIN_REJECTED` | ✅ 人齐后的 HELLO 被拒（`relay.rejects > 0`） |
| `SUBPROCESS_CROSS_JVM` | ✅ `--host` 与 `--join` 各起**真实 JVM 子进程**，FINAL hash 逐字节相同 `d978824b368f0c71` 且 `desync=false` |

#### (d) 途中踩的坑（已写进 `OPEN_ITEMS.md` §24.5(d)）

并行 join 死锁 / `NotYetConnectedException` / 幽灵玩家 / `ProcessBuilder(List)` 别名 bug / 日志必须走文件 / 前向引用——其中 **`ProcessBuilder(List)` 别名 bug** 最阴：Java 8 存引用不拷贝，两个 builder 共用 `base` 会把 `--join` 追加进 host 命令行 → host 被解析成 join 模式 → 全场无中继 → 双双超时。

#### (e) 还剩什么（N4 前置 → N4 已收口）

| 项 | 现状 | 下一步 |
|---|---|---|
| **每 tick 取快照性能** | ✅ **N4 已解决**：`World.ensureBaseline` 懒生成 + `baseMat/baseMass` 引用复用，`baselineRegenCount` ≤3 实测（缓存生效，消除每 tick 204ms 全窗重生成） | — |
| **World.player 单数** | ✅ **N4 已绕过**：`PredictiveSession` 经 `InputSink` 按 `playerIds` 升序对位注入多玩家意图 | — |
| **预测回滚未接** | ✅ **N4 已接**：`PredictiveSession` 驱动「本地预测 + 权威到达回滚」，复用 `World.snapshot()`/`restoreInPlace` 与原地恢复原语 | — |
| **接入渲染主循环** | `NetMain` 是无头驱动器 | N5：接进 `render/lwjgl/Game` 主循环 |
| **房间/大厅服务** | 中继已能做，无"找房间"层 | N5：大厅服务（按需） |

### 5.10 N4 落地：预测回滚（GGPO 式客户端预测 + 权威帧到达回滚重演）（2026-09-14）

N4 收口。在 N3 纯锁步之上叠加**客户端预测 + 回滚**：本地意图即时跑在前面（零手感延迟），远端意图延迟到达时用「重复上次确认远端意图」预测，权威帧与记录帧不符则 `restoreInPlace` 原地回滚 + 重演。终态与纯锁步（D=0）逐字节一致——确定性卖点延续。四基线指纹逐字节不变。

**关键取舍：不碰 `World.player` 复数化。** N4 用 `PredictiveSession` 经既有 `InputSink` 按 `playerIds` 升序对位注入多玩家意图，绕开「`World.player` 改复数」这个会牵动全工程的改动；`World` 这边只新增**可重算缓存**（`baseMat/baseMass` + 失效标志），非仿真状态，已进 `StateCodec.SKIP`。

#### (a) 新文件

| 文件 | 角色 |
|---|---|
| `core/net/RollbackEngine.java` | 回滚缓冲：`Frame{tick, intents[], snapshot[]}` + `TreeMap` 环形缓冲；`record`/`frameAt`/`snapshotAt`/`pruneBefore`/`pruneAfter` |
| `core/net/PredictiveSession.java` | 预测回滚会话：`InputSink`/`LocalIntentSource` 接口；`advance`/`receiveFrame`/`setAuthoritative`/`reconcile`/`rollbackTo`/`predictRemote`/`recomputeConfirmed` |
| `core/sim/PredRollbackTest.java` | 第 45 道门禁 `PREDROLLBACK`（5 项断言） |

#### (b) 实测（第 45 道门禁 `PREDROLLBACK`）

| 断言 | 结果 |
|---|---|
| `FINAL_EQUALS_REFERENCE` | ✅ 延迟 D=4 预测回滚终态 `netHash` == 纯锁步（D=0）参照 `netHash`（到达顺序不同，已确认历史演化结果不变） |
| `ROLLBACK_HAPPENED` | ✅ 预测与真实远端意图不同 → `rollbackCount > 0`（真回滚，非"延迟≠语义"假象） |
| `NO_ROLLBACK_STILL_CONVERGES` | ✅ 纯锁步参照 `rollbackCount == 0` 且终态一致 |
| `DETERMINISTIC_REPLAY` | ✅ 重演路径终态 `netHash` == 预测路径终态（确定性重演，无随机残留） |
| `BASELINE_CACHED` | ✅ `baselineRegenCount <= 3`（基线懒生成生效，消除每 tick 204ms 全窗重生成） |

#### (c) 途中踩的坑（已写进 `OPEN_ITEMS.md` §24.6）

1. **`TreeMap.tailMap` 返回 `SortedMap` 而非 `NavigableMap`**：`RollbackEngine.pruneAfter` 原用 `frames.tailMap(tick+1)` 拿 `NavigableMap` 调 `.pollFirstEntry()` 失败（编译错）。修：用两参重载 `tailMap(tick+1, true)` 返回 `NavigableMap`。
2. **`StateCodec` 把缓存基线当状态序列化**：新增 `baseMat/baseMass`（各 160×112×160 全栅）= 每快照膨胀 +23 MB → `NETSNAP` 1 MB 预算失败。修：`SKIP` 表补 6 项（`baseMat`/`baseMass`/`baseWinCX0`/`baseWinCZ0`/`baselineValid`/`baselineRegenCount`），`SnapshotStateTest.SKIP_DOC` 同步 + `RECORDED_SKIPPED` 19→25。
3. **预测回滚驱动收敛 guard 偏紧**：非周期远端意图下每个 tick 都回滚，最坏 ≈ N·(D+1) ≈ 200 次迭代，超出旧 guard `(N+5)*4=180` → 误报"未收敛"。修：guard 提到 `(N+5)*(D+5)`。
4. **`out/` 陈旧类误导**：CORE 段编译失败时旧的 `World.class` 留盘，会让 `NETSNAP` 假性失败、掩盖 `PREDROLLBACK` 真实逻辑 bug。修：重命名 `out/` 触发干净重建（`BW_KEEP_OUT=1` 仅跳过 `shutil.rmtree`，仍会全量重编）。

### 5.11 N5 落地：联机会话接入渲染主循环（2026-09-14）

N5 把「协议层（N0–N4）」接进真正的游戏窗口——`--host`/`--join` 不再只是 `NetMain` 无头驱动器，而是 `render/lwjgl/Game` 里可被玩家看见、可移动、可交互的联机对局。整套联机路线（N0→N5）至此闭环。

#### (a) 抽象：`TickBody` 整 tick 推进体

单机整 tick = `Game.loop()` 固定步长里那 4 行（`world.tick()` + `rules.tick` + `techs.tick` + `effects.tick`）。其中 `rules/techs/effects` 是 `core.content` 内容层（经 `initContentLayer(seed)` 装配），**不在** `world.systems`（92 系统）内——故无头会话旧 `advance()` 只跑 `world.tick()` 会漏内容层。N5 抽出 `TickBody` 接口把"一整 tick 该跑什么"参数化：

```java
package core.net;
import core.world.World;
public interface TickBody { void tick(World world); }
```

`LockstepSession`/`PredictiveSession` 新增带 `TickBody` 的构造（默认 `this.tickBody = world::tick;` 向后兼容 `NetMain` 无头路径），`advance()` 内 `sink.apply(t, arr); tickBody.tick(world);` 替换原 `world.tick();`。`LockstepRunner.open` 重载 `open(World, Joined, int inputDelay, TickBody tickBody)`。

#### (b) 渲染主循环接线（`Game.java`）

- `main(String[])` 转发 `args`；`run(String[])` 解析 `--host`/`--join`/`--port`/`--seed`/`--players`/`--input-delay`，**单机分支逐字不变**。
- 联机态 `setupNetwork` 经 `UdpRelay.bind` + 本进程 `UdpTransport.join`（host）或直连（join）装配 `LockstepSession`，注入 `gameTickBody`（= 单行循环那 4 行）；`finally` 关 `relay`。
- `loop()` 固定步长分支：网络态由 `session.advance()` 驱动（`submitLocal` 喂本地 `Intent` + `pump` + `canAdvance` 推进）；单机态维持原 4 行。
- `updateInput` 本地移动：网络态改由 `gameTickBody` 内按已注入 `applied[0]` 做固定步长确定性位移（`physicsTick`），**不再**走连续 `physicsTick`（零帧 dt → 两端一致）；单机态仍走连续 `physicsTick`（手感/重力/碰撞逐字保留）。

#### (c) 关键设计判断

- **本地移动确定性化**：`Player` 意图是 `Deque`（`setIntent` 入队、`World.tick` 消费 `pollIntent`），**无公开 `intent` 字段**；故网络态用自定义 `InputSink` 把 `arr[0]`（最低 id 意图，两端同序 → 确定性）同时 `setIntent`（供 `World.tick` 战斗/aggro，与单机一致）并暂存到 `applied[0]`，`gameTickBody` 先位移再 `world.tick` + 内容层。
- **向后兼容**：`NetMain` 无头路径仍可用（默认 `world::tick`），跨 JVM 子进程门禁不受影响；三启动形态（`--host`/`--dedicated`/`--join`）语法不变。

#### (d) 实测（第 46 道门禁 `NETINTEG`）

| 断言 | 结果 |
|---|---|
| `CROSS_INSTANCE` | ✅ 两 `LockstepSession` 跨实例、N=30 tick 后 `simHash` 一致（`hashA==hashB`） |
| `WRAPPER_FAITHFUL` | ✅ 会话驱动终态 `simHash` == 直接 `world.tick()` 演化终态（`hashA==hashD`，`wD.tick==30`）；首跑曾因逐 tick 相位错开假阳性失败（回环投递时序使 t=1 时 `canAdvance()` 仍 false），改比终态后通过 |
| `TICKBODY_INJECTED` | ✅ 注入带确定性副作用的自定义 `TickBody` 后两端仍一致且与默认 `world::tick` 不同（确被使用） |

#### (e) 途中踩的坑（已写进 `OPEN_ITEMS.md` §24.7）

1. **`Player` 无公开 `intent` 字段**：初版误用 `w.player.intent` 做网络位移 → 编译失败；改为 `setIntent`/`pollIntent` + `applied[0]` 暂存。
2. **受检异常传递**：`setupNetwork` 声明 `throws Exception` 后，`run`/`main` 必须 `throws Exception`。
3. **相位错开假阳性**：逐 tick 哈希比对把"回环投递未推进"误判为"分叉"；`NETINTEG` 改为比终态 + 断言 tick 数一致。
4. **`javac` 中文注释**：`Game.java`/`NetIntegTest.java` 含中文注释，编译须 `-encoding UTF-8`。
