package com.mymeido.entity;

import java.util.ArrayList;
import java.util.List;

/**
 * 女仆皮肤池。一张皮肤 = 一个角色。
 *
 * <p>贴图文件放在 {@code .minecraft/config/mymeido/skins/} 下，客户端按
 * {@link #fileName()} 去取；找不到会再做一次「忽略大小写 / 空格 / 下划线」的宽容匹配，
 * 所以把 {@code Sakurai Momoka.png} 改名成 {@code momoka.png} 也还能用。
 * 再找不到才回落原版 Steve（那张一定在游戏本体里，不会缺）。
 *
 * <p>为什么是枚举、而不是「扫目录里有多少 png 就算多少个」：
 * 皮肤 id 要被 {@code DataTracker} + 存档 NBT + 指令补全三处共用，
 * 必须是一个<b>两端都确定</b>的集合。专用服务器上没有客户端的 config 目录，
 * 扫目录会得到服务端和客户端不一致的结果。想加角色就在这里加一行。
 *
 * <p>所有皮肤都按「wide（粗手臂）」模型渲染；slim（细手臂）留到二期。
 */
public enum MeidoSkin {

    HOSHINO("hoshino", "hoshino.png", "hoshino"),
    RIKKA("rikka", "rikka.png", "rikka"),
    KOTONE("kotone", "kotone.png", "kotone"),
    MOMOKA("momoka", "Sakurai Momoka.png", "momoka");

    /**
     * 默认皮肤。两处会落回它：
     * {@code /mymeido summon} 不带参数时，以及存档里的皮肤 id 已经不存在时
     * （比如你删掉了某个角色，老存档里的女仆不该因此变透明）。
     */
    public static final MeidoSkin DEFAULT = HOSHINO;

    private final String id;
    private final String fileName;
    private final String displayName;

    MeidoSkin(String id, String fileName, String displayName) {
        this.id = id;
        this.fileName = fileName;
        this.displayName = displayName;
    }

    /** 指令 / NBT / DataTracker 用的稳定字符串 id。 */
    public String getId() {
        return this.id;
    }

    /** 皮肤目录里的文件名（原样，含扩展名）。 */
    public String fileName() {
        return this.fileName;
    }

    /**
     * 头顶名牌 / 聊天栏里显示的<b>默认角色名</b>。
     *
     * <p>与 {@link #getId()} 分开是为了「改显示名不动存档」：
     * id 一旦写进存档就不能再变，而显示名随时可以改成中文（比如 {@code hoshino → 小鸟游星野}）。
     * 玩家用 {@code /mymeido name} 改过名之后，这里就不再起作用。
     */
    public String displayName() {
        return this.displayName;
    }

    /** 认不出来就回落 {@link #DEFAULT}，绝不返回 null。 */
    public static MeidoSkin fromId(String id) {
        if (id != null) {
            for (MeidoSkin s : values()) {
                if (s.id.equalsIgnoreCase(id)
                        || s.name().equalsIgnoreCase(id)
                        || s.fileName.equalsIgnoreCase(id)) {
                    return s;
                }
            }
        }
        return DEFAULT;
    }

    /** 命令补全用。 */
    public static List<String> ids() {
        List<String> out = new ArrayList<>(values().length);
        for (MeidoSkin s : values()) {
            out.add(s.id);
        }
        return out;
    }
}
