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

import com.mymeido.MeidoLocale;
import com.mymeido.MyMeido;

import net.minecraft.server.MinecraftServer;

/**
 * OpenAI-compatible chat/completions client (phase 3 A3; streaming + custom headers added 2026-09-21).
 *
 * <p>Both local llama-server and remote API speak the same protocol, so the routing layer
 * ({@link MeidoAi}) calls both with one method, no need to write it twice (hard A3 requirement 2).
 *
 * <p>★ Fully async ({@code sendAsync}): network waits happen on the HTTP thread pool, the
 * <b>main thread is never blocked a single frame</b>; callbacks all bounce back to the main
 * thread via {@code server.execute()}, satisfying "API failure/timeout must not stall the game".
 * Zero dependencies -- HTTP uses JDK's own {@link HttpClient}, JSON uses MC's bundled Gson.
 *
 * <h2>★ 2026-09-21: two additions for "subscription APIs"</h2>
 *
 * <p>Background: the original implementation could only connect to <b>standard OpenAI endpoints</b>
 * (Bearer auth + non-streaming), while subscription / client-session-reuse endpoints often
 * <b>reject both</b>. Measured locally (actually run):
 * <pre>
 * stream=false -> HTTP 400, body says "non-streaming unsupported"
 * stream=true  -> HTTP 200 Content-Type: text/event-stream, data: {...} chunks
 * </pre>
 * So we added:
 * <ol>
 *   <li><b>{@code api_stream=true}</b> -- SSE: read {@code data:} line by line, stitch
 *       {@code choices[0].delta.content}. Default <b>false</b> (old config behavior unchanged);</li>
 *   <li><b>{@code api_extra_headers}</b> -- custom request headers ({@code name:value; name:value}),
 *       solving endpoints that "require a spoofed User-Agent / X-Product" or "auth header isn't Authorization".</li>
 * </ol>
 * Also fixed a frequent footgun: <b>when base_url already ends with {@code /chat/completions},
 * don't append again</b> (otherwise it becomes {@code .../chat/completions/chat/completions}
 * -> 404, and the error looks like "service not up").
 */
public final class MeidoLlm {

    /** One message. role is "system" / "user" / "assistant". */
    public record Msg(String role, String content) {
    }

    /**
     * All params for one request. ★ Bundled into one record for a <b>single source of truth</b> --
     * the old 3 call sites each hand-copied 6 params, so adding a switch meant editing three times
     * (inevitable drift).
     *
     * @param extraHeaders shape {@code [[name, value], ...]}, setHeader in order
     */
    public record Options(String baseUrl, String model, String apiKey, int timeoutMs,
            boolean stream, List<String[]> extraHeaders) {
    }

    private static final Gson GSON = new Gson();

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    /**
     * Thread pool for reading SSE streams (daemon threads, don't block JVM exit).
     * 4 threads: "conversation" and "persona update" are inherently concurrent (by design),
     * don't let them queue behind each other.
     */
    private static final ExecutorService STREAM_POOL = Executors.newFixedThreadPool(4, runnable -> {
        Thread thread = new Thread(runnable, "mymeido-llm-stream");
        thread.setDaemon(true);
        return thread;
    });

    private MeidoLlm() {
    }

    /**
     * Send one conversation request.
     *
     * @param onSuccess main-thread callback, arg is the model's full reply text
     * @param onError   main-thread callback, arg is a human-readable failure reason
     *                  (the routing layer uses it for fallback and hints)
     */
    public static void chat(MinecraftServer server, Options options, List<Msg> messages,
            Consumer<String> onSuccess, Consumer<String> onError) {
        JsonObject body = new JsonObject();
        body.addProperty("model", options.model());
        // A maid's line of one or two sentences is enough -- cap the length: fast and won't spam chat.
        body.addProperty("max_tokens", 120);
        // 2026-09-20 Hisui feedback "too dumb": 0.9 is too high for a 4B small model, tends to ramble;
        // dropped to 0.7 + top_p 0.9 for more on-topic answers. (Both are standard OpenAI fields, remote API eats them too.)
        body.addProperty("temperature", 0.7);
        body.addProperty("top_p", 0.9);
        if (options.stream()) {
            // ★ Some subscription endpoints 400 without stream (see class-note measurement).
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
        // Custom headers first, then core headers setHeader over them -- rule: "the mod's own lines always win".
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
    // Non-streaming (default, behavior identical to before the change)
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
                                ? reasoningOnly(model)
                                : MeidoLocale.pick("回复是空的（响应不是 OpenAI 格式？）",
                                        "The reply is empty (response not in OpenAI format?)")));
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
    // Streaming (SSE): used when api_stream=true
    // ------------------------------------------------------------------

