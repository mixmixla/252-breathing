package core.sim;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;

import core.content.CharacterDef;
import core.content.ContentRegistry;
import core.world.Appearance;
import core.world.World;

/**
 * 捏脸系统门禁（F 批，第 N 道）。
 *
 * <p>断言四类：
 * <ol>
 *   <li><b>内容</b>：{@code assets/content/characters/*.json} 加载为预设（≥4），解析字段有效。</li>
 *   <li><b>菜单状态机</b>：MAIN 第 5 项进 CHARACTER 页（8 项）；调参循环预设/改肤色确定性生效；返回落点稳定。</li>
 *   <li><b>零漂移</b>：玩家外观（身份数据）<b>不进</b> {@link World#hashState()} —— 两套不同外观跑同种子同 tick，终态指纹逐字节一致。</li>
 *   <li><b>存读档往返</b>：v4 扩展段序列化外观，save→load 后外观字段逐字节还原（旧档 ext&lt;4 自然默认，不破坏立项 F）。</li>
 * </ol>
 *
 * 运行：java -cp out core.sim.CharacterTest
 */
public final class CharacterTest {

    private static final int SX = 64, SY = 40, SZ = 64;
    private static final long SEED = 20260914L;
    private static final int T = 200;

    private static int passed = 0;
    private static void ck(String name, boolean ok, String detail) {
        System.out.println((ok ? "  ok  " : " FAIL ") + name + (ok ? "" : "  -> " + detail));
        if (ok) passed++;
    }

