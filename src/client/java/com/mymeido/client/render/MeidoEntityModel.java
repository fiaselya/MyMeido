package com.mymeido.client.render;

import com.mymeido.entity.MeidoEmotion;

//? if >=1.21.11 {
import net.minecraft.client.model.ModelPart;
import net.minecraft.client.render.entity.model.BipedEntityModel;

/**
 * 女仆模型 = 原版玩家模型（wide/粗手臂）+ 情绪头部动作。
 *
 * <p>1.21.2+ 渲染状态重构：模型泛型从 {@code <实体>} 换成 {@code <RenderState>}，
 * {@code setAngles(entity, limbAngle, ...)} 整个签名换成
 * {@code setAngles(state)}。情绪数据不再直接来自实体，而是渲染器每帧
 * {@code updateRenderState} 时拷进 {@link MeidoEntityRenderState} 的那份拷贝
 * —— 头部动作的算法与 1.21.1 版完全一致，只是数据来源换了个对象。
 *
 * <p>为什么用「继承模型」而不是继承渲染器来加头部动作：
 * {@code LivingEntityRenderer.render} 里的顺序是
 * {@code model.setAngles(...) → model.render(...)}，
 * 也就是说在渲染器里改骨骼，改完立刻会被 setAngles 覆盖掉。
 * 想不写 Mixin 又要拿到「setAngles 之后、render 之前」这个时机，
 * 唯一干净的位置就是 setAngles 自己的尾巴 —— 所以动作写在这里。
 *
 * <p>⚠️ <b>「下一帧会被覆盖回去」对 pitch / yaw 成立，对 roll 不一定成立</b> ——
 * 1.21.1 的 {@code BipedEntityModel.setAngles} 从不写 {@code head.roll}（javap -c 核实）。
 * 1.21.11 同样保险起见：先无条件归零再叠加，绝不依赖「原版会帮我擦掉」。
 * <b>结论：往共享 ModelPart 上写东西，必须先确认「下一帧谁来把它擦掉」。</b>
 */
public class MeidoEntityModel extends BipedEntityModel<MeidoEntityRenderState> {

    /** 情绪起来时的淡入占比（前 12%）。 */
    private static final float FADE_IN = 0.12f;
    /** 情绪结束前的淡出占比（后 20%）。 */
    private static final float FADE_OUT = 0.20f;

    public MeidoEntityModel(ModelPart root) {
        super(root);
    }

    @Override
    public void setAngles(MeidoEntityRenderState state) {
        super.setAngles(state);

        // ★ 必须【无条件】先把 head.roll 归零 —— 详见类注释。
        //   原版 setAngles 对 head 只写 yaw / pitch / pivotY，从来不碰 roll；
        //   只加不清的累计偏移就是「头疯狂转圈 + 最后停在歪头」的全部成因。
        this.head.roll = 0.0f;

        MeidoEmotion emotion = state.emotion;
        if (emotion == MeidoEmotion.NEUTRAL) {
            return;
        }
        int total = emotion.durationTicks();
        if (total <= 0) {
            return;
        }

        // 进度 0..1。剩余 tick 由服务端同步过来，于是淡出是天然同步的。
        float progress = clamp((total - state.emotionLeft) / (float) total);
        float envelope = envelope(progress);

        this.head.pitch += emotion.headPitch(progress) * envelope;
        this.head.yaw += emotion.headYaw(progress) * envelope;
        this.head.roll += emotion.headRoll(progress) * envelope;
    }

    /** 两头收力，中间全力 —— 避免情绪一开始就"咔"地弹到最大角度。 */
    private static float envelope(float progress) {
        float fadeIn = clamp(progress / FADE_IN);
        float fadeOut = clamp((1.0f - progress) / FADE_OUT);
        return Math.min(fadeIn, fadeOut);
    }

    private static float clamp(float value) {
        if (value < 0.0f) {
            return 0.0f;
        }
        return value > 1.0f ? 1.0f : value;
    }
}
//?} else {
import com.mymeido.entity.MeidoEntity;

import net.minecraft.client.model.ModelPart;
import net.minecraft.client.render.entity.model.BipedEntityModel;

