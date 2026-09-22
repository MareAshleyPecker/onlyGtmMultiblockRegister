package rain.fox.ogmr.api.machine;

import com.lowdragmc.lowdraglib.utils.ShapeUtils;
import rain.fox.ogmr.api.recipe.OGMRRecipeType;

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
