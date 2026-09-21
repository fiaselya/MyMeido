package com.mymeido.client;

import java.util.Optional;

import com.mymeido.MyMeido;
import com.mymeido.client.emotion.MeidoEmotionFx;
import com.mymeido.client.render.MeidoEntityRenderer;
import com.mymeido.client.screen.MeidoChatScreen;
import com.mymeido.client.screen.MeidoHistoryScreen;
import com.mymeido.client.screen.MeidoModeScreen;
import com.mymeido.client.skin.MeidoSkinManager;
import com.mymeido.entity.MeidoEntity;
import com.mymeido.item.CommandAlarmItem;
import com.mymeido.registry.MyMeidoEntities;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;

//? if >=1.21.11 {
import net.minecraft.util.Identifier;
//?}

import org.lwjgl.glfw.GLFW;

/**
 * 客户端入口。只有玩家客户端会执行这里。
 *
 * <p>客户端负责的是「看得见的部分」：渲染器、皮肤贴图、情绪粒子。
 * 注意这个类引用了 {@code net.minecraft.client.*}，所以它必须待在
 * {@code src/client} 源集里 —— 混进主源集会让专用服务器（dedicated server）
 * 的编译直接失败，而 entity 注册偏偏又必须在主源集，这就是
 * {@code splitEnvironmentSourceSets()} 存在的理由。
 */
public class MyMeidoClient implements ClientModInitializer {

    //? if >=1.21.11 {
    /**
     * 1.21.11：按键分类从裸字符串换成了 {@link KeyBinding.Category} 记录。
     * 标签翻译键由 id 推导：{@code key.category.<namespace>.<path>}
     * （javap -c 核实 {@code getLabel()} 走 {@code Identifier.toTranslationKey("key.category")}），
     * 所以语言文件里新增了 {@code key.category.mymeido.main} —— 与 1.21.1 用的
     * {@code key.category.mymeido} 并存，两份都留着互不干扰。
     */
    private static final KeyBinding.Category KEY_CATEGORY =
            KeyBinding.Category.create(Identifier.of("mymeido", "main"));
    //?}

    /**
     * 「和女仆说话」的按键（默认 {@code G}，可在按键设置里改）。
     * A2 定稿：★ 不占用原生的 T 聊天键 —— 我们要的是一个独立输入框，
     * 走 C2S 包直达服务端，不经过原版聊天命令解析。
     */
    public static final KeyBinding CHAT_KEY = KeyBindingHelper.registerKeyBinding(
            new KeyBinding("key.mymeido.chat",
                    InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_G,
                    //? if >=1.21.11 {
                    KEY_CATEGORY));
                    //?} else {
                    "key.category.mymeido"));
                    //?}

    /**
     * 「对话历史面板」按键（默认 {@code H}）。A2 定稿的另一半：
     * 原生聊天栏会滚走，这里按一下就能回看和她聊过什么（含记忆摘要）。
     */
    public static final KeyBinding HISTORY_KEY = KeyBindingHelper.registerKeyBinding(
            new KeyBinding("key.mymeido.history",
                    InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_H,
                    //? if >=1.21.11 {
                    KEY_CATEGORY));
                    //?} else {
                    "key.category.mymeido"));
                    //?}

    @Override
    public void onInitializeClient() {
        EntityRendererRegistry.register(MyMeidoEntities.MEIDO, MeidoEntityRenderer::new);
        MeidoSkinManager.init();
        // 语言上报：把游戏当前语言告诉公共端（它自己读不到客户端类），
        // 并挂在资源重载钩子上 —— 玩家在选项里换语言后按 F3+T 即跟着变。
        MeidoLocaleClient.init();
        MeidoEmotionFx.init();
        // 闹钟本体在主源集（专用服务器也要编译），那里一个 net.minecraft.client.* 都不能出现。
        // 所以两件「只有客户端才有意义」的事都从这儿装进去：
        //   1) 「右键空气开界面」
        //   2) 「就近那只女仆现在在干什么」—— 提示文本要显示这一行
        CommandAlarmItem.setPicker(() -> MinecraftClient.getInstance().setScreen(new MeidoModeScreen()));
        CommandAlarmItem.setNearbyStateReader(MyMeidoClient::nearbyMeidoModeId);

        // G 键 → 对话输入框。wasPressed 循环是按键绑定的标准吃法（一 tick 压两下也吃两下）。
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (CHAT_KEY.wasPressed()) {
                client.setScreen(new MeidoChatScreen());
            }
            while (HISTORY_KEY.wasPressed()) {
                client.setScreen(new MeidoHistoryScreen());
            }
        });
        MeidoHistoryScreen.registerS2C(); // 历史面板的 S2C 回包（C2S 请求包在主入口注册）

        MyMeido.LOGGER.info("[mymeido] client init done: renderer / skins / emotion fx / mode screen"
                + " / chat box loaded; language={}", com.mymeido.MeidoLocale.code());
    }

    /**
     * 「我手上这块闹钟对谁有效」那位女仆现在的模式 id，给闹钟的提示文本用。
     *
     * <p>读的是 {@link MeidoEntity#getSyncedModeId()}（实体同步过来的字段），
     * <b>不是</b>闹钟上的物品组件 —— 前者才是「她真在干什么」。
     * 这两份数据不同步正是「倒完东西她回游走了、闹钟上还写着丢弃物品」的成因。
     *
     * <p>★ 走 {@link CommandAlarmItem#targetOf}：绑了闹钟的找她本人，
     * 没绑的才退化成「最近的那位」—— 两端用同一个入口，提示里那句话才不会和实际派活对象错位。
     */
    private static Optional<String> nearbyMeidoModeId() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) {
            return Optional.empty();
        }
        MeidoEntity meido = CommandAlarmItem.targetOf(client.player);
        return meido == null ? Optional.empty() : Optional.of(meido.getSyncedModeId());
    }
}
