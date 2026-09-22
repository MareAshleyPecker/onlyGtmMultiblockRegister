package rain.fox.ogmr.api.pattern.error;

import net.minecraft.network.chat.Component;

import java.util.function.Supplier;

/**
 * 「自定义文本」错误：文案由调用方在运行时给出（本库新增，GT 里没有对应类）。
 *
 * <p>
 * 用途：{@code Predicates.custom(predicate, errorMessage)} 需要一个能携带任意
 * {@link Component}（而不是固定语言键）的错误对象；{@link PatternStringError} 只能装语言键，
 * 所以这里补一个延迟求值的版本（{@link Supplier} 形式，只有真正要显示时才构造文本）。
 */
public class PatternMessageError extends PatternError {

    private final Supplier<Component> message;

    public PatternMessageError(Supplier<Component> message) {
        this.message = message;
    }

    @Override
    public Component getErrorInfo() {
        return message.get();
    }
}
