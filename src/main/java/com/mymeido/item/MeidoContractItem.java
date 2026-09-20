package com.mymeido.item;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.mymeido.MeidoLocale;
import com.mymeido.MyMeido;
import com.mymeido.entity.MeidoEntity;
import com.mymeido.entity.MeidoSkin;
import com.mymeido.entity.MeidoSkinRegistry;
import com.mymeido.entity.MeidoSpawn;

import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.ItemUsageContext;
import net.minecraft.item.tooltip.TooltipType;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.MutableText;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.TypedActionResult;
import net.minecraft.world.World;

/**
 * 女仆契约 —— <b>创造一位女仆的道具</b>（玩家加入世界时自动到手）。
 *
 * <h2>一段对话，两步操作</h2>
 *
 * <pre>
 *   右键（空气或地面） → 聊天栏列出皮肤库里的所有角色，编号 1..N
 *   在聊天栏输入编号   → 她出现在你脚下，契约消耗一张，并附赠一个指令闹钟
 * </pre>
 *
 * <h2>★ 为什么「输入编号」而不是弹一个选择界面</h2>
 *
 * <p>因为弹界面要写客户端源集的东西（{@code Screen}），而这套流程<b>纯服务端就能实现</b>：
 * 服务端本来就会把玩家打的字交给 mod（{@link com.mymeido.net.MeidoPlayerChat} 就在做这件事），
 * 顺水推舟把「1..N」解释成选择即可。好处是专用服务器 / 无客户端 mod 的场景也一样能用，
 * 而且玩家不用学新的操作（≪打字≫是这个游戏里最基础的动作）。
 *
 * <h2>★ 数字为什么不会跑到公共聊天栏，也不会被女仆回复</h2>
 *
 * <p>拦截点在 {@code ServerMessageEvents.ALLOW_CHAT_MESSAGE}，它返回 {@code false} 时
 * Fabric 的实现是 {@code ci.cancel(); return;}（2026-09-21 读 fabric-message-api 源码核实）——
 * <b>广播被取消，而且后面的 {@code CHAT_MESSAGE} 事件根本不会触发</b>。
 * 所以「女仆把玩家输入的 2 当成台词回复一句」这种事不会发生，
 * 不需要在别处再加一层「她是不是正在被选」的判断。
 *
 * <h2>只吃「裸数字」和「取消」，其余照常聊天</h2>
 *
 * <p>菜单在等选择时<b>不是模态</b>的：不认识的内容一律放行（只有一条 action bar 轻提示），
 * 玩家想跟别人聊天、想跟女仆说话都不受影响。代价是「窗口期内恰好单独打了个 1」
 * 会被当成选择 —— 但这本来就是刚用完契约时最自然的动作，可接受。
 *
 * <h2>契约只能用一次</h2>
 *
 * <p>堆叠上限 1、用掉就没了（<b>创造模式也扣</b>，2026-09-21 绯色定稿）。
 * 一份契约换一位女仆。想要更多：合成（下界之星 + 8 张纸）或 {@code /mymeido contract}。
 */
public class MeidoContractItem extends Item {

    /** 选择窗口时长（毫秒）。够读完清单再打字，又不至于让「等待选择」永远挂着。 */
    private static final long WINDOW_MS = 60_000L;

    /**
     * 谁正在选、什么时候过期。key = 玩家 UUID。
     *
     * <p>放静态 Map 而不是物品组件：选择是<b>玩家级</b>的瞬时状态，
     * 不是某一张契约的属性 —— 把契约丢给别人 / 塞进箱子，不该把「他正在选」这件事也带走。
     */
    private static final Map<UUID, Long> PENDING = new ConcurrentHashMap<>();

    public MeidoContractItem(Settings settings) {
        super(settings);
    }

    // ------------------------------------------------------------------
    // 两个入口都开菜单
    // ------------------------------------------------------------------

    @Override
    public TypedActionResult<ItemStack> use(World world, PlayerEntity player, Hand hand) {
        ItemStack stack = player.getStackInHand(hand);
        if (world.isClient()) {
            // 真正的逻辑全在服务端（要造实体、要往聊天栏发东西）。
            // 客户端返回 SUCCESS 只是告诉原版「这一下用掉了」，并顺带甩一下手臂。
            return TypedActionResult.success(stack, true);
        }
        if (player instanceof ServerPlayerEntity serverPlayer) {
            openMenu(serverPlayer);
        }
        return TypedActionResult.success(stack, false);
    }

