package com.mymeido.net;

import java.util.List;

import com.mymeido.MeidoLocale;
import com.mymeido.MyMeido;
import com.mymeido.ai.MeidoAi;
import com.mymeido.entity.MeidoEntity;
import com.mymeido.item.CommandAlarmItem;

import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

/**
 * 「玩家对女仆说话」的统一路由（2026-09-20 绯色需求：多人判定 + @ 指名）。
 *
 * <p>两个入口汇到同一个 {@link #route}：
 * <ul>
 *   <li>**原生聊天栏**（T 键）—— {@code CHAT_MESSAGE} 事件接管。之前不接这里是个体验黑洞：
 *       玩家自然会直接打字说话，mod 却只监听自己的输入框，于是「发了消息石沉大海」；</li>
 *   <li>**mod 对话输入框**（G 键）—— {@link MeidoChatPayload} 发的包
 *       （原版没显示过原文，所以这个入口要补一条灰色回显）。</li>
 * </ul>
 *
 * <h2>多人判定（绯色定稿：服务器上必须 @，没 @ 就不回复）</h2>
 *
 * <ul>
 *   <li>**单机**（integrated server 且只有自己）→ 老规矩：跟最近的女仆说话；</li>
 *   <li>**服务器 / 局域网多人** → 消息必须以 {@code @名字} 开头才触发，
 *       全世界范围按「角色名 / 昵称包含匹配」点名（人可能在基地，你在野外），
 *       多只匹配取离你最近的；**没有 @ 就不回复**（只给发送者一条 action bar 轻提示，
 *       不在公共聊天栏刷屏）。</li>
 * </ul>
 */
public final class MeidoPlayerChat {

    /** 单条台词长度上限（与服务端 G 输入框一致）。 */
    private static final int MAX_LEN = 240;

    private MeidoPlayerChat() {
    }

    /** 由主入口调用：接管原生聊天。 */
    public static void register() {
        ServerMessageEvents.CHAT_MESSAGE.register((message, sender, params) ->
                sender.getServer().execute(() ->
                        route(sender, message.getSignedContent(), false)));
    }

    /** G 输入框入口：原版没显示过原文，路由器会补一条灰色回显。 */
    public static void handlePayload(ServerPlayerEntity player, String raw) {
        route(player, raw, true);
    }

    // ------------------------------------------------------------------
    // 路由核心
    // ------------------------------------------------------------------

    /**
     * @param echo 原文要不要由 mod 回显一遍
     *             （原生聊天里原版已经显示了，只有 G 输入框需要补）
     */
    private static void route(ServerPlayerEntity player, String raw, boolean echo) {
        String line = raw == null ? "" : raw.strip();
        if (line.isEmpty()) {
            return;
        }
        if (line.length() > MAX_LEN) {
            line = line.substring(0, MAX_LEN);
        }

        MinecraftServer server = player.getServer();
        boolean multiplayer = server != null
                && (!server.isSingleplayer() || server.getPlayerManager().getCurrentPlayerCount() > 1);

        MeidoEntity meido;
        if (multiplayer) {
            // ---- 多人：必须 @名字，没 @ 就不回复 ----
            if (!line.startsWith("@")) {
                hintNoAt(player);
                return;
            }
            String rest = line.substring(1).strip();
            int space = rest.indexOf(' ');
            String target = space >= 0 ? rest.substring(0, space) : rest;
            String body = space >= 0 ? rest.substring(space + 1).strip() : "";
            if (target.isEmpty() || body.isEmpty()) {
                hintNoAt(player);
                return;
            }
            meido = findByName(player, target);
            if (meido == null) {
                player.sendMessage(Text.literal(MeidoLocale.pick(
                        "[mymeido] 没有 @ 到叫「" + target + "」的女仆",
                        "[mymeido] No maid named '" + target + "' was @-mentioned")), true);
                return;
            }
            line = body;
        } else {
            // ---- 单机：老规矩，跟最近的女仆说 ----
            meido = CommandAlarmItem.findNearest(player);
            if (meido == null) {
                player.sendMessage(Text.literal(MeidoLocale.pick(
                        "[mymeido] 附近没有女仆。先 /mymeido summon 召一只，或走过去再说话",
                        "[mymeido] No maid nearby. Summon one with /mymeido summon first, or walk over and talk")), false);
                return;
            }
        }

        if (echo) {
            echo(player, line);
        }
        MyMeido.LOGGER.info("[mymeido][chat] <{}> {}", player.getName().getString(), line);
        MeidoAi.onPlayerLine(meido, player, line);
    }

    private static void echo(ServerPlayerEntity player, String line) {
        MinecraftServer server = player.getServer();
        if (server != null) {
            server.getPlayerManager().broadcast(Text.literal(
                    "<" + player.getName().getString() + "> " + line)
                    .formatted(Formatting.GRAY), false);
        }
    }

    /** 没有 @ 的轻提示：只给发送者的 action bar，公共聊天栏不受污染。 */
    private static void hintNoAt(ServerPlayerEntity player) {
        player.sendMessage(Text.literal(MeidoLocale.pick(
                "[mymeido] 服务器上人多，想跟女仆说话要先 @她的名字（例如 @hoshino 你好）",
                "[mymeido] On a busy server, @-mention her name to talk to a maid (e.g. @hoshino hello)")), true);
    }

    /**
     * 按「角色名 / 昵称包含匹配」点名，不限距离；多只匹配取离玩家最近的。
     */
    private static MeidoEntity findByName(ServerPlayerEntity player, String target) {
        String needle = target.toLowerCase();
        List<MeidoEntity> candidates = player.getWorld().getEntitiesByClass(
                MeidoEntity.class,
                player.getBoundingBox().expand(1.0E6D),
                e -> e.isAlive()
                        && (e.characterName().toLowerCase().contains(needle)
                                || e.getNickname().toLowerCase().contains(needle)));
        MeidoEntity best = null;
        double bestSquared = Double.MAX_VALUE;
        for (MeidoEntity candidate : candidates) {
            double d = player.squaredDistanceTo(candidate);
            if (d < bestSquared) {
                bestSquared = d;
                best = candidate;
            }
        }
        return best;
    }
}
