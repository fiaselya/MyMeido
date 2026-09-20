package com.mymeido.mode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mymeido.MyMeido;

import net.fabricmc.loader.api.FabricLoader;

/**
 * 模式清单：从 {@code config/mymeido/modes.json} 读，玩家可自由增删改。
 *
 * <h2>为什么模式列表要放 JSON</h2>
 *
 * <p>「有哪些模式」是<b>内容</b>，不是<b>逻辑</b>。做成配置之后，
 * 想加一个「只在雨天去收衣服」的自定义模式，不用重新编译 mod。
 * 代码只需要认识 {@link MeidoModeType} 那几个<b>行为钩子</b>，
 * 条目怎么叫、说明怎么写、有几个条目，全归玩家。
 *
 * <h2>几条刻意的设计</h2>
 *
 * <ul>
 *   <li><b>文件不存在才写默认值。</b>存在但写坏了<b>绝不覆盖</b> ——
 *       玩家手打的几十行配置不该因为我解析失败就没了。解析失败时退回内存里的默认清单，
 *       并在日志里说清是第几个条目、哪个字段坏了。</li>
 *   <li><b>类型不认识 = 跳过 + 明确报错</b>，不是「静默回落到游走」。
 *       拼错一个字母就默默变成别的模式，是最难查的那种 bug。</li>
 *   <li><b>单条坏不掉整份。</b>一个条目解析失败只跳过它，其它照样能用。</li>
 * </ul>
 */
public final class MeidoModeRegistry {

    private static final Path CONFIG_FILE =
            FabricLoader.getInstance().getConfigDir().resolve("mymeido").resolve("modes.json");

    /**
     * 默认清单。顺序 = 界面里从上到下的顺序，「游走」排第一（它也是默认模式）。
     *
     * <p>★ 2026-09-20：删掉了「挖取方块」（{@code mine}），并把做不了行为的「守卫前方」挪到最后 ——
     * 界面上最后一个条目挂着「（行为待三期）」比夹在中间更不碍事。
     */
    private static final List<String[]> DEFAULTS = List.of(
            new String[]{"wander", "游走", "wander"},
            new String[]{"stand", "原地待命", "stand"},
            new String[]{"come", "到这里来", "come"},
            new String[]{"dump", "丢弃物品", "dump"},
            new String[]{"bind_home", "绑定家（床）", "bind_home"},
            new String[]{"fish", "钓鱼", "fish"},
            new String[]{"farm", "种植", "farm"},
            new String[]{"guard", "守卫前方", "guard"});

    /** 默认模式 id。玩家没派发过任何模式时就是它。 */
    public static final String DEFAULT_MODE_ID = "wander";

    private static volatile List<MeidoModeDef> modes = List.of();

    /**
     * ★ A4-2 的 {@code guard_exclude} 开关（2026-09-20 第三批落地）：
     * {@code modes.json} 顶层加 {@code "guard_attack_players": true} 后，
     * 守卫/游走的战斗判据在敌对怪之外<b>还包含玩家</b>（真无差别攻击）。
     * 默认 {@code false} —— 玩家和自家女仆不互殴（自家 NPC 用「不是 Monster」
     * 这条判据天然排除，不随本开关变化）。
     */
    private static volatile boolean guardAttackPlayers = false;

    private MeidoModeRegistry() {
    }

    /** 由主入口调用一次；也可以用 {@code /mymeido reload} 重来。 */
    public static synchronized void load() {
        if (!Files.isRegularFile(CONFIG_FILE)) {
            writeDefaults();
        }
        List<MeidoModeDef> parsed = parse(readFile());
        if (parsed.isEmpty()) {
            MyMeido.LOGGER.warn("[mymeido] {} 里一条可用模式都没有，暂时使用内置默认清单", CONFIG_FILE);
            parsed = builtinDefaults();
        }
        modes = List.copyOf(parsed);
        MyMeido.LOGGER.info("[mymeido] 模式清单已加载：{} 条（{}）", modes.size(), CONFIG_FILE);
    }

    /** 界面里从上到下的顺序。 */
    public static List<MeidoModeDef> all() {
        return modes;
    }

    public static Optional<MeidoModeDef> byId(String id) {
        if (id != null) {
            for (MeidoModeDef def : modes) {
                if (def.id().equalsIgnoreCase(id)) {
                    return Optional.of(def);
                }
            }
        }
        return Optional.empty();
    }

    /**
     * 这个 id 是不是一个合法模式。
     *
     * <p>派发时<b>必须</b>过这一关：客户端发来的 id 不可信（配置可能两边不一致，
     * 也可能是改过的客户端），服务端认不出来就该拒绝而不是硬存。
     */
    public static boolean isKnown(String id) {
        return byId(id).isPresent();
    }

