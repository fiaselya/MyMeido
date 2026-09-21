package com.mymeido.entity;

import com.mymeido.MeidoCompat;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.mymeido.MyMeido;
import com.mymeido.MeidoLocale;
import com.mymeido.ai.MeidoAi;
import com.mymeido.ai.MeidoAiConfig;
import com.mymeido.chat.MeidoChat;
import com.mymeido.entity.goal.MeidoApproachOwnerGoal;
import com.mymeido.entity.goal.MeidoFleeGoal;
import com.mymeido.entity.goal.MeidoGoHomeGoal;
import com.mymeido.entity.goal.MeidoGuardGoal;
import com.mymeido.entity.goal.MeidoInteractGoal;
import com.mymeido.entity.goal.MeidoMissionGoal;
import com.mymeido.entity.goal.MeidoRetaliateGoal;
import com.mymeido.item.CommandAlarmItem;
import com.mymeido.mode.MeidoModeDef;
import com.mymeido.mode.MeidoModeRegistry;
import com.mymeido.mode.MeidoModeType;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.CropBlock;
import net.minecraft.block.NetherWartBlock;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.ai.goal.LookAroundGoal;
import net.minecraft.entity.ai.goal.LookAtEntityGoal;
import net.minecraft.entity.ai.goal.SwimGoal;
import net.minecraft.entity.ai.goal.WanderAroundFarGoal;
import net.minecraft.entity.attribute.DefaultAttributeContainer;
import net.minecraft.entity.attribute.EntityAttribute;
import net.minecraft.entity.attribute.EntityAttributeInstance;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.data.DataTracker;
import net.minecraft.entity.data.TrackedData;
import net.minecraft.entity.data.TrackedDataHandlerRegistry;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.mob.PathAwareEntity;
import net.minecraft.entity.player.PlayerEntity;
//? if >=1.21.11 {
import net.minecraft.component.type.EquippableComponent;
//?} else {
/*import net.minecraft.item.Equipment;
*///?}
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.inventory.Inventory;
import net.minecraft.loot.LootTable;
import net.minecraft.loot.LootTables;
//? if >=1.21.11 {
import net.minecraft.loot.context.LootWorldContext;
//?} else {
/*import net.minecraft.loot.context.LootContextParameterSet;
*///?}
import net.minecraft.loot.context.LootContextParameters;
import net.minecraft.loot.context.LootContextTypes;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtString;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ChunkLevelType;
import net.minecraft.server.world.ChunkLevels;
import net.minecraft.server.world.ChunkTicketType;
import net.minecraft.server.world.ServerChunkManager;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.ScreenHandlerType;
import net.minecraft.screen.SimpleNamedScreenHandlerFactory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.text.TextColor;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
//? if <1.21.11 {
/*import net.minecraft.util.Unit;
*///?}

//? if >=1.21.11 {
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.storage.ReadView;
import net.minecraft.storage.WriteView;
import net.minecraft.util.Uuids;
//?}
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;

/**
 * 女仆 NPC 本体。
 *
 * <p>为什么继承 {@link PathAwareEntity} 而不是 {@code Villager}：
 * 村民身上挂着交易 / 职业 / 繁殖 / 村庄绑定一大堆我们完全不想要的东西，
 * 拆比建贵。PathAwareEntity 是「会自己走路、但没有大脑」的干净底座。
 *
 * <p>行为定位（设计定稿 A 类决策）：
 * <ul>
 *   <li><b>永不消失</b>：{@code setPersistent()} → {@code cannotDespawn()} 为 true，
 *       走多远、换维度、重启服务器都还在。</li>
 *   <li><b>中立</b>：goalSelector 只放游走 / 看人 / 张望；
 *       targetSelector <b>故意留空</b>，她不主动打任何东西，也不会被怪物逻辑接管。</li>
 *   <li><b>不参与自然生成</b>：注册时用 SpawnGroup.MISC 且不给生物群系生成表。</li>
 * </ul>
 *
 * <p>血量与移速见 {@link #DESIGN_MAX_HEALTH} / {@link #DESIGN_MOVEMENT_SPEED}。
 * 移速<b>刻意对齐村民</b>：低移速不只让人走得慢，还会让原版走路动画彻底垮掉（像慢动作）。
 */
public class MeidoEntity extends PathAwareEntity {

    /**
     * 早期版本写死的默认名「女仆」。
     *
     * <p>现在默认名<b>跟着皮肤走</b>（见 {@link MeidoSkin#displayName()}），
     * 所以这个常量只剩一个用途：读老存档时认出「这是当年的默认名、不是玩家改的名」，
     * 好把它替换成新皮肤名。想取「当前的默认名」请用 {@link #defaultName()}。
     */
    public static final String LEGACY_DEFAULT_NAME = "女仆";

    /**
     * 移速设计值。<b>0.5 就是村民的值</b>（僵尸 0.23、铁傀儡 0.25 都明显更慢）。
     *
     * <p>这个数字不只是「走多快」：原版 limb 动画的推进量取自实际位移，
     * 给低了腿会摆得又小又慢，看起来像慢动作。想跟正常人形生物一致就得用这个量级。
     */
    public static final double DESIGN_MOVEMENT_SPEED = 0.5;

    /** 血量设计值。40 = 玩家的两倍；不是要她打架，是「被打死」不该轻易发生。 */
    public static final double DESIGN_MAX_HEALTH = 40.0;

    /**
     * 攻击力设计值。3.0 = 原版僵尸的量级 —— 她拿着剑打怪要「打得动」，
     * 但也别一巴掌一个（那是玩家的diamond剑该干的事，伤害细账走武器的附魔/材质，不走这个基线）。
     * 写进 {@link #applyDesignAttributes} 是同一个理由：老存档里存着旧基线，读档时要刷新。
     */
    public static final double DESIGN_ATTACK_DAMAGE = 3.0;

    // ---- 交互状态（二期） ----

    /**
     * 交互状态持续多久（tick）。200 = 10 秒。
     *
     * <p>「交互状态」是「她注意到你了」的窗口期：这期间她停下手里的事、转过来看你，
     * 并且<b>只在这期间</b>会去捡你扔出来的东西。平时她什么都不捡，
     * 否则你基地门口掉的装备会被她一件件吞进背包。
     */
    public static final int INTERACT_TICKS = 200;

    /**
     * 每次成功接到东西后，把窗口续到至少这么久（100 tick = 5 秒）。
     *
     * <p>这样「扔一件 → 再扔一件 → 再扔一件」能连成一串，
     * 不用每扔一件就重新右键她一次。
     */
    public static final int INTERACT_EXTEND_TICKS = 100;

    /** 只认这个半径内、由交互玩家扔出的物品（格）。 */
    public static final double PICKUP_RANGE = 8.0;

    /** 走过去捞东西的移速倍率。1.0 明显快于游走的 0.6，看起来像「赶紧过来」。 */
    public static final double PICKUP_SPEED = 1.0;

    // ---- 同步数据。主源集定义，两端都读得到；客户端靠它决定皮肤 / 粒子 / 头部动作。 ----
    private static final TrackedData<String> SKIN =
            DataTracker.registerData(MeidoEntity.class, TrackedDataHandlerRegistry.STRING);
    private static final TrackedData<Integer> EMOTION =
            DataTracker.registerData(MeidoEntity.class, TrackedDataHandlerRegistry.INTEGER);
    private static final TrackedData<Integer> EMOTION_LEFT =
            DataTracker.registerData(MeidoEntity.class, TrackedDataHandlerRegistry.INTEGER);
    private static final TrackedData<Integer> COLOR =
            DataTracker.registerData(MeidoEntity.class, TrackedDataHandlerRegistry.INTEGER);
    /**
     * 玩家显式改的名字（昵称）。<b>空串 = 没改过，用皮肤名当默认名。</b>
     *
     * <p>为什么不直接读原版 {@code CustomName}：那个字段同时承担「显示用的带样式 Text」，
     * 分不出「玩家改的名」和「我们按皮肤算出来的默认名」。
     * 分开存之后，「换皮肤 → 名字跟着变；改过名 → 换皮肤也不动名字」这条规则才成立。
     */
    private static final TrackedData<String> NICKNAME =
            DataTracker.registerData(MeidoEntity.class, TrackedDataHandlerRegistry.STRING);

    /**
     * 当前模式 id（二期第二批）。
     *
     * <p>同步给客户端是为了能在界面上显示「她现在是干什么的」，
     * 以及将来在头顶/名牌上挂一个小小的状态标记。
     * 目标坐标与家<b>刻意不同步</b> —— 客户端不需要它们，
     * 要知道的时候走 {@code /mymeido mission}（服务端读，客户端显示）。
     */
    private static final TrackedData<String> MODE_ID =
            DataTracker.registerData(MeidoEntity.class, TrackedDataHandlerRegistry.STRING);

    // ---- 服务端私有状态（不参与同步） ----

    /**
     * 派活状态：模式 + 目标点 + 家。存档走 {@link #writeCustomDataToNbt}。
     *
     * <p>只活服务端 —— 行为本来就是服务端说了算。
     */
    private final MeidoMission mission = new MeidoMission();

    /**
     * 她自己的 9 格背包。玩家的东西给到她手上之后，最终都落到这里或者身上装备槽。
     *
     * <p>刻意<b>不同步</b>：背包内容属于服务端权威数据，
     * 客户端要知道有什么应该走「打开容器界面」那条路（二期后面再接），
     * 而不是塞进 DataTracker 每 tick 广播 9 个 ItemStack。
     */
    private final MeidoInventory inventory = new MeidoInventory();

    /**
     * ★ A4-5 强制加载：她脚下 3×3 区块的票（{@code ChunkPos.toLong()} 的集合）。
     *
     * <p>没有这个，你走远一步区块就卸载，她整个人连同手头的活一起冻结 ——
     * 冒烟测试里一直是拿 {@code /forceload add} 顶的，正式做法就是 mod 自己挂票
     * （和 {@code /forceload} 同级的 {@code ChunkLevelType.FULL}，全负荷 tick）。
     * 每 {@code CHUNK_TICK_INTERVAL} tick 对比一次「想要的位置」和「已挂的票」，
     * 差多少补多少；她被杀/被清除时 {@link #onRemoved()} 全部撤掉。
     */
    // 1.21.11：ChunkTicketType 变成 record(expiryTicks, flags)，create() 没了；
    // 语义照搬旧版 create()：不过期、不参与序列化（票由我们每轮自行挂撤）。
    //? if >=1.21.11 {
    private static final ChunkTicketType LOAD_TICKET =
            new ChunkTicketType(ChunkTicketType.NO_EXPIRATION,
                    ChunkTicketType.FOR_LOADING | ChunkTicketType.FOR_SIMULATION);
    //?} else {
    /*private static final ChunkTicketType<Unit> LOAD_TICKET =
            ChunkTicketType.create("mymeido_maid", (a, b) -> 0);
    *///?}

    /** 常驻加载范围：脚下方块所在区块 + 周围一圈 = 3×3。 */
    private static final int LOAD_RADIUS_CHUNKS = 1;

    /** 多少 tick 校准一次票（每秒一次足够 —— 票不跟着每格移动，跟着区块走）。 */
    private static final int CHUNK_TICK_INTERVAL = 20;

    private final LongSet heldChunkTickets = new LongOpenHashSet();
    private int chunkTicketTimer;

    /**
     * 回血（2026-09-20 第三批追加）：她没有原版玩家的饥饿值机制，MobEntity 又天生不回血 ——
     * 不补这两条，40 血掉完就只能重新召。两条叠加：
     *
     * <ul>
     *   <li><b>吃背包里的食物</b>：受伤且没吃饱时，从背包摸一份食物吃掉，
     *       回血量 = 原版营养值（面包 5 / 牛排 8 / 金苹果 4）。3 秒一份的节奏；</li>
     *   <li><b>慢速自然回血</b>：脱战 3 秒后，每 5 秒 +1 血（原版满饥饿玩家的量级）。</li>
     * </ul>
     */
    private static final int REGEN_INTERVAL = 100;
    private static final int REGEN_HURT_GRACE = 60;
    private static final int EAT_INTERVAL = 60;
    private int regenTimer;
    private int eatCooldown;
    private long lastHurtAge = -99999L;

    /**
     * ★ A4-4 好感度（0~100，初始 50 = 「中」档）。NBT 键 {@code MeidoFavor}。
     *
     * <p>事件驱动（A4-3 的性能底线）：只在「有人跟她发生关系」时变，不轮询 ——
     * <ul>
     *   <li>她捡起玩家扔的物品 <b>+2</b>（5 秒冷却防连刷）；</li>
     *   <li>玩家右键打招呼 <b>+1</b>（5 分钟冷却防刷）；</li>
     *   <li>玩家打她 <b>−10</b>，并立刻按好感度档位做反应（见 {@link #onAttackedByPlayer}）。</li>
     * </ul>
     * 档位与被攻击反应的对应表见设计稿 A4-4：极高不逃不还手、高扛到残血、
     * 中立刻逃、低有武器还手 / 无武器逃。
     */
    private int favor = 50;
    /** 上次捡物加分的游戏日（{@code getTime()/24000}，不随 /time set 回跳）。初始 -1 = 今天还没加过。 */
    private long lastFavorPickupDay = -1L;
    /** 上次打招呼加分的游戏日。 */
    private long lastFavorChatDay = -1L;

    /** 被玩家打后的逃跑状态（A4-4）：{@link #age} &lt; {@code fleeUntil} 期间在逃。 */
    private Vec3d fleeFromPos;
    private long fleeUntil = -1L;
    /** 被玩家打后的还手状态（低好感 + 有武器）：期间把攻击者当战斗目标。 */
    private LivingEntity retaliateTarget;
    private long retaliateUntil = -1L;
    /** 夜间收纳：今晚已经收过（天亮重置）。 */
    private boolean storedTonight;

    /** 交互状态剩余 tick。只活服务端 —— 行为本来就是服务端说了算。 */
    private int interactTicks;

    /**
     * 钓鱼：还要等多少 tick 才咬钩。<b>0 = 还没甩竿</b>。
     *
     * <p>只活服务端、也不存档 —— 服务端重启就重新甩一次竿，没有任何损失。
     */
    private int fishWaitTicks;

    /**
     * 她手上那根钓鱼竿是「从背包里挪到手上」的吗。
     *
     * <p>是的话，收工（模式换掉 / 任务结束）时要挪回背包，背包满了掉在脚边。
     * 不挪回去的话她会一辈子举着一根竿，下次派别的活时手还被占着。
     *
     * <p>竿必须是<b>她自己的</b>（玩家递给她 / 扔给她的那一根）：
     * 附魔（海之眷顾 / 饵钓）读的就是这根竿上的组件，凭空变一根假的就没意义了。
     */
    private boolean fishRodFromBackpack;

    /**
     * 她手上那把武器是「战斗时从背包里摸出来」的吗。
     *
     * <p>现在的规则是「<b>平时手是空的，武器只在攻击时上手</b>」：
     * {@link #accept} 给到武器只进背包；{@code MeidoGuardGoal} 开打时 {@link #holdWeapon}
     * 把它摸到主手，收工时 {@link #stowWeapon} 收回去。
     * 实际收手动作统一走 {@link #stowWeapon}（无条件把手上的近战武器归仓），
     * 这个标记只是给「收回去的时候别把别人塞的东西弄丢」多加一道保险。
     */
    private boolean weaponFromBackpack;

