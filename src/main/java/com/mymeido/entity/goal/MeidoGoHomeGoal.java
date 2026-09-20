package com.mymeido.entity.goal;

import java.util.EnumSet;

import com.mymeido.entity.MeidoEntity;
import com.mymeido.mode.MeidoModeType;

import net.minecraft.block.BedBlock;
import net.minecraft.entity.ai.goal.Goal;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

/**
 * 天黑自己回家（A4 的「bind_home」后一半）。
 *
 * <h2>什么时候回</h2>
 *
 * <ul>
 *   <li>绑定过家（{@code MeidoMission.hasHome()}，绑床那次记的账）；</li>
 *   <li>现在是「能睡觉的时间」（原版上床窗口 12542~23459 tick，javap 自
 *       {@code TimeHelper} 的判据，跟玩家能上床的窗口一致）；</li>
 *   <li>当前在<b>游走</b>模式 —— 钓鱼/种植/守卫的活不抢，天塌下来先干完手里的；
 *       战斗（优先级更高）也不受影响，怪来了照样先打。</li>
 * </ul>
 *
 * <h2>怎么回</h2>
 *
 * <p>每 {@code REPATH_INTERVAL} tick 刷一次到床边的路径，走到床 2 格内站住
 * （床不是她的座位，站边上就行）。天亮（或家没了 / 模式换了）就散 ——
 * 游走 Goal 在更低优先级，她自然接着晃。床被挖了不报警也不清账：
 * 家还是那个家，也许你只是搬了个位置重新放（重新 bind 一次就更新）。
 */
public class MeidoGoHomeGoal extends Goal {

    /** 到床多近算「到家了」（距离平方，2 格）。 */
    private static final double ARRIVE_SQUARED = 4.0;

    /** 原版「可以睡觉」的时间窗（tick of day）。 */
    private static final long SLEEP_FROM = 12542L;
    private static final long SLEEP_TO = 23459L;

    /** 寻路重刷间隔（tick）。 */
    private static final int REPATH_INTERVAL = 10;

    /** 走路速度。回家不赶时间。 */
    private static final double TRAVEL_SPEED = 0.6;

    private final MeidoEntity meido;
    private int repathTimer;

    public MeidoGoHomeGoal(MeidoEntity meido) {
        this.meido = meido;
        this.setControls(EnumSet.of(Goal.Control.MOVE, Goal.Control.LOOK));
    }

    @Override
    public boolean canStart() {
        if (this.meido.isInteracting() || !this.isIdleAtHome()) {
            return false;
        }
        BlockPos home = this.meido.getMission().home();
        return home != null && this.isBedTime()
                && this.meido.squaredDistanceTo(Vec3d.ofCenter(home)) > ARRIVE_SQUARED;
    }

    @Override
    public void start() {
        this.repathTimer = 0;
    }

    @Override
    public boolean shouldContinue() {
        if (!this.isIdleAtHome() || !this.isBedTime()) {
            return false;
        }
        BlockPos home = this.meido.getMission().home();
        // 床被挖了就别在空气旁边站一晚上 —— 散了，天亮继续晃。
        return home != null && this.isHomeABed(home);
    }

    @Override
    public void stop() {
        this.meido.getNavigation().stop();
    }

    @Override
    public void tick() {
        BlockPos home = this.meido.getMission().home();
        if (home == null) {
            return;
        }
        Vec3d bed = Vec3d.ofCenter(home);
        this.meido.getLookControl().lookAt(bed.x, bed.y, bed.z);
        if (this.meido.squaredDistanceTo(bed) <= ARRIVE_SQUARED) {
            this.meido.getNavigation().stop();
            return;
        }
        if (this.repathTimer-- <= 0) {
            this.repathTimer = REPATH_INTERVAL;
            this.meido.getNavigation().startMovingTo(bed.x, bed.y, bed.z, TRAVEL_SPEED);
        }
    }

    /** 「闲着」= 游走模式且没在跟人聊天。干活（钓鱼/种植/守卫）和战斗都不归这个 Goal 管。 */
    private boolean isIdleAtHome() {
        return this.meido.getMission().type() == MeidoModeType.WANDER
                && !this.meido.isInteracting();
    }

    /** 原版上床时间窗：12542 ~ 23459（跟玩家「能睡觉」的判定一致）。 */
    private boolean isBedTime() {
        long timeOfDay = this.meido.getWorld().getTimeOfDay() % 24000L;
        return timeOfDay >= SLEEP_FROM && timeOfDay <= SLEEP_TO;
    }

    /** 家还在不在：床被挖了就别在空气旁边站一晚上。 */
    private boolean isHomeABed(BlockPos home) {
        return this.meido.getWorld().getBlockState(home).getBlock() instanceof BedBlock;
    }
}
