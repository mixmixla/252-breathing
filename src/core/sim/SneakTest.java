package core.sim;

import core.world.Blocks;
import core.world.Player;
import core.world.World;

/**
 * 潜行防坠边性质测试（QA 2026-09-12 · TEST_STRATEGY P0）。
 *
 * <p>断言的是<b>性质</b>：
 * ① 防坠边——悬空走廊上 sneak 持续推进，玩家被"看不见的栏杆"拦在平台内（不掉落、保持 onGround）；
 * ② 不误伤——同一路径 sneak=false 能正常走出平台边缘（防坠边只属于潜行）；
 * ③ 减速比——平地上 sneak 位移 ≈ 0.45× 正常位移；
 * ④ 旧签名兼容——STEP/WALL2 由 PhysicsTest 继续锁定（旧 5 参 physicsTick 转发 sneak=false）。
 */
public final class SneakTest {
    private static final float DT = 1f / 60f;

    public static void main(String[] args) {
        int fails = 0;

        // ---- 场景：悬空 1 格宽走廊（y=25 平台 x40..46 / z=48，四周清空）----
        World w = new World(20260912L, 96, 48, 96);
        for (int x = 34; x <= 52; x++)
            for (int z = 44; z <= 52; z++)
                for (int y = 22; y <= 28; y++)
                    w.setBlock(x, y, z, Blocks.AIR.index);
        for (int x = 40; x <= 46; x++) w.setBlock(x, 25, 48, Blocks.STONE.index);

        // ---- 1. sneak：被拦在平台内 ----
        Player p = new Player();
        p.x = 40.5f; p.y = 26f; p.z = 48.5f; p.onGround = true; p.vy = 0f;
        w.player = p;
        for (int i = 0; i < 400; i++) p.physicsTick(w, 1f, 0f, false, false, true, DT);
        // 性质：玩家盒永不完全离开支撑（允许盒沿压着平台末列搭边——与 MC maybeBackOffFromEdge 语义一致），且不掉落
        boolean blocked = p.y > 25.5f && p.onGround && (p.x - 0.3f) < 47.0f;
        System.out.println("EDGE-BLOCK " + (blocked ? "PASS" : "FAIL") + "  x=" + p.x + " y=" + p.y + " onGround=" + p.onGround);
        if (!blocked) fails++;

        // ---- 2. 不误伤：normal 同路径走出边缘掉落 ----
        Player q = new Player();
        q.x = 40.5f; q.y = 26f; q.z = 48.5f; q.onGround = true; q.vy = 0f;
        w.player = q;
        for (int i = 0; i < 400; i++) q.physicsTick(w, 1f, 0f, false, false, false, DT);
        boolean free = q.x > 47.0f || !q.onGround;
        System.out.println("EDGE-FREE  " + (free ? "PASS" : "FAIL") + "  x=" + q.x + " y=" + q.y + " onGround=" + q.onGround);
        if (!free) fails++;

        // ---- 3. 减速比 ≈ 0.45（开阔平地，排除地形干扰）----
        for (int x = 50; x <= 74; x++)
            for (int z = 44; z <= 52; z++) {
                for (int y = 20; y <= 24; y++) w.setBlock(x, y, z, Blocks.AIR.index);
                w.setBlock(x, 19, z, Blocks.STONE.index);
            }
        Player s1 = new Player();
        s1.x = 56.5f; s1.y = 20f; s1.z = 48.5f; s1.onGround = true; s1.vy = 0f;
        w.player = s1;
        for (int i = 0; i < 60; i++) s1.physicsTick(w, 1f, 0f, false, false, true, DT);
        Player s2 = new Player();
        s2.x = 56.5f; s2.y = 20f; s2.z = 48.5f; s2.onGround = true; s2.vy = 0f;
        w.player = s2;
        for (int i = 0; i < 60; i++) s2.physicsTick(w, 1f, 0f, false, false, false, DT);
        float dSneak = Math.abs(s1.x - 56.5f), dNorm = Math.abs(s2.x - 56.5f);
        boolean ratio = dNorm > 0.5f && dSneak / dNorm > 0.35f && dSneak / dNorm < 0.55f;
        System.out.println("SLOW-RATIO " + (ratio ? "PASS" : "FAIL") + "  sneak=" + dSneak + " normal=" + dNorm + " ratio=" + (dNorm > 0 ? dSneak / dNorm : 0));
        if (!ratio) fails++;

        System.out.println(fails == 0 ? "SNEAK PASS" : ("SNEAK FAIL (" + fails + ")"));
        if (fails > 0) System.exit(1);
    }
}
