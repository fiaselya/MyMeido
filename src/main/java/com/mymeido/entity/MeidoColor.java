package com.mymeido.entity;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.util.math.random.Random;

/**
 * 角色的「专属色」。
 *
 * <p>用途有两个，都要求同一个色：
 * <ol>
 *   <li>聊天栏里她说的话 —— 名字用 {@link #nameColor()} 加粗，正文用 {@link #bodyColor()}；</li>
 *   <li>头顶名牌（一期先不做，二期接上）。</li>
 * </ol>
 *
 * <p>选色规则（来自设计定稿）：<b>等亮度色板</b>，正文亮度 ≥ 80%，名字 ≥ 60%。
 * 也就是所有颜色的最大通道都在 227/255 以上（≈89%），名字再统一乘 0.78
 * 压到 ≈70% —— 名字比正文略深一档但依然够亮，正文永远是最醒目的那个。
 * 每个角色一个色，多个女仆同屏时一眼能分出谁在说话。
 */
public enum MeidoColor {

    PINK("pink", 0xFF9BC4),
    CYAN("cyan", 0x7FE3E3),
    PURPLE("purple", 0xC39BFF),
    ORANGE("orange", 0xFFB07F),
    GREEN("green", 0x8FE39B),
    BLUE("blue", 0x8FB8FF),
    YELLOW("yellow", 0xFFDD7F),
    RED("red", 0xFF8F8F);

    /** 名字色 = 正文色 × 这个系数。0.78 让最大通道落在 ≈199（78%），满足「≥60%」。 */
    private static final float NAME_SCALE = 0.78f;

    private final String id;
    private final int rgb;

    MeidoColor(String id, int rgb) {
        this.id = id;
        this.rgb = rgb;
    }

    public String getId() {
        return this.id;
    }

    /** 正文色，0xRRGGBB。 */
    public int bodyColor() {
        return this.rgb;
    }

    /** 名字色，比正文深一档。 */
    public int nameColor() {
        return scale(this.rgb, NAME_SCALE);
    }

    private static int scale(int rgb, float factor) {
        int r = (rgb >> 16) & 0xFF;
        int g = (rgb >> 8) & 0xFF;
        int b = rgb & 0xFF;
        int nr = Math.min(255, Math.round(r * factor));
        int ng = Math.min(255, Math.round(g * factor));
        int nb = Math.min(255, Math.round(b * factor));
        return (nr << 16) | (ng << 8) | nb;
    }

    /** 越界一律回落 PINK。 */
    public static MeidoColor byOrdinal(int ordinal) {
        MeidoColor[] all = values();
        return ordinal >= 0 && ordinal < all.length ? all[ordinal] : PINK;
    }

    public static MeidoColor fromId(String id) {
        if (id != null) {
            for (MeidoColor c : values()) {
                if (c.id.equalsIgnoreCase(id) || c.name().equalsIgnoreCase(id)) {
                    return c;
                }
            }
        }
        return PINK;
    }

    /** 召唤时随机分一个色，免得两个女仆撞色。 */
    public static MeidoColor random(Random random) {
        return values()[random.nextInt(values().length)];
    }

    /** 命令补全用。 */
    public static List<String> ids() {
        List<String> out = new ArrayList<>(values().length);
        for (MeidoColor c : values()) {
            out.add(c.id);
        }
        return out;
    }
}
