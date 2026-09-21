package com.mymeido.entity.goal;

import com.mymeido.MeidoCompat;

import java.util.EnumSet;
import java.util.List;

import com.mymeido.entity.MeidoEntity;
import com.mymeido.mode.MeidoModeType;

import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.ai.goal.Goal;
import net.minecraft.entity.mob.CreeperEntity;
import net.minecraft.entity.mob.Monster;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

/**
 * 近战战斗 Goal：发现怪物 → 逼近 → 打一下 → <b>拉开距离等冷却</b> → 再上。
 *
 * <h2>为什么是「打带跑」</h2>
 *
 * <p>绯色实测：怪物贴脸时她 3~4 秒才出一刀——原因是原版近战每次命中都带击退，
 * 目标被打出攻击圈后要重新寻路凑回去，节奏全耗在「追」上。所以第六轮把攻击行为
 * 改成显式状态机：<b>打一.hit → 主动拉开约 3 格 → 冷却 {@code ATTACK_INTERVAL} 走完 →
 * 再凑上去打</b>。冷却 30 tick（1.5 秒）+ 一个来回的走位 ≈ <b>2 秒一刀</b>，
 * 而且每一刀都是「冷却好了、距离够了」才出手，节奏可预期。
 *
 * <h2>两种模式都用它</h2>
 *
 * <ul>
 *   <li><b>守卫（GUARD）</b>：岗位方块是锚点。她守在方块上不乱跑，怪物进入
 *       {@code SEARCH_RADIUS} 才会接敌——逼近也是朝目标贴几步，打完往锚点方向拉开；
 *       追出 {@code LEASH_RADIUS} 直接松手回岗（守卫不是猎犬）。</li>
 *   <li><b>游走（WANDER）</b>：也会还手。锚点 = 开打那一刻她站的位置，
 *       打完该晃还晃。区别只在锚点和接敌后的回位方向。</li>
 * </ul>
 *
 * <h2>打谁：只打 {@link Monster} 一族</h2>
 *
 * <p>用「Monster 的子类」当判据，玩家 / 动物 / 村民 / 别的女仆<b>天然不在名单里</b>
 * （想让玩家也进名单：{@code modes.json} 顶层加 {@code "guard_attack_players": true}，
 * 已实现，默认关）。苦力怕刻意不打：近战够它一下，它原地爆炸（是否给苦力怕开口子尚未定）。
 *
 * <h2>附魔为什么自动生效（javap 核实过的字节码）</h2>
 *
 * <p>原版 {@code MobEntity.tryAttack} 里就是
 * {@code EnchantmentHelper.getDamage(serverWorld, this.getWeaponStack(), target, mobAttack(this), base)}
 * —— 主手那根武器的<b>锋利</b>直接进伤害公式，谁持有都一样；
 * <b>抢夺</b>走战利品表的 {@code enchanted_count_increase} / {@code random_chance_with_enchanted_bonus}
 * （读直接击杀者的武器等级，不要求玩家），所以她击杀的怪物掉落也吃抢夺加成。
 * 唯一吃不到的是 {@code killed_by_player} 门控的掉落（凋灵骷髅头这类）——原版就这么定的。
 */
public class MeidoGuardGoal extends Goal {

    /** 锚点向外找目标的半径（格）。目标进这个圈才会被接敌。 */
    public static final double SEARCH_RADIUS = 8.0;

    /** 接敌拴绳：目标离锚点超过这个距离就放弃（守卫松手回岗）。 */
    public static final double LEASH_RADIUS = 10.0;

    /** 近战够得着 = 距离平方 ≤ 3.2（约 1.8 格）。 */
    private static final double ATTACK_REACH_SQUARED = 3.2;

    /** 两次攻击的冷却（tick）。30 = 1.5 秒，加一个来回的走位 ≈ 2 秒一刀。 */
    private static final int ATTACK_INTERVAL = 30;

    /** 拉开到离目标多远算「拉够了」（距离平方，3 格）。 */
    private static final double RETREAT_DIST_SQUARED = 9.0;

    /** 移速（接敌 / 拉开共用一档，短途冲刺用不着另一档）。 */
    private static final double TRAVEL_SPEED = 0.7;

    /** 寻路重刷间隔（tick）：每 tick 刷路径会一抽一抽。 */
    private static final int REPATH_INTERVAL = 10;

    private enum Phase {
        /** 冷却已好、正往目标身上凑。 */
        ENGAGE,
        /** 刚打完一下，正拉开距离等冷却。 */
        RETREAT
    }

