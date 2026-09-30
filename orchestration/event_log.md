# Orchestration Event Log

[IMPL-COMBAT] gameplay-programmer target=src/core/systems/BeastSystem.java,src/core/world/Player.java(ATTACK),src/core/sim/Simulation.java,src/render/lwjgl/Game.java(F键) result=ok gate=PASS note=DETERMINISM hashA=ad9e7b31ed47ec45 hashB=ad9e7b31ed47ec45(a==b PASS); ZERO-DRIFT simHash=10ad281a0bf669ce refHash=10ad281a0bf669ce(PASS); PHYSICS PASS; STREAMING hash=e722b7f80ffc8317(PASS). 攻击键=F(玩家ATTACK命中最近敌兵,beast.hp-=10,击杀移除并log combat/repel触发繁荣链); REPEL分支原样保留。敌兵为实体不写网格,随机只走w.simStream,零漂移安全。

[PROG-BUILD] gameplay-programmer target=src/core/world/Player.java result=ok gate=PASS note=属性 str/dex/vit/end + 装备槽(weapon/armor/charm) + 装备目录常量(WEAPONS/ARMORS/CHARMS)。recompute() 合成 maxHp=20+vit*5+(lv-1)*5+hpBonus、atk=8+str*2+(lv-1)*1+atkBonus、maxStamina=80+end*10+stamBonus、artCdMax。investSoul(attr) 成本 30+pointsSpent*10，消费 souls 提 STR/VIT/END/DEX。onKill() 保留 souls+8/xp+12/killCount++ + 确定性掉落自动晋级更好装备(写 lootLog+log)。souls 真实消费出口成立。玩家实体不进 hashState，零漂移安全。四门禁指纹不变: DETERMINISM ad9e7b31 / ZERO-DRIFT c9e1d983 / PHYSICS PASS / STREAMING e722b7f8。

[ZELDA-ABILITY] systems-designer target=src/core/world/World.java(Shrine)+src/core/systems/ShrineSystem.java+src/core/sim/Simulation.java+src/core/world/Player.java(physicsTick/dodgeRoll/bomb) result=ok gate=PASS note=World.Shrine 实体(public 构造器,跨包可访问); ShrineSystem tick==1 用 w.simStream("shrine:place") 确定性放 3 祭坛(GLIDE/DASH/BOMB); 玩家3格内未觉醒→grantAbility+souls+20+prosperity+1+recordMemory; Simulation 末位注册保固定迭代序。GLIDE 缓降/DASH 翻滚/BOMB 炸方 均以 abilities.contains 门控。simStream 非消耗派生流→主 rng 状态不变→DETERMINISM 指纹不变 ad9e7b31。(ZERO-DRIFT 绝对值 10ad281a→c9e1d983 因玩家在 ZeroDrift 场景游走觉醒祭坛改变 villageMemory/prosperity，simHash==refHash 仍成立，非回归)

[ART-PROG] creative/technical target=src/render/lwjgl/Chunk.java(9-float+法线)+src/render/lwjgl/Game.java(worldShader 方向光/两遍描边/Particle) result=ok gate=PASS(code) note=Chunk stride 6→9 输出法线,SHADE 统一 1.0; Game worldShader FS lambert l=0.55+0.45*max(dot(N,L),0) uLightDir=(0.45,1.0,0.35); drawEntities Pass1 反相外壳描边(POLYGON_OFFSET_FILL)+Pass2 本体(角色/敌兵/祭坛光柱/粒子); Particle(spawnSoulMotes/spawnDust/spawnSparks/updateParticles)用 fxRng。render 层编译通过; 四门禁只跑 World.tick 不受影响。待本机 run-game.bat GL 目视验收。

[BUILD-RENDER] technical target=build.bat(managed JDK8 corretto-1.8.0_502 全量重编) result=ok note=core+systems+sim EXIT=0, software EXIT=0, lwjgl(Game+Chunk+Font) EXIT=0; 四门禁全绿(指纹同上)。render 层零编译错误，仿真侧改动零漂移守恒。

[ART-DIRECTOR-2] art-director/technical target=docs/ART_BIBLE.md + src/core/world/Blocks.java(wind 字段) + src/render/lwjgl/Chunk.java(10-float+vertexAO+wind) + src/render/lwjgl/Game.java(worldShader 雾/天空/接触阴影/drawFrame 重排) result=ok gate=PASS(code,四门禁全绿) note=先立视觉圣经 ART_BIBLE.md(视觉支柱/调色板/光照/材质/三项扩展规格/零漂移铁律)。Blocks.Block 加 8 参字段 wind；LEAF/FLOWER 标记 wind=true(field-only,无 API break,无 hashState 影响)。Chunk 顶点 9→10 float(加 aWind)；vertexAO 按 +n 层相邻两侧+对角共 3 方块实心度算 4 级遮蔽 T=[1.0,0.80,0.62,0.42] 烘焙进顶点色(不改拓扑,零漂移安全)；wind 顶点正弦偏移(x±0.09/z±0.06/y±0.02)。Game: worldShader FS 加距离雾(密度 0.012,雾色=地平线)；initSkyShader 全屏渐变天空(天顶(0.30,0.55,0.85)→地平线(0.78,0.86,0.92))；initShadowShader 接触阴影软盘(径向 α 衰减,α_max 0.35,半径 0.5/0.42,glDepthMask(false)+深度测试)；drawFrame 重排 天空→世界→接触阴影→实体→HUD；drawEntities 改 flat shader(规避 entVAO 无法线致 normalize(0)=NaN)；addShadow + time 渲染层累加器。四门禁全绿: DETERMINISM ad9e7b31ed47ec45 / ZERO-DRIFT c9e1d98362321283 / PHYSICS PASS / STREAMING e722b7f80ffc8317。纯渲染层改动零漂移,待本机 run-game.bat GL 目视验收。

[CD-PILLARS] creative-director target=docs/CD-PILLARS.md + docs/ART_BIBLE.md(从属声明) + orchestration/blackboard.json(creative_pillars 行) result=ok note=用户裁决「保留现有 4 支柱」并落正式创意方向文档。CD-PILLARS.md 确立 4 创意核心=世界活着(P1,↔G2)/手感(P2,↔G1)/探索回报(P3,↔G3)/成长可见(P4,↔G3)，每条带可证伪设计测试+内部张力+部门约束；厘清三层(创意支柱/交付闸门G1-G5/美术执行层)；Art Bible 4 视觉支柱降为 P1 执行层(from属声明已加)；blackboard 加 creative_pillars 行。MDA 排序 Discovery>Sensation>Challenge>Expression；SDT 对齐 Autonomy/Competence/Relatedness；Ludonarrative Consonance 铁律(村志须被玩家看见)。此为唯一身份真理源,部门冲突以此裁决。

