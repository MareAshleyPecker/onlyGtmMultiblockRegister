package rain.fox.ogmr.api.energy;

import rain.fox.ogmr.api.lang.OGMRLang;

import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * 本文件是为 ogmr 新写的能量系统（需求 7）。
 *
 * <p>
 * <b>可注册的仓室尺寸表。</b>库本身不规定「有哪几级能源仓」：
 * 谁做内容谁注册（addon 在 {@code FMLCommonSetupEvent} 之类的地方调 {@link #register}），
 * 注册顺序就是索引顺序，{@link #of(int)} 用整数下标取。
 *
 * <p>
 * 默认<b>什么都不注册</b>（空表）—— 因为「有哪些等级」是内容决定的事，
 * 不该由 API 库替使用者拍板。想直接用 Modular Machinery 那套 8 级数值的话，
 * 显式调一次 {@link #registerDefaultPreset()} 即可（幂等，重复调不会重复注册）：
 *
 * <pre>
 * 名字           输入(FE/t)     容量(FE)      默认单位
 * tiny             128          2,048        FE
 * small            512          4,096        FE
 * normal           512          8,192        FE
 * reinforced     2,048         16,384        FE
 * big            8,192         32,768        FE
 * huge          32,768        131,072        FE
 * ludicrous    131,072        524,288        FE
 * ultimate     524,288      2,097,152        FE
 * </pre>
 *
 * <p>
 * 线程安全：全部方法 {@code synchronized}。注册只发生在启动期，读取发生在运行期/渲染线程，
 * 用锁比用并发集合简单且不会读到「注册了一半」的表。
 */
public final class EnergyHatchSizes {

    private EnergyHatchSizes() {}

    /** name → 尺寸（保持注册顺序，方便 {@link #all()} 稳定输出）。 */
    private static final Map<String, EnergyHatchSize> BY_NAME = new LinkedHashMap<>();

    /** 注册顺序表（{@link #of(int)} 的下标）。 */
    private static final List<EnergyHatchSize> ORDER = new ArrayList<>();

    // ═══════════════ 注册 / 查询 ═══════════════

    /**
     * 注册一个仓室尺寸。
     *
     * <p>
     * <b>按 name 去重</b>：同一个名字再次注册时不覆盖、返回<b>已有</b>的那个实例。
     * 这样 addon 之间、或 addon 与内置预设之间抢同一个名字时，结果是确定的（先注册的赢），
     * 也不会出现「同一个名字指向两个不同数值」的幽灵 bug。
     *
     * @return 表里最终生效的那个实例（可能是传入的 {@code size}，也可能是已存在的同名实例）
     */
    public static synchronized EnergyHatchSize register(EnergyHatchSize size) {
        Objects.requireNonNull(size, "size");
        EnergyHatchSize existing = BY_NAME.get(size.name());
        if (existing != null) return existing;
        BY_NAME.put(size.name(), size);
        ORDER.add(size);
        return size;
    }

    /** {@link #register} 的便捷重载。 */
    public static EnergyHatchSize register(String name, long capacityFe, long maxInputFe, long maxOutputFe,
                                           IEnergyType defaultUnit) {
        return register(new EnergyHatchSize(name, capacityFe, maxInputFe, maxOutputFe, defaultUnit));
    }

    /** {@link #register} 的便捷重载（收发对称）。 */
    public static EnergyHatchSize register(String name, long capacityFe, long maxInputFe) {
        return register(EnergyHatchSize.of(name, capacityFe, maxInputFe));
    }

    /** {@link #register} 的便捷重载（收发对称 + 指定默认单位）。 */
    public static EnergyHatchSize register(String name, long capacityFe, long maxInputFe, IEnergyType defaultUnit) {
        return register(EnergyHatchSize.of(name, capacityFe, maxInputFe, defaultUnit));
    }

    /**
     * 按名字查（大小写/空格不敏感，因为 {@link EnergyHatchSize} 构造时已规范化）。
     *
     * @return 没注册过时返回 {@code null}
     */
    @Nullable
    public static synchronized EnergyHatchSize get(@Nullable String name) {
        if (name == null) return null;
        return BY_NAME.get(name.trim().toLowerCase(Locale.ROOT));
    }

    /**
     * 按下标取，<b>越界钳到边界</b>（负数 → 第 0 个，过大 → 最后一个）。
     *
     * @throws IllegalStateException 表是空的（说明既没调 {@link #registerDefaultPreset()}
     *                               也没注册任何自定义尺寸）—— 这种情况没有合理的兜底值，
     *                               直接报错比给一台「0 容量仓室」强
     */
    public static synchronized EnergyHatchSize of(int index) {
        if (ORDER.isEmpty()) {
            throw new IllegalStateException(
                    "no energy hatch size registered — call EnergyHatchSizes.registerDefaultPreset() "
                            + "or register your own EnergyHatchSize first");
        }
        int i = index < 0 ? 0 : Math.min(index, ORDER.size() - 1);
        return ORDER.get(i);
    }

    /** 全部尺寸（注册顺序，只读副本）。 */
    public static synchronized List<EnergyHatchSize> all() {
        return Collections.unmodifiableList(new ArrayList<>(ORDER));
    }

    /** 已注册的尺寸数量。 */
    public static synchronized int sizeCount() {
        return ORDER.size();
    }

    /** 名字 → 下标；没注册过返回 -1（想存「这台是哪一级」时用）。 */
    public static synchronized int indexOf(@Nullable String name) {
        EnergyHatchSize size = get(name);
        return size == null ? -1 : ORDER.indexOf(size);
    }

    /** 表是否是空的（没注册任何尺寸）。 */
    public static synchronized boolean isEmpty() {
        return ORDER.isEmpty();
    }

    // ═══════════════ 内置预设（可选） ═══════════════

    /** 内置预设的名字（Modular Machinery 的 8 级）。 */
    public static final String[] PRESET_NAMES = {
            "tiny", "small", "normal", "reinforced", "big", "huge", "ludicrous", "ultimate",
    };

    /** 内置预设的容量（FE）。 */
    private static final long[] PRESET_CAPACITY_FE = {
            2048L, 4096L, 8192L, 16384L, 32768L, 131072L, 524288L, 2097152L,
    };

    /** 内置预设的输入（FE/t）。 */
    private static final long[] PRESET_MAX_INPUT_FE = {
            128L, 512L, 512L, 2048L, 8192L, 32768L, 131072L, 524288L,
    };

    /** 内置预设的英文名。 */
    private static final String[] PRESET_EN = {
            "Tiny Energy Hatch", "Small Energy Hatch", "Normal Energy Hatch", "Reinforced Energy Hatch",
            "Big Energy Hatch", "Huge Energy Hatch", "Ludicrous Energy Hatch", "Ultimate Energy Hatch",
    };

    /** 内置预设的中文名。 */
    private static final String[] PRESET_ZH = {
            "微型能源仓", "小型能源仓", "中型能源仓", "强化能源仓",
            "大型能源仓", "巨型能源仓", "超级能源仓", "终极能源仓",
    };

    /**
     * 把 Modular Machinery 的 8 级数值当成<b>可选预设</b>注册进来（<b>不会自动调用</b>）。
     *
     * <p>
     * 数值原样照抄 MM：容量增长比输入速度慢得多，所以小仓室极容易满、大仓室几乎是缓冲池 ——
     * 这是 MM 的原始手感，本库不改。同时登记这 8 个名字的中英文本。
     *
     * <p>
     * 幂等：重复调用只会命中 {@link #register} 的去重逻辑；如果某个名字已经被 addon
     * 抢先注册过（自定义数值），这里的预设不会覆盖它。
     *
     * @return 真正生效的 8 个实例（顺序 = {@link #PRESET_NAMES}）
     */
    public static synchronized EnergyHatchSize[] registerDefaultPreset() {
        EnergyHatchSize[] result = new EnergyHatchSize[PRESET_NAMES.length];
        for (int i = 0; i < PRESET_NAMES.length; i++) {
            result[i] = register(new EnergyHatchSize(PRESET_NAMES[i], PRESET_CAPACITY_FE[i],
                    PRESET_MAX_INPUT_FE[i], PRESET_MAX_INPUT_FE[i], EnergyUnit.FE));
            registerLang(PRESET_NAMES[i], PRESET_EN[i], PRESET_ZH[i]);
        }
        return result;
    }

    // ═══════════════ 下标便利查询 ═══════════════

    /** 第 {@code index} 级的容量（FE）。越界自动钳到边界，表空则抛异常。 */
    public static long capacity(int index) {
        return of(index).capacityFe();
    }

    /** 第 {@code index} 级的最大输入（FE/t）。 */
    public static long maxInput(int index) {
        return of(index).maxInputFe();
    }

    /** 第 {@code index} 级的最大输出（FE/t）。 */
    public static long maxOutput(int index) {
        return of(index).maxOutputFe();
    }

    /** 第 {@code index} 级的名字。 */
    public static String name(int index) {
        return of(index).name();
    }

    /** 第 {@code index} 级的语言键。 */
    public static String langKey(int index) {
        return of(index).langKey();
    }

    // ═══════════════ 语言 ═══════════════

    /** 名字 → 语言键，例如 {@code tiny → ogmr.energy.hatch.tiny}。 */
    public static String langKey(String name) {
        return "ogmr.energy.hatch." + (name == null ? "unknown" : name.trim().toLowerCase(java.util.Locale.ROOT));
    }

    /**
     * 给一个尺寸名登记中英文（自定义尺寸的开发者用；内置预设已在
     * {@link #registerDefaultPreset()} 里登记过）。
     *
     * <p>
     * 幂等：{@link OGMRLang#add} 对同一个键保留先登记的那条。
     */
    public static void registerLang(String name, String english, @Nullable String chinese) {
        OGMRLang.add(langKey(name), english, chinese);
    }

    /**
     * 把当前表里所有尺寸的名字登记一遍。
     *
     * <p>
     * 命中内置预设名字的用预设的中英名（微型能源仓……），其余自定义名字先兜底登记成同名文本
     * （{@code mySize → "mySize"}，总比面板上出现裸语言键好）；想给自定义尺寸好看的中英名，
     * 请在注册时用 {@link #registerLang} 显式给。
     *
     * <p>
     * 幂等且不覆盖：{@link OGMRLang#add} 对同一个键保留先登记的那条，
     * 所以谁先登记谁赢，重复调用不会把已有文案刷掉。
     */
    public static synchronized void initLang() {
        for (EnergyHatchSize size : ORDER) {
            int preset = presetIndexOf(size.name());
            if (preset >= 0) {
                registerLang(size.name(), PRESET_EN[preset], PRESET_ZH[preset]);
            } else {
                OGMRLang.add(size.langKey(), size.name(), size.name());
            }
        }
    }

    private static int presetIndexOf(String name) {
        for (int i = 0; i < PRESET_NAMES.length; i++) {
            if (PRESET_NAMES[i].equals(name)) return i;
        }
        return -1;
    }
}
