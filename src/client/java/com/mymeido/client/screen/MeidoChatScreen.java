package com.mymeido.client.screen;

import com.mymeido.MeidoLocale;
import com.mymeido.net.MeidoChatPayload;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

//? if >=1.21.11 {
import net.minecraft.client.input.KeyInput;
//?}

import org.lwjgl.glfw.GLFW;

/**
 * 对话输入框（A2 定稿：自定义按键 + 独立输入框，<b>不占原生 T 聊天键</b>）。
 *
 * <p>朴素得刻意：一行输入框 + 一行标题。回车发送并关闭，Esc 直接关闭。
 * 发送走 {@link MeidoChatPayload}（C2S），服务端找最近的女仆、回显玩家这半句、
 * 再把 LLM 的回复广播出来 —— 界面这边一个服务端逻辑都不碰，最干净。
 *
 * <p>原生聊天栏的固有短板（A2 已知）：历史回看靠原版聊天记录（按 T 能翻），
 * 独立的对话历史面板是后面的活。
 */
public class MeidoChatScreen extends Screen {

    /** 和服务端 {@code MeidoChatPayload.handle} 的截断长度保持一致。 */
    private static final int MAX_LEN = 240;

    private TextFieldWidget input;

    public MeidoChatScreen() {
        super(Text.literal(MeidoLocale.pick("跟她说一句话", "Say something to her")));
    }

    @Override
    protected void init() {
        this.input = new TextFieldWidget(this.textRenderer,
                this.width / 2 - 155, this.height / 2 - 10, 310, 18, this.title);
        this.input.setMaxLength(MAX_LEN);
        // ⚠️ Yarn 1.21.1 叫 setPlaceholder（setHint 是 1.21.4+ 才加的，javap 核实过）。
        this.input.setPlaceholder(Text.literal(MeidoLocale.pick("跟她说一句话，回车发送（Esc 取消）",
                "Say something to her, press Enter to send (Esc to cancel)"))
                .formatted(Formatting.GRAY));
        // ★ addSelectableChild + 手动 render —— 抄原版 ChatScreen 的路子，
        //   不用 addDrawableChild（那会让 super.render 再画一遍，叠两层文字阴影）。
        this.addSelectableChild(this.input);
        this.setInitialFocus(this.input);
    }

    /**
     * ★ 1.21 的默认 renderBackground 会给背后世界加**高斯模糊**（暂停菜单那种效果）——
     * 绯色实测「按 G 界面很模糊」就是它。对话框要像聊天栏一样轻，
     * 改成半透明黑渐变，世界原样透出来，一点不糊。
     */
    @Override
    public void renderBackground(DrawContext context, int mouseX, int mouseY, float delta) {
        context.fillGradient(0, 0, this.width, this.height, 0x66000000, 0xB3000000);
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        this.renderBackground(context, mouseX, mouseY, delta);        context.drawCenteredTextWithShadow(this.textRenderer, this.title,
                this.width / 2, this.height / 2 - 32, 0xFFFFFF);
        this.input.render(context, mouseX, mouseY, delta);
        super.render(context, mouseX, mouseY, delta);
    }

    //? if >=1.21.11 {
    /**
     * 1.21.11：{@code Screen.keyPressed} 的参数从三个 int 换成了 {@link KeyInput} 记录
     * （{@code key()} / {@code scancode()} / {@code modifiers()}），逻辑不变。
     */
    @Override
    public boolean keyPressed(KeyInput input) {
        if (input.key() == GLFW.GLFW_KEY_ENTER || input.key() == GLFW.GLFW_KEY_KP_ENTER) {
            String text = this.input.getText().strip();
            if (!text.isEmpty()) {
                ClientPlayNetworking.send(new MeidoChatPayload(text));
            }
            this.close();
            return true;
        }
        return super.keyPressed(input);
    }
    //?} else {
    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
            String text = this.input.getText().strip();
            if (!text.isEmpty()) {
                ClientPlayNetworking.send(new MeidoChatPayload(text));
            }
            this.close();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }
    //?}

    /** 打字的时候不该暂停游戏 —— 她可能还在你身后干活呢。 */
    @Override
    public boolean shouldPause() {
        return false;
    }

    /** 输入框拿到焦点时，别的按键绑定（比如原版聊天键）别抢。 */
    @Override
    public boolean shouldCloseOnEsc() {
        return true;
    }
}
