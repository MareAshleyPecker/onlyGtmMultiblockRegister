package rain.fox.ogmr.api.gui;

import com.lowdragmc.lowdraglib.gui.texture.ColorBorderTexture;
import com.lowdragmc.lowdraglib.gui.texture.ColorRectTexture;
import com.lowdragmc.lowdraglib.gui.texture.GuiTextureGroup;
import com.lowdragmc.lowdraglib.gui.texture.IGuiTexture;
import com.lowdragmc.lowdraglib.gui.texture.ResourceBorderTexture;
import com.lowdragmc.lowdraglib.gui.texture.ResourceTexture;
import com.lowdragmc.lowdraglib.gui.widget.SlotWidget;
import com.lowdragmc.lowdraglib.gui.widget.TankWidget;

import org.jetbrains.annotations.Nullable;

/**
 * ogmr 新写 —— 本库 GUI 层用到的全部默认贴图常量。
 *
 * <p>
 * 参考 GTM 的 {@code com.gregtechceu.gtceu.api.gui.GuiTextures}，但只保留「机器 UI / rtui」这一小块，
 * 并且额外解决一个 GTM 不需要解决的问题：<b>ogmr 是一个独立库，理论上可以一张美术资源都没有。</b>
 * 所以本类提供两套并存的表现：
 * <ul>
 * <li><b>资源贴图</b>：{@code new ResourceTexture("ogmr:textures/gui/...")} / {@code ResourceBorderTexture}
 * （九宫格）。addon 把对应的 png 放进自己或本库的 {@code assets/<ns>/textures/gui/...} 就能看到美术图；</li>
 * <li><b>纯色描边兜底</b>：{@link #fallback(int, int, int)} 用 {@code ColorRectTexture} 铺底 +
 * {@code ColorBorderTexture} 描边拼出来，完全不需要任何 png。</li>
 * </ul>
 *
 * <p>
 * 两者通过 {@link #setForceFallback(boolean)} 切换：默认返回资源贴图；当 addon 没有任何美术资源时，
 * 在客户端初始化阶段调一次 {@code GuiTextures.setForceFallback(true)}，此后所有 {@code xxx()} 访问器
 * 都返回兜底贴图。注意<b>常量</b>（{@code BACKGROUND} 等）永远是资源贴图，不受开关影响 —— 这样
 * addon 也能显式地写 {@code .background(GuiTextures.BACKGROUND)} 强制用美术图。
 *
 * <p>
 * 另外附赠两个「零美术资源也能好看」的现成贴图：{@link #LDLIB_SLOT} / {@link #LDLIB_FLUID_SLOT}，
 * 直接复用 LDLib 自带的槽位贴图（LDLib 一定装了，所以一定存在）。
 */
public final class GuiTextures {

    /** 本库 GUI 贴图的资源根路径（不含 {@code .png} 后缀）。 */
    public static final String ROOT = "ogmr:textures/gui/";

    private GuiTextures() {}

    // ═══════════════════════ 颜色常量（ARGB） ═══════════════════════

    /** 机器/rtui 面板底色。 */
    public static final int COLOR_PANEL_FILL = 0xFF3B3B3B;
    /** 机器/rtui 面板描边色。 */
    public static final int COLOR_PANEL_BORDER = 0xFF9A9A9A;
    /** 物品槽底色。 */
    public static final int COLOR_SLOT_FILL = 0xFF1E1E1E;
    /** 物品槽描边色。 */
    public static final int COLOR_SLOT_BORDER = 0xFF8B8B8B;
    /** 流体槽底色。 */
    public static final int COLOR_FLUID_SLOT_FILL = 0xFF17242C;
    /** 流体槽描边色。 */
    public static final int COLOR_FLUID_SLOT_BORDER = 0xFF4A7A9A;
    /** 进度条底槽色。 */
    public static final int COLOR_PROGRESS_EMPTY = 0xFF1E1E1E;
    /** 进度条底槽描边色。 */
    public static final int COLOR_PROGRESS_EMPTY_BORDER = 0xFF6A6A6A;
    /** 进度条填充色。 */
    public static final int COLOR_PROGRESS_FILLED = 0xFF3F8F3F;
    /** 信息栏（右侧文本面板）底色。 */
    public static final int COLOR_INFO_FILL = 0xC0101010;
    /** 信息栏描边色。 */
    public static final int COLOR_INFO_BORDER = 0xFF5A5A5A;
    /** 文本默认色（不要用纯黑，深色底上看不见）。 */
    public static final int COLOR_TEXT = 0xFFE0E0E0;
    /** 标题文本色。 */
    public static final int COLOR_TEXT_TITLE = 0xFFFFFFFF;

    // ═══════════════════════ 资源贴图常量 ═══════════════════════

    /** 机器 UI 背景（九宫格，4px 边）。 */
    public static final ResourceBorderTexture BACKGROUND = new ResourceBorderTexture(
            ROOT + "base/background.png", 16, 16, 4, 4);

    /** 信息栏/内嵌面板背景（九宫格）。 */
    public static final ResourceBorderTexture INFO_BACKGROUND = new ResourceBorderTexture(
            ROOT + "base/info_background.png", 16, 16, 2, 2);

    /** 物品槽（九宫格，1px 边）。 */
    public static final ResourceBorderTexture SLOT = new ResourceBorderTexture(
            ROOT + "base/slot.png", 18, 18, 1, 1);

    /** 流体槽（九宫格，1px 边）。 */
    public static final ResourceBorderTexture FLUID_SLOT = new ResourceBorderTexture(
            ROOT + "base/fluid_slot.png", 18, 18, 1, 1);

