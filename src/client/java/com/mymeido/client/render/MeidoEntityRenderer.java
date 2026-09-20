package com.mymeido.client.render;

import com.mymeido.MyMeido;
import com.mymeido.client.skin.MeidoSkinManager;
import com.mymeido.entity.MeidoEntity;

import net.minecraft.client.render.entity.BipedEntityRenderer;
import net.minecraft.client.render.entity.EntityRendererFactory;
import net.minecraft.client.render.entity.feature.ArmorFeatureRenderer;
import net.minecraft.client.render.entity.feature.ElytraFeatureRenderer;
import net.minecraft.client.render.entity.feature.HeadFeatureRenderer;
import net.minecraft.client.render.entity.feature.HeldItemFeatureRenderer;
import net.minecraft.client.render.entity.model.EntityModelLayers;
import net.minecraft.client.render.entity.model.PlayerEntityModel;
import net.minecraft.util.Identifier;

/**
 * 女仆渲染器。
 *
 * <p>用原版 {@link BipedEntityRenderer}（人形两足）而不是自己写一套 —— 走姿、
 * 手臂摆动、受击闪红、死亡倒转全部白送。我们只需要告诉它两件事：
 * 「用哪个模型」和「用哪张贴图」。
 *
 * <p>{@code EntityModelLayers.PLAYER} 是原版粗手臂玩家模型的骨骼模板。
 *
 * <p>{@link #getTexture} 每帧都会被调一次，所以里面必须只做查表，
 * 绝不能在里头读文件或建纹理 —— 那是 MeidoSkinManager 的活儿。
 *
 * <h2>⚠️ 装备 / 手持物品必须自己加渲染层</h2>
 *
 * <p>{@code BipedEntityRenderer} <b>不会</b>自动帮你渲染护甲和手上的东西 ——
 * 原版玩家那几层是 {@code PlayerEntityRenderer} 在构造器里一层层 {@code addFeature} 上去的。
 * 不加会怎样？装备在逻辑上<b>完全生效</b>（{@code getArmorValue()} 照样算减伤、
 * 手上的剑照样能打出伤害），但<b>玩家什么都看不见</b> ——
 * 一个「扔了把剑她却空着手」的灵异现象，而且没有任何报错，极难定位。
 *
 * <p>这里对齐原版玩家的做法：
 * <ul>
 *   <li>{@link HeldItemFeatureRenderer} —— 主手 / 副手拿着的东西，含挥动手臂的姿势；</li>
 *   <li>{@link HeadFeatureRenderer} —— 头顶戴的东西（南瓜、骷髅头、方块）；</li>
 *   <li>{@link ElytraFeatureRenderer} —— 鞘翅。</li>
 * </ul>
 *
 * <h2>★ 护甲外观「刻意不渲染」（见 {@link #RENDER_ARMOR}）</h2>
 *
 * <p>护甲套在女仆装外面不好看，所以她身上的护甲<b>只穿不显</b>：
 * 服务端该装备的照旧装备（减伤照算、{@code /mymeido hand} 查得到），
 * 只是客户端这里<b>不挂护甲渲染层</b>。
 *
 * <h2>如果哪天要打开护甲显示 —— 两条已经踩平的坑</h2>
 *
 * <p>骨架表必须用 {@code PLAYER_INNER_ARMOR} / {@code PLAYER_OUTER_ARMOR}，
 * 且骨架类要用 {@link PlayerEntityModel}（跟原版玩家一致，别用裸 {@code BipedEntityModel}），
 * 构造器第二参 {@code false} = 粗手臂（wide），与上面 {@code EntityModelLayers.PLAYER} 一致。
 * javap 实测 1.21.1 的边界是 {@code PlayerEntityModel<T extends LivingEntity>}
 * （不是教程里常说的 {@code PlayerEntity}），所以自定义 NPC 能直接拿来用。
 *
 * <p>另外客户端 {@code ArmorFeatureRenderer} 里有一道<b>静默</b>校验：
 * 它拿 {@code ArmorItem.getSlotType()} 和「装备实际所在的槽位」比对，
 * 不一致就<b>直接不画</b>（没有日志、没有异常）。
 * 所以服务端「护甲进哪个槽」也必须严格等于护甲自己声明的槽位 ——
 * 见 {@code MeidoEntity.armorSlotOf}。
 */
public class MeidoEntityRenderer extends BipedEntityRenderer<MeidoEntity, MeidoEntityModel> {

    /**
     * 是否渲染护甲外观。
     *
     * <p><b>刻意保持 {@code false}</b>：护甲套在女仆装外面不好看，所以要「藏起来」。
     * 注意这<b>只影响外观</b> —— 她的护甲照穿不误（减伤照算、
     * {@code /mymeido hand} 里也查得到），只是你看不见那层壳。
     *
     * <p>想恢复显示：改成 {@code true} 重新构建即可。
     *
     * <p>手持物品 / 头顶物品 / 鞘翅<b>不受这个开关影响</b>，一直是显示的
     * （那几样都是玩家特意给的，不显示反而奇怪）。
     */
    private static final boolean RENDER_ARMOR = false;

    public MeidoEntityRenderer(EntityRendererFactory.Context ctx) {
        super(ctx, new MeidoEntityModel(ctx.getPart(EntityModelLayers.PLAYER)), 0.35f);

        if (RENDER_ARMOR) {
            // 护甲：内层（贴身，贴玩家皮肤）+ 外层（护甲本身）。两套骨架都要给。
            PlayerEntityModel<MeidoEntity> innerArmor =
                    new PlayerEntityModel<>(ctx.getPart(EntityModelLayers.PLAYER_INNER_ARMOR), false);
            PlayerEntityModel<MeidoEntity> outerArmor =
                    new PlayerEntityModel<>(ctx.getPart(EntityModelLayers.PLAYER_OUTER_ARMOR), false);
            this.addFeature(new ArmorFeatureRenderer<>(this, innerArmor, outerArmor, ctx.getModelManager()));
        }

        this.addFeature(new HeldItemFeatureRenderer<>(this, ctx.getHeldItemRenderer()));
        this.addFeature(new HeadFeatureRenderer<>(this, ctx.getModelLoader(), ctx.getHeldItemRenderer()));
        this.addFeature(new ElytraFeatureRenderer<>(this, ctx.getModelLoader()));

        // 装备层属于「不生效也不报错」的那类问题，留一条启动日志，
        // 以后翻 logs/latest.log 一眼就能确认新版本挂了没有、护甲显示是开是关。
        MyMeido.LOGGER.info("[mymeido] render layers mounted: {} (armor appearance: {})",
                this.features.size(), RENDER_ARMOR ? "shown" : "hidden (intentional)");
    }

    @Override
    public Identifier getTexture(MeidoEntity entity) {
        return MeidoSkinManager.textureFor(entity.getSkin());
    }
}
