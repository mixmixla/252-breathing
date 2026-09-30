@echo off
chcp 65001 >nul
setlocal
cd /d %~dp0

REM ---- QA 2026-09-13: 关掉旧的游戏实例（按主类精确匹配）----
powershell -NoProfile -Command "Get-CimInstance Win32_Process -Filter \"Name='java.exe'\" | Where-Object { \$_.CommandLine -like '*render.lwjgl.Game*' } | ForEach-Object { Stop-Process -Id \$_.ProcessId -Force }" >nul 2>&1

REM ---- 编译：双击本脚本永远跑最新源码 ----
if not exist out mkdir out
set LIBS=libs/jna-5.13.0.jar;libs/gson-2.10.1.jar;libs/lwjgl-3.3.3.jar;libs/lwjgl-glfw-3.3.3.jar;libs/lwjgl-opengl-3.3.3.jar;libs/joml-1.10.5.jar
echo [compile] core (rng+world+agent+systems+audio+content+anim+net+sim) ...
javac -encoding UTF-8 -cp "out;%LIBS%" -d out src/core/rng/*.java src/core/world/*.java src/core/agent/*.java src/core/systems/*.java src/core/audio/*.java src/core/content/*.java src/core/anim/*.java src/core/net/*.java src/core/sim/*.java
if errorlevel 1 goto :fail
echo [compile] render/software ...
javac -encoding UTF-8 -cp out -d out src/render/software/SoftwareRenderer.java
if errorlevel 1 goto :fail
echo [compile] render/audio (AudioOut) ...
javac -encoding UTF-8 -cp out -d out src/render/audio/*.java
if errorlevel 1 goto :fail
echo [compile] render/lwjgl (Game + Chunk + Font + CjkFont + HudText + GameLog) ...
javac -encoding UTF-8 -cp "out;%LIBS%" -d out src/render/lwjgl/*.java
if errorlevel 1 goto :fail

set CP=out;%LIBS%;libs/lwjgl-3.3.3-natives-windows.jar;libs/lwjgl-glfw-3.3.3-natives-windows.jar;libs/lwjgl-opengl-3.3.3-natives-windows.jar
echo [run] render.lwjgl.Game  %date% %time%
REM QA 2026-09-26：JVM 参数走 BW_OPTS —— 原来的 %* 落在主类**之后**（是程序参数），
REM   所以 `-Dbw.fpsdiag=1` 这类 JVM 参数根本传不进去。用法：
REM       set BW_OPTS=-Dbw.fpsdiag=1
REM       run-game.bat
java %BW_OPTS% -cp "%CP%" render.lwjgl.Game %*
goto :eof

:fail
echo BUILD FAILED - 把上面的报错发给 AI
pause
exit /b 1
