package core.anim;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * JSON 配置加载（QC 2026-09-13 · 自建可配置动作系统 · 一期）。
 *
 * <p>外部文件格式（{@code assets/anims/<name>.json}）：
 * <pre>
 * { "skeleton": { "bones": [ {"name":"root","parent":null,"pos":[0,0,0]}, ... ] },
 *   "clips": [ { "name":"attack", "length":0.6, "loop":false,
 *                "tracks":[ {"joint":"armR","keys":[{"t":0,"rot":[0,0,0]},
 *                                                    {"t":0.25,"rot":[-120,0,0],"ease":1},
 *                                                    {"t":0.6,"rot":[0,0,0]}]} ],
 *                "events":[ {"t":0.25,"id":1} ] } ] }
 * </pre>
 * 配置只描述参数（非美术素材），可手改、可扩展；加载后行为完全确定（零 RNG）。
 */
public final class AnimJson {

    private static final Gson GSON = new GsonBuilder().create();

    // ---- JSON 结构 ----
    public static final class RigDef {
        public SkeletonDef skeleton;
        public ClipDef[] clips = new ClipDef[0];
    }

    public static final class SkeletonDef {
        public BoneDef[] bones = new BoneDef[0];
    }

    public static final class BoneDef {
        public String name;
        public String parent;
        public float[] pos = new float[]{0, 0, 0};
    }

    public static final class ClipDef {
        public String name;
        public float length;
        public boolean loop;
        public TrackDef[] tracks = new TrackDef[0];
        public EventDef[] events = new EventDef[0];
    }

    public static final class TrackDef {
        public String joint;
        public KeyDef[] keys = new KeyDef[0];
    }

    public static final class KeyDef {
        public float t;
        public float[] rot = new float[]{0, 0, 0};
        public float[] pos;
        public float ease;
    }

    public static final class EventDef {
        public float t;
        public int id;
    }

    /** 加载结果：骨架 + 名字索引的动作表。 */
    public static final class Rig {
        public final Joint.Skeleton skeleton;
        public final Map<String, Clip> clips;

        Rig(Joint.Skeleton skeleton, Map<String, Clip> clips) {
            this.skeleton = skeleton;
            this.clips = clips;
        }

        public Clip clip(String name) { return clips.get(name); }
    }

    private AnimJson() { }

    /** 解析（字符串 → Rig）。所有解析错误抛 IllegalArgumentException（调用方决定降级）。 */
    public static Rig parse(String json) {
        RigDef def = GSON.fromJson(json, RigDef.class);
        if (def == null) throw new IllegalArgumentException("empty rig json");
        if (def.clips == null) def.clips = new ClipDef[0];
        if (def.skeleton == null) def.skeleton = new SkeletonDef();

        // 骨架：按父名构建（父可后置声明，最多两轮）
        Map<String, Joint> made = new LinkedHashMap<String, Joint>();
        Joint root = null;
        int guard = 0;
        while (made.size() < def.skeleton.bones.length && guard++ < def.skeleton.bones.length * 2 + 4) {
            for (BoneDef b : def.skeleton.bones) {
                if (made.containsKey(b.name)) continue;
                if (b.parent == null || b.parent.isEmpty()) {
                    Joint j = new Joint(b.name, null, b.pos[0], b.pos[1], b.pos[2]);
                    made.put(b.name, j);
                    if (root == null) root = j;
                } else {
                    Joint p = made.get(b.parent);
                    if (p == null) continue;
                    Joint j = new Joint(b.name, p, b.pos[0], b.pos[1], b.pos[2]);
                    p.children.add(j);
                    made.put(b.name, j);
                }
            }
        }
        if (root == null) throw new IllegalArgumentException("rig has no root bone");

        Map<String, Clip> clips = new LinkedHashMap<String, Clip>();
        for (ClipDef cd : def.clips) {
            Clip.Track[] tracks = new Clip.Track[cd.tracks.length];
            for (int i = 0; i < cd.tracks.length; i++) {
                TrackDef td = cd.tracks[i];
                Clip.Key[] keys = new Clip.Key[td.keys.length];
                for (int k = 0; k < td.keys.length; k++) {
                    KeyDef kd = td.keys[k];
                    Clip.Key key = new Clip.Key(kd.t, kd.rot[0], kd.rot[1], kd.rot[2]);
                    if (kd.pos != null && kd.pos.length >= 3) key.pos(kd.pos[0], kd.pos[1], kd.pos[2]);
                    key.ease(kd.ease);
                    keys[k] = key;
                }
                tracks[i] = new Clip.Track(td.joint, keys);
            }
            Clip clip = new Clip(cd.name, cd.length, cd.loop, tracks);
            if (cd.events.length > 0) {
                float[] ts = new float[cd.events.length];
                int[] ids = new int[cd.events.length];
                for (int e = 0; e < cd.events.length; e++) { ts[e] = cd.events[e].t; ids[e] = cd.events[e].id; }
                clip.events(ts, ids);
            }
            clips.put(cd.name, clip);
        }
        return new Rig(new Joint.Skeleton(root), clips);
    }

    /** 从文件加载（UTF-8）。 */
    public static Rig load(File f) throws IOException {
        String json = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
        return parse(json);
    }

    /** 便捷：内部字符串（零文件环境/门禁用）。 */
    public static Rig parseInternal(String json) { return parse(json); }
}
