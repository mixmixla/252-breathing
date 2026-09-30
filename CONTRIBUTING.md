# CONTRIBUTING · 协作与开发约定

> 面向**第一次参与这个项目**的人。
> 这个仓库是一个 Java 8 + LWJGL3 手搓体素引擎的开放世界沙盒游戏，核心卖点是 **确定性涌现**（同种子同输入 → 逐字节复现）。
> **它不是普通的游戏项目：这里"改对了"的判据不是"看起来能玩"，而是"88 处门禁退出码全 0"。**

---

## 0. 三分钟上手

```bash
git clone https://github.com/mixmixla/252-breathing.git
cd 252-breathing

# 一次性构建（Windows）
build.bat            # ⚠️ 只跑 17 道门禁、且不检查每个测试的退出码 ⇒ 红了你也看不出来
python build_runner.py   # ✅ 完整验证走这个（82 道门禁 + 5 段编译 + 审计 = 88 处 *_EXIT）

# 跑游戏
run-game.bat
```

> ⚠️ **别信 `build.bat` 的 `BUILD OK`。** 实测它：`BUILD OK.` 打印在**跑测试之前**、
> 只跑 **17 道**（主构建器 82 道）、每个 `java ...Test` 后**没有 `if errorlevel`**、
> 末尾**无条件** `ALL 17 GATES DONE.`。它只适合当"编译 + 快速冒烟"的快捷键。

**环境要求**：JDK **8**（项目用 Java 8 语法，无 `var` / `record` / `String.strip()`）、显卡支持 OpenGL 3.3+。
`libs/` 里已经带了全部依赖 jar（LWJGL / JOML / Gson / JNA），**不需要自己配依赖**。

`run-game.bat` 会先编译再启动，双击即跑，始终是最新源码。

---

## 1. 改代码之前：你必须知道的三件事

### 1.1 唯一的"正确性"判据是 88 处退出码

```bash
# 关键：先清 out/，防止"编译失败 ⇒ 门禁跑旧 class ⇒ 静默假绿"
rm -rf out
BW_KEEP_OUT=1 python build_runner.py
```

然后**必须**做这两步检查：

```bash
grep -c "_EXIT=" build_report.txt                      # 必须是 88
grep "_EXIT=" build_report.txt | grep -v "_EXIT=0"     # 必须没有任何输出
```

> 🩸 **`build_runner.py` 的进程退出码恒为 0**（脚本里没有 `sys.exit`）。
> 所以 `python build_runner.py && echo OK` 这种写法**永远打印 OK**，哪怕门禁全红。
> **只看 `$?` 等于没测。** 必须数 `*_EXIT` 非零个数。

再加一道静态审计：

```bash
python audit_invariants.py     # 期望 FAIL 0 / WARN 0（26 项）
```

### 1.2 `simHash` 是回归锚点

构建报告里有仿真指纹（`DETERMINISM` / `ZERO-DRIFT` / `STREAMING` / `NPC` 等）。
**改动前后它要么不变，要么你必须明确声明"这是有意演进"并写进文档**——
后者是允许的（改了玩法，指纹当然会变），但**绝不能悄悄变**。

### 1.3 编辑器警告：`core/systems` 包里 `System` 被遮蔽

`core/systems/System.java` 会和 `java.lang.System` 冲突。
在 `core.world` 等包里写 `System.out` 会编译错，要写 `java.lang.System.out`。

---

## 2. 十二条铁律（违反即打回）

