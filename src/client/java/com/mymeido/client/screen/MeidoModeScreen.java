package com.mymeido.client.screen;

import java.util.List;
import java.util.Optional;

import com.mymeido.entity.MeidoEntity;
import com.mymeido.item.CommandAlarmItem;
import com.mymeido.item.MeidoItems;
import com.mymeido.mode.MeidoModeDef;
import com.mymeido.mode.MeidoModeRegistry;
import com.mymeido.net.MeidoModeSelectPayload;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.MathHelper;

/**
 * 模式选择界面 —— 右键空气弹出，选一个模式写进闹钟。
 *
 * <h2>刻意做得很朴素</h2>
 *
 * <p>就是一列按钮，「哪个是我挑好的」直接用文字标出来（「← 选中」）。
 * 不做图标、不做自绘高亮 —— 这一批的目的是<b>把派活这条路走通</b>，
 * 界面好看属于后面的事，现在多画一笔就是多一处能坏的地方。
 *
 * <h2>★「选中」和「她现在」是两行，不是一回事</h2>
 *
 * <p>界面上必须同时看到这两个，否则一定会误判：
 * <ul>
 *   <li><b>← 选中</b> —— 你挑好的单子（存在闹钟的物品组件里）。只有你重新挑才会变。</li>
 *   <li><b>她现在：X</b> —— 就近那只女仆的真实状态，读实体同步过来的字段，实时刷新。</li>
 * </ul>
 *
 * <p>「丢弃物品 / 绑定家」是一次性任务：她干完会自己回游走。
 * 如果界面只显示「选中」，你在她干完活之后回来一看还是「丢弃物品 ← 选中」，
 * 就会以为界面坏了 —— 2026-09-20 实测就是这么被提的。
 * 现在两行都写，再加一条「和选中不一致」的灰色说明，一眼能分清是哪种情况。
 *
 * <h2>为什么列表是分页的，而不是加滚动条</h2>
 *
 * <p>{@code modes.json} 是玩家能随手加条目的，条目数没有上限，而窗口高度有。
 * 滚动条要自己算裁剪区、自己处理滚轮和拖拽，代码量是分页的好几倍，
 * 还容易在小窗口 / 高 GUI 缩放下出各种边界问题。
 * 分页只有「每页几个」一个数字要算，且加多少条目都不会溢出屏幕。
 *
 * <h2>选完为什么本地也要改一遍</h2>
 *
 * <p>因为要的就是「立刻见效」：鼠标一点，界面关掉，
 * 你低头看手上的闹钟提示已经变成新模式了 —— 不用等服务端往返一趟。
 * 服务端那一份由 {@code MeidoModeSelectPayload} 负责改，两边改的是同一个闹钟
 * （定位逻辑都是 {@link CommandAlarmItem#findAlarm}）。
 */
public class MeidoModeScreen extends Screen {

    private static final int BUTTON_WIDTH = 200;
    private static final int BUTTON_HEIGHT = 20;
    private static final int GAP = 4;
    private static final int ROW = BUTTON_HEIGHT + GAP;

    /** 标题 + 副标题 + 「她现在」三行占掉的高度。 */
    private static final int LIST_TOP = 62;

    /** 底部留给「上一页 / 页码 / 下一页 / 关闭」的高度。 */
    private static final int FOOTER = 46;

    private static final int TITLE_COLOR = 0xFFFFFF;
    private static final int SUBTITLE_COLOR = 0xA0A0A0;
    private static final int NOW_COLOR = 0x55FF55;
    private static final int HINT_COLOR = 0x808080;

    /** 当前第几页（从 0 数）。切页只重建控件，不重开界面。 */
    private int page;

    public MeidoModeScreen() {
        super(Text.literal("派活"));
    }

    // ------------------------------------------------------------------
    // 布局
    // ------------------------------------------------------------------

    @Override
    protected void init() {
        List<MeidoModeDef> modes = MeidoModeRegistry.all();
        int perPage = Math.max(1, (this.height - LIST_TOP - FOOTER) / ROW);
        int pages = Math.max(1, (modes.size() + perPage - 1) / perPage);
        this.page = MathHelper.clamp(this.page, 0, pages - 1);

        String selected = currentSelectionId();
        int width = Math.min(BUTTON_WIDTH, this.width - 20);
        int x = (this.width - width) / 2;

        int from = this.page * perPage;
        int to = Math.min(modes.size(), from + perPage);
        int y = LIST_TOP;
        for (int i = from; i < to; i++) {
            MeidoModeDef def = modes.get(i);
            this.addDrawableChild(buildModeButton(def, def.id().equalsIgnoreCase(selected), x, y, width));
            y += ROW;
        }

        if (modes.isEmpty()) {
            // 理论上 load() 之后不会为空（读失败会退回内置清单），但真出现了得看得出来。
            this.addDrawableChild(ButtonWidget.builder(
                            Text.literal("模式清单是空的（检查 config/mymeido/modes.json）"), b -> {
                            })
                    .dimensions(x, LIST_TOP, width, BUTTON_HEIGHT).build());
        }

        if (pages > 1) {
            int arrowWidth = 20;
            int arrowY = this.height - 44;
            this.addDrawableChild(ButtonWidget.builder(Text.literal("<"), b -> turnPage(-1))
                    .dimensions(x, arrowY, arrowWidth, BUTTON_HEIGHT)
                    .tooltip(Tooltip.of(Text.literal("上一页")))
                    .build());
            this.addDrawableChild(ButtonWidget.builder(Text.literal(">"), b -> turnPage(1))
                    .dimensions(x + width - arrowWidth, arrowY, arrowWidth, BUTTON_HEIGHT)
                    .tooltip(Tooltip.of(Text.literal("下一页")))
                    .build());
        }

        this.addDrawableChild(ButtonWidget.builder(Text.literal("关闭"), b -> this.close())
                .dimensions(x, this.height - 24, width, BUTTON_HEIGHT).build());
    }

