package com.mymeido.mode;

/**
 * 「行为钩子」—— <b>代码真正认识的那几种模式</b>。
 *
 * <p>和 JSON 配置的关系（很重要，别搞混）：
 * <ul>
 *   <li>{@code modes.json} 里的每一条是<b>玩家看到的一个条目</b>（可以改名字、改说明、
 *       甚至用同一个钩子注册两个条目）；</li>
 *   <li>这个枚举是<b>代码里实际写好了行为的那几个</b>。JSON 条目通过 {@code type}
 *       字段指向这里的一个值。</li>
 * </ul>
 *
 * <p>好处：玩家能自由增删/改名模式条目而不用动代码，
 * 但只要 {@code type} 写了这里没有的字符串，就会被当成「未知类型」跳过并在日志里说清楚
 * —— 而不是默默失效。
 *
 * <p>⚠️ 所有钩子的行为都已实现（2026-09-20 起：钓鱼 / 种植 / 守卫全部落地）。
 * {@code implemented} 字段保留是为了将来加「只做入口」的钩子时不用改结构。
 *
 * <p>★ 2026-09-20：「挖取方块」已按需求删除 ——
 * 一个「自动挖掉方块」的女仆放在生存里风险太大（她会把你要留的东西挖了），
 * 与其做一个要处处防错的模式，不如不做。删掉后老存档里残留的 {@code mine} 条目
 * 会被当成「未知类型」跳过并在日志里说明（这正是当初把 id 认字符串、不认枚举序号的原因）。
 */
public enum MeidoModeType {

    /** 默认模式：四处游走；怪物靠太近会还手（打一下就拉开，冷却好了再上）。 */
    WANDER("wander", "游走", Target.NONE, true,
            "像平时那样四处走走。怪物凑太近会还手 —— 打一下就拉开距离，冷却好了再上，"
                    + "打完继续晃（玩家、动物和你的其它女仆不打，苦力怕不打）。"),

    /** 站着不动。 */
    STAND("stand", "原地待命", Target.NONE, true,
            "待在原地不动，等你下一个指令。"),

    /** 到指定的方块上去，然后站住不动（「召唤到身边」的用法：右键你脚边的地面）。 */
    COME("come", "到这里来", Target.REQUIRED, true,
            "走到你右键的方块上站着不动。点哪她去哪（点方块顶面就是站到它上面），"
                    + "直到你派下一个指令。"),

    /** 把身上的东西丢到指定位置，丢完自动回到游走。 */
    DUMP("dump", "丢弃物品", Target.OPTIONAL, true,
            "把背包里的东西全丢到指定位置，丢完自动回到游走。"),

    /** 把某张床记成她的家。 */
    BIND_HOME("bind_home", "绑定家", Target.REQUIRED, true,
            "把右键的那张床记成她的家。绑一次长期有效（跟当前模式无关）。"
                    + "到了能睡觉的时间，只要她在「游走」，就会自己走回床边待着，天亮再继续晃；"
                    + "在钓鱼 / 种植 / 守卫的活里不打断（手上的活干完才算），有怪也照样先打。"
                    + "床被挖了她就不回去 —— 家还记在账上，重新绑一次（或放回原位）即可。"),

    /** 钓鱼：走到岸边 → 甩竿 → 出货。 */
    FISH("fish", "钓鱼", Target.REQUIRED, true,
            "去你指的水边钓鱼。右键水面或岸边都行 —— 她会自己走到岸上，不会踩进水里。"),

    /** 种植：找耕地 → 播种 → 成熟收获。 */
    FARM("farm", "种植", Target.REQUIRED, true,
            "在附近的耕地上种小麦，成熟了收进自己背包，然后接着种。需要你先用锄头把地翻好。"),

    /**
     * 守卫：守在指定的方块上，怪物进入攻击范围就接敌。打带跑节奏 ——
     * 打一下就拉开约 3 格，冷却 1.5 秒走完再上（约 2 秒一刀）。
     * 只打敌对生物（Monster 一族，天然排除玩家 / 动物 / 自己人），
     * 追出岗位 10 格就松手回岗。武器只在战斗时从背包摸上手，打完收回去。
     * <p>苦力怕刻意不打 —— 近战打它等于抱着一颗会炸的雷。
     */
    GUARD("guard", "守卫前方", Target.OPTIONAL, true,
            "守在指定的方块上不乱跑，怪物进入攻击范围就打（不打玩家、动物和你的其它女仆）。"
                    + "打一下就拉开距离，约 2 秒一刀；武器只有战斗时才拿在手里。"
                    + "苦力怕不打（近战会引爆它）。");

    /** 这个模式需不需要一个「目标位置」。 */
    public enum Target {
        /** 不需要位置，直接在原地开始。 */
        NONE,
        /** 位置可选：给了就用（比如丢弃物品的落点），不给就在她脚下。 */
        OPTIONAL,
        /** 必须有位置，没位置不允许派发。 */
        REQUIRED
    }

    private final String id;
    private final String displayName;
    private final Target target;
    private final boolean implemented;
    private final String description;

    MeidoModeType(String id, String displayName, Target target, boolean implemented, String description) {
        this.id = id;
        this.displayName = displayName;
        this.target = target;
        this.implemented = implemented;
        this.description = description;
    }

    /** JSON / 指令 / NBT 里用的稳定字符串。 */
    public String getId() {
        return this.id;
    }

    /** 给玩家看的中文名。 */
    public String getDisplayName() {
        return this.displayName;
    }

    public Target getTarget() {
        return this.target;
    }

    /** 行为是否已经写好。{@code false} = 现在只有入口，行为留后面。 */
    public boolean isImplemented() {
        return this.implemented;
    }

    public String getDescription() {
        return this.description;
    }

    /** 从 id 反查；不认识就返回 {@code null}（<b>不要</b>偷偷回落默认值，否则配置写错会静默失效）。 */
    public static MeidoModeType fromId(String id) {
        if (id != null) {
            for (MeidoModeType type : values()) {
                if (type.id.equalsIgnoreCase(id) || type.name().equalsIgnoreCase(id)) {
                    return type;
                }
            }
        }
        return null;
    }
}
