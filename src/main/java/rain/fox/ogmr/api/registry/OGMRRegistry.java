package rain.fox.ogmr.api.registry;

import rain.fox.ogmr.Ogmr;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.fml.ModContainer;
import net.minecraftforge.fml.ModLoadingContext;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;

import lombok.Getter;

import java.util.*;

/**
 * 一套「非 Forge 注册表」的轻量 K/V 注册表。
 *
 * <p>
 * 这是从 GregTech Modern 的 {@code GTRegistry} 拆出来并去 GT 化的版本：GTM 的多方块、仓室、配方类型
 * 都不是 Forge 官方注册表里的对象（它们是「定义对象」，方块/物品才是 Forge 注册项），
 * 所以它自带一层注册表，负责：
 * <ul>
 * <li>按 key（{@link ResourceLocation} 或 String）索引定义对象；</li>
 * <li>登录 / 冻结（{@code freeze}）语义 —— 冻结后不再接受注册，保证注册阶段可判定；</li>
 * <li>序列化三件套：{@link #writeBuf} / {@link #saveToNBT} / {@link #codec}，供网络与 JSON 使用。</li>
 * </ul>
 *
 * <p>
 * 与 GTM 原版的差别：冻结校验不再依赖 GTCEu 的 modId，而是校验「当前正在加载的 mod」是否
 * 拥有该注册表命名空间（或本库）。这样任何 addon 都能安全地冻结自己的注册表。
 *
 * @param <K> 键类型（{@link ResourceLocation} 或 {@link String}）
 * @param <V> 值类型（各种 Definition）
 */
public abstract class OGMRRegistry<K, V> implements Iterable<V> {

    /** 所有已创建的注册表，按注册表名索引。 */
    public static final Map<ResourceLocation, OGMRRegistry<?, ?>> REGISTERED = new LinkedHashMap<>();

    protected final Map<K, V> keyToValue;
    protected final Map<V, K> valueToKey;
    @Getter
    protected final ResourceLocation registryName;
    /**
     * 是否已冻结。
     *
     * <p>
     * ⚠️ 初值是 <b>false（开放）</b>：注册表刚建出来时必须能收注册 —— addon 的注册阶段
     * （{@code IOGMRAddon#registerPartAbilities/registerMachines/...}）就发生在构造期，
     * 跑完由 {@link OGMRRegistries#freezeAll()} 统一冻结。
     * （GTM 的 {@code GTRegistry} 初值是 {@code true}，但它有配套的 unfreeze 调用；
     * 早期这里照抄了初值却漏了解冻，导致**任何注册都抛异常**、
     * {@code TestRegistrations} 静态初始化直接失败 —— 只在跑游戏时才会暴露。）
     */
    @Getter
    protected boolean frozen = false;
    /** true = 共享注册表（本库自带的那些）：任何 mod 都可以注册/冻结，不做命名空间归属校验。 */
    @Getter
    protected final boolean shared;

    public OGMRRegistry(ResourceLocation registryName) {
        this(registryName, true);
    }

    public OGMRRegistry(ResourceLocation registryName, boolean shared) {
        this.keyToValue = new LinkedHashMap<>();
        this.valueToKey = new IdentityHashMap<>();
        this.registryName = registryName;
        this.shared = shared;
        REGISTERED.put(registryName, this);
    }

    public boolean containKey(K key) {
        return keyToValue.containsKey(key);
    }

    public boolean containValue(V value) {
        return valueToKey.containsKey(value);
    }

    /** 冻结：之后 {@link #register} / {@link #registerOrOverride} 都会抛异常。 */
    public void freeze() {
        if (frozen) throw new IllegalStateException("Registry %s is already frozen!".formatted(registryName));
        if (!checkActiveModContainer()) return;
        this.frozen = true;
    }

    /** 解冻：只允许注册表的拥有者（或本库）调用。 */
    public void unfreeze() {
        if (!frozen) throw new IllegalStateException("Registry %s is already unfrozen!".formatted(registryName));
        if (!checkActiveModContainer()) return;
        this.frozen = false;
    }

    /**
     * 校验「当前正在加载的 mod」是否有权改这个注册表 —— 防止别人的 addon 在自己的阶段
     * 往你的注册表里塞东西。
     */
    private boolean checkActiveModContainer() {
        // 本库自带的共享注册表（MACHINES / RECIPE_TYPES / ...）对所有 addon 开放
        if (shared) return true;
        ModContainer container = ModLoadingContext.get().getActiveContainer();
        if (container == null) return true;
        // ⚠️ 这里必须写全限定名：本类的嵌套类 String<V> 会遮蔽 java.lang.String
        //    （JLS 6.5.5.1 成员类型遮蔽），写 String 会解析到嵌套类型导致编译失败。
        java.lang.String modId = container.getModId();
        return modId.equals(registryName.getNamespace()) || modId.equals(Ogmr.MOD_ID) || modId.equals("minecraft");
    }

    public <T extends V> T register(K key, T value) {
        if (keyToValue.containsKey(key)) {
            throw new IllegalStateException("[register] registry %s already contains key %s".formatted(registryName, key));
        }
        return registerOrOverride(key, value);
    }

    /** 把一个已存在的键改名（数据包迁移时用）。 */
    public void remap(K oldKey, K newKey) {
        if (frozen) throw new IllegalStateException("[remap] registry %s has been frozen".formatted(registryName));
        if (keyToValue.containsKey(oldKey)) {
            Ogmr.LOGGER.warn("[remap] cannot remap existing key {} in registry {}", oldKey, registryName);
            return;
        }
        if (!keyToValue.containsKey(newKey)) {
            Ogmr.LOGGER.warn("[remap] couldn't find value for key {} in registry {}", newKey, registryName);
            return;
        }
        keyToValue.put(oldKey, keyToValue.get(newKey));
    }