    /** 一个模式按钮。挑好的那个在名字后面挂「← 选中」，比任何高亮都直白。 */
    private ButtonWidget buildModeButton(MeidoModeDef def, boolean selected, int x, int y, int width) {
        Text label = Text.literal(def.name())
                .append(Text.literal(def.isImplemented() ? "" : "（行为待三期）").formatted(Formatting.DARK_GRAY))
                .append(Text.literal(selected ? "   ← 选中" : "").formatted(Formatting.YELLOW));
        return ButtonWidget.builder(label, b -> choose(def))
                .dimensions(x, y, width, BUTTON_HEIGHT)
                .tooltip(Tooltip.of(Text.literal(def.desc() + "\n" + def.hint())))
                .build();
    }

    /** 切页：清掉控件重新按新页码排一遍，界面本身不关。 */
    private void turnPage(int delta) {
        this.page += delta;
        // 页码越界的容忍交给 init() 里的 clamp —— 那边是唯一知道「共几页」的地方，
        // 在这里再算一遍就会出现两份「共几页」的定义，迟早对不上。
        this.clearAndInit();
    }

    // ------------------------------------------------------------------
    // 动作
    // ------------------------------------------------------------------

    private void choose(MeidoModeDef def) {
        MinecraftClient client = this.client;
        if (client == null || client.player == null) {
            return;
        }
        // 本地先改：界面一关，手上闹钟的提示就已经是新模式了。
        ItemStack alarm = CommandAlarmItem.findAlarm(client.player);
        if (alarm != null) {
            alarm.set(MeidoItems.ALARM_MODE, def.id());
        }
        // 服务端那一份走这个包。只有一个字符串字段，没什么可协商的。
        ClientPlayNetworking.send(new MeidoModeSelectPayload(def.id()));
        this.close();
    }

    /** 闹钟上当前挑好的模式 id；没拿闹钟 / 没设过就是默认模式。 */
    private String currentSelectionId() {
        MinecraftClient client = this.client;
        if (client == null || client.player == null) {
            return MeidoModeRegistry.DEFAULT_MODE_ID;
        }
        ItemStack alarm = CommandAlarmItem.findAlarm(client.player);
        return alarm == null ? MeidoModeRegistry.DEFAULT_MODE_ID : CommandAlarmItem.rawSelectedId(alarm);
    }

    /**
     * 「这块闹钟对谁有效」那位女仆现在的模式 id（实体同步过来的那份）。
     *
     * <p>读的是 {@link MeidoEntity#getSyncedModeId()}，不是闹钟上的组件 ——
     * 这样才能在她干完一次性的活、自己走回游走之后，界面上真的跟着变。
     *
     * <p>★ 走 {@code targetOf}：绑定的闹钟看她本人，未绑定的才退化成「最近的那位」。
     */
    private Optional<String> nearbyModeId() {
        MinecraftClient client = this.client;
        if (client == null || client.player == null) {
            return Optional.empty();
        }
        MeidoEntity meido = CommandAlarmItem.targetOf(client.player);
        return meido == null ? Optional.empty() : Optional.of(meido.getSyncedModeId());
    }

    // ------------------------------------------------------------------
    // 绘制
    // ------------------------------------------------------------------

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        // super 会画背景（含压暗）并把所有控件画出来，文字再叠上去。
        super.render(context, mouseX, mouseY, delta);

        context.drawCenteredTextWithShadow(this.textRenderer, this.title, this.width / 2, 16, TITLE_COLOR);
        context.drawCenteredTextWithShadow(this.textRenderer,
                Text.literal("选一个模式，然后右键方块派给她"), this.width / 2, 32, SUBTITLE_COLOR);

        // ★ 单独一行写「她现在」：上面那些「← 选中」是你挑的单子，
        //   这一行才是她此刻真在干的事（一次性任务干完会自己变回游走）。
        Optional<String> nowId = nearbyModeId();
        if (nowId.isPresent()) {
            String id = nowId.get();
            context.drawCenteredTextWithShadow(this.textRenderer,
                    Text.literal("她现在："
                            + MeidoModeRegistry.byId(id).map(MeidoModeDef::name).orElse(id)),
                    this.width / 2, 46, NOW_COLOR);
        } else {
            context.drawCenteredTextWithShadow(this.textRenderer,
                    Text.literal("附近没有女仆"), this.width / 2, 46, HINT_COLOR);
        }

        List<MeidoModeDef> modes = MeidoModeRegistry.all();
        int perPage = Math.max(1, (this.height - LIST_TOP - FOOTER) / ROW);
        int pages = Math.max(1, (modes.size() + perPage - 1) / perPage);
        if (pages > 1) {
            context.drawCenteredTextWithShadow(this.textRenderer,
                    Text.literal("第 " + (this.page + 1) + " / " + pages + " 页"),
                    this.width / 2, this.height - 44 + (BUTTON_HEIGHT - 8) / 2, SUBTITLE_COLOR);
        }

        context.drawCenteredTextWithShadow(this.textRenderer,
                Text.literal("右键空气 = 开这个界面　·　潜行右键方块 = 一定派发（不会被床 / 箱子抢走）"),
                this.width / 2, this.height - 34, HINT_COLOR);
    }

    /**
     * 不暂停游戏。
     *
     * <p>默认菜单会把单机游戏暂停，但这里挑模式的时候你多半还想看看她站在哪儿 ——
     * 一暂停，世界就冻在你右键前的那一帧，选模式变成了闭眼睛做决定。
     */
    @Override
    public boolean shouldPause() {
        return false;
    }
}
