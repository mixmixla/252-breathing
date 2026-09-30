# 世界回响 / 村庄记忆（Narrative Echo）叙事设计文档

> 设计任务：`NARRATIVE-ECHO`（叙事专家 deliverable，P3，gate NONE）
> 范围：把 `world_echo` / `village_memory` 叙事织进 **NPC 对话** 与 **世界回响事件**。
> 硬约束：不触碰 `hashState()` / 模拟逻辑 / 4 个门禁测试 / `play/web3d.html` / `.js` / `orchestration/*`。
> 本文件为纯设计 + 一个只读派生 helper（已编译通过，零漂移安全）。

---

## 0. 设计立场（对齐 Python 概念）

Python 侧的"世界活着（G2）"闭环是：

```
玩家击退来犯之敌 (combat.repel)
   → ProsperitySystem.on_repel：score++，过阈解锁隐藏蓝图 (world.skills.add)
   → 记 world_echo.unlock 事件 + 落 VillageMemory(RAG，可检索续篇)
   → 村志(storyteller) 与 NPC 对话(dialogue.use_memory) 回放"被记得的守护"
```

Java 侧已落地 `prosperity / skills / villageMemory / events` 与 `Player` 的 `combat.repel`
日志（见 `Player.java:38`），链路**已自洽**。本设计只定义"这些记忆/状态该如何被读出并讲给人听"，
不新增任何会改变仿真状态的代码。

---

## 1. 触发 `villageMemory` 条目的来源（canonical triggers）

Java 当前已记录记忆的系统（按触发源归类）：

| 触发源 | 系统 | 阈值 / 条件 | 已记录记忆模板（现状） |
|-------|------|-----------|---------------------|
| 玩家击退野兽 | `Player.combat.repel` → `ProsperitySystem` | 每 repel：prosperity++ | `繁荣+：木栅蓝图已解锁`（≥1）/ `繁荣++：望楼蓝图已解锁`（≥3） |
| 繁荣点亮地标 | `BeaconSystem` | prosperity≥6，低概率 | `繁荣灯塔：村庄中心亮起 LAMP` |
| 草原兽群 | `HerdSystem` | 连片 GRASS≥6，1% 概率 | `兽群足迹: x=,z= 草原繁盛`（并 prosperity++） |
| 聚落扩张 | `RuinsSystem` | prosperity≥4，1% 概率 | `聚落扩张：新暖屋落成于 (cx,cz)` |
| 贸易商队 | `TradeCaravanSystem` | prosperity≥2，2% 概率 | `caravan:x,z 繁荣=prosperity` |

> 关键设计点：所有"写入记忆"的动作都发生在**系统 update 内部**，且记完即止（幂等由
> `hasSkill` + 低概率双重保证）。叙事层**只读**这些已落定的字符串，绝不反向驱动仿真。

### 1.1 繁荣等级（叙事用，派生自 prosperity，不进仿真）

对齐 Python `ProsperitySystem.level()`：`0 荒村 / 1 初安 / 2 安稳 / 3 昌盛`。
Java 暂无该字段，但可由 `prosperity` 阈值**只读派生**（见 §4 helper），用于决定 NPC 语气与对话口径。

---

## 2. 叙事如何"浮现"

### (a) NPC 对话层（templates that read current world state）

NPC 在 Java 侧尚未实现（见 §5 差距），但其对话契约应如下：

1. **忆往（use_memory）**：NPC 被攀谈时，若 `villageMemory` 非空，按"今日相关度"拣最近/最匹配一条，
   以 `（神情一肃）说起旧事——{mem_line}` 织进回话，让玩家感到"我的守护被村人记得"。
2. **语气随繁荣档**：`level()==0`→"戒备/疏离"，`>=1`→"亲近/有盼头"，`>=3`→"敬重/以你为村之守护"。
3. **话题感知**：对话含"敌人/危险"→追忆某次击退；含"建造/蓝图"→追忆木栅/望楼解锁；
   含"集市"→追忆商队抵达。
4. **确定性轮转**：同一 (npcId, tick, mood) 两次回话逐字一致（对齐 Python `_stable_hash`），
   换时辰/情绪才换措辞——涌现门禁 r1==r2 才能成立。

### (b) 世界回响事件层（log 总线 / 繁荣链）

- **事件总线**（`World.log` / `World.events`）是回响的"发生侧"：combat.repel、caravan.arrive、
  beacon/ruins/herd 的 unlock 事件。**调试/渲染只读**，不进 `hashState()`。
- **繁荣链可视化**（渲染层职责，非本任务）：prosperity 驱动径向辉光、解锁脉冲、结构辉光；
  这些属演出随机（`fxRng`），与仿真随机（`rng`）隔离（见 `World` 顶部纪律注释）。
