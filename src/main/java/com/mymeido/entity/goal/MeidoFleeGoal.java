package com.mymeido.entity.goal;

import java.util.EnumSet;

import com.mymeido.entity.MeidoEntity;

import net.minecraft.entity.ai.goal.Goal;
import net.minecraft.util.math.Vec3d;

/**
 * 被玩家打后的<b>逃跑</b>（A4-4）：中档立刻逃、高档扛到残血逃、低档没武器逃。
 *
 * <p>方向 = 从「打她的人」指向她自己的反方向，一口气跑 8 格；
 * 5 秒后状态自然过期（{@code MeidoEntity.isFleeing()}），游走 Goal 接手。
 * 逃跑期间她抢走 MOVE，战斗（Guard）排在同优先级之后进不来 ——
 * 她在逃命，没空打怪。
 */
public class MeidoFleeGoal extends Goal {

    /** 逃跑距离（格）。 */
    private static final double FLEE_DIST = 8.0;

    /** 逃命移速：比游走快、比玩家冲刺慢 —— 追得上，但不体面。 */
    private static final double FLEE_SPEED = 1.1;

    private final MeidoEntity meido;
    private int repathTimer;

    public MeidoFleeGoal(MeidoEntity meido) {
        this.meido = meido;
        this.setControls(EnumSet.of(Goal.Control.MOVE, Goal.Control.LOOK));
    }

    @Override
    public boolean canStart() {
        return this.meido.isFleeing();
    }

    @Override
    public boolean shouldContinue() {
        return this.meido.isFleeing();
    }

    @Override
    public void start() {
        this.repathTimer = 0;
    }

    @Override
    public void stop() {
        this.meido.getNavigation().stop();
    }

    @Override
    public void tick() {
        Vec3d away = this.meido.getPos().subtract(this.meido.fleeFromPos());
        away = new Vec3d(away.x, 0, away.z);
        if (away.lengthSquared() < 1.0E-4) {
            away = new Vec3d(1, 0, 0);   // 贴脸打的：随便选个方向跑。
        }
        Vec3d dest = this.meido.getPos().add(away.normalize().multiply(FLEE_DIST));
        if (this.repathTimer-- <= 0) {
            this.repathTimer = 10;
            this.meido.getNavigation().startMovingTo(dest.x, this.meido.getY(), dest.z, FLEE_SPEED);
        }
    }
}
