package core.sim;

import core.content.ContentRegistry;
import core.content.ContentSource;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * MOD 支持门禁（第 33 个出口）—— 断言「内容可被替换与扩展」的十条性质。
 *
 * <p>MOD 的工程本质只有两件事：<b>多来源合并</b>与<b>覆盖优先级</b>。
 * 这两件事一旦正确，其余（校验/环检测/冲突报告）都能复用既有能力 —— 这个门禁就是来证明这一点。
 *
 * <p>关键断言：<b>OVERRIDE</b>（mod 真的改掉了官方内容）、<b>PRIORITY</b>（高优先胜）、
 * <b>BASE_INTACT</b>（不装 mod 时官方内容不被污染）、<b>DET</b>（同输入同结果，与枚举顺序无关）、
 * <b>ZIP</b>（压缩包形式可用）、<b>ISOLATION</b>（坏 mod 的引用错误会被报出，不静默）。
 */
public final class ModTest {

    private static int fails = 0;

    private static void ck(String tag, boolean cond, String detail) {
        System.out.println(tag + " " + (cond ? "PASS" : "FAIL") + "  " + detail);
        if (!cond) fails++;
    }

    private static void write(File f, String text) throws Exception {
        f.getParentFile().mkdirs();
        FileOutputStream out = new FileOutputStream(f);
        try { out.write(text.getBytes("UTF-8")); } finally { out.close(); }
    }

    private static float costOf(ContentRegistry r, String skillId) {
        if (r.of("skills").get(skillId) == null) return -1f;
        return r.of("skills").get(skillId).get("costSouls").getAsFloat();
    }

    public static void main(String[] args) throws Exception {

        File baseDir = new File("assets/content");
        File modsDir = new File("mods");

        // ---------------- DISCOVER：扫描 mods 目录 ----------------
        List<ContentSource> mods = ContentRegistry.discoverMods(modsDir);
        boolean found = false;
        for (ContentSource m : mods) if (m.name.startsWith("example_mod")) found = true;
        ck("DISCOVER", found, "mods=" + mods.size() + " containsExampleMod=" + found);

        // ---------------- BASE_INTACT：只装 base 时是官方值 ----------------
        List<ContentSource> onlyBase = new ArrayList<ContentSource>();
        onlyBase.add(ContentSource.dir("base", baseDir, 0));
        ContentRegistry rb = ContentRegistry.loadAll(onlyBase);
        float baseCost = costOf(rb, "ember_harvest");
        boolean noModContent = !rb.of("particles").containsKey("ember");
        ck("BASE_INTACT", rb.ok() && baseCost == 25f && noModContent,
                "ember_harvest.costSouls=" + baseCost + " (expect 25) modParticleAbsent=" + noModContent);

        // ---------------- LOADALL：base + mod 合并 ----------------
        List<ContentSource> all = new ArrayList<ContentSource>();
        all.add(ContentSource.dir("base", baseDir, 0));
        all.addAll(mods);
        ContentRegistry r = ContentRegistry.loadAll(all);
        boolean modParticle = r.of("particles").containsKey("ember");
        ck("LOADALL", r.ok() && modParticle && r.total() > rb.total(),
                "total=" + r.total() + " (base=" + rb.total() + ") modParticle=" + modParticle
                        + (r.errors().isEmpty() ? "" : " first=\"" + r.errors().get(0) + "\""));

        // ---------------- OVERRIDE：mod 改掉了官方技能 ----------------
        float modCost = costOf(r, "ember_harvest");
        ck("OVERRIDE", modCost == 10f,
                "ember_harvest.costSouls " + baseCost + " -> " + modCost + " (expect 10)");

        // ---------------- REPORT：覆盖被记录成清单 ----------------
        boolean logged = false;
        String sample = "(none)";
        for (String o : r.overrides()) {
            if (o.contains("ember_harvest")) { logged = true; sample = o; }
        }
        ck("REPORT", logged, "overrides=" + r.overrides().size() + " sample=\"" + sample + "\"");

        // ---------------- PRIORITY：高优先级来源胜 ----------------
        File tmp = Files.createTempDirectory("modtest").toFile();
        write(new File(tmp, "lo/content/particles/p.json"), "{\"count\":1}");
        write(new File(tmp, "hi/content/particles/p.json"), "{\"count\":99}");
        List<ContentSource> pri = new ArrayList<ContentSource>();
        pri.add(ContentSource.dir("base", baseDir, 0));
        pri.add(ContentSource.dir("lo", new File(tmp, "lo/content"), 10));
        pri.add(ContentSource.dir("hi", new File(tmp, "hi/content"), 20));
        ContentRegistry rp = ContentRegistry.loadAll(pri);
        float cnt = rp.of("particles").get("p").get("count").getAsFloat();
        ck("PRIORITY", cnt == 99f, "winning count=" + cnt + " (hi=20 beats lo=10)");

        // ---------------- DET：同输入同结果（与来源顺序无关） ----------------
        List<ContentSource> shuffled = new ArrayList<ContentSource>();
        shuffled.add(ContentSource.dir("hi", new File(tmp, "hi/content"), 20));
        shuffled.add(ContentSource.dir("base", baseDir, 0));
        shuffled.add(ContentSource.dir("lo", new File(tmp, "lo/content"), 10));
        ContentRegistry rp2 = ContentRegistry.loadAll(shuffled);
        boolean det = rp.snapshot().equals(rp2.snapshot());
        ck("DET", det, "snapshotBytes=" + rp.snapshot().length() + " identical=" + det);

        // ---------------- ZIP：压缩包 mod 可用 ----------------
        File zipFile = File.createTempFile("modziptest", ".zip");
        ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(zipFile));
        zos.putNextEntry(new ZipEntry("content/particles/ziptest.json"));
        zos.write("{\"count\":7}".getBytes("UTF-8"));
        zos.closeEntry();
        zos.putNextEntry(new ZipEntry("content/skills/zipskill.json"));
        zos.write("{\"name\":\"ZipSkill\",\"tier\":1,\"costSouls\":0,\"effects\":[{\"type\":\"SPAWN_PARTICLE\",\"id\":\"ziptest\"}]}".getBytes("UTF-8"));
        zos.closeEntry();
        zos.close();
        List<ContentSource> zsrc = new ArrayList<ContentSource>();
        zsrc.add(ContentSource.dir("base", baseDir, 0));
        zsrc.add(ContentSource.zip("zmod", zipFile, "content/", 10));
        ContentRegistry rz = ContentRegistry.loadAll(zsrc);
        ck("ZIP", rz.ok() && rz.of("particles").containsKey("ziptest") && rz.of("skills").containsKey("zipskill"),
                "particles=" + (rz.of("particles").containsKey("ziptest"))
                        + " skills=" + (rz.of("skills").containsKey("zipskill")));