/**
 * 女仆模型 = 原版玩家模型（wide/粗手臂）+ 情绪头部动作。
 *
 * <p>为什么用「继承模型」而不是继承渲染器来加头部动作：
 * {@code LivingEntityRenderer.render} 里的顺序是
 * {@code model.setAngles(...) → model.render(...)}，
 * 也就是说在渲染器里改骨骼，改完立刻会被 setAngles 覆盖掉。
 * 想不写 Mixin 又要拿到「setAngles 之后、render 之前」这个时机，
 * 唯一干净的位置就是 setAngles 自己的尾巴 —— 所以动作写在这里。
 *
 * <p>注意模型骨骼是原版玩家那套<b>共享</b>的 ModelPart，我们只在每次绘制前叠加偏移。
 *
 * <p>⚠️ <b>但「下一帧会被覆盖回去」这句话只对 pitch / yaw 成立，对 roll 不成立</b> ——
 * 原版 {@code BipedEntityModel.setAngles} 压根不写 {@code head.roll}
 * （javap -c 核实），所以 roll 的偏移<b>不会被任何人清掉</b>。
 * 详细推导见 {@link #setAngles} 里那行「无条件归零」的注释。
 * <b>结论：往共享 ModelPart 上写东西，必须先确认「下一帧谁来把它擦掉」。</b>
 */
public class MeidoEntityModel extends BipedEntityModel<MeidoEntity> {

    /** 情绪起来时的淡入占比（前 12%）。 */
    private static final float FADE_IN = 0.12f;
    /** 情绪结束前的淡出占比（后 20%）。 */
    private static final float FADE_OUT = 0.20f;

    public MeidoEntityModel(ModelPart root) {
        super(root);
    }

    @Override
    public void setAngles(MeidoEntity entity, float limbAngle, float limbDistance,
                          float animationProgress, float headYaw, float headPitch) {
        super.setAngles(entity, limbAngle, limbDistance, animationProgress, headYaw, headPitch);

        // ★★★ 必须【无条件】先把 head.roll 归零 —— 这一行是修「头疯狂转圈 + 最后停在歪头」的。
        //
        // 原版 BipedEntityModel.setAngles 对 head 只写 yaw / pitch / pivotY，
        // ★【从来不碰 head.roll】。
        //   javap -c 核实：整个 setAngles 字节码里对 ModelPart.roll 的 putfield
        //   只出现在 rightArm / leftArm / rightLeg / leftLeg 上，head 一次都没有。
        //
        // 而 this.head 是 EntityModelLayers.PLAYER 的【共享】ModelPart（本类开头那段注释也提过）。
        // 于是只要写 `head.roll += x` 就是【只加不清、永不归零】：
        //   · 情绪进行中：每渲染帧加一点 → 头绕前后轴越转越多 → 看起来就是头在转圈；
        //   · 情绪结束：下面 NEUTRAL 分支直接 return，残留的角度留在模型上 → 头永久歪着。
        //
        // 量级也对得上：SHY 的 headRoll 是常数 0.05，55 tick 在 120fps 下约 330 帧
        // → 累计 ≈ 16.5 弧度 ≈ 2.6 圈。正好是"转了好几圈"。
        //
        // 所以 roll 用「先归零、再叠加」，而不是累加；
        // pitch / yaw 可以放心用 += —— super 每帧都会重新赋值，天然不会累积。
        this.head.roll = 0.0f;

        MeidoEmotion emotion = entity.getEmotion();
        if (emotion == MeidoEmotion.NEUTRAL) {
            return;
        }
        int total = emotion.durationTicks();
        if (total <= 0) {
            return;
        }

        // 进度 0..1。剩余 tick 由服务端同步过来，于是淡出是天然同步的。
        float progress = clamp((total - entity.getEmotionLeft()) / (float) total);
        float envelope = envelope(progress);

        this.head.pitch += emotion.headPitch(progress) * envelope;
        this.head.yaw += emotion.headYaw(progress) * envelope;
        this.head.roll += emotion.headRoll(progress) * envelope;
    }

    /** 两头收力，中间全力 —— 避免情绪一开始就"咔"地弹到最大角度。 */
    private static float envelope(float progress) {
        float fadeIn = clamp(progress / FADE_IN);
        float fadeOut = clamp((1.0f - progress) / FADE_OUT);
        return Math.min(fadeIn, fadeOut);
    }

    private static float clamp(float value) {
        if (value < 0.0f) {
            return 0.0f;
        }
        return value > 1.0f ? 1.0f : value;
    }
}
//?}
