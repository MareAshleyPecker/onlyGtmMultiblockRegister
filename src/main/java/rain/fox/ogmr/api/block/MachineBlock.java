package rain.fox.ogmr.api.block;

import rain.fox.ogmr.api.blockentity.MachineBlockEntity;
import rain.fox.ogmr.api.machine.IMachineBlockEntity;
import rain.fox.ogmr.api.machine.MachineDefinition;
import rain.fox.ogmr.api.machine.MetaMachine;
import rain.fox.ogmr.api.machine.RotationState;

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
import net.minecraft.world.level.block.Mirror;
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
import net.minecraft.world.phys.Vec3;
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

    /**
     * 水平朝向属性 —— <b>兼容常量</b>：{@link RotationState#Y_AXIS} 的机器（普通机器、多方块控制器）
     * 用的就是它。
     *
     * <p>
     * ⚠️ 仓室默认是 {@link RotationState#ALL}，用的是原版的 {@code facing}（另一个属性对象），
     * 对它 {@code state.getValue(MachineBlock.FACING)} 会抛异常。读朝向请走
     * {@link MachineDefinition#getFacing(BlockState)}。
     */
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

        BlockState state = defaultBlockState();
        RotationState rotation = definition.getRotationState();
        // hasProperty 这一层保险是为了「状态表里没建出这个属性」时不炸（正常路径下它一定在：
        // MachineBuilder 在建方块前标了 getBuilt()，见 createBlockStateDefinition）。
        if (rotation.hasFacing() && state.hasProperty(rotation.getProperty())) {
            state = state.setValue(rotation.getProperty(), rotation.getDefaultDirection());
        }
        registerDefaultState(state.setValue(ACTIVE, false).setValue(FORMED, false));
    }

    /** 参数顺序反过来的等价构造器（本库自己的 builder 一开始就是按这个写的）。 */
    public MachineBlock(MachineDefinition definition, Properties properties) {
        this(properties, definition);
    }

    // ═══════════════ blockstate ═══════════════

    /**
     * 建方块状态表。
     *
     * <p>
     * ⚠️ 本方法由原版 {@code Block} 的构造器调用（在 {@code super(properties)} 里面），那时本类的
     * {@link #definition} 字段<b>还没赋值</b>，所以只能问 {@link MachineDefinition#getBuilt()}
     * ——builder 在建方块之前把它标好了（GTM 的 {@code MachineDefinition.getBuilt()} 是同一招）。
     */
    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(ACTIVE, FORMED);
        MachineDefinition built = MachineDefinition.getBuilt();
        RotationState rotation = built != null ? built.getRotationState() : RotationState.Y_AXIS;
        if (rotation.hasFacing()) {
            builder.add(rotation.getProperty());
        }
    }

    /**
     * 摆放时的朝向：<b>口对着玩家</b>（照 GTM {@code MetaMachineBlock#getStateForPlacement} 的规则）。
     *
     * <ol>
     * <li>基础朝向 = {@code player.getDirection().getOpposite()}（玩家看的反方向 = 正对玩家）；</li>
     * <li>站在方块正上/正下方附近、朝下/朝上放，且朝向设定允许（{@link RotationState#ALL}）时，
     * 改成 {@code UP} / {@code DOWN} —— 仓室摆在地板/天花板上时口朝上/朝下也说得通；</li>
     * <li>没有玩家（机械臂之类）时用 {@link RotationState#getDefaultDirection()}。</li>
     * </ol>
     */
    @Override
    @Nullable
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        RotationState rotation = definition.getRotationState();
        BlockState state = defaultBlockState();
        if (!rotation.hasFacing()) {
            return state;
        }
        DirectionProperty property = rotation.getProperty();
        state = state.setValue(property, rotation.getDefaultDirection());

        Player player = context.getPlayer();
        if (player == null) {
            return state;
        }
        state = state.setValue(property, player.getDirection().getOpposite());

        BlockPos pos = context.getClickedPos();
        Vec3 eye = player.position();
        if (Math.abs(eye.x - ((float) pos.getX() + 0.5F)) < 2.0D &&
                Math.abs(eye.z - ((float) pos.getZ() + 0.5F)) < 2.0D) {
            double eyeY = eye.y + player.getEyeHeight();
            if (eyeY - pos.getY() > 2.0D && rotation.test(Direction.UP)) {
                state = state.setValue(property, Direction.UP);
            }
            if ((double) pos.getY() - eyeY > 0.0D && rotation.test(Direction.DOWN)) {
                state = state.setValue(property, Direction.DOWN);
            }
        }
        return state;
    }

    /** 结构旋转：朝向跟着转（没有朝向属性的机器原样返回）。 */
    @Override
    public BlockState rotate(BlockState state, Rotation rotation) {
        DirectionProperty property = facingProperty(state);
        return property == null ? state : state.setValue(property, rotation.rotate(state.getValue(property)));
    }

    /** 结构翻转：同上。 */
    @Override
    public BlockState mirror(BlockState state, Mirror mirror) {
        DirectionProperty property = facingProperty(state);
        return property == null ? state : state.setValue(property, mirror.mirror(state.getValue(property)));
    }

    /** 这个方块状态实际用的朝向属性；没有则为 null。 */
    @Nullable
    public DirectionProperty facingProperty(BlockState state) {
        RotationState rotation = definition.getRotationState();
        return rotation.hasFacing() && state.hasProperty(rotation.getProperty()) ? rotation.getProperty() : null;
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

    /**
     * 渲染方式：默认走<b>静态模型</b>（数据生成产出的 blockstates/models）。
     *
     * <p>
     * ⚠️ 这里曾经是无条件 {@link RenderShape#ENTITYBLOCK_ANIMATED}（GTM 的写法），结果是
     * 「世界里方块什么都不画、物品栏里却正常」——因为 ENTITYBLOCK_ANIMATED 表示「静态模型别画，
     * 交给方块实体渲染器」，而本库<b>默认不注册 BER</b>。所以只有定义里显式声明了
     * {@link MachineDefinition#isUseEntityRenderer()}（即 addon 自己注册了 BER）才用那条路。
     */
    @Override
    public RenderShape getRenderShape(BlockState state) {
        return definition.isUseEntityRenderer() ? RenderShape.ENTITYBLOCK_ANIMATED : RenderShape.MODEL;
    }

    /**
     * 碰撞箱：按朝向取（没朝向属性的机器按 NORTH 取）。
     *
     * <p>朝向一律走 {@link MachineDefinition#getFacing(BlockState)} —— 仓室用的是六向属性，
     * 直接读 {@link #FACING} 会抛异常。
     */
    @Override
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return definition.getShape(definition.getFacing(state));
    }

    // ═══════════════ 交互 / 事件 ═══════════════

    /**
     * 右键交互：<b>打开机器界面</b>。
     *
     * <p>
     * 机器没配界面（{@code getDefinition().hasUI()} 为 false）时返回
     * {@link InteractionResult#PASS}，把事件让给别的东西（扳手、方块、物品……）。
     * 实际打开动作在服务端由 {@link MetaMachine#tryToOpenUI} 发起（LDLib 的 UI 工厂会同步给客户端）。
     */
    @Override
    public InteractionResult use(BlockState state, Level world, BlockPos pos, Player player, InteractionHand hand,
                                 BlockHitResult hit) {
        MetaMachine machine = getMachine(world, pos);
        if (machine == null) {
            return InteractionResult.PASS;
        }
        return machine.tryToOpenUI(player, hand, hit);
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
        DirectionProperty property = facingProperty(newState);
        if (state.hasBlockEntity() && state.is(newState.getBlock()) && property != null
                && state.hasProperty(property)) {
            Direction oldFacing = state.getValue(property);
            Direction newFacing = newState.getValue(property);
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