    /** 默认模式。配置文件里连默认模式都删了的话，返回一个内置的兜底对象。 */
    public static MeidoModeDef defaultMode() {
        return byId(DEFAULT_MODE_ID)
                .or(() -> modes.isEmpty() ? Optional.empty() : Optional.of(modes.get(0)))
                .orElseGet(() -> new MeidoModeDef(DEFAULT_MODE_ID, "游走",
                        MeidoModeType.WANDER, MeidoModeType.Target.NONE, "内置兜底条目"));
    }

    public static Path configFile() {
        return CONFIG_FILE;
    }

    // ------------------------------------------------------------------
    // 读
    // ------------------------------------------------------------------

    private static String readFile() {
        try {
            return Files.readString(CONFIG_FILE, StandardCharsets.UTF_8);
        } catch (IOException e) {
            MyMeido.LOGGER.warn("[mymeido] 读不了 {}：{}", CONFIG_FILE, e.toString());
            return "";
        }
    }

    private static List<MeidoModeDef> builtinDefaults() {
        List<MeidoModeDef> out = new ArrayList<>(DEFAULTS.size());
        for (String[] row : DEFAULTS) {
            MeidoModeType type = MeidoModeType.fromId(row[2]);
            if (type != null) {
                out.add(new MeidoModeDef(row[0], row[1], type, type.getTarget(), type.getDescription()));
            }
        }
        return out;
    }

    private static List<MeidoModeDef> parse(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        JsonObject root;
        try {
            root = JsonParser.parseString(json).getAsJsonObject();
        } catch (RuntimeException e) {
            MyMeido.LOGGER.warn("[mymeido] modes.json 解析失败（文件保持原样不动，请自行修正）：{}", e.toString());
            return List.of();
        }
        JsonElement modesElement = root.get("modes");
        if (modesElement == null || !modesElement.isJsonArray()) {
            MyMeido.LOGGER.warn("[mymeido] modes.json 里缺少 \"modes\" 数组");
            return List.of();
        }

        JsonArray array = modesElement.getAsJsonArray();
        List<MeidoModeDef> out = new ArrayList<>(array.size());
        List<String> seen = new ArrayList<>();
        for (int i = 0; i < array.size(); i++) {
            MeidoModeDef def = parseOne(array.get(i), i);
            if (def == null) {
                continue;
            }
            if (seen.contains(def.id())) {
                MyMeido.LOGGER.warn("[mymeido] modes.json 第 {} 个条目的 id \"{}\" 重复，跳过", i, def.id());
                continue;
            }
            seen.add(def.id());
            out.add(def);
        }
        // 顶层可选项：守卫/游走战斗要不要把玩家也算进目标（默认否）。
        JsonElement gap = root.get("guard_attack_players");
        if (gap != null && gap.isJsonPrimitive()) {
            guardAttackPlayers = gap.getAsBoolean();
        }
        return out;
    }

    /** 守卫/游走的战斗判据要不要包含玩家（modes.json 顶层 {@code guard_attack_players}）。 */
    public static boolean guardAttackPlayers() {
        return guardAttackPlayers;
    }

    private static MeidoModeDef parseOne(JsonElement element, int index) {
        if (!element.isJsonObject()) {
            MyMeido.LOGGER.warn("[mymeido] modes.json 第 {} 个条目不是对象，跳过", index);
            return null;
        }
        JsonObject obj = element.getAsJsonObject();
        String typeId = string(obj, "type", null);
        MeidoModeType type = MeidoModeType.fromId(typeId);
        if (type == null) {
            // 故意不做「静默回落到游走」：拼错一个字母就变成别的模式，是最难查的 bug。
            MyMeido.LOGGER.warn("[mymeido] modes.json 第 {} 个条目的 type \"{}\" 不是已知行为钩子，跳过。"
                    + "可选值：{}", index, typeId, typeIds());
            return null;
        }

        String id = string(obj, "id", type.getId());
        String name = string(obj, "name", type.getDisplayName());
        String desc = string(obj, "desc", type.getDescription());
        MeidoModeType.Target target = readTarget(obj, type);
        return new MeidoModeDef(id.toLowerCase(Locale.ROOT), name, type, target, desc);
    }

