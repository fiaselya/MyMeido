package com.mymeido.mode;

/**
 * 模式清单里的<b>一条</b> —— 玩家在界面里看到的那一行。
 *
 * <p>{@code id} 是身份（存进 NBT、发到网络、指令里敲的都是它），
 * {@code name} / {@code desc} 只是给人看的，随便改。
 *
 * <p>{@code type} 决定「她到底会干什么」—— 见 {@link MeidoModeType}。
 * 同一个 {@code type} 可以挂多条不同 {@code id} 的条目（比如「去河边钓鱼」和
 * 「去海边钓鱼」都指向 {@code fish}，只是名字不同、目标位置不同）。
 */
public record MeidoModeDef(String id, String name, MeidoModeType type,
                           MeidoModeType.Target target, String desc) {

    /** 派发之前必须有一个目标位置吗。 */
    public boolean needsTarget() {
        return this.target == MeidoModeType.Target.REQUIRED;
    }

    /** 会不会用到目标位置（用来决定「右键空气直接派发」时要不要拦一下）。 */
    public boolean usesTarget() {
        return this.target != MeidoModeType.Target.NONE;
    }

    /** 行为是否已经写好。{@code false} = 派发能成功，但她暂时不会真的干活。 */
    public boolean isImplemented() {
        return this.type.isImplemented();
    }

    /** 界面上那一行小字。 */
    public String hint() {
        return switch (this.target) {
            case NONE -> "选中后右键任意方块派发";
            case OPTIONAL -> "选中后右键方块派发（位置会用作落点）";
            case REQUIRED -> "选中后右键目标方块派发";
        };
    }
}
