package rain.fox.ogmr.api.lang;

import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 本库自己的轻量语言键登记表。
 *
 * <p>
 * 背景：GTM 的语言文件由 datagen 产出，而 GTCEu 的 {@code IGTAddon} 只在很晚的阶段初始化，
 * 所以「基类里用到的语言键」必须在 <b>数据生成之前</b> 就被登记下来。本类就是那个登记处：
 * 任何基类/工具类都可以在自己的 {@code initLang()} 里 {@link #add}，数据生成时统一写出
 * {@code en_us.json} / {@code zh_cn.json}。
 *
 * <p>
 * 与 GTEternalTime 的 {@code LangUtil} 的区别：这里不依赖任何外部 mod，纯静态收集，
 * 且线程安全（登记只发生在类初始化期，读取发生在 datagen 期）。
 *
 * <pre>{@code
 * public static final String LANG_NO_HOST = "ogmr.machine.module.no_host";
 *
 * public static void initLang() {
 *     OGMRLang.add(LANG_NO_HOST, "No module host within %s blocks", "附近 %s 格内没有模块主机");
 * }
 * }</pre>
 */
public final class OGMRLang {

    /**
     * 一条语言键的三个值。
     *
     * @param en 英文（{@code en_us}）
     * @param zh 中文（{@code zh_cn}），可为 null 表示暂时只有英文
     */
    public record Entry(String key, String en, @Nullable String zh, String owner) {}

    private static final Map<String, Entry> ENTRIES = Collections.synchronizedMap(new LinkedHashMap<>());

    private OGMRLang() {}

    /**
     * 登记一条双语语言键。
     *
     * <p>
     * 同一个键重复登记时 <b>保留先登记的那条</b>（避免子类覆盖基类文案导致的随机性），
     * 因此本方法是幂等的，可以放心在静态初始化块里反复调用。
     *
     * @param key 语言键，约定以 {@code ogmr.} 开头
     * @param en  英文文案
     * @param zh  中文文案，可传 null
     */
    public static void add(String key, String en, @Nullable String zh) {
        String owner = callerName();
        ENTRIES.putIfAbsent(key, new Entry(key, en, zh, owner));
    }

    /**
     * 只登记英文（中文暂缺时用；之后再用 {@link #add} 补中文不会生效，需要 {@link #override}）。
     */
    public static void add(String key, String en) {
        add(key, en, null);
    }

    /**
     * 强制覆盖一条语言键（子类要改写基类文案时用）。
     */
    public static void override(String key, String en, @Nullable String zh) {
        ENTRIES.put(key, new Entry(key, en, zh, callerName()));
    }

    public static boolean contains(String key) {
        return ENTRIES.containsKey(key);
    }

    @Nullable
    public static Entry get(String key) {
        return ENTRIES.get(key);
    }

    /** 全部登记项（只读视图，供 datagen 使用）。 */
    public static Map<String, Entry> entries() {
        return Collections.unmodifiableMap(ENTRIES);
    }

    /**
     * 取出 {@code en_us.json} 的内容。
     */
    public static Map<String, String> english() {
        Map<String, String> map = new LinkedHashMap<>();
        ENTRIES.values().forEach(e -> map.put(e.key(), e.en()));
        return map;
    }

    /**
     * 取出 {@code zh_cn.json} 的内容（缺失中文的键回退到英文，保证不会出现裸键）。
     */
    public static Map<String, String> chinese() {
        Map<String, String> map = new LinkedHashMap<>();
        ENTRIES.values().forEach(e -> map.put(e.key(), e.zh() != null ? e.zh() : e.en()));
        return map;
    }

    /** 让 addon 在数据生成之前把自己那一批键灌进来。 */
    @FunctionalInterface
    public interface LangContributor {

        void contribute();
    }

    private static String callerName() {
        StackWalker.StackFrame[] frames = StackWalker.getInstance().walk(s -> s.limit(4).toArray(StackWalker.StackFrame[]::new));
        // frames[0] = callerName, frames[1] = add/override, frames[2] = 真正的调用方
        return frames.length > 2 ? frames[2].getClassName() : "unknown";
    }
}
