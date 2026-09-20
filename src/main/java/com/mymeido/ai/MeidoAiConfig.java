package com.mymeido.ai;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

import com.mymeido.MeidoLocale;
import com.mymeido.MyMeido;

import net.fabricmc.loader.api.FabricLoader;

/**
 * Conversation config (major rework 2026-09-20: ★ the mod no longer binds to any model,
 * it only trusts "one API the player provides").
 *
 * <h2>Two states, no third</h2>
 * <ol>
 *   <li><b>Basic mode</b> ({@code api_base_url} or {@code api_model} left blank) ——
 *       the mod never touches the network: she only answers your <b>clicks</b>
 *       (right-click greeting) with built-in lines; <b>she does not reply to what you
 *       type in chat</b>. Zero config, zero dependency, play out of the box;</li>
 *   <li><b>API mode</b> (both filled) —— her speech is routed to this API: greetings
 *       get generated lines, and she replies when you talk to her. Local or cloud
 *       both work, as long as it speaks the OpenAI-compatible protocol.</li>
 * </ol>
 *
 * <p>★ Difference from the previous version: removed the old {@code api_mode}
 * local/hybrid/pure-API tri-state, the whole {@code local_*} set and "mod launches
 * llama-server for the player" —— the old version hard-bound the mod to one concrete
 * model (Qwen3-4B GGUF + llama.cpp); switching models meant editing a pile of keys.
 * Now there is only one group of {@code api_*}: fill what you connect to.
 *
 * <p>Format is still "notebook-editable" {@code key=value} plain text (hand-written
 * parser, so the Chinese comments are preserved verbatim). Missing items fall back to
 * defaults; a bad line only skips that line.
 */
public final class MeidoAiConfig {

    private static final Path CONFIG_FILE =
            FabricLoader.getInstance().getConfigDir().resolve("mymeido").resolve("chat").resolve("api.txt");

    /** Old config (local/remote dual-backend set), only used to nudge the player to migrate. */
    private static final Path LEGACY_FILE =
            FabricLoader.getInstance().getConfigDir().resolve("mymeido").resolve("models").resolve("backend.txt");

    /** First-run template (ZH). The first three lines are a plaintext-key warning. */
    private static final String TEMPLATE_ZH = """
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

    /** First-run template (EN). Mirrors TEMPLATE_ZH; only the comments differ. */
    private static final String TEMPLATE_EN = """
            # WARNING: if you put a secret key in api_key, it is stored here in PLAINTEXT.
            # Do NOT screenshot, post, or share this file -- that hands out your API account.
            # ==================================================================
            #
            # This mod is not tied to any model. Fill the card below = she chats via the API;
            # leave it blank = basic mode: she only answers your clicks with built-in lines,
            # and does not reply to what you type.
            #
            # ============ Fill these three lines to use ============
            # api_base_url  OpenAI-compatible endpoint address, must end with /v1
            #   local e.g.: http://127.0.0.1:8080/v1   (llama.cpp / LM Studio / ollama all work)
            #   cloud e.g.: https://api.deepseek.com/v1
            # api_key       usually required for cloud; can be left blank for local
            # api_model     the model actually loaded on the service, e.g. qwen3-4b / deepseek-chat / gpt-4o-mini
            #
            # Example (local llama.cpp, port 8080):
            #   api_base_url=http://127.0.0.1:8080/v1
            #   api_model=qwen3-4b
            # Example (cloud):
            #   api_base_url=https://api.deepseek.com/v1
            #   api_key=sk-your-key
            #   api_model=deepseek-chat
            #
            # After editing, run /mymeido aireload in-game to apply; /mymeido aistatus shows current state;
            # /mymeido aiguide explains "how to get a service running".
            api_base_url=
            api_key=
            api_model=

            # ============ Subscription / special endpoints (usually ignore) ============
            #
            # This mod sends requests as a "standard OpenAI endpoint" by default: Bearer auth
            # + one-shot full JSON. Some "subscription" endpoints break this, e.g.:
            #   - reject non-streaming (400 without stream) -- typical of private endpoints reusing
            #     a client login session;
            #   - auth header is not Authorization, or requires a spoofed User-Agent / product name.
            # The two lines below are switches for these cases.
            #
            # api_stream  switch to streaming (SSE). Turn on if the other side says "non-streaming unsupported".
            #             true  = receive SSE chunks and stitch them (result is identical to her)
            #             false (default) = wait for the full response at once
            api_stream=false
            #
            # api_extra_headers  extra request headers, format "name:value; name:value" (semicolon separated,
            #                    only the first colon counts).
            #   e.g. (endpoint requiring spoofed client headers):
            #     api_extra_headers=User-Agent:CLI/1.0 Client/1.0; X-Product:SaaS
            #   e.g. (endpoint whose auth header is not Authorization):
            #     api_extra_headers=x-api-key:your-key
            #   note: Content-Type and Authorization are written by the mod; same-named headers here are overridden.
            api_extra_headers=
            #
            # TIP: api_base_url only needs to reach the /v1 level (the mod appends /chat/completions itself);
            #   if you pasted the full .../chat/completions, that is fine too -- it will not be duplicated.

