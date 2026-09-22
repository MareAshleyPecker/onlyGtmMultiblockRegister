package rain.fox.ogmr.api.pattern.error;

import net.minecraft.network.chat.Component;

/**
 * 「一句话」错误：只带一个语言键（或直接就是原文）的结构错误。
 *
 * <p>
 * 从 GTM 的 {@code com.gregtechceu.gtceu.api.pattern.error.PatternStringError} 拆出，逻辑完全一致。
 * GT 里常见的用法如 {@code new PatternStringError("no controller found")}、
 * {@code new PatternStringError("multiblocked.pattern.error.chunk")} 等，本库沿用同样的字符串。
 */
public class PatternStringError extends PatternError {

    public final String translateKey;

    public PatternStringError(String translateKey) {
        this.translateKey = translateKey;
    }

    @Override
    public Component getErrorInfo() {
        return Component.translatable(translateKey);
    }
}
