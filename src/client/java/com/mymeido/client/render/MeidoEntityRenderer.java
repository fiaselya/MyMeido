package com.mymeido.client.render;

import com.mymeido.MyMeido;
import com.mymeido.client.skin.MeidoSkinManager;
import com.mymeido.entity.MeidoEntity;

//? if >=1.21.11 {
import net.minecraft.client.render.entity.BipedEntityRenderer;
import net.minecraft.client.render.entity.EntityRendererFactory;
import net.minecraft.client.render.entity.feature.ArmorFeatureRenderer;
import net.minecraft.client.render.entity.feature.ElytraFeatureRenderer;
import net.minecraft.client.render.entity.feature.HeadFeatureRenderer;
import net.minecraft.client.render.entity.feature.HeldItemFeatureRenderer;
import net.minecraft.client.render.entity.model.BipedEntityModel;
import net.minecraft.client.render.entity.model.EquipmentModelData;
import net.minecraft.client.render.entity.model.EntityModelLayers;
import net.minecraft.util.Identifier;
//?} else {
import net.minecraft.client.render.entity.BipedEntityRenderer;
import net.minecraft.client.render.entity.EntityRendererFactory;
import net.minecraft.client.render.entity.feature.ArmorFeatureRenderer;
import net.minecraft.client.render.entity.feature.ElytraFeatureRenderer;
import net.minecraft.client.render.entity.feature.HeadFeatureRenderer;
import net.minecraft.client.render.entity.feature.HeldItemFeatureRenderer;
import net.minecraft.client.render.entity.model.EntityModelLayers;
import net.minecraft.client.render.entity.model.PlayerEntityModel;
import net.minecraft.util.Identifier;
//?}

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
 * <p>骨架必须用玩家那套（1.21.11 用 {@code EntityModelLayers.PLAYER_EQUIPMENT} 整套
 * EquipmentModelData + {@code EquipmentModelData.mapToEntityModel}；
 * 1.21.1 用 {@code PLAYER_INNER_ARMOR} / {@code PLAYER_OUTER_ARMOR} + {@link PlayerEntityModel}）。
 *
 * <p>另外护甲渲染层里有一道<b>静默</b>校验：
 * 它拿「装备自己声明的槽位」和「装备实际所在的槽位」比对，
 * 不一致就<b>直接不画</b>（没有日志、没有异常）。
 * 所以服务端「护甲进哪个槽」也必须严格等于护甲自己声明的槽位 ——
 * 见 {@code MeidoEntity.armorSlotOf}。
 */
//? if >=1.21.11 {
public class MeidoEntityRenderer extends BipedEntityRenderer<MeidoEntity, MeidoEntityRenderState, MeidoEntityModel> {
//?} else {
public class MeidoEntityRenderer extends BipedEntityRenderer<MeidoEntity, MeidoEntityModel> {
//?}

    /**
     * 是否渲染护甲外观。
     *
     * <p><b>刻意保持 {@code false}</b>：护甲套在女仆装外面不好看，所以要「藏起来」。
     * 注意这<b>只影响外观</b> —— 她的护甲照穿不误（减伤照算、
     * {@code /mymeido hand} 里也查得到），只是你看不见那层壳。
     *
     * <p>想恢复显示：改成 {@code true} 重新构建即可（两个版本的挂层代码都已备好）。
     *
     * <p>手持物品 / 头顶物品 / 鞘翅<b>不受这个开关影响</b>，一直是显示的
     * （那几样都是玩家特意给的，不显示反而奇怪）。
     */
    private static final boolean RENDER_ARMOR = false;

