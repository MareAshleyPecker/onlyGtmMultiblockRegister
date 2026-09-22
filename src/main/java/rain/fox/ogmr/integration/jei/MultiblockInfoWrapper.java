package rain.fox.ogmr.integration.jei;

import rain.fox.ogmr.Ogmr;
import rain.fox.ogmr.api.machine.MultiblockMachineDefinition;
import rain.fox.ogmr.api.pattern.MultiblockShapeInfo;

import lombok.Getter;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;

import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * JEI 里的一条「多方块预览配方」= <b>一台多方块</b>。
 *
 * <p>
 * 关于 shape 的粒度：GTM 的做法是「一台机器 → 一个 wrapper，wrapper 内部自己翻页」，
 * 于是 wrapper 里同时装着 {@code MBPattern[]} 和一个 {@code index}。本类沿用同样的形态 ——
 * 一台多方块一个 wrapper，内部持有 {@code shapes} 列表与当前 {@code shapeIndex}：
 * <ul>
 * <li>好处一：JEI 的「上一/下一个配方」不会被同一台机器的 5 个变体刷屏；</li>
 * <li>好处二：翻页只是改一个 int，不需要跟 JEI 的 recipe manager 打交道，
 * 左右箭头按钮因此可以做成真正的按钮；</li>
 * <li>好处三：每个 shape 各有一份 {@link StructurePreviewDrawable}，
 * 于是转到某个 shape 上转了几圈这件事会按 shape 各自记住。</li>
 * </ul>
 *
 * <p>
 * 本类<b>不 import 任何 JEI 类型</b>，是纯粹的「配方数据 + 结构画布」承载物，方便单测与复用。
 */
public class MultiblockInfoWrapper {

    /** 配方背景宽（GTM 的多方块预览也是 160）。 */
    public static final int WIDTH = 160;

    /** 配方背景高。 */
    public static final int HEIGHT = 160;

    /** 结构预览框：左边距。 */
    public static final int PREVIEW_X = 4;

    /** 结构预览框：上边距。 */
    public static final int PREVIEW_Y = 28;

    /** 结构预览框：宽。 */
    public static final int PREVIEW_W = 152;

    /** 结构预览框：高。 */
    public static final int PREVIEW_H = 104;

    /** 这一条配方对应的多方块定义。 */
    public final MultiblockMachineDefinition definition;

    /** 控制器物品；拿不到时为 EMPTY（那种情况下分类不会给它开槽）。 */
    public final ItemStack controllerStack;

    /** 每个 shape 一份画布，顺序与 {@code definition.getMatchingShapes()} 一致。 */
    private final List<StructurePreviewDrawable> previews;

    /** 当前显示第几个 shape（当前是第几份图案，0 起）。 */
    @Getter
    private int shapeIndex;

    public MultiblockInfoWrapper(MultiblockMachineDefinition definition) {
        this.definition = definition;
        this.controllerStack = resolveControllerStack(definition);

        List<MultiblockShapeInfo> shapes = safeShapes(definition);
        List<StructurePreviewDrawable> built = new ArrayList<>(shapes.size());
        for (MultiblockShapeInfo shape : shapes) {
            // shape 允许为 null 元素：of() 会退化成一个空画布而不是抛异常
            built.add(StructurePreviewDrawable.of(shape, PREVIEW_W, PREVIEW_H));
        }
        this.previews = List.copyOf(built);
    }

    // ═══════════════════════ shape 翻页 ═══════════════════════

    /** 这台机器登记了几份结构图案；0 表示「没登记」（只在 JEI 里显示一句提示，不会崩）。 */
    public int getShapeCount() {
        return previews.size();
    }

    public boolean hasShape() {
        return !previews.isEmpty();
    }

    /** 下一份图案，到底了绕回第一份。 */
    public void nextShape() {
        if (previews.isEmpty()) return;
        shapeIndex = (shapeIndex + 1) % previews.size();
    }

    /** 上一份图案，到头了绕到最后一份。 */
    public void prevShape() {
        if (previews.isEmpty()) return;
        shapeIndex = (shapeIndex - 1 + previews.size()) % previews.size();
    }

    /** 当前 shape 的画布；没登记 shape 时返回 null。 */
    @Nullable
    public StructurePreviewDrawable currentPreview() {
        if (previews.isEmpty()) return null;
        return previews.get(Math.min(shapeIndex, previews.size() - 1));
    }

    // ═══════════════════════ 兜底取数 ═══════════════════════

    /**
     * 取结构图案列表，<b>永远不返回 null</b>。
     *
     * <p>
     * 任务里明确要求「shape 列表可能为空，为空时不要崩」。这里把 null 与异常都收敛成空列表：
     * 一台机器在注册过程中形状还没填好是完全可能的，JEI 预览不该因此把整个 JEI 拖崩。
     */
    private static List<MultiblockShapeInfo> safeShapes(MultiblockMachineDefinition definition) {
        try {
            List<MultiblockShapeInfo> shapes = definition.getMatchingShapes();
            return shapes == null ? List.of() : List.copyOf(shapes);
        } catch (RuntimeException e) {
            Ogmr.LOGGER.warn("ogmr: multiblock {} has no usable JEI preview shape", definition, e);
            return List.of();
        }
    }

    /**
     * 取控制器物品。
     *
     * <p>
     * 依次尝试 {@code asStack()} → 方块本身的物品形式 → {@link Items#BARRIER}（空）。
     * 之所以要兜底：{@code asStack()} 在定义还没完全建好时会返回空，
     * 而 JEI 的槽位不接受空 ItemStack。
     */
    private static ItemStack resolveControllerStack(MultiblockMachineDefinition definition) {
        try {
            ItemStack stack = definition.asStack();
            if (stack != null && !stack.isEmpty()) return stack;
        } catch (RuntimeException e) {
            Ogmr.LOGGER.warn("ogmr: multiblock {} could not produce an item stack for the JEI preview",
                    definition, e);
        }
        try {
            Block block = definition.getBlock();
            if (block != null) {
                ItemStack fromBlock = new ItemStack(block);
                if (!fromBlock.isEmpty()) return fromBlock;
            }
        } catch (RuntimeException e) {
            Ogmr.LOGGER.warn("ogmr: multiblock {} has no resolvable block for the JEI preview", definition, e);
        }
        return ItemStack.EMPTY;
    }
}
