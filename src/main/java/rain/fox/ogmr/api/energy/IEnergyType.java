package rain.fox.ogmr.api.energy;

import rain.fox.ogmr.api.lang.OGMRLang;

/**
 * 能量<b>种类</b>接口 —— 本库能量系统的扩展点。
 *
 * <p>
 * 之前能量种类被写死成一个枚举（{@code EU / FE / AE / J}），第三方想加自己的能量
 * （RF、Mana、Starlight、某个整合包自研的「魔能」……）就必须改本库源码。现在改成接口：
 * <b>任何 mod 都能自己实现一份，注册进 {@link EnergyTypes} 之后就能像内置单位一样用</b>
 * —— 可以塞进 {@link EnergyHatchPartMachine} 的构造参数、写进 {@link EnergyHatchSize}、
 * 参与 {@link EnergyConversion} 的换算与面板显示。
 *
 * <h3>怎么加一种自己的能量</h3>
 * <pre>{@code
 * // ① 继承 AbstractEnergyType（省掉一堆样板），或者直接 implements IEnergyType
 * public static final IEnergyType RF = EnergyTypes.register(
 *         new AbstractEnergyType("rf", "RF", "RF (Redstone Flux)", "RF（红石能量）", 1.0) {},
 *         "redstone_flux", "红石能量");
 *
 * // ② 然后就能当单位用了
 * new EnergyHatchPartMachine(holder, tier, size, RF, true);
 * }</pre>
 *
 * <h3>系数约定</h3>
 * <p>
 * 本库内部<b>一律以 FE 为基准</b>存 {@code long}。所以每种能量只需要回答一个问题：
 * <b>{@code 1 个本单位 = ? FE}</b>（{@link #getFePerUnit()}）。内置四种的实测值：
 * FE = 1.0、EU = 4.0、AE = 2.0、RF = 1.0、J = 0.4。
 * 反过来的倒数由 {@link #getUnitsPerFe()} 免费给出。
 *
 * <p>
 * 这个系数是「标称值」：运行期可以用 {@code EnergyTypes.overrideRatio(type, x)} 覆盖
 * （整合包改换算比），覆盖不会写回你的对象，所以实现可以做成不可变的。
 *
 * <p>
 * ⚠️ <b>实现应当是无状态且幂等的</b>：{@link #getFePerUnit()} 会被高频调用（每 tick 换算、
 * 每帧渲染面板），不要在里面对世界/配置做 IO。
 */
public interface IEnergyType {

    /**
     * 唯一标识，全小写、建议只用 {@code [a-z0-9_]}，例如 {@code "fe"}、{@code "eu"}、{@code "my_mana"}。
     *
     * <p>
     * 它是<b>注册表的键</b>，也是配置/JSON 里写的那种能量名；重复注册会被拒绝（见
     * {@link EnergyTypes#register}）。
     */
    String getId();

    /** 展示用缩写，例如 {@code "FE"}。会拼在数值后面（{@code "1.25k FE"}）。 */
    String getSymbol();

    /** 英文显示名，例如 {@code "FE (Forge Energy)"}。 */
    String getDisplayName();

    /** 中文显示名，例如 {@code "FE（Forge Energy）"}。没中文可返回 {@link #getDisplayName()}。 */
    String getChineseName();

    /**
     * <b>1 个本单位等于多少 FE</b>（内部基准）。
     *
     * <p>
     * 必须是有限正数；返回 0 或负数会让所有换算变成垃圾数据，{@link EnergyTypes#register}
     * 与 {@link EnergyConversion} 都会检查。
     */
    double getFePerUnit();

    /** {@code 1 FE = ? 本单位}，默认取倒数。 */
    default double getUnitsPerFe() {
        double ratio = getFePerUnit();
        return ratio > 0.0 ? 1.0 / ratio : 0.0;
    }

    /** 语言键，默认 {@code "ogmr.energy.type.<id>"}；登记见 {@link #registerLang()}。 */
    default String getLangKey() {
        return "ogmr.energy.type." + getId();
    }

    /**
     * 是不是<b>内部基准单位</b>（也就是本库用来存 {@code long} 的那一个，FE）。
     *
     * <p>
     * ⚠️ <b>不要用「系数 == 1.0」来判断</b>：有些能量与 FE 是 1:1 的
     * （典型例子是 Redstone Flux —— CoFH 的 {@code IRedstoneFluxStorage} 干脆就是
     * {@code net.minecraftforge.energy.IEnergyStorage} 的子接口，RF 只是 FE 的另一个名字），
     * 它们系数也是 1.0，但<b>不是</b>基准单位。把它们当成基准会让
     * {@link EnergyConversion#format(long)} 在「多单位并排」里把它们悄悄丢掉，
     * 机器面板也会少显示一段信息。
     *
     * <p>
     * 所以本方法的默认实现<b>返回 false</b>，只有 {@link EnergyTypes#FE} 显式声明为 true。
     * 自己实现 {@link IEnergyType} 时保持默认即可。
     */
    default boolean isBaseUnit() {
        return false;
    }

    /**
     * 是不是本库内置的能量种类。
     *
     * <p>
     * 只用于显示与日志（面板上标注「内置/第三方」、调试时区分来源），
     * <b>不参与任何逻辑分支</b> —— 自定义类型和内置类型在功能上完全平权，这正是这次重构的目的。
     */
    default boolean isBuiltin() {
        return false;
    }

    /** 把本种类的双语名登记进 {@link OGMRLang}（幂等，数据生成之前调用即可）。 */
    default void registerLang() {
        OGMRLang.add(getLangKey(), getDisplayName(), getChineseName());
    }
}
