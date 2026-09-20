package com.mymeido;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

import net.fabricmc.loader.api.FabricLoader;

/**
 * 全局语言：<b>「这个 mod 说中文还是英文」的唯一判据</b>。
 *
 * <h2>为什么要它</h2>
 * Minecraft 自带的语言文件（{@code assets/mymeido/lang/*.json}）只能翻译走
 * {@code Text.translatable} 的东西 —— 物品名、按键、实体名。而本 mod 有大量文案
 * <b>不是</b> lang 文件能覆盖的：
 * <ul>
 *   <li>运行时<b>生成的文件内容</b>：{@code chat/api.txt} 的注释模板、{@code modes.json}
 *       的 name/desc、人设卡模板、记忆副本 Markdown；</li>
 *   <li>发给模型的 <b>prompt</b> —— 她用什么语言回你，取决于 prompt 用什么语言写；</li>
 *   <li>指令反馈、聊天栏提示、自带台词。</li>
 * </ul>
 * 这些地方一律走 {@link #pick(String, String)}：中文在前、英文在后，一眼能看出两种语言
 * 是否都写了。
 *
 * <h2>语言怎么定（从上到下，先命中先用）</h2>
 * <ol>
 *   <li>{@code settings.txt} 里 {@code language=zh_cn / en_us} —— 玩家显式指定，最高优先；</li>
 *   <li>客户端上报的当前语言（{@code MyMeidoClient} 在初始化与资源重载时喂进来）；</li>
 *   <li>{@code options.txt} 里的 {@code lang:} —— 服务端也能读（存档目录里的游戏设置）；</li>
 *   <li>JVM 默认语言（{@link Locale#getDefault()}）—— 专用服务器上基本只剩这一条；</li>
 *   <li>都不命中 → <b>英文</b>（国际默认）。</li>
 * </ol>
 *
 * <p>★ 为什么不用 {@code Text.translatable} 一把梭：那些是<b>纯字符串</b>场景
 * （写文件、发 prompt），拿不到 {@code Text}；而且服务端侧本来就没有「玩家的语言」
 * 这个概念。这里选的是「整个实例一个语言」，够用、可诊断、出问题好排查。
 *
 * <p>★ 语言只在 {@link #load()} 与客户端上报时重算，运行期不读盘 ——
 * {@link #pick} 会被高频调用（每帧/每 tick 级别），不能在里面做 IO。
 */
public final class MeidoLocale {

    /** 生效语言只有两种。没有第三种，也就不需要回退链。 */
    public enum Lang {
        ZH, EN
    }

    private static final Path CONFIG_DIR = FabricLoader.getInstance().getConfigDir().resolve("mymeido");
    private static final Path SETTINGS_FILE = CONFIG_DIR.resolve("settings.txt");

    /** 首次生成的模板。中英各写一遍，免得英文玩家看不懂怎么改。 */
    private static final String TEMPLATE = """
            # ============================== 全局设置 ==============================
            #
            # language  这个 mod 说哪种语言 / which language this mod speaks
            #   auto   = 跟随游戏语言（客户端）或系统语言（服务端）  follow the game/system language
            #   zh_cn  = 一律中文                                  always Chinese
            #   en_us  = 一律英文                                  always English
            #
            # 影响范围 / what it affects:
            #   · 指令反馈、聊天栏提示、女仆的自带台词
            #   · 生成的文件内容：chat/api.txt 的注释、modes.json 的模式说明、
            #     人设卡模板、memory/ 里的 Markdown 副本
            #   · 发给模型的提示词（也就是「她用中文还是英文跟你说话」）
            #   注意：物品名/按键名走游戏自带的语言文件，跟这里无关 —— 那是你的
            #   游戏语言设置说了算。
            #
            # 改完敲 /mymeido aireload 生效；/mymeido lang 可以看当前认成了哪种。
            language=auto
            """;

    private static volatile String setting = "auto";
    private static volatile Lang current = Lang.EN;
    /** 客户端上报的当前语言（如 {@code zh_cn}）。服务端为 null。 */
    private static volatile String clientLanguage = null;

    private MeidoLocale() {
    }

