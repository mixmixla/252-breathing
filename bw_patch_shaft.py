# -*- coding: utf-8 -*-
"""
bw_patch_shaft.py —— 给 Game.java 的 world FS 注入「体积光轴（light shafts）」。

思路：内散射项（UE ExponentialHeightFog 的 DirectionalInScattering，已移植）目前**没有遮挡** ⇒
树冠/洞口不会出现光柱。做法：沿**视射线**（片元 -> 相机）取 6 个样本，每个样本用**朝光源方向的
2 段短程占用**（1.1 / 3.0 格）近似"该处能否看到太阳"，平均得透过率，乘到 `dIns`。

为什么这样便宜：不新增 pass；每片元 6×2 = 12 次 `texelFetch`（与刚加的 AO 同量级），
且 `uShaftAmount<=0.002` 时**入口早退恒等** ⇒ `-Dbw.shaft=0` 与改动前逐字节相同（零基线）。

方向用 `uDirInscDir`（"当前在天上的那个天体"）而不是 `uLightDir` —— 与已有的内散射辉光同源，
否则夜里光柱会指向地平线以下的太阳（与既有实现踩过的坑同源）。
"""
import sys

SRC = r"src/render/lwjgl/Game.java"

SHAFT_FN = (
    "float voxShaft(vec3 wp,vec3 vdir,float dist,float amt){"
    "if(amt<=0.002)return 1.0;"
    "vec3 SL=normalize(uDirInscDir);"
    "float span=min(dist,24.0);"
    "if(span<=0.2)return 1.0;"
    "float lit=0.0;"
    "for(int i=0;i<6;i++){"
    "vec3 p=wp+vdir*(span*((float(i)+0.5)*0.1666667));"
    "float occ=voxOcc(p+SL*1.1)*0.5+voxOcc(p+SL*3.0);"
    "lit+=1.0-clamp(occ,0.0,1.0);"
    "}"
    "lit*=0.1666667;"
    "return mix(1.0,lit,amt);"
    "}"
)

PATCHES = [
    ("world FS: 声明 uShaftAmount",
     b"uniform float uAoStrength;",
     b"uniform float uAoStrength;uniform float uShaftAmount;", 1),
    ("world FS: 在 voxAO 之后、sunShadow 之前插入 voxShaft()",
     b"return clamp(1.0-occ*k,0.25,1.0);}\\nfloat sunShadow(",
     b"return clamp(1.0-occ*k,0.25,1.0);}\\n" + SHAFT_FN.encode("ascii") + b"\\nfloat sunShadow(", 1),
    ("world FS: 内散射按光路透过率调制",
     b"float dIns=dAmt*f*(1.0-uUnder)*smoothstep(uDirInscStart,uDirInscStart*2.2,dist);",
     b"float dIns=dAmt*f*(1.0-uUnder)*smoothstep(uDirInscStart,uDirInscStart*2.2,dist);"
     b"if(dAmt>0.001)dIns*=voxShaft(vWorld,vDir,dist,uShaftAmount);", 1),
]


def main():
    with open(SRC, "rb") as f:
        data = f.read()
    orig = len(data)
    for desc, old, new, want in PATCHES:
        n = data.count(old)
        if n != want:
            print("FAIL  %s\n      命中 %d 次，期望 %d 次 —— 中止（不改文件）" % (desc, n, want))
            return 1
        data = data.replace(old, new)
        print("OK    %s" % desc)
    if '"' in SHAFT_FN:
        print("FAIL  注入片段含双引号 —— 会截断 Java 字面量，中止")
        return 1
    with open(SRC, "wb") as f:
        f.write(data)
    print("字节 %d -> %d (Δ%+d)" % (orig, len(data), len(data) - orig))
    return 0


if __name__ == "__main__":
    sys.exit(main())
