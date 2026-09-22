package rain.fox.ogmr.api.gui;

import rain.fox.ogmr.Ogmr;
import rain.fox.ogmr.api.recipe.OGMRRecipeType;
import rain.fox.ogmr.api.recipe.ui.RecipeTypeUI;
import rain.fox.ogmr.api.registry.OGMRRegistries;

import com.lowdragmc.lowdraglib.gui.editor.configurator.IConfigurableWidget;
import com.lowdragmc.lowdraglib.gui.editor.data.Resources;
import com.lowdragmc.lowdraglib.gui.texture.ProgressTexture;
import com.lowdragmc.lowdraglib.gui.widget.LabelWidget;
import com.lowdragmc.lowdraglib.gui.widget.ProgressWidget;
import com.lowdragmc.lowdraglib.gui.widget.SlotWidget;
import com.lowdragmc.lowdraglib.gui.widget.TankWidget;
import com.lowdragmc.lowdraglib.gui.widget.Widget;
import com.lowdragmc.lowdraglib.gui.widget.WidgetGroup;
import com.lowdragmc.lowdraglib.utils.Position;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * ogmr 新写 —— 配方类型 UI（rtui）的<b>绘制层</b>。
 *
 * <p>
 * 对应 GTM 的 {@code com.gregtechceu.gtceu.api.recipe.ui.GTRecipeTypeUI#createEditableUITemplate}
 * 里那段「摆槽位 + 摆进度条」的代码，但把「数据侧」和「绘制侧」彻底分开了：
 * 数据（尺寸、图标、{@code .rtui} 缓存）在 {@link RecipeTypeUI} 里，本类只负责把 widget 摆出来。
 *
 * <h2>默认布局</h2>
 * <p>照 GTM 默认 rtui 的排布思路（输入横排在左、输出横排在右、中间夹一个进度条），
 * 但按本库的规格简化为<b>单行</b>：
 *
 * <pre>
 * ┌──────────────────────────────────────────────┐
 * │ [标题 ogmr.xxx]                              │
 * │ [物][物][流][流]  [进度条]  [物][物][流][流] │
 * │                  EU/t: ...                   │
 * │                  Time: ...                   │
 * └──────────────────────────────────────────────┘
 * </pre>
 *
 * <p>槽位数量来自 {@link OGMRRecipeType#getMaxItemInputs()} 等四个上限；物品槽排在流体槽前面。
 * 槽位是<b>空壳</b>（{@code new SlotWidget()} / {@code new TankWidget()} 占位，与 GTM 的
 * {@code addSlot} 一致）—— rtui 是「展示配方」用的，真正的数据由 XEI/配方页绑定，
 * 这里只把位置和 id 定死，绑定时按 id 找回 widget 即可。
 *
 * <h2>自定义 .rtui</h2>
 * <p>{@link #createDefaultWidget(OGMRRecipeType)} 会先问 {@link RecipeTypeUI#hasCustomUI()}：
 * 有就 {@link #loadCustom(RecipeTypeUI)} 反序列化（读法照 GTM 的
 * {@code RecipeTypeUIProject}：{@code root} + {@code resources} 两棵子树），没有才走默认布局。
 *
 * <h2>怎么接上 {@code RecipeTypeUI#createDefaultWidget()}</h2>
 * <p>{@link RecipeTypeUI#createDefaultWidget()} 是数据侧留下的 TODO 占位（恒返回 null），
 * <b>本类不会去改那个文件</b>。而当前 LDLib / 本库的 {@code RecipeTypeUI} 上<b>没有</b>
 * {@code setWidgetFactory(...)} 方法，所以按备选方案实现：
 * <pre>{@code
 * // addon 的 registerUI()（或任何「配方类型都注册完、客户端起来之后」的阶段）里调一次：
 * RecipeTypeUIWidgets.install();
 * }</pre>
 * {@link #install()} 会把「默认工厂」以及当前已登记的每个 {@link OGMRRecipeType} 的工厂
 * 塞进本类的静态表；之后外部（配方页、编辑器模板树、机器 UI 的 rtui 页）统一走
 * {@link #create(OGMRRecipeType)} 取 widget，就能做到「addon 想换布局只换一处」。
 * 也可以用 {@link #setWidgetFactory(ResourceLocation, Function)} / {@link #setDefaultWidgetFactory(Function)}
 * 单独覆盖某个类型或全局默认。
 */
public final class RecipeTypeUIWidgets {

    // ═══════════════════════ widget id 约定 ═══════════════════════

    /** 标题 LabelWidget 的 id。 */
    public static final String ID_TITLE = "title";
    /** 进度条 ProgressWidget 的 id。 */
    public static final String ID_PROGRESS = "progress";
    /** 「EU/t」文本 LabelWidget 的 id。 */
    public static final String ID_EU_T = "eu_t";
    /** 「时长」文本 LabelWidget 的 id。 */
    public static final String ID_DURATION = "duration";

    /** 输入物品槽 id。 */
    public static String idItemInput(int index) {
        return "item_in_" + index;
    }

    /** 输出物品槽 id。 */
    public static String idItemOutput(int index) {
        return "item_out_" + index;
    }

    /** 输入流体槽 id。 */
    public static String idFluidInput(int index) {
        return "fluid_in_" + index;
    }

    /** 输出流体槽 id。 */
    public static String idFluidOutput(int index) {
        return "fluid_out_" + index;
    }

    // ═══════════════════════ 布局常量 ═══════════════════════

    public static final int PADDING = 4;
    public static final int TITLE_HEIGHT = 10;
    public static final int SLOT_SIZE = 18;
    /** 槽位与进度条之间的水平间距。 */
    public static final int GAP = 8;
    public static final int PROGRESS_WIDTH = 24;
    public static final int PROGRESS_HEIGHT = 16;
    public static final int TEXT_LINE_HEIGHT = 10;
    /** 槽位行与下方文本行的间距。 */
    public static final int TEXT_GAP = 4;

    // ═══════════════════════ 静态工厂表 ═══════════════════════

    /** {@code 配方类型 id -> 工厂}；{@link #install()} 填默认值，外部可覆盖。 */
    private static final Map<ResourceLocation, Function<OGMRRecipeType, WidgetGroup>> FACTORIES = new ConcurrentHashMap<>();

    /** 全局默认工厂；null 表示用 {@link #createDefaultWidget(OGMRRecipeType)}。 */
    @Nullable
    private static volatile Function<OGMRRecipeType, WidgetGroup> defaultFactory;

    private RecipeTypeUIWidgets() {}

    /**
     * 把本类的默认工厂接上。
     *
     * <p>应在 addon 的 {@code registerUI()} 阶段（或任何「配方类型注册完毕、GUI 还没被打开」的阶段）
     * 调用一次。重复调用是幂等的：只会补齐缺失的项，不会覆盖 addon 已经
     * {@link #setWidgetFactory(ResourceLocation, Function)} 过的类型。
     *
     * <p>之所以需要这个方法：{@link RecipeTypeUI} 上没有 {@code setWidgetFactory(...)}，
     * 所以只能由本类维持一份「配方类型 → 工厂」的静态表供外部取用。
     */
    public static void install() {
        if (defaultFactory == null) {
            defaultFactory = RecipeTypeUIWidgets::createDefaultWidget;
        }
        for (OGMRRecipeType type : OGMRRegistries.RECIPE_TYPES) {
            if (type != null && type.registryName != null) {
                FACTORIES.putIfAbsent(type.registryName, RecipeTypeUIWidgets::createDefaultWidget);
            }
        }
    }

    /** 覆盖某个配方类型的工厂。 */
    public static void setWidgetFactory(ResourceLocation recipeTypeId, Function<OGMRRecipeType, WidgetGroup> factory) {
        if (recipeTypeId == null || factory == null) {
            return;
        }
        FACTORIES.put(recipeTypeId, factory);
    }

    /** 覆盖全局默认工厂（没有单独登记的配方类型走它）。 */
    public static void setDefaultWidgetFactory(@Nullable Function<OGMRRecipeType, WidgetGroup> factory) {
        defaultFactory = factory;
    }

    /** 取某个配方类型的工厂（找不到时回退到默认工厂，再回退到 {@link #createDefaultWidget}）。 */
    public static Function<OGMRRecipeType, WidgetGroup> getFactory(OGMRRecipeType type) {
        Function<OGMRRecipeType, WidgetGroup> factory = type == null ? null : FACTORIES.get(type.registryName);
        if (factory != null) {
            return factory;
        }
        Function<OGMRRecipeType, WidgetGroup> fallback = defaultFactory;
        return fallback != null ? fallback : RecipeTypeUIWidgets::createDefaultWidget;
    }

    /** 清空静态表（仅供测试/资源重载用）。 */
    public static void clearFactories() {
        FACTORIES.clear();
        defaultFactory = null;
    }

    // ═══════════════════════ 入口 ═══════════════════════

    /** 走静态表里的工厂产出 widget；等价于「配方类型 UI 的统一出口」。 */
    public static WidgetGroup create(OGMRRecipeType type) {
        return getFactory(type).apply(type);
    }

    /**
     * 默认绘制入口：有自定义 {@code .rtui} 就用它，否则用 {@link #createDefaultLayout(OGMRRecipeType)}。
     */
    public static WidgetGroup createDefaultWidget(OGMRRecipeType type) {
        RecipeTypeUI ui = type.getRecipeUI();
        if (ui != null && ui.hasCustomUI()) {
            WidgetGroup custom = loadCustom(ui);
            if (custom != null) {
                return custom;
            }
        }
        return createDefaultLayout(type);
    }

    /**
     * 把 {@code .rtui} 自定义界面反序列化成 WidgetGroup。
     *
     * <p>读法照 GTM 的 {@code RecipeTypeUIProject#attachMenu}：
     * NBT 里有 {@code root}（根 widget 树）与 {@code resources}（贴图等资源表）两棵子树。
     *
     * @return 没有自定义 UI 或反序列化失败时返回 null（调用方应回退到默认布局）
     */
    @Nullable
    public static WidgetGroup loadCustom(@Nullable RecipeTypeUI ui) {
        if (ui == null || !ui.hasCustomUI()) {
            return null;
        }
        try {
            CompoundTag nbt = ui.getCustomUI();
            WidgetGroup group = new WidgetGroup();
            IConfigurableWidget.deserializeNBT(group, nbt.getCompound("root"),
                    Resources.fromNBT(nbt.getCompound("resources")), false);
            group.setSelfPosition(new Position(0, 0));
            return group;
        } catch (Exception e) {
            // 编辑器存坏的 .rtui 不应该把整个 XEI 页面炸掉，退回默认布局即可。
            Ogmr.LOGGER.warn("ogmr: failed to load custom recipe type UI {}: {}", ui.getUiPath(), e.toString());
            return null;
        }
    }

    // ═══════════════════════ 默认布局 ═══════════════════════

    /**
     * 按 {@link OGMRRecipeType} 的槽位上限摆一份默认布局（不含任何配方数据绑定）。
     *
     * <p>布局宽度取「内容所需宽度」与 {@link RecipeTypeUI#getWidth()} 的较大者，
     * 这样 addon 把 rtui 显式设宽之后不会被内容挤窄。
     */
    public static WidgetGroup createDefaultLayout(OGMRRecipeType type) {
        int itemIn = Math.max(0, type.getMaxItemInputs());
        int fluidIn = Math.max(0, type.getMaxFluidInputs());
        int itemOut = Math.max(0, type.getMaxItemOutputs());
        int fluidOut = Math.max(0, type.getMaxFluidOutputs());

        int inWidth = Math.max(itemIn + fluidIn, 1) * SLOT_SIZE;
        int outWidth = Math.max(itemOut + fluidOut, 1) * SLOT_SIZE;

        RecipeTypeUI ui = type.getRecipeUI();
        int minWidth = ui == null ? RecipeTypeUI.DEFAULT_WIDTH : ui.getWidth();
        int width = Math.max(PADDING + inWidth + GAP + PROGRESS_WIDTH + GAP + outWidth + PADDING, minWidth);
        int height = PADDING + TITLE_HEIGHT + SLOT_SIZE + TEXT_GAP + 2 * TEXT_LINE_HEIGHT + PADDING;

        WidgetGroup root = new WidgetGroup(0, 0, width, height);
        root.setBackground(GuiTextures.recipeTypeBackground());

        // ── 标题：配方类型的语言键（ogmr.xxx） ──
        root.addWidget(new LabelWidget(PADDING, PADDING, Component.translatable(type.registryName.toLanguageKey()))
                .setTextColor(GuiTextures.COLOR_TEXT_TITLE)
                .setId(ID_TITLE));

        // ── 槽位行 ──
        int slotY = PADDING + TITLE_HEIGHT;
        int x = PADDING;
        for (int i = 0; i < itemIn; i++, x += SLOT_SIZE) {
            root.addWidget(createItemSlot(x, slotY, idItemInput(i)));
        }
        for (int i = 0; i < fluidIn; i++, x += SLOT_SIZE) {
            root.addWidget(createTank(x, slotY, idFluidInput(i)));
        }

        // ── 进度条：夹在输入与输出之间，垂直居中于槽位行 ──
        int progressX = PADDING + inWidth + GAP;
        int progressY = slotY + (SLOT_SIZE - PROGRESS_HEIGHT) / 2;
        ProgressTexture progressTexture = new ProgressTexture(GuiTextures.progressBarBackground(),
                GuiTextures.progressBarFilled())
                .setFillDirection(ProgressTexture.FillDirection.LEFT_TO_RIGHT);
        root.addWidget(new ProgressWidget(ProgressWidget.JEIProgress, progressX, progressY, PROGRESS_WIDTH,
                PROGRESS_HEIGHT, progressTexture).setId(ID_PROGRESS));

        // ── 输出槽 ──
        int outX = progressX + PROGRESS_WIDTH + GAP;
        for (int i = 0; i < itemOut; i++, outX += SLOT_SIZE) {
            root.addWidget(createItemSlot(outX, slotY, idItemOutput(i)));
        }
        for (int i = 0; i < fluidOut; i++, outX += SLOT_SIZE) {
            root.addWidget(createTank(outX, slotY, idFluidOutput(i)));
        }

        // ── 「EU/t 与时长」两个文本位（内容由配方页绑定） ──
        int textY = slotY + SLOT_SIZE + TEXT_GAP;
        root.addWidget(new LabelWidget(progressX, textY, "").setTextColor(GuiTextures.COLOR_TEXT).setId(ID_EU_T));
        root.addWidget(new LabelWidget(progressX, textY + TEXT_LINE_HEIGHT, "")
                .setTextColor(GuiTextures.COLOR_TEXT)
                .setId(ID_DURATION));

        return root;
    }

    // ═══════════════════════ 小工具 ═══════════════════════

    /** 造一个空壳物品槽（与 GTM {@code GTRecipeTypeUI#addSlot} 的非流体分支一致）。 */
    public static SlotWidget createItemSlot(int x, int y, String id) {
        SlotWidget slot = new SlotWidget();
        slot.initTemplate();
        slot.setSelfPosition(new Position(x, y));
        slot.setBackground(GuiTextures.recipeTypeSlot());
        slot.setId(id);
        return slot;
    }

    /** 造一个空壳流体槽（与 GTM {@code GTRecipeTypeUI#addSlot} 的流体分支一致）。 */
    public static TankWidget createTank(int x, int y, String id) {
        TankWidget tank = new TankWidget();
        tank.initTemplate();
        tank.setSelfPosition(new Position(x, y));
        tank.setBackground(GuiTextures.fluidSlot());
        tank.setFillDirection(ProgressTexture.FillDirection.ALWAYS_FULL);
        tank.setId(id);
        return tank;
    }

    /** 按 id 往布局里塞文本（配方页绑「EU/t」「时长」用）。 */
    public static void setText(WidgetGroup root, String id, @Nullable Component text) {
        Widget widget = root.getFirstWidgetById(id);
        if (widget instanceof LabelWidget label) {
            label.setText(text == null ? "" : text.getString());
        }
    }
}
