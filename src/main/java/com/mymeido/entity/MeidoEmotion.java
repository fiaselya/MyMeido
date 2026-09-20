package com.mymeido.entity;

import com.mymeido.MeidoLocale;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.particle.ParticleEffect;
import net.minecraft.particle.ParticleTypes;

/**
 * 女仆情绪。
 *
 * <p>一期只做「表现层」：一次情绪 = 持续若干 tick 的粒子爆发 + 头部微动作，
 * 播完自动回落到 {@link #NEUTRAL}。<b>不接 LLM</b>，只由指令或右键交互触发。
 *
 * <p>为什么头部动作的数学写在这里、而不是写在渲染器里：
 * 主源集（服务端也要编译）拿不到 {@code net.minecraft.client.model.ModelPart} 这类客户端类。
 * 所以这里只产出「纯数字」（弧度偏移），客户端源码集里的
 * {@code MeidoEntityModel} 负责把它们写进模型骨骼。
 *
 * <p>性能底线：粒子只在情绪存在期间、按固定间隔放几个，绝不每 tick 刷。
 */
public enum MeidoEmotion {

    NEUTRAL("neutral", "平静", "Calm", 0, null, 0),
    HAPPY("happy", "开心", "Happy", 50, ParticleTypes.HAPPY_VILLAGER, 2),
    LOVE("love", "心动", "Smitten", 60, ParticleTypes.HEART, 3),
    SHY("shy", "害羞", "Shy", 55, ParticleTypes.HAPPY_VILLAGER, 1),
    ANGRY("angry", "生气", "Angry", 45, ParticleTypes.ANGRY_VILLAGER, 1),
    SAD("sad", "难过", "Sad", 70, ParticleTypes.SPLASH, 1),
    SURPRISED("surprised", "惊讶", "Surprised", 30, ParticleTypes.NOTE, 2),
    THINKING("thinking", "思考", "Thinking", 70, ParticleTypes.CRIT, 1),
    TIRED("tired", "疲倦", "Tired", 80, ParticleTypes.CLOUD, 1),
    CONFUSED("confused", "困惑", "Confused", 50, ParticleTypes.NOTE, 1);

    private final String id;
    private final String zhName;
    private final String enName;
    private final int durationTicks;
    private final ParticleEffect particle;
    private final int particleCount;

    MeidoEmotion(String id, String zhName, String enName, int durationTicks, ParticleEffect particle, int particleCount) {
        this.id = id;
        this.zhName = zhName;
        this.enName = enName;
        this.durationTicks = durationTicks;
        this.particle = particle;
        this.particleCount = particleCount;
    }

    /** 指令 / NBT 用的稳定字符串 id。 */
    public String getId() {
        return this.id;
    }

    /** 给玩家看的名字（随语言切换）。 */
    public String getDisplayName() {
        return MeidoLocale.pick(this.zhName, this.enName);
    }

    /** 这次情绪持续多少 tick（20 tick = 1 秒）。NEUTRAL 为 0。 */
    public int durationTicks() {
        return this.durationTicks;
    }

    public ParticleEffect particle() {
        return this.particle;
    }

    public int particleCount() {
        return this.particleCount;
    }

    public boolean hasParticles() {
        return this.particle != null && this.particleCount > 0;
    }

    /** 越界一律回落 NEUTRAL —— 网络数据坏了也不该崩。 */
    public static MeidoEmotion byOrdinal(int ordinal) {
        MeidoEmotion[] all = values();
        return ordinal >= 0 && ordinal < all.length ? all[ordinal] : NEUTRAL;
    }

    public static MeidoEmotion fromId(String id) {
        if (id != null) {
            for (MeidoEmotion e : values()) {
                if (e.id.equalsIgnoreCase(id) || e.name().equalsIgnoreCase(id)) {
                    return e;
                }
            }
        }
        return NEUTRAL;
    }

    /** 命令补全用。 */
    public static List<String> ids() {
        List<String> out = new ArrayList<>(values().length);
        for (MeidoEmotion e : values()) {
            out.add(e.id);
        }
        return out;
    }

    // ------------------------------------------------------------------
    // 头部姿态曲线。p = 情绪进度，0.0 表示刚开始，1.0 表示即将结束。
    // 返回值为弧度，直接叠加到模型头部的 pitch / yaw / roll 上。
    // ------------------------------------------------------------------

    /** 点头 / 低头为正，抬头为负。 */
    public float headPitch(float p) {
        return switch (this) {
            case HAPPY -> (float) Math.sin(p * Math.PI * 4) * 0.09f;
            case LOVE -> -(float) Math.sin(p * Math.PI * 2) * 0.05f;
            case SHY -> 0.30f + (float) Math.sin(p * Math.PI * 2) * 0.03f;
            case ANGRY -> (float) Math.sin(p * Math.PI * 14) * 0.045f;
            case SAD -> 0.38f + (float) Math.sin(p * Math.PI * 1.5) * 0.02f;
            case SURPRISED -> -0.18f;
            case THINKING -> 0.12f + (float) Math.sin(p * Math.PI * 2) * 0.04f;
            case TIRED -> 0.34f + (float) Math.sin(p * Math.PI * 3) * 0.06f;
            case CONFUSED -> (float) Math.sin(p * Math.PI * 3) * 0.05f;
            default -> 0.0f;
        };
    }

    /** 左右转头。 */
    public float headYaw(float p) {
        return switch (this) {
            case ANGRY -> (float) Math.sin(p * Math.PI * 12) * 0.07f;
            case CONFUSED -> (float) Math.sin(p * Math.PI * 4) * 0.18f;
            case SHY -> (float) Math.sin(p * Math.PI * 2) * 0.06f;
            case THINKING -> (float) Math.sin(p * Math.PI * 2) * 0.12f;
            default -> 0.0f;
        };
    }

    /** 歪头。 */
    public float headRoll(float p) {
        return switch (this) {
            case LOVE -> (float) Math.sin(p * Math.PI * 2) * 0.12f;
            case CONFUSED -> (float) Math.sin(p * Math.PI * 2) * 0.10f;
            case TIRED -> (float) Math.sin(p * Math.PI * 1.5) * 0.08f;
            case SHY -> 0.05f;
            default -> 0.0f;
        };
    }
}
