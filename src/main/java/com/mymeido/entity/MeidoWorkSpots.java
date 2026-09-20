package com.mymeido.entity;

import java.util.Optional;
import java.util.function.Predicate;

import com.mymeido.mode.MeidoModeType;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.CropBlock;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.World;

/**
 * 「玩家点的那一格 → 她真正该站的那一格」—— 钓鱼 / 种植共用的找位置逻辑。
 *
 * <h2>为什么必须有这一层</h2>
 *
 * <p>钓鱼模式一开始是「你右键水，她就往水上走」—— 于是她直接踩进水里。
 * 玩家想说的是「**去这片水边钓鱼**」，不是「站到水里」。
 * 种植同理：你右键耕地，她该站在<b>耕地旁边</b>，而不是踩在耕地上（踩上去收不了、看着也不对）。
 *
 * <p>所以派活时要把「你点的那一格」翻译成「她该站的那一格」：
 * <ul>
 *   <li>钓鱼：先在附近找水，再从水边上找一块<b>能站人的干地</b>；</li>
 *   <li>种植：先在附近找耕地（或已经种了东西的耕地），再找它旁边能站人的干地。</li>
 * </ul>
 *
 * <p>翻译结果会当成真正的位置存进 {@link MeidoMission}，
 * 所以「已派活：钓鱼 → x y z」报的就是她<b>最终会站的地方</b>，不是水面。
 *
 * <h2>为什么翻译要做两次（派发时 + 每次 tick）</h2>
 *
 * <p>派发时翻译是为了「当场告诉你成不成」——
 * 你指的地方周围没水 / 没耕地，就直说失败，别让她站着装样子。
 * tick 时再翻译一次是为了<b>自愈</b>：水被填了、耕地被挖了，
 * 她会重新找一块；实在找不到才认输、发消息、回游走。
 * 两处用的是同一段代码，所以「什么算能钓鱼的地方」只有一份定义。
 *
 * <h2>刻意宽松</h2>
 *
 * <p>「能不能站」只要求<b>固体 + 头顶是空气</b>，而且比原版宽松：
 * 不要求露天、不要求邻近水是「大片水面」。
 * 宁可让她在奇怪的地方也能钓上鱼，也不要出现「明明有水却钓不了」。
 */
public final class MeidoWorkSpots {

    /** 派发时，从你点的那一格向外找水 / 找耕地的半径。 */
    public static final int SEARCH_RADIUS = 8;

    /** 从找到的水 / 耕地向外找「能站人的位置」的半径。 */
    private static final int STAND_RADIUS = 3;

    /** 干活时（tick 里）允许在她脚下多远以内找水 / 找耕地。 */
    public static final int WORK_RADIUS = 4;

    private MeidoWorkSpots() {
    }

    // ------------------------------------------------------------------
    // 派发入口：按模式翻译
    // ------------------------------------------------------------------

    /**
     * 「玩家点的那一格」→「她该站的那一格」，按模式分派。
     *
     * <p>不需要位置（{@code NONE}）或者不需要翻译的模式原样返回 ——
     * 调用方因此不用自己写 if/else，也就不会漏掉某一个模式。
     *
     * @return 该站的位置；{@code empty} = 这个模式在这里没法干活（要让玩家当场知道）
     */
    public static Optional<BlockPos> resolveSpot(MeidoModeType type, World world, BlockPos seed) {
        if (type == MeidoModeType.FISH) {
            return resolveFishingSpot(world, seed);
        }
        if (type == MeidoModeType.FARM) {
            return resolveFarmSpot(world, seed);
        }
        if (type == MeidoModeType.COME) {
            return resolveComeSpot(world, seed);
        }
        if (type == MeidoModeType.GUARD) {
            // 守卫的「岗位」和「到这里来」一样要能站人：点实心方块 → 站到它上面。
            return resolveComeSpot(world, seed);
        }
        return Optional.of(seed);
    }

