package rain.fox.ogmr.api.pattern.predicates;

import rain.fox.ogmr.api.util.Memoizer;

import com.lowdragmc.lowdraglib.utils.BlockInfo;

import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;

import org.apache.commons.lang3.ArrayUtils;

import java.util.Arrays;
import java.util.Objects;

/**
 * 「匹配若干种流体」的规则（{@code Predicates.fluids(...)}，用于液体填充位）。
 *
 * <p>
 * 从 GTM 的 {@code com.gregtechceu.gtceu.api.pattern.predicates.PredicateFluids} 拆出，
 * 逻辑一致：空列表退化成水；候选用 {@code createLegacyBlock()} 转成方块状态给预览用。
 *
 * <p>
 * 与 GTM 版本的差异：候选列表用本库 {@link Memoizer} 缓存。
 */
public class PredicateFluids extends SimplePredicate {

    public Fluid[] fluids = new Fluid[0];

    public PredicateFluids() {
        super("fluids");
    }

    public PredicateFluids(Fluid... fluids) {
        this();
        this.fluids = fluids;
        buildPredicate();
    }

    @Override
    public SimplePredicate buildPredicate() {
        fluids = Arrays.stream(fluids).filter(Objects::nonNull).toArray(Fluid[]::new);
        if (fluids.length == 0) fluids = new Fluid[] { Fluids.WATER };
        final Fluid[] allowed = fluids;
        predicate = state -> ArrayUtils.contains(allowed, state.getBlockState().getFluidState().getType());
        candidates = Memoizer.memoize(() -> Arrays.stream(allowed)
                .map(fluid -> BlockInfo.fromBlockState(fluid.defaultFluidState().createLegacyBlock()))
                .toArray(BlockInfo[]::new));
        return this;
    }
}
