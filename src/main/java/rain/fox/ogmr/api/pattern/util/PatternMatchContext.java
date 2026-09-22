package rain.fox.ogmr.api.pattern.util;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * 结构匹配过程中的临时数据容器。
 *
 * <p>
 * 从 GTM 的 {@code com.gregtechceu.gtceu.api.pattern.util.PatternMatchContext} 原样拆出：
 * 一次 {@code checkPatternAt} 期间供所有 predicate 共享的 key→value 袋子
 * （例如 "CoilType"、"FilterType"、"parts"、"ioMap"、"renderMask" 等键）。
 *
 * <p>
 * 本文件没有任何 GT 依赖，逻辑与 GTM 完全一致（只有类注释与 import 顺序是本库的）。
 */
public class PatternMatchContext {

    private final Map<String, Object> data = new HashMap<>();

    /** 清空上下文（每次重新匹配结构时调用）。 */
    public void reset() {
        this.data.clear();
    }

    public void set(String key, Object value) {
        this.data.put(key, value);
    }

    public int getInt(String key) {
        return data.containsKey(key) ? (int) data.get(key) : 0;
    }

    public void increment(String key, int value) {
        set(key, getOrDefault(key, 0) + value);
    }

    @SuppressWarnings("unchecked")
    public <T> T getOrDefault(String key, T defaultValue) {
        return (T) data.getOrDefault(key, defaultValue);
    }

    @SuppressWarnings("unchecked")
    public <T> T get(String key) {
        return (T) data.get(key);
    }

    /** 取 key 对应的值，不存在时用 creator 造一个并存进去。 */
    public <T> T getOrCreate(String key, Supplier<T> creator) {
        T result = get(key);
        if (result == null) {
            result = creator.get();
            set(key, result);
        }
        return result;
    }

    /** 取 key 对应的值，不存在时存下 initialValue 并返回它。 */
    public <T> T getOrPut(String key, T initialValue) {
        T result = get(key);
        if (result == null) {
            result = initialValue;
            set(key, result);
        }
        return result;
    }

    public boolean containsKey(String key) {
        return data.containsKey(key);
    }

    public Set<Map.Entry<String, Object>> entrySet() {
        return data.entrySet();
    }
}
