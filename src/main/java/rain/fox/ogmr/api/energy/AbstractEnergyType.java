package rain.fox.ogmr.api.energy;

import lombok.Getter;

import org.jetbrains.annotations.Nullable;

import java.util.Locale;
import java.util.Objects;

/**
 * {@link IEnergyType} 的抽象基类 —— 把「id / 缩写 / 双语名 / 系数」这些样板收进来，
 * 让第三方加一种能量只需要写一行。
 *
 * <p>
 * 本类刻意做成<b>不自动注册</b>：构造完不会偷偷进注册表，必须显式
 * {@code EnergyTypes.register(...)}。理由是「声明一个常量」和「让它全局可见」是两件事，
 * 自动注册会让类加载顺序变成一个隐形依赖。
 *
 * <p>
 * 用法（匿名子类是最省事的写法，子类可以覆写任何方法）：
 * <pre>{@code
 * public static final IEnergyType MANA = EnergyTypes.register(
 *         new AbstractEnergyType("mana", "Mana", "Mana", "魔力", 0.5) {
 *             // 需要的话可以覆写，例如系数随游戏阶段变化：
 *             // @Override public double getFePerUnit() { return ...; }
 *         },
 *         "botania_mana", "魔力");
 * }</pre>
 */
public abstract class AbstractEnergyType implements IEnergyType {

    @Getter
    private final String id;
    @Getter
    private final String symbol;
    /** 英文显示名；对外方法叫 {@code getDisplayName()}，与字段名对不上，故那个方法保持手写。 */
    private final String englishName;
    @Getter
    private final String chineseName;
    @Getter
    private final double fePerUnit;
    @Getter
    private final boolean builtin;
    @Getter
    private final boolean baseUnit;

    /**
     * @param id           唯一标识（会被规整成小写）
     * @param symbol       展示缩写
     * @param englishName  英文显示名
     * @param chineseName  中文显示名；传 {@code null} 时退回英文名
     * @param fePerUnit    {@code 1 个本单位 = ? FE}，必须有限且为正
     */
    protected AbstractEnergyType(String id, String symbol, String englishName,
                                 @Nullable String chineseName, double fePerUnit) {
        this(id, symbol, englishName, chineseName, fePerUnit, false, false);
    }

    /**
     * 内置类型用的构造器（多 {@code builtin} / {@code baseUnit} 两个标记，{@link EnergyTypes} 内部用）。
     *
     * <p>
     * ⚠️ {@code baseUnit} 与「系数是不是 1.0」是两件事：RF 的系数也是 1.0，但它只是 FE 的
     * 另一个名字，不是内部基准。见 {@link IEnergyType#isBaseUnit()}。
     */
    protected AbstractEnergyType(String id, String symbol, String englishName,
                                 @Nullable String chineseName, double fePerUnit,
                                 boolean builtin, boolean baseUnit) {
        this.id = Objects.requireNonNull(id, "energy type id").trim().toLowerCase(Locale.ROOT);
        if (this.id.isEmpty()) {
            throw new IllegalArgumentException("energy type id must not be blank");
        }
        this.symbol = Objects.requireNonNull(symbol, "energy type symbol");
        this.englishName = Objects.requireNonNull(englishName, "energy type english name");
        this.chineseName = chineseName != null ? chineseName : englishName;
        if (!Double.isFinite(fePerUnit) || fePerUnit <= 0.0) {
            throw new IllegalArgumentException(
                    "energy type '" + this.id + "' fePerUnit must be a finite positive number, got " + fePerUnit);
        }
        this.fePerUnit = fePerUnit;
        this.builtin = builtin;
        this.baseUnit = baseUnit;
    }

    /** 英文显示名（字段叫 {@code englishName}，所以 Lombok 生不出 {@code getDisplayName()}，只能手写）。 */
    @Override
    public String getDisplayName() {
        return englishName;
    }

    /** 日志/调试输出用缩写，避免打出对象哈希。 */
    @Override
    public String toString() {
        return symbol;
    }
}
