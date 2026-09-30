#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""QA 无头截图驱动（stdlib-only）。

被本目录下其它 python 分析脚本 import：`from qa_snap import run, ROOT`。
存在的理由：`-Dbw.snap` 的 java 命令行（classpath + libraries + args 顺序）很容易拼错，
集中在这里一处，A/B 脚本就只剩"参数与判读"。

用法示例：
    import sys; sys.path.insert(0, "tools")
    from qa_snap import run
    run("proof/forest_dusk.png", phase="0.78", yaw="90", pitch="0", extra=["-Dbw.forest=1"])

约定：
- 所有 `-D` 属性必须在主类名**之前**（JVM 要求）。
- 返回 True 表示 PNG 已生成；False 表示该次运行没出图（看 game_diag.log）。
"""
import os
import subprocess

ROOT = os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
JAVA = r"C:\Users\Administrator\.jdks\corretto-1.8.0_502\bin\java.exe"
LIBS = [
    "out",
    "libs/gson-2.10.1.jar", "libs/jna-5.13.0.jar", "libs/joml-1.10.5.jar",
    "libs/lwjgl-3.3.3.jar", "libs/lwjgl-glfw-3.3.3.jar", "libs/lwjgl-opengl-3.3.3.jar",
    "libs/lwjgl-3.3.3-natives-windows.jar",
    "libs/lwjgl-glfw-3.3.3-natives-windows.jar",
    "libs/lwjgl-opengl-3.3.3-natives-windows.jar",
]
CP = ";".join(os.path.join(ROOT, p) for p in LIBS)
MAIN = "render.lwjgl.Game"


def run(out, phase=None, yaw=None, pitch=None, snap_frame="3", extra=(), timeout=240):
    """出图到 `out`（相对 ROOT 的路径）。返回 True/False。"""
    outp = os.path.join(ROOT, out)
    if os.path.exists(outp):
        os.remove(outp)
    props = ["-Djava.library.path=" + os.path.join(ROOT, "libs"), "-Dbw.snap=" + out]
    if snap_frame is not None:
        props.append("-Dbw.snapFrame=" + snap_frame)
    if phase is not None:
        props.append("-Dbw.phase=" + phase)
    if yaw is not None:
        props.append("-Dbw.yaw=" + yaw)
    if pitch is not None:
        props.append("-Dbw.pitch=" + pitch)
    props.extend(extra)
    try:
        subprocess.run([JAVA] + props + ["-cp", CP, MAIN],
                       cwd=ROOT, capture_output=True, timeout=timeout)
    except subprocess.TimeoutExpired:
        return False
    return os.path.exists(outp)


def same(a, b):
    """两个 PNG 是否逐字节相同（缺文件视为不同）。"""
    pa, pb = os.path.join(ROOT, a), os.path.join(ROOT, b)
    if not (os.path.exists(pa) and os.path.exists(pb)):
        return False
    with open(pa, "rb") as fa, open(pb, "rb") as fb:
        return fa.read() == fb.read()
