package com.mymeido.item;

import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

import com.mymeido.MyMeido;
import com.mymeido.entity.MeidoEntity;
import com.mymeido.mode.MeidoModeDef;
import com.mymeido.mode.MeidoModeRegistry;
import com.mymeido.mode.MeidoModeType;

import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.ItemUsageContext;
import net.minecraft.item.tooltip.TooltipType;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.TypedActionResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.world.World;

/**
 * 指令闹钟 —— <b>给女仆派活的入口</b>（设计稿 A4-2）。
 *
 * <h2>为什么叫「闹钟」</h2>
 *
 * <p>因为她不该被「打开一个菜单」指挥，而该被<b>叫</b>。
 * 你手上这个东西就是用来叫她的：先设好「要她干什么」，再冲着她该去的地方按一下。
 *
 * <h2>两条操作，一条规则</h2>
 *
 * <pre>
 *   右键空气      → 打开模式选择界面（客户端），选一个模式
 *   右键方块      → 把选中的模式派给她，方块位置就是任务目标点
 *   潜行 + 右键方块 → 同上，但<b>优先抢到这次点击</b>（见下）
 * </pre>
 *
 * <h2>它是<b>自动到手</b>的，不用敲指令</h2>
 *
 * <p>你召出第一只女仆的那一刻，她「顺手把闹钟塞给你」——
 * 因为「召了她之后不知道该干什么」是这个玩法最容易卡住的地方。
 * 身上已经带着一个的时候不会再给第二个（<b>否则每召一只就多一个，背包很快就满了</b>）。
 * 掉了 / 送人了想要回来，才需要 {@code /mymeido alarm}。
 *
 * <h2>为什么需要「潜行 + 右键」这一条</h2>
 *
 * <p>这个不是我们加的规矩，是原版就有的：右键方块时<b>方块自己有优先权</b>
 * （箱子会打开、床会睡觉）。javap 看 {@code ClientPlayerInteractionManager.interactBlockInternal}
 * 的字节码，顺序是「方块 {@code onUseWithItem} → 方块 {@code onUse} → 才轮到物品 {@code useOnBlock}」，
 * 而且只在 {@code shouldCancelInteraction()}（也就是潜行）成立时才会<b>跳过前两步</b>。
 *
 * <p>所以：绑家要右键床 → 必须潜行右键，否则你只会躺上去睡觉。
 * 普通方块（泥土、石头、水面）不用潜行，直接右键就行。
 *
 * <h2>★「选中模式」和「她现在的模式」是两份数据</h2>
 *
 * <p>这是本类最容易看错的一点，务必分清：
 * <ul>
 *   <li><b>选中模式</b>（存在 {@link MeidoItems#ALARM_MODE} 组件里）—— 你挑好的单子。
 *       它跟着这块闹钟走，只有「你重新挑一个」才会变。派活时用的就是它。</li>
 *   <li><b>她现在的模式</b> —— 女仆实体身上的真实状态，每 tick 同步给客户端。</li>
 * </ul>
 *
 * <p>一次性任务（丢弃物品 / 绑定家）干完之后，她自己会回到游走 ——
 * 这时候如果只改她那一份，闹钟上就会一直写着「丢弃物品」，
 * 玩家看到的就是「界面在骗我」（2026-09-20 实测踩到）。
 * 所以收尾必须两边一起改：{@code MeidoEntity.finishOneShotMission()} 负责改她那边的同时，
 * 通过 {@link #resetSelection} 把派活人闹钟上的选择也切回游走。
 *
 * <p>提示文本因此<b>两行都写</b>：上面是「选中模式」，下面是「她现在」。
 *
 * <h2>主源集不能引用客户端类</h2>
 *
 * <p>「打开界面」是客户端专属动作，而本类在主源集（专用服务器也要编译）。
 * 所以这里不 new Screen，而是留一个 {@link #setPicker(Runnable)} 钩子，
 * 由客户端入口在初始化时把真正的「打开界面」动作填进来。
 * 「她现在在干什么」同理，走 {@link #setNearbyStateReader}。
 * 这样主源集里一行 {@code net.minecraft.client.*} 都不会出现。
 */
public class CommandAlarmItem extends Item {

    /**
     * 派活时找女仆的半径（格）。
     *
     * <p>2026-09-20 由 16 提到 <b>64</b>：她本来就是「走到地方再干活」的，
     * 16 格在自家院子够用，一旦让她去河边 / 去远处的地就点不到人，
     * 闹钟只会回一句「附近没有女仆可以派活」，很反直觉。
     *
     * <p>配套改动：{@code MeidoEntity#GENERIC_FOLLOW_RANGE} 也要跟着放大
     * （原版寻路 A* 的搜索半径跟这个属性挂钩，只放大派活半径而不放大它，
     * 就会出现「派得上活但她走一半就停住不动」——很隐蔽）。
     */
    public static final double DISPATCH_RANGE = 64.0;

