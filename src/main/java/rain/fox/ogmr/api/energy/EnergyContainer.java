package rain.fox.ogmr.api.energy;

import net.minecraftforge.energy.IEnergyStorage;

/**
 * 本文件是为 ogmr 新写的能量系统（需求 7）。
 *
 * <p>
 * {@link IEnergyContainer} 的简易可变实现：一个容量 + 输入/输出速率都固定的「电池」。
 * 仓室、单方块机器、甚至测试代码都可以直接用它，不必各自写一遍加减法。
 *
 * <h3>线程安全</h3>
 * <p>
 * 全部读写方法都是 {@code synchronized}（锁就是 {@code this}）。
 * 之所以不用 {@code AtomicLong}：存量、容量、输入输出上限是<b>一组</b>互相约束的状态，
 * 用 CAS 拼出「不超过容量」的语义要写循环重试，还不如一把锁清楚。
 * 本类的方法都很短（纯算术，没有 IO、没有回调），锁竞争不在热路径上。
 *
 * <p>
 * 注意：不要在 {@code synchronized} 方法里回调外部代码（那会引入死锁风险）。
 * 本类没有任何回调。
 */
public class EnergyContainer implements IEnergyContainer {

    /** 容量上限（FE），构造后不变。 */
    private final long capacity;

    private long maxInput;
    private long maxOutput;

    /** 当前存量（FE），恒在 {@code [0, capacity]} 内。 */
    private long energy;

    /**
     * @param capacityFe  容量上限（FE），负数按 0 处理
     * @param maxInputFe  单次最大可接受量（FE/t），负数按 0 处理
     * @param maxOutputFe 单次最大可给出量（FE/t），负数按 0 处理
     */
    public EnergyContainer(long capacityFe, long maxInputFe, long maxOutputFe) {
        this.capacity = Math.max(capacityFe, 0L);
        this.maxInput = Math.max(maxInputFe, 0L);
        this.maxOutput = Math.max(maxOutputFe, 0L);
    }

    /** 输入输出对称的常用构造（MM 的能源仓就是这种）。 */
    public EnergyContainer(long capacityFe, long maxInputFe) {
        this(capacityFe, maxInputFe, maxInputFe);
    }

    // ═══════════════ 读 ═══════════════

    @Override
    public synchronized long getEnergyStored() {
        return energy;
    }

    @Override
    public long getEnergyCapacity() {
        return capacity;
    }

    @Override
    public synchronized long getMaxInput() {
        return maxInput;
    }

    @Override
    public synchronized long getMaxOutput() {
        return maxOutput;
    }

    // ═══════════════ 收发 ═══════════════

    @Override
    public synchronized long acceptEnergy(long amount) {
        if (amount <= 0L || maxInput <= 0L) return 0L;
        long accepted = Math.min(Math.min(amount, maxInput), capacity - energy);
        if (accepted <= 0L) return 0L;
        energy += accepted;
        return accepted;
    }

    @Override
    public synchronized long extractEnergy(long amount) {
        if (amount <= 0L || maxOutput <= 0L) return 0L;
        long extracted = Math.min(Math.min(amount, maxOutput), energy);
        if (extracted <= 0L) return 0L;
        energy -= extracted;
        return extracted;
    }

    // ═══════════════ 调试 / 管理用写方法 ═══════════════

    /** 直接设定存量（夹到 {@code [0, capacity]}）。 */
    public synchronized void setEnergy(long fe) {
        if (fe <= 0L) {
            energy = 0L;
        } else {
            energy = Math.min(fe, capacity);
        }
    }

    /** 加能量（夹到容量上限），返回实际加进去的量。 */
    public synchronized long addEnergy(long fe) {
        if (fe <= 0L) return 0L;
        long added = Math.min(fe, capacity - energy);
        if (added <= 0L) return 0L;
        energy += added;
        return added;
    }