    /**
     * 种植：距下一次「看一圈地」还有多少 tick。
     *
     * <p>需要一个冷却，而不是每 tick 都扫一遍方块 ——
     * 作物成熟以「分钟」计，每 tick 扫 9×9×9 个方块是纯浪费。
     */
    private int farmCooldown;

    /** 正在跟她交互的玩家 UUID。null = 没有。 */
    private UUID interactPlayer;

    // ---- 主动搭话（2026-09-21 第四批）----

    /**
     * 要开口时她得走到玩家多近（<b>距离平方</b>）。2.5 格。
     *
     * <p>再近就会挤进玩家身体里（实体互相推挤），再远又不像「特地过来跟你说话」。
     */
    public static final double PROACTIVE_ARRIVE_SQUARED = 6.25;

    /**
     * 「在她附近」的判定半径（格）。创建人待够时间的<b>计时圈</b>。
     *
     * <p>为什么给到 32 这么大：她自己是会到处游走的（{@code WanderAroundFarGoal}），
     * 玩家站着不动时两人的距离一直在变。圈太小的话「她溜达出圈」会被误判成
     * 「玩家走了」，5 分钟永远攒不满。出圈只是<b>暂停</b>计时、不清零，就是为了这个。
     */
    public static final double PROACTIVE_RANGE = 32.0;

    /**
     * 走过去最多给自己多久（tick）。200 = 10 秒。
     *
     * <p>到点还到不了就放弃这次 —— 她卡在树后面、玩家在墙那头，
     * 都不能变成「永远走过去」的状态。放弃<b>不消耗</b>本小时的次数。
     */
    public static final int PROACTIVE_APPROACH_TICKS = 200;

    /** 说完话在她身边再站多久（tick）。60 = 3 秒 —— 说完立刻转身走掉太像路人。 */
    public static final int PROACTIVE_LINGER_TICKS = 60;

    /** 最多记几句「最近主动说过的话」（防重复用）。 */
    public static final int PROACTIVE_SAID_LIMIT = 6;

    /** 原版「夜里」的时间窗（tick of day，和玩家能上床的窗口一致）。 */
    private static final long NIGHT_FROM = 12542L;
    private static final long NIGHT_TO = 23459L;

    /**
     * 造她的那个玩家（契约道具 / {@code /mymeido summon} 时记下）。null = 不知道。
     *
     * <p>★ 与 {@link MeidoMission#dispatchedBy()} 是<b>两回事</b>：那个是「这次活谁派的」、
     * 每派一次都可能换人、干完就清；这个是「她是谁的」，一辈子只有一个。
     * 主动搭话只认这个（要求写得很清楚：<b>创建</b>她的那个玩家）。
     */
    private UUID ownerUuid;

    /**
     * 创建人在附近<b>累计</b>待了多少 tick。
     *
     * <p>刻意是累计而不是「连续计时」：见 {@link #PROACTIVE_RANGE}。
     * 出圈不清零，只有「她/他不在同一维度、人下线、这次搭话已经发生了」才重新算。
     */
    private int ownerDwellTicks;

    /** 本小时已经主动搭话几次。 */
    private int proactiveCount;

    /** 上面那个计数属于哪个「小时桶」（{@code getTime()/1000}）。-1 = 还没记过。 */
    private long proactiveHour = -1L;

    /** 最近主动说过的话（防重复）。随存档走 —— 重启之后也不会把同一句再说一遍。 */
    private final Deque<String> proactiveSaid = new ArrayDeque<>();

    /** 正在走向玩家准备搭话的剩余 tick。0 = 没这个打算。 */
    private int approachTicks;

    /** 这次要走向谁。null = 没有目标。 */
    private UUID approachTarget;

    /** 说完了还要在她身边站一会儿的剩余 tick。 */
    private int lingerTicks;

    /**
     * 对话历史（三期 A4-3）：环形队列，条目是 {@code [role, content]} ——
     * role 取 {@code "user"} / {@code "assistant"}，由 {@link com.mymeido.ai.MeidoAi} 维护。
     *
     * <p>放在实体身上而不是 AI 模块里，是因为生命周期天然跟实体走：
     * 她没了历史也该没（已随 NBT 持久化：MeidoHistory 键，重启不失忆）。
     * 访问方一律 {@code synchronized (history)} —— 写在 HTTP 回调线程、
     * 读在主线程拼 prompt，两边不是同一个线程。
     */
    private final Deque<String[]> aiHistory = new ArrayDeque<>();

    /**
     * 记忆摘要（三期记忆系统）：近期对话每攒满一轮由 LLM 压缩成要点，
     * 老对话从环形队列里清掉、要点留在这里 —— 上下文有界化（A3/29 章）。
     * 随存档走（NBT MeidoAiSummary），重启不失忆。空串 = 还没有摘要。
     */
    private String aiSummary = "";

    /**
     * 「收下一件东西」的结果。用来决定她说哪句话、以及要不要掉在地上。
     */
    public enum AcceptResult {
        /** 穿身上了（护甲 / 盾 / 主手武器）。 */
        EQUIPPED,
        /** 塞进背包了。 */
        STORED,
        /** 背包满了，掉回地上。 */
        OVERFLOW,
        /** 完全没要（空气）。 */
        IGNORED
    }

    public MeidoEntity(EntityType<? extends PathAwareEntity> entityType, World world) {
        super(entityType, world);
        // 永不消失：这是「养一个住下来的人」，不是「刷出来的一只怪」。
        this.setPersistent();
        // 不掉经验（她不是可击杀目标）。
        this.experiencePoints = 0;
        // ★ 设计稿 A4-1 的硬要求：默认什么都不捡。
        // 原版 MobEntity 的默认值本来就是 false，这里显式写出来是防后人「顺手打开」——
        // 一开她就会把地上所有掉落物（包括你掉进岩浆前扔出来的钻石）全收了。
        this.setCanPickUpLoot(false);
        this.applyNameStyle();
    }

    /**
     * 跟随距离：不光是「她能看见多远」，原版寻路 A* 的搜索半径也挂在它上面。
     *
     * <p>2026-09-20 由 32 提到 <b>96</b> —— 派活半径放宽到 64 格后，
     * 这个值若还留在 32，就会出现「活派得出去、她也确实在往那儿走，
     * 但走到一半路径算不出来就原地站着」的怪现象（路径超出搜索半径）。
     * 给 96 = 64 派活距离 + 一段余量，够她从玩家身边一直走到目标点。
     */
    public static final double DESIGN_FOLLOW_RANGE = 96.0;

    /** 注册时用：血量 / 移速取上面的设计值，跟随距离与抬腿高度按玩家量级给。 */
    public static DefaultAttributeContainer.Builder createMeidoAttributes() {
        return MobEntity.createMobAttributes()
                .add(MeidoCompat.ATTR_MAX_HEALTH, DESIGN_MAX_HEALTH)
                .add(MeidoCompat.ATTR_MOVEMENT_SPEED, DESIGN_MOVEMENT_SPEED)
                .add(MeidoCompat.ATTR_FOLLOW_RANGE, DESIGN_FOLLOW_RANGE)
                .add(MeidoCompat.ATTR_STEP_HEIGHT, 0.6)
                .add(MeidoCompat.ATTR_ATTACK_DAMAGE, DESIGN_ATTACK_DAMAGE);
    }

    /**
     * 把上面的「设计值」回写进属性。注册时写一次，<b>读档后再写一次</b>。
     *
     * <p>为什么读档后必须再写：MC 会把实体属性的<b>基础值</b>一并存进 NBT，
     * 而 {@code LivingEntity.readCustomDataFromNbt} 会用存档里的旧数值盖掉注册默认值。
     * 后果就是「移速改了，世界里已有的女仆却纹丝不动，非得重新召一只才行」。
     *
     * <p>我们把这些常量当设计值，让它们赢过老存档；
     * 玩家自己用 {@code /attribute} 加的 modifier 不受影响 —— 这里动的只是 base。
     */
    private void applyDesignAttributes() {
        setAttributeBase(MeidoCompat.ATTR_MOVEMENT_SPEED, DESIGN_MOVEMENT_SPEED);
        setAttributeBase(MeidoCompat.ATTR_MAX_HEALTH, DESIGN_MAX_HEALTH);
        setAttributeBase(MeidoCompat.ATTR_ATTACK_DAMAGE, DESIGN_ATTACK_DAMAGE);
    }

    private void setAttributeBase(RegistryEntry<EntityAttribute> attribute, double value) {
        EntityAttributeInstance instance = this.getAttributeInstance(attribute);
        if (instance != null) {
            instance.setBaseValue(value);
        }
    }

    @Override
    protected void initGoals() {
        // 顺序即优先级（数字小的优先）。
        //
        // ⚠️ 别给两个 Goal 用同一个优先级然后指望「后加的那个会排后面」：
        // 1.21 的 GoalSelector 内部是 ObjectLinkedOpenHashSet，同优先级时按<b>加入顺序</b>
        // 依次判断，先加的那个先抢到控制权。分配优先级比赌顺序清楚。
        this.goalSelector.add(0, new SwimGoal(this));
        // 交互排在最前（仅次于防溺水）：你一右键她，游走就该立刻被打断，不是「走完这一步再说」。
        this.goalSelector.add(1, new MeidoInteractGoal(this));
        // 被玩家打后的反应（A4-4）排在战斗前面：逃跑 / 还手优先于打怪。
        // 同优先级先插入的先跑 —— Flee > Retaliate > Guard。
        this.goalSelector.add(2, new MeidoFleeGoal(this));
        this.goalSelector.add(2, new MeidoRetaliateGoal(this));
        // 守卫战斗排第二：岗位上有怪就优先开打（打完它自己收工，岗位 Goal 接着把她带回原位）。
        this.goalSelector.add(2, new MeidoGuardGoal(this));
        // 天黑回家排第三：只在游走模式生效，跟 MissionGoal（同优先级但互斥——
        // Mission 在非游走模式才 active）不会打架；战斗（2）随时能打断回家的路。
        // ★ 主动搭话插在「回家」前面、同一个优先级：两者本来就不会同时成立
        //   （搭话只在白天、回家只在夜里），真撞上了也该以「先跟主人说句话」为准。
        this.goalSelector.add(3, new MeidoApproachOwnerGoal(this));
        this.goalSelector.add(3, new MeidoGoHomeGoal(this));
        // 派活排第四：接了任务（非游走）就压住游走，走到目标点/原地待命。
        this.goalSelector.add(4, new MeidoMissionGoal(this));
        this.goalSelector.add(5, new WanderAroundFarGoal(this, 0.6));
        this.goalSelector.add(6, new LookAtEntityGoal(this, PlayerEntity.class, 8.0f));
        this.goalSelector.add(7, new LookAroundGoal(this));

        // targetSelector 故意保持为空 —— 中立，不主动攻击。
        // 想让她「护卫」的时候再往这里加目标，别提前埋雷。
    }

    @Override
    protected void initDataTracker(DataTracker.Builder builder) {
        super.initDataTracker(builder);
        // 默认皮肤 = 皮肤库里的第一位（不是写死的 hoshino）：
        // 玩家只放了一张 yuuka.png 时，新女仆就该是 yuuka，而不是一张找不到图的 hoshino。
        builder.add(SKIN, MeidoSkinRegistry.defaultSkin().getId());
        builder.add(EMOTION, MeidoEmotion.NEUTRAL.ordinal());
        builder.add(EMOTION_LEFT, 0);
        builder.add(COLOR, MeidoColor.PINK.ordinal());
        builder.add(NICKNAME, "");
        builder.add(MODE_ID, MeidoModeRegistry.DEFAULT_MODE_ID);
    }

    @Override
    public void tick() {
        super.tick();
        if (MeidoCompat.worldOf(this).isClient()) {
            return;
        }
        // 交互状态倒计时。归零 = 她重新回到自己的日常（游走）。
        if (this.interactTicks > 0) {
            this.interactTicks--;
            if (this.interactTicks == 0) {
                this.interactPlayer = null;
            }
        }
        // 情绪倒计时：到 0 自动回落 neutral。客户端只读，不自己算时间，
        // 这样中途进服的玩家也能看到正确的情绪状态。
        int left = this.dataTracker.get(EMOTION_LEFT);
        if (left > 0) {
            left--;
            this.dataTracker.set(EMOTION_LEFT, left);
            if (left == 0) {
                this.dataTracker.set(EMOTION, MeidoEmotion.NEUTRAL.ordinal());
            }
        }
        // 先清「干活的临时状态」，再干活：换模式的路径有好几条，
        // 让清理跟着「当前模式」走，比在每个换模式的地方各调一次靠谱。
        this.tickWorkCleanup();
        this.tickChunkLoading();
        this.tickRegeneration();
        this.tickNightStorage();
        this.tickDumpMission();
        this.tickFishingMission();
        this.tickFarmMission();
        this.tickProactiveChat();
    }

    /**
     * A4：「晚上若床附近有箱子 → 自动把物品放进去」——但<b>食物、武器、护甲不放</b>。
     *
     * <p>触发条件：绑过家 + 在睡觉时间窗 + 已经走到床边（4 格内）+ 今晚还没收过。
     * 收纳目标：床 5×4×5 范围内<b>第一个找到的容器</b>（箱子/木桶/任何带 Inventory 的方块实体）。
     * 有没有找到容器都算「今晚试过了」，不会每 tick 翻箱倒柜。
     */
    private void tickNightStorage() {
        long timeOfDay = MeidoCompat.worldOf(this).getTimeOfDay() % 24000L;
        if (timeOfDay < 12542L || timeOfDay > 23459L) {
            this.storedTonight = false;   // 天亮重置，明晚再收。
            return;
        }
        if (this.storedTonight || !this.mission.hasHome()) {
            return;
        }
        BlockPos home = this.mission.home();
        if (this.squaredDistanceTo(Vec3d.ofCenter(home)) > 16) {
            return;   // 还没到家，站着别乱掏。
        }
        this.storedTonight = true;
        for (BlockPos pos : BlockPos.iterate(
                home.add(-5, -2, -5), home.add(5, 2, 5))) {
            if (MeidoCompat.worldOf(this).getBlockEntity(pos) instanceof Inventory chest) {
                int moved = this.transferKeepItems(chest);
                if (moved > 0) {
                    this.playSound(SoundEvents.BLOCK_CHEST_CLOSE, 0.6f, 1.0f);
                }
                break;   // 只翻第一个容器 —— 全村翻箱倒柜太吓人。
            }
        }
    }

    /** 把她背包里「可以放的」转移到容器。返回动了几个<b>格子</b>。 */
    private int transferKeepItems(Inventory chest) {
        int moved = 0;
        for (int i = 0; i < this.inventory.size(); i++) {
            ItemStack stack = this.inventory.get(i);
            if (stack.isEmpty() || this.shouldKeepItem(stack)) {
                continue;
            }
            ItemStack leftover = this.addToChest(chest, stack);
            int taken = stack.getCount() - leftover.getCount();
            if (taken > 0) {
                stack.decrement(taken);
                if (stack.isEmpty()) {
                    this.inventory.take(i);
                }
                moved++;
            }
        }
        if (moved > 0) {
            this.inventory.backing().markDirty();
        }
        return moved;
    }

