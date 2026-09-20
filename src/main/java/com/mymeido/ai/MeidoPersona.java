package com.mymeido.ai;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.mymeido.MeidoLocale;
import com.mymeido.MyMeido;

import net.fabricmc.loader.api.FabricLoader;

/**
 * Persona card: {@code config/mymeido/personas/<skinId>.txt}, free-form plain text --
 * personality, catchphrases, backstory, how to address the player, anything goes.
 *
 * <p>Stored <b>per skin</b> (same skin shares one card); a template is auto-generated on first load;
 * after editing, {@code /mymeido aireload} applies it.
 *
 * <h2>What the file looks like (2026-09-20 added "API auto-extract")</h2>
 * <pre>
 * the part you write by hand (write anything, the program never touches it)
 *
 * # === auto-extract start (updated by /mymeido persona extract) ===
 * personality / catchphrases / address the API summarized from your conversations ...
 * # === auto-extract end ===
 * </pre>
 *
 * <p>★ Lines starting with {@code #} are <b>comments</b> and are NOT fed to the prompt -- so the
 * example in the template and the auto-block markers are never treated as her settings (an early
 * version did feed the example in, a real bug, now fixed). Too lazy to write by hand? Just run
 * {@code /mymeido persona extract} once and let the API read your conversations and write it itself.
 */
public final class MeidoPersona {

    private static final Path DIR =
            FabricLoader.getInstance().getConfigDir().resolve("mymeido").resolve("personas");

    /**
     * Current auto-block boundary markers. Content between them is overwritten by API extraction;
     * the outside part is the player's. These are what we <b>write</b> on every auto-update.
     */
    private static final String AUTO_BEGIN =
            "# === auto-extract start (updated by /mymeido persona extract; do not write inside here) ===";
    private static final String AUTO_END = "# === auto-extract end ===";

    /**
     * Legacy (old Chinese) auto-block markers. Used only when <i>reading</i> an existing persona
     * file: earlier releases wrote these, so recognizing them stops an upgraded player's old
     * auto-block from being mistaken for hand-written content and duplicated under a brand-new
     * English block. New writes always use {@link #AUTO_BEGIN}/{@link #AUTO_END}.
     */
    private static final String AUTO_BEGIN_LEGACY =
            "# === 自动提取开始（/mymeido persona extract 更新，别在这里面写东西）===";
    private static final String AUTO_END_LEGACY = "# === 自动提取结束 ===";

    /** Begin markers we accept when reading: current + legacy (order is irrelevant). */
    private static final String[] BEGIN_MARKERS = {AUTO_BEGIN, AUTO_BEGIN_LEGACY};

    /** Index of the earliest occurrence of any of {@code markers} in {@code text}, or -1. */
    private static int indexOfAny(String text, String... markers) {
        int idx = -1;
        for (String m : markers) {
            int i = text.indexOf(m);
            if (i >= 0 && (idx < 0 || i < idx)) {
                idx = i;
            }
        }
        return idx;
    }

    /** skin id -> persona text (comments stripped, empty string = nothing written).
     *  Invalidated and re-read wholesale on aireload. */
    private static final Map<String, String> CACHE = new ConcurrentHashMap<>();

    private MeidoPersona() {
    }

    /** Called on start / aireload: backfill missing templates + clear cache. */
    public static synchronized void loadAll(List<String> skinIds) {
        try {
            Files.createDirectories(DIR);
            for (String skinId : skinIds) {
                Path file = DIR.resolve(skinId + ".txt");
                if (!Files.exists(file)) {
                    Files.writeString(file, template(skinId), StandardCharsets.UTF_8);
                } else if (isEffectivelyEmpty(Files.readString(file, StandardCharsets.UTF_8))) {
                    // Old-version template (example text uncommented) -> migrate to new template,
                    // otherwise that example text would be fed to the model as real persona.
                    Files.writeString(file, template(skinId), StandardCharsets.UTF_8);
                }
            }
        } catch (IOException e) {
            MyMeido.LOGGER.error("[mymeido] persona card directory init failed (all personas fall back to default): {}",
                    e.toString());
        }
        CACHE.clear();
    }

    /** Get a persona card (comments and auto-block markers stripped); returns empty string if nothing written. */
    public static String personaFor(String skinId) {
        return CACHE.computeIfAbsent(skinId, id -> {
            try {
                Path file = DIR.resolve(id + ".txt");
                if (Files.exists(file)) {
                    return stripComments(Files.readString(file, StandardCharsets.UTF_8));
                }
            } catch (IOException e) {
                MyMeido.LOGGER.warn("[mymeido] failed to read persona card {}: {}", id, e.toString());
            }
            return "";
        });
    }

