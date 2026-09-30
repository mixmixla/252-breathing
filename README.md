# 会呼吸的世界 · Java/LWJGL3 真 3D 版

> 这是《会呼吸的世界》项目的 **Java + LWJGL3 手搓体素引擎** 平行版本。
> 与 `play/web3d.html`（Three.js 单文件浏览器原型）并存的"额外版本"，走 GPU 真 3D + 确定性涌现仿真。
>
> - 仿真侧：纯 Java（无图形库依赖），可单测、可重放
> - 渲染侧：LWJGL3 + OpenGL 3.3 core + JOML，**真 3D GPU 体素**
> - 证明侧：零依赖软件光栅器（`SoftwareRenderer`）可在无显示器环境出 PNG
>
> 📋 **还剩什么要做？→ 看 `docs/OPEN_ITEMS.md`（唯一待办总览；任务板的机器入口见 `orchestration/task_board.json`）。**
> 🤝 **第一次参与开发？→ 先读 `CONTRIBUTING.md`（构建 / 跑门禁 / 12 条铁律 / 常见假绿陷阱）。**
> 📊 **当前状态与真实缺口 → 见本文件 §5（2026-09-30 重写，含实测帧率与内容盘点）。**

---

## 0. 三句话回答"它是什么"

1. **底层**：确定性涌现仿真（**99 个系统，分 10 域** + 体素世界 + 玩家 + 世界回响），同种子同输入 → 逐字节复现（`DETERMINISM PASS`）。
2. **画面**：MC 明亮饱和体素 + 蓝天 + 真 3D 透视（不是等距伪 3D），LWJGL3 手搓，无游戏引擎抽象。
3. **可玩**：WASD 行走 / 鼠标视角 / Space 跳跃（重力落地、AABB 碰撞不穿墙）/ F5 第一-第三人称切换 / 左键挖 / 右键放 / 1-5 切方块 / Esc 退出；仿真以固定步长 20 ticks/s 推进，与渲染解耦。

---

## 1. 怎么跑

要求：JDK 8+、`javac`/`java` 在 PATH、显卡支持 OpenGL 3.3+（任何 2012 年后集显都够）。

### 一次性构建
```
build.bat        # Windows
./build.sh       # Git Bash / Linux
```
输出 `BUILD OK` 即成功（产物在 `out/`）。

### 跑真 3D 游戏（你本机）
```
run-game.bat     # Windows
./run-game.sh    # Linux（需把 natives-linux jars 放进 libs/）
```
默认种子 `20260908L`；可传参 `run-game.bat 123` 改种子。

### 跑零依赖证明图（任何环境，沙箱也行）
```
run-proof.bat
```
产物：`proof/world_proof.png`（等距 MC 地形 PNG）。

