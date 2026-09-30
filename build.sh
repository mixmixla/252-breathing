#!/usr/bin/env bash
# 会呼吸的世界 · Java LWJGL3 版 —— 全量编译 + 17 道门禁
# 纪律：源清单一律用通配（与 build.bat / run-game.bat 对齐），新增 core 子包/渲染类无需改本文件。
# 注意：src/render/audio 必须排在 src/render/lwjgl 之前编译（Game 依赖 AudioOut，且它不需要 libs）。
set -e
cd "$(dirname "$0")"
rm -rf out && mkdir -p out

LIBS="libs/lwjgl-3.3.3.jar:libs/lwjgl-glfw-3.3.3.jar:libs/lwjgl-opengl-3.3.3.jar:libs/joml-1.10.5.jar"

javac -encoding UTF-8 -d out \
  src/core/rng/*.java src/core/world/*.java src/core/agent/*.java \
  src/core/systems/*.java src/core/audio/*.java src/core/sim/*.java
javac -encoding UTF-8 -cp out -d out src/render/software/*.java
javac -encoding UTF-8 -cp out -d out src/render/audio/*.java
javac -encoding UTF-8 -cp "out:${LIBS}" -d out src/render/lwjgl/*.java
echo "BUILD OK"

echo "=== GATES 1-4 (BASELINE: determinism + zero-drift + physics + streaming) ==="
java -cp out core.sim.DeterminismTest
java -cp out core.sim.ZeroDriftTest
java -cp out core.sim.PhysicsTest
java -cp out core.sim.StreamingTest

echo "=== GATES 5-11 (PER-LAYER: npc + social + storyteller + civ + individual + polity + trial) ==="
java -cp out core.sim.NpcDeterminismTest
java -cp out core.sim.SocialDeterminismTest
java -cp out core.sim.StorytellerDeterminismTest
java -cp out core.sim.CivilizationDeterminismTest
java -cp out core.sim.IndividualDeterminismTest
java -cp out core.sim.PolityDeterminismTest
java -cp out core.sim.TrialDeterminismTest

echo "=== GATE 12 (PLAYER ACTION LAYER: weapon arts behaviour + zero-drift invariant) ==="
java -cp out core.sim.WeaponArtTest

echo "=== GATE 13 (UI/FLOW LAYER: menu + pause-transparency + settings clamps) ==="
java -cp out core.sim.MenuTest

echo "=== GATE 14 (SKY/DAY CYCLE: continuity + horizon-locked ray + pure-function zero-drift) ==="
java -cp out core.sim.DayNightTest

echo "=== GATE 15 (AUDIO: pure synth + envelope + voice pool + zero-drift) ==="
java -cp out core.sim.AudioTest

echo "=== GATE 16 (MATTER/ENERGY: phases+entropy+fusion+deepsea determinism + fingerprint isolation + reachability) ==="
java -cp out core.sim.MatterDeterminismTest
java -cp out core.sim.MegalithDeterminismTest   # C6 巨构神殿（确定性+可达性+小世界豁免）

echo "ALL 17 GATES DONE"
