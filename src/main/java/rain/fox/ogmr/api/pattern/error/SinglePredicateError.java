package rain.fox.ogmr.api.pattern.error;

import rain.fox.ogmr.api.pattern.predicates.SimplePredicate;

import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import java.util.Collections;
import java.util.List;

/**
 * 「某个 predicate 的数量越界」错误。
 *
 * <p>
 * 从 GTM 的 {@code com.gregtechceu.gtceu.api.pattern.error.SinglePredicateError} 拆出。
 * {@code type} 的取值与 GT 完全一致，语言键也保持原样，玩家看到的提示不变：
 * <ul>
 * <li>0 = 超过全局上限（maxCount）</li>
 * <li>1 = 少于全局下限（minCount）</li>
 * <li>2 = 超过单层上限（maxLayerCount）</li>
 * <li>3 = 少于单层下限（minLayerCount）</li>
 * </ul>
 */
public class SinglePredicateError extends PatternError {

    public final SimplePredicate predicate;
    public final int type;

    public SinglePredicateError(SimplePredicate predicate, int type) {
        this.predicate = predicate;
        this.type = type;
    }

    @Override
    public List<List<ItemStack>> getCandidates() {
        return Collections.singletonList(predicate.getCandidates());
    }

    @Override
    public Component getErrorInfo() {
        int number = -1;
        if (type == 0) {
            number = predicate.maxCount;
        }
        if (type == 1) {
            number = predicate.minCount;
        }
        if (type == 2) {
            number = predicate.maxLayerCount;
        }
        if (type == 3) {
            number = predicate.minLayerCount;
        }
        return Component.translatable("gtceu.multiblock.pattern.error.limited." + type, number);
    }
}
