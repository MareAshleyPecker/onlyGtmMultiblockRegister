package rain.fox.ogmr.api.pattern.error;

import rain.fox.ogmr.api.pattern.MultiblockState;
import rain.fox.ogmr.api.pattern.TraceabilityPredicate;
import rain.fox.ogmr.api.pattern.predicates.SimplePredicate;

import lombok.Setter;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.List;

/**
 * 结构匹配失败的「错误对象」：既描述失败原因，也提供该位置期望的候选方块。
 *
 * <p>
 * 从 GTM 的 {@code com.gregtechceu.gtceu.api.pattern.error.PatternError} 拆出，
 * 逻辑与语言键保持原样（{@code gtceu.multiblock.pattern.error}，玩家看到的文案不变）。
 *
 * <p>
 * 与 GTM 版本的差异：只把包路径与依赖换成本库类型，没有其它改动。
 * 使用方可以继承它给出自己的错误文案（见 {@link PatternStringError}、{@link PatternMessageError}）。
 */
public class PatternError {

    @Setter
    protected MultiblockState worldState;

    public Level getWorld() {
        return worldState.getWorld();
    }

    public BlockPos getPos() {
        return worldState.getPos();
    }

    /** 出错位置期望的方块候选，每个 predicate 一组物品。 */
    public List<List<ItemStack>> getCandidates() {
        TraceabilityPredicate predicate = worldState.predicate;
        List<List<ItemStack>> candidates = new ArrayList<>();
        for (SimplePredicate common : predicate.common) {
            candidates.add(common.getCandidates());
        }
        for (SimplePredicate limited : predicate.limited) {
            candidates.add(limited.getCandidates());
        }
        return candidates;
    }

    public Component getErrorInfo() {
        List<List<ItemStack>> candidates = getCandidates();
        StringBuilder builder = new StringBuilder();
        for (List<ItemStack> candidate : candidates) {
            if (!candidate.isEmpty()) {
                builder.append(candidate.get(0).getDisplayName().getString());
                builder.append(", ");
            }
        }
        builder.append("...");
        return Component.translatable("gtceu.multiblock.pattern.error", builder.toString(), worldState.getPos());
    }
}
