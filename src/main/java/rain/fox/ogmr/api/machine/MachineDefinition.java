package rain.fox.ogmr.api.machine;

import com.lowdragmc.lowdraglib.utils.ShapeUtils;
import rain.fox.ogmr.api.recipe.OGMRRecipeType;
import rain.fox.ogmr.utils.ResourceLocations;

import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;

import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import org.jetbrains.annotations.Nullable;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * 本文件是从 GTM 的 {@code com.gregtechceu.gtceu.api.machine.MachineDefinition} 精简拆出来的。
 *
 * <p>
 * 「机器定义」= 一个机器在注册期固定下来的静态信息：id、方块/物品/BE 类型、等级、配方类型、外观、
 * tooltip、碰撞箱、默认染色…… 真正的运行时状态在 {@link MetaMachine} 里。
 *
 * <p>
 * 与 GTM 的差别（精简版）：
 * <ul>
 * <li>不实现 {@code IMachineBlock}（本库的方块是 {@code rain.fox.ogmr.api.block.MachineBlock}），
 * 因此 {@code get()} 直接返回 {@link Block}；</li>
 * <li>砍掉：渲染状态（MachineRenderState）、RotationState、RecipeModifier、
 * 配方输出上限、覆盖板开关等 GT 内容相关字段；</li>
 * <li>多方块专属字段在 {@link MultiblockMachineDefinition} 里。</li>
 * </ul>
 *
 * <p>
 * 方块/物品/BE 类型的 getter 都是「转发到 supplier」的懒查询 —— 因为定义对象通常先创建、后由
 * builder 把注册结果（方块、物品、BE 类型）回填进来，这样可以避免注册顺序上的循环依赖。
 */
@Accessors(chain = true)
public class MachineDefinition implements Supplier<Block> {

    /** 定义 id，同时作为 equals/hashCode 的依据。 */
    @Getter
    private final ResourceLocation id;

    // ── 由 builder 回填的注册结果 ──
    @Setter
    private Supplier<? extends Block> blockSupplier;
    @Setter
    private Supplier<? extends Item> itemSupplier;
    @Setter
    private Supplier<? extends BlockEntityType<?>> blockEntityTypeSupplier;
    @Setter
    private Function<IMachineBlockEntity, MetaMachine> machineSupplier;

    // ── 内容字段 ──
    /** 本机器支持的配方类型（第一个是默认类型）。 */
    @Getter
    private OGMRRecipeType[] recipeTypes = new OGMRRecipeType[0];
    @Getter
    @Setter
    private int tier;
    @Getter
    @Setter
    private boolean allowExtendedFacing;
    private VoxelShape shape = Shapes.block();
    /** 按朝向缓存的旋转结果（一个方块被摆成 4/6 个朝向后形状只算一次）。 */
    private final Map<Direction, VoxelShape> shapeCache = new EnumMap<>(Direction.class);
    /** 外观方块状态（用于多方块「共用外壳」的伪装外观）。 */
    @Getter
    @Setter
    private Supplier<BlockState> appearance = () -> getBlock().defaultBlockState();

    /**
     * 英文显示名（Registrate 里叫 {@code langValue}）。
     *
     * <p>
     * datagen 用它写出 {@code block.<ns>.<name>} 的英文名；为 null 时按 id 自动推导
     * （{@code lv_item_bus → "Lv Item Bus"}，见 {@code Formatting#toEnglishName}）。
     * 中文名走 {@code OGMRLang.add("block.<ns>.<name>", en, zh)} —— 显式登记的中文优先。
     */
    @Getter
    @Setter
    private String langValue;

    /**
     * 本机器方块模型的<b>贴图</b>（六面同贴图，即 {@code cube_all}）。
     *
     * <p>
     * 数据生成时由 {@code OGMRMachineModelProvider} 读它，自动产出
     * {@code blockstates/<name>.json} + {@code models/block/<name>.json} + {@code models/item/<name>.json}
     * —— addon 不用再手写这三个 JSON。
     *
     * <p>
     * 为 null 时用模型 provider 的兜底贴图（默认 {@code minecraft:block/iron_block}），
     * 免得忘配就渲染成紫黑块。
     */
    @Getter
    @Setter
    private ResourceLocation modelTexture;

    // ═══════════════ 朝向 / 「口」 ═══════════════

