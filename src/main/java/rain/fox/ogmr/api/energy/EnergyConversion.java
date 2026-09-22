package rain.fox.ogmr.api.energy;

import rain.fox.ogmr.Ogmr;

import java.util.Locale;

/**
 * 本文件是为 ogmr 新写的能量系统（需求 7）。
 *
 * <p>
 * 单位换算中枢。内部一切能量都以 <b>FE</b>（Forge Energy）的 {@code long} 表示，
 * 本类负责「任意 {@link IEnergyType} ↔ FE」的双向换算、任意两种能量之间的互转、
 * 以及给 UI/日志用的统一格式化。
 *
 * <p>
 * ⚠️ 本类<b>不认识任何具体的能量名字</b>：它只跟 {@link IEnergyType#getFePerUnit()} 打交道。
 * 加一种新能量不需要动这个文件，实现 {@link IEnergyType} 并
 * {@code EnergyTypes.register(...)} 即可 —— 这是把原来写死的枚举换成接口之后的关键收益。
 * 之前写在枚举上的 {@code EU/FE/AE/J} 现在就是 {@link EnergyTypes} 里的四个实例。
 *
 * <h3>换算关系（内置五种的默认值，全部对着各 mod 本体核实过）</h3>
 * <ul>
 * <li>{@code 1 FE = 1 FE} —— 内部基准；</li>
 * <li>{@code 1 EU = 4 FE}（GT / IC2 惯例），即 {@code 1 EU = 10 J}
 * —— 与 Mekanism 的 {@code ic2ConversionRate} 默认值 10 一致；</li>
 * <li>{@code 1 AE = 2 FE}（Applied Energistics 2），即 {@code 1 AE = 5 J}；</li>
 * <li>{@code 1 RF = 1 FE}（CoFH / 热力膨胀）—— RF 在现代版本就是 FE 的另一个名字；</li>
 * <li>{@code 1 FE = 2.5 J}（Mekanism），即 {@code 1 J = 0.4 FE}
 * —— 与 Mekanism 的 {@code feConversionRate} 默认值 2.5 一致。</li>
 * </ul>
 *
 * <p>
 * 三条系数的出处（都是打开 mod 的 class 文件核对的，不是查资料）：
 * <ul>
 * <li><b>AE</b>：AE2 的 {@code appeng.api.config.PowerUnits.convertTo(target, amount)} 算的是
 * {@code amount × this.ratio ÷ target.ratio}，其中 {@code AE.ratio = 1.0}，
 * {@code FE.ratio = 配置项 powerRatioForgeEnergy 的默认值 0.5}（读自 {@code appeng.core.AEConfig}），
 * 于是 {@code AE → FE} 恰好 {@code × 2}；</li>
 * <li><b>RF</b>：CoFH 的 {@code cofh.lib.common.energy.IRedstoneFluxStorage} 定义就是
 * {@code extends net.minecraftforge.energy.IEnergyStorage}，<b>一个额外方法都没有</b>，
 * 所以不存在 RF→FE 的换算因子；</li>
 * <li><b>J / EU</b>：Mekanism 的 {@code mekanism.common.config.GeneralConfig} 里
 * {@code forgeConversionRate} 默认 {@code 2.5}（注释原文：Conversion multiplier from Forge Energy
 * to Joules）、{@code ic2ConversionRate} 默认 {@code 10}（EU × 该值 = Joules）。</li>
 * </ul>
 *
 * <p>
 * ⚠️ Mekanism 那两个倍率是<b>玩家可配置</b>的；整合包改过之后，本库这边用
 * {@link EnergyTypes#overrideRatio(IEnergyType, double)} 对齐即可。
 *
 * <h3>这三家怎么接进来</h3>
 * <p>
 * <b>不需要为它们各写一个适配器。</b>这三家的对外能量面最终都落在 Forge Energy 上：
 * AE2 是 {@code appeng.helpers.ForgeEnergyAdapter implements IEnergyStorage}，
 * CoFH 的 {@code IRedstoneFluxStorage} 本身就是 {@code IEnergyStorage} 子接口，
 * Mekanism 是 {@code ForgeEnergyCompat} 暴露 {@code ForgeCapabilities.ENERGY}
 * 并内部按 {@code feConversionRate} 折算成 J。所以本库只要把
 * {@link EnergyContainer#asForgeStorage()} 暴露出去，三家就都能推拉能量，
 * 单位换算由本类负责显示。
 *
 * <h3>取整规则</h3>
 * <p>
 * 整数换算一律 <b>向下取整（floor，朝负无穷）</b>：{@code 1 EU = 4 FE} 精确；
 * {@code 1 FE = 2 J}（2.5 取整后是 2）；{@code 3 J = 1 FE}（1.2 取整后是 1）。
 * 因此「先转过去再转回来」只在网格点上严格可逆：
 * <ul>
 * <li>EU ↔ FE 完全可逆（系数是整数 4）；</li>
 * <li>AE ↔ FE 完全可逆（系数是整数 2）；</li>
 * <li>FE ↔ J 在 {@code 2 FE = 5 J} 的网格上可逆（系数 5/2 精确表示成整数比），
 * 其余值向下取整后会掉一点零头 —— 这正是「除不尽」的必然结果，不是本类的 bug。</li>
 * </ul>
 * 换算两边共用 {@link EnergyTypes#ratioOf(IEnergyType)} 这一个来源，
 * 所以绝不会出现「去程和回程用了不同比例」的漂移。
 *
 * <h3>配置覆盖</h3>
 * <p>
 * 本类不读 {@code OGMRConfig}，而是通过 {@link EnergyTypes#overrideRatio(IEnergyType, double)}
 * 接受外部注入（例如配置加载回调）。覆盖值存在 {@link EnergyTypes} 里，不写回
 * {@link IEnergyType} 对象，所以能量类型的实现可以做成不可变。
 * 下面几个 {@code overrideRatios(...)} 是给内置四种准备的便捷写法。
 */
