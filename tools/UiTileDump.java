import render.lwjgl.TextureAtlas;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.file.*;
import java.util.zip.CRC32;
import java.util.zip.Deflater;

/** 导出 UI 图集区为 PNG（无头、纯 CPU、零原生依赖），供人眼审阅"自研艺术 UI"贴图。 */
public class UiTileDump {
    public static void main(String[] a) throws Exception {
        ByteBuffer atlas = TextureAtlas.bakeAlbedoOffscreen();
        int AP = TextureAtlas.ATLAS_PX, CELL = TextureAtlas.CELL_PX, G = TextureAtlas.GUTTER, T = TextureAtlas.TILE_PX;
        int N = TextureAtlas.UI_TILE_COUNT;
        int cols = 4, rows = (N + cols - 1) / cols;
        int pad = 8;
        int W = cols * (T + pad) + pad, H = rows * (T + pad) + pad;
        byte[] out = new byte[W * H * 4];
        // 棋盘底（看 alpha 透明）
        for (int y = 0; y < H; y++) for (int x = 0; x < W; x++) {
            int c = (((x / 8) + (y / 8)) % 2 == 0) ? 60 : 96;
            int i = (y * W + x) * 4;
            out[i] = (byte) c; out[i + 1] = (byte) c; out[i + 2] = (byte) (c + 6); out[i + 3] = (byte) 255;
        }
        for (int k = 0; k < N; k++) {
            int tile = TextureAtlas.UI_TILE_BASE + k;
            int tx0 = TextureAtlas.cellX(tile) + G, ty0 = TextureAtlas.cellY(tile) + G;
            int ox = pad + (k % cols) * (T + pad), oy = pad + (k / cols) * (T + pad);
            for (int y = 0; y < T; y++) for (int x = 0; x < T; x++) {
                int si = ((ty0 + y) * AP + tx0 + x) * 4;
                int di = ((oy + y) * W + ox + x) * 4;
                int r = atlas.get(si) & 255, g2 = atlas.get(si + 1) & 255, b = atlas.get(si + 2) & 255, al = atlas.get(si + 3) & 255;
                if (al == 0) continue;   // 真透明 → 露棋盘
                // 简单 over 合成
                float af = al / 255f;
                out[di] = (byte) (r * af + (out[di] & 255) * (1 - af));
                out[di + 1] = (byte) (g2 * af + (out[di + 1] & 255) * (1 - af));
                out[di + 2] = (byte) (b * af + (out[di + 2] & 255) * (1 - af));
                out[di + 3] = (byte) 255;
            }
        }
        writePng(a.length > 0 ? a[0] : "proof/artui/ui_tiles.png", W, H, out);
        System.out.println("UI tiles dumped: " + N + " tiles -> " + (a.length > 0 ? a[0] : "proof/artui/ui_tiles.png") + "  (" + W + "x" + H + ")");
    }

    static void writePng(String path, int w, int h, byte[] rgba) throws IOException {
        // raw scanlines (filter 0)
        byte[] raw = new byte[h * (1 + w * 4)];
        for (int y = 0; y < h; y++) {
            raw[y * (1 + w * 4)] = 0;
            System.arraycopy(rgba, y * w * 4, raw, y * (1 + w * 4) + 1, w * 4);
        }
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        DataOutputStream d = new DataOutputStream(bo);
        d.writeInt(0x89504E47); d.writeInt(0x0D0A1A0A);
        ByteArrayOutputStream ih = new ByteArrayOutputStream();
        DataOutputStream di = new DataOutputStream(ih);
        di.writeInt(w); di.writeInt(h); di.writeByte(8); di.writeByte(6); di.writeByte(0); di.writeByte(0); di.writeByte(0);
        chunk(d, "IHDR", ih.toByteArray());
        // zlib
        Deflater def = new Deflater(9);
        def.setInput(raw); def.finish();
        byte[] buf = new byte[raw.length + 1024]; int len = 0;
        while (!def.finished()) len += def.deflate(buf, len, buf.length - len);
        byte[] z = new byte[len]; System.arraycopy(buf, 0, z, 0, len);
        chunk(d, "IDAT", z);
        chunk(d, "IEND", new byte[0]);
        d.flush();
        File f = new File(path);
        f.getParentFile().mkdirs();
        Files.write(f.toPath(), bo.toByteArray());
    }

    static void chunk(DataOutputStream d, String type, byte[] data) throws IOException {
        d.writeInt(data.length);
        byte[] t = type.getBytes("US-ASCII");
        d.write(t); d.write(data);
        CRC32 c = new CRC32(); c.update(t); c.update(data);
        d.writeInt((int) c.getValue());
    }
}