### 跑门禁（82 道 / 88 处 `*_EXIT`，以 `build_runner.py` 的 `gate(...)` 清单为准）
```
java -cp out core.sim.DeterminismTest          # 基线 1 指纹
java -cp out core.sim.ZeroDriftTest            # 基线 2 sim/render 隔离
java -cp out core.sim.PhysicsTest              # 基线 3 玩家物理
java -cp out core.sim.StreamingTest            # 基线 4 无限流式
java -cp out core.sim.NpcDeterminismTest       # 批次0   NPC
java -cp out core.sim.SocialDeterminismTest    # 批次1   社会核心
java -cp out core.sim.StorytellerDeterminismTest # 批次1余 说书人/村志（含 D2 长程去重/计数解耦）
java -cp out core.sim.CivilizationDeterminismTest # 批次2 文明深度
java -cp out core.sim.IndividualDeterminismTest  # 批次3 个体成长
java -cp out core.sim.PolityDeterminismTest      # 批次4 民政经济与灾害
java -cp out core.sim.TrialDeterminismTest       # C2    试炼/世界之心（含可达性）
java -cp out core.sim.WeaponArtTest             # C3    多武器战技（行为断言 + 零漂移不变量）
java -cp out core.sim.MenuTest                  # C5    菜单/暂停/设置（状态机 + 暂停透明性）
java -cp out core.sim.DayNightTest              # D3    昼夜/天空（连续性 + 视线射线基 + 纯函数零漂移）
java -cp out core.sim.AudioTest                  # C4    程序化音频（纯函数 + 包络起收零 + 声部池 + 混音契约）
java -cp out core.sim.MatterDeterminismTest      # B5    抽象字段（物相/熵/聚变/深海 + 指纹隔离 + 零 RNG + 可达性）
java -cp out core.sim.MegalithDeterminismTest
java -cp out core.sim.SaveLoadTest            # 门禁 18：存读档黄金测试    # C6    巨构神殿（材化确定性 + 滑动复现 + 门洞可达性 + 小世界豁免）
java -cp out core.sim.StreamChunkTest         # M3   跨图流式分帧等价 + INDEX 分片等价 + 索引惰性等价 + 邻域去 O(N²) 等价
java -cp out core.sim.PortableMathTest        # N0   联机可移植性（src/core 禁非 StrictMath 超越函数）
java -cp out core.sim.NetCodecTest            # N1   联机输入编解码（6B 协议 / 无损往返 / 拒绝而非截断）
java -cp out core.sim.SnapshotStateTest       # N2-0 快照完备性分类（穷尽分类 / 缺口基线）
java -cp out core.sim.NetDesyncTest            # N3-0 desync 检测宽哈希（与快照同源 / 21ms / 每 20~40 tick 校验）
java -cp out core.sim.NetLockTest              # N3-1 锁步协议层（回环可测：缺帧不推进 / 输入延迟仅调度旋钮 / 篡改一字节两端都报）
java -cp out core.sim.UdpLockTest              # N3-2 真实 UDP + 跨 JVM 子进程（--host/--join hash 逐字节一致）
java -cp out core.sim.PredRollbackTest          # N4 预测回滚（GGPO 式：延迟 D=4 终态==纯锁步 / 预测真错→真回滚 / 确定性重演 / 基线已缓存）
java -cp out core.sim.SubsystemTest             # 2026-09-17 空转参数落地（7 个预设参数各有牙 + 出厂默认 no-op + 负例）
java -cp out core.sim.RecipeTest                # 2026-09-17 冶炼产业链显式契约（ore3+coal2→iron_bar1 / 不足不扣料 / 未知键响亮）
java -cp out ShaderCheck                        # 2026-09-18 内联 GLSL 离屏真编译（解析 Game.java 的 initXxxShader → 建不可见 GL 上下文编译+链接；无 GL 时优雅 SKIP）
```
> ⚠️ 上面清单为**节选**；`build_runner.py` 的 `gate(...)` 是权威源
> （当前 **82 道门禁 + 5 段编译 + 审计 = 88 处 `*_EXIT`**，全 0 才算通过）。
> ⚠️ **`build_runner.py` 的 RC 恒为 0**（脚本里没有任何 `sys.exit`）——门禁红灯**不反映在退出码上**，
> 必须数 `*_EXIT` 非零个数。这是本项目最容易踩的「假绿」：只看 `$?` 会永远以为构建成功。
> 📊 全部门禁名 + 耗时排行见 `build_report.txt` 的 `=== GATE TIMINGS ===` / `=== COMPILE TIMINGS ===`。
> ⚡ 想提速：`BW_JOBS=4 python build_runner.py`（实测 117s → 43s，且与串行**逐行等价**；默认 1）。
`build.bat` / `build_runner.py` 编译后会自动跑全部，全 `PASS` 才输出 `BUILD OK`。
- `DeterminismTest`：同种子同输入 → 仿真指纹一致（基线 `74ad826636fe8eb2`）
- `ZeroDriftTest`：渲染乱用 fxRng 不影响 sim 指纹（sim/render 隔离；基线 `5ce9392207387ebf`）
- `PhysicsTest`：玩家行走物理 headless 验证（落地不穿地 / 跳跃峰值 ~1.25 格 / 撞墙不穿）
- `StreamingTest`：无限世界流式下确定性 + 玩家建造跨区块卸载/重载仍持久化（P3 门禁；基线 `87d5bf1cecb4c628`）
- `*DeterminismTest`（NPC…TRIAL）：每层接入主循环后，同种子两遍运行该层状态自洽；`TrialDeterminismTest` 额外做**可达性**断言（目标链能从头走到尾，防"门永远打不开"的死尾）
- `WeaponArtTest`：**玩家动作层**专用——四道基线门禁只跑 `World.tick()`，**看不见玩家的战技/攻击**，所以这道门禁不断言指纹而**断言行为差异**（回旋斩一次命中 3 只、远射命中 12 格外而 18 格外不受影响、远射无目标不空放扣体力、Lv1 锁定、解锁幂等），并附一条「整套动作跑完 `hashState` 逐字节不变」的零漂移不变量
- `MenuTest`：**UI/流程层**专用——菜单逻辑抽成纯模型 `MenuModel`（零 GL 零世界写入）后，导航/层级/设置钳制/QUIT 二次确认都可 headless 断言；核心不变量是**暂停透明性**：暂停被定义为「丢 tick」（`World.paused`），故「中途暂停任意次」与「从未暂停」跑同样多次 tick 后 `hashState` **逐字节一致**（若做成「冻结时钟」会静默错位存档/回放）
- `DayNightTest`：**天空/昼夜层**专用——`DayCycle` 是 `w.tick` 的纯函数（天色/日照方向/环境光/光色/雾密度），本门禁断言四件事：① **连续性**（逐 tick 扫 2 个整周期，色值变化量 ≤0.06，防「天色硬切」）；② **取值域与昼夜单调**（正午亮于午夜、正午雾薄于晨昏雾）；③ **视线射线基**（`SkyBasis`：屏幕中心==相机前向、fovY=90° 时屏幕上边缘恰上仰 45°、沿屏幕 Y 扫描视线高度严格单调——这正是"老版天空用屏幕 Y 取色、抬头天色不动"的修复点）；④ **纯函数零漂移**（狂算整套昼夜量后 `hashState` 与 `rng.state()` 逐字节不变）。另含**防死分支**断言：极光事件必须 >0 且**全部落在夜里**（对齐共享「夜」定义时若判据写反，极光会静默变成永不触发的死代码）
- `MatterDeterminismTest`：**抽象字段层**专用（批次5：物相/熵与有效能/聚变辐射/深海基地）——四道基线门禁既看不见"字段是否真在演化"（可能恒 0 死代码）、也证不了"该层确实在指纹之外"，故本门禁断言四类性质：① **不变量**（同种子两遍 `hashState` 与 `matter.snapshot` 一致）；② **可达性**（`transitions=21≥1`、`phasesSeen=2≥2`、12 个字段全非死——直接防"系统在跑、字段恒 0"）；③ **指纹隔离证明**（主动改 `matter` 各字段后 `hashState` 逐字节不变——"该层不进指纹"的构造性证明）；④ **零 RNG 证明**（跑一次 `update()` 前后 `rng.state()` 不变——新增系统不借用主 RNG，故不扰动其它层）。材料执行层（辐射扩散/作物变异/海洋遮罩/潜艇布点）由 `MatterSystem.MATERIAL_WORKS=false` 默认关守护，关时网格一字不改
- `AudioTest`：**音频层**专用——它与前两道同源：混音逻辑若长在 `SourceDataLine` 写回调里就只剩耳朵验收。门禁断言五类：① **纯函数确定性**（同 `(音效,变体)` 渲染两遍逐样点相同；整段脚本经混音器两遍逐字节相同，含噪声型音效以证明噪声发生器也是 `(variant,i)` 的纯函数）；② **包络起收为 0**（每个音效首样点**恰为 0**、末样点归零——这是"不炸咔哒"的保证——且峰值非 0，防「其实是静音所以当然没爆音」的假绿）；③ **声部池**（上限 8 硬约束 / 高优先级抢占低优先级 / **同优先级抢最旧**（老化，防长音饿死后续）/ 低优先级在满池高优先级时被丢弃并计数）；④ **静音语义与混音契约**（禁用或零音量 → 不发声且输出全零；**逐样点**比对 `clamp(Σ round(sample·32767))`，一次覆盖"定点和累加是否回卷"与"取整/钳制边界是否错位"——注意不能用"相邻样点跳变>满量程"判回卷，饱和波形本就在两轨间大幅跳变，会大面积误报）；⑤ **零漂移不变量**（「跑 tick 且每 tick 播不同音频」与「完全不播音频」的 `hashState` 逐字节一致——音频绝不吃任何 RNG，连 `simStream` 都不吃）
- `StreamChunkTest`：**M3 规模层**专用。四道门禁只跑 `World.tick()`，看不见「跨图平移」「派生索引」这些**只改派生状态**的改造，故本门禁断言四段：① **分帧等价**（`streamToSliced` 每帧 3 块跑完 vs 一次性 `streamTo`，window/`hashState`/`surfaceY`/索引**全等**）；② **推进单调**（跨窗未完成时新目标被忽略，不叠加/交错）；③ **索引惰性等价**（`setBlock` 增量补丁后 `cellsOfType`/`nonAirCells` 的**元素集合与迭代序**和「暴力扫网格重建」逐元素一致 —— 防"为了性能把索引改坏"这一最隐蔽的漂移）；④ **INDEX 分片等价**（每帧仅 1 条带的最深分帧，316 帧完成，结果与 `rebuildIndex()` 一次跑完逐字节相同 —— 护栏本轮新增的「索引重建分帧」路径）。另含 ⑤ **邻域去 O(N²) 等价**（均匀网格 3×3 与暴力双重循环在 N=400 下 mismatches=0）
- `PortableMathTest`：**联机可移植性层**专用（N0）。`Math` 的超越函数（`sin/cos/exp/log/pow/hypot/…`）Java 规范**不保证**跨平台逐位一致，只有 `StrictMath` 保证——本项目单机门禁全绿，但换台机器 1–2 ulp 的差异会让 `ShrineSystem`/`TrialSystem` 选到不同方块坐标、直接改写 `mat`，于是确定性锁步联机立刻 desync。本门禁断言三件事：① **清洁性**（`src/core` 下所有非测试源文件不使用非 `StrictMath` 的超越函数）；② **防假绿 A**（扫描到的非测试文件数 ≥ 120，防「扫描器坏了 = 假 PASS」）；③ **防假绿 B**（检测器必须在合成样本上真的报出违规，证明「零命中 = 真没有」而不是「永远返回空」）。豁免 `*Test.java`/`*Bench.java`；`Math.sqrt/floor/ceil/abs/min/max/round` 为精确定义，不在管辖内
- `NetCodecTest`：**联机输入层**专用（N1）。锁步只同步「意图」，所以输入编解码是整条链路的地基——一旦它悄悄丢精度或改顺序，两端会在几秒后分叉，而症状（某只怪卡住）离根因很远。本门禁断言六件事：① **协议稳定**（类型码是显式契约 IDLE=0..BUILD=4，不随枚举重排而变）；② **无损往返**（全部类型 + 全部字段边界值 encode→decode 逐字段相等，且写出不越界——前后哨兵字节不被改写）；③ **拒绝而非截断**（越界值 / 未知类型码 / 重复或降序玩家 id / 长度不符 / 缓冲区截断，一律抛 `IllegalArgumentException`；静默截断会把「输入层 bug」变成「两端悄悄分叉」）；④ **逐字节确定性**（同一逻辑帧 pack 两次字节完全相同）；⑤ **惰性证明**（跑 2 万次编解码后 `world.hashState()` 逐字节不变）；⑥ **防假绿**（正样本对照 + 工作计数器非零，防「循环被优化掉 = 什么都没测」）
- `SnapshotStateTest`：**快照完备性/紧凑性层**专用（N2-0 / N2-1）。回滚要求快照**完整**，而「完整」最怕的不是难写，是**沉默**——加一个字段忘了序列化不会有任何症状，直到某次回滚后世界悄悄走偏，而 `hashState()` 又因故意做窄而看不见。本门禁**与 `core/net/StateCodec` 同源**：`PERSISTED` 直接由 codec 算出（新增字段自动进入持久化 → 零维护、零缺口），门禁只维护 `SKIP_DOC`（**跳过字段 + 理由**，键是**类限定名**如 `World.mat` —— 按纯字段名是全局匹配，会让别类的同名字段被静默跳过）。断言六件事：① **跳过名单必须全部有理由**（有人偷偷把字段加进 codec 的 SKIP → 立刻 FAIL，直到写下理由——**跳过永远是审查过的决定，不可能沉默发生**）；② **无幽灵条目**（每条 SKIP 必须是该类的真实实例字段，字段改名后条目不会永远留着）；③ **跳过数等于记录值**；④ **防假绿**（枚举字段数 ≥ 下限）；⑤ **每个字段要么被持久化、要么有理由**；⑥ **派生字段必须在 SKIP 里**——防的是"快照悄悄膨胀"这类**不会让任何门禁变红**的退化（N2-1 实测：`surfaceCells` 651 KB + `surfaceY` 130 KB + `surfaceTopY` 130 KB 被当成状态写入，占 v5 段 69%/916 KB）。实测：World 58/73 + Player 38/40 持久化，19 个有理由的跳过
- `NetSnapTest`：**紧致快照层**专用（N2-1）。`save/load` 曾把每个被编辑块**整块**落盘（16×112×16×(int+float) = 229 KB/块），实测 22.25 MB。本门禁断言七件事：① **字节预算**（≤1 MB）；② **压缩比**（相对整块窗口落盘 ≥8x，实测 68x）；③ **稀疏性**（窗口差分 ≤5% 窗口格数，实测 0.65%）；④ **结构等价**（`原始基线 + 差分` 逐格复原原世界，用 codec 自己的比较器自校验为空）；⑤ **双一致**（save→load→再演化 120 tick，hash 逐字节一致）；⑥ **防假绿**（差分格数 / 涉及块数 / 窗口被编辑块数均 >0，防扫描器走空）
- `RollbackTest`：**回滚正确性层**专用（N2-2 原地恢复 + N2-3 黄金判据），整套回滚机制唯一的正确性证明。黄金判据：「跑 N → 回滚到 RB → 用**修正输入**跑到 N」 == 「一开始就用修正输入跑到 N」。本门禁比字面判据更狠一层——回滚世界在快照之后**先故意用错误输入跑偏**，再原地恢复，所以它同时证明"恢复能把已经走歪的世界**拽回**快照状态"。断言五件事：① **原地恢复精确**（恢复后 hash 与快照时逐字节一致、tick 回退到位）；② **恢复前确实已跑偏**（防假绿：否则本测试什么都没证明）；③ **回滚 == 直跑**（黄金判据）；④ **拒绝不匹配快照**（seed/尺寸不同必须响亮抛错，不得静默接受）；⑤ **惰性**（取快照本身不改动仿真状态）
- `NetDesyncTest`：**desync 检测层**专用（N3-0）。`hashState()` 是**故意做窄**的 —— 实体层、系统标量、系统实例私有状态都不在里面，联机时在这些字段上分叉它看不见。`netHash()` 与快照**同源**：`hashState() + FNV(StateCodec.encode(world)) + FNV(各系统 encode)`，**编码覆盖什么就检测什么，新增字段自动纳入，不存在第二份名单**。断言六件事：① 同源一致；② **宽于 hashState**（只改一个 hashState 看不见的字段 netHash 必须变）；③ 敏感（改一格方块即变）；④ 可区分种子；⑤ 惰性 + 确定性（调用不改 hashState，重复调用同值）；⑥ 成本预算（实测 21ms，不可每 tick 调用）。⚠️ 测试教训：做"改完再改回"的往返断言必须用**整数**字段 —— 浮点 `x+1f-1f` 不保证回到原值（实测 `0.7f → 0.70000005f`），那不是 bug，恰恰证明它连 1 ulp 都抓得到
- `LockstepTest`：**锁步协议层**专用（N3-1），回环传输 —— **不需要 socket 就能测协议**。断言五件事：① 收敛（两端经有延迟的回环线路跑 40 tick，逐 checkpoint netHash 一致）；② **输入延迟 ≠ 语义**（D=0 与 D=3 跑出同一个最终 netHash —— 延迟只影响"何时发"，不影响"发什么"）；③ **缺帧不推进**（掐断一端的输入帧，另一端必须停在原地 —— 宁可卡住不可走偏，这是锁步的根）；④ 补录历史被拒（对已模拟 tick 补录输入响亮抛错）；⑤ **desync 可检出**（篡改一条输入帧的一个字节，改成"合法但不同"的意图 → 两端都报 desync）。边界：World.player 单数，多玩家意图应用由 InputSink 注入（N4 补）
- `UdpLockTest`：**真实 UDP 传输层**专用（N3-2），测**真操作系统套接字 + 跨 JVM 进程**。断言四件事：① **两端收敛**（真 UDP + 星形中继 + 2 客户端并行加入，N=20 tick 后 netHash 一致、无 desync）；② **三端收敛**（星形中继在 3 人下同样成立，且与两端**同 hash**——人多人少不影响确定性）；③ **晚加入被拒**（人齐后的 HELLO 响亮拒绝，`relay.rejects > 0`）；④ **跨 JVM 子进程一致**（ `--host` 与 `--join` 各起真实 JVM 子进程，FINAL hash 逐字节相同 `d978824b368f0c71` 且 `desync=false`）—— 三种启动形态走的是同一条协议路径。边界：协议层零改动（与 `LoopbackTransport` 实现同一 `Transport` 接口）；TCP 仍未实现（同一接口，会话代码零改即可加）；预测回滚接渲染主循环属 N4/N5
- `PredRollbackTest`：**预测回滚层**专用（N4），GGPO 式客户端预测 + 权威帧到达回滚重演。断言五件事：① **终态==纯锁步**（延迟 D=4 预测回滚终态 `netHash` == 纯锁步（D=0）参照 `netHash`，到达顺序不同但已确认历史演化结果不变）；② **预测真错→真回滚**（`rollbackCount > 0`，非"延迟≠语义"假象）；③ **纯锁步无回滚也收敛**（D=0 参照 `rollbackCount == 0` 且终态一致）；④ **确定性重演**（重演路径终态 == 预测路径终态）；⑤ **基线已缓存**（`baselineRegenCount <= 3`，消除每 tick 204ms 全窗重生成）。黄金判据：手感零延迟，且终点与纯锁步逐字节一致

