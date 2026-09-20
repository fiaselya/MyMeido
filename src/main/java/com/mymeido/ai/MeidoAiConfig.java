package com.mymeido.ai;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

import com.mymeido.MyMeido;

import net.fabricmc.loader.api.FabricLoader;

/**
 * 对话配置（2026-09-20 大改：★ mod 不再绑定任何模型，只认「玩家给的一个 API」）。
 *
 * <h2>两种状态，没有第三种</h2>
 * <ol>
 *   <li><b>基础模式</b>（{@code api_base_url} 或 {@code api_model} 没填）——
 *       mod 完全不联网：她只会用自带台词回应你的<b>点击</b>（右键打招呼）；
 *       <b>不回复你在聊天栏说的话</b>。零配置、零依赖、开箱即玩；</li>
 *   <li><b>API 模式</b>（两者都填了）—— 她的发言接入这个 API：打招呼会生成台词，
 *       你对她说话她会回。本地跑的还是云上的都行，只要说 OpenAI 兼容协议。</li>
 * </ol>
 *
 * <p>★ 与上一版的区别：删掉了 {@code api_mode} 本地/混合/纯API 三态、
 * {@code local_*} 全套与「mod 替玩家拉起 llama-server」——
 * 上一版等于把 mod 和一个具体模型（Qwen3-4B GGUF + llama.cpp）绑死了，
 * 换模型要改一堆键。现在只有一组 {@code api_*}：填什么连什么。
 *
 * <p>格式仍是「记事本就能改」的 {@code 键=值} 纯文本（解析手写，为的是原样保留中文注释）。
 * 缺项走默认值，某行写错只跳过那一行。
 */
public final class MeidoAiConfig {

    private static final Path CONFIG_FILE =
            FabricLoader.getInstance().getConfigDir().resolve("mymeido").resolve("chat").resolve("api.txt");

    /** 旧版配置（本地/远程双后端那套），只用来提示玩家迁移。 */
    private static final Path LEGACY_FILE =
            FabricLoader.getInstance().getConfigDir().resolve("mymeido").resolve("models").resolve("backend.txt");

    /** 首次生成用的模板。头三行是明文密钥警告。 */
    private static final String TEMPLATE = """
            # ⚠️⚠️⚠️ 警告：如果你在 api_key 里填了密钥，这个文件里就是【明文密钥】。
            # ⚠️ 不要把这个文件截图、发群、发给别人 —— 等于把你的 API 账号送出去。
            # ==================================================================
            #
            # 本 mod 不绑定任何模型。下面这张卡填了 = 她会用 API 跟你聊天；
            # 不填 = 基础模式：她只用自带台词回应你的点击，不回复你打的话。
            #
            # ============ 填这三行就能用 ============
            # api_base_url  OpenAI 兼容接口的地址，结尾要带 /v1
            #   本地服务例：http://127.0.0.1:8080/v1   （llama.cpp / LM Studio / ollama 都行）
            #   云服务例：  https://api.deepseek.com/v1
            # api_key       云服务一般必填；本地服务留空也行
            # api_model     服务里实际加载的模型名，例：qwen3-4b / deepseek-chat / gpt-4o-mini
            #
            # 例（本地 llama.cpp，端口 8080）：
            #   api_base_url=http://127.0.0.1:8080/v1
            #   api_model=qwen3-4b
            # 例（云）：
            #   api_base_url=https://api.deepseek.com/v1
            #   api_key=sk-你的密钥
            #   api_model=deepseek-chat
            #
            # 改完在游戏里敲 /mymeido aireload 生效；/mymeido aistatus 看当前状态；
            # /mymeido aiguide 看「怎么把服务跑起来」的说明。
            api_base_url=
            api_key=
            api_model=

            # ============ 订阅制 / 特殊端点（大多数情况不用管）============
            #
            # 本 mod 默认按「标准 OpenAI 端点」发请求：Bearer 鉴权 + 一次性返回完整 JSON。
            # 有些「订阅制」端点不按这套来，比如：
            #   · 不接受非流式（不传 stream 直接 400）—— 典型是复用客户端登录态的私有端点；
            #   · 鉴权头不叫 Authorization，或要求伪装 User-Agent / 产品名 才给调。
            # 下面两行就是给这些情况用的开关。
            #
            # api_stream  是否改用流式（SSE）。对方报「非流式不支持」就打开。
            #             true = 按 SSE 逐片收、自己拼起来（对她来说结果一模一样）
            #             false（默认）= 一次性等完整响应
            api_stream=false
            #
            # api_extra_headers  额外的请求头，格式「名:值; 名:值」（分号隔开，只认第一个冒号）。
            #   例（要求伪装客户端头的端点）：
            #     api_extra_headers=User-Agent:CLI/1.0 Client/1.0; X-Product:SaaS
            #   例（鉴权头不是 Authorization 的端点）：
            #     api_extra_headers=x-api-key:你的密钥
            #   注意：Content-Type 和 Authorization 由 mod 自己写，这里写的同名头会被覆盖。
            api_extra_headers=
            #
            # ★ 填法提示：api_base_url 填到 /v1 这一层就行（mod 会自己接 /chat/completions）；
            #   要是你把整条 .../chat/completions 都粘进来了，也没关系 —— 不会重复拼。

            # ============ 可选 ============
            # 单次请求超时（毫秒）。本地小模型建议 15000；云 API 8000 够。
            api_timeout_ms=15000
            # 她说的话用纯白字（false = 角色专属色）。游戏里 /mymeido chatstyle 也能切，会写回这里
            chat_plain=false
            # 每轮对话后，让她「顺便」把人物设定卡更新一遍（按对话学新口癖/喜好，
            # 写进 config/mymeido/personas/<皮肤>.txt 的自动区块）。
            # 注意：这会让她每次对话多发一个请求 —— 云 API 按次计费时可以关掉，
            # 关掉后仍可用 /mymeido persona extract 手动更新。
            persona_auto_update=true

            # ============ 她主动搭话（默认开，只在 API 模式下有效）============
            #
            # 玩法：当年用「女仆契约」造她的那个玩家，在她附近待够一会儿，
            # 她会自己走过来跟他说句话（白天才来；天黑了就不打扰了）。
            # 台词是让 API 现想的，会带上你们现在的关系氛围，并避开最近说过的话。
            # ★ 没接 API（基础模式）时这条不生效 —— 她不会自己开口，也不消耗次数。
            # 现场看效果 / 排查为什么没开口：/mymeido proactive
            proactive_chat=true
            # 要在她附近待够多少【秒】才触发一次。默认 300 = 5 分钟。
            # 想快点看效果可以改成 15；造她的那个玩家离开 32 格只是暂停计时，不清零。
            proactive_interval_seconds=300
            # 每小时最多主动搭话几次（按游戏内时间算的一小时 = 1000 tick）。
            proactive_max_per_hour=2
            """;

