package rain.fox.ogmr.integration.jei;

import rain.fox.ogmr.Ogmr;
import rain.fox.ogmr.api.lang.OGMRLang;
import rain.fox.ogmr.api.machine.MultiblockMachineDefinition;
import rain.fox.ogmr.api.registry.OGMRRegistries;

import lombok.Getter;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.BlockState;

import com.mojang.blaze3d.platform.InputConstants;

import mezz.jei.api.gui.builder.IRecipeLayoutBuilder;
import mezz.jei.api.gui.builder.ITooltipBuilder;
import mezz.jei.api.gui.drawable.IDrawable;
import mezz.jei.api.gui.ingredient.IRecipeSlotsView;
import mezz.jei.api.gui.inputs.IJeiInputHandler;
import mezz.jei.api.gui.inputs.IJeiUserInput;
import mezz.jei.api.gui.widgets.IRecipeExtrasBuilder;
import mezz.jei.api.helpers.IGuiHelper;
import mezz.jei.api.helpers.IJeiHelpers;
import mezz.jei.api.recipe.IFocusGroup;
import mezz.jei.api.recipe.RecipeIngredientRole;
import mezz.jei.api.recipe.RecipeType;
import mezz.jei.api.recipe.category.IRecipeCategory;
import mezz.jei.api.registration.IRecipeRegistration;

