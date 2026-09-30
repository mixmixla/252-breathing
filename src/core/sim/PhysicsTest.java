package core.sim;

import core.world.Blocks;
import core.world.Player;
import core.world.World;

/**
 * 玩家行走物理 headless 验证（不依赖 GPU/显示器）。
 * 验证三件事：① 从空中下落能落在方块顶（不穿地、onGround=true）；
 * ② 着地时按跳能起跳升高并最终再落地；③ 向固体墙推进会被 AABB 挡住不穿墙。
 */
public final class PhysicsTest {
    public static void main(String[] args) {
        int fails = 0;

        // ---- 1. 落地 ----
        World w = new World(20260908L, 96, 48, 96);
        Player p = new Player();
        float ground = Player.spawnY(w);
        System.out.println("DEBUG ground=" + ground + " SY=" + w.SY);
        p.x = 48.5f; p.z = 48.5f; p.y = Math.min(ground + 2f, w.SY - 3f); p.onGround = false; p.vy = 0f;
        for (int i = 0; i < 600; i++) p.physicsTick(w, 0, 0, false, false, 1f / 60f);
        boolean underFootSolid = Blocks.byIndex(w.getBlock((int) Math.floor(p.x), (int) Math.floor(p.y) - 1, (int) Math.floor(p.z))).solid;
        boolean landOk = p.onGround && underFootSolid;   // 落地且脚下是实心 = 没穿地
        System.out.println("LAND  " + (landOk ? "PASS" : "FAIL") + "  y=" + p.y + " onGround=" + p.onGround + " underFootSolid=" + underFootSolid);
        if (!landOk) fails++;

        // ---- 2. 跳跃（看峰值高度，而非单帧位移）----
        float yBefore = p.y, maxY = p.y;
        for (int i = 0; i < 240; i++) {
            p.physicsTick(w, 0, 0, i == 0, false, 1f / 60f);    // 第 0 帧起跳
            if (p.y > maxY) maxY = p.y;
        }
        boolean jumpOk = (maxY - yBefore) > 0.8f && p.onGround;
        System.out.println("JUMP  " + (jumpOk ? "PASS" : "FAIL") + "  peakRise=" + (maxY - yBefore));
        if (!jumpOk) fails++;

        // ---- 3. 撞墙 ----
        int wallX = (int) Math.floor(p.x) + 1;
        for (int yy = (int) Math.floor(p.y) - 1; yy <= (int) Math.floor(p.y) + 1; yy++)
            w.setBlock(wallX, yy, (int) Math.floor(p.z), Blocks.STONE.index);
        for (int i = 0; i < 200; i++) p.physicsTick(w, 1f, 0f, false, false, 1f / 60f);  // 持续推向 +x
        boolean wallOk = p.x < wallX - 0.3f + 0.02f;     // 玩家半宽0.3，右缘不能进墙
        System.out.println("WALL  " + (wallOk ? "PASS" : "FAIL") + "  x=" + p.x + " wallX=" + wallX);
        if (!wallOk) fails++;

        // ---- 4. auto-step 上台阶（LD-2026-09-11：tryStepUp 从未生效的回归锁）----
        // 性质：1 格台阶持续推行应"自动登上"（y≈台阶顶 且 脚下 solid）；
        //       2 格墙推行应被挡（STEP_H=1.05 只够 1 格）——锁 stepHeight 语义而非"能跑"。
        int baseY = (int) Math.floor(p.y);
        int zA = (int) Math.floor(p.z);
        int fx = (int) Math.floor(p.x);
        int sx0 = fx + 8;                                   // 高台前沿（1 格台阶）
        int wx2 = sx0 + 10;                                 // 2 格墙位置（同走廊远端）
        // 走廊 A：清空 → 铺地板 → sx0 起 1 格高台（登顶后可继续行走，状态稳定）
        for (int xx = fx; xx <= wx2; xx++)
            for (int yy = baseY; yy <= baseY + 3; yy++)
                w.setBlock(xx, yy, zA, Blocks.AIR.index);
        for (int xx = fx; xx < sx0; xx++) w.setBlock(xx, baseY - 1, zA, Blocks.STONE.index);
        for (int xx = sx0; xx <= wx2 + 6; xx++) w.setBlock(xx, baseY, zA, Blocks.STONE.index);
        p.x = fx + 0.5f; p.y = baseY; p.z = zA + 0.5f;
        p.onGround = true; p.vy = 0f;
        for (int i = 0; i < 240; i++) p.physicsTick(w, 1f, 0f, false, false, 1f / 60f);
        boolean onStepTop = Math.abs(p.y - (baseY + 1f)) < 0.2f
                && Blocks.byIndex(w.getBlock((int) Math.floor(p.x), (int) Math.floor(p.y) - 1, zA)).solid;
        System.out.println("STEP  " + (onStepTop ? "PASS" : "FAIL") + "  y=" + p.y + " (want ~" + (baseY + 1) + ")");
        if (!onStepTop) fails++;

        // ---- 5. 2 格墙挡住（auto-step 不该变成攀墙；独立走廊防地形残留干扰）----
        int zB = zA + 5;
        for (int xx = fx; xx <= wx2; xx++)
            for (int yy = baseY; yy <= baseY + 3; yy++)
                w.setBlock(xx, yy, zB, Blocks.AIR.index);
        for (int xx = fx; xx < wx2; xx++) w.setBlock(xx, baseY - 1, zB, Blocks.STONE.index);
        w.setBlock(wx2, baseY, zB, Blocks.STONE.index);     // 2 格墙：y=baseY 与 baseY+1
        w.setBlock(wx2, baseY + 1, zB, Blocks.STONE.index);
        p.x = fx + 0.5f; p.y = baseY; p.z = zB + 0.5f;
        p.onGround = true; p.vy = 0f;
        for (int i = 0; i < 240; i++) p.physicsTick(w, 1f, 0f, false, false, 1f / 60f);
        boolean wall2Ok = p.x < wx2 - 0.3f + 0.02f;
        System.out.println("WALL2 " + (wall2Ok ? "PASS" : "FAIL") + "  x=" + p.x + " wallX=" + wx2);
        if (!wall2Ok) fails++;

        // ---- 5.5 几何基座（第十六批）：非满格方块（台阶/半砖）的**碰撞高度** ----
        // 为什么必须有这一段：`Blocks.shapeHeight` 有三个消费面，只有实体碰撞这一面**没人管** ——
        // 面剔除有 MESHCULL 看着（不许空洞）、顶点有 TILEART/肉眼看着，而 floorY / solidBox /
        // Player.collides 三处一旦口径不一致，症状是"站得住但走不进"或"走进去了却掉下半格"，
        // 两者都只能在实机里靠手感发现。这里把三处的**共同性质**钉死：
        //   ① 半砖地面高度 = 格底 + 0.5（不是 +1，否则玩家"悬在半砖上方半格的空气里"）；
        //   ② 脚底恰在半砖顶面 ⇒ AABB **不**与它相交（所以能站住）；下探 0.1 ⇒ 相交（所以不会穿地）。
        int zC = zA + 10;
        for (int xx = fx; xx <= wx2; xx++)
            for (int yy = baseY; yy <= baseY + 3; yy++)
                w.setBlock(xx, yy, zC, Blocks.AIR.index);
        for (int xx = fx; xx < sx0 - 2; xx++) w.setBlock(xx, baseY - 1, zC, Blocks.STONE.index);      // 平地
        for (int xx = sx0 - 2; xx <= wx2; xx++)                                                       // 半砖地面
            w.setBlock(xx, baseY - 1, zC, Blocks.STONE_SLAB.index);
        float slabTop = (baseY - 1) + Blocks.HALF_BLOCK;
        float floorOnSlab = w.floorY(sx0 + 2.5f, zC + 0.5f, 0.3f, baseY + 3f);
        boolean slabFloorOk = Math.abs(floorOnSlab - slabTop) < 1e-4f;
        System.out.println("SLAB-FLOOR " + (slabFloorOk ? "PASS" : "FAIL")
                + "  floorY=" + floorOnSlab + " want " + slabTop);
        if (!slabFloorOk) fails++;

        boolean atTop = w.solidBox(sx0 + 2.5f, slabTop, zC + 0.5f, 0.3f, 1.8f);
        boolean sunk  = w.solidBox(sx0 + 2.5f, slabTop - 0.1f, zC + 0.5f, 0.3f, 1.8f);
        boolean slabBoxOk = !atTop && sunk;
        System.out.println("SLAB-BOX " + (slabBoxOk ? "PASS" : "FAIL")
                + "  站在顶面相交=" + atTop + "(want false)  下探0.1相交=" + sunk + "(want true)");
        if (!slabBoxOk) fails++;

        // 实机：从半砖上方落下 → 应停在半砖顶面（不是格顶 y=baseY，也不是穿过）
        p.x = sx0 + 2.5f; p.z = zC + 0.5f; p.y = baseY + 1.5f; p.onGround = false; p.vy = 0f;
        for (int i = 0; i < 300; i++) p.physicsTick(w, 0f, 0f, false, false, 1f / 60f);
        boolean slabStand = p.onGround && Math.abs(p.y - slabTop) < 0.2f;
        System.out.println("SLAB-STAND " + (slabStand ? "PASS" : "FAIL")
                + "  y=" + p.y + " want ~" + slabTop + " onGround=" + p.onGround);
        if (!slabStand) fails++;

        // ---- 5.6 台阶（第十七批）：L 形的**两半必须有不同实心高度** ----
        // 性质：朝向那半挡到格顶（1.0），另一半只挡半格（0.5）。
        // 这是 `World.stairsHighHalf` 唯一的守护 —— 它与 solidBox / collides **共用同一个判据**，
        // 写错的症状是"台阶只能从一侧上去"或"从下面穿模"，两者都只能在实机上靠手感发现。
        int zD = zA + 15;
        for (int xx = fx; xx <= wx2; xx++)
            for (int yy = baseY; yy <= baseY + 3; yy++)
                w.setBlock(xx, yy, zD, Blocks.AIR.index);
        int sxStair = fx + 6;
        w.setBlock(sxStair, baseY - 1, zD, Blocks.STONE_STAIRS.index);
        w.setFacing(sxStair, baseY - 1, zD, 0);          // 朝 +X ⇒ 高半边在 x ≥ 格中心
        int gySt = baseY - 1;
        boolean stairHigh = !w.solidBox(sxStair + 0.8f, gySt + 1f, zD + 0.5f, 0.1f, 1.8f)
                && w.solidBox(sxStair + 0.8f, gySt + 0.9f, zD + 0.5f, 0.1f, 1.8f);
        boolean stairLow = !w.solidBox(sxStair + 0.2f, gySt + 0.5f, zD + 0.5f, 0.1f, 1.8f)
                && w.solidBox(sxStair + 0.2f, gySt + 0.4f, zD + 0.5f, 0.1f, 1.8f);
        System.out.println("STAIRS-SHAPE " + ((stairHigh && stairLow) ? "PASS" : "FAIL")
                + "  高半边(挡到 1.0)=" + stairHigh + "  低半边(只挡 0.5)=" + stairLow);
        if (!(stairHigh && stairLow)) fails++;

        // ---- 6. 矿车（第二十一批）：轨道滑行 / 脱轨停车 / **实体层不进窄哈希** ----
        // 这是 `World.updateCarts` 唯一的守护。它不抽 RNG、只读 mat + per-block 朝向 ⇒ 三条性质都能无头断言。
        // ⚠️ 驱动方式：**先禁用所有系统再 tick**（技能 21.12 的范式）—— 否则别的系统会改地形，
        //    "hashState 不变"这条断言就会被无关的写入搞红。
        int zE = zA + 20;
        for (int xx = fx; xx <= wx2; xx++)
            for (int yy = baseY; yy <= baseY + 3; yy++)
                w.setBlock(xx, yy, zE, Blocks.AIR.index);
        for (int xx = fx; xx <= fx + 6; xx++) {
            w.setBlock(xx, baseY - 1, zE, Blocks.STONE.index);
            w.setBlock(xx, baseY, zE, Blocks.RAIL.index);
            w.setFacing(xx, baseY, zE, 0);                  // 沿 +X 的轨道
        }
        core.world.Minecart cart = new core.world.Minecart(fx + 0.5f, baseY, zE + 0.5f);
        cart.vx = 0.2f;                                     // 相当于被推了一下
        w.carts.add(cart);
        java.util.List<String> sysNames = new java.util.ArrayList<String>(w.registry.activeNames());
        for (String n : sysNames) w.registry.disable(n);    // 冻住其它系统（只留矿车推进）
        // "实体层不进窄哈希"必须**同 tick 下**比较（有车 vs 无车）——
        // hashState() 的构成里**含 tick**，比较不同 tick 的哈希必然不等（本断言第一版就是这么写错的）。
        long hashWith = w.hashState();
        w.carts.clear();
        long hashWithout = w.hashState();
        w.carts.add(cart);
        boolean cartNoHash = hashWith == hashWithout;
        for (int i = 0; i < 30; i++) w.tick();
        boolean cartMoved = cart.x > fx + 1.0f && cart.vz == 0f;     // 沿 +X 滑出去，且横向分量被投影清零
        for (int xx = fx; xx <= fx + 6; xx++) w.setBlock(xx, baseY, zE, Blocks.AIR.index);   // 拆掉轨道
        for (int i = 0; i < 12; i++) w.tick();
        boolean cartStops = cart.atRest();                           // 脱轨 → 停下（不是"会飞的箱子"）
        System.out.println("CART-RUN  " + ((cartMoved && cartNoHash && cartStops) ? "PASS" : "FAIL")
                + "  x=" + cart.x + "(起点 " + (fx + 0.5f) + ") 滑行=" + cartMoved
                + " 窄哈希不变=" + cartNoHash + " 脱轨停=" + cartStops);
        if (!(cartMoved && cartNoHash && cartStops)) fails++;
        w.carts.clear();

        // ---- 7. 上下车（第二十二批）：位置跟随 + 前进键驱动 ----
        // 「载具」的全部就是这两条：坐下后**位置由车决定**、输入转成**车的推力**。
        // 门禁里就要把这两条都钉住 —— 只测"能坐下"会漏掉"坐上去还在自己跑物理"（表现为被重力拽下去）。
        for (int xx = fx; xx <= fx + 6; xx++) {
            w.setBlock(xx, baseY, zE, Blocks.RAIL.index);
            w.setFacing(xx, baseY, zE, 0);
        }
        core.world.Minecart rc = new core.world.Minecart(fx + 0.5f, baseY, zE + 0.5f);
        w.carts.add(rc);
        p.ridingCartIdx = 0;
        p.x = 0f; p.y = 0f; p.z = 0f;
        p.physicsTick(w, 0f, 0f, false, false, 1f / 60f);
        boolean rideFollow = Math.abs(p.x - rc.x) < 1e-4f && Math.abs(p.z - rc.z) < 1e-4f
                && p.y > rc.y;                                        // 位置被车接管（而不是被重力拽走）
        float v0 = rc.vx;
        for (int i = 0; i < 10; i++) p.physicsTick(w, 1f, 0f, false, false, 1f / 60f);
        boolean ridePush = rc.vx > v0;                                // 前进键 → 车被推动（沿轨道走向）
        boolean rideKeepsFollow = Math.abs(p.x - rc.x) < 1e-4f;       // 车动了，人也跟着动
        p.ridingCartIdx = -1;
        System.out.println("CART-RIDE " + ((rideFollow && ridePush && rideKeepsFollow) ? "PASS" : "FAIL")
                + "  位置跟随=" + rideFollow + " 前进键驱动=" + ridePush
                + " 车动人动=" + rideKeepsFollow + " (vx " + v0 + " -> " + rc.vx + ")");
        if (!(rideFollow && ridePush && rideKeepsFollow)) fails++;
        w.carts.clear();

        // ---- 8. 碰撞（第二十三批）：矿车**不得穿入实心方块** ----
        // 补的是一个**真缺口**：脱轨分支此前只衰减速度、位置照推进 ⇒ 矿车会滑进墙里。
        // 场景：一段**非轨道**地面（脱轨滑行）+ 前方一堵墙；满速冲过去必须停在墙前。
        // 初速取 5.0：脱轨摩擦 0.5 ⇒ 无碰撞时会滑过 5 格（越过墙），有碰撞则被目标格判据拦下。
        for (int xx = fx; xx <= fx + 8; xx++) {
            w.setBlock(xx, baseY - 1, zE, Blocks.STONE.index);   // 地面
            w.setBlock(xx, baseY, zE, Blocks.AIR.index);         // 该段**没有轨道**
        }
        int cartWallX = fx + 5;
        w.setBlock(cartWallX, baseY, zE, Blocks.STONE.index);    // 墙（在矿车所乘的那一层）
        core.world.Minecart wc = new core.world.Minecart(fx + 0.5f, baseY, zE + 0.5f);
        wc.vx = 5.0f;                                            // 全力冲向墙
        w.carts.add(wc);
        for (int i = 0; i < 12; i++) w.tick();
        boolean cartWall = wc.x < cartWallX && wc.atRest();      // 停在墙**前**（没穿进去）
        System.out.println("CART-WALL " + (cartWall ? "PASS" : "FAIL")
                + "  x=" + wc.x + " (墙在 " + cartWallX + ") 停=" + wc.atRest());
        if (!cartWall) fails++;
        w.carts.clear();

        // ---- 9. 载物（第二十三批）：容器挂在**实体**上 + 载重真接进运动 + 窄哈希不变 ----
        // 本批的架构点：方块的身份是坐标（chestStore 用 cellKey），而矿车的身份是**对象本身**
        // ⇒ 容器直接挂在实体上，没有、也不需要稳定坐标键。
        core.world.Minecart cargoCart = new core.world.Minecart(fx + 0.5f, baseY, zE + 0.5f);
        cargoCart.cargo.put("stone", Integer.valueOf(5));
        boolean cargoApi = cargoCart.hasCargo() && cargoCart.cargoCount() == 5;
        w.carts.add(cargoCart);
        long hLoad = w.hashState();
        cargoCart.cargo.clear();
        cargoCart.cargo.put("iron", Integer.valueOf(2));
        long hLoad2 = w.hashState();
        boolean cargoNoHash = hLoad == hLoad2;                   // 实体层（含其容器）整体不进窄哈希
        w.carts.clear();
        // 载重反馈：同初速、同 tick 数后，重车速度衰减更快（摩擦 0.94 vs 0.98）⇒ 载物**真的**接进了运动。
        for (int xx = fx; xx <= fx + 3; xx++) {
            w.setBlock(xx, baseY - 1, zE, Blocks.STONE.index);
            w.setBlock(xx, baseY, zE, Blocks.RAIL.index);
            w.setFacing(xx, baseY, zE, 0);
        }
        core.world.Minecart ec = new core.world.Minecart(fx + 0.5f, baseY, zE + 0.5f);
        ec.vx = 0.2f;                                            // 空车
        core.world.Minecart lc = new core.world.Minecart(fx + 0.5f, baseY, zE + 0.5f);
        lc.vx = 0.2f; lc.cargo.put("stone", Integer.valueOf(3)); // 载货车
        w.carts.add(ec); w.carts.add(lc);
        for (int i = 0; i < 2; i++) w.tick();
        boolean cargoHeavier = lc.vx < ec.vx && lc.vx > 0f;
        System.out.println("CART-CARGO " + ((cargoApi && cargoNoHash && cargoHeavier) ? "PASS" : "FAIL")
                + "  容器API=" + cargoApi + " 窄哈希不变=" + cargoNoHash
                + " 重车衰减更快=" + cargoHeavier + " (空 " + ec.vx + " / 载 " + lc.vx + ")");
        if (!(cargoApi && cargoNoHash && cargoHeavier)) fails++;
        w.carts.clear();

        for (String n : sysNames) w.registry.enable(n);              // 复原（后续断言不受影响）

        // ---- 6. 时间基（2026-09-16，2026-09-21 P0① 重写）----
        // 原性质（旧实现）：一帧 dt=0.2 s 的位移 ≈ 4.5×0.2 = 0.90（当时是瞬时满速）。
        // 新性质（引入加速度层后）：单帧 dt 更大**不等于**走的更远 —— 因为 0→WALK 有加速段，
        //   大 dt 里"加速斜坡"占的比例不可忽略。所以本条改成验**切片等价性**：
        //   ① 一帧 dt=0.2 s 的位移，必须与"4 个 0.05 s 子步"（physicsTick 内部就是这么切的）一致到 1e-4；
        //   ② 位移必须落在 [WALK·dt - 加速亏欠, WALK·dt] 区间 —— 即"不超过满速上界，且不是被夹死的旧行为"；
        //   ③ 小 dt 位移 ≈ WALK·dt（加速斜坡在单步内几乎走完）。
        World wt = new World(20260916L, 96, 48, 96);
        Player pt = new Player();
        int cz = 20, fy = 20, cx0 = 10, cx1 = 70;
        for (int xx = cx0; xx <= cx1; xx++) {
            for (int yy = fy; yy <= fy + 4; yy++) wt.setBlock(xx, yy, cz, Blocks.AIR.index);
            wt.setBlock(xx, fy - 1, cz, Blocks.STONE.index);
        }
        pt.x = cx0 + 0.5f; pt.y = fy; pt.z = cz + 0.5f;
        pt.onGround = true; pt.vy = 0f; pt.fallPeak = pt.y;
        float x0 = pt.x;
        pt.physicsTick(wt, 1f, 0f, false, false, 0.2f);          // 一帧 0.2 s
        float dxBig = pt.x - x0;
        // ② 上界 = 满速位移；下界留出加速亏欠（0→4.5 需 0.107 s，亏欠 ≈ 4.5*0.107/2 ≈ 0.24 格）
        boolean bigOk = dxBig <= 0.9f + 1e-4f && dxBig > 0.9f - 0.30f;
        // ① 切片等价性：另建一个玩家，用 4 个 0.05 s 子步走同样的 0.2 s，位移必须一致
        Player ptc = new Player();
        ptc.x = cx0 + 0.5f; ptc.y = fy; ptc.z = cz + 0.5f;
        ptc.onGround = true; ptc.vy = 0f; ptc.fallPeak = ptc.y;
        for (int i = 0; i < 4; i++) ptc.physicsTick(wt, 1f, 0f, false, false, 0.05f);
        boolean sliceOk = Math.abs((ptc.x - x0) - dxBig) < 1e-4f;
        // ③ 小 dt：加速斜坡在单步内基本走完，位移接近满速
        float x1 = pt.x;
        pt.physicsTick(wt, 1f, 0f, false, false, 1f / 60f);      // 一帧 1/60 s
        float dxSmall = pt.x - x1;
        boolean tbOk = bigOk && sliceOk && dxSmall > 0.05f && dxSmall < 0.09f;
        System.out.println("TIMEBASE " + (tbOk ? "PASS" : "FAIL")
                + "  dx@0.20s=" + dxBig + " (∈(0.60,0.90])  sliceEq=" + sliceOk
                + "  dx@1/60s=" + dxSmall + " (want ~0.075)");
        if (!tbOk) fails++;

        // ---- 7. P0① 移动手感：加速度 / 摩擦（2026-09-21 新增，为"运动手感"立牙）----
        // 性质（旧实现是瞬时满速，本条会全部 FAIL —— 即门禁有牙）：
        //   ① 起步不是瞬时满速：第 1 个 1/60 帧位移 < 满速位移的一半（有加速斜坡）；
        //   ② 达到满速 ≈ GROUND_ACC 决定的时间（0→4.5 需 4.5/42 ≈ 0.107 s ≈ 7 帧）；
        //   ③ 松手后有滑行（不是当帧归零）：总滑行 ∈ (0.2, 0.6) 格；
        //   ④ 空中控制 < 地面：同一 0.1 s 内，空中横向位移 < 地面横向位移。
        World wm = new World(20261021L, 96, 48, 96);
        int mz = 30, my = 20, mx0 = 20, mx1 = 90;
        for (int xx = mx0; xx <= mx1; xx++) {
            for (int yy = my; yy <= my + 6; yy++) wm.setBlock(xx, yy, mz, Blocks.AIR.index);
            wm.setBlock(xx, my - 1, mz, Blocks.STONE.index);
        }
        Player pm = new Player();
        pm.x = mx0 + 0.5f; pm.y = my; pm.z = mz + 0.5f;
        pm.onGround = true; pm.vy = 0f; pm.fallPeak = pm.y;
        float m0 = pm.x;
        pm.physicsTick(wm, 1f, 0f, false, false, 1f / 60f);
        float firstStep = pm.x - m0;
        boolean rampOk = firstStep > 0.001f && firstStep < 0.075f * 0.5f;   // 不是瞬时满速
        for (int i = 0; i < 30; i++) pm.physicsTick(wm, 1f, 0f, false, false, 1f / 60f);
        float cruiseStep = pm.x - (m0 + firstStep);                        // 后 30 帧总位移
        boolean cruiseOk = cruiseStep > 30f * 0.075f * 0.90f;              // 已近满速
        // 松手滑行
        float s0 = pm.x;
        for (int i = 0; i < 60; i++) pm.physicsTick(wm, 0f, 0f, false, false, 1f / 60f);
        float glideDist = pm.x - s0;
        boolean glideOk = glideDist > 0.20f && glideDist < 0.60f;          // 有滑行但不飘
        // 空中控制 < 地面：同一 0.1s，地面/空中各跑一次
        Player pg = new Player();
        pg.x = mx0 + 0.5f; pg.y = my; pg.z = mz + 0.5f; pg.onGround = true; pg.vy = 0f; pg.fallPeak = pg.y;
        float g0 = pg.x;
        for (int i = 0; i < 6; i++) pg.physicsTick(wm, 1f, 0f, false, false, 1f / 60f);
        float groundDx = pg.x - g0;
        Player pa = new Player();
        pa.x = mx0 + 0.5f; pa.y = my + 5f; pa.z = mz + 0.5f; pa.onGround = false; pa.vy = 0f; pa.fallPeak = pa.y;
        float a0 = pa.x;
        for (int i = 0; i < 6; i++) pa.physicsTick(wm, 1f, 0f, false, false, 1f / 60f);
        float airDx = pa.x - a0;
        boolean airOk = airDx > 0f && airDx < groundDx;
        boolean feelOk = rampOk && cruiseOk && glideOk && airOk;
        System.out.println("FEEL  " + (feelOk ? "PASS" : "FAIL")
                + "  firstStep=" + firstStep + " cruise30=" + cruiseStep + " glide=" + glideDist
                + " airDx=" + airDx + " < groundDx=" + groundDx);
        if (!feelOk) fails++;

        // ---- 8. P0① 起跳预输入缓冲（2026-09-21 新增）----
        // 性质：玩家在**落地前的缓冲窗口内**（120 ms）按一下跳，落地后不需要再按就能起跳。
        // 做法：从贴地高度起跳后的下落段很短，这里从 jy+0.45f 落下（≈0.18 s 落地），
        // 在落地前 3 帧按跳（此时仍在空中）→ 落地帧应兑现这一跳（vy>0 且离地）。
        World wj = new World(20261022L, 96, 48, 96);
        int jz = 30, jy = 20, jx0 = 20, jx1 = 60;
        for (int xx = jx0; xx <= jx1; xx++) {
            for (int yy = jy; yy <= jy + 4; yy++) wj.setBlock(xx, yy, jz, Blocks.AIR.index);
            wj.setBlock(xx, jy - 1, jz, Blocks.STONE.index);
        }
        Player pj = new Player();
        pj.x = jx0 + 0.5f; pj.z = jz + 0.5f; pj.y = jy + 0.45f; pj.onGround = false; pj.vy = 0f; pj.fallPeak = pj.y;
        // 先跑若干帧让它下落接近地面，检测"落地前 3 帧按跳 → 落地即起跳"
        boolean buffered = false, landedOnce = false;
        float lastVy = 0f;
        for (int i = 0; i < 120; i++) {
            // 距地面很近（<0.12 格）且仍在下落时按跳（模拟玩家提前一点按）
            boolean j = !pj.onGround && (pj.y - jy) < 0.12f && pj.vy < 0f;
            boolean wasAir = !pj.onGround;
            pj.physicsTick(wj, 0f, 0f, j, false, 1f / 60f);
            if (landedOnce && !pj.onGround && pj.vy > 0f) buffered = true;   // 落地后自动起跳
            if (wasAir && pj.onGround) landedOnce = true;
            lastVy = pj.vy;
        }
        System.out.println("JUMPBUF " + (buffered ? "PASS" : "FAIL") + "  bufferedJump=" + buffered + " lastVy=" + lastVy);
        if (!buffered) fails++;

        System.out.println(fails == 0 ? "PHYSICS PASS" : ("PHYSICS FAIL (" + fails + ")"));
        if (fails > 0) System.exit(1);
    }
}
