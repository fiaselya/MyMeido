package com.mymeido.client.skin;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

import com.mymeido.MeidoConst;
import com.mymeido.MyMeido;
import com.mymeido.entity.MeidoSkin;

import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.fabricmc.fabric.api.resource.SimpleSynchronousResourceReloadListener;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.resource.ResourceManager;
import net.minecraft.resource.ResourceType;
import net.minecraft.util.Identifier;

/**
 * 皮肤池：把「皮肤枚举」翻译成「贴图 Identifier」。
 *
 * <p>贴图来源只有一处：{@code .minecraft/config/mymeido/skins/} 里的 png，
 * 按 {@link MeidoSkin#fileName()} 取。
 *
 * <p>读盘 + 建纹理<b>必须缓存</b> —— {@code getTexture} 每帧都会被调一次，
 * 不缓存就是每帧读一次磁盘、再建一次纹理，几秒钟就能把显存吃光。
 * 解析失败也照样缓存，否则会退化成「每帧刷一行报错」。
 *
 * <p>F3+T 重载资源会清掉缓存，所以换图不用重启游戏。
 */
public final class MeidoSkinManager {

    /** 皮肤目录：{@code .minecraft/config/mymeido/skins/} */
    private static final Path SKIN_DIR =
            FabricLoader.getInstance().getConfigDir().resolve("mymeido").resolve("skins");

    /** 找不到自家贴图时的兜底：原版 Steve 一定在游戏本体里，缺不了。 */
    private static final Identifier FALLBACK =
            Identifier.ofVanilla("textures/entity/player/wide/steve.png");

    private static final Map<MeidoSkin, Identifier> CACHE = new EnumMap<>(MeidoSkin.class);

    private MeidoSkinManager() {
    }

    public static void init() {
        // 目录先建出来，玩家才知道该往哪儿放图。
        try {
            Files.createDirectories(SKIN_DIR);
        } catch (IOException e) {
            MyMeido.LOGGER.warn("[mymeido] 无法创建皮肤目录 {}：{}", SKIN_DIR, e.toString());
        }

        ResourceManagerHelper.get(ResourceType.CLIENT_RESOURCES).registerReloadListener(
                new SimpleSynchronousResourceReloadListener() {
                    @Override
                    public Identifier getFabricId() {
                        return MeidoConst.id("skins");
                    }

                    @Override
                    public void reload(ResourceManager manager) {
                        CACHE.clear();
                    }
                });
    }

    /** 渲染器唯一入口。必须足够快 —— 它每帧都会被调用。 */
    public static Identifier textureFor(MeidoSkin skin) {
        Identifier cached = CACHE.get(skin);
        if (cached != null) {
            return cached;
        }
        Identifier resolved = load(skin);
        CACHE.put(skin, resolved);
        return resolved;
    }

    private static Identifier load(MeidoSkin skin) {
        Path file = find(skin);
        if (file == null) {
            MyMeido.LOGGER.warn("[mymeido] 皮肤目录里找不到 {}，回落原版 Steve。目录：{}",
                    skin.fileName(), SKIN_DIR);
            return FALLBACK;
        }
        try (InputStream in = Files.newInputStream(file)) {
            NativeImage image = NativeImage.read(in);
            // 贴图 id 直接用皮肤 id：稳定、唯一，同一张图重复加载也不会越堆越多。
            Identifier id = MeidoConst.id("skins/" + skin.getId());
            MinecraftClient.getInstance().getTextureManager()
                    .registerTexture(id, new NativeImageBackedTexture(image));
            MyMeido.LOGGER.info("[mymeido] 已加载皮肤 {}：{} -> {}",
                    skin.getId(), file.getFileName(), id);
            return id;
        } catch (IOException | RuntimeException e) {
            MyMeido.LOGGER.warn("[mymeido] 皮肤 {} 加载失败，回落原版 Steve：{}",
                    skin.getId(), e.toString());
            return FALLBACK;
        }
    }

    /**
     * 先按原名精确找；找不到再按「小写 + 去掉空格/下划线/连字符」比对一次，
     * 这样玩家重命名或改写大小写之后也还能用。
     */
    private static Path find(MeidoSkin skin) {
        Path exact = SKIN_DIR.resolve(skin.fileName());
        if (Files.isRegularFile(exact)) {
            return exact;
        }

        String want = normalize(skin.fileName());
        try (Stream<Path> stream = Files.list(SKIN_DIR)) {
            List<Path> hits = stream
                    .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".png"))
                    .filter(path -> normalize(path.getFileName().toString()).equals(want))
                    .toList();
            return hits.isEmpty() ? null : hits.get(0);
        } catch (IOException e) {
            // 目录不存在 / 不可读，等同于「没找到」。
            return null;
        }
    }

    /** {@code "Sakurai Momoka.png"} -> {@code "sakuramomoka"} */
    private static String normalize(String fileName) {
        String name = fileName.toLowerCase(Locale.ROOT);
        if (name.endsWith(".png")) {
            name = name.substring(0, name.length() - 4);
        }
        return name.replace(" ", "").replace("_", "").replace("-", "");
    }
}
