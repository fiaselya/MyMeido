package com.mymeido.net;

import com.mymeido.MeidoConst;
import com.mymeido.entity.MeidoEntity;
import com.mymeido.item.CommandAlarmItem;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

/**
 * 「打开对话历史面板」的请求（客户端按 {@code H} 键时发，不带任何字段）。
 *
 * <p>历史只存在<b>服务端</b>实体身上（NBT MeidoHistory），客户端实体副本没有这份数据，
 * 所以必须走「请求 → 应答」：服务端找最近的女仆，把她的近期对话 + 记忆摘要
 * 用 {@link MeidoHistoryPayload} 发回去，面板收到才画。
 */
public record MeidoHistoryRequestPayload() implements CustomPayload {

    /** ⚠️ 要带命名空间就得 new CustomPayload.Id —— 坑见 {@link MeidoModeSelectPayload} 的注释。 */
    public static final CustomPayload.Id<MeidoHistoryRequestPayload> ID =
            new CustomPayload.Id<>(MeidoConst.id("history_request"));

    /** 无字段包用 unit：编解码永远是同一个空实例。 */
    public static final PacketCodec<RegistryByteBuf, MeidoHistoryRequestPayload> CODEC =
            PacketCodec.unit(new MeidoHistoryRequestPayload());

    @Override
    public CustomPayload.Id<? extends CustomPayload> getId() {
        return ID;
    }

    /** 由主入口调用。两端都执行 —— 注册是全局的。 */
    public static void register() {
        PayloadTypeRegistry.playC2S().register(ID, CODEC);
        ServerPlayNetworking.registerGlobalReceiver(ID, (payload, context) ->
                // 收包在网络线程上，碰实体必须回主线程。
                context.server().execute(() -> handle(context.player())));
    }

    private static void handle(ServerPlayerEntity player) {
        MeidoEntity meido = CommandAlarmItem.findNearest(player);
        if (meido == null) {
            player.sendMessage(Text.literal(
                    "[mymeido] 附近没有女仆，没有对话可看。先 /mymeido summon 召一只"), false);
            return;
        }

        StringBuilder out = new StringBuilder();
        String summary = meido.getAiSummary();
        if (!summary.isEmpty()) {
            // 摘要是「很久以前就聊过」的部分，排在最上面当地基。
            out.append("〔记忆要点〕\n").append(summary).append("\n\n");
        }
        out.append("〔近期对话〕");
        var history = meido.aiHistory();
        synchronized (history) {
            if (history.isEmpty() && summary.isEmpty()) {
                player.sendMessage(Text.literal(
                        "[mymeido] " + meido.characterName() + " 还没跟你聊过天（右键打招呼或按 G 说话）"), false);
                return;
            }
            for (String[] entry : history) {
                out.append('\n');
                // 客户端拿到的是纯文本，谁在说话靠前缀区分（user = 主人）。
                out.append("user".equals(entry[0]) ? "你" : meido.characterName())
                        .append("：").append(entry[1]);
            }
        }

        ServerPlayNetworking.send(player, new MeidoHistoryPayload(out.toString()));
    }
}