    private final MeidoEntity meido;
    private LivingEntity target;
    private Phase phase;
    private int cooldown;
    /** 锚点：GUARD = 岗位方块；WANDER = 开打时的位置。拴绳从这里量。 */
    private Vec3d anchor;
    private int repathTimer;

    public MeidoGuardGoal(MeidoEntity meido) {
        this.meido = meido;
        // MOVE = 打带跑的走位；LOOK = 盯着目标看。战斗里她说一不二。
        this.setControls(EnumSet.of(Goal.Control.MOVE, Goal.Control.LOOK));
    }

    /** 战斗接单范围：守卫和游走都接。 */
    private static boolean isCombatMode(MeidoModeType type) {
        return type == MeidoModeType.GUARD || type == MeidoModeType.WANDER;
    }

    @Override
    public boolean canStart() {
        if (this.meido.isInteracting()) {
            return false;
        }
        if (!isCombatMode(this.meido.getMission().type())) {
            return false;
        }
        return this.findTarget() != null;
    }

    @Override
    public void start() {
        this.target = this.findTarget();
        if (this.target == null) {
            return;
        }
        // 锚点：守卫锚在岗位方块，游走锚在开打时的位置。
        this.anchor = this.meido.getMission().type() == MeidoModeType.GUARD
                ? this.postCenter()
                : MeidoCompat.posOf(this.meido);
        this.meido.setTarget(this.target);
        // ★ 「只在攻击时把武器装备上」的那一下：从背包摸剑上手。
        this.meido.holdWeapon();
        this.phase = Phase.ENGAGE;
        this.cooldown = 0;
        this.repathTimer = 0;
    }

    @Override
    public boolean shouldContinue() {
        return this.isCombatActive()
                && this.target != null && this.target.isAlive()
                && !this.target.isRemoved()
                && this.target.squaredDistanceTo(this.anchor) <= LEASH_RADIUS * LEASH_RADIUS;
    }

    @Override
    public void stop() {
        // 收工三件套：松开目标、把武器收回背包、停住脚步。
        // 武器收回去之后，「平时手是空的」这条铁律就恢复了。
        this.meido.setTarget(null);
        this.meido.stowWeapon();
        this.meido.getNavigation().stop();
        this.target = null;
        this.anchor = null;
    }

    @Override
    public void tick() {
        if (this.target == null || !this.target.isAlive()) {
            return;   // shouldContinue 下一拍会收工，这里别拿死人当路标。
        }
        if (this.cooldown > 0) {
            this.cooldown--;
        }
        // 盯着目标看（LOOK 控制权在本 Goal 手里）。
        this.meido.getLookControl().lookAt(this.target, 30.0f, 30.0f);

        double distanceSquared = this.meido.squaredDistanceTo(this.target);
        if (this.phase == Phase.ENGAGE) {
            this.tickEngage(distanceSquared);
        } else {
            this.tickRetreat(distanceSquared);
        }
    }

    /** 接敌：够得着且冷却好 → 出手；冷却没好 → 原地等（别贴脸干瞪着挨打）。 */
    private void tickEngage(double distanceSquared) {
        if (distanceSquared <= ATTACK_REACH_SQUARED) {
            if (this.cooldown <= 0) {
                this.attack();
            } else {
                this.meido.getNavigation().stop();
            }
            return;
        }
        // 够不着：冷却好就凑上去（每 REPATH_INTERVAL 刷一次路径）。
        if (this.cooldown <= 0) {
            if (this.repathTimer-- <= 0) {
                this.repathTimer = REPATH_INTERVAL;
                this.meido.getNavigation().startMovingTo(
                        this.target.getX(), this.target.getY(), this.target.getZ(), TRAVEL_SPEED);
            }
        } else {
            this.meido.getNavigation().stop();
        }
    }

    /** 打完一下：往锚点方向拉开 3 格，冷却走完就回接敌。 */
    private void tickRetreat(double distanceSquared) {
        boolean farEnough = distanceSquared >= RETREAT_DIST_SQUARED;
        if (farEnough && this.cooldown <= 0) {
            this.phase = Phase.ENGAGE;
            this.repathTimer = 0;
            return;
        }
        // 继续往拉开的点走（路径没了就重算一次）。
        if (this.meido.getNavigation().isIdle()) {
            Vec3d retreat = this.retreatPoint();
            this.meido.getNavigation().startMovingTo(retreat.x, retreat.y, retreat.z, TRAVEL_SPEED);
        }
    }