    private static String baseUrl = "";
    private static String apiKey = "";
    private static String model = "";
    private static int timeoutMs = 15000;
    private static boolean plainChat = false;
    private static boolean autoPersonaUpdate = true;
    private static boolean stream = false;
    private static String extraHeaders = "";
    private static boolean proactiveChat = true;
    private static int proactiveIntervalSeconds = 300;
    private static int proactiveMaxPerHour = 2;

    private MeidoAiConfig() {
    }

    /** 服务端启动 / {@code /mymeido aireload} 时调用。文件不存在就生成模板。 */
    public static synchronized void load() {
        try {
            if (!Files.exists(CONFIG_FILE)) {
                Files.createDirectories(CONFIG_FILE.getParent());
                Files.writeString(CONFIG_FILE, TEMPLATE, StandardCharsets.UTF_8);
                MyMeido.LOGGER.info("[mymeido] 已生成对话配置模板：{}", CONFIG_FILE);
            }
            parse();
            if (Files.exists(LEGACY_FILE)) {
                MyMeido.LOGGER.warn("[mymeido] 检测到旧版配置 {} —— 新版本不再读它（改读 chat/api.txt），"
                        + "里面若是本地模型那套 local_* 键，新版已全部废弃；"
                        + "想继续用就把 api_base_url / api_model 填进新文件", LEGACY_FILE);
            }
        } catch (IOException e) {
            MyMeido.LOGGER.error("[mymeido] 读对话配置失败，按基础模式跑：{}", e.toString());
            applyDefaults();
        }
    }

