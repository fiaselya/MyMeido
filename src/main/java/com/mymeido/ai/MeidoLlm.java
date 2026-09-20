package com.mymeido.ai;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import com.mymeido.MyMeido;

import net.minecraft.server.MinecraftServer;

/**
 * OpenAI 兼容的 chat/completions 客户端（三期 A3；2026-09-21 加流式与自定义头）。
 *
 * <p>本地 llama-server 和远程 API 都是同一套协议 → 路由层（{@link MeidoAi}）
 * 对两者用同一个方法，不用写两遍（A3 硬要求 2）。
 *
 * <p>★ 全程异步（{@code sendAsync}）：网络等待发生在 HTTP 线程池上，
 * <b>主线程一帧都不阻塞</b>；回调统一经 {@code server.execute()} 弹回主线程，
 * 满足「API 失败/超时都不能让游戏卡住」的要求。零依赖 ——
 * HTTP 用 JDK 自带 {@link HttpClient}，JSON 用 MC 自带的 Gson。
 *
 * <h2>★ 2026-09-21：为了「订阅制 API」补的两块</h2>
 *
 * <p>起因：原先的实现只能连<b>标准 OpenAI 端点</b>（Bearer 鉴权 + 非流式），
 * 而订阅制 / 客户端登录态复用的端点常常<b>不吃这两条</b>。实测（本机真跑）：
 * <pre>
 * stream=false → HTTP 400，正文说明「不支持非流式」
 * stream=true  → HTTP 200 Content-Type: text/event-stream，data: {...} 分片
 * </pre>
 * 所以补了：
 * <ol>
 *   <li><b>{@code api_stream=true}</b> —— 走 SSE：逐行读 {@code data:}、把
 *       {@code choices[0].delta.content} 拼起来。默认 <b>false</b>（老配置行为一字不变）；</li>
 *   <li><b>{@code api_extra_headers}</b> —— 自定义请求头（{@code 名:值; 名:值}），
 *       解决「必须伪装 User-Agent / X-Product」「鉴权头不叫 Authorization」这类端点。</li>
 * </ol>
 * 另外顺手修了一个高频踩坑：<b>base_url 已经带 {@code /chat/completions} 时不再重复拼</b>
 * （否则变成 {@code .../chat/completions/chat/completions} → 404，而报错看上去像「服务没起」）。
 */
public final class MeidoLlm {

    /** 一条消息。role 取 "system" / "user" / "assistant"。 */
    public record Msg(String role, String content) {
    }

    /**
     * 一次请求的全部参数。★ 收成一个 record 是为了<b>只有一个来源</b> ——
     * 原先 3 处调用点各自手抄 6 个参数，加一个开关就要改三遍（必然漂移）。
     *
     * @param extraHeaders 形如 {@code [[名, 值], ...]}，按顺序 setHeader
     */
    public record Options(String baseUrl, String model, String apiKey, int timeoutMs,
            boolean stream, List<String[]> extraHeaders) {
    }

    private static final Gson GSON = new Gson();

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    /**
     * 读 SSE 流用的线程池（守护线程，别拽住 JVM 退出）。
     * 用 4 个线程：「对话」和「人设更新」本来就会并发（设计如此），别让它们互相排队。
     */
    private static final ExecutorService STREAM_POOL = Executors.newFixedThreadPool(4, runnable -> {
        Thread thread = new Thread(runnable, "mymeido-llm-stream");
        thread.setDaemon(true);
        return thread;
    });

    private MeidoLlm() {
    }

