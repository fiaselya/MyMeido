package com.mymeido.client.skin;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import com.mymeido.MeidoConst;
import com.mymeido.MyMeido;
import com.mymeido.entity.MeidoSkin;
import com.mymeido.entity.MeidoSkinRegistry;

import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.fabricmc.fabric.api.resource.SimpleSynchronousResourceReloadListener;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.resource.ResourceManager;
import net.minecraft.resource.ResourceType;
import net.minecraft.util.Identifier;

/**
 * 皮肤池：把「皮肤」翻译成「贴图 Identifier」。
 *
 * <p>贴图来源只有一处：{@code .minecraft/config/mymeido/skins/} 里的 png，
 * 按 {@link MeidoSkin#fileName()} 取 —— 而那个文件名正是
 * {@link MeidoSkinRegistry} 扫目录扫出来的，所以「有几张图就有几个角色，
 * 加图不用改代码」这条链是通的。
 *
 * <p>读盘 + 建纹理<b>必须缓存</b> —— {@code getTexture} 每帧都会被调一次，
 * 不缓存就是每帧读一次磁盘、再建一次纹理，几秒钟就能把显存吃光。
 * 解析失败也照样缓存，否则会退化成「每帧刷一行报错」。
 *
 * <p>按 {@code F3+T} 重载资源会<b>重扫目录 + 清缓存</b>，所以换图 / 加图都不用重启游戏。
 */
public final class MeidoSkinManager {

    /** 找不到自家贴图时的兜底：原版 Steve 一定在游戏本体里，缺不了。 */
    private static final Identifier FALLBACK =
            Identifier.ofVanilla("textures/entity/player/wide/steve.png");

    /**
     * key = 皮肤 id。
     *
     * <p>用 {@code String} 而不是 {@code MeidoSkin}：皮肤现在不是枚举了
     * （{@code EnumMap} 用不了），而且这张表活在每帧都会被查一次的路径上，
     * 用不可变的 id 字符串当键最省事。
     */
    private static final Map<String, Identifier> CACHE = new HashMap<>();

    private MeidoSkinManager() {
    }

    public static void init() {
        // 目录的创建与清单的扫描都在 MeidoSkinRegistry 里（公共源集），
        // 它在 MyMeido#onInitialize 里已经跑过一次 —— 客户端这里不重复扫。
        ResourceManagerHelper.get(ResourceType.CLIENT_RESOURCES).registerReloadListener(
                new SimpleSynchronousResourceReloadListener() {
                    @Override
                    public Identifier getFabricId() {
                        return MeidoConst.id("skins");
                    }

                    @Override
                    public void reload(ResourceManager manager) {
                        // ★ 重扫目录也放在这里：F3+T 是玩家「刚往 skins 里丢了一张图，
                        //   怎么让它立刻生效」最自然的动作。只清贴图缓存而不重扫清单的话，
                        //   新角色还是不会出现在选人菜单里（菜单读的是注册表）。
                        MeidoSkinRegistry.reload();
                        CACHE.clear();
                    }
                });
    }

    /** 渲染器唯一入口。必须足够快 —— 它每帧都会被调用。 */
    public static Identifier textureFor(MeidoSkin skin) {
        String key = skin.getId();
        Identifier cached = CACHE.get(key);
        if (cached != null) {
            return cached;
        }
        Identifier resolved = load(skin);
        CACHE.put(key, resolved);
        return resolved;
    }

    private static Identifier load(MeidoSkin skin) {
        Path file = find(skin);
        if (file == null) {
            MyMeido.LOGGER.warn("[mymeido] 皮肤目录里找不到 {}，回落原版 Steve。目录：{}",
                    skin.fileName(), MeidoSkinRegistry.dir());
            return FALLBACK;
        }
        try (InputStream in = Files.newInputStream(file)) {
            NativeImage image = NativeImage.read(in);
            Identifier id = MeidoConst.id(texturePath(skin));
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
     * 贴图资源路径 —— 必须能当 {@link Identifier} 的 path 用。
     *
     * <p>★ 皮肤 id 现在来自玩家自己起的文件名，可能是中文（{@code 小鸟游星野.png}），
     * 而 {@code Identifier} 只认 {@code [a-z0-9_.-/]}，直接拿 id 拼路径会抛
     * {@code InvalidIdentifierException}（而且是在渲染线程上抛）。
     * 所以：非法字符一律换成下划线，再补一段 id 的哈希 ——
     * 光净化会撞名（两个中文角色都变成一串下划线），补哈希才唯一。
     */
    private static String texturePath(MeidoSkin skin) {
        String id = skin.getId();
        StringBuilder safe = new StringBuilder(id.length() + 9);
        for (int i = 0; i < id.length(); i++) {
            char c = id.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                    || c == '_' || c == '.' || c == '-';
            safe.append(ok ? c : '_');
        }
        return "skins/" + safe + "-" + Integer.toHexString(id.hashCode());
    }

    /**
     * 找图：先按注册表里记的真实文件名拿（精确路径，常规情况一步到位）；
     * 找不到再把整个目录扫一遍做一次「忽略大小写 / 空格 / 下划线」的宽容匹配
     * —— 这样玩家把 {@code Sakurai Momoka.png} 改名成 {@code sakurai_momoka.png}
     * 之后仍然能用（虽然注册表重扫后本来就会认出来，这里是兜底）。
     */
    private static Path find(MeidoSkin skin) {
        Path dir = MeidoSkinRegistry.dir();
        Path exact = dir.resolve(skin.fileName());
        if (Files.isRegularFile(exact)) {
            return exact;
        }

        String want = skin.getId();
        try (var stream = Files.list(dir)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().toLowerCase(java.util.Locale.ROOT)
                            .endsWith(".png"))
                    .filter(path -> MeidoSkin.canonicalId(path.getFileName().toString()).equals(want))
                    .findFirst()
                    .orElse(null);
        } catch (IOException e) {
            // 目录不存在 / 不可读，等同于「没找到」。
            return null;
        }
    }
}