    /**
     * 把一份物品塞进容器的空格/同类格（手写 —— 1.21.1 的 {@code Inventory}
     * 接口没有 addStack，那是 {@code SimpleInventory} 自己的方法）。
     * 返回没塞下的剩余。
     */
    private ItemStack addToChest(Inventory chest, ItemStack stack) {
        ItemStack remainder = stack.copy();
        for (int i = 0; i < chest.size() && !remainder.isEmpty(); i++) {
            ItemStack slot = chest.getStack(i);
            if (!slot.isEmpty()
                    && ItemStack.areItemsAndComponentsEqual(slot, remainder)
                    && slot.getCount() < slot.getMaxCount()) {
                int move = Math.min(slot.getMaxCount() - slot.getCount(), remainder.getCount());
                slot.increment(move);
                remainder.decrement(move);
            }
        }
        for (int i = 0; i < chest.size() && !remainder.isEmpty(); i++) {
            if (chest.getStack(i).isEmpty()) {
                chest.setStack(i, remainder.copy());
                remainder.setCount(0);
            }
        }
        if (!stack.equals(remainder)) {
            chest.markDirty();
        }
        return remainder;
    }

    /** A4 的白名单（其实是黑名单的反面）：食物 / 武器 / 钓竿 / 护甲，留在她身上。 */
    private boolean shouldKeepItem(ItemStack stack) {
        if (stack.contains(DataComponentTypes.FOOD)) {
            return true;
        }
        if (isMeleeWeapon(stack) || stack.isOf(Items.FISHING_ROD)) {
            return true;
        }
        EquipmentSlot slot = MeidoCompat.equipmentSlotOf(stack);
        if (slot != null) {
            switch (slot) {
                case FEET, LEGS, CHEST, HEAD -> {
                    return true;
                }
                default -> {
                }
            }
        }
        return false;
    }

    /**
     * 回血 tick：先试吃饭（快），吃饱不够再自然回（慢）。
     * 吃饭不受脱战宽限限制 —— 打带跑拉开的那几秒正好扒两口。
     */
    private void tickRegeneration() {
        if (this.eatCooldown > 0) {
            this.eatCooldown--;
        }
        if (this.getHealth() >= this.getMaxHealth() - 0.01f) {
            this.regenTimer = 0;
            return;   // 满血，什么都不干。
        }
        if (this.eatCooldown <= 0) {
            float healed = this.tryEatFromBackpack();
            if (healed > 0) {
                this.heal(healed);
                this.eatCooldown = EAT_INTERVAL;
                MeidoCompat.worldOf(this).playSound(null, this.getBlockPos(),
                        MeidoCompat.eatSound(), this.getSoundCategory(), 0.8f, 1.0f);
                return;
            }
        }
        // 自然回血：脱战 3 秒后才起步，每 5 秒 +1。
        if (this.age - this.lastHurtAge >= REGEN_HURT_GRACE
                && ++this.regenTimer >= REGEN_INTERVAL) {
            this.regenTimer = 0;
            this.heal(1.0f);
        }
    }

    /**
     * 从背包吃一份食物，返回回血量（原版营养值）；背包里没有吃的返回 0。
     *
     * <p>只扣 1 份。营养值查原版 {@code DataComponentTypes.FOOD} ——
     * 所以任何「原版能吃的」（面包/肉/胡萝卜/金苹果…）她都能吃，mod 食物带了 FOOD
     * 组件也自动兼容。1.21.1 起食物附带的状态效果（金苹果的再生等）挪进了
     * Consumable 组件，那是玩家进食链路的；我们只取营养值当回血量，不模拟状态效果。
     */
    private float tryEatFromBackpack() {
        for (int slot = 0; slot < this.inventory.size(); slot++) {
            ItemStack stack = this.inventory.get(slot);
            net.minecraft.component.type.FoodComponent food =
                    stack.get(DataComponentTypes.FOOD);
            if (food == null || food.nutrition() <= 0) {
                continue;
            }
            float heal = food.nutrition();
            stack.decrement(1);
            if (stack.isEmpty()) {
                this.inventory.take(slot);
            }
            return heal;
        }
        return 0;
    }

    // 1.21.5 起 LivingEntity.damage 要带 ServerWorld（javap 实锤），覆写签名跟着变。
    //? if >=1.21.11 {
    @Override
    public boolean damage(ServerWorld world, net.minecraft.entity.damage.DamageSource source, float amount) {
        boolean hurt = super.damage(world, source, amount);
        this.afterDamage(source, hurt);
        return hurt;
    }
    //?} else {
    /*@Override
    public boolean damage(net.minecraft.entity.damage.DamageSource source, float amount) {
        boolean hurt = super.damage(source, amount);
        this.afterDamage(source, hurt);
        return hurt;
    }
    *///?}

    /** damage 覆写的共用尾巴（脱战计时 + 被玩家打的反应），两版签名汇到这里。 */
    private void afterDamage(net.minecraft.entity.damage.DamageSource source, boolean hurt) {
        if (hurt) {
            // 自然回血的脱战计时从「最后一次真受伤」重新起算。
            this.lastHurtAge = this.age;
        }
        // A4-4：被玩家打 = 好感度 −10 + 按档位做反应。
        if (hurt && source.getAttacker() instanceof ServerPlayerEntity attacker) {
            this.addFavor(-10, attacker);
            this.onAttackedByPlayer(attacker);
        }
    }

    /** 好感度档位：3=极高(≥90) / 2=高(≥70) / 1=中(≥30) / 0=低。阈值设计稿 A4-4，可调。 */
    public int favorTier() {
        if (this.favor >= 90) {
            return 3;
        }
        if (this.favor >= 70) {
            return 2;
        }
        return this.favor >= 30 ? 1 : 0;
    }

    /** 档位中文名（内部排查用，不给玩家看 —— 好感度是隐性数值）。 */
    public String favorTierName() {
        return switch (this.favorTier()) {
            case 3 -> "极高";
            case 2 -> "高";
            case 1 -> "中";
            default -> "低";
        };
    }

    /** 给玩家的<b>模糊</b>好感度描述（不露数字不露档位，只能品出来）。 */
    public String favorHint() {
        return switch (this.favorTier()) {
            case 3 -> MeidoLocale.pick("她看你的眼神里有藏不住的欢喜，什么都愿意为你做。",
                    "Her eyes light up whenever she looks at you — she'd do anything for you.");
            case 2 -> MeidoLocale.pick("她挺喜欢你的，说话时眼睛是弯着的。",
                    "She rather likes you; her eyes crinkle when she speaks.");
            case 1 -> MeidoLocale.pick("关系还算亲近，但也就是普通的距离。",
                    "You're fairly close, but it's just a normal distance.");
            default -> MeidoLocale.pick("她对你还存着戒心，说话总是客客气气的。",
                    "She's still wary of you, always polite and careful with her words.");
        };
    }

    public int getFavor() {
        return this.favor;
    }

    /** 当前是第几个游戏日（{@code getTime()/24000}）。{@code getTime} 是世界总时间，不随 /time set 回跳。 */
    private long currentGameDay() {
        return MeidoCompat.worldOf(this).getTime() / 24000L;
    }

    /**
     * 好感度增减（夹在 0~100）。
     *
     * <p>★ <b>数值完全隐藏</b>（2026-09-20 绯色定）：不弹 action bar、不给档位名 ——
     * 玩家只能从她的说法和反应里<b>感觉</b>出来（被打了会委屈/还手/逃跑，
     * 喂食了下次聊天语气会不同）。galgame 的好感度露数字就变养成游戏了。
     */
    public void addFavor(int delta, ServerPlayerEntity source) {
        this.favor = MathHelper.clamp(this.favor + delta, 0, 100);
    }

    /**
     * 被玩家打了，按 A4-4 的表做反应。注意「有武器」看她<b>身上</b>
     * （手 + 背包）—— 武器平时收在背包里，不算没带刀。
     */
    private void onAttackedByPlayer(ServerPlayerEntity attacker) {
        this.fleeFromPos = null;
        this.retaliateTarget = null;
        boolean hasWeapon = isMeleeWeapon(this.getEquippedStack(EquipmentSlot.MAINHAND))
                || this.weaponSlotInInventory() >= 0;
        switch (this.favorTier()) {
            case 3 -> MeidoChat.say(this, MeidoLocale.pick("呜……为什么是我……我做错什么了吗……",
                    "Sob… why me… what did I do wrong…"));
            case 2 -> {
                // 高档：先硬扛；掉到残血线（25%）才逃。damage() 走到这里时血已扣完，
                // 所以直接看当前血量。
                if (this.getHealth() <= this.getMaxHealth() * 0.25f) {
                    this.startFleeing(MeidoCompat.posOf(attacker));
                } else {
                    MeidoChat.say(this, MeidoLocale.pick("……我、我不还手。别生气了好不好。",
                            "…I won't fight back. Please don't be mad."));
                }
            }
            case 1 -> this.startFleeing(MeidoCompat.posOf(attacker));
            default -> {
                if (hasWeapon) {
                    this.retaliateTarget = attacker;
                    this.retaliateUntil = this.age + 200;
                    this.holdWeapon();
                    MeidoChat.say(this, MeidoLocale.pick("……够了。你也别怪我不客气。",
                            "…That's enough. Don't blame me for what happens next."));
                } else {
                    this.startFleeing(MeidoCompat.posOf(attacker));
                }
            }
        }
    }

    /** 进入逃跑状态：往远离攻击者的方向跑 5 秒。 */
    private void startFleeing(Vec3d from) {
        this.fleeFromPos = from;
        this.fleeUntil = this.age + 100;
    }

    public boolean isFleeing() {
        return this.age < this.fleeUntil && this.fleeFromPos != null;
    }

    /** 逃跑的方向参考点：从她指向这个点的反方向跑。 */
    public Vec3d fleeFromPos() {
        return this.fleeFromPos == null ? MeidoCompat.posOf(this) : this.fleeFromPos;
    }

    public boolean isRetaliating() {
        return this.age < this.retaliateUntil
                && this.retaliateTarget != null && this.retaliateTarget.isAlive();
    }

    public LivingEntity retaliateTarget() {
        return this.retaliateTarget;
    }

    /** 还手收工（Goal stop 时调）：清状态 + 武器收回背包。 */
    public void clearRetaliation() {
        this.retaliateTarget = null;
        this.retaliateUntil = -1L;
        this.stowWeapon();
    }

    /**
     * ★ A4-5：把她周围 3×3 区块钉在「全负荷 tick」档位（和 {@code /forceload} 同级）。
     *
     * <p>票挂在<b>区块</b>上不跟人走：她跨区块了就撤旧票挂新票（对比集合，差多少补多少），
     * 不是每 tick 撤光重挂 —— {@code removeTicket} 会触发区块等级重算，乱刷有开销。
     */
    private void tickChunkLoading() {
        if (++this.chunkTicketTimer < CHUNK_TICK_INTERVAL) {
            return;
        }
        this.chunkTicketTimer = 0;
        if (!(MeidoCompat.worldOf(this) instanceof ServerWorld world)) {
            return;
        }
        ChunkPos center = new ChunkPos(this.getBlockPos());
        LongSet wanted = new LongOpenHashSet();
        for (int dx = -LOAD_RADIUS_CHUNKS; dx <= LOAD_RADIUS_CHUNKS; dx++) {
            for (int dz = -LOAD_RADIUS_CHUNKS; dz <= LOAD_RADIUS_CHUNKS; dz++) {
                wanted.add(new ChunkPos(center.x + dx, center.z + dz).toLong());
            }
        }
        ServerChunkManager chunks = world.getChunkManager();
        int level = ChunkLevels.getLevelFromType(ChunkLevelType.FULL);
        // 撤掉不再需要的
        for (long held : this.heldChunkTickets.toLongArray()) {
            if (!wanted.contains(held)) {
                //? if >=1.21.11 {
                chunks.removeTicket(LOAD_TICKET, new ChunkPos(held), level);
                //?} else {
                /*chunks.removeTicket(LOAD_TICKET, new ChunkPos(held), level, Unit.INSTANCE);
                *///?}
                this.heldChunkTickets.remove(held);
            }
        }
        // 挂上还缺的
        for (long want : wanted.toLongArray()) {
            if (!this.heldChunkTickets.contains(want)) {
                //? if >=1.21.11 {
                chunks.addTicket(LOAD_TICKET, new ChunkPos(want), level);
                //?} else {
                /*chunks.addTicket(LOAD_TICKET, new ChunkPos(want), level, Unit.INSTANCE);
                *///?}
                this.heldChunkTickets.add(want);
            }
        }
    }

    /** 她被杀/被清除/区块随她卸载时，把自己挂的票全撤掉，不给世界留幽灵加载。 */
    private void removeChunkTickets() {
        if (!(MeidoCompat.worldOf(this) instanceof ServerWorld world)) {
            return;
        }
        ServerChunkManager chunks = world.getChunkManager();
        int level = ChunkLevels.getLevelFromType(ChunkLevelType.FULL);
        for (long held : this.heldChunkTickets.toLongArray()) {
            //? if >=1.21.11 {
            chunks.removeTicket(LOAD_TICKET, new ChunkPos(held), level);
            //?} else {
            /*chunks.removeTicket(LOAD_TICKET, new ChunkPos(held), level, Unit.INSTANCE);
            *///?}
        }
        this.heldChunkTickets.clear();
    }

    @Override
    public void onRemoved() {
        this.removeChunkTickets();
        super.onRemoved();
    }

    /**
     * 蹲下 + 右键 = 打开她的背包界面（原版单排箱子 UI，9 格正好是她的背包容量）。
     *
     * <p>为什么不做成自定义 Screen：客户端要同步注册 ScreenHandler，两边版本要咬死，
     * 而原版 {@code GENERIC_9X1} 的格子增删改查、光标物品、关界面回落全都是现成的 ——
     * 女仆背包没有「锁格子」这种特殊规则，白拿是最稳的。
     * 不蹲下的右键仍是打招呼，老习惯不受影响。
     */
    private ActionResult openInventoryScreen(ServerPlayerEntity player) {
        this.inventory.backing().markDirty();
        player.openHandledScreen(new SimpleNamedScreenHandlerFactory(
                (syncId, playerInventory, p) -> new GenericContainerScreenHandler(
                        ScreenHandlerType.GENERIC_9X1, syncId, playerInventory,
                        this.inventory.backing(), 1),
                Text.literal(this.getNickname().isEmpty()
                        ? MeidoLocale.pick("女仆的背包", "Maid's backpack")
                        : this.getNickname() + MeidoLocale.pick(" 的背包", "'s backpack"))));
        return ActionResult.SUCCESS;
    }