    @Override
    public ActionResult useOnBlock(ItemUsageContext context) {
        PlayerEntity player = context.getPlayer();
        if (player == null) {
            return ActionResult.PASS;
        }
        if (context.getWorld().isClient()) {
            // ★ 两边都要返回 SUCCESS。客户端返回 PASS 的话原版会接着走「用物品」那一支，
            //   同一个右键会开两次菜单（闹钟当年踩过一模一样的坑）。
            return ActionResult.SUCCESS;
        }
        if (player instanceof ServerPlayerEntity serverPlayer) {
            openMenu(serverPlayer);
        }
        return ActionResult.SUCCESS;
    }

    // ------------------------------------------------------------------
    // 编号清单
    // ------------------------------------------------------------------

    /** 列出皮肤库里的所有角色，编号 1..N，并把该玩家标成「正在选」。 */
    public static void openMenu(ServerPlayerEntity player) {
        // 再按一次就是「重新开始选」：刷新窗口、重发清单，不叠加。
        PENDING.put(player.getUuid(), System.currentTimeMillis() + WINDOW_MS);
        for (Text line : menuLines()) {
            player.sendMessage(line, false);
        }
    }

    /**
     * 编号清单的每一行（纯文本，<b>不含发送</b>）。
     *
     * <p>玩家菜单与控制台预览共用这一份 —— 两处各写一遍的话，
     * 一定会出现「文档/指令里说的编号」和「游戏里看到的编号」不一致。
     */
    public static List<Text> menuLines() {
        List<MeidoSkin> skins = MeidoSkinRegistry.all();
        List<Text> lines = new ArrayList<>(skins.size() + 3);
        lines.add(Text.literal(MeidoLocale.pick("[mymeido] 选一位女仆创造出来（皮肤库共 " + skins.size()
                + " 位，一张 png = 一个角色）：",
                "[mymeido] Choose a maid to create (skin library has " + skins.size()
                + " entries, one png = one character):")));

        for (int i = 0; i < skins.size(); i++) {
            int number = i + 1;
            MeidoSkin skin = skins.get(i);
            // 点一下就自动把编号填进聊天框（SUGGEST_COMMAND）—— 比让玩家手打一个字符友好，
            // 而且不需要客户端代码：点击事件是原版就渲染并处理的东西。
            MutableText name = Text.literal(label(skin)).setStyle(Style.EMPTY
                    .withColor(Formatting.AQUA)
                    .withClickEvent(new ClickEvent(
                            ClickEvent.Action.SUGGEST_COMMAND, String.valueOf(number))));
            lines.add(Text.literal("  " + number + ") ").formatted(Formatting.GRAY).append(name));
        }

        // ★ 皮肤目录里一张图都没有时，上面列的是内置占位槽位 —— 必须说出来。
        //   不说的话玩家会以为「这 4 个就是 mod 自带的角色」，然后抱怨贴图是 Steve。
        if (MeidoSkinRegistry.fileCount() == 0) {
            lines.add(Text.literal(MeidoLocale.pick("  注意：skins 文件夹里还没有 png，上面是内置的占位槽位"
                    + "（贴图是原版 Steve）。放几张图进去，你的角色就在这儿了 → "
                    + MeidoSkinRegistry.dir(),
                    "  Note: no png in the skins folder yet; the entries above are built-in placeholder slots "
                    + "(texture is vanilla Steve). Drop some images in and your characters appear here -> "
                    + MeidoSkinRegistry.dir())).formatted(Formatting.YELLOW));
        }

        lines.add(Text.literal(MeidoLocale.pick("  输入编号创造；输入「取消」放弃。"
                + (WINDOW_MS / 1000) + " 秒内有效，过期再右键一次。",
                "  Enter a number to create; type 'cancel' to give up. "
                + "Valid for " + (WINDOW_MS / 1000) + "s, right-click again after it expires."))
                .formatted(Formatting.DARK_GRAY));
        return lines;
    }

    /** 清单上显示的名字：显示名和 id 不一样时两个都写出来（玩家一眼知道对应哪个皮肤文件）。 */
    private static String label(MeidoSkin skin) {
        String shown = skin.displayName();
        if (shown == null || shown.isBlank() || shown.equals(skin.getId())) {
            return skin.getId();
        }
        return shown + "（" + skin.getId() + "）";
    }

    // ------------------------------------------------------------------
    // 消化聊天输入
    // ------------------------------------------------------------------

