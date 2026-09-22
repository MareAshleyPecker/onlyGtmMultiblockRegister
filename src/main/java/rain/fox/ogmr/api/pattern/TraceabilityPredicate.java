package rain.fox.ogmr.api.pattern;

import rain.fox.ogmr.api.pattern.predicates.SimplePredicate;
import rain.fox.ogmr.api.pattern.util.IO;

import com.lowdragmc.lowdraglib.utils.BlockInfo;

import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * 结构图案里「一个字符」对应的匹配规则集合（common + limited 两组 predicate）。
 *
 * <p>
 * 从 GTM 的 {@code com.gregtechceu.gtceu.api.pattern.TraceabilityPredicate} 拆出。
 * 所有链式方法都保留、语义一致：
 * {@code setMinGlobalLimited / setMaxGlobalLimited / setMinLayerLimited / setMaxLayerLimited /
 * setExactLimit / setPreviewCount / setController / setIO / setNBTParser / setSlotName /
 * disableRenderFormed / addTooltips / or / sort / isAny / isAir / isSingle / hasAir / addCache / test}。
 *
 * <p>
 * 与 GTM 版本的差异（去 GT 化）：
 * <ul>
 * <li>{@code com.gregtechceu.gtceu.api.capability.recipe.IO} → 本库的
 * {@link rain.fox.ogmr.api.pattern.util.IO}；</li>
 * <li>GTM 7.5.3 的 TraceabilityPredicate 本身没有 {@code and(...)} / {@code limitTier(...)} /
 * {@code countGlobal(...)}（这些是更早版本 GTCEu 的方法，且 {@code and} 在「common 内部本就是 OR」的
 * 模型里无法表达真 AND），因此本库同样不提供，避免造出语义不对的 API。
 * 全局计数请用 {@code setMinGlobalLimited}/{@code setMaxGlobalLimited}，仓室档位请用
 * {@code Predicates.ability(PartAbility, int)}。</li>
 * </ul>
 */
public class TraceabilityPredicate {

    /** 无数量限制的规则（OR 关系）。 */
    public List<SimplePredicate> common = new ArrayList<>();
    /** 有数量限制的规则（受 minCount/maxCount/minLayerCount/maxLayerCount 约束）。 */
    public List<SimplePredicate> limited = new ArrayList<>();
    /** 该字符是否代表多方块控制器本体。 */
    public boolean isController;

    public TraceabilityPredicate() {}

    public TraceabilityPredicate(TraceabilityPredicate predicate) {
        common.addAll(predicate.common);
        limited.addAll(predicate.limited);
        isController = predicate.isController;
    }

    public TraceabilityPredicate(Predicate<MultiblockState> predicate, Supplier<BlockInfo[]> candidates) {
        common.add(new SimplePredicate(predicate, candidates));
    }

    public TraceabilityPredicate(SimplePredicate simplePredicate) {
        if (simplePredicate.minCount != -1 || simplePredicate.maxCount != -1) {
            limited.add(simplePredicate);
        } else {
            common.add(simplePredicate);
        }
    }

    /**
     * 标记该字符为多方块控制器。一般不用自己调，用 {@code Predicates.controller(...)} 即可。
     */
    public TraceabilityPredicate setController() {
        isController = true;
        return this;
    }

    /** 按全局下限排序（limited 里 minCount 小的排前面，成型顺序更稳定）。 */
    public TraceabilityPredicate sort() {
        limited.sort(Comparator.comparingInt(a -> a.minCount));
        return this;
    }

    /**
     * 给候选方块加 tooltip，显示在结构预览 / JEI-EMI 页面上。
     */
    public TraceabilityPredicate addTooltips(Component... tips) {
        if (tips.length > 0) {
            List<Component> tooltips = Arrays.stream(tips).toList();
            common.forEach(predicate -> {
                if (predicate.candidates == null) return;
                if (predicate.toolTips == null) {
                    predicate.toolTips = new ArrayList<>();
                }
                predicate.toolTips.addAll(tooltips);
            });
            limited.forEach(predicate -> {
                if (predicate.candidates == null) return;
                if (predicate.toolTips == null) {
                    predicate.toolTips = new ArrayList<>();
                }
                predicate.toolTips.addAll(tooltips);
            });
        }
        return this;
    }

    /**
     * 设置候选方块的最小全局数量。
     */
    public TraceabilityPredicate setMinGlobalLimited(int min) {
        limited.addAll(common);
        common.clear();
        for (SimplePredicate predicate : limited) {
            predicate.minCount = min;
        }
        return this;
    }

    public TraceabilityPredicate setMinGlobalLimited(int min, int previewCount) {
        return this.setMinGlobalLimited(min).setPreviewCount(previewCount);
    }