    /**
     * 「丢弃物品」模式的一次性动作：<b>走到指定位置 → 把背包倒空 → 自己回到游走</b>。
     *
     * <p>为什么放 {@code tick} 而不是做成一个 Goal：
     * 它不是「持续行为」，是<b>一次性任务 + 回头改状态</b>。
     * 写成 Goal 的话，Goal 得负责改自己的任务状态（谁改了模式、谁该停），
     * 很容易出现「Goal 停了但模式还在」的不一致。放在实体 tick 里，
     * 「做完就把模式设回游走」是同一段代码里的两句话，不可能漏。
     *
     * <p>没给目标位置时（{@code target} 为空）就在原地倒 ——
     * 设计稿里这个模式的 target 是 OPTIONAL，本来就允许「随手倒在脚下」。
     */
    private void tickDumpMission() {
        if (this.mission.type() != MeidoModeType.DUMP) {
            return;
        }
        BlockPos target = this.mission.target();
        // ★ 用和 MeidoMissionGoal 同一个阈值（以前这里自己写了个 4.0，
        //   两处一旦不同就会出现「Goal 觉得到了、这里觉得还没到」，她站着不倒东西）。
        double arriveSquared = MeidoMissionGoal.ARRIVE_SQUARED;
        if (target != null
                && this.squaredDistanceTo(target.getX() + 0.5, target.getY(), target.getZ() + 0.5) > arriveSquared) {
            // 还没走到，交给 MeidoMissionGoal 带路。
            return;
        }
        int count = this.dropBackpack();
        this.finishOneShotMission();
        if (count > 0) {
            this.setEmotion(MeidoEmotion.HAPPY);
            MeidoChat.say(this, MeidoLocale.pick("东西都放这儿了。", "I left everything here."));
        }
    }

    // ------------------------------------------------------------------
    // 干活（A4-2 的实际行为：钓鱼 / 种植）
    // ------------------------------------------------------------------

    /** 甩一次竿要等多久（tick）。原版基准就是 100~600（5~30 秒），竿上有「饵钓」再往下减。 */
    private static final int FISH_WAIT_MIN = 100;
    private static final int FISH_WAIT_RANDOM = 500;

    /** 「看一圈地」的间隔（tick）。1 秒一次：作物以分钟计，够快也够省。 */
    private static final int FARM_INTERVAL = 20;

    /** 一轮干活最多动几块地。干太多会一次刷一屏聊天，也没必要。 */
    private static final int FARM_ACTIONS_PER_ROUND = 6;

    /**
     * 钓鱼：<b>到位 → 甩竿 → 等咬钩 → 收线拿鱼 → 下一竿</b>。
     *
     * <h2>先决条件：她身上得有一根钓鱼竿</h2>
     *
     * <p>派活时就会查（见 {@link #assignMode}），这里每次甩竿前再查一遍（竿被拿走了要认输）。
     * 竿是<b>玩家给她的那一根</b>，附魔从竿上读：
     * <ul>
     *   <li><b>饵钓（Lure）</b>：缩短等待 —— 公式照抄原版
     *       {@code wait = nextInt(100,600) - (int)(getFishingTimeReduction * 20)}；</li>
     *   <li><b>海之眷顾（Luck of the Sea）</b>：改掉落 —— 原版把它当 {@code luck}
     *       传进战利品表上下文，我们传同一个数。</li>
     * </ul>
     *
     * <h2>为什么不用原版鱼钩</h2>
     *
     * <p>{@code FishingBobberEntity.tick()} 开头就是「{@code getPlayerOwner()} 为空就 {@code return}」，
     * 而它唯一的构造器只吃 {@code PlayerEntity} ——
     * owner 不是玩家时浮标<b>永远不会 tick</b>：不落水、不算咬钩，连钓线都不画
     * （线的渲染也要 {@code getPlayerOwner()}）。想用它就得造一个假玩家实体，代价太大。
     *
     * <p>所以自己写一套，但<b>数据仍然用原版的</b>：收线时抽
     * {@link LootTables#FISHING_GAMEPLAY}（这张表内部已经按 {@code quality} 分好了
     * 鱼 / 垃圾 / 宝藏 三支，{@code luck} 参数直接影响三支的概率），外观只做「手里拿竿 + 水面冒粒子」。
     * 中间那段「浮标落水 → 拉线」的物理省略掉 —— 玩家要的是钓上来什么。
     */
    private void tickFishingMission() {
        if (this.mission.type() != MeidoModeType.FISH) {
            return;
        }
        BlockPos post = this.mission.target();
        if (post == null || !this.hasArrivedAt(post)) {
            return;
        }

        // ★ 干活前先确认「脚边还有水」。水被填了 / 她站的地方被挖了，
        //   就该重新找一块，而不是对着空气甩一辈子竿。
        Optional<BlockPos> water = MeidoWorkSpots.findWaterNear(MeidoCompat.worldOf(this), post, MeidoWorkSpots.WORK_RADIUS);
        if (water.isEmpty()) {
            // 自愈用更小的半径重找（8 格会把她带到别的池塘去，那就不是「这片水」了）。
            Optional<BlockPos> again = MeidoWorkSpots.resolveFishingSpot(MeidoCompat.worldOf(this), post, 6);
            if (again.isEmpty()) {
                this.endMission(MeidoLocale.pick("钓鱼干不下去了：附近没有可以下钩的水面了",
                        "Can't fish anymore: there's no water nearby to cast a line into"));
                return;
            }
            this.mission.setTarget(again.get());
            return;
        }

        if (this.fishWaitTicks <= 0) {
            // 甩竿：先把她的竿拿到手上（没有竿就当场收工），再定「等多久」。
            ItemStack rod = this.holdFishingRod();
            if (rod.isEmpty()) {
                this.endMission(MeidoLocale.pick(
                        "钓鱼干不下去了：她身上没有钓鱼竿了（右键递给她或 Q 扔给她一根）",
                        "Can't fish anymore: she has no fishing rod (right-click to hand her one, or press Q to drop it)"));
                return;
            }
            this.swingHand(Hand.MAIN_HAND);
            // 等待时间照抄原版：nextInt(100, 600) 减去「饵钓」的缩减量。
            // （javap 过 FishingRodItem.use：原版就是 getFishingTimeReduction(...) * 20f 取整。）
            int wait = FISH_WAIT_MIN + this.getRandom().nextInt(FISH_WAIT_RANDOM);
            if (MeidoCompat.worldOf(this) instanceof ServerWorld serverWorld) {
                wait -= (int) (EnchantmentHelper.getFishingTimeReduction(serverWorld, rod, this) * 20.0f);
            }
            this.fishWaitTicks = Math.max(1, wait);
            this.spawnFishingParticles(water.get(), false);
            return;
        }

        this.fishWaitTicks--;
        if (this.fishWaitTicks % 40 == 0) {
            // 等的时候偶尔冒两个泡 —— 让「水里有东西」这件事看得见。
            this.spawnFishingParticles(water.get(), false);
        }
        if (this.fishWaitTicks <= 0) {
            this.reelIn(water.get());
        }
    }

    /**
     * 收线：抽原版钓鱼战利品表，东西进她背包（塞不下就掉脚边），然后等下一次甩竿。
     *
     * <p>{@code luck} 传的是<b>她那根竿上海之眷顾的等级</b>（原版是
     * {@code luckBonus + 玩家自身 luck}；她不是玩家，自身恒为 0）——
     * luck 越高，战利品表里「宝藏」支的权重越大、垃圾支越小，和玩家自己钓完全同一条公式。
     */
    private void reelIn(BlockPos water) {
        if (!(MeidoCompat.worldOf(this) instanceof ServerWorld serverWorld)) {
            return;
        }
        this.swingHand(Hand.MAIN_HAND);
        this.spawnFishingParticles(water, true);

        // 竿在等待期间被拿走（理论上）就退回无附魔的默认竿，别让 TOOL 缺失。
        ItemStack rod = this.currentFishingRod();
        if (rod.isEmpty()) {
            rod = new ItemStack(Items.FISHING_ROD);
        }
        LootTable table = serverWorld.getServer().getReloadableRegistries().getLootTable(LootTables.FISHING_GAMEPLAY);
        // 1.21.11 改名 LootWorldContext（Builder 方法面一致，javap 核实）。
        // ★ 分支必须整条语句复制 —— stonecutter 不支持把标记切在未完成表达式的中间。
        //? if >=1.21.11 {
        LootWorldContext params = new LootWorldContext.Builder(serverWorld)
                .add(LootContextParameters.ORIGIN, Vec3d.ofCenter(water))
                // ★ TOOL 传她那根真竿：战利品表 / 未来的条件都会看到真实附魔。
                .add(LootContextParameters.TOOL, rod)
                .addOptional(LootContextParameters.THIS_ENTITY, this)
                .luck(EnchantmentHelper.getFishingLuckBonus(serverWorld, rod, this))
                .build(LootContextTypes.FISHING);
        //?} else {
        /*LootContextParameterSet params = new LootContextParameterSet.Builder(serverWorld)
                .add(LootContextParameters.ORIGIN, Vec3d.ofCenter(water))
                // ★ TOOL 传她那根真竿：战利品表 / 未来的条件都会看到真实附魔。
                .add(LootContextParameters.TOOL, rod)
                .addOptional(LootContextParameters.THIS_ENTITY, this)
                .luck(EnchantmentHelper.getFishingLuckBonus(serverWorld, rod, this))
                .build(LootContextTypes.FISHING);
        *///?}

        ItemStack first = ItemStack.EMPTY;
        for (ItemStack loot : table.generateLoot(params)) {
            if (loot.isEmpty()) {
                continue;
            }
            if (first.isEmpty()) {
                first = loot.copy();
            }
            this.accept(loot);
        }
        if (!first.isEmpty()) {
            this.setEmotion(MeidoEmotion.HAPPY);
            MeidoChat.say(this, MeidoLocale.pick("钓到 ", "Caught a ") + first.getName().getString()
                    + MeidoLocale.pick(" 了。", "."));
        }
    }

    private void spawnFishingParticles(BlockPos water, boolean splash) {
        if (!(MeidoCompat.worldOf(this) instanceof ServerWorld serverWorld)) {
            return;
        }
        serverWorld.spawnParticles(
                splash ? ParticleTypes.SPLASH : ParticleTypes.FISHING,
                water.getX() + 0.5, water.getY() + 0.9, water.getZ() + 0.5,
                splash ? 12 : 3, 0.25, 0.0, 0.25, 0.02);
    }

    // ------------------------------------------------------------------
    // 钓鱼竿：她必须用「自己那根」（玩家给的），附魔才作数
    // ------------------------------------------------------------------

    /** 她现在能用的钓鱼竿：主手有竿就用主手的，否则翻背包。没有就是 EMPTY。 */
    private ItemStack currentFishingRod() {
        ItemStack hand = this.getEquippedStack(EquipmentSlot.MAINHAND);
        if (hand.isOf(Items.FISHING_ROD)) {
            return hand;
        }
        int slot = this.rodSlotInInventory();
        return slot < 0 ? ItemStack.EMPTY : this.inventory.get(slot);
    }

    /**
     * 甩竿前把竿「拿到手上」—— 纯粹为了看得见（{@code HeldItemFeatureRenderer} 只渲染主手）。
     *
     * <p>三种情况：
     * <ul>
     *   <li>主手已经是竿 → 直接用，什么都不动；</li>
     *   <li>主手空、竿在背包 → <b>真的从背包挪到手上</b>（拿的是同一个堆，不会凭空复制），
     *       记下 {@link #fishRodFromBackpack}，收工时挪回去；</li>
     *   <li>主手被剑之类占着 → 不动她的手，竿从背包里读附魔照用（少个视觉细节，不亏）。</li>
     * </ul>
     *
     * @return 能用的那根竿；她身上根本没有竿就返回 EMPTY（调用方该收工了）
     */
    private ItemStack holdFishingRod() {
        ItemStack hand = this.getEquippedStack(EquipmentSlot.MAINHAND);
        if (hand.isOf(Items.FISHING_ROD)) {
            return hand;
        }
        int slot = this.rodSlotInInventory();
        if (slot < 0) {
            return ItemStack.EMPTY;
        }
        if (hand.isEmpty()) {
            ItemStack rod = this.inventory.take(slot);
            this.equipStack(EquipmentSlot.MAINHAND, rod);
            this.fishRodFromBackpack = true;
            return rod;
        }
        return this.inventory.get(slot);
    }

