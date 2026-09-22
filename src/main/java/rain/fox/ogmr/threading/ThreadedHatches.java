package rain.fox.ogmr.threading;

import rain.fox.ogmr.Ogmr;
import rain.fox.ogmr.api.OGMRValues;
import rain.fox.ogmr.api.machine.MachineDefinition;
import rain.fox.ogmr.api.registry.MachineRegistrar;
import rain.fox.ogmr.api.registry.OGMRRegistries;

import java.util.Arrays;
import java.util.Locale;

/**
 * 「线程仓」注册入口 —— 一行注册一整排。
 *
 * <p>
 * Java 重写自 GTEternalTime 的 {@code data/machine/hatch/ETThreadHatches.kt}，改动：
 * <ul>
 * <li>Kotlin 版是「变体表（{@code ThreadHatchVariant(id, threads, tier)}）+ 逐个 registerOne」，
 * 每个变体自带唯一 tier 与线程数，还要自己处理贴图覆盖层（GTOCore 素材）；
 * 本版按需求做成<b>两个数组参数</b>（{@code tiers} / {@code threads}）的便捷方法 ——
 * 名字由档位推导（{@code lv_thread_hatch}），线程数不推导（原样传给部件），
 * 贴图/外观由 addon 在返回的 {@link MachineDefinition} 上自己接（本库的模型体系由 addon 决定）；</li>
 * <li>Kotlin 版一个变体一个显式 id；本版按需求用
 * {@code OGMRValues.VN[tier].toLowerCase(Locale.ROOT) + "_thread_hatch"} 生成，
 * 所以<b>同一档只能注册一次</b>（重复注册会撞注册表的同名键）—— 要同档多规格请自己在
 * {@code MachineRegistrar} 上注册，别用本方法；</li>
 * <li>线程数默认表抄的是 GTET 的变体表（ZPM=4 起每档翻倍到 MAX=512），见 {@link #DEFAULT_TIERS}
 * / {@link #DEFAULT_THREADS}。</li>
 * </ul>
 *
 * <h3>用法</h3>
 * <pre>{@code
 * // 在 addon 的 registerMachines(...) 里：
 * public static final MachineDefinition[] THREAD_HATCHES =
 *     ThreadedHatches.registerThreadHatches(REGISTRAR);
 * }</pre>
 *
 * <p>
 * 本类的静态初始化会顺手登记本子系统全部语言键（{@link ThreadedRecipeLogic#initLang()} /
 * {@link ThreadHatchPartMachine#initLang()}），所以只要 addon 用过本方法，语言键就一定在数据生成之前
 * 落进 {@code OGMRLang}；没用本方法而是手写注册的 addon 请自己调一次那两个 {@code initLang()}。
 */
public final class ThreadedHatches {

    private ThreadedHatches() {}

    static {
        // 注册用的是本类的静态入口，顺手把语言键登记掉（幂等）
        initLang();
    }

    /** 默认档位表：ZPM 起每档翻倍一路到 MAX（与 GTET 的线程仓变体表逐档一致）。 */
    public static final int[] DEFAULT_TIERS = {
            OGMRValues.ZPM, OGMRValues.UV, OGMRValues.UHV, OGMRValues.UEV,
            OGMRValues.UIV, OGMRValues.UXV, OGMRValues.OpV, OGMRValues.MAX,
    };

    /** 默认线程数表：与 {@link #DEFAULT_TIERS} 一一对应（4 / 8 / 16 / 32 / 64 / 128 / 256 / 512）。 */
    public static final int[] DEFAULT_THREADS = { 4, 8, 16, 32, 64, 128, 256, 512 };

    /** 登记本子系统全部语言键（幂等）。 */
    public static void initLang() {
        ThreadedRecipeLogic.initLang();
        ThreadHatchPartMachine.initLang();
        ThreadedMultiblockMachine.initLang();
    }

    /** 用默认档位/线程数表注册一整排线程仓。 */
    public static MachineDefinition[] registerThreadHatches(MachineRegistrar registrar) {
        return registerThreadHatches(registrar, DEFAULT_TIERS, DEFAULT_THREADS);
    }