| # | 铁律 | 为什么 |
|---|---|---|
| **R1** | **88 处 `*_EXIT` 必须全 0；改完跑全量构建。** | 这是唯一的可信回归判据。 |
| **R2** | **不许削弱、跳过、注释掉任何门禁。** | 门禁是过去踩过的坑凝固成的。历史上"把红灯当环境 flaky"掩埋了真 bug **五批**。 |
| **R3** | **先量再改。** 任何性能/行为改动前必须有实测数据；测量要在**真实 tick 循环内**或**消融**，不能把系统拎出来连调 N 次。 | 那样会冻结世界 + 复用同一 RNG ⇒ 得到假热点（真实踩过）。 |
| **R4** | **改核心数据结构前，先找"最强的等价性门禁"。** 没有等价性断言的模块，别先动。 | `CellSet` 改造能安全完成，全靠 `STREAMCHUNK` 断言了"元素集合 + 迭代序"。 |
| **R5** | **纯表现层不许污染仿真随机流。** 演出/渲染随机只走 `fxRng`；仿真随机只走 `simStream(name)`。 | 历史上演出随机用了主 RNG ⇒ 难度不可复现。 |
| **R6** | **新 World 字段：若是派生/计时/瞬态，必须进 `StateCodec.SKIP`；派生脏标记同样要 SKIP。** | `hashState()`（窄指纹）看不见的东西，只有 `netHash()`（宽哈希）类门禁能看见。 |
| **R7** | **不许动 `play/` 下的 JS 版**（那是另一个被锁死的基线，`winRate 0.75`）。 | 双核基线不能互相牵连。 |
| **R8** | **不许硬编码总量**（系统数 / 门禁数 / 内容条数），全部由门禁派生。 | 否则加内容必然漏改一处。 |
| **R9** | **注释不得说谎。** 自称"守恒 / 1:1 / 已接线"就必须实测对账。 | 已揭发三处伪"守恒"注释。 |
| **R10** | **QA 场景必须自证"真的进了画面"**，且日志要放在**被测时刻之后**。 | "参数传了" ≠ "场景生效了"（栽过 6 次）。 |
| **R11** | **凡跨帧累积的量，QA 下必须有确定性终值。** | 已为此制过 5 次同型补丁。 |
| **R12** | **别用 Java 8 以上的语言特性。** | 构建链是 JDK 8。 |

---

## 3. 常见"假绿"陷阱（照抄会得出错误结论）

1. **`javac ... | head -N && echo OK`** —— 管道退出码是 `head` 的，**javac 失败也打印 OK**。
   正确写法：`javac ... > log 2>&1; echo "rc=$?"; head -20 log`。
2. **CORE 编译失败 ⇒ 门禁跑旧 `out/`** —— 报告里标签总数会塌到个位。存疑先 `rm -rf out`。
3. **裸 `new World(...)` 不带系统** —— `World.systems` 是空的，`tick()` 的系统循环跑 **0 个**，世界一格没变。
   等价性探针必须用真 `Simulation` 驱动。
4. **门禁只打印 FAILED 却 `exit 0`** —— 那就是假绿（已抓到 2 例）。门禁必须有失败退出码。
5. **headless 下 `World.materials` 是空存根**（density 全 20 兜底）—— 探针必须显式
   `ContentRegistry.load(new File("assets/content")).materialBook()` 并加自检断言。
6. **QA 场景参数传了但没进画面** —— 必须带自证日志（R10）。
7. **"探针跑了 N tick" ≠ "N tick 真的发生了"** —— 见第 3 条。
8. **构建 RC 恒 0** —— 见 §1.1。

---

## 4. 代码地图（从哪读起）

| 想改什么 | 去读 |
|---|---|
| 主循环 / 渲染 / HUD / 输入 / 菜单 | `src/render/lwjgl/Game.java`（**8,863 行，单点巨文件**，做好心理准备） |
| 世界状态 / 地形 / 方块 | `src/core/world/World.java`（3,520 行）、`Blocks.java` |
| 玩家机制（属性/装备/战技/背包） | `src/core/world/Player.java` |
| 敌人 | `src/core/world/Beast.java`（5 原型 + 1 Boss） |
| NPC / AI 决策 | `src/core/agent/Decision.java`、`Npc.java`、`src/core/systems/NpcSystem.java` |
| 加内容（**优先走这条**） | `assets/content/<type>/*.json` —— **加内容不需要改引擎** |
| 内容注册 / 校验 | `src/core/content/ContentRegistry.java` |
| 新增系统 | `src/core/systems/SystemRegistry.java`（**追加到末尾**，同步 COUNT + 报告 + GOLDEN_HASH） |
| 存档 / 联机状态编码 | `src/core/net/StateCodec.java` |
| 门禁本身 | `build_runner.py` 的 `gate(...)` 清单（82 条） |

---

## 5. 提交规范