    public static void main(String[] args) throws java.io.IOException {
        StringBuilder ev = new StringBuilder();

        // ---------- 1) 内容：角色预设加载 + 解析 ----------
        ContentRegistry reg = ContentRegistry.load(new File("assets/content"));
        int nChars = reg.characters().size();
        boolean contentLoaded = nChars >= 4;
        boolean parsedOk = true;
        String parseDetail = "";
        for (java.util.Map.Entry<String, com.google.gson.JsonObject> e : reg.characters().entrySet()) {
            CharacterDef d = CharacterDef.fromJson(e.getKey(), e.getValue());
            if (d.name == null || d.name.isEmpty() || d.skin == 0) { parsedOk = false; parseDetail = e.getKey(); break; }
        }
        ck("CONTENT_LOAD", contentLoaded, "characters=" + nChars);
        ck("CONTENT_PARSE", parsedOk, parseDetail);

        // ---------- 2) 菜单状态机：MAIN→CHARACTER ----------
        MenuModel.CharacterProvider stub = new MenuModel.CharacterProvider() {
            @Override public Appearance preset(String id) {
                com.google.gson.JsonObject o = reg.get("characters", id);
                if (o == null) return null;
                CharacterDef d = CharacterDef.fromJson(id, o);
                Appearance a = new Appearance(); a.applyDef(d); return a;
            }
            @Override public String[] ids() {
                return reg.characters().keySet().toArray(new String[0]);
            }
        };
        MenuModel m = new MenuModel();
        m.open();
        m.down(); m.down(); m.down();                 // 0→1→2→3（CHARACTER 第 5 项）
        boolean reachChar = m.page() == MenuModel.Page.MAIN && m.selected() == 3;
        m.openCharacter(new Appearance(), stub);       // 初始化工作副本（默认外观）
        m.activate();                                  // → CHARACTER 页
        boolean charPage = m.page() == MenuModel.Page.CHARACTER && m.itemCount() == 20;
        String name0 = m.working.name;
        m.adjust(1);                                   // 切预设（sel 0）
        boolean presetCycle = !m.working.name.equals(name0) && m.working.presetId != null;
        int skin0 = m.working.skin;
        m.down();                                      // sel 0→1（SKIN）
        m.adjust(1);                                   // 改肤色
        boolean skinChange = m.working.skin != skin0;
        m.back();                                      // 返回 MAIN（落 CHARACTER 项）
        boolean backMain = m.page() == MenuModel.Page.MAIN && m.selected() == 3;
        ck("MENU_REACH", reachChar, "page=" + m.page() + " sel=" + m.selected());
        ck("MENU_CHAR_PAGE", charPage, "page=" + m.page() + " n=" + m.itemCount());
        ck("MENU_PRESET_CYCLE", presetCycle, "name " + name0 + "->" + m.working.name);
        ck("MENU_SKIN_CHANGE", skinChange, "skin " + skin0 + "->" + m.working.skin);
        ck("MENU_BACK", backMain, "page=" + m.page() + " sel=" + m.selected());
        ev.append("CONTENT n=").append(nChars).append(" MENU reach=").append(reachChar)
          .append(" page=").append(charPage).append(" preset=").append(presetCycle)
          .append(" skin=").append(skinChange).append(" back=").append(backMain);

        // ---------- 3) 零漂移：外观不进指纹 ----------
        Simulation s1 = new Simulation(SEED, SX, SY, SZ);
        Simulation s2 = new Simulation(SEED, SX, SY, SZ);
        s1.world.player.appearance.skin = 0x123456;
        s1.world.player.appearance.hairStyle = 2;
        s1.world.player.appearance.build = 0;
        s2.world.player.appearance.skin = 0xABCDEF;
        s2.world.player.appearance.hairStyle = 3;
        s2.world.player.appearance.build = 2;
        for (int i = 0; i < T; i++) { s1.world.tick(); s2.world.tick(); }
        boolean zeroDrift = s1.world.hashState() == s2.world.hashState();
        ev.append(" | ZERO-DRIFT ").append(Long.toHexString(s1.world.hashState()))
          .append("==").append(Long.toHexString(s2.world.hashState()));
        ck("ZERO_DRIFT", zeroDrift, "hash differs with different appearance");

        // ---------- 4) 存读档往返（v4 外观段）----------
        Simulation s3 = new Simulation(SEED, SX, SY, SZ);
        s3.world.player.appearance.presetId = "guardian";
        s3.world.player.appearance.name = "\u6d4b\u8bd5\u540d";
        s3.world.player.appearance.skin = 0x112233;
        s3.world.player.appearance.hairStyle = 3;
        s3.world.player.appearance.build = 2;
        s3.world.player.appearance.accent = 0x445566;
        s3.world.player.appearance.eye = 0x778899;
        s3.world.player.appearance.faceShape = 2;
        s3.world.player.appearance.eyeShape = 1;
        s3.world.player.appearance.browStyle = 2;
        s3.world.player.appearance.noseStyle = 2;
        s3.world.player.appearance.mouthStyle = 1;
        s3.world.player.appearance.age = 1;
        s3.world.player.appearance.height = 2;
        s3.world.player.appearance.tattoo = 3;
        s3.world.player.appearance.beard = 2;
        s3.world.player.appearance.armor = 2;
        s3.world.player.appearance.helmet = 3;
        s3.world.player.appearance.cloak = 1;
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        s3.world.save(baos);
        World w2 = World.load(new ByteArrayInputStream(baos.toByteArray()));
        Appearance a2 = w2.player != null ? w2.player.appearance : null;
        boolean saveOk = a2 != null
                && "guardian".equals(a2.presetId)
                && "\u6d4b\u8bd5\u540d".equals(a2.name)
                && a2.skin == 0x112233 && a2.hairStyle == 3 && a2.build == 2
                && a2.accent == 0x445566 && a2.eye == 0x778899
                && a2.faceShape == 2 && a2.eyeShape == 1 && a2.browStyle == 2
                && a2.noseStyle == 2 && a2.mouthStyle == 1 && a2.age == 1
                && a2.height == 2 && a2.tattoo == 3 && a2.beard == 2
                && a2.armor == 2 && a2.helmet == 3 && a2.cloak == 1;
        ck("SAVE_ROUNDTRIP", saveOk,
                a2 == null ? "no player" : ("preset=" + a2.presetId + " skin=" + a2.skin + " build=" + a2.build));
        ev.append(" | SAVE_ROUNDTRIP=").append(saveOk);

        boolean pass = contentLoaded && parsedOk && reachChar && charPage && presetCycle
                && skinChange && backMain && zeroDrift && saveOk;
        System.out.println("CHARACTER " + ev);
        System.out.println(pass ? "CHARACTER PASS (" + passed + " props)" : "CHARACTER FAIL");
        if (!pass) System.exit(1);
    }
}