    /**
     * 绑定闹钟找「她本人」的半径（格）。
     *
     * <p>比派活半径（{@value #DISPATCH_RANGE}）宽得多：绑定之后找的是<b>特定的一位</b>，
     * 不该因为「她刚好在 70 格外种地」就报找不到。但也<b>不能</b>全世界无限扫 ——
     * 找不到就该找不到，让规则保持「绑了就是她，没有别人」。
     */
    public static final double BOUND_RANGE = 256.0;


    /**
     * 「打开模式选择界面」这个动作。默认什么都不做 ——
     * 专用服务器上没人会去点它，客户端入口会把它换成真的。
     */
    private static Runnable picker = () -> {
    };

    /** 客户端入口调用，把真正的「开界面」装进来。 */
    public static void setPicker(Runnable opener) {
        picker = opener == null ? () -> {
        } : opener;
    }

    /**
     * 「就近那只女仆现在在干什么」——返回模式 <b>id</b>，空表示附近没有女仆。
     *
     * <p>默认返回空：专用服务器上没有「就近的女仆」这个概念，也没人会来看提示。
     * 客户端入口会把它换成真的实现（读实体同步过来的模式 id）。
     *
     * <p>返回 id 而不是显示名，是因为比较要在 id 上做 ——
     * 配置里改个名字不该让「她现在的模式 == 我选的模式」这个判断失效。
     */
    private static Supplier<Optional<String>> nearbyState = Optional::empty;

    /** 客户端入口调用，把真正的「读她现在的模式」装进来。 */
    public static void setNearbyStateReader(Supplier<Optional<String>> reader) {
        nearbyState = reader == null ? Optional::empty : reader;
    }

    public CommandAlarmItem(Settings settings) {
        super(settings);
    }

    // ------------------------------------------------------------------
    // 右键空气 = 挑单子
    // ------------------------------------------------------------------

    @Override
    public TypedActionResult<ItemStack> use(World world, PlayerEntity player, Hand hand) {
        ItemStack stack = player.getStackInHand(hand);
        if (world.isClient()) {
            // 界面是纯客户端的东西：只在这里开。
            picker.run();
            return TypedActionResult.success(stack, true);
        }
        // 服务端什么都不做 —— 真正改模式的是玩家在界面里选完之后发来的那个包。
        // 返回 SUCCESS 只是告诉原版「这一下用掉了」，免得又去触发别的默认行为。
        return TypedActionResult.success(stack, false);
    }

    // ------------------------------------------------------------------
    // 右键方块 = 下单
    // ------------------------------------------------------------------

    @Override
    public ActionResult useOnBlock(ItemUsageContext context) {
        PlayerEntity player = context.getPlayer();
        if (player == null) {
            return ActionResult.PASS;
        }
        if (context.getWorld().isClient()) {
            // ★ 客户端必须也返回 SUCCESS：返回 PASS 的话原版会接着走「用物品」那一支，
            //   于是同一个右键既派了活、又把模式选择界面弹出来。
            return ActionResult.SUCCESS;
        }

        ItemStack stack = context.getStack();
        MeidoModeDef def = selectedMode(stack).orElse(null);
        if (def == null) {
            // 闹钟上的模式 id 在服务端配置里不存在（多半是两边 modes.json 不一致）。
            warn(player, "闹钟上的模式「" + rawSelectedId(stack) + "」服务端不认识，请重新选一次");
            return ActionResult.FAIL;
        }

        // ★ 绑了就是她 —— 找不到要明确报错，绝不悄悄改派给别的女仆（「避免串了」的关键）。
        Optional<String> failure = boundFailure(player);
        if (failure.isPresent()) {
            warn(player, failure.get());
            return ActionResult.FAIL;
        }

        MeidoEntity meido = targetOf(player);
        if (meido == null) {
            warn(player, "附近 " + (int) DISPATCH_RANGE + " 格内没有女仆可以派活");
            return ActionResult.FAIL;
        }

        BlockPos pos = context.getBlockPos();
        // ★ 把「谁派的」一起传下去：一次性任务干完后要把他闹钟上的选择也切回游走。
        // ★ 返回的 spot 是她「最终会站的地方」—— 钓鱼时是岸边，不是你会的那片水，
        //   所以下面那句提示报的坐标才是真能去找她的地方。
        MeidoEntity.Assignment result = meido.assignMode(def.id(), pos, player);
        if (!result.isOk()) {
            warn(player, result.failure());
            return ActionResult.FAIL;
        }

        MeidoModeDef accepted = result.def();
        String where = accepted.usesTarget() && result.spot() != null ? " → " + fmt(result.spot()) : "";
        warn(player, "已派活：" + accepted.name() + where);
        if (!accepted.isImplemented()) {
            // 诚实一点：这一批只做了「派发」，行为留第三期。
            warn(player, "（「" + accepted.name() + "」的实际行为还在做，"
                    + "她暂时只会走到那个位置站着）");
        }
        return ActionResult.SUCCESS;
    }