    /**
     * 在「正在选」的窗口内消化一条聊天内容。
     *
     * @return {@code true} = 这条已经被菜单吃掉，调用方要拦下广播
     *         （否则数字会跑进公共聊天栏、而且女仆还会把它当台词回一句）；
     *         {@code false} = 与菜单无关，当普通聊天放行
     */
    public static boolean tryHandleSelection(ServerPlayerEntity player, String raw) {
        UUID id = player.getUuid();
        Long deadline = PENDING.get(id);
        if (deadline == null) {
            return false;
        }
        if (System.currentTimeMillis() > deadline) {
            // 过期了才想起来打字：清掉状态，当普通聊天放行（不再刷菜单，免得像卡住了）。
            PENDING.remove(id);
            return false;
        }

        String line = raw == null ? "" : raw.strip();
        if (line.isEmpty()) {
            return false;
        }

        if ("取消".equals(line) || "cancel".equalsIgnoreCase(line) || "q".equalsIgnoreCase(line)) {
            PENDING.remove(id);
            player.sendMessage(Text.literal(MeidoLocale.pick("[mymeido] 已取消。契约还在你手里，想创造的时候再右键一次。",
                    "[mymeido] Cancelled. The contract is still in your hand; right-click again when you want to create."))
                    .formatted(Formatting.GRAY), false);
            return true;
        }

        List<MeidoSkin> skins = MeidoSkinRegistry.all();
        Integer pick = parseNumber(line, skins.size());
        if (pick == null) {
            // 不认识的内容不抢着吞掉 —— 只轻提示一句，让正常聊天照常走。
            player.sendMessage(Text.literal(MeidoLocale.pick("[mymeido] 还没选好：输入 1~" + skins.size()
                    + " 选角色，或输入「取消」。",
                    "[mymeido] Not selected yet: enter 1~" + skins.size()
                    + " to choose a character, or type 'cancel'."))
                    .formatted(Formatting.YELLOW), true);
            return false;
        }

        PENDING.remove(id);
        create(player, skins.get(pick - 1));
        return true;
    }