    /** 收工把手上的竿挪回背包（背包满了掉在脚边）。 */
    private void unholdFishingRod() {
        if (!this.fishRodFromBackpack) {
            return;
        }
        this.fishRodFromBackpack = false;
        ItemStack hand = this.getEquippedStack(EquipmentSlot.MAINHAND);
        if (!hand.isOf(Items.FISHING_ROD)) {
            // 等待期间她手上的竿被换掉了（比如玩家扔了把剑给她）→ 这根竿现在就是她的，不动。
            return;
        }
        this.equipStack(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
        ItemStack leftover = this.inventory.add(hand);
        if (!leftover.isEmpty()) {
            MeidoCompat.dropStack(this, leftover);
        }
    }

    /** 背包里钓鱼竿在第几格；没有返回 -1。 */
    private int rodSlotInInventory() {
        for (int i = 0; i < this.inventory.size(); i++) {
            if (this.inventory.get(i).isOf(Items.FISHING_ROD)) {
                return i;
            }
        }
        return -1;
    }

    // ------------------------------------------------------------------
    // 武器：平时收在背包，只在攻击时上手（MeidoGuardGoal 开打/收工时调）
    // ------------------------------------------------------------------

    /** 近战武器 = 原版「剑」或「斧」标签里的东西。收手的判据只认这两类，别把钓竿当武器收了。 */
    private static boolean isMeleeWeapon(ItemStack stack) {
        return !stack.isEmpty() && (stack.isIn(ItemTags.SWORDS) || stack.isIn(ItemTags.AXES));
    }

    /**
     * 开打前把武器摸到主手。
     *
     * <ul>
     *   <li>主手已经是近战武器 → 直接用；</li>
     *   <li>主手空、背包里有 → <b>真的从背包挪到手上</b>（同一个堆，不复制；
     *       优先剑，没剑用斧，都没有就空手）；</li>
     *   <li>主手被别的东西占着 → 不动它，空手打（别为了打架把她的钓竿扔地上）。</li>
     * </ul>
     */
    /** 开打前把武器摸到主手。由 {@code MeidoGuardGoal.start()} 调。 */
    public void holdWeapon() {
        ItemStack hand = this.getEquippedStack(EquipmentSlot.MAINHAND);
        if (isMeleeWeapon(hand)) {
            return;
        }
        if (!hand.isEmpty()) {
            return;   // 主手有东西（钓竿之类）：空手打，别抢她的手。
        }
        int slot = this.weaponSlotInInventory();
        if (slot < 0) {
            return;   // 没武器就空手打，聊胜于无。
        }
        ItemStack weapon = this.inventory.take(slot);
        this.equipStack(EquipmentSlot.MAINHAND, weapon);
        this.weaponFromBackpack = true;
    }

    /** 战斗结束把武器收回背包（背包满了掉在脚边）。由 {@code MeidoGuardGoal.stop()} 和 tickWorkCleanup 调。 */
    public void stowWeapon() {
        this.weaponFromBackpack = false;
        ItemStack hand = this.getEquippedStack(EquipmentSlot.MAINHAND);
        if (!isMeleeWeapon(hand)) {
            return;   // 手上没有近战武器（空手/钓竿）→ 没什么可收的。
        }
        this.equipStack(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
        ItemStack leftover = this.inventory.add(hand);
        if (!leftover.isEmpty()) {
            MeidoCompat.dropStack(this, leftover);
        }
    }

    /** 背包里近战武器在第几格（优先剑，其次斧）；没有返回 -1。 */
    private int weaponSlotInInventory() {
        int axeSlot = -1;
        for (int i = 0; i < this.inventory.size(); i++) {
            ItemStack stack = this.inventory.get(i);
            if (stack.isIn(ItemTags.SWORDS)) {
                return i;
            }
            if (axeSlot < 0 && stack.isIn(ItemTags.AXES)) {
                axeSlot = i;
            }
        }
        return axeSlot;
    }

    /**
     * 种植：<b>到位 → 看一圈地 → 空地播种 / 熟了的收掉并补种</b>。
     *
     * <h2>按土质决定种什么</h2>
     *
     * <p>耕地（且光照 ≥8）→ 小麦，吃小麦种子；灵魂沙 → 地狱疣，吃地狱疣本身
     * （原版 {@code NetherWartBlock.canPlaceAt} 只认灵魂沙、不查光照，javap 核实）。
     * 一块田里两种土质混着也行，她按格分开处理。
     *
     * <h2>种子是硬前提：包里没有就种不了</h2>
     *
     * <p>播种（含收后的补种）都从她背包里扣 1 个「种子」，包里没有就跳过 ——
     * 凭空变出作物既不是「种植」，也测不出来。派活时就会查（见 {@link #assignMode}），
     * 活干到一半种子用光、且附近也没有长着的作物可收（收了能续种子）时，她会明说并回游走。
     *
     * <h2>收获范围：只收小麦和地狱疣</h2>
     *
     * <p>判定里写死了 {@code Blocks.WHEAT} / {@code Blocks.NETHER_WART}。
     * 地里如果是你种的胡萝卜，她一根都不碰 —— 抢玩家的作物比少干点活糟糕得多。
     * 收获数量抄原版量级：小麦 1 + 0~2 种子，地狱疣 2~4 个（时运不吃），
     * 顺序都是<b>先收后补种</b>，补种正好用刚收进包的「种子」。
     */
    private void tickFarmMission() {
        if (this.mission.type() != MeidoModeType.FARM) {
            return;
        }
        BlockPos post = this.mission.target();
        if (post == null || !this.hasArrivedAt(post)) {
            return;
        }
        if (this.farmCooldown > 0) {
            this.farmCooldown--;
            return;
        }
        // 自愈：地没了（被挖了 / 被踩了）就重新找一块，都找不到才认输。
        //（isFarmPlot 把「种了东西的耕地」也算在内，所以 plot 为空 = 方圆内压根没有耕地。）
        Optional<BlockPos> plot = MeidoWorkSpots.findFarmPlotNear(MeidoCompat.worldOf(this), post, MeidoWorkSpots.WORK_RADIUS);
        if (plot.isEmpty()) {
            Optional<BlockPos> again = MeidoWorkSpots.resolveFarmSpot(MeidoCompat.worldOf(this), post);
            if (again.isEmpty()) {
                this.endMission(MeidoLocale.pick(
                        "种植干不下去了：附近没有能种的耕地了（要先翻地，而且得有光照）",
                        "Can't farm anymore: no farmland to plant within range (till it with a hoe first, and it needs light)"));
                return;
            }
            this.mission.setTarget(again.get());
            return;
        }
        // ★ 种 = 消耗她包里的「种子」（小麦种子 / 地狱疣）；「包里没有」不立刻收工 ——
        //   地里只要还有长着的作物（哪怕没熟），熟了就能收、收了掉种子，循环还续得上。
        //   两者皆无才是死局。
        if (!this.hasPlantableSeeds()
                && !MeidoWorkSpots.hasCropNear(MeidoCompat.worldOf(this), post, MeidoWorkSpots.WORK_RADIUS)) {
            this.endMission(MeidoLocale.pick(
                    "种植干不下去了：她包里没有能种的东西了（Q 扔给她一些小麦种子或地狱疣）",
                    "Can't farm anymore: she has nothing to plant (press Q to drop her some wheat seeds or nether wart)"));
            return;
        }
        this.farmCooldown = FARM_INTERVAL;
        this.workFarming(post);
    }

    /** 把脚下周围看一圈：熟了的收、空地按土质种（耕地 → 小麦，灵魂沙 → 地狱疣）。 */
    private void workFarming(BlockPos post) {
        World world = MeidoCompat.worldOf(this);
        int radius = MeidoWorkSpots.WORK_RADIUS;
        int acted = 0;
        for (int dy = -1; dy <= 1; dy++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    BlockPos pos = post.add(dx, dy, dz);
                    BlockState state = world.getBlockState(pos);
                    if (state.isOf(Blocks.WHEAT)) {
                        if (state.getBlock() instanceof CropBlock crop && crop.isMature(state)) {
                            this.harvestCrop(pos);
                            acted++;
                        }
                    } else if (state.isOf(Blocks.NETHER_WART)) {
                        // 地狱疣不是 CropBlock（独立的 AGE 0~3 属性），成熟判定单独写。
                        if (state.get(NetherWartBlock.AGE) >= NetherWartBlock.MAX_AGE) {
                            this.harvestCrop(pos);
                            acted++;
                        }
                    } else if (state.isOf(Blocks.FARMLAND) && world.getBlockState(pos.up()).isAir()
                            // ★ 光照不够就别种小麦：原版在暗处根本种不下去，
                            //   我们绕过检查硬塞的话，那一格活不过一次邻格更新 ——
                            //   她会一直种、地里永远什么都没有（见 MeidoWorkSpots.hasEnoughLight）。
                            && MeidoWorkSpots.hasEnoughLight(world, pos.up())) {
                        // ★ 种子从她背包里出：包里没有就跳过这一格，而不是凭空变出小麦。
                        if (this.consumePlantableSeed(Items.WHEAT_SEEDS)) {
                            world.setBlockState(pos.up(), Blocks.WHEAT.getDefaultState(), 3);
                            acted++;
                        }
                    } else if (state.isOf(Blocks.SOUL_SAND) && world.getBlockState(pos.up()).isAir()) {
                        // 地狱疣：原版 canPlaceAt 只认灵魂沙、**不查光照**（地下暗处照样种，javap 核实）。
                        // 「种子」就是地狱疣本身，同样从她包里扣。
                        if (this.consumePlantableSeed(Items.NETHER_WART)) {
                            world.setBlockState(pos.up(), Blocks.NETHER_WART.getDefaultState(), 3);
                            acted++;
                        }
                    }
                    if (acted >= FARM_ACTIONS_PER_ROUND) {
                        return;
                    }
                }
            }
        }
    }

    /**
     * 收一茬作物：打掉 → 产物直接进背包 → 立刻补种。
     *
     * <p>补种也要消耗 1 个「种子」（小麦收货掉 0~2 颗种子，正好够续；
     * 地狱疣是自己的种子，收货 2~4 个，扣 1 个补种绰绰有余）；
     * 包里刚好没有就不补 —— 空地留着，她下个扫描周期有种子了再种。
     */
    private void harvestCrop(BlockPos pos) {
        World world = MeidoCompat.worldOf(this);
        if (world.isClient()) {
            return;
        }
        BlockState state = world.getBlockState(pos);
        world.setBlockState(pos, Blocks.AIR.getDefaultState(), 3);

        if (state.isOf(Blocks.WHEAT)) {
            this.accept(new ItemStack(Items.WHEAT, 1));
            int seeds = this.getRandom().nextInt(3);   // 0~2，原版量级
            if (seeds > 0) {
                this.accept(new ItemStack(Items.WHEAT_SEEDS, seeds));
            }
            // ★ 补种：「一块地循环产出」的关键。不补的话收完就是一片荒地。
            //   （先收后补：收获掉的种子已经进包了，正好够补种用。）
            if (this.consumePlantableSeed(Items.WHEAT_SEEDS)) {
                world.setBlockState(pos, Blocks.WHEAT.getDefaultState(), 3);
            }
            this.swingHand(Hand.MAIN_HAND);
            this.setEmotion(MeidoEmotion.HAPPY);
            MeidoChat.say(this, MeidoLocale.pick("收了一茬小麦。", "Harvested a patch of wheat."));
        } else if (state.isOf(Blocks.NETHER_WART)) {
            // 原版量级：无时运 2~4 个（时运我们不吃，跟钓竿一个道理）。
            int count = 2 + this.getRandom().nextInt(3);
            this.accept(new ItemStack(Items.NETHER_WART, count));
            if (this.consumePlantableSeed(Items.NETHER_WART)) {
                world.setBlockState(pos, Blocks.NETHER_WART.getDefaultState(), 3);
            }
            this.swingHand(Hand.MAIN_HAND);
            this.setEmotion(MeidoEmotion.HAPPY);
            MeidoChat.say(this, MeidoLocale.pick("收了一茬地狱疣。", "Harvested a patch of nether wart."));
        }
    }

    // ------------------------------------------------------------------
    // 种子：种 = 从她背包里消耗，「凭空变出作物」从来都不是种植
    // ------------------------------------------------------------------

    /** 包里有没有任何一种能种的「种子」（小麦种子或地狱疣，收获口径与之一致）。 */
    private boolean hasPlantableSeeds() {
        return this.seedSlotInInventory(Items.WHEAT_SEEDS) >= 0
                || this.seedSlotInInventory(Items.NETHER_WART) >= 0;
    }

    /** 从包里消耗 1 个指定的种子；没有返回 false。 */
    private boolean consumePlantableSeed(Item seed) {
        int slot = this.seedSlotInInventory(seed);
        if (slot < 0) {
            return false;
        }
        ItemStack seeds = this.inventory.get(slot);   // 活引用，直接扣
        seeds.decrement(1);
        if (seeds.isEmpty()) {
            this.inventory.take(slot);
        }
        return true;
    }

    /** 背包里指定种子在第几格；没有返回 -1。 */
    private int seedSlotInInventory(Item seed) {
        for (int i = 0; i < this.inventory.size(); i++) {
            if (this.inventory.get(i).isOf(seed)) {
                return i;
            }
        }
        return -1;
    }

    /** 她到岗位了没。和 {@code MeidoMissionGoal} 用的是同一个阈值：两边各写一个迟早对不上。 */
    private boolean hasArrivedAt(BlockPos post) {
        return this.squaredDistanceTo(post.getX() + 0.5, post.getY(), post.getZ() + 0.5)
                <= MeidoMissionGoal.ARRIVE_SQUARED;
    }

    /**
     * 不干活了就把「干活的临时状态」清干净。
     *
     * <p>放在 tick 里按「当前模式」判断，而不是在每个「换模式」的地方各调一次：
     * 换模式的路径不止一条（派活 / 干完 / 指令 / 读档），
     * 每处都记得调一次的写法，一定会漏掉其中一条。
     *
     * <p>★ 判据是<b>「这一项不属于当前模式」</b>，而不是「不是钓鱼就全清」：
     * 写成「只要不是钓鱼就把 {@code farmCooldown} 也清零」的话，
     * 种植模式<b>每 tick</b> 都会看到冷却为 0 → 那个「1 秒扫一圈」的节流等于没有，
     * 变成每 tick 扫 9×9×3 个方块（2026-09-20 自查时发现）。
     */
    private void tickWorkCleanup() {
        MeidoModeType type = this.mission.type();
        if (type != MeidoModeType.FISH) {
            this.fishWaitTicks = 0;
            this.unholdFishingRod();
        }
        if (type != MeidoModeType.FARM) {
            this.farmCooldown = 0;
        }
        // ★ 「平时手是空的」铁律的兜底：不在战斗模式（守卫/游走），手上有近战武器就收进背包。
        //   这同时覆盖了老存档（accept 旧规矩给到主手的剑）和一切「武器怎么跑上手」的漏网路径。
        //   战斗模式内不在这清 —— 战斗中收武器的是 MeidoGuardGoal.stop()；
        //   游走也算战斗模式（第七轮起游走会还手），在这收会把刚摸上手的剑立刻没收（smoke15 实测）。
        if (type != MeidoModeType.GUARD && type != MeidoModeType.WANDER) {
            this.stowWeapon();
        }
    }

    /**
     * 任务收尾：她自己回到游走，<b>并且把派活那个人闹钟上的选择也切回游走</b>。
     *
     * <h2>为什么非要连玩家的闹钟一起改</h2>
     *
     * <p>「选中模式」和「她现在的模式」是<b>两份数据</b>：
     * 前者在闹钟的物品组件里（你挑的单子），后者在实体身上（她真在干什么）。
     * 如果只改实体那一份，你低头看闹钟还是老样子 —— 从玩家视角看就是「界面在骗我」
     * （2026-09-20 实测踩到）。所以「收工」这一个动作必须把两份一起改，并且<b>说一句</b>。
     *
     * <p>找不到派活的人（下线了 / 服务器重启过 / 指令路径没记）就只改她自己那份，
     * 不报错 —— 这本来就是个「顺手的通知」，不是必须成功的事。
     *
     * @param message 发给派活人的那句话；<b>已经带上 {@code [mymeido]} 前缀</b>
     */
    private void endMission(String message) {
        this.mission.setMode(MeidoModeRegistry.DEFAULT_MODE_ID);
        this.applyMissionToTracker();

        UUID dispatcher = this.mission.dispatchedBy();
        this.mission.setDispatchedBy(null);
        if (dispatcher == null || !(MeidoCompat.worldOf(this) instanceof ServerWorld serverWorld)) {
            return;
        }
        ServerPlayerEntity player = serverWorld.getServer().getPlayerManager().getPlayer(dispatcher);
        if (player == null) {
            return;
        }
        CommandAlarmItem.resetSelection(player);
        player.sendMessage(Text.literal("[mymeido] " + message), true);
    }

    /**
     * 一次性任务（丢弃物品 / 绑定家）干完了。
     *
     * <p>只是给 {@link #endMission} 配一句话；<b>先记下干完的是哪件活再改模式</b>，
     * 顺序反了就只剩一个新的默认模式名可报。
     */
    private void finishOneShotMission() {
        String finished = this.mission.displayName();
        this.endMission(MeidoLocale.pick("「", "‘") + finished + MeidoLocale.pick(
                "」干完了，闹钟已切回", "’ done; alarm reset to ")
                + MeidoModeRegistry.byId(MeidoModeRegistry.DEFAULT_MODE_ID)
                        .map(MeidoModeDef::name).orElse(MeidoLocale.pick("默认模式", "default mode")));
    }

    // ------------------------------------------------------------------
    // 交互（二期 · A4-1）
    // ------------------------------------------------------------------

