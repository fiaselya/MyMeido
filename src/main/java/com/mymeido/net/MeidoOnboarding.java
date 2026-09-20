package com.mymeido.net;

import com.mymeido.MeidoLocale;
import com.mymeido.item.MeidoContractItem;

import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

/**
 * 玩家的「第一次进门」—— 发契约、以及把「选女仆」的输入接上。
 *
 * <h2>① 加入世界就给一张女仆契约</h2>
 *
 * <p>为什么不靠 {@code /mymeido summon}：指令是<b>知道有这条指令的人</b>才会敲的东西，
 * 而玩家的默认预期是「进世界 → 上手就有的玩」。所以契约直接塞给他，
 * 并且把「右键它 → 选编号 → 她就是你的人了」这句话说在明面上 ——
 * 手里多一块不认识的东西却不给用法，比不给还糟。
 *
 * <p>身上已经有一张时<b>不重复给</b>（否则每次重进世界都 +1，背包很快被契约塞满）。
 * 这时候也要说一句，免得玩家以为「怎么这次没给我」。默认进 action bar，不刷聊天栏。
 *
 * <h2>② 「正在选女仆」时，聊天里的数字归菜单吃</h2>
 *
 * <p>用 {@code ALLOW_CHAT_MESSAGE}：它返回 {@code false} 时 Fabric 会
 * {@code ci.cancel(); return;}（读 fabric-message-api 源码核实），
 * 于是<b>广播被拦下、后面的 {@code CHAT_MESSAGE} 也不会触发</b> ——
 * 数字既不会出现在公共聊天栏，女仆也不会把它当台词回一句。
 * 正因为「拦下就不会触发」，这里<b>不需要</b>再去 {@link MeidoPlayerChat} 里
 * 加一层「她是不是正在被选」的判断。
 *
 * <p>返回 {@code true}（放行）代表「这条与菜单无关」—— 菜单开着的时候聊天照常可用。
 */
public final class MeidoOnboarding {

    private MeidoOnboarding() {
    }

    /** 由主入口调用。 */
    public static void register() {
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) ->
                // 加入事件在 Netty 线程上跑，改世界 / 发消息都该回主线程。
                server.execute(() -> onJoin(handler.player)));

        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) ->
                MeidoContractItem.forget(handler.player));

        ServerMessageEvents.ALLOW_CHAT_MESSAGE.register((message, sender, params) ->
                // 返回 false = 取消广播（并且 CHAT_MESSAGE 不再触发）。
                !MeidoContractItem.tryHandleSelection(sender, message.getSignedContent()));
    }

    private static void onJoin(ServerPlayerEntity player) {
        if (MeidoContractItem.hasContract(player)) {
            player.sendMessage(Text.literal(MeidoLocale.pick(
                    "[mymeido] 你身上还有一张女仆契约 —— 右键它就能创造一位女仆。",
                    "[mymeido] You still have a maid contract on you -- right-click it to create a maid.")
                    ).formatted(Formatting.GRAY), true);
            return;
        }
        boolean inInventory = MeidoContractItem.grant(player);
        player.sendMessage(Text.literal(MeidoLocale.pick(
                "[mymeido] 给你一张「女仆契约」：右键它 → 在聊天栏选编号 → 她就出现了。",
                "[mymeido] Here is a 'maid contract': right-click it -> pick a number in chat -> she appears.")
                ).formatted(Formatting.AQUA), false);
        if (!inInventory) {
            player.sendMessage(Text.literal(MeidoLocale.pick("[mymeido] （背包满了，契约掉在你脚边）",
                    "[mymeido] (Inventory is full, the contract dropped at your feet)"))
                    .formatted(Formatting.YELLOW), false);
        }
    }
}
