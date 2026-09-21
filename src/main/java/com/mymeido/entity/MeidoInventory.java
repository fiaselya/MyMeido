package com.mymeido.entity;

import net.minecraft.inventory.Inventories;
import net.minecraft.inventory.SimpleInventory;
//? if >=1.21.11 {
import net.minecraft.storage.ReadView;
import net.minecraft.storage.WriteView;
//?}
import net.minecraft.util.collection.DefaultedList;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.registry.RegistryWrapper;

/**
 * 女仆自己的背包（9 格，像玩家的快捷栏）。
 *
 * <p><b>为什么必须自己造一个：</b>原版 {@code Mob} 身上只有「主手 + 副手 + 4 个护甲槽」，
 * 没有能装杂物的通用容器。而「捡起玩家扔的东西」「白天挖的产出」「晚上收进箱子」
 * 这些设计全都要求她能<b>带着东西走</b> —— 没有背包，二期就无从谈起。
 *
 * <p>实现上直接复用 {@link SimpleInventory}（原版给箱子/漏斗用的那个）：
 * 它自带「同类物品自动叠堆」「塞不下时返回剩余」的语义，比手撸一个 ItemStack[] 靠谱。
 * 我们只负责把它接进 NBT 存档。
 *
 * <p>NBT 写读用的是 {@code SimpleInventory#toNbtList / readNbtList}，
 * 它需要 {@link RegistryWrapper.WrapperLookup} 来解析物品组件（1.21 物品是靠组件描述自己的，
 * 没有 registry 就没法把「钻石剑」还原成对象）。调用方传实体自己的 registryManager 即可。
 */
public final class MeidoInventory {

    /** 格数。9 = 玩家快捷栏的宽度，够装一天的产出。 */
    public static final int SIZE = 9;

    /** 存档键。改了它 = 老存档的背包内容读不出来（会静默清空），别乱动。 */
    private static final String NBT_KEY = "MeidoItems";

    private final SimpleInventory backing;

    public MeidoInventory() {
        this(SIZE);
    }

    public MeidoInventory(int size) {
        this.backing = new SimpleInventory(size);
    }

    public int size() {
        return this.backing.size();
    }

    /**
     * 底层 {@link SimpleInventory} 本体。只给「背包界面」用 ——
     * 原版 {@code GenericContainerScreenHandler} 要一个真正的 {@code Inventory}，
     * 直接把 backing 递过去，格子增删改查和容量校验全是原版现成的。
     * 其它路径（钓鱼/种植/战斗）照旧走上面那层薄封装，别绕过 add/take 的数量记账。
     */
    public SimpleInventory backing() {
        return this.backing;
    }

    public boolean isEmpty() {
        return this.backing.isEmpty();
    }

    /** 第 slot 格。空槽返回 {@code ItemStack.EMPTY}。 */
    public ItemStack get(int slot) {
        return this.backing.getStack(slot);
    }

    /**
     * 把一堆东西塞进来，同类会自动并到已有格子上。
     *
     * @param stack 会被就地消耗（数量减少）—— 调用方靠「还剩多少」判断要不要掉在地上
     * @return <b>没塞下的剩余</b>；空 = 全塞下了
     *
     * <p>⚠️★★ <b>这里必须自己扣数量，不能指望 {@code SimpleInventory.addStack} 帮忙。</b>
     * 原版实现是（javap 核实过字节码）：
     * <pre>
     * int before;
     * ItemStack copy = stack.copy();     // ← 全程操作的是副本
     * this.addToExistingSlot(copy);
     * this.addToNewSlot(copy);
     * return copy.isEmpty() ? ItemStack.EMPTY : copy;
     * </pre>
     * 它<b>从头到尾没碰过传入的那个 stack</b>。
     *
     * <p>照直写 {@code return backing.addStack(stack)} 的后果：物品明明已经进了背包，
     * 但调用方看到 {@code stack} 还满着 → 判定「没塞下」→ 地上的物品实体不被清除
     * → 下一 tick 又捡到同一件 → <b>每 tick 重复捡一次</b>。
     * 表现是「她一直在捡那件东西、情绪反复触发、地上的东西永远不消失」，且<b>零报错</b>。
     */
    public ItemStack add(ItemStack stack) {
        if (stack.isEmpty()) {
            return ItemStack.EMPTY;
        }
        int before = stack.getCount();
        // 传副本进去，确保原版实现对我们的对象没有任何副作用。
        ItemStack leftover = this.backing.addStack(stack.copy());
        stack.decrement(before - leftover.getCount());
        return leftover;
    }

    /** 把第 slot 格整个取出来（格子变空）。 */
    public ItemStack take(int slot) {
        return this.backing.removeStack(slot);
    }

    public void clear() {
        this.backing.clear();
    }

    // 1.21.11：SimpleInventory 不再有 toNbtList/readNbtList，改走 Inventories.writeData/
    // readData(WriteView/ReadView)（javap 实锤）。原版读写的是 DefaultedList，
    // 所以先拷进一个临时 DefaultedList 再交给原版，读回来再写回背包。
    //? if >=1.21.11 {
    public void writeView(WriteView view, RegistryWrapper.WrapperLookup lookup) {
        DefaultedList<ItemStack> list = DefaultedList.ofSize(this.backing.size(), ItemStack.EMPTY);
        for (int i = 0; i < this.backing.size(); i++) {
            list.set(i, this.backing.getStack(i));
        }
        Inventories.writeData(view.get(NBT_KEY), list);
    }

    public void readView(ReadView view, RegistryWrapper.WrapperLookup lookup) {
        view.getOptionalReadView(NBT_KEY).ifPresent(sub -> {
            DefaultedList<ItemStack> list = DefaultedList.ofSize(this.backing.size(), ItemStack.EMPTY);
            Inventories.readData(sub, list);
            for (int i = 0; i < this.backing.size() && i < list.size(); i++) {
                this.backing.setStack(i, list.get(i));
            }
        });
    }
    //?} else {
    /*public void writeNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup lookup) {
        nbt.put(NBT_KEY, this.backing.toNbtList(lookup));
    }

    public void readNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup lookup) {
        if (nbt.contains(NBT_KEY, NbtElement.LIST_TYPE)) {
            this.backing.readNbtList(nbt.getList(NBT_KEY, NbtElement.COMPOUND_TYPE), lookup);
        }
    }
    *///?}
}