---

## 2. 架构与目录
```
java/breathing-world/
├── README.md              ← 你正在看
├── PORTING_PLAYBOOK.md    ← 把剩余 81 系统机械移植到 Java 的手册
├── build.bat / build.sh   ← 全量编译
├── run-game.bat / .sh     ← 起 LWJGL 真 3D 游戏
├── run-proof.bat / .sh    ← 出零依赖证明图
├── libs/                  ← 已下载 LWJGL3 + JOML + windows natives（项目自包含）
├── proof/world_proof.png  ← 软件渲染器输出的 MC 地形证明图
└── src/
    ├── core/                        ← 引擎无关的纯 Java 仿真脊柱（零漂移铁律落点）
    │   ├── rng/SeededRNG.java       ← 主种子 + SHA-256 流派生
    │   ├── world/Blocks.java        ← MC 风方块注册表
    │   ├── world/World.java         ← 体素 3D 世界 + 种子地形 + tick 主循环
    │   ├── world/Player.java        ← 玩家回环（P0-1 等价）+ 养成/装备/魂锻/战技（C3）
    │   ├── world/Weapons.java       ← 武器 ↔ 战技形态原型表（C3：LUNGE/CLEAVE/SHOT）
    │   ├── systems/System.java      ← 涌现系统接口
    │   ├── systems/ProsperitySystem.java   ← 签名：世界回响/繁荣链（P0-2 等价）
    │   ├── systems/FireSpreadSystem.java   ← 涌现样板
    │   ├── systems/TreeGrowthSystem.java   ← 涌现样板
    │   ├── systems/WaterFlowSystem.java    ← 涌现样板
    │   ├── systems/NpcSystem.java          ← NPC 社会层入口（批次0）
    │   ├── systems/CivilizationSystem.java  ← 文明深度（批次2）
    │   ├── systems/IndividualSystem.java    ← 个体成长（批次3）
    │   ├── systems/PolitySystem.java        ← 民政经济（批次4）
    │   ├── systems/CalamitySystem.java      ← 灾害触发层（批次4）
    │   ├── systems/TrialSystem.java         ← 试炼/世界之心（C2）
    │   └── sim/MenuModel.java                ← 菜单/暂停/设置 纯逻辑状态机（C5，零 GL 零世界写入）
    │   └── world/DayCycle.java                ← 昼夜/天空 纯模型（D3：天色/日照/环境光/雾/星空，零状态零 RNG）
    │   └── sim/Simulation.java              ← 装配
    │   └── sim/DeterminismTest.java         ← 门禁 1  仿真指纹
    │   └── sim/ZeroDriftTest.java           ← 门禁 2  sim/render 隔离
    │   └── sim/PhysicsTest.java             ← 门禁 3  玩家行走物理（落地/跳/撞墙）
    │   └── sim/StreamingTest.java           ← 门禁 4  无限世界流式（P3）
    │   └── sim/NpcDeterminismTest.java      ← 门禁 5  NPC
    │   └── sim/SocialDeterminismTest.java   ← 门禁 6  社会核心
    │   └── sim/StorytellerDeterminismTest.java ← 门禁 7 说书人/村志
    │   └── sim/CivilizationDeterminismTest.java ← 门禁 8 文明深度
    │   └── sim/IndividualDeterminismTest.java   ← 门禁 9 个体成长
    │   └── sim/PolityDeterminismTest.java       ← 门禁 10 民政/灾害
    │   └── sim/TrialDeterminismTest.java        ← 门禁 11 试炼（含可达性）
    │   └── sim/WeaponArtTest.java               ← 门禁 12 玩家动作层（战技行为断言）
    │   └── sim/MenuTest.java                    ← 门禁 13 UI/流程层（菜单 + 暂停透明性）
    │   ├── sim/DayNightTest.java                ← 门禁 14 天空/昼夜层（连续性 + 射线基 + 纯函数零漂移）
    │   ├── sim/AudioTest.java                   ← 门禁 15 音频层（纯函数 + 包络 + 声部池 + 混音契约）
    │   ├── sim/MatterDeterminismTest.java       ← 门禁 16 抽象字段层（可达性 + 指纹隔离 + 零 RNG）
    │   ├── sim/MegalithDeterminismTest.java     ← 门禁 17 巨构神殿（材化 + 可达性 + 小世界豁免）
    │   ├── sim/SaveLoadTest.java                ← 门禁 18 存读档（0-tick + 300-tick 演化一致）
    │   ├── sim/FxTest.java                      ← 内容/特效/小地图/成就（纯读断言）
    │   ├── sim/ContentTest.java                 ← 内容层（计数门禁 + 引用完整性）
    │   ├── sim/RecipeTest.java                  ← 冶炼产业链显式契约
    │   ├── sim/SubsystemTest.java               ← 空转参数落地（7 参数各有牙 + 出厂 no-op + 负例）
    │   ├── sim/StreamChunkTest.java             ← M3 分帧等价 / INDEX 分片 / 索引惰性 / 邻域去 O(N²)
    │   ├── sim/PortableMathTest.java            ← N0 联机可移植性（禁非 StrictMath 超越函数）
    │   ├── sim/NetCodecTest.java                ← N1 输入编解码（6B 协议 / 无损往返 / 拒绝而非截断）
    │   ├── sim/SnapshotStateTest.java           ← N2-0 快照完备性（穷尽分类 / SKIP 必带理由）
    │   ├── sim/NetSnapTest.java                 ← N2-1 紧致快照（字节预算 / 稀疏性 / 压缩比）
    │   ├── sim/RollbackTest.java                ← N2-2/N2-3 原地恢复 + 回滚黄金判据
    │   ├── sim/NetDesyncTest.java               ← N3-0 desync 宽哈希检测（与快照同源）
    │   ├── sim/LockstepTest.java                 ← N3-1 锁步协议层（回环可测）
    │   ├── sim/UdpLockTest.java                 ← N3-2 真实 UDP + 跨 JVM 逐字节一致
    │   ├── sim/PredRollbackTest.java            ← N4 预测回滚（GGPO 式）
    │   └── sim/NetIntegTest.java                ← N5 联机会话接入主循环
    │   └── world/Matter.java                    ← 抽象字段容器（物相/熵/聚变/深海；不进 hashState）
    │   └── systems/MatterSystem.java            ← 抽象字段驱动（MATERIAL_WORKS 默认关，不消费 rng）
    ├── render/software/SoftwareRenderer.java    ← 零依赖 PNG 出图（沙箱可跑）
    ├── render/lwjgl/Game.java                   ← LWJGL3 真 3D GPU 渲染器（主循环 / HUD / 输入 / 菜单）
    ├── render/lwjgl/{Chunk,MeshBuilder,TextureAtlas,VoxelShadow,VoxelVariety}.java ← 网格 / 图集 / 占用 / 变体
    ├── render/lwjgl/{MsdfFont,MsdfGen,CjkFont,HudText}.java ← 中文与英文上屏（零字体资产）
    ├── render/audio/AudioOut.java               ← javax.sound 输出层（C4：程序化音频，零新增依赖）
    └── core/audio/{Sfx,AudioSynth,AudioMixer}.java ← C4 音效参数表 + 纯函数合成 + 声部池混音
```

