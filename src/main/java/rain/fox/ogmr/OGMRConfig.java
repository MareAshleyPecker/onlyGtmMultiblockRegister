package rain.fox.ogmr;

import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.config.ModConfigEvent;

import org.apache.commons.lang3.tuple.Pair;
import org.jetbrains.annotations.Nullable;

import java.util.function.Supplier;

/**
 * 本库的配置。
 *
 * <p>
 * 库本身没有游戏内容，配置项都是「框架行为」级别的开关：
 * 多线程配方的默认线程池大小、模块化主机的默认搜索半径、是否打印 addon 扫描结果等。
 *
 * <h3>怎么读</h3>
 * <p>
 * <b>每个配置项都有一个静态 getter</b>（{@code getXxx()} / {@code isXxx()}），请优先用它们，
 * 而不是自己去摸 {@link #INSTANCE}：
 * <ul>
 * <li>getter 会做<b>「配置还没加载」的降级</b> —— Forge 的 {@code ConfigValue#get()} 在配置加载之前
 * 会抛 {@code IllegalStateException}，而机器类的静态初始化、{@code @OGMRAddon} 的扫描等
 * 都可能跑得比配置加载早。getter 在这种时候返回该配置项的<b>声明默认值</b>，
 * 于是调用方不用再到处写 try/catch（本库内部那些 try/catch 就是被这套规则取代掉的）；</li>
 * <li>配置热重载后 getter 立刻反映新值（每次调用都重新读，不做缓存）；</li>
 * <li>整合包作者改完配置不需要重启，机器下次 tick 就会用新值。</li>
 * </ul>
 *
 * <p>
 * 想拿原始的 {@link ForgeConfigSpec.ConfigValue}（例如要监听变更、要显示在 UI 上）才用
 * {@link #INSTANCE} 上的公开字段。addon 也可以监听 {@code ModConfigEvent}。
 */
@Mod.EventBusSubscriber(modid = Ogmr.MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD)
public class OGMRConfig {

    public static final ForgeConfigSpec SPEC;
    public static final OGMRConfig INSTANCE;

    static {
        Pair<OGMRConfig, ForgeConfigSpec> pair = new ForgeConfigSpec.Builder().configure(OGMRConfig::new);
        INSTANCE = pair.getLeft();
        SPEC = pair.getRight();
    }

    // ═══════════════ 声明默认值 ═══════════════
    // 这些常量就是配置项在 defineXxx(...) 里写的默认值，getter 在「配置未加载」时返回它们。
    // ⚠️ 改默认值要两边一起改（构造器里的 define 与这里的常量），否则降级路径会跟正常路径不一致。

    /** {@code threading.defaultThreadCount} 的默认值。 */
    public static final int DEFAULT_THREAD_COUNT = 4;
    /** {@code threading.maxThreadCount} 的默认值。 */
    public static final int MAX_THREAD_COUNT = 64;
    /** {@code threading.shareThreadPoolAcrossMachines} 的默认值。 */
    public static final boolean DEFAULT_SHARE_THREAD_POOL = true;
    /** {@code modular.defaultHostRange} 的默认值。 */
    public static final int DEFAULT_HOST_RANGE = 32;
    /** {@code modular.defaultHostRecheckInterval} 的默认值。 */
    public static final int DEFAULT_HOST_RECHECK_INTERVAL = 80;
    /** {@code misc.logAddonDiscovery} 的默认值。 */
    public static final boolean DEFAULT_LOG_ADDON_DISCOVERY = true;
    /** {@code misc.allowStructurePreview} 的默认值。 */
    public static final boolean DEFAULT_ALLOW_STRUCTURE_PREVIEW = true;

    // ═══════════════ 配置项本体 ═══════════════

    // ─────────────── threading ───────────────
    public final ForgeConfigSpec.IntValue defaultThreadCount;
    public final ForgeConfigSpec.IntValue maxThreadCount;
    public final ForgeConfigSpec.BooleanValue shareThreadPoolAcrossMachines;

    // ─────────────── modular ───────────────
    public final ForgeConfigSpec.IntValue defaultHostRange;
    public final ForgeConfigSpec.IntValue defaultHostRecheckInterval;

