package rain.fox.ogmr.api.gui;

import rain.fox.ogmr.api.gui.editor.EditableMachineUI;
import rain.fox.ogmr.api.machine.MetaMachine;

import com.lowdragmc.lowdraglib.gui.texture.IGuiTexture;
import com.lowdragmc.lowdraglib.gui.texture.ProgressTexture;
import com.lowdragmc.lowdraglib.gui.widget.LabelWidget;
import com.lowdragmc.lowdraglib.gui.widget.ProgressWidget;
import com.lowdragmc.lowdraglib.gui.widget.SlotWidget;
import com.lowdragmc.lowdraglib.gui.widget.TankWidget;
import com.lowdragmc.lowdraglib.gui.widget.Widget;
import com.lowdragmc.lowdraglib.gui.widget.WidgetGroup;
import com.lowdragmc.lowdraglib.gui.widget.custom.PlayerInventoryWidget;
import com.lowdragmc.lowdraglib.side.fluid.IFluidTransfer;
import com.lowdragmc.lowdraglib.side.fluid.forge.FluidTransferWrapper;
import com.lowdragmc.lowdraglib.side.item.IItemTransfer;
import com.lowdragmc.lowdraglib.side.item.forge.ItemTransferHelperImpl;
import com.lowdragmc.lowdraglib.utils.Position;

import lombok.Getter;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.fluids.capability.IFluidHandler;
import net.minecraftforge.items.IItemHandler;

import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

/**
 * ogmr 新写 —— 机器 UI 的<b>流式装配器</b>。
 *
 * <p>
 * 这是本库 GUI 层的主入口，解决的是「addon 想加一台机器，却被迫手写几十个 widget」的问题。
 * 用法是一串链式调用，最后 {@link #build(MetaMachine)}：
 *
 * <pre>{@code
 * MachineUI.create("assembler", ResourceLocations.of("myaddon", "assembler"))
 *         .size(176, 166)
 *         .title()
 *         .itemSlot(26, 20, 0, false)
 *         .itemSlot(26, 42, 1, false)
 *         .itemSlot(98, 20, 2, true)
 *         .fluidTank(134, 20, 0)
 *         .progress(62, 33, 24, 16, ProgressDirection.LEFT_TO_RIGHT,
 *                   () -> machine.getRecipeProgress())
 *         .text(8, 60, () -> Component.literal("..."))
 *         .playerInventory(8, 84);
 * }</pre>
 *
 * <h2>两条装配通道</h2>
 * <ol>
 * <li><b>默认布局</b>：本类按登记顺序摆 widget；</li>
 * <li><b>自定义 .mui</b>：如果 {@code assets/<ns>/ui/machine/<path>.mui} 存在
 * （由 LDLib 的 UI 编辑器产出），{@link #build(MetaMachine)} 会<b>反序列化它并只做数据绑定</b>，
 * 默认布局不参与。判定与读取逻辑参考 GTM 的
 * {@code com.gregtechceu.gtceu.api.gui.editor.EditableMachineUI#getCustomUI}。</li>
 * </ol>
 * 两条通道共用同一套 widget id 约定（见 {@link #ID_TITLE} 等常量与 {@link #idItemSlot(int, boolean)}），
 * 所以玩家在编辑器里画的槽位只要 id 对得上，运行时就能自动绑到机器的仓储上。
 *
 * <h2>仓储（物品/流体）怎么绑</h2>
 * <p>
 * ogmr 的 {@link MetaMachine} 契约里<b>没有</b> handler 访问口，所以默认实现走 Forge 能力：
 * {@code machine.getLevel().getBlockEntity(machine.getPos())} 上取
 * {@link ForgeCapabilities#ITEM_HANDLER} / {@link ForgeCapabilities#FLUID_HANDLER}，
 * 再用 LDLib 的适配器转成 {@code IItemTransfer} / {@code IFluidTransfer}。
 * 拿不到能力时该槽位<b>降级成纯背景占位框</b>（不会崩，也不会显示假数据）。
 * 如果 addon 的机器不走方块实体能力，用显式重载
 * {@link #itemSlot(int, int, IItemTransfer, int, boolean, boolean)} /
 * {@link #fluidTank(int, int, IFluidTransfer, int)} 直接塞 handler 即可。
 *
 * <p><b>线程/复用注意</b>：一个 {@code MachineUI} 实例描述的是「一台机器的界面模板」，
 * 请<b>每台机器注册时创建一个</b>（通常就放在 builder 里），不要跨机器共享；
 * 但同一个实例可以被 {@link #build(MetaMachine)} 反复调用（每次都会重建一份 widget 树）。
 */
