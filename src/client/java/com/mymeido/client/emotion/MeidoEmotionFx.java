package com.mymeido.client.emotion;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.mymeido.entity.MeidoEmotion;
import com.mymeido.entity.MeidoEntity;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.random.Random;

/**
 * 情绪的表现层（客户端）。
 *
 * <p>分工：
 * <ul>
 *   <li>头部动作 —— 由 {@code MeidoEntityModel} 在渲染时算，天然是连续的；</li>
 *   <li>粒子 —— 由这里在 tick 里按<b>固定间隔</b>放几个，是离散的爆发。</li>
 * </ul>
 *
 * <p>关键性能约束：<b>绝不是每 tick 每情绪都放粒子</b>。
 * 一次心跳（2 tick、约 10 个粒子）+ 情绪总长 45~80 tick，
 * 平摊下来每秒不到 10 个，跟一个村民冒爱心同量级。
 *
 * <p>搜索范围只在玩家周围 48 格 —— 远处的女仆不放粒子，反正也看不见。
 */
public final class MeidoEmotionFx {

    /** 两次放粒子之间隔多少 tick。 */
    private static final int INTERVAL = 3;

    /** 只处理玩家周围这个半径内的女仆。 */
    private static final double RANGE = 48.0;

    /** 实体 id -> 剩余冷却 tick。 */
    private static final Map<Integer, Integer> COOLDOWNS = new HashMap<>();

    private MeidoEmotionFx() {
    }

    public static void init() {
        ClientTickEvents.END_CLIENT_TICK.register(MeidoEmotionFx::onEndTick);
    }

    private static void onEndTick(MinecraftClient client) {
        if (client.world == null || client.player == null) {
            COOLDOWNS.clear();
            return;
        }

        Box box = client.player.getBoundingBox().expand(RANGE);
        List<MeidoEntity> meidos = client.world.getEntitiesByClass(MeidoEntity.class, box, entity -> true);
        for (MeidoEntity meido : meidos) {
            play(client, meido);
        }
    }

    private static void play(MinecraftClient client, MeidoEntity meido) {
        MeidoEmotion emotion = meido.getEmotion();
        int key = meido.getId();

        if (emotion == MeidoEmotion.NEUTRAL || !emotion.hasParticles() || meido.getEmotionLeft() <= 0) {
            COOLDOWNS.remove(key);
            return;
        }

        int cooldown = COOLDOWNS.getOrDefault(key, 0);
        if (cooldown > 0) {
            COOLDOWNS.put(key, cooldown - 1);
            return;
        }
        COOLDOWNS.put(key, INTERVAL);

        Vec3d origin = meido.getEyePos().add(0.0, 0.18, 0.0);
        Random random = meido.getRandom();
        for (int i = 0; i < emotion.particleCount(); i++) {
            double dx = (random.nextDouble() - 0.5) * 0.7;
            double dy = (random.nextDouble() - 0.5) * 0.35;
            double dz = (random.nextDouble() - 0.5) * 0.7;
            client.world.addParticle(
                    emotion.particle(),
                    origin.x + dx, origin.y + dy, origin.z + dz,
                    dx * 0.12, 0.015, dz * 0.12);
        }
    }
}
