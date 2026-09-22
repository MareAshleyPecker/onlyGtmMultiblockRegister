package rain.fox.ogmr.api.gui.editor;

import rain.fox.ogmr.api.machine.MetaMachine;

import com.lowdragmc.lowdraglib.LDLib;
import com.lowdragmc.lowdraglib.gui.editor.configurator.IConfigurableWidget;
import com.lowdragmc.lowdraglib.gui.editor.data.Resources;
import com.lowdragmc.lowdraglib.gui.widget.WidgetGroup;
import com.lowdragmc.lowdraglib.utils.Position;

import lombok.Getter;

import net.minecraft.client.Minecraft;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraftforge.server.ServerLifecycleHooks;

import org.jetbrains.annotations.Nullable;

import java.io.DataInputStream;
import java.io.InputStream;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

/**
 * ogmr 新写 —— 机器 UI 的「默认布局 + 自定义 .mui」双通道装配器。
 *
 * <p>
 * 逐行对照 GTM 的 {@code com.gregtechceu.gtceu.api.gui.editor.EditableMachineUI} 移植，
 * 差异只有两处：
 * <ul>
 * <li>GTCEu 的 {@code GTCEu.isClientSide()} 换成 {@link LDLib#isClient()}，
 * {@code GTCEu.getMinecraftServer()} 换成 {@link ServerLifecycleHooks#getCurrentServer()}，
 * 这样不用为了一个布尔值把 fmlloader 拉进来；</li>
 * <li>泛型参数换成 ogmr 自己的 {@link IEditableUI}（两个泛型参数，而不是 GTM 的三个）。</li>
 * </ul>
 *
 * <p>
 * 读取逻辑（{@link #getCustomUI()}）与 GTM 一致：优先客户端资源管理器，其次集成服务端资源管理器；
 * 两者都拿不到（比如在数据生成阶段）时返回空 tag，即「没有自定义 UI」。
 * 读文件失败也一律吞掉异常并缓存空 tag —— 编辑器写坏的 .mui 不应该让整台机器打不开界面。
 *
 * <p>文件路径约定：{@code assets/<namespace>/ui/machine/<path>.mui}，
 * 其中 {@code <namespace>/<path>} 来自 {@link #getUiPath()}。
 *
 * <p>注意：{@link #getCustomUI()} 的结果会被缓存，编辑器保存后必须调 {@link #reloadCustomUI()} 失效缓存。
 */
public class EditableMachineUI implements IEditableUI<WidgetGroup, MetaMachine> {

    @Getter
    private final String groupName;
    @Getter
    private final ResourceLocation uiPath;
    private final Supplier<WidgetGroup> widgetSupplier;
    private final BiConsumer<WidgetGroup, MetaMachine> binder;

    @Nullable
    private CompoundTag customUICache;

    /**
     * 造出本句柄的 {@link rain.fox.ogmr.api.gui.MachineUI}（反查用）；手工 {@code new} 的为 null。
     *
     * <p>
     * 用途：addon 只写了 {@code .editableUI(ui.buildEditable())} 时，注册期需要靠它把
     * 「这台机器该用哪份 UI」找回来，否则会退回自动生成的零配置界面、把 addon 的布局丢掉。
     */
    @Getter
    @Nullable
    private rain.fox.ogmr.api.gui.MachineUI owner;

    public EditableMachineUI(String groupName, ResourceLocation uiPath, Supplier<WidgetGroup> widgetSupplier,
                             BiConsumer<WidgetGroup, MetaMachine> binder) {
        this.groupName = groupName;
        this.uiPath = uiPath;
        this.widgetSupplier = widgetSupplier;
        this.binder = binder;
    }

    /** 记下造出本句柄的 UI（由 {@code MachineUI#buildEditable()} 调用）。 */
    public EditableMachineUI withOwner(@Nullable rain.fox.ogmr.api.gui.MachineUI owner) {
        this.owner = owner;
        return this;
    }

    /** 便捷静态工厂，等价于 {@code new EditableMachineUI(...)}。 */
    public static EditableMachineUI create(String groupName, ResourceLocation uiPath,
                                           Supplier<WidgetGroup> widgetSupplier,
                                           BiConsumer<WidgetGroup, MetaMachine> binder) {
        return new EditableMachineUI(groupName, uiPath, widgetSupplier, binder);
    }

    /** 默认布局（未绑定数据）。 */
    @Override
    public WidgetGroup createDefault() {
        return widgetSupplier.get();
    }

    /** 只做数据绑定，不碰布局。 */
    @Override
    public void setupUI(WidgetGroup template, MetaMachine machine) {
        binder.accept(template, machine);
    }

    // ═══════════════════════ 自定义 .mui ═══════════════════════

    /**
     * 把自定义 .mui 反序列化成一个 {@link WidgetGroup}；没有自定义 UI 时返回 {@code null}。
     *
     * <p>反序列化用的是 LDLib 编辑器自己的 NBT 格式：{@code root} 子树是根 widget，
     * {@code resources} 子树是贴图/资源表。
     */
    @Nullable
    public WidgetGroup createCustomUI() {
        if (!hasCustomUI()) {
            return null;
        }
        var nbt = getCustomUI();
        var group = new WidgetGroup();
        IConfigurableWidget.deserializeNBT(group, nbt.getCompound("root"),
                Resources.fromNBT(nbt.getCompound("resources")), false);
        group.setSelfPosition(new Position(0, 0));
        return group;
    }

    /** 读取并缓存 {@code .mui} 的 NBT；读不到时返回空 tag（不会返回 null）。 */
    public CompoundTag getCustomUI() {
        if (this.customUICache == null) {
            ResourceManager resourceManager = null;
            if (LDLib.isClient()) {
                resourceManager = Minecraft.getInstance().getResourceManager();
            } else {
                MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
                if (server != null) {
                    resourceManager = server.getResourceManager();
                }
            }
            if (resourceManager == null) {
                this.customUICache = new CompoundTag();
            } else {
                try {
                    var resource = resourceManager.getResourceOrThrow(
                            rain.fox.ogmr.utils.ResourceLocations.withPath(uiPath,
                                    "ui/machine/%s.mui".formatted(uiPath.getPath())));
                    try (InputStream inputStream = resource.open();
                            DataInputStream dataInputStream = new DataInputStream(inputStream)) {
                        this.customUICache = NbtIo.read(dataInputStream, NbtAccounter.UNLIMITED);
                    }
                } catch (Exception e) {
                    this.customUICache = new CompoundTag();
                }
                if (this.customUICache == null) {
                    this.customUICache = new CompoundTag();
                }
            }
        }
        return this.customUICache;
    }

    /** 是否存在自定义 .mui。 */
    public boolean hasCustomUI() {
        return !getCustomUI().isEmpty();
    }

    /** 编辑器保存 .mui 之后必须调用，否则 {@link #getCustomUI()} 还会返回旧缓存。 */
    public void reloadCustomUI() {
        this.customUICache = null;
    }
}
