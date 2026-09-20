package com.mymeido.entity.goal;

import java.util.EnumSet;

import com.mymeido.entity.MeidoEntity;

import net.minecraft.entity.ai.goal.Goal;
import net.minecraft.server.network.ServerPlayerEntity;

/**
 * 「她主动走过来跟主人搭话」的那一段路。
 *
 * <h2>为什么必须是一个 Goal，不能在 tick() 里直接 startMovingTo</h2>
 *
 * <p>游走（{@code WanderAroundFarGoal}，优先级 5）每 tick 都会自己
 * {@code getNavigation().startMovingTo(...)}。在 {@code tick()} 里直接派路的结果是
 * 两边轮流改导航目标 —— 从外面看就是<b>她走两步、拐个弯、再走两步</b>，
 * 永远到不了玩家跟前。只有 Goal 能「占住 MOVE 控制权」，让游走整个停下。
 *
 * <h2>优先级为什么是 3（和「天黑回家」同级、插在它前面）</h2>
 *
 * <ul>
 *   <li>低于 1（交互）/ 2（逃跑 / 还手 / 守卫）—— <b>右键她永远优先</b>，
 *       打架也不该被打断去聊天；</li>
 *   <li>和「回家」同级：这两件事本来就不会同时成立（搭话只在白天、回家只在夜里），
 *       真撞上了以「先跟主人说句话」为准，所以插在它前面。</li>
 * </ul>
 *
 * <p>另外她和「回家」一样<b>不打断手里的活</b>这一点是有意为之的反面 ——
 * 见 {@link #canStart()}：这个 Goal 不检查模式，因为她是在招呼<b>主人</b>，
 * 钓鱼钓到一半主人站在旁边，她答一声是应该的（任务状态不会丢，
 * 这个 Goal 结束之后 {@code MeidoMissionGoal} 会自己把她带回岗位）。
 *
 * <h2>三个阶段</h2>
 * <ol>
 *   <li><b>走过去</b>——每 {@code REPATH_INTERVAL} tick 刷一次路；</li>
 *   <li><b>到了</b>——交回 {@link MeidoEntity#onProactiveArrived()}（说那句话 + 扣次数）；</li>
 *   <li><b>站一会儿</b>——说完了别立刻转身走掉（那是路人不是女仆），
 *       导航停下、转头看着她，{@code PROACTIVE_LINGER_TICKS} 之后自然结束。</li>
 * </ol>
 *
 * <p>天黑了 / 人不见了 / 10 秒还走不到，都不是这个 Goal 的责任 ——
 * {@link MeidoEntity#tickProactiveChat()} 会在下一 tick 把状态清掉，
 * 本 Goal 的 {@link #shouldContinue()} 随之变 false，自然收工。
 */
public class MeidoApproachOwnerGoal extends Goal {

    /** 到玩家多近算「到了」（距离平方 = 2.5 格）。 */
    private static final double ARRIVE_SQUARED = MeidoEntity.PROACTIVE_ARRIVE_SQUARED;

    /** 寻路重刷间隔（tick）。玩家会走，路要跟着改。 */
    private static final int REPATH_INTERVAL = 10;

    /** 走路速度。<b>比游走的 0.6 快一点</b> —— 这一趟是「她特地过来找你」。 */
    private static final double TRAVEL_SPEED = 0.7;

    /** 转头速度上限（度/tick）。跟交互 Goal 用同一档：是一下转过头来，不是慢慢悠悠。 */
    private static final float LOOK_YAW_SPEED = 30.0f;
    private static final float LOOK_PITCH_SPEED = 30.0f;

    private final MeidoEntity meido;
    private int repathTimer;

    public MeidoApproachOwnerGoal(MeidoEntity meido) {
        this.meido = meido;
        this.setControls(EnumSet.of(Goal.Control.MOVE, Goal.Control.LOOK));
    }

    @Override
    public boolean canStart() {
        return this.meido.isProactiveApproaching();
    }

    @Override
    public boolean shouldContinue() {
        // 说完了还要站一会儿（isProactiveLingering）—— 走与站是同一次出工的上下半场，
        // 拆成两个 Goal 会互相抢 MOVE，她会在玩家面前原地抖。
        return this.meido.isProactiveApproaching() || this.meido.isProactiveLingering();
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
        ServerPlayerEntity owner = this.meido.proactiveTarget();
        if (owner == null) {
            return;   // 人不见了：MeidoEntity 那边下一 tick 会自己取消
        }
        this.meido.getLookControl().lookAt(owner, LOOK_YAW_SPEED, LOOK_PITCH_SPEED);

        // 说完了 → 站住看她。别继续挤过去：贴到 0 格会互相推挤，两个实体都在抖。
        if (this.meido.isProactiveLingering()) {
            this.meido.getNavigation().stop();
            return;
        }
        if (this.meido.squaredDistanceTo(owner) <= ARRIVE_SQUARED) {
            this.meido.onProactiveArrived();   // 里面会说话、扣次数、切成「站一会儿」
            return;
        }
        if (this.repathTimer-- <= 0) {
            this.repathTimer = REPATH_INTERVAL;
            this.meido.getNavigation().startMovingTo(owner, TRAVEL_SPEED);
        }
    }
}
