package rain.fox.ogmr.api.addon;

import rain.fox.ogmr.Ogmr;

import net.minecraftforge.fml.ModList;
import net.minecraftforge.forgespi.language.ModFileScanData;

import org.objectweb.asm.Type;

import java.lang.reflect.Constructor;
import java.util.*;

/**
 * {@code @OGMRAddon} 扫描器。
 *
 * <p>
 * 与 GTM 的 {@code AddonFinder} 同思路：不靠 ServiceLoader、也不靠 forge 的 {@code mods.toml}
 * 声明，而是直接读 Forge 已经解析好的注解扫描数据（{@link ModFileScanData}），
 * 找出所有标了 {@link OGMRAddon} 的类并实例化。
 *
 * <p>
 * 好处是 addon 侧零配置：只要类上有注解、有无参构造，就会被发现。
 */
public final class AddonFinder {

    private AddonFinder() {}

    private static List<IOGMRAddon> cache = null;

    /** 全部 addon（按 {@link IOGMRAddon#priority()} 升序，同优先级按类名排序保证确定性）。 */
    public static List<IOGMRAddon> getAddons() {
        if (cache == null) {
            List<IOGMRAddon> addons = new ArrayList<>(getInstances(OGMRAddon.class, IOGMRAddon.class));
            addons.sort(Comparator.<IOGMRAddon>comparingInt(IOGMRAddon::priority)
                    .thenComparing(a -> a.getClass().getName()));
            // 同名 modId 冲突时保留先出现的（优先级高的那个）
            Map<String, IOGMRAddon> byModId = new LinkedHashMap<>();
            for (IOGMRAddon addon : addons) {
                IOGMRAddon previous = byModId.putIfAbsent(addon.addonModId(), addon);
                if (previous != null) {
                    Ogmr.LOGGER.error("Duplicate ogmr addon for mod id '{}': {} is ignored, keeping {}",
                            addon.addonModId(), addon.getClass().getName(), previous.getClass().getName());
                }
            }
            cache = List.copyOf(byModId.values());
        }
        return cache;
    }

    /** 按 mod id 取 addon。 */
    public static IOGMRAddon getAddon(String modId) {
        for (IOGMRAddon addon : getAddons()) {
            if (addon.addonModId().equals(modId)) return addon;
        }
        return null;
    }

    /** 清空缓存（仅调试/重载用）。 */
    public static void invalidate() {
        cache = null;
    }

    @SuppressWarnings("SameParameterValue")
    private static <T> List<T> getInstances(Class<?> annotationClass, Class<T> instanceClass) {
        Type annotationType = Type.getType(annotationClass);
        Set<String> classNames = new LinkedHashSet<>();
        for (ModFileScanData scanData : ModList.get().getAllScanData()) {
            for (ModFileScanData.AnnotationData data : scanData.getAnnotations()) {
                if (Objects.equals(data.annotationType(), annotationType)) {
                    classNames.add(data.memberName());
                }
            }
        }

        List<T> instances = new ArrayList<>();
        for (String className : classNames) {
            try {
                Class<?> asmClass = Class.forName(className);
                Class<? extends T> impl = asmClass.asSubclass(instanceClass);
                Constructor<? extends T> ctor = impl.getDeclaredConstructor();
                ctor.setAccessible(true);
                instances.add(ctor.newInstance());
            } catch (ReflectiveOperationException | LinkageError e) {
                Ogmr.LOGGER.error("Failed to load ogmr addon: {}", className, e);
            }
        }
        return instances;
    }
}
