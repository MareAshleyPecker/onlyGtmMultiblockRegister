package rain.fox.ogmr.api.machine;

import lombok.Getter;

import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DirectionProperty;

import org.jetbrains.annotations.Nullable;

/**
 * 机器的<b>朝向</b>设定 —— 拆自 GTM 的 {@code com.gregtechceu.gtceu.api.machine.RotationState}。
 *
 * <p>
 * 决定三件事：
 * <ol>
 * <li>机器方块状态里用哪个 {@code facing} 属性（{@link #property}，{@code NONE} 表示没有）；</li>
 * <li>摆放时的默认朝向（{@link #defaultDirection}）；</li>
 * <li>上限：能不能朝上/朝下（{@link #test(Direction)}）。</li>
 * </ol>
 *
 * <h3>三种取值怎么选</h3>
 * <table border="1">
 * <caption>RotationState</caption>
 * <tr><th>值</th><th>属性</th><th>典型用途</th></tr>
 * <tr><td>{@link #NONE}</td><td>无</td><td>外壳、无正面无口的方块</td></tr>
 * <tr><td>{@link #Y_AXIS}</td><td>{@code horizontal_facing}（4 向）</td><td>普通单方块机器、多方块控制器（默认）</td></tr>
 * <tr><td>{@link #ALL}</td><td>{@code facing}（6 向）</td><td><b>仓室</b>：口可以朝墙、也可以朝地板/天花板</td></tr>
 * </table>
 *
 * <p>
 * ⚠️ {@link #Y_AXIS} 用的就是原版的 {@link BlockStateProperties#HORIZONTAL_FACING} 单例，
 * 所以 {@code MachineBlock.FACING} 这个老常量对它们依然成立；换成 {@link #ALL} 之后属性对象变了，
 * 读朝向必须走 {@link MachineDefinition#getFacing(net.minecraft.world.level.block.state.BlockState)}
 * （它会挑对应的属性），不要直接 {@code state.getValue(MachineBlock.FACING)}。
 */
public enum RotationState {

    /** 没有朝向属性。 */
    NONE(null, Direction.NORTH),

    /** 只有水平四向（原版 {@code horizontal_facing}）。 */
    Y_AXIS(BlockStateProperties.HORIZONTAL_FACING, Direction.NORTH),

    /** 六向都能朝（原版 {@code facing}）—— 仓室的「口」用它。 */
    ALL(BlockStateProperties.FACING, Direction.NORTH);

    /** 方块状态里的朝向属性；{@link #NONE} 时为 {@code null}。 */
    @Getter
    @Nullable
    private final DirectionProperty property;

    /** 摆放时算不出朝向（没有玩家）时用的默认值。 */
    @Getter
    private final Direction defaultDirection;

    RotationState(@Nullable DirectionProperty property, Direction defaultDirection) {
        this.property = property;
        this.defaultDirection = defaultDirection;
    }

    /** 是否有朝向属性。 */
    public boolean hasFacing() {
        return property != null;
    }

    /**
     * 这个方向允不允许作为本状态的朝向。
     *
     * <p>{@link #Y_AXIS} 只允许水平四向；{@link #ALL} 六向都行；{@link #NONE} 恒 false。
     */
    public boolean test(Direction direction) {
        if (direction == null) return false;
        return switch (this) {
            case NONE -> false;
            case Y_AXIS -> direction.getAxis().isHorizontal();
            case ALL -> true;
        };
    }

    @Override
    public String toString() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }
}