public class MachineUI {

    // ═══════════════════════ widget id 约定 ═══════════════════════

    /** 机器名 LabelWidget 的 id。 */
    public static final String ID_TITLE = "ogmr_title";
    /** 右侧信息栏（ComponentPanelWidget，渲染 {@code addDisplayText}）的 id。 */
    public static final String ID_INFO = "ogmr_info";
    /** 玩家背包（PlayerInventoryWidget）的 id。 */
    public static final String ID_PLAYER_INV = "ogmr_player_inv";

    /** 物品槽 id：{@code ogmr_slot_in_0} / {@code ogmr_slot_out_0}。 */
    public static String idItemSlot(int index, boolean output) {
        return (output ? "ogmr_slot_out_" : "ogmr_slot_in_") + index;
    }

    /** 流体槽 id：{@code ogmr_tank_in_0}。 */
    public static String idFluidTank(int index) {
        return "ogmr_tank_in_" + index;
    }

    /** 进度条 id：{@code ogmr_progress_0}。 */
    public static String idProgress(int index) {
        return "ogmr_progress_" + index;
    }

    /** 动态文本 id：{@code ogmr_text_0}。 */
    public static String idText(int index) {
        return "ogmr_text_" + index;
    }

    // ═══════════════════════ 状态 ═══════════════════════

    @Getter
    private final String groupName;
    @Getter
    private final ResourceLocation uiPath;

    @Getter
    private int width = 176;
    @Getter
    private int height = 166;
    private IGuiTexture background = GuiTextures.machineBackground();
    private boolean withTitle = false;

    @Nullable
    private Position playerInventoryPos;

    private final List<Entry> entries = new ArrayList<>();

    /** 供 {@link MachineUIWidget#updateScreen()} 每帧重读的动态文本。 */
    private final List<TextSource> textSources = new ArrayList<>();
    /** 供 {@link MachineUIWidget#updateScreen()} 每帧重设 supplier 的进度条。 */
    private final List<ProgressSource> progressSources = new ArrayList<>();

    @Nullable
    private EditableMachineUI editableUI;

    // ═══════════════════════ 内部类型 ═══════════════════════

    /**
     * 一条登记项。
     *
     * @param id      运行时绑定时用来在模板里找回这个 widget
     * @param creator 默认布局通道：往 root 里放一个未绑定的 widget
     * @param binding 绑定通道：把机器数据写进（可能来自 .mui 的）widget
     */
    private record Entry(String id, BiConsumer<WidgetGroup, MetaMachine> creator, Binding binding) {}

    /** 数据绑定动作。 */
    @FunctionalInterface
    private interface Binding {

        void bind(Widget widget, MetaMachine machine);
    }

    /** 动态文本来源（id + 取值器）。 */
    public record TextSource(String id, Supplier<Component> supplier) {}

    /** 进度条来源（id + 取值器）。 */
    public record ProgressSource(String id, Supplier<Double> supplier) {}

    protected MachineUI(String groupName, ResourceLocation uiPath) {
        this.groupName = groupName;
        this.uiPath = uiPath;
    }

    /**
     * 创建一台机器的 UI 装配器。
     *
     * @param groupName LDLib UI 编辑器左侧「模板」树里的分组名（通常就是机器名）
     * @param uiPath    UI 工程路径；{@code .mui} 会读写到
     *                  {@code assets/<namespace>/ui/machine/<path>.mui}
     */
    public static MachineUI create(String groupName, ResourceLocation uiPath) {
        return new MachineUI(groupName, uiPath);
    }

    // ═══════════════════════ 流式 API ═══════════════════════

    /** 界面尺寸（默认 176×166，即标准箱子界面）。 */
    public MachineUI size(int w, int h) {
        this.width = w;
        this.height = h;
        return this;
    }

    /** 换背景贴图；默认是 {@link GuiTextures#machineBackground()}（纯色+描边，零美术资源可用）。 */
    public MachineUI background(@Nullable IGuiTexture tex) {
        this.background = tex;
        return this;
    }

    /**
     * 加一行机器名 —— {@code Component.translatable(definition.getDescriptionId())}。
     *
     * <p>位置固定在 (6, 6)；想挪位置就直接用 {@link #widget(Widget)} 自己塞一个 LabelWidget。
     */
    public MachineUI title() {
        this.withTitle = true;
        return this;
    }

    /** 玩家背包 + 快捷栏（LDLib 的 {@link PlayerInventoryWidget}，自带宽 9×4 格 + 间距）。 */
    public MachineUI playerInventory(int x, int y) {
        this.playerInventoryPos = new Position(x, y);
        return this;
    }

