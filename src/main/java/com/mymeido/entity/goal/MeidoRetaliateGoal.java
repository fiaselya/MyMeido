package com.mymeido.entity.goal;

import com.mymeido.MeidoCompat;
import java.util.EnumSet;

import com.mymeido.entity.MeidoEntity;

import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.ai.goal.Goal;
import net.minecraft.util.Hand;

/**
 * 被玩家打后的<b>还手</b>（A4-4）：好感度「低」且身上有武器才触发。
 *
 * <p>和守卫战斗（{@link MeidoGuardGoal}）的区别：还手是<b>急眼了</b>——
 * 不搞打带跑，站桩输出；目标是打过她的那个玩家；10 秒没人再挑事就收手
 * （武器收回背包，好感度也回不到从前）。武器在进入还手状态时由
 * {@code MeidoEntity} 已经摸到手上（低档「有武器」的判定包含背包）。
 */
public class MeidoRetaliateGoal extends Goal {

    /** 近战够得着 = 距离平方 ≤ 3.2。 */
    private static final double ATTACK_REACH_SQUARED = 3.2;

    /** 还手也是每秒一刀（原版近战无冷却，节奏自己数拍子）。 */
    private static final int ATTACK_INTERVAL = 20;

    private final MeidoEntity meido;
    private int attackCooldown;
    private int repathTimer;

    public MeidoRetaliateGoal(MeidoEntity meido) {
        this.meido = meido;
        this.setControls(EnumSet.of(Goal.Control.MOVE, Goal.Control.LOOK));
    }

    @Override
    public boolean canStart() {
        return this.meido.isRetaliating();
    }

    @Override
    public boolean shouldContinue() {
        LivingEntity target = this.meido.retaliateTarget();
        return this.meido.isRetaliating()
                && target != null && target.isAlive() && !target.isRemoved();
    }

    @Override
    public void start() {
        this.attackCooldown = 0;
        this.repathTimer = 0;
    }

    @Override
    public void stop() {
        this.meido.clearRetaliation();
        this.meido.getNavigation().stop();
    }

    @Override
    public void tick() {
        LivingEntity target = this.meido.retaliateTarget();
        if (target == null) {
            return;
        }
        this.meido.getLookControl().lookAt(target, 30.0f, 30.0f);
        double distanceSquared = this.meido.squaredDistanceTo(target);
        if (distanceSquared > ATTACK_REACH_SQUARED) {
            if (this.repathTimer-- <= 0) {
                this.repathTimer = 10;
                this.meido.getNavigation().startMovingTo(
                        target.getX(), target.getY(), target.getZ(), 0.8);
            }
            return;
        }
        // 站桩输出：急眼的打法没有拉扯。
        this.meido.getNavigation().stop();
        this.meido.setVelocity(0.0, this.meido.getVelocity().y, 0.0);
        if (this.attackCooldown > 0) {
            this.attackCooldown--;
            return;
        }
        this.attackCooldown = ATTACK_INTERVAL;
        this.meido.swingHand(Hand.MAIN_HAND);
        // 1.21.5 起 tryAttack 要 ServerWorld —— 差异收口在 MeidoCompat。
        MeidoCompat.tryAttack(this.meido, target);
    }
}
