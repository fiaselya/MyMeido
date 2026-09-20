package com.mymeido.ai;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Deque;

import com.mymeido.MyMeido;
import com.mymeido.entity.MeidoEntity;

import net.fabricmc.loader.api.FabricLoader;

/**
 * 记忆的<b>可读副本</b>：{@code config/mymeido/memory/<角色名>-<uuid前8>.md}。
 *
 * <p>为什么要有这个：记忆真正的家是实体 NBT（{@code MeidoHistory} /
 * {@code MeidoAiSummary}）—— 那玩意儿在存档二进制里，玩家看不见也改不了。
 * 每次对话与压缩之后，把「记忆要点 + 最近对话」<b>镜像</b>一份成 Markdown，
 * 玩家能打开看、能备份、能确认她到底记住了什么。
 *
 * <p>★ 方向是单向的：<b>只能看，不能改</b>（改了不会影响游戏里的她，下次刷新会被覆盖）。
 * 想动手就改人设卡（personas/），那个是真读的。
 *
 * <p>权限与体积都不成问题：一只她最多 12 条近期对话 + 一段 150 字要点。
 */
public final class MeidoMemoryExport {

    private static final Path DIR =
            FabricLoader.getInstance().getConfigDir().resolve("mymeido").resolve("memory");

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private MeidoMemoryExport() {
    }

    /** 导出目录（给指令提示用）。 */
    public static Path dir() {
        return DIR;
    }

    /**
     * 刷新某只她的记忆副本。对话收尾、压缩完成时调用 —— 出错只记日志，绝不影响游戏。
     *
     * @return 写出的文件路径；失败 null
     */
    public static Path write(MeidoEntity meido) {
        try {
            Files.createDirectories(DIR);
            Path file = DIR.resolve(fileName(meido));
            Files.writeString(file, render(meido), StandardCharsets.UTF_8);
            return file;
        } catch (IOException e) {
            MyMeido.LOGGER.warn("[mymeido] 导出记忆副本失败（不影响游戏）：{}", e.toString());
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
        sb.append("# ").append(meido.characterName()).append(" 的记忆\n\n")
                .append("> 这是 mymeido 自动导出的**只读副本**（对话结束后自动刷新）。\n")
                .append("> 游戏里的记忆以存档为准 —— 改这个文件不会影响她；\n")
                .append("> 想改她的性格请编辑 `config/mymeido/personas/")
                .append(meido.getSkin().getId()).append(".txt`。\n\n")
                .append("- 更新时间：").append(LocalDateTime.now().format(STAMP)).append('\n')
                .append("- 皮肤：").append(meido.getSkin().getId()).append('\n')
                .append("- 当前状态：").append(meido.getMission().displayName()).append('\n')
                .append("- 好感度：").append(meido.favorHint()).append('\n');

        String summary = meido.getAiSummary();
        sb.append("\n## 记忆要点（API 压缩出来的长期记忆）\n\n");
        sb.append(summary.isEmpty() ? "（还没有 —— 聊满一轮她会自动压缩）\n" : summary + "\n");

        sb.append("\n## 最近对话（最多 12 条）\n\n");
        Deque<String[]> history = meido.aiHistory();
        synchronized (history) {
            if (history.isEmpty()) {
                sb.append("（还没有对话）\n");
            } else {
                for (String[] entry : history) {
                    sb.append("**").append("user".equals(entry[0]) ? "你" : meido.characterName())
                            .append("**：").append(entry[1]).append("\n\n");
                }
            }
        }
        return sb.toString();
    }

    /** 文件名安全化（角色名来自玩家改名，可能带 / 之类）。 */
    private static String sanitize(String name) {
        String out = name == null ? "meido" : name.strip();
        out = out.replaceAll("[\\\\/:*?\"<>|\\s]", "_");
        return out.isEmpty() ? "meido" : out;
    }
}