public final class EnergyConversion {

    /** 默认的 {@code 1 FE = ? J}。 */
    public static final double DEFAULT_JOULES_PER_FE = 2.5;

    /** 默认的 {@code 1 EU = ? FE}。 */
    public static final double DEFAULT_FE_PER_EU = 4.0;

    /** 默认的 {@code 1 AE = ? FE}（与 AE2 的 {@code powerRatioForgeEnergy = 0.5} 一致）。 */
    public static final double DEFAULT_FE_PER_AE = 2.0;

    private static final String[] SUFFIXES = { "", "k", "M", "G", "T", "P", "E" };

    private EnergyConversion() {}

    // ═══════════════ 系数读写（转发到 EnergyTypes） ═══════════════

    /** 当前生效的 {@code 1 FE = ? J}。 */
    public static double joulesPerFe() {
        return EnergyTypes.ratioOf(EnergyTypes.J) > 0.0 ? 1.0 / EnergyTypes.ratioOf(EnergyTypes.J) : 0.0;
    }

    /** 当前生效的 {@code 1 EU = ? FE}。 */
    public static double fePerEu() {
        return EnergyTypes.ratioOf(EnergyTypes.EU);
    }

    /** 当前生效的 {@code 1 AE = ? FE}。 */
    public static double fePerAe() {
        return EnergyTypes.ratioOf(EnergyTypes.AE);
    }

    /** 当前生效的 {@code 1 J = ? FE}。 */
    public static double fePerJoule() {
        return EnergyTypes.ratioOf(EnergyTypes.J);
    }

    /** 当前生效的 {@code 1 EU = ? J}（默认 10.0 = 4 FE × 2.5 J/FE）。 */
    public static double joulesPerEu() {
        return fePerEu() * joulesPerFe();
    }

    /** 当前生效的 {@code 1 AE = ? J}（默认 5.0 = 2 FE × 2.5 J/FE）。 */
    public static double joulesPerAe() {
        return fePerAe() * joulesPerFe();
    }

    /** 当前生效的 {@code 1 EU = ? AE}（默认 2.0 = 4 FE ÷ 2 FE/AE）。 */
    public static double aePerEu() {
        return fePerEu() / fePerAe();
    }

    /** 任意能量种类：{@code 1 个本单位 = ? FE}（含运行期覆盖）。 */
    public static double fePerUnit(IEnergyType type) {
        return EnergyTypes.ratioOf(type);
    }

    /** 任意能量种类：{@code 1 FE = ? 本单位}（含运行期覆盖）。 */
    public static double unitsPerFe(IEnergyType type) {
        double ratio = EnergyTypes.ratioOf(type);
        return ratio > 0.0 ? 1.0 / ratio : 0.0;
    }

    /** 从配置注入换算比例，AE 用默认值（{@value #DEFAULT_FE_PER_AE}）。 */
    public static void overrideRatios(double joulesPerFe, double fePerEu) {
        overrideRatios(joulesPerFe, fePerEu, DEFAULT_FE_PER_AE);
    }

