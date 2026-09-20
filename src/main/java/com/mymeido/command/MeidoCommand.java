package com.mymeido.command;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.DynamicCommandExceptionType;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import com.mymeido.ai.MeidoAi;
import com.mymeido.ai.MeidoAiConfig;
import com.mymeido.ai.MeidoMemoryExport;
import com.mymeido.ai.MeidoPersona;
import com.mymeido.chat.MeidoChat;
import com.mymeido.entity.MeidoColor;
import com.mymeido.entity.MeidoEmotion;
import com.mymeido.entity.MeidoEntity;
import com.mymeido.entity.MeidoInventory;
import com.mymeido.entity.MeidoMission;
import com.mymeido.entity.MeidoSkin;
import com.mymeido.entity.MeidoSkinRegistry;
import com.mymeido.entity.MeidoSpawn;
import com.mymeido.item.CommandAlarmItem;
import com.mymeido.item.MeidoContractItem;
import com.mymeido.mode.MeidoModeDef;
import com.mymeido.mode.MeidoModeRegistry;
import com.mymeido.registry.MyMeidoEntities;

import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.command.CommandSource;
import net.minecraft.command.argument.BlockPosArgumentType;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.item.ItemStack;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

/**
 * 一期全部功能都靠指令手动触发 —— 这样验证时不依赖任何 AI 组件，
 * 出问题能立刻判断是「实体/渲染/皮肤」的锅还是「LLM」的锅。
 *
 * <p>指令树：
 * <pre>
 * /mymeido summon [skin]        在脚下召唤一个女仆（★ 顺手给创建人一个指令闹钟）
 * /mymeido skin   &lt;skin&gt;         给最近的女仆换皮
 * /mymeido color  &lt;color&gt;        给最近的女仆换专属色
 * /mymeido emotion &lt;emotion&gt;     给最近的女仆播一段情绪
 * /mymeido name   &lt;名字&gt;         给最近的女仆改名（空 = 恢复皮肤名）
 * /mymeido say    &lt;文本&gt;         让最近的女仆在聊天栏说一句（走角色专属色）
 * /mymeido proactive            看她的「主动搭话」状态（待够多久、说了几次、白天黑夜）
 * /mymeido proactive say        让她现在就主动说一句（跳过停留时间/次数上限，看效果用）
 * /mymeido inventory            看她背包里有什么（二期）
 * /mymeido hand                 看她手上 / 身上穿着什么（二期）
 * /mymeido drop                 把她背包里的东西全倒在地上（二期）
 * /mymeido alarm                给你一个指令闹钟（召女仆时会自动给，这条是丢了之后找回用）
 * /mymeido contract             给你一张女仆契约（加入世界时自动给，这条是多要一张）
 * /mymeido mode &lt;模式&gt; [x y z]   直接派活（闹钟界面出问题时的备用通道）
 * /mymeido mission              看她现在在干什么、在哪儿、家在哪儿
 * /mymeido reload               重新读 config/mymeido/modes.json（不用重启游戏）
 * </pre>
 *
 * <p>「最近的女仆」= 以玩家为中心 {@value #SEARCH_RADIUS} 格内、距离最近的那一个。
 * 二期会换成实体选择器，一期这样最简单也够用。
 */
public final class MeidoCommand {

    /**
     * 搜索半径（格）。与 {@code CommandAlarmItem.DISPATCH_RANGE} 保持一致 ——
     * 两个入口（命令 / 闹钟）能找到的范围不一样的话，
     * 「闹钟点不到、命令却点得到」会让人以为界面坏了。
     */
    private static final double SEARCH_RADIUS = 64.0;

    /** {@code /mymeido hand} 展示哪些槽位。顺序按「先看手上再看身上」排。 */
    private static final EquipmentSlot[] SHOWN_SLOTS = {
            EquipmentSlot.MAINHAND,
            EquipmentSlot.OFFHAND,
            EquipmentSlot.HEAD,
            EquipmentSlot.CHEST,
            EquipmentSlot.LEGS,
            EquipmentSlot.FEET,
    };

    private static final SimpleCommandExceptionType NO_MEIDO =
            new SimpleCommandExceptionType(Text.translatable("commands.mymeido.no_meido"));
    private static final SimpleCommandExceptionType NO_PLAYER =
            new SimpleCommandExceptionType(Text.translatable("commands.mymeido.no_player"));
    /** 实体选择器：<code><who></code> 没匹配到任何女仆。 */
    private static final DynamicCommandExceptionType WHO_NOT_FOUND =
            new DynamicCommandExceptionType(who ->
                    Text.literal("[mymeido] 附近没有名字带「" + who + "」的女仆"));

