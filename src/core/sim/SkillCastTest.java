package core.sim;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import core.content.ContentRegistry;
import core.content.EffectSink;
import core.content.SkillDef;
import core.systems.BuffSystem;
import core.systems.ContentSystem;
import core.world.Beast;
import core.world.Player;
import core.world.World;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 技能链门禁 —— 断言 A 批（技能释放 / targeting / 状态）的三组性质。
 *
 * <p>这三条此前<b>都不成立</b>，因为链子是断的：{@code ContentSystem.cast} 从未被调用、
 * {@code targeting} 从未被读、{@code APPLY_BUFF} 掉进空分支被静默丢弃。本门禁把它们钉住：
 * <ol>
 *   <li><b>DET</b>：施法不扰动确定性 —— 同种子同输入两遍 {@code hashState()} 逐字节一致
 *       （技能只走实体的 HP / buff 表，从不写 mat/mass/rng）；</li>
 *   <li><b>TARGETING</b>：{@code sphere/radius/maxTargets} 真的生效 —— 半径内全中、
 *       半径外一只不打（<b>负例护栏</b>）、超上限真的截断；</li>
 *   <li><b>CAST / FANOUT / BUFF</b>：校验（未学 / 体力 / 冷却）真的拦得住；
 *       {@code TARGET} 锚点的效果真的按命中数展开；状态真的按 {@code tickEvery} 掉血、
 *       真的减速、真的到期停止。</li>
 * </ol>
 *
 * <p><b>边界（诚实声明）</b>：{@code DAMAGE} / {@code APPLY_BUFF} 的"落地语义"由 Game 层
 * 在 {@link EffectSink#simulate} 里实现（那里才有 world/player 的游戏语义）。门禁环境没有
 * 渲染器，故用一个**等价替身 sink**（{@link Rec}）复刻同一套语义：伤害走
 * {@link Player#hitBeast}（产品代码里唯一的伤害入口），状态走 {@link BuffSystem}（产品代码）。
 * 因此本门禁验证的是「cast 提交的坐标与条数正确 + 那两条入口可用」，而不是"GL 画面对不对"。
 */
public final class SkillCastTest {

    private static int fails = 0;

    private static void ck(String tag, boolean cond, String detail) {
        System.out.println(tag + " " + (cond ? "PASS" : "FAIL") + "  " + detail);
        if (!cond) fails++;
    }

    // ---------- 内存内容（自包含，不依赖磁盘 / assets） ----------

    private static Map<String, String> content() {
        Map<String, String> f = new LinkedHashMap<String, String>();
        f.put("particles/spark.json", "{\"shape\":\"point\",\"count\":10}");
        f.put("fx/burst.json", "{\"emitters\":[{\"particle\":\"spark\"}]}");
        f.put("buffs/burning.json",
                "{\"name\":\"Burning\",\"duration\":4.0,\"tickEvery\":0.5,"
              + "\"modifiers\":{\"speed\":-0.15},"
              + "\"effects\":[{\"type\":\"DAMAGE\",\"amount\":3,\"target\":\"SELF\"}]}");
        f.put("skills/flame_burst.json",
                "{\"name\":\"Flame Burst\",\"anim\":\"attack\",\"cost\":22,\"cooldown\":1.4,"
              + "\"targeting\":{\"shape\":\"sphere\",\"radius\":4.0,\"maxTargets\":6},"
              + "\"effects\":["
              + "{\"type\":\"PLAY_SFX\",\"id\":\"charge\"},"
              + "{\"type\":\"DAMAGE\",\"amount\":26,\"target\":\"TARGET\"},"
              + "{\"type\":\"SPAWN_FX\",\"id\":\"burst\",\"at\":\"TARGET\",\"delay\":0.12},"
              + "{\"type\":\"APPLY_BUFF\",\"id\":\"burning\",\"duration\":4.0}]}");
        return f;
    }

    /**
     * 记录型 sink —— 复刻 Game 层 {@code simulate} 的落地语义（DAMAGE→hitBeast、
     * APPLY_BUFF→BuffSystem.apply），其余表现类只计数。
     */
    private static final class Rec implements EffectSink {
        final List<String> calls = new ArrayList<String>();
        final ContentRegistry reg;
        final World w;
        final Player p;
        final BuffSystem buffs;

        Rec(ContentRegistry reg, World w, Player p, BuffSystem buffs) {
            this.reg = reg; this.w = w; this.p = p; this.buffs = buffs;
        }

        int count(String prefix) {
            int n = 0;
            for (int i = 0; i < calls.size(); i++) if (calls.get(i).startsWith(prefix)) n++;
            return n;
        }

        @Override public void particle(JsonObject d, float x, float y, float z) { calls.add("particle"); }
        @Override public void fx(JsonObject d, float x, float y, float z)       { calls.add("fx"); }
        @Override public void sfx(String id)                                     { calls.add("sfx:" + id); }
        @Override public void shake(float amp)                                   { calls.add("shake"); }
        @Override public void banner(String text)                                { calls.add("banner"); }

        @Override public void simulate(String type, String id, float amount, float x, float y, float z) {
            calls.add("sim:" + type + ":" + id);
            if ("DAMAGE".equals(type)) {
                Beast t = near(x, y, z, 3.0f);
                if (t != null) p.hitBeast(w, t, Math.max(1, Math.round(amount)), "test");
            } else if ("APPLY_BUFF".equals(type)) {
                JsonObject bdef = reg.get("buffs", id);
                Beast t = near(x, y, z, 3.0f);
                if (bdef != null && t != null) buffs.apply(bdef, id, t);
            }
        }

        private Beast near(float x, float y, float z, float r) {
            Beast best = null; float bd = r * r;
            for (int i = 0; i < w.beasts.size(); i++) {
                Beast b = w.beasts.get(i);
                if (b == null || b.hp <= 0) continue;
                float dx = b.x - x, dy = b.y - y, dz = b.z - z;
                float q = dx * dx + dy * dy + dz * dz;
                if (q <= bd) { bd = q; best = b; }
            }
            return best;
        }
    }

    /** 造一个最小世界：玩家 + 已学技能 + 若干敌兵。 */
    private static World mkWorld(long seed, Player[] out, int[] beastXs) {
        World w = new World(seed, 64, 40, 64);
        Player p = new Player();
        w.player = p;
        p.x = 20.5f; p.y = 30f; p.z = 20.5f;
        out[0] = p;
        for (int i = 0; i < beastXs.length; i++) {
            w.beasts.add(Beast.make(0, beastXs[i], 30f, 20.5f));
        }
        return w;
    }

    // ---------- 1) DET：施法不扰动确定性 ----------

    private static String determinismRun(long seed) {
        Player[] po = new Player[1];
        World w = mkWorld(seed, po, new int[]{21, 22, 23});
        Player p = po[0];
        ContentRegistry reg = ContentRegistry.of(content());
        ContentSystem cs = new ContentSystem(reg, seed);
        cs.setSink(EffectSink.NONE);
        w.addSkill("flame_burst");

        boolean cast = cs.cast(w, p, "flame_burst", w.tick);
        for (int t = 0; t < 6; t++) {
            w.tick();
            cs.update(w, null);        // rng 不传 —— 内容层零 RNG
        }
        return w.hashState() + "|cast=" + cast + "|cd=" + p.skillCd;
    }

    // ---------- main ----------

    public static void main(String[] args) {

        // ---- 1) DET：两遍同种子同输入 → hashState 逐字节一致 ----
        String h1 = determinismRun(20260916L);
        String h2 = determinismRun(20260916L);
        ck("DET", h1.equals(h2) && h1.contains("cast=true"),
                "hash=" + h1.substring(0, Math.min(40, h1.length())) + " equal=" + h1.equals(h2));

        ContentRegistry reg = ContentRegistry.of(content());
        SkillDef sd = SkillDef.parse("flame_burst", reg.get("skills", "flame_burst"));
        ck("SKILLDEF", sd != null && sd.cost == 22 && sd.cooldown == 1.4f
                        && sd.maxTargets == 6 && Math.abs(sd.radius - 4.0f) < 1e-6f,
                "cost=" + (sd == null ? "?" : sd.cost) + " cd=" + (sd == null ? "?" : sd.cooldown)
                        + " r=" + (sd == null ? "?" : sd.radius) + " n<=" + (sd == null ? "?" : sd.maxTargets));

        // ---- 2) TARGETING：半径内全中、半径外一只不打（负例）----
        Player[] po1 = new Player[1];
        World w1 = mkWorld(11L, po1, new int[]{21, 23, 24, 30, 20});
        // 索引 0/1/2 在 r=4 内（dx=1..4, dy=0, dz=0）；索引 3 在 dx=10（远）；索引 4 在 dx=-1 但 z=20.5 → 在内
        // 为让负例明确，把索引 4 挪到远处：
        w1.beasts.get(4).z = 40f;                       // dz≈19.5 → 球外
        List<Beast> hit = SkillDef.selectTargets(w1, sd, 20.5f, 30f, 20.5f);
        boolean inRange = hit.size() == 3;
        boolean farExcluded = !hit.contains(w1.beasts.get(3)) && !hit.contains(w1.beasts.get(4));
        ck("TARGETING", inRange, "hit=" + hit.size() + " expect 3");
        ck("TARGETING_NEG", farExcluded, "far excluded (dx=10 / dz=19.5)");

        // ---- 3) MAXTARGETS：8 只在内 → 截断到 6 ----
        Player[] po2 = new Player[1];
        World w2 = mkWorld(12L, po2, new int[]{21, 22, 23, 24, 20, 20, 21, 22});
        for (int i = 0; i < 8; i++) { w2.beasts.get(i).z = 20.5f; w2.beasts.get(i).y = 30f; }
        List<Beast> h8 = SkillDef.selectTargets(w2, sd, 20.5f, 30f, 20.5f);
        ck("MAXTARGETS", h8.size() == 6, "hit=" + h8.size() + " expect 6 (cap)");

        // ---- 4) CAST 校验：未学 / 体力 / 冷却 三道闸 ----
        Player[] po3 = new Player[1];
        World w3 = mkWorld(13L, po3, new int[]{21, 22, 23});
        Player p3 = po3[0];
        ContentSystem cs3 = new ContentSystem(reg, 13L);
        cs3.setSink(EffectSink.NONE);
        boolean noLearn = !cs3.cast(w3, p3, "flame_burst", w3.tick);      // 未学
        w3.addSkill("flame_burst");
        p3.stamina = 5;
        boolean noStam = !cs3.cast(w3, p3, "flame_burst", w3.tick);       // 体力不足（need 22）
        p3.stamina = 100;
        boolean ok = cs3.cast(w3, p3, "flame_burst", w3.tick);            // 成功
        boolean onCd = !cs3.cast(w3, p3, "flame_burst", w3.tick);         // 冷却中
        ck("CAST_GATE", noLearn && noStam && ok && onCd,
                "noLearn=" + noLearn + " noStamina=" + noStam + " ok=" + ok + " onCd=" + onCd);
        ck("CAST_COST", p3.stamina == 78, "stamina=" + p3.stamina + " expect 78");
        ck("CAST_CD", Math.abs(p3.skillCd - 1.4f) < 1e-5f, "skillCd=" + p3.skillCd);

        // ---- 5) FANOUT：TARGET 锚点按命中数展开（3 命中 → DAMAGE×3 / FX×3）----
        Player[] po4 = new Player[1];
        World w4 = mkWorld(14L, po4, new int[]{21, 22, 23});
        Player p4 = po4[0];
        w4.addSkill("flame_burst");
        BuffSystem bs4 = new BuffSystem();
        Rec rec = new Rec(reg, w4, p4, bs4);
        ContentSystem cs4 = new ContentSystem(reg, 14L);
        cs4.setSink(rec);
        cs4.cast(w4, p4, "flame_burst", w4.tick);
        cs4.update(w4, null);                                  // delay=0 的效果立刻执行
        int dmg = rec.count("sim:DAMAGE");
        for (int t = 0; t < 3; t++) { w4.tick(); cs4.update(w4, null); }   // delay=0.12s → 2 tick 后
        int fxCalls = rec.count("fx");
        boolean hurt = w4.beasts.get(0).maxHp - w4.beasts.get(0).hp >= 26;   // 命中 26 点
        ck("CAST_FANOUT", dmg == 3 && fxCalls == 3,
                "DAMAGE×" + dmg + " FX×" + fxCalls + " expect 3/3");
        ck("CAST_HURT", hurt, "beast0 hp=" + w4.beasts.get(0).hp + "/" + w4.beasts.get(0).maxHp);
        ck("CAST_BUFF", bs4.activeCount() == 1, "buffs applied=" + bs4.activeCount());

        // ---- 6) BUFF：tickEvery 掉血 / 减速 / 到期停止 ----
        Player[] po5 = new Player[1];
        World w5 = mkWorld(15L, po5, new int[]{});
        Player p5 = po5[0];
        JsonObject bdef = reg.get("buffs", "burning");
        BuffSystem bs = new BuffSystem();
        Beast b = Beast.make(0, 25f, 30f, 25f);
        w5.beasts.add(b);
        int hp0 = b.hp;
        bs.apply(bdef, "burning", b);
        ck("BUFF_APPLY", bs.activeCount() == 1, "active=" + bs.activeCount() + " hp0=" + hp0);

        for (int i = 0; i < 10; i++) bs.update(w5, null);          // 0.5s = 10 tick → 触发 1 次 × 3 伤
        ck("BUFF_DOT", b.hp == hp0 - 3, "hp " + hp0 + " -> " + b.hp + " (expect -3)");
        ck("BUFF_SLOW", Math.abs(b.slowMul - 0.85f) < 1e-4f, "slowMul=" + b.slowMul);

        for (int i = 0; i < 200; i++) bs.update(w5, null);         // 4s = 80 tick → 早已到期
        ck("BUFF_EXPIRE", bs.activeCount() == 0 && Math.abs(b.slowMul - 1f) < 1e-6f,
                "active=" + bs.activeCount() + " slowMul=" + b.slowMul);

        System.out.println("SKILLCAST RESULT: " + (fails == 0 ? "PASS" : "FAIL (" + fails + ")"));
        if (fails > 0) System.exit(1);
    }
}