[NPC-SOC-START] orchestrator target=src/core/agent/*(PLANNED) + docs/PORTING_GAP.md(PLANNED) result=dispatched note=用户裁决「先建 NPC 社会层地基再移植原始概念系统」(方向 A)。审计结论: Java 81 系统为全新体素涌现集(非 Python 原 80 概念系统); 原 systems/(civilization/diplomacy/religion/genetics/quantum/relativity/emotion/family/...) 及 agents/(mind/social/body/npc/decision) 均未移植, 皆依赖 Java 缺失的 NPC 社会层。派发 Agent1 重建 Java NPC 地基(Body/Mind/Social/Npc/Decision 纯 sim 类, World.npcs 豁免 hashState, 守四门禁), Agent2 产出 PORTING_GAP.md 差距矩阵+路线图。编排层 scheduler.py 从未落地, 改由 Agent 工具手动派发 K=3。

[NPC-SOC-FOUND] orchestrator target=src/core/agent/Body.java,Mind.java,Social.java,Npc.java,Decision.java + src/core/world/World.java(npcs 字段+spawnNpc) result=ok gate=PASS(verified 2026-09-10) note=地基实测已存在且编译通过(CORE/SOFT/LWJGL 全 EXIT=0)。5 类镜像 Python agents/(body/mind/social/npc/decision)，纯确定性 sim 类：Body(hp/hunger/thirst/stamina/comfort/rates + advance/applyEnvironment/applyStarvation)、Mind(emotion fear/anger/trust + memory/grudge/goals + remember/decay/seeDanger/hurt/tradeSuccess)、Social(affinity 映射 + longTerm 恩仇事件 + drift/decay/updateMood/setAdjacency)、Npc(聚合+decide)、Decision(GOAP-lite choose，WANDER 仅走传入 rng)。World.npcs 字段+spawnNpc 已预埋，实体豁免 hashState。四门禁指纹不变: DETERMINISM ad9e7b31ed47ec45 / ZERO-DRIFT c9e1d98362321283 / PHYSICS PASS / STREAMING e722b7f80ffc8317。地基阶段不接入 World.tick，零漂移安全。

[PORTING-GAP] systems-designer target=docs/PORTING_GAP.md result=ok note=产出 原系统→Java 移植差距矩阵。结论: 两个"81"不同批——Python systems/ 91 概念系统(社会模拟引擎)+agents/ 6 社会认知层 vs Java 81 体素涌现系统。真正对应仅 ~13(多为简化版: prosperity/biodiversity/symbiosis/swarm/climate/farming/trade/civilization拆分/combat简化/ecology拆分/terrain/village_memory部分/disasters部分)；~52 依赖 NPC 社会层(NEEDS-NPC: family/culture/emotion/religion/tech/diplomacy/dialogue/magic/...)，地基已建待接入主循环；~28 纯抽象 DOES-NOT-FIT(quantum/relativity/galaxy/orbital/timewarp/dimension/...)，建议跳过或抽象世界状态字段。路线图分 批次0 NPC接入主循环 / 批次1 社会核心 / 批次2 文明深度 / 批次3 个体成长 / 批次4 打磨已覆盖 / 批次5 抽象字段；每批末四门禁闸控。

[NPC-SOC-WIRE] systems-designer/technical target=src/core/systems/NpcSystem.java + src/core/sim/Simulation.java(register) + src/core/sim/NpcDeterminismTest.java result=ok gate=PASS(code,四门禁+第5门全绿) note=批次0落地: NpcSystem 注册进 registerDefaultSystems 末位(固定序); 首tick(tick==1且npcs空)经 simStream("npc:village") 确定性生成5人聚落( farmer/crafter/trader/herbalist/guard, 围绕世界中心,贴地表,谨慎度由rng派生); 每tick驱动 每个存活NPC 的 body.advance/mind.update/social.decay/decision.choose + 执行意图(EAT/DRINK/WANDER纯内部态,不写mat)。倒序遍历移除死亡NPC。纪律: 随机只走 world.simStream(deriveStream 不推进主rng.state, 见SeededRNG.deriveStream 用seed非state); 不读fxRng; 不写mat/mass/prosperity/skills/villageMemory/builtMass → 四门禁指纹与无NPC基线逐字节一致。实测: DETERMINISM ad9e7b31ed47ec45 / ZERO-DRIFT c9e1d98362321283 / PHYSICS PASS / STREAMING e722b7f80ffc8317 全不变; NpcDeterminismTest hashA==hashB(734e3abaa4bd2167) npcCount=5 内部态(饥饿/口渴/情绪/坐标)两遍签名一致。解锁全部 ~52 NEEDS-NPC 系统移植(批次1起).

[NPC-SOC-PORT-B1] systems-designer+art/UI target=src/core/agent/VillageSocial.java + src/core/agent/Dialogue.java + src/core/systems/SocialSystem.java + src/core/sim/SocialDeterminismTest.java + src/render/lwjgl/Game.java + src/core/systems/NpcSystem.java(mutual) result=ok gate=PASS(五门禁全绿) note=批次1 社会核心落地。移植 Python family.py/emotion.py/norms.py/dialogue.py 的确定性路径为 Java: VillageSocial(家族注册表+村庄情绪 mood/stress/connected/trauma/crash+规范 trust/cooperation/violations/norm/sanctions, 实体级开放标量→不进 hashState); SocialSystem(注册进 registerDefaultSystems 末位: 邻里半径3内升温 setAdjacency + 情绪状态机 updateMood + 村庄情绪每8tick + 规范每10tick + 家族结亲/生育, 全确定性无 rng, 只改 NPC 内部态+events 不写 mat/prosperity/skills/villageMemory); Dialogue(persona 由 industrious/cautious/greedy 推导+tone+mood/饥/累/亲疏分档规则回话+话题感知+villageMemory 忆往+ASCII 性格/情绪标签); NpcSystem 漫步约束村心半径8内(使邻里日久生情)。渲染(Game.java): 村民职业着色人形+头顶情绪色标(语言无关)+接触阴影+靠近提示+T 键交谈(回话落 dialogue.log UTF-8; GL 字体仅 ASCII 故中文回话暂不上屏)。门禁实测: 编译 CORE/SOFT/LWJGL EXIT=0; DETERMINISM ad9e7b31ed47ec45(与接入前逐字节一致,证明社会层不写指纹字段) / ZERO-DRIFT c9e1d98362321283 / PHYSICS PASS / STREAMING e722b7f80ffc8317; NPC-DETERMINISM hashA==hashB n=8(5村民+3后代,证明婚育链工作); SOCIAL-DETERMINISM hashA==hashB(f90f8774380e755c) npcs=16 families=4 births=11 mood=0.405 stress=0.55 conn=0.527 trust=0.424 coop=0.845 norm=true。校准: Java 无 Python trade/learning/diplomacy 信号源, 情绪/规范改用社会层信号合成并重调权重(避免 mood/connected 顶极值、trust 被 beast 压力压死)。注入安全: 渲染层交谈写 npc/dialogue 事件, ProsperitySystem 只消费 combat/repel, 且 events 不进指纹→渲染层注入对仿真零影响。待办: 批次1 余项 storyteller/村志消费端; 批次2+ 见 docs/PORTING_GAP.md。

[NPC-SOC-PORT-B1-CLOSE] systems-designer+ui target=src/core/world/Chronicle.java + src/core/systems/StorytellerSystem.java + src/core/world/World.java(chronicle 字段) + src/core/sim/Simulation.java(register) + src/core/sim/StorytellerDeterminismTest.java + src/core/agent/Dialogue.java(引用村志) + src/render/lwjgl/Game.java(HUD+chronicle.log) result=ok gate=PASS(四门禁+第7门全绿) note=批次1 余项落地: Chronicle(村志档案库: 事件→episode[marry/birth/repel/awaken/norm/despair] + 字符级 bigram Jaccard 检索 + 村志叙述生成, 不进 hashState); StorytellerSystem 注册进 registerDefaultSystems 末位, 每 tick absorb(单调水印)+周期 STORY_INTERVAL=24 出当日村志, 不调 w.log 防回馈环; World.chronicle 字段只读 events/npcs/social; 消费端 Game HUD 常驻 ASCII 摘要 CHRONICLE D..REPEL/MARRY/BIRTH + LAST 条目, chronicle.log 落中文全文(UTF-8), Dialogue.talk 优先引用村志最新档案。零漂移铁证: DETERMINISM ad9e7b31ed47ec45 与接入前逐字节一致 / ZERO-DRIFT c9e1d98362321283 / PHYSICS PASS / STREAMING e722b7f80ffc8317; STORYTELLER-DETERMINISM hashA==hashB(732852463181b8fe) CHRONICLE D11 REPEL35 MARRY2 BIRTH3 episodes=41 days=11。兑现 CD-PILLARS P1世界因你而变被看见(Ludonarrative Consonance, EVAL-1 P0-B 悬案闭环)。中文村志浮层上屏仍待 CJK 字体。批次1 全部收口。

[NPC-SOC-PORT-B2] systems-designer+ui target=src/core/world/Civilization.java + src/core/systems/CivilizationSystem.java + src/core/world/World.java(civ 字段) + src/core/sim/Simulation.java(register) + src/core/world/Chronicle.java(文明里程碑归档) + src/render/lwjgl/Game.java(HUD CIV) + src/core/sim/CivilizationDeterminismTest.java result=ok gate=PASS(四门禁+第8门全绿) note=批次2 文明深度落地: 7 个 Python 文明系统(culture/religion/tech/industry/urban/diplomacy/warfare)移植为 Civilization(开放标量+区划覆盖层+意图队列) + CivilizationSystem(注册进 registerDefaultSystems 末位, 固定子步序 urban10/tech20/ind10/religion10/diplo20/war10/culture24)。信号源替换(Java 无 trade/market/robots/deepsea): coins=prosperity*1, 市场盈余=prosperity*0.02, 污染/犯罪=0; link.couple 跨系统联动(religion<->warfare<->research)改同容器直写。材料执行层(tech 自治修墙/重建 + industry 网格磨蚀/铺缆)由 CivilizationSystem.MATERIAL_WORKS 开关守护默认关 -> 网格一字不改守指纹; 开启则 1:1 守恒重排(指纹演进属预期), 归批次4 文明实体化。零漂移铁证: DETERMINISM ad9e7b31ed47ec45 与接入前逐字节一致 / ZERO-DRIFT c9e1d98362321283 / PHYSICS PASS / STREAMING e722b7f80ffc8317; CIVILIZATION-DETERMINISM hashA==hashB(0a8533fd5eb2ec6b) research=175 unlocked=[farming2/medicine] temples=8 believers=67.76 faith=0.12 miracles=5 sects=5 区划 res37/ind48/farm55 roads24 liv0.99 外交 nomad0.38/hill0.63/valley0.55 政权2 armies69 unrest0.05 wfWars4 revolt1 culture=2(festivals=添丁节/婚嫁节)。消费端: HUD 常驻 ASCII 文明摘要(CIV RES/TECH/TEMPLE/ROADS/REL/REGIME) + Chronicle 扩展归档文明里程碑(tech/religion/diplomacy/warfare/culture) -> 文明进展进村志(兑现 P1)。发现: NPC 人口无界增长(1200tick 5->110) 待加聚落上限。批次2 收口; 下一步批次3 个体成长。

[NPC-SOC-PORT-B3] systems-designer+ui target=src/core/world/Individual.java + src/core/systems/IndividualSystem.java + src/core/world/World.java(individual 字段) + src/core/sim/Simulation.java(register) + src/core/world/Chronicle.java(个体里程碑) + src/render/lwjgl/Game.java(HUD LIFE) + src/core/sim/IndividualDeterminismTest.java + src/core/systems/CivilizationSystem.java(工业链修复) result=ok gate=PASS(九门禁全绿) note=批次3 个体成长落地: Python 18 系统(learning/behavior/persona/genetics/evolution/medicine/robotics/firearms/fishing/taming/xeno/ascension/magic/cultivation/myth/dreamscape/chemistry/roleplay) 移植为 Individual(个体层开放标量容器) + IndividualSystem(注册末位, 固定子步序 learning10/behavior8/persona20/genetics20/evolution10/medicine5/robotics10/firearms10/fishing8/taming8/xeno10/ascension12/magic12/cultivation20/myth10/dreamscape8/chemistry12/roleplay20)。材料执行层(magic 真实施法写 temp/chemistry 反应层写 mat/mass/temp)由 IndividualSystem.MATERIAL_WORKS 默认关守护 → 网格一字不改。【跨批修复① 信号源分离】Python 全局 world._research 被 tech/firearms/myth/dreamscape 共享, 照搬致个体层抽干文明科技池(实测 civ.research 175→6.4, medicine 永久不解锁)→ 个体层自持 Individual.research(learning/myth/dreamscape 供养, firearms/magic 消费), civ.research 由 CivilizationSystem 独享 → 复原 108.6。【跨批修复② 工业链死尾】批次2 power() 仅取 GENERATOR/WIRE 方块(Java 无此块→power=0)致 assemble()(需ASSEMBLE_POWER=1)永不启动, mine() 又被 !MATERIAL_WORKS 提前 return 致 ore=0 → 改数字层产矿(按速率计入账本、不磨蚀网格)+畜力/水力供电基线(base=max(1.5,0.1*alive)) → ore/iron>0、robots 复活 0→11。【跨批修复③ 外星人死分支】xeno signal 由永不触发的 research≥200 改为 knowledge≥600 → aliens 0→3。零漂移铁证: DETERMINISM ad9e7b31ed47ec45 与接入前逐字节一致 / ZERO-DRIFT c9e1d98362321283 / PHYSICS PASS / STREAMING e722b7f80ffc8317; INDIVIDUAL-DETERMINISM hashA==hashB know=954 evoGen=121 mf=0.26 spec=2 robots=11 guns=8 recovered=24 spells=1 realm=6 heroes=4 lucid=44 aliens=3 discoveries=3。消费端: Game HUD 常驻 ASCII 个体摘要(LIFE KNOW/MUT/EVO/SICK/HERO/DREAM) + Chronicle 扩展归档个体里程碑。批次3 收口; 下一步批次4 打磨(含开启 MATERIAL_WORKS 文明实体化)。

[NPC-SOC-PORT-B4] systems-designer+ui target=src/core/world/Polity.java + src/core/world/Calamity.java + src/core/systems/PolitySystem.java + src/core/systems/CalamitySystem.java + src/core/world/World.java(polity/calamity 字段 + raining 镜像) + src/core/systems/WeatherSystem.java(暴露 raining) + src/core/world/Chronicle.java(文明/灾害/民政里程碑) + src/core/systems/Simulation.java(register) + src/core/sim/PolityDeterminismTest.java + src/render/lwjgl/Game.java(HUD POLITY/CALAMITY) result=ok gate=PASS(十道门禁全绿) note=批次4 深度打磨已覆盖系统落地。移植 Python civilization.py(M11)+trade.py(M34)+disasters.py(M21) 的确定性路径为: Polity(民政经济容器: 城镇等级/集市定价/犯罪/仲裁/累犯 + 货币/商队/通胀/供应链货流; 不进 hashState) + PolitySystem(注册进 registerDefaultSystems 末位, 子步序 civ(5): town→price→crime→judge / trade(10): labor→mint→caravan→inflation→supply-chain) + Calamity(灾害状态: dryTicks/rainStreak/flag/各灾种计数/撤离) + CalamitySystem(触发层: 干燥→点燃/连雨→洪涝/久旱→干旱/周期→地震 + NPC 撤离信号)。与既有 Wildfire/Flood/Drought/Earthquake 扩散系统分工(本批补的正是 Python disasters 的触发层, 不重复造轮子)。farming(季节+灌溉)与 combat(技能树)已由既有 SeasonSystem/IrrigationSystem/玩家技能树覆盖, 本批不重造。材料执行层(灾害点燃易燃格/抬高水位/房屋→木残骸)由 CalamitySystem.MATERIAL_WORKS 开关守护默认关 → 网格一字不改守指纹。可达性修复(本批关键, 避免死尾): ①撤离原按全局旗标见谁吓谁→久旱旗标滞留致全村永久 scared(evac=877, 不再漫步)→改局部危险感知(邻近5×5有火/淹才逃, evac=60, NPC 恢复 calm/fond); ②商队 (int)rate(rate≈0.88)恒0→改小数累积器 caravanFrac(攒够1才+1, caravans 0→105); ③仲裁惩戒不可达(Java 邻里亲疏被 SocialSystem 烘热, 人人都有≥0.45相护者, pen=0/shield=36)→增设累犯维度(初犯可获人情/惯犯失众望一律惩戒, pen=17/shield=19/recid=17)。零漂移铁证: DETERMINISM ad9e7b31ed47ec45 与接入前逐字节一致 / ZERO-DRIFT c9e1d98362321283 / PHYSICS PASS / STREAMING e722b7f80ffc8317; NPC/SOCIAL/STORYTELLER/CIVILIZATION/INDIVIDUAL 同种子自洽; POLITY-DETERMINISM hashA==hashB(0a8533fd5eb2ec6b) lv=3(堡垒) alive=24 fams=7 crime=36(theft15/fight21) judge=36(pen17/shield19) coins=184.82 infl=1.44 caravans=105 inflating=true; CALAMITY fire=1 flood=3 drought=4 quake=1 alerts=23 evac=60。消费端: HUD 常驻 ASCII 民政摘要 POLITY(LV/POP/FAM/COIN/INFL/CARAVAN/CRIME/JUDGE) + 灾害摘要 CALAMITY(flag/DRY/RAIN/FIRE/FLOOD/DROUGHT/QUAKE/EVAC); Chronicle 扩展归档 TOWN/CRIME/JUDGE/DISASTER/INFLATION → 是非/天灾/物价也进村志(兑现 P1)。已知校准注记(非缺陷): 极端资源价格贴合上下限(iron/food 触顶, stone 触底), 因供给量级悬殊; wood/berry 保留动态信号。主移植清单批次0–4 全部 DONE; 仅余批次5 抽象字段(可选)。

[C1-CJK-FONT] ui/technical target=src/render/lwjgl/CjkFont.java(新建) + src/render/lwjgl/Font.java(两处 bug 修复) + src/render/lwjgl/Game.java(T 回话气泡 + B 村志浮层) result=ok gate=PASS(十门禁全绿) note=C1 CJK 中文上屏。**关键发现（先于 CJK 修复）**: 原 Font.java 有两处 bug 致**整个 ASCII HUD 从未渲染** —— (1) PX=1/14 使字形亚像素不可见; (2) 像素判定查 '#' 而字表存 '0'/'1'。无头探针实证修复前 ASCII verts=0。修复后 PX=1.4, 判定改 '1'||'#'。CjkFont 设计: 运行期用 java.awt 把字符光栅化成 16x16 布尔点阵(惰性缓存) → 按「同行连续亮像素合并为一条」发射与 Font.draw 完全一致的 7 浮点顶点(pos3+rgba4, clip-space) → **零资产、零纹理**, 契合项目自包含取向; 字体候选 Microsoft YaHei/SimHei/SimSun/... 缺失或无 AWT 时 status()=UNAVAILABLE 且 draw() 不发顶点(不崩溃)。Game 消费端: T 键交谈→屏幕下方中文回话气泡(CjkFont.wrap 换行 + 最多4行); B 键→右侧中文村志浮层(说书人当日村志全文 + 'full text: chronicle.log' 脚注); hudBuf 400000→1600000 浮点(CJK 顶点较多); 启动写 render_diag.log 的 'CJK font: <status>'。零漂移铁证: 纯渲染层, 不读不写任何仿真状态/不进 hashState; 十道门禁全绿且 DETERMINISM ad9e7b31ed47ec45 与接入前逐字节一致 / ZERO-DRIFT c9e1d98362321283 / PHYSICS PASS / STREAMING e722b7f80ffc8317 / NPC·SOCIAL·STORYTELLER·CIVILIZATION·INDIVIDUAL·POLITY 同种子自洽。无头验证: ASCII verts=1572(原 0) / CJK verts=1980 advancePx=204(12字) / status='CJK OK  font=Microsoft YaHei  cell=16'。C 类队列第 1 项收口; 队列下一项 C2 塞尔达后半段。

[C2-ZELDA-LOOP-2] game-designer+systems-designer target=src/core/world/Trials.java + src/core/systems/TrialSystem.java + src/core/world/World.java(trials 字段) + src/core/sim/Simulation.java(register) + src/core/world/Chronicle.java(trial/relic + trial/heart 归档) + src/render/lwjgl/Game.java(渲染+HUD OBJ/TRIALS+横幅) + src/core/sim/TrialDeterminismTest.java result=ok gate=PASS(十一道门禁全绿) note=C2 塞尔达循环后半段落地(补完「祭坛授能力」之后缺失的「用能力做什么」)。Trials 实体级开放状态容器: Site 试炼点(3 座, 各以 GLIDE/DASH/BOMB 为印)/Cache 补给箱(4 处, 靠近即取给魂)/世界之心(终局) + objective() 世界目标链 + asciiSummary()/snapshot()。TrialSystem 注册进 registerDefaultSystems 末位(固定序): 首 tick(w.tick==1 且 sites 空) 经 w.simStream("trial:place") 确定性布点 3 试炼点(surfaceY 贴地, 距心 20/27/34 格, 比祭坛更远→第二梯队) + 4 补给箱(距心 9/16/23/30, 魂 10..25); 世界之心置于全图最高峰(surfaceY 最大, 并列取最小 x 再最小 z → 确定性)。认取判定: 试炼点需「邻近(3格)+已具对应能力」(能力门), 补给箱仅需邻近(2格), 世界之心需「已显现+三力齐备+登顶(3格)」。目标链推进: 集齐三遗物 → 世界之心显现(写 trial/heart_reveal 事件)。渲染: 试炼点按能力印色/补给箱金色/世界之心红白悬浮块 + 接触阴影; HUD 新增 OBJ 目标行(FIND A SHRINE→PASS THE TRIALS RELIC n/3→SEEK THE WORLD HEART→THE WORLD HEART IS YOURS) + TRIALS 进度行; **顺带修复旧死分支**: shrine 横幅原「设置但从不绘制」(bannerText 无任何 draw) → 现统一由 trial/relic/heart 复用并在 drawHud 绘制。零漂移铁证: DETERMINISM ad9e7b31ed47ec45 与接入前逐字节一致 / ZERO-DRIFT c9e1d98362321283 / PHYSICS PASS / STREAMING e722b7f80ffc8317; TRIAL-DETERMINISM hashA==hashB(e98fbd625997d1d3) sites=3 caches=4; REACH relics=3 caches=4 heartRevealed=true heartClaimed=true souls=290。关键判据: 试炼层是实体级状态, 认取只改 Trials 自身 + Player.souls + 追加 events, 绝不写 mat/mass/prosperity/skills/villageMemory → 指纹不变; 并兑现 CD-PILLARS P1「世界因你而变被看见」(破印/认取进村志: RELIC/HEART 条目)。待办: 批次5 抽象字段(可选) + 多武器/音频/菜单。

[C3-WEAPON-ARTS] game-designer/systems-designer target=src/core/world/Weapons.java(新建) + src/core/world/Player.java(武器表外置+arts/cycleArt/unlockArt/weaponArt 分派+beastsWithin/hitBeast) + src/render/lwjgl/Game.java(G键轮换+三套战技演出+HUD+G横幅/CJK播报) + src/core/sim/WeaponArtTest.java result=ok gate=PASS(十二道门禁全绿) note=C3 多武器·多战技落地(把此前「5 把武器共用一种突刺」的假 build 差异做成真形态差异)。设计: 武器与战技形态**解耦**为 Weapons 原型表 —— Art{LUNGE,CLEAVE,SHOT} × Def{name,atk,art,cost,range,mult,cd}，5 档武器链 拳→铁剑→战斧→猎弓→符文刃(掉落公式 1+level/3 不变，故 level 3/6/9 分别拿到战斧/猎弓/符文刃，打法随之改变)。三形态: **LUNGE 突刺**(冲 2.4 格 + 单体重击, 射程 3.6, mult 2.0) / **CLEAVE 回旋斩**(原地上撩横扫，命中半径内**全体**并沿径向击退 1.2 格，射程 3.2, mult 1.7, 耗体 32——重武器更贵更广) / **SHOT 远射**(射程 16 格单体重击, mult 1.9, 耗体 20——弓的价值是「不用贴身」，可越沟跨崖)。实际 CD = max(DEX 派生 artCdMax, 武器自带 cd)，故重武器更慢。解锁语义: 拿到某武器即经 unlockArt() **永久**解锁其原型(不因后续换装丢失)并立即切换；G 键 cycleArt() 在已解锁原型间轮换(LinkedHashSet 保序=到达顺序)。边界修复: SHOT **无目标不空放**(返回 false、不耗体力、不进 CD)——远程战技空放扣体力是经典手感坑。重构: Player 的 WEAPONS/WEAPON_ATK/ART_RANGE/ART_COST 全部由 Weapons 取代; hitBeast() 统一命中结算(受击闪白→扣血→归零则移除+记 combat.repel+onKill)，tryAttack 与三战技共用，消除重复。渲染层: 三套世界空间演出(LUNGE 蓝色斩痕串/CLEAVE 橙色扩散环 radius 0.9→3.2/SHOT 玩家→目标的绿色弹道点串) + 右上 `ART <形态>` 与 `G: SWITCH ART x<n>` + 新战技解锁横幅 + **复用 C1 的 CJK 通道**弹中文战技说明(「习得战技『回旋斩』——原地横扫…按 G 切换」) + 失败提示(ART LOCKED (Lv2) / NO TARGET IN RANGE / ART BUSY / NO STAMINA)。**门禁方法论(本批要点)**: 四道零漂移门禁只跑 World.tick，**玩家动作层根本不在其中** —— 战技即使永远打空、范围写错、或顺手改了网格，指纹门禁也照绿。故新增第 12 道门禁 WeaponArtTest **不断言指纹而断言行为**: 高血量假兽承接伤害(既能读伤害又不触发击杀掉落污染后续用例) → LUNGE dmg=46 且 8 格外不受影响 / CLEAVE 一次命中 3/3 且 4.6 格外不受影响 + 击退成立 / SHOT dmg@12=57 且 18 格外不受影响 / SHOT 无目标不空放不耗体 / Lv1 锁定 / 解锁幂等 + 轮换 / 其中还含**零漂移不变量**(整套战技流程前后 hashState 逐字节不变=42bb9b3211abdaf8)。零漂移铁证: DETERMINISM ad9e7b31ed47ec45 与接入前逐字节一致 / ZERO-DRIFT c9e1d98362321283 / PHYSICS PASS / STREAMING e722b7f80ffc8317 / 其余七道 per-layer 同种子自洽。C 类队列: C1✅ C2✅ 本项 C3✅; 余 C4 音频 / C5 菜单 + 批次5 抽象字段(可选)。

[C5-MENU-PAUSE] ui/systems-designer target=src/core/sim/MenuModel.java(新建) + src/core/world/World.java(paused 开关 + tick 首行 early-return) + src/render/lwjgl/Game.java(ESC 菜单/输入路由/光标模式/设置应用/浮层) + src/render/lwjgl/Font.java(width 助手) + src/core/sim/MenuTest.java + build_runner.py/build.bat(第13道) result=ok gate=PASS(十三道门禁全绿) note=C5 菜单/暂停/设置落地。**设计动机**: 菜单逻辑若长在 Game 的 GLFW 回调里, 就只能靠肉眼验收(本沙箱无显示器), 故抽为纯模型 MenuModel(core/sim: Page{CLOSED,MAIN,SETTINGS,CONTROLS,QUIT_CONFIRM} + Action{NONE,RESUME,QUIT,BACK}, 导航环绕/页跳转回退落点稳定/设置钳制/QUIT 二次确认默认落 CANCEL; 零 GL 零世界写入)。**暂停语义 = 丢 tick(关键决定)**: World.paused 为不进 hashState 的布尔开关, tick() 首行 if(paused) return —— 不推进 tick 计数、不耗 RNG、不改网格。因此『中途暂停任意次再恢复』与『从未暂停』跑同样多次 tick 后 hashState 逐字节一致; 若做成『冻结时钟』(暂停期间仍推进 tick 只是不渲染)则会静默错位存档/回放/多人同步。渲染层: ESC 改为起菜单(不再直接退出) + 菜单输入路由(W/S/↑↓ 导航, A/D/←→ 调值, ENTER 确认, ESC 逐层返回) + 光标模式切换(菜单放开指针并重置视角基准防瞬跳) + 鼠标灵敏度走 menu.mouseSens()(默认 0.15 = 旧硬编码, 故默认手感不变) + 循环暂停丢帧且不追帧(避免恢复瞬间补跑一批 tick) + 暂停中禁用挖放并清空待处理动作(防恢复瞬间补放) + 设置即时应用(改 FOV/视距重算投影矩阵, HUD 可显隐但菜单必画) + 居中浮层(压暗 + 选中高亮 + CJK 中文副标题复用 C1 通道)。零漂移铁证: DETERMINISM ad9e7b31ed47ec45 与接入前逐字节一致 / ZERO-DRIFT c9e1d98362321283 / PHYSICS PASS / STREAMING e722b7f80ffc8317; 第13道门禁 MenuTest 无头实测 STATE init/openMain/wrap=true, NAV set/backS/ctrl/backC/resume=true, QUIT confirmDefaultCancel/cancel/reConfirm/yes=true, SETTINGS FOV/SENS/VD hi-lo/hudFlip/editable/noop=true, **PAUSE-TRANSPARENT 673a080d240eaa84 == 673a080d240eaa84 == 673a080d240eaa84 == 673a080d240eaa84**(p1/p3/p7 与基线一致), FROZEN hashEq=true resumeAdvances=true menuNoDrift=true。C 类队列: C1✅ C2✅ C3✅ 本项 C5✅; 余 C4 音频 + 批次5 抽象字段(可选)。

[D3-DAYNIGHT-VISUAL] art-director/technical-director target=src/core/world/DayCycle.java(新建) + src/render/lwjgl/Game.java(world shader uAmbient/uLightTint + sky shader 重写 + drawFrame 昼夜上传 + HUD 时钟) + src/core/systems/AuroraSystem.java(改用共享「夜」定义) + src/core/sim/DayNightTest.java + build_runner.py/build.bat/build.sh(第14道) result=ok gate=PASS(十四道门禁全绿) note=D3 昼夜/天气视觉落地(此前昼夜只是各系统里的私有计数器, 画面上完全看不出来)。**设计**: 新增纯确定性派生模型 DayCycle(core/world: 零状态/零 RNG/零网格写入), 唯一时基 w.tick(已进 hashState 指纹), DAY_LEN=512 tick(20tick/s → 25.6s 一昼夜)。输出 太阳高度/光源方向/环境光/光色/天空顶色/地平线色/星空强度/雾密度/时段/时钟, 用 5 关键帧调色板 + smoothstep 插值(保证天色连续不跳变)。**为什么放 core 而非渲染层**: 它要被仿真系统(AuroraSystem)读; 又因是纯函数不吃 RNG 不写状态 → 对四道基线门禁逐字节零影响(第14道门禁实证)。**修掉两处旧缺陷**: ① 天空渐变原本用屏幕 Y 取色(vY=aP.y) → 抬头/低头天色完全不动, 天空像"贴在屏幕上的壁纸", 且无法画日月/星空(它们必须锚在世界方向)。重写为**按视线方向取色**: 逐像素由 NDC 反解世界视线方向(公式与 DayCycle.SkyBasis.ray 逐字对应), 于是渐变锚定真实地平线、日月盘锚定世界方向(转动视角会滑出视野)、星空锚定天球。② AuroraSystem 自带私有相位 t%128>=64 表"夜", 与其它任何系统及渲染层天色都不一致(隐性不一致) → 改用 DayCycle.isNight(w.tick), **"夜"全项目唯一定义**。**渲染细节**: world shader 由写死 l=0.55+0.45d 改 uAmbient + uLightTint(夜偏月蓝/晨昏偏暖橙/正午近白); sky shader 新增程序化星空(3D hash, 不消耗任何 RNG 流)+太阳/月亮盘与光晕(同一光源方向, 夜里 uStar 高时自动画月亮)+降雨压暗偏冷(uRain); 雾色绑地平线色(地平线不断层)、雾密度随晨昏(晨雾最浓 0.0144)/夜/雨变化; 光照方向 y 分量恒 >0(避免光源贴地致整面全黑与昼夜间亮度突变); 降雨视觉用渲染层平滑量 rainSmooth(起雨/停雨不硬切)。HUD: 顶部居中新增时钟条(DAY n / HH:MM / 时段 / CLEAR|RAIN + 24h 游标, 白天金/夜间银蓝) —— 这是肉眼验证昼夜的锚点。**门禁方法论**: 第14道 DayNightTest 断言四类以前只能肉眼验收的性质 —— 周期/回绕/日序; **连续性**(逐 tick 扫 2 个整周期, 天顶色/地平线色/光色/环境光/光源方向/星空强度变化量 ≤0.06, 防"天色硬切"), 实测最大 0.0269(星空); 取值域与昼夜单调(正午 0.62 > 午夜 0.20, 正午雾 0.0090 < 晨昏雾 0.0144); **射线基**(屏幕中心==相机前向、fovY=90° 时屏幕上边缘恰 arctan 1 = 45° 精确值、沿屏幕 Y 扫描视线高度严格单调递增、单位长度、tanX/tanY=1.7778/1.0000 —— 这一条正是"老版贴屏幕天空"的修复点); **纯函数零漂移**(狂算整套昼夜量后 hashState 与 rng.state 逐字节不变)。另加**防死分支**断言: 逐 tick 推进并给每条极光事件标注它出现时的 tick, 断言极光事件 >0 且**全部落在夜里**(对齐时若把判据写反, 极光会静默变成永不触发的死代码)。零漂移铁证: DETERMINISM ad9e7b31ed47ec45 与接入前逐字节一致 / ZERO-DRIFT c9e1d98362321283 / PHYSICS PASS / STREAMING e722b7f80ffc8317 / 其余分层门禁同种子自洽; DAYNIGHT CYCLE 全 true、CONTINUOUS eps=0.06 top=0.0118 hor=0.0246 tint=0.0143 amb=0.0039 dir=0.0110 star=0.0269、RAY center=fwd/up45/monotone/unit 全 true、PURE hashEq=true rngEq=true h=673a080d240eaa84、AURORA events=7 atNight=7 atDay=0。**顺带发现(未修, 已记入 PORTING_PLAYBOOK 未修清单)**: MistSystem 的"清晨"窗口是 w.tick%64==0(每 3.2s 一次, 合 8×/日), 与 DayCycle 的 dawn(每日一次)不一致 —— 因纯属日志事件且无渲染消费者, 本批不动以免超范围。D 类队列: D3✅; 余 C4 音频(唯一剩余非移植功能) / 批次5 抽象字段(可选) / D1/D2/D5(用户已决定暂缓)。

[C4-AUDIO] technical-audio/systems-designer/ui target=src/core/audio/Sfx.java + src/core/audio/AudioSynth.java + src/core/audio/AudioMixer.java(均新建) + src/render/audio/AudioOut.java(新建) + src/core/sim/MenuModel.java(VOLUME/SFX 两行) + src/render/lwjgl/Game.java(29 处触发 + F 键暂停守卫) + src/core/sim/AudioTest.java + build_runner.py/build.bat/build.sh result=ok gate=PASS(十五道门禁全绿) note=C4 程序化音频落地(此前游戏完全无声)。**关键取向: 零新增依赖** —— libs/ 里没有 OpenAL, 本来要走"新增依赖"; 但 JDK 自带 javax.sound.sampled(自 1.3 起在 rt.jar), 配合 AudioSynth 的**程序化合成**(不加载任何 .wav/.ogg), 整条音频链一个新 jar 都不加 —— 与 C1 用 java.awt 光栅化代替字体文件同一取舍(宁可算, 不背资产)。**三层设计**: Sfx(23 音效原型表: 波形{正弦/方波/锯齿/三角/噪声} + 扫频 f0→f1 + 时长/增益/起振/衰减指数/尾部释放/优先级 0-3/噪声比/第二分音, 全常量); AudioSynth(纯函数: sampleAt(sfx,variant,i) 只依赖三元组, 线性扫频的相位积分 φ=2π(f0t+(f1-f0)t²/2dur) 闭式 → 可 O(1) 取任意样点, 无需存历史; 噪声=白噪+一阶高通, 因噪声是 (variant,i) 的纯函数故差分也是纯函数); AudioMixer(纯逻辑: MAX_VOICES=8 定长声部数组无 HashMap 迭代序依赖, 优先级抢占+同级抢最旧(老化, 防长音饿死后续), int 累加后**一次性**钳制(避免边加边截的顺序敏感), 静音/零音量时声部照常老化但输出全零)。AudioOut(render/audio): 22050Hz/16bit/单声道/小端 + 200ms 缓冲。**三条硬纪律**: ① 喂前先查 line.available(), 缓冲满就少喂或跳过本帧 —— 宁可音频轻微迟滞也不让游戏掉帧(渲染帧率是玩家直接感知的); ② 无设备/无线/被占用/驱动异常一律 try-catch 降级静音, 不许异常冒泡到主循环(本沙箱正是这种情况: status=UNAVAILABLE, 游戏照常跑); ③ SourceDataLine 只有 write(byte[],off,len) 没有 short[] 重载 → 手动摊平成小端 16bit(与 AudioFormat bigEndian=false 严格对应)。**音频绝不碰 RNG**: 音高微扰(±2.5%)与噪声声源全部来自 (variant,i) 的整数位混洗(detune/noiseAt), **连 simStream 都不吃** → 播不播音频对 hashState 零影响。**Game 接线 29 处**: 挖 DIG/放 PLACE/挥空 SWING/命中 HIT/击杀 KILL/受伤 HURT/起跳 JUMP/落地 LAND/翻滚 ROLL/炸弹 BOMB/三战技(ART_LUNGE·CLEAVE·SHOT, 与形态一一对应)/升级 LEVELUP/掉落·魂锻 LOOT/交谈 TALK/新战技解锁与祭坛觉醒 UNLOCK/遗物 RELIC/世界之心 HEART/菜单 MOVE·SELECT·BACK/被拒 DENY(远射空放·体力不足·无村民·魂不足)。**命中判据**: 用「敌兵总血量下降」判定 HIT 且与 KILL 互斥 —— 挥空只有风声, 不撒谎。菜单 SETTINGS 新增 VOLUME(0-100)/SFX(开关), 且只在变更时下发(因 setEnabled(false) 会清空声部池, 每帧调用会把正在响的音效切掉)。**顺手修复**: 轻攻击 F 键加 !menu.isOpen() 守卫 —— setIntent 写的是 Player 自身意图队列(不受 clearQueuedActions 保护), 暂停中按 F 会留下一条意图、恢复瞬间"补砍一刀", 正是 C5 想消灭的补放。**门禁方法论(本批核心)**: 第 15 道门禁 AudioTest 把池上限/抢占次序/老化/静音语义/混音契约从 SourceDataLine 写回调里剥出来 headless 断言, 并**当场抓到两个真缺陷** —— ① 「老化」原按 clock(已混音帧数)计年龄, 于是连续 play 而不 mix 时 8 个声部年龄**全相同**, 同优先级抢占退化成"永远抢 0 号槽"(LRU 名存实亡, 门禁报 lruOldest=false) → 改为 play() 里 vStart=clock++(年龄=播放先后, 这才是"更早开始"的语义); ② 回卷检测原用"相邻样点跳变>满量程", 但饱和波形本来就在 +32767/-32768 两轨间大幅跳变 → **大面积误报**(首跑 wrap=630, 是测试自身判据错)。正确判据是**逐样点比对混音契约** clamp(Σ round(sample·32767)), 一次覆盖"定点和累加是否回卷"与"取整/钳制边界是否错位"。另加**防空洞断言**: 只断言"没削顶"是空洞的(静音永不削顶), 故同时断言两条轨都真的压过(max==32767 && min<=-30000)且峰值非 0。零漂移铁证: DETERMINISM ad9e7b31ed47ec45 与接入前逐字节一致 / ZERO-DRIFT c9e1d98362321283 / PHYSICS PASS / STREAMING e722b7f80ffc8317 / 其余分层门禁同种子自洽; AUDIO PURE 23/23 pcmEq=true、DET burstEq=true bytes=8192、ENVELOPE 23/23(bad=[])、VOICES cap=8 fill32 played=32 dropped=0 active=8、STEAL hi>lo=true starveLo=true lruOldest=true、MUTE silent=true blocked=true zeroVol=true、MIX rails=true exact=true differ=0、**ZERO-DRIFT e9c4d55a16022fae == e9c4d55a16022fae == e9c4d55a16022fae**(跑 tick 且每 tick 播不同音频 / 零音量播 / 完全不播 = 三份指纹相同)。**顺带修复**: build.bat/run-game.bat 的 javac 源清单补 core/audio 与 render/audio(render/audio 必须排在 render/lwjgl 之前, 因 Game 依赖 AudioOut 而它不需要 libs), 并给 run-game.bat 原本缺失的 core 编译 errorlevel 守卫补上。C 类队列 C1✅ C2✅ C3✅ C4✅ C5✅ **全部收口**; 仅余批次5 抽象字段(可选)；D1/D5 用户已决定暂缓(D2 实为未开工的 ⏳ 项，非暂缓，本轮清掉)。

[D2-CHRONICLE-DEDUP] systems-designer/ui target=src/core/world/Chronicle.java + src/core/systems/CivilizationSystem.java + src/core/sim/StorytellerDeterminismTest.java result=ok gate=PASS(十五道门禁全绿) note=D2 村志记忆长程去重。**症状**: 240 tick 内 episodes=60 里有 **35 条**是同一句“旅人击退了来犯之敌，村寨得以安宁”，村志「近几件值得记下的事」几乎全是它 —— 长程重复同义句把村志刷成了复读机。**根因**: Chronicle.absorb 只按单调水印截断新事件，**完全没有去重**；且 episode 文本内嵌「第N时辰：」，所以连 Python 原版 village_memory 的“按全文去重”(含“第N天”前缀，实际只能挡住同一天内的重复) 也做不到长程去重。**修法三条**: ① **骨架判等** —— 去掉「第N时辰：」前缀得“骨架”，以 `action|骨架` 为去重键，同义事件只入库一条且与发生时刻无关；参数不同的事件(不同夫妻/不同新生儿/不同罪犯)骨架不同，**不会被误并** —— 特意**没有**用“模糊相似度”(bigram Jaccard≥阈值)：实测会把“X困顿难耐，偷走了邻家的存粮”与“Y…”这类**不同主体**的事件误并成同一件。② **计数/归档解耦** —— counts 记录“每一次发生”(含被去重未入库的)，episodes 只留“不同档案”。这一条是必须的：HUD 的 `REPEL n` 是 A 类验收里“世界因你而变”的**可见证据**，若把计数挂在“入库条数”上，去重会顺手把这个玩家能看见的反馈也一起削掉。③ 新增 lastAscii/lastTick，HUD `LAST` 反映“最新发生”而非“最新入库”。**连带可达性守护(易漏)**: CivilizationSystem.incubate 原本用**村志档案条数**解锁节日/绰号(`marry>=2`→婚嫁节)，去重后 35→1 会让节日**静默变不可达**(特性死亡且门禁不报) → 把阈值改按 `Chronicle.count`(发生次数)计：语义更对(节日纪念的是“发生了多少次”)，且与去重前的数值**完全一致**(实测 festivals 仍=[添丁节, 婚嫁节])。**实测**: episodes 60→18(-70%)、counts 一字不变(REPEL 35 / MARRY 2 / BIRTH 3 / occur 60)；第7道门禁加固后新增 DEDUP 三类断言(真实跑去重 + 合成事件精确验证 + 叙事层无雷同句)全 true；四道基线 DETERMINISM ad9e7b31ed47ec45 / ZERO-DRIFT c9e1d98362321283 / PHYSICS / STREAMING e722b7f80ffc8317 **逐字节不变**，且**整份 15 道门禁报告只有 storyteller 那 2 行不同、其余输出逐字节一致**(把改前 build_report 存一份逐行 diff 得的证据，比“反正都 PASS”强得多)。**方法论沉淀(PORTING_PLAYBOOK §S)**: 去重/收敛类改动动手前必须同时问两句 —— “谁在**按条数计阈值**?”(可达性) 与 “谁在**靠条数当证据**?”(可见性)。

[B5-MATTER-FIELDS] systems-designer/ui target=src/core/world/Matter.java(新建) + src/core/systems/MatterSystem.java(新建) + src/core/world/World.java(matter 字段) + src/core/sim/Simulation.java(register) + src/render/lwjgl/Game.java(HUD MATTER/DEEPSEA 两行) + src/core/sim/MatterDeterminismTest.java(新建) + build_runner.py/build.bat/build.sh(第16道) result=ok gate=PASS(十六道门禁全绿) note=批次5 抽象字段落地。**判定纪律(本批核心)**: 抽象概念先分两类 —— ① **有世界状态可演化的量**(phases 物相/entropy 熵与有效能/fusion 聚变辐射/deepsea 深海基地) → 移植为**开放标量容器** Matter(15 标量, World 只加一个 public final matter 字段, **不进 hashState**, 与 npcs/beasts/shrines/social/chronicle/civ/individual/polity/calamity 同纪律)；② **无世界状态的粘合/配置层**(link.py 鸭子类型 getattr 跨系统耦合 / portal.py CLI·Web 配置中枢) → **显式排除**、记录在案(非遗漏, 它们不产出任何世界状态)。**代理映射(缺网格时的正解)**: Java 世界没有 Python 的 temp/radiation 网格, 故从**既有真实量**派生并写明理由 —— 温度 t=20 + FIRE方块数×40 + LAMP方块数×0.3 + 昼夜气候(1.5·sin(2πtick/512)); 熵 supply=civ.power+日照, work 累积, entropy += work×(1−0.62), exergy=max(0, supply×0.62 − entropy×0.05); 聚变 reactors=(科研≥60 且 供电≥1)?(int)power:0, fusionOut=reactors×2, radiation 向 target 平滑; 深海 pressure=WATER方块数×0.02, subs=(水≥100 且 科研≥60)?1+科研/200:0, mineral 累积、pollution 钳制、colonies。**防死代码(关键)**: Python deepsea 的潜航门是「科技≥60 且 齿轮≥2」, 但实测 Java 的 civ.gear **恒为 0**(工业链到不了齿轮, 见批次3 工业链修复) → 若照搬, 深海军门**永不可达且任何门禁都不报**(典型的安静死分支, 呼应 §J/§L) → 改用「水≥100 且 科研≥60」门(信号源真实可得)。**材料执行层**: 辐射扩散/作物变异/NPC 辐射伤害/海洋遮罩/潜艇布点 由 MatterSystem.MATERIAL_WORKS=false 默认关守护 → 关时网格一字不改, 守指纹基线(与 §H/批4 同法)。**零 RNG**: MatterSystem 完全忽略入参 rng, 连 simStream 都不吃 → 不扰动其它层。**门禁方法论(本批要点, 呼应 §O/§Q)**: 抽象字段层**同样落不进四道基线门禁的盲区之外** —— 四道基线只跑 World.tick, 既看不见"字段是否真在演化"(可能恒 0 死代码), 也证不了"该层确实在指纹之外"。故第 16 道 MatterDeterminismTest 断言四类性质: ① **不变量**(同种子两遍 hashState 与 matter.snapshot 逐字节一致); ② **可达性**(transitions=21≥1 且 phasesSeen=2≥2 且 12 个字段全非死 —— 直接防"系统在跑、字段恒 0"); ③ **指纹隔离证明**(主动改 matter 的各字段后 hashState 逐字节不变 → 这是"该层不进指纹"的**构造性证明**, 比"反正都 PASS"强); ④ **零 RNG 证明**(跑一次 update() 前后 rng.state() 不变 → 新增系统绝不借用主 RNG)。**实测**: DETERMINISM ad9e7b31ed47ec45 与接入前逐字节一致 / ZERO-DRIFT c9e1d98362321283 / PHYSICS PASS / STREAMING e722b7f80ffc8317; MATTER-DETERMINISM hashA==hashB(0a8533fd5eb2ec6b); MATTER-GATE det=true layer=true trans=21 phasesSeen=2 live=true isolated=true noRng=true; MATTER A phase=LIQ alloy=0.664 supply=3.267 work=0.439 entropy=23.392 exergy=0.856 reactors=2 fusionOut=4.000 rad=3.722 subs=1 pressure=2.740 mineral=62.400 pollution=2.057 colonies=18.221。**外科手术式改动证明**: 与改前 build_report 逐行比对, 10 处抽样非 MATTER 行**逐字节一致**, 仅新增 MATTER 3 行。**方法论沉淀(PORTING_PLAYBOOK §T)**: 抽象字段移植三问 —— ① 有状态可演化 vs 无状态粘合? ② 缺网格能否用既有真实量代理(并写明理由)? ③ 落地后是否补了 指纹隔离/零 RNG/可达性 三证? 批次5 收口; **主移植清单(批次0–5)全部 DONE + C 类功能全部收口 + D2/D3/D4 已清**, agent 端无待办, 仅余用户本机 run-game.bat 验收。

[ART-PROG-3] art-director target=src/render/lwjgl/Game.java + src/render/lwjgl/Chunk.java + src/core/world/Blocks.java + src/core/world/DayCycle.java + docs/ART_BIBLE.md | 美术三期（触发：本机验收截图暴露「洋红花海像 missing texture / 大色块死板 / 背光面压死成黑 / 夜雨浑浊」）：① worldShader 单光→key+半球双光（hemi=0.5+0.5*n.y；l=uAmbient+(1-uAmbient)*(0.72*key+0.28*hemi)）；② 每体素材质颗粒 col*=0.94+0.12*h31(floor(vWorld))（世界坐标纯哈希，零 RNG）；③ Chunk.vegTint 花田五色盘（玫瑰/金盏/白/紫/珊瑚，按体素哈希取色）+ 叶 ±12% 明度抖动，Flower 不再成洋红地毯；④ DayCycle.ambient 夜 0.20→0.26 + 夜色地平线抬亮；⑤ 世界/天空 shader 统一 +6% 饱和 +6% 对比 + gl_FragCoord 径向暗角（四角 -20%，不引入 FBO/后处理 pass）；⑥ 雨压暗 0.25→0.15 | gate=PASS：十六道门禁全绿，四道基线 ad9e7b31ed47ec45 / c9e1d98362321283 / PHYSICS / e722b7f80ffc8317 逐字节不变，MATTER 0a8533fd5eb2ec6b 不变，20 处 _EXIT=0、0 编译错误 → 纯渲染层零漂移 | 待本机 GL 目视验收
[C6-MEGALITH-P1] art-director(technical-artist 执行) target=src/render/lwjgl/Silhouettes.java(新建) + Game.java(7 处) — 巨构 Phase 1 远景剪影落地：全局坐标确定性 monumentAt（间距 384/存在率 0.62/TOWER+GATE/坡度否决/原点格必出 H176@(170,170) 离出生点 240 格）；地形锚定逐字节复刻 World.terrainHeightField；色板 ASHLAR/SHADE/VEIN/GOLD 渲染层自有 + GOLD 烘焙×1.8 夜辉；断拱/断柱/半埋碎块。worldShader 新增 uFogScale（剪影雾缓解 0.35，白天雾 256 格饱和→缓解后可读 ~730 格）。gate=PASS（十六道全绿，四道基线逐字节不变 ad9e7b31ed47ec45/c9e1d98362321283/e722b7f80ffc8317 + MATTER 0a8533fd5eb2ec6b；20 处 _EXIT=0、0 编译错误；纯逻辑自测 det=true 双种子、survey ±6 格 91 座）—— render-only 不写 mat、不消费任何 RNG；待本机 GL 目视验收
[C6-MEGALITH-P2] art-director(technical-artist 执行) target=src/core/world/Megalith.java(新建) + Blocks.java(+4) + World.java(包内可见+挂钩) + Silhouettes.java(委托+让位) + Game.java(SY 48→112) — 巨构 Phase 2 近场神殿落地：Game 世界 SY 48→112；神殿材化进 mat（全局坐标确定性，TOWER 总高 48=环墙+11×15 门洞+中庭空腔+祭坛长明灯+金冠塔楼；GATE 双柱断拱+半埋断龙骨）；选址带 waterLevel+2 ≤ gy ≤ sy-58（MIN_SY=96 保证非空）；剪影↔真身共用 descAt、近于 SKIP_R=128 剪影让位。gate=PASS（**十七道门禁全绿**：新增第 17 道 MegalithDeterminismTest det=true anchor=true materialized=true struct=true slide=true exempt=true；**四道基线指纹逐字节不变** ad9e7b31ed47ec45/c9e1d98362321283/e722b7f80ffc8317 + MATTER 0a8533fd5eb2ec6b；21 处 _EXIT=0、0 编译错误）—— **零基线漂移**（原预期有意重基线，MIN_SY 门控使其未发生）；待本机 GL 目视验收
[C6-MEGALITH-P3] art-director(technical-artist 执行) target=src/core/world/Megalith.java — 巨构 Phase 3 被吞噬增强：weather() 风化层（gy-8..gy+8 带内 ASHLAR 家族按埋深 5%→25% 侵蚀为 MOSS，不碰 GOLD/LAMP/AIR，全局坐标哈希确定性）+ writeTower 正交两条倒伏半埋躺柱（哈希缺口 → 断口参差）；MegalithDeterminismTest 断龙骨探针兼容 MOSS。gate=PASS（十七道全绿、四道基线逐字节不变；条石家族 29417→27519 ≈1900 块被侵蚀证明风化生效）；待本机 GL 目视验收
[ATLAS-A] art-director(technical-artist 执行) target=src/render/lwjgl/TextureAtlas.java(新建)+Chunk/Game/Silhouettes(顶点格式 10→12 float +UV2) — MC 复审翻案项 A 程序化纹理图集落地：启动时 CPU 烘焙 256×256 RGBA（16×16px tile ×256，tile=blockIndex*3+kind 侧/顶/底；固定种子像素整数哈希，零素材零 RNG）；worldShader 新增 sampler2D uTex + alpha<0.5 discard（花丛/玻璃裁切）；配色分工=实心块顶点色灰度化(AO×方向明度)纹理承担颜色、风动植被(LEAF/WATER)与 FLOWER 反之（五色花盘/树冠抖动保留）；剪影用 WHITE_TILE 纯白微噪（金饰 ×1.8 夜辉不变）；AP3 体素颗粒被纹理取代移除。gate=PASS（17 道全绿 21 处 _EXIT=0、0 编译错误；四道基线指纹逐字节不变 21a2de200fda8a8b/8a69c3559c2d86bf/87d5bf1cecb4c628/2597c5fb57ef35f9）；待本机 GL 目视验收
[BIOME-B] art-director(technical-artist 执行) target=src/core/world/World.java — MC 复审翻案项 B 生物群系表层落地：biomeAt(gx,gz)=温/湿两条 64 格 valueNoise 确定性查表（SNOWY<0.30 / ARID>0.70&&humid<0.42 / BLOOM humid>0.62 / TEMPERATE 默认），高度公式零改动（SurfaceRules 思想）；表层=雪原 SNOW 盖顶+waterLevel 冻湖 ICE+稀树(nextInt600)/沙海 SAND+1 加厚+CACTUS(nextInt180, cellHash 定高 2-3)/花海 GRASS+FLOWER 16% 密度(cellHash)/温带原样；MIN_SY=96 参数门控（同巨构先例）→ 门禁小世界 SY=40 恒温带=17 道门禁与四道基线指纹逐字节不变；F3 加 BIOME 显示。gate=PASS（21 处 _EXIT=0、0 编译错误；指纹 21a2de200fda8a8b/8a69c3559c2d86bf/87d5bf1cecb4c628/2597c5fb57ef35f9 不变）；自测：占比 52.9/21.9/6.9/18.3%、最近距离 SNOWY40/ARID48/BLOOM32、出生窗口 SNOW641/ICE68/CACTUS14/FLOWER140、det=true、出生点=TEMPERATE；待本机目视验收
[LAMP-C] art-director(technical-artist 执行) target=src/core/world/World.java(computeLight BFS)+Chunk/Game/Silhouettes(顶点 12→13 float +lampLight) — MC 复审翻案项 C LAMP 块光 BFS 落地：逐源 BFS（半径 14、6 邻、非不透明传播衰减 1/格、墙挡光；队列 int 复用零分配）；lightGrid 渲染派生缓存不进 hashState、World.tick 永不触碰 → 门禁天然零漂移（无需门控）；setBlock+generateChunk 标 lightDirty，Game 渲染每帧至多重算一次；FS 夜间暖光滑入（白天自动减弱）。附带修复立项 A 潜伏 bug：Chunk faces 缓冲 memAllocFloat(faces*6*10) 未随顶点格式 12 float 扩容（GPU 越界读，本机未跑过未暴露）→ 13。gate=PASS（21 处 _EXIT=0、0 编译错误；四道基线指纹逐字节不变）；自测三证：光斑 below0.93/+3 0.71/beyond0、封闭盒遮挡 0.00/拆面 0.43 命中理论、det=true+isolated=true；待本机目视验收
[HOTFIX-SKYH31] target=Game.java initSkyShader — 本机闪退修复：引擎优化轮加天空方块流云时 h31(floor(cp*0.5)) 实参为 vec2 而 h31 只重载 vec3 → GLSL 编译失败 makeProgram 抛异常闪退（沙箱无 GL 编译期查不出，用户首跑爆雷）。修复=改显式 vec3 构造；并自查本会话全部 shader 改动（world VS/FS aLamp/vLamp/uTex 调用-定义匹配、sky 其余 h31 调用全 vec3）无同类问题。gate=PASS（21 处 _EXIT=0、四道基线指纹逐字节不变）
[HOTFIX-SKYVEC] target=Game.java initSkyShader — 第二次闪退修复：上次 h31(vec2) 改 h31(vec3(floor(cp*0.5),3.7,9.1)) 时只数参数个数没数**分量数**（floor(vec2)=2 分量 +2 float=4 > vec3 的 3）→ constructor too many arguments 再闪退。修复=vec3(floor(cp*0.5),3.7)（2+1=3）；并写了分量计数静态审计器扫全部 5 个 shader 块：0 违规（2 处为检查器切分假阳性，实为既有合法语法）。gate=PASS（21 处 _EXIT=0、四道基线指纹逐字节不变）
[HOTFIX-STENCIL] target=Game.java drawEntities — 村民黑盒第三次修复（根治）：深度偏移玄学不可靠（壳面与本体面共面竞争，偏移量依赖深度缓冲精度，远处/特定视角失守）→ 改 **stencil 描边**（ER 同款）：先画本体（stencil ALWAYS 写 1），后画壳（GL_EQUAL 0 才落地）→ 壳只在未被本体覆盖的像素着色=纯轮廓带，几何精确。前提：GLFW_STENCIL_BITS=8 已有；glClear 加 GL_STENCIL_BUFFER_BIT；绘制顺序本体→壳对调。同轮完成 LOD/遮挡二次复审：LOD=MC 本体无 LOD（REFERENCE 0 提及，Sodium/DH 是模组）判定升级'不该学'；遮挡='⏸ 挂起+量化触发器'（draw>60/顶点>500 万/视距>800/窗口>256²/室内场景），触发后第一刀=terrainHeightField 高度场轻量遮挡 ~40 行。gate=PASS（21 处 _EXIT=0、四道基线指纹逐字节不变）；待本机目视验收（村民应为彩色职业色+细描边轮廓）
[OCCL-E] target=Game.java(窗口 96→160/视距 128/terrainOccluded/F3 观测)+Chunk.java(maxY) — 用户裁决主动制造遮挡剔除前置：窗口扩至 160×160（36→100 chunk）触发规模成立 → 轻量山地遮挡第一刀落地：Chunk.maxY 列顶包络（rebuild 顺带算）+ terrainOccluded 纯函数（相机→目标顶每 16 格采样、判定线上移 2 格、中段被截即遮挡、近 64 格豁免）+ F3 加 DRAW/FACES/OCCL。自测四场景 PASS；gate=PASS（21 处 _EXIT=0、四道基线指纹逐字节不变——门禁小世界不受 Game 窗口参数影响）；本机验收观察=F3 的 OCCL 数（谷底应显著>0）与 DRAW 下降
[F-SAVE] target=World.save/load 扩展段 v2 + Simulation(World) 构造器 + Game F9/F10/启动自动读档 + SaveLoadTest 接线门禁 18 — 落盘存档（VISION_CHECK 头号欠账②）落地：黄金 8 项（hashState 全覆盖）既有设施本已完整（含滑窗快照路径），本轮补 player 完整状态（hp/souls/level/str/weapon/abilities）+ 7 系统状态标量（civ/polity/social/matter/trials/calamity/individual）扩展段 v2；setBlock 标 editedChunks 顺手修「系统建筑滑窗消失」bug；Game F9 快存（SAVED toast）/F10 运行中读档（整体替换 sim+全块重建 ~0.2s）/启动自动读档 save/world.sav。gate=PASS（18 道门禁：SAVEL-GATE hash0=true hash300=true——存读档后 300 tick 演化仍逐字节一致；22 处 _EXIT=0；四道基线指纹逐字节不变）

[BEAST-SPAWN] target=BeastSystem.java(spawn/move) + OPEN_ITEMS §9 — LD-2026-09-11「开局两兽堵死玩家」修复：①出生宽限 GRACE_TICKS=120（≈6s 不刷敌，旧版 tick0 即刷 4-8 格内贴脸围殴，开局空手=死局）；②刷距 4-8→10-18 格（看清世界再接敌）；③近战 move 到 contact*0.8 即停（旧版走进玩家身体坐标重叠=「两个东西堵着动不了」的体感来源）。仿真层有意演进：DETERMINISM 21a2de200fda8a8b→7fee9fbff5cffab1（300-tick 繁荣度 30→52，玩家存活变好）、ZERO-DRIFT 8a69c3559c2d86bf→2927e48836bc7127；STREAMING 87d5bf1cecb4c628 / NPC 390fbd4ba5f90ac5 不变；gate=PASS（22 出口全 0，SAVEL 黄金自洽）。同轮 AP-TX（图集 320 gutter+mipmap+aniso+DETAIL_TILE 细节叠加 / HudText 文本缓存 / World.SCATTER_* 参数表 / Silhouettes 碑林）+ 失焦自动暂停（focus callback 清输入+paused|= !windowFocused+锁定 toast）均为渲染层零漂移

[SPAWN-PICK] target=Simulation.findSpawn + Player.spawnY(World,int,int) 重载 + Game 罗盘 — LD-2026-09-11 开局体验治本：①开局选址器（中心 >=4 格避让涌现场落块带，环形扫到 24 格取首个 3x3 开阔平坦点：邻列高差<=1 无围困、上方 2 格净空不嵌树冠；确定性零 RNG，找不到回落中心）；②方位罗盘（HUD 顶部刻度条 N/E/S/W + 未领取祭坛金点 + 村庄绿点，视线中心刻度，纯渲染层）。指纹演进：DET 7fee9fbff5cffab1->43723456791b5364（繁荣度 52->59，好出生点让世界发展更好）、ZD 2927e48836bc7127->34c8bb722c5a1a6f、NPC 390fbd4ba5f90ac5->8560321416dc6ac5（环带随新出生点，落点不合格者跳过 npcCount 7->5）；STREAMING 87d5bf1cecb4c628 不变；gate=PASS（22 出口全 0）。读档路径 Simulation(World) 不重算选址（旧档兼容）

[MC-CTRL] target=Game.java(输入/交互层) + MenuModel.CONTROLS — MC 1.20 操作对标（抄思想不抄代码）：①放置防嵌墙（BlockItem.canPlace 思想：目标格与玩家 AABB 相交拒绝放置+DENY 音效——堵死「右键把自己嵌进方块=莫名卡住」的根源）；②按住连续挖/放（rightClickDelay 思想 0.2s 节流，mouseHeldL/R 按住态；垫脚越障不再狂点右键）；③疾跑（Ctrl+移动 速度 x1.35 + FOV 拉伸 8 度平滑反馈 fovEffectScale 思想，lastProjFov float 投影基准；输入层乘系数 Player 不改）；④操作卡第 11 条 CTRL 疾跑 + CONTROLS 页同步 SPRINT/HOLD=REPEAT。gate=PASS 22 出口全 0 且四指纹逐字节不变（43723456791b5364/34c8bb722c5a1a6f——门禁世界无键鼠输入，输入层改动天然零漂移）

[M3-STREAM-SCALE] engine/technical target=src/core/world/World.java(CellSet/rebuildIndexBegin/rebuildIndexStep/stepShift phase4/indexDelta) + src/core/systems/SocialSystem.java(邻域均匀网格) + src/render/lwjgl/Game.java(shifting 期暂停 tick) + src/core/sim/StreamChunkTest.java(第4段断言) + src/core/sim/M3Bench.java result=ok gate=STREAMCHUNK PASS(4 段全 true) note=M3 大世界规模流畅落地并达标（用户明确要求按无缝大世界标准做，不以 N≤24 够用为由偷懒）。问题定位（微基准拆解 160x112x160）：纯扫描 2.87M 格 = 8.0ms；+ new int[3]x97万 = 20.3ms；+ 逐个 TreeSet.add = 321ms —— 比较器+红黑树税 301ms（94%）才是 rebuildIndex 583ms 的绝对大头，不是地形生成。改造四步：① 索引表示 TreeSet<int[]> 换 CellSet（升序平铺 int[]，3 int/格）：全量重建走网格序扫描=天然升序→顺序 append（零移位零分配），增量变更走两分查找+arraycopy 移位；对外仍返回 Collection<int[]>，21 处调用点仅 1 处（CivilizationSystem.mine）需改类型。② setBlock 热路径去 TreeSet 插入，改为追加 (old→new, coord) 到 indexDelta 紧凑缓冲（零分配 O(1)），消费前 ensureIndex() 应用少量变更。③ rebuildIndex 三趟合一：主扫描同时完成 索引 append + surfaceCells/waterSurfaceCells 判定 + surfaceY 维护（用 surfaceTopY 暂存），消除对 97 万格的二/三次遍历。④ stepShift 新增 INDEX 阶段（phase 4）：把索引重建切成 x 条带按预算推进；契约变更 = isShifting() 期间渲染层须暂停 tick（Game.loop 已接线），因 INDEX 阶段索引不完整、GEN 阶段地形半新半旧，混态下 tick 会使演化失真；门禁/无头步进走同步 streamTo（预算=Integer.MAX_VALUE）→ 单次调用跑完，语义逐字节不变。⑤ M3②：SocialSystem.adjacencyAndMood 双重循环 → 均匀网格 3x3 邻域查询（判据/顺序不变，零漂移）。实测（M3Bench，200 次预热+独立实例+中位数）：单帧最坏 508ms → 11.5ms（44x）；COMMIT 帧最坏 677ms → 2.67ms（253x）；每帧平均 3.2ms；smoothness gain 1.3x → 11.8x；immediate（整窗同步）median 136ms。零漂移证据：四基线指纹逐字节不变 —— DET 43723456791b5364 / ZD 34c8bb722c5a1a6f / PHYSICS PASS / STREAMING 87d5bf1cecb4c628；全量 36 道门禁 40 处 *_EXIT 全 0。新增 StreamChunkTest 第 4 段断言：INDEX 最深分帧（每帧 1 条带，316 帧）与一次跑完的 window/hash/index/surface 完全一致。教训：Override / System 在本项目被自定义类遮蔽 → 内部类写注解/调用需 @java.lang.Override / java.lang.System；分帧期间的中间态一致性是硬约束 —— 凡切片重建派生结构，必须同时规定「谁能读、能否 tick」。

## [N0-PORTABLE-MATH] 联机前置：可移植性硬化（2026-09-14）

**需求**：用户提出「做联机的服务」。架构决策：A（确定性锁步）与 B（权威服务器）**不是二选一**——「客户端模型」与「权威进程住哪」是两个正交维度；统一为「transport-agnostic 的 headless 权威层」，本地服务（listen server）与专用服务器只是**同一份代码的两种启动参数**。方案见 `docs/NETPLAY_READINESS.md`。

**问题定位（侦察所得，非猜测）**：项目「同种子同输入逐字节复现」在**单机**成立，但 Java 规范明确规定 `Math` 的超越函数（`sin/cos/tan/exp/log/pow/hypot/…`）**不保证**跨平台逐位一致，只有 `StrictMath` 保证。换一台机器时 1–2 ulp 的差异会让 `ShrineSystem:38-39` / `TrialSystem:102-110` 用 `cos/sin` 选到**不同的方块坐标** → 直接改写 `mat` → 确定性锁步联机**立刻 desync**。

> 对照：地形生成本身是**安全**的——`World.cellHash` / `cellHash3` 是纯整数（long 乘/异或/移位），`valueNoise` 只用 `Math.floor` + IEEE-754 基本运算；渲染层 `Chunk.hash01` 也是纯整数。RNG 亦然：`simStream(name) = deriveStream(name + ":" + tick)` 是纯函数，不累积漂移。

**改造**：`src/core` 内 **56 处**非 `StrictMath` 超越函数调用全部改为 `StrictMath`，覆盖 **16 个文件**（`anim/Joint`、`audio/AudioSynth`、`content/ParticleSim`、`systems/{Beast,Civilization,Climate,Individual,Matter,Npc,Polity,Shrine,Social,Trial,Wind}System`、`world/{DayCycle,Player}`）。`Math.sqrt`（精确舍入）与 `Math.floor/ceil/abs/min/max/round`（精确定义）**一律不动**。

**新增第 37 道门禁 `PORTABLEMATH`**（`core/sim/PortableMathTest`）三重防假绿：① **清洁性**（`src/core` 非测试源禁用非 `StrictMath` 超越函数）；② **防假绿 A**（扫描到的非测试文件数 ≥ 120 —— 防「扫描器坏掉 = 零命中 = 假 PASS」）；③ **防假绿 B**（检测器必须在合成样本上真的报出违规，证明「零命中 = 真没有」而非「永远返回空」）。豁免 `*Test.java`/`*Bench.java`。实测输出：`POSITIVE_CONTROL detected=[pow, sin]`（`StrictMath.cos` 被正确忽略）、`SCAN_FLOOR scanned=150 skipped=38 floor=120`、违规 **0**。

**实测**：全量 **37 道门禁 41 处 `*_EXIT` 全 0**；四基线指纹**逐字节未变**——DET `43723456791b5364` / ZD `34c8bb722c5a1a6f` / PHYSICS PASS / STREAMING `87d5bf1cecb4c628`，且 NPC `8560321416dc6ac5` / MEGA `3a08e840e0d16524` / TRIAL `202b17462c8791d4` 同。说明**本机 JVM 上 `Math` 与 `StrictMath` 对这些输入结果一致**，迁移是**行为保持**的，同时又消除了跨平台风险 → 本轮**无需指纹演进**。

**留给后续**：`strictfp` / JDK 版本统一（建议客户端与服务器统一 **JDK 17+** —— 那里浮点默认严格，JEP 306）。下一步：**N1** `Intent` 编解码 → **N2 回放/录像**（关键枢纽，**不需要网络**即可回答「跨进程确定性是否成立」）→ N3 UDP 锁步（本地服务 + 专用服务器）→ N4 客户端预测回滚。

**教训**：接「做联机」这类大需求，先做**就绪度侦察**并**找出已经是资产的既有设计**（本项目即 `Intent` 输入边界 + `simStream` 纯函数 + 整数哈希地形），再决定架构；同时必须把「单机成立」与「跨机成立」分开验证——**门禁全绿不等于可移植**。

## [N0b-JDK-CROSSVALID] 跨 JDK 实证：Java 8 与 Java 26 逐字节一致（2026-09-14）

**背景**：用户问「我本地是不是有个 java 版本管理器」。实测：**没有命令行版本管理器**（无 SDKMAN / jabba / jenv / asdf / scoop / choco），但存在 `C:\Users\Administrator\.jdks`（**IntelliJ IDEA 管理的多 JDK 仓库**，带 `.intellij` 标记文件），内含两套 JDK：

| JDK | 版本 | 备注 |
|---|---|---|
| `corretto-1.8.0_502` | Amazon Corretto **8** (1.8.0_502) | 当前 `JAVA_HOME`，且是 PATH 首选 → `build_runner.find_exe()` 按 PATH 优先命中它，故项目规范工具链 = Java 8 |
| `openjdk-26.0.1` | OpenJDK **26.0.1**（2026-04-21） | 带 `javac`，可独立编译 |

另有 `~/.gradle`、`~/.m2`（Maven/Gradle 用户目录），以及 `.workbuddy/binaries`（WorkBuddy 自带的 python/node 运行时）。

**实验**：把 `PATH`/`JAVA_HOME` 指向各自 JDK，**分别用两套 JDK 编译并运行全量门禁**（`build_report.txt` 的 `JAVAC=`/`JAVA=` 两行确认取自对应 JDK）。

**结果：41 处 `*_EXIT` 全 0，且所有指纹逐字节相同。**

| 指纹 | Corretto 8 | OpenJDK 26 |
|---|---|---|
| DET | `43723456791b5364` | `43723456791b5364` |
| ZERO-DRIFT | `34c8bb722c5a1a6f` | `34c8bb722c5a1a6f` |
| STREAMING | `87d5bf1cecb4c628` | `87d5bf1cecb4c628` |
| NPC / SOCIAL / STORYTELLER | `8560321416dc6ac5` / `ed4b43107588cb41` / `1ea5d40a3c17c732` | 同 |
| CIV·IND·POLITY·MATTER / TRIAL / MEGA | `1610d5235a5300db` / `202b17462c8791d4` / `3a08e840e0d16524` | 同 |

**意义（三条）**：
1. 这是**联机方案 A（确定性锁步）的实证地基** —— 不是「理论上可移植」，而是**跨两代 JVM（相隔 18 个大版本）实测成立**。两端只要同 seed + 同 Intent，世界就是同一个。
2. 它同时反证了 **N0 的 StrictMath 迁移行为保持**：若迁移改变了任何输出，这里必然分叉。
3. **冷水 2（JDK 版本漂移）实测不存在**：Java 17+ 起浮点默认严格（JEP 306），且与 Java 8 结果一致 → `strictfp` 的担忧自动消失。

**收尾**：实验后已用 Corretto 8 重跑还原规范工具链状态（`out/` 内 278 个 class 文件 major version 均为 **52** = Java 8，避免 `UnsupportedClassVersionError` 影响 `run-game.bat`）。

**建议**：客户端与服务器统一到 **Java 25 LTS**（26 非 LTS）；N3 起把「目标 JRE」写进构建约定，并在握手时交换版本号，不一致直接拒绝。本项目仍以 Corretto 8 为规范工具链，**不改基线**。

**教训**：把「跨平台可移植」当成**可实验验证的命题**而不是纸面推理 —— 手里有两个 JDK 时，跑一遍双 JDK 门禁对比，成本约 1.5 分钟，却能把「理论可移植」升级为「实测可移植」。

## [N1-NET-CODEC] 联机输入编解码：Intent ⇄ 6 字节（2026-09-14）

**需求与决策**：用户拍板「**一开始就上预测回滚**」并要求开始 N1。这条决策把 N2 从"回放功能"升级为"**回滚原语**"（详见方案 §5.2）。

**新增文件**：
- `src/core/net/IntentCodec.java` —— `Player.Intent` ⇄ **6 字节**。布局（显式大端，杜绝平台差异）：`type u8 | dx i8 | dz i8 | yaw u16 | blockIdx u8`。类型码是**显式契约** `IDLE=0 / MOVE=1 / REPEL=2 / ATTACK=3 / BUILD=4`（`switch` 映射，不依赖枚举 ordinal —— 防"重排枚举即改协议"）。越界值 / 未知类型码 **抛 IllegalArgumentException 而非静默截断**（静默截断会把"输入层 bug"变成"两端悄悄分叉"）。新增包内 `require()` 做缓冲区边界显式检查。
- `src/core/net/InputFrame.java` —— 一个 tick 的全部玩家输入。布局：`tick i32 | count u16 | count × [playerId u16 | intent 6B]`，故 `byteSize(n) = 6 + 8n`（3 人 ≈ 30 B/tick）。**`playerIds` 严格升序 + 唯一性在构造时强校验** —— 锁步"应用一帧"的顺序必须全体一致，不能依赖网络到达顺序；把"顺序漂移"这类最难查的 desync 挡在门外。

**改动**：
- `Player.Intent` 新增 `yaw` **线路字段**（量化 uint16，0..65535 ↔ 0..360°）+ `of(Type)` 工厂 + `yawDeg(float)` / `yawDegrees()` 便捷换算。**当前仿真逻辑一律不读 yaw**（攻击走"最近敌兵"、建造落 `tryBuild` 在玩家自身格）→ **对 hashState 与全部门禁零影响**。之所以现在就加：锁步输入包格式一旦上线就不该再改，朝向本来就属于"输入"。
- `build_runner.py`：core 编译段新增 `collect("src/core/net")`；新增 `gate("NETCODEC", "core.sim.NetCodecTest")`。

**新增第 38 道门禁 `NETCODEC`**（`core/sim/NetCodecTest`，**14 项断言全绿**）：协议码稳定 / 类型映射往返 / 全类型无损往返 / 字段边界极值往返 / 6 字节且不越界（前后哨兵字节不被改写）/ yaw 角度量化 / 越界拒绝 / 未知类型码拒绝 / **截断拒绝** / 三方帧往返 / 帧字节确定性 / `intentOf` 查询 / 帧构造拒绝（重复 id、降序 id、长度不符、负 tick）/ **惰性证明**（2 万次编解码后 `world.hashState()` 逐字节不变，且带工作计数器防"循环被优化掉"）。

**门禁首跑即抓到一个真 bug（本轮的实证价值）**：截断缓冲原本抛的是 `ArrayIndexOutOfBoundsException`，而契约声明的是 `IllegalArgumentException` —— 调用方无法区分"包坏了"与"我代码写错了"。已修：新增显式 `require()` 边界检查。同时发现**我自己的报告过滤脚本把 FAIL 行藏住了**（只匹配 `ok  ` 前缀），差点误判为通过 → 解析构建报告时**必须同时检索 `FAIL` 行**。

**实测**：全量 **38 道门禁 42 处 `*_EXIT` 全 0**；四基线指纹**逐字节未变**（DET `43723456791b5364` / ZD `34c8bb722c5a1a6f` / PHYSICS PASS / STREAMING `87d5bf1cecb4c628`；NPC/SOCIAL/STORYTELLER/CIV·IND·POLITY·MATTER/TRIAL/MEGA 同）。

**下一步（N2 重新定义）**：交付物 = **`World` 内存快照 + 按 tick 重演**，而非"录像文件"。N2a 快照（双一致：快照→恢复 hash 一致 **且** 再跑 N tick hash 一致）→ N2b 重演（录→放 hash 全等）→ N2c 回滚（**黄金判据**：「跑 100 tick → 回滚到 90 → 用修正输入跑到 100」== 「一开始就用修正输入跑到 100」）。现有 `World.save/load` 是文件 IO，**不可用于每帧回滚**，必须另做内存版。

## [N2-PREP-SAVE-FIX] 栈前量化推翻 N2 原方案 + 修掉两个真实立项 F bug（2026-09-14）

**背景**：用户定「一开始就上预测回滚」并要求推进。回滚 = 回到过去某 tick 的快照 → 用修正输入重演，故第一步是「World 内存快照」（原 N2a）。原计划**复用现成的 `World.save/load`**（它们已是 `OutputStream`/`InputStream`，可走 `ByteArrayStream`）。

**按「规则 0：先探针量化，再动手」实测**（真实 Game 尺寸 160×112×160 + `Simulation`，92 系统全开）：

| 指标 | 实测 | 判定 |
|---|---|---|
| 快照尺寸 | **22,250,720 字节 ≈ 22.3 MB**（tick 60，**无玩家编辑**） | ❌ 22 MB/帧 @20Hz = 440 MB/s |
| 尺寸成因 | `save()` 先 `snapshotInWindowEdits()` → 把**所有被系统写过的块**全量落盘（≈229 KB/块）；真实世界里系统大量写块 | 结构性，非优化问题 |
| 读档后实体层 | `npcs=0`、`shrines=0` | ❌ 不持久化 |
| 读档后继续跑 120 tick | hash 分叉：`95febe9ac33b24d3` vs `4539c8fe5a1a2ad8` | ❌ 存档→读档 ≠ 等价 |

**结论：N2 不能建立在 `World.save/load` 之上。** 它是为"断点续玩"设计的（不要求逐字节等价、不要求快）；回滚要求「完整 + 紧致 + 快」。方案已修订为 N2-0 完备性 → N2-1 紧致快照（基线=seed 地形，只存差分）→ N2-2 原地恢复 → N2-3 回滚。

**顺带修掉两个真实 bug（均已加回归护栏）**：

1. **读档必崩**：`TrialSystem` 只在 `tick == 1` 用子流 `trial:place` 布点，而 `World.load` 只恢复状态、不跑 tick → ext 段读 `w.trials.sites.get(i)` **越界崩溃**。即"只要试炼点已布点，读档必崩" = 真实世界的**每一次**存读档。修：`load` 内若存档有布点（`ns > 0`）且当前为空，则以规范的 `tick = 1` 子流重建（`TrialSystem.placeOnLoad`）—— `deriveStream(name) = SHA256(seed + "|" + name)` **只依赖 seed 与名字、与 rng.state 无关**，故可逐位复现原始布点（`sx/sz` 完全一致）。
2. **读档后世界冻结**：`Simulation(World)`（读档装配路径，`Game` 的启动自动读档 + F10 两处都用它）**漏调用 `registerDefaultSystems()`** → 读档后 `systemCount()=0`，NPC / 文明 / 试炼 / 民政**全不演化**，只剩地形与玩家能动。修：`if (w.systemCount() == 0) registerDefaultSystems();`（幂等）。

**为什么门禁此前完全没抓到**：旧 `SaveLoadTest` 用**裸 `World`**——`addSystem` 从未被调用 → 系统数为 0 → 走的是"无系统"降级路径，两个 bug 都无法触发。**这是门禁覆盖盲区**。已补 **场景 2**：用真实 `Simulation`（92 系统）跑 60 tick → save → load，断言 `systemCount` 保持、试炼点/补给箱数量保持、`hashState` 相等，并在输出里显式打印 `[KNOWN GAP]`（实体层未持久化 + 快照尺寸）—— **把"尚未做到"变成可见事实，而不是沉默**。

**实测**：全量 **38 道门禁 42 处 `*_EXIT` 全 0**、0 条 FAIL；`SAVEL-GATE hash0=true hash300=true realPathLoad=true => PASS`；四基线指纹**逐字节未变**（修复对仿真零影响）。

**教训**：① 架构级方案**先探针量化再定**——N2 原方案看着天经地义，实测三刀全废；② **门禁走的是哪条路径**要和"玩家走的是哪条路径"对齐——同一份 `save/load`，测试走无系统降级路径、玩家走 92 系统路径，于是"门禁全绿"掩盖了两个必现 crash/功能失效。

## [N2-0-SNAP-STATE] 快照完备性：把「还剩多少没存」机械化成门禁（2026-09-14）

**问题**：回滚要求快照**完整**。但「完整」最怕的不是难写，是**沉默** —— 加一个字段忘了序列化不会有任何症状，直到某次回滚后世界悄悄走偏，而 `hashState()` 又因**故意做窄**而看不见。上一轮实测已证明：存档→读档在真实（92 系统）路径上会分叉，且实体层根本没存。

**做法（先量化再动手）**：先写一次性反射探针盘点 `core.world.World` 与 `core.world.Player` 的**全部实例字段**，再把这套分类**机械化**成第 39 道门禁 `SNAPSTATE`（`core/sim/SnapshotStateTest`）。

**门禁断言的性质（5 项）**：
1. **穷尽分类** —— 每个实例字段必须落进且仅落进一个桶：`PERSISTED` / `PARTIAL` / `GAP` / `DERIVED` / `DIAG` / `CONST`。**新增字段不分类即 FAIL**（这是核心价值）。
2. **桶互斥** —— 不允许一个字段同时在两个桶（防分类漂移）。
3. **防假绿** —— 反射枚举字段数必须 ≥ 下限（World≥70 / Player≥36），防「枚举走空 = 全通过」。
4. **缺口基线** —— 未持久化字段数（GAP+PARTIAL）必须等于记录值，任何增删都是一次**有意识的改动**（同 SYSTEMREG 的黄金序列思路）。
5. **无幽灵条目** —— 桶里不允许出现类中已不存在的字段名。

**实测（输出）**：`enumerated: World=72 Player=40`；`World: PERSISTED=16 PARTIAL=8 GAP=21 DERIVED=22 DIAG=5`；`Player: PERSISTED=11 GAP=28 CONST=1`；**未持久化字段合计 = 57**。
其中 `PARTIAL` 是最危险的一类（对象「看起来已覆盖」）：`civ` / `individual` / `polity` / `social` / `calamity` / `trials` / `matter` / `player`。例如 `individual` 只存了 10 个标量，而类里有约 100 个字段。

**这一步交付的不是「存好了」，而是把「还剩多少没存」从未知变成「57 项、可枚举、且不会再悄悄增长」** —— 从今往后任何人加字段都会被门禁当场拦下要求分类。

**实测**：全量 **39 道门禁 43 处 `*_EXIT` 全 0**、0 条 FAIL；四基线指纹**逐字节未变**（本门禁只做静态分类，不碰 World 实例）。

**踩坑**：新建门禁时漏写 `ok(...)` 辅助方法 → CORE 编译失败（5 处「找不到符号」）；另外一次性脚本里在 Python 双引号字符串中混入 ASCII 双引号 → SyntaxError。**两者都靠"先看编译/退出码，再看报告文本"的顺序抓到** —— 报告解析脚本此前还由于只匹配 `ok  ` 前缀而漏看 `FAIL` 行，已一并纠正。

## [N2-0-DONE] 快照完备性达成「双一致」+ 架构级发现（2026-09-14）

**目标**：回滚要求快照**完整**。上一轮把"还剩多少没存"机械化成 `SNAPSTATE`（57 项缺口）。

**做法（反射式，加字段自动覆盖）**：新增 `core/net/StateCodec` —— 通用反射式状态编解码器：
① 字段按**名字排序**写出（不依赖 JVM 声明顺序，那不在规范保证内）；② **自描述类型名**（枚举/对象都写类名，故集合元素里的类型也能还原）；③ **原地恢复**（不 new 顶层对象，集合 clear 后重填、列表元素优先复用原位实例 → 保住对象身份，也让 `SeededRNG` 这类"构造后 seed 不可变"的类型能原地改状态）；④ 构造用 `Unsafe.allocateInstance` 兜底（数据类普遍没有无参构造：`Npc` 需 Body/Mind/Social、`Intent` 构造器私有）；⑤ **不支持的字段类型抛异常，绝不静默跳过**。
`World.save/load` 升至 ext **v5**，追加全状态快照：**World 63 + Player 38** 个字段 + **92 个系统实例的私有状态**。

**两次"final 字段"实测教训**（都踩过，已写进 codec 注释）：
1. 最初"跳过所有 final" → `npcs`/`beasts`/`shrines` 全是 `final List`（引用不可变、内容可变）→ **实体层被整层跳过**，读档后世界空心。
2. 改成"只跳过 final 原始类型/String/枚举/数组" → `World.Event` 的 4 个字段、`Trials.Site` 的坐标、`Chronicle.Episode` 的文本**全是 final**（不可变数据类的常态）→ 数据又被**静默**跳过。
→ 结论：**不能靠 final 判断"是不是状态"**。最终改为"写出全部非 SKIP 字段"，跳过与否完全由**显式清单**决定。

**架构级发现（本轮最大收获）：状态不只在 `World` 上。**
`WindSystem` 持有 `private int t = 0` 与 `gustX/gustZ` —— `t` 决定基础风向角。读档后系统是新注册的 → `t` 从 0 重来 → 风向立刻不同 → 生态/天气/方块写入全偏。**而读档瞬间的 `hashState` 是相等的**，只有继续演化才暴露。
落地：快照按**系统名**记录每个系统的实例状态字节；`World.load` 时系统尚未注册（注册属 `Simulation(World)`），故先暂存于 `pendingSystemState`，注册完成后由 `World.applyPendingSystemStates()` 按名字对位写入（按名而非顺序 → 系统列表顺序变化也不错位）。

**验收（双一致）**：`SAVEL-GATE` 新增 `doubleConsistency` —— 读档后两世界**再各演化 120 tick**，hash 仍逐字节一致。实测 `hashEqOnLoad=true hashEqAfter120=true`。
另做**双进程隔离实验**（dump 进程写快照 → load 进程读快照）排除"同 JVM 跨实例静态干扰"这个伪因：两进程 `h240` **完全相同**。

**定位方法（写下来复用）**：① `hashEq` 相等 ≠ 快照完整，验收必须是"读档后**继续演化**仍相等"；② 先双进程隔离排除伪因；③ 逐字段字节 diff（`StateCodec.writeAny`）直接给出"哪些字段不同"；④ **逐 tick 找第一个分叉点**把 120 tick 的模糊分叉收敛到 **tick 121**，再在该 tick 做字段 diff → 一次命中 `humidity`/`windX`/`windZ`。

**门禁改造**：`SNAPSTATE` 改为**与 codec 同源** —— `PERSISTED` 由 `StateCodec.writtenNames` 算出，门禁只维护 `SKIP_DOC`（**跳过字段 + 理由**）。于是：新增字段自动进入持久化（零维护、零缺口）；有人偷偷把字段加进 codec 的 SKIP → 立刻 FAIL 直到写下理由 —— **跳过永远是审查过的决定，不可能沉默发生**。实测：World 63/73 + Player 38/40 持久化，仅 12 个有理由的跳过。

**实测**：全量 **39 道门禁 43 处 `*_EXIT` 全 0**、0 条 FAIL；四基线指纹**逐字节未变**。

**余下（N2-1/2/3）**：快照仍 **23 MB**（`snapshotInWindowEdits` 把系统写过的**整块** 229 KB 全量落盘）→ 改为「seed 基线地形 + **稀疏差分**」；再补「原地恢复不 new World」与「回滚黄金判据」。
## [N2-SNAPSHOT-ROLLBACK] 2026-09-14 · 快照/回滚三件套收口（N2-1 紧致快照 + N2-2 原地恢复 + N2-3 黄金判据）

**问题定位（先量化，结论推翻直觉）**：N2-0 之后快照仍 22.25 MB。逐段拆开看，
`mat`/`mass` 早已在 `StateCodec.SKIP` 里 —— 22 MB 完全来自 `save()` **显式**落盘的
`chunkEdits`（每块 16×112×16×(int+float) = 229 KB）。再量「与 seed 确定性地形基线的差分密度」：
差异格数 **28,699 / 2,867,200 = 1.0009%**，且 **100/100 块均有差异**（每块 18~406 格，无「整块大改」离群）
→ 差异高度稀疏、分布均匀 → **稀疏差分**是最优解。

**改造（三件套）**
1. **N2-1 紧致快照**：新格式 `BWORLD2` —— 窗口内编辑只存「与原始地形基线的差分」：
   `long key | int n | int idx[n] | byte mat[n] | float mass[n]`（9 B/格）。新增 `core/net/ChunkDiff.java`；
   `World.captureWindowDiffs()` / `applyWindowDiffs()` / `diffAgainst()`；`save/load` 走稀疏段；
   窗口**外**的编辑块仍整块落盘。删除已冗余的 `snapshotInWindowEdits()`（窗口编辑不再污染 chunkEdits）。
2. **N2-2 原地恢复**：`World.restoreInPlace(byte[])` —— 不 `new World`、不重注册系统，
   先**强制重建**窗口基线（**不能复用** `restoreWindowOrigin`，它同原点会提前返回不重生成）再叠差分；
   `load`/`restoreInPlace` 重构为共享 `readInto(in, sparse, target)`；seed/尺寸不符**响亮抛错**；
   `target != null` 时立刻 `applyPendingSystemStates()`。渲染派生状态（`dirtyChunks`/`lightDirty`）恢复后显式整体标脏。
3. **N2-3 回滚**：`RollbackTest` 黄金判据 —— 「跑 N → 回滚 RB → 用修正输入跑到 N」==「一开始就用修正输入跑到 N」。
   实现更狠一层：回滚世界取完快照后**先故意用错误输入跑偏**，再原地恢复 → 同时证明「恢复能把走歪的世界拽回」。

**途中挖出并修掉的三个「不会让门禁变红」的坑**（本轮最大价值）
1. **派生字段被当成状态写入**：逐字段量 v5 反射段（1,331,786 B）→ `surfaceCells` 651 KB /
   `surfaceY` 130 KB / `surfaceTopY` 130 KB / `waterSurfaceCells` 5.5 KB = **916,629 B（69%）**，
   而它们紧跟 `StateCodec.read` 之后就被一行 `rebuildIndex()` 全量重算。**原本的门禁对此完全沉默**
   （只断言「要么持久化、要么有理由」，而派生字段被持久化是「合法」的）。
   → 补第 ⑥ 条断言 `DERIVED_FIELDS_NOT_PERSISTED`：**「派生」必须是可断言的性质**。
2. **`SKIP` 按纯字段名 = 全局匹配**：`ContentSystem.registry`（内容注册表 / 装配句柄）与 `World.registry`
   同名而被「顺带」跳过；`ChunkDiff.mat/mass`、`InputFrame.intents` 同理。**「因重名而恰好正确」是偶然** ——
   一旦改名，跳过就无声消失。→ `SKIP` 改为**类限定名**，每个跳过都是针对某一类的显式决定。
3. **幽灵条目**：字段改名后旧 SKIP 条目会永远留着。→ 补 `NO_PHANTOM_SKIP`（每条 SKIP 必须是该类的真实实例字段）。
   顺带把 `Civilization.urbanOrder`（`int[51200]` = 256 KB，可由 `(SX,SZ,centerX,centerZ)` 确定性排序重建，
   `null` 时 `buildOrder()` 自动重建）纳入跳过。

**实测**
| 指标 | 改造前 | 改造后 |
|---|---|---|
| 快照字节 | 22,250,720 B | **317,330 B（70x）** |
| 窗口差分格数占比 | — | **0.65%** |
| 构成 | — | 窗口稀疏差分 ≈156 KB + v5 反射段 ≈155 KB（对半） |
| 双一致（save→load→再演化 120 tick） | ✅ | ✅（未回归） |
| 回滚黄金判据 | — | ✅ `h@rollback == h@direct` |

**门禁**：新增 **第 40 道 `NETSNAP`**（字节预算 / 压缩比 / 稀疏性 / 结构等价 / 双一致 / 防假绿，7 项断言）+
**第 41 道 `ROLLBACK`**（原地恢复精确 / 恢复前已跑偏 / 回滚==直跑 / 拒绝不匹配 / 惰性，5 项断言）；
`SNAPSTATE` 由 4 项扩到 6 项（+ 派生字段禁持久化 + 无幽灵条目）。**41 道门禁 / 45 处 `*_EXIT` 全 0、0 FAIL**。
四道基线指纹**逐字节不变**：DET `43723456791b5364` / ZD `34c8bb722c5a1a6f` / PHYSICS PASS / STREAMING `87d5bf1cecb4c628`。

**余下（诚实清单，列为 N3 前置）**
- **每 tick 取快照仍不可行**：`captureWindowDiffs()` 内含一次全窗基线重生成（204 ms）。数据量不是瓶颈
  （317 KB × 60 帧 ring = 19 MB），**时间**才是 → 需 ① 缓存基线（窗口不动时基线不变）+ ② 用增量脏格记录替代全窗差分扫描。
- **窗口外的编辑块仍整块落盘**（229 KB/块）：`Megalith.fillChunk` 选址/地面高度会读**邻列** `mat`，
  故「原始地形」只在整个窗口按固定序全量生成时才可复现 → 异窗上下文里稀疏差分**不安全**。块数只随玩家实际路径增长（有界）。
- **`Player.intents` 不进快照**：它属外部输入（N1 `InputFrame`），回滚时由输入层重新注入 —— 但这是**必须写明的契约**：
  若某端一 tick 注入多条，队列会残留。

**方法论（三条，已进技能 `determinism-gated-port`）**
1. **「不完备」与「膨胀」是两种沉默**：前者让回滚走偏，后者让快照涨回去 —— 都要**静态断言**钉住，不能靠「写的人记得」。
2. **按名字匹配的开关表是全局的**：任何「按名字跳过/启用」的机制都必须限定作用域，否则会因**重名**静默生效或静默失效。
3. **「派生」是可断言的性质**：`x 能被一行代码重算` ⇒ `x 不该进快照`，这条关系要写成门禁，而不是注释。
## [N3-LOCKSTEP-CORE] 2026-09-14 · 锁步协议层：netHash 宽哈希 + Transport/LockstepSession（N3-0/N3-1）

**目标**：补齐锁步的两块地基 —— desync 检测用的宽哈希，以及锁步会话本身（协议层）。
**关键取舍：协议层不碰 socket** —— `Transport` 抽象 + `LoopbackTransport`（一对交叉队列 + 可调延迟）
就能把帧序、锁步定序、desync 检测全部测掉；真正的 UDP 之后实现同一接口即可，会话代码零改动。

**改造（3 个新文件 + 1 个新 API）**
1. `core/net/Transport.java` —— 传输抽象：`send/recv/close`；`recv()` **必须非阻塞**（无消息返回 null）；
   `send(null/空)` 一律抛（静默丢包会把上层 bug 变成莫名卡顿）。
2. `core/net/LoopbackTransport.java` —— 一对**交叉**队列 + 信封投递时钟（延迟单位 = 网络回合）；
   线路拥塞**响亮抛错**而非静默丢弃。
3. `core/net/LockstepSession.java` —— 协议三条：① 锁步（缺任何玩家的帧就不推进）；② 输入延迟 D
   （本地在 S 生成的输入供 S+1+D 用）；③ desync 检测（定期互发 netHash）。
   消息两条：`MSG_INPUT`（`InputFrame.pack()`）+ `MSG_HASH`（tick i32 + netHash i64）。
4. `World.netHash()` —— **与快照同源**的宽哈希：`hashState() + FNV(StateCodec.encode(world))
   + Σ FNV(系统名 + encode(系统))`。**编码覆盖什么就检测什么，新增字段自动纳入，不存在第二份名单。**

**实测**
| 门禁 | 断言 | 结果 |
|---|---|---|
| 第 42 道 `NETDESYNC`（7 项） | 同源一致 / **宽于 hashState** / 敏感（1 格）/ 可区分种子 / 惰性+确定 / 成本预算 | PASS，单次 **21 ms** |
| 第 43 道 `NETLOCK`（5 项） | 收敛（2 回合延迟跑 40 tick）/ **输入延迟≠语义**（D=0 与 D=3 同终态）/ **缺帧不推进** / 补录被拒 / **desync 可检出** | PASS |

**43 道门禁 / 47 处 `*_EXIT` 全 0、0 FAIL；四基线指纹逐字节不变**
（DET `43723456791b5364` / ZD `34c8bb722c5a1a6f` / PHYSICS PASS / STREAMING `87d5bf1cecb4c628`）。

**被门禁抓出来的两个测试错误（写下来，下次不再犯）**
1. **故障注入必须包在真实对端上**：第一版把 corrupting wrapper 包在一个**新建的孤儿线路**上，
   B 什么都收不到 → 测成了"缺帧"而不是"篡改"。门禁以 FAIL 指出。
2. **浮点往返不精确**：`civ.research += 1f; -= 1f` 后 `0.7f → 0.70000005f`（0.7 本就不能被 float 精确表示），
   `RESTORED` 断言红。改用**整数**字段（`civ.temples`）往返即绿。
   —— 这不是 netHash 的 bug，恰恰证明它**连 1 ulp 的浮点漂移都抓得到**。

**边界（诚实清单）**
- `World.player` 是单数：多玩家意图的"应用"由 `InputSink` 注入（本层只管定序与齐帧）→ N4 补。
- UDP/TCP 未实现；TCP 需长度前缀帧器。
- 暂停语义：`advance()` 在 `world.paused` 时不推进（暂停须全局一致）；正式暂停控制消息待定。
- 预测回滚（N4）前必须先做：缓存基线 + 增量脏格记录（现在每 tick 取快照含 204 ms 基线重生成）。

## [N3-UDP-LOCKSTEP] 2026-09-14 · 真实 UDP 传输 + 三启动形态（N3-2）

**目标**：把 N3-1 的锁步协议层接到**真操作系统套接字**，并用**一份权威代码**撑起三种启动形态（`--host`/`--dedicated`/`--join`）。联机路线 N0→N3 至此闭环。

**关键取舍：协议层零改动** —— `UdpTransport` 与 `LoopbackTransport` 实现同一 `Transport` 接口，`LockstepSession` 对其一无所知；N3-1 测过的「收敛/缺帧不推进/输入延迟仅调度旋钮/篡改一字节两端都报」在真网络上逐字成立。这正是 NETPLAY §6「客户端模型 × 权威进程住哪是两个正交维度」决策的兑现。

**改造（5 个新文件）**
1. `core/net/UdpTransport.java` —— UDP `Transport`：`DatagramChannel` 非阻塞 recv；`join(host,port,timeout)` 并行连、人齐才 `connect` 收 WELCOME、重试超时响亮抛 `IOException`；`parseWelcome` 从正确偏移读 `yourId`/`playerIds`/`seed`。
2. `core/net/UdpRelay.java` —— 星形中继：`bind(port,seed,players)` 起后台线程收 HELLO，**人齐一次发 WELCOME**；重复 HELLO **幂等重发**（防幽灵玩家）；人齐后 HELLO 计数 `rejects++`。
3. `core/net/LockstepRunner.java` —— 无头锁步驱动器（`--host`/`--join` 共用）；`run` 收尾打印 `FINAL tick=.. hash=.. desync=..` 供跨进程门禁断言。
4. `core/net/NetMain.java` —— 联机入口：`--host`（起中继+自己也是客户端）/ `--dedicated`（纯中继无仿真）/ `--join`（纯客户端）；`--out` 双写日志文件。
5. `core/sim/UdpLockTest.java` —— 第 44 道门禁 `UDPLOCK`。

**实测**
| 门禁 | 断言 | 结果 |
|---|---|---|
| 第 44 道 `UDPLOCK`（4 项） | `UDP_TWO_CLIENTS_CONVERGE` / `UDP_THREE_CLIENTS_CONVERGE` / `LATE_JOIN_REJECTED` / `SUBPROCESS_CROSS_JVM` | PASS |
| 跨 JVM 子进程 | `--host` 与 `--join` 各起真实 JVM，FINAL hash 逐字节相同 | `d978824b368f0c71` / `desync=false` |

**44 道门禁 / 48 处 `*_EXIT` 全 0、0 FAIL；四基线指纹逐字节不变**
（DET `43723456791b5364` / ZD `34c8bb722c5a1a6f` / PHYSICS PASS / STREAMING `87d5bf1cecb4c628`）。

**途中踩的坑（写进 `OPEN_ITEMS.md` §24.5(d) + `NETPLAY_READINESS.md` §5.9(d)）**
1. **并行纪律**：握手要等人齐才发 WELCOME —— 客户端必须并行 join；串行 join 会死锁（第一版以"加入超时"暴露）。
2. **`NotYetConnectedException`**：join 成功拿到 WELCOME 后才 `ch.connect(server)`，否则 recv 抛未连接异常。
3. **幽灵玩家**：join 重试每次新建 channel + relay 对重复 HELLO 不幂等 → 多出一个 `id=1` 的幽灵；修：channel 在循环外建 + relay 重复 HELLO 幂等重发。
4. **`ProcessBuilder(List)` 别名 bug（最阴）**：Java 8 的 `new ProcessBuilder(List)` 存引用不拷贝，两 builder 共用 `base` → 第二个 `addAll(--join)` 把 `--join` 也追加进 host 命令行 → host 被解析成 join 模式（mode 覆盖）→ 全场无中继 → 双双超时。修：每个 builder 一份独立 `new ArrayList<>(base)` 拷贝。
5. **日志必须走文件**：stdout 管道在子进程被 `destroy()` 时静默吞 IO，跨进程门禁读不到 `FINAL` → `NetMain` 加 `--out` 双写文件，门禁从文件读。
6. **前向引用**：`cmdH.addAll("--out", fHost.getAbsolutePath())` 在 `fHost` 声明之前 → 编译期 `找不到符号`；文件在引用前创建。

**边界（诚实清单，N4 前置）**
- **每 tick 取快照仍不可行**：`captureWindowDiffs()` 含一次全窗基线重生成（204 ms）；需缓存基线 + 增量脏格记录。
- **`World.player` 是单数**：多玩家意图应用由 `InputSink` 注入（只应用 `playerIds[0]`）→ N4：`remotePlayers[]` 或多玩家意图按 id 对位注入。
- **预测回滚未接**：回滚原语（N2-3 黄金判据）已就绪，但未在 tick 循环里驱动"本地预测 + 权威到达回滚"。
- **接入渲染主循环**：`NetMain` 是无头驱动器，用于协议验证与跨进程门禁；接进 `render/lwjgl/Game` 主循环属 N4/N5。
- **TCP / 房间大厅服务**：TCP 未实现（同一 `Transport` 接口、会话零改即可加）；房间服务按需（N4/N5）。

## [N4-PREDICTIVE-ROLLBACK] 2026-09-14 · 预测回滚（GGPO 式客户端预测 + 权威帧到达回滚重演）

**目标**：在 N3 纯锁步之上叠加**客户端预测 + 回滚**——本地意图即时跑在前面（零手感延迟），远端意图延迟到达时用「重复上次确认远端意图」预测，权威帧与记录帧不符则原地回滚 + 重演。终态须与纯锁步（D=0）逐字节一致（确定性卖点延续）。

**关键取舍：不碰 `World.player` 复数化** —— N4 用 `PredictiveSession` 经既有 `InputSink` 按 `playerIds` 升序对位注入多玩家意图，绕开「`World.player` 改复数」这个会牵动全工程的改动；`World` 这边只新增**可重算缓存**（`baseMat/baseMass` + 失效标志），非仿真状态，已进 `StateCodec.SKIP`。

**改造（3 个新文件 + 3 处小改）**
1. `core/net/RollbackEngine.java`（NEW）—— `Frame{tick, intents[], snapshot[]}` + `TreeMap` 环形缓冲；`record`/`frameAt`/`snapshotAt`/`pruneBefore`/`pruneAfter`。
2. `core/net/PredictiveSession.java`（NEW）—— `InputSink`/`LocalIntentSource` 接口；`advance`（本地即时 / 远端延迟到则重复上次确认意图 / 否则 `predictRemote` 取最近远端意图）/ `receiveFrame`/`setAuthoritative`/`reconcile`（从 tick=1 起到 predictedTick 找首个已权威齐备但记录帧≠权威帧 → `rollbackTo`）/`rollbackTo`（恢复 snapshotAt(t) 原地 + 截断后续记录 + 触发重演）/ `predictRemote`/`recomputeConfirmed`。
3. `core/world/World.java` —— 新增 `baseMat/baseMass/baseWinCX0/baseWinCZ0/baselineValid/baselineRegenCount`；`ensureBaseline`（懒生成 pristine 世界数组，窗口原点变化才失效）/ `invalidateBaseline` / `copyBaseToMat` / `diffAgainstBaseline`（与旧 `diffAgainst(pristine)` 逐字节等价）；`captureWindowDiffs` 改走缓存基线；`restoreWindowOrigin`·流式平移·`readInto` 原地去重均接 `invalidateBaseline`/`ensureBaseline`；新增 `World.snapshot()`（包 `save` 返回字节数组，不改动仿真状态）。**消除每 tick 204ms 全窗重生成**：窗口不动时基线零重生成。
4. `core/net/StateCodec.java` —— `SKIP` 表新增 6 项（`baseMat`/`baseMass`/`baseWinCX0`/`baseWinCZ0`/`baselineValid`/`baselineRegenCount`），它们是可重算缓存；`SnapshotStateTest.SKIP_DOC` 同步 + `RECORDED_SKIPPED` 19→25。
5. `core/sim/PredRollbackTest.java`（NEW）—— 第 45 道门禁 `PREDROLLBACK`（`SEED=0x9A77C0DE`、N=40、D=4、双玩家：本地即时 + 远端驱动）。
6. `build_runner.py` —— `gate("PREDROLLBACK", "core.sim.PredRollbackTest")`（44→45 道 / 48→49 处退出码）。

**实测（第 45 道门禁 `PREDROLLBACK`，5 项断言全绿）**
| 断言 | 结果 |
|---|---|
| `FINAL_EQUALS_REFERENCE` | ✅ 延迟 D=4 预测回滚终态 `netHash` == 纯锁步（D=0）参照 `netHash` |
| `ROLLBACK_HAPPENED` | ✅ 预测与真实远端意图不同 → `rollbackCount > 0`（真回滚，非"延迟≠语义"假象） |
| `NO_ROLLBACK_STILL_CONVERGES` | ✅ 纯锁步参照 `rollbackCount == 0` 且终态一致 |
| `DETERMINISTIC_REPLAY` | ✅ 重演路径终态 == 预测路径终态 |
| `BASELINE_CACHED` | ✅ `baselineRegenCount <= 3`（基线懒生成生效，消除每 tick 204ms 全窗重生成） |

**45 道门禁 / 49 处 `*_EXIT` 全 0、0 FAIL；四基线指纹逐字节不变**
（DET `43723456791b5364` / ZD `34c8bb722c5a1a6f` / PHYSICS PASS / STREAMING `87d5bf1cecb4c628`；NPC/SOCIAL/STORYTELLER/CIV·IND·POLITY·MATTER/TRIAL/MEGA 同）。

**途中踩的坑（写进 `OPEN_ITEMS.md` §24.6(c) + `NETPLAY_READINESS.md` §5.10(c)）**
1. **`TreeMap.tailMap` 返回 `SortedMap` 而非 `NavigableMap`**：`RollbackEngine.pruneAfter` 调 `frames.tailMap(tick+1)` 后 `.pollFirstEntry()` 编译失败（CORE 段编译崩溃，连带 PREDROLLBACK 报"class not found"）。修：两参重载 `tailMap(tick+1, true)` 返回 `NavigableMap`。
2. **`StateCodec` 把缓存基线当状态序列化**：新增 `baseMat/baseMass`（各 160×112×160 全栅）= 每快照膨胀 +23 MB → `NETSNAP` 1 MB 预算失败（先误判为 stale-class 假象，干净重建后确认是真回归）。修：`SKIP` 补 6 项 + `SnapshotStateTest.SKIP_DOC` 同步。
3. **预测回滚驱动收敛 guard 偏紧**：非周期远端意图下每个 tick 都回滚，最坏 ≈ N·(D+1) ≈ 200 次迭代，超出旧 guard `(N+5)*4 = 180` → 误报"未收敛"。修：guard 提到 `(N+5)*(D+5)`。
4. **`out/` 陈旧类误导**：CORE 段编译失败时旧 `World.class` 留盘，会让 `NETSNAP` 假性失败、掩盖 `PREDROLLBACK` 真实逻辑 bug。修：重命名 `out/` 触发干净重建（`BW_KEEP_OUT=1` 仅跳过 `shutil.rmtree`，仍会全量重编）。

**边界（诚实清单，N5 前置）**
- **接入渲染主循环**：`NetMain` 是无头驱动器；接进 `render/lwjgl/Game` 主循环（tick 由会话推进、窗口照常渲染）属 N5。
- **房间/大厅服务**：中继已能做，无"找房间/状态同步"层，按需（N5）。
- **多玩家意图按 id 注入**：已通过 `PredictiveSession` + `InputSink` 绕开 `World.player` 单数，门禁验证两玩家驱动下终态==纯锁步。
[N5-RENDER-LOOP] agent target=src/core/net/TickBody.java + src/core/net/LockstepSession.java + src/core/net/PredictiveSession.java + src/core/net/LockstepRunner.java + src/core/sim/NetIntegTest.java + src/render/lwjgl/Game.java result=ok gate=PASS(46 道门禁全绿，50 处 *_EXIT 全 0) note=联机会话接入渲染主循环，联机路线 N0→N5 闭环。抽象 core/net/TickBody 整 tick 推进体（含 rules/techs/effects 内容层，与单机演化逐字节等价）；LockstepSession/PredictiveSession 新增带 TickBody 构造（默认 world::tick 向后兼容 NetMain 无头路径），advance() 内 sink.apply(t,arr); tickBody.tick(world)；LockstepRunner.open 重载注入。Game 解析 --host/--join/--port/--seed/--players/--input-delay，联机态由会话驱动完整整 tick、本地移动改按 tick 喂 Intent.move（physicsTick 固定步长、零帧 dt，两端一致），单机分支逐字不变，finally 关 relay。Player 意图是 Deque（setIntent 入队/World.tick 消费 pollIntent），无公开 intent 字段 → 网络态用自定义 InputSink 把 arr[0] 暂存 applied[0] 再位移。第 46 道门禁 NETINTEG 三断言：CROSS_INSTANCE（跨实例收敛）/ WRAPPER_FAITHFUL（会话终态==直接 world.tick 演化；首跑因逐 tick 相位错开假阳性失败，改比终态后通过）/ TICKBODY_INJECTED（注入被使用）。四基线指纹逐字节未变：DET 43723456791b5364 / ZD 34c8bb722c5a1a6f / STREAMING 87d5bf1cecb4c628 / NPC 8560321416dc6ac5 / MEGA 3a08e840e0d16524。
[SUBSYS-CONTENT-WIRING] agent target=src/core/world/WorldConfig.java + src/core/systems/{Ascension,Hunger,Wire,Haul,Capture}System.java + src/core/content/RecipeBook.java + src/core/sim/{SubsystemTest,RecipeTest,PerfProbe,AblationProbe}.java + tools/WriteCostProbe.java + src/core/content/ParticleSim.java + src/core/systems/ErosionSystem.java + src/core/sim/Simulation.java + src/core/world/Player.java + src/core/world/Beast.java + src/core/systems/BeastSystem.java + src/core/net/StateCodec.java + src/core/sim/{SnapshotStateTest,SystemRegistryTest,MenuModel}.java + src/core/world/World.java（nonAirCells 惰性重建 + 两个派生脏标记进 SKIP） + src/render/lwjgl/Game.java + assets/content/items/orb.json + assets/content/rules/capture_ammo.json result=ok gate=PASS(52 道门禁 / 57 处 *_EXIT 全 0；审计 FAIL 0 / WARN 0) note=空转参数落地 + 死内容键清零（**零指纹变更**）。7 个「空转子系统参数」（erosionRate/ascensionThreshold/haulRate/hungerRate/orbItemCost/hpThreshold/wireRange）各配真实消费系统（新增 AscensionSystem/HungerSystem/WireSystem/HaulSystem/CaptureSystem，系统数 93→98，全部追加在注册序末尾）；3 个配方键（coal/ore/iron_bar）经 core/content/RecipeBook 显式契约（ore3+coal2→iron_bar1），TechTree 未知键进 recipeIssues 响亮记录不静默；审计 CONTENT_KEY_EXEMPT 清空（C8 报未接线 0 个）。三条零漂移设计原则：① 出厂默认 update() 首行返回（零 RNG 零写入）；② 注册序只追加在末尾；③ 参数落 World.config 且进 StateCodec.SKIP（配置不是演化量）。落点 Simulation.applyPreset 真消费 7 键 + world.config.reset() 防跨预设泄漏。玩家入口 Y=投捕捉球（Game.queuedCapture → CaptureSystem.tryCapture，两段判定：血量比≤hpThreshold + 背包 orb≥orbItemCost）+ MenuModel.CONTROLS 登记 + 审计 KEYCODE_NAME 补 89:Y；新物品 items/orb.json + 新规则 rules/capture_ammo.json（击退野兽 50% 掉 1 球）。新门禁 SUBSYS（9 断言：DEFAULT_NOOP / 每参数有牙 + 负例 / PARAM_RESET）与 RECIPE（6 断言：真实物品 / 键集合 / 可结算 / 真扣真产 / 不足则整条不结算 / 未知键响亮）。SYSTEMREG **有意重锁**：COUNT 93→98、GOLDEN_HASH 9078168720030413075、PRESET_PARAMS 断言反转为「不得再有 ?param= 且参数真落到 WorldConfig」。顺带修掉两个真问题：① ParticleSim.spawn 把 offset 当二元区间读却按 [dx,dy,dz] 索引 → 每次 spawn 必抛 ArrayIndexOutOfBoundsException:2（FX 门禁长期 EXIT=1、游戏内粒子全不显示；根因是 build_runner RC 恒 0 掩盖门禁红灯）；② 电路电源不能用 FIRE（FireSpreadSystem 每 tick 50% 概率灭火，火源活不过 2 tick）→ 改用 COAL_ORE。引擎调参（实测驱动）+ 热点修复。**先纠错**：第一版 SystemPerfProbe 把每个系统连续调用 60 次而不推进 tick → w.tick 不变 → simStream 返回同一 RNG + 世界状态冻结 → 给出 lava 47.6%/flood 22.4% 的假热点（该探针已删除；逐系统计时沿用既有 tools/SimPerf，它带「探针循环 hash == 真 tick hash」自检）。**正确归因用消融法**（新增 core.sim.AblationProbe）：关掉 ash（火山灰沉降）一个系统，tick 从 17.874ms → 4.188ms（省 76.6%）；sand 11.3%/pond 8.0%/vine 6.2%/flood 5.8%/biodiversity 5.1%/water 3.7%/snowcap 1.8%/lava 仅 1.7%；反直觉：关掉 flower 反而涨到 47.1ms（-163%）→ 成本高度依赖世界状态。**根因**：新增 tools/WriteCostProbe 实测 setBlock 本身只 0.3µs，成本在惰性 ensureIndex() 的 CellSet 插入/删除（arraycopy 移位 = O(该类型格数)）——本世界 nonAirCells=1,006,024 格、typeCells[STONE]=849,084 格，故任何跨 AIR 边界的写入一次就是兆字节级 memmove，而 ash 每 tick 往地表堆 ~27 格 STONE（正是最贵形态）。**已修**：nonAirCells 全仓唯一读者是 World.nonAirCells()，生产代码从不调用（只有 StreamChunkTest 与 tools 读）→ 改为惰性重建（跨 AIR 边界只标脏，按网格序 x→y→z 重建，与 rebuildIndex 的 nonAir 填充同源同序）→ tick median 19.65→5.13ms / p95 28.02→7.75ms / max 35.41→11.95ms（3.8x），60fps 预算内可容 tick 0.6→2.2；DET 74ad826636fe8eb2 未变、STREAMCHUNK（断言 nonAir 集合与迭代序等价）PASS。**踩坑**：新加的派生脏标记 World.nonAirStale 默认非 SKIP → 被编进 netHash，而生产世界里它几乎恒 true、restoreInPlace 后是 false → PREDROLLBACK 立刻红灯（FINAL_EQUALS_REFERENCE / NO_ROLLBACK_STILL_CONVERGES FAIL）；修法：World.nonAirStale 与同类隐患 World.indexStale 一并进 StateCodec.SKIP + SNAPSTATE.SKIP_DOC + MUST_BE_SKIPPED，RECORDED_SKIPPED 27→29。**帧预算据此重定**：MAX_STEPS_PER_FRAME 3→2（p95 7.75ms×2=15.5ms 落在 16.67ms 内；×3=23.2ms 越预算）+ 单帧仿真时间盒 MAX_FRAME_SIM_MS=12.0 + 积压钳制 MAX_ACC_SEC=0.15；SHIFT_CHUNKS_PER_STEP 保持 8；审计 C12 依据同步为「tick p95 7.75ms」。**第二刀（同日）：CellSet 覆盖层改造** —— addCell/removeCell 原为 arraycopy 移位 = O(该类型格数)（typeCells[STONE] 84.9 万格），改为**待加入/待删除两个有序覆盖层 + 压实**：增删只改覆盖层，迭代时把「基线 − 待删除」与「待加入」归并输出（仍升序、仍每格一次），覆盖层合计超 OVERLAY_LIMIT 即压实一次（O(基线) 归并）→ 摊还 ≈ |基线|/1024；OVERLAY_LIMIT 由实测扫描定为 1024（256/512/1024/2048 → median 2.17/2.08/2.01/1.95ms、p95 7.64/4.90/4.43/4.33ms）。**对外契约完全不变**。零漂移证据：DETERMINISM 74ad826636fe8eb2 未变 + STREAMCHUNK（专门断言 cellsOfType/nonAirCells 的元素集合与迭代序在分帧/即时两条路径下逐元素等价）PASS + PREDROLLBACK/SNAPSTATE/NETSNAP 全 PASS + 7 个指纹逐字节未变 + 52 道门禁 57 处 *_EXIT 全 0。**最终成绩**：tick median 19.65→2.01ms（9.8x）/ p95 28.02→4.43ms（6.3x）/ max 35.41→15.09ms；60fps 预算内可容 tick 0.6→3.8；消融 baseline 17.874→3.458ms（5.2x），成本不再集中于单点（sand 30.2%/ash 19.8%/pond 18.2%/flood 14.6%/snowcap 9.1%/lava 8.1%/water 5.2%），消融全跑耗时 2m24s→23s。帧预算据此定 MAX_STEPS_PER_FRAME=2（p95 4.43×2=8.9ms 落在 16.67ms 内）+ 时间盒 12ms + 积压钳制 0.15s；审计 C12 依据同步为「tick p95 4.43ms」。美术续批（纯渲染层，零漂移）：Game.drawRainOverlay 雨幕层（240 条斜雨丝，位置/速度全走 EffectQueue.hash01 纯哈希、复用既有 HUD 2D 四边形通道、不新增 GLSL）+ 雨雾 0.01→0.022 + 夜色下限 0.16→0.20 + 调色 饱和 1.06→1.10 / 对比 1.06→1.08 / 暗角 0.80→0.86 + 暂停主菜单页世界状态两行。锚点文档同轮同步：OPEN_ITEMS.md（状态锚点 / §0 / §1 / §6 指纹链 / 新增 §37）· task_board.json（updated / gates.count 50→52 / per_layer / open_items / 新任务条）· blackboard.json（gates_current 45→52 + 新增 subsys_content_wiring）· event_log.md（本条）· README.md · docs/ENGINE_MATURITY.md（新增「仿真 tick 成本」行）· VALIDATION_CHECKLIST.md · PORTING_PLAYBOOK.md · docs/PORTING_GAP.md · docs/UNIMPLEMENTED_2026-09-16.md（一、二节标记已落地）。四道仿真指纹逐字节未变：DET 74ad826636fe8eb2 / ZD 5ce9392207387ebf / STREAMING 87d5bf1cecb4c628 / NPC afa036e4b0065f35 / MEGA 070a02ddb58ea0eb / SOCIAL 0dc8d8bef2718582 / TRIAL 8352df75e6dcd359。
[CONTENT-DELIVERY] agent target=src/core/content/Preset.java + src/core/content/ContentRegistry.java + src/core/sim/Simulation.java + src/core/systems/HungerSystem.java + src/core/sim/SubsystemTest.java + assets/content/presets/{breathing_world,mythic_sandbox}.json result=ok gate=PASS(57 处 *_EXIT 全 0；审计 FAIL 0 / WARN 0；SUBSYS 10 properties) note=修「内容层→玩法层」的**投递路径**缺口。问题：ContentRegistry.modules() 全仓零生产调用 → 模块里声明的 params 只有解析、没有消费者 → 只在模块里出现的键（wireRange/hungerRate/orbItemCost）任何预设都送不到 World.config；表现为 palworld_like 写了 haulRate 1.5 但 wireRange 恒 0 → HaulSystem 永远没电 → 无人搬运在游戏里从不发生。即「消费端接线了、投递端没接」——7 个参数里实际有 3 个（外加搬运整条链）不可达。修法：Preset 挂上「启用模块的 params」（ContentRegistry.parsePresets 解析后注入，按 modules 声明序叠加、后者覆盖前者），Simulation.applyPreset 改两层应用（① 模块参数=默认值；② 预设自身 params 覆盖），抽出单点 applyParam() 让两来源走同一套键名映射与钳制（两处各写一份必然漂移），未知模块参数同样点名 ?modparam=（不让死配置沉默）。零漂移：breathing_world 显式写 hungerRate 0，使「模块+预设」叠加后仍==出厂值。顺带调平衡：HungerSystem.DRAIN_PER_TICK 0.05→0.01（rate1.0 时 100 点≈8 分钟；原值 100 秒就饿到扣血，一旦真打开会像 bug）。门禁：SUBSYS 增第 9 组 PRESET_DELIVERY（默认==出厂 / 模块参数真送达 / 预设覆盖模块 / 7 键各自都能被某个出厂预设送出非出厂值 → keysReachable=7/7），加组不加道 → 门禁数不变。内容：mythic_sandbox 加 orbItemCost 2（否则该键永远等于出厂值 1，可达性无从证明）。⚠️ 待用户定：peaceful_valley 启用 survival → 现在也带饥饿，要关在 peaceful_valley.params 加 hungerRate:0。教训：「接线」要分两半看 —— 消费端（谁读它）与投递端（谁把它送到消费者手里）；只测「手工设 config 后系统有没有牙」不够，必须有端到端断言。7 个指纹逐字节未变。
[MINIMAP] agent target=src/core/content/MapField.java + src/render/lwjgl/Game.java + src/core/sim/MenuModel.java + src/core/sim/FxTest.java result=ok gate=PASS(57 处 *_EXIT 全 0；审计 FAIL 0 / WARN 0；FX 20 properties) note=内容扩张第一项：小地图。六项候选（多阶段 Boss/招架/处决/小地图/合成台/箱子）里选它的理由：唯一纯渲染层（零指纹风险）+ 模型能在无头环境断言，且直接回应玩家报过的「不知道自己在哪」。新增 core/content/MapField（纯采样模型，沿用 ParticleSim/RainField 的先例：是数学的那部分放 core）：玩家周围 48×48 格地表俯视图 → 24×24 色，颜色直接用 Blocks.Block 自带 r,g,b（世界本色，不另维护调色板——两套必然分叉）× 高度明暗 heightShade∈[0.55,1.15]（否则同种石头铺满整图、起伏读不出来）；列顶是水（surfaceY+1 为 WATER）则按水色画（否则湖被画成水底地色、在图上消失）；纯读 mat/surfaceY，不写状态不碰 RNG → 零漂移。Game.drawMinimap：右上角 96×96 + 中心玩家菱形（与罗盘标记同形），复用既有 HUD 2D 通道（addRect2D/addDiamond2D/panel），不新增 GLSL。N 键开关（默认开；HUD 关或菜单开时不画；CONTROLS 登记，C9 过，39 条）。FX 门禁加 MAP 段 5 断言（加段不加道，15→20）：MAP_DET（同入参两次逐格一致）/ MAP_CENTER（中心格==玩家所在列取色，采样对齐没偏）/ MAP_WATER（人工铺一格水→按水色画，与期望色逐位相等）/ MAP_EDGE（贴角落采样→越界格==OUT_OF_WORLD，不崩不环绕，实测 432/576 界外）/ MAP_SHADE（高度明暗单调有界）。坑一：MAP_SHADE 首跑 FAIL 不是代码错，是断言浮点比较写死（0.55f+0.60f 在 float 下≈1.15000004 > 1.15f），已改带 1e-6 容差。坑二：audit_consumers.py 抓出 MapField.radiusBlocks() 零调用（注释写「给 UI 与门禁用」但两处都没用）→ 已删。7 个仿真指纹逐字节未变。
[CRAFT-CHAIN] agent target=ContentRegistry.itemForBlock + RecipeBook.craft/craftable + Game(I) + RECIPE gate result=ok gate=PASS(57 *_EXIT=0) note= 2026-09-17（内容扩张第二项）：**合成台 + 冶炼链可达性修复** —— 挖矿原来用 blockId.toLowerCase() 猜掉落，COAL_ORE→coal_ore、IRON_ORE→iron_ore，而配方要 coal/ore（原来无 block 字段）→ 整条冶炼链不可达。新增 ContentRegistry.itemForBlock 反向索引（item.block 为真相，旧内容回退命名匹配），coal 认领 COAL_ORE、ore 认领 IRON_ORE，删零引用 coal_ore/iron_ore。I 键手搓：RecipeBook.craftable + craft（真实 Game 消费者，富足全扣真产、不足整条不扣料），CONTROLS 补 I CRAFT (HAND)。RECIPE 增 CHAIN_REACHABLE/CRAFT_PAYS_ALL/CRAFT_SELECTS，7 properties；7 指纹不变。