> 完整文件清单以 `find src -name '*.java'` 为准（**283 个文件 / 约 2.4 万行**）。上面只列了结构与有代表性的几类。

---

## 3. 零漂移铁律在 Java 版的落点

| Python 纪律 | Java 落点 |
| --- | --- |
| `random.Random(seed)` 主种子 | `core.rng.SeededRNG(long seed)` + xorshift64 |
| `spawn(f"eco:{tick}")` SHA-256 流派生 | `world.simStream(name) = rng.deriveStream(name + ":" + tick)` |
| 事件不含 ts 才能进指纹 | `world.events` 列表无 ts；`world.hashState()` 排除 events |
| 仿真随机 ≠ 演出随机 | `world.rng`（仿真）vs `world.fxRng`（演出独立种子），互不读取 |
| 玩家回环不被跳过 | `World.tick()` 始终调 `player.tick(this)`（死亡也驱动重生） |

**两个门禁就是这两个纪律的实证**：`DeterminismTest` 验证指纹一致；`ZeroDriftTest` 验证渲染乱用 fxRng 不影响 sim 指纹。

---

## 4. 美术：MC 体素画风

调色板与 `play/web3d.html` 对齐（见 `production/art-bible.md` v1.1 MC 路线）：明亮饱和纯色 + 经典蓝天。块 ID 不变，只动颜色映射 → 零确定性风险。