    /**
     * 右键她。按「越具体越优先」排，三步走：
     * <ol>
     *   <li><b>名牌</b> → 改昵称（走我们自己的改名系统，不碰原版 CustomName）；</li>
     *   <li><b>原版行为</b>（栓绳之类）→ 让 {@code super} 先试；</li>
     *   <li><b>其它一律 = 打招呼</b> —— 空手、拿着东西、拿着武器，行为完全相同。</li>
     * </ol>
     */
    @Override
    protected ActionResult interactMob(PlayerEntity player, Hand hand) {
        // 只认主手。不挡副手的话，同一个右键会被走两遍。
        if (hand != Hand.MAIN_HAND) {
            return ActionResult.PASS;
        }
        if (MeidoCompat.worldOf(this).isClient()) {
            // 客户端只负责「这一下算用掉了」，真正的逻辑交给服务端，避免双端各跑一遍。
            return ActionResult.SUCCESS;
        }

        ItemStack held = player.getStackInHand(hand);

        // ---- 0) 蹲下 + 右键：打开她的背包界面 ----
        // 蹲下这个修饰键把「看她的包」和「打招呼」区分开 —— 都是右键，
        // 但一个是管理动作、一个是社交动作，不能共用同一个入口。
        if (player.isSneaking() && player instanceof ServerPlayerEntity serverPlayer) {
            return this.openInventoryScreen(serverPlayer);
        }

        // ---- 1) 名牌：改走我们自己的昵称系统 ----
        // 原版名牌是直接往 CustomName 上写裸文本的，而 CustomName 正是我们用来渲染
        // 「角色名 + 专属色」的地方。让它被写会造成一个很隐蔽的 bug：
        // 用名牌改完名，一切正常；可一旦换皮肤，名字又会被皮肤名盖回去（因为昵称还是空的）。
        if (held.isOf(Items.NAME_TAG)) {
            Text custom = held.get(DataComponentTypes.CUSTOM_NAME);
            if (custom != null) {
                this.setNickname(custom.getString());
                held.decrementUnlessCreative(1, player);
                this.beginInteraction(player);
                this.setEmotion(MeidoEmotion.HAPPY);
                MeidoChat.say(this, MeidoChat.renamed(this));
                return ActionResult.SUCCESS;
            }
        }

        // ---- 2) 栓绳之类的原版行为让 super 先试，别抢 ----
        ActionResult vanilla = super.interactMob(player, hand);
        if (vanilla.isAccepted()) {
            return vanilla;
        }

        // ---- 3) 打招呼。手上的东西不影响 ----
        // ★ 刻意<b>不做</b>「拿着东西右键 = 直接送给她」。
        // 理由：同一个右键如果有时收东西、有时不收，玩家根本没法预期自己刚才干了什么 ——
        // 想整理背包时右键一下就被她收走一件，是纯粹的惊吓，不是玩法。
        // 给她东西的唯一入口是 <b>扔出来</b>（Q），那才是「明确表达我要给你」的动作。
        this.beginInteraction(player);
        this.setEmotion(MeidoEmotion.HAPPY);
        MeidoChat.say(this, MeidoChat.greeting(this, player));
        // A4-4：打招呼 +1 好感 —— 每个游戏日一次（聊一百次也只有今天这一分，
        // 明天再来）。防止「对着右键刷好感」。
        if (this.currentGameDay() > this.lastFavorChatDay) {
            this.lastFavorChatDay = this.currentGameDay();
            this.addFavor(1, (ServerPlayerEntity) player);
        }
        return ActionResult.SUCCESS;
    }

    /** 进入（或续期）交互状态：立刻刹车，转身面对玩家。 */
    public void beginInteraction(PlayerEntity player) {
        this.interactPlayer = player.getUuid();
        this.interactTicks = INTERACT_TICKS;
        this.getNavigation().stop();
        // 只清水平速度，竖直分量留着 —— 免得她在下落途中被「定」在半空。
        this.setVelocity(0.0, this.getVelocity().y, 0.0);
    }

    /** 是不是处在「刚被交互过」的窗口期里。 */
    public boolean isInteracting() {
        return this.interactTicks > 0;
    }

    // ------------------------------------------------------------------
    // 主动搭话（2026-09-21 第四批）
    // ------------------------------------------------------------------

    /**
     * 记下「造她的人」（{@link MeidoSpawn#atPlayer} 里调，两个创造入口共用一条实现）。
     *
     * <p>老存档没有这个键 → 保持 null，她就永远不会主动搭话（不会崩、不会乱认人）。
     */
    public void setOwner(UUID owner) {
        this.ownerUuid = owner;
    }

    public UUID getOwnerUuid() {
        return this.ownerUuid;
    }

    /** 创建人现在在哪。null = 没记过 / 不在线 / 换维度了。 */
    public ServerPlayerEntity resolveOwner() {
        return resolvePlayer(this.ownerUuid);
    }

    /** 按 UUID 找在线玩家：下线 / 已被移除 / 不在同一维度都算「不算数」。 */
    private ServerPlayerEntity resolvePlayer(UUID id) {
        if (id == null) {
            return null;
        }
        MinecraftServer server = MeidoCompat.worldOf(this).getServer();
        if (server == null) {
            return null;
        }
        ServerPlayerEntity player = server.getPlayerManager().getPlayer(id);
        if (player == null || player.isRemoved() || MeidoCompat.worldOf(player) != MeidoCompat.worldOf(this)) {
            return null;
        }
        return player;
    }

    /** 现在是原版意义上的「夜里」吗（跟玩家能上床的窗口同一判据）。 */
    public boolean isNightNow() {
        long timeOfDay = MeidoCompat.worldOf(this).getTimeOfDay() % 24000L;
        return timeOfDay >= NIGHT_FROM && timeOfDay <= NIGHT_TO;
    }

    /** 本小时还能搭话几次。负数按 0 处理（配置改小了的时候）。 */
    public int proactiveLeft() {
        return Math.max(0, MeidoAiConfig.proactiveMaxPerHour() - this.proactiveCount);
    }

    public int getProactiveCount() {
        return this.proactiveCount;
    }

    /** 创建人已经累计待了多久（tick）。指令显示用。 */
    public int getOwnerDwellTicks() {
        return this.ownerDwellTicks;
    }

    /** 这一段还差多少 tick 触发（凑不满就返回 0）。指令显示用。 */
    public int proactiveNeedTicks() {
        return Math.max(0, MeidoAiConfig.proactiveIntervalSeconds() * 20 - this.ownerDwellTicks);
    }

    /** 正在走向玩家准备搭话吗（{@link MeidoApproachOwnerGoal} 用）。 */
    public boolean isProactiveApproaching() {
        return this.approachTicks > 0;
    }

    /** 走过去还剩多少 tick 就放弃。指令显示用。 */
    public int proactiveApproachLeft() {
        return Math.max(0, this.approachTicks);
    }

    /** 话已经说完了、还在玩家身边站着吗。 */
    public boolean isProactiveLingering() {
        return this.lingerTicks > 0;
    }

    /** 她这次要走过去搭话的对象（人没了返回 null）。 */
    public ServerPlayerEntity proactiveTarget() {
        return resolvePlayer(this.approachTarget);
    }

    /** 最近主动说过的话（复制一份出去 —— 内部队列不能被外部改）。 */
    public List<String> recentProactiveSaid() {
        synchronized (this.proactiveSaid) {
            return new ArrayList<>(this.proactiveSaid);
        }
    }

    /** 记一句「刚主动说过的话」，超上限丢最老的。 */
    public void rememberProactiveSaid(String line) {
        if (line == null || line.isBlank()) {
            return;
        }
        synchronized (this.proactiveSaid) {
            this.proactiveSaid.addLast(line);
            while (this.proactiveSaid.size() > PROACTIVE_SAID_LIMIT) {
                this.proactiveSaid.removeFirst();
            }
        }
    }

    /**
     * 每 tick 推进「她主动搭话」这条状态机（服务端）。
     *
     * <p>三个阶段，互斥：<b>走过去</b> → <b>说完了站着</b> → <b>重新攒停留时间</b>。
     * 走到一半要能「取消」（天黑了 / 人不见了 / 走不到），取消不消耗次数 ——
     * 次数只在<b>她真的开口了</b>那一刻扣（见 {@link #onProactiveArrived()}）。
     */
    private void tickProactiveChat() {
        if (this.lingerTicks > 0) {
            this.lingerTicks--;
        }

        // ---- 阶段一：正在走过去 ----
        if (this.approachTicks > 0) {
            ServerPlayerEntity target = this.proactiveTarget();
            this.approachTicks--;
            if (target == null) {
                this.cancelProactiveApproach("owner gone");
            } else if (this.isNightNow()) {
                this.cancelProactiveApproach("it's night");
            } else if (this.approachTicks == 0) {
                this.cancelProactiveApproach("can't reach");
            }
            return;
        }

        // ---- 阶段三：攒停留时间 ----
        if (!MeidoAiConfig.proactiveEnabled()) {
            this.ownerDwellTicks = 0;   // 关掉了 / 没接 API → 这个功能整个不存在
            return;
        }
        ServerPlayerEntity owner = this.resolveOwner();
        if (owner == null) {
            this.ownerDwellTicks = 0;   // 没记过创建人、或者人不在
            return;
        }
        if (this.squaredDistanceTo(owner) > PROACTIVE_RANGE * PROACTIVE_RANGE) {
            return;   // 出圈只是暂停计时，不清零 —— 她自己在游走，别把她溜达算成玩家走了
        }
        this.refreshProactiveHour();
        this.ownerDwellTicks++;
        if (this.ownerDwellTicks < MeidoAiConfig.proactiveIntervalSeconds() * 20) {
            return;
        }

        // ---- 到点了：先决定这次搭不搭话，再清零（不管搭不搭，计时都从头来）----
        this.ownerDwellTicks = 0;
        if (this.isNightNow()) {
            MyMeido.LOGGER.info("[mymeido] {} accumulated enough dwell time, but it's night now — skipping this proactive chat",
                    this.characterName());
            return;
        }
        if (this.proactiveCount >= MeidoAiConfig.proactiveMaxPerHour()) {
            MyMeido.LOGGER.info("[mymeido] {} already proactively chatted {} times this hour (cap {}), waiting for next hour",
                    this.characterName(), this.proactiveCount, MeidoAiConfig.proactiveMaxPerHour());
            return;
        }
        this.beginProactiveApproach(owner);
    }

    /** 跨小时就把本小时次数清零。用 {@code getTime()/1000}：不随 /time set 回跳（防刷）。 */
    private void refreshProactiveHour() {
        long hour = MeidoCompat.worldOf(this).getTime() / 1000L;
        if (this.proactiveHour != hour) {
            this.proactiveHour = hour;
            this.proactiveCount = 0;
        }
    }

    private void beginProactiveApproach(ServerPlayerEntity owner) {
        this.approachTarget = owner.getUuid();
        this.approachTicks = PROACTIVE_APPROACH_TICKS;
        MyMeido.LOGGER.info("[mymeido] {} is going to chat with {}",
                this.characterName(), owner.getName().getString());
    }

    /**
     * 走到玩家身边了（由 {@link MeidoApproachOwnerGoal} 调）。
     *
     * <p>★ 次数<b>在这里</b>才扣：走到一半天黑/被怪打断，不该算数 ——
     * 「每小时最多 2 次」是「她开口说了 2 次」，不是「她起了 2 次念头」。
     */
    public void onProactiveArrived() {
        ServerPlayerEntity owner = this.proactiveTarget();
        this.approachTicks = 0;
        this.approachTarget = null;
        if (owner == null) {
            return;   // 到的那一下人正好不见了/下线了，当无事发生
        }
        this.lingerTicks = PROACTIVE_LINGER_TICKS;
        this.getNavigation().stop();
        this.setVelocity(0.0, this.getVelocity().y, 0.0);
        // 关系一般的时候不硬摆笑脸 —— 她那句台词本来就该是客客气气的。
        if (this.favorTier() >= 1) {
            this.setEmotion(MeidoEmotion.HAPPY);
        }
        this.refreshProactiveHour();
        this.proactiveCount++;
        MyMeido.LOGGER.info("[mymeido] {} walked up to {} to chat ({}th time this hour)",
                this.characterName(), owner.getName().getString(), this.proactiveCount);
        MeidoAi.onProactive(this, owner);
    }

    /** 放弃这次搭话。重复调用是安全的（状态已清就直接返回）。 */
    private void cancelProactiveApproach(String reason) {
        if (this.approachTicks <= 0 && this.approachTarget == null) {
            return;
        }
        MyMeido.LOGGER.info("[mymeido] {} cancelled this proactive chat ({})", this.characterName(), reason);
        this.approachTicks = 0;
        this.approachTarget = null;
        this.getNavigation().stop();
    }

    /** 对话历史（读写都请 {@code synchronized} 包住 —— 写在回调线程，读在主线程）。 */
    public Deque<String[]> aiHistory() {
        return this.aiHistory;
    }

    /** 记忆摘要（LLM 压缩出的长期要点；空串 = 无）。 */
    public String getAiSummary() {
        return this.aiSummary;
    }

    public void setAiSummary(String summary) {
        this.aiSummary = summary == null ? "" : summary.strip();
    }

    /**
     * 当前正在跟她交互的玩家。三种情况算「不算数了」：人下线了、换维度了、跑太远了。
     * 返回 null 时她只会站着看你（而不是去追一个不存在的人）。
     */
    public PlayerEntity getInteractionPlayer() {
        if (this.interactPlayer == null) {
            return null;
        }
        MinecraftServer server = MeidoCompat.worldOf(this).getServer();
        if (server == null) {
            return null;
        }
        ServerPlayerEntity player = server.getPlayerManager().getPlayer(this.interactPlayer);
        if (player == null || player.isRemoved() || MeidoCompat.worldOf(player) != MeidoCompat.worldOf(this)) {
            return null;
        }
        return player.squaredDistanceTo(this) <= PICKUP_RANGE * PICKUP_RANGE ? player : null;
    }

    /** 接到东西之后续一下窗口，方便连着给好几件。 */
    private void keepInteracting() {
        if (this.interactTicks < INTERACT_EXTEND_TICKS) {
            this.interactTicks = INTERACT_EXTEND_TICKS;
        }
    }

    public MeidoInventory getInventory() {
        return this.inventory;
    }

    // ------------------------------------------------------------------
    // 收下东西：给 / 捡 走的是同一条路
    // ------------------------------------------------------------------

    /**
     * 把一个地上的物品实体收进她身上。背包装满时，剩下的<b>留在原地</b>不动。
     *
     * <p>由 {@link MeidoInteractGoal} 在走到脚边时调用。
     */
    public void pickUp(ItemEntity item) {
        ItemStack stack = item.getStack();
        if (stack.isEmpty()) {
            item.discard();
            return;
        }
        boolean food = stack.contains(DataComponentTypes.FOOD);
        String itemName = stack.getName().getString();

        AcceptResult result = this.accept(stack);

        // 不管结果如何，这个物品实体都到此为止 ——
        //   EQUIPPED / STORED：内容已经在她身上了；
        //   OVERFLOW：塞不下的那份已由 store() 重新掉在她脚边，不在这里。
        item.discard();
        if (result == AcceptResult.IGNORED) {
            return;
        }

        this.setEmotion(food ? MeidoEmotion.LOVE : MeidoEmotion.HAPPY);
        this.keepInteracting();
        this.playSound(SoundEvents.ENTITY_ITEM_PICKUP, 0.25f, 1.4f);
        MeidoChat.say(this, MeidoChat.received(itemName, result));

        // A4-4：捡起玩家扔的物品 +2 好感 —— 每个游戏日一次（按世界总天数算，
        // /time set 不影响；隔天再来还能加）。找不到扔的人不加。
        if (item.getOwner() instanceof ServerPlayerEntity thrower
                && this.currentGameDay() > this.lastFavorPickupDay) {
            this.lastFavorPickupDay = this.currentGameDay();
            this.addFavor(2, thrower);
        }
    }

