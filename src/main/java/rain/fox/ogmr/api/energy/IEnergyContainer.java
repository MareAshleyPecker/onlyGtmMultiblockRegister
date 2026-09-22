package rain.fox.ogmr.api.energy;

/**
 * 本文件是为 ogmr 新写的能量系统（需求 7）。
 *
 * <p>
 * 能量容器的最小契约：所有数值一律是 <b>FE</b>（Forge Energy），一律 {@code long}
 * （Forge 自带的 {@code IEnergyStorage} 只有 {@code int}，装不下 UV/MAX 档的储能，
 * 所以本库内部不用它；要暴露给别的 mod 时用 {@link EnergyContainer#asForgeStorage()}）。
 *
 * <p>
 * {@link #acceptEnergy(long)} / {@link #extractEnergy(long)} 是「这一次操作」的语义：
 * 实现方应当自己把请求量夹到 {@link #getMaxInput()} / {@link #getMaxOutput()} 与剩余空间/存量之内，
 * 返回<b>真正吃下/给出的量</b>，而不是抛异常或者假装成功。
 * 一个 tick 内被调用多次是允许的（多方块控制器常常从多个仓室各取一次）。
 *
 * <p>
 * 本接口只描述「存 + 收 + 发」，不描述「谁给谁送」。多方块内部的能量调度由控制器负责，
 * 仓室保持哑存储。
 */
public interface IEnergyContainer {

    // ═══════════════ 状态 ═══════════════

    /** 当前存量（FE）。 */
    long getEnergyStored();

    /** 容量上限（FE）。 */
    long getEnergyCapacity();

    /** 单次最大可接受的量（FE/t）。0 表示本容器只出不进。 */
    long getMaxInput();

    /** 单次最大可给出的量（FE/t）。0 表示本容器只进不出。 */
    long getMaxOutput();

    // ═══════════════ 收发 ═══════════════

    /**
     * 尽量接受 {@code amount} FE。
     *
     * @return 实际收下的量（0 ≤ 返回值 ≤ min(amount, getMaxInput(), 剩余空间)）
     */
    long acceptEnergy(long amount);

    /**
     * 尽量给出 {@code amount} FE。
     *
     * @return 实际给出的量（0 ≤ 返回值 ≤ min(amount, getMaxOutput(), 当前存量)）
     */
    long extractEnergy(long amount);

    // ═══════════════ 便利查询（默认实现） ═══════════════

    /** 满了。 */
    default boolean isFull() {
        return getEnergyStored() >= getEnergyCapacity();
    }

    /** 空了。 */
    default boolean isEmpty() {
        return getEnergyStored() <= 0L;
    }

    /** 存量占比，0.0 ~ 1.0（容量为 0 时返回 0.0；存量异常超容时夹到 1.0）。 */
    default double getStoredRatio() {
        long capacity = getEnergyCapacity();
        if (capacity <= 0L) return 0.0;
        double ratio = (double) getEnergyStored() / (double) capacity;
        if (ratio <= 0.0) return 0.0;
        return Math.min(ratio, 1.0);
    }

    /** 还能再塞多少（FE）。 */
    default long getFreeSpace() {
        long free = getEnergyCapacity() - getEnergyStored();
        return Math.max(free, 0L);
    }

    /** 能不能收能量（{@link #getMaxInput()} > 0 且没满）。 */
    default boolean canReceive() {
        return getMaxInput() > 0L && !isFull();
    }

    /** 能不能给能量（{@link #getMaxOutput()} > 0 且非空）。 */
    default boolean canExtract() {
        return getMaxOutput() > 0L && !isEmpty();
    }

    /** 存量百分比文本，例如 {@code "62.5%"}（显示用）。 */
    default String getStoredPercentText() {
        return EnergyConversion.formatPercent(getStoredRatio());
    }
}
