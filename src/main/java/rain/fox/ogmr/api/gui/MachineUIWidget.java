package rain.fox.ogmr.api.gui;

import rain.fox.ogmr.api.machine.MetaMachine;

import com.lowdragmc.lowdraglib.gui.modular.ModularUI;
import com.lowdragmc.lowdraglib.gui.widget.ComponentPanelWidget;
import com.lowdragmc.lowdraglib.gui.widget.LabelWidget;
import com.lowdragmc.lowdraglib.gui.widget.ProgressWidget;
import com.lowdragmc.lowdraglib.gui.widget.Widget;
import com.lowdragmc.lowdraglib.gui.widget.WidgetGroup;
import com.lowdragmc.lowdraglib.gui.widget.custom.PlayerInventoryWidget;

import lombok.Getter;

import net.minecraft.network.chat.Component;

import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * ogmr 新写 —— 把 {@link MachineUI} 装出来的 WidgetGroup 包成 LDLib 的机器界面 widget。
 *
 * <p>
 * 参照 GTM 的 {@code com.gregtechceu.gtceu.api.gui.fancy.FancyMachineUIWidget} 的「容器 widget」
 * 思路，但<b>刻意做得比 Fancy 版简单</b>：Fancy 版有一整套页面导航/侧边标签/标题栏，
 * 本库只要「一个 root + 一个右侧信息栏 + 每帧刷新」，所以这里直接继承 {@link WidgetGroup}
 * 并把 {@link MachineUI#build(MetaMachine, WidgetGroup)} 装进自己。
 *
 * <p>本类负责三件事：
 * <ol>
 * <li><b>装配</b>：构造时把 {@code MachineUI} 的登记项装进自己（自身就是 root，不做二次拷贝）；</li>
 * <li><b>绑定玩家</b>：{@link #initWidget()} 里把 {@code gui.entityPlayer} 灌给
 * {@link PlayerInventoryWidget}（LDLib 的背包 widget 不会自己找玩家，GTM 也是在 initWidget 里塞的）；</li>
 * <li><b>每帧刷新</b>：{@link #updateScreen()} 重设动态文本与进度条的取值器，
 * 并把 {@link MetaMachine#addDisplayText(List)} 的行渲染到右侧 {@link ComponentPanelWidget} 信息栏。</li>
 * </ol>
 *
 * <h2>信息栏位置</h2>
 * <p>默认贴在主面板右侧外侧（{@code x = width + 4, y = 4}），和 GTM 的 Fancy 信息栏一个思路 ——
 * 这样加机器时不用为了几行状态文本把主面板撑宽。想放到别处用
 * {@link #setInfoPanelLayout(int, int, int)}，或者 {@link #setInfoPanelVisible(boolean)} 关掉。
 *
 * <h2>典型用法</h2>
 * <pre>{@code
 * new ModularUI(ui.getWidth(), ui.getHeight(), holder, player)
 *         .widget(new MachineUIWidget(machine, ui));
 * }</pre>
 */
public class MachineUIWidget extends WidgetGroup {

    @Getter
    protected final MetaMachine machine;
    @Getter
    protected final MachineUI ui;

    /** 每帧要重跑一次的刷新动作（重设文本/进度取值器）。 */
    private final List<Runnable> refreshers = new ArrayList<>();

    /** 右侧信息栏；只在自己构建的 UI 上有。 */
    @Getter
    @Nullable
    protected final ComponentPanelWidget infoPanel;

    protected int infoPanelOffsetX = 4;
    protected int infoPanelOffsetY = 4;
    protected int infoPanelWidth = 90;
    protected boolean infoPanelVisible = true;

    public MachineUIWidget(MetaMachine machine, MachineUI ui) {
        super(0, 0, ui.getWidth(), ui.getHeight());
        this.machine = machine;
        this.ui = ui;

        // 直接把登记项装进自己（build 的 root 版本），避免一次多余的 widget 树拷贝。
        ui.build(machine, this);

        // 收集需要每帧刷新的 widget。build() 之后树就定下来了，这里一次性按 id 找到并捕获引用，
        // 免得每帧都去递归找 widget。
        for (MachineUI.TextSource source : ui.textSources()) {
            Widget widget = MachineUI.findById(this, source.id());
            if (widget instanceof LabelWidget label) {
                refreshers.add(() -> label.setTextProvider(() -> plainText(source.supplier().get())));
            }
        }
        for (MachineUI.ProgressSource source : ui.progressSources()) {
            Widget widget = MachineUI.findById(this, source.id());
            if (widget instanceof ProgressWidget progress) {
                // 适配 Supplier<Double> -> DoubleSupplier
                refreshers.add(() -> progress.setProgressSupplier(() -> source.supplier().get()));
            }
        }

        if (this.infoPanelVisible) {
            this.infoPanel = new ComponentPanelWidget(getSizeWidth() + infoPanelOffsetX, infoPanelOffsetY,
                    this::collectDisplayText)
                    .setMaxWidthLimit(infoPanelWidth);
            this.infoPanel.setId(MachineUI.ID_INFO);
            addWidget(this.infoPanel);
        } else {
            this.infoPanel = null;
        }
    }

    // ═══════════════════════ 生命周期 ═══════════════════════

    @Override
    public void initWidget() {
        super.initWidget();
        ModularUI gui = getGui();
        if (gui == null || gui.entityPlayer == null) {
            return;
        }
        for (PlayerInventoryWidget inventory : MachineUI.findWidgets(this, PlayerInventoryWidget.class)) {
            inventory.setPlayer(gui.entityPlayer);
        }
    }

    /**
     * 每帧刷新动态文本与进度条。
     *
     * <p>
     * LDLib 自己也会经由 {@code detectAndSendChanges}/{@code readUpdateInfo} 同步这两类数据，
     * 这里再刷一遍是为了<b>不依赖 LDLib 的同步链路</b>：只要 widget 树是本地建的（哪怕是纯客户端
     * 预览界面），文本和进度依然是活的。
     */
    @Override
    public void updateScreen() {
        super.updateScreen();
        for (Runnable refresher : refreshers) {
            refresher.run();
        }
    }

    // ═══════════════════════ 信息栏 ═══════════════════════

    /** 把 {@code machine.addDisplayText(...)} 的行灌进信息栏（ComponentPanelWidget 的取值回调）。 */
    protected void collectDisplayText(List<Component> textList) {
        machine.addDisplayText(textList);
    }

    /** 调整信息栏的位置与最大宽度。 */
    public MachineUIWidget setInfoPanelLayout(int offsetX, int offsetY, int maxWidth) {
        this.infoPanelOffsetX = offsetX;
        this.infoPanelOffsetY = offsetY;
        this.infoPanelWidth = maxWidth;
        if (infoPanel != null) {
            infoPanel.setSelfPosition(new com.lowdragmc.lowdraglib.utils.Position(
                    getSizeWidth() + offsetX, offsetY));
            infoPanel.setMaxWidthLimit(maxWidth);
        }
        return this;
    }

    /**
     * 开关信息栏。
     *
     * <p>注意：构造之后调用只能隐藏，不能凭空造出信息栏（widget 树已经装完了）——
     * 想彻底不带信息栏，请在构造前决定；这里提供的是「运行期临时收起」。
     */
    public MachineUIWidget setInfoPanelVisible(boolean visible) {
        this.infoPanelVisible = visible;
        if (infoPanel != null) {
            infoPanel.setVisible(visible);
            infoPanel.setActive(visible);
        }
        return this;
    }

    private static String plainText(@Nullable Component component) {
        return component == null ? "" : component.getString();
    }
}