    /**
     * 从配置注入换算比例（本库不自己读配置，由外部在配置加载/变更时调用）。
     *
     * <p>
     * 三个值都必须是有限正数，否则抛 {@link IllegalArgumentException} ——
     * 系数是 0 或负数会让所有换算变成垃圾数据，早失败比晚崩好。
     *
     * @param joulesPerFe {@code 1 FE} 等于多少 J（默认 {@value #DEFAULT_JOULES_PER_FE}）
     * @param fePerEu     {@code 1 EU} 等于多少 FE（默认 {@value #DEFAULT_FE_PER_EU}）
     * @param fePerAe     {@code 1 AE} 等于多少 FE（默认 {@value #DEFAULT_FE_PER_AE}，
     *                    与 AE2 的 {@code powerRatioForgeEnergy = 0.5} 对应）
     */
    public static void overrideRatios(double joulesPerFe, double fePerEu, double fePerAe) {
        requirePositive("joulesPerFe", joulesPerFe);
        requirePositive("fePerEu", fePerEu);
        requirePositive("fePerAe", fePerAe);

        EnergyTypes.overrideRatio(EnergyTypes.FE, 1.0);
        EnergyTypes.overrideRatio(EnergyTypes.EU, fePerEu);
        EnergyTypes.overrideRatio(EnergyTypes.AE, fePerAe);
        // J 的标称系数就是「1 J = ? FE」，而配置给的是「1 FE = ? J」，取倒数
        EnergyTypes.overrideRatio(EnergyTypes.J, 1.0 / joulesPerFe);

        Ogmr.LOGGER.debug("ogmr energy ratios overridden: 1 FE = {} J, 1 EU = {} FE, 1 AE = {} FE"
                + " (1 EU = {} J, 1 AE = {} J)",
                joulesPerFe, fePerEu, fePerAe, joulesPerEu(), joulesPerAe());
    }

    /** 恢复内置四种能量的默认比例（测试 / 配置被删掉时用）。 */
    public static void resetToDefaults() {
        EnergyTypes.resetAllOverrides();
    }

    private static void requirePositive(String name, double value) {
        if (!Double.isFinite(value) || value <= 0.0) {
            throw new IllegalArgumentException("energy ratio " + name + " must be a finite positive number, got " + value);
        }
    }

    // ═══════════════ 任意能量 → FE / FE → 任意能量 ═══════════════

    /** 把 {@code amount}（单位 {@code type}）换算成 FE，向下取整。 */
    public static long toFe(long amount, IEnergyType type) {
        if (amount == 0L || type == null || type.isBaseUnit()) return amount;
        return convertByRatio(amount, EnergyTypes.ratio(type), EnergyTypes.ratioOf(type));
    }

    /** 把 FE 换算成 {@code type}，向下取整。 */
    public static long fromFe(long fe, IEnergyType type) {
        if (fe == 0L || type == null || type.isBaseUnit()) return fe;
        return convertByInverseRatio(fe, EnergyTypes.ratio(type), EnergyTypes.ratioOf(type));
    }

    /** 把 {@code amount}（单位 {@code type}）换算成 FE，保留小数（不做取整）。 */
    public static double toFeDouble(double amount, IEnergyType type) {
        return amount * EnergyTypes.ratioOf(type);
    }

    /** 把 FE 换算成 {@code type}，保留小数（不做取整）。 */
    public static double fromFeDouble(double fe, IEnergyType type) {
        return fe * unitsPerFe(type);
    }

    /**
     * <b>任意两种能量之间互转</b>（先算成 FE 再算出去），向下取整。
     *
     * <pre>{@code
     * EnergyConversion.convert(1000, EnergyTypes.EU, EnergyTypes.AE); // 1000 EU = 4000 FE = 2000 AE
     * }</pre>
     */
    public static long convert(long amount, IEnergyType from, IEnergyType to) {
        if (amount == 0L) return 0L;
        return fromFe(toFe(amount, from), to);
    }

    // ═══════════════ 内置四种的快捷方法 ═══════════════

    /** {@code 1 EU = 4 FE}。 */
    public static long euToFe(long eu) {
        return toFe(eu, EnergyTypes.EU);
    }

    /** {@code 1 EU = 4 FE}，向下取整。 */
    public static long feToEu(long fe) {
        return fromFe(fe, EnergyTypes.EU);
    }

    /** {@code 1 FE = 2.5 J}。 */
    public static long feToJoules(long fe) {
        return fromFe(fe, EnergyTypes.J);
    }

    /** {@code 1 J = 0.4 FE}，向下取整。 */
    public static long joulesToFe(long j) {
        return toFe(j, EnergyTypes.J);
    }

