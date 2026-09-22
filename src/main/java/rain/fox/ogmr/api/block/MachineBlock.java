package rain.fox.ogmr.api.block;

import rain.fox.ogmr.api.blockentity.MachineBlockEntity;
import rain.fox.ogmr.api.machine.IMachineBlockEntity;
import rain.fox.ogmr.api.machine.MachineDefinition;
import rain.fox.ogmr.api.machine.MetaMachine;

import lombok.Getter;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

import org.jetbrains.annotations.Nullable;

/**
 * 本文件是从 GTM 的 {@code com.gregtechceu.gtceu.api.block.MetaMachineBlock} 精简拆出来的。
 *
 * <p>
 * 所有机器方块（单方块机器、多方块控制器、仓室）的基类。它只负责「方块那一半」：
 * 三个 blockstate 属性、摆放时的朝向、方块实体与 ticker、形状与渲染方式。
 * 行为逻辑全在 {@link MetaMachine} 里。
 *
 * <p>
 * blockstate 属性：
 * <ul>
 * <li>{@link #FACING} —— 水平朝向（摆放时按玩家朝向取反）；</li>
 * <li>{@link #ACTIVE} —— 是否正在工作（由具体机器的 RecipeLogic 更新）；</li>
 * <li>{@link #FORMED} —— 多方块结构是否成型（由控制器更新）。</li>
 * </ul>
 *
 * <p>
 * TODO(ogmr): 本库的精简版<b>不自动</b>维护 {@link #ACTIVE} / {@link #FORMED} 这两个属性
 * （GTM 用 {@code MachineRenderState} 驱动）。谁的工作状态变了谁自己
 * {@code level.setBlock(pos, state.setValue(ACTIVE, true), Block.UPDATE_ALL)} 即可；
 * 若以后要统一，建议在 {@link MetaMachine#serverTick()} 之后加一个「状态 → blockstate」的同步点。
 */
public class MachineBlock extends Block implements EntityBlock {

    /** 水平朝向。 */
    public static final DirectionProperty FACING = BlockStateProperties.HORIZONTAL_FACING;
    /** 是否正在工作。 */
    public static final BooleanProperty ACTIVE = BooleanProperty.create("active");
    /** 多方块是否成型。 */
    public static final BooleanProperty FORMED = BooleanProperty.create("formed");

    /** 本方块对应的机器定义。 */
    @Getter
    public final MachineDefinition definition;

    /**
     * GTM 的构造参数顺序（{@code Properties} 在前）。
     *
     * <p>
     * 注册 builder（{@code MachineBuilder}）用的就是这一个。
     */
    public MachineBlock(Properties properties, MachineDefinition definition) {
        super(properties);
        this.definition = definition;
        registerDefaultState(defaultBlockState()
                .setValue(FACING, Direction.NORTH)
                .setValue(ACTIVE, false)
                .setValue(FORMED, false));
    }

    /** 参数顺序反过来的等价构造器（本库自己的 builder 一开始就是按这个写的）。 */
    public MachineBlock(MachineDefinition definition, Properties properties) {
        this(properties, definition);
    }

    // ═══════════════ blockstate ═══════════════

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, ACTIVE, FORMED);
    }

    /** 摆放时按玩家朝向取反（机器正面朝向玩家）。 */
    @Override
    @Nullable
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        Player player = context.getPlayer();
        Direction facing = player != null ? player.getDirection().getOpposite() : Direction.NORTH;
        return defaultBlockState().setValue(FACING, facing);
    }

    @Override
    public BlockState rotate(BlockState state, Rotation rotation) {
        return state.setValue(FACING, rotation.rotate(state.getValue(FACING)));
    }

    // ═══════════════ 方块实体 ═══════════════

    @Override
    @Nullable
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new MachineBlockEntity(definition, pos, state);
    }

    /**
     * tick 入口：服务端跑 {@link MachineBlockEntity#serverTick}，客户端跑
     * {@link MachineBlockEntity#clientTick}。
     */
    @Override
    @Nullable
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state,
                                                                  BlockEntityType<T> blockEntityType) {
        BlockEntityType<?> expected = definition.getBlockEntityType();
        if (expected != null && blockEntityType != expected) return null;
        if (level.isClientSide) {
            return (lvl, pos, st, blockEntity) -> {
                if (blockEntity instanceof MachineBlockEntity machine) {
                    MachineBlockEntity.clientTick(lvl, pos, st, machine);
                }
            };
        }
        return (lvl, pos, st, blockEntity) -> {
            if (blockEntity instanceof MachineBlockEntity machine) {
                MachineBlockEntity.serverTick(lvl, pos, st, machine);
            }
        };
    }

    /** 渲染成实体方块模型（模型由 BE / 外部渲染器负责）。 */
    @Override
    public RenderShape getRenderShape(BlockState state) {
        return RenderShape.ENTITYBLOCK_ANIMATED;
    }

    @Override
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return definition.getShape(state.getValue(FACING));
    }

    // ═══════════════ 交互 / 事件 ═══════════════

    /**
     * 右键交互。
     *
     * <p>
     * TODO(ogmr): 精简版不做交互（直接返回 PASS，把事件留给别的东西）。GTM 在这里做的是
     * 工具交互 + 所有权登记 + 打开 UI。接入 UI 后应该改成：
     * {@code getMachine(world, pos).onUse(...)} 或者调 UI 层的 {@code tryToOpenUI(player, hand, hit)}。
     */
    @Override
    public InteractionResult use(BlockState state, Level world, BlockPos pos, Player player, InteractionHand hand,
                                 BlockHitResult hit) {
        return InteractionResult.PASS;
    }

    @Override
    public void neighborChanged(BlockState state, Level level, BlockPos pos, Block block, BlockPos fromPos,
                                boolean isMoving) {
        MetaMachine machine = getMachine(level, pos);
        if (machine != null) {
            machine.onNeighborChanged(block, fromPos, isMoving);
        }
        super.neighborChanged(state, level, pos, block, fromPos, isMoving);
    }

    /** 方块事件：转发给 BE（{@link IMachineBlockEntity#scheduleRenderUpdate()} 的服务端那一半）。 */
    @Override
    public boolean triggerEvent(BlockState state, Level level, BlockPos pos, int id, int param) {
        BlockEntity blockEntity = level.getBlockEntity(pos);
        if (blockEntity != null) {
            return blockEntity.triggerEvent(id, param);
        }
        return super.triggerEvent(state, level, pos, id, param);
    }

    /** 朝向被改变了（例如结构旋转）：通知机器。 */
    @Override
    public void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean isMoving) {
        if (state.hasBlockEntity() && state.is(newState.getBlock()) && newState.hasProperty(FACING)) {
            Direction oldFacing = state.getValue(FACING);
            Direction newFacing = newState.getValue(FACING);
            if (oldFacing != newFacing) {
                MetaMachine machine = getMachine(level, pos);
                if (machine != null) {
                    machine.onRotated(oldFacing, newFacing);
                }
            }
        }
        super.onRemove(state, level, pos, newState, isMoving);
    }

    /** 取某个坐标上的机器（没有则 null）。 */
    @Nullable
    public static MetaMachine getMachine(BlockGetter level, BlockPos pos) {
        return MetaMachine.getMachine(level, pos);
    }
}
