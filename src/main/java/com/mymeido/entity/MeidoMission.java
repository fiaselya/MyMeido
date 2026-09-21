package com.mymeido.entity;

import java.util.Optional;
import java.util.UUID;

import com.mymeido.mode.MeidoModeDef;
import com.mymeido.mode.MeidoModeRegistry;
import com.mymeido.mode.MeidoModeType;

import net.minecraft.nbt.NbtCompound;
//? if >=1.21.11 {
import net.minecraft.storage.ReadView;
import net.minecraft.storage.WriteView;
import net.minecraft.util.Uuids;
//?}

import net.minecraft.util.math.BlockPos;

/**
 * 女仆身上那份「派活」状态：<b>她现在该干什么、在哪儿干、家在哪儿、谁派的</b>。
 *
 * <p>单独拎成一个类、而不是在 {@link MeidoEntity} 里散着放三个字段，是因为
 * 这套东西会一起存、一起读、一起被指令查询，散着放迟早会漏掉某个字段的存档。
 *
 * <h2>四个字段各自的用途</h2>
 * <ul>
 *   <li>{@code modeId} —— 当前模式。<b>认的是 id 字符串</b>，不是枚举序号：
 *       玩家在 {@code modes.json} 里删掉或改名一条模式之后，老存档不该读出一只
 *       「模式变成了别的」的女仆。id 认不出来时回落成游走，并记一条日志。</li>
 *   <li>{@code target} —— 派发时右键的那个位置（钓鱼的河、种植的地、丢东西的落点……）。
 *       可以为空：游走 / 待命这类模式本来就不需要位置。</li>
 *   <li>{@code home} —— 绑定的家（床）。<b>和模式无关</b>，绑一次一直有效，
 *       所有模式通用（设计稿 A4-2）。</li>
 *   <li>{@code dispatchedBy} —— 派这次活的人。<b>只有一次性任务用得上</b>：
 *       丢弃物品 / 绑定家干完之后，要把<b>他</b>手上闹钟的「选中模式」也切回游走 ——
 *       否则闹钟会一直写着「丢弃物品」，而她早就走开了（2026-09-20 实测踩到）。
 *       找不到人（下线 / 重启过）就不发通知，这不是错误。</li>
 * </ul>
 *
 * <p>注意 {@code target} 与 {@code home} 都是<b>一个方块坐标</b>，不存维度 ——
 * 这个 mod 的用法是「她就住在你这张地图上」，跨维度还要各自记一份家的复杂度
 * 目前不值得（真要跨维度了再说）。
 */
public final class MeidoMission {

    private static final String KEY_MODE = "Mode";
    private static final String KEY_TARGET = "TargetPos";
    private static final String KEY_HOME = "HomePos";
    private static final String KEY_DISPATCHER = "Dispatcher";

    private String modeId = MeidoModeRegistry.DEFAULT_MODE_ID;

    /** 派发目标位置。null = 没有。 */
    private BlockPos target;

    /** 绑定的家。null = 还没绑。 */
    private BlockPos home;

    /** 派这次活的人。null = 不知道（指令路径 / 老存档 / 已经处理过了）。 */
    private UUID dispatchedBy;

    public String modeId() {
        return this.modeId;
    }

    /** 现在的模式条目。配置里认不出来的话返回 empty（调用方该回落游走）。 */
    public Optional<MeidoModeDef> def() {
        return MeidoModeRegistry.byId(this.modeId);
    }

    public MeidoModeType type() {
        return def().map(MeidoModeDef::type).orElse(MeidoModeType.WANDER);
    }

    /** 指令 / 聊天里显示的名字。配置被删了就退回 id 本身，别显示成空白。 */
    public String displayName() {
        return def().map(MeidoModeDef::name).orElse(this.modeId);
    }

    public BlockPos target() {
        return this.target;
    }

    public BlockPos home() {
        return this.home;
    }

    public boolean hasHome() {
        return this.home != null;
    }

    public UUID dispatchedBy() {
        return this.dispatchedBy;
    }

    /**
     * 记下这次是谁派的。
     *
     * <p>刻意做成一个可以设成 null 的普通 setter，而不是塞进 {@link #setMode}：
     * 「现在干什么」和「谁说的」是两件事 —— 读档回落游走（{@link #sanitize()}）
     * 不该把派活人一起抹掉，否则重启一次服务端，那次没干完的活就再也不会通知谁了。
     */
    public void setDispatchedBy(UUID playerId) {
        this.dispatchedBy = playerId;
    }