    /** {@code 1 AE = 2 FE}（AE2 的默认口径）。 */
    public static long aeToFe(long ae) {
        return toFe(ae, EnergyTypes.AE);
    }

    /** {@code 2 FE = 1 AE}，向下取整。 */
    public static long feToAe(long fe) {
        return fromFe(fe, EnergyTypes.AE);
    }

    // ═══════════════ 换算的执行 ═══════════════

    /**
     * {@code floor(value × ratio)}：能精确表示成整数比就走整数运算，否则退回 double。
     */
    private static long convertByRatio(long value, EnergyRatio ratio, double fallbackFactor) {
        if (ratio.isExact()) return mulDivFloor(value, ratio.num(), ratio.den());
        return floorToLong((double) value * fallbackFactor);
    }

    /**
     * {@code floor(value ÷ ratio)}。
     */
    private static long convertByInverseRatio(long value, EnergyRatio ratio, double fallbackFactor) {
        if (ratio.isExact()) return mulDivFloor(value, ratio.den(), ratio.num());
        return floorToLong(fallbackFactor > 0.0 ? (double) value / fallbackFactor : 0.0);
    }

    /**
     * {@code floor(value * num / den)}，乘法溢出时退回 double 近似并夹紧到 {@code long} 边界。
     *
     * <p>
     * 用 {@link Math#floorDiv} 而不是 {@code /}，是为了让负数也保持「向下取整」
     * （例如 {@code -1 FE → -1 J}，而不是向零取整的 0）。
     */
    private static long mulDivFloor(long value, long num, long den) {
        try {
            return Math.floorDiv(Math.multiplyExact(value, num), den);
        } catch (ArithmeticException overflow) {
            // 只有在 |value| 大到 ×num 就爆 long 时才走这里（现实中不可能的能量值）
            return floorToLong((double) value * (double) num / (double) den);
        }
    }

    /** double → long 的向下取整；NaN 归 0，超出 long 范围时夹紧（Java 的窄化转换本身就会饱和）。 */
    private static long floorToLong(double value) {
        if (Double.isNaN(value)) return 0L;
        return (long) Math.floor(value);
    }

    // ═══════════════ 格式化 ═══════════════

    /**
     * 把一个 FE 值摊成「基准 + 若干单位」并排的可读文本。
     *
     * <p>
     * 例：{@code format(1250L)} → {@code "1.25k FE (3.13k J / 312 EU / 625 AE)"}。
     *
     * <p>
     * 括号里显示哪些单位由 {@link EnergyTypes#displayTypes()} 决定 ——
     * 第三方注册了自己的能量之后把它加进显示列表，面板上就会多一项，
     * <b>不需要改这个方法</b>。
     */
    public static String format(long fe) {
        StringBuilder sb = new StringBuilder(formatAmount(fe, EnergyTypes.FE));
        // 直接遍历显示列表，不先 toList()：这个方法会被机器面板每帧调到，少一次列表分配
        boolean any = false;
        for (IEnergyType type : EnergyTypes.displayTypes()) {
            if (type == null || type.isBaseUnit()) continue;
            sb.append(any ? " / " : " (");
            any = true;
            sb.append(formatAmount(fromFe(fe, type), type));
        }
        if (any) sb.append(')');
        return sb.toString();
    }

    /** 只格式化一个数值 + 单位缩写（不想带上其他单位时用）。 */
    public static String formatAmount(long value, IEnergyType type) {
        int index = 0;
        double scaled = value;
        double abs = Math.abs(scaled);
        while (abs >= 1000.0 && index < SUFFIXES.length - 1) {
            scaled /= 1000.0;
            abs /= 1000.0;
            index++;
        }
        String number = index == 0
                ? String.format(Locale.ROOT, "%,d", value)
                : String.format(Locale.ROOT, "%.2f%s", scaled, SUFFIXES[index]);
        return number + " " + (type != null ? type.getSymbol() : EnergyTypes.FE.getSymbol());
    }

    /**
     * 把 0.0 ~ 1.0 的占比格式化成百分比文本，例如 {@code "62.5%"}。
     *
     * <p>
     * 非有限值按 0% 处理，超出 0~1 的按边界夹紧 —— 显示用的函数不该因为脏数据抛异常。
     */
    public static String formatPercent(double ratio) {
        double clamped = Double.isFinite(ratio) ? Math.max(0.0, Math.min(ratio, 1.0)) : 0.0;
        return String.format(Locale.ROOT, "%.1f%%", clamped * 100.0);
    }
}
