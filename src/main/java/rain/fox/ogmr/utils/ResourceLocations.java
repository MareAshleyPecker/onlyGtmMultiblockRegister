package rain.fox.ogmr.utils;

import rain.fox.ogmr.Ogmr;

import net.minecraft.resources.ResourceLocation;

import org.jetbrains.annotations.Nullable;

import java.util.Optional;

/**
 * {@link ResourceLocation} 的统一入口。
 *
 * <p>
 * 为什么不在各处直接写 {@code new ResourceLocation(...)}：
 * <ul>
 * <li><b>从外部数据解析时不该抛异常。</b>NBT / 网络 / 配置文件里的字符串是「不可信输入」，
 * 一个脏字符串（旧存档、手改配置、别的 mod 写错）不应该让机器加载崩掉。
 * {@link #tryParse(String)} 把这类调用收敛成「解析失败返回 null」，
 * 而这些地方本来就必须处理 null（查表查不到也是 null）；</li>
 * <li><b>拼接的意图一眼可见。</b>{@code new ResourceLocation(id.getNamespace(), "part/" + id.getPath())}
 * 读起来要在脑子里拆一遍；{@link #withPath(ResourceLocation, String)} 直接说明「换路径、保命名空间」；</li>
 * <li><b>默认命名空间策略只有一处。</b>{@link #of(String)} 里「没有冒号就补 {@code minecraft}」
 * 是显式规则，而不是各处各写一遍；</li>
 * <li><b>以后要改只改这里。</b>比如加规范化（小写化）、加缓存、加日志埋点，
 * 不需要去 8 个文件里逐个找。</li>
 * </ul>
 *
 * <p>
 * 本库自己的命名空间用 {@link Ogmr#id(String)}（等价于 {@link #ogmr(String)}）。
 */
public final class ResourceLocations {

    private ResourceLocations() {}

    // ═══════════════ 构造 ═══════════════

    /**
     * 按「命名空间 + 路径」构造，等价于 {@code new ResourceLocation(namespace, path)}。
     *
     * <p>
     * 命名空间与路径本身非法时仍然抛 {@link net.minecraft.ResourceLocationException} ——
     * 这两个参数是代码里写死的常量，写错了属于程序 bug，应该立刻炸而不是悄悄变成别的 id。
     */
    public static ResourceLocation of(String namespace, String path) {
        return new ResourceLocation(namespace, path);
    }

    /**
     * 解析 {@code "namespace:path"}，没有冒号时按 {@code minecraft:path} 处理。
     *
     * <p>
     * ⚠️ 这个是「宽松解析」：{@code "minecraft:stone"} 与 {@code "stone"} 等价。
     * 用于内部常量拼接；解析外部数据请用 {@link #tryParse(String)}。
     */
    public static ResourceLocation of(String namespaceOrColonPath) {
        return new ResourceLocation(namespaceOrColonPath);
    }

    /** 本库命名空间下的 id，等价于 {@link Ogmr#id(String)}。 */
    public static ResourceLocation ogmr(String path) {
        return new ResourceLocation(Ogmr.MOD_ID, path);
    }

    // ═══════════════ 安全解析（外部数据用这个） ═══════════════

    /**
     * 解析 {@code "namespace:path"}，<b>非法时返回 {@code null} 而不是抛异常</b>。
     *
     * <p>
     * NBT、网络包、配置文件里读出来的字符串都用它。调用方本来就该处理「查不到」的情况
     * （注册表里查不到也是 null），所以多一个 null 不会增加负担；
     * 反过来，让一个脏字符串把机器/存档加载炸掉则是实打实的线上事故。
     */
    @Nullable
    public static ResourceLocation tryParse(@Nullable String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return new ResourceLocation(raw);
        } catch (RuntimeException invalid) {
            // ResourceLocationException 是 RuntimeException 的子类；这里连它的兄弟异常一起兜住，
            // 因为解析失败的原因可能是路径含非法字符、命名空间为空、长度超限等等
            Ogmr.LOGGER.debug("ogmr: 忽略非法的 ResourceLocation 字符串 [{}]", raw);
            return null;
        }
    }

    /** {@link #tryParse(String)} 的 Optional 版本（想写流式代码时用）。 */
    public static Optional<ResourceLocation> parse(@Nullable String raw) {
        return Optional.ofNullable(tryParse(raw));
    }

    /** 这个字符串是不是合法的 ResourceLocation。 */
    public static boolean isValid(@Nullable String raw) {
        return tryParse(raw) != null;
    }

    // ═══════════════ 派生 ═══════════════

    /**
     * 保留命名空间、只换路径。
     *
     * <p>
     * 典型用法：{@code withPath(id, "part/" + id.getPath())}、{@code withPath(uiPath, "ui/machine/x.mui")}。
     */
    public static ResourceLocation withPath(ResourceLocation base, String newPath) {
        return new ResourceLocation(base.getNamespace(), newPath);
    }

    /** 保留路径、只换命名空间（跨命名空间复用同一套贴图/目录时用）。 */
    public static ResourceLocation withNamespace(ResourceLocation base, String newNamespace) {
        return new ResourceLocation(newNamespace, base.getPath());
    }

    /** 在路径前面加一段前缀：{@code prefix(base, "part/")}。 */
    public static ResourceLocation withPathPrefix(ResourceLocation base, String prefix) {
        return new ResourceLocation(base.getNamespace(), prefix + base.getPath());
    }

    /** 在路径后面加一段后缀：{@code suffix(base, "_ui")}。 */
    public static ResourceLocation withPathSuffix(ResourceLocation base, String suffix) {
        return new ResourceLocation(base.getNamespace(), base.getPath() + suffix);
    }

    /** 取 {@code "namespace:path"} 里的路径部分（语义化包装，省得到处 getPath()）。 */
    public static String pathOf(ResourceLocation id) {
        return id.getPath();
    }

    /** 取 {@code "namespace:path"} 里的命名空间部分。 */
    public static String namespaceOf(ResourceLocation id) {
        return id.getNamespace();
    }
}