    /** 出手那一下：甩手 + 交给原版 tryAttack（锋利等附魔在这里自动进伤害公式）。 */
    private void attack() {
        this.cooldown = ATTACK_INTERVAL;
        this.phase = Phase.RETREAT;
        this.meido.swingHand(net.minecraft.util.Hand.MAIN_HAND);
        // 1.21.5 起 tryAttack 要 ServerWorld —— 差异收口在 MeidoCompat。
        MeidoCompat.tryAttack(this.meido, this.target);
    }

    /**
     * 拉开的落点：从目标指向自己的方向退 3.5 格，<b>偏向锚点</b>（守卫退完正好回岗位一侧）。
     * 方向退化（目标和自己重合）时直接退向锚点。
     */
    private Vec3d retreatPoint() {
        Vec3d away = MeidoCompat.posOf(this.meido).subtract(MeidoCompat.posOf(this.target));
        Vec3d towardAnchor = this.anchor.subtract(MeidoCompat.posOf(this.meido));
        Vec3d dir = away.add(towardAnchor.normalize().multiply(0.5));
        if (dir.lengthSquared() < 1.0E-4) {
            dir = towardAnchor;
        }
        dir = dir.normalize().multiply(3.5);
        Vec3d point = MeidoCompat.posOf(this.meido).add(dir);
        return new Vec3d(
                MathHelper.clamp(point.x, this.anchor.x - LEASH_RADIUS, this.anchor.x + LEASH_RADIUS),
                point.y,
                MathHelper.clamp(point.z, this.anchor.z - LEASH_RADIUS, this.anchor.z + LEASH_RADIUS));
    }

    /**
     * 找一个该打的目标：锚点 {@code SEARCH_RADIUS} 内<b>最近</b>的怪物。
     * 苦力怕被排除（近战引爆它 = 自杀），其余 Monster 一族全在名单上。
     *
     * <p>{@code Monster} 是 Yarn 里的<b>接口</b>（敌对怪标记），没有实体方法 ——
     * 所以按 {@code LivingEntity} 收集、用 {@code instanceof Monster} 过滤，
     * 变量类型一路都是 {@code LivingEntity}。
     *
     * <p>{@code guard_attack_players} 开着时（modes.json 顶层），玩家也进名单
     * —— 「真无差别攻击」是设计稿 A4-2 留的口子；自家女仆不在此列
     * （她们不是 Monster，也不该互殴）。
     */
    private LivingEntity findTarget() {
        Vec3d center = this.anchorOrPos();
        Box box = new Box(
                center.x - SEARCH_RADIUS, center.y - SEARCH_RADIUS, center.z - SEARCH_RADIUS,
                center.x + SEARCH_RADIUS, center.y + SEARCH_RADIUS, center.z + SEARCH_RADIUS);
        boolean attackPlayers = com.mymeido.mode.MeidoModeRegistry.guardAttackPlayers();
        List<LivingEntity> candidates = MeidoCompat.worldOf(this.meido).getEntitiesByClass(
                LivingEntity.class, box, mob -> mob.isAlive()
                        && (mob instanceof Monster
                                || (attackPlayers && mob instanceof net.minecraft.server.network.ServerPlayerEntity))
                        && !(mob instanceof CreeperEntity)
                        && mob.squaredDistanceTo(center) <= LEASH_RADIUS * LEASH_RADIUS);
        LivingEntity best = null;
        double bestDistance = Double.MAX_VALUE;
        for (LivingEntity candidate : candidates) {
            double distance = candidate.squaredDistanceTo(center);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = candidate;
            }
        }
        return best;
    }

    /** 岗位中心：给了守卫位置就用它，没给就当她守在原地。 */
    private Vec3d postCenter() {
        com.mymeido.entity.MeidoMission mission = this.meido.getMission();
        if (mission.target() != null) {
            return Vec3d.ofCenter(mission.target());
        }
        return MeidoCompat.posOf(this.meido);
    }

    /** canStart 时锚点还没定，用岗位（或自己脚下）先当找怪中心。 */
    private Vec3d anchorOrPos() {
        return this.meido.getMission().type() == MeidoModeType.GUARD
                ? this.postCenter()
                : MeidoCompat.posOf(this.meido);
    }

    private boolean isCombatActive() {
        return isCombatMode(this.meido.getMission().type())
                && !this.meido.isInteracting();
    }
}