    // ─────────────── misc ───────────────
    public final ForgeConfigSpec.BooleanValue logAddonDiscovery;
    public final ForgeConfigSpec.BooleanValue allowStructurePreview;

    private OGMRConfig(ForgeConfigSpec.Builder builder) {
        builder.comment("onlyGtmMultiblockRegister —— 多方块注册工具库").push("ogmr");

        builder.comment("多线程配方（rain.fox.ogmr.threading）").push("threading");
        defaultThreadCount = builder
                .comment("一台多线程机器默认开几条并行配方线程")
                .defineInRange("defaultThreadCount", DEFAULT_THREAD_COUNT, 1, 256);
        maxThreadCount = builder
                .comment("单台机器允许的最大线程数（玩家/结构升级也越不过这个上限）")
                .defineInRange("maxThreadCount", MAX_THREAD_COUNT, 1, 1024);
        shareThreadPoolAcrossMachines = builder
                .comment("【保留项，当前无读取方】多线程的「线程」是逻辑配方槽位，跑在服务端主线程上、不创建 OS 线程池；",
                        "这个开关留给将来真的要开工作线程池时用，现在改它没有任何效果。")
                .define("shareThreadPoolAcrossMachines", DEFAULT_SHARE_THREAD_POOL);
        builder.pop();

        builder.comment("模块化多方块（rain.fox.ogmr.modular）").push("modular");
        defaultHostRange = builder
                .comment("单元默认搜索主机的半径（格）")
                .defineInRange("defaultHostRange", DEFAULT_HOST_RANGE, 1, 128);
        defaultHostRecheckInterval = builder
                .comment("单元重新搜索主机的间隔（tick）")
                .defineInRange("defaultHostRecheckInterval", DEFAULT_HOST_RECHECK_INTERVAL, 1, 1200);
        builder.pop();

        builder.comment("杂项").push("misc");
        logAddonDiscovery = builder
                .comment("启动时把扫描到的 @OGMRAddon 打进日志")
                .define("logAddonDiscovery", DEFAULT_LOG_ADDON_DISCOVERY);
        allowStructurePreview = builder
                .comment("是否允许多方块结构预览（世界内投影 / JEI-EMI 预览页）")
                .define("allowStructurePreview", DEFAULT_ALLOW_STRUCTURE_PREVIEW);
        builder.pop();

        builder.pop();
    }

    // ═══════════════ 每个配置项一个 getter ═══════════════

    /** 一台多线程机器默认开几条并行配方线程。配置未加载时回落到 {@value #DEFAULT_THREAD_COUNT}。 */
    public static int getDefaultThreadCount() {
        return readInt(INSTANCE != null ? INSTANCE.defaultThreadCount : null, DEFAULT_THREAD_COUNT);
    }

    /** 单台机器允许的最大线程数。配置未加载时回落到 {@value #MAX_THREAD_COUNT}。 */
    public static int getMaxThreadCount() {
        return readInt(INSTANCE != null ? INSTANCE.maxThreadCount : null, MAX_THREAD_COUNT);
    }

    /**
     * 是否全服共用一个工作线程池。
     *
     * <p>
     * ⚠️ <b>当前是保留项，没有任何读取方</b>：本库多线程子系统里的「线程」是逻辑配方槽位，
     * 跑在服务端主线程上，并不创建 OS 线程池。这个开关留着给将来真要开工作线程池时用。
     * 配置未加载时回落到 {@value #DEFAULT_SHARE_THREAD_POOL}。
     */
    public static boolean isShareThreadPoolAcrossMachines() {
        return readBool(INSTANCE != null ? INSTANCE.shareThreadPoolAcrossMachines : null,
                DEFAULT_SHARE_THREAD_POOL);
    }

    /** 单元默认搜索主机的半径（格）。配置未加载时回落到 {@value #DEFAULT_HOST_RANGE}。 */
    public static int getDefaultHostRange() {
        return readInt(INSTANCE != null ? INSTANCE.defaultHostRange : null, DEFAULT_HOST_RANGE);
    }

    /** 单元重新搜索主机的间隔（tick）。配置未加载时回落到 {@value #DEFAULT_HOST_RECHECK_INTERVAL}。 */
    public static int getDefaultHostRecheckInterval() {
        return readInt(INSTANCE != null ? INSTANCE.defaultHostRecheckInterval : null,
                DEFAULT_HOST_RECHECK_INTERVAL);
    }