---

## 5. 现状与真实缺口（诚实交代）

> ⚠️ **本节 2026-09-30 重写。** 此前停留在 2026-09-08 的状态（写"81 系统已移植 34 个 / 音效 UI 存档尚未移植"），
> **严重低估了当前进度**——新协作者照旧版读会以为项目只做了 40%。
> 下列数字均可复现：`BW_KEEP_OUT=1 python build_runner.py` + 读 `game_diag.log` 的 `[SYS]` / `[CONTENT]` 行。

### 5.1 已经做完的（可直接用）

| 层 | 状态 | 证据 |
|---|---|---|
| 确定性仿真内核 | ✅ **99 个系统 / 10 域全部注册**（`disabled=0`） | `game_diag.log` → `[SYS] systems=99` |
| 零漂移门禁 | ✅ **82 道门禁 + 5 段编译 + 审计 = 88 处 `*_EXIT` 全 0** | `build_report.txt` |
| 静态不变量审计 | ✅ C1~C13 `FAIL 0 / WARN 0`（26 项） | `python audit_invariants.py` |
| 渲染管线 | ✅ ACES 色调映射 / 大气散射雾 / 体积光轴 god-ray / 大尺度 AO / Bloom / 阴影 / 昼夜天气日月星空 / 块光平滑 / MSDF 中英文字 | `SHADERCHECK PASS (15 programs)` |
| 流式大世界 | ✅ 无限滑窗 + 平移只重建进入条带 + 网格复用（`MeshShiftCheck` 逐 float 等价） | 单帧最坏 508ms → 11.5ms |
| 增量光照 | ✅ 与全量**逐字节等价**（`LIGHTINCREMENTAL`，覆盖 8 种平移） | 同上 |
| 动作 / 战斗 | ✅ 连招取消窗 / 完美闪避 / 招架 / 处决 / 3 种战技 / 武器切换 | `ART` / `COMBAT` 门禁 |
| 养成 | ✅ STR/DEX/VIT/END 属性 / 装备 / 魂锻 / 升级 / 魂掉落回收 | `CHARACTER` / `INVENTORY` |
| 内容平台 | ✅ **316 个 JSON**（物品 133 / 材料 131 / 规则 8 / 捏脸 7 / 反应 7 / 预设 4 / 科技 4 / 技能 3 / MOD 支持） | `game_diag.log` → `[CONTENT] total=317` |
| 菜单 / UI | ✅ ESC 菜单 / 设置 / 键位页 / 捏脸 / 小地图 / 背包 / 村庄村志 / 中文上屏 | `MENU` / `UILAYOUT` / `ARTUI` |
| 音频 | ✅ 23 种程序化合成音效（**零新增依赖**，`javax.sound`） | `AUDIO` 门禁 |
| 存档 | ✅ 自定义二进制 + 反射式快照 + **字段数不符即拒绝并上屏 banner**（不再静默丢进度） | `SAVE` / `SNAPSTATE` |
| 联机 | ✅ 确定性锁步 + 稀疏差分快照（22.25 MB → 317 KB）+ 预测回滚（GGPO 式）+ 真实 UDP + 三启动形态 | `NETLOCK` / `UDPLOCK` / `PREDROLLBACK` |