import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * JEI 的「多方块结构」预览分类。
 *
 * <p>
 * 一页配方 = 一台多方块（见 {@link MultiblockInfoWrapper} 里关于 shape 粒度的说明）。版面自上而下：
 * <ol>
 * <li><b>头部</b>（y 3..26）：控制器物品槽 + 物品名 + {@code getDescriptionId()} 的译文；</li>
 * <li><b>结构预览</b>（{@link MultiblockInfoWrapper#PREVIEW_X} 定义的 152×104 框）：由
 * {@link StructurePreviewDrawable} 画 2.5D 等轴测结构；</li>
 * <li><b>按钮行</b>（y 133..145）：层级按钮 + 上一份/下一份图案；</li>
 * <li><b>文字行</b>（y 147）：左边「尺寸 3×3×3」、右边「图案 1/2」。</li>
 * </ol>
 *
 * <p>
 * <b>与 GTM 的关系：</b>GTM 的 {@code MultiblockInfoCategory} 是
 * {@code ModularUIRecipeCategory<MultiblockInfoWrapper>}，整块内容其实是 LDLib 的 ModularUI 画布
 * （{@code PatternPreviewWidget}），JEI 只负责把它摆上去。本库不依赖那套东西，改成
 * <b>纯 JEI {@link IRecipeCategory} + 自绘 {@link IDrawable}</b>：一条配方就是一个 drawable、
 * 一个 input handler，没有 ModularUI 生命周期、没有假世界。
 *
 * <p>
 * <b>JEI 15.20 的 API 迁移：</b>本类只覆写未废弃的入口 —— {@code getWidth()/getHeight()}
 * （而不是已废弃的 {@code getBackground()}）、{@code getTooltip(ITooltipBuilder, ...)}
 * （而不是已废弃的 {@code getTooltipStrings(...)}）。两者都留了兼容桥，见方法注释。
 */
public class MultiblockInfoCategory implements IRecipeCategory<MultiblockInfoWrapper> {

    /** 配方类型 UID：{@code ogmr:multiblock_info}。 */
    public static final ResourceLocation UID = Ogmr.id("multiblock_info");

    public static final RecipeType<MultiblockInfoWrapper> RECIPE_TYPE =
            new RecipeType<>(UID, MultiblockInfoWrapper.class);

    // ═══════════════ 语言键 ═══════════════
    // 常量与文案都在 MultiblockPreviewLang 里（那个类不碰 JEI，datagen 环境也能调用其 initLang）。

    public static final String LANG_TITLE = MultiblockPreviewLang.LANG_TITLE;
    public static final String LANG_NO_SHAPE = MultiblockPreviewLang.LANG_NO_SHAPE;
    public static final String LANG_SIZE = MultiblockPreviewLang.LANG_SIZE;
    public static final String LANG_PAGE = MultiblockPreviewLang.LANG_PAGE;
    public static final String LANG_LAYER_ALL = MultiblockPreviewLang.LANG_LAYER_ALL;
    public static final String LANG_LAYER_FMT = MultiblockPreviewLang.LANG_LAYER_FMT;
    public static final String LANG_HINT_ROTATE = MultiblockPreviewLang.LANG_HINT_ROTATE;
    public static final String LANG_HINT_LAYER = MultiblockPreviewLang.LANG_HINT_LAYER;
    public static final String LANG_HINT_PREV = MultiblockPreviewLang.LANG_HINT_PREV;
    public static final String LANG_HINT_NEXT = MultiblockPreviewLang.LANG_HINT_NEXT;

    // ═══════════════ 版面 ═══════════════

    private static final int WIDTH = MultiblockInfoWrapper.WIDTH;
    private static final int HEIGHT = MultiblockInfoWrapper.HEIGHT;

    private static final int PREVIEW_X = MultiblockInfoWrapper.PREVIEW_X;
    private static final int PREVIEW_Y = MultiblockInfoWrapper.PREVIEW_Y;
    private static final int PREVIEW_W = MultiblockInfoWrapper.PREVIEW_W;
    private static final int PREVIEW_H = MultiblockInfoWrapper.PREVIEW_H;

    /** 控制器物品槽（18×18，JEI 自己会画槽底）。 */
    private static final int SLOT_X = 3;
    private static final int SLOT_Y = 3;

    private static final int TITLE_X = 24;
    private static final int TITLE_Y = 7;
    private static final int DESC_X = 24;
    private static final int DESC_Y = 17;

    /** 文字行：左边尺寸、右边页码。 */
    private static final int INFO_Y = 147;
    private static final int INFO_RIGHT = WIDTH - 4;

    /** 按钮行。 */
    private static final int BTN_Y = 133;
    private static final int BTN_H = 12;
    private static final int LAYER_BTN_X = 4;
    private static final int LAYER_BTN_W = 60;
    private static final int PREV_BTN_X = 112;
    private static final int NEXT_BTN_X = 134;
    private static final int BTN_W = 20;

    // ═══════════════ 配色 ═══════════════

    private static final int COLOR_BG = 0xF0101010;
    private static final int COLOR_BORDER = 0xFF3C3C3C;
    private static final int COLOR_WELL = 0xFF080808;
    private static final int COLOR_WELL_BORDER = 0xFF2A2A2A;
    private static final int COLOR_TEXT = 0xFFE8E8E8;
    private static final int COLOR_DIM = 0xFF9A9A9A;
    private static final int COLOR_BTN = 0xFF2A2A2A;
    private static final int COLOR_BTN_HOVER = 0xFF3D6EA5;
    private static final int COLOR_BTN_BORDER = 0xFF6A6A6A;

    static {
        // 语言键登记放在静态块里：JEI 一装载本分类就顺便把键灌进 OGMRLang。
        // ⚠ 注意：静态块只在「本类被类装载」时执行，而本类带着 JEI 的类型引用，
        //    所以 datagen（没有 JEI）时这些键不会被登记。见 initLang() 的注释。
        initLang();
    }

    @Getter
    private final IDrawable icon;

    public MultiblockInfoCategory(IJeiHelpers helpers) {
        IGuiHelper guiHelper = helpers.getGuiHelper();
        this.icon = guiHelper.createDrawableItemStack(pickIconStack());
    }

    /**
     * 登记本分类用到的全部语言键 —— 转发到 {@link MultiblockPreviewLang}（不碰 JEI 的那个持有类）。
     *
     * <p>
     * 文案与常量都在那边，{@code Ogmr} 在数据生成之前调用它的 {@code initLang()}，
     * 所以这些键会正常进 datagen 产出的 {@code en_us.json} / {@code zh_cn.json}。
     * 这里保留一个同名方法只是给「按类查找 initLang」的调用方一个入口。
     */
    public static void initLang() {
        MultiblockPreviewLang.initLang();
    }

    // ═══════════════ 注册 ═══════════════

    /**
     * 为<b>每一台已注册的多方块</b>产出一条预览。
     *
     * <p>
     * 这里刻意不过滤 {@link MultiblockMachineDefinition#isRenderXEIPreview()}（GTM 是会过滤的）：
     * 任务要求「每一台已注册的多方块都产出预览条目」，而且本库那个标记的默认值是 false，
     * 过滤会让分类整体空掉。将来若想要「可选关闭」，把下面这行改成
     * {@code if (!definition.isRenderXEIPreview()) continue;} 即可。
     *
     * <p>
     * 单台机器的构建失败（拿不到方块、shape 列表抛异常……）只跳过这一台，不会连累其它机器 ——
     * {@link MultiblockInfoWrapper} 的构造器已经把这类问题都兜住了，这里再加一层保险。
     */
    public static void registerRecipes(IRecipeRegistration registration) {
        // 整合包作者可以用配置整体关掉结构预览（关掉时 JEI 里就不出现这个分类的内容）
        if (!rain.fox.ogmr.OGMRConfig.isAllowStructurePreview()) {
            Ogmr.LOGGER.info("ogmr: multiblock JEI preview disabled by config (misc.allowStructurePreview=false)");
            return;
        }
        List<MultiblockInfoWrapper> recipes = new ArrayList<>();
        for (MultiblockMachineDefinition definition : OGMRRegistries.MULTIBLOCKS) {
            try {
                recipes.add(new MultiblockInfoWrapper(definition));
            } catch (RuntimeException e) {
                Ogmr.LOGGER.error("ogmr: skipping JEI multiblock preview that failed to build: {}", definition, e);
            }
        }
        registration.addRecipes(RECIPE_TYPE, recipes);
    }

    /**
     * 分类图标：拿第一台「有物品形式」的多方块的控制器物品。
     *
     * <p>
     * JEI 允许 {@code getIcon()} 返回 null（它会退回用第一条配方的 catalyst），但返回 null 在
     * 15.20 的签名上没有 {@code @Nullable}，所以这里永远给一个非空 drawable。
     */
    private static ItemStack pickIconStack() {
        for (MultiblockMachineDefinition definition : OGMRRegistries.MULTIBLOCKS) {
            try {
                ItemStack stack = definition.asStack();
                if (stack != null && !stack.isEmpty()) return stack;
            } catch (RuntimeException e) {
                // 单台机器的物品形式取不到就算了，继续找下一台
                Ogmr.LOGGER.debug("ogmr: multiblock {} has no item for the JEI category icon", definition, e);
            }
        }
        // 一台都没注册（或都没物品）时的中性兜底
        return new ItemStack(Items.CRAFTING_TABLE);
    }

    // ═══════════════ IRecipeCategory ═══════════════

    @Override
    public RecipeType<MultiblockInfoWrapper> getRecipeType() {
        return RECIPE_TYPE;
    }

    @Override
    public Component getTitle() {
        return Component.translatable(LANG_TITLE);
    }

    /**
     * 背景尺寸。
     *
     * <p>
     * JEI 15.20 起 {@code getBackground()} 已废弃、默认返回 null，并且明确要求
     * 「background 为 null 时必须覆写 getWidth()/getHeight()」（否则默认实现直接抛
     * {@code IllegalStateException}）。所以这里只覆写尺寸，背景由 {@link #draw} 自绘。
     */
    @Override
    public int getWidth() {
        return WIDTH;
    }

    @Override
    public int getHeight() {
        return HEIGHT;
    }

    @Override
    public void setRecipe(IRecipeLayoutBuilder builder, MultiblockInfoWrapper recipe, IFocusGroup focuses) {
        ItemStack controller = recipe.controllerStack;
        if (controller.isEmpty()) return;
        // CATALYST：物品 → 配方 的反查里，控制器物品就是「能做出这台多方块」的东西，
        // 于是「对控制器按 R」能看到自己这台机器的结构预览。
        builder.addSlot(RecipeIngredientRole.CATALYST, SLOT_X, SLOT_Y)
                .addItemStack(controller)
                .setStandardSlotBackground();
    }

    /**
     * 挂上输入转发器，这样结构预览才能接收滚轮 / 拖拽 / 点击。
     *
     * <p>
     * 这是 JEI 15.9 起的标准做法：{@code IRecipeCategory} 本身已经没有任何可靠的输入入口
     * （{@code handleInput} 也已废弃，且只覆盖「按键」），要处理滚轮与拖拽只能用
     * {@link IJeiInputHandler}。
     */
    @Override
    public void createRecipeExtras(IRecipeExtrasBuilder builder, MultiblockInfoWrapper recipe, IFocusGroup focuses) {
        builder.addInputHandler(new PreviewInputHandler(recipe));
    }

    @Override
    public void draw(MultiblockInfoWrapper recipe, IRecipeSlotsView recipeSlotsView, GuiGraphics graphics,
                     double mouseX, double mouseY) {
        Font font = Minecraft.getInstance().font;

        drawBackground(graphics);
        drawHeader(graphics, font, recipe);
        drawPreview(graphics, recipe, mouseX, mouseY);
        drawFooter(graphics, font, recipe, mouseX, mouseY);
    }

    private static void drawBackground(GuiGraphics graphics) {
        graphics.fill(0, 0, WIDTH, HEIGHT, COLOR_BG);
        graphics.fill(0, 0, WIDTH, 1, COLOR_BORDER);
        graphics.fill(0, HEIGHT - 1, WIDTH, HEIGHT, COLOR_BORDER);
        graphics.fill(0, 0, 1, HEIGHT, COLOR_BORDER);
        graphics.fill(WIDTH - 1, 0, WIDTH, HEIGHT, COLOR_BORDER);

        // 预览框做成「凹槽」，让结构看起来是嵌在里面的
        graphics.fill(PREVIEW_X - 1, PREVIEW_Y - 1, PREVIEW_X + PREVIEW_W + 1, PREVIEW_Y + PREVIEW_H + 1,
                COLOR_WELL_BORDER);
        graphics.fill(PREVIEW_X, PREVIEW_Y, PREVIEW_X + PREVIEW_W, PREVIEW_Y + PREVIEW_H, COLOR_WELL);
    }

    private static void drawHeader(GuiGraphics graphics, Font font, MultiblockInfoWrapper recipe) {
        Component name = recipe.controllerStack.isEmpty()
                ? Component.literal(recipe.definition.getId().toString())
                : recipe.controllerStack.getHoverName();
        // 名字可能很长（带等级前缀……），按可用宽度截断，免得糊到 JEI 的界面上
        String clipped = font.plainSubstrByWidth(name.getString(), WIDTH - TITLE_X - 4);
        graphics.drawString(font, clipped, TITLE_X, TITLE_Y, COLOR_TEXT, false);

        // getDescriptionId() 的实现是 getBlock().getDescriptionId() —— 一个方块的「翻译键」
        // （block.ogmr.xxx）。translatable 一次正好拿到译文；万一将来它改成返回已翻译的字符串，
        // translatable 也会原样吐出，两种实现都不会出错。
        String descKey = recipe.definition.getDescriptionId();
        if (descKey != null && !descKey.isEmpty()) {
            String desc = font.plainSubstrByWidth(Component.translatable(descKey).getString(),
                    WIDTH - DESC_X - 4);
            graphics.drawString(font, desc, DESC_X, DESC_Y, COLOR_DIM, false);
        }
    }

    private static void drawPreview(GuiGraphics graphics, MultiblockInfoWrapper recipe,
                                    double mouseX, double mouseY) {
        StructurePreviewDrawable preview = recipe.currentPreview();
        if (preview == null || preview.isEmpty()) {
            // 「shape 列表可能为空」的优雅退化：不画结构，只留一句提示
            Font font = Minecraft.getInstance().font;
            graphics.drawCenteredString(font, Component.translatable(LANG_NO_SHAPE),
                    PREVIEW_X + PREVIEW_W / 2, PREVIEW_Y + PREVIEW_H / 2 - 4, COLOR_DIM);
            return;
        }
        // 鼠标坐标换算到「相对预览框左上角」，与 getBlockAt 的约定一致
        preview.draw(graphics, PREVIEW_X, PREVIEW_Y, mouseX - PREVIEW_X, mouseY - PREVIEW_Y);
    }

    private static void drawFooter(GuiGraphics graphics, Font font, MultiblockInfoWrapper recipe,
                                   double mouseX, double mouseY) {
        StructurePreviewDrawable preview = recipe.currentPreview();

        // ── 文字行：左「尺寸」，右「图案 i/n」──
        if (preview != null && !preview.isEmpty()) {
            Component size = Component.translatable(LANG_SIZE,
                    preview.getSizeX(), preview.getSizeY(), preview.getSizeZ());
            graphics.drawString(font, size, PREVIEW_X, INFO_Y, COLOR_DIM, false);
        }
        if (recipe.getShapeCount() > 1) {
            Component page = Component.translatable(LANG_PAGE,
                    recipe.getShapeIndex() + 1, recipe.getShapeCount());
            graphics.drawString(font, page, INFO_RIGHT - font.width(page), INFO_Y, COLOR_DIM, false);
        }

        // ── 按钮行 ──
        if (preview != null) {
            drawButton(graphics, font, LAYER_BTN_X, BTN_Y, LAYER_BTN_W, BTN_H,
                    layerLabel(preview),
                    isHover(mouseX, mouseY, LAYER_BTN_X, BTN_Y, LAYER_BTN_W, BTN_H));
        }
        if (recipe.getShapeCount() > 1) {
            drawButton(graphics, font, PREV_BTN_X, BTN_Y, BTN_W, BTN_H, Component.literal("<"),
                    isHover(mouseX, mouseY, PREV_BTN_X, BTN_Y, BTN_W, BTN_H));
            drawButton(graphics, font, NEXT_BTN_X, BTN_Y, BTN_W, BTN_H, Component.literal(">"),
                    isHover(mouseX, mouseY, NEXT_BTN_X, BTN_Y, BTN_W, BTN_H));
        }
    }

    private static Component layerLabel(StructurePreviewDrawable preview) {
        int layer = preview.getLayer();
        if (layer == StructurePreviewDrawable.ALL_LAYERS) {
            return Component.translatable(LANG_LAYER_ALL);
        }
        return Component.translatable(LANG_LAYER_FMT, layer + 1, preview.getLayerCount());
    }

    private static void drawButton(GuiGraphics graphics, Font font, int x, int y, int w, int h,
                                   Component label, boolean hovered) {
        graphics.fill(x, y, x + w, y + h, hovered ? COLOR_BTN_HOVER : COLOR_BTN);
        graphics.fill(x, y, x + w, y + 1, COLOR_BTN_BORDER);
        graphics.fill(x, y + h - 1, x + w, y + h, COLOR_BTN_BORDER);
        graphics.fill(x, y, x + 1, y + h, COLOR_BTN_BORDER);
        graphics.fill(x + w - 1, y, x + w, y + h, COLOR_BTN_BORDER);
        // 按钮里文字固定 8px 高（原版字号），垂直居中
        graphics.drawCenteredString(font, label, x + w / 2, y + (h - 8) / 2, COLOR_TEXT);
    }

    private static boolean isHover(double mouseX, double mouseY, int x, int y, int w, int h) {
        return mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
    }

    // ═══════════════ Tooltip ═══════════════

    /**
     * 悬浮说明。
     *
     * <p>
     * <b>API 说明：</b>任务里点名的是 {@code getTooltipStrings(...)}，但它在 JEI 15.20 已废弃，
     * 且默认实现是「{@code getTooltip(ITooltipBuilder,...)} 反过来调用它」。这里覆写<b>未废弃</b>的
     * 新入口（JEI 实际调用的就是它），同时把已废弃的旧方法留成兼容桥转发过来，
     * 于是新旧两条路径行为一致，也不会在编译时冒废弃警告。
     */
    @Override
    public void getTooltip(ITooltipBuilder tooltip, MultiblockInfoWrapper recipe,
                           IRecipeSlotsView recipeSlotsView, double mouseX, double mouseY) {
        tooltip.addAll(collectTooltips(recipe, mouseX, mouseY));
    }

    /** 兼容桥：JEI &lt; 15.20 以及部分第三方 XEI 消费者仍会走这个已废弃的入口。 */
    @Override
    @SuppressWarnings("deprecation")
    public List<Component> getTooltipStrings(MultiblockInfoWrapper recipe, IRecipeSlotsView recipeSlotsView,
                                             double mouseX, double mouseY) {
        return collectTooltips(recipe, mouseX, mouseY);
    }

    private static List<Component> collectTooltips(MultiblockInfoWrapper recipe, double mouseX, double mouseY) {
        List<Component> lines = new ArrayList<>(3);

        // 按钮的说明优先：它们在结构格之外，正常不会和格子重叠，但先判按钮语义更清晰
        if (recipe.currentPreview() != null
                && isHover(mouseX, mouseY, LAYER_BTN_X, BTN_Y, LAYER_BTN_W, BTN_H)) {
            lines.add(Component.translatable(LANG_HINT_LAYER));
            return lines;
        }
        if (recipe.getShapeCount() > 1) {
            if (isHover(mouseX, mouseY, PREV_BTN_X, BTN_Y, BTN_W, BTN_H)) {
                lines.add(Component.translatable(LANG_HINT_PREV));
                return lines;
            }
            if (isHover(mouseX, mouseY, NEXT_BTN_X, BTN_Y, BTN_W, BTN_H)) {
                lines.add(Component.translatable(LANG_HINT_NEXT));
                return lines;
            }
        }

        // 结构格：显示方块名
        if (!isHover(mouseX, mouseY, PREVIEW_X, PREVIEW_Y, PREVIEW_W, PREVIEW_H)) return lines;
        StructurePreviewDrawable preview = recipe.currentPreview();
        if (preview == null) return lines;

        BlockState state = preview.getBlockAt(mouseX - PREVIEW_X, mouseY - PREVIEW_Y);
        if (state == null) return lines;

        lines.add(state.getBlock().getName());
        Component ability = describeAbility(state);
        if (ability != null) lines.add(ability);
        return lines;
    }

    /**
     * 「这一格能不能用仓室（Part）替代、替代的能力叫什么」。
     *
     * <p>
     * <b>当前返回 null = 拿不到，于是跳过（任务里明确允许）。</b>原因：能力信息挂在
     * {@code TraceabilityPredicate} / {@code SimplePredicate} 上，而
     * {@code MultiblockShapeInfo} 只带了 {@code BlockInfo[][][]} —— 预览页里拿不到谓词表，
     * 这一格到底是「必须放机器外壳」还是「可以放任意 1 个 EU 输入仓」，单看方块状态是判不出来的。
     *
     * <p>
     * 故意留成独立方法，而且签名只需要一个 {@code BlockState}：将来
     * {@code BlockInfo}（LDLib 的）或本库的 shape 对象要是补上了 ability 字段，
     * 只改这一个方法就能把提示接上，其它代码一行不动。
     */
    @Nullable
    private static Component describeAbility(BlockState state) {
        return null;
    }

    @Override
    @Nullable
    public ResourceLocation getRegistryName(MultiblockInfoWrapper recipe) {
        return recipe.definition.getId();
    }

    // ═══════════════ 输入 ═══════════════

    /**
     * 结构预览区的输入转发器。
     *
     * <p>
     * <b>坐标约定：</b>{@link IJeiInputHandler#getArea()} 的文档里有两处措辞打架 ——
     * 接口注释说传给 handler 的是「相对父元素」的坐标，{@code getArea()} 的注释又说会「平移到
     * 区域原点」（即相对区域左上角）。为了不受这个歧义影响，这里把区域设成<b>整张配方
     * (0,0,160,160)</b>：此时「区域相对」与「父元素相对」在数值上完全一致，无论 JEI 用哪种解释，
     * 收到的坐标都等于「相对配方左上角」，与 {@link MultiblockInfoCategory#draw} 里拿到的
     * mouseX / mouseY 同一坐标系。
     *
     * <p>
     * <b>为什么不整块吞掉输入：</b>滚轮只在落在结构预览框内时才返回 true。落在标题 / 说明文字上的
     * 滚轮继续交给 JEI 自己，于是「滚轮翻配方」这个默认操作没被破坏。
     */
    private static final class PreviewInputHandler implements IJeiInputHandler {

        private static final ScreenRectangle AREA = new ScreenRectangle(0, 0, WIDTH, HEIGHT);

        private final MultiblockInfoWrapper recipe;

        private PreviewInputHandler(MultiblockInfoWrapper recipe) {
            this.recipe = recipe;
        }

        @Override
        public ScreenRectangle getArea() {
            return AREA;
        }

        /** 滚轮 = 绕 Y 轴转四分之一圈。 */
        @Override
        public boolean handleMouseScrolled(double mouseX, double mouseY, double scrollDelta) {
            if (!isHover(mouseX, mouseY, PREVIEW_X, PREVIEW_Y, PREVIEW_W, PREVIEW_H)) return false;
            StructurePreviewDrawable preview = recipe.currentPreview();
            if (preview == null || preview.isEmpty()) return false;
            preview.rotate(scrollDelta < 0 ? 1 : -1);
            return true;
        }

        /** 拖拽 = 连续旋转（累计位移，见 {@link StructurePreviewDrawable#drag(double)}）。 */
        @Override
        public boolean handleMouseDragged(double mouseX, double mouseY, InputConstants.Key mouseKey,
                                          double dragX, double dragY) {
            if (!isHover(mouseX, mouseY, PREVIEW_X, PREVIEW_Y, PREVIEW_W, PREVIEW_H)) return false;
            StructurePreviewDrawable preview = recipe.currentPreview();
            if (preview == null || preview.isEmpty()) return false;
            preview.drag(dragX);
            return true;
        }

        /**
         * 点击 = 按按钮。
         *
         * <p>
         * JEI 的约定：左键<b>按下</b>时 {@code isSimulate()} 为 true，handler 只回答「我能不能处理」；
         * 左键<b>松开</b>时才真正执行 —— 这样玩家按下后把鼠标移开再松开就不会误触发。
         * 键盘按下则直接执行（没有 simulate 阶段）。
         */
        @Override
        public boolean handleInput(double mouseX, double mouseY, IJeiUserInput input) {
            InputConstants.Key key = input.getKey();
            boolean leftClick = key.getType() == InputConstants.Type.MOUSE && key.getValue() == 0;
            if (!leftClick) return false;

            if (isHover(mouseX, mouseY, LAYER_BTN_X, BTN_Y, LAYER_BTN_W, BTN_H)) {
                if (!input.isSimulate()) {
                    StructurePreviewDrawable preview = recipe.currentPreview();
                    if (preview != null) preview.nextLayer();
                }
                return true;
            }
            if (recipe.getShapeCount() > 1) {
                if (isHover(mouseX, mouseY, PREV_BTN_X, BTN_Y, BTN_W, BTN_H)) {
                    if (!input.isSimulate()) recipe.prevShape();
                    return true;
                }
                if (isHover(mouseX, mouseY, NEXT_BTN_X, BTN_Y, BTN_W, BTN_H)) {
                    if (!input.isSimulate()) recipe.nextShape();
                    return true;
                }
            }
            return false;
        }
    }
}
