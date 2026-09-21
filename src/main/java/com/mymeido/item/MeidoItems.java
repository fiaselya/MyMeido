package com.mymeido.item;

import com.mojang.serialization.Codec;
import com.mymeido.MeidoConst;

import net.fabricmc.fabric.api.itemgroup.v1.FabricItemGroup;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.minecraft.component.ComponentType;
import net.minecraft.item.Item;
import net.minecraft.item.ItemGroup;
import net.minecraft.item.ItemGroups;
import net.minecraft.item.ItemStack;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.text.Text;

/**
 * 本 mod 的物品、物品组件与创造物品栏标签页。
 *
 * <h2>⚠️ 1.21 的自定义物品组件必须<b>同时</b>给 codec 和 packetCodec</h2>
 *
 * <p>物品现在自带组件，物品栏里的每一个 ItemStack 都会被同步到客户端。
 * 如果一个组件只有 {@code codec}（存档用）而没有 {@code packetCodec}（网络用），
 * <b>单机可能一点事都没有，一联机就崩在发包那一步</b> —— 因为单机也要过网络层。
 * 所以两个都写，别偷懒。
 *
 * <h2>创造物品栏：★ 自定义标签页在 1.21.1 会被 Fabric 赶到第 2 页</h2>
 *
 * <p><b>2026-09-21 实测教训（绯色报「创造物品栏里没有」）</b>：自建标签页本身没问题，
 * 但 <b>Fabric API 会给创造栏分页</b>（`fabric-item-group-api-v1` 的
 * {@code FabricCreativeGuiComponents} / {@code ItemGroupsMixin}）：
 * <ul>
 *   <li>原版那 13/14 个组被钉死在**第 1 页**（{@code fabric_setPage(0)}）；</li>
 *   <li>**所有模组加的组都被排到第 2 页起**（{@code fabric_setPage((count / TABS_PER_PAGE) + 1)}，
 *       {@code TABS_PER_PAGE = 10}）；</li>
 *   <li>第 2 页要靠标签栏最右边那个 {@code >} 箭头翻过去 —— 按钮出现在
 *       {@code displayableGroups.size() > 13}（管理员 14）时，也就是**装了自定义组的存档才有**。</li>
 * </ul>
 * 结论：**自定义标签页能出现，但在第 2 页，玩家极易以为「根本没有」**。
 * 所以现在**两处都放**：既保留「我的女仆」标签页（第 2 页），
 * 也把两件物品塞进原版 **红石** 标签页（第 1 页，一眼可见，绯色点名要的）。
 *
 * <p>⚠️ 顺带修正一条早先写错的注释：{@code ItemGroups.REDSTONE} 等常量
 * <b>是 {@code public static final RegistryKey<ItemGroup>}</b>（javap 已复核），
 * 完全能塞 —— 之前那句「常量是 private，塞不进去」是错的，
 * 真正塞不进去的是它对应的 {@code ItemGroup} 对象（那才是私有的）。
 */
public final class MeidoItems {

    /**
     * 指令闹钟上「当前选中的模式 id」。
     *
     * <p>缺省（没设过）时读出来是 {@code null}，由
     * {@link CommandAlarmItem#rawSelectedId} 负责回落成默认模式 ——
     * 于是<b>刚拿到手的闹钟不用先设一次就能直接用</b>。
     */
    public static final ComponentType<String> ALARM_MODE = Registry.register(
            Registries.DATA_COMPONENT_TYPE,
            MeidoConst.id("alarm_mode"),
            ComponentType.<String>builder()
                    .codec(Codec.STRING)
                    .packetCodec(PacketCodecs.STRING)
                    .build());

    /**
     * 指令闹钟<b>绑给谁</b>（女仆实体的 UUID 字符串）。
     *
     * <p>2026-09-21 加：契约造出来的女仆一人配一块闹钟，这块闹钟<b>只对她有效</b>。
     * 之前「派活 = 找最近的那位」在只有一只女仆时没问题，一旦养了两三只，
     * 站在她俩中间派活就会派错人 —— 这种「串了」玩家很难归因，
     * 只会觉得「这 mod 有点不受控」。绑 UUID 之后：
     * <b>绑了就是她，找不到就明确报错，绝不悄悄改派给别人。</b>
     *
     * <p>存字符串而不是 UUID 对象：组件只支持有 codec 的类型，
     * 字符串是最省事也最不会随版本变的一种。
     */
    public static final ComponentType<String> ALARM_MAID = Registry.register(
            Registries.DATA_COMPONENT_TYPE,
            MeidoConst.id("alarm_maid"),
            ComponentType.<String>builder()
                    .codec(Codec.STRING)
                    .packetCodec(PacketCodecs.STRING)
                    .build());

