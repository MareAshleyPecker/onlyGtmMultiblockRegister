package rain.fox.ogmr.api.pattern.predicates;

import rain.fox.ogmr.api.util.Memoizer;

import com.lowdragmc.lowdraglib.utils.BlockInfo;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import org.apache.commons.lang3.ArrayUtils;

import java.util.Arrays;
import java.util.Objects;

/**
 * 「匹配若干个方块」的规则（结构里最常见的 {@code Predicates.blocks(...)}）。
 *
 * <p>
 * 从 GTM 的 {@code com.gregtechceu.gtceu.api.pattern.predicates.PredicateBlocks} 拆出，
 * 逻辑与 GTM 一致：空列表退化成 {@code Blocks.BARRIER}（永远匹配不上，用于提示「这里你没填东西」）。
 *
 * <p>
 * 与 GTM 版本的差异：候选列表改用本库的 {@link Memoizer} 缓存（GT 用的是 Guava 记忆化），
 * 避免每次预览都重新构造 {@code BlockInfo[]}。
 */
public class PredicateBlocks extends SimplePredicate {

    public Block[] blocks = new Block[0];

    public PredicateBlocks() {
        super("blocks");
    }

    public PredicateBlocks(Block... blocks) {
        this();
        this.blocks = blocks;
        buildPredicate();
    }

    @Override
    public SimplePredicate buildPredicate() {
        blocks = Arrays.stream(blocks).filter(Objects::nonNull).toArray(Block[]::new);
        if (blocks.length == 0) blocks = new Block[] { Blocks.BARRIER };
        final Block[] allowed = blocks;
        predicate = state -> ArrayUtils.contains(allowed, state.getBlockState().getBlock());
        candidates = Memoizer.memoize(() -> Arrays.stream(allowed).map(BlockInfo::fromBlock).toArray(BlockInfo[]::new));
        return this;
    }
}
