package render.lwjgl;

import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;

import static org.lwjgl.opengl.GL33.*;

/**
 * 可平铺梯度噪声纹理（128×128 RGBA8，4 个通道 = 4 个互相独立的可平铺梯度噪声场）。
 *
 * <p><b>为什么需要</b>：天空云层要"fbm 多八度 + 域扭曲"才有有机形状，但逐像素用
 * {@code fract(sin(dot(...)))} 哈希算 16 次八度，在核显上实测把 720p 天空从 0.78ms 拉到 3.45ms
 * （1080p 会到 ~7.6ms，不可接受）。改成本纹理后：<b>一次采样 = 硬件双线性插值 + mipmap</b>，
 * 既省掉全部逐像素哈希，又靠 mipmap 自动消除远处（地平线附近）的噪声走样条纹。</p>
 *
 * <p><b>可平铺</b>：梯度只在 {@link #LAT}×{@link #LAT} 的格点上定义，格点索引按 LAT 取模 →
 * 纹理左右/上下接缝处梯度一致 → GL_REPEAT 无缝。</p>
 *
 * <p>纯渲染资源：不参与仿真、不进指纹。由 Game 启动时 {@link #bake()} 一次。</p>
 */
public final class NoiseTex {

    /** 纹理边长（像素）。 */
    public static final int SIZE = 128;
    /** 格点数（每边）。格点索引对 LAT 取模 → 纹理可平铺。 */
    public static final int LAT = 8;

    private NoiseTex() { }

    /** 8 个均匀方向的单位梯度（避免在着色器里算 cos/sin）。 */
    private static final float[] GRX = {1.0000f, 0.7071f, 0.0000f, -0.7071f, -1.0000f, -0.7071f, 0.0000f, 0.7071f};
    private static final float[] GRY = {0.0000f, 0.7071f, 1.0000f, 0.7071f, 0.0000f, -0.7071f, -1.0000f, -0.7071f};

    /** 确定性哈希 → [0,1)。整型混合，跨平台一致。 */
    private static float h01(int x, int y, int c) {
        int h = x * 374761393 + y * 668265263 + c * 1274126177;
        h = (h ^ (h >>> 13)) * 1274126177;
        h = h ^ (h >>> 16);
        return (h & 0x7fffffff) / (float) 0x7fffffff;
    }

    private static float gradDot(int i, int j, float dx, float dy, int c) {
        int w = ((i % LAT) + LAT) % LAT;
        int q = ((j % LAT) + LAT) % LAT;
        int g = (int) (h01(w, q, c) * 8f) & 7;
        return GRX[g] * dx + GRY[g] * dy;
    }

    /** 单个噪声场的取值（值域约 [-0.75,0.75]，均值 0）。 */
    private static float noise(float u, float v, int c) {
        int i0 = (int) Math.floor(u), j0 = (int) Math.floor(v);
        float fu = u - i0, fv = v - j0;
        float su = fu * fu * (3f - 2f * fu), sv = fv * fv * (3f - 2f * fv);
        float n00 = gradDot(i0, j0, fu, fv, c);
        float n10 = gradDot(i0 + 1, j0, fu - 1f, fv, c);
        float n01 = gradDot(i0, j0 + 1, fu, fv - 1f, c);
        float n11 = gradDot(i0 + 1, j0 + 1, fu - 1f, fv - 1f, c);
        float a = n00 + (n10 - n00) * su;
        float b = n01 + (n11 - n01) * su;
        return a + (b - a) * sv;
    }

    /**
     * 生成并上传纹理，返回 GL 纹理 id（需已有当前 GL 上下文）。
     * 采样约定：GL_LINEAR（texel 中心）+ REPEAT + mipmap 三线性。
     */
    public static int bake() {
        ByteBuffer buf = MemoryUtil.memAlloc(SIZE * SIZE * 4);
        for (int y = 0; y < SIZE; y++) {
            for (int x = 0; x < SIZE; x++) {
                // +0.5：让 texel 中心对齐采样约定，硬件双线性才能忠实还原该场
                float u = ((x + 0.5f) / SIZE) * LAT;
                float v = ((y + 0.5f) / SIZE) * LAT;
                for (int c = 0; c < 4; c++) {
                    float n = noise(u, v, c);
                    int b = (int) ((0.5f + n * 0.72f) * 255f);
                    if (b < 0) b = 0;
                    if (b > 255) b = 255;
                    buf.put((byte) b);
                }
            }
        }
        buf.flip();
        int id = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, id);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, SIZE, SIZE, 0, GL_RGBA, GL_UNSIGNED_BYTE, buf);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_REPEAT);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_REPEAT);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR_MIPMAP_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glGenerateMipmap(GL_TEXTURE_2D);
        glBindTexture(GL_TEXTURE_2D, 0);
        MemoryUtil.memFree(buf);
        return id;
    }
}
