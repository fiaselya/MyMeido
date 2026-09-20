package com.mymeido.entity;

import java.util.Locale;
import java.util.Map;

/**
 * 一位女仆角色 —— <b>一张皮肤 = 一个角色</b>。
 *
 * <p>贴图文件放在 {@code .minecraft/config/mymeido/skins/} 下，<b>文件名就是角色名</b>：
 * {@code yuuka.png} 就是角色 {@code yuuka}，{@code 小鸟游星野.png} 就是角色 {@code 小鸟游星野}。
 * 放几张就有几个角色 —— <b>数量不设上限</b>，清单（{@link MeidoSkinRegistry}）是扫目录扫出来的。
 *
 * <h2>★ 2026-09-21 大改：从「编译期写死的枚举」改成「跟着文件夹走」</h2>
 *
 * <p>原来是 4 项写死的枚举（hoshino / rikka / kotone / momoka），玩家往 skins 里
 * 多丢一张图不会被认出来，玩家合理地以为「功能没做」。现在改成扫目录：
 * 一张 png 就是一个可选角色。
 *
 * <p><b>但 id 一旦写进存档就永远有效</b> —— {@link #fromId} <b>不校验</b>注册表，
 * 认不出来也照样按那个 id 造一个出来（只是贴图找不到、回落原版 Steve）。
 * 这样「玩家删掉某张图」不会让老存档里的女仆<b>换脸</b>（原枚举版本的
 * 「认不出来就回落默认皮肤」在动态数量下反而会串号，这是必须避开的坑）。
 *
 * <p>所有皮肤都按「wide（粗手臂）」模型渲染；slim（细手臂）留到二期。
 */
public final class MeidoSkin {

    private final String id;
    private final String fileName;
    private final String displayName;

    private MeidoSkin(String id, String fileName, String displayName) {
        this.id = id;
        this.fileName = fileName;
        this.displayName = displayName;
    }

    /** 枚举时代（≤ 0.1.0）写进存档的 id 的兼容表 —— 用法见 {@link #canonicalId}。 */
    // ⚠️ key 是 normalize("Sakurai Momoka") 的结果：sakurai + momoka 拼起来是
    //    "sakuraimomoka"（两个 i），手写时漏一个 i 就会静默不生效 —— smoke33 抓到过一次。
    private static final Map<String, String> LEGACY_IDS = Map.of("sakuraimomoka", "momoka");

    /**
     * 把<b>任意写法</b>归一成规范 id：先 {@link #normalize 规范化}，再过一次老 id 兼容表。
     *
     * <p>★ 老 id 必须保留。老存档的实体 NBT 写着 {@code MeidoSkin:"momoka"}，
     * 玩家目录里可能还有他照着当时文档写的 {@code personas/momoka.txt}。
     * 如果 0.2.0 把 {@code Sakurai Momoka.png} 规范化成 {@code sakuraimomoka}，
     * 这位女仆会<b>同时丢皮肤和丢人设</b>（皮肤找不到图 → Steve；人设卡换个名字重新生成）。
     *
     * <p>为什么只有它需要特判：另外三个角色的文件名本身就是 id
     * （{@code hoshino.png} → {@code hoshino}），只有它是「文件名 ≠ 老 id」。
     *
     * <p>这个函数同时也是那一层宽容：玩家手打 {@code /mymeido skin sakuraimomoka}
     * 也一样命中 {@code momoka}（他就着文件名推出来的写法）。
     */
    public static String canonicalId(String raw) {
        String id = normalize(raw);
        String legacy = LEGACY_IDS.get(id);
        return legacy != null ? legacy : id;
    }

    /**
     * 由磁盘上的文件名造一个皮肤。这是<b>唯一</b>会带上真实文件名的入口。
     *
     * @param fileName 目录里的真实文件名，如 {@code Sakurai Momoka.png}
     */
    public static MeidoSkin ofFile(String fileName) {
        String base = stripExtension(fileName);
        return new MeidoSkin(canonicalId(base), fileName, base);
    }

    /** 由 id 造一个「光杆」皮肤（磁盘上可能已经没有这张图了）。 */
    public static MeidoSkin ofId(String id) {
        String canonical = canonicalId(id);
        return new MeidoSkin(canonical, canonical + ".png", canonical);
    }

    /** 指令 / NBT / DataTracker 用的稳定字符串 id（已规范化）。 */
    public String getId() {
        return this.id;
    }

    /** 皮肤目录里的文件名（原样，含扩展名）。找不到图时回落成 {@code <id>.png}。 */
    public String fileName() {
        return this.fileName;
    }

    /**
     * 头顶名牌 / 聊天栏里显示的<b>默认角色名</b> —— 就是文件名去掉 {@code .png}。
     *
     * <p>与 {@link #getId()} 分开是为了「改显示名不动存档」：
     * id 一旦写进存档就不能再变，而显示名随时可以改（比如把 {@code hoshino.png}
     * 改名成 {@code 小鸟游星野.png}，存档里的 id 仍是 {@code hoshino}）。
     * 玩家用 {@code /mymeido name} 改过名之后，这里就不再起作用。
     */
    public String displayName() {
        return this.displayName;
    }

    /**
     * 由存档 / 网络里拿到的字符串还原成皮肤。
     *
     * <p><b>绝不返回 null，也绝不「认不出就换成别的角色」</b> ——
     * 先查皮肤库（能拿回带真实文件名的那一份），查不到就按这个 id 光杆造一个。
     * 见类注释里「id 一旦写进存档就永远有效」。
     */
    public static MeidoSkin fromId(String id) {
        if (id == null || id.isBlank()) {
            return MeidoSkinRegistry.defaultSkin();
        }
        MeidoSkin hit = MeidoSkinRegistry.byId(id);
        return hit != null ? hit : ofId(id);
    }

    /**
     * 规范 id：<b>小写 + 去掉空格 / 下划线 / 连字符</b>，并去掉 {@code .png} 后缀。
     *
     * <p>为什么要抹平这些差别：玩家会把 {@code Sakurai Momoka.png} 改名成
     * {@code sakurai_momoka.png} 或 {@code SakuraiMomoka.png}，
     * 这两种情况应该仍然认成同一个角色，而不是凭空多出两个。
     *
     * <pre>
     *   "Sakurai Momoka.png" -> "sakuraimomoka"
     *   "yuuka.png"          -> "yuuka"
     *   "小鸟游星野.png"      -> "小鸟游星野"
     * </pre>
     */
    public static String normalize(String rawName) {
        String name = rawName == null ? "" : rawName.toLowerCase(Locale.ROOT).strip();
        if (name.endsWith(".png")) {
            name = name.substring(0, name.length() - 4);
        }
        return name.replace(" ", "").replace("_", "").replace("-", "").strip();
    }

    private static String stripExtension(String fileName) {
        String name = fileName.strip();
        return name.toLowerCase(Locale.ROOT).endsWith(".png")
                ? name.substring(0, name.length() - 4).strip()
                : name;
    }

    /** 按 id 相等 —— 同一个角色无论从存档来还是从目录来都是同一个皮肤。 */
    @Override
    public boolean equals(Object other) {
        return other instanceof MeidoSkin skin && this.id.equals(skin.id);
    }

    @Override
    public int hashCode() {
        return this.id.hashCode();
    }

    @Override
    public String toString() {
        return this.id;
    }
}