    /**
     * 设置候选方块的最大全局数量。
     */
    public TraceabilityPredicate setMaxGlobalLimited(int max) {
        limited.addAll(common);
        common.clear();
        for (SimplePredicate predicate : limited) {
            predicate.maxCount = max;
        }
        return this;
    }

    public TraceabilityPredicate setMaxGlobalLimited(int max, int previewCount) {
        return this.setMaxGlobalLimited(max).setPreviewCount(previewCount);
    }

    /**
     * 设置每层（每条 aisle）里候选方块的最小数量。
     */
    public TraceabilityPredicate setMinLayerLimited(int min) {
        limited.addAll(common);
        common.clear();
        for (SimplePredicate predicate : limited) {
            predicate.minLayerCount = min;
        }
        return this;
    }

    public TraceabilityPredicate setMinLayerLimited(int min, int previewCount) {
        return this.setMinLayerLimited(min).setPreviewCount(previewCount);
    }

    /**
     * 设置每层（每条 aisle）里候选方块的最大数量。
     */
    public TraceabilityPredicate setMaxLayerLimited(int max) {
        limited.addAll(common);
        common.clear();
        for (SimplePredicate predicate : limited) {
            predicate.maxLayerCount = max;
        }
        return this;
    }

    public TraceabilityPredicate setMaxLayerLimited(int max, int previewCount) {
        return this.setMaxLayerLimited(max).setPreviewCount(previewCount);
    }

    /**
     * 把全局上下限设成同一个值（等价于 {@code setMinGlobalLimited(limit).setMaxGlobalLimited(limit)}）。
     *
     * @param limit 上限与下限
     */
    public TraceabilityPredicate setExactLimit(int limit) {
        return this.setMinGlobalLimited(limit).setMaxGlobalLimited(limit);
    }

    /**
     * 设置它在预览里出现的次数（只影响结构预览，不影响匹配）。
     */
    public TraceabilityPredicate setPreviewCount(int count) {
        common.forEach(predicate -> predicate.previewCount = count);
        limited.forEach(predicate -> predicate.previewCount = count);
        return this;
    }

    /**
     * 成型后不再渲染这些方块（写入 matchContext 的 "renderMask"）。
     */
    public TraceabilityPredicate disableRenderFormed() {
        common.forEach(predicate -> predicate.disableRenderFormed = true);
        limited.forEach(predicate -> predicate.disableRenderFormed = true);
        return this;
    }

    /**
     * 设置该字符代表的位置的 IO 方向（{@link MultiblockState#io}）。
     */
    public TraceabilityPredicate setIO(IO io) {
        common.forEach(predicate -> predicate.io = io);
        limited.forEach(predicate -> predicate.io = io);
        return this;
    }

    /** 用正则匹配方块实体 NBT（如 {@code "{Energy:.*}"}）。 */
    public TraceabilityPredicate setNBTParser(String nbtParser) {
        common.forEach(predicate -> predicate.nbtParser = nbtParser);
        limited.forEach(predicate -> predicate.nbtParser = nbtParser);
        return this;
    }

    /** 给该字符的位置打上槽位名（用于多方块内部槽位映射）。 */
    public TraceabilityPredicate setSlotName(String slotName) {
        common.forEach(predicate -> predicate.slotName = slotName);
        limited.forEach(predicate -> predicate.slotName = slotName);
        return this;
    }

    /** 该位置的方块是否满足本规则，并把错误写回 worldState。 */
    public boolean test(MultiblockState blockWorldState) {
        blockWorldState.io = IO.BOTH;
        boolean flag = false;
        for (SimplePredicate predicate : limited) {
            if (predicate.testLimited(blockWorldState)) {
                flag = true;
            }
        }
        flag = flag || common.stream().anyMatch(predicate -> predicate.test(blockWorldState));
        if (flag) {
            blockWorldState.setError(null);
        }
        return flag;
    }

    /** 两个规则取并集（结果是一个新对象，不修改原对象）。 */
    public TraceabilityPredicate or(TraceabilityPredicate other) {
        if (other != null) {
            TraceabilityPredicate newPredicate = new TraceabilityPredicate(this);
            newPredicate.common.addAll(other.common);
            newPredicate.limited.addAll(other.limited);
            return newPredicate;
        }
        return this;
    }

    public boolean isAny() {
        return this.common.size() == 1 && this.limited.isEmpty() && this.common.get(0) == SimplePredicate.ANY;
    }

    /** 需要把匹配到的位置记进缓存吗（any 不需要）。 */
    public boolean addCache() {
        return !isAny();
    }

    public boolean isAir() {
        return this.common.size() == 1 && this.limited.isEmpty() && this.common.get(0) == SimplePredicate.AIR;
    }

    public boolean isSingle() {
        return !isAny() && !isAir() && this.common.size() + this.limited.size() == 1;
    }

    public boolean hasAir() {
        return this.common.contains(SimplePredicate.AIR);
    }
}