    /**
     * 翻译不出来时给玩家看的话。
     *
     * <p>写成「差在哪 + 怎么补」，不要只说「失败了」——
     * 玩家右键一下什么都没发生、只收到三个字，是没法自己 debug 的。
     */
    public static String failureHint(MeidoModeType type, String modeName) {
        if (type == MeidoModeType.FISH) {
            return "派活失败：「" + modeName + "」附近 " + SEARCH_RADIUS
                    + " 格内没有能下钩的水面（点水里、点岸边都认，但得挨着水）";
        }
        if (type == MeidoModeType.FARM) {
            return "派活失败：「" + modeName + "」附近 " + SEARCH_RADIUS
                    + " 格内没有能种的耕地（要先用锄头翻地，而且得有光照 —— 暗处种下去会自己消失）";
        }
        return "派活失败：「" + modeName + "」这里站不住人";
    }

    // ------------------------------------------------------------------
    // 通用：够不够「站」 / 是不是水
    // ------------------------------------------------------------------

    public static boolean isWater(World world, BlockPos pos) {
        return world.getBlockState(pos).getFluidState().isIn(FluidTags.WATER);
    }

    /**
     * 这一格能不能站人。
     *
     * <p>只看两条：<b>脚下是固体</b>、<b>头顶是空气</b>。
     * 不问是不是露天、不问周围有什么 —— 判断越简单，出假否定的机会越小。
     */
    public static boolean isStandable(World world, BlockPos pos) {
        BlockState state = world.getBlockState(pos);
        if (state.getFluidState().isIn(FluidTags.WATER)) {
            return false;
        }
        if (!state.isSolidBlock(world, pos)) {
            return false;
        }
        return world.getBlockState(pos.up()).isAir();
    }

    // ------------------------------------------------------------------
    // 钓鱼
    // ------------------------------------------------------------------

    /**
     * 把「你点的那一格」翻译成「她该站的岸边」。
     *
     * <p>你点水里 / 点岸上都可以：先在附近找水，再从水边上挑一块能站的干地，
     * 挑的是<b>离你点的那一格最近</b>的一块 —— 于是「你在北岸点」她就站北岸，
     * 不会绕到对岸去。
     *
     * @return 该站的位置；附近没有水、或者水边一圈都站不了人，就返回 empty
     */
    public static Optional<BlockPos> resolveFishingSpot(World world, BlockPos seed) {
        return resolveFishingSpot(world, seed, SEARCH_RADIUS);
    }

    /** 同上，但由调用方指定找水的半径（tick 里自愈时用更小的值，免得她跑去别的池塘）。 */
    public static Optional<BlockPos> resolveFishingSpot(World world, BlockPos seed, int searchRadius) {
        Optional<BlockPos> water = isWater(world, seed)
                ? Optional.of(seed)
                : nearest(world, seed, searchRadius, pos -> isWater(world, pos));
        if (water.isEmpty()) {
            return Optional.empty();
        }
        // 候选范围以「水」为中心，但按「离玩家点的那一格多近」排序。
        return nearestAround(world, water.get(), STAND_RADIUS, seed, pos -> isStandable(world, pos)
                && touchesWater(world, pos));
    }

    /** 站在 stand 上，身边（同一层或脚下一层）挨着水吗。 */
    private static boolean touchesWater(World world, BlockPos stand) {
        BlockPos feet = stand.down();
        for (Direction dir : Direction.Type.HORIZONTAL) {
            if (isWater(world, stand.offset(dir)) || isWater(world, feet.offset(dir))) {
                return true;
            }
        }
        return false;
    }

    /** 她脚下附近有没有水（干活时判断「这水还在不在」）。 */
    public static Optional<BlockPos> findWaterNear(World world, BlockPos center, int radius) {
        return nearest(world, center, radius, pos -> isWater(world, pos));
    }

    // ------------------------------------------------------------------
    // 种植
    // ------------------------------------------------------------------

