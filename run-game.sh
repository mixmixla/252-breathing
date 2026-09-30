#!/usr/bin/env bash
set -e
cd "$(dirname "$0")"
CP="out:libs/lwjgl-3.3.3.jar:libs/lwjgl-glfw-3.3.3.jar:libs/lwjgl-opengl-3.3.3.jar:libs/joml-1.10.5.jar"
# natives（按本机平台追加；这里示例为 linux）
for n in libs/lwjgl-3.3.3-natives-linux.jar libs/lwjgl-glfw-3.3.3-natives-linux.jar libs/lwjgl-opengl-3.3.3-natives-linux.jar; do
  [ -f "$n" ] && CP="$CP:$n"
done
java -cp "$CP" render.lwjgl.Game "$@"