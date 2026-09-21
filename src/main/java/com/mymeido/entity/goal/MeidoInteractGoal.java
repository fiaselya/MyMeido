package com.mymeido.entity.goal;

import com.mymeido.MeidoCompat;

import java.util.EnumSet;
import java.util.List;

import com.mymeido.entity.MeidoEntity;

import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.ai.goal.Goal;
import net.minecraft.entity.player.PlayerEntity;

/**
 * 「交互状态」这个 Goal，一个 Goal 管完两件事：
 *
 * <ol>
 *   <li><b>附近没有她该捡的东西</b> → 停下脚步、转身看着玩家（停住当前行为）；</li>
 *   <li><b>地上有玩家刚扔出来的东西</b> → 走过去，到了脚边就收下。</li>
 * </ol>
 *
 * <p><b>为什么两件事塞一个 Goal：</b>它们都要抢 {@code MOVE} 控制权。
 * 拆成两个 Goal 的话，一个在「走向物品」、另一个在「停住不动」，
 * 会互相打断，她就会原地抽搐。一个 Goal 内部做状态判断，天然不会自相冲突。
 *
 * <p><b>只在她「交互状态」里跑</b>（{@link MeidoEntity#isInteracting()}）。
 * 平时她什么都不捡 —— 这是设计稿 A4-1 的硬要求，
 * 否则你掉在基地门口的东西会被她一件件吞掉。
 *
 * <p>优先级排在游走之前，所以一旦进入交互状态，游走会被立刻打断。
 */
public class MeidoInteractGoal extends Goal {

    /** 判定「走到物品脚边了」的距离平方。4.0 = 2 格。 */
    private static final double ARRIVE_SQUARED = 4.0;

    /**
     * 盯物品的最近距离平方。4.0 = 2 格。
     *
     * <p>★★ <b>比这个更近就绝对不要再 {@code lookAt(物品)}</b>——
     * 物品滚到她脚边时，{@code 物品位置 - 她位置} 的水平向量趋近于 (0,0)，
     * 而 {@code LookControl} 是靠 {@code atan2(dz, dx)} 算转向角的：
     * 分子分母同时趋于 0 时，结果会被最微小的数值扰动（自己走路、物品被推动、
     * 浮点误差）甩到 ±180° 的两端。于是每 tick 目标朝向都在正负之间乱跳，
     * 而每 tick 又允许转 30° —— 从外面看就是<b>她的头（和身体）在原地疯狂打转</b>。
     *
     * <p>近身阶段改看玩家 —— 玩家的位置离她通常有 1 格以上，方向稳定。
     *
     * <p>⚠️ <b>勘误（2026-09-20）</b>：这条曾经被我当成「头 360° 转圈」的根因，
     * 其实<b>不是</b>。真正的原因是 {@code MeidoEntityModel} 往共享 ModelPart 的
     * {@code head.roll} 上做了 {@code +=}，而原版从不重置该字段 → 无限累积。
     * 这里保留这道防护是因为「朝自己脚下的点看」本来就是退化输入（该防），
     * 但<b>别再把它当成转圈的解释</b>。详见 {@code MeidoEntityModel.setAngles}。
     */
    private static final double LOOK_AT_MIN_SQUARED = 4.0;

    /** 转头速度上限（度/tick）。给大一点，看起来是「一下转过头来」而不是慢慢悠悠。 */
    private static final float LOOK_YAW_SPEED = 30.0f;
    private static final float LOOK_PITCH_SPEED = 30.0f;

    private final MeidoEntity meido;

    /** 当前盯上的地面物品。null = 没东西可捡。 */
    private ItemEntity target;

    public MeidoInteractGoal(MeidoEntity meido) {
        this.meido = meido;
        this.setControls(EnumSet.of(Goal.Control.MOVE, Goal.Control.LOOK));
    }

    /**
     * 默认 Goal 每 2 tick 才 tick 一次。走路要每 tick 刷新路径，
     * 否则会一抽一抽的（尤其是短距离移动）。
     */
    @Override
    public boolean shouldRunEveryTick() {
        return true;
    }

    @Override
    public boolean canStart() {
        return this.meido.isInteracting();
    }

