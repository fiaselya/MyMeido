package com.mymeido.registry;

import com.mymeido.MeidoConst;
import com.mymeido.entity.MeidoEntity;

import net.fabricmc.fabric.api.object.builder.v1.entity.FabricDefaultAttributeRegistry;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.SpawnGroup;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;

/**
 * 实体注册表。
 *
 * <p>两个必须成对出现的动作：
 * <ol>
 *   <li>把 EntityType 注册进 {@code Registries.ENTITY_TYPE}；</li>
 *   <li>用 {@code FabricDefaultAttributeRegistry} 挂上属性 ——
 *       漏了这步的典型症状是「实体能召唤出来，但一 tick 就崩」，
 *       因为 LivingEntity 拿不到 MAX_HEALTH 容器。</li>
 * </ol>
 *
 * <p>注意 SpawnGroup 用 {@code MISC}：女仆<b>不参与自然生成</b>，
 * 只能被玩家召唤出来。同时也意味着不需要（也不该有）刷怪蛋。
 */
public final class MyMeidoEntities {

    public static final EntityType<MeidoEntity> MEIDO = Registry.register(
            Registries.ENTITY_TYPE,
            MeidoConst.id("meido"),
            EntityType.Builder.create(MeidoEntity::new, SpawnGroup.MISC)
                    // 玩家同尺寸：宽 0.6、高 1.8、眼高 1.62
                    .dimensions(0.6f, 1.8f)
                    .eyeHeight(1.62f)
                    // 追踪范围给到跟玩家同档，免得远一点就开始抽搐
                    .maxTrackingRange(10)
                    // 1.21.11：build 要 RegistryKey，不再收 String（javap 实锤）。
                    //? if >=1.21.11 {
                    .build(RegistryKey.of(RegistryKeys.ENTITY_TYPE, MeidoConst.id("meido")))
                    //?} else {
                    /*.build("meido")
                    *///?}
    );

    private MyMeidoEntities() {
    }

    /** 由主入口调用。 */
    public static void register() {
        FabricDefaultAttributeRegistry.register(MEIDO, MeidoEntity.createMeidoAttributes());
    }
}
