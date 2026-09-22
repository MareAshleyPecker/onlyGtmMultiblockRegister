package rain.fox.ogmr.api.gui;

import rain.fox.ogmr.api.machine.MetaMachine;

import com.lowdragmc.lowdraglib.gui.widget.WidgetGroup;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * ogmr 新写 —— 仓室（Part）UI 的<b>简化版</b>装配入口。
 *
 * <p>
 * 仓室不需要多方块控制器那一整套（结构预览、成型状态、配方进度、并行数……），
 * 它通常只有「自己的几个槽位/储罐 + 一行状态文本」，所以这里不复用
 * {@link MachineUIWidget} 的右侧信息栏，而是给一个<b>单面板</b>的
 * {@link MachineUI#build(MetaMachine)} 结果。
 *
 * <p>参考 GTM 的 {@code com.gregtechceu.gtceu.api.machine.multiblock.part}
 * 下各仓室自建 UI 的写法（例如 {@code MaintenanceHatchPartMachine#createUIWidget}），
 * 但把「摆槽位」这件事抽成了 {@link #simple(MetaMachine, int, int, int, int)} 的参数。
 *
 * <h2>两种粒度</h2>
 * <ul>
 * <li>{@link #simple(MetaMachine, int, int)} —— 一行状态文本，不带任何槽位。
 * 仓室自己知道有几个槽，覆写 {@link #builder(MetaMachine)} 链式加就行；</li>
 * <li>{@link #simple(MetaMachine, int, int, int, int)} —— 按给定的物品/流体槽数量
 * <b>自动横排</b>（物品在前、流体在后）。</li>
 * </ul>
 *
 * <p>想接玩家背包（很多仓室界面需要），直接自己拿 {@link #builder(MetaMachine)} 再
 * {@code .playerInventory(x, y)} 后 {@code .build(part)}。
 */
public final class PartUI {

    /** 单个槽位的边长（与根贴图一致）。 */
    public static final int SLOT_SIZE = 18;
    /** 面板内边距。 */
    public static final int PADDING = 8;
    /** 标题行高度。 */
    public static final int TITLE_HEIGHT = 12;
    /** 状态文本行高。 */
    public static final int TEXT_LINE_HEIGHT = 10;

    private PartUI() {}

    // ═══════════════════════ 入口 ═══════════════════════

    /**
     * 最简形态：背景 + 标题 + 一行状态文本。
     *
     * <p>状态文本取 {@link MetaMachine#addDisplayText(List)} 的第一行；
     * 仓室没覆写它的话就是空串（不会显示裸键）。
     */
    public static WidgetGroup simple(MetaMachine part, int width, int height) {
        return simple(part, width, height, 0, 0);
    }

    /** 背景 + 标题 + 自动横排的槽位 + 一行状态文本。 */
    public static WidgetGroup simple(MetaMachine part, int width, int height, int itemSlots, int fluidTanks) {
        return simple(part, width, height, itemSlots, fluidTanks, statusSupplier(part));
    }

    /** 全参数版：自己给状态文本的取值器（传 null 表示不要状态文本）。 */
    public static WidgetGroup simple(MetaMachine part, int width, int height, int itemSlots, int fluidTanks,
                                     @Nullable Supplier<Component> status) {
        MachineUI ui = builder(part).size(width, height);
        layoutSlots(ui, itemSlots, fluidTanks);
        if (status != null) {
            ui.text(PADDING, height - PADDING - TEXT_LINE_HEIGHT, status);
        }
        return ui.build(part);
    }

    /**
     * 带右侧信息栏的版本（只有想把 {@code addDisplayText} 的<b>全部</b>行都显示出来时才需要）。
     *
     * <p>返回类型是 {@link MachineUIWidget}，它本身就是 {@link WidgetGroup}。
     */
    public static MachineUIWidget simpleWidget(MetaMachine part, int width, int height) {
        return new MachineUIWidget(part, builder(part).size(width, height));
    }

    /** {@link #simpleWidget(MetaMachine, int, int)} 的带槽位版本。 */
    public static MachineUIWidget simpleWidget(MetaMachine part, int width, int height, int itemSlots,
                                               int fluidTanks) {
        MachineUI ui = builder(part).size(width, height);
        layoutSlots(ui, itemSlots, fluidTanks);
        return new MachineUIWidget(part, ui);
    }

    // ═══════════════════════ 组装件 ═══════════════════════

    /**
     * 一个「上下文正确」的 {@link MachineUI} 骨架（背景 + 标题），供调用方继续链式加东西。
     *
     * <p>uiPath 取 {@code <definition 的命名空间>:part/<definition 的 path>}，
     * 也就是自定义界面放在 {@code assets/<ns>/ui/machine/part/<path>.mui}。
     */
    public static MachineUI builder(MetaMachine part) {
        return builder(part, defaultUiPath(part));
    }

    /** {@link #builder(MetaMachine)} 的自定义 uiPath 版本。 */
    public static MachineUI builder(MetaMachine part, ResourceLocation uiPath) {
        return MachineUI.create("part/" + part.getDefinition().getName(), uiPath)
                .background(GuiTextures.machineBackground())
                .title();
    }

    /** 仓室默认的 {@code .mui} 工程路径。 */
    public static ResourceLocation defaultUiPath(MetaMachine part) {
        // 换路径、保留命名空间 —— 用 helper 而不是手写 new ResourceLocation(ns, "part/" + path)
        return rain.fox.ogmr.utils.ResourceLocations.withPathPrefix(part.getDefinition().getId(), "part/");
    }

    /**
     * 把 {@code itemSlots} 个物品槽与 {@code fluidTanks} 个储罐横排到面板顶部。
     *
     * <p>槽位 index 顺序：物品 0..n-1，流体 0..m-1（与 {@link MachineUI#itemSlot} 的
     * {@code index} 参数一一对应，即「槽位 0 就是 handler 的第 0 格」）。
     */
    public static void layoutSlots(MachineUI ui, int itemSlots, int fluidTanks) {
        int x = PADDING;
        int y = PADDING + TITLE_HEIGHT;
        for (int i = 0; i < itemSlots; i++, x += SLOT_SIZE) {
            ui.itemSlot(x, y, i, false);
        }
        for (int i = 0; i < fluidTanks; i++, x += SLOT_SIZE) {
            ui.fluidTank(x, y, i);
        }
    }

    /**
     * 默认状态文本：{@link MetaMachine#addDisplayText(List)} 的第一行。
     *
     * <p>注意这里每帧都会调一次 {@code addDisplayText}（和 {@link MachineUIWidget} 的信息栏一样），
     * 所以仓室的 {@code addDisplayText} 应该是「读同步字段」这种廉价实现，别在里面扫多方块结构。
     */
    public static Supplier<Component> statusSupplier(MetaMachine part) {
        return () -> {
            List<Component> lines = new ArrayList<>();
            part.addDisplayText(lines);
            return lines.isEmpty() ? Component.empty() : lines.get(0);
        };
    }
}
