package rain.fox.ogmr.api.machine;

import com.lowdragmc.lowdraglib.syncdata.blockentity.IAsyncAutoSyncBlockEntity;
import com.lowdragmc.lowdraglib.syncdata.blockentity.IAutoPersistBlockEntity;
import com.lowdragmc.lowdraglib.syncdata.blockentity.IRPCBlockEntity;
import com.lowdragmc.lowdraglib.syncdata.managed.MultiManagedStorage;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.common.extensions.IForgeBlockEntity;

/**
 * 本文件是从 GTM 的 {@code com.gregtechceu.gtceu.api.machine.IMachineBlockEntity} 精简拆出来的。
 *
 * <p>
 * 机器方块实体的最小契约：把 LDLib 的自动同步（{@link IAsyncAutoSyncBlockEntity}）、自动持久化
 * （{@link IAutoPersistBlockEntity}）、RPC（{@link IRPCBlockEntity}）和 Forge 的 BE 扩展
 * （{@link IForgeBlockEntity}）缝在一起，再补上「机器运行时」需要的那几个查询入口。
 *
 * <p>
 * 与 GTM 的差别（精简版）：
 * <ul>
 * <li>没有 {@code MachineRenderState}、没有覆盖板（cover）/工具（tool）/染色相关的接口；</li>
 * <li>没有 {@code getModelData()} 转发（本版本没有渲染状态模型数据）；</li>
 * <li>染色只保留「值」的读写，不联动渲染状态属性。</li>
 * </ul>
 */
public interface IMachineBlockEntity extends IAsyncAutoSyncBlockEntity, IRPCBlockEntity, IAutoPersistBlockEntity,
                                     IForgeBlockEntity {

    /** BE 自身。 */
    BlockEntity self();

    /** 所在世界；BE 还没被放进世界时为 null。 */
    default Level level() {
        return self().getLevel();
    }

    /** 所在坐标。 */
    default BlockPos pos() {
        return self().getBlockPos();
    }

    /** 让周围方块重新计算（方块变化后调用）。 */
    default void notifyBlockUpdate() {
        if (level() != null) {
            level().updateNeighborsAt(pos(), level().getBlockState(pos()).getBlock());
        }
    }

    /**
     * 请求一次渲染刷新。
     *
     * <p>
     * 客户端直接重发方块更新并请求模型数据刷新；服务端走 blockEvent（id=1），由
     * {@code MachineBlock#triggerEvent} → {@code MachineBlockEntity#triggerEvent} 在客户端落地。
     */
    default void scheduleRenderUpdate() {
        Level level = level();
        if (level == null) return;
        BlockPos pos = pos();
        if (level.isClientSide) {
            var state = level.getBlockState(pos);
            level.sendBlockUpdated(pos, state, state, Block.UPDATE_IMMEDIATE);
            self().requestModelDataUpdate();
        } else {
            level.blockEvent(pos, level.getBlockState(pos).getBlock(), 1, 0);
        }
    }

    /**
     * 每个 BE 固定的「偏移时间」，用于把同类机器的定时任务错开（避免同 tick 抖动）。
     *
     * @see MachineBlockEntity 里用一个随机初值实现
     */
    long getOffset();

    /** 本 BE 的机器定义。 */
    MachineDefinition getDefinition();

    /** 懒加载的机器实例（第一次访问时通过 definition 创建）。 */
    MetaMachine getMetaMachine();

    /** LDLib 的根存储：机器自己的 FieldManagedStorage + 所有 trait 的 FieldManagedStorage 都挂在这上面。 */
    MultiManagedStorage getRootStorage();

    @Override
    default void saveCustomPersistedData(CompoundTag tag, boolean forDrop) {
        IAutoPersistBlockEntity.super.saveCustomPersistedData(tag, forDrop);
        getMetaMachine().saveCustomPersistedData(tag, forDrop);
    }

    @Override
    default void loadCustomPersistedData(CompoundTag tag) {
        IAutoPersistBlockEntity.super.loadCustomPersistedData(tag);
        getMetaMachine().loadCustomPersistedData(tag);
    }

    /** 当前染色（ARGB）；-1 表示未染色。 */
    default int getPaintingColor() {
        return getMetaMachine().getPaintingColor();
    }

    default void setPaintingColor(int color) {
        getMetaMachine().setPaintingColor(color);
    }

    /** 默认染色：definition 里的值，未设置时 -1。 */
    default int getDefaultPaintingColor() {
        return getMetaMachine().getDefaultPaintingColor();
    }
}