    // ------------------------------------------------------------------
    // 提示文本
    // ------------------------------------------------------------------

    @Override
    public void appendTooltip(ItemStack stack, TooltipContext context, List<Text> tooltip, TooltipType type) {
        super.appendTooltip(stack, context, tooltip, type);

        // ---- 第零段：这块闹钟是谁的 ----
        // 摆在最前面，因为「我手上这块是给谁的」是拿错闹钟时最先要确认的事。
        java.util.UUID bound = boundMaidId(stack).orElse(null);
        if (bound == null) {
            tooltip.add(Text.literal("未绑定：对最近的女仆有效").formatted(Formatting.DARK_GRAY));
        } else {
            String label = boundMaidName(stack);
            tooltip.add(Text.literal("绑定：" + label).formatted(Formatting.GOLD));
        }

        // ---- 第一段：你挑好的单子（派活时用哪个） ----
        MeidoModeDef def = selectedMode(stack).orElse(null);
        if (def == null) {
            tooltip.add(Text.literal("选中模式：未选择").formatted(Formatting.GRAY));
            tooltip.add(Text.literal("右键空气挑一个模式").formatted(Formatting.DARK_GRAY));
        } else {
            tooltip.add(Text.literal("选中模式：" + def.name()).formatted(Formatting.AQUA));
            tooltip.add(Text.literal("  " + def.hint()).formatted(Formatting.GRAY));
            if (!def.isImplemented()) {
                tooltip.add(Text.literal("  （行为留第三期）").formatted(Formatting.DARK_GRAY));
            }
        }

        // ---- 第二段：她此刻的真实状态（实时同步来的，和上面那份无关） ----
        Optional<String> nowId = nearbyState.get();
        if (nowId.isEmpty()) {
            tooltip.add(Text.literal(bound == null ? "附近没有女仆" : label0(boundMaidName(stack)) + " 不在附近")
                    .formatted(Formatting.DARK_GRAY));
            return;
        }
        String id = nowId.get();
        tooltip.add(Text.literal("她现在："
                + MeidoModeRegistry.byId(id).map(MeidoModeDef::name).orElse(id)).formatted(Formatting.GREEN));
        if (def != null && !def.id().equalsIgnoreCase(id)) {
            // 两条不一致时的两种正常解释，都写出来 —— 别让玩家自己去猜是哪个。
            tooltip.add(Text.literal("  （还没派给她，或者上一件活已经干完）").formatted(Formatting.DARK_GRAY));
        }
    }

    private static String label0(String raw) {
        return raw == null || raw.isBlank() ? "绑定的女仆" : raw;
    }

    // ------------------------------------------------------------------
    // 组件读写
    // ------------------------------------------------------------------

    /** 闹钟上的模式 id。<b>没设过 = 用默认模式</b>，所以刚拿到手的闹钟立刻就能用。 */
    public static String rawSelectedId(ItemStack stack) {
        String id = stack.get(MeidoItems.ALARM_MODE);
        return id == null || id.isBlank() ? MeidoModeRegistry.DEFAULT_MODE_ID : id;
    }

    /**
     * 选中的模式条目。
     *
     * <p>id 在配置里找不到时返回 empty —— <b>不回落</b>：
     * 「你选的那个模式没了」和「你选的是游走」是两件事，静默切换会让人一头雾水。
     */
    public static Optional<MeidoModeDef> selectedMode(ItemStack stack) {
        return MeidoModeRegistry.byId(rawSelectedId(stack));
    }

    /** 有没有闹钟的样子。 */
    public static boolean isAlarm(ItemStack stack) {
        return stack.getItem() instanceof CommandAlarmItem;
    }

    // ------------------------------------------------------------------
    // 找闹钟 / 发闹钟 / 收闹钟
    // ------------------------------------------------------------------

