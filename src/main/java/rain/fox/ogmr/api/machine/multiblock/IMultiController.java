package rain.fox.ogmr.api.machine.multiblock;

import rain.fox.ogmr.api.machine.MetaMachine;

import net.minecraft.world.level.Level;

import java.util.List;

/**
 * 本文件是从 GTM 的 {@code com.gregtechceu.gtceu.api.machine.feature.multiblock.IMultiController}
 * 精简拆出来的。
 *
 * <p>
 * 「多方块控制器」：结构的中心那个方块对应的机器。它负责
 * <ul>
 * <li>结构判定（图案匹配）与成型/失效的生命周期；</li>
 * <li>维护仓室列表（{@link #getParts()}），并在成型/失效时通知每个仓室。</li>
 * </ul>
 *
 * <p>
 * 与 GTM 的差别：没有 {@code getMultiblockState()} / {@code checkPattern()} / {@code asyncCheckPattern()}
 * 这些方法（它们是 GTM 自己的实现细节，本库放在 {@code MultiblockControllerMachine} 里，不放接口上），
 * 也没有并行仓（{@code IParallelHatch}）、自动搭建、共享仓室相关的方法。
 */
public interface IMultiController {

    /** 本控制器的机器实例。 */
    MetaMachine self();

    default Level getLevel() {
        return self().getLevel();
    }

    /** 结构是否已成型。 */
    boolean isFormed();

    /** 当前挂着的仓室（活列表，请勿直接修改）。 */
    List<IMultiPart> getParts();

    /** 收编一个仓室。 */
    void addPart(IMultiPart part);

    /** 摘掉一个仓室。 */
    void removePart(IMultiPart part);
}