    /**
     * 这一格的光照够不够让作物活下来。
     *
     * <h2>为什么必须有这一条（2026-09-20 血泪）</h2>
     *
     * <p>原版 {@code CropBlock.canPlaceAt} 是
     * <b>{@code hasEnoughLightAt(world, pos) && PlantBlock.canPlaceAt(...)}</b>，
     * 而 {@code hasEnoughLightAt} 经 javap 核实就是一勺：
     * <pre>
     * return world.getBaseLightLevel(pos, 0) &gt;= 8;
     * </pre>
     *
     * <p>我们是用 {@code setBlockState} 直接塞的，<b>绕过了这个检查</b>，
     * 于是能在黑地方「种下」小麦 —— 但那种小麦活不过一次邻格更新：
     * 只要旁边再种一格，{@code PlantBlock.getStateForNeighborUpdate}
     * 就会重新问一遍 {@code canPlaceAt}，答「不够亮」，作物当场变成空气。
     * 表现极具欺骗性：<b>她一直在种，地里却永远什么都没有</b>
     * （我为此查了六轮冒烟测试才定位到）。
     *
     * <p>所以「能不能种」必须把光照算进去。这也和玩家一致 ——
     * 玩家在暗处右键耕地是<b>根本种不下去</b>的。
     */
    public static boolean hasEnoughLight(World world, BlockPos pos) {
        return world.getBaseLightLevel(pos, 0) >= 8;
    }

    /**
     * 这一格算不算「一块地」。
     *
     * <p>三种：<b>空着的、且能种的耕地</b>（头顶是空气 + 光照够 → 种小麦）、
     * <b>空着的灵魂沙</b>（头顶是空气 → 种地狱疣，**原版不要求光照**，javap 过
     * {@code NetherWartBlock.canPlaceAt}：只查下面是不是灵魂沙）、
     * <b>已经种了东西的地</b>（要去看看熟没熟）。这样她既能开荒也能收成。
     *
     * <p>小麦那半必须带光照条件：不带的话，暗处的耕地也会被认成「有活可干」，
     * 她会站过去一直种、一直看着作物消失，而失败提示永远不会出现。
     * 收成那半<b>不需要</b>光照 —— 已经长在那儿的作物，熟了就收。
     */
    public static boolean isFarmPlot(World world, BlockPos pos) {
        BlockState state = world.getBlockState(pos);
        if (state.isOf(Blocks.FARMLAND) && world.getBlockState(pos.up()).isAir()) {
            return hasEnoughLight(world, pos.up());
        }
        if (state.isOf(Blocks.SOUL_SAND) && world.getBlockState(pos.up()).isAir()) {
            return true;   // 地狱疣：原版 canPlaceAt 不查光照（地下/主世界都一样种）
        }
        return state.getBlock() instanceof CropBlock && world.getBlockState(pos.down()).isOf(Blocks.FARMLAND)
                || state.isOf(Blocks.NETHER_WART) && world.getBlockState(pos.down()).isOf(Blocks.SOUL_SAND);
    }

    public static Optional<BlockPos> findFarmPlotNear(World world, BlockPos center, int radius) {
        return nearest(world, center, radius, pos -> isFarmPlot(world, pos));
    }