    /**
     * 绑定时她的名字（快照）。
     *
     * <p>只为了「她不在附近 / 已经没了」时，提示里还能说清<b>这块闹钟本来是谁的</b>——
     * 只说「找不到绑定的女仆」，玩家手里几块闹钟就完全分不清哪块是哪块。
     * 找到她时以实体上的实时名字为准（玩家可能改过名）。
     */
    public static final ComponentType<String> ALARM_MAID_NAME = Registry.register(
            Registries.DATA_COMPONENT_TYPE,
            MeidoConst.id("alarm_maid_name"),
            ComponentType.<String>builder()
                    .codec(Codec.STRING)
                    .packetCodec(PacketCodecs.STRING)
                    .build());

    /**
     * 指令闹钟本体。堆叠上限 1：它是个「工具」，不设数量没有意义。
     *
     * <p>★ 1.21.2+ 要求 {@code Item} 构造时就在 Settings 里带上 {@code registryKey}
     * （物品 id 从「注册时才分配」改成「构造时就必须知道」），否则构造直接
     * NPE「Item id not set」—— 无头冒烟 2026-09-21 实锤。1.21.1 走老路径。
     */
    //? if >=1.21.11 {
    private static final RegistryKey<Item> COMMAND_ALARM_KEY =
            RegistryKey.of(RegistryKeys.ITEM, MeidoConst.id("command_alarm"));

    public static final Item COMMAND_ALARM = Registry.register(
            Registries.ITEM,
            COMMAND_ALARM_KEY,
            new CommandAlarmItem(new Item.Settings().maxCount(1).registryKey(COMMAND_ALARM_KEY)));
    //?} else {
    public static final Item COMMAND_ALARM = Registry.register(
            Registries.ITEM,
            MeidoConst.id("command_alarm"),
            new CommandAlarmItem(new Item.Settings().maxCount(1)));
    //?}

    /**
     * 女仆契约 —— 创造一位女仆的道具（玩家加入世界时自动到手）。
     *
     * <p>★ 堆叠上限 <b>1</b>，且<b>用掉就没了</b>（2026-09-21 绯色定稿：「契约只能用一次」）。
     * —— 一份契约换一位女仆，不存在「一张契约造一群」的情况，
     * 上限 1 让这件事在物品栏里也一眼看得出来（不会出现「我手里 7 张，以为能用 7 次」的暗示）。
     * 想要更多：合成（下界之星 + 8 张纸）或 {@code /mymeido contract}。
     */
    //? if >=1.21.11 {
    private static final RegistryKey<Item> MEIDO_CONTRACT_KEY =
            RegistryKey.of(RegistryKeys.ITEM, MeidoConst.id("meido_contract"));

    public static final Item MEIDO_CONTRACT = Registry.register(
            Registries.ITEM,
            MEIDO_CONTRACT_KEY,
            new MeidoContractItem(new Item.Settings().maxCount(1).registryKey(MEIDO_CONTRACT_KEY)));
    //?} else {
    public static final Item MEIDO_CONTRACT = Registry.register(
            Registries.ITEM,
            MeidoConst.id("meido_contract"),
            new MeidoContractItem(new Item.Settings().maxCount(1)));
    //?}

    /** 「我的女仆」创造物品栏标签页的注册键。 */
    public static final RegistryKey<ItemGroup> MEIDO_GROUP_KEY = RegistryKey.of(
            RegistryKeys.ITEM_GROUP, MeidoConst.id("mymeido"));

    private MeidoItems() {
    }

    /** 由主入口调用。字段的静态初始化已经完成了注册，这里只是给一个明确的调用点。 */
    public static void register() {
        // ① 原版「红石」标签页 —— 第 1 页、一眼可见（绯色点名要的落点）。
        //    自定义标签页会被 Fabric 赶到第 2 页，光靠它玩家会以为「创造栏里没有」。
        ItemGroupEvents.modifyEntriesEvent(ItemGroups.REDSTONE).register(group -> {
            group.add(COMMAND_ALARM);
            group.add(MEIDO_CONTRACT);
        });

        // ② 自建「我的女仆」标签页：必须在物品注册完之后再建（icon 要拿 COMMAND_ALARM 的栈），
        //    放在 register() 里正好保证调用顺序。
        //    ⚠️ 它落在**第 2 页**（Fabric API 的分页规则，见类注释），不是 bug。
        Registry.register(Registries.ITEM_GROUP, MEIDO_GROUP_KEY, FabricItemGroup.builder()
                .icon(() -> new ItemStack(COMMAND_ALARM))
                .displayName(Text.translatable("itemgroup.mymeido"))
                .build());
        ItemGroupEvents.modifyEntriesEvent(MEIDO_GROUP_KEY)
                .register(group -> group.add(COMMAND_ALARM));
        ItemGroupEvents.modifyEntriesEvent(MEIDO_GROUP_KEY)
                .register(group -> group.add(MEIDO_CONTRACT));
    }
}
