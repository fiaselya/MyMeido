package com.mymeido.chat;

import com.mymeido.ai.MeidoAiConfig;
import com.mymeido.entity.MeidoColor;
import com.mymeido.entity.MeidoEntity;

import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.text.MutableText;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.text.TextColor;
import net.minecraft.world.World;

/**
 * 聊天栏里的「她说话」。
 *
 * <p>一期不接 LLM，所以这里只负责两件事：
 * <ol>
 *   <li>把一句话包装成「名字（专属色+加粗）+ 正文（专属色）」的 {@link Text}；</li>
 *   <li>广播给服务器上所有玩家。</li>
 * </ol>
 * 三期的 LLM 输出接进来时，只要把生成的字符串丢给 {@link #say} 就行，这层不用改。
 *
 * <p>配色刻意分成两档（见 {@link MeidoColor}）：名字略深、正文最亮。
 * 这样一排对话扫下来，眼睛先落在内容上，而不是名字上。
 */
public final class MeidoChat {

    /** 名字与正文之间的分隔符。 */
    private static final String SEPARATOR = "：";

    private static final String[] GREETINGS = {
            "欢迎回来，主人。",
            "今天也要一起加油呀。",
            "主人，需要我做点什么吗？",
            "嗯……我一直在等你。",
            "要喝点什么吗？",
    };

    private MeidoChat() {
    }

    /** 拼一行「她说的话」。纯白模式（A2 三条不可让步之一）下全部退回默认白字，名字保留加粗。 */
    public static Text line(MeidoEntity meido, String text) {
        boolean plain = MeidoAiConfig.plainChat();
        MeidoColor color = meido.getMeidoColor();

        MutableText name = Text.literal(meido.characterName())
                .setStyle(plain
                        ? Style.EMPTY.withBold(true)
                        : Style.EMPTY
                                .withColor(TextColor.fromRgb(color.nameColor()))
                                .withBold(true));

        MutableText separator = Text.literal(SEPARATOR)
                .setStyle(plain ? Style.EMPTY : Style.EMPTY.withColor(TextColor.fromRgb(color.nameColor())));

        MutableText body = Text.literal(text)
                .setStyle(plain ? Style.EMPTY : Style.EMPTY.withColor(TextColor.fromRgb(color.bodyColor())));

        return Text.empty().append(name).append(separator).append(body);
    }

    /**
     * 广播她说的一句话。
     *
     * @return 成功广播返回 true；在没有服务端的环境（纯客户端）返回 false。
     */
    public static boolean say(MeidoEntity meido, String text) {
        World world = meido.getWorld();
        MinecraftServer server = world.getServer();
        if (server == null) {
            return false;
        }
        server.getPlayerManager().broadcast(line(meido, text), false);
        return true;
    }

    /** 一期用的固定台词，等三期换成 LLM 生成。 */
    public static String greeting(MeidoEntity meido, PlayerEntity player) {
        String base = GREETINGS[meido.getRandom().nextInt(GREETINGS.length)];
        return base;
    }

    /**
     * 「收下东西」之后的一句话。台词按结果分岔 ——
     * 玩家丢了一把剑却听她说「收好了」会以为自己给错了，说清楚现在在哪儿很重要。
     *
     * <p>依然是写死的；三期接 LLM 时把这一句换掉就行，调用方不用改。
     */
    public static String received(String itemName, MeidoEntity.AcceptResult result) {
        return switch (result) {
            case EQUIPPED -> "这个我换上了：" + itemName + "。";
            case STORED -> "收好了，" + itemName + "。";
            case OVERFLOW -> "背包塞不下了……" + itemName + " 先放地上好吗？";
            case IGNORED -> "……";
        };
    }

    /** 被名牌改名之后的一句话。 */
    public static String renamed(MeidoEntity meido) {
        return "记住了，" + meido.characterName() + " 这个名字。";
    }
}
