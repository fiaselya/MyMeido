package com.mymeido.entity;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

import com.mymeido.MyMeido;

import net.fabricmc.loader.api.FabricLoader;

/**
 * 皮肤库 —— <b>扫目录</b>得出「现在一共能选几个角色」。
 *
 * <p>目录：{@code .minecraft/config/mymeido/skins/}。
 * <b>一张 png = 一个角色 = 菜单里的一个编号</b>，文件名就是角色名（去扩展名），
 * 数量不设上限。加图之后按 {@code F3+T} 重载资源（或 {@code /mymeido skins reload}）即可生效，
 * 不用重启游戏。
 *
 * <h2>★ 为什么「服务端也扫这个目录」也成立</h2>
 *
 * <p>皮肤清单要在服务端用（发给玩家看的编号菜单、{@code /mymeido skin} 补全），
 * 所以扫描逻辑放在<b>公共源集</b>，两端各扫自己的目录。
 * 单机（含「对局域网开放」）里两端是同一个 JVM、同一个 config 目录，结果天然一致；
 * 专用服务器上服务端扫的是服务器自己的目录（没放图就是内置保底 4 位），
 * 而<b>渲染永远用玩家自己客户端的图</b> —— 这是「各看各的」的固有代价，
 * 换来的是「加图完全不用改代码」。
 *
 * <h2>★ 一张图都没有时的保底</h2>
 *
 * <p>本 mod 不附带任何贴图（版权原因），所以新装玩家打开菜单时目录是空的。
 * 这时不能给「0 个角色」—— 那就等于契约道具点下去毫无反应、玩法直接卡死。
 * 所以退回 {@link #BUILTIN} 那 4 个经典槽位（贴图回落原版 Steve），
 * 玩家的第一件事就是往目录里丢图。
 */
public final class MeidoSkinRegistry {

    /** 皮肤目录：{@code .minecraft/config/mymeido/skins/} */
    public static final Path SKIN_DIR =
            FabricLoader.getInstance().getConfigDir().resolve("mymeido").resolve("skins");

    /**
     * 目录为空时的保底槽位。
     *
     * <p>这 4 位的 id 与「枚举时代」写进存档的 id <b>完全一致</b>
     * （见 {@link MeidoSkin#normalize}），所以老存档的女仆不会丢皮肤。
     */
    private static final List<MeidoSkin> BUILTIN = List.of(
            MeidoSkin.ofFile("hoshino.png"),
            MeidoSkin.ofFile("rikka.png"),
            MeidoSkin.ofFile("kotone.png"),
            MeidoSkin.ofFile("Sakurai Momoka.png"));

    /** 全部角色，已排序（按 id，忽略大小写）。永不为空。 */
    private static volatile List<MeidoSkin> all = BUILTIN;
    private static volatile Map<String, MeidoSkin> byId = index(BUILTIN);

    /** 上一次扫描时目录里实际有几张 png —— 用来在提示里区分「保底」和「你自己的图」。 */
    private static volatile int scannedFiles = 0;

    private MeidoSkinRegistry() {
    }

    /** 全部角色（不可变、已排序）。 */
    public static List<MeidoSkin> all() {
        return all;
    }

    /** 全部角色的 id（指令补全用）。 */
    public static List<String> ids() {
        List<MeidoSkin> snapshot = all;
        List<String> out = new ArrayList<>(snapshot.size());
        for (MeidoSkin skin : snapshot) {
            out.add(skin.getId());
        }
        return out;
    }

    /** 现在有几个角色可选。菜单里的编号就是 {@code 1..size()}。 */
    public static int size() {
        return all.size();
    }

    /** 目录里实际上有几个 png（= 玩家自己放了几个角色；0 表示用的是内置保底）。 */
    public static int fileCount() {
        return scannedFiles;
    }

    /** 皮肤目录路径（提示文本用）。 */
    public static Path dir() {
        return SKIN_DIR;
    }

