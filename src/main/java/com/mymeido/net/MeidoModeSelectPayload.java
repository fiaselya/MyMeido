package com.mymeido.net;

import com.mymeido.MeidoConst;
import com.mymeido.MeidoLocale;
import com.mymeido.MyMeido;
import com.mymeido.item.CommandAlarmItem;
import com.mymeido.item.MeidoItems;
import com.mymeido.mode.MeidoModeDef;
import com.mymeido.mode.MeidoModeRegistry;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.item.ItemStack;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

/**
 * 这个 mod 的<b>唯一</b>一个网络包：玩家在界面里选完模式，客户端告诉服务端。
 *
 * <h2>为什么只需要这一个方向</h2>
 *
 * <p>「有哪些模式」是一份 {@code config/mymeido/modes.json}。单机下客户端和服务端
 * 读的是<b>同一个文件</b>，所以界面不需要服务端把清单发过来 —— 客户端自己读就行，
 * 于是省掉了整个 S2C 方向。
 *
 * <p>代价写在明面上：<b>联机时两边配置不一致的话，你选的服务端可能不认识。</b>
 * 那种情况服务端会拒掉并回一句提示（见 {@link #handle}），
 * 而不是硬存进去留下一个谁也解释不了的模式 id。
 *
 * <h2>组件读写两侧都要做</h2>
 *
 * <p>客户端选完会<b>先自己改一遍</b>（本地立刻见效，tooltip 马上更新），
 * 再把这个包发出去让服务端改权威的那一份。
 * 两边用的是同一个 {@link CommandAlarmItem#findAlarm}，
 * 所以「说的是同一个闹钟」这件事不靠约定，靠同一段代码。
 */
public record MeidoModeSelectPayload(String modeId) implements CustomPayload {

    /**
     * ⚠️ <b>别写成 {@code CustomPayload.id("mymeido:mode_select")}</b>。
     *
     * <p>{@code CustomPayload.id(String)} 内部走的是 {@code Identifier.ofVanilla(String)}，
     * 也就是把整个字符串当成<b>路径</b>、命名空间写死 {@code minecraft} ，
     * 结果会去校验 {@code "mymeido:mode_select"} 这个路径里有没有非法字符 ——
     * 冒号当然非法，于是<b>在类初始化那一刻就抛 {@code InvalidIdentifierException}</b>，
     * mod 整个加载失败（不是运行时某次发包才炸，是一启动就炸）。
     *
     * <p>要带命名空间就得自己 new 一个 {@link CustomPayload.Id}，把拼好的
     * {@code Identifier} 交给它。编译期这两行长得一样看不出区别，
     * 只有真跑一次服务端才会暴露 —— 所以这个坑写在代码旁边。
     */
    public static final CustomPayload.Id<MeidoModeSelectPayload> ID =
            new CustomPayload.Id<>(MeidoConst.id("mode_select"));

    /** 只有一个字符串字段 —— 不用手写读写，tuple 就是为这种情况准备的。 */
    public static final PacketCodec<RegistryByteBuf, MeidoModeSelectPayload> CODEC =
            PacketCodec.tuple(PacketCodecs.STRING, MeidoModeSelectPayload::modeId,
                    MeidoModeSelectPayload::new);

    @Override
    public CustomPayload.Id<? extends CustomPayload> getId() {
        return ID;
    }

    // ------------------------------------------------------------------
    // 注册
    // ------------------------------------------------------------------

    /** 由主入口调用。两个端都会执行 —— 注册是全局的，不分端。 */
    public static void register() {
        PayloadTypeRegistry.playC2S().register(ID, CODEC);
        ServerPlayNetworking.registerGlobalReceiver(ID, (payload, context) ->
                // ★ 收到包时我们在【网络线程】上，绝不能在这里碰世界 / 实体 / 背包。
                //   server().execute 会把活儿丢回主线程排队。
                context.server().execute(() -> handle(context.player(), payload.modeId())));
    }

    // ------------------------------------------------------------------
    // 服务端处理
    // ------------------------------------------------------------------

    private static void handle(ServerPlayerEntity player, String modeId) {
        // 客户端发来的一切都不可信，先校验。
        if (!MeidoModeRegistry.isKnown(modeId)) {
            // 多半是联机时两边的 modes.json 不一样 —— 说清楚，别让人以为是 mod 坏了。
            player.sendMessage(Text.literal(MeidoLocale.pick("[mymeido] 服务端不认识模式「" + modeId
                    + "」，可能是两边的 modes.json 不一致",
                    "[mymeido] The server does not recognize mode '" + modeId
                    + "', the two sides' modes.json may differ")), false);
            MyMeido.LOGGER.warn("[mymeido] player {} selected a mode unknown to the server: {}",
                    player.getName().getString(), modeId);
            return;
        }

        ItemStack alarm = CommandAlarmItem.findAlarm(player);
        if (alarm == null) {
            // 界面开着的时候把闹钟扔了 / 换手了？那就什么都不做。
            player.sendMessage(Text.literal(MeidoLocale.pick("[mymeido] 你手上没有指令闹钟",
                    "[mymeido] You have no command alarm in hand")), true);
            return;
        }

        alarm.set(MeidoItems.ALARM_MODE, modeId);
        // ★ 手动推一次槽位同步。
        //   原版对「直接在物品上 set 组件」是不广播的（它只在你通过界面搬东西时才发槽位包），
        //   所以不补这一下的话：客户端本地先改过一次（看着是对的），
        //   但服务端这一份改了却不告诉客户端 —— 两边就这么各说各话，直到下一次碰巧同步。
        //   补一次 syncState，服务端那份立刻成为客户端看到的唯一真相。
        player.currentScreenHandler.syncState();

        MeidoModeDef def = MeidoModeRegistry.byId(modeId).orElse(null);
        if (def != null) {
            player.sendMessage(Text.literal(MeidoLocale.pick("[mymeido] 已选择：" + def.name()
                    + "（" + def.hint() + "）",
                    "[mymeido] Selected: " + def.name() + " (" + def.hint() + ")")), true);
        }
    }
}