    /** 朝向设定（默认 {@link RotationState#Y_AXIS}；仓室由 {@code PartBuilder} 改成 {@link RotationState#ALL}）。 */
    @Getter
    private RotationState rotationState = RotationState.Y_AXIS;

    /**
     * 「口」（仓室的开口面）的贴图；{@code null} = 没有口。
     *
     * <p>
     * 非空时数据生成会额外产出 4（或 6）个「底盘 + 口」的模型，并让 blockstate 按朝向挑模型 ——
     * 于是口<b>只画在朝向那一面</b>，而且贴在底盘之上（几何上比 16 稍微凸出一点点，深度测试必胜）。
     * 贴图由使用者在自己的资源包里给（本库附赠一张 {@link #DEFAULT_PORT_TEXTURE} 兜底）。
     */
    @Getter
    @Nullable
    private ResourceLocation portTexture;

    /** 口的贴图是否需要 alpha（默认 true → 模型用 {@code cutout} 渲染层）。 */
    @Getter
    private boolean portCutout = true;

    // ── 覆盖层贴图（照 GTM 的 overlay_front / overlay_front_emissive / IS_FORMED 那套拆的）──

    /**
     * 正面覆盖层（GTM 的 {@code overlay_front}）—— 画在<b>朝向那一面</b>、底盘之上。
     *
     * <p>多用于多方块控制器：底盘是外壳贴图，正面这一层才是「这是一台控制器」的花纹。
     */
    @Getter
    @Nullable
    private ResourceLocation overlayTexture;

    /**
     * 正面覆盖层的<b>发光</b>层（GTM 的 {@code overlay_front_emissive}）—— 只在机器
     * {@code active=true}（正在工作）时画，位置在普通覆盖层之上。
     */
    @Getter
    @Nullable
    private ResourceLocation emissiveOverlayTexture;

    /**
     * 成型覆盖层 —— 只在多方块 {@code formed=true} 时画（对应 GTM 模型属性 {@code IS_FORMED}）。
     *
     * <p>层序在普通覆盖层之上、发光层之下。
     */
    @Getter
    @Nullable
    private ResourceLocation formedOverlayTexture;

    /** 覆盖层是否走 {@code cutout} 渲染层（默认 true —— 覆盖层通常带透明像素）。 */
    @Getter
    private boolean overlayCutout = true;

    /** 本库自带的兜底覆盖层贴图（纯色描边；作者可以直接换掉）。 */
    public static final ResourceLocation DEFAULT_OVERLAY_TEXTURE = ResourceLocations.ogmr("block/machine/overlay_front_default");
    /** 本库自带的兜底「成型」覆盖层贴图。 */
    public static final ResourceLocation DEFAULT_FORMED_OVERLAY_TEXTURE = ResourceLocations.ogmr("block/machine/overlay_formed_default");
    /** 本库自带的兜底「发光」覆盖层贴图。 */
    public static final ResourceLocation DEFAULT_EMISSIVE_OVERLAY_TEXTURE = ResourceLocations.ogmr("block/machine/overlay_front_emissive_default");

    /** 本库自带的兜底「口」贴图（纯色描边；作者可以直接换掉）。 */
    public static final ResourceLocation DEFAULT_PORT_TEXTURE = ResourceLocations.ogmr("block/machine/port_default");

    /**
     * 正在构造的机器定义 —— 给 {@code MachineBlock} 的构造器用。
     *
     * <p>
     * ⚠️ 这不是多此一举：原版 {@code Block} 的构造器里就会调 {@code createBlockStateDefinition}
     * （方块状态表必须在 {@code super()} 里就建好），那时候子类字段（{@code MachineBlock.definition}）
     * 还没赋值，只能走这个静态引用。GTM 的 {@code MachineDefinition.getBuilt()} 是同一招，
     * 由 {@code MachineBuilder#register()} 在「建方块」前后包一下。
     */
    @Nullable
    private static volatile MachineDefinition building;

    /** 取「正在构造的机器定义」；不在构造期时为 {@code null}。 */
    @Nullable
    public static MachineDefinition getBuilt() {
        return building;
    }

    /** 标记进入「构造某个定义的方块」阶段（由 builder 调用，别自己用）。 */
    public static void beginBuild(MachineDefinition definition) {
        building = definition;
    }

