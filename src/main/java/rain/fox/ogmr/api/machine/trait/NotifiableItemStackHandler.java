package rain.fox.ogmr.api.machine.trait;

import rain.fox.ogmr.api.machine.MachineTrait;
import rain.fox.ogmr.api.machine.MetaMachine;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.items.ItemStackHandler;

import lombok.Getter;

import org.jetbrains.annotations.NotNull;

/**
 * 可通知的机器物品栏 —— 本库精简版的 {@code NotifiableItemStackHandler}。
 *
 * <p>
 * GTM 的那套（{@code NotifiableItemStackHandler} + {@code RecipeHandlerList} + 能力代理）依赖
 * GTM 自己的 trait/capability 体系，本库不照搬；这里只保留「机器的一块物品栏，
 * 内容一变就回调」这个最小语义 —— 模块化机器的模块槽、仓室的输入输出槽都用它。
 *
 * <p>
 * 序列化走 {@link MachineTrait} 自己的 NBT 通道（由机器在
 * {@code saveCustomPersistedData} / {@code loadCustomPersistedData} 里转发），
 * 因此不需要 LDLib 的字段反射。
 */
public class NotifiableItemStackHandler extends MachineTrait {

    @Getter
    protected final ItemStackHandler handler;

    /** 是否允许外部插入 / 抽出（模块槽一般两个都关掉）。 */
    protected boolean allowInsert = true;
    protected boolean allowExtract = true;

    public NotifiableItemStackHandler(MetaMachine machine, int slots) {
        this(machine, slots, true, true);
    }

    public NotifiableItemStackHandler(MetaMachine machine, int slots, boolean allowInsert, boolean allowExtract) {
        super(machine);
        this.allowInsert = allowInsert;
        this.allowExtract = allowExtract;
        this.handler = new ItemStackHandler(slots) {

            @Override
            protected void onContentsChanged(int slot) {
                super.onContentsChanged(slot);
                NotifiableItemStackHandler.this.onContentsChanged();
            }

            @Override
            public boolean isItemValid(int slot, @NotNull ItemStack stack) {
                return NotifiableItemStackHandler.this.isItemValid(slot, stack);
            }
        };
    }

    // ═══════════════ 访问 ═══════════════

    public int getSlots() {
        return handler.getSlots();
    }

    public ItemStack getStackInSlot(int slot) {
        return handler.getStackInSlot(slot);
    }

    public void setStackInSlot(int slot, ItemStack stack) {
        handler.setStackInSlot(slot, stack);
    }

    /** 直接写内容且不触发回调（读档时用）。 */
    public void setStackInSlotSilently(int slot, ItemStack stack) {
        this.silent = true;
        try {
            handler.setStackInSlot(slot, stack);
        } finally {
            this.silent = false;
        }
    }

    public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
        return allowInsert ? handler.insertItem(slot, stack, simulate) : stack;
    }

    public ItemStack extractItem(int slot, int amount, boolean simulate) {
        return allowExtract ? handler.extractItem(slot, amount, simulate) : ItemStack.EMPTY;
    }

    public boolean isEmpty() {
        for (int i = 0; i < handler.getSlots(); i++) {
            if (!handler.getStackInSlot(i).isEmpty()) return false;
        }
        return true;
    }

    private boolean silent = false;

    /** 内容变了（子类覆写；默认转发给机器 onChanged）。 */
    public void onContentsChanged() {
        if (silent) return;
        machine.onChanged();
    }

    /** 某一格是否接受这个物品（返回 false 时模块槽会拒绝非模块物品）。 */
    public boolean isItemValid(int slot, ItemStack stack) {
        return true;
    }

    // ═══════════════ 能力分发 ═══════════════

    /**
     * 把内部物品栏当作 Forge 的 {@code ITEM_HANDLER} 暴露出去。
     *
     * <p>
     * 有了这一条，机器<b>不用写任何胶水</b>就能被漏斗、管道、AE2 总线之类访问 ——
     * {@link rain.fox.ogmr.api.machine.MetaMachine#getCapability} 会依次问每个 trait。
     * 插入/抽出仍受 {@link #allowInsert} / {@link #allowExtract} 约束（模块槽那种就是用它们关掉的）。
     */
    @Override
    @SuppressWarnings("unchecked")
    public <T> T getCapability(net.minecraftforge.common.capabilities.Capability<T> capability,
                               net.minecraft.core.Direction side) {
        if (capability == net.minecraftforge.common.capabilities.ForgeCapabilities.ITEM_HANDLER) {
            return (T) handler;
        }
        return null;
    }

    // ═══════════════ 序列化 ═══════════════

    @Override
    public CompoundTag serializeNBT() {
        CompoundTag tag = new CompoundTag();
        tag.put("Items", handler.serializeNBT());
        return tag;
    }

    @Override
    public void deserializeNBT(CompoundTag tag) {
        if (tag.contains("Items")) {
            this.silent = true;
            try {
                handler.deserializeNBT(tag.getCompound("Items"));
            } finally {
                this.silent = false;
            }
        }
    }
}
