package rain.fox.ogmr.api.registry;

import lombok.Getter;

import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.eventbus.api.GenericEvent;
import net.minecraftforge.fml.event.IModBusEvent;

/**
 * 注册阶段事件 —— addon 在这里往注册表里写定义对象。
 *
 * <p>
 * 拆自 GTM 的 {@code GTCEuAPI.RegisterEvent}。它在 <b>mod 事件总线</b> 上以泛型事件的形式派发，
 * 所以监听者可以只关心自己那一类：{@code addGenericListener(MachineDefinition.class, ...)}。
 *
 * <p>
 * 相比 Forge 官方的 {@code RegisterEvent}，这里的「注册表」是本库自己的
 * {@link OGMRRegistry}（定义对象不是 Forge 注册项），因此需要单独一套事件。
 */
public class OGMRRegisterEvent<K, V> extends GenericEvent<V> implements IModBusEvent {

    @Getter
    protected final OGMRRegistry<K, V> registry;

    protected OGMRRegisterEvent(OGMRRegistry<K, V> registry, Class<V> type) {
        super(type);
        this.registry = registry;
    }

    /**
     * 注册一个定义对象。
     *
     * @param key   注册名
     * @param value 定义对象
     */
    public void register(K key, V value) {
        if (registry != null) registry.register(key, value);
    }

    /** 覆盖注册（同名定义已存在时用）。 */
    public void registerOrOverride(K key, V value) {
        if (registry != null) registry.registerOrOverride(key, value);
    }

    /** key 为 {@link ResourceLocation} 的注册事件。 */
    public static class RL<V> extends OGMRRegisterEvent<ResourceLocation, V> {

        public RL(OGMRRegistry.RL<V> registry, Class<V> type) {
            super(registry, type);
        }

        @Override
        public OGMRRegistry.RL<V> getRegistry() {
            return (OGMRRegistry.RL<V>) registry;
        }
    }

    /** key 为 {@link String} 的注册事件。 */
    public static class String<V> extends OGMRRegisterEvent<java.lang.String, V> {

        public String(OGMRRegistry.String<V> registry, Class<V> type) {
            super(registry, type);
        }

        @Override
        public OGMRRegistry.String<V> getRegistry() {
            return (OGMRRegistry.String<V>) registry;
        }
    }
}
