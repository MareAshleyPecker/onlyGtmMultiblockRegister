package rain.fox.ogmr.api.recipe.ui;

import rain.fox.ogmr.Ogmr;
import rain.fox.ogmr.api.recipe.OGMRRecipeType;

import com.lowdragmc.lowdraglib.LDLib;
import com.lowdragmc.lowdraglib.gui.widget.WidgetGroup;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.world.item.ItemStack;

import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;

import org.jetbrains.annotations.Nullable;

import java.io.DataInputStream;
import java.io.InputStream;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * 配方类型 UI 的 <b>数据侧</b> —— 尺寸、图标、以及 {@code .rtui} 自定义界面的懒加载缓存。
 *
 * <p>
 * 从 GTM 的 {@code api.recipe.ui.GTRecipeTypeUI} + {@code api.gui.editor.EditableMachineUI}
 * 拆出来的「数据与登记」这一半：
 * <ul>
 * <li>{@link #uiPath} / {@link #width} / {@link #height} / {@link #icon} —— 绘制所需的元数据；</li>
 * <li>{@link #getCustomUI()} —— 懒加载
 * {@code assets/<namespace>/ui/recipe_type/<path>.rtui}（LDLib 编辑器存出来的 NBT），
 * 读不到就当作「没有自定义 UI」，逻辑照 {@code EditableMachineUI#getCustomUI} 写；</li>
 * <li>{@link #reloadCustomUI()} —— 资源重载后清缓存。</li>
 * </ul>
 *
 * <p>
 * <b>本类刻意不含任何 widget 代码</b>：真正的绘制/组装由 {@code rain.fox.ogmr.api.gui} 层实现，
 * 那边拿到这里的数据后自行装配 widget，两边不互相踩。
 */
@Accessors(chain = true)
public class RecipeTypeUI {

    /** 自定义界面在资源包里的路径格式（{@code assets/<ns>/ui/recipe_type/<path>.rtui}）。 */
    public static final String UI_PATH_FORMAT = "ui/recipe_type/%s.rtui";

    /** 默认宽高，与 GTM/原版机器界面一致。 */
    public static final int DEFAULT_WIDTH = 176;
    public static final int DEFAULT_HEIGHT = 166;

    @Nullable
    @Getter
    private final OGMRRecipeType recipeType;
    /** UI 路径的「命名空间 + 路径」部分，决定 {@code .rtui} 落在哪个资源包里。 */
    @Getter
    private ResourceLocation uiPath;
    @Getter
    @Setter
    private int width = DEFAULT_WIDTH;
    @Getter
    @Setter
    private int height = DEFAULT_HEIGHT;
    /** 图标供应器（动态读配方类型的图标，除非被 {@link #setIcon} 覆盖）。 */
    @Getter
    private Supplier<ItemStack> icon;
    /** 懒加载的 .rtui 内容；null = 还没读过，空 tag = 读失败/不存在（不再重试）。 */
    @Nullable
    private CompoundTag customUICache;

    public RecipeTypeUI(@Nullable OGMRRecipeType recipeType) {
        this.recipeType = recipeType;
        // 默认与配方类型同名：ogmr:xxx 类型 -> 找 assets/ogmr/ui/recipe_type/xxx.rtui
        this.uiPath = recipeType == null ?
                Ogmr.id("unknown") :
                Ogmr.id(recipeType.getRegistryName().getPath());
        this.icon = recipeType == null ? () -> ItemStack.EMPTY : recipeType::getIcon;
    }

    // ═══════════════ 元数据 ═══════════════

    public RecipeTypeUI setUiPath(ResourceLocation uiPath) {
        this.uiPath = uiPath;
        reloadCustomUI();
        return this;
    }

    /** 自定义界面在资源包里的完整路径（{@code assets/} 之后那一段）。 */
    public ResourceLocation getFullUiPath() {
        return rain.fox.ogmr.utils.ResourceLocations.withPath(uiPath, UI_PATH_FORMAT.formatted(uiPath.getPath()));
    }

    public RecipeTypeUI setSize(int width, int height) {
        this.width = width;
        this.height = height;
        return this;
    }

    public RecipeTypeUI setIcon(@Nullable Supplier<ItemStack> icon) {
        this.icon = icon == null ? () -> ItemStack.EMPTY : icon;
        return this;
    }

    // ═══════════════ .rtui 自定义界面 ═══════════════

    /** 清掉缓存，下次 {@link #getCustomUI()} 会重新从资源包读（资源重载时调用）。 */
    public void reloadCustomUI() {
        this.customUICache = null;
    }

    /**
     * 自定义界面的 NBT 内容（永不为 null；读不到时返回空 tag）。
     *
     * <p>
     * 只读一次并缓存：不存在/解析失败都会缓存成空 tag，避免每帧都去戳资源管理器。
     */
    public CompoundTag getCustomUI() {
        if (customUICache == null) {
            customUICache = loadCustomUI();
        }
        return customUICache;
    }

    /** 是否有可用的自定义界面。 */
    public boolean hasCustomUI() {
        return !getCustomUI().isEmpty();
    }

    private CompoundTag loadCustomUI() {
        // 专用服务器上没有资源包，也没有 Minecraft 类，直接当作「没有自定义 UI」
        if (!LDLib.isClient()) {
            return new CompoundTag();
        }
        try {
            return ClientLoader.load(getFullUiPath());
        } catch (Throwable e) {
            Ogmr.LOGGER.debug("ogmr: failed to read custom recipe type UI {}: {}", getFullUiPath(), e.toString());
            return new CompoundTag();
        }
    }

    /**
     * 真正碰客户端的部分单独放一个类里 —— 专用服务器上只要不调用它就永远不会加载
     * {@code Minecraft} 这个客户端类（GTM 的 {@code EditableMachineUI} 也是这个路子）。
     */
    private static final class ClientLoader {

        private ClientLoader() {}

        static CompoundTag load(ResourceLocation fullPath) throws Exception {
            ResourceManager resourceManager = net.minecraft.client.Minecraft.getInstance().getResourceManager();
            Optional<Resource> resource = resourceManager.getResource(fullPath);
            if (resource.isEmpty()) {
                Ogmr.LOGGER.debug("ogmr: no custom recipe type UI at {}, using default layout", fullPath);
                return new CompoundTag();
            }
            try (InputStream inputStream = resource.get().open();
                    DataInputStream dataInputStream = new DataInputStream(inputStream)) {
                CompoundTag tag = NbtIo.read(dataInputStream, NbtAccounter.UNLIMITED);
                return tag == null ? new CompoundTag() : tag;
            }
        }
    }

    // ═══════════════ 占位 ═══════════════

    /**
     * 默认界面布局的 widget 占位。
     *
     * <p>
     * TODO: 由 {@code rain.fox.ogmr.api.gui} 层实现 —— 那边按 {@link #getWidth()}/{@link #getHeight()}
     * 组装背景、槽位、进度条，并在 {@link #hasCustomUI()} 为 true 时改用 {@link #getCustomUI()}
     * 反序列化出来的布局。本类只提供数据，故此处恒返回 null。
     */
    @Nullable
    public WidgetGroup createDefaultWidget() {
        return null;
    }

    @Override
    public String toString() {
        return "RecipeTypeUI[" + uiPath + ", " + width + "x" + height + ", custom=" + hasCustomUI() + "]";
    }
}
