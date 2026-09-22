package rain.fox.ogmr.api.blockentity;

import rain.fox.ogmr.api.machine.IMachineBlockEntity;
import rain.fox.ogmr.api.machine.MachineDefinition;
import rain.fox.ogmr.api.machine.MetaMachine;

import com.lowdragmc.lowdraglib.syncdata.managed.MultiManagedStorage;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import lombok.Getter;

import org.jetbrains.annotations.Nullable;

import java.util.concurrent.ThreadLocalRandom;

/**
 * 本文件是从 GTM 的 {@code com.gregtechceu.gtceu.api.blockentity.MetaMachineBlockEntity}
 * 精简拆出来的。
 *
 * <p>
 * 机器方块实体：它自己几乎没有逻辑，只做三件事 ——
 * <ol>
 * <li>持有 {@link MultiManagedStorage} 根存储（机器与 trait 的同步/持久化字段都挂在这上面）；</li>
 * <li>持有并<b>懒加载</b> {@link MetaMachine}；</li>
 * <li>把原版的 tick 转发给机器（{@link #serverTick(Level, BlockPos, BlockState, MachineBlockEntity)} /
 * {@link #clientTick(Level, BlockPos, BlockState, MachineBlockEntity)}）。</li>
 * </ol>
 *
 * <p>
 * 与 GTM 的差别：
 * <ul>
 * <li>没有 {@code MachineRenderState}（本库精简版不做渲染状态属性）；</li>
 * <li>没有覆盖板 / 工具 / AE2 的能力分发（{@code getCapability} 一大坨都砍了）；</li>
 * <li>机器的创建改在 {@link #getMetaMachine()} 里懒加载，而不是构造器里直接建
 * （构造器里建需要 definition 的 machineSupplier 已经就绪，懒加载对注册顺序更宽容）。</li>
 * </ul>
 */
public class MachineBlockEntity extends BlockEntity implements IMachineBlockEntity {

    /** 根存储：机器自己 + 所有 trait + RecipeLogic 的 FieldManagedStorage 都 attach 在这上面。 */
    @Getter
    private final MultiManagedStorage rootStorage = new MultiManagedStorage();

    /** 本 BE 的机器定义。 */
    @Getter
    private final MachineDefinition definition;

    /** 每个 BE 固定的偏移量（0~19），用于把同类机器的周期任务错开。 */
    @Getter
    private final long offset = ThreadLocalRandom.current().nextInt(20);

    /** 懒加载的机器实例。 */
    @Nullable
    private MetaMachine metaMachine;

    public MachineBlockEntity(MachineDefinition definition, BlockPos pos, BlockState state) {
        super(definition.getBlockEntityType(), pos, state);
        this.definition = definition;
    }

    // ═══════════════ IMachineBlockEntity ═══════════════

    @Override
    public BlockEntity self() {
        return this;
    }

    @Override
    public MetaMachine getMetaMachine() {
        if (metaMachine == null) {
            metaMachine = definition.createMetaMachine(this);
        }
        return metaMachine;
    }

    // ═══════════════ tick ═══════════════

    /**
     * 服务端每 tick（由 {@code MachineBlock#getTicker} 挂上）。
     *
     * <p>
     * 顺序很重要：先跑机器的 {@link MetaMachine#serverTick()}（里面驱动 tick 订阅与结构检查），
     * 再调 {@link #defaultServerTick()}（LDLib 的自动同步：把本 tick 变脏的字段打包发给
     * 追踪该区块的客户端）。
     */
    public static void serverTick(Level level, BlockPos pos, BlockState state, MachineBlockEntity blockEntity) {
        MetaMachine machine = blockEntity.getMetaMachine();
        machine.serverTick();
        blockEntity.defaultServerTick();
    }

    /** 客户端每 tick。 */
    public static void clientTick(Level level, BlockPos pos, BlockState state, MachineBlockEntity blockEntity) {
        blockEntity.getMetaMachine().clientTick();
    }

    // ═══════════════ 生命周期 ═══════════════

