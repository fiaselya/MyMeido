package com.mymeido.net;

import com.mymeido.MeidoConst;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.server.network.ServerPlayerEntity;

/**
 * 「玩家对她说的话」：对话输入框（客户端 {@code G} 键弹出）敲完字发到服务端。
 *
 * <p>服务端只负责把包转交给统一路由 {@link MeidoPlayerChat#handlePayload} ——
 * 多人 @ 判定、单机就近、灰色回显都在那一处做，和原生聊天栏入口行为完全一致。
 * LLM 那头是异步的，她的回复过 1~3 秒由聊天栏广播出来 —— 主线程一帧不阻塞。
 */
public record MeidoChatPayload(String text) implements CustomPayload {

    /** ⚠️ 要带命名空间就得 new CustomPayload.Id —— 坑见 {@link MeidoModeSelectPayload} 的注释。 */
    public static final CustomPayload.Id<MeidoChatPayload> ID =
            new CustomPayload.Id<>(MeidoConst.id("chat_send"));

    public static final PacketCodec<RegistryByteBuf, MeidoChatPayload> CODEC =
            PacketCodec.tuple(PacketCodecs.STRING, MeidoChatPayload::text,
                    MeidoChatPayload::new);

    @Override
    public CustomPayload.Id<? extends CustomPayload> getId() {
        return ID;
    }

    /** 由主入口调用。两端都执行 —— 注册是全局的。 */
    public static void register() {
        PayloadTypeRegistry.playC2S().register(ID, CODEC);
        ServerPlayNetworking.registerGlobalReceiver(ID, (payload, context) ->
                // 收包在网络线程上，碰实体必须回主线程。
                context.server().execute(() -> handle(context.player(), payload.text())));
    }

    private static void handle(ServerPlayerEntity player, String text) {
        // 客户端发来的一切都不可信：空白直接丢，超长截断 —— 这些校验在
        // MeidoPlayerChat.route 里统一做。这里只负责把包转交统一路由
        // （多人 @ 判定 / 单机就近 / 回显都在那一处，两个入口行为完全一致）。
        MeidoPlayerChat.handlePayload(player, text);
    }
}