    /** 按行解析。任何一行坏掉只跳过那一行。 */
    private static void parse() throws IOException {
        applyDefaults();
        List<String> lines = Files.readAllLines(CONFIG_FILE, StandardCharsets.UTF_8);
        for (String raw : lines) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#") || !line.contains("=")) {
                continue;
            }
            int eq = line.indexOf('=');
            String key = line.substring(0, eq).trim().toLowerCase(Locale.ROOT);
            String value = line.substring(eq + 1).trim();
            switch (key) {
                case "api_base_url" -> baseUrl = value;
                case "api_key" -> apiKey = value;
                case "api_model" -> model = value;
                case "api_timeout_ms" -> timeoutMs = parseIntOr(value, 15000);
                case "api_stream" -> stream = value.equalsIgnoreCase("true");
                case "api_extra_headers" -> extraHeaders = value;
                case "chat_plain" -> plainChat = value.equalsIgnoreCase("true");
                case "persona_auto_update" -> autoPersonaUpdate = value.equalsIgnoreCase("true");
                case "proactive_chat" -> proactiveChat = value.equalsIgnoreCase("true");
                // 兜底值刻意给「默认」而不是「关」：写错一个数字不该让整个功能静默消失。
                case "proactive_interval_seconds" ->
                        proactiveIntervalSeconds = clampInt(parseIntOr(value, 300), 5, 86_400);
                case "proactive_max_per_hour" ->
                        proactiveMaxPerHour = clampInt(parseIntOr(value, 2), 0, 100);
                default -> {
                    // 认不出的键直接忽略 —— 包括旧版的 api_mode / local_* / remote_*。
                }
            }
        }
        if (!baseUrl.isEmpty() && !model.isEmpty() && MyMeido.LOGGER.isDebugEnabled()) {
            MyMeido.LOGGER.debug("[mymeido] API 模式：{} / {}", baseUrl, model);
        }
    }

    private static void applyDefaults() {
        baseUrl = "";
        apiKey = "";
        model = "";
        timeoutMs = 15000;
        plainChat = false;
        autoPersonaUpdate = true;
        stream = false;
        extraHeaders = "";
        proactiveChat = true;
        proactiveIntervalSeconds = 300;
        proactiveMaxPerHour = 2;
    }

    private static int parseIntOr(String value, int fallback) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /** 把数字夹进合理区间 —— 配置是记事本手写的，0 秒或 -1 次这种值要让它们变回人话。 */
    private static int clampInt(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    // ------------------------------------------------------------------
    // 只读访问器（MeidoAi 路由用）
    // ------------------------------------------------------------------

    /** ★ 判据只有一条：地址和模型名都填了，才认为玩家接好了 API。 */
    public static synchronized boolean aiEnabled() {
        return !baseUrl.isBlank() && !model.isBlank();
    }

    public static synchronized String baseUrl() {
        return baseUrl;
    }

    public static synchronized String apiKey() {
        return apiKey;
    }

    public static synchronized String model() {
        return model;
    }

    public static synchronized int timeoutMs() {
        return timeoutMs;
    }

    public static synchronized boolean plainChat() {
        return plainChat;
    }

    /** 每轮对话后是否自动让 API 更新人设卡（关掉仍可手动 /mymeido persona extract）。 */
    public static synchronized boolean autoPersonaUpdate() {
        return autoPersonaUpdate;
    }

    /** 是否走 SSE 流式（{@code api_stream}）。默认关 —— 老配置行为一字不变。 */
    public static synchronized boolean stream() {
        return stream;
    }

    /** {@code api_extra_headers} 的原始文本（未解析）。 */
    public static synchronized String extraHeadersRaw() {
        return extraHeaders;
    }

    /** 已解析的自定义请求头（{@code [[名, 值], ...]}）。 */
    public static synchronized List<String[]> extraHeaders() {
        return MeidoLlm.parseHeaders(extraHeaders);
    }

    /** 她会不会主动搭话（总开关）。注意：这个开关**不能**替代 {@link #aiEnabled()} —— 见下面的组合判据。 */
    public static synchronized boolean proactiveChat() {
        return proactiveChat;
    }

    /** 停留多少秒触发一次。 */
    public static synchronized int proactiveIntervalSeconds() {
        return proactiveIntervalSeconds;
    }

    /** 每小时最多几次。 */
    public static synchronized int proactiveMaxPerHour() {
        return proactiveMaxPerHour;
    }

    /**
     * ★ 「她会不会主动搭话」的<b>唯一</b>判据 = 开关开着 <b>且</b> 接了 API。
     *
     * <p>为什么不只看 {@link #proactiveChat()}：主动搭话的台词是让 API 现想的（设计上就必须不重复、
     * 还要按好感度来），基础模式没有 API 就没法满足这两条。与其退化成几句写死的台词天天重复，
     * 不如<b>基础模式下干脆不主动开口</b> —— 这也和「基础模式只有自带点击台词」的定稿一致。
     */
    public static synchronized boolean proactiveEnabled() {
        return proactiveChat && aiEnabled();
    }

    /**
     * ★ 一次请求的全部参数，收成一个对象 —— 3 个调用点共用，
     * 以后再加开关只改这里，不用满世界找 {@code MeidoLlm.chat(} 的三处手抄。
     */
    public static synchronized MeidoLlm.Options llmOptions() {
        return new MeidoLlm.Options(baseUrl, model, apiKey, timeoutMs, stream, MeidoLlm.parseHeaders(extraHeaders));
    }

    public static synchronized Path configFile() {
        return CONFIG_FILE;
    }

    /**
     * 没接 API 时给玩家的一句说明（聊天栏一次性提示用）；接了 API 返回空串。
     */
    public static synchronized String statusHint() {
        if (aiEnabled()) {
            return "";
        }
        return "还没有接上 API，她只会用自带台词回应你的点击（/mymeido aiguide 看怎么接）";
    }

    /**
     * 「怎么把 API 跑起来」的说明（{@code /mymeido aiguide} 与文档共用一份，改不散）。
     * 本 mod 不下载、不启动任何模型 —— 只告诉玩家去哪儿弄、填哪几行。
     */
    public static List<String> guideLines() {
        return List.of(
                "本 mod 不绑定模型。要让她会聊天，你自己起一个 OpenAI 兼容服务，然后把地址填给她：",
                "──── 路线 A：本地服务（免费、断网可用）────",
                "① 起服务，三选一：",
                "   · llama.cpp：llama-server -m 你的模型.gguf --port 8080 -c 12288 --jinja",
                "     下载：https://github.com/ggml-org/llama.cpp/releases",
                "   · LM Studio：图形界面里加载模型后开「Local Server」，默认 http://127.0.0.1:1234/v1",
                "   · ollama：ollama run qwen3:4b（默认 http://127.0.0.1:11434/v1）",
                "② 在 config/mymeido/chat/api.txt 填：",
                "   api_base_url=http://127.0.0.1:8080/v1",
                "   api_model=你的模型名（llama.cpp 随便填，LM Studio/ollama 要填对）",
                "   （本地服务 api_key 留空即可）",
                "③ 游戏里敲 /mymeido aireload",
                "──── 路线 B：云 API（省事、要密钥、要联网）────",
                "① api_base_url 填服务商地址（如 https://api.deepseek.com/v1）",
                "② api_key 填你的密钥；api_model 填模型名（如 deepseek-chat）",
                "③ /mymeido aireload",
                "──── 路线 C：订阅制 / 特殊端点 ────",
                "只要对方是 OpenAI 兼容（POST 地址 + /chat/completions + 能收 messages），就能用。",
                "遇到下面两种「不兼容」，配置里有两个开关：",
                "① 报「非流式不支持」/ 400 带 stream 字样 → api_stream=true",
                "② 要求伪装请求头、或鉴权头不叫 Authorization",
                "   → api_extra_headers=名:值; 名:值（例：User-Agent:CLI/1.0 C/1.0; X-Product:SaaS）",
                "③ 改完记得 /mymeido aireload，再 /mymeido aistatus 确认「流式」和「自定义头」对上了",
                "──── 基础模式（什么都不填）────",
                "她仍然会说话：右键点她，用 mod 自带的台词回应你；但不会回复你打的话。",
                "──── 她会主动搭话吗 ────",
                "只有 API 模式才会：造她的那个玩家在她附近待够 5 分钟，她白天会走过来搭一句话。",
                "台词让 API 现想（带关系氛围、避开最近说过的），每小时最多 2 次。",
                "想调时间/次数/关掉 → chat/api.txt 的 proactive_* 三行；现场看状态 → /mymeido proactive。");
    }

    /**
     * 切纯白模式并<b>写回文件</b>。写回是按行替换/追加，中文注释原样保留。
     *
     * @return 切换后的状态（true = 现在是纯白）
     */
    public static synchronized boolean togglePlainChat() {
        plainChat = !plainChat;
        try {
            List<String> lines = Files.exists(CONFIG_FILE)
                    ? Files.readAllLines(CONFIG_FILE, StandardCharsets.UTF_8)
                    : List.of(TEMPLATE.split("\n", -1));
            StringBuilder out = new StringBuilder();
            boolean replaced = false;
            for (String line : lines) {
                if (line.trim().startsWith("chat_plain=")) {
                    out.append("chat_plain=").append(plainChat).append('\n');
                    replaced = true;
                } else {
                    out.append(line).append('\n');
                }
            }
            if (!replaced) {
                out.append("chat_plain=").append(plainChat).append('\n');
            }
            Files.createDirectories(CONFIG_FILE.getParent());
            Files.writeString(CONFIG_FILE, out.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            MyMeido.LOGGER.error("[mymeido] 写回 chat_plain 失败（本次会话内仍然生效）：{}", e.toString());
        }
        return plainChat;
    }
}
