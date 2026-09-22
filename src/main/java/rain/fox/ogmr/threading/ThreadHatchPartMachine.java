package rain.fox.ogmr.threading;

import rain.fox.ogmr.OGMRConfig;
import rain.fox.ogmr.api.OGMRValues;
import rain.fox.ogmr.api.lang.OGMRLang;
import rain.fox.ogmr.api.machine.IMachineBlockEntity;
import rain.fox.ogmr.api.machine.multiblock.PartAbility;
import rain.fox.ogmr.api.machine.multiblock.part.MultiblockPartMachine;
import rain.fox.ogmr.api.registry.OGMRRegistries;

import lombok.Getter;

import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * 「线程仓」多方块部件。
 *
 * <p>
 * Java 重写自 GTEternalTime 的 {@code part/ThreadHatchPartMachine.kt}，改动：
 * <ul>
 * <li>Kotlin 版继承 GTCEu 的 {@code TieredPartMachine}（自带档位字段）并实现 {@code IFancyUIMachine}
 * （LDLib 数值输入面板，玩家可在 1 ~ 上限之间下调线程数，值 {@code @Persisted} 进 NBT）；
 * 本版的基类 {@link MultiblockPartMachine} 不带档位，所以档位由本类自己存；
 * <b>没有</b>面板 UI（本库的控件体系尚未落地），玩家下调接口保留成普通方法
 * {@link #setThreadAmount(int)}，但<b>不持久化</b>（重启回到上限）；</li>
 * <li>线程数规则多了一条「按档位缩放」的兜底：构造参数 {@code threads > 0} 时<b>原样使用</b>
 * （与 Kotlin 的变体表语义一致：表里写多少就是多少），{@code threads <= 0} 时才按档位现算
 * （见 {@link #threadsForTier(int)}）—— Kotlin 早期那份「由 tier 现算」被删掉了，
 * 本版把它留成兜底，好让 addon 少写一张变体表；</li>
 * <li>Kotlin 的 {@code canShared() = false}（禁止多方块部件共享）在本库没有对应机制，见下方 TODO。</li>
 * </ul>
 *
 * <h3>线程数怎么给</h3>
 * <p>
 * {@link #getThreadCount()} 返回的是<b>当前生效</b>值，{@link #getMaxThreads()} 是上限；
 * 默认两者相等（装上去就是全开）。整机上限由 {@link ThreadedMultiblockMachine#getMaxThreads()}
 * 把结构里所有仓的当前值<b>求和</b>得到，再由 {@link ThreadedRecipeLogic#setThreadCount(int)}
 * 夹到 {@code OGMRConfig.maxThreadCount}。
 *
 * <p>
 * ⚠️ 与「并行仓」不是同一个量：线程 = 同时跑<b>几种不同配方</b>，并行 = 同一种配方<b>同时跑几次</b>，
 * 所以两族的档位不需要对齐。
 *
 * @author rain fox（Java 重写）
 */
public class ThreadHatchPartMachine extends MultiblockPartMachine implements IThreadHatch {

    // ═══════════════ 常量 ═══════════════

    /** 本仓能力的注册名（也是 {@link #threadAbility()} 在 {@link OGMRRegistries#PART_ABILITIES} 里的键）。 */
    public static final String ABILITY_NAME = "thread_hatch";

    /** 线程数下限：至少 1 条（= 退化成「一台机器一条配方」）。 */
    public static final int MIN_THREAD = 1;

    /** 「按档位缩放」的基准档（ZPM）：这一档给 {@link #BASE_THREADS} 条，每高一档翻倍。 */
    public static final int BASE_TIER = OGMRValues.ZPM;

    /** 基准档（ZPM）的线程数 —— 与 GTET 变体表一致。 */
    public static final int BASE_THREADS = 4;

    // ═══════════════ 语言键 ═══════════════

    /** 线程数：{@code Threads %s / %s}（参数 = 当前、上限）。 */
    public static final String LANG_THREADS = "ogmr.threading.hatch.threads";
    /** 档位：{@code Tier %s · %s threads per hatch}（参数 = 档位名、本仓上限）。 */
    public static final String LANG_TIER = "ogmr.threading.hatch.tier";

    // ═══════════════ 状态 ═══════════════

    /** 电压档位（本库的 {@link MultiblockPartMachine} 不存档位，所以由本类自己记）。 */
    @Getter
    private final int tier;

    /** 本仓的线程数上限（构造时定死，之后不变；玩家只能往下调）。 */
    @Getter
    private final int maxThreads;

    /** 当前生效的线程数（{@link #MIN_THREAD} ~ {@link #maxThreads}）。 */
    private int currentThread;

    /** 能力实例（懒加载；见 {@link #threadAbility()}）。 */
    private static volatile PartAbility threadAbility;

    /**
     * @param holder  机器宿主
     * @param tier    电压档位（决定外壳贴图与「按档位缩放」的兜底线程数）
     * @param threads 本档的线程数上限；{@code <= 0} 时按 {@link #threadsForTier(int)} 现算
     */
    public ThreadHatchPartMachine(IMachineBlockEntity holder, int tier, int threads) {
        super(holder);
        this.tier = OGMRValues.clampTier(tier);
        this.maxThreads = threads > 0 ? threads : threadsForTier(this.tier);
        this.currentThread = this.maxThreads;
    }

    // ═══════════════ 线程数 ═══════════════

    /**
     * 「由档位现算线程数」的唯一规则 —— <b>只有构造参数给不出正数时才用</b>。
     *
     * <p>
     * 规则：{@link #BASE_TIER}（ZPM）档 = {@link #BASE_THREADS}（4）条，<b>每高一档翻倍</b>；
     * 低于 ZPM 的档位一律兜底 {@link #MIN_THREAD}（1 条）。算出来的值与 GTET 的变体表逐档相同：
     * ZPM=4、UV=8、UHV=16、UEV=32、UIV=64、UXV=128、OpV=256、MAX=512。
     *
     * <p>
     * 之所以只当兜底而不当主规则：Kotlin 版把线程数放进了显式变体表（「表里写多少就是多少，
     * 本类不做任何推导」），本版保留「显式值优先」，同时给懒得写表的 addon 一条现算的路。
     */
    public static int threadsForTier(int tier) {
        int t = OGMRValues.clampTier(tier);
        if (t < BASE_TIER) return MIN_THREAD;
        int shift = t - BASE_TIER;
        // 高位档 + 左移可能溢出：夹到合理上限（配置上限由线程逻辑再夹一次）
        if (shift >= 20) return Integer.MAX_VALUE / 2;
        return BASE_THREADS << shift;
    }

    @Override
    public int getThreadCount() {
        return currentThread;
    }

    /**
     * 玩家/结构下调线程数（夹到 {@code [1, maxThreads]}）。
     *
     * <p>
     * ⚠️ 下调<b>不会</b>杀掉已经在跑的线程（Kotlin 版同款取舍：线程一开就已经扣过料，
     * 砍掉等于把材料凭空吞了）；它只影响「之后还能开几条新线程」，
     * 由 {@link ThreadedRecipeLogic} 的槽位容量规则保证。
     */
    public void setThreadAmount(int amount) {
        int clamped = Math.max(MIN_THREAD, Math.min(amount, maxThreads));
        this.currentThread = clamped;
    }

    /**
     * 有没有插进成型结构里。
     *
     * <p>
     * 「通电」不在本类管：本仓不存能量。想要求电的 addon 覆写本方法即可
     * （例如再检查结构里有没有可用的能源仓）。
     */
    @Override
    public boolean isActive() {
        return isFormed();
    }

    // ═══════════════ 面板 ═══════════════

    @Override
    public void addDisplayText(List<Component> textList) {
        super.addDisplayText(textList);

        // 第一行：ZPM 线程仓 · 本档规格
        textList.add(Component.literal(OGMRValues.tierNameRaw(tier))
                .append(" · ")
                .append(Component.translatable(LANG_TIER,
                        OGMRValues.tierNameRaw(tier),
                        rain.fox.ogmr.utils.Formatting.formatNumber(maxThreads))));
        // 第二行：当前 / 上限
        textList.add(Component.translatable(LANG_THREADS,
                rain.fox.ogmr.utils.Formatting.formatNumber(currentThread),
                rain.fox.ogmr.utils.Formatting.formatNumber(maxThreads)));
    }

    // ═══════════════ 能力注册 ═══════════════

    /**
     * 本仓的「线程仓」能力（懒加载、幂等）。
     *
     * <p>
     * 先查注册表：已经登记过同名能力就<b>复用那一个实例</b>（{@code PartAbility} 内部维护
     * 「档位 → 方块」的表，两个实例会让结构匹配与运行时反查各看一张表）。
     *
     * <p>
     * ⚠️ 这里用了 {@code PartAbility.create(String)} 这个工厂名；若并行编写的
     * {@code PartAbility} 最终用的是别的写法（例如公开构造器），只需改这一行。
     */
    public static PartAbility threadAbility() {
        PartAbility cached = threadAbility;
        if (cached != null) return cached;
        synchronized (ThreadHatchPartMachine.class) {
            if (threadAbility != null) return threadAbility;
            PartAbility existing = OGMRRegistries.PART_ABILITIES.get(ABILITY_NAME);
            if (existing == null) {
                existing = PartAbility.create(ABILITY_NAME);
                registerInRegistry(existing);
            }
            threadAbility = existing;
            return existing;
        }
    }

    /**
     * 把能力登记进 {@link OGMRRegistries#PART_ABILITIES}（幂等，重复调用不报错）。
     *
     * <p>
     * 注册表在「注册阶段之外」是冻结的（{@code OGMRRegistry#registerOrOverride} 见冻结就抛），
     * 而本方法可能在类加载/tooltip 之类的地方被顺手调到，所以这里对「已冻结 / 已存在」两种情况
     * 一律静默跳过 —— 拿不到表项最多是「{@code MultiblockPartMachine#getAbilities()} 不列这条能力」，
     * 不该让世界 tick 崩掉。
     */
    private static void registerInRegistry(PartAbility ability) {
        try {
            if (OGMRRegistries.PART_ABILITIES.containKey(ABILITY_NAME)) return;
            if (OGMRRegistries.PART_ABILITIES.isFrozen()) return;
            OGMRRegistries.PART_ABILITIES.registerOrOverride(ABILITY_NAME, ability);
        } catch (Throwable ignored) {
            // 见方法注释：拿不到表项是可接受的降级
        }
    }

    /**
     * 确保线程仓能力已登记（<b>幂等</b>，重复调用不报错）。
     *
     * <p>
     * addon 的 {@code registerPartAbilities()} 阶段调它一次即可；
     * {@link ThreadedHatches#registerThreadHatches} 也会先调一次，所以「只用便捷注册」的 addon
     * 不必再手动调。
     *
     * <p>
     * 结构图案里请用 {@code Predicates.abilities(ThreadHatchPartMachine.threadAbility())}，
     * 别再自己 new 一个同名的能力。
     */
    public static void registerAbilities() {
        threadAbility();
    }

    // ═══════════════ 配置 ═══════════════

    /** 单机线程数上限（读配置 getter；配置未加载时它自己会回落到默认值）。 */
    static int maxThreadCount() {
        return Math.max(1, OGMRConfig.getMaxThreadCount());
    }

    // ═══════════════ 语言 ═══════════════

    /** 登记本类用到的语言键（幂等）。 */
    public static void initLang() {
        OGMRLang.add(LANG_THREADS, "Threads %s / %s", "线程 %s / %s");
        OGMRLang.add(LANG_TIER, "Tier %s · %s threads per hatch", "档位 %s · 每仓 %s 条线程");
    }

    @Override
    public String toString() {
        return "ThreadHatchPartMachine[%s, %d/%d threads]".formatted(
                OGMRValues.tierNameRaw(tier), currentThread, maxThreads);
    }
}