- **村志**（`echoNarrative()` 派生的"近事"串）可每 N tick 由渲染/UI 层取一次，作为"今日回响"浮层。

---

## 3. 6–10 条具体叙事模板（绑定特定世界状态）

> `{p}` = prosperity，`{lvl}` = 繁荣等级名，`{mem}` = 最近一条 villageMemory，
> `{npc}` = NPC 名，`{who}` = 交谈者名。均为**只读派生**，不写任何状态。

1. **荒村初始（p=0）**
   `村庄尚在沉睡，尚无回响落进记忆。`（helper 空状态默认句）

2. **首次击退（p=1, 解锁木栅）**
   `{npc}：你那日把来犯之敌挡在村口，如今环村的木栅，是照着那场仗的画法立的。`

3. **望楼落成（p=3, 解锁望楼）**
   `{npc}：登高才知你守了我们三次。那座望楼，村里人叫它"守夜台"。`

4. **商队抵达（p>=2, 有 caravan 记忆）**
   `{npc}：自打商队肯来，簸箩里的货色多了。都说——是有人替咱把野地镇住了。`

5. **草原兽群（有 herd 记忆）**
   `{npc}：（指着远处）草场上又见兽群蹄印了。早些年哪敢想，如今它们肯在咱地界歇脚。`

6. **聚落扩张（p>=4, 有 ruins 记忆）**
   `{npc}：村尾那间新暖屋，是今年落成的。炉火一生，外头的人也敢往咱这儿凑了。`

7. **繁荣灯塔（p>=6, 有 beacon 记忆）**
   `{npc}：夜里村心那盏灯，是谁点起来的？村里都说是你护出来的光。`

8. **昌盛敬重（lvl=昌盛）**
   `{npc}：{who}，村中老小都念你的好。你来的那些年，是从荒到安的年。`

9. **村志浮层（通用"近事"）**
   `村史已记 {N} 段回响（你击退来犯之敌 {p} 次，村民记得你的守护）。近事：{mem}`

10. **久无战事（p>=1 但无新记忆多 tick）**
    `{npc}：仗是歇了，可那几场你挡下的祸，村口石碑上咱没忘刻。`

---

## 4. 只读派生 helper（已落地，编译通过）

签名（位于 `World.java`，世界回响接口区）：

```java
/**
 * 世界回响（只读派生）：根据当前世界状态拼出一段"村庄记忆续写"叙述。
 * 纯派生——只读 prosperity / skills / villageMemory，绝不修改任何仿真状态，
 * 不影响 hashState() 确定性指纹。
 */
public String echoNarrative()
```

- 只读：`prosperity`、`villageMemory.size()`、`villageMemory.get(...)`。
- 不写：mat / mass / rng / skills / prosperity / 任何 `hashState()` 纳入的字段。
- 编译校验：`javac -encoding UTF-8 -d out_ne src/core/rng/SeededRNG.java
  src/core/world/*.java src/core/systems/*.java src/core/sim/*.java` → `COMPILE_OK`。

---

## 5. Python 概念 vs Java 现状 差距（GAP）

1. **无 NPC / 对话系统**：Java `World` 只有 `Player`，没有 `NPC`、`social.affinity`、`dialogue`。
   本文 §2(a) 的"对话忆往/语气分档"在 Java 侧**尚无承载对象**，需后续建 NPC 层。
2. **无 RAG 检索（continuity_lines）**：Python `village_memory` 是带评分检索的可检索记忆，
   Java `villageMemory` 仅是 `List<String>`（顺序追加、无去重检索）。§3 的"按相关度拣旧事"
   目前只能退化为"取最近一条"。
3. **无 culture 孵化（M12）**：Python 从记忆沉淀节日/传说/禁忌/绰号；Java 侧未实现，
   故村志里的"软文化"段落暂无来源。
4. **无 level() 字段**：繁荣等级名（荒村/初安/安稳/昌盛）需调用方自行按阈值派生，
   已借 `echoNarrative` 隐含处理，但未独立暴露。
5. **记忆去重缺失**：Python 写入前 `{text}` 去重；Java 各系统未做去重，长程运行可能重复记同义句
   （不影响仿真，但叙事会重复）——建议未来在 `recordMemory` 调用点加幂等判断（不改 `hashState`）。

> 以上差距均**不阻塞本任务**：本设计为对话层与事件层给出契约与模板，helper 已安全落地；
> 真正的 NPC/文化/RAG 实现属后续任务范畴，且须各自守住零漂移纪律。