    private MeidoCommand() {
    }

    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                dispatcher.register(CommandManager.literal("mymeido")
                        // 需要 OP（权限等级 2）。这是开发期指令，不该给普通玩家。
                        .requires(source -> source.hasPermissionLevel(2))

                        .then(CommandManager.literal("summon")
                                .executes(ctx -> summon(ctx.getSource(), MeidoSkinRegistry.defaultSkin()))
                                .then(CommandManager.argument("skin", StringArgumentType.word())
                                        .suggests(MeidoCommand::suggestSkins)
                                        .executes(ctx -> summon(ctx.getSource(),
                                                MeidoSkin.fromId(StringArgumentType.getString(ctx, "skin"))))))

                        .then(CommandManager.literal("skin")
                                .then(CommandManager.argument("skin", StringArgumentType.word())
                                        .suggests(MeidoCommand::suggestSkins)
                                        .executes(ctx -> setSkinCmd(ctx.getSource(),
                                                StringArgumentType.getString(ctx, "skin"), whoOrNull(ctx)))
                                        .then(CommandManager.argument("who", StringArgumentType.word())
                                                .executes(ctx -> setSkinCmd(ctx.getSource(),
                                                        StringArgumentType.getString(ctx, "skin"), whoOrNull(ctx))))))

                        .then(CommandManager.literal("color")
                                .then(CommandManager.argument("color", StringArgumentType.word())
                                        .suggests(MeidoCommand::suggestColors)
                                        .executes(ctx -> setColorCmd(ctx.getSource(),
                                                StringArgumentType.getString(ctx, "color"), whoOrNull(ctx)))
                                        .then(CommandManager.argument("who", StringArgumentType.word())
                                                .executes(ctx -> setColorCmd(ctx.getSource(),
                                                        StringArgumentType.getString(ctx, "color"), whoOrNull(ctx))))))

                        .then(CommandManager.literal("emotion")
                                .then(CommandManager.argument("emotion", StringArgumentType.word())
                                        .suggests(MeidoCommand::suggestEmotions)
                                        .executes(ctx -> setEmotionCmd(ctx.getSource(),
                                                StringArgumentType.getString(ctx, "emotion"), whoOrNull(ctx)))
                                        .then(CommandManager.argument("who", StringArgumentType.word())
                                                .executes(ctx -> setEmotionCmd(ctx.getSource(),
                                                        StringArgumentType.getString(ctx, "emotion"), whoOrNull(ctx))))))

                        .then(CommandManager.literal("name")
                                .then(CommandManager.argument("name", StringArgumentType.greedyString())
                                        .executes(ctx -> {
                                            MeidoEntity meido = pick(ctx.getSource(), whoOrNull(ctx));
                                            String name = StringArgumentType.getString(ctx, "name");
                                            // 传空字符串 = 清掉改名，恢复成皮肤名。
                                            meido.setNickname(name);
                                            feedback(ctx.getSource(), "当前名字：" + meido.characterName());
                                            return 1;
                                        })))

                        .then(CommandManager.literal("say")
                                .then(CommandManager.argument("text", StringArgumentType.greedyString())
                                        .executes(ctx -> {
                                            MeidoEntity meido = pick(ctx.getSource(), whoOrNull(ctx));
                                            MeidoChat.say(meido, StringArgumentType.getString(ctx, "text"));
                                            return 1;
                                        })))

                        // ---- 二期：背包与装备的查看 / 清空 ----
                        // 二期还没有容器界面，先靠指令把「她身上到底有什么」摊开来看。

                        .then(CommandManager.literal("inventory")
                                .executes(ctx -> showInventory(ctx.getSource(), whoOrNull(ctx)))
                                .then(CommandManager.argument("who", StringArgumentType.word())
                                        .executes(ctx -> showInventory(ctx.getSource(), whoOrNull(ctx)))))

                        .then(CommandManager.literal("hand")
                                .executes(ctx -> showHand(ctx.getSource(), whoOrNull(ctx)))
                                .then(CommandManager.argument("who", StringArgumentType.word())
                                        .executes(ctx -> showHand(ctx.getSource(), whoOrNull(ctx)))))

                        .then(CommandManager.literal("drop")
                                .executes(ctx -> dropAll(ctx.getSource(), whoOrNull(ctx)))
                                .then(CommandManager.argument("who", StringArgumentType.word())
                                        .executes(ctx -> dropAll(ctx.getSource(), whoOrNull(ctx)))))

                        // ---- 二期第二批：派活 ----
                        // 正常玩法走「指令闹钟」，而且那块闹钟是**召女仆时自动到手**的
                        // （见 summon() 末尾）—— 这里的 alarm 只是「掉了 / 送人了」时的找回通道，
                        // mode / mission / reload 则是给验证和排查用的：
                        // 界面出问题的时候，至少还有一条路能把模式派下去、再把它读出来。

                        .then(CommandManager.literal("alarm")
                                .executes(ctx -> {
                                    ServerPlayerEntity player = requirePlayer(ctx.getSource());
                                    return giveAlarm(ctx.getSource(), player);
                                }))

                        // 「女仆契约」是创造入口的正规通道（玩家加入世界自动到手一张）。
                        // 这条指令是多要一张用的：丢了、送人了、或者一次想连造几位。
                        .then(CommandManager.literal("contract")
                                .executes(ctx -> giveContract(ctx.getSource())))

                        .then(CommandManager.literal("mode")
                                .then(CommandManager.argument("mode", StringArgumentType.word())
                                        .suggests(MeidoCommand::suggestModes)
                                        .executes(ctx -> assign(ctx.getSource(),
                                                StringArgumentType.getString(ctx, "mode"), null, whoOrNull(ctx)))
                                        .then(CommandManager.argument("pos", BlockPosArgumentType.blockPos())
                                                .executes(ctx -> assign(ctx.getSource(),
                                                        StringArgumentType.getString(ctx, "mode"),
                                                        BlockPosArgumentType.getBlockPos(ctx, "pos"), whoOrNull(ctx)))
                                                .then(CommandManager.argument("who", StringArgumentType.word())
                                                        .executes(ctx -> assign(ctx.getSource(),
                                                                StringArgumentType.getString(ctx, "mode"),
                                                                BlockPosArgumentType.getBlockPos(ctx, "pos"), whoOrNull(ctx))))
                                        )
                                        .then(CommandManager.argument("who", StringArgumentType.word())
                                                .executes(ctx -> assign(ctx.getSource(),
                                                        StringArgumentType.getString(ctx, "mode"), null, whoOrNull(ctx))))))

                        .then(CommandManager.literal("mission")
                                .executes(ctx -> showMission(ctx.getSource(), whoOrNull(ctx)))
                                .then(CommandManager.argument("who", StringArgumentType.word())
                                        .executes(ctx -> showMission(ctx.getSource(), whoOrNull(ctx)))))

                        .then(CommandManager.literal("favor")
                                .executes(ctx -> showFavor(ctx.getSource(), whoOrNull(ctx)))
                                .then(CommandManager.argument("who", StringArgumentType.word())
                                        .executes(ctx -> showFavor(ctx.getSource(), whoOrNull(ctx)))))

                        // ---- 三期：对话（A2/A3/A4-3）----
                        // chat = 「玩家说一句 → 她回一句」的最小闭环。正式的对话输入框
                        // （自定义按键，不占原生 T）是后面的活，先用指令把 LLM 链路跑通。
                        // 控制台也能用（无玩家，AI 回复发到日志），无头冒烟测试靠这个。

                        .then(CommandManager.literal("chat")
                                .then(CommandManager.argument("text", StringArgumentType.greedyString())
                                        .executes(ctx -> chatCmd(ctx.getSource(),
                                                StringArgumentType.getString(ctx, "text"), whoOrNull(ctx)))
                                        .then(CommandManager.argument("who", StringArgumentType.word())
                                                .executes(ctx -> chatCmd(ctx.getSource(),
                                                        StringArgumentType.getString(ctx, "text"), whoOrNull(ctx))))))

                        .then(CommandManager.literal("aistatus")
                                .executes(ctx -> aiStatus(ctx.getSource())))

                        .then(CommandManager.literal("aiguide")
                                .executes(ctx -> {
                                    // B2 定稿：mod 不内置模型下载器，给地址让玩家自己去下。
                                    for (String line : MeidoAiConfig.guideLines()) {
                                        ctx.getSource().sendFeedback(
                                                () -> Text.literal("[mymeido] " + line), false);
                                    }
                                    return 1;
                                }))

                        .then(CommandManager.literal("aireload")
                                .executes(ctx -> {
                                    MeidoAiConfig.load();
                                    // 顺手重扫皮肤目录：往 skins/ 里丢了新角色之后，
                                    // 一条 aireload 就能把它的初始人设卡补出来。
                                    MeidoSkinRegistry.reload();
                                    MeidoPersona.loadAll(MeidoSkinRegistry.ids());
                                    MeidoAi.clearCooldowns();
                                    feedback(ctx.getSource(), "对话配置与人设卡已重载（失败冷却也清了）");
                                    return aiStatus(ctx.getSource());
                                }))

                        .then(CommandManager.literal("persona")
                                .executes(ctx -> showPersona(ctx.getSource(), whoOrNull(ctx)))
                                .then(CommandManager.argument("who", StringArgumentType.word())
                                        .executes(ctx -> showPersona(ctx.getSource(), whoOrNull(ctx))))
                                .then(CommandManager.literal("extract")
                                        .executes(ctx -> extractPersona(ctx.getSource(), whoOrNull(ctx)))
                                        .then(CommandManager.argument("who", StringArgumentType.word())
                                                .executes(ctx -> extractPersona(ctx.getSource(), whoOrNull(ctx))))))

                        .then(CommandManager.literal("memory")
                                .executes(ctx -> showMemory(ctx.getSource(), whoOrNull(ctx)))
                                .then(CommandManager.argument("who", StringArgumentType.word())
                                        .executes(ctx -> showMemory(ctx.getSource(), whoOrNull(ctx)))))

                        .then(CommandManager.literal("proactive")
                                // 无参 = 看状态：她为什么还不开口，一眼看明白（时间/次数/白天黑夜全在里面）
                                .executes(ctx -> showProactive(ctx.getSource(), whoOrNull(ctx)))
                                .then(CommandManager.argument("who", StringArgumentType.word())
                                        .executes(ctx -> showProactive(ctx.getSource(), whoOrNull(ctx))))
                                // proactive say = 跳过所有限制，让她现在就说一句。
                                // 两个用途：玩家「等不及想先看看效果」，和无头冒烟测试
                                // （没有真玩家就没法制造「待在附近 5 分钟」，只能直接点这一下）。
                                .then(CommandManager.literal("say")
                                        .executes(ctx -> proactiveSay(ctx.getSource(), whoOrNull(ctx)))
                                        .then(CommandManager.argument("who", StringArgumentType.word())
                                                .executes(ctx -> proactiveSay(ctx.getSource(), whoOrNull(ctx))))))

                        .then(CommandManager.literal("chatstyle")
                                .executes(ctx -> {
                                    boolean plain = MeidoAiConfig.togglePlainChat();
                                    feedback(ctx.getSource(), plain
                                            ? "她说的话已切回纯白字（写回了 backend.txt）"
                                            : "她说的话恢复角色专属色（写回了 backend.txt）");
                                    return 1;
                                }))

                        .then(CommandManager.literal("reload")
                                .executes(ctx -> {
                                    MeidoModeRegistry.load();
                                    int count = MeidoModeRegistry.all().size();
                                    feedback(ctx.getSource(), "模式清单已重新加载：" + count + " 条（"
                                            + MeidoModeRegistry.configFile() + "）");
                                    return MeidoModeRegistry.ids().isEmpty() ? 0 : 1;
                                }))

                        // 看皮肤库 / 重扫皮肤目录。
                        // ★ 独立于 aireload：那个是「对话后端」的事，这个纯粹是「我往 skins
                        //   里丢了一张图，怎么让它立刻被认出来」—— 最常见的问题是
                        //   「图放了但游戏里没反应」，这条指令就是给那种情况准备的
                        //   （渲染侧还要按 F3+T 重载一次资源才会重新读盘）。
                        .then(CommandManager.literal("skins")
                                .executes(ctx -> showSkins(ctx.getSource()))
                                .then(CommandManager.literal("reload")
                                        .executes(ctx -> {
                                            MeidoSkinRegistry.reload();
                                            MeidoPersona.loadAll(MeidoSkinRegistry.ids());
                                            feedback(ctx.getSource(), "皮肤目录已重扫，人设卡也补齐了");
                                            return showSkins(ctx.getSource());
                                        }))))
                );
    }

    // ------------------------------------------------------------------
    // 执行体
    // ------------------------------------------------------------------

    private static int summon(ServerCommandSource source, MeidoSkin skin) throws CommandSyntaxException {
        ServerPlayerEntity player = requirePlayer(source);

        // ★ 造她的配方只有一份（{@link MeidoSpawn#atPlayer}）：
        //   指令与「女仆契约」道具两个入口共用，免得改了一处忘了另一处。
        MeidoEntity meido = MeidoSpawn.atPlayer(player, skin);
        feedback(source, "已召唤女仆。皮肤 " + skin.getId() + " / 颜色 " + meido.getMeidoColor().getId());

        // ★ 造出来的时候顺手把「指令闹钟」塞给创建人 —— 这是整个玩法唯一一个
        //   「你必须先有道具才会玩」的门槛，让她自己把闹钟交到你手上，
        //   比在文档里写一句「先敲 /mymeido alarm」有用得多。
        //   ★ 2026-09-21 起这块闹钟**绑她本人**，而且每次都发（一块只管一位女仆）——
        //     省着发的话，玩家下一块闹钟总是指向别人，就是「串了」。
        giveAlarmFor(source, player, meido);
        return 1;
    }

    /** 发一块绑给 {@code meido} 的专属闹钟，并说清「拿到了 / 背包满了」。 */
    private static int giveAlarmFor(ServerCommandSource source, ServerPlayerEntity player, MeidoEntity meido) {
        if (CommandAlarmItem.grantFor(player, meido)) {
            feedback(source, "给了你一块只对「" + meido.characterName()
                    + "」有效的指令闹钟：右键空气挑模式，右键方块派活");
        } else {
            feedback(source, "背包满了，闹钟掉在你脚边了");
        }
        return 1;
    }

    /**
     * 给玩家一张女仆契约（多要一张 / 丢了找回）。
     *
     * <p>和 {@link #giveAlarm} 对称：契约是<b>消耗品</b>，所以这里不检查「是不是已经有了」——
     * 手里 3 张也很正常（一次想连造几位女仆）。加入世界那次才检查，那是为了防止重进刷满背包。
     *
     * <p>控制台 / 命令方块执行时没有「玩家」，那就把<b>本该发给玩家的编号清单</b>打出来：
     * 它既是管理员的说明书，也是无头冒烟唯一能验「编号 ↔ 皮肤」对应关系的入口
     * （真玩家右键 + 聊天栏输入数字这套动作，在无头环境里没有玩家可操作）。
     */
    private static int giveContract(ServerCommandSource source) {
        ServerPlayerEntity player = source.getPlayer();
        if (player == null) {
            feedback(source, "女仆契约 mymeido:meido_contract —— 玩家加入世界时自动到手一张；"
                    + "用法是右键它，再在聊天栏输入编号。下面是发给玩家的那份清单：");
            for (Text line : MeidoContractItem.menuLines()) {
                source.sendFeedback(() -> line, false);
            }
            return 1;
        }
        if (MeidoContractItem.grant(player)) {
            feedback(source, "给了你一张女仆契约：右键它 → 聊天栏选编号 → 她就在你脚下了");
        } else {
            feedback(source, "背包满了，女仆契约掉在你脚边了");
        }
        return 1;
    }

    /**
     * 把指令闹钟交到玩家手上，并说清「拿到了 / 已经有了 / 背包满了」三种结果。
     *
     * <p>召女仆时自动调一次，{@code /mymeido alarm} 也走这里 ——
     * 于是「什么时候给、给了之后说什么」只有一份实现。
     */
    /**
     * 给一块<b>未绑定</b>的指令闹钟（{@code /mymeido alarm} 专用）。
     *
     * <p>★ 2026-09-21 起，「造女仆时自动发的那块」走 {@link #giveAlarmFor}（绑她本人）；
     * 这条指令留下的是<b>找回 / 备用</b>通道，发出来的是老式「对最近的女仆有效」那种，
     * 工具提示里会明写「未绑定」。养了多只女仆的话，请用造她时配的那块专属闹钟 ——
     * 未绑定的这块在两只女仆之间确实会派错人，这是它的定义，不是 bug。
     */
    private static int giveAlarm(ServerCommandSource source, ServerPlayerEntity player) {
        if (CommandAlarmItem.grant(player)) {
            feedback(source, "给了你一块未绑定的指令闹钟（对最近的女仆有效）：右键空气挑模式，右键方块派活。"
                    + "想让闹钟只管某一位，用她造出来时配的那块专属的");
        } else {
            feedback(source, "背包满了，指令闹钟掉在你脚边了");
        }
        return 1;
    }

    /**
     * 给最近的女仆派活。验证 / 排查用；正常玩法走指令闹钟。
     *
     * <p>「id 不认识」和「这个模式必须给位置却没给」都用 {@code sendError} 直说，
     * 而不是抛 {@code CommandSyntaxException} —— 后者的报错文本得在注册时定死，
     * 而这里想报的是「你敲的这个 id 错了，可选的有这些」，是动态内容。
     */
    private static int assign(ServerCommandSource source, String modeId, BlockPos target, String who)
            throws CommandSyntaxException {
        // ★ 先校验 id，再去找女仆。
        //   反过来的话，「你敲的模式名不存在」会先变成「附近没有女仆」——
        //   于是你得先跑回家门口、再敲一遍，才知道是 id 打错了。
        Optional<MeidoModeDef> def = MeidoModeRegistry.byId(modeId);
        if (def.isEmpty()) {
            source.sendError(Text.literal("[mymeido] 未知模式：" + modeId
                    + "（可选：" + String.join(" / ", MeidoModeRegistry.ids()) + "）"));
            return 0;
        }
        if (def.get().needsTarget() && target == null) {
            source.sendError(Text.literal("[mymeido]「" + def.get().name()
                    + "」必须给目标位置：/mymeido mode " + def.get().id() + " <x y z>"));
            return 0;
        }
        MeidoEntity meido = (target != null && source.getPlayer() == null)
                // ★ 控制台（无玩家）+ 带坐标：按目标位置找最近的女仆。
                //   无头冒烟测试全靠这条路 —— 派发前置检查（有竿 / 有种子）也能在
                //   不开客户端的情况下验证。带玩家时行为完全不变。
                ? nearestToPos(source, Vec3d.ofCenter(target))
                : pick(source, who);
        // ★ 把「谁派的」一起传下去。指令这条路也传，是为了行为一致：
        //   不管是右键派活还是敲指令，一次性任务干完后闹钟都会切回游走。
        //   （不用 requirePlayer()：nearest() 里已经调过一次了，这儿再调只是白抛一次异常。）
        MeidoEntity.Assignment result = meido.assignMode(def.get().id(), target, source.getPlayer());
        if (!result.isOk()) {
            // ★ 失败原因直接用它给的整句（里面已经写清「差在哪 + 怎么补」），
            //   这里再拼一次前缀的话，两处的措辞会各走各的。
            source.sendError(Text.literal("[mymeido] " + result.failure()));
            return 0;
        }
        MeidoModeDef accepted = result.def();
        // ★ 报 her 的真实落点（钓鱼是岸边），不是玩家敲的那个坐标。
        String where = accepted.usesTarget() && result.spot() != null
                ? " → " + MeidoMission.format(result.spot()) : "";
        feedback(source, meido.characterName() + " 接到：" + accepted.name() + where);
        if (!accepted.isImplemented()) {
            feedback(source, "（这个模式的行为还在做，她只会走到目标点站着）");
        }
        return 1;
    }

    /** 玩家为中心 {@value #SEARCH_RADIUS} 格内、距离最近的那个女仆。 */
    private static MeidoEntity nearest(ServerCommandSource source) throws CommandSyntaxException {
        ServerPlayerEntity player = requirePlayer(source);
        List<MeidoEntity> candidates = player.getWorld().getEntitiesByClass(
                MeidoEntity.class,
                player.getBoundingBox().expand(SEARCH_RADIUS),
                entity -> true);
        if (candidates.isEmpty()) {
            throw NO_MEIDO.create();
        }
        MeidoEntity best = null;
        double bestDistance = Double.MAX_VALUE;
        for (MeidoEntity candidate : candidates) {
            double distance = candidate.squaredDistanceTo(player);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = candidate;
            }
        }
        return best;
    }

    /**
     * 以某个坐标为中心 {@value #SEARCH_RADIUS} 格内、离它最近的女仆。
     *
     * <p>给<b>控制台</b>用的：控制台没有「玩家在哪」可参考，只能拿指令里给的
     * 目标坐标当锚点。无头冒烟测试（没有客户端、没有玩家）靠它跑通派活全链路。
     */
    private static MeidoEntity nearestToPos(ServerCommandSource source, Vec3d pos)
            throws CommandSyntaxException {
        if (!(source.getWorld() instanceof ServerWorld world)) {
            throw NO_MEIDO.create();
        }
        List<MeidoEntity> candidates = world.getEntitiesByClass(
                MeidoEntity.class,
                new Box(pos.x - SEARCH_RADIUS, pos.y - SEARCH_RADIUS, pos.z - SEARCH_RADIUS,
                        pos.x + SEARCH_RADIUS, pos.y + SEARCH_RADIUS, pos.z + SEARCH_RADIUS),
                entity -> true);
        if (candidates.isEmpty()) {
            throw NO_MEIDO.create();
        }
        MeidoEntity best = null;
        double bestDistance = Double.MAX_VALUE;
        for (MeidoEntity candidate : candidates) {
            double distance = candidate.squaredDistanceTo(pos);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = candidate;
            }
        }
        return best;
    }

    // ------------------------------------------------------------------
    // 实体选择器（第三批）：指令里加可选的 <who>，按名字挑人，不传还是最近的那个
    // ------------------------------------------------------------------

    /**
     * 按名字挑女仆：{@code who} 为空 = 老规矩（最近的那一个）；
     * 给了就先按名字过滤（昵称或角色名，不区分大小写的<b>包含</b>匹配），
     * 过滤完再取最近的。一个都匹配不上报「找不到叫 xx 的」。
     */
    private static MeidoEntity pick(ServerCommandSource source, String who)
            throws CommandSyntaxException {
        if (who == null || who.isBlank()) {
            return nearest(source);
        }
        ServerPlayerEntity player = requirePlayer(source);
        List<MeidoEntity> candidates = player.getWorld().getEntitiesByClass(
                MeidoEntity.class,
                player.getBoundingBox().expand(SEARCH_RADIUS),
                entity -> nameMatches(entity, who));
        if (candidates.isEmpty()) {
            throw WHO_NOT_FOUND.create(who);
        }
        MeidoEntity best = null;
        double bestDistance = Double.MAX_VALUE;
        for (MeidoEntity candidate : candidates) {
            double distance = candidate.squaredDistanceTo(player);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = candidate;
            }
        }
        return best;
    }

    /** 昵称或角色名包含 {@code who}（不区分大小写）。 */
    private static boolean nameMatches(MeidoEntity meido, String who) {
        String needle = who.toLowerCase();
        return meido.characterName().toLowerCase().contains(needle)
                || meido.getNickname().toLowerCase().contains(needle);
    }

    /** 指令树里有没有解析到可选的 {@code <who>} 参数；没有就返回 {@code null}。 */
    private static String whoOrNull(CommandContext<ServerCommandSource> ctx) {
        try {
            return StringArgumentType.getString(ctx, "who");
        } catch (IllegalArgumentException e) {
            // brigadier 的 getArgument 对「没解析到」直接抛 IAE —— 没有试探用的 API，只能接住。
            return null;
        }
    }

    // ---------------- 提出来的子命令执行体（为了共用可选 <who>） ----------------

    private static int setSkinCmd(ServerCommandSource source, String skinId, String who)
            throws CommandSyntaxException {
        MeidoEntity meido = pick(source, who);
        MeidoSkin skin = MeidoSkin.fromId(skinId);
        meido.setSkin(skin);
        feedback(source, meido.characterName() + " 换好皮肤了：" + skin.getId());
        return 1;
    }

    private static int setColorCmd(ServerCommandSource source, String colorId, String who)
            throws CommandSyntaxException {
        MeidoEntity meido = pick(source, who);
        MeidoColor color = MeidoColor.fromId(colorId);
        meido.setMeidoColor(color);
        feedback(source, meido.characterName() + " 专属色改成：" + color.getId());
        return 1;
    }

    private static int setEmotionCmd(ServerCommandSource source, String emotionId, String who)
            throws CommandSyntaxException {
        MeidoEntity meido = pick(source, who);
        MeidoEmotion emotion = MeidoEmotion.fromId(emotionId);
        meido.setEmotion(emotion);
        feedback(source, meido.characterName() + " 情绪：" + emotion.getDisplayName());
        return 1;
    }

    private static int showInventory(ServerCommandSource source, String who)
            throws CommandSyntaxException {
        MeidoEntity meido = pick(source, who);
        MeidoInventory inventory = meido.getInventory();
        if (inventory.isEmpty()) {
            feedback(source, meido.characterName() + " 的背包是空的");
            return 0;
        }
        feedback(source, meido.characterName() + " 的背包：");
        for (int slot = 0; slot < inventory.size(); slot++) {
            ItemStack stack = inventory.get(slot);
            if (stack.isEmpty()) {
                continue;
            }
            String line = "  [" + slot + "] "
                    + stack.getCount() + " x " + stack.getName().getString();
            source.sendFeedback(() -> Text.literal(line), false);
        }
        return 1;
    }

    private static int showHand(ServerCommandSource source, String who)
            throws CommandSyntaxException {
        MeidoEntity meido = pick(source, who);
        for (EquipmentSlot slot : SHOWN_SLOTS) {
            ItemStack stack = meido.getEquippedStack(slot);
            String line = "  " + slot.getName() + "： "
                    + (stack.isEmpty()
                            ? "（空）"
                            : stack.getCount() + " x " + stack.getName().getString());
            source.sendFeedback(() -> Text.literal(line), false);
        }
        return 1;
    }

    private static int dropAll(ServerCommandSource source, String who)
            throws CommandSyntaxException {
        MeidoEntity meido = pick(source, who);
        int count = meido.dropBackpack();
        feedback(source, meido.characterName() + " 倒出 " + count + " 件东西");
        return 1;
    }

    private static int showFavor(ServerCommandSource source, String who)
            throws CommandSyntaxException {
        MeidoEntity meido = pick(source, who);
        // ★ 好感度是隐性数值（不显示分数、不显示档位名）—— 只给一句氛围描述。
        feedback(source, meido.characterName() + "：" + meido.favorHint());
        return 1;
    }

    private static int showMission(ServerCommandSource source, String who)
            throws CommandSyntaxException {
        MeidoEntity meido = pick(source, who);
        MeidoMission mission = meido.getMission();
        feedback(source,
                meido.characterName() + " 现在在：" + mission.displayName()
                        + "（" + mission.modeId() + "）");
        source.sendFeedback(() -> Text.literal(
                "  目标点：" + MeidoMission.format(mission.target())), false);
        source.sendFeedback(() -> Text.literal(
                "  家：" + MeidoMission.format(mission.home())), false);
        Optional<MeidoModeDef> def = meido.getModeDef();
        if (def.isPresent() && !def.get().isImplemented()) {
            source.sendFeedback(() -> Text.literal(
                    "  （这个模式的「行为」还没做，她只会走到目标点站着）"), false);
        }
        return 1;
    }

    // ---------------- 三期：对话 ----------------

    /**
     * 玩家说一句话，走 LLM 让她回。回信是异步的 —— 反馈只说「正在想」，
     * 她的话过 1~3 秒（视后端）由聊天栏广播出来。
     *
     * <p>控制台（无玩家）也能用：世界范围内随便挑一个匹配的女仆，
     * AI 回复同时进日志 —— 无头冒烟测试没有玩家，靠 grep 日志验证。
     */
    private static int chatCmd(ServerCommandSource source, String text, String who)
            throws CommandSyntaxException {
        ServerPlayerEntity player = source.getPlayer();
        MeidoEntity meido = player != null
                ? pick(source, who)
                : anyMeidoInWorld(source.getWorld(), who);
        MeidoAi.onPlayerLine(meido, player, text);
        feedback(source, meido.characterName() + " 正在想怎么回……（回复有 1~3 秒延迟是正常的）");
        return 1;
    }

    /** 控制台专用：全世界范围内找第一个名字匹配（或任意）的女仆。 */
    private static MeidoEntity anyMeidoInWorld(ServerWorld world, String who)
            throws CommandSyntaxException {
        for (Entity entity : world.iterateEntities()) {
            if (entity instanceof MeidoEntity meido
                    && (who == null || who.isBlank() || nameMatches(meido, who))) {
                return meido;
            }
        }
        throw NO_MEIDO.create();
    }

    private static int aiStatus(ServerCommandSource source) {
        for (String line : MeidoAi.statusLines()) {
            source.sendFeedback(() -> Text.literal("[mymeido] " + line), false);
        }
        return 1;
    }

    // ---------------- 三期：主动搭话（2026-09-21 第四批） ----------------

    /**
     * {@code /mymeido proactive} —— 「她为什么还不主动开口」的现场体检。
     *
     * <p>这个功能是<b>隐藏触发</b>（待够 5 分钟 + 白天 + 本小时没超过 2 次），
     * 条件不满足时玩家看到的只是「什么都没发生」。不给一条能一眼看完的诊断，
     * 玩家只会以为坏了 —— 所以这条指令是功能的一部分，不是调试残留。
     */
    private static int showProactive(ServerCommandSource source, String who) throws CommandSyntaxException {
        MeidoEntity meido = pickForCommand(source, who);
        feedback(source, meido.characterName() + " 的主动搭话：");
        if (!MeidoAiConfig.proactiveChat()) {
            feedback(source, "  已关闭 —— chat/api.txt 里 proactive_chat=false");
            return 1;
        }
        if (!MeidoAiConfig.aiEnabled()) {
            feedback(source, "  不会发生 —— 还没接 API。主动搭话的台词必须让 API 现想");
            feedback(source, "   （怎么接：/mymeido aiguide；接完 /mymeido aireload）");
            return 1;
        }
        // ★ 计时圈半径（PROACTIVE_RANGE）必须印在这条「设置」行里，而不是只在最后
        //   「已累计停留」那句里 —— 最后那句要「创建人在线」才走得到，没记过创建人 /
        //   人不在线时都提前 return 了，玩家就永远看不到这个数字（2026-09-21 踩到：
        //   无头冒烟也断不到它）。半径是静态设置，跟秒数、次数一样属于「一眼该看见」的。
        feedback(source, "  设置：创建人在 " + (int) MeidoEntity.PROACTIVE_RANGE
                + " 格内时待够 " + MeidoAiConfig.proactiveIntervalSeconds() + " 秒触发一次，"
                + "每小时最多 " + MeidoAiConfig.proactiveMaxPerHour() + " 次");
        feedback(source, "  本小时：已说 " + meido.getProactiveCount() + " 次");
        // ★ 白天/黑夜排在「创建人」前面：这两条是<b>不论</b>创建人在不在都要看的信息，
        //   排在会 early-return 的检查后面就会被吃掉（第一版就踩了这个坑）。
        feedback(source, "  现在：" + (meido.isNightNow()
                ? "夜里 —— 她不会搭话（她只挑白天来）"
                : "白天 —— 到点她会自己走过来"));
        if (meido.getOwnerUuid() == null) {
            feedback(source, "  创建人：没记录 —— 她不是「对某个玩家」造出来的");
            feedback(source, "   → 用一张「女仆契约」再造一位（契约/指令造她时才认得主人）");
            return 1;
        }
        ServerPlayerEntity owner = meido.resolveOwner();
        if (owner == null) {
            feedback(source, "  创建人：UUID " + meido.getOwnerUuid() + " —— 不在线 / 不在这个世界");
            return 1;
        }
        feedback(source, "  创建人：" + owner.getName().getString() + "（在线，"
                + Math.round(Math.sqrt(meido.squaredDistanceTo(owner))) + " 格外）");
        if (meido.isProactiveApproaching()) {
            feedback(source, "  正在走过去：还剩 " + meido.proactiveApproachLeft() + " tick 就放弃这次");
            return 1;
        }
        if (meido.isProactiveLingering()) {
            feedback(source, "  刚说完，正在她身边站一会儿");
            return 1;
        }
        feedback(source, "  已累计停留 " + formatTicks(meido.getOwnerDwellTicks())
                + "（还差 " + formatTicks(meido.proactiveNeedTicks()) + "；离开 "
                + (int) MeidoEntity.PROACTIVE_RANGE + " 格只是暂停计时）");
        return 1;
    }

    /**
     * {@code /mymeido proactive say} —— 让她<b>现在</b>就主动说一句。
     *
     * <p>跳过停留时间、白天黑夜、次数上限（这是「我手动点的」，不是她自己触发的）。
     * 用途：玩家等不及 5 分钟想先看看效果；无头冒烟测试没有真玩家，
     * 只能靠这一下把「主动搭话 → API → 台词不重复」整条链路跑通。
     */
    private static int proactiveSay(ServerCommandSource source, String who) throws CommandSyntaxException {
        MeidoEntity meido = pickForCommand(source, who);
        if (!MeidoAiConfig.aiEnabled()) {
            source.sendError(Text.literal("[mymeido] 她还没接 API —— 主动搭话的台词是让 API 现想的，"
                    + "基础模式下没有这句可发（/mymeido aiguide）"));
            return 0;
        }
        // 有玩家就对着玩家说（那才是「主人」）；控制台跑的，退而求其次找创建人。
        ServerPlayerEntity player = source.getPlayer() != null ? source.getPlayer() : meido.resolveOwner();
        MeidoAi.onProactive(meido, player);
        feedback(source, "让 " + meido.characterName() + " 主动说一句……（1~3 秒后出现在聊天栏）");
        return 1;
    }

    /** {@code 300} → {@code 15.0 秒}。诊断输出里只出现秒，不出现 tick。 */
    private static String formatTicks(int ticks) {
        return String.format(java.util.Locale.ROOT, "%.1f 秒", ticks / 20.0);
    }

    // ---------------- 三期：人设与记忆（都能落到本地文件） ----------------

    /** 看她的人设卡在哪儿、里面现在是什么（注释不进 prompt，所以只显示生效的内容）。 */
    private static int showPersona(ServerCommandSource source, String who) throws CommandSyntaxException {
        MeidoEntity meido = pickForCommand(source, who);
        String skinId = meido.getSkin().getId();
        feedback(source, meido.characterName() + " 的人设卡：" + MeidoPersona.fileFor(skinId));
        String persona = MeidoPersona.personaFor(skinId);
        if (persona.isEmpty()) {
            feedback(source, "  现在还没有生效的设定 —— 自己写，或敲 /mymeido persona extract 让 API 写");
        } else {
            feedback(source, "  当前生效的设定：" + persona);
        }
        return 1;
    }

    /** 让 API 读对话、写人设，存进本地人设卡。 */
    private static int extractPersona(ServerCommandSource source, String who) throws CommandSyntaxException {
        MeidoEntity meido = pickForCommand(source, who);
        MeidoAi.extractPersona(meido, source.getPlayer());
        return 1;
    }

    /** 看她的记忆（摘要 + 可读副本的路径）。 */
    private static int showMemory(ServerCommandSource source, String who) throws CommandSyntaxException {
        MeidoEntity meido = pickForCommand(source, who);
        String summary = meido.getAiSummary();
        feedback(source, meido.characterName() + " 的记忆要点："
                + (summary.isEmpty() ? "（还没有，聊满一轮会自动压缩）" : summary));
        int recent;
        synchronized (meido.aiHistory()) {
            recent = meido.aiHistory().size();
        }
        feedback(source, "  近期对话 " + recent + " 条（上限 12，满了就压缩成上面的要点）");
        java.nio.file.Path file = MeidoMemoryExport.write(meido);
        feedback(source, "  可读副本：" + (file == null ? "导出失败（看日志）" : file));
        return 1;
    }

    /** 玩家在就近 / 控制台按名字挑一只（人设与记忆指令共用）。 */
    private static MeidoEntity pickForCommand(ServerCommandSource source, String who)
            throws CommandSyntaxException {
        ServerPlayerEntity player = source.getPlayer();
        return player != null ? pick(source, who) : anyMeidoInWorld(source.getWorld(), who);
    }

    /** 这些指令都要玩家位置做参照，控制台执行不了。 */
    private static ServerPlayerEntity requirePlayer(ServerCommandSource source) throws CommandSyntaxException {
        ServerPlayerEntity player = source.getPlayer();
        if (player == null) {
            throw NO_PLAYER.create();
        }
        return player;
    }

    private static void feedback(ServerCommandSource source, String message) {
        source.sendFeedback(() -> Text.literal("[mymeido] " + message), false);
    }

    /**
     * 把皮肤库整个念出来：共几位、分别是哪个文件、目录在哪。
     *
     * <p>「我往 skins 里丢了图，为什么游戏里还是那 4 个」是这个玩法最容易卡住的地方，
     * 这条指令就是为了让玩家一眼看出<b>认到的到底是哪几个文件</b>
     * （规范化后的 id 会跟文件名不一样，比如 {@code Sakurai Momoka.png} → {@code momoka}）。
     */
    private static int showSkins(ServerCommandSource source) {
        List<MeidoSkin> skins = MeidoSkinRegistry.all();
        int files = MeidoSkinRegistry.fileCount();
        feedback(source, "皮肤库共 " + skins.size() + " 位角色"
                + (files == 0
                        ? "（皮肤目录里还没有 png，这是内置的占位槽位，贴图回落原版 Steve）"
                        : "（皮肤目录里有 " + files + " 张 png）"));
        for (int i = 0; i < skins.size(); i++) {
            MeidoSkin skin = skins.get(i);
            feedback(source, "  " + (i + 1) + ") " + skin.getId() + "   ←   " + skin.fileName());
        }
        feedback(source, "目录：" + MeidoSkinRegistry.dir());
        return skins.size();
    }

    // ------------------------------------------------------------------
    // 补全
    // ------------------------------------------------------------------

    private static CompletableFuture<Suggestions> suggestSkins(
            CommandContext<ServerCommandSource> ctx, SuggestionsBuilder builder) {
        return CommandSource.suggestMatching(MeidoSkinRegistry.ids(), builder);
    }

    private static CompletableFuture<Suggestions> suggestColors(
            CommandContext<ServerCommandSource> ctx, SuggestionsBuilder builder) {
        return CommandSource.suggestMatching(MeidoColor.ids(), builder);
    }

    private static CompletableFuture<Suggestions> suggestEmotions(
            CommandContext<ServerCommandSource> ctx, SuggestionsBuilder builder) {
        return CommandSource.suggestMatching(MeidoEmotion.ids(), builder);
    }

    private static CompletableFuture<Suggestions> suggestModes(
            CommandContext<ServerCommandSource> ctx, SuggestionsBuilder builder) {
        return CommandSource.suggestMatching(MeidoModeRegistry.ids(), builder);
    }
}