    // ------------------------------------------------------------------
    // 装载与判定
    // ------------------------------------------------------------------

    /** 服务端启动 / {@code /mymeido aireload} 时调用。文件不存在就写模板。 */
    public static synchronized void load() {
        try {
            if (!Files.exists(SETTINGS_FILE)) {
                Files.createDirectories(CONFIG_DIR);
                Files.writeString(SETTINGS_FILE, TEMPLATE, StandardCharsets.UTF_8);
                MyMeido.LOGGER.info("[mymeido] wrote default settings template: {}", SETTINGS_FILE);
            }
            setting = parseSetting(SETTINGS_FILE);
        } catch (IOException | RuntimeException e) {
            MyMeido.LOGGER.warn("[mymeido] cannot read {} (keeping '{}'): {}",
                    SETTINGS_FILE, setting, e.toString());
        }
        current = resolve();
    }

    /** 客户端在初始化 / 资源重载时把 {@code MinecraftClient} 的当前语言喂进来。 */
    public static synchronized void setClientLanguage(String code) {
        clientLanguage = (code == null || code.isBlank()) ? null : code;
        current = resolve();
    }

    /** 解析 {@code settings.txt} 里的 language 行。认不出的一律当 auto。 */
    private static String parseSetting(Path file) throws IOException {
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        for (String raw : lines) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#") || !line.contains("=")) {
                continue;
            }
            int eq = line.indexOf('=');
            if (!line.substring(0, eq).trim().equalsIgnoreCase("language")) {
                continue;
            }
            return normalizeSetting(line.substring(eq + 1).trim());
        }
        return "auto";
    }

    /** 把玩家手写的各种写法收敛成三选一。 */
    public static String normalizeSetting(String raw) {
        if (raw == null) {
            return "auto";
        }
        String v = raw.trim().toLowerCase(Locale.ROOT).replace('-', '_');
        switch (v) {
            case "zh", "zh_cn", "cn", "chinese", "chinese_simplified", "中文" -> {
                return "zh_cn";
            }
            case "en", "en_us", "english", "英文" -> {
                return "en_us";
            }
            default -> {
                return "auto";
            }
        }
    }

    /** 真正的那条回退链。 */
    private static Lang resolve() {
        switch (setting) {
            case "zh_cn" -> {
                return Lang.ZH;
            }
            case "en_us" -> {
                return Lang.EN;
            }
            default -> {
                // auto：往下找
            }
        }
        Lang fromClient = fromClientLanguage();
        if (fromClient != null) {
            return fromClient;
        }
        Lang fromOptions = fromOptionsFile();
        if (fromOptions != null) {
            return fromOptions;
        }
        Lang fromJvm = fromJvmLocale();
        if (fromJvm != null) {
            return fromJvm;
        }
        return Lang.EN;
    }

    /** 「zh_cn」这类语言码 → 语言。只认主语言前缀，{@code zh_tw} / {@code zh_hk} 也算中文。 */
    private static Lang fromCode(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        String c = code.trim().toLowerCase(Locale.ROOT).replace('-', '_');
        if (c.equals("zh") || c.startsWith("zh_")) {
            return Lang.ZH;
        }
        if (c.startsWith("en")) {
            return Lang.EN;
        }
        // 既不是中文也不是英文（日语 / 俄语 / …）→ 走英文
        return Lang.EN;
    }

    private static Lang fromClientLanguage() {
        return fromCode(clientLanguage);
    }

    /**
     * 读存档目录下的 {@code options.txt}（里面有一行 {@code lang:zh_cn}）。
     *
     * <p>★ 用文件而不是客户端类：{@code src/main} 是「公共端」，编译期看不到
     * {@code net.minecraft.client.*}，引用它会让专用服务器在加载类时炸掉。
     * 这个文件在「客户端 + 集成服务器」下一定存在，专用服务器上读不到就往下走。
     */
    private static Lang fromOptionsFile() {
        try {
            Path options = FabricLoader.getInstance().getGameDir().resolve("options.txt");
            if (!Files.exists(options)) {
                return null;
            }
            for (String line : Files.readAllLines(options, StandardCharsets.UTF_8)) {
                String s = line.trim();
                if (s.startsWith("lang:")) {
                    return fromCode(s.substring("lang:".length()));
                }
            }
        } catch (IOException | RuntimeException e) {
            // 读不到就当没这一环 —— 语言判定绝不该让游戏起不来
        }
        return null;
    }

    private static Lang fromJvmLocale() {
        try {
            return fromCode(Locale.getDefault().getLanguage());
        } catch (RuntimeException e) {
            return null;
        }
    }

    // ------------------------------------------------------------------
    // 给业务用的读口
    // ------------------------------------------------------------------

    /** {@code settings.txt} 里写的原始值（{@code auto} / {@code zh_cn} / {@code en_us}）。 */
    public static String setting() {
        return setting;
    }

    /** 当前生效的语言。 */
    public static Lang current() {
        return current;
    }

    public static boolean isZh() {
        return current == Lang.ZH;
    }

    public static boolean isEn() {
        return current == Lang.EN;
    }

    /**
     * ★ 全 mod 唯一的双语取值口。中文写前面、英文写后面，两边都别偷懒写一半。
     *
     * <p>例：{@code MeidoLocale.pick("背包满了", "Inventory is full")}
     */
    public static String pick(String zh, String en) {
        return current == Lang.ZH ? zh : en;
    }

    /** 语言码，用来拼文件名/日志（{@code zh_cn} / {@code en_us}）。 */
    public static String code() {
        return current == Lang.ZH ? "zh_cn" : "en_us";
    }

    /** 语言自身的人类可读名，永远用它自己的语言写（诊断输出用）。 */
    public static String displayName() {
        return current == Lang.ZH ? "中文（简体）" : "English (US)";
    }

    /** 因为哪一环定下来的 —— 玩家问「为什么是英文」时看这行。 */
    public static String source() {
        if (!"auto".equals(setting)) {
            return "settings.txt: language=" + setting;
        }
        if (clientLanguage != null) {
            return "game language: " + clientLanguage;
        }
        if (fromOptionsFile() != null) {
            return "options.txt lang:";
        }
        if (fromJvmLocale() != null) {
            return "system locale: " + Locale.getDefault();
        }
        return "fallback";
    }

    /** 配置文件位置（诊断输出用）。 */
    public static Path configFile() {
        return SETTINGS_FILE;
    }

    // ------------------------------------------------------------------
    // 写回
    // ------------------------------------------------------------------

    /**
     * 把 {@code language=} 改成 {@code value} 并写回文件（{@code /mymeido lang} 用）。
     *
     * <p>★ 只改 language 那一行，其它行（含玩家自己加的注释）原样留着 ——
     * 玩家可能在这个文件里写了别的设置，整份覆盖会把它吃掉。
     *
     * @return 写成功返回 true；文件写不进去返回 false（内存里仍然生效）
     */
    public static synchronized boolean save(String value) {
        setting = normalizeSetting(value);
        current = resolve();
        try {
            if (!Files.exists(SETTINGS_FILE)) {
                Files.createDirectories(CONFIG_DIR);
                Files.writeString(SETTINGS_FILE, TEMPLATE, StandardCharsets.UTF_8);
            }
            List<String> lines = Files.readAllLines(SETTINGS_FILE, StandardCharsets.UTF_8);
            boolean replaced = false;
            for (int i = 0; i < lines.size(); i++) {
                String s = lines.get(i).trim();
                if (s.startsWith("#") || !s.contains("=")) {
                    continue;
                }
                if (s.substring(0, s.indexOf('=')).trim().equalsIgnoreCase("language")) {
                    lines.set(i, "language=" + setting);
                    replaced = true;
                    break;
                }
            }
            if (!replaced) {
                lines.add("language=" + setting);
            }
            Files.write(SETTINGS_FILE, lines, StandardCharsets.UTF_8);
            return true;
        } catch (IOException | RuntimeException e) {
            MyMeido.LOGGER.warn("[mymeido] cannot write {}: {}", SETTINGS_FILE, e.toString());
            return false;
        }
    }
}