    /** 覆盖已有键；键不存在时只警告不报错。 */
    public <T extends V> T replace(K key, T value) {
        if (!containKey(key)) {
            Ogmr.LOGGER.warn("[replace] couldn't find key {} in registry {}", registryName, key);
        }
        return registerOrOverride(key, value);
    }

    public <T extends V> T registerOrOverride(K key, T value) {
        if (frozen) throw new IllegalStateException("[register] registry %s has been frozen".formatted(registryName));
        keyToValue.put(key, value);
        valueToKey.put(value, key);
        return value;
    }

    @Override
    public Iterator<V> iterator() {
        return values().iterator();
    }

    public Set<V> values() {
        return Collections.unmodifiableSet(new LinkedHashSet<>(keyToValue.values()));
    }

    public Set<K> keys() {
        return Collections.unmodifiableSet(keyToValue.keySet());
    }

    public Set<Map.Entry<K, V>> entries() {
        return Collections.unmodifiableSet(keyToValue.entrySet());
    }

    public Map<K, V> registry() {
        return Collections.unmodifiableMap(keyToValue);
    }

    public int size() {
        return keyToValue.size();
    }

    public void clear() {
        if (frozen) throw new IllegalStateException("Registry %s is frozen!".formatted(registryName));
        keyToValue.clear();
        valueToKey.clear();
    }

    public V get(K key) {
        return keyToValue.get(key);
    }

    public V getOrDefault(K key, V defaultValue) {
        return keyToValue.getOrDefault(key, defaultValue);
    }

    /** 取键；值不在表里时返回 null。 */
    public K getKey(V value) {
        return valueToKey.get(value);
    }

    public K getOrDefaultKey(V value, K defaultKey) {
        K key = valueToKey.get(value);
        return key != null ? key : defaultKey;
    }

    public boolean remove(K name) {
        V value = keyToValue.remove(name);
        if (value != null) {
            valueToKey.remove(value);
            return true;
        }
        return false;
    }

    // ─────────────── 序列化契约 ───────────────

    public abstract void writeBuf(V value, FriendlyByteBuf buf);

    public abstract V readBuf(FriendlyByteBuf buf);

    public abstract Tag saveToNBT(V value);

    public abstract V loadFromNBT(Tag tag);

    public abstract Codec<V> codec();

    // ═══════════════════════ 内置实现 ═══════════════════════

    /** key 为 {@link String} 的注册表。 */
    public static class String<V> extends OGMRRegistry<java.lang.String, V> {

        public String(ResourceLocation registryName) {
            super(registryName);
        }

        @Override
        public void writeBuf(V value, FriendlyByteBuf buf) {
            buf.writeBoolean(containValue(value));
            if (containValue(value)) buf.writeUtf(getKey(value));
        }

        @Override
        public V readBuf(FriendlyByteBuf buf) {
            return buf.readBoolean() ? get(buf.readUtf()) : null;
        }

        @Override
        public Tag saveToNBT(V value) {
            return containValue(value) ? StringTag.valueOf(getKey(value)) : new CompoundTag();
        }

        @Override
        public V loadFromNBT(Tag tag) {
            return get(tag.getAsString());
        }

        @Override
        public Codec<V> codec() {
            return Codec.STRING.flatXmap(
                    key -> Optional.ofNullable(get(key)).map(DataResult::success)
                            .orElseGet(() -> DataResult.error(() -> "Unknown key in %s: %s".formatted(registryName, key))),
                    val -> Optional.ofNullable(getKey(val)).map(DataResult::success)
                            .orElseGet(() -> DataResult.error(() -> "Unknown value in %s: %s".formatted(registryName, val))));
        }
    }

    /** key 为 {@link ResourceLocation} 的注册表。 */
    public static class RL<V> extends OGMRRegistry<ResourceLocation, V> {

        public RL(ResourceLocation registryName) {
            super(registryName);
        }

        @Override
        public void writeBuf(V value, FriendlyByteBuf buf) {
            buf.writeBoolean(containValue(value));
            if (containValue(value)) buf.writeUtf(getKey(value).toString());
        }

        @Override
        public V readBuf(FriendlyByteBuf buf) {
            // 网络来的字符串是不可信输入：解析失败当作「没有」处理，不要抛异常把连接打断
            if (!buf.readBoolean()) return null;
            return get(rain.fox.ogmr.utils.ResourceLocations.tryParse(buf.readUtf()));
        }

        @Override
        public Tag saveToNBT(V value) {
            return containValue(value) ? StringTag.valueOf(getKey(value).toString()) : new CompoundTag();
        }

        @Override
        public V loadFromNBT(Tag tag) {
            // 存档里的字符串同理：旧存档/手改过的数据不该让机器加载崩掉
            return get(rain.fox.ogmr.utils.ResourceLocations.tryParse(tag.getAsString()));
        }

        @Override
        public Codec<V> codec() {
            return ResourceLocation.CODEC.flatXmap(
                    key -> Optional.ofNullable(get(key)).map(DataResult::success)
                            .orElseGet(() -> DataResult.error(() -> "Unknown key in %s: %s".formatted(registryName, key))),
                    val -> Optional.ofNullable(getKey(val)).map(DataResult::success)
                            .orElseGet(() -> DataResult.error(() -> "Unknown value in %s: %s".formatted(registryName, val))));
        }
    }
}
