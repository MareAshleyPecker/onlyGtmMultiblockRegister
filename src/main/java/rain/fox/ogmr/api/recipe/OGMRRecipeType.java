package rain.fox.ogmr.api.recipe;

import rain.fox.ogmr.api.pattern.util.IO;
import rain.fox.ogmr.api.recipe.ui.RecipeTypeUI;
import rain.fox.ogmr.api.registry.OGMRRegistries;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeType;

import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;

import org.jetbrains.annotations.Nullable;

import java.util.function.Supplier;

/**
 * 配方类型 —— 机器的「配方表」身份。
 *
 * <p>
 * 从 GTM 的 {@code api.recipe.GTRecipeType} 精简拆出来。GTM 的 RecipeType 同时承担
 * 「注册标识 + capability 上限表 + 概率提升函数 + UI + 配方索引数据库（RecipeDB）+ 分类」
 * 六件事，本库只保留前三件里最必要的部分：
 * <ul>
 * <li>{@link #registryName} / {@link #group} —— 身份与分组（分组给 UI 归类用）；</li>
 * <li>{@code maxItemInputs} 等四个上限 —— 由机器层的槽位/储罐布局去读，用来决定该显示几个槽；</li>
 * <li>{@link #recipeUI} —— 数据侧的 {@link RecipeTypeUI}（尺寸/图标/.rtui 缓存），绘制由 GUI 层负责；</li>
 * <li>{@link #iconSupplier} / {@link #sound} —— 展示图标与工作时循环音。</li>
 * </ul>
 *
 * <p>
 * <b>注册方式</b>：{@link #register(ResourceLocation, String)}，内部走
 * {@link OGMRRegistries#RECIPE_TYPES}，重名直接抛 {@link IllegalStateException}
 * （与 GTM 一样：宁可炸在注册期，也不要进游戏才发现配方类型被静默覆盖）。
 *
 * <p>
 * <b>注意</b>：本类不是 Forge 注册项（不进 {@code BuiltInRegistries.RECIPE_TYPE}），
 * 它只活在 {@link OGMRRegistries#RECIPE_TYPES} 里；机器通过
 * {@code MachineDefinition#getRecipeTypes()} 拿到实例，配方 JSON 里写它
 * {@link #registryName} 的字符串形式。
 */
@Accessors(chain = true)
public class OGMRRecipeType implements RecipeType<OGMRRecipe> {

    /** 本类型的注册名（同时是配方 JSON 里 {@code "recipe_type"} 字段的取值）。 */
    @Getter
    public final ResourceLocation registryName;
    /** UI 分组名（例如 {@code "machines"}），仅用于界面归类。 */
    @Getter
    @Setter
    public String group;

    /**
     * 物品/流体的输入输出槽上限（顺序与 GTM 一致：物品入、物品出、流体入、流体出）。
     *
     * <p>
     * <b>故意做成 public 字段</b>：GUI 层（{@code rain.fox.ogmr.api.gui}）按字段名直接读这几个值
     * 来排槽位/储罐，不要改成 getter。之所以不是 {@code final}，是因为 {@link #setMaxIOSize}
     * 是链式 setter，注册期还要能改（GTM 那边也是一张可写的 maxInputs / maxOutputs 上限表）。
     */
    @Getter
    public int maxItemInputs = 1;
    @Getter
    public int maxItemOutputs = 1;
    @Getter
    public int maxFluidInputs = 1;
    @Getter
    public int maxFluidOutputs = 1;

    @Getter
    private Supplier<ItemStack> iconSupplier = () -> ItemStack.EMPTY;
    /** 数据侧 UI（尺寸/图标/.rtui 缓存）。 */
    @Getter
    @Setter
    private RecipeTypeUI recipeUI;
    /** 工作时的循环音；null = 不播。 */
    @Nullable
    @Getter
    @Setter
    private SoundEvent sound;

