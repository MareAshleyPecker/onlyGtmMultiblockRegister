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
import java.util.function.Function;
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
 *                   machine -> machine instanceof MyMachine m ? m.getProgressPercent() : 0d)
 *         .text(8, 60, machine -> Component.literal("..."))
 *         .playerInventory(8, 84);
 * }</pre>
 *
 * <p>
 * 动态文本/进度的取值器都<b>带着机器</b>：一份 {@code MachineUI} 是所有同类机器共用的模板，
 * 装配时才知道具体是哪一台（见 {@link #progress} / {@link #text}）。
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

    /**
     * 是否按机器暴露的仓储<b>自动摆槽位</b>。
     *
     * <p>
     * 只有 {@link #createDefault} 造的「零配置界面」会打开它：装配时按机器实际有的
     * 物品/流体仓储摆出槽位（见 {@link #autoEntries}）。addon 自己链式配了槽位的界面不受影响。
     */
    private boolean autoLayout = false;

    // ═══════════════════════ 自动布局参数 ═══════════════════════

    /** 自动槽位的起始位置（9 个一行，最多两行）。 */
    private static final int AUTO_SLOT_X = 8;
    private static final int AUTO_SLOT_Y = 20;
    /** 自动储罐的起始位置（一行最多 3 个 18×18 小罐）。 */
    private static final int AUTO_TANK_X = 8;
    private static final int AUTO_TANK_Y = 58;
    /** 自动布局最多摆多少个槽位 / 储罐。 */
    private static final int AUTO_MAX_SLOTS = 18;
    private static final int AUTO_MAX_TANKS = 3;

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

    /** 动态文本来源（id + 带机器的取值器）。 */
    public record TextSource(String id, Function<MetaMachine, Component> supplier) {}

    /** 进度条来源（id + 带机器的取值器）。 */
    public record ProgressSource(String id, Function<MetaMachine, Double> supplier) {}

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

    /**
     * 造一个「零配置」界面：标题 + 玩家背包 + 按机器仓储自动摆的槽位。
     *
     * <p>
     * builder 在 addon 没给界面时用它，所以任何机器注册完都能右键打开一个像样的面板，
     * 不需要先写 widget 代码。对没有物品/流体仓储的机器（多方块控制器之类），自动槽位是空的，
     * 面板上还剩标题、右侧信息栏（{@code MetaMachine#addDisplayText} 的内容）与玩家背包。
     *
     * @param groupName LDLib 编辑器分组名（机器名）
     * @param uiPath    UI 工程路径（{@code assets/<ns>/ui/machine/<path>.mui}）
     */
    public static MachineUI createDefault(String groupName, ResourceLocation uiPath) {
        return create(groupName, uiPath)
                .title()
                .playerInventory(8, 84)
                .autoLayout(true);
    }

    // ═══════════════════════ 流式 API ═══════════════════════

    /** 开关自动布局（{@link #createDefault} 已默认打开）。 */
    public MachineUI autoLayout(boolean enabled) {
        this.autoLayout = enabled;
        return this;
    }

    /** 当前是否开了自动布局。 */
    public boolean isAutoLayout() {
        return autoLayout;
    }

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
        entries.add(itemEntry(x, y, index, output));
        return this;
    }

    /** 一个「按槽位下标绑到机器仓储」的物品槽登记项（自动布局也用它，所以单独抽出来）。 */
    private Entry itemEntry(int x, int y, int index, boolean output) {
        String id = idItemSlot(index, output);
        return new Entry(id,
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
                });
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
        entries.add(fluidEntry(x, y, index));
        return this;
    }

    /** 一个「按储罐下标绑到机器仓储」的流体罐登记项（自动布局也用它）。 */
    private Entry fluidEntry(int x, int y, int index) {
        String id = idFluidTank(index);
        return new Entry(id,
                (root, machine) -> root.addWidget(createTank(x, y, id)),
                (widget, machine) -> {
                    if (widget instanceof TankWidget tank) {
                        IFluidTransfer transfer = resolveFluidTransfer(machine, index);
                        if (transfer != null) {
                            tank.setFluidTank(transfer, index);
                        }
                    }
                });
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
     * <p>
     * 取值器<b>带着机器</b>传进来 —— 一份 {@code MachineUI} 是所有同类机器共用的模板，
     * 只能在装配时才知道是哪一台，所以取值器必须能拿到 {@link MetaMachine}：
     * <pre>{@code
     * .progress(62, 33, 24, 16, ProgressDirection.LEFT_TO_RIGHT,
     *           machine -> machine instanceof WorkableMultiblockMachine multi ? multi.getProgress() : 0)
     * }</pre>
     *
     * @param progress 取值器，返回 0..1；每帧读取
     */
    public MachineUI progress(int x, int y, int w, int h, ProgressDirection dir, Function<MetaMachine, Double> progress) {
        String id = idProgress(progressSources.size());
        entries.add(new Entry(id,
                (root, machine) -> root.addWidget(new ProgressWidget(ProgressWidget.JEIProgress, x, y, w, h, texture(dir))
                        .setId(id)),
                (widget, machine) -> {
                    if (widget instanceof ProgressWidget pw) {
                        // LDLib 的 ProgressWidget 收 DoubleSupplier，这里补一层「机器 → 数值」的适配
                        pw.setProgressSupplier(() -> valueOrZero(progress, machine));
                    }
                }));
        progressSources.add(new ProgressSource(id, progress));
        return this;
    }

    private static ProgressTexture texture(ProgressDirection dir) {
        return new ProgressTexture(GuiTextures.progressBarBackground(), GuiTextures.progressBarFilled())
                .setFillDirection(dir.toFillDirection());
    }

    /** 与机器无关的常量式取值器（例如编辑器里做静态预览）；一般机器请用带机器参数的那个重载。 */
    public MachineUI progress(int x, int y, int w, int h, ProgressDirection dir, Supplier<Double> progress) {
        return progress(x, y, w, h, dir, machine -> progress.get());
    }

    /**
     * 加一段动态文本（取值器带机器，理由同 {@link #progress}）。
     *
     * <p>用 {@link LabelWidget#setTextProvider(Supplier)} 挂上取值器，所以服务端会随
     * {@code detectAndSendChanges} 自动同步、客户端每帧重读；{@link MachineUIWidget} 也会
     * 在 {@code updateScreen()} 里再刷一遍，双保险。
     */
    public MachineUI text(int x, int y, Function<MetaMachine, Component> supplier) {
        String id = idText(textSources.size());
        entries.add(new Entry(id,
                (root, machine) -> root.addWidget(createLabel(x, y, id, supplier)),
                (widget, machine) -> {
                    if (widget instanceof LabelWidget label) {
                        label.setTextProvider(() -> plainOrEmpty(supplier.apply(machine)));
                    }
                }));
        textSources.add(new TextSource(id, supplier));
        return this;
    }

    /** 与机器无关的常量式文本。 */
    public MachineUI text(int x, int y, Supplier<Component> supplier) {
        return text(x, y, machine -> supplier.get());
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

        List<Entry> active = activeEntries(machine);
        EditableMachineUI editable = editable();
        WidgetGroup custom = editable.hasCustomUI() ? editable.createCustomUI() : null;
        if (custom != null) {
            // 自定义布局：把 .mui 里的 widget 原样搬进 root，然后只做绑定。
            for (Widget child : new ArrayList<>(custom.widgets)) {
                custom.removeWidget(child);
                root.addWidget(child);
            }
        } else {
            createDefaultLayout(root, active);
        }
        bind(root, machine, active);
    }

    /** 默认布局（不含数据绑定）：给编辑器当模板，或给没有 .mui 的机器当运行时布局。 */
    public WidgetGroup createDefaultTemplate() {
        WidgetGroup root = new WidgetGroup(0, 0, width, height);
        root.setBackground(background);
        createDefaultLayout(root, entries);
        return root;
    }

    private void createDefaultLayout(WidgetGroup root, List<Entry> active) {
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
        for (Entry entry : active) {
            entry.creator().accept(root, null);
        }
    }

    /**
     * 本次装配实际要用的登记项：显式登记的 + （开了自动布局时）按机器仓储自动摆的。
     *
     * <p>自动项是<b>每次装配现算</b>的临时列表，不会写回 {@link #entries}，
     * 所以同一份 {@code MachineUI} 给多台机器用也不会互相污染。
     */
    private List<Entry> activeEntries(@Nullable MetaMachine machine) {
        if (!autoLayout || machine == null) {
            return entries;
        }
        List<Entry> all = new ArrayList<>(entries);
        all.addAll(autoEntries(machine));
        return all;
    }

    /**
     * 按机器<b>实际暴露</b>的仓储自动生成槽位/储罐。
     *
     * <p>仓储从方块实体的 Forge 能力上取，所以任何「用 trait 暴露了物品栏/储罐」的机器
     * 不用写一行 UI 代码就有对应界面；没有仓储（多方块控制器之类）时自动部分为空。
     */
    private List<Entry> autoEntries(MetaMachine machine) {
        List<Entry> auto = new ArrayList<>();

        int slots = Math.min(AUTO_MAX_SLOTS, resolveItemSlotCount(machine));
        for (int i = 0; i < slots; i++) {
            auto.add(itemEntry(AUTO_SLOT_X + (i % 9) * 18, AUTO_SLOT_Y + (i / 9) * 18, i, false));
        }

        int tanks = Math.min(AUTO_MAX_TANKS, resolveFluidTankCount(machine));
        for (int i = 0; i < tanks; i++) {
            auto.add(fluidEntry(AUTO_TANK_X + i * 20, AUTO_TANK_Y, i));
        }
        return auto;
    }

    /** 把机器数据绑进模板（默认布局与自定义 .mui 共用这一条路径）。 */
    public void bind(WidgetGroup root, MetaMachine machine) {
        bind(root, machine, entries);
    }

    /** 绑定指定的一批登记项（自动布局那条路会把「显式 + 自动」合起来传进来）。 */
    public void bind(WidgetGroup root, MetaMachine machine, List<Entry> active) {
        if (withTitle) {
            Widget title = findById(root, ID_TITLE);
            if (title instanceof LabelWidget label) {
                label.setComponent(Component.translatable(machine.getDefinition().getDescriptionId()));
            }
        }
        for (Entry entry : active) {
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
            editableUI = new EditableMachineUI(groupName, uiPath, this::createDefaultTemplate, this::bind)
                    .withOwner(this);
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
        IItemHandler handler = itemHandlerOf(machine);
        if (handler == null || index < 0 || index >= handler.getSlots()) {
            return null;
        }
        return ItemTransferHelperImpl.toItemTransfer(handler);
    }

    /** 从机器所在方块实体上取流体能力并转成 LDLib 的 {@link IFluidTransfer}。 */
    @Nullable
    public static IFluidTransfer resolveFluidTransfer(MetaMachine machine, int index) {
        IFluidHandler handler = fluidHandlerOf(machine);
        if (handler == null || index < 0 || index >= handler.getTanks()) {
            return null;
        }
        return new FluidTransferWrapper(handler);
    }

    /** 机器暴露的物品槽数量（没有物品能力时 0）。自动布局用它决定摆几个槽位。 */
    public static int resolveItemSlotCount(MetaMachine machine) {
        IItemHandler handler = itemHandlerOf(machine);
        return handler == null ? 0 : handler.getSlots();
    }

    /** 机器暴露的储罐数量（没有流体能力时 0）。 */
    public static int resolveFluidTankCount(MetaMachine machine) {
        IFluidHandler handler = fluidHandlerOf(machine);
        return handler == null ? 0 : handler.getTanks();
    }

    @Nullable
    private static IItemHandler itemHandlerOf(MetaMachine machine) {
        BlockEntity blockEntity = blockEntityOf(machine);
        return blockEntity == null ? null
                : blockEntity.getCapability(ForgeCapabilities.ITEM_HANDLER, null).resolve().orElse(null);
    }

    @Nullable
    private static IFluidHandler fluidHandlerOf(MetaMachine machine) {
        BlockEntity blockEntity = blockEntityOf(machine);
        return blockEntity == null ? null
                : blockEntity.getCapability(ForgeCapabilities.FLUID_HANDLER, null).resolve().orElse(null);
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

    private static LabelWidget createLabel(int x, int y, String id, Function<MetaMachine, Component> supplier) {
        LabelWidget label = new LabelWidget(x, y, "");
        label.setTextColor(GuiTextures.COLOR_TEXT);
        label.setTextProvider(() -> plainOrEmpty(supplier.apply(null)));
        label.setId(id);
        return label;
    }

    /** 取值器返回 null 时给空串，免得 LDLib 的 LabelWidget 拿到 null 文本。 */
    private static String plainOrEmpty(@Nullable Component component) {
        return component == null ? "" : component.getString();
    }

    /** 进度取值器返回 null 时按 0 处理。 */
    private static double valueOrZero(Function<MetaMachine, Double> supplier, MetaMachine machine) {
        Double value = supplier.apply(machine);
        return value == null ? 0d : value;
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
