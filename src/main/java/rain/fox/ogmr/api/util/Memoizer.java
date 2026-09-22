package rain.fox.ogmr.api.util;

import java.util.Objects;
import java.util.function.Supplier;

/**
 * 极简的「记忆化（memoize）」工具。
 *
 * <p>
 * 从 GTM 的 {@code com.gregtechceu.gtceu.utils.memoization.GTMemoizer} 拆出来的最小子集：
 * 只保留 {@code static <T> Supplier<T> memoize(Supplier<T>)}，把一个 Supplier 包成
 * 「第一次取值后把结果缓存下来」的 Supplier。
 *
 * <p>
 * 与 GTM 版本的差异：
 * <ul>
 * <li>去掉了 Guava 的 {@code Suppliers.memoize} / {@code Suppliers.memoizeWithExpiration} 依赖，
 * 内部改用 {@code volatile} 字段 + 双检锁自己实现，任何线程安全场景都够用；</li>
 * <li>去掉了 {@code memoize(Function)}、带过期时间的重载与 {@code MemoizedSupplier} 包装类。</li>
 * </ul>
 *
 * <p>
 * 注意：本实现不缓存 {@code null}（{@code null} 结果每次都会重新调用 delegate），
 * 这与 Guava 的行为一致，调用方不要用 null 表达「已计算」。
 */
public final class Memoizer {

    private Memoizer() {}

    /**
     * 把 delegate 包成「只求值一次」的 Supplier。
     *
     * @param delegate 真正的取值逻辑，不能为 null
     * @param <T>      值类型
     * @return 线程安全的记忆化 Supplier
     */
    public static <T> Supplier<T> memoize(Supplier<T> delegate) {
        Objects.requireNonNull(delegate, "delegate");
        return new Supplier<T>() {

            private volatile T value;

            @Override
            public T get() {
                T result = value;
                if (result == null) {
                    synchronized (this) {
                        result = value;
                        if (result == null) {
                            result = delegate.get();
                            value = result;
                        }
                    }
                }
                return result;
            }
        };
    }
}
