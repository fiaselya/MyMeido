package com.mymeido.entity;

import com.mymeido.MeidoCompat;

import com.mymeido.registry.MyMeidoEntities;

import net.minecraft.server.network.ServerPlayerEntity;

/**
 * 造一只女仆 —— <b>唯一一份</b>实现。
 *
 * <p>为什么单独抽出来：造她的步骤不是「new 一下就完事」，而是一条<b>必须保持同步</b>的配方 ——
 * 站到玩家那儿 → 面朝玩家 → 设皮肤（会顺带设默认名）→ 随机一个专属配色 → 放进世界。
 * 早先只有 {@code /mymeido summon} 一个调用点时，这几行写在指令里没问题；
 * 一旦出现第二个入口（{@code 女仆契约}道具），两边各抄一份就一定会漂移 ——
 * 比如以后加了「初始好感度」或「默认模式」，改了一处忘了另一处，玩家就会遇到
 * 「指令召出来的和道具召出来的不一样」这种极难排查的问题。
 *
 * <p>★ 注意 {@code setSkin} <b>必须</b>在 {@code spawnEntity} 之前调：它会改
 * {@code DataTracker} 里的同步字段，实体一旦进了世界，客户端可能已经先收到一份
 * 「默认皮肤」的初始同步包，之后再改就得靠额外的同步往返，中间那几 tick 会看到
 * 她闪一下变成另一个角色。
 */
public final class MeidoSpawn {

    private MeidoSpawn() {
    }

    /**
     * 在玩家脚下造一只指定皮肤的女仆，返回造好的实体。
     *
     * <p><b>只在服务端调</b>：{@code spawnEntity} 在客户端世界里没有意义。
     */
    public static MeidoEntity atPlayer(ServerPlayerEntity player, MeidoSkin skin) {
        MeidoEntity meido = new MeidoEntity(MyMeidoEntities.MEIDO, MeidoCompat.serverWorldOf(player));
        // 站在玩家身上、转身面对玩家（+180°）：她一出场就是「看着你」的，
        // 而不是背对你站着 —— 这一下是 galgame 观感的来源，别省。
        meido.refreshPositionAndAngles(
                player.getX(), player.getY(), player.getZ(),
                player.getYaw() + 180.0f, 0.0f);
        meido.setSkin(skin);          // 会顺带把默认名设成皮肤名
        meido.setMeidoColor(MeidoColor.random(meido.getRandom()));
        // ★ 「谁造的」= 造的那一刻站在她面前的这个人，两个创造入口（契约道具 / 指令）
        //   共用的这一份实现里记一次就够了。主动搭话只认这个玩家（设计定稿：
        //   「创建 npc 的玩家」），跟「这次活谁派的」是两回事。
        meido.setOwner(player.getUuid());
        MeidoCompat.serverWorldOf(player).spawnEntity(meido);
        return meido;
    }
}
