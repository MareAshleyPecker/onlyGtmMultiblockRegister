package rain.fox.ogmr.api.energy;

import rain.fox.ogmr.api.lang.OGMRLang;

import org.jetbrains.annotations.Nullable;

import java.util.Locale;

/**
 * 能量单位的<b>兼容层</b>（保留旧写法用）。
 *
 * <p>
 * ⚠️ <b>新代码请直接用 {@link IEnergyType} / {@link EnergyTypes}。</b>
 * 能量种类在本库已经改成接口（{@link IEnergyType}），内置能量是
 * {@link EnergyTypes#FE} / {@link EnergyTypes#EU} / {@link EnergyTypes#AE} / {@link EnergyTypes#RF}
 * / {@link EnergyTypes#J} 五个实现；本枚举只是为了让「构造参数写 {@code EnergyUnit.FE}」的老代码还能编译，
 * 它自己不持有任何数据，每个方法都转发到对应的 {@link IEnergyType}。
 *
 * <p>
 * 保留它的好处是升级不会打断已有调用点；坏处是同一个概念有两个名字，所以：
 * <ul>
 * <li>{@link #toType()} —— 转成现代写法；</li>
 * <li>{@link #of(IEnergyType)} —— 从任意 {@link IEnergyType}（包括第三方自定义的）反查；
 * 自定义类型不在枚举里，此时返回 {@link #FE}，<b>请优先使用 {@link IEnergyType} 本身</b>。</li>
 * </ul>
 *
 * @deprecated 用 {@link IEnergyType} / {@link EnergyTypes} 代替。
 */
@Deprecated(since = "1.1", forRemoval = false)
public enum EnergyUnit implements IEnergyType {

    /** GregTech / IC2 的能量单位。1 EU = 4 FE = 10 J。 */
    EU(EnergyTypes.EU),

    /** 基准单位：Forge Energy。 */
    FE(EnergyTypes.FE),

    /** Applied Energistics 2 的能量单位。1 AE = 2 FE = 5 J。 */
    AE(EnergyTypes.AE),

    /**
     * Redstone Flux（CoFH / 热力膨胀）。1 RF = 1 FE。
     *
     * <p>
     * 加进这个兼容枚举是为了让 {@link #of(IEnergyType)} 不会把内置的 RF 认错成 FE
     * （两者数值相同，但语义不同，见 {@link IEnergyType#isBaseUnit()}）。
     */
    RF(EnergyTypes.RF),

    /** 焦耳（Mekanism 的单位）。1 J = 0.4 FE。 */
    J(EnergyTypes.J);

    /** 认不出名字时的兜底单位（内部基准，最不容易出错）。 */
    public static final EnergyUnit DEFAULT = FE;

    private final IEnergyType delegate;

    EnergyUnit(IEnergyType delegate) {
        this.delegate = delegate;
    }

    /** 本枚举常量背后的现代表示。 */
    public IEnergyType toType() {
        return delegate;
    }

    /**
     * 从任意 {@link IEnergyType} 反查枚举常量。
     *
     * <p>
     * ⚠️ 第三方自定义的能量种类不在这个枚举里，会回落到 {@link #FE} ——
     * 这正是「能量种类不该是枚举」的原因，所以能用 {@link IEnergyType} 就别用本方法。
     */
    public static EnergyUnit of(@Nullable IEnergyType type) {
        if (type != null) {
            for (EnergyUnit unit : values()) {
                if (unit.delegate == type || unit.delegate.getId().equals(type.getId())) {
                    return unit;
                }
            }
        }
        return DEFAULT;
    }

    /**
     * 按名字解析单位，<b>大小写不敏感</b>。
     *
     * <p>
     * 转发到 {@link EnergyTypes#byName(String)}（认得 id / 缩写 / 别名，认不出回 {@link #FE}）。
     * 注意：这个方法只可能返回枚举里的四种；想解析自定义种类请用
     * {@link EnergyTypes#byName(String)} 或 {@link EnergyTypes#get(String)}。
     */
    public static EnergyUnit fromName(@Nullable String name) {
        return of(EnergyTypes.byName(name));
    }

    // ═══════════════ IEnergyType 转发 ═══════════════

    @Override
    public String getId() {
        return delegate.getId();
    }

    @Override
    public String getSymbol() {
        return delegate.getSymbol();
    }

    @Override
    public String getDisplayName() {
        return delegate.getDisplayName();
    }

    @Override
    public String getChineseName() {
        return delegate.getChineseName();
    }

    @Override
    public double getFePerUnit() {
        // 走 EnergyTypes：这样运行期用 overrideRatio 改过比例之后，本枚举读到的也是新值
        return EnergyTypes.ratioOf(delegate);
    }

    @Override
    public boolean isBuiltin() {
        return true;
    }

    /** 把四个单位的双语名登记进 {@link OGMRLang}（幂等，可在静态块里反复调）。 */
    public static void initLang() {
        for (EnergyUnit unit : values()) {
            unit.delegate.registerLang();
        }
        // 第三方注册进来的自定义能量种类也一起登记（本库自己那份语言键不该漏掉它们）
        for (IEnergyType type : EnergyTypes.all()) {
            if (!type.isBuiltin()) type.registerLang();
        }
    }

    /** 日志 / 调试输出用缩写，避免打出 {@code EnergyUnit.FE} 这种噪声。 */
    @Override
    public String toString() {
        return getSymbol().toUpperCase(Locale.ROOT);
    }
}
