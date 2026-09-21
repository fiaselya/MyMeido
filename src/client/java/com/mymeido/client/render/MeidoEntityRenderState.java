package com.mymeido.client.render;

import com.mymeido.entity.MeidoEmotion;
import com.mymeido.entity.MeidoSkin;

//? if >=1.21.11 {
import net.minecraft.client.render.entity.state.BipedEntityRenderState;

/**
 * 女仆的渲染状态（1.21.11+ 才有渲染状态重构）。
 *
 * <p>1.21.2 起渲染管线改成「每帧把实体数据拷进一个可复用的 RenderState」，
 * 模型 {@code setAngles} 与渲染器 {@code getTexture} 拿到的都不再是实体本身，
 * 而是这个状态对象。所以要把情绪表现与皮肤贴图需要的三个字段
 * （情绪种类 / 情绪剩余 tick / 皮肤）从 {@code MeidoEntity} 拷进来。
 *
 * <p>对应关系：
 * <ul>
 *   <li>{@link #emotion} / {@link #emotionLeft} —— 给 {@code MeidoEntityModel.setAngles}
 *       算头部动作（逻辑原样照搬 1.21.1 那版，只是数据来源从实体换成状态）；</li>
 *   <li>{@link #skin} —— 给 {@code MeidoEntityRenderer.getTexture} 查皮肤贴图。</li>
 * </ul>
 */
public class MeidoEntityRenderState extends BipedEntityRenderState {

    /** 当前情绪（服务端同步到实体的那份，这里再拷一份进渲染状态）。 */
    public MeidoEmotion emotion = MeidoEmotion.NEUTRAL;

    /** 情绪剩余 tick —— 淡出进度靠它和 {@link MeidoEmotion#durationTicks()} 相减得到。 */
    public int emotionLeft;

    /** 皮肤（贴图按它查表，绝不能在这里读文件 —— 那是 MeidoSkinManager 的活儿）。 */
    public MeidoSkin skin;
}
//?}
