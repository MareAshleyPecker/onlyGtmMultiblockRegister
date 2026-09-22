package rain.fox.ogmr.api.machine;

import rain.fox.ogmr.api.machine.multiblock.IMultiController;
import rain.fox.ogmr.api.machine.multiblock.IMultiPart;
import rain.fox.ogmr.api.machine.multiblock.MultiblockControllerMachine;
import rain.fox.ogmr.api.pattern.BlockPattern;
import rain.fox.ogmr.api.pattern.MultiblockShapeInfo;

import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

import lombok.Getter;
import lombok.Setter;

import org.apache.commons.lang3.function.TriFunction;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * 本文件是从 GTM 的 {@code com.gregtechceu.gtceu.api.machine.MultiblockMachineDefinition}
 * 精简拆出来的。
 *
 * <p>
 * 在 {@link MachineDefinition} 之上补上多方块专属的注册信息：结构图案、JEI/REI 预览用的形状列表、
 * 仓室排序器、额外 tooltip、仓室外观、回收物品……
 *
 * <p>
 * 与 GTM 的差别：没有 {@code GTRecipeType}（改成 {@code OGMRRecipeType}，在父类里）、
 * 没有 {@code IParallelHatch} / {@code MultiblockState} 相关字段。
 */
@Getter
@Setter
public class MultiblockMachineDefinition extends MachineDefinition {

    // ── 结构相关 ──
    /** 是否发电机（决定 UI/工作逻辑的走向；本精简版只登记标记，不改变行为）。 */
    private boolean generator;
    /** 结构图案工厂；由 builder 设置，运行期 {@code MultiblockControllerMachine#getPattern()} 会调用它。 */
    private Supplier<BlockPattern> patternFactory = () -> null;
    /** 手工指定的预览形状列表；为空时由 {@link #getMatchingShapes()} 从图案展开。 */
    private Supplier<List<MultiblockShapeInfo>> shapes = List::of;
    /** 是否允许翻转成型（墙共享控制器那种结构要关掉）。 */
    private boolean allowFlip;
    /** 是否在 XEI（JEI/REI）里渲染结构预览。 */
    private boolean renderXEIPreview;
    /** 结构被破坏时返还的物品（例如各种仓室）。 */
    private Supplier<ItemStack[]> recoveryItems = () -> new ItemStack[0];

    // ── 仓室相关 ──
    /** 仓室排序器（影响 {@code getParts()} 的顺序，UI 里按这个顺序展示）。 */
    private Function<MultiblockControllerMachine, Comparator<IMultiPart>> partSorter;
    /** 仓室在成型状态下的外观（例如外观统一成控制器指定的外壳）。 */
    private TriFunction<IMultiController, IMultiPart, Direction, BlockState> partAppearance;
    /** 追加到控制器 display text 末尾的内容（一般是仓室汇总信息）。 */
    private BiConsumer<IMultiController, List<Component>> additionalDisplay = (controller, list) -> {};

    public MultiblockMachineDefinition(ResourceLocation id) {
        super(id);
    }

    // ═══════════════ 预览形状展开 ═══════════════

    /**
     * 取用于 XEI 预览的形状列表。
     *
     * <p>
     * 逻辑（照 GTM 原逻辑）：{@code shapes} 非空就直接用；否则从结构图案的
     * {@code aisleRepetitions}（每一「层」允许重复的最小/最大次数）做 DFS 展开，每个组合
     * 生成一张预览图。
     *
     * <p>
     * TODO(ogmr): 这里依赖 {@code rain.fox.ogmr.api.pattern} 包（由另一位同事实现）的这三样东西：
     * <ol>
     * <li>{@code BlockPattern.aisleRepetitions}：{@code int[][]}，每行是 {@code [min, max]}；</li>
     * <li>{@code BlockPattern#getPreview(int[] repetition)}：按重复次数生成预览方块矩阵；</li>
     * <li>{@code new MultiblockShapeInfo(BlockInfo[][][])}：包装成预览对象。</li>
     * </ol>
     * 如果那边的签名和上面不一致（例如 {@code getPreview} 返回类型变了、或构造器参数变了），
     * 直接把本方法改成 {@code return shapes.get();} 即可，其余逻辑不受影响。
     */
    public List<MultiblockShapeInfo> getMatchingShapes() {
        List<MultiblockShapeInfo> designs = shapes.get();
        if (designs != null && !designs.isEmpty()) return designs;

        BlockPattern pattern = patternFactory.get();
        if (pattern == null) return List.of();

        int[][] aisleRepetitions = pattern.aisleRepetitions;
        List<MultiblockShapeInfo> pages = new ArrayList<>();
        repetitionDFS(pattern, pages, aisleRepetitions, new ArrayList<>());
        return pages;
    }

    /** DFS：把每一层的重复次数从 min 枚举到 max，凑齐一层就生成一张预览图。 */
    private void repetitionDFS(BlockPattern pattern, List<MultiblockShapeInfo> pages, int[][] aisleRepetitions,
                               List<Integer> repetitionStack) {
        if (repetitionStack.size() == aisleRepetitions.length) {
            int[] repetition = new int[repetitionStack.size()];
            for (int i = 0; i < repetitionStack.size(); i++) {
                repetition[i] = repetitionStack.get(i);
            }
            pages.add(new MultiblockShapeInfo(pattern.getPreview(repetition)));
            return;
        }
        int depth = repetitionStack.size();
        for (int i = aisleRepetitions[depth][0]; i <= aisleRepetitions[depth][1]; i++) {
            repetitionStack.add(i);
            repetitionDFS(pattern, pages, aisleRepetitions, repetitionStack);
            repetitionStack.remove(repetitionStack.size() - 1);
        }
    }
}