    /**
     * 找出玩家身上的那个闹钟，没有就返回 {@code null}。
     *
     * <p>顺序是<b>先两手、再背包</b>，因为「界面开着的时候顺手换了个手 / 把闹钟塞回背包」
     * 是很自然的动作，不该因此丢掉这次选择。
     *
     * <p>返回的是<b>背包里那个 ItemStack 本身</b>（原版返回的就是活引用，不是副本），
     * 所以拿到之后可以就地 {@code set} 组件改它。
     *
     * <p>客户端和服务端<b>都</b>调这个方法 —— 两边各改自己那一份，
     * 「说的肯定是同一个闹钟」这件事因此不靠约定，靠同一段代码。
     */
    public static ItemStack findAlarm(PlayerEntity player) {
        ItemStack main = player.getMainHandStack();
        if (isAlarm(main)) {
            return main;
        }
        ItemStack off = player.getOffHandStack();
        if (isAlarm(off)) {
            return off;
        }
        PlayerInventory inventory = player.getInventory();
        for (int slot = 0; slot < inventory.size(); slot++) {
            ItemStack stack = inventory.getStack(slot);
            if (isAlarm(stack)) {
                return stack;
            }
        }
        return null;
    }

    /** 他身上已经有闹钟了吗。 */
    public static boolean hasAlarm(PlayerEntity player) {
        return findAlarm(player) != null;
    }

    /**
     * 发一个指令闹钟。
     *
     * <p><b>只在服务端调</b>（调用方都在服务端路径上）。
     *
     * <p>背包真满了的话<b>掉在他脚边，而不是凭空消失</b>——
     * {@code giveItemStack} 内部就是 {@code inventory.insertStack(stack)}，
     * 塞不进去的余量会<b>留在传进去的那个 stack 里</b>，
     * 所以这里掉的正好是没塞进去的那一份，不会多也不会少。
     *
     * @return true = 已经进他背包了；false = 背包满，掉在脚边
     */
    public static boolean grant(PlayerEntity player) {
        ItemStack stack = new ItemStack(MeidoItems.COMMAND_ALARM);
        if (player.giveItemStack(stack)) {
            return true;
        }
        if (!stack.isEmpty()) {
            player.dropItem(stack, false);
        }
        return false;
    }

    /**
     * 把闹钟上的「选中模式」切回默认（游走）。
     *
     * <p>一次性任务（丢弃物品 / 绑定家）干完时由服务端调一次。不这么做的话，
     * 闹钟会一直写着「丢弃物品」，而她早就走开了 —— 这就是「界面在骗我」。
     *
     * <p><b>只在服务端调</b>，而且改完必须 {@code syncState()}：
     * 服务端直接 set 组件不会自动同步到客户端（原版只在「通过界面搬东西」时发槽位包），
     * 少这一下，玩家低头看到的还是旧模式 —— 这个坑本批已经踩过一次。
     *
     * @return true = 真的改了；false = 没闹钟 / 本来就是默认模式（那就没必要发提示刷屏）
     */
    public static boolean resetSelection(PlayerEntity player) {
        ItemStack alarm = findAlarm(player);
        if (alarm == null) {
            return false;
        }
        if (MeidoModeRegistry.DEFAULT_MODE_ID.equals(rawSelectedId(alarm))) {
            return false;
        }
        alarm.set(MeidoItems.ALARM_MODE, MeidoModeRegistry.DEFAULT_MODE_ID);
        player.currentScreenHandler.syncState();
        return true;
    }

    // ------------------------------------------------------------------
    // 找女仆
    // ------------------------------------------------------------------

    /** 找玩家附近最近的一只女仆（<b>只用于没绑定的闹钟</b>）。 */
    public static MeidoEntity findNearest(PlayerEntity player) {
        Box box = player.getBoundingBox().expand(DISPATCH_RANGE);
        MeidoEntity best = null;
        double bestSquared = Double.MAX_VALUE;
        for (MeidoEntity meido : player.getWorld().getEntitiesByClass(MeidoEntity.class, box, e -> e.isAlive())) {
            double d = player.squaredDistanceTo(meido);
            if (d < bestSquared) {
                bestSquared = d;
                best = meido;
            }
        }
        return best;
    }

    /**
     * 按 UUID 找那位女仆（已绑定闹钟用）。
     *
     * <p>★ 用范围盒而不是「全世界扫」：她跑到远处 / 区块卸载时<b>找不到就是找不到</b>，
     * 由调用方明确报错 —— 这正是绑定想要的行为。悄悄回落到「最近的那位」
     * 会让「串了」这个问题重新长回来，而且更难查。
     *
     * <p>客户端也用这个方法（{@code World.getEntitiesByClass} 两端都有），
     * 所以闹钟提示里那句「她现在」对绑定闹钟也能显示对的人。
     */
    public static MeidoEntity findMaidById(PlayerEntity player, java.util.UUID id) {
        if (id == null) {
            return null;
        }
        Box box = player.getBoundingBox().expand(BOUND_RANGE);
        for (MeidoEntity meido : player.getWorld().getEntitiesByClass(MeidoEntity.class, box,
                e -> e.isAlive() && id.equals(e.getUuid()))) {
            return meido;
        }
        return null;
    }

