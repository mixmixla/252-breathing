# -*- coding: utf-8 -*-
"""tools/png_diff.py —— 零依赖 PNG 像素对比（只用 stdlib）。

为什么需要：本项目的美术 pass 靠"无头截图 + 受控 A/B"验收，而**判读必须给数值**
（voxel-render-art-pass 规则 26.6：目测会把 (217,104,255) 读成"品红"、把 17 个像素差读成"地形消失"）。
此前的脚本只会 `cmp`（只能答"逐字节相同吗"），答不了"差多少"。

支持：8bit / colortype 2(RGB) 或 6(RGBA) / 非隔行（ImageIO 写出的就是这种）。

用法：
    python tools/png_diff.py A.png B.png [阈值=2]
输出：宽高校验、逐通道 meanAbs、maxDelta、超阈值像素占比、差异重心行带。
"""
import struct
import sys
import zlib


def read_png(path):
    d = open(path, "rb").read()
    if d[:8] != b"\x89PNG\r\n\x1a\n":
        raise ValueError("not a png: " + path)
    w, h, bd, ct, comp, filt, inter = struct.unpack(">IIBBBBB", d[16:29])
    if bd != 8 or inter != 0 or ct not in (2, 6):
        raise ValueError("unsupported png (bd=%d ct=%d interlace=%d)" % (bd, ct, inter))
    nch = 3 if ct == 2 else 4
    idat = bytearray()
    i = 8
    while i < len(d):
        ln = struct.unpack(">I", d[i:i + 4])[0]
        k = d[i + 4:i + 8]
        if k == b"IDAT":
            idat += d[i + 8:i + 8 + ln]
        elif k == b"IEND":
            break
        i += 12 + ln
    raw = zlib.decompress(bytes(idat))
    stride = w * nch
    out = bytearray(h * stride)
    prev = bytearray(stride)
    pos = 0
    for y in range(h):
        f = raw[pos]; pos += 1
        line = bytearray(raw[pos:pos + stride]); pos += stride
        if f == 1:                       # Sub
            for x in range(nch, stride):
                line[x] = (line[x] + line[x - nch]) & 0xFF
        elif f == 2:                     # Up
            for x in range(stride):
                line[x] = (line[x] + prev[x]) & 0xFF
        elif f == 3:                     # Average
            for x in range(stride):
                a = line[x - nch] if x >= nch else 0
                line[x] = (line[x] + ((a + prev[x]) >> 1)) & 0xFF
        elif f == 4:                     # Paeth
            for x in range(stride):
                a = line[x - nch] if x >= nch else 0
                b = prev[x]
                c = prev[x - nch] if x >= nch else 0
                p = a + b - c
                pa = abs(p - a); pb = abs(p - b); pc = abs(p - c)
                pr = a if (pa <= pb and pa <= pc) else (b if pb <= pc else c)
                line[x] = (line[x] + pr) & 0xFF
        elif f != 0:
            raise ValueError("bad filter %d at row %d" % (f, y))
        out[y * stride:(y + 1) * stride] = line
        prev = line
    return w, h, nch, bytes(out)


def main():
    if len(sys.argv) < 3:
        print(__doc__)
        return 1
    thr = int(sys.argv[3]) if len(sys.argv) > 3 else 2
    wa, ha, na, A = read_png(sys.argv[1])
    wb, hb, nb, B = read_png(sys.argv[2])
    print("A=%s  %dx%d ch=%d" % (sys.argv[1], wa, ha, na))
    print("B=%s  %dx%d ch=%d" % (sys.argv[2], wb, hb, nb))
    if (wa, ha, na) != (wb, hb, nb):
        print("!! 尺寸/通道不一致 —— 不可比")
        return 1
    n = wa * ha
    sums = [0] * na
    mx = 0
    over = 0
    for i in range(n):
        o = i * na
        worst = 0
        for c in range(na):
            d = abs(A[o + c] - B[o + c])
            sums[c] += d
            if d > worst:
                worst = d
            if d > mx:
                mx = d
        if worst > thr:
            over += 1
    print("逐通道 meanAbs: " + " ".join("%.3f" % (s / n) for s in sums))
    print("maxDelta=%d   超阈值(>%d)像素=%d / %d (%.3f%%)" % (mx, thr, over, n, 100.0 * over / n))
    same = all(s == 0 for s in sums)
    print("判定: " + ("逐字节相同（0 差异）" if same else "存在差异"))
    return 0


if __name__ == "__main__":
    sys.exit(main())
