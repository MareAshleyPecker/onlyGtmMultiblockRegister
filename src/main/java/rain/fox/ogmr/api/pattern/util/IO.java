package rain.fox.ogmr.api.pattern.util;

import lombok.Getter;

/**
 * 方块/仓室在结构里承担的 IO 方向。
 *
 * <p>
 * 从 GTM 的 {@code com.gregtechceu.gtceu.api.capability.recipe.IO} 拆出来的极简版：
 * 本库不允许依赖 {@code com.gregtechceu.gtceu.api.capability.*}，而结构匹配里只用到
 * 「这个位置的方块是输入、输出、还是双向（NONE = 不参与 IO 统计）」这一个语义，
 * 因此这里只保留常量、语言键与 {@link #support(IO)} 判定。
 *
 * <p>
 * 已去掉的部分：GUI 图标（GT 的 {@code ResourceTexture} / {@code EnumSelectorWidget.SelectableEnum}）
 * —— UI 渲染交给使用方自己实现。
 */
public enum IO {

    IN("ogmr.io.import"),
    OUT("ogmr.io.export"),
    BOTH("ogmr.io.both"),
    NONE("ogmr.io.none");

    /** 语言键（本库自带，使用方可用 {@code OGMRLang} 注册翻译）。 */
    @Getter
    private final String tooltipKey;

    IO(String tooltipKey) {
        this.tooltipKey = tooltipKey;
    }

    /**
     * 当前 IO 模式是否支持 {@code io} 这种方向。
     * <p>
     * 语义与 GTM 一致：{@code NONE} 谁都不支持；{@code BOTH} 支持一切；其余只支持自己。
     */
    public boolean support(IO io) {
        if (io == this) return true;
        if (io == NONE) return false;
        return this == BOTH;
    }
}