### 5.2 真实缺口（**新协作者最该知道的**）

| # | 缺口 | 现状 | 严重度 |
|---|---|---|---|
| 1 | **内容量远小于引擎容量** | `quests=1` / `beasts=1` / `skills=3` / `techs=4` / `buffs=1` / **音乐 0**。而代码里已有 5 种敌兵原型 + 1 个 Boss（WARDEN）。**管道远大于货量。** | 🔴 最高 |
| 2 | **NPC 社会层有名无实** | `Decision.choose()` 只产出 5 个标签（FLEE/DRINK/EAT/WANDER/IDLE）；`GATHER/TRADE/CRAFT/STATION/MEDITATE` 是**无生产者、无消费者**的常量 ⇒ NPC 不劳动 / 不交易 / 不合成。 | 🔴 最高 |
| 3 | **冷启动 13.5 秒只有 10~30 fps** | 稳态 144 fps 没问题，但进场要等 ~13.5s；实测累计 `built=1484` 块而窗口只有 **100 块** ⇒ 疑似重复烘焙（**根因未定位，先测量再改**）。 | 🟠 高 |
| 4 | **存档旧档读不回** | `StateCodec` 按字段**位置**编解码，字段增减即失效。现行策略是"拒绝加载 + banner"，但**自描述格式（写字段名 + 按名匹配）未做**。 | 🟠 中 |
| 5 | **联机缺外壳** | 协议 / 快照 / 回滚全有，但无房间大厅 UI、无重连、无真人实测。 | 🟠 中（按需） |
| 6 | **音频缺一半** | 有 23 种 SFX，**无背景音乐 / 无 3D 定位**。 | 🟠 中 |
| 7 | **MSAA / A2C / 自动曝光未实机验证** | 本机 `glGetInteger(GL_SAMPLES)` 读回 0（请求 4 得 0），只有真机能验；旋钮 `-Dbw.msaa=4` / `-Dbw.show=1` 已备。 | 🟡 低 |
| 8 | **`Game.java` 8,863 行单点巨文件** | 主循环 / 渲染 / HUD / 输入 / 菜单 / 存档 / 联机接线都在一个类里。 | 🟡 低（不伤玩家） |