    /**
     * 这块闹钟<b>对谁</b>有效 —— 派活、提示、界面全走这一个入口。
     *
     * <p>规则（2026-09-21 绯色定稿「避免串了」）：
     * <ul>
     *   <li><b>绑了</b> → 只认她。她在范围内就返回她；不在了 / 走太远 → {@code null}
     *       （调用方据此报错，<b>绝不改派给别人</b>）；</li>
     *   <li><b>没绑</b> → 老行为，最近的那位（{@code /mymeido alarm} 发的、或老存档里的闹钟）。</li>
     * </ul>
     */
    public static MeidoEntity targetOf(PlayerEntity player) {
        ItemStack alarm = findAlarm(player);
        java.util.UUID bound = alarm == null ? null : boundMaidId(alarm).orElse(null);
        if (bound == null) {
            return findNearest(player);
        }
        return findMaidById(player, bound);
    }

    /** 绑了人却找不到她时，给玩家一句说清「是谁、为什么没找到」。没绑则返回空。 */
    public static Optional<String> boundFailure(PlayerEntity player) {
        ItemStack alarm = findAlarm(player);
        if (alarm == null) {
            return Optional.empty();
        }
        java.util.UUID bound = boundMaidId(alarm).orElse(null);
        if (bound == null || findMaidById(player, bound) != null) {
            return Optional.empty();
        }
        return Optional.of("这块闹钟是「" + label0(boundMaidName(alarm)) + "」的，但她在 "
                + (int) BOUND_RANGE + " 格内找不到（走远了 / 区块没加载 / 已经不在了）");
    }

    // ------------------------------------------------------------------
    // 绑定
    // ------------------------------------------------------------------

    /** 把闹钟绑给某位女仆。只在服务端调（发新闹钟那一刻顺手绑）。 */
    public static void bindTo(ItemStack stack, MeidoEntity meido) {
        if (stack == null || meido == null) {
            return;
        }
        stack.set(MeidoItems.ALARM_MAID, meido.getUuid().toString());
        stack.set(MeidoItems.ALARM_MAID_NAME, meido.characterName());
    }

    /** 闹钟绑的 UUID；没绑 / 数据坏了都返回空。 */
    public static Optional<java.util.UUID> boundMaidId(ItemStack stack) {
        String raw = stack.get(MeidoItems.ALARM_MAID);
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(java.util.UUID.fromString(raw));
        } catch (IllegalArgumentException e) {
            // 存档里是坏数据：当没绑定处理，总比让派活整条链挂掉好。
            return Optional.empty();
        }
    }

    /** 绑定时记下的名字（她不在附近时提示里要用）。 */
    public static String boundMaidName(ItemStack stack) {
        String raw = stack.get(MeidoItems.ALARM_MAID_NAME);
        return raw == null ? "" : raw;
    }

    /**
     * 给玩家发一块<b>绑给指定女仆</b>的闹钟 —— 造女仆时的标准动作。
     *
     * <p>★ 不再有「身上已经有一块就不给」那一套：一块闹钟只管一位女仆，
     * 造第二位就必须再来一块，否则新造出来的她根本没法指挥。
     * 也就是说「背包被闹钟占格」是这个设计的固有代价，换来的是<b>不会派错人</b>。
     */
    public static boolean grantFor(PlayerEntity player, MeidoEntity meido) {
        ItemStack stack = new ItemStack(MeidoItems.COMMAND_ALARM);
        bindTo(stack, meido);
        if (player.giveItemStack(stack)) {
            return true;
        }
        if (!stack.isEmpty()) {
            player.dropItem(stack, false);
        }
        return false;
    }

    private static String fmt(BlockPos pos) {
        return pos.getX() + " " + pos.getY() + " " + pos.getZ();
    }

    private static void warn(PlayerEntity player, String message) {
        // true = 走 action bar（物品栏上方那行），不刷屏聊天框。
        player.sendMessage(Text.literal("[mymeido] " + message), true);
        MyMeido.LOGGER.debug("[mymeido] 闹钟派活：{}", message);
    }

    /** 给界面显示用：这个模式类型要不要位置。 */
    public static MeidoModeType.Target targetOf(MeidoModeDef def) {
        return def.target();
    }
}