    @Override
    public boolean shouldContinue() {
        return this.meido.isInteracting();
    }

    @Override
    public void start() {
        this.target = null;
        this.meido.getNavigation().stop();
    }

    @Override
    public void stop() {
        this.target = null;
        this.meido.getNavigation().stop();
    }

    @Override
    public void tick() {
        PlayerEntity player = this.meido.getInteractionPlayer();

        // 盯上的东西被别的玩家捡走了 / 被火烧了 → 重新找一个
        if (this.target != null && (!this.target.isAlive() || this.target.getStack().isEmpty())) {
            this.target = null;
        }
        if (this.target == null) {
            this.target = this.findTarget(player);
        }

        if (this.target != null) {
            double distanceSquared = this.meido.squaredDistanceTo(this.target);

            // 只在「还离得够远」时才盯着物品。近到脚边还盯着它会让 LookControl 的
            // 方向向量退化成零向量、朝向乱跳（详见 LOOK_AT_MIN_SQUARED 的说明）。
            if (distanceSquared > LOOK_AT_MIN_SQUARED) {
                this.meido.getLookControl().lookAt(this.target, LOOK_YAW_SPEED, LOOK_PITCH_SPEED);
            } else if (player != null) {
                this.meido.getLookControl().lookAt(player, LOOK_YAW_SPEED, LOOK_PITCH_SPEED);
            }

            if (distanceSquared <= ARRIVE_SQUARED) {
                // 到了脚边也不一定拿得起来：原版给刚扔出来的物品挂了 40 tick 的拾取冷却
                // （PlayerEntity.dropItem 里写死的）。冷却没过就原地等，
                // 否则她会反复「抓空气」。
                if (!this.target.cannotPickup()) {
                    this.meido.pickUp(this.target);
                    this.target = null;
                }
            } else {
                this.meido.getNavigation().startMovingTo(this.target, MeidoEntity.PICKUP_SPEED);
            }
            return;
        }

        // 没东西可捡 → 站住，看着她。
        // 速度只清水平分量，竖直分量留着，免得她在半空中被「定住」。
        this.meido.getNavigation().stop();
        this.meido.setVelocity(0.0, this.meido.getVelocity().y, 0.0);
        if (player != null) {
            this.meido.getLookControl().lookAt(player, LOOK_YAW_SPEED, LOOK_PITCH_SPEED);
        }
    }

    /**
     * 找一个「值得去捡」的东西。
     *
     * <p>两个筛选条件：
     * <ul>
     *   <li><b>是交互的那个玩家扔的</b> —— 靠 {@code ItemEntity.getOwner()} 认人。
     *       这条最关键：没有它就等于「她会捡走你地上所有东西」。
     *       已核实：按 Q 丢出来的物品走的是
     *       {@code ServerPlayerEntity.dropSelectedItem → PlayerEntity.dropItem(stack, false, true)}，
     *       第三个参数 {@code retainOwnership} 为 true，所以确实会 {@code setThrower(玩家)}。</li>
     *   <li><b>在 {@link MeidoEntity#PICKUP_RANGE} 内</b> —— 太远的不管，
     *       免得她为了一个扔出界的东西跑没影。</li>
     * </ul>
     * 刻意<b>不过滤</b>「拾取冷却中」的物品：那会让她在旁边干站着等 2 秒，
     * 看起来像没听见你说话。先走过去再说，冷却一过就伸手（见 {@link #tick()}）。
     */
    private ItemEntity findTarget(PlayerEntity player) {
        if (player == null) {
            return null;
        }
        List<ItemEntity> items = MeidoCompat.worldOf(this.meido).getEntitiesByClass(
                ItemEntity.class,
                this.meido.getBoundingBox().expand(MeidoEntity.PICKUP_RANGE),
                item -> !item.getStack().isEmpty()
                        && item.getOwner() != null
                        && item.getOwner().getUuid().equals(player.getUuid()));

        ItemEntity best = null;
        double bestDistance = Double.MAX_VALUE;
        for (ItemEntity item : items) {
            double distance = this.meido.squaredDistanceTo(item);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = item;
            }
        }
        return best;
    }
}