    /** 进度条底槽（整图拉伸）。 */
    public static final ResourceTexture PROGRESS_BAR_BACKGROUND = new ResourceTexture(
            ROOT + "progress/bar_background.png");

    /** 进度条填充（整图拉伸）。 */
    public static final ResourceTexture PROGRESS_BAR_FILLED = new ResourceTexture(
            ROOT + "progress/bar_filled.png");

    /** rtui 默认背景（九宫格）。 */
    public static final ResourceBorderTexture RECIPE_TYPE_BASE = new ResourceBorderTexture(
            ROOT + "recipe_type/base.png", 16, 16, 4, 4);

    /** rtui 里的槽位底图（九宫格，1px 边）。 */
    public static final ResourceBorderTexture RECIPE_TYPE_SLOT = new ResourceBorderTexture(
            ROOT + "recipe_type/slot.png", 18, 18, 1, 1);

    // ═══════════════════════ LDLib 自带贴图（零美术资源时的现成品） ═══════════════════════

    /** LDLib 自带的物品槽贴图 —— LDLib 一定装了，所以这张一定存在。 */
    public static final ResourceBorderTexture LDLIB_SLOT = SlotWidget.ITEM_SLOT_TEXTURE;

    /** LDLib 自带的流体槽贴图。 */
    public static final ResourceBorderTexture LDLIB_FLUID_SLOT = TankWidget.FLUID_SLOT_TEXTURE;

    // ═══════════════════════ 兜底开关 ═══════════════════════

    private static volatile boolean forceFallback = false;

    /**
     * 切换「纯色描边兜底」模式 —— 库没有任何美术资源时在客户端初始化里调一次即可。
     *
     * <p>默认 {@code false}（用 {@code ogmr:textures/gui/...} 的资源贴图）。
     */
    public static void setForceFallback(boolean force) {
        forceFallback = force;
    }

    public static boolean isForceFallback() {
        return forceFallback;
    }

    // ═══════════════════════ 兜底贴图工厂 ═══════════════════════

    /**
     * 生成一张「纯色铺底 + 描边」的贴图，完全不依赖任何 png。
     *
     * <p>实现是 {@code GuiTextureGroup(ColorRectTexture(fill), ColorBorderTexture(border, borderColor))}：
     * 先铺一层实心色，再在最外圈画 {@code border} 像素宽的边框。
     *
     * @param fillColor   底色（ARGB）
     * @param borderColor 描边色（ARGB）
     * @param borderWidth 描边宽度（像素，{@code <= 0} 表示不描边）
     */
    public static IGuiTexture fallback(int fillColor, int borderColor, int borderWidth) {
        var rect = new ColorRectTexture(fillColor);
        if (borderWidth <= 0) {
            return rect;
        }
        // 注意 LDLib 的 ColorBorderTexture 构造是 (border, color)：第一个参数是宽度，第二个是颜色。
        return new GuiTextureGroup(rect, new ColorBorderTexture(borderWidth, borderColor));
    }

    /** {@link #fallback(int, int, int)} 的 1px 描边版本。 */
    public static IGuiTexture fallback(int fillColor, int borderColor) {
        return fallback(fillColor, borderColor, 1);
    }

    /** {@link #fallback(int, int, int)} 的无描边版本。 */
    public static IGuiTexture fallback(int fillColor) {
        return fallback(fillColor, 0, 0);
    }

    // ═══════════════════════ 访问器（受兜底开关影响） ═══════════════════════

    /** 机器 UI 背景。 */
    public static IGuiTexture machineBackground() {
        return forceFallback ? fallback(COLOR_PANEL_FILL, COLOR_PANEL_BORDER) : BACKGROUND;
    }

    /** rtui 背景。 */
    public static IGuiTexture recipeTypeBackground() {
        return forceFallback ? fallback(COLOR_PANEL_FILL, COLOR_PANEL_BORDER) : RECIPE_TYPE_BASE;
    }

    /** 信息栏背景。 */
    public static IGuiTexture infoBackground() {
        return forceFallback ? fallback(COLOR_INFO_FILL, COLOR_INFO_BORDER) : INFO_BACKGROUND;
    }

    /** 物品槽背景。 */
    public static IGuiTexture slot() {
        return forceFallback ? fallback(COLOR_SLOT_FILL, COLOR_SLOT_BORDER) : SLOT;
    }

    /** 流体槽背景。 */
    public static IGuiTexture fluidSlot() {
        return forceFallback ? fallback(COLOR_FLUID_SLOT_FILL, COLOR_FLUID_SLOT_BORDER) : FLUID_SLOT;
    }

    /** rtui 里的物品槽背景。 */
    public static IGuiTexture recipeTypeSlot() {
        return forceFallback ? fallback(COLOR_SLOT_FILL, COLOR_SLOT_BORDER) : RECIPE_TYPE_SLOT;
    }

    /** 进度条底槽。 */
    public static IGuiTexture progressBarBackground() {
        return forceFallback ? fallback(COLOR_PROGRESS_EMPTY, COLOR_PROGRESS_EMPTY_BORDER) : PROGRESS_BAR_BACKGROUND;
    }

    /** 进度条填充。 */
    public static IGuiTexture progressBarFilled() {
        return forceFallback ? fallback(COLOR_PROGRESS_FILLED) : PROGRESS_BAR_FILLED;
    }

    /**
     * 允许 addon 用「强制资源贴图」的方式绕过兜底开关，取到某张具名贴图。
     *
     * @param texture 资源贴图常量，可为 null
     * @param orElse  为 null 时返回的备选贴图
     */
    public static IGuiTexture orFallback(@Nullable IGuiTexture texture, IGuiTexture orElse) {
        return texture != null ? texture : orElse;
    }
}