    /**
     * {@code target} 字段可省略，省略时用行为钩子自带的默认值。
     *
     * <p>写成 {@code "target": "required"} / {@code "optional"} / {@code "none"}。
     * 认不出来就退回钩子默认值 —— 这个字段只影响「界面提示你右键哪里」，
     * 猜错的代价很小，不值得为它把整条模式丢掉。
     */
    private static MeidoModeType.Target readTarget(JsonObject obj, MeidoModeType type) {
        String raw = string(obj, "target", null);
        if (raw == null) {
            return type.getTarget();
        }
        for (MeidoModeType.Target candidate : MeidoModeType.Target.values()) {
            if (candidate.name().equalsIgnoreCase(raw)) {
                return candidate;
            }
        }
        MyMeido.LOGGER.warn("[mymeido] 模式 \"{}\" 的 target \"{}\" 认不出来，按行为默认值 {} 处理",
                type.getId(), raw, type.getTarget());
        return type.getTarget();
    }

    private static String string(JsonObject obj, String key, String fallback) {
        JsonElement element = obj.get(key);
        if (element == null || !element.isJsonPrimitive()) {
            return fallback;
        }
        String value = element.getAsString();
        return value.isBlank() ? fallback : value;
    }

    private static String typeIds() {
        StringBuilder sb = new StringBuilder();
        for (MeidoModeType type : MeidoModeType.values()) {
            if (!sb.isEmpty()) {
                sb.append(" / ");
            }
            sb.append(type.getId());
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // 写（只在文件不存在时发生）
    // ------------------------------------------------------------------

    private static void writeDefaults() {
        JsonArray array = new JsonArray();
        for (String[] row : DEFAULTS) {
            MeidoModeType type = MeidoModeType.fromId(row[2]);
            JsonObject mode = new JsonObject();
            mode.addProperty("id", row[0]);
            mode.addProperty("name", row[1]);
            mode.addProperty("type", row[2]);
            if (type != null) {
                if (type.getTarget() != MeidoModeType.Target.NONE) {
                    mode.addProperty("target", type.getTarget().name().toLowerCase(Locale.ROOT));
                }
                mode.addProperty("desc", type.getDescription());
                if (!type.isImplemented()) {
                    mode.addProperty("_state", "行为留第三期，现在派发能成功但不会真的干活");
                }
            }
            array.add(mode);
        }

        JsonObject root = new JsonObject();
        // ★ 说明文字只放【根上】，绝不塞进 modes 数组里。
        //   曾经在这里 array.add(comment(...)) 过 —— 结果自己写出来的文件自己读不了：
        //   parse 会把那条注释当成一个模式条目，报「type "null" 不是已知行为钩子，跳过」。
        //   契约就是「modes 数组里只能有模式对象」，那么写的时候也不能破例。
        root.addProperty("_readme", "女仆模式清单。改完用 /mymeido reload 生效，不用重启游戏。");
        root.addProperty("_types", "可用的 type：" + typeIds()
                + "。加自己的模式：复制一条改 id / name / target。");
        root.add("modes", array);

        try {
            Files.createDirectories(CONFIG_FILE.getParent());
            Files.writeString(CONFIG_FILE, pretty(root), StandardCharsets.UTF_8);
            MyMeido.LOGGER.info("[mymeido] 已生成默认模式清单：{}", CONFIG_FILE);
        } catch (IOException e) {
            MyMeido.LOGGER.warn("[mymeido] 写不了 {}：{}", CONFIG_FILE, e.toString());
        }
    }

    /** 手写一个缩进友好的输出，免得生成出来是一整行。 */
    private static String pretty(JsonElement element) {
        StringBuilder sb = new StringBuilder();
        append(sb, element, 0);
        sb.append('\n');
        return sb.toString();
    }

    private static void append(StringBuilder sb, JsonElement element, int indent) {
        String pad = "  ".repeat(indent);
        String inner = "  ".repeat(indent + 1);
        if (element.isJsonObject()) {
            sb.append("{\n");
            var entries = element.getAsJsonObject().entrySet();
            int i = 0;
            for (var entry : entries) {
                sb.append(inner).append('"').append(entry.getKey()).append("\": ");
                append(sb, entry.getValue(), indent + 1);
                if (++i < entries.size()) {
                    sb.append(',');
                }
                sb.append('\n');
            }
            sb.append(pad).append('}');
        } else if (element.isJsonArray()) {
            JsonArray array = element.getAsJsonArray();
            sb.append("[\n");
            for (int i = 0; i < array.size(); i++) {
                sb.append(inner);
                append(sb, array.get(i), indent + 1);
                if (i < array.size() - 1) {
                    sb.append(',');
                }
                sb.append('\n');
            }
            sb.append(pad).append(']');
        } else if (element.isJsonNull()) {
            sb.append("null");
        } else {
            sb.append(element.toString());
        }
    }

    /** 给指令补全用。 */
    public static List<String> ids() {
        List<String> out = new ArrayList<>(modes.size());
        for (MeidoModeDef def : modes) {
            out.add(def.id());
        }
        return Collections.unmodifiableList(out);
    }
}