    /**
     * 绑到机器第 {@code index} 个物品槽。
     *
     * @param output true = 产物槽（玩家只能拿不能放）
     */
    public MachineUI itemSlot(int x, int y, int index, boolean output) {
        String id = idItemSlot(index, output);
        entries.add(new Entry(id,
                (root, machine) -> root.addWidget(createItemSlot(x, y, id, output)),
                (widget, machine) -> {
                    if (widget instanceof SlotWidget slot) {
                        IItemTransfer transfer = resolveItemTransfer(machine, index);
                        if (transfer != null) {
                            slot.setHandlerSlot(transfer, index);
                            slot.setCanPutItems(!output);
                            slot.setCanTakeItems(true);
                        }
                    }
                }));
        return this;
    }

    /** {@link #itemSlot(int, int, int, boolean)} 的显式 handler 版本（不做能力查询）。 */
    public MachineUI itemSlot(int x, int y, IItemTransfer transfer, int index, boolean canTake, boolean canPut) {
        String id = idItemSlot(index, false);
        entries.add(new Entry(id,
                (root, machine) -> root.addWidget(createItemSlot(x, y, id, !canPut)),
                (widget, machine) -> {
                    if (widget instanceof SlotWidget slot && transfer != null && index < transfer.getSlots()) {
                        slot.setHandlerSlot(transfer, index);
                        slot.setCanPutItems(canPut);
                        slot.setCanTakeItems(canTake);
                    }
                }));
        return this;
    }

    /** {@link #itemSlot(int, int, int, boolean)} 的 Forge {@link IItemHandler} 版本。 */
    public MachineUI itemSlot(int x, int y, IItemHandler handler, int index, boolean canTake, boolean canPut) {
        return itemSlot(x, y, handler == null ? null : ItemTransferHelperImpl.toItemTransfer(handler), index, canTake,
                canPut);
    }

    /** 绑到机器第 {@code index} 个储罐。 */
    public MachineUI fluidTank(int x, int y, int index) {
        String id = idFluidTank(index);
        entries.add(new Entry(id,
                (root, machine) -> root.addWidget(createTank(x, y, id)),
                (widget, machine) -> {
                    if (widget instanceof TankWidget tank) {
                        IFluidTransfer transfer = resolveFluidTransfer(machine, index);
                        if (transfer != null) {
                            tank.setFluidTank(transfer, index);
                        }
                    }
                }));
        return this;
    }

    /** {@link #fluidTank(int, int, int)} 的显式 handler 版本。 */
    public MachineUI fluidTank(int x, int y, IFluidTransfer transfer, int index) {
        String id = idFluidTank(index);
        entries.add(new Entry(id,
                (root, machine) -> root.addWidget(createTank(x, y, id)),
                (widget, machine) -> {
                    if (widget instanceof TankWidget tank && transfer != null && index < transfer.getTanks()) {
                        tank.setFluidTank(transfer, index);
                    }
                }));
        return this;
    }

    /** {@link #fluidTank(int, int, int)} 的 Forge {@link IFluidHandler} 版本。 */
    public MachineUI fluidTank(int x, int y, IFluidHandler handler, int index) {
        return fluidTank(x, y, handler == null ? null : new FluidTransferWrapper(handler), index);
    }

    /**
     * 加一个进度条。
     *
     * @param progress 取值器，返回 0..1；每帧读取
     */
    public MachineUI progress(int x, int y, int w, int h, ProgressDirection dir, Supplier<Double> progress) {
        String id = idProgress(progressSources.size());
        ProgressTexture texture = new ProgressTexture(GuiTextures.progressBarBackground(),
                GuiTextures.progressBarFilled())
                .setFillDirection(dir.toFillDirection());
        entries.add(new Entry(id,
                (root, machine) -> root.addWidget(new ProgressWidget(ProgressWidget.JEIProgress, x, y, w, h, texture)
                        .setId(id)),
                (widget, machine) -> {
                    if (widget instanceof ProgressWidget pw) {
                        // LDLib 的 ProgressWidget 收 DoubleSupplier，而对外 API 用的是 Supplier<Double>
                        // （与需求文档一致），这里补一层自动拆箱的适配。
                        pw.setProgressSupplier(() -> progress.get());
                    }
                }));
        progressSources.add(new ProgressSource(id, progress));
        return this;
    }

