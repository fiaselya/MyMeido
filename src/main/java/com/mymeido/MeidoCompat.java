package com.mymeido;

//? if >=1.21.11 {
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.EquippableComponent;
import net.minecraft.command.permission.Permission;
import net.minecraft.command.permission.PermissionLevel;
//?} else {
/*import net.minecraft.item.Equipment;
*///?}

import net.minecraft.entity.Entity;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.attribute.EntityAttribute;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundEvent;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

/**
 * 1.21.1 / 1.21.11 两个版本的 MC API 差异<b>收口在这一处</b>。
 *
 * <p>Stonecutter 的 {@code //? if} 条件只允许出现在这个文件里（存档视图那套大分支除外，
 * 见 MeidoEntity / MeidoMission / MeidoInventory 的存档方法）；其余代码一律调用
 * 这些静态方法，保证同一份源码对两个版本同时成立。加新版本时只改这里。
 */
public final class MeidoCompat {

    private MeidoCompat() {
    }

    /** 实体所在世界。1.21.1 = {@code getWorld()}；1.21.11+ 改名 {@code getEntityWorld()}。 */
    public static World worldOf(Entity e) {
        //? if >=1.21.11 {
        return e.getEntityWorld();
        //?} else {
        /*return e.getWorld();
        *///?}
    }

    /** 实体坐标。1.21.1 = {@code getPos()}；1.21.11+ 改名 {@code getEntityPos()}。 */
    public static Vec3d posOf(Entity e) {
        //? if >=1.21.11 {
        return e.getEntityPos();
        //?} else {
        /*return e.getPos();
        *///?}
    }

    /**
     * 实体所在的 {@link ServerWorld}。只在服务端实体上调用（调用方都先 instanceof 判过）。
     * 两版都没有对任意实体的同名方法，只能强转 —— 1.21.1 是 {@code (ServerWorld) getWorld()}，
     * 1.21.11 是 {@code (ServerWorld) getEntityWorld()}。
     */
    public static ServerWorld serverWorldOf(Entity e) {
        //? if >=1.21.11 {
        return (ServerWorld) e.getEntityWorld();
        //?} else {
        /*return (ServerWorld) e.getWorld();
        *///?}
    }

    /** 实体所属的服务端。1.21.1 Entity 有 {@code getServer()}；1.21.11 只能绕道世界。 */
    public static MinecraftServer serverOf(Entity e) {
        //? if >=1.21.11 {
        return e.getEntityWorld().getServer();
        //?} else {
        /*return e.getServer();
        *///?}
    }

    /**
     * 吃东西音效。1.21.1 常量就是 {@code SoundEvent}；1.21.11 起常量全变成
     * {@code RegistryEntry.Reference<SoundEvent>}（javap 实锤），要 {@code .value()} 拆包。
     */
    public static SoundEvent eatSound() {
        //? if >=1.21.11 {
        return SoundEvents.ENTITY_GENERIC_EAT.value();
        //?} else {
        /*return SoundEvents.ENTITY_GENERIC_EAT;
        *///?}
    }

    /**
     * 近战攻击。1.21.5 起 {@code tryAttack} 要 {@code ServerWorld}（javap 实锤）。
     * 只在双方都是服务端实体时调（Goal 里都满足）。
     */
    public static boolean tryAttack(MobEntity attacker, Entity target) {
        //? if >=1.21.11 {
        return attacker.tryAttack(serverWorldOf(attacker), target);
        //?} else {
        /*return attacker.tryAttack(target);
        *///?}
    }

    /**
     * 实体掉落物品。1.21.5 起 {@code dropStack} 要 {@code ServerWorld}（javap 实锤）。
     * 只在服务端调（所有调用点都在服务端路径上）。
     */
    public static void dropStack(Entity entity, ItemStack stack) {
        //? if >=1.21.11 {
        entity.dropStack(serverWorldOf(entity), stack);
        //?} else {
        /*entity.dropStack(stack);
        *///?}
    }

    /**
     * 这件物品「自己声明的装备槽」，不是装备返回 {@code null}。
     *
     * <p>1.21.1 是 {@code net.minecraft.item.Equipment} 接口（{@code instanceof} 判定）；
     * 1.21.11 换成数据组件 {@code DataComponentTypes.EQUIPPABLE}（{@code EquippableComponent.slot()}）。
     * 只给「槽位是什么」这一件事，调用方自行决定要不要过滤护甲槽。
     */
    public static EquipmentSlot equipmentSlotOf(ItemStack stack) {
        //? if >=1.21.11 {
        EquippableComponent equippable = stack.get(DataComponentTypes.EQUIPPABLE);
        return equippable == null ? null : equippable.slot();
        //?} else {
        /*return stack.getItem() instanceof Equipment equipment ? equipment.getSlotType() : null;
        *///?}
    }

    /**
     * 指令权限：OP 等级 ≥ 2。
     * 1.21.1 是 {@code hasPermissionLevel(2)}；1.21.11 权限系统重做
     * （{@code PermissionPredicate / Permission.Level}，javap 实锤），等级 2 = GAMEMASTERS。
     */
    public static boolean hasOpLevel2(ServerCommandSource source) {
        //? if >=1.21.11 {
        return source.getPermissions()
                .hasPermission(new Permission.Level(PermissionLevel.GAMEMASTERS));
        //?} else {
        /*return source.hasPermissionLevel(2);
        *///?}
    }

    // ---- 属性常量：1.21.2 起 EntityAttributes 去掉了 GENERIC_ 前缀 ----

    //? if >=1.21.11 {
    /** 移速（0.5 = 村民量级，见 MeidoEntity.DESIGN_MOVEMENT_SPEED）。 */
    public static final RegistryEntry<EntityAttribute> ATTR_MOVEMENT_SPEED = EntityAttributes.MOVEMENT_SPEED;
    /** 血量上限。 */
    public static final RegistryEntry<EntityAttribute> ATTR_MAX_HEALTH = EntityAttributes.MAX_HEALTH;
    /** 近战攻击基线。 */
    public static final RegistryEntry<EntityAttribute> ATTR_ATTACK_DAMAGE = EntityAttributes.ATTACK_DAMAGE;
    /** 索敌范围。 */
    public static final RegistryEntry<EntityAttribute> ATTR_FOLLOW_RANGE = EntityAttributes.FOLLOW_RANGE;
    /** 台阶高度。 */
    public static final RegistryEntry<EntityAttribute> ATTR_STEP_HEIGHT = EntityAttributes.STEP_HEIGHT;
    //?} else {
    /*/^* 移速（0.5 = 村民量级，见 MeidoEntity.DESIGN_MOVEMENT_SPEED）。 ^/
    public static final RegistryEntry<EntityAttribute> ATTR_MOVEMENT_SPEED = EntityAttributes.GENERIC_MOVEMENT_SPEED;
    /^* 血量上限。 ^/
    public static final RegistryEntry<EntityAttribute> ATTR_MAX_HEALTH = EntityAttributes.GENERIC_MAX_HEALTH;
    /^* 近战攻击基线。 ^/
    public static final RegistryEntry<EntityAttribute> ATTR_ATTACK_DAMAGE = EntityAttributes.GENERIC_ATTACK_DAMAGE;
    /^* 索敌范围。 ^/
    public static final RegistryEntry<EntityAttribute> ATTR_FOLLOW_RANGE = EntityAttributes.GENERIC_FOLLOW_RANGE;
    /^* 台阶高度。 ^/
    public static final RegistryEntry<EntityAttribute> ATTR_STEP_HEIGHT = EntityAttributes.GENERIC_STEP_HEIGHT;
    *///?}
}