### 5.3 必须说清的边界

- **世界是"开放系统"**：96³ / 20k tick 实测质量账 **+4.18%**（源 ASH +178k / 汇 STONE −228k）。
  **这是设计选择，不是 bug**——涌现需要有"来源与汇"，但它不是守恒系统。三处曾自称"守恒"的注释已改正。
- **75% 胜率基线属于 JS 版**：那是 `play/web3d.html`（Three.js 单文件原型）的 CI 专属硬约束（9 胜 3 负）。
  Java 版是独立版本，**不沿用那个基线**——避免双核基线互相牵连。
- **构建脚本 RC 恒为 0**：`build_runner.py` 里没有任何 `sys.exit`，红灯只出现在文本里。
  **CI 若只看退出码会全绿放行**（见 §1 警告）。
- **`docs/OPEN_ITEMS.md` 是待办权威源**（约 11 万字战史），但它头部的状态锚点可能滞后于代码 —— **以 `build_report.txt` 为准**。
- **性能测量的口径**：稳态 144 fps 是**玩家静止**时；`MESHSHIFT` 门禁证明的是**网格等价**，不是**帧率**。二者不能互相代替。

---

## 6. 你能立刻看到的成果

打开 `proof/world_proof.png`（已生成）——零依赖软件光栅器出的等距 MC 地形：
- 绿色草顶、棕色泥侧、灰色石层、底部水/沙、稀疏树
- 等距投影、真 3D 体素、无 GPU 依赖

跑 `run-game.bat` 看到的是透视真 3D（同种子），可行走 / 跳跃 / 挖放方块，自由探索世界。

> **基线指纹演进（2026-09-11）**：（2026-09-11 地形可玩性调参后基线指纹有意演进：振幅减半 + 尺度 18/6，修复"卡在冲沟"）。旧值 ad9e7b31ed47ec45 / c9e1d98362321283 / e722b7f80ffc8317 / 0a8533fd5eb2ec6b 为演进前记录（event_log 历史条目保留）。门禁为同种子自洽断言，18 道仍全绿。
