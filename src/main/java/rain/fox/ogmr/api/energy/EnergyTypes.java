package rain.fox.ogmr.api.energy;

import rain.fox.ogmr.Ogmr;

import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 能量种类的注册表 —— 本库能量系统「可扩展」的那一半（需求 7 的重构）。
 *
 * <p>
 * 之前能量种类是写死的枚举；现在 {@link IEnergyType} 是接口，本类负责：
 * <ul>
 * <li><b>注册</b>：{@link #register(IEnergyType, String...)} 把第三方能量种类收进来，
 * 顺带支持别名（配置里写 {@code "redstone_flux"} 也能认出 RF）；</li>
 * <li><b>查表</b>：{@link #get(String)}（按 id，大小写不敏感）、
 * {@link #byName(String)}（按 id / 缩写 / 别名，认不出回落到 {@link #DEFAULT} 而不是 null）；</li>
 * <li><b>换算系数</b>：{@link #ratioOf(IEnergyType)} —— 标称系数 {@link IEnergyType#getFePerUnit()}
 * 与运行期覆盖的合成结果，并给出精确整数比缓存；</li>
 * <li><b>显示列表</b>：{@link #displayTypes()} —— {@link EnergyConversion#format(long)} 就是按它
 * 把同一个能量值摊成几种单位并排显示，想要面板多显示一种能量，把它加进来即可。</li>
 * </ul>
 *
 * <h3>内置的五种</h3>
 * <p>
 * {@link #FE}（内部基准）、{@link #EU}、{@link #AE}、{@link #RF}、{@link #J}。
 * 它们和第三方注册进来的类型<b>在功能上完全平权</b> —— 内置标记
 * {@link IEnergyType#isBuiltin()} 只用于显示，不参与任何逻辑分支。
 *
 * <p>
 * 五个系数都是<b>开箱核实过</b>的（对着各 mod 自己的字节码/常量，不是查wiki）：
 * <table border="1">
 * <caption>系数来源</caption>
 * <tr><th>种类</th><th>关系</th><th>来源</th></tr>
 * <tr><td>FE</td><td>1 FE</td><td>本库内部基准（Forge Energy）</td></tr>
 * <tr><td>EU</td><td>1 EU = 4 FE = 10 J</td><td>GT/IC2 惯例；Mekanism {@code ic2ConversionRate} 默认 10</td></tr>
 * <tr><td>AE</td><td>1 AE = 2 FE</td><td>AE2 {@code PowerUnits.powerRatioForgeEnergy = 0.5}</td></tr>
 * <tr><td>RF</td><td>1 RF = 1 FE</td><td>CoFH {@code IRedstoneFluxStorage extends IEnergyStorage}（无换算）</td></tr>
 * <tr><td>J</td><td>1 FE = 2.5 J</td><td>Mekanism {@code feConversionRate} 默认 2.5</td></tr>
 * </table>
 *
 * <p>
 * 注意 Mekanism 的两个倍率（{@code feConversionRate} / {@code ic2ConversionRate}）是
 * <b>玩家可配置</b>的；整合包改过它们之后，用
 * {@link #overrideRatio(IEnergyType, double)} 把这里也改掉即可对齐。
 */
public final class EnergyTypes {

    // ⚠️ 下面这些容器必须在四个内置常量之前声明：静态字段按声明顺序初始化，
    //    而内置常量会在自己的初始化里调用 register(...)。
    private static final Map<String, IEnergyType> BY_ID = new LinkedHashMap<>();
    private static final Map<String, IEnergyType> BY_ALIAS = new ConcurrentHashMap<>();
    private static final Map<String, Double> RATIO_OVERRIDES = new ConcurrentHashMap<>();
    private static final Map<String, EnergyRatio> RATIO_CACHE = new ConcurrentHashMap<>();
    private static final List<IEnergyType> DISPLAY = new CopyOnWriteArrayList<>();

    // ═══════════════ 内置能量种类 ═══════════════

    /**
     * 内部基准单位：Forge Energy。{@code 1 FE = 1 FE}。
     *
     * <p>
     * 只有它是 {@code baseUnit = true}。别的能量即使系数也是 1.0（例如下面的 {@link #RF}）
     * 也只是「和 FE 等值」，不是基准 —— 理由见 {@link IEnergyType#isBaseUnit()}。
     */
    public static final IEnergyType FE = register(new AbstractEnergyType("fe", "FE",
            "FE (Forge Energy)", "FE（Forge Energy）", 1.0, true, true) {},
            "forge energy", "forge_energy", "forgeenergy", "fe/t");

    /** GregTech / IC2：{@code 1 EU = 4 FE = 10 J}（Mekanism 的 {@code ic2ConversionRate} 也是 10）。 */
    public static final IEnergyType EU = register(new AbstractEnergyType("eu", "EU",
            "EU (GregTech)", "EU（格雷科技）", 4.0, true, false) {},
            "gregtech", "ic2", "gt", "eu/t");

    /**
     * Applied Energistics 2：{@code 1 AE = 2 FE = 5 J}。
     *
     * <p>
     * 系数与 AE2 自己的 {@code appeng.api.config.PowerUnits} 对齐：那里
     * {@code convertTo(target, amount)} 算的是 {@code amount × this.ratio ÷ target.ratio}，
     * 而 {@code AE.ratio = 1.0}、{@code FE.ratio = 默认 powerRatioForgeEnergy = 0.5}，
     * 于是 {@code AE → FE} 恰好是 {@code × 2}。
     */
    public static final IEnergyType AE = register(new AbstractEnergyType("ae", "AE",
            "AE (Applied Energistics)", "AE（应用能源 2）", 2.0, true, false) {},
            "ae2", "applied energistics", "applied_energistics", "appliedenergistics",
            "applied energistics 2", "应用能源", "应用能源2", "ae/t");

    /**
     * Redstone Flux（CoFH / 热力膨胀）：<b>{@code 1 RF = 1 FE}</b>。
     *
     * <p>
     * 系数不是猜的：CoFH 的 {@code cofh.lib.common.energy.IRedstoneFluxStorage}
     * 定义就是 {@code extends net.minecraftforge.energy.IEnergyStorage}，<b>没有任何额外方法</b> ——
     * 也就是说现代版本的 RF 只是 FE 的另一个名字，不存在换算因子。
     * （Mekanism 的 {@code blacklistForge} 注释里也把 {@code RF} 和 {@code FE/IF/uF/CF} 并列当成同一路能量。）
     *
     * <p>
     * ⚠️ 它<b>不是</b>基准单位：{@code isBaseUnit()} 为 false。
     * 如果按「系数 == 1.0 就是基准」来判断，RF 会在多单位并排显示里被悄悄丢掉。
     */
    public static final IEnergyType RF = register(new AbstractEnergyType("rf", "RF",
            "RF (Redstone Flux)", "RF（红石能量）", 1.0, true, false) {},
            "redstone flux", "redstone_flux", "redstoneflux", "thermal", "cofh",
            "红石能量", "热力膨胀", "rf/t");

    /** 焦耳（Mekanism 换算）：{@code 1 FE = 2.5 J}（Mekanism 的 {@code feConversionRate} 默认就是 2.5）。 */
    public static final IEnergyType J = register(new AbstractEnergyType("j", "J",
            "J (Joule)", "J（焦耳）", 0.4, true, false) {},
            "joule", "joules", "焦耳", "j/t");

    /** 认不出名字时的兜底（内部基准，最不容易出错）。 */
    public static final IEnergyType DEFAULT = FE;

    static {
        // 默认显示顺序：基准 → 常用三种，与之前的 format() 输出一致
        DISPLAY.add(FE);
        DISPLAY.add(J);
        DISPLAY.add(EU);
        DISPLAY.add(AE);
    }

    private EnergyTypes() {}

    // ═══════════════ 注册与查表 ═══════════════

    /**
     * 注册一种能量，<b>同时把它的 id 与缩写注册成可查的名字</b>（大小写不敏感）。
     *
     * <p>
     * 重名（id 已被占用）会抛 {@link IllegalStateException} —— 静默覆盖会让「我注册的明明是
     * 自己的能量，结果拿到别人的」这种问题拖到很久以后才暴露。想显式替换请用
     * {@link #replace(IEnergyType)}。
     *
     * @param type    能量种类
     * @param aliases 额外别名（可空）
     * @return 传进来的 {@code type}，方便写成 {@code public static final IEnergyType X = register(new ...)}
     */
    public static synchronized <T extends IEnergyType> T register(T type, String... aliases) {
        requireValid(type);
        String id = normalize(type.getId());
        IEnergyType existing = BY_ID.get(id);
        if (existing != null && existing != type) {
            throw new IllegalStateException("Energy type id '" + id + "' is already registered by "
                    + existing.getSymbol() + "; use replace() if you really mean to override it");
        }
        BY_ID.put(id, type);
        BY_ALIAS.put(id, type);
        BY_ALIAS.put(normalize(type.getSymbol()), type);
        for (String alias : aliases) {
            if (alias != null && !alias.isBlank()) {
                BY_ALIAS.put(normalize(alias), type);
            }
        }
        invalidateRatio(type);
        return type;
    }

    /**
     * 显式替换一个已注册的 id（整合包魔改、测试用）。
     */
    public static synchronized <T extends IEnergyType> T replace(T type, String... aliases) {
        requireValid(type);
        BY_ID.put(normalize(type.getId()), type);
        return registerAliases(type, aliases);
    }

    private static <T extends IEnergyType> T registerAliases(T type, String... aliases) {
        BY_ALIAS.put(normalize(type.getId()), type);
        BY_ALIAS.put(normalize(type.getSymbol()), type);
        for (String alias : aliases) {
            if (alias != null && !alias.isBlank()) BY_ALIAS.put(normalize(alias), type);
        }
        invalidateRatio(type);
        return type;
    }

    private static void requireValid(IEnergyType type) {
        if (type == null) throw new IllegalArgumentException("energy type must not be null");
        if (type.getId() == null || type.getId().isBlank()) {
            throw new IllegalArgumentException("energy type id must not be blank");
        }
        double ratio = type.getFePerUnit();
        if (!Double.isFinite(ratio) || ratio <= 0.0) {
            throw new IllegalArgumentException("energy type '" + type.getId()
                    + "' must declare a finite positive fePerUnit, got " + ratio);
        }
    }

    private static String normalize(String name) {
        return name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
    }

    /** 按 id 精确查（大小写不敏感）；查不到返回 {@code null}。 */
    @Nullable
    public static IEnergyType get(String id) {
        return BY_ID.get(normalize(id));
    }

    /**
     * 按 id / 缩写 / 别名解析能量种类，<b>认不出时返回 {@link #DEFAULT}（FE）而不是 null</b>
     * —— 配置里写错一个名字不该让机器加载崩掉，退回内部基准是最安全的降级。
     */
    public static IEnergyType byName(@Nullable String name) {
        if (name == null) return DEFAULT;
        String key = normalize(name);
        if (key.isEmpty()) return DEFAULT;
        IEnergyType byAlias = BY_ALIAS.get(key);
        if (byAlias != null) return byAlias;
        IEnergyType byId = BY_ID.get(key);
        return byId != null ? byId : DEFAULT;
    }

    /** 是否已经注册过这个 id。 */
    public static boolean contains(String id) {
        return BY_ID.containsKey(normalize(id));
    }

    /** 全部已注册的能量种类（注册顺序；内置的四个在最前面）。 */
    public static synchronized Collection<IEnergyType> all() {
        return List.copyOf(BY_ID.values());
    }

    /** 已经注册的数量。 */
    public static synchronized int size() {
        return BY_ID.size();
    }

    // ═══════════════ 显示列表 ═══════════════

    /**
     * {@link EnergyConversion#format(long)} 会按这个列表把同一个能量值摊成几种单位并排显示。
     *
     * <p>
     * 默认是 {@code FE / J / EU / AE}。第三方想让自己那种能量也出现在机器面板上，
     * 只需把它加进来（或整体替换）。
     */
    public static List<IEnergyType> displayTypes() {
        return List.copyOf(DISPLAY);
    }

    /** 追加一个显示单位（幂等）。 */
    public static void addDisplayType(IEnergyType type) {
        if (type != null && !DISPLAY.contains(type)) DISPLAY.add(type);
    }

    /** 整体替换显示列表（顺序即显示顺序）。 */
    public static void setDisplayTypes(IEnergyType... types) {
        DISPLAY.clear();
        for (IEnergyType type : types) {
            if (type != null && !DISPLAY.contains(type)) DISPLAY.add(type);
        }
        if (DISPLAY.isEmpty()) DISPLAY.add(FE);
    }

    // ═══════════════ 换算系数 ═══════════════

    /** 标称系数（{@link IEnergyType#getFePerUnit()}），不受运行期覆盖影响。 */
    public static double nominalRatio(IEnergyType type) {
        return type != null ? type.getFePerUnit() : 1.0;
    }

    /**
     * <b>当前生效</b>的系数：有运行期覆盖就用覆盖值，否则用标称值。
     *
     * <p>
     * 换算与显示一律走这个方法，保证「改了比例之后到处都是同一个数」。
     */
    public static double ratioOf(IEnergyType type) {
        if (type == null) return 1.0;
        Double override = RATIO_OVERRIDES.get(type.getId());
        if (override != null) return override;
        return type.getFePerUnit();
    }

    /**
     * 覆盖某种能量的换算系数（整合包改比例用）。
     *
     * <p>
     * 不会写回 {@link IEnergyType} 对象本身 —— 实现可以安全地做成不可变/共享的。
     */
    public static void overrideRatio(IEnergyType type, double fePerUnit) {
        if (type == null) return;
        if (!Double.isFinite(fePerUnit) || fePerUnit <= 0.0) {
            throw new IllegalArgumentException("energy ratio for '" + type.getId()
                    + "' must be a finite positive number, got " + fePerUnit);
        }
        RATIO_OVERRIDES.put(type.getId(), fePerUnit);
        invalidateRatio(type);
        Ogmr.LOGGER.debug("ogmr: energy ratio of {} overridden to 1 {} = {} FE",
                type.getSymbol(), type.getSymbol(), fePerUnit);
    }

    /** 取消某种能量的覆盖，回到它的标称系数。 */
    public static void resetOverride(IEnergyType type) {
        if (type == null) return;
        if (RATIO_OVERRIDES.remove(type.getId()) != null) invalidateRatio(type);
    }

    /** 取消全部覆盖（测试 / 配置被删掉时用）。 */
    public static void resetAllOverrides() {
        RATIO_OVERRIDES.clear();
        RATIO_CACHE.clear();
    }

    /** 是否有运行期覆盖。 */
    public static boolean hasOverride(IEnergyType type) {
        return type != null && RATIO_OVERRIDES.containsKey(type.getId());
    }

    /** 取（并缓存）某种能量的精确整数比；改过系数后会自动重建。 */
    static EnergyRatio ratio(IEnergyType type) {
        if (type == null) return EnergyRatio.of(1.0);
        return RATIO_CACHE.computeIfAbsent(type.getId(), id -> EnergyRatio.of(ratioOf(type)));
    }

    private static void invalidateRatio(IEnergyType type) {
        if (type != null) RATIO_CACHE.remove(type.getId());
    }

    // ═══════════════ 语言键 ═══════════════

    /** 把所有已注册能量种类的双语名登记进语言表；数据生成之前调用即可（幂等）。 */
    public static void initLang() {
        for (IEnergyType type : all()) {
            type.registerLang();
        }
    }

    /** 调试用：把当前注册表打出来。 */
    public static List<String> describeAll() {
        List<String> lines = new ArrayList<>();
        for (IEnergyType type : all()) {
            lines.add("%s (%s) 1 %s = %s FE%s".formatted(
                    type.getId(), type.getSymbol(), type.getSymbol(),
                    ratioOf(type), type.isBuiltin() ? "" : " [custom]"));
        }
        return lines;
    }
}
