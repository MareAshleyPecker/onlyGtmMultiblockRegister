package rain.fox.ogmr.api.recipe;

import rain.fox.ogmr.Ogmr;

import net.minecraft.data.recipes.FinishedRecipe;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraftforge.fluids.FluidStack;

import com.google.gson.JsonObject;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * {@link OGMRRecipe} 的流式构建器 + 运行时配方表。
 *
 * <p>
 * 从 GTM 的 {@code data.recipe.builder.GTRecipeBuilder} 精简拆出来：GTM 的 builder 有
 * 上百个字段（4 张 capability 表、概率逻辑、条件、并行、材料信息……），本库只留
 * 「输入、输出、时长、电压」四件事，加上一个概率开关。
 *
 * <p>
 * <b>与 GTM 的一个关键差别</b>：GTM 的配方只活在数据包里（datagen 写出 JSON，游戏里由
 * {@code RecipeManager} 加载）；本库额外维护一张内存表 {@link #ALL_RECIPES}，
 * {@link #save(Consumer)} 时把配方同时塞进去，这样 {@code RecipeLogic} 可以不解包数据包直接查表
 * （机器逻辑简单、也方便写单测）。两者是同一份数据的两个视图，别只改一边。
 *
 * <p>
 * 用法：
 *
 * <pre>{@code
 * type.recipeBuilder(Ogmr.id("steel_ingot"))
 *         .input(Ingredient.of(Items.IRON_INGOT), 2)
 *         .input(new FluidStack(Fluids.WATER, 1000))
 *         .output(new ItemStack(MyItems.STEEL_INGOT))
 *         .duration(200)
 *         .eut(32)
 *         .save(consumer);
 * }</pre>
 */
public class RecipeBuilder {

    /**
     * 运行时配方表（id → 配方），插入顺序稳定（{@link LinkedHashMap}），
     * 用 {@link #byType(OGMRRecipeType)} 按配方类型取子表。
     */
    public static final Map<ResourceLocation, OGMRRecipe> ALL_RECIPES = new LinkedHashMap<>();

    private final OGMRRecipeType recipeType;
    private final ResourceLocation id;
    private final List<Content> inputs = new ArrayList<>();
    private final List<Content> outputs = new ArrayList<>();
    private int duration = OGMRRecipe.DEFAULT_DURATION;
    private long eut = 0L;
    /** -1 = 由 eut 反查。 */
    private int tier = OGMRRecipeSerializer.AUTO_TIER;
    /** 之后加入的内容使用的概率；{@link #chance(float)} 只影响调用它之后加入的内容。 */
    private float chance = 1f;

    public RecipeBuilder(OGMRRecipeType recipeType, ResourceLocation id) {
        this.recipeType = Objects.requireNonNull(recipeType, "recipeType");
        this.id = id;
    }

    // ═══════════════ 输入 ═══════════════

    public RecipeBuilder input(Content content) {
        if (content == null || content.isEmpty()) {
            Ogmr.LOGGER.warn("ogmr: ignoring empty input content on recipe {}", id);
            return this;
        }
        inputs.add(applyChance(content));
        return this;
    }

    public RecipeBuilder input(ItemStack stack) {
        return input(Content.item(stack));
    }

    public RecipeBuilder input(Ingredient ingredient) {
        return input(Content.item(ingredient));
    }

    public RecipeBuilder input(Ingredient ingredient, int count) {
        return input(Content.item(ingredient, count));
    }

    public RecipeBuilder input(TagKey<Item> tag) {
        return input(Content.item(tag));
    }

    public RecipeBuilder input(FluidStack stack) {
        return input(Content.fluid(stack));
    }

    public RecipeBuilder inputs(Content... contents) {
        for (Content content : contents) input(content);
        return this;
    }

    // ═══════════════ 输出 ═══════════════

    public RecipeBuilder output(Content content) {
        if (content == null || content.isEmpty()) {
            Ogmr.LOGGER.warn("ogmr: ignoring empty output content on recipe {}", id);
            return this;
        }
        outputs.add(applyChance(content));
        return this;
    }

    public RecipeBuilder output(ItemStack stack) {
        return output(Content.item(stack));
    }

    public RecipeBuilder output(Ingredient ingredient) {
        return output(Content.item(ingredient));
    }

    public RecipeBuilder output(Ingredient ingredient, int count) {
        return output(Content.item(ingredient, count));
    }

    public RecipeBuilder output(TagKey<Item> tag) {
        return output(Content.item(tag));
    }

    public RecipeBuilder output(FluidStack stack) {
        return output(Content.fluid(stack));
    }

    public RecipeBuilder outputs(Content... contents) {
        for (Content content : contents) output(content);
        return this;
    }

    // ═══════════════ 参数 ═══════════════

    /** 加工时长（tick）；小于 1 会被夹到 1。 */
    public RecipeBuilder duration(int duration) {
        this.duration = Math.max(1, duration);
        return this;
    }

    /** 每 tick 的 EU；负数表示发电配方。 */
    public RecipeBuilder eut(long eut) {
        this.eut = eut;
        return this;
    }

    /** 显式指定电压档位（覆盖按 eut 反查的结果）。 */
    public RecipeBuilder tier(int tier) {
        this.tier = tier;
        return this;
    }

    /**
     * 设置「之后加入的内容」的概率（0~1，1 = 必出）。
     *
     * <p>
     * 与 GTM 的 {@code chancedOutput} 一样是「作用域式」的：先 {@code .chance(0.5f)} 再加内容，
     * 那些内容的概率就是 0.5；再加 {@code .chance(1f)} 又回到必出。
     */
    public RecipeBuilder chance(float chance) {
        this.chance = chance;
        return this;
    }

    /**
     * 把 builder 当前的 {@link #chance} 施加到内容上。
     *
     * <p>
     * 规则：内容自带非满概率（{@code < 1}，说明调用方显式指定过）时<b>保留它</b>，
     * 否则用 builder 的当前概率。这样 {@code .chance(0.5f).output(...)} 与
     * {@code .output(Content.item(...).withChance(0.3f))} 两种写法都能表达，互不覆盖。
     */
    private Content applyChance(Content content) {
        return content.isGuaranteed() ? content.withChance(chance) : content;
    }

    // ═══════════════ 产出 ═══════════════

    /** 构建配方对象（不进 {@link #ALL_RECIPES}，只有 {@link #save} 才会登记）。 */
    public OGMRRecipe build() {
        if (inputs.isEmpty() && outputs.isEmpty()) {
            Ogmr.LOGGER.warn("ogmr: recipe {} has neither inputs nor outputs", id);
        }
        int resolvedTier = tier == OGMRRecipeSerializer.AUTO_TIER ? OGMRRecipe.tierFromEut(eut) : tier;
        return new OGMRRecipe(recipeType, id, inputs, outputs, duration, eut, resolvedTier);
    }

    /**
     * 产出 datagen 用的 {@link FinishedRecipe}。
     *
     * <p>
     * {@code getType()} 返回的是 {@link OGMRRecipeSerializer#SERIALIZER}（原版
     * {@code FinishedRecipe#serializeRecipe()} 会用它拼出 {@code "type"} 字段），
     * {@code getId()} 返回本配方的 id。
     */
    public FinishedRecipe buildFinished() {
        return new BuiltRecipe(build());
    }

    /** 登记进 {@link #ALL_RECIPES} 并把 {@link FinishedRecipe} 交给数据生成器。 */
    public void save(Consumer<FinishedRecipe> consumer) {
        OGMRRecipe recipe = build();
        if (recipe.getId() == null) {
            throw new IllegalStateException("ogmr: cannot save a recipe without an id (recipe type %s)"
                    .formatted(recipeType.getRegistryName()));
        }
        OGMRRecipe previous = ALL_RECIPES.put(recipe.getId(), recipe);
        if (previous != null) {
            Ogmr.LOGGER.warn("ogmr: recipe {} was overwritten in the runtime recipe table", recipe.getId());
        }
        consumer.accept(new BuiltRecipe(recipe));
    }

    // ═══════════════ 运行时配方表 ═══════════════

    /** 某个配方类型的全部配方（按登记顺序）。 */
    public static List<OGMRRecipe> byType(OGMRRecipeType recipeType) {
        List<OGMRRecipe> result = new ArrayList<>();
        for (OGMRRecipe recipe : ALL_RECIPES.values()) {
            if (recipe.getRecipeType() == recipeType) result.add(recipe);
        }
        return Collections.unmodifiableList(result);
    }

    /** 清空运行时配方表（datagen 重跑 / 换存档时用）。 */
    public static void clearRecipes() {
        ALL_RECIPES.clear();
    }

    // ═══════════════ FinishedRecipe ═══════════════

    /**
     * datagen 用的 {@link FinishedRecipe} 实现：把配方本身交给
     * {@link OGMRRecipeSerializer#CODEC} 编码成 JSON。
     */
    public record BuiltRecipe(OGMRRecipe recipe) implements FinishedRecipe {

        @Override
        public void serializeRecipeData(JsonObject json) {
            JsonObject encoded = OGMRRecipeSerializer.toJson(recipe);
            if (encoded == null) {
                throw new IllegalStateException(
                        "ogmr: failed to serialize recipe %s, see the log for the codec error".formatted(recipe.getId()));
            }
            encoded.entrySet().forEach(entry -> json.add(entry.getKey(), entry.getValue()));
        }

        @Override
        public ResourceLocation getId() {
            return Objects.requireNonNull(recipe.getId(), "recipe id");
        }

        @Override
        public RecipeSerializer<?> getType() {
            return OGMRRecipeSerializer.SERIALIZER;
        }

        @Nullable
        @Override
        public JsonObject serializeAdvancement() {
            return null;
        }

        @Nullable
        @Override
        public ResourceLocation getAdvancementId() {
            return null;
        }
    }
}