    /**
     * 加一段动态文本。
     *
     * <p>用 {@link LabelWidget#setTextProvider(Supplier)} 挂上取值器，所以服务端会随
     * {@code detectAndSendChanges} 自动同步、客户端每帧重读；{@link MachineUIWidget} 也会
     * 在 {@code updateScreen()} 里再刷一遍，双保险。
     */
    public MachineUI text(int x, int y, Supplier<Component> supplier) {
        String id = idText(textSources.size());
        entries.add(new Entry(id,
                (root, machine) -> root.addWidget(createLabel(x, y, id, supplier)),
                (widget, machine) -> {
                    if (widget instanceof LabelWidget label) {
                        label.setTextProvider(() -> safeText(supplier));
                    }
                }));
        textSources.add(new TextSource(id, supplier));
        return this;
    }

    /** 直接塞一个自己造的 widget（位置/尺寸/setId 都由调用方负责）。 */
    public MachineUI widget(Widget w) {
        String id = w.getId() == null ? "ogmr_widget_" + entries.size() : w.getId();
        entries.add(new Entry(id, (root, machine) -> root.addWidget(w), (widget, machine) -> {}));
        return this;
    }

    // ═══════════════════════ 装配 ═══════════════════════

    /**
     * 真正的装配：先看 {@link #getUiPath()} 对应的自定义 {@code .mui} 在不在，
     * 在就用它反序列化 + 只做数据绑定，不在才走默认布局。
     */
    public WidgetGroup build(MetaMachine machine) {
        WidgetGroup root = new WidgetGroup(0, 0, width, height);
        build(machine, root);
        return root;
    }

    /**
     * 把界面装进一个<b>已存在</b>的 root（{@link MachineUIWidget} 用这个 —— 它自己就是 WidgetGroup）。
     */
    public void build(MetaMachine machine, WidgetGroup root) {
        root.setBackground(background);

        EditableMachineUI editable = editable();
        WidgetGroup custom = editable.hasCustomUI() ? editable.createCustomUI() : null;
        if (custom != null) {
            // 自定义布局：把 .mui 里的 widget 原样搬进 root，然后只做绑定。
            for (Widget child : new ArrayList<>(custom.widgets)) {
                custom.removeWidget(child);
                root.addWidget(child);
            }
        } else {
            createDefaultLayout(root);
        }
        bind(root, machine);
    }

    /** 默认布局（不含数据绑定）：给编辑器当模板，或给没有 .mui 的机器当运行时布局。 */
    public WidgetGroup createDefaultTemplate() {
        WidgetGroup root = new WidgetGroup(0, 0, width, height);
        root.setBackground(background);
        createDefaultLayout(root);
        return root;
    }

    private void createDefaultLayout(WidgetGroup root) {
        if (withTitle) {
            LabelWidget title = new LabelWidget(6, 6, Component.empty());
            title.setTextColor(GuiTextures.COLOR_TEXT_TITLE);
            title.setId(ID_TITLE);
            root.addWidget(title);
        }
        if (playerInventoryPos != null) {
            PlayerInventoryWidget inventory = new PlayerInventoryWidget();
            inventory.setSelfPosition(playerInventoryPos);
            inventory.setId(ID_PLAYER_INV);
            root.addWidget(inventory);
        }
        for (Entry entry : entries) {
            entry.creator().accept(root, null);
        }
    }

    /** 把机器数据绑进模板（默认布局与自定义 .mui 共用这一条路径）。 */
    public void bind(WidgetGroup root, MetaMachine machine) {
        if (withTitle) {
            Widget title = findById(root, ID_TITLE);
            if (title instanceof LabelWidget label) {
                label.setComponent(Component.translatable(machine.getDefinition().getDescriptionId()));
            }
        }
        for (Entry entry : entries) {
            Widget widget = findById(root, entry.id());
            if (widget != null) {
                entry.binding().bind(widget, machine);
            }
        }
    }

    /**
     * 交给 {@code MachineDefinition.setEditableUI(...)} 的句柄。
     *
     * <p>同一个 {@code MachineUI} 只会造一个实例（缓存），所以编辑器保存 .mui 后调
     * {@link EditableMachineUI#reloadCustomUI()} 就能让运行时也看到新布局。
     */
    public EditableMachineUI buildEditable() {
        return editable();
    }

    private EditableMachineUI editable() {
        if (editableUI == null) {
            editableUI = new EditableMachineUI(groupName, uiPath, this::createDefaultTemplate, this::bind);
        }
        return editableUI;
    }

    // ═══════════════════════ 只读访问器 ═══════════════════════

    /** 动态文本来源（{@link MachineUIWidget} 用来每帧刷新）。 */
    public List<TextSource> textSources() {
        return Collections.unmodifiableList(textSources);
    }