    /** 启动时是否把扫描到的 {@code @OGMRAddon} 打进日志。配置未加载时回落到 {@value #DEFAULT_LOG_ADDON_DISCOVERY}。 */
    public static boolean isLogAddonDiscovery() {
        return readBool(INSTANCE != null ? INSTANCE.logAddonDiscovery : null, DEFAULT_LOG_ADDON_DISCOVERY);
    }

    /** 是否允许多方块结构预览（世界内投影 / JEI-EMI 预览页）。配置未加载时回落到 {@value #DEFAULT_ALLOW_STRUCTURE_PREVIEW}。 */
    public static boolean isAllowStructurePreview() {
        return readBool(INSTANCE != null ? INSTANCE.allowStructurePreview : null,
                DEFAULT_ALLOW_STRUCTURE_PREVIEW);
    }

    // ═══════════════ 旧名字（保留兼容） ═══════════════

    /**
     * 旧名，等价于 {@link #getDefaultThreadCount()}。
     *
     * @deprecated 用 {@link #getDefaultThreadCount()}，名字里带上「Default」不容易和「运行时实际线程数」混淆。
     */
    @Deprecated(since = "1.1", forRemoval = false)
    public static int threadCount() {
        return getDefaultThreadCount();
    }

    /**
     * 旧名，等价于 {@link #getDefaultHostRange()}。
     *
     * @deprecated 用 {@link #getDefaultHostRange()}。
     */
    @Deprecated(since = "1.1", forRemoval = false)
    public static int hostRange() {
        return getDefaultHostRange();
    }

    // ═══════════════ 内部：安全读取 ═══════════════

    /**
     * 读一个 int 配置项；配置还没加载（或整个 config 对象还没建好）时返回 {@code fallback}。
     *
     * <p>
     * Forge 的 {@code ConfigValue#get()} 在配置加载前会抛 {@link IllegalStateException}，
     * 而本库有些读取点确实可能更早（机器类的静态初始化、addon 扫描、数据生成）。
     * 这里统一把它降级成「用声明默认值」，比让调用方各自 try/catch 更不容易漏。
     */
    private static int readInt(@Nullable ForgeConfigSpec.IntValue value, int fallback) {
        if (value == null) return fallback;
        try {
            Integer read = value.get();
            return read != null ? read : fallback;
        } catch (IllegalStateException notLoadedYet) {
            return fallback;
        }
    }

    /** 读一个 boolean 配置项；配置还没加载时返回 {@code fallback}。语义同 {@link #readInt}。 */
    private static boolean readBool(@Nullable ForgeConfigSpec.BooleanValue value, boolean fallback) {
        if (value == null) return fallback;
        try {
            Boolean read = value.get();
            return read != null ? read : fallback;
        } catch (IllegalStateException notLoadedYet) {
            return fallback;
        }
    }

    /** 通用版本：给 addon 自己定义 {@code ConfigValue} 时复用同一套降级逻辑。 */
    public static <T> T readOrDefault(@Nullable ForgeConfigSpec.ConfigValue<T> value, T fallback) {
        if (value == null) return fallback;
        try {
            T read = value.get();
            return read != null ? read : fallback;
        } catch (IllegalStateException notLoadedYet) {
            return fallback;
        }
    }

    /** 调试用：把当前生效的全部配置打成可读文本（配置没加载时给出默认值）。 */
    public static String describe() {
        return "ogmr config { defaultThreadCount=%d, maxThreadCount=%d, shareThreadPool=%s,"
                .formatted(getDefaultThreadCount(), getMaxThreadCount(), isShareThreadPoolAcrossMachines())
                + " hostRange=%d, hostRecheckInterval=%d, logAddonDiscovery=%s, allowStructurePreview=%s }"
                        .formatted(getDefaultHostRange(), getDefaultHostRecheckInterval(),
                                isLogAddonDiscovery(), isAllowStructurePreview());
    }

    @SubscribeEvent
    static void onLoad(final ModConfigEvent event) {
        if (event.getConfig().getSpec() == SPEC) {
            Ogmr.LOGGER.debug("ogmr config loaded: {} -> {}", event.getConfig().getFileName(), describe());
        }
    }
}
