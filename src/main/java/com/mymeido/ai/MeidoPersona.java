package com.mymeido.ai;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.mymeido.MyMeido;

import net.fabricmc.loader.api.FabricLoader;

/**
 * 人设卡：{@code config/mymeido/personas/<skinId>.txt}，纯文本自由写 ——
 * 性格、口癖、背景、怎么称呼玩家，都可以。
 *
 * <p>按<b>皮肤</b>存（同皮肤共用一张卡）；首次加载自动生成模板；
 * 改完 {@code /mymeido aireload} 生效。
 *
 * <h2>文件长什么样（2026-09-20 支持「API 自动提取」）</h2>
 * <pre>
 * 你手写的部分（随便写，不会被程序动）
 *
 * # === 自动提取开始（/mymeido persona extract 更新）===
 * API 从你们的对话里总结出来的性格 / 口癖 / 称呼……
 * # === 自动提取结束 ===
 * </pre>
 *
 * <p>★ 以 {@code #} 开头的行是<b>注释</b>，不进 prompt —— 所以模板里的示例
 * 和自动区块的标记都不会被当成她的设定喂给模型（早期版本会把示例一起喂进去，
 * 是个真 bug，已修）。懒得手写也行：敲一次
 * {@code /mymeido persona extract} 让 API 自己读你们的对话去写。
 */
public final class MeidoPersona {

    private static final Path DIR =
            FabricLoader.getInstance().getConfigDir().resolve("mymeido").resolve("personas");

    /** 自动区块的边界标记。中间内容由 API 提取覆盖，外面的部分玩家说了算。 */
    private static final String AUTO_BEGIN =
            "# === 自动提取开始（/mymeido persona extract 更新，别在这里面写东西）===";
    private static final String AUTO_END = "# === 自动提取结束 ===";

    /** 皮肤 id → 人设文本（注释已剔除，空串 = 没写）。aireload 时整体失效重读。 */
    private static final Map<String, String> CACHE = new ConcurrentHashMap<>();

    private MeidoPersona() {
    }

    /** 启动 / aireload 时调用：补齐缺失的模板 + 清缓存。 */
    public static synchronized void loadAll(List<String> skinIds) {
        try {
            Files.createDirectories(DIR);
            for (String skinId : skinIds) {
                Path file = DIR.resolve(skinId + ".txt");
                if (!Files.exists(file)) {
                    Files.writeString(file, template(skinId), StandardCharsets.UTF_8);
                } else if (isEffectivelyEmpty(Files.readString(file, StandardCharsets.UTF_8))) {
                    // 老版本生成的模板（示例文字没加注释）→ 迁移成新模板，
                    // 否则那段示例会被当成真人设喂给模型。
                    Files.writeString(file, template(skinId), StandardCharsets.UTF_8);
                }
            }
        } catch (IOException e) {
            MyMeido.LOGGER.error("[mymeido] 人设卡目录初始化失败（人设将全部走默认）：{}", e.toString());
        }
        CACHE.clear();
    }

    /** 拿某张人设卡（已剔除注释与自动区块标记）；没写内容返回空串。 */
    public static String personaFor(String skinId) {
        return CACHE.computeIfAbsent(skinId, id -> {
            try {
                Path file = DIR.resolve(id + ".txt");
                if (Files.exists(file)) {
                    return stripComments(Files.readString(file, StandardCharsets.UTF_8));
                }
            } catch (IOException e) {
                MyMeido.LOGGER.warn("[mymeido] 读人设卡 {} 失败：{}", id, e.toString());
            }
            return "";
        });
    }

    /** 人设卡文件路径（给玩家看的）。 */
    public static Path fileFor(String skinId) {
        return DIR.resolve(skinId + ".txt");
    }

    /**
     * 把 API 提取出来的设定写进自动区块（覆盖上一次的自动内容，玩家手写的部分原样保留）。
     *
     * @return 写入后的文件路径；失败返回 null
     */
    public static synchronized Path writeAuto(String skinId, String text) {
        Path file = fileFor(skinId);
        try {
            Files.createDirectories(DIR);
            String existing = Files.exists(file)
                    ? Files.readString(file, StandardCharsets.UTF_8)
                    : template(skinId);
            String head = existing;
            int begin = existing.indexOf(AUTO_BEGIN);
            if (begin >= 0) {
                head = existing.substring(0, begin);
            }
            String out = head.stripTrailing()
                    + "\n\n" + AUTO_BEGIN + "\n"
                    + text.strip() + "\n"
                    + AUTO_END + "\n";
            Files.writeString(file, out, StandardCharsets.UTF_8);
            CACHE.remove(skinId);   // 下一次对话立刻用上新的
            MyMeido.LOGGER.info("[mymeido] 人设卡已更新（自动提取）：{}", file);
            return file;
        } catch (IOException e) {
            MyMeido.LOGGER.error("[mymeido] 写人设卡失败：{}", e.toString());
            return null;
        }
    }

    /** 剔除注释行（# 开头）和早期模板里没加注释的示例块，只留真正的设定文字。 */
    private static String stripComments(String text) {
        StringBuilder out = new StringBuilder();
        // ★ 老模板的示例是【两行】：以「（示例」开头、以「）」结尾。
        //   只按行首过滤会漏掉第二行（会被当成真人设喂给模型），所以整块跳过。
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

    /** 老模板判定：去掉注释和示例后一个字符都不剩 = 玩家没写过东西。 */
    private static boolean isEffectivelyEmpty(String text) {
        if (text.contains(AUTO_BEGIN)) {
            // 有自动提取内容就不是空的，别把人家的人设冲掉
            return false;
        }
        return stripComments(text).isEmpty();
    }

    /** 模板：示例整段注释掉（★ 注释不进 prompt），玩家照着写自己的。 */
    private static String template(String skinId) {
        return """
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
                """.formatted(skinId);
    }
}