    /** 进度条来源（{@link MachineUIWidget} 用来每帧重设 supplier）。 */
    public List<ProgressSource> progressSources() {
        return Collections.unmodifiableList(progressSources);
    }

    public int getEntryCount() {
        return entries.size();
    }

    // ═══════════════════════ 仓储解析 ═══════════════════════

    /**
     * 从机器所在方块实体上取物品能力并转成 LDLib 的 {@link IItemTransfer}。
     *
     * @return 拿不到、或者槽位数量不够 {@code index} 时返回 null（调用方应降级成占位框）
     */
    @Nullable
    public static IItemTransfer resolveItemTransfer(MetaMachine machine, int index) {
        BlockEntity blockEntity = blockEntityOf(machine);
        if (blockEntity == null) {
            return null;
        }
        IItemHandler handler = blockEntity.getCapability(ForgeCapabilities.ITEM_HANDLER, null).resolve().orElse(null);
        if (handler == null || index < 0 || index >= handler.getSlots()) {
            return null;
        }
        return ItemTransferHelperImpl.toItemTransfer(handler);
    }

    /** 从机器所在方块实体上取流体能力并转成 LDLib 的 {@link IFluidTransfer}。 */
    @Nullable
    public static IFluidTransfer resolveFluidTransfer(MetaMachine machine, int index) {
        BlockEntity blockEntity = blockEntityOf(machine);
        if (blockEntity == null) {
            return null;
        }
        IFluidHandler handler = blockEntity.getCapability(ForgeCapabilities.FLUID_HANDLER, null).resolve().orElse(null);
        if (handler == null || index < 0 || index >= handler.getTanks()) {
            return null;
        }
        return new FluidTransferWrapper(handler);
    }

    @Nullable
    private static BlockEntity blockEntityOf(MetaMachine machine) {
        var level = machine.getLevel();
        if (level == null) {
            return null;
        }
        return level.getBlockEntity(machine.getPos());
    }

    // ═══════════════════════ widget 工厂 / 查找工具 ═══════════════════════

    private static SlotWidget createItemSlot(int x, int y, String id, boolean output) {
        SlotWidget slot = new SlotWidget();
        slot.initTemplate();
        slot.setSelfPosition(new Position(x, y));
        slot.setBackground(output ? GuiTextures.slot() : GuiTextures.slot());
        slot.setCanPutItems(!output);
        slot.setCanTakeItems(true);
        slot.setId(id);
        return slot;
    }

    private static TankWidget createTank(int x, int y, String id) {
        TankWidget tank = new TankWidget();
        tank.initTemplate();
        tank.setSelfPosition(new Position(x, y));
        tank.setBackground(GuiTextures.fluidSlot());
        tank.setFillDirection(ProgressTexture.FillDirection.ALWAYS_FULL);
        tank.setId(id);
        return tank;
    }

    private static LabelWidget createLabel(int x, int y, String id, Supplier<Component> supplier) {
        LabelWidget label = new LabelWidget(x, y, "");
        label.setTextColor(GuiTextures.COLOR_TEXT);
        label.setTextProvider(() -> safeText(supplier));
        label.setId(id);
        return label;
    }

    /** 取值器返回 null 时给空串，免得 LDLib 的 LabelWidget 拿到 null 文本。 */
    private static String safeText(Supplier<Component> supplier) {
        Component component = supplier.get();
        return component == null ? "" : component.getString();
    }

    /**
     * 在 widget 树里按 id 找第一个 widget（递归进子 {@link WidgetGroup}）。
     *
     * <p>不用 LDLib 自带的 {@code WidgetGroup#getFirstWidgetById}，因为它不保证递归 ——
     * 而我们的槽位常常被包在子面板里。
     */
    @Nullable
    public static Widget findById(WidgetGroup root, String id) {
        for (Widget widget : root.widgets) {
            if (id.equals(widget.getId())) {
                return widget;
            }
            if (widget instanceof WidgetGroup group) {
                Widget found = findById(group, id);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    /** 在 widget 树里收集某一类 widget（递归）。 */
    public static <T extends Widget> List<T> findWidgets(WidgetGroup root, Class<T> type) {
        List<T> found = new ArrayList<>();
        collectWidgets(root, type, found);
        return found;
    }

    private static <T extends Widget> void collectWidgets(WidgetGroup root, Class<T> type, List<T> out) {
        for (Widget widget : root.widgets) {
            if (type.isInstance(widget)) {
                out.add(type.cast(widget));
            }
            if (widget instanceof WidgetGroup group) {
                collectWidgets(group, type, out);
            }
        }
    }
}