    /**
     * Forge 能力分发：转发给 {@link MetaMachine#getCapability}，机器不给再问 BE 自己（默认）。
     *
     * <p>
     * 这一条是「机器能被外部访问」的入口：能量仓能被线缆充电、物品仓能被漏斗/管道灌注，
     * 全靠它会问到底下的 trait。没有它，仓室内部的物品栏对外界是隐形的。
     *
     * <p>
     * ⚠️ Forge 1.20.1 的 {@code ICapabilityProvider#getCapability} 返回的是
     * {@link LazyOptional}（不是裸值），所以这里把机器给出的对象包一层。
     */
    @Override
    public <T> net.minecraftforge.common.util.LazyOptional<T> getCapability(
                                                                             net.minecraftforge.common.capabilities.Capability<T> capability,
                                                                             @org.jetbrains.annotations.Nullable net.minecraft.core.Direction side) {
        T fromMachine = getMetaMachine().getCapability(capability, side);
        if (fromMachine != null) {
            return net.minecraftforge.common.util.LazyOptional.of(() -> fromMachine);
        }
        return super.getCapability(capability, side);
    }

    @Override
    public void onLoad() {
        // BlockEntity 本身没有 onLoad（Forge 通过 IForgeBlockEntity 的默认方法提供），
        // 这里先走默认实现（请求一次模型数据刷新），再交给机器。
        super.onLoad();
        // 强制创建机器：保证「第一次 tick / 第一次 clearRemoved 的存储 init」之前机器与它的
        // trait 已经挂在根存储上（懒加载 + 挂载顺序的坑就在这）。
        getMetaMachine().onLoad();
    }

    @Override
    public void setRemoved() {
        super.setRemoved();
        if (metaMachine != null) {
            // 退订服务端 tick、转发 onMachineUnLoad
            metaMachine.onUnload();
        }
    }

    /** 方块事件（服务端 {@code scheduleRenderUpdate()} 走这里，在客户端落地成一次渲染刷新）。 */
    @Override
    public boolean triggerEvent(int id, int param) {
        if (id == 1) { // chunk re render
            if (level != null && level.isClientSide) {
                scheduleRenderUpdate();
            }
            return true;
        }
        return super.triggerEvent(id, param);
    }

    // ═══════════════ 存盘 / 同步 ═══════════════

    /**
     * 读档。
     *
     * <p>
     * ⚠️ <b>不要</b>在这里再调 {@code loadManagedPersistentData(tag)}：LDLib 的
     * {@code com.lowdragmc.lowdraglib.core.mixins.BlockEntityMixin#injectLoad} 已经在
     * {@code BlockEntity.load()} 的 RETURN 处做了这件事（有同步包就解析同步包，否则读回
     * {@code @Persisted} 字段），而 {@code loadManagedPersistentData} 内部还会派发
     * {@code loadCustomPersistedData}。再调一次 = 同一份数据读两遍。
     *
     * <p>
     * TODO(ogmr): 如果将来 LDLib 去掉那个 mixin，这里补两行即可：
     * {@code loadManagedPersistentData(tag);}（它内部会转发 {@code loadCustomPersistedData}）。
     */
    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
    }

    /**
     * 存档 —— 同上，持久化由 LDLib 的 {@code BlockEntityMixin#injectSaveAdditional} 在
     * {@code BlockEntity.saveAdditional()} 的 RETURN 处完成（{@code saveManagedPersistentData(tag, false)}，
     * 内部派发 {@code saveCustomPersistedData}）。
     *
     * <p>
     * TODO(ogmr): 如果将来 LDLib 去掉那个 mixin，这里补一行：
     * {@code saveManagedPersistentData(tag, false);}
     */
    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
    }

    /**
     * 区块更新包用的 tag。
     *
     * <p>
     * 必须调 {@code super.getUpdateTag()}：LDLib 的 {@code BlockEntityMixin#injectGetUpdateTag}
     * 会在原版实现的 RETURN 处塞进 {@code SPacketManagedPayload}
     * （其中包含 {@code writeCustomSyncData} 写入的自定义同步数据），自己 new 一个空 tag 会把它丢掉。
     */
    @Override
    public CompoundTag getUpdateTag() {
        return super.getUpdateTag();
    }

    /**
     * 客户端收到区块更新包。
     *
     * <p>
     * Forge 的默认实现就是 {@code self().load(tag)}，而 LDLib 的同步解析挂在
     * {@code BlockEntity.load()} 上，所以直接复用默认实现。
     */
    @Override
    public void handleUpdateTag(CompoundTag tag) {
        super.handleUpdateTag(tag);
    }
}
