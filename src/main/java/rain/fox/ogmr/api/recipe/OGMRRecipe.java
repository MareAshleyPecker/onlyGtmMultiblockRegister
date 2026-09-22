package rain.fox.ogmr.api.recipe;

import rain.fox.ogmr.api.OGMRValues;
import rain.fox.ogmr.api.recipe.content.ContentKinds;
import rain.fox.ogmr.api.recipe.content.IContentKind;

import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;

import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.Level;

import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * 一条「格雷式」配方：内容化的输入/输出 + 时长 + 电压。
 *
 * <p>
 * 从 GTM 的 {@code api.recipe.GTRecipe} 精简拆出来。GTM 的 GTRecipe 有 4 张
 * capability→Content 映射（输入/输出/每 tick 输入/每 tick 输出）、概率逻辑、条件、研究、
 * 并行数、配方分类……本库只保留机器真正跑起来必需的那几个：
 * <ul>
 * <li>{@link #inputs} / {@link #outputs} —— 两张 {@link Content} 列表（{@link Content#kind()} 区分物品/流体）；</li>
 * <li>{@link #duration} —— 加工时长（tick）；</li>
 * <li>{@link #eut} —— 每 tick 的 EU；<b>负数表示发电配方</b>（产能），0 表示不耗电；</li>
 * <li>{@link #tier} —— 电压档位，默认由 {@code |eut|} 反查（{@link OGMRValues#getTierByVoltage(long)}）。</li>
 * </ul>
 *
 * <p>
 * 因为 {@link DummyInput} 永远不匹配（{@link #matches} 恒 false），这些配方不可能被原版合成台
 * 触发；它们是给机器的 {@code RecipeLogic} 用的。原版 {@code RecipeManager} 只负责
 * 按 {@code "type"} 字段反序列化 + 同步，本类必须能进它，所以仍然实现 {@link Recipe}。
 */
@Accessors(chain = true)
public class OGMRRecipe implements Recipe<OGMRRecipe.DummyInput> {

    /** 默认加工时长（tick）。 */
    public static final int DEFAULT_DURATION = 200;

    /**
     * 配方 id（数据包路径决定；JSON 里不写，由反序列化时注入）。
     *
     * <p>
     * 反序列化时由序列化器换一个 id；构造期与拷贝时也允许为 {@code null}
     * （{@link #equals}/{@link #hashCode} 都按 null 安全处理）。
     */
    @Nullable
    @Getter
    @Setter
    protected ResourceLocation id;
    @Getter
    protected final OGMRRecipeType recipeType;
    @Getter
    protected final List<Content> inputs;
    @Getter
    protected final List<Content> outputs;
    @Getter
    protected int duration;
    @Getter
    protected long eut;
    @Getter
    protected int tier;

    public OGMRRecipe(OGMRRecipeType recipeType, @Nullable ResourceLocation id, List<Content> inputs,
                      List<Content> outputs, int duration, long eut) {
        this(recipeType, id, inputs, outputs, duration, eut, tierFromEut(eut));
    }

    public OGMRRecipe(OGMRRecipeType recipeType, @Nullable ResourceLocation id, List<Content> inputs,
                      List<Content> outputs, int duration, long eut, int tier) {
        this.recipeType = Objects.requireNonNull(recipeType, "recipeType");
        this.id = id;
        this.inputs = Collections.unmodifiableList(new ArrayList<>(inputs));
        this.outputs = Collections.unmodifiableList(new ArrayList<>(outputs));
        this.duration = Math.max(1, duration);
        this.eut = eut;
        this.tier = OGMRValues.clampTier(tier);
    }

    /** 由 EU/t 反查电压档位（取绝对值，发电配方也一样）。 */
    public static int tierFromEut(long eut) {
        return OGMRValues.getTierByVoltage(Math.abs(eut));
    }

    // ═══════════════ 访问器 ═══════════════

    /** 是否为发电配方（eut < 0：加工时产能而不是耗能）。 */
    public boolean isGenerator() {
        return eut < 0;
    }

    /** 是否需要耗电（eut > 0）。 */
    public boolean isConsumer() {
        return eut > 0;
    }

    /** 这条配方一次运行的总 EU（负数 = 总发电量）。 */
    public long getTotalEU() {
        return eut * (long) duration;
    }

    /** 输入里的物品内容（顺序与 {@link #getInputs()} 一致）。 */
    public List<Content> getItemInputs() {
        return filter(ContentKinds.ITEM, inputs);
    }

    public List<Content> getItemOutputs() {
        return filter(ContentKinds.ITEM, outputs);
    }

    public List<Content> getFluidInputs() {
        return filter(ContentKinds.FLUID, inputs);
    }

    public List<Content> getFluidOutputs() {
        return filter(ContentKinds.FLUID, outputs);
    }

    /** 按内容种类过滤（第三方种类也能用这个筛自己的内容）。 */
    public static List<Content> filter(IContentKind kind, List<Content> contents) {
        List<Content> result = new ArrayList<>();
        for (Content content : contents) {
            if (content.kind() == kind) result.add(content);
        }
        return Collections.unmodifiableList(result);
    }

    /** 拷贝（id 也一起拷）。{@link Content} 本身不可变，因此这里只需拷两张列表。 */
    public OGMRRecipe copy() {
        return copy(id);
    }

    /** 拷贝并换一个 id（并行/超频派生配方时用）。 */
    public OGMRRecipe copy(@Nullable ResourceLocation newId) {
        return new OGMRRecipe(recipeType, newId, inputs, outputs, duration, eut, tier);
    }

    public OGMRRecipe setDuration(int newDuration) {
        this.duration = Math.max(1, newDuration);
        return this;
    }

    public OGMRRecipe setEut(long newEut) {
        this.eut = newEut;
        this.tier = tierFromEut(newEut);
        return this;
    }

    public OGMRRecipe setTier(int newTier) {
        this.tier = OGMRValues.clampTier(newTier);
        return this;
    }

    // ═══════════════ Recipe 契约 ═══════════════

    @Override
    public boolean matches(DummyInput container, Level level) {
        return false;
    }

    @Override
    public ItemStack assemble(DummyInput container, RegistryAccess registryAccess) {
        return ItemStack.EMPTY;
    }

    @Override
    public boolean canCraftInDimensions(int width, int height) {
        return true;
    }

    /** 第一个物品输出（给 UI/JEI 展示用）；没有物品输出时返回空。 */
    @Override
    public ItemStack getResultItem(RegistryAccess registryAccess) {
        for (Content content : outputs) {
            if (content.kind() == ContentKinds.ITEM) {
                ItemStack stack = content.representativeItem();
                if (!stack.isEmpty()) return stack;
            }
        }
        return ItemStack.EMPTY;
    }

    @Override
    public RecipeSerializer<?> getSerializer() {
        return OGMRRecipeSerializer.SERIALIZER;
    }

    @Override
    public RecipeType<?> getType() {
        return recipeType;
    }

    /** 与 GTM 一致：配方只按 id 判等（同 id 只应存在一个实例）。 */
    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof OGMRRecipe other)) return false;
        return id != null && id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return id == null ? 0 : id.hashCode();
    }

    @Override
    public String toString() {
        return "OGMRRecipe[%s, %s, in=%d, out=%d, %dt, %dEU/t, tier=%s]".formatted(
                id, recipeType.registryName, inputs.size(), outputs.size(), duration, eut,
                OGMRValues.tierNameRaw(tier));
    }

    /**
     * 占位容器 —— GTM 的 {@code GTRecipe} 直接实现 {@code Recipe<Container>} 并在
     * {@link #matches} 里恒返回 false；本库用这个空实现的 {@link Container} 把泛型钉死，
     * 免得 {@code Recipe<Container>} 这种「宽泛但没意义」的类型出现在公开签名里。
     */
    public static class DummyInput implements Container {

        @Override
        public int getContainerSize() {
            return 0;
        }

        @Override
        public boolean isEmpty() {
            return true;
        }

        @Override
        public ItemStack getItem(int slot) {
            return ItemStack.EMPTY;
        }

        @Override
        public ItemStack removeItem(int slot, int amount) {
            return ItemStack.EMPTY;
        }

        @Override
        public ItemStack removeItemNoUpdate(int slot) {
            return ItemStack.EMPTY;
        }

        @Override
        public void setItem(int slot, ItemStack stack) {}

        @Override
        public void setChanged() {}

        @Override
        public boolean stillValid(Player player) {
            return false;
        }

        @Override
        public void clearContent() {}
    }
}