    /**
     * 认「裸数字」编号。
     *
     * <p>★ 2026-09-21：从「只认一位数」改成<b>多位也行</b> —— 皮肤库现在跟着 skins
     * 文件夹走、数量不设上限，只认一位数的话第 10 个角色就永远选不到。
     * 多位数字不会被误吃：超出编号范围的一律返回 {@code null} 当普通聊天放行。
     *
     * <p>顺手把全角数字（{@code １}，中文输入法下最常见）折成半角 ——
     * 玩家当然会一直开着中文输入法来打这个数字，不折的话他会觉得「打了没反应」。
     */
    private static Integer parseNumber(String line, int max) {
        String s = line.strip();
        // 超过 3 位的直接不当编号：皮肤库不可能有 1000 个角色，
        // 但「2024」这种正常聊天内容却随时可能出现，别把它吃掉。
        if (s.isEmpty() || s.length() > 3) {
            return null;
        }
        StringBuilder digits = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= '０' && c <= '９') {
                c = (char) (c - '０' + '0');
            }
            if (c < '0' || c > '9') {
                return null;    // 混了非数字 → 不是编号
            }
            digits.append(c);
        }
        int n;
        try {
            n = Integer.parseInt(digits.toString());
        } catch (NumberFormatException e) {
            return null;
        }
        return (n >= 1 && n <= max) ? n : null;
    }

    // ------------------------------------------------------------------
    // 创造 + 发闹钟
    // ------------------------------------------------------------------

    private static void create(ServerPlayerEntity player, MeidoSkin skin) {
        MeidoEntity meido = MeidoSpawn.atPlayer(player, skin);

        // ★ 契约只能用一次：拿在手上的优先扣（就是它触发的这次选择），否则找背包里那张。
        //   创造模式<b>也扣</b>——「一次」就该是一次，不然创造模式下这张契约等于无限用，
        //   玩家根本感受不到它有消耗（也测不出「用完就没了」这条规则）。
        ItemStack held = player.getMainHandStack();
        if (isContract(held)) {
            held.decrement(1);
        } else {
            ItemStack found = findContract(player);
            if (found != null) {
                found.decrement(1);
            }
        }

        MyMeido.LOGGER.info("[mymeido] {} created a maid with the contract (skin {})",
                player.getName().getString(), skin.getId());
        player.sendMessage(Text.literal(MeidoLocale.pick("[mymeido] " + meido.characterName() + " 来了。皮肤 "
                + skin.getId() + " / 颜色 " + meido.getMeidoColor().getId(),
                "[mymeido] " + meido.characterName() + " has arrived. Skin "
                + skin.getId() + " / color " + meido.getMeidoColor().getId()))
                .formatted(Formatting.GREEN), false);

        giveAlarm(player, meido);
    }

    /**
     * 造完女仆给她配一块<b>专属</b>指令闹钟 —— 和 {@code /mymeido summon} 走同一套说法。
     *
     * <p>★ 每次创造都发，而且<b>不再检查「身上是不是已经有闹钟了」</b>：
     * 闹钟是绑人的，一块只管一位女仆。省这一块的话，新造出来的她就完全没法和别的区分开
     * —— 玩家的下一块闹钟总是指向别人，这就是「串了」。
     *
     * <p>「召了她之后不知道该干什么」是这个玩法最容易卡住的地方，
     * 所以这里必须明确说出「右键空气挑模式、右键方块派活」。
     */
    private static void giveAlarm(ServerPlayerEntity player, MeidoEntity meido) {
        if (CommandAlarmItem.grantFor(player, meido)) {
            player.sendMessage(Text.literal(MeidoLocale.pick("[mymeido] 给了她专属的指令闹钟（只对 "
                    + meido.characterName() + " 有效）：右键空气挑模式，右键方块派活。",
                    "[mymeido] Gave her a dedicated command alarm (only valid for "
                    + meido.characterName() + "): right-click air to pick a mode, right-click a block to dispatch.")
                    ).formatted(Formatting.GREEN), false);
        } else {
            player.sendMessage(Text.literal(MeidoLocale.pick("[mymeido] 背包满了，" + meido.characterName()
                    + " 的专属闹钟掉在你脚边了。",
                    "[mymeido] Inventory is full, " + meido.characterName()
                    + "'s dedicated alarm dropped at your feet.")).formatted(Formatting.YELLOW), false);
        }
    }

    // ------------------------------------------------------------------
    // 契约的找 / 发 / 查
    // ------------------------------------------------------------------

    public static boolean isContract(ItemStack stack) {
        return stack.getItem() instanceof MeidoContractItem;
    }

    /** 找出玩家身上的契约（先两手、再背包），没有返回 {@code null}。 */
    public static ItemStack findContract(PlayerEntity player) {
        ItemStack main = player.getMainHandStack();
        if (isContract(main)) {
            return main;
        }
        ItemStack off = player.getOffHandStack();
        if (isContract(off)) {
            return off;
        }
        PlayerInventory inventory = player.getInventory();
        for (int slot = 0; slot < inventory.size(); slot++) {
            ItemStack stack = inventory.getStack(slot);
            if (isContract(stack)) {
                return stack;
            }
        }
        return null;
    }

    public static boolean hasContract(PlayerEntity player) {
        return findContract(player) != null;
    }

    /** 发一张契约。背包满了掉在脚边（{@code giveItemStack} 会把塞不下的余量留在 stack 里）。 */
    public static boolean grant(PlayerEntity player) {
        ItemStack stack = new ItemStack(MeidoItems.MEIDO_CONTRACT);
        if (player.giveItemStack(stack)) {
            return true;
        }
        if (!stack.isEmpty()) {
            player.dropItem(stack, false);
        }
        return false;
    }

    /** 玩家退出时清掉等待态，避免「他不在线时窗口过期、下次进来还挂着」。 */
    public static void forget(PlayerEntity player) {
        if (player != null) {
            PENDING.remove(player.getUuid());
        }
    }

    // ------------------------------------------------------------------
    // 提示文本
    // ------------------------------------------------------------------

    @Override
    public void appendTooltip(ItemStack stack, TooltipContext context, List<Text> tooltip, TooltipType type) {
        super.appendTooltip(stack, context, tooltip, type);
        tooltip.add(Text.literal(MeidoLocale.pick("右键（空气或地面）：在聊天栏列出皮肤库里的角色，编号 1~"
                + MeidoSkinRegistry.size(),
                "Right-click (air or ground): lists the characters in the skin library in chat, numbered 1~"
                + MeidoSkinRegistry.size())).formatted(Formatting.GRAY));
        tooltip.add(Text.literal(MeidoLocale.pick("角色 = config/mymeido/skins/ 里的一张 png，文件名就是角色名",
                "A character = a png in config/mymeido/skins/, the file name is the character name"))
                .formatted(Formatting.GRAY));
        tooltip.add(Text.literal(MeidoLocale.pick("然后在聊天栏输入编号，她就出现在你脚下",
                "Then enter the number in chat and she appears at your feet")).formatted(Formatting.GRAY));
        tooltip.add(Text.literal(MeidoLocale.pick("用掉就没了（一份只创造一位）；合成：下界之星 + 8 张纸",
                "Used up once gone (one contract creates one maid); craft: nether star + 8 paper"))
                .formatted(Formatting.DARK_GRAY));
        tooltip.add(Text.literal(MeidoLocale.pick("她到手时会附赠一块只对她有效的指令闹钟",
                "She comes with a dedicated command alarm that only works for her"))
                .formatted(Formatting.DARK_GRAY));
    }
}