    /** Persona card file path (for the player to look at). */
    public static Path fileFor(String skinId) {
        return DIR.resolve(skinId + ".txt");
    }

    /**
     * Write the API-extracted settings into the auto block (overwrites the previous auto content;
     * the player's hand-written part is preserved verbatim).
     *
     * @return the written file path; null on failure
     */
    public static synchronized Path writeAuto(String skinId, String text) {
        Path file = fileFor(skinId);
        try {
            Files.createDirectories(DIR);
            String existing = Files.exists(file)
                    ? Files.readString(file, StandardCharsets.UTF_8)
                    : template(skinId);
            String head = existing;
            int begin = indexOfAny(existing, BEGIN_MARKERS);
            if (begin >= 0) {
                head = existing.substring(0, begin);
            }
            String out = head.stripTrailing()
                    + "\n\n" + AUTO_BEGIN + "\n"
                    + text.strip() + "\n"
                    + AUTO_END + "\n";
            Files.writeString(file, out, StandardCharsets.UTF_8);
            CACHE.remove(skinId);   // next conversation uses the new one immediately
            MyMeido.LOGGER.info("[mymeido] persona card updated (auto-extract): {}", file);
            return file;
        } catch (IOException e) {
            MyMeido.LOGGER.error("[mymeido] failed to write persona card: {}", e.toString());
            return null;
        }
    }

    /** Strip comment lines (# at start) and the uncommented example block from old templates,
     *  leaving only real persona text. */
    private static String stripComments(String text) {
        StringBuilder out = new StringBuilder();
        // ★ The old template's example was [two lines]: starts with "（示例" and ends with "）".
        //   Filtering only by line start would miss the second line (it'd be fed as real persona),
        //   so skip the whole block.
        boolean inExample = false;
        for (String line : text.split("\n", -1)) {
            String trimmed = line.strip();
            if (inExample) {
                if (trimmed.endsWith("）")) {
                    inExample = false;
                }
                continue;
            }
            if (trimmed.startsWith("（示例")) {
                inExample = !trimmed.endsWith("）");
                continue;
            }
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            out.append(trimmed).append('\n');
        }
        return out.toString().strip();
    }

    /** Old-template judgment: nothing left after stripping comments and examples = player wrote nothing. */
    private static boolean isEffectivelyEmpty(String text) {
        if (indexOfAny(text, BEGIN_MARKERS) >= 0) {
            // Has auto-extracted content (current or legacy marker), so not empty -- don't wipe their persona.
            return false;
        }
        return stripComments(text).isEmpty();
    }

    /** Template: the example is fully commented out (★ comments are not fed to the prompt);
     *  the player writes their own following it. */
    private static String template(String skinId) {
        return MeidoLocale.pick(
                """
                # 这是「%s」的人设卡 —— 想让她是什么性格就写什么，纯文本随便写。
                # 以 # 开头的行是注释，不会喂给模型，# 后面写什么都没关系。
                # 写好后敲 /mymeido aireload 生效；也可以让 API 自己读你们的对话来写：
                #     /mymeido persona extract
                # （提取出来的设定写在文件末尾的「自动提取」区块里，你手写的内容不会被动）
                #
                # 想省事就照这些方向写（这几行都是注释，不写就当没有）：
                #   性格：元气满满但有点天然呆
                #   喜好：特别喜欢甜食
                #   称呼：管玩家叫「主人大人」
                #   雷区：说到星星的话题会特别兴奋；生气时会嘟嘴不说话
                """.formatted(skinId),
                """
                # This is the persona card for '%s' -- write whatever personality you want, plain text, anything goes.
                # Lines starting with # are comments and are NOT fed to the model; write anything after #.
                # After editing, run /mymeido aireload to apply; or let the API read your conversations and write it:
                #     /mymeido persona extract
                # (Extracted settings are written into the 'auto-extract' block at the end; your hand-written content is untouched)
                #
                # If you want it easy, follow these hints (all comments; blank = none):
                #   personality: energetic but a bit airheaded
                #   likes: loves sweets
                #   address: calls the player "Master"
                #   taboo: gets especially excited talking about stars; pouts and goes silent when angry
                """.formatted(skinId));
    }
}
