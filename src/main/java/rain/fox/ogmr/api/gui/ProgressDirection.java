package rain.fox.ogmr.api.gui;

import com.lowdragmc.lowdraglib.gui.texture.ProgressTexture;

/**
 * ogmr 新写 —— 进度条填充方向。
 *
 * <p>
 * 本库<b>故意不复用</b> LDLib 的 {@link ProgressTexture.FillDirection}，原因有两点：
 * <ol>
 * <li>LDLib 那个枚举多了一个 {@code ALWAYS_FULL}（流体罐用的），放在进度条 API 里是噪音；</li>
 * <li>名字对不上：LDLib 叫 {@code UP_TO_DOWN / DOWN_TO_UP}，而 addon 更习惯 {@code TOP_TO_BOTTOM}。
 * 多一层薄映射，以后 LDLib 改枚举名也不会波及 addon 代码。</li>
 * </ol>
 *
 * <p>映射关系见 {@link #toFillDirection()}。
 */
public enum ProgressDirection {

    /** 从左往右填充（水平进度条默认）。 */
    LEFT_TO_RIGHT(ProgressTexture.FillDirection.LEFT_TO_RIGHT),

    /** 从右往左填充。 */
    RIGHT_TO_LEFT(ProgressTexture.FillDirection.RIGHT_TO_LEFT),

    /** 从上往下填充（竖直进度条默认）。 */
    TOP_TO_BOTTOM(ProgressTexture.FillDirection.UP_TO_DOWN),

    /** 从下往上填充。 */
    BOTTOM_TO_TOP(ProgressTexture.FillDirection.DOWN_TO_UP);

    private final ProgressTexture.FillDirection fillDirection;

    ProgressDirection(ProgressTexture.FillDirection fillDirection) {
        this.fillDirection = fillDirection;
    }

    /** 转成 LDLib 的枚举，交给 {@code ProgressTexture#setFillDirection}。 */
    public ProgressTexture.FillDirection toFillDirection() {
        return fillDirection;
    }

    /** 是否竖直方向（调用方要按这个决定宽高比）。 */
    public boolean isVertical() {
        return this == TOP_TO_BOTTOM || this == BOTTOM_TO_TOP;
    }
}
