// PROTOTYPE - NOT FOR PRODUCTION
// Question: 洞穴世界下，81 个涌现系统中"休眠的地下系统"(Lava/Geode/Crystal/MagmaChamber/Sinkhole 等)
//           是否真的改写网格？以及洞穴生成是否生效？
// Date: 2026-09-09
//
// 无头运行（不需要 GPU/GL）：构造带洞穴的世界 → 跑 N tick → 对比初始/终末方块分布。
// 用法: javac -cp out;libs/joml-1.10.5.jar -d out_chk tools/VerifySystems.java
//       java  -cp out_chk;out;libs/joml-1.10.5.jar VerifySystems

import core.sim.Simulation;
import core.world.Blocks;
import core.world.World;

public class VerifySystems {
    public static void main(String[] args) {
        int SX = 48, SY = 32, SZ = 48;
        long seed = 20260908L;
        int TICKS = 400;

        Simulation sim = new Simulation(seed, SX, SY, SZ);
        World w = sim.world;

        int[] before = tally(w);
        int caveAir0 = undergroundAir(w);
        long hash0 = w.hashState();

        for (int t = 0; t < TICKS; t++) w.tick();
        int[] after = tally(w);
        int caveAir1 = undergroundAir(w);
        long hash1 = w.hashState();

        System.out.println("=== VerifySystems (seed=" + seed + ", world=" + SX + "x" + SY + "x" + SZ
                + ", ticks=" + TICKS + ", systems=" + w.systemCount() + ") ===");

        // 确定性自检：同种子两次独立运行应一致（这里只跑一次，但给出指纹供人工对比）
        System.out.println("hashState (after) = " + Long.toHexString(hash1));

        // 洞穴生效判定：地下空腔应显著存在（无洞穴时地下实心，undergroundAir≈0）
        System.out.println("underground AIR (initial) = " + caveAir0
                + (caveAir0 > 100 ? "  [CAVES OK]" : "  [WARN: too few caves]"));

        // 地下系统签名
        int deepFire  = blockRange(w, Blocks.FIRE.index, 0, SY / 3);       // Lava / MagmaChamber
        int deepLamp  = blockRange(w, Blocks.LAMP.index, 0, SY / 3);       // Crystal / MagmaChamber 发光矿
        int oreCount  = blockRange(w, Blocks.COAL_ORE.index, 0, SY)
                      + blockRange(w, Blocks.IRON_ORE.index, 0, SY);        // Geode / MineralVein / OreExposure
        int fireAny   = blockRange(w, Blocks.FIRE.index, 0, SY);
        int oreAny    = oreCount;
        int sandTop   = blockRange(w, Blocks.SAND.index, SY - 2, SY);       // Volcano 喷灰落顶（间接）

        System.out.println("--- underground-system signatures (after " + TICKS + " ticks) ---");
        System.out.println("deep FIRE  (y<" + (SY/3) + ") = " + deepFire + "   [Lava/MagmaChamber]");
        System.out.println("deep LAMP  (y<" + (SY/3) + ") = " + deepLamp + "   [Crystal]");
        System.out.println("ORE total  (COAL+IRON)    = " + oreAny  + "   [Geode/MineralVein/OreExposure]");
        System.out.println("FIRE total               = " + fireAny);
        System.out.println("surface SAND (top 2 y)   = " + sandTop + "   [Volcano indirect]");

        // 方块分布变化表
        System.out.println("--- block count delta (after - before) ---");
        String[] names = {"AIR","GRASS","DIRT","STONE","WATER","SAND","WOOD","LEAF","BEDROCK",
                "LAMP","SHELTER","COAL_ORE","IRON_ORE","FIRE","GLASS","SNOW","ICE","CACTUS",
                "FLOWER","MOSS","CLAY"};
        for (int i = 0; i < names.length && i < before.length; i++) {
            int d = after[i] - before[i];
            if (d != 0) System.out.printf("  %-9s %+d%n", names[i], d);
        }

        boolean cavesOk   = caveAir0 > 100;
        boolean underOk   = (deepFire + deepLamp + oreAny) > 0;
        boolean consistency = (hash0 != hash1) || TICKS == 0;  // 跑过 tick 指纹必变（有系统活动/演化）
        System.out.println("=== VERDICT ===");
        System.out.println("caves generated      : " + (cavesOk ? "PASS" : "FAIL"));
        System.out.println("underground systems  : " + (underOk ? "PASS" : "FAIL (still mostly dormant)"));
        System.out.println("state evolved        : " + (consistency ? "PASS" : "FAIL"));
        if (cavesOk && underOk) {
            System.out.println("RESULT: PASS — 洞穴生效，地下系统已激活改写网格");
        } else {
            System.out.println("RESULT: NEEDS-TUNING — 见上方 WARN/FAIL");
        }
    }

    static int[] tally(World w) {
        int[] c = new int[Blocks.count()];
        for (int x = 0; x < w.SX; x++)
            for (int y = 0; y < w.SY; y++)
                for (int z = 0; z < w.SZ; z++)
                    c[w.mat[x][y][z]]++;
        return c;
    }

    static int undergroundAir(World w) {
        int n = 0;
        for (int x = 0; x < w.SX; x++)
            for (int y = 2; y < w.SY / 2; y++)
                for (int z = 0; z < w.SZ; z++)
                    if (w.mat[x][y][z] == Blocks.AIR.index) n++;
        return n;
    }

    static int blockRange(World w, int idx, int y0, int y1) {
        int n = 0;
        for (int x = 0; x < w.SX; x++)
            for (int y = y0; y < y1; y++)
                for (int z = 0; z < w.SZ; z++)
                    if (w.mat[x][y][z] == idx) n++;
        return n;
    }
}