    /**
     * 把一件东西放到她身上 —— <b>「穿哪 / 拿哪 / 塞进背包」的全部规则都在这一个方法里</b>。
     *
     * <p>不管是玩家当面给的还是扔出来被她捡的，最终都调这里，所以规则只有一份。
     * 归类顺序：
     * <ol>
     *   <li>原版说她「天生该穿在某个槽」的（护甲 → 头/胸/腿/脚，盾 → 副手）
     *       → 直接占那个槽，原本穿着的<b>退回背包</b>；
     *       <b>注意：护甲外观是刻意隐藏的</b>（客户端 {@code RENDER_ARMOR = false}），
     *       所以穿上是真的、看得见是假的 —— 别把「没显示」当 bug 查；</li>
     *   <li>武器 / 工具（剑斧镐锹锄、弓弩、钓竿）→ <b>一律进背包</b>。
     *       ★ 2026-09-20 改的规矩：「只在攻击时把武器装备上」——
     *       武器上手是 {@code MeidoGuardGoal} 战斗期间的事（{@link #holdWeapon}），
     *       平时她手是空的。钓竿是唯一的例外：钓鱼模式会自己把竿摸上手（{@link #holdFishingRod}）；
     *       <b>刻意不做「自动换更强的武器」</b>，免得把你精心留着的附魔剑换成一把木剑；</li>
     *   <li>其余（食物、材料、方块……）→ 进背包。</li>
     * </ol>
     *
     * @param stack 会被就地消耗：装备掉 1 个，剩下的进背包
     */
    public AcceptResult accept(ItemStack stack) {
        if (stack.isEmpty()) {
            return AcceptResult.IGNORED;
        }
        // 护甲先单独认一遍（见 armorSlotOf 的说明）；盾/其余再交给原版判。
        EquipmentSlot slot = armorSlotOf(stack);
        if (slot == null) {
            slot = this.getPreferredEquipmentSlot(stack);
        }
        if (slot != EquipmentSlot.MAINHAND) {
            return this.equipInto(slot, stack);
        }
        // 武器/工具（剑斧镐锹锄、弓弩、钓竿，原版判到 MAINHAND 的都在这）只进背包：
        // 「只在攻击时把武器装备上」，上手是守卫战斗时的事。
        return this.store(stack);
    }

    /**
     * 这件东西「是不是原版认定的护甲」，是的话返回它该去的槽；不是返回 null。
     *
     * <p><b>为什么不直接用 {@link #getPreferredEquipmentSlot}</b>：
     * 那个方法内部在拿到槽位之后还会再问一句 {@code canUseSlot(slot)}，
     * 任何一环不满足就<b>静默</b>降级成 {@code MAINHAND} —— 于是护甲被当成杂物塞进背包，
     * 永远穿不上，而玩家只看到「扔了护甲她没穿」，没有任何报错可查。
     * 这里直接把 {@code Equipment.getSlotType()} 当答案，不给它降级的机会。
     *
     * <p>护甲<b>穿上是算减伤的</b>，只是客户端刻意不渲染它的外观
     * （{@code MeidoEntityRenderer.RENDER_ARMOR = false}）——
     * 护甲套在女仆装外面不好看。所以「扔了护甲看不见」是设计，不是故障。
     *
     * <p>返回的槽位与护甲自己声明的槽位<b>必然一致</b>。这点在打开护甲显示后很关键：
     * 客户端的 {@code ArmorFeatureRenderer} 会校验
     * {@code armorItem.getSlotType() == 所在槽位}，不一致就<b>默默不画</b>。
     */
    private static EquipmentSlot armorSlotOf(ItemStack stack) {
        EquipmentSlot slot = MeidoCompat.equipmentSlotOf(stack);
        if (slot == null) {
            return null;
        }
        // 不用 isArmorSlot()（1.21.11 行为未核实），直接白名单四个护甲槽 —— 语义等价且两版一致。
        return switch (slot) {
            case FEET, LEGS, CHEST, HEAD -> slot;
            default -> null;
        };
    }

    /** 装备 1 个到 slot，把原来在那个槽的东西退回背包。 */
    private AcceptResult equipInto(EquipmentSlot slot, ItemStack stack) {
        ItemStack worn = stack.split(1);
        if (worn.isEmpty()) {
            return AcceptResult.IGNORED;
        }
        ItemStack previous = this.getEquippedStack(slot);
        this.equipStack(slot, worn);
        if (!previous.isEmpty()) {
            this.store(previous);
        }
        if (!stack.isEmpty()) {
            // 一叠里剩下的（比如一下扔了 3 双同样的靴子）按普通物品收。
            this.store(stack);
        }
        return AcceptResult.EQUIPPED;
    }

    /**
     * 塞进背包；塞不下的部分掉在她脚边。
     *
     * <p><b>无论成功与否都会把 {@code stack} 清空</b> —— 让调用方统一认为「已经处理完了」。
     * 否则调用方（拿到地面物品实体的 {@link #pickUp}）会看到「还剩一些」，
     * 于是把物品实体留着，而这里又已经把多余的重新掉了一份 —— <b>同一件东西变成两份</b>。
     */
    private AcceptResult store(ItemStack stack) {
        if (stack.isEmpty()) {
            return AcceptResult.IGNORED;
        }
        ItemStack leftover = this.inventory.add(stack);
        if (leftover.isEmpty()) {
            return AcceptResult.STORED;
        }
        stack.setCount(0);
        MeidoCompat.dropStack(this, leftover);
        return AcceptResult.OVERFLOW;
    }

    /** 把背包里的东西全倒在地上，返回件数。调试用，也是三期「丢弃物品」模式的雏形。 */
    public int dropBackpack() {
        int count = 0;
        for (int slot = 0; slot < this.inventory.size(); slot++) {
            ItemStack stack = this.inventory.take(slot);
            if (stack.isEmpty()) {
                continue;
            }
            count += stack.getCount();
            MeidoCompat.dropStack(this, stack);
        }
        return count;
    }

    // ------------------------------------------------------------------
    // 派活（二期第二批 · A4-2）
    // ------------------------------------------------------------------

    /** 她的派活状态。**只读**用途从这里拿；改状态请走 {@link #assignMode} / {@link #bindHome}。 */
    public MeidoMission getMission() {
        return this.mission;
    }

    /** 当前模式的条目。配置被改坏了就是 empty —— 调用方按「游走」处理。 */
    public Optional<MeidoModeDef> getModeDef() {
        return this.mission.def();
    }

    /**
     * 派活的结果。
     *
     * <h2>为什么不是一个 {@code Optional}</h2>
     *
     * <p>派活失败有<b>两种</b>原因，要跟玩家说的话完全不同：
     * 「这个 id 服务端不认识」（版本对不上 / 配置文件被改了）和
     * 「这地方干不了这个活」（水边没落脚点 / 周围没耕地）。
     * 用 {@code Optional.empty()} 把两者混成一个的话，
     * 调用方只能报一句含糊的「派活失败」，玩家没法自己判断该改配置还是该挪地方。
     *
     * <p>成功时 {@code spot} 是<b>她最终会站的地方</b>（钓鱼时是岸边，不是水面），
     * 所以「已派活：钓鱼 → x y z」这句报的坐标就是可以去找她的地方。
     *
     * @param def     被接受的模式条目；失败时为 {@code null}
     * @param spot    她最终会站的位置；模式不需要位置时为 {@code null}
     * @param failure 失败原因（已经是可以直接发给玩家的整句）；成功时为 {@code null}
     */
    public record Assignment(MeidoModeDef def, BlockPos spot, String failure) {

        static Assignment ok(MeidoModeDef def, BlockPos spot) {
            return new Assignment(def, spot, null);
        }

        static Assignment failed(String message) {
            return new Assignment(null, null, message);
        }

        public boolean isOk() {
            return this.def != null;
        }
    }

    /**
     * 派一个模式给她，<b>不知道是谁派的</b>。
     *
     * <p>只在测试 / 旧调用点用。正常玩法请走 {@link #assignMode(String, BlockPos, PlayerEntity)} ——
     * 那边会记住是谁派的，一次性任务干完才能把那个人的闹钟也切回游走。
     */
    public Assignment assignMode(String modeId, BlockPos target) {
        return this.assignMode(modeId, target, null);
    }

    /**
     * 派一个模式给她，并<b>记住是谁派的</b>。
     *
     * <p>记派活人只有一个用途：一次性任务（丢弃物品 / 绑定家）干完之后，
     * 要把<b>他闹钟上的「选中模式」也切回游走</b>（见 {@link #finishOneShotMission()}）。
     * 不记的话那份选择会永远停在「丢弃物品」，而她已经走开了。
     *
     * <h2>派发时就翻译「点的那一格」</h2>
     *
     * <p>你右键水面，意思不是「站到水里」，而是「去这片水边钓鱼」。
     * 所以这里先把点到的位置过一遍 {@link MeidoWorkSpots#resolveSpot}，
     * 把「水面」翻译成「水边能站人的干地」，翻译结果才写进任务。
     * 种植同理：右键耕地 → 站到耕地<b>旁边</b>。
     *
     * <p>翻译不出来就<b>当场失败、什么都不改</b>：
     * 与其让她走到一个干不了活的地方装样子，不如立刻告诉玩家差在哪。
     * （「水后来被填了」这种活干到一半才发生的，由 tick 里的自愈兜底。）
     *
     * @param modeId     必须已经在 {@link MeidoModeRegistry} 里 ——
     *                   <b>调用方负责先校验</b>（指令那边用不了的两个参数是错的，客户端发来的更不可信）
     * @param target     目标位置，可以为 null
     * @param dispatcher 派活的人，<b>可以为 null</b>（测试路径 / 没法确定是谁）；
     *                   为 null 时一次性任务干完不发通知，其余行为完全一样
     * @return 见 {@link Assignment}；失败时她<b>保持原样</b>，一点状态都不改
     */
    public Assignment assignMode(String modeId, BlockPos target, PlayerEntity dispatcher) {
        Optional<MeidoModeDef> defOpt = MeidoModeRegistry.byId(modeId);
        if (defOpt.isEmpty()) {
            return Assignment.failed(MeidoLocale.pick("派活失败：服务端不认识模式「", "Assignment failed: server does not recognize mode '") + modeId + MeidoLocale.pick("」", "'"));
        }
        MeidoModeDef def = defOpt.get();

        // ★ 先决条件在派发时就拦下：钓鱼得她有竿、种植得她有种子。
        //   「先给东西再派活」是玩法的一部分，缺了就当场说清楚，别让她走到地方才发现干不了。
        //   （活干到一半东西没了的，由 tick 里的对应检查兜底：竿没了收工 / 没种子没熟麦收工。）
        if (def.type() == MeidoModeType.FISH && this.currentFishingRod().isEmpty()) {
            return Assignment.failed(MeidoLocale.pick("派活失败：「", "Assignment failed: '") + def.name()
                    + MeidoLocale.pick("」要先给她一根钓鱼竿（右键递给她或 Q 扔给她）",
                            "' needs a fishing rod first (right-click to hand it to her, or press Q to drop it)"));
        }
        if (def.type() == MeidoModeType.FARM && !this.hasPlantableSeeds()) {
            return Assignment.failed(MeidoLocale.pick("派活失败：「", "Assignment failed: '") + def.name()
                    + MeidoLocale.pick("」要先给她小麦种子或地狱疣（Q 扔给她即可）",
                            "' needs wheat seeds or nether wart first (press Q to drop them)"));
        }

        // ★ 翻译「你点的那一格」。模式不需要位置时原样返回，所以这里不用再分情况。
        BlockPos spot = target;
        if (def.usesTarget() && target != null) {
            Optional<BlockPos> resolved = MeidoWorkSpots.resolveSpot(def.type(), MeidoCompat.worldOf(this), target);
            if (resolved.isEmpty()) {
                return Assignment.failed(MeidoWorkSpots.failureHint(def.type(), def.name()));
            }
            spot = resolved.get();
        }

        this.mission.setMode(def.id());
        // OPTIONAL / REQUIRED 才记位置；NONE 的模式给了位置也不记，免得留下没用的脏数据。
        if (def.usesTarget() && spot != null) {
            this.mission.setTarget(spot);
        }
        this.mission.setDispatchedBy(dispatcher == null ? null : dispatcher.getUuid());
        this.applyMissionToTracker();

        // 「绑定家」是一次性动作：记下来就完事，然后自己回到游走 ——
        // 顺手把派活人闹钟上的选择也切回去，走的是和「丢弃物品」同一个收尾函数，
        // 免得两处各写一遍、然后其中一处忘了改（这正是这个 bug 的成因）。
        if (def.type() == MeidoModeType.BIND_HOME) {
            if (target != null) {
                this.mission.setHome(target);
            }
            this.finishOneShotMission();
        }

        // 派活 = 打断她当前正在做的事，立刻转向新任务。
        this.getNavigation().stop();
        this.setVelocity(0.0, this.getVelocity().y, 0.0);
        return Assignment.ok(def, spot);
    }

    /** 绑家。和模式无关，绑一次一直有效。 */
    public void bindHome(BlockPos pos) {
        this.mission.setHome(pos);
    }

    /**
     * 让她回到默认模式（游走），并把「谁派的」一起清掉。
     *
     * <p>清派活人是必要的：留着的话，下一次「不记派活人」的派活之后她走完收尾流程，
     * 会把<b>上一次那个人</b>错当成这次的通知对象。
     */
    public void clearMission() {
        this.mission.setMode(MeidoModeRegistry.DEFAULT_MODE_ID);
        this.mission.setDispatchedBy(null);
        this.applyMissionToTracker();
    }

    /** 把服务端的模式写进同步字段。客户端读 {@code MODE_ID} 就能显示当前状态。 */
    private void applyMissionToTracker() {
        this.dataTracker.set(MODE_ID, this.mission.modeId());
    }

    /** 客户端用：当前模式 id（已同步）。 */
    public String getSyncedModeId() {
        return this.dataTracker.get(MODE_ID);
    }

    /** 武器或工具？用原版标签判断，模组物品只要挂进这些标签也自动算。 */
    private static boolean isWeaponOrTool(ItemStack stack) {
        return stack.isIn(ItemTags.SWORDS)
                || stack.isIn(ItemTags.AXES)
                || stack.isIn(ItemTags.PICKAXES)
                || stack.isIn(ItemTags.SHOVELS)
                || stack.isIn(ItemTags.HOES)
                || stack.isIn(ItemTags.TRIDENT_ENCHANTABLE)
                || stack.isOf(Items.BOW)
                || stack.isOf(Items.CROSSBOW)
                || stack.isOf(Items.FISHING_ROD);
    }

    // ------------------------------------------------------------------
    // 存档
    // ------------------------------------------------------------------

    // ------------------------------------------------------------------
    // 存档（1.21.11：实体存档走 WriteView / ReadView —— javap 实锤
    // Entity.writeCustomData(WriteView) / readCustomData(ReadView) 为抽象方法；
    // 1.21.1 分支保留原 NbtCompound 版本，见下方 else 块）
    // ------------------------------------------------------------------
    //? if >=1.21.11 {

