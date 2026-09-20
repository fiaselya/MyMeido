package com.mymeido.client;

import com.mymeido.MeidoConst;
import com.mymeido.MeidoLocale;
import com.mymeido.MyMeido;

import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.fabricmc.fabric.api.resource.SimpleSynchronousResourceReloadListener;
import net.minecraft.client.MinecraftClient;
import net.minecraft.resource.ResourceManager;
import net.minecraft.resource.ResourceType;
import net.minecraft.util.Identifier;

/**
 * 把「游戏当前语言」喂给 {@link MeidoLocale}（公共端）。
 *
 * <p>公共端（{@code src/main}）编译期看不到 {@code net.minecraft.client.*} ——
 * 引用了它，专用服务器的类加载会崩。所以「读客户端语言」这件事只能放在
 * {@code src/client}，由这里主动上报。公共端那边留了一条 {@code options.txt} 兜底，
 * 保证服务端侧也有个合理的判据。
 *
 * <p>★ 为什么挂在<b>资源重载</b>上：玩家在「选项 → 语言」里换语言时，
 * 客户端会重载一次资源 —— 这正是 F3+T 走的那条路（皮肤池也挂在同一个钩子上）。
 * 挂这里，玩家换了语言按一下 F3+T 就跟着变，不用重启游戏。
 */
public final class MeidoLocaleClient {

    private MeidoLocaleClient() {
    }

    public static void init() {
        refresh();
        ResourceManagerHelper.get(ResourceType.CLIENT_RESOURCES).registerReloadListener(
                new SimpleSynchronousResourceReloadListener() {
                    @Override
                    public Identifier getFabricId() {
                        return MeidoConst.id("locale");
                    }

                    @Override
                    public void reload(ResourceManager manager) {
                        refresh();
                    }
                });
    }

    /**
     * 读一次当前语言并上报。
     *
     * <p>包裹在 {@code Throwable} 里：语言判定只是「显示成哪种文字」，
     * 绝不该因为它把客户端启动搞崩。
     */
    public static void refresh() {
        try {
            MinecraftClient client = MinecraftClient.getInstance();
            if (client == null || client.getLanguageManager() == null) {
                return;
            }
            MeidoLocale.setClientLanguage(client.getLanguageManager().getLanguage());
        } catch (Throwable t) {
            MyMeido.LOGGER.warn("[mymeido] could not read the client language: {}", t.toString());
        }
    }
}
