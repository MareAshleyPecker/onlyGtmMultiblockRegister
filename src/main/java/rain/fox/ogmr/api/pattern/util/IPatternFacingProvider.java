package rain.fox.ogmr.api.pattern.util;

import net.minecraft.core.Direction;

/**
 * 图案检查所需的「控制器朝向信息」来源（本库新增的桥接接口）。
 *
 * <p>
 * <b>为什么需要它：</b>GTM 的 {@code BlockPattern} 在做结构匹配时直接读
 * {@code controller.self().getFrontFacing() / getUpwardsFacing() / isFlipped() / allowFlip()}，
 * 但这些方法位于 GT 的 {@code MetaMachine} 上，本库的 {@code rain.fox.ogmr.api.machine.MetaMachine}
 * 并不保证提供。于是本库把「图案检查真正需要的朝向信息」收敛成这一个 5 方法的接口：
 *
 * <ul>
 * <li>多方块控制器（{@code rain.fox.ogmr.api.machine.multiblock.IMultiController} 的实现）
 * 实现本接口，{@link rain.fox.ogmr.api.pattern.BlockPattern} 就能像 GTM 一样做朝向旋转 / 上下翻转匹配；</li>
 * <li>不实现时，BlockPattern 按「没有固定朝向」处理：四个水平方向各试一次，且不允许翻转
 * （等价于 GT 里 {@code hasFrontFacing() == false && allowFlip() == false} 的行为）。</li>
 * </ul>
 *
 * <p>
 * 如果调用方不愿意/不能在机器类上实现本接口，也可以直接调用
 * {@code BlockPattern.checkPatternAt(worldState, centerPos, frontFacing, upwardsFacing, isFlipped, savePredicate)}
 * 显式把朝向传进来。
 */
public interface IPatternFacingProvider {

    /** 控制器正面朝向（结构会随它旋转）。 */
    Direction getFrontFacing();

    /** 是否有固定正面朝向；false = 四个水平方向都尝试。 */
    default boolean hasFrontFacing() {
        return true;
    }

    /** 控制器的「上」朝向（用于结构绕正面轴旋转 90° 的场合）。 */
    Direction getUpwardsFacing();

    /** 第一遍匹配失败后是否允许上下翻转再试一次。 */
    default boolean allowFlip() {
        return false;
    }

    /** 当前结构是否处于翻转状态（成型后由控制器记录）。 */
    default boolean isFlipped() {
        return false;
    }
}
