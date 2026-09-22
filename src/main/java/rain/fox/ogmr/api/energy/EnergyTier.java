package rain.fox.ogmr.api.energy;

import rain.fox.ogmr.api.OGMRValues;

/**
 * 本文件是为 ogmr 新写的能量系统（需求 7）。
 *
 * <p>
 * 把 {@link OGMRValues} 的 <b>15 个电压档</b>（ULV…MAX）和「能源仓大小 0~7」
 * （{@link EnergyHatchSizes} 的注册下标）对应起来，让「按机器档位自动挑仓室」这种常见需求
 * 不用每个 addon 抄一遍映射表。
 *
 * <pre>
 * 仓室下标   0     1     2     3     4     5     6      7
 * 电压档     LV    MV    HV    EV    IV    LuV   ZPM    UV
 * （默认预设）tiny  small normal reinforced big huge ludicrous ultimate
 * </pre>
 *
 * <p>
 * <b>这是本库的默认映射，不是规则。</b>映射只跟「注册顺序」有关，与具体数值无关 ——
 * 也就是说它天然适配 {@link EnergyHatchSizes#registerDefaultPreset()} 的 8 级预设，
 * 也适配 addon 自己按「由弱到强」顺序注册的任意尺寸表。
 * addon 完全可以不用它：例如某个整合包想把 MAX 档接某个自定义大仓、
 * 或者把 ZPM 接下标 4，直接自己写映射或用 {@link EnergyHatchSizes} 的原始数值即可。
 *
 * <p>
 * 低档（ULV）钳到下标 0，高档（UHV/UEV/UIV/UXV/OpV/MAX，电压档 9~14）钳到最后一个下标 ——
 * 因为仓室表通常只有 8 级，超出的档位没有对应的仓室，钳紧比抛异常实用。
 *
 * <p>
 * 注意：{@link #capacityForTier(int)} / {@link #maxInputForTier(int)} / {@link #sizeForTier(int)}
 * 会去查注册表，<b>表为空时 {@link EnergyHatchSizes#of(int)} 会抛
 * {@link IllegalStateException}</b>（先注册尺寸或调一次
 * {@link EnergyHatchSizes#registerDefaultPreset()}）。
 */
public final class EnergyTier {

    private EnergyTier() {}

    /** 映射到的最小仓室下标。 */
    public static final int MIN_HATCH_SIZE = 0;

    /**
     * 映射到的最大仓室下标（= 当前注册表数量 - 1）。
     *
     * <p>
     * 故意做成<b>方法</b>而不是常量：仓室表是可注册的，常量会在类加载那一刻被冻死
     * （比如在 addon 注册尺寸之前就被读成 -1）。
     */
    public static int maxHatchSize() {
        return Math.max(EnergyHatchSizes.sizeCount() - 1, 0);
    }

    /** 参与默认映射的最低电压档（LV）。 */
    public static final int MIN_TIER = OGMRValues.LV;

    /** 参与默认映射的最高电压档（UV）。 */
    public static final int MAX_TIER = OGMRValues.UV;

    /**
     * 电压档 → 仓室下标：LV→0、MV→1、…、UV→7；
     * ULV 及以下钳到 0，超出当前仓室表范围的高档钳到最后一个下标。
     */
    public static int hatchSizeForTier(int tier) {
        int size = OGMRValues.clampTier(tier) - MIN_TIER;
        if (size < MIN_HATCH_SIZE) return MIN_HATCH_SIZE;
        return Math.min(size, maxHatchSize());
    }

    /** 仓室大小 → 电压档：0→LV、1→MV、…、7→UV。越界大小自动夹紧。 */
    public static int tierForHatchSize(int size) {
        return Math.max(size, 0) + MIN_TIER;
    }

    /** 该电压档对应的仓室尺寸定义（查注册表；表为空时抛异常）。 */
    public static EnergyHatchSize sizeForTier(int tier) {
        return EnergyHatchSizes.of(hatchSizeForTier(tier));
    }

    /** 该电压档对应仓室的容量（FE）。 */
    public static long capacityForTier(int tier) {
        return sizeForTier(tier).capacityFe();
    }

    /** 该电压档对应仓室的最大输入（FE/t）。 */
    public static long maxInputForTier(int tier) {
        return sizeForTier(tier).maxInputFe();
    }

    /**
     * 该电压档的额定电压，换算成 FE/t（{@code OGMRValues.V[tier] EU/t × 4 FE/EU}）。
     *
     * <p>
     * 注意这和 {@link #maxInputForTier(int)} 不是一回事：前者是「这一档的电压」，
     * 后者是「默认给这一档配的仓室能塞多快」。低档时 V 明显小于仓室速率（HV = 512 EU/t = 2048 FE/t，
     * 而 normal 仓只有 512 FE/t），这是 MM 数值表的原样，不是笔误。
     */
    public static long tierVoltageFe(int tier) {
        return EnergyConversion.euToFe(OGMRValues.getTierPower(tier));
    }
}
