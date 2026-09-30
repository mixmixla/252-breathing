// 一次性诊断探针：验证若干“mut=0 但看似满足前置”的系统是否真被世界条件阻断。
import core.world.Blocks;
import core.world.World;
import java.util.*;

public class ProbeAudit {
    public static void main(String[] a) {
        int SX=96,SY=48,SZ=96,T=400; long seed=20260909L;
        World w = new World(seed,SX,SY,SZ);
        // 用默认 81 系统
        core.sim.Simulation sim = new core.sim.Simulation(seed,SX,SY,SZ);
        w = sim.world;
        for (int t=0;t<T;t++) w.tickAudited();

        int moss=0, leaf=0, water=0, sand=0, coal=0, iron=0;
        for (int x=0;x<SX;x++)for(int y=0;y<SY;y++)for(int z=0;z<SZ;z++){
            int b=w.mat[x][y][z];
            if(b==Blocks.MOSS.index)moss++;
            if(b==Blocks.LEAF.index)leaf++;
            if(b==Blocks.WATER.index)water++;
            if(b==Blocks.SAND.index)sand++;
            if(b==Blocks.COAL_ORE.index)coal++;
            if(b==Blocks.IRON_ORE.index)iron++;
        }
        System.out.println("MOSS="+moss+" LEAF="+leaf+" WATER="+water+" SAND="+sand+" COAL="+coal+" IRON="+iron);

        // SporeSystem 前置：LEAF/MOSS 且其正上方为 AIR 的格数
        int sporeOK=0;
        for (int x=0;x<SX;x++)for(int y=0;y<SY-1;y++)for(int z=0;z<SZ;z++){
            int b=w.mat[x][y][z];
            if((b==Blocks.LEAF.index||b==Blocks.MOSS.index) && w.mat[x][y+1][z]==Blocks.AIR.index) sporeOK++;
        }
        System.out.println("SporeSystem viable cells (LEAF/MOSS with AIR above) = "+sporeOK);

        // ParasiteSystem 前置：LEAF 且 6 邻 ≥4 LEAF
        int paraOK=0;
        int[][]D6={{1,0,0},{-1,0,0},{0,1,0},{0,-1,0},{0,0,1},{0,0,-1}};
        for (int x=1;x<SX-1;x++)for(int y=1;y<SY-1;y++)for(int z=1;z<SZ-1;z++){
            if(w.mat[x][y][z]!=Blocks.LEAF.index)continue;
            int n=0;for(int[]o:D6)if(w.getBlock(x+o[0],y+o[1],z+o[2])==Blocks.LEAF.index)n++;
            if(n>=4)paraOK++;
        }
        System.out.println("ParasiteSystem viable cells (LEAF w/ >=4 LEAF nbrs) = "+paraOK);

        // TideSystem 前置：水表面列，且其上方 2 层皆 AIR（可涨潮）或上方 1 层 AIR（可退潮）
        int tideHigh=0, tideLow=0, waterCols=0;
        for (int x=0;x<SX;x++)for(int z=0;z<SZ;z++){
            int wy=-1;
            for(int y=SY-1;y>=0;y--){if(w.mat[x][y][z]==Blocks.WATER.index){wy=y;break;}}
            if(wy<0)continue;
            waterCols++;
            if(w.getBlock(x,wy+1,z)==Blocks.AIR.index && w.getBlock(x,wy+2,z)==Blocks.AIR.index)tideHigh++;
            if(w.getBlock(x,wy+1,z)==Blocks.AIR.index)tideLow++;
        }
        System.out.println("TideSystem waterCols="+waterCols+" highViable="+tideHigh+" lowViable="+tideLow);

        // Geode/Mushroom 前置：AIR 且 6 邻 ≥5 STONE
        int geodeOK=0;
        for (int x=1;x<SX-1;x++)for(int y=1;y<SY-1;y++)for(int z=1;z<SZ-1;z++){
            if(w.mat[x][y][z]!=Blocks.AIR.index)continue;
            int s=0;for(int[]o:D6)if(w.getBlock(x+o[0],y+o[1],z+o[2])==Blocks.STONE.index)s++;
            if(s>=5)geodeOK++;
        }
        System.out.println("Geode/Mushroom viable cells (AIR w/ >=5 STONE nbrs) = "+geodeOK);

        System.out.println("sysMut spore="+w.sysMut.get("spore")+" parasite="+w.sysMut.get("parasite")
            +" tide="+w.sysMut.get("tide")+" geode="+w.sysMut.get("geode")+" fungus="+w.sysMut.get("fungus"));
    }
}