    /**
     * 附近有没有<b>长着的作物（任意生长期）</b>：小麦或地狱疣。
     *
     * <p>用途：种子是种植的硬前提，但「包里没种子」不等于「永远没活可干」——
     * 地里还有长着的作物的话，熟了就能收、收了会掉「种子」，循环还能续上。
     * 所以「没种子要不要收工」得看这一格的答案（见 {@code MeidoEntity#tickFarmMission}）。
     */
    public static boolean hasCropNear(World world, BlockPos center, int radius) {
        for (int dy = -1; dy <= 1; dy++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    BlockState state = world.getBlockState(center.add(dx, dy, dz));
                    if (state.isOf(Blocks.WHEAT) || state.isOf(Blocks.NETHER_WART)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * 把「你点的那一格」翻译成「她该站的耕地旁边」。
     *
     * <p>找不到耕地就返回 empty —— <b>这里刻意不帮她翻地</b>：
     * 「先用锄头把土变成耕地」是玩家的活，她只负责种和收。
     * 所以失败提示会明说「附近没有耕地」，而不是含糊的「失败了」。
     */
    public static Optional<BlockPos> resolveFarmSpot(World world, BlockPos seed) {
        Optional<BlockPos> plot = isFarmPlot(world, seed)
                ? Optional.of(seed)
                : findFarmPlotNear(world, seed, SEARCH_RADIUS);
        if (plot.isEmpty()) {
            return Optional.empty();
        }
        // ★ 候选人里要排掉「地本身」：耕地是固体、头顶也有空气，按 isStandable 是能站的，
        //   但她要是站在耕地上，收成的时候得从自己脚下挖 —— 看着就不对。
        //
        // ★★ 搜索半径必须是 SEARCH_RADIUS 而不是 STAND_RADIUS（2026-09-20 smoke11 实测翻车）：
        //   玩家点在 9×9 农田的正中心时，以田中心为圆心、±3 格的立方里同一层
        //   「全部是耕地」（全被 !isFarmPlot 排掉），上下的空气站不了人、下面的石头上面还是石头
        //   —— 半径 3 根本走不出田边，永远返回 empty。半径给到 8 就能走到田埂上，
        //   而排序仍按「离你点的那格最近」，她只是多走两步，站的地方性质不变。
        return nearestAround(world, plot.get(), SEARCH_RADIUS, seed,
                pos -> isStandable(world, pos) && !isFarmPlot(world, pos));
    }

    // ------------------------------------------------------------------
    // 到这里来
    // ------------------------------------------------------------------

    /**
     * 「到这里来」：把她叫到你右键的那格上站着。
     *
     * <p>闹钟右键方块拿到的是<b>被点中的那个方块</b>本身 —— 而它多半是实心的，
     * 直接把她派进石头里她只能卡在旁边。所以这里做一层小翻译：
     * <ol>
     *   <li>点的这格本身能站 → 就这格（指令路径直接给可站坐标时走这条）；</li>
     *   <li>不然看<b>它的正上方</b>能站吗 → 点方块顶面就是「站到它上面」（最常见）；</li>
     *   <li>还不行就在附近（±2 格）找能站的地方 —— 点侧面/点柱子中段时兜底；</li>
     *   <li>全都站不了才认输，让玩家当场知道。</li>
     * </ol>
     */
    public static Optional<BlockPos> resolveComeSpot(World world, BlockPos seed) {
        if (isStandable(world, seed)) {
            return Optional.of(seed);
        }
        if (isStandable(world, seed.up())) {
            return Optional.of(seed.up());
        }
        return nearestAround(world, seed, 2, seed, pos -> isStandable(world, pos));
    }

    // ------------------------------------------------------------------
    // 枚举小工具
    // ------------------------------------------------------------------

    /** 以 center 为中心、半径 radius 的立方体里，离 center 最近的匹配位置。 */
    private static Optional<BlockPos> nearest(World world, BlockPos center, int radius, Predicate<BlockPos> match) {
        return nearestAround(world, center, radius, center, match);
    }

    /**
     * 以 around 为中心搜候选，但按<b>离 rank 的远近</b>挑最好的那个。
     *
     * <p>只有「在水边挑落脚点」这种场景需要两个中心：候选范围跟着水走（不然会挑到
     * 隔了一座山的干地），而挑哪一个跟着玩家点的地方走（不然会挑到对岸）。
     *
     * <p>遍历顺序是固定的（dy → dx → dz），加上「严格小于才换」的比较，
     * 所以同距离时结果稳定 —— 服务端每次算出来都是同一个位置，
     * 不会出现「同一个任务每次 tick 换个落脚点、她在原地来回晃」。
     */
    private static Optional<BlockPos> nearestAround(World world, BlockPos around, int radius, BlockPos rank,
                                                    Predicate<BlockPos> match) {
        BlockPos best = null;
        long bestSquared = Long.MAX_VALUE;
        for (int dy = -radius; dy <= radius; dy++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    BlockPos pos = around.add(dx, dy, dz);
                    if (!match.test(pos)) {
                        continue;
                    }
                    long ox = (long) pos.getX() - rank.getX();
                    long oy = (long) pos.getY() - rank.getY();
                    long oz = (long) pos.getZ() - rank.getZ();
                    long squared = ox * ox + oy * oy + oz * oz;
                    if (squared < bestSquared) {
                        bestSquared = squared;
                        best = pos;
                    }
                }
            }
        }
        return Optional.ofNullable(best);
    }
}