    private static void receiveStream(MinecraftServer server, HttpRequest request, String model,
            Consumer<String> onSuccess, Consumer<String> onError) {
        CLIENT.sendAsync(request, HttpResponse.BodyHandlers.ofLines())
                // ★ Use thenAcceptAsync with the pool: reading the stream is blocking, never do it
                //   on HttpClient's own selector thread (and never touch the server main thread).
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
                        // Cut off mid-read: if we already got content, treat as success (stream endpoints
                        // often drop the tail); only a single byte received counts as failure.
                        if (fragments.content.isEmpty()) {
                            server.execute(() -> onError.accept(
                                    e.getClass().getSimpleName() + ": " + e.getMessage()));
                            return;
                        }
                        MyMeido.LOGGER.warn("[mymeido][ai] stream read interrupted, finishing with received content: {}",
                                e.toString());
                    }
                    String content = fragments.content.toString();
                    if (content.isBlank()) {
                        server.execute(() -> onError.accept(fragments.reasoning.length() > 0
                                ? reasoningOnly(model)
                                : MeidoLocale.pick("流式回复是空的（响应不是 OpenAI SSE 格式？）",
                                        "The streaming reply is empty (response not in OpenAI SSE format?)")));
                        return;
                    }
                    server.execute(() -> onSuccess.accept(cleanReply(content)));
                }, STREAM_POOL)
                .exceptionally(throwable -> {
                    fail(server, onError, throwable);
                    return null;
                });
    }

    /** Streaming accumulator: actual line + reasoning segment (reasoning only for error diagnosis, never into the line). */
    private static final class Fragments {
        private final StringBuilder content = new StringBuilder();
        private final StringBuilder reasoning = new StringBuilder();
    }

    /**
     * Parse one SSE line, append the incremental text.
     *
     * <p>Only {@code choices[0].delta.content} counts as the line:
     * <b>deliberately ignore {@code reasoning_content}</b> -- reasoning-model thinking lives in that
     * field, and grabbing the wrong one makes her read the reasoning out loud (the old llama.cpp
     * {@code --reasoning-budget 0} couldn't stop Qwen3 from doing exactly this).
     *
     * <p>But a 2026-09-21 measurement found a new case: <b>some models (reasoning-type, e.g. {@code hy3})
     * put the entire reply into {@code reasoning_content}, with {@code content} empty throughout</b> --
     * if we only said "reply is empty", the player wouldn't know why.
     * So we also collect the reasoning segment, but <b>only for error diagnosis</b> (see {@link #reasoningOnly}).
     */
    private static void appendDelta(String line, Fragments out) {
        if (line == null) {
            return;
        }
        String trimmed = line.trim();
        if (!trimmed.startsWith("data:")) {
            return;   // blank lines, comment lines (: keep-alive), event: lines all skipped
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
            // One bad chunk doesn't break the whole (some providers insert non-JSON heartbeats in the stream)
        }
    }

    private static void appendIfPresent(JsonElement piece, StringBuilder target) {
        if (piece != null && !piece.isJsonNull()) {
            target.append(piece.getAsString());
        }
    }

    // ------------------------------------------------------------------
    // Small helpers
    // ------------------------------------------------------------------

    /** Non-streaming extraction result: line + whether a reasoning segment was seen (latter only for diagnosis). */
    private record Whole(String content, boolean reasoningSeen) {
    }

    /** Take choices[0].message.content (and peek at reasoning_content); null if structure mismatches. */
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
     * Explanation for "only a reasoning segment returned".
     *
     * <p>Measured (2026-09-21): some reasoning models (e.g. {@code hy3}) put the entire reply into
     * {@code reasoning_content} with {@code content} empty throughout -- switching to
     * {@code deepseek-v4.1-flash} works normally. If we only said "reply is empty", the player would
     * suspect the address/key and never think the model was the wrong choice.
     */
    private static String reasoningOnly(String model) {
        MyMeido.LOGGER.warn("[mymeido][ai] model '{}' returned only a reasoning segment (reasoning_content) "
                + "with no actual line; this is reasoning-model behavior, switch to a non-reasoning model", model);
        return MeidoLocale.pick(
                "她只回了思考段没给台词 —— 模型「" + model + "」是推理型的，换一个非推理的（例：deepseek-v4.1-flash / glm-5.3-flash）",
                "She only returned a reasoning segment with no line -- model '" + model + "' is a reasoning model; "
                        + "switch to a non-reasoning one (e.g. deepseek-v4.1-flash / glm-5.3-flash)");
    }

    /**
     * Build the real endpoint from base_url.
     * ★ If it already ends with {@code /chat/completions}, use it <b>as-is</b> --
     * players almost always paste the full path, appending again yields 404 (and looks like "service down").
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
     * Custom request header parsing ({@code name:value; name:value}).
     * Value may contain colons (split on the <b>first</b> colon only); empty items skipped;
     * an illegal name only affects that one item.
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
                MyMeido.LOGGER.warn("[mymeido][ai] api_extra_headers item has no colon, skipped: {}", item);
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
                // JDK forbids setting connection / content-length / host etc.; only skip that one.
                MyMeido.LOGGER.warn("[mymeido][ai] custom header '{}' rejected by JDK ({}), skipped",
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
        MyMeido.LOGGER.warn("[mymeido][ai] request exception: {}", message);
        server.execute(() -> onError.accept(message));
    }

    /**
     * Small-model bad-habit guard: strip markdown code fences and surrounding quotes.
     * ★ Also strip {@code <think>...</think>} -- in case some model/template leaks the thinking segment
     * into content (b11062's --reasoning-budget 0 couldn't stop it), she'd read reasoning aloud in chat.
     */
    private static String cleanReply(String text) {
        String out = text.trim();
        int thinkStart = out.indexOf("<think>");
        if (thinkStart >= 0) {
            int thinkEnd = out.indexOf("</think>", thinkStart);
            out = thinkEnd >= 0
                    ? out.substring(0, thinkStart) + out.substring(thinkEnd + "</think>".length())
                    // Only an unclosed opening: drop the whole thing as thinking.
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
