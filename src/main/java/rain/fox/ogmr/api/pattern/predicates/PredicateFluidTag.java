package rain.fox.ogmr.api.pattern.predicates;

import rain.fox.ogmr.api.util.Memoizer;

import com.lowdragmc.lowdraglib.utils.BlockInfo;

import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.material.Fluid;

/**
 * 「匹配某个流体标签」的规则（{@code Predicates.fluidTag(...)}）。
 *
 * <p>
 * 从 GTM 的 {@code com.gregtechceu.gtceu.api.pattern.predicates.PredicateFluidTag} 拆出，
 * 判定与候选逻辑一致（tag 为 null 时退化成 BARRIER + 永不匹配）。
 *
 * <p>
 * 与 GTM 版本的差异：候选列表用本库 {@link Memoizer} 缓存。
 */
public class PredicateFluidTag extends SimplePredicate {

    public TagKey<Fluid> tag = null;

    public PredicateFluidTag() {
        super("tags");
    }

    public PredicateFluidTag(TagKey<Fluid> tag) {
        this();
        this.tag = tag;
        buildPredicate();
    }

    @Override
    public SimplePredicate buildPredicate() {
        if (tag == null) {
            predicate = state -> false;
            candidates = () -> new BlockInfo[] { BlockInfo.fromBlock(Blocks.BARRIER) };
            return this;
        }
        final TagKey<Fluid> allowedTag = tag;
        predicate = state -> state.getBlockState().getFluidState().is(allowedTag);
        candidates = Memoizer.memoize(() -> BuiltInRegistries.FLUID.getTag(allowedTag)
                .stream()
                .flatMap(HolderSet.Named::stream)
                .map(Holder::value)
                .map(fluid -> BlockInfo.fromBlockState(fluid.defaultFluidState().createLegacyBlock()))
                .toArray(BlockInfo[]::new));
        return this;
    }
}
