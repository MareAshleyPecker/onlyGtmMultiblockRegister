package rain.fox.ogmr.api.machine;

import com.lowdragmc.lowdraglib.syncdata.IManaged;
import com.lowdragmc.lowdraglib.syncdata.IManagedStorage;
import com.lowdragmc.lowdraglib.syncdata.ITagSerializable;
import com.lowdragmc.lowdraglib.syncdata.field.FieldManagedStorage;
import com.lowdragmc.lowdraglib.syncdata.field.ManagedFieldHolder;
import com.lowdragmc.lowdraglib.syncdata.managed.IRef;

import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraftforge.client.model.data.ModelData;
import net.minecraftforge.common.capabilities.Capability;

import org.jetbrains.annotations.Nullable;

/**
 * 本文件是从 GTM 的 {@code com.gregtechceu.gtceu.api.machine.trait.MachineTrait} 精简拆出来的。
 *
 * <p>
 * trait = 机器的一项「能力」（物品栏、流体罐、能量容器、配方逻辑……）。它和机器一样是 IManaged，
 * 自己带一份 {@link FieldManagedStorage}，构造时把自己挂到 BE 的根存储上，于是它内部的
 * {@code @Persisted} / {@code @DescSynced} 字段会自动参与存盘和同步。
 *
 * <p>
 * 生命周期顺序：trait 在机器构造期间 new 出来（构造器里就完成挂载）→ {@link #onMachineLoad()}
 * → 运行期 → {@link #onMachineUnLoad()}。
 *
 * <p>
 * 与 GTM 的差别：这里实现的是 {@link IManaged} 而不是 {@code IEnhancedManaged}
 * （本精简版没有 {@code MachineRenderState}，因此不需要 {@code scheduleRenderUpdate()} 那条链路）。
 */
public abstract class MachineTrait implements IManaged, ITagSerializable<CompoundTag> {

    /** 基类的字段持有者；子类写 {@code new ManagedFieldHolder(MyTrait.class, MachineTrait.MANAGED_FIELD_HOLDER)}。 */
    public static final ManagedFieldHolder MANAGED_FIELD_HOLDER = new ManagedFieldHolder(MachineTrait.class);

    /** 本 trait 自己的同步存储。 */
    private final FieldManagedStorage syncStorage = new FieldManagedStorage(this);

    /** 所属机器。 */
    public final MetaMachine machine;

    protected MachineTrait(MetaMachine machine) {
        this.machine = machine;
        // 把自己的 FieldManagedStorage 挂到 BE 的根存储（MultiManagedStorage）上：
        // 只有挂上去，trait 里的 @Persisted / @DescSynced 字段才会被 BE 的存盘与每 tick 同步扫到。
        // 挂载必须发生在 attachTraits 之前，保证「同步字段已登记」先于「机器开始 tick」。
        // 注：/!\ GTM 原版只调用 machine.attachTraits(this)，trait 的存储并没有挂到根存储上
        //     （GTM 的 trait 字段靠各自的访问器单独走），本精简版改为统一挂载，行为更直观。
        if (machine.holder.getRootStorage() != null) {
            machine.holder.getRootStorage().attach(syncStorage);
        }
        machine.attachTraits(this);
    }

    @Override
    public ManagedFieldHolder getFieldHolder() {
        return MANAGED_FIELD_HOLDER;
    }

    @Override
    public IManagedStorage getSyncStorage() {
        return syncStorage;
    }

    // ═══════════════ 生命周期钩子 ═══════════════

    /** 字段变脏时由 LDLib 回调；默认转发给机器。 */
    @Override
    public void onChanged() {
        machine.onChanged();
    }

    /** 一个被同步字段真的变化了（同步到客户端之后/本地更新时）时的钩子；默认空。 */
    public void onSync() {}

    @Override
    public void onSyncChanged(IRef ref, boolean isDirty) {
        if (isDirty) {
            onSync();
        }
    }

    /** 机器载入世界。 */
    public void onMachineLoad() {}

    /** 机器离开世界。 */
    public void onMachineUnLoad() {}

    /**
     * 把自己的所有被同步字段标记为脏（下个 tick 会随 BE 一起同步给客户端）。
     *
     * <p>
     * TODO(ogmr): GTM 没有这个方法（那边靠 {@code IManaged#markDirty(String)} 按字段名标记）；
     * 本库提供一个「整体标脏」的简化入口，避免调用方去维护字段名常量。
     */
    public void markAsDirty() {
        getSyncStorage().markAllDirty();
    }

    // ═══════════════ 能力分发 ═══════════════

    /**
     * Forge 能力查询。
     *
     * <p>
     * 默认返回 {@code null}（= 本 trait 不提供这个能力）。带物品栏的 trait
     * （例如 {@code NotifiableItemStackHandler}）覆写它返回自己的 {@code IItemHandler}，
     * 于是机器自动就能被漏斗/管道访问 —— {@link MetaMachine#getCapability} 会依次问每个 trait。
     *
     * @param capability 要查的能力
     * @param side       访问方向；{@code null} 表示不指定方向
     */
    @Nullable
    public <T> T getCapability(Capability<T> capability, @Nullable Direction side) {
        return null;
    }

    // ═══════════════ 模型 / 持久化 ═══════════════

    /** 供模型数据使用；默认空。 */
    public void updateModelData(ModelData.Builder builder) {}

    /**
     * 自定义持久化；默认空，子类按需覆写。
     *
     * @param forDrop true = 这是「被破坏掉落成物品」时保存
     */
    public void saveCustomPersistedData(CompoundTag tag, boolean forDrop) {}

    public void loadCustomPersistedData(CompoundTag tag) {}

    /**
     * 序列化成 NBT。
     *
     * <p>
     * TODO(ogmr): 和 {@link MetaMachine#serializeNBT()} 一样，本精简版只走自定义持久化，
     * {@code @Persisted} 字段由 BE 的 saveManagedPersistentData / loadManagedPersistentData 负责。
     */
    @Override
    public CompoundTag serializeNBT() {
        CompoundTag tag = new CompoundTag();
        saveCustomPersistedData(tag, false);
        return tag;
    }

    @Override
    public void deserializeNBT(CompoundTag tag) {
        loadCustomPersistedData(tag);
    }
}