- **一次提交只做一件事**，提交信息用 `type(scope): 摘要` 形式，例如：
  - `feat(sim): 批 C —— 材料反应引擎`
  - `fix(render): 修块光初始化晚于网格导致自带光源不发光`
  - `perf(sim): sand 全窗扫描 → 体素索引（附 SANDFALLEQ 门禁）`
- **提交前必须**：88 处 `*_EXIT` 全 0 + `audit_invariants.py` FAIL 0。
- **碰了确定性行为的改动**：在提交信息里写明"指纹有意演进 + 新值"。
- **新增内容（JSON）**：同步更新计数门禁，否则 `CONTENT` 门禁会红。
- 不提交 `out/` / `save/` / `proof/` / `*.log` / `__pycache__/`（`.gitignore` 已覆盖）。

---

## 6. 想找活干？按这个顺序

| 优先级 | 方向 | 为什么 |
|---|---|---|
| 1 | **修冷启动**（13.5 s → < 4 s） | 唯一每个玩家都会遇到的体验缺陷；先用 `-Dbw.fpsdiag=1` 拿数据再动手 |
| 2 | **NPC 劳动闭环**（`Decision` 的 `GATHER/CRAFT/TRADE/STATION` 目前无实现） | "会呼吸的世界"这一卖点从宣传语变成可玩事实的关键 |
| 3 | **内容扩容**（纯 JSON，零改引擎） | 当前 `quests=1 / beasts=1 / skills=3`，管道远大于货量 |
| 4 | **让涌现被玩家看见**（因果回声 / 区域状态可视化） | 99 个系统在跑，但玩家感知不到 |
| 5 | 自适应音乐（补齐音频的另一半） | 零新增依赖（沿用 `javax.sound` + 程序化合成范式） |

完整待办与优先级见 `docs/OPEN_ITEMS.md`；当前状态与真实缺口见 `README.md §5`。

---

## 7. 常用诊断开关

| 开关 | 作用 |
|---|---|
| `-Dbw.fpsdiag=1` | 每秒一行真实帧率 + `dirty / mesh=inflight/threads / built`（诊断进场卡顿的主力） |
| `-Dbw.snap=<png>` | 无头截图（冻结仿真/演出/输入，可复现）。**用 `tools/qa_snap.py` 调用，别手拼命令行** |
| `-Dbw.phase=<0..1>` | 指定一天中的时刻 |
| `-Dbw.yaw` / `-Dbw.pitch` | 指定相机朝向（拍 god-ray / 体积光轴必需） |
| `-Dbw.warm=<N>` | 截图前预热 N 帧 |
| `-Dbw.ao` / `-Dbw.shaft` / `-Dbw.dither` / `-Dbw.smoothlight` / `-Dbw.forest` | 各美术特性旋钮（**0 = 恒等、逐字节等价**，用于 A/B） |
| `-Dbw.msaa=<n>` / `-Dbw.show=1` | MSAA 实机验证 |
| `-Dbw.menu=1` / `--debug` | 直接进菜单 / 调试沙盒（授全部能力 + 无敌，不进指纹） |

> ⚠️ JVM 参数在 Windows 上要通过 `set BW_OPTS=-Dbw.fpsdiag=1` 传（`run-game.bat` 里已处理）；
> 直接写在主类名后面是**程序参数**，不会被 JVM 识别。

---

## 8. 提速技巧

```bash
BW_JOBS=4 python build_runner.py    # 117s → 43s（实测 2.55×），且与串行逐行等价
```

> 默认是 `BW_JOBS=1`。并行会同时跑 4 个 JVM（各建 160³ 世界），**内存占用高**，按机器情况开。
> 想只跑编译不跑门禁：`BW_KEEP_OUT=1 python build_runner.py` 之前可先看 `COMPILE TIMINGS` 段。

---

## 9. 有疑问怎么办

1. 先查 `docs/OPEN_ITEMS.md`（待办 + 战史，约 11 万字，**先看 §1 优先级队列**）。
2. 再查 `docs/` 下的专题文档：`ENGINE_MATURITY.md`（该不该动引擎）、`ART_BIBLE.md`（美术规范）、
   `NETPLAY_READINESS.md`（联机方案）、`FOUR_SOURCE_PICK.md`（Noita / Terraria 借鉴清单）。
3. 还不清楚就开 issue，**带上 `build_report.txt` 的相关片段**（不要只贴结论）。
