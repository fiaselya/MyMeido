package com.mymeido;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.mymeido.ai.MeidoAiConfig;
import com.mymeido.ai.MeidoPersona;
import com.mymeido.command.MeidoCommand;
import com.mymeido.entity.MeidoSkin;
import com.mymeido.net.MeidoChatPayload;
import com.mymeido.net.MeidoHistoryRequestPayload;
import com.mymeido.item.MeidoItems;
import com.mymeido.mode.MeidoModeRegistry;
import com.mymeido.net.MeidoModeSelectPayload;
import com.mymeido.net.MeidoOnboarding;
import com.mymeido.net.MeidoPlayerChat;
import com.mymeido.registry.MyMeidoEntities;

import net.fabricmc.api.ModInitializer;

/**
 * MyMeido —— 在 Minecraft 里养你自己的女仆。
 *
 * <p>定稿：MC 1.21.1 + Fabric（依据见工作区里的《设计定稿与待办.md》A1）。
 *
 * <p>这里是「公共端」入口，服务端和客户端都会执行。
 * 要在两个端都存在的东西放这里注册：实体、物品、方块、指令、网络包。
 * 只在客户端存在的东西（渲染器、皮肤贴图、粒子）放
 * {@link com.mymeido.client.MyMeidoClient}，那边引用了客户端专有类，
 * 混进来会让专用服务器的编译直接失败。
 */
public class MyMeido implements ModInitializer {
    public static final String MOD_ID = MeidoConst.MOD_ID;
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        // ★ 顺序有讲究：
        //   1) 物品与组件必须先注册 —— 后面任何一处引用 MeidoItems 都会触发它的静态初始化，
        //      而「注册」这件事本身必须在 mod 初始化阶段内完成；
        //   2) 模式清单紧跟着加载 —— 实体读档时要靠它判断「这个模式 id 还认不认识」；
        //   3) 网络包注册与实体、指令一样，都是「两端都要有」的东西。
        MeidoItems.register();
        MeidoAiConfig.load();          // 对话配置（chat/api.txt；没有就生成带中文说明的模板）
        MeidoPersona.loadAll(MeidoSkin.ids()); // 人设卡（config/mymeido/personas/<skinId>.txt；没有就生成模板）
        MeidoModeRegistry.load();
        MyMeidoEntities.register();
        MeidoModeSelectPayload.register();
        MeidoChatPayload.register();   // 对话输入框（G 键）发来的玩家台词
        MeidoHistoryRequestPayload.register(); // 对话历史面板（H 键）的「要数据」请求
        MeidoPlayerChat.register();    // 原生聊天栏（T 键）也接管 —— 多人 @ 判定在这层统一做
        MeidoOnboarding.register();    // 加入世界发「女仆契约」+ 把「选女仆」的数字输入接上
        MeidoCommand.register();
        // ★ 2026-09-20 大改：mod 不再绑定 / 拉起任何模型（本地 llama-server 那套已删）。
        //   要 AI 对话就玩家自己把 API 填进 chat/api.txt；不填 = 只有自带台词。
        LOGGER.info("[mymeido] 公共端初始化完成：实体 mymeido:meido + 指令闹钟 + /mymeido 指令已就位");
    }
}
