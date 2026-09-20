package com.mymeido.entity.goal;

import java.util.EnumSet;

import com.mymeido.entity.MeidoEntity;
import com.mymeido.mode.MeidoModeType;

import net.minecraft.entity.ai.goal.Goal;
import net.minecraft.util.math.BlockPos;

/**
 * 派活 Goal：<b>接了任务（非游走）就压住游走，走到目标点然后原地待命</b>。
 *
 * <h2>为什么这个 Goal 只做「带路 + 站住」</h2>
 *
 * <p>二期第二批的目标是<b>把「派活」这个入口打通</b>：闹钟能选、能派、能存、能查。
 * 各模式的<b>实际行为</b>（真的去钓鱼、真的去种地）排在第三期。
 * 但「派了活她还在到处乱走」在体感上是坏的 —— 你根本看不出派发成不成功。
 * 所以这里统一做一件最朴素的事：<b>走到你指的地方，然后待在那儿</b>。
 * 这既让「派发成功了」这件事肉眼可见，也正好是第三期那些行为的地基。
 *
 * <h2>控制权</h2>
 *
 * <p>只抢 {@code MOVE}：走路归它管，但「看哪儿」留给交互 Goal / 看人 Goal，
 * 这样她站在岗位上也还会转头看你 —— 不然像个雕塑。
 *
 * <p>优先级排在交互之后、游走之前：你一右键她，交互照样能把她叫停；
 * 但只要任务还在，游走就抢不回控制权。
 */
public class MeidoMissionGoal extends Goal {

    /**
     * 走到目标点多近算「到了」。4.0 = 2 格。
     *
     * <p>{@code public} 是为了让 {@code MeidoEntity} 里「干活」那几段用同一个数：
     * 两边各写一个阈值，迟早会出现「Goal 认为已经到了、干活那段认为还没到」
     * （或者反过来），表现就是她站在岗位上一动不动、或者还在走就开始甩竿。
     */
    public static final double ARRIVE_SQUARED = 4.0;

    /** 去岗位的移速倍率。比游走的 0.6 稍快一点，像个「去干活」的样子。 */
    private static final double TRAVEL_SPEED = 0.7;

    private final MeidoEntity meido;

    public MeidoMissionGoal(MeidoEntity meido) {
        this.meido = meido;
        this.setControls(EnumSet.of(Goal.Control.MOVE));
    }

    /**
     * 有任务（模式不是游走）时才接管。
     *
     * <p>{@code BIND_HOME} 不会出现在这里：它在派发的那一刻就自己改回游走了
     * （见 {@code MeidoEntity.assignMode}），是个一次性动作，没有「持续岗位」。
     */
    @Override
    public boolean canStart() {
        return this.hasPost();
    }

    @Override
    public boolean shouldContinue() {
        return this.hasPost();
    }

    @Override
    public void start() {
        // 接下来往哪走由 tick 决定；这里不清导航 —— 派发时 assignMode 已经清过了，
        // 再清一次会让「接了任务立刻开始走」晚一 tick。
    }

    @Override
    public void stop() {
        this.meido.getNavigation().stop();
    }

    @Override
    public void tick() {
        BlockPos target = this.meido.getMission().target();
        if (target == null) {
            // 没有目标位置的模式（原地待命 / 游走以外但没给点）→ 就站这儿。
            this.hold();
            return;
        }
        double distanceSquared = this.meido.squaredDistanceTo(
                target.getX() + 0.5, target.getY(), target.getZ() + 0.5);
        if (distanceSquared > ARRIVE_SQUARED) {
            // 每 tick 刷一次路径。不刷的话短距离移动会一抽一抽的
            // （和 MeidoInteractGoal 里一样的理由：默认 Goal 是每 2 tick 才 tick 一次）。
            this.meido.getNavigation().startMovingTo(target.getX() + 0.5, target.getY(), target.getZ() + 0.5,
                    TRAVEL_SPEED);
        } else {
            this.hold();
        }
    }

    /** 站住。只清水平速度，竖直分量留着 —— 否则她在半空 / 水里会被「钉」住。 */
    private void hold() {
        this.meido.getNavigation().stop();
        this.meido.setVelocity(0.0, this.meido.getVelocity().y, 0.0);
    }

    /**
     * 有没有「岗位」要守。
     *
     * <p>{@code DUMP} 也算：它要靠这个 Goal 带路走到落点，
     * 到了之后由 {@code MeidoEntity.tickDumpMission} 完成倒东西并改回游走。
     */
    private boolean hasPost() {
        MeidoModeType type = this.meido.getMission().type();
        if (type == MeidoModeType.WANDER) {
            return false;
        }
        // 交互状态优先：她正在跟你说话的时候别急着去干活（交互 Goal 优先级更高，
        // 这里提前让路能让「停下来看你」更干脆，不用等 MOVE 控制权被抢）。
        return !this.meido.isInteracting();
    }
}