    /** 结束构造阶段。 */
    public static void endBuild() {
        building = null;
    }
    /** 物品 tooltip 的附加行生成器（第二个参数是待追加的列表）。 */
    @Getter
    @Setter
    private BiConsumer<ItemStack, List<Component>> tooltipBuilder = (stack, tooltip) -> {};
    @Getter
    @Setter
    private int defaultPaintingColor = -1;
    /**
     * 可编辑机器 UI（需求 1）。
     *
     * <p>
     * 非空时，机器面板会先尝试加载 {@code assets/<namespace>/ui/machine/<uiPath>.mui}
     * 里的自定义布局；没有自定义文件时才用 {@link rain.fox.ogmr.api.gui.MachineUI} 的默认布局。
     * 由 {@code MachineUI#buildEditable()} 产出、builder 回填。
     *
     * <p>
     * 未设置时为 {@code null}（面板走默认布局）。
     */
    @Getter
    @Setter
    private rain.fox.ogmr.api.gui.editor.EditableMachineUI editableUI;

    /**
     * 本机器的界面（需求 1）。
     *
     * <p>
     * 非空时右键机器就能打开它（见 {@code MetaMachine#tryToOpenUI}）。
     * builder 没显式给 UI 时会给一个 {@code MachineUI.createDefault(...)} 的零配置界面
     * （标题 + 玩家背包 + 按机器仓储自动摆的槽位），所以「注册了机器却没有界面」不会发生。
     */
    @Getter
    private rain.fox.ogmr.api.gui.MachineUI machineUI;

    /**
     * 是否把方块渲染交给方块实体（BER）。
     *
     * <p>
     * 默认 {@code false}：方块走<b>静态模型</b>（数据生成的
     * {@code blockstates/<name>.json} + {@code models/block/<name>.json}），这是本库的默认路径。
     * 只有你自己注册了 BER（动态模型、旋转覆盖层之类）才把它设成 true ——
     * 设 true 而没注册 BER，方块会<b>什么都不画</b>（表现为「贴图是空的」，物品栏里却正常）。
     */
    @Getter
    private boolean useEntityRenderer;

    public MachineDefinition(ResourceLocation id) {
        this.id = id;
    }

    // ═══════════════ 注册结果 ═══════════════

    public Block getBlock() {
        return blockSupplier.get();
    }

    public Item getItem() {
        return itemSupplier.get();
    }

    public BlockEntityType<?> getBlockEntityType() {
        return blockEntityTypeSupplier.get();
    }

    /** 由 definition 创建这台机器的运行时实例（Builder 里注入）。 */
    public MetaMachine createMetaMachine(IMachineBlockEntity holder) {
        return machineSupplier.apply(holder);
    }

    // ═══════════════ 基本信息 ═══════════════

    @Override
    public Block get() {
        return getBlock();
    }

    /** 机器名 = id 的 path（例如 {@code ogmr:lv_macerator} → {@code lv_macerator}）。 */
    public String getName() {
        return id.getPath();
    }

    /** 方块的翻译键（物品 tooltip / 语言文件用）。 */
    public String getDescriptionId() {
        return getBlock().getDescriptionId();
    }

    @Override
    public String toString() {
        return id.toString();
    }

    // ═══════════════ 等级 / 配方 ═══════════════

    public MachineDefinition setRecipeTypes(OGMRRecipeType... types) {
        this.recipeTypes = types == null ? new OGMRRecipeType[0] : types;
        return this;
    }

    // ═══════════════ UI / 渲染 ═══════════════

    /** 设置界面（链式；同时把可编辑 UI 句柄一并挂上，两个通道指向同一个布局）。 */
    public MachineDefinition setMachineUI(rain.fox.ogmr.api.gui.MachineUI ui) {
        this.machineUI = ui;
        if (ui != null) {
            this.editableUI = ui.buildEditable();
        }
        return this;
    }

    /** 是否有可打开的界面。 */
    public boolean hasUI() {
        return machineUI != null;
    }

    /** 链式设置 {@link #isUseEntityRenderer()}。 */
    public MachineDefinition setUseEntityRenderer(boolean useEntityRenderer) {
        this.useEntityRenderer = useEntityRenderer;
        return this;
    }

    // ═══════════════ 朝向 / 口 ═══════════════

    /** 链条版：设置朝向设定。 */
    public MachineDefinition setRotationState(RotationState rotationState) {
        this.rotationState = rotationState == null ? RotationState.NONE : rotationState;
        return this;
    }

