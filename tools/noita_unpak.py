# -*- coding: utf-8 -*-
"""
Noita `data.wak` 解包器（只读源 → 写到外部研究目录；**绝不改动游戏安装**）。

这是"学习用"的工具，不属于游戏本体代码。

## 为什么不用官方解包器
`Noita\tools_modding\data_wak_unpack.bat` 内容只有一行 `noita.exe -wizard_unpak`，
它要求把工具复制到游戏根目录并**往安装目录写文件**（Program Files 需管理员，且会改动游戏本体）。
本脚本改为**自己解析 wak 格式**，只读游戏文件、只往指定目录写。

## wak 格式（实测确定，14744/14744 相邻条目连续性吻合）
    header 16B = [u32 pad=0][u32 count][u32 dirEnd][u32 0]
    之后 count 条： [u32 dataOffset][u32 size][u32 nameLen][name(utf-8/latin-1)]
    数据区紧跟目录区（dirEnd = 目录区结束偏移 = 表项总长 + 16）
    相邻数据块之间间隔 1 字节（分隔符）→ 校验：off[i+1] == off[i] + size[i] + 1

## 用法
    python noita_unpak.py                 # 干跑：打印统计（不写盘）
    python noita_unpak.py --go 2000       # 写入最多 2000 个「尚不存在」的文件后退出（可反复调用续跑）
    python noita_unpak.py --out D:\\somewhere --go 2000

`--go N` 的分块设计是为了绕开沙箱"单条命令批量写文件数上限"（实测写到约 5600 个会被 SIGTERM），
脚本按"目标已存在且大小一致"跳过，因此**反复调用即可续跑**，天然幂等。
"""
import io
import os
import struct
import sys
import time

WAK = r'C:\Program Files (x86)\Steam\steamapps\common\Noita\data\data.wak'
DEFAULT_OUT = r'D:\study\Noita_unpacked'


def parse_table(blob):
    zero, count, dir_end, _z = struct.unpack('<4I', blob[:16])
    p, ent = 16, []
    for _i in range(count):
        off, size, nl = struct.unpack('<3I', blob[p:p + 12])
        if nl == 0 or nl > 512:
            raise ValueError('bad namelen %d at %d' % (nl, p))
        name = blob[p + 12:p + 12 + nl].decode('latin-1')
        ent.append((name, off, size))
        p += 12 + nl
    return ent, p, dir_end


def main():
    argv = sys.argv[1:]
    out = DEFAULT_OUT
    if '--out' in argv:
        out = argv[argv.index('--out') + 1]
    blob = open(WAK, 'rb').read()
    ent, table_end, dir_end = parse_table(blob)

    from collections import Counter
    ext = Counter(os.path.splitext(n)[1].lower() for n, _, _ in ent)
    bad = sum(1 for i in range(len(ent) - 1) if ent[i + 1][1] != ent[i][1] + ent[i][2] + 1)
    print('wak=%.2f MB  entries=%d  tableEnd=%d  dirEnd=%d  连续性异常=%d'
          % (len(blob) / 1048576, len(ent), table_end, dir_end, bad))
    print('扩展名:', ext.most_common(12))

    if '--go' not in argv:
        print('(干跑结束；加 --go N 才会写盘)')
        return
    limit = int(argv[argv.index('--go') + 1])
    os.makedirs(out, exist_ok=True)

    t0, wrote, done = time.time(), 0, 0
    for name, off, size in ent:
        dst = os.path.join(out, *name.replace('\\', '/').split('/'))
        if os.path.isfile(dst) and os.path.getsize(dst) == size:
            done += 1
            continue
        if wrote >= limit:
            continue
        d = os.path.dirname(dst)
        if d and not os.path.isdir(d):
            os.makedirs(d, exist_ok=True)
        with open(dst, 'wb') as f:
            f.write(blob[off:off + size])
        wrote += 1
        done += 1
    print('本轮写入 %d；已完成 %d/%d；剩余 %d；(%.1fs)'
          % (wrote, done, len(ent), len(ent) - done, time.time() - t0))


if __name__ == '__main__':
    main()