    /**
     * 换模式。
     *
     * <p>换模式时<b>顺手清掉上一个模式的目标位置</b> ——
     * 上一份任务的目标点对新任务是脏数据（例如「钓鱼的河」被当成了「丢东西的落点」）。
     * {@code home} 不清：那是家，不属于任何一次派发。
     */
    public void setMode(String newModeId) {
        this.modeId = newModeId == null || newModeId.isBlank()
                ? MeidoModeRegistry.DEFAULT_MODE_ID : newModeId;
        this.target = null;
    }

    public void setTarget(BlockPos pos) {
        this.target = pos == null ? null : pos.toImmutable();
    }

    public void setHome(BlockPos pos) {
        this.home = pos == null ? null : pos.toImmutable();
    }

    /** 读档时把认不出来的模式拉回默认值，并让调用方有机会记日志。 */
    public boolean sanitize() {
        if (MeidoModeRegistry.isKnown(this.modeId)) {
            return false;
        }
        String old = this.modeId;
        this.modeId = MeidoModeRegistry.DEFAULT_MODE_ID;
        return !old.equals(this.modeId);
    }

    // ------------------------------------------------------------------
    // 存档
    // ------------------------------------------------------------------

    // 1.21.11 实体存档走 WriteView/ReadView（javap 实锤），与 1.21.1 的 NbtCompound
    // 版本并存 —— 键名两套保持一致，per-version 各读各的。
    //? if >=1.21.11 {
    public void writeView(WriteView view) {
        view.putString(KEY_MODE, this.modeId);
        // putNullable：null 时整键不写，与旧版「null 就不放」语义一致。
        view.putNullable(KEY_TARGET, BlockPos.CODEC, this.target);
        view.putNullable(KEY_HOME, BlockPos.CODEC, this.home);
        // ★ INT_STREAM_CODEC（int 数组版）而不是 Uuids.CODEC（字符串版）：
        //   1.21.1 的 putUuid 写的是 [I;a,b,c,d]，必须同型才能互相读档。
        view.putNullable(KEY_DISPATCHER, Uuids.INT_STREAM_CODEC, this.dispatchedBy);
    }

    public void readView(ReadView view) {
        view.getOptionalString(KEY_MODE).ifPresent(id -> this.modeId = id);
        // 坐标沿用 asLong 思路换成 codec：一个键装下 xyz，不会「存了 x 忘了 z」。
        this.target = view.read(KEY_TARGET, BlockPos.CODEC).orElse(null);
        this.home = view.read(KEY_HOME, BlockPos.CODEC).orElse(null);
        this.dispatchedBy = view.read(KEY_DISPATCHER, Uuids.CODEC).orElse(null);
    }
    //?} else {
    /*public void writeNbt(NbtCompound nbt) {
        nbt.putString(KEY_MODE, this.modeId);
        if (this.target != null) {
            nbt.putLong(KEY_TARGET, this.target.asLong());
        }
        if (this.home != null) {
            nbt.putLong(KEY_HOME, this.home.asLong());
        }
        if (this.dispatchedBy != null) {
            // ★ 用 putUuid / getUuid（1.21.1 已有），别自己拆成两个 long —— 手拆就会有人手滑。
            nbt.putUuid(KEY_DISPATCHER, this.dispatchedBy);
        }
    }

    public void readNbt(NbtCompound nbt) {
        if (nbt.contains(KEY_MODE)) {
            this.modeId = nbt.getString(KEY_MODE);
        }
        // ★ 坐标用 asLong / fromLong 存：一个 long 装下 xyz，不用自己拆三个 int，
        //   也就不会出现「存了 x 忘了 z」这种手滑。
        this.target = nbt.contains(KEY_TARGET) ? BlockPos.fromLong(nbt.getLong(KEY_TARGET)) : null;
        this.home = nbt.contains(KEY_HOME) ? BlockPos.fromLong(nbt.getLong(KEY_HOME)) : null;
        this.dispatchedBy = nbt.contains(KEY_DISPATCHER) ? nbt.getUuid(KEY_DISPATCHER) : null;
    }
    *///?}

    /** 「x y z」或者「—」，给指令输出用。 */
    public static String format(BlockPos pos) {
        return pos == null ? "—" : pos.getX() + " " + pos.getY() + " " + pos.getZ();
    }
}
