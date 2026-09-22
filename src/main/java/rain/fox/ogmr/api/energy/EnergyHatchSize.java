package rain.fox.ogmr.api.energy;

import java.util.Locale;

/**
 * 本文件是为 ogmr 新写的能量系统（需求 7）。
 *
 * <p>
 * 一「级」能源仓的尺寸定义：容量、输入速率、输出速率、默认单位。
 * 它是<b>可注册的数据</b>，不再写死在库里 —— addon / 整合包可以注册自己的
 * 「2.5 倍大仓」「只进不出的蓄水池仓」之类，本库不预设你的数值。
 *
 * <p>
 * 数值单位是 <b>FE</b>（内部基准）。{@link #defaultUnit()} 只决定这台仓室
 * <i>默认</i>用哪个单位报数/显示，不影响存储 —— 存储永远是 FE，见
 * {@link EnergyHatchPartMachine#getUnit()}。
 *
 * <p>
 * 构造时会做规范化：名字去空白 + 转小写、负数夹到 0、{@code defaultUnit} 为 null 时按 FE。
 * 于是「注册表里的名字」和「构造时写的名字」不会因为大小写/空格而分裂成两条。
 *
 * @param name        内部名（小写），例如 {@code "tiny"}；同时是语言键后缀，见 {@link EnergyHatchSizes#langKey(String)}
 * @param capacityFe  容量（FE）
 * @param maxInputFe  单次最大可接受量（FE/t）
 * @param maxOutputFe 单次最大可给出量（FE/t）
 * @param defaultUnit 默认对外单位（内部存储仍是 FE）；类型是 {@link IEnergyType}，
 *                    所以第三方注册的自定义能量也能写在这里
 */
public record EnergyHatchSize(String name, long capacityFe, long maxInputFe, long maxOutputFe,
                              IEnergyType defaultUnit) {

    /** 紧凑构造：规范化入参，避免注册表里出现 {@code "Tiny "} 和 {@code "tiny"} 两条。 */
    public EnergyHatchSize {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("energy hatch size name must not be blank");
        }
        name = name.trim().toLowerCase(Locale.ROOT);
        capacityFe = Math.max(capacityFe, 0L);
        maxInputFe = Math.max(maxInputFe, 0L);
        maxOutputFe = Math.max(maxOutputFe, 0L);
        if (defaultUnit == null) defaultUnit = EnergyTypes.DEFAULT;
    }

    /** 收发对称的最常用写法（MM 的能源仓就是这样），默认单位 FE。 */
    public static EnergyHatchSize of(String name, long capacityFe, long maxInputFe) {
        return new EnergyHatchSize(name, capacityFe, maxInputFe, maxInputFe, EnergyTypes.DEFAULT);
    }

    /** 收发对称 + 指定默认单位（任意 {@link IEnergyType}，含第三方自定义的）。 */
    public static EnergyHatchSize of(String name, long capacityFe, long maxInputFe, IEnergyType defaultUnit) {
        return new EnergyHatchSize(name, capacityFe, maxInputFe, maxInputFe, defaultUnit);
    }

    /** 换一个默认单位（容量/速率不变）。 */
    public EnergyHatchSize withUnit(IEnergyType unit) {
        return new EnergyHatchSize(name, capacityFe, maxInputFe, maxOutputFe, unit);
    }

    /** 本尺寸的语言键，例如 {@code "ogmr.energy.hatch.tiny"}。 */
    public String langKey() {
        return EnergyHatchSizes.langKey(name);
    }

    /** 能收能量吗（输入速率 > 0）。 */
    public boolean canReceive() {
        return maxInputFe > 0L;
    }

    /** 能发能量吗（输出速率 > 0）。 */
    public boolean canExtract() {
        return maxOutputFe > 0L;
    }

    /** 日志用：{@code tiny(2048 FE, in 128 FE/t, out 128 FE/t, FE)}。 */
    public String describe() {
        return name + "(" + capacityFe + " FE, in " + maxInputFe + " FE/t, out " + maxOutputFe
                + " FE/t, " + defaultUnit.getSymbol() + ")";
    }

    @Override
    public String toString() {
        return describe();
    }
}