    /**
     * 注册一整排线程仓。
     *
     * <p>
     * 注册名 = {@code OGMRValues.VN[tier].toLowerCase(Locale.ROOT) + "_thread_hatch"}
     * （例如 {@code lv_thread_hatch}、{@code max_thread_hatch}）；
     * 每个仓登记「线程仓」能力（{@link ThreadHatchPartMachine#threadAbility()}），
     * 这样 {@code PartBuilder#register()} 会把方块写进能力表，结构图案里的
     * {@code Predicates.abilities(ThreadHatchPartMachine.threadAbility())} 才匹配得到。
     *
     * @param registrar 注册器
     * @param tiers     电压档位数组（越界会被夹到合法档位）
     * @param threads   对应的线程数数组；{@code <= 0} 表示「按档位现算」
     *                  （见 {@link ThreadHatchPartMachine#threadsForTier(int)}）
     * @return 注册好的定义数组，顺序与入参一致
     */
    public static MachineDefinition[] registerThreadHatches(MachineRegistrar registrar,
                                                            int[] tiers, int[] threads) {
        if (registrar == null || tiers == null || threads == null) {
            throw new IllegalArgumentException("ogmr: registerThreadHatches 的 registrar/tiers/threads 都不能为 null");
        }
        int count = Math.min(tiers.length, threads.length);
        if (tiers.length != threads.length) {
            Ogmr.LOGGER.warn("ogmr: registerThreadHatches 的 tiers({}) 与 threads({}) 长度不一致，只注册前 {} 个",
                    tiers.length, threads.length, count);
        }

        // 能力先登记（幂等）：PartBuilder 注册时会用它把方块写进能力表
        ThreadHatchPartMachine.registerAbilities();

        MachineDefinition[] registered = new MachineDefinition[count];
        for (int i = 0; i < count; i++) {
            registered[i] = registerOne(registrar, tiers[i], threads[i]);
        }
        return registered;
    }

    /** 注册单个线程仓。 */
    private static MachineDefinition registerOne(MachineRegistrar registrar, int tier, int threadCount) {
        final int clampedTier = OGMRValues.clampTier(tier);
        final int count = threadCount;
        String name = OGMRValues.VN[clampedTier].toLowerCase(Locale.ROOT) + "_thread_hatch";

        // 注意：这里刻意不做链式调用 —— part(...) 返回的是带通配符的 PartBuilder<..., ?>，
        // 分句写比一路链下去更不容易踩到捕获类型（capture）的坑。
        var builder = registrar.part(name,
                holder -> new ThreadHatchPartMachine(holder, clampedTier, count));
        builder.tier(clampedTier);
        builder.abilities(ThreadHatchPartMachine.threadAbility());
        // 名字跟着档位走：LV / MV / HV … 线程仓
        builder.langValue(OGMRValues.tierNameRaw(clampedTier) + " Thread Hatch",
                OGMRValues.tierNameRaw(clampedTier) + " 线程仓");

        // ⚠️ 这里**刻意不接** register() 的返回值，而是注册完再从注册表按 id 取回定义：
        // 并行开发的 MachineRegistrar#part(...) 目前把 PartBuilder 的定义类型参数 D 写成了
        // MultiblockPartMachine（机器类，而 D 的约束是 MachineDefinition），于是 register() 的
        // 静态返回类型暂时不是 MachineDefinition。走注册表既能绕开这一步，又在泛型修好之后
        // 依然正确（注册表里的就是同一个对象）。等那边对齐后可以直接写成
        // `MachineDefinition definition = builder.register();`。
        builder.register();
        MachineDefinition definition = OGMRRegistries.MACHINES.get(builder.getId());
        if (definition == null) {
            Ogmr.LOGGER.error("ogmr: thread hatch {} 注册后没有出现在 MACHINES 注册表里", name);
        }

        Ogmr.LOGGER.debug("ogmr: registered thread hatch {} (tier {}, {} threads)",
                name, OGMRValues.tierNameRaw(clampedTier),
                count > 0 ? count : ThreadHatchPartMachine.threadsForTier(clampedTier));
        return definition;
    }

    @Override
    public String toString() {
        return "ThreadedHatches[defaults tiers=%s threads=%s]".formatted(
                Arrays.toString(DEFAULT_TIERS), Arrays.toString(DEFAULT_THREADS));
    }
}