    /** 是否有朝向属性（{@link RotationState#NONE} 时为 false）。 */
    public boolean hasFacing() {
        return rotationState.hasFacing();
    }

    /**
     * 从方块状态里读朝向 —— <b>读朝向一律走这里</b>。
     *
     * <p>
     * 因为朝向属性是随 {@link RotationState} 变的（{@code Y_AXIS} 用 {@code horizontal_facing}、
     * {@code ALL} 用 {@code facing}），直接 {@code state.getValue(MachineBlock.FACING)} 对
     * 六向的仓室会抛 {@code IllegalArgumentException}。
     */
    public Direction getFacing(BlockState state) {
        if (!rotationState.hasFacing() || state == null || !state.hasProperty(rotationState.getProperty())) {
            return Direction.NORTH;
        }
        return state.getValue(rotationState.getProperty());
    }

    /** 链条版：设置「口」的贴图（传 {@code null} 表示没有口）。 */
    public MachineDefinition setPortTexture(@Nullable ResourceLocation portTexture) {
        this.portTexture = portTexture;
        return this;
    }

    /** 链条版：设置「口」是否走 cutout 渲染层。 */
    public MachineDefinition setPortCutout(boolean portCutout) {
        this.portCutout = portCutout;
        return this;
    }

    /** 是否有「口」。 */
    public boolean hasPort() {
        return portTexture != null;
    }

    // ═══════════════ 覆盖层 ═══════════════

    /** 链条版：设置正面覆盖层。 */
    public MachineDefinition setOverlayTexture(@Nullable ResourceLocation overlayTexture) {
        this.overlayTexture = overlayTexture;
        return this;
    }

    /** 链条版：设置「正在工作」时显示的发光覆盖层。 */
    public MachineDefinition setEmissiveOverlayTexture(@Nullable ResourceLocation emissiveOverlayTexture) {
        this.emissiveOverlayTexture = emissiveOverlayTexture;
        return this;
    }

    /** 链条版：设置「多方块成型」时显示的覆盖层。 */
    public MachineDefinition setFormedOverlayTexture(@Nullable ResourceLocation formedOverlayTexture) {
        this.formedOverlayTexture = formedOverlayTexture;
        return this;
    }

    /** 链条版：覆盖层是否走 cutout 渲染层。 */
    public MachineDefinition setOverlayCutout(boolean overlayCutout) {
        this.overlayCutout = overlayCutout;
        return this;
    }

    /** 是否有任何覆盖层（正面 / 成型 / 发光）。 */
    public boolean hasOverlay() {
        return overlayTexture != null || formedOverlayTexture != null || emissiveOverlayTexture != null;
    }

    /**
     * 是否有任何「贴在朝向那一面」的额外层（覆盖层或口）。
     *
     * <p>数据生成据此决定要不要产出「按朝向挑模型」的 blockstate —— cube_all 是六面同贴图，
     * 只有这些层才让朝向在视觉上有意义。
     */
    public boolean hasFacingLayers() {
        return hasOverlay() || hasPort();
    }

    // ═══════════════ 外观 / 碰撞箱 ═══════════════

    /**
     * 取指定朝向下的碰撞箱。
     *
     * <p>
     * 空形状或满方块直接返回原形状（不旋转）；否则按方向缓存旋转结果。
     *
     * <p>
     * 注意：缓存是普通 EnumMap（和 GTM 一致）。服务端/客户端可能并发首次访问，但计算是纯函数、
     * 结果相同，所以即使重复计算也无害。
     */
    public VoxelShape getShape(Direction direction) {
        if (shape.isEmpty() || Shapes.block().equals(shape) || direction == Direction.NORTH) {
            return shape;
        }
        return this.shapeCache.computeIfAbsent(direction, dir -> ShapeUtils.rotate(shape, dir));
    }

    public MachineDefinition setShape(VoxelShape shape) {
        this.shape = shape;
        this.shapeCache.clear();
        return this;
    }

    public ItemStack asStack() {
        return new ItemStack(getItem());
    }

    public ItemStack asStack(int count) {
        return new ItemStack(getItem(), count);
    }

    /** 默认方块状态（未摆放时的状态）。 */
    public BlockState defaultBlockState() {
        return getBlock().defaultBlockState();
    }

    // ═══════════════ equals / hashCode ═══════════════

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        MachineDefinition that = (MachineDefinition) o;
        return id.equals(that.id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }
}
