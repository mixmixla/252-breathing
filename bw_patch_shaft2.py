# -*- coding: utf-8 -*-
"""
bw_patch_shaft2.py —— 光轴探针"够不着遮挡物"的修正。

诊断证据（二分法）：把 voxShaft 的返回换成常量 0.30 后，画面 meanAbs=0.84 / maxΔ=40 ⇒
`dIns` 非零、调制机制是通的；但真实探针（1.1 / 3.0 格）在所有 yaw 上都给出 ~0 差异
⇒ **探针太短，够不着遮住太阳的地形/树冠**（低太阳时遮挡物常在几十格外）。

修正：把每样本的 2 段短探（1.1/3.0 格）改为 3 段**长探**（4 / 12 / 28 格），
并把视射线上的样本数 6 -> 4（保持总采样数 12，与 AO 同量级）。
"""
import sys

SRC = r"src/render/lwjgl/Game.java"

PATCHES = [
    ("修回调试常量（0.30 -> lit）",
     b"lit*=0.1666667;return mix(1.0,0.30,amt);}",
     b"lit*=0.25;return mix(1.0,lit,amt);}", 1),
    ("采样循环：6x2 短探 -> 4x3 长探（4/12/28 格）",
     b"for(int i=0;i<6;i++){vec3 p=wp+vdir*(span*((float(i)+0.5)*0.1666667));"
     b"float occ=voxOcc(p+SL*1.1)*0.5+voxOcc(p+SL*3.0);"
     b"lit+=1.0-clamp(occ,0.0,1.0);}"
     b"lit*=0.25;",
     b"for(int i=0;i<4;i++){vec3 p=wp+vdir*(span*((float(i)+0.5)*0.25));"
     b"float occ=voxOcc(p+SL*4.0)+voxOcc(p+SL*12.0)+voxOcc(p+SL*28.0);"
     b"lit+=1.0-clamp(occ*0.3333333,0.0,1.0);}"
     b"lit*=0.25;", 1),
]


def main():
    with open(SRC, "rb") as f:
        data = f.read()
    orig = len(data)
    for desc, old, new, want in PATCHES:
        n = data.count(old)
        if n != want:
            print("FAIL  %s\n      命中 %d 次，期望 %d 次 —— 中止" % (desc, n, want))
            return 1
        data = data.replace(old, new)
        print("OK    %s" % desc)
    with open(SRC, "wb") as f:
        f.write(data)
    print("字节 %d -> %d (Δ%+d)" % (orig, len(data), len(data) - orig))
    return 0


if __name__ == "__main__":
    sys.exit(main())