            # ============ Optional ============
            # Per-request timeout (ms). 15000 for local small models; 8000 is enough for cloud API.
            api_timeout_ms=15000
            # Her speech uses plain white text (false = character-specific color). /mymeido chatstyle toggles it too, and writes back here.
            chat_plain=false
            # After each conversation, let her "incidentally" refresh the persona card (learns new catchphrases/likes
            # from the chat, written into the auto block of config/mymeido/personas/<skin>.txt).
            # Note: this makes her send one extra request per conversation -- for per-call billed cloud APIs you can
            # turn it off; after turning off you can still run /mymeido persona extract manually.
            persona_auto_update=true

            # ============ Proactive chat (on by default, only effective in API mode) ============
            #
            # How it works: the player who created her with the "maid contract" stays near her long enough,
            # and she walks over to say something (only in daytime; leaves you alone at night).
            # The line is generated live by the API, carrying your current relationship mood and avoiding
            # recently said lines.
            # NOTE: without an API (basic mode) this does nothing -- she won't speak on her own and won't consume a count.
            # Check effect live / debug why she didn't speak: /mymeido proactive
            proactive_chat=true
            # How many [seconds] near her before one trigger. Default 300 = 5 minutes.
            # Set to 15 to see the effect faster; the creating player leaving 32 blocks only pauses the timer, not reset.
            proactive_interval_seconds=300
            # Max proactive lines per hour (in-game hour = 1000 ticks).
            proactive_max_per_hour=2
            """;

    /** Resolves the template for the current language at call time. */
    private static String template() {
        return MeidoLocale.pick(TEMPLATE_ZH, TEMPLATE_EN);
    }

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

    /** Called on server start / {@code /mymeido aireload}. Generates the template if the file is missing. */
    public static synchronized void load() {
        try {
            if (!Files.exists(CONFIG_FILE)) {
                Files.createDirectories(CONFIG_FILE.getParent());
                Files.writeString(CONFIG_FILE, template(), StandardCharsets.UTF_8);
                MyMeido.LOGGER.info("[mymeido] generated chat config template: {}", CONFIG_FILE);
            }
            parse();
            if (Files.exists(LEGACY_FILE)) {
                MyMeido.LOGGER.warn("[mymeido] found legacy config {} -- the new version no longer reads it "
                        + "(now reads chat/api.txt); if it had the old local_* model keys, they are all removed; "
                        + "to keep using, fill api_base_url / api_model into the new file", LEGACY_FILE);
            }
        } catch (IOException e) {
            MyMeido.LOGGER.error("[mymeido] failed to read chat config, falling back to basic mode: {}", e.toString());
            applyDefaults();
        }
    }

    /** Parses line by line. A bad line only skips that line. */
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
                // Fallback value is deliberately "default" rather than "off": a wrong number
                // shouldn't silently kill the whole feature.
                case "proactive_interval_seconds" ->
                        proactiveIntervalSeconds = clampInt(parseIntOr(value, 300), 5, 86_400);
                case "proactive_max_per_hour" ->
                        proactiveMaxPerHour = clampInt(parseIntOr(value, 2), 0, 100);
                default -> {
                    // Unknown keys are ignored -- including the old api_mode / local_* / remote_*.
                }
            }
        }
        if (!baseUrl.isEmpty() && !model.isEmpty() && MyMeido.LOGGER.isDebugEnabled()) {
            MyMeido.LOGGER.debug("[mymeido] API mode: {} / {}", baseUrl, model);
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

    /** Clamps a number into a sane range -- the config is hand-edited in Notepad, so 0s or -1s
     *  should be turned back into something human. */
    private static int clampInt(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    // ------------------------------------------------------------------
    // Read-only accessors (used by MeidoAi routing)
    // ------------------------------------------------------------------

    /** ★ The only judgment: both address and model name filled => the player connected an API. */
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

    /** Whether to let the API refresh the persona card after each conversation
     *  (can still run /mymeido persona extract manually when off). */
    public static synchronized boolean autoPersonaUpdate() {
        return autoPersonaUpdate;
    }

    /** Whether to use SSE streaming ({@code api_stream}). Default off -- old config behavior unchanged. */
    public static synchronized boolean stream() {
        return stream;
    }

    /** Raw text of {@code api_extra_headers} (unparsed). */
    public static synchronized String extraHeadersRaw() {
        return extraHeaders;
    }

    /** Parsed custom headers ({@code [[name, value], ...]}). */
    public static synchronized List<String[]> extraHeaders() {
        return MeidoLlm.parseHeaders(extraHeaders);
    }

    /** Whether she chats proactively (master switch). Note: this switch does NOT replace
     *  {@link #aiEnabled()} -- see the combined judgment below. */
    public static synchronized boolean proactiveChat() {
        return proactiveChat;
    }

    /** Seconds to stay before a trigger. */
    public static synchronized int proactiveIntervalSeconds() {
        return proactiveIntervalSeconds;
    }

    /** Max times per hour. */
    public static synchronized int proactiveMaxPerHour() {
        return proactiveMaxPerHour;
    }

    /**
     * ★ The <b>only</b> judgment for "will she chat proactively" = switch on <b>and</b> API connected.
     *
     * <p>Why not just {@link #proactiveChat()}: the proactive line is generated live by the API
     * (by design it must be non-repeating and mood-aware), so basic mode -- with no API -- can't
     * satisfy either. Rather than degrade to a few hardcoded lines repeated daily, it is better to
     * <b>simply stay silent in basic mode</b> -- consistent with the finalized "basic mode only has
     * built-in click lines".
     */
    public static synchronized boolean proactiveEnabled() {
        return proactiveChat && aiEnabled();
    }

    /**
     * ★ One object holding all request params -- shared by 3 call sites, so adding a switch later
     *  only touches here instead of hunting down the three hand-copied {@code MeidoLlm.chat(} calls.
     */
    public static synchronized MeidoLlm.Options llmOptions() {
        return new MeidoLlm.Options(baseUrl, model, apiKey, timeoutMs, stream, MeidoLlm.parseHeaders(extraHeaders));
    }

    public static synchronized Path configFile() {
        return CONFIG_FILE;
    }

    /**
     * A one-shot chat hint shown when no API is connected; empty string when an API is connected.
     */
    public static synchronized String statusHint() {
        if (aiEnabled()) {
            return "";
        }
        return MeidoLocale.pick(
                "还没有接上 API，她只会用自带台词回应你的点击（/mymeido aiguide 看怎么接）",
                "No API connected yet -- she will only reply to your clicks with built-in lines "
                        + "(use /mymeido aiguide to see how to connect one)");
    }

    /**
     * "How to get an API running" guide (shared by {@code /mymeido aiguide} and docs, edited in one place).
     * This mod does not download or launch any model -- it only tells the player where to get one
     * and which lines to fill.
     */
    public static List<String> guideLines() {
        return List.of(
                MeidoLocale.pick(
                        "本 mod 不绑定模型。要让她会聊天，你自己起一个 OpenAI 兼容服务，然后把地址填给她：",
                        "This mod is not tied to any model. To let her chat, run an OpenAI-compatible "
                                + "service yourself and give her the address:"),
                MeidoLocale.pick(
                        "──── 路线 A：本地服务（免费、断网可用）────",
                        "──── Path A: Local service (free, works offline) ────"),
                MeidoLocale.pick(
                        "① 起服务，三选一：",
                        "① Start a service, pick one of three:"),
                MeidoLocale.pick(
                        "   · llama.cpp：llama-server -m 你的模型.gguf --port 8080 -c 12288 --jinja",
                        "   · llama.cpp: llama-server -m your-model.gguf --port 8080 -c 12288 --jinja"),
                MeidoLocale.pick(
                        "     下载：https://github.com/ggml-org/llama.cpp/releases",
                        "     Download: https://github.com/ggml-org/llama.cpp/releases"),
                MeidoLocale.pick(
                        "   · LM Studio：图形界面里加载模型后开「Local Server」，默认 http://127.0.0.1:1234/v1",
                        "   · LM Studio: load a model in the GUI then enable 'Local Server', "
                                + "default http://127.0.0.1:1234/v1"),
                MeidoLocale.pick(
                        "   · ollama：ollama run qwen3:4b（默认 http://127.0.0.1:11434/v1）",
                        "   · ollama: ollama run qwen3:4b (default http://127.0.0.1:11434/v1)"),
                MeidoLocale.pick(
                        "② 在 config/mymeido/chat/api.txt 填：",
                        "② In config/mymeido/chat/api.txt fill:"),
                "   api_base_url=http://127.0.0.1:8080/v1",
                MeidoLocale.pick(
                        "   api_model=你的模型名（llama.cpp 随便填，LM Studio/ollama 要填对）",
                        "   api_model=your-model-name (any value for llama.cpp; must be exact for LM Studio/ollama)"),
                MeidoLocale.pick(
                        "   （本地服务 api_key 留空即可）",
                        "   (for local service, leave api_key empty)"),
                MeidoLocale.pick(
                        "③ 游戏里敲 /mymeido aireload",
                        "③ In-game run /mymeido aireload"),
                MeidoLocale.pick(
                        "──── 路线 B：云 API（省事、要密钥、要联网）────",
                        "──── Path B: Cloud API (convenient, needs key & network) ────"),
                MeidoLocale.pick(
                        "① api_base_url 填服务商地址（如 https://api.deepseek.com/v1）",
                        "① api_base_url = your provider's address (e.g. https://api.deepseek.com/v1)"),
                MeidoLocale.pick(
                        "② api_key 填你的密钥；api_model 填模型名（如 deepseek-chat）",
                        "② api_key = your key; api_model = the model name (e.g. deepseek-chat)"),
                MeidoLocale.pick(
                        "③ /mymeido aireload",
                        "③ /mymeido aireload"),
                MeidoLocale.pick(
                        "──── 路线 C：订阅制 / 特殊端点 ────",
                        "──── Path C: Subscription / special endpoints ────"),
                MeidoLocale.pick(
                        "只要对方是 OpenAI 兼容（POST 地址 + /chat/completions + 能收 messages），就能用。",
                        "As long as it is OpenAI-compatible (POST URL + /chat/completions + accepts messages), it works."),
                MeidoLocale.pick(
                        "遇到下面两种「不兼容」，配置里有两个开关：",
                        "If you hit either of these 'incompatibilities', there are two switches in the config:"),
                MeidoLocale.pick(
                        "① 报「非流式不支持」/ 400 带 stream 字样 → api_stream=true",
                        "① Error 'non-streaming not supported' / 400 mentioning stream -> api_stream=true"),
                MeidoLocale.pick(
                        "② 要求伪装请求头、或鉴权头不叫 Authorization",
                        "② Requires spoofed request headers, or the auth header isn't Authorization"),
                MeidoLocale.pick(
                        "   → api_extra_headers=名:值; 名:值（例：User-Agent:CLI/1.0 C/1.0; X-Product:SaaS）",
                        "   -> api_extra_headers=name:value; name:value (e.g. User-Agent:CLI/1.0 C/1.0; X-Product:SaaS)"),
                MeidoLocale.pick(
                        "③ 改完记得 /mymeido aireload，再 /mymeido aistatus 确认「流式」和「自定义头」对上了",
                        "③ After editing, run /mymeido aireload, then /mymeido aistatus to confirm "
                                + "'streaming' and 'custom headers' are applied"),
                MeidoLocale.pick(
                        "──── 基础模式（什么都不填）────",
                        "──── Basic mode (fill nothing) ────"),
                MeidoLocale.pick(
                        "她仍然会说话：右键点她，用 mod 自带的台词回应你；但不会回复你打的话。",
                        "She still talks: right-click her and she replies with the mod's built-in lines; "
                                + "but she won't answer what you type."),
                MeidoLocale.pick(
                        "──── 她会主动搭话吗 ────",
                        "──── Will she talk to you on her own? ────"),
                MeidoLocale.pick(
                        "只有 API 模式才会：造她的那个玩家在她附近待够 5 分钟，她白天会走过来搭一句话。",
                        "Only in API mode: the player who created her stays near her for 5 minutes, "
                                + "and she'll walk over and say something during daytime."),
                MeidoLocale.pick(
                        "台词让 API 现想（带关系氛围、避开最近说过的），每小时最多 2 次。",
                        "Lines are generated live by the API (with relationship mood, avoiding recent repeats), "
                                + "at most 2 times per hour."),
                MeidoLocale.pick(
                        "想调时间/次数/关掉 → chat/api.txt 的 proactive_* 三行；现场看状态 → /mymeido proactive。",
                        "To adjust timing/count/disable -> the proactive_* lines in chat/api.txt; "
                                + "to check live status -> /mymeido proactive."));
    }

    /**
     * Toggles plain-white mode and <b>writes it back</b>. The write is a line replace/append,
     * Chinese comments preserved verbatim.
     *
     * @return the toggled state (true = now plain white)
     */
    public static synchronized boolean togglePlainChat() {
        plainChat = !plainChat;
        try {
            List<String> lines = Files.exists(CONFIG_FILE)
                    ? Files.readAllLines(CONFIG_FILE, StandardCharsets.UTF_8)
                    : List.of(template().split("\n", -1));
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
            MyMeido.LOGGER.error("[mymeido] failed to write back chat_plain (still in effect this session): {}",
                    e.toString());
        }
        return plainChat;
    }
}