    /** 对话历史单条（role + text）。列表走 codec，与 {@code getListAppender} 配套。 */
    private static final Codec<String[]> HISTORY_ENTRY_CODEC = RecordCodecBuilder.create(instance ->
            instance.group(
                    Codec.STRING.fieldOf("Role").forGetter(e -> e[0]),
                    Codec.STRING.fieldOf("Text").forGetter(e -> e[1])
            ).apply(instance, (role, text) -> new String[] { role, text }));

    @Override
    protected void writeCustomData(WriteView view) {
        super.writeCustomData(view);
        view.putString("MeidoSkin", this.getSkin().getId());
        view.putInt("MeidoColor", this.getMeidoColor().ordinal());
        view.putString("MeidoNickname", this.getNickname());
        view.putInt("MeidoFavor", this.favor);
        view.putLong("MeidoFavorPickupDay", this.lastFavorPickupDay);
        view.putLong("MeidoFavorChatDay", this.lastFavorChatDay);
        this.mission.writeView(view);
        // 物品组件要 registry 才能序列化，实体自己的 registryManager 就够。
        this.inventory.writeView(view, this.getRegistryManager());
        // 三期：对话历史 + 记忆摘要随存档走 —— 重启之后她还认得你（她说的）。
        WriteView.ListAppender<String[]> history = view.getListAppender("MeidoHistory", HISTORY_ENTRY_CODEC);
        synchronized (this.aiHistory) {
            for (String[] entry : this.aiHistory) {
                history.add(entry);
            }
        }
        view.putString("MeidoAiSummary", this.aiSummary);
        // 主动搭话：创建人 + 本小时已说几次 + 最近说过的话（防重复）。
        // putNullable：ownerUuid 为 null 时整键不写，和旧版「没创建人就什么都不放」一致。
        // ★ 用 INT_STREAM_CODEC 而不是 Uuids.CODEC：后者是字符串版，
        //   NBT 里会写成字符串；而 1.21.1 的 putUuid / summon 手写 NBT / 旧存档
        //   全是 [I;a,b,c,d] 的 int 数组 —— 无头冒烟 2026-09-21 实锤往返失败。
        view.putNullable("MeidoOwner", Uuids.INT_STREAM_CODEC, this.ownerUuid);
        view.putLong("MeidoProactiveHour", this.proactiveHour);
        view.putInt("MeidoProactiveCount", this.proactiveCount);
        WriteView.ListAppender<String> said = view.getListAppender("MeidoProactiveSaid", Codec.STRING);
        synchronized (this.proactiveSaid) {
            for (String line : this.proactiveSaid) {
                said.add(line);
            }
        }
    }

    @Override
    protected void readCustomData(ReadView view) {
        super.readCustomData(view);
        // 必须在 super 之后：super 会用存档里的旧属性覆盖注册默认值。
        this.applyDesignAttributes();
        view.getOptionalString("MeidoSkin").ifPresent(id -> this.setSkin(MeidoSkin.fromId(id)));
        view.getOptionalInt("MeidoColor").ifPresent(ord -> this.setMeidoColor(MeidoColor.byOrdinal(ord)));
        this.inventory.readView(view, this.getRegistryManager());
        this.readNickname(view.getOptionalString("MeidoNickname").orElse(null));
        this.mission.readView(view);
        // 好感度：老存档没有这个键 → 保持初始值 50（中档）。
        view.getOptionalInt("MeidoFavor").ifPresent(v -> this.favor = MathHelper.clamp(v, 0, 100));
        // 每日加分的天数标记：随存档走，重进存档不会把「今天已加过」刷成「没加过」。
        view.getOptionalLong("MeidoFavorPickupDay").ifPresent(v -> this.lastFavorPickupDay = v);
        view.getOptionalLong("MeidoFavorChatDay").ifPresent(v -> this.lastFavorChatDay = v);
        // 老存档没有这几个键 → mission 保持默认（游走）；配置里删掉过某个模式 → 拉回游走。
        if (this.mission.sanitize()) {
            MyMeido.LOGGER.warn("[mymeido] saved mode id is no longer in config, falling back to {} (entity {})",
                    this.mission.modeId(), this.getUuid());
        }
        // 三期：对话历史 + 记忆摘要。老存档没有 → 空历史/空摘要，行为不变。
        // （旧版读前先验类型的口子，在这里由 codec 天然兜住：类型不对 = Optional.empty。）
        view.getOptionalTypedListView("MeidoHistory", HISTORY_ENTRY_CODEC).ifPresent(history -> {
            synchronized (this.aiHistory) {
                this.aiHistory.clear();
                for (String[] entry : history) {
                    if ("user".equals(entry[0]) || "assistant".equals(entry[0])) {
                        this.aiHistory.addLast(new String[] { entry[0], entry[1] });
                    }
                }
            }
        });
        view.getOptionalString("MeidoAiSummary").ifPresent(s -> this.aiSummary = s);
        // 主动搭话。老存档没有这几个键 → 没创建人（不搭话）、计时从头、没有历史台词。
        view.read("MeidoOwner", Uuids.CODEC).ifPresent(uuid -> this.ownerUuid = uuid);
        this.proactiveHour = view.getLong("MeidoProactiveHour", -1L);
        this.proactiveCount = view.getInt("MeidoProactiveCount", 0);
        view.getOptionalTypedListView("MeidoProactiveSaid", Codec.STRING).ifPresent(said -> {
            synchronized (this.proactiveSaid) {
                this.proactiveSaid.clear();
                for (String line : said) {
                    this.proactiveSaid.addLast(line);
                }
            }
        });
        this.applyMissionToTracker();
        this.applyNameStyle();
    }

    //?} else {
    /*@Override
    public void writeCustomDataToNbt(NbtCompound nbt) {
        super.writeCustomDataToNbt(nbt);
        nbt.putString("MeidoSkin", this.getSkin().getId());
        nbt.putInt("MeidoColor", this.getMeidoColor().ordinal());
        nbt.putString("MeidoNickname", this.getNickname());
        nbt.putInt("MeidoFavor", this.favor);
        nbt.putLong("MeidoFavorPickupDay", this.lastFavorPickupDay);
        nbt.putLong("MeidoFavorChatDay", this.lastFavorChatDay);
        this.mission.writeNbt(nbt);
        // 物品组件要 registry 才能序列化，实体自己的 registryManager 就够。
        this.inventory.writeNbt(nbt, this.getRegistryManager());
        // 三期：对话历史 + 记忆摘要随存档走 —— 重启之后她还认得你（她说的）。
        NbtList history = new NbtList();
        synchronized (this.aiHistory) {
            for (String[] entry : this.aiHistory) {
                NbtCompound item = new NbtCompound();
                item.putString("Role", entry[0]);
                item.putString("Text", entry[1]);
                history.add(item);
            }
        }
        nbt.put("MeidoHistory", history);
        nbt.putString("MeidoAiSummary", this.aiSummary);
        // 主动搭话：创建人 + 本小时已说几次 + 最近说过的话（防重复）。
        if (this.ownerUuid != null) {
            nbt.putUuid("MeidoOwner", this.ownerUuid);
        }
        nbt.putLong("MeidoProactiveHour", this.proactiveHour);
        nbt.putInt("MeidoProactiveCount", this.proactiveCount);
        NbtList said = new NbtList();
        synchronized (this.proactiveSaid) {
            for (String line : this.proactiveSaid) {
                said.add(NbtString.of(line));
            }
        }
        nbt.put("MeidoProactiveSaid", said);
    }

    @Override
    public void readCustomDataFromNbt(NbtCompound nbt) {
        super.readCustomDataFromNbt(nbt);
        // 必须在 super 之后：super 会用存档里的旧属性覆盖注册默认值。
        this.applyDesignAttributes();
        if (nbt.contains("MeidoSkin")) {
            this.setSkin(MeidoSkin.fromId(nbt.getString("MeidoSkin")));
        }
        if (nbt.contains("MeidoColor")) {
            this.setMeidoColor(MeidoColor.byOrdinal(nbt.getInt("MeidoColor")));
        }
        this.inventory.readNbt(nbt, this.getRegistryManager());
        this.readNickname(nbt.contains("MeidoNickname") ? nbt.getString("MeidoNickname") : null);
        this.mission.readNbt(nbt);
        // 好感度：老存档没有这个键 → 保持初始值 50（中档）。
        if (nbt.contains("MeidoFavor")) {
            this.favor = MathHelper.clamp(nbt.getInt("MeidoFavor"), 0, 100);
        }
        // 每日加分的天数标记：随存档走，重进存档不会把「今天已加过」刷成「没加过」。
        if (nbt.contains("MeidoFavorPickupDay")) {
            this.lastFavorPickupDay = nbt.getLong("MeidoFavorPickupDay");
        }
        if (nbt.contains("MeidoFavorChatDay")) {
            this.lastFavorChatDay = nbt.getLong("MeidoFavorChatDay");
        }
        // 老存档没有这几个键 → mission 保持默认（游走）；配置里删掉过某个模式 → 拉回游走。
        if (this.mission.sanitize()) {
            MyMeido.LOGGER.warn("[mymeido] saved mode id is no longer in config, falling back to {} (entity {})",
                    this.mission.modeId(), this.getUuid());
        }
        // 三期：对话历史 + 记忆摘要。老存档没有 → 空历史/空摘要，行为不变。
        if (nbt.contains("MeidoHistory")) {
            synchronized (this.aiHistory) {
                this.aiHistory.clear();
                // 1.21.1 的 getList 要显式元素类型（javap 核实过：getList(String,int)）。
                NbtList history = nbt.getList("MeidoHistory", NbtElement.COMPOUND_TYPE);
                for (int i = 0; i < history.size(); i++) {
                    NbtCompound item = history.getCompound(i);
                    String role = item.getString("Role");
                    if ("user".equals(role) || "assistant".equals(role)) {
                        this.aiHistory.addLast(new String[] { role, item.getString("Text") });
                    }
                }
            }
        }
        if (nbt.contains("MeidoAiSummary")) {
            this.aiSummary = nbt.getString("MeidoAiSummary");
        }
        // 主动搭话。老存档没有这几个键 → 没创建人（不搭话）、计时从头、没有历史台词。
        // ★ 读之前先验类型：这几个键是给玩家手写 /summon 留下的口子，
        //   写错类型（比如 MeidoOwner 写成字符串）不该让整个实体读档炸掉。
        //   containsUuid = 「是长度 4 的 int 数组」（javap 核实 1.21.1 已有）。
        if (nbt.containsUuid("MeidoOwner")) {
            this.ownerUuid = nbt.getUuid("MeidoOwner");
        }
        this.proactiveHour = nbt.contains("MeidoProactiveHour") ? nbt.getLong("MeidoProactiveHour") : -1L;
        this.proactiveCount = nbt.getInt("MeidoProactiveCount");
        if (nbt.contains("MeidoProactiveSaid", NbtElement.LIST_TYPE)) {
            synchronized (this.proactiveSaid) {
                this.proactiveSaid.clear();
                NbtList said = nbt.getList("MeidoProactiveSaid", NbtElement.STRING_TYPE);
                for (int i = 0; i < said.size(); i++) {
                    this.proactiveSaid.addLast(said.getString(i));
                }
            }
        }
        this.applyMissionToTracker();
        this.applyNameStyle();
    }
    *///?}

    /**
     * 读昵称，并兼容老存档。
     *
     * <p>老版本把名字直接写在原版 {@code CustomName} 上，没有 {@code MeidoNickname} 这个键。
     * 所以这里补一条迁移：如果 CustomName 既不是当年写死的「女仆」、也不等于当前皮肤名，
     * 就说明玩家当年真的改过名，把它继承过来；否则留空，让它自动回落到皮肤名。
     */
    private void readNickname(String explicit) {
        if (explicit != null && !explicit.isBlank()) {
            this.setNickname(explicit);
            return;
        }
        Text custom = this.getCustomName();
        if (custom == null) {
            return;
        }
        String raw = custom.getString().strip();
        if (!raw.isEmpty()
                && !raw.equals(LEGACY_DEFAULT_NAME)
                && !raw.equals(this.defaultName())) {
            this.setNickname(raw);
        }
    }

    // ------------------------------------------------------------------
    // 皮肤
    // ------------------------------------------------------------------

    public MeidoSkin getSkin() {
        return MeidoSkin.fromId(this.dataTracker.get(SKIN));
    }

    public void setSkin(MeidoSkin skin) {
        this.dataTracker.set(SKIN, skin.getId());
        // 没改过名时让名字跟着皮肤走 ——「一张皮肤 = 一个角色」。
        this.applyNameStyle();
    }

    // ------------------------------------------------------------------
    // 情绪
    // ------------------------------------------------------------------

    public MeidoEmotion getEmotion() {
        return MeidoEmotion.byOrdinal(this.dataTracker.get(EMOTION));
    }

    /** 剩余 tick。客户端用它算动画进度，同时天然得到淡出效果。 */
    public int getEmotionLeft() {
        return this.dataTracker.get(EMOTION_LEFT);
    }

    public void setEmotion(MeidoEmotion emotion) {
        this.dataTracker.set(EMOTION, emotion.ordinal());
        this.dataTracker.set(EMOTION_LEFT, emotion.durationTicks());
    }

    // ------------------------------------------------------------------
    // 角色专属色
    // ------------------------------------------------------------------

    public MeidoColor getMeidoColor() {
        return MeidoColor.byOrdinal(this.dataTracker.get(COLOR));
    }

    public void setMeidoColor(MeidoColor color) {
        this.dataTracker.set(COLOR, color.ordinal());
        this.applyNameStyle();
    }

    // ------------------------------------------------------------------
    // 名字
    // ------------------------------------------------------------------

    /**
     * 当前的默认名 —— <b>就是皮肤名</b>。
     * 玩家改过名之后它依然返回皮肤名，因为它代表的是「没改名时该叫什么」。
     */
    public String defaultName() {
        return this.getSkin().displayName();
    }

    /** 玩家改的名。空串 = 没改过。 */
    public String getNickname() {
        String raw = this.dataTracker.get(NICKNAME);
        return raw == null ? "" : raw;
    }

    /** 设昵称。传空白 = 清掉改名，回落到皮肤名。 */
    public void setNickname(String nickname) {
        this.dataTracker.set(NICKNAME, nickname == null ? "" : nickname.strip());
        this.applyNameStyle();
    }

    /** 最终显示的角色名：改过用改的，没改过用皮肤名。永不为空。 */
    public String characterName() {
        String nickname = this.getNickname();
        return nickname.isEmpty() ? this.defaultName() : nickname;
    }

    /**
     * 把角色名写回自定义名字，并带上专属色。
     *
     * <p>头顶名牌的渲染最终会走 {@code entity.getDisplayName()}，
     * 所以「名字带颜色」这件事只要把样式挂在 Text 上就成立了，不需要 Mixin。
     * 名字 / 皮肤 / 专属色任一变动之后都要重新调一次。
     */
    private void applyNameStyle() {
        TextColor color = TextColor.fromRgb(this.getMeidoColor().nameColor());
        this.setCustomName(Text.literal(this.characterName())
                .setStyle(Style.EMPTY.withColor(color).withBold(true)));
        this.setCustomNameVisible(true);
    }
}
