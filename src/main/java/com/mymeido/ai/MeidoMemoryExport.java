package com.mymeido.ai;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Deque;

import com.mymeido.MeidoLocale;
import com.mymeido.MyMeido;
import com.mymeido.entity.MeidoEntity;

import net.fabricmc.loader.api.FabricLoader;

/**
 * A <b>readable copy</b> of memory: {@code config/mymeido/memory/<character>-<uuid8>.md}.
 *
 * <p>Why this exists: memory's real home is entity NBT ({@code MeidoHistory} /
 * {@code MeidoAiSummary}) -- that lives in the save binary, invisible and uneditable by the player.
 * After each conversation and each compression, we <b>mirror</b> "memory highlights + recent
 * conversations" into Markdown so the player can open it, back it up, and confirm what she actually
 * remembers.
 *
 * <p>★ Direction is one-way: <b>view only, cannot edit</b> (editing won't affect the in-game her;
 * the next refresh overwrites it). To actually change her, edit the persona card (personas/),
 * which is really read.
 *
 * <p>Permissions and size are non-issues: one she has at most 12 recent conversations + one
 * 150-char highlight.
 */
public final class MeidoMemoryExport {

    private static final Path DIR =
            FabricLoader.getInstance().getConfigDir().resolve("mymeido").resolve("memory");

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private MeidoMemoryExport() {
    }

    /** Export directory (for command hints). */
    public static Path dir() {
        return DIR;
    }

    /**
     * Refresh one she's memory copy. Called after a conversation ends and when compression completes
     * -- on error only logs, never affects the game.
     *
     * @return the written file path; null on failure
     */
    public static Path write(MeidoEntity meido) {
        try {
            Files.createDirectories(DIR);
            Path file = DIR.resolve(fileName(meido));
            Files.writeString(file, render(meido), StandardCharsets.UTF_8);
            return file;
        } catch (IOException e) {
            MyMeido.LOGGER.warn("[mymeido] failed to export memory copy (game unaffected): {}", e.toString());
            return null;
        }
    }

    private static String fileName(MeidoEntity meido) {
        String name = sanitize(meido.characterName());
        String uuid = meido.getUuid().toString().substring(0, 8);
        return name + "-" + uuid + ".md";
    }

    private static String render(MeidoEntity meido) {
        StringBuilder sb = new StringBuilder();
        sb.append("# ").append(meido.characterName())
                .append(MeidoLocale.pick(" 的记忆\n\n", "'s memory\n\n"))
                .append(MeidoLocale.pick(
                        "> 这是 mymeido 自动导出的**只读副本**（对话结束后自动刷新）。\n",
                        "> This is the mymeido auto-exported **read-only copy** (refreshed after each conversation).\n"))
                .append(MeidoLocale.pick(
                        "> 游戏里的记忆以存档为准 —— 改这个文件不会影响她；\n",
                        "> In-game memory is the source of truth -- editing this file won't affect her;\n"))
                .append(MeidoLocale.pick(
                        "> 想改她的性格请编辑 `config/mymeido/personas/",
                        "> To change her personality, edit `config/mymeido/personas/"))
                .append(meido.getSkin().getId())
                .append(MeidoLocale.pick(".txt`。\n\n", ".txt`.\n\n"))
                .append(MeidoLocale.pick("- 更新时间：", "- Updated: "))
                .append(LocalDateTime.now().format(STAMP)).append('\n')
                .append(MeidoLocale.pick("- 皮肤：", "- Skin: "))
                .append(meido.getSkin().getId()).append('\n')
                .append(MeidoLocale.pick("- 当前状态：", "- Current state: "))
                .append(meido.getMission().displayName()).append('\n')
                .append(MeidoLocale.pick("- 好感度：", "- Affection: "))
                .append(meido.favorHint()).append('\n');

        String summary = meido.getAiSummary();
        sb.append(MeidoLocale.pick("\n## 记忆要点（API 压缩出来的长期记忆）\n\n",
                "\n## Memory highlights (long-term memory compressed by the API)\n\n"));
        sb.append(summary.isEmpty()
                ? MeidoLocale.pick("（还没有 —— 聊满一轮她会自动压缩）\n",
                        "(none yet -- she auto-compresses after a full round)\n")
                : summary + "\n");

        sb.append(MeidoLocale.pick("\n## 最近对话（最多 12 条）\n\n",
                "\n## Recent conversations (up to 12)\n\n"));
        Deque<String[]> history = meido.aiHistory();
        synchronized (history) {
            if (history.isEmpty()) {
                sb.append(MeidoLocale.pick("（还没有对话）\n", "(no conversations yet)\n"));
            } else {
                for (String[] entry : history) {
                    sb.append("**").append("user".equals(entry[0])
                                    ? MeidoLocale.pick("你", "You")
                                    : meido.characterName())
                            .append(MeidoLocale.pick("**：", "**: "))
                            .append(entry[1]).append("\n\n");
                }
            }
        }
        return sb.toString();
    }

    /** File-name safety (character name comes from player rename, may contain / etc.). */
    private static String sanitize(String name) {
        String out = name == null ? "meido" : name.strip();
        out = out.replaceAll("[\\\\/:*?\"<>|\\s]", "_");
        return out.isEmpty() ? "meido" : out;
    }
}