        // ---------------- ISOLATION：坏 mod 的引用错误必须报出（不静默） ----------------
        write(new File(tmp, "bad/content/skills/broken.json"),
                "{\"name\":\"Broken\",\"effects\":[{\"type\":\"SPAWN_PARTICLE\",\"id\":\"ghost_particle\"}]}");
        List<ContentSource> bsrc = new ArrayList<ContentSource>();
        bsrc.add(ContentSource.dir("base", baseDir, 0));
        bsrc.add(ContentSource.dir("bad", new File(tmp, "bad/content"), 10));
        ContentRegistry rbad = ContentRegistry.loadAll(bsrc);
        boolean caught = !rbad.ok();
        String msg = rbad.errors().isEmpty() ? "(none)" : rbad.errors().get(0);
        ck("ISOLATION", caught && msg.contains("ghost_particle"),
                "errors=" + rbad.errors().size() + " first=\"" + msg + "\"");

        // ---------------- CONFLICT：同优先级两名来源争同一 ID → 后者胜且被记录 ----------------
        write(new File(tmp, "m1/content/particles/c.json"), "{\"count\":11}");
        write(new File(tmp, "m2/content/particles/c.json"), "{\"count\":22}");
        List<ContentSource> csrc = new ArrayList<ContentSource>();
        csrc.add(ContentSource.dir("base", baseDir, 0));
        csrc.add(ContentSource.dir("m1", new File(tmp, "m1/content"), 10));
        csrc.add(ContentSource.dir("m2", new File(tmp, "m2/content"), 10));   // 同优先级 → 名字典序 m2 后放
        ContentRegistry rc = ContentRegistry.loadAll(csrc);
        float win = rc.of("particles").get("c").get("count").getAsFloat();
        boolean recorded = false;
        for (String o : rc.overrides()) if (o.contains("particles/c.json")) recorded = true;
        ck("CONFLICT", win == 22f && recorded,
                "winner=m2(" + win + ") tieBrokenByName=true recorded=" + recorded);

        System.out.println("MOD " + (fails == 0 ? "PASS" : "FAIL " + fails) + "  (10 properties)");
        if (fails > 0) System.exit(1);
    }
}
