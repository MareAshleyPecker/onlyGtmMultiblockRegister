package rain.fox.ogmr.api.pattern;

import rain.fox.ogmr.api.machine.MachineDefinition;

import com.lowdragmc.lowdraglib.utils.BlockInfo;
import com.lowdragmc.lowdraglib.utils.Builder;

import lombok.Getter;

import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

import java.util.function.Supplier;

/**
 * 结构「形状信息」：{@code [z][y][x]} 的 {@link BlockInfo} 三维数组，用于结构预览 / 形状共享。
 *
 * <p>
 * 从 GTM 的 {@code com.gregtechceu.gtceu.api.pattern.MultiblockShapeInfo} 拆出，
 * 用法（{@code MultiblockShapeInfo.builder().aisle(...).where('A', ...).build()}）与 GTM 一致。
 *
 * <p>
 * 与 GTM 版本的差异（去 GT 化）：
 * <ul>
 * <li>{@code where(char, IMachineBlock, Direction)} → {@code where(char, Block, Direction)} /
 * {@code where(char, MachineDefinition, Direction)}：不再依赖 GT 的 {@code RotationState}，
 * 改为「方块状态里若有 HORIZONTAL_FACING / FACING 属性就设成给定朝向」（GT 的 RotationState 干的是同一件事）；</li>
 * <li>其余（builder / bake / getBlocks）逻辑不变。</li>
 * </ul>
 */
public class MultiblockShapeInfo {

    @Getter
    private final BlockInfo[][][] blocks; // [z][y][x]

    public MultiblockShapeInfo(BlockInfo[][][] blocks) {
        this.blocks = blocks;
    }

    public static ShapeInfoBuilder builder() {
        return new ShapeInfoBuilder();
    }

    public static class ShapeInfoBuilder extends Builder<BlockInfo, ShapeInfoBuilder> {

        public ShapeInfoBuilder where(char symbol, BlockState blockState) {
            return where(symbol, BlockInfo.fromBlockState(blockState));
        }

        public ShapeInfoBuilder where(char symbol, Supplier<? extends Block> block) {
            return where(symbol, block.get());
        }

        public ShapeInfoBuilder where(char symbol, Block block) {
            return where(symbol, block.defaultBlockState());
        }

        /** 用机器定义填一个带朝向的字符。 */
        public ShapeInfoBuilder where(char symbol, MachineDefinition machine, Direction facing) {
            return where(symbol, machine.getBlock(), facing);
        }

        /** 用带朝向的方块填一个字符（有水平朝向属性就设水平朝向，否则设六向朝向）。 */
        public ShapeInfoBuilder where(char symbol, Block block, Direction facing) {
            BlockState state = block.defaultBlockState();
            if (state.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) {
                state = state.setValue(BlockStateProperties.HORIZONTAL_FACING,
                        facing.getAxis() == Direction.Axis.Y ? Direction.NORTH : facing);
            } else if (state.hasProperty(BlockStateProperties.FACING)) {
                state = state.setValue(BlockStateProperties.FACING, facing);
            }
            return where(symbol, state);
        }

        private BlockInfo[][][] bake() {
            return this.bakeArray(BlockInfo.class, BlockInfo.EMPTY);
        }

        public MultiblockShapeInfo build() {
            return new MultiblockShapeInfo(bake());
        }
    }
}
