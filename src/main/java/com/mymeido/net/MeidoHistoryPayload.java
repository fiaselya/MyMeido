package com.mymeido.net;

import com.mymeido.MeidoConst;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;

/**
 * 「对话历史面板」的数据应答（S2C）：服务端把某只女仆的近期对话 + 记忆摘要
 * 打包成一段纯文本发给客户端，{@code MeidoHistoryScreen} 收到才渲染。
 *
 * <p>历史只存在服务端实体身上，这是「请求 → 应答」里的应答半边（请求见
 * {@link MeidoHistoryRequestPayload}）。多条对话用 {@code \n} 连成一段 ——
 * 面板本来就是按行画的，拆包反而多余。
 */
public record MeidoHistoryPayload(String text) implements CustomPayload {

    /** ⚠️ 要带命名空间就得 new CustomPayload.Id —— 坑见 {@link MeidoModeSelectPayload} 的注释。 */
    public static final CustomPayload.Id<MeidoHistoryPayload> ID =
            new CustomPayload.Id<>(MeidoConst.id("history_data"));

    public static final PacketCodec<RegistryByteBuf, MeidoHistoryPayload> CODEC =
            PacketCodec.tuple(PacketCodecs.STRING, MeidoHistoryPayload::text,
                    MeidoHistoryPayload::new);

    @Override
    public CustomPayload.Id<? extends CustomPayload> getId() {
        return ID;
    }

    /** 只在客户端源集注册（S2C 接收方），见 MeidoHistoryScreen。 */
    public static void registerS2C() {
        PayloadTypeRegistry.playS2C().register(ID, CODEC);
    }
}
