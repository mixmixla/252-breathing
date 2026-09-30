package core.anim;

import com.google.gson.Gson;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * 模型定义（QC 2026-09-13 · 混合路线：体素部件绑定骨架关节）。
 *
 * <p>{@code assets/models/<name>.json}：每个 part 绑定一个关节名 + 形状（一期用方块 box）
 * + 尺寸（像素/2 = 块单位由渲染层换算）+ 材质名（对应材质表）。渲染层按关节世界变换装配，
 * 于是"模型 = 配置"，改 JSON 即改模型。
 */
public final class ModelDef {

    public static final class Part {
        public String joint;
        public String kind = "box";            // 一期：box（二期可扩 voxel/mesh）
        public float[] size = new float[]{4, 4, 4};
        public float[] offset = new float[]{0, 0, 0};
        public String material = "default";
    }

    public String name = "unnamed";
    public String format = "voxel-parts";
    public Part[] parts = new Part[0];

    public static ModelDef parse(String json) {
        ModelDef def = new Gson().fromJson(json, ModelDef.class);
        if (def == null || def.parts == null) throw new IllegalArgumentException("empty model json");
        return def;
    }

    public static ModelDef load(File f) throws IOException {
        return parse(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
    }
}
