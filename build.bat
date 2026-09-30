@echo off
chcp 65001 >nul
cd /d %~dp0
if exist out rmdir /s /q out
mkdir out
set LIBS=libs/jna-5.13.0.jar;libs/gson-2.10.1.jar;libs/lwjgl-3.3.3.jar;libs/lwjgl-glfw-3.3.3.jar;libs/lwjgl-opengl-3.3.3.jar;libs/joml-1.10.5.jar
echo [compile] core (rng+world+agent+systems+audio+content+anim+net+sim) ...
javac -encoding UTF-8 -cp "out;%LIBS%" -d out src/core/rng/*.java src/core/world/*.java src/core/agent/*.java src/core/systems/*.java src/core/audio/*.java src/core/content/*.java src/core/anim/*.java src/core/net/*.java src/core/sim/*.java
if errorlevel 1 exit /b 1
javac -encoding UTF-8 -cp out -d out src/render/software/SoftwareRenderer.java
if errorlevel 1 exit /b 1
javac -encoding UTF-8 -cp out -d out src/render/audio/*.java
if errorlevel 1 exit /b 1
echo [compile] render/lwjgl (Game + Chunk + Font + CjkFont + HudText + GameLog) ...
javac -encoding UTF-8 -cp "out;%LIBS%" -d out src/render/lwjgl/*.java
if errorlevel 1 exit /b 1
echo BUILD OK.
echo === GATES 1-4 (BASELINE: determinism + zero-drift + physics + streaming) ===
java -cp "out;%LIBS%" core.sim.DeterminismTest
java -cp "out;%LIBS%" core.sim.ZeroDriftTest
java -cp "out;%LIBS%" core.sim.PhysicsTest
java -cp "out;%LIBS%" core.sim.StreamingTest
echo === GATES 5-11 (PER-LAYER: npc + social + storyteller + civ + individual + polity + trial) ===
java -cp "out;%LIBS%" core.sim.NpcDeterminismTest
java -cp "out;%LIBS%" core.sim.SocialDeterminismTest
java -cp "out;%LIBS%" core.sim.StorytellerDeterminismTest
java -cp "out;%LIBS%" core.sim.CivilizationDeterminismTest
java -cp "out;%LIBS%" core.sim.IndividualDeterminismTest
java -cp "out;%LIBS%" core.sim.PolityDeterminismTest
java -cp "out;%LIBS%" core.sim.TrialDeterminismTest
echo === GATE 12 (PLAYER ACTION LAYER: weapon arts behaviour + zero-drift invariant) ===
java -cp "out;%LIBS%" core.sim.WeaponArtTest
echo === GATE 13 (UI/FLOW LAYER: menu + pause-transparency + settings clamps) ===
java -cp "out;%LIBS%" core.sim.MenuTest
echo === GATE 14 (SKY/DAY CYCLE: continuity + horizon-locked ray + pure-function zero-drift) ===
java -cp "out;%LIBS%" core.sim.DayNightTest
echo === GATE 15 (AUDIO: pure synth + envelope + voice pool + zero-drift) ===
java -cp "out;%LIBS%" core.sim.AudioTest
echo === GATE 16 (MATTER/ENERGY: phases+entropy+fusion+deepsea determinism + fingerprint isolation + reachability) ===
java -cp "out;%LIBS%" core.sim.MatterDeterminismTest
java -cp "out;%LIBS%" core.sim.MegalithDeterminismTest
echo ALL 17 GATES DONE.
