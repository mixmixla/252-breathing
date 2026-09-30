package core.content;

import java.io.File;

/**
 * 内容来源 —— 「官方内容」与「MOD 内容」的统一抽象。
 *
 * <p>一个来源 = 一批 {@code <type>/<id>.json} 文件。两种形态：
 * <ul>
 *   <li>{@link #dir} —— 目录源（官方 {@code assets/content}，或 {@code mods/mymod/content}）</li>
 *   <li>{@link #zip} —— 压缩包源（{@code mods/mymod.zip} 内含 {@code content/...}）</li>
 * </ul>
 *
 * <p><b>覆盖规则</b>：多个来源合并时，{@link #priority} <b>高者覆盖</b>低者的同名 ID
 * （同优先级按 {@link #name} 字典序，保证与文件系统枚举顺序无关 → 确定性）。
 * 于是「mod 改官方的一个技能」只需要在 mod 里放一个同名文件。
 *
 * <p>这是泰拉瑞亚式扩展点的第一层：**内容可被替换**。新机制仍需代码（{@code GameplayModule}）。
 */
public final class ContentSource {

    public final String name;      // "base" / mod 名（决定同优先级下的次序）
    public final File dir;         // 目录源（与 zip 互斥）
    public final File zip;         // 压缩包源
    public final String prefix;    // zip 内的内容前缀（通常 "content/"）
    public final int priority;     // 越大越优先

    private ContentSource(String name, File dir, File zip, String prefix, int priority) {
        this.name = name; this.dir = dir; this.zip = zip; this.prefix = prefix;
        this.priority = priority;
    }

    /** 目录源：{@code root} 下直接是 {@code particles/ fx/ skills/ ...}。 */
    public static ContentSource dir(String name, File root, int priority) {
        return new ContentSource(name, root, null, null, priority);
    }

    /** 压缩包源：{@code zipFile} 内 {@code prefix} 下是 {@code particles/ fx/ ...}。 */
    public static ContentSource zip(String name, File zipFile, String prefix, int priority) {
        return new ContentSource(name, null, zipFile, (prefix == null ? "" : prefix), priority);
    }

    public boolean isZip() { return zip != null; }

    public boolean exists() {
        if (isZip()) return zip != null && zip.isFile();
        return dir != null && dir.isDirectory();
    }

    public String describe() {
        if (isZip()) return name + "(zip,pri=" + priority + ")";
        return name + "(dir,pri=" + priority + ")";
    }

    @Override public String toString() { return describe(); }
}