    /**
     * 这个配方类型的<b>能量方向</b> —— 也就是「用这种配方的机器是<b>用电器</b>还是<b>发电机</b>」。
     *
     * <p>
     * 复用库里已有的 {@link IO} 枚举（{@code api.pattern.util.IO}），全库只保留这一套 IO 词汇：
     * <ul>
     * <li>{@link IO#IN} —— <b>用电器</b>：机器消耗能量（默认值，也是绝大多数机器的情形）；</li>
     * <li>{@link IO#OUT} —— <b>发电机</b>：机器产出能量（配方 {@code eut} 为负）；</li>
     * <li>{@link IO#BOTH} —— 双向（少见：既能耗电又能发电，例如可逆储能/双向转换机）；</li>
     * <li>{@link IO#NONE} —— 不涉及能量（纯结构机、只搬物品/流体的机器）。</li>
     * </ul>
     *
     * <p>
     * 声明它的收益是三处自动接线（都不用你手写）：
     * <ol>
     * <li>{@code Predicates.autoAbilities(types...)} 会据此决定结构里要不要<b>能源输入仓</b>
     * （{@code IN}）还是<b>能源输出仓</b>（{@code OUT}）—— 在此之前只能一律按「耗电型」处理；</li>
     * <li>{@code MultiblockMachineBuilder#register()} 在未显式调用 {@code .generator(...)} 时，
     * 会按它自动把多方块标成发电机（影响面板文本与 {@code MultiblockMachineDefinition#isGenerator()}）；</li>
     * <li>机器实现可以用 {@link #isConsumer()} / {@link #isGenerator()} 分支自己的行为
     * （例如面板显示「发电 XX EU/t」而不是「耗电 XX EU/t」）。</li>
     * </ol>
     *
     * <p>
     * 不确定就<b>别设</b>（保持默认 {@code IN}），或者用 {@link #inferEnergyIO()} 从已注册的配方里
     * 按 {@code eut} 正负推一遍。
     */
    @Getter
    private IO energyIO = IO.IN;

    /**
     * 设置能量方向（链式，等价于 Lombok 生成的 {@code setEnergyIO}，但读起来更像声明）。
     *
     * <pre>{@code
     * public static final OGMRRecipeType GENERATOR_RECIPES =
     *         OGMRRecipeType.register(Ogmr.id("generator_recipes"), "ogmr").energyIO(IO.OUT);
     * }</pre>
     */
    public OGMRRecipeType energyIO(IO io) {
        this.energyIO = io != null ? io : IO.IN;
        return this;
    }

    /** 用电器？——机器要<b>吃</b>能量（{@link IO#IN} 或 {@link IO#BOTH}）。 */
    public boolean isConsumer() {
        return energyIO.support(IO.IN);
    }

    /** 发电机？——机器会<b>产</b>能量（{@link IO#OUT} 或 {@link IO#BOTH}）。 */
    public boolean isGenerator() {
        return energyIO.support(IO.OUT);
    }

    /** 这个配方类型的机器与能量有关吗（{@link IO#NONE} 表示纯结构机 / 只搬物品流体）。 */
    public boolean usesEnergy() {
        return energyIO != IO.NONE;
    }

    /**
     * 从<b>已注册的配方</b>推断能量方向，并按推断结果设置 {@link #energyIO}。
     *
     * <p>
     * 判据是配方的 {@code eut} 正负（见 {@link OGMRRecipe#isGenerator()} / {@link OGMRRecipe#isConsumer()}）：
     * 出现发电配方 → 至少要 {@code OUT}；出现耗电配方 → 至少要 {@code IN}；两种都有 → {@code BOTH}。
     * 一条配方都没有时<b>不改动</b>当前值（返回 false）。
     *
     * <p>
     * ⚠️ 必须在配方都注册完之后调用（例如 {@code FMLCommonSetupEvent} 里），
     * 注册期太早调用会一条配方都扫不到。
     *
     * @return 是否成功推断（至少扫到一条配方）
     */
    public boolean inferEnergyIO() {
        boolean consumer = false;
        boolean generator = false;
        for (OGMRRecipe recipe : RecipeBuilder.byType(this)) {
            if (recipe.isGenerator()) generator = true;
            if (recipe.isConsumer()) consumer = true;
            if (generator && consumer) break;
        }
        if (!consumer && !generator) return false;

        this.energyIO = consumer && generator ? IO.BOTH : (generator ? IO.OUT : IO.IN);
        return true;
    }

