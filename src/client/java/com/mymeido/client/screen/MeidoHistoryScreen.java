package com.mymeido.client.screen;

import java.util.ArrayList;
import java.util.List;

import com.mymeido.net.MeidoHistoryPayload;
import com.mymeido.net.MeidoHistoryRequestPayload;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.MathHelper;

/**
 * 对话历史面板（A2 定稿的「另一半」）：按 {@code H} 打开，回看和她的近期对话
 * + 记忆摘要。原生聊天栏会滚走，这里留给玩家的「我们的对话都聊过什么」。
 *
 * <p>数据流：打开面板 → 发 {@link MeidoHistoryRequestPayload}（C2S）→
 * 服务端找最近的女仆、把她的 NBT 历史打包成文本发回（{@link MeidoHistoryPayload}）
 * → 回包到达时存在静态收件箱里，下一帧画出来。数据没到之前显示「加载中」。
 *
 * <p>历史最多 12 条 + 摘要一段，一屏放得下；给个鼠标滚轮以防小屏幕 / 长台词。
 */
public class MeidoHistoryScreen extends Screen {

    /** S2C 回包的收件箱：网络回包到达时面板可能还没 setScreen，先攒着。 */
    private static String inbox = null;

    /** 面板当前显示的行。 */
    private final List<String> lines = new ArrayList<>();

    /** 滚动偏移（单位：行）。0 = 顶部。 */
    private int scroll;

    public MeidoHistoryScreen() {
        super(Text.literal("对话记录"));
    }

    /** 客户端入口注册（S2C 接收）：回包进收件箱，若面板正开着直接触发重画数据。 */
    public static void registerS2C() {
        MeidoHistoryPayload.registerS2C();
        ClientPlayNetworking.registerGlobalReceiver(MeidoHistoryPayload.ID, (payload, context) ->
                MinecraftClient.getInstance().execute(() -> inbox = payload.text()));
    }

    @Override
    protected void init() {
        // 打开面板就向服务端要数据；历史在服务端实体身上，客户端副本没有。
        inbox = null;
        this.lines.clear();
        this.scroll = 0;
        ClientPlayNetworking.send(new MeidoHistoryRequestPayload());
    }

    /** 同 MeidoChatScreen：去掉 1.21 默认的世界高斯模糊（绯色实测「H 很模糊」），换半透明渐变。 */
    @Override
    public void renderBackground(DrawContext context, int mouseX, int mouseY, float delta) {
        context.fillGradient(0, 0, this.width, this.height, 0x66000000, 0xCC000000);
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        this.renderBackground(context, mouseX, mouseY, delta);

        // 收件箱有新货就重新拆行（\n 分行，单行超宽再硬折，别让它跑出屏幕）。
        if (inbox != null && this.lines.isEmpty()) {
            for (String rawLine : inbox.split("\n", -1)) {
                this.lines.addAll(wrap(rawLine));
            }
        }
        inbox = null;

        context.drawCenteredTextWithShadow(this.textRenderer, this.title,
                this.width / 2, this.height / 2 - 105, 0xFFD9A0);

        if (this.lines.isEmpty()) {
            context.drawCenteredTextWithShadow(this.textRenderer,
                    Text.literal("正在向她翻记忆……（附近没有女仆的话会直接在聊天栏提示）")
                            .formatted(Formatting.GRAY),
                    this.width / 2, this.height / 2 - 20, 0xFFFFFF);
            super.render(context, mouseX, mouseY, delta);
            return;
        }

        // 滚动夹紧：最多滚到「最后一行贴着底部」。
        int lineHeight = this.textRenderer.fontHeight + 2;
        int areaTop = this.height / 2 - 88;
        int areaBottom = this.height / 2 + 100;
        int visible = Math.max(1, (areaBottom - areaTop) / lineHeight);
        this.scroll = MathHelper.clamp(this.scroll, 0, Math.max(0, this.lines.size() - visible));

        int y = areaTop;
        for (int i = this.scroll; i < this.lines.size() && y < areaBottom; i++, y += lineHeight) {
            String line = this.lines.get(i);
            // 摘要行和「谁在说话」用不同颜色：记忆要点淡金、主人淡蓝、她的话白。
            int color = line.startsWith("〔") ? 0xE8C170
                    : line.startsWith("你：") ? 0x9FC5FF
                    : 0xFFFFFF;
            context.drawTextWithShadow(this.textRenderer, line, 20, y, color);
        }

        if (this.lines.size() > visible) {
            context.drawCenteredTextWithShadow(this.textRenderer,
                    Text.literal("（滚轮翻页 " + (this.scroll + 1) + "/" + this.lines.size() + "）")
                            .formatted(Formatting.DARK_GRAY),
                    this.width / 2, Math.min(areaBottom + 4, this.height - 10), 0xFFFFFF);
        }
        super.render(context, mouseX, mouseY, delta);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        this.scroll -= (int) Math.signum(verticalAmount) * 3;
        this.scroll = MathHelper.clamp(this.scroll, 0, Math.max(0, this.lines.size() - 1));
        return true;
    }

    /** Esc 关闭。 */
    @Override
    public boolean shouldCloseOnEsc() {
        return true;
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    /** 按渲染宽度硬折行（中英混排，textRenderer 量宽最准）。 */
    private static List<String> wrap(String line) {
        var renderer = MinecraftClient.getInstance().textRenderer;
        int maxWidth = MinecraftClient.getInstance().getWindow().getScaledWidth() - 40;
        List<String> out = new ArrayList<>();
        String rest = line;
        if (rest.isEmpty()) {
            out.add("");
            return out;
        }
        while (rest.length() > 1 && renderer.getWidth(rest) > maxWidth) {
            // 先按字符数对半猜，再往回收/放，二分太重，台词就几十个字够用了。
            int cut = Math.max(1, rest.length() / 2);
            while (cut < rest.length() - 1 && renderer.getWidth(rest.substring(0, cut + 1)) <= maxWidth) {
                cut++;
            }
            out.add(rest.substring(0, cut));
            rest = rest.substring(cut);
        }
        out.add(rest);
        return out;
    }
}