    /** 扣能量（夹到 0），返回实际扣掉的量。 */
    public synchronized long removeEnergy(long fe) {
        if (fe <= 0L) return 0L;
        long removed = Math.min(fe, energy);
        energy -= removed;
        return removed;
    }

    /** 改单次最大输入（FE/t）。 */
    public synchronized void setMaxInput(long maxInputFe) {
        this.maxInput = Math.max(maxInputFe, 0L);
    }

    /** 改单次最大输出（FE/t）。 */
    public synchronized void setMaxOutput(long maxOutputFe) {
        this.maxOutput = Math.max(maxOutputFe, 0L);
    }

    /** 充满（调试 / 创造模式仓室用）。 */
    public synchronized void setFull() {
        energy = capacity;
    }

    /** 清空。 */
    public synchronized void clear() {
        energy = 0L;
    }

    // ═══════════════ Forge 适配 ═══════════════

    /**
     * 把本容器当成 Forge Energy 接口暴露给别的 mod 的机器。
     *
     * <p>
     * 读写都通（不是只读）：别的 mod 通过 Forge 能力往里塞/往外抽，最终都会落到本容器的
     * {@link #acceptEnergy(long)} / {@link #extractEnergy(long)}，因此同样受
     * 最大输入/输出与容量的夹紧约束。
     *
     * <p>
     * Forge 的 {@code IEnergyStorage} 是 {@code int} 制，超过 {@link Integer#MAX_VALUE} 的值会被夹紧
     * （本库的 ultimate 仓容量 2097152 FE 远小于它，实际不会碰到）。
     *
     * <p>
     * 每次调用返回一个新的适配器实例；适配器本身无状态，等价于同一个对象的多个视图，
     * 所以不需要缓存。
     */
    public IEnergyStorage asForgeStorage() {
        return new ForgeStorageAdapter();
    }

    /** {@link #asForgeStorage()} 的实现：所有读写都转发回外层容器。 */
    private final class ForgeStorageAdapter implements IEnergyStorage {

        @Override
        public int receiveEnergy(int maxReceive, boolean simulate) {
            if (maxReceive <= 0) return 0;
            long request = Math.min((long) maxReceive, (long) Integer.MAX_VALUE);
            if (simulate) {
                long accepted = Math.min(Math.min(request, getMaxInput()), getFreeSpace());
                return saturateToInt(accepted);
            }
            return saturateToInt(acceptEnergy(request));
        }

        @Override
        public int extractEnergy(int maxExtract, boolean simulate) {
            if (maxExtract <= 0) return 0;
            long request = Math.min((long) maxExtract, (long) Integer.MAX_VALUE);
            if (simulate) {
                // 注意：这里必须写 EnergyContainer.this.* —— 适配器自己也有同名方法
                long extracted = Math.min(Math.min(request, getMaxOutput()), EnergyContainer.this.getEnergyStored());
                return saturateToInt(extracted);
            }
            return saturateToInt(EnergyContainer.this.extractEnergy(request));
        }

        @Override
        public int getEnergyStored() {
            return saturateToInt(EnergyContainer.this.getEnergyStored());
        }

        @Override
        public int getMaxEnergyStored() {
            return saturateToInt(capacity);
        }

        @Override
        public boolean canExtract() {
            return getMaxOutput() > 0L;
        }

        @Override
        public boolean canReceive() {
            return getMaxInput() > 0L;
        }
    }

    /** FE 的 long 值 → Forge 的 int（越界夹紧，负数归 0）。 */
    private static int saturateToInt(long value) {
        if (value <= 0L) return 0;
        return value >= (long) Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) value;
    }

    @Override
    public String toString() {
        return "EnergyContainer[" + EnergyConversion.format(getEnergyStored())
                + " / " + EnergyConversion.format(capacity)
                + ", in " + EnergyConversion.formatAmount(maxInput, EnergyTypes.FE) + "/t"
                + ", out " + EnergyConversion.formatAmount(maxOutput, EnergyTypes.FE) + "/t]";
    }
}
