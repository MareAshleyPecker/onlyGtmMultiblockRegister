package rain.fox.ogmr.api.recipe.content;

import rain.fox.ogmr.Ogmr;
import rain.fox.ogmr.api.recipe.Content;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 内容种类的注册表 —— {@link IContentKind} 的查表与注册处。
 *
 * <p>
 * 与 {@code EnergyTypes} 一样的分工：接口负责「一种内容长什么样」，本类负责
 * 「有哪几种、按名字怎么找回来」。
 *
 * <ul>
 * <li>内置两种：{@link #ITEM}、{@link #FLUID}（id 分别是 {@code "item"} / {@code "fluid"}）；</li>
 * <li>{@link #register(IContentKind)} 把第三方种类收进来，重名会抛异常而不是静默覆盖；</li>
 * <li>{@link #codecFor(String)} 给 {@link Content#CODEC} 用 —— 认不出的 {@code type} 会得到
 * 一个「解码必定失败」的 codec，报错里带上到底收到了什么、支持哪些；</li>
 * <li>{@link #byName(String)} 是宽容版（认不出回落 {@link #DEFAULT}），给配置/网络之外的场景用。</li>
 * </ul>
 *
 * <p>
 * ⚠️ <b>注册时机</b>：必须早于任何配方被解析（也就是数据包加载之前），一般就在 mod 构造期或
 * addon 的 {@code initialize()} 里注册。晚注册的后果是配方 JSON 里那种 {@code type} 会被判为
 * 「未知内容类型」而整条配方加载失败。
 */
public final class ContentKinds {

    // ⚠️ 容器必须在内置常量之前声明：静态字段按声明顺序初始化，而内置常量会在自己的初始化里 register(...)
    private static final Map<String, IContentKind> BY_ID = new LinkedHashMap<>();

    /** 物品 —— {@code "type": "item"}。 */
    public static final IContentKind ITEM = register(new ItemContentKind());

    /** 流体 —— {@code "type": "fluid"}。 */
    public static final IContentKind FLUID = register(new FluidContentKind());

    /** 认不出名字时的兜底（物品最容易出对，和以前的 {@code Kind.byName} 行为一致）。 */
    public static final IContentKind DEFAULT = ITEM;

    private ContentKinds() {}

    /**
     * 注册一种内容种类。
     *
     * <p>
     * id 已被占用时抛 {@link IllegalStateException} —— 静默覆盖会让「我注册的明明是自己那种内容，
     * 结果读出来是别人的」这种问题拖到很久以后才暴露。确实要换掉请用 {@link #replace(IContentKind)}。
     *
     * @return 传进来的 {@code kind}，方便写成 {@code public static final IContentKind X = register(new ...)}
     */
    public static synchronized <T extends IContentKind> T register(T kind) {
        if (kind == null) throw new IllegalArgumentException("content kind must not be null");
        String id = normalize(kind.getId());
        if (id.isEmpty()) throw new IllegalArgumentException("content kind id must not be blank");
        if (kind.codec() == null) {
            throw new IllegalArgumentException("content kind '" + id + "' must provide a non-null codec");
        }
        IContentKind existing = BY_ID.get(id);
        if (existing != null && existing != kind) {
            throw new IllegalStateException("Content kind id '" + id + "' is already registered by "
                    + existing.getClass().getSimpleName() + "; use replace() if you really mean to override it");
        }
        BY_ID.put(id, kind);
        return kind;
    }

    /** 显式替换一个已注册的 id（整合包魔改、测试用）。 */
    public static synchronized <T extends IContentKind> T replace(T kind) {
        if (kind == null) throw new IllegalArgumentException("content kind must not be null");
        BY_ID.put(normalize(kind.getId()), kind);
        return kind;
    }

    /** 按 id 精确查（大小写不敏感）；查不到返回 {@code null}。 */
    @Nullable
    public static IContentKind get(String id) {
        return BY_ID.get(normalize(id));
    }

    /**
     * 按 id 查，<b>认不出时返回 {@link #DEFAULT}</b>而不抛异常 —— 适合「配置里写了个名字」这种
     * 不该让机器加载崩掉的场合。配方 JSON 的 {@code type} 请走 {@link #codecFor(String)}：
     * 那里认不出会明确报错，而不是把一条魔力的配方当成物品读。
     */
    public static IContentKind byName(@Nullable String name) {
        IContentKind kind = name == null ? null : BY_ID.get(normalize(name));
        if (kind == null && name != null) {
            Ogmr.LOGGER.warn("ogmr: unknown content kind '{}', falling back to {}", name, DEFAULT.getId());
        }
        return kind != null ? kind : DEFAULT;
    }

    /** 是否已经注册过这个 id。 */
    public static boolean contains(String id) {
        return BY_ID.containsKey(normalize(id));
    }

    /** 全部已注册的内容种类（注册顺序，内置两种在最前面）。 */
    public static synchronized Collection<IContentKind> all() {
        return List.copyOf(BY_ID.values());
    }

    /** 已经注册的数量。 */
    public static synchronized int size() {
        return BY_ID.size();
    }

    /**
     * 取某种内容的 JSON codec —— 给 {@link Content#CODEC} 的 {@code "type"} 分派用。
     *
     * <p>
     * 认不出的名字返回一个<b>必定失败</b>的 codec（而不是回落到物品），这样配方里写错一个
     * {@code type} 会直接报「unknown content type 'xxx', expected one of [...]」，
     * 而不是安静地解析成一条空配方。
     */
    public static Codec<Content> codecFor(String id) {
        IContentKind kind = BY_ID.get(normalize(id));
        if (kind != null) return kind.codec();
        return Codec.STRING.comapFlatMap(
                name -> DataResult.error(() -> "ogmr: unknown content type '%s', expected one of %s"
                        .formatted(name, ids())),
                content -> content.kind().getId());
    }

    /** 全部 id（报错信息与调试用）。 */
    public static List<String> ids() {
        return List.copyOf(BY_ID.keySet());
    }

    /** 把所有已注册内容种类的双语名登记进语言表；数据生成之前调用即可（幂等）。 */
    public static void initLang() {
        for (IContentKind kind : all()) {
            kind.registerLang();
        }
    }

    /** 调试用：把当前注册表打出来。 */
    public static List<String> describeAll() {
        return all().stream()
                .map(kind -> "%s (%s)".formatted(kind.getId(), kind.getDisplayName()))
                .toList();
    }

    private static String normalize(String name) {
        return name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
    }
}
