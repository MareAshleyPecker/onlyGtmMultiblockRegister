package rain.fox.ogmr.api.machine.multiblock;

import rain.fox.ogmr.api.machine.MetaMachine;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

/**
 * 本文件是从 GTM 的 {@code com.gregtechceu.gtceu.api.machine.feature.multiblock.IMultiPart} 精简拆出来的。
 *
 * <p>
 * 「仓室」：多方块结构里可以被替换的那些格子（输入/输出总线、能量仓、维护仓……）。
 * 它在结构成型时被控制器登记，结构失效时被摘掉，因此它<b>始终知道自己挂在哪个控制器上</b>。
 *
 * <p>
 * 与 GTM 的差别：砍掉了共享仓室（{@code canShared / hasController / getFormedAppearance}）、
 * 覆盖板与 UI 相关的方法，只保留结构生命周期与最基本的查询。
 *
 * <p>
 * 实现者一般直接继承 {@code rain.fox.ogmr.api.machine.multiblock.part.MultiblockPartMachine}，
 * 不用自己实现本接口。
 */
public interface IMultiPart {

    /** 本仓室的机器实例（{@code MultiblockPartMachine} 里就是 {@code this}）。 */
    MetaMachine self();

    default Level getLevel() {
        return self().getLevel();
    }

    default BlockPos getPos() {
        return self().getPos();
    }

    /** 所属控制器是否已成型（没有控制器时必然为 false）。 */
    boolean isFormed();

    /** 被某个控制器收编（结构成型时调用）。 */
    void addedToController(IMultiController controller);

    /** 从某个控制器上摘下（结构失效时调用）。 */
    void removedFromController(IMultiController controller);
}
