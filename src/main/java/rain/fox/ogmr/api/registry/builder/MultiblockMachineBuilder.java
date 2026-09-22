package rain.fox.ogmr.api.registry.builder;

import rain.fox.ogmr.api.machine.IMachineBlockEntity;
import rain.fox.ogmr.api.machine.MultiblockMachineDefinition;
import rain.fox.ogmr.api.machine.multiblock.IMultiController;
import rain.fox.ogmr.api.machine.multiblock.IMultiPart;
import rain.fox.ogmr.api.machine.multiblock.MultiblockControllerMachine;
import rain.fox.ogmr.api.pattern.BlockPattern;
import rain.fox.ogmr.api.pattern.MultiblockShapeInfo;
import rain.fox.ogmr.api.registry.MachineRegistrar;
import rain.fox.ogmr.api.registry.OGMRRegistries;

import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ItemLike;
import net.minecraft.world.level.block.state.BlockState;

import org.apache.commons.lang3.function.TriFunction;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * 多方块注册 builder —— 需求 1 的核心。
 *
 * <p>
 * 相比 {@link MachineBuilder} 多出的是「结构」与「显示」两块：
 * <ul>
 * <li>{@link #pattern(Function)} —— 结构图案（{@link BlockPattern}），<b>必填</b>，没给会抛异常；</li>
 * <li>{@link #shapeInfo}/{@link #shapeInfos} —— JEI / 世界内预览用的示例结构（需求 6 的预览界面就读它）；</li>
 * <li>{@link #appearanceBlock} —— 机器外壳方块，JEI 预览与仓室外观都用它；</li>
 * <li>{@link #partAppearance} / {@link #partSorter} —— 仓室在结构里的显示与排序；</li>
 * <li>{@link #additionalDisplay} —— 结构成型后写进机器面板的额外文本。</li>
 * </ul>
 *
 * <pre>{@code
 * public static final MultiblockMachineDefinition FOUNDRY =
 *     REGISTRAR.multiblock("foundry", FoundryMachine::new)
 *         .recipeType(MyRecipeTypes.FOUNDRY)
 *         .appearanceBlock(CASING_STEEL)
 *         .pattern(def -> FactoryBlockPattern.start()
 *                 .aisle("XXX", "XXX", "XXX")
 *                 .aisle("X#X", "X X", "X#X")
 *                 .aisle("XSX", "XXX", "XXX")
 *                 .where('S', Predicates.controller(def.getBlock()))
 *                 .where('X', Predicates.blocks(CASING_STEEL)
 *                         .or(Predicates.autoAbilities(def.getRecipeTypes())))
 *                 .where('#', Predicates.air())
 *                 .build())
 *         .register();
 * }</pre>
 */
public class MultiblockMachineBuilder<D extends MultiblockMachineDefinition,
        B extends MultiblockMachineBuilder<D, B>> extends MachineBuilder<D, B> {

    protected boolean generator;
    /** 用户是否显式调过 {@link #generator(boolean)}；没调过才允许按配方类型的能量方向自动判定。 */
    protected boolean generatorExplicit;
    protected Function<MultiblockMachineDefinition, BlockPattern> pattern;
    protected final List<Function<MultiblockMachineDefinition, List<MultiblockShapeInfo>>> shapeInfos = new ArrayList<>();
    /** 只有「控制器可能与墙共用」的多方块才需要关掉翻转。 */
    protected boolean allowFlip = true;
    protected boolean renderXEIPreview = true;
    protected final List<Supplier<ItemStack[]>> recoveryItems = new ArrayList<>();
    protected Function<MultiblockControllerMachine, Comparator<IMultiPart>> partSorter = c -> (a, b) -> 0;
    protected TriFunction<IMultiController, IMultiPart, Direction, BlockState> partAppearance;
    protected BiConsumer<IMultiController, List<Component>> additionalDisplay = (m, l) -> {};

    @SuppressWarnings("unchecked")
    public MultiblockMachineBuilder(MachineRegistrar registrar, String name,
                                    Function<IMachineBlockEntity, ? extends MultiblockControllerMachine> machineFactory) {
        // ⚠️ D 是 F-bounded 的类型变量，构造器引用推不出来，必须显式转型
        super(registrar, name, machineFactory::apply, id -> (D) new MultiblockMachineDefinition(id));
    }

    // ═══════════════ 链式配置 ═══════════════

    /** 结构图案工厂；<b>必填</b>。 */
    public B pattern(Function<MultiblockMachineDefinition, BlockPattern> pattern) {
        this.pattern = pattern;
        return self();
    }

    /**
     * 是否发电多方块（影响面板文本与默认行为）。
     *
     * <p>
     * 一般<b>不用显式调</b>：如果这台机器配的配方类型把
     * {@link rain.fox.ogmr.api.recipe.OGMRRecipeType#energyIO(rain.fox.ogmr.api.pattern.util.IO)}
     * 声明成了 {@code OUT}（发电机），{@code register()} 会自动把它标成发电机。
     * 只有在「配方类型没声明、但机器确实发电」这种特殊情况下才需要显式调 —— 调过之后就以你为准。
     */
    public B generator(boolean generator) {
        this.generator = generator;
        this.generatorExplicit = true;
        return self();
    }

    /** 是否允许控制器朝向/结构翻转；只有可能「与墙共用控制器」的多方块才需要设 false。 */
    public B allowFlip(boolean allowFlip) {
        this.allowFlip = allowFlip;
        return self();
    }

    /** 是否在 JEI/EMI 里显示结构预览（需求 6）。 */
    public B renderXEIPreview(boolean render) {
        this.renderXEIPreview = render;
        return self();
    }

    /** 加一个预览用示例结构。 */
    public B shapeInfo(Function<MultiblockMachineDefinition, MultiblockShapeInfo> shape) {
        this.shapeInfos.add(def -> List.of(shape.apply(def)));
        return self();
    }

    /** 加一组预览用示例结构（例如按线圈等级铺开好几页）。 */
    public B shapeInfos(Function<MultiblockMachineDefinition, List<MultiblockShapeInfo>> shapes) {
        this.shapeInfos.add(shapes);
        return self();
    }

    /** 拆机时返还的物品（例如残留的线圈）。 */
    public B recoveryItems(Supplier<ItemLike[]> items) {
        this.recoveryItems.add(() -> Arrays.stream(items.get())
                .map(ItemLike::asItem).map(Item::getDefaultInstance).toArray(ItemStack[]::new));
        return self();
    }

    public B recoveryStacks(Supplier<ItemStack[]> stacks) {
        this.recoveryItems.add(stacks);
        return self();
    }

    /** 仓室在结构里的排序（决定面板里仓室的顺序、也影响配方匹配优先级）。 */
    public B partSorter(Function<MultiblockControllerMachine, Comparator<IMultiPart>> sorter) {
        this.partSorter = sorter;
        return self();
    }

    public B partSorter(Comparator<IMultiPart> sorter) {
        this.partSorter = c -> sorter;
        return self();
    }

    /** 仓室在结构里显示成什么方块（默认 = 控制器外观）。 */
    public B partAppearance(TriFunction<IMultiController, IMultiPart, Direction, BlockState> appearance) {
        this.partAppearance = appearance;
        return self();
    }

    /** 面板里的额外文本。 */
    public B additionalDisplay(BiConsumer<IMultiController, List<Component>> display) {
        this.additionalDisplay = display;
        return self();
    }

    // ═══════════════ 注册 ═══════════════

    @Override
    public D register() {
        if (pattern == null) {
            throw new IllegalStateException("多方块 " + name + " 缺少结构图案：必须调用 .pattern(...)");
        }

        D definition = super.register();

        // 没显式声明过 generator 时，按配方类型的能量方向自动判定：
        // 只要有一个配方类型声明了 OUT（发电机），这台机器就是发电机。
        // 这样 addon 只要把「发电机配方类型」写对，机器类型就自动是对的（见 OGMRRecipeType#energyIO）。
        if (!generatorExplicit) {
            for (var type : recipeTypes) {
                if (type != null && type.isGenerator()) {
                    generator = true;
                    break;
                }
            }
        }

        definition.setGenerator(generator);
        // setPatternFactory 要的是 Supplier<BlockPattern>，而 pattern 是 Function<定义, BlockPattern>
        definition.setPatternFactory(() -> pattern.apply(definition));
        definition.setShapes(() -> shapeInfos.stream()
                .map(factory -> factory.apply(definition))
                .flatMap(List::stream)
                .toList());
        definition.setAllowFlip(allowFlip);
        definition.setRenderXEIPreview(renderXEIPreview);
        if (!recoveryItems.isEmpty()) {
            definition.setRecoveryItems(() -> recoveryItems.stream()
                    .map(Supplier::get).flatMap(Arrays::stream).toArray(ItemStack[]::new));
        }
        definition.setPartSorter(partSorter);
        if (partAppearance == null) {
            definition.setPartAppearance((controller, part, side) -> definition.getAppearance().get());
        } else {
            definition.setPartAppearance(partAppearance);
        }
        definition.setAdditionalDisplay(additionalDisplay);

        // 多方块单独进一张表，方便遍历（需求 6 的 JEI 预览就只遍历这张表）
        OGMRRegistries.MULTIBLOCKS.register(id, definition);
        logRegistered();
        return definition;
    }
}