    /**
     * 按 id 查。宽容：大小写 / 空格 / 下划线都无所谓，枚举时代的老 id
     * （{@code momoka}）也认 —— 归一化统一走 {@link MeidoSkin#canonicalId}。
     *
     * @return 查不到返回 {@code null}（调用方负责回落，见 {@link MeidoSkin#fromId}）
     */
    public static MeidoSkin byId(String id) {
        return id == null ? null : byId.get(MeidoSkin.canonicalId(id));
    }

    /**
     * 默认皮肤 —— 清单里的第一位。
     *
     * <p>新造的女仆、以及 {@code /mymeido summon} 不带参数时都用它。
     * 「第一位」而不是「写死 hoshino」：玩家只放了一张 {@code yuuka.png} 时，
     * 新女仆就该是 yuuka，而不是一张找不到图的 hoshino。
     */
    public static MeidoSkin defaultSkin() {
        List<MeidoSkin> snapshot = all;
        return snapshot.isEmpty() ? MeidoSkin.ofFile("hoshino.png") : snapshot.get(0);
    }

    /**
     * 重新扫目录。启动时、{@code F3+T} 重载资源时、{@code /mymeido skins reload} 时各调一次。
     *
     * @return 现在有几个角色
     */
    public static int reload() {
        List<MeidoSkin> found = new ArrayList<>();

        // 目录先建出来，玩家才知道该往哪儿放图（第一次跑 mod 时它一定不存在）。
        try {
            Files.createDirectories(SKIN_DIR);
        } catch (IOException e) {
            MyMeido.LOGGER.warn("[mymeido] cannot create skin directory {}: {}", SKIN_DIR, e.toString());
        }

        try (Stream<Path> stream = Files.list(SKIN_DIR)) {
            List<Path> files = stream
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString()
                            .toLowerCase(Locale.ROOT).endsWith(".png"))
                    // 排序在这里定死：两端、每次启动都必须是同一个顺序，
                    // 否则「编号 2」在不同时候指向不同角色，玩家会以为选错了。
                    .sorted(Comparator.comparing(
                            path -> path.getFileName().toString().toLowerCase(Locale.ROOT)))
                    .toList();

            Map<String, Path> seen = new LinkedHashMap<>();
            for (Path file : files) {
                String fileName = file.getFileName().toString();
                String id = MeidoSkin.normalize(fileName);
                if (id.isEmpty()) {
                    continue;
                }
                Path previous = seen.putIfAbsent(id, file);
                if (previous != null) {
                    // 规范化后撞名（Sakurai Momoka.png vs sakurai_momoka.png）：
                    // 保留先扫到的那个，另一个明确报出来，免得玩家以为两张都被认了。
                    MyMeido.LOGGER.warn("[mymeido] skins {} and {} normalize to the same id '{}'; "
                            + "only the first is kept — rename one if you want both",
                            previous.getFileName(), fileName, id);
                    continue;
                }
                found.add(MeidoSkin.ofFile(fileName));
            }

            scannedFiles = found.size();
            if (found.isEmpty()) {
                // 一张图都没有：退回内置保底，别让「选角色的菜单」变成空清单。
                all = BUILTIN;
                MyMeido.LOGGER.info("[mymeido] skin directory {} has no png yet; using {} built-in placeholder "
                        + "slots (textures fall back to vanilla Steve). Drop pngs in to use them.", SKIN_DIR, BUILTIN.size());
            } else {
                found.sort(Comparator.comparing(MeidoSkin::getId, String.CASE_INSENSITIVE_ORDER));
                all = List.copyOf(found);
                MyMeido.LOGGER.info("[mymeido] skin registry loaded: {} characters {} — directory {}",
                        all.size(), ids(), SKIN_DIR);
            }
            byId = index(all);
        } catch (IOException e) {
            // 目录读不了（权限 / 被占用）：保留上一次的清单比清空更好用。
            MyMeido.LOGGER.warn("[mymeido] failed to read skin directory {}; keeping the previous registry: {}",
                    SKIN_DIR, e.toString());
        }

        return all.size();
    }

    private static Map<String, MeidoSkin> index(List<MeidoSkin> skins) {
        Map<String, MeidoSkin> map = new LinkedHashMap<>();
        for (MeidoSkin skin : skins) {
            map.putIfAbsent(skin.getId(), skin);
        }
        return Map.copyOf(map);
    }
}
