package rain.fox.ogmr.api.recipe.content;

import net.minecraft.util.StringRepresentable;

import org.jetbrains.annotations.Nullable;

import java.util.Locale;
import java.util.Objects;

/**
 * {@link IContentKind} 的抽象基类 —— 把「id / 双语名」这些样板收进来。
 *
 * <p>
 * 与 {@code AbstractEnergyType} 一样，本类<b>不自动注册</b>：构造完不会偷偷进
 * {@link ContentKinds}，必须显式 {@code ContentKinds.register(...)}。
 * 「声明一个常量」和「让它全局可见」是两件事，自动注册会让类加载顺序变成隐形依赖。
 *
 * <p>
 * 顺带实现 {@link StringRepresentable}，这样 {@code StringRepresentable.fromEnum(...)} 之外的
 * 场景（例如写 JSON 时直接拿名字）也能直接用。
 */
public abstract class AbstractContentKind implements IContentKind, StringRepresentable {

    private final String id;
    private final String englishName;
    @Nullable
    private final String chineseName;

    /**
     * @param id          序列化名（会被规整成小写）
     * @param englishName 英文展示名
     * @param chineseName 中文展示名；传 {@code null} 回退英文
     */
    protected AbstractContentKind(String id, String englishName, @Nullable String chineseName) {
        this.id = Objects.requireNonNull(id, "content kind id").trim().toLowerCase(Locale.ROOT);
        if (this.id.isEmpty()) {
            throw new IllegalArgumentException("content kind id must not be blank");
        }
        this.englishName = Objects.requireNonNull(englishName, "content kind english name");
        this.chineseName = chineseName;
    }

    @Override
    public String getId() {
        return id;
    }

    /** {@link StringRepresentable} 的契约方法，值就是 {@link #getId()}。 */
    @Override
    public String getSerializedName() {
        return id;
    }

    @Override
    public String getDisplayName() {
        return englishName;
    }

    @Override
    @Nullable
    public String getChineseName() {
        return chineseName;
    }

    /** 日志/调试输出用 id，避免打出对象哈希。 */
    @Override
    public String toString() {
        return id;
    }
}