    /** 能量方向的文字说明（面板/调试用），例如 {@code "generator (energy out)"}。 */
    public String energyIODescription() {
        return switch (energyIO) {
            case IN -> "consumer (energy in)";
            case OUT -> "generator (energy out)";
            case BOTH -> "bidirectional energy";
            case NONE -> "no energy";
        };
    }

    public OGMRRecipeType(ResourceLocation registryName, String group) {
        this.registryName = registryName;
        this.group = group;
        // 注意顺序：RecipeTypeUI 的构造会读 registryName，必须先赋值
        this.recipeUI = new RecipeTypeUI(this);
    }

    public OGMRRecipeType(ResourceLocation registryName) {
        this(registryName, "");
    }

    /**
     * 注册一个配方类型。
     *
     * @param id    注册名（建议 {@code <modid>:<name>}）
     * @param group UI 分组名，可为空串
     * @throws IllegalStateException 同名类型已存在时
     */
    public static OGMRRecipeType register(ResourceLocation id, String group) {
        if (OGMRRegistries.RECIPE_TYPES.containKey(id)) {
            throw new IllegalStateException(
                    "ogmr: recipe type %s is already registered, cannot register it twice".formatted(id));
        }
        OGMRRecipeType type = new OGMRRecipeType(id, group);
        OGMRRegistries.RECIPE_TYPES.register(id, type);
        return type;
    }

    /** 注册一个无分组的配方类型。 */
    public static OGMRRecipeType register(ResourceLocation id) {
        return register(id, "");
    }

    // ═══════════════ 链式配置 ═══════════════

    /** 设置物品/流体的输入输出槽上限（顺序与 GTM 一致：物品入、物品出、流体入、流体出）。 */
    public OGMRRecipeType setMaxIOSize(int maxItemInputs, int maxItemOutputs, int maxFluidInputs, int maxFluidOutputs) {
        this.maxItemInputs = Math.max(0, maxItemInputs);
        this.maxItemOutputs = Math.max(0, maxItemOutputs);
        this.maxFluidInputs = Math.max(0, maxFluidInputs);
        this.maxFluidOutputs = Math.max(0, maxFluidOutputs);
        return this;
    }

    /** 展示图标（配方类型列表/机器 UI 标题用）；传 null 等价于空图标。 */
    public OGMRRecipeType setIcon(@Nullable Supplier<ItemStack> iconSupplier) {
        this.iconSupplier = iconSupplier == null ? () -> ItemStack.EMPTY : iconSupplier;
        return this;
    }

    // ═══════════════ 访问器 ═══════════════

    /** 图标物品堆；未设置时为空。 */
    public ItemStack getIcon() {
        ItemStack stack = iconSupplier.get();
        return stack == null ? ItemStack.EMPTY : stack;
    }

    // ═══════════════ 配方构建 ═══════════════

    /** 为这个配方类型开一个流式 builder。 */
    public RecipeBuilder recipeBuilder(ResourceLocation recipeId) {
        return new RecipeBuilder(this, recipeId);
    }

    /** 便捷写法：{@code ogmr:xxx} 命名空间。 */
    public RecipeBuilder recipeBuilder(String path) {
        return new RecipeBuilder(this, rain.fox.ogmr.utils.ResourceLocations.of(registryName.getNamespace(), path));
    }

    @Override
    public String toString() {
        return registryName.toString();
    }
}
