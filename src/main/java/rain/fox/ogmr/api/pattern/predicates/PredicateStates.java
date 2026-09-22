package rain.fox.ogmr.api.pattern.predicates;

import rain.fox.ogmr.api.util.Memoizer;

import com.lowdragmc.lowdraglib.utils.BlockInfo;

import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import org.apache.commons.lang3.ArrayUtils;

import java.util.Arrays;
import java.util.Objects;

/**
 * 「精确匹配若干个方块状态」的规则（{@code Predicates.states(...)} / {@code blockStates(...)}）。
 *
 * <p>
 * 从 GTM 的 {@code com.gregtechceu.gtceu.api.pattern.predicates.PredicateStates} 拆出，逻辑一致：
 * 空列表退化成 {@code Blocks.BARRIER}。
 *
 * <p>
 * 与 GTM 版本的差异：候选列表用本库 {@link Memoizer} 缓存。
 */
public class PredicateStates extends SimplePredicate {

    public BlockState[] states = new BlockState[0];

    public PredicateStates() {
        super("states");
    }

    public PredicateStates(BlockState... states) {
        this();
        this.states = states;
        buildPredicate();
    }

    @Override
    public SimplePredicate buildPredicate() {
        states = Arrays.stream(states).filter(Objects::nonNull).toArray(BlockState[]::new);
        if (states.length == 0) states = new BlockState[] { Blocks.BARRIER.defaultBlockState() };
        final BlockState[] allowed = states;
        predicate = state -> ArrayUtils.contains(allowed, state.getBlockState());
        candidates = Memoizer
                .memoize(() -> Arrays.stream(allowed).map(BlockInfo::fromBlockState).toArray(BlockInfo[]::new));
        return this;
    }
}