    /**
     * 发一次对话请求。
     *
     * @param onSuccess 主线程回调，参数是模型的回复全文
     * @param onError   主线程回调，参数是人类可读的失败原因（路由层拿去做回落与提示）
     */
    public static void chat(MinecraftServer server, Options options, List<Msg> messages,
            Consumer<String> onSuccess, Consumer<String> onError) {
        JsonObject body = new JsonObject();
        body.addProperty("model", options.model());
        // 女仆台词一两句就够 —— 限死长度，既快又不至于让她在聊天栏里刷屏。
        body.addProperty("max_tokens", 120);
        // 2026-09-20 绯色反馈「智力太低」：0.9 对 4B 小模型太高，容易胡言；
        // 降到 0.7 + top_p 0.9，回答更贴题。（都是 OpenAI 标准字段，远程 API 也吃。）
        body.addProperty("temperature", 0.7);
        body.addProperty("top_p", 0.9);
        if (options.stream()) {
            // ★ 有些订阅端点不写 stream 就 400（见类注释的实测）。
            body.addProperty("stream", true);
        }
        JsonArray array = new JsonArray();
        for (Msg message : messages) {
            JsonObject item = new JsonObject();
            item.addProperty("role", message.role());
            item.addProperty("content", message.content());
            array.add(item);
        }
        body.add("messages", array);

        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(endpoint(options.baseUrl())))
                .timeout(Duration.ofMillis(Math.max(1000, options.timeoutMs())));
        // 自定义头先上，核心头随后 setHeader 覆盖 —— 规则是「mod 自己那几行永远说了算」。
        applyExtraHeaders(builder, options.extraHeaders());
        builder.setHeader("Content-Type", "application/json");
        if (options.apiKey() != null && !options.apiKey().isBlank()) {
            builder.setHeader("Authorization", "Bearer " + options.apiKey().trim());
        }
        builder.POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(body), StandardCharsets.UTF_8));

        if (options.stream()) {
            receiveStream(server, builder.build(), options.model(), onSuccess, onError);
            return;
        }
        receiveWhole(server, builder.build(), options.model(), onSuccess, onError);
    }

    // ------------------------------------------------------------------
    // 非流式（默认，行为与改前完全一致）
    // ------------------------------------------------------------------

    private static void receiveWhole(MinecraftServer server, HttpRequest request, String model,
            Consumer<String> onSuccess, Consumer<String> onError) {
        CLIENT.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenAccept(response -> {
                    if (response.statusCode() / 100 != 2) {
                        server.execute(() -> onError.accept(httpError(response.statusCode(), response.body())));
                        return;
                    }
                    Whole whole = extractWhole(response.body());
                    if (whole.content() == null || whole.content().isBlank()) {
                        server.execute(() -> onError.accept(whole.reasoningSeen()
                                ? reasoningOnly(model) : "回复是空的（响应不是 OpenAI 格式？）"));
                        return;
                    }
                    server.execute(() -> onSuccess.accept(cleanReply(whole.content())));
                })
                .exceptionally(throwable -> {
                    fail(server, onError, throwable);
                    return null;
                });
    }

    // ------------------------------------------------------------------
    // 流式（SSE）：api_stream=true 时走这条
    // ------------------------------------------------------------------

    private static void receiveStream(MinecraftServer server, HttpRequest request, String model,
            Consumer<String> onSuccess, Consumer<String> onError) {
        CLIENT.sendAsync(request, HttpResponse.BodyHandlers.ofLines())
                // ★ 用 thenAcceptAsync 指定线程池：读流是阻塞的，绝不能在
                //   HttpClient 自己的选择器线程上干（也绝不能碰服务端主线程）。
                .thenAcceptAsync(response -> {
                    if (response.statusCode() / 100 != 2) {
                        String detail = response.body().limit(16).collect(Collectors.joining(" "));
                        server.execute(() -> onError.accept(httpError(response.statusCode(), detail)));
                        return;
                    }
                    Fragments fragments = new Fragments();
                    try (Stream<String> lines = response.body()) {
                        lines.forEach(line -> appendDelta(line, fragments));
                    } catch (Exception e) {
                        // 读到一半断了：已经收到内容就当成功（流式端点收尾断流很常见），
                        // 一个字节都没收到才算失败。
                        if (fragments.content.isEmpty()) {
                            server.execute(() -> onError.accept(
                                    e.getClass().getSimpleName() + ": " + e.getMessage()));
                            return;
                        }
                        MyMeido.LOGGER.warn("[mymeido][ai] 流式读取中断，用已收到的内容收尾：{}", e.toString());
                    }
                    String content = fragments.content.toString();
                    if (content.isBlank()) {
                        server.execute(() -> onError.accept(fragments.reasoning.length() > 0
                                ? reasoningOnly(model) : "流式回复是空的（响应不是 OpenAI SSE 格式？）"));
                        return;
                    }
                    server.execute(() -> onSuccess.accept(cleanReply(content)));
                }, STREAM_POOL)
                .exceptionally(throwable -> {
                    fail(server, onError, throwable);
                    return null;
                });
    }

    /** 流式累计：正式台词 + 思考段（思考段只用于出错时说清原因，绝不进台词）。 */
    private static final class Fragments {
        private final StringBuilder content = new StringBuilder();
        private final StringBuilder reasoning = new StringBuilder();
    }

    /**
     * 解析一行 SSE，把增量文本追加进去。
     *
     * <p>只把 {@code choices[0].delta.content} 当台词：
     * <b>刻意不碰 {@code reasoning_content}</b> —— 推理模型的思考段就在那个字段里，
     * 取错了她会当场把推理过程念出来（当初 llama.cpp 的 {@code --reasoning-budget 0}
     * 拦不住 Qwen3 就是这个坑）。
     *
     * <p>但 2026-09-21 实测发现一个新情况：<b>有的模型（推理型，如 {@code hy3}）
     * 会把整段话都放进 {@code reasoning_content}，{@code content} 从头到尾是空的</b> ——
     * 这时如果只回一句「回复是空的」，玩家根本不知道为什么。
     * 所以思考段也收着，但<b>只用于出错时的诊断</b>（见 {@link #reasoningOnly}）。
     */
    private static void appendDelta(String line, Fragments out) {
        if (line == null) {
            return;
        }
        String trimmed = line.trim();
        if (!trimmed.startsWith("data:")) {
            return;   // 空行、注释行（: keep-alive）、event: 行都跳过
        }
        String payload = trimmed.substring("data:".length()).trim();
        if (payload.isEmpty() || "[DONE]".equals(payload)) {
            return;
        }
        try {
            JsonObject root = JsonParser.parseString(payload).getAsJsonObject();
            JsonArray choices = root.getAsJsonArray("choices");
            if (choices == null || choices.isEmpty()) {
                return;
            }
            JsonObject delta = choices.get(0).getAsJsonObject().getAsJsonObject("delta");
            if (delta == null) {
                return;
            }
            appendIfPresent(delta.get("content"), out.content);
            appendIfPresent(delta.get("reasoning_content"), out.reasoning);
        } catch (Exception ignored) {
            // 单个分片坏掉不影响整体（有的服务商会在流里插非 JSON 的心跳）
        }
    }

    private static void appendIfPresent(JsonElement piece, StringBuilder target) {
        if (piece != null && !piece.isJsonNull()) {
            target.append(piece.getAsString());
        }
    }

    // ------------------------------------------------------------------
    // 小工具
    // ------------------------------------------------------------------

    /** 非流式响应的提取结果：台词 + 有没有看到思考段（后者只用于诊断）。 */
    private record Whole(String content, boolean reasoningSeen) {
    }

    /** 取 choices[0].message.content（顺带看一眼有没有 reasoning_content）；结构对不上返回空。 */
    private static Whole extractWhole(String json) {
        try {
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            JsonArray choices = root.getAsJsonArray("choices");
            if (choices == null || choices.isEmpty()) {
                return new Whole(null, false);
            }
            JsonObject message = choices.get(0).getAsJsonObject().getAsJsonObject("message");
            if (message == null) {
                return new Whole(null, false);
            }
            JsonElement content = message.get("content");
            JsonElement reasoning = message.get("reasoning_content");
            String text = content == null || content.isJsonNull() ? null : content.getAsString();
            boolean reasoningSeen = reasoning != null && !reasoning.isJsonNull()
                    && !reasoning.getAsString().isBlank();
            return new Whole(text, reasoningSeen);
        } catch (Exception e) {
            return new Whole(null, false);
        }
    }

    /**
     * 「只回了思考段」的说明。
     *
     * <p>实测（2026-09-21）：有的推理型模型（如 {@code hy3}）会把整段话都塞进
     * {@code reasoning_content}、{@code content} 从头到尾为空 —— 换
     * {@code deepseek-v4.1-flash} 就正常。如果只说「回复是空的」，
     * 玩家会去怀疑地址/密钥，根本想不到是模型选错了。
     */
    private static String reasoningOnly(String model) {
        MyMeido.LOGGER.warn("[mymeido][ai] 模型「{}」只回思考段（reasoning_content），没有正式台词；"
                + "这是推理型模型的行为，换一个非推理模型即可", model);
        return "她只回了思考段没给台词 —— 模型「" + model + "」是推理型的，"
                + "换一个非推理的（例：deepseek-v4.1-flash / glm-5.3-flash）";
    }

    /**
     * 把 base_url 拼成真正的端点。
     * ★ 已经以 {@code /chat/completions} 结尾就<b>原样用</b> ——
     * 玩家十有八九会把全路径粘进来，再拼一次就是 404（而且看起来像「服务没起」）。
     */
    static String endpoint(String baseUrl) {
        String base = baseUrl == null ? "" : baseUrl.trim();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        if (base.endsWith("/chat/completions")) {
            return base;
        }
        return base + "/chat/completions";
    }

    /**
     * 自定义请求头的解析（{@code 名:值; 名:值}）。
     * 值里可以带冒号（只按<b>第一个</b>冒号切）；空项跳过；名字非法只影响那一条。
     */
    static List<String[]> parseHeaders(String raw) {
        List<String[]> out = new java.util.ArrayList<>();
        if (raw == null || raw.isBlank()) {
            return out;
        }
        for (String chunk : raw.split(";")) {
            String item = chunk.trim();
            if (item.isEmpty()) {
                continue;
            }
            int colon = item.indexOf(':');
            if (colon <= 0) {
                MyMeido.LOGGER.warn("[mymeido][ai] api_extra_headers 里这一项没有冒号，已跳过：{}", item);
                continue;
            }
            out.add(new String[] { item.substring(0, colon).trim(), item.substring(colon + 1).trim() });
        }
        return out;
    }

    private static void applyExtraHeaders(HttpRequest.Builder builder, List<String[]> headers) {
        if (headers == null) {
            return;
        }
        for (String[] header : headers) {
            try {
                builder.setHeader(header[0], header[1]);
            } catch (IllegalArgumentException e) {
                // JDK 禁止设置 connection / content-length / host 等这几个；只跳过这一条。
                MyMeido.LOGGER.warn("[mymeido][ai] 自定义头「{}」被 JDK 拒绝（{}），已跳过",
                        header[0], e.getMessage());
            }
        }
    }

    private static String httpError(int status, String body) {
        String detail = body == null ? "" : body.substring(0, Math.min(200, body.length()));
        return "HTTP " + status + " " + detail;
    }

    private static void fail(MinecraftServer server, Consumer<String> onError, Throwable throwable) {
        Throwable cause = throwable.getCause() != null ? throwable.getCause() : throwable;
        String message = cause.getClass().getSimpleName() + ": " + cause.getMessage();
        MyMeido.LOGGER.warn("[mymeido][ai] 请求异常：{}", message);
        server.execute(() -> onError.accept(message));
    }

    /**
     * 小模型的坏习惯兜底：剥掉 markdown 代码围栏和首尾引号。
     * ★ 还要剥 {@code <think>…</think>} —— 万一哪个模型/模板把思考段漏进 content
     *   （b11062 的 --reasoning-budget 0 就拦不住），她会在聊天栏里念推理过程。
     */
    private static String cleanReply(String text) {
        String out = text.trim();
        int thinkStart = out.indexOf("<think>");
        if (thinkStart >= 0) {
            int thinkEnd = out.indexOf("</think>", thinkStart);
            out = thinkEnd >= 0
                    ? out.substring(0, thinkStart) + out.substring(thinkEnd + "</think>".length())
                    // 只有开头没闭合：整段当思考扔掉
                    : out.substring(0, thinkStart);
        }
        if (out.startsWith("```")) {
            int firstBreak = out.indexOf('\n');
            out = firstBreak >= 0 ? out.substring(firstBreak + 1) : out;
            int fence = out.lastIndexOf("```");
            if (fence >= 0) {
                out = out.substring(0, fence);
            }
        }
        return out.trim();
    }
}