    public MeidoEntityRenderer(EntityRendererFactory.Context ctx) {
        super(ctx, new MeidoEntityModel(ctx.getPart(EntityModelLayers.PLAYER)), 0.35f);

        if (RENDER_ARMOR) {
            // 护甲：一套「四个槽位各用哪块骨架」的模型数据 + 装备渲染器。两套骨架都要给。
            //? if >=1.21.11 {
            // 1.21.11：原版玩家也是这么挂的 —— PLAYER_EQUIPMENT 是 EquipmentModelData<EntityModelLayer>，
            // mapToEntityModel 把四个槽位的 layer 烘成真正的 BipedEntityModel。
            EquipmentModelData<BipedEntityModel<MeidoEntityRenderState>> armorModels =
                    EquipmentModelData.mapToEntityModel(EntityModelLayers.PLAYER_EQUIPMENT,
                            ctx.getEntityModels(), part -> new BipedEntityModel<>(part));
            this.addFeature(new ArmorFeatureRenderer<>(this, armorModels, ctx.getEquipmentRenderer()));
            //?} else {
            // 1.21.1：内层（贴身，贴玩家皮肤）+ 外层（护甲本身）两套骨架。
            PlayerEntityModel<MeidoEntity> innerArmor =
                    new PlayerEntityModel<>(ctx.getPart(EntityModelLayers.PLAYER_INNER_ARMOR), false);
            PlayerEntityModel<MeidoEntity> outerArmor =
                    new PlayerEntityModel<>(ctx.getPart(EntityModelLayers.PLAYER_OUTER_ARMOR), false);
            this.addFeature(new ArmorFeatureRenderer<>(this, innerArmor, outerArmor, ctx.getModelManager()));
            //?}
        }

        //? if >=1.21.11 {
        // 1.21.11 签名：HeldItem 只剩 context 一个参（手持渲染走 ItemModelManager）；
        // Head 用 LoadedEntityModels + PlayerSkinCache；Elytra 加了 EquipmentRenderer。
        this.addFeature(new HeldItemFeatureRenderer<>(this));
        this.addFeature(new HeadFeatureRenderer<>(this, ctx.getEntityModels(), ctx.getPlayerSkinCache()));
        this.addFeature(new ElytraFeatureRenderer<>(this, ctx.getEntityModels(), ctx.getEquipmentRenderer()));
        //?} else {
        this.addFeature(new HeldItemFeatureRenderer<>(this, ctx.getHeldItemRenderer()));
        this.addFeature(new HeadFeatureRenderer<>(this, ctx.getModelLoader(), ctx.getHeldItemRenderer()));
        this.addFeature(new ElytraFeatureRenderer<>(this, ctx.getModelLoader()));
        //?}

        // 装备层属于「不生效也不报错」的那类问题，留一条启动日志，
        // 以后翻 logs/latest.log 一眼就能确认新版本挂了没有、护甲显示是开是关。
        MyMeido.LOGGER.info("[mymeido] render layers mounted: {} (armor appearance: {})",
                this.features.size(), RENDER_ARMOR ? "shown" : "hidden (intentional)");
    }

    //? if >=1.21.11 {
    /**
     * 1.21.11：每个渲染器负责声明自己的渲染状态类型 ——
     * {@code EntityRenderer.createRenderState()} 是抽象方法，必须返回我们的子类，
     * 否则 {@code updateRenderState} / {@code setAngles} 拿到的就是空的基类状态。
     */
    @Override
    public MeidoEntityRenderState createRenderState() {
        return new MeidoEntityRenderState();
    }

    /**
     * 1.21.11：把实体数据拷进渲染状态 —— 模型的情绪动作和贴图查表都吃这份拷贝。
     * super 已经拷了 limb / 姿势 / 装备栈等通用字段，这里只补女仆专属的三个。
     */
    @Override
    public void updateRenderState(MeidoEntity entity, MeidoEntityRenderState state, float tickDelta) {
        super.updateRenderState(entity, state, tickDelta);
        state.emotion = entity.getEmotion();
        state.emotionLeft = entity.getEmotionLeft();
        state.skin = entity.getSkin();
    }

    @Override
    public Identifier getTexture(MeidoEntityRenderState state) {
        return MeidoSkinManager.textureFor(state.skin);
    }
    //?} else {
    @Override
    public Identifier getTexture(MeidoEntity entity) {
        return MeidoSkinManager.textureFor(entity.getSkin());
    }
    //?}
}
