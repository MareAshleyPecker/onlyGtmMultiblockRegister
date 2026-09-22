package rain.fox.ogmr.api.recipe.content;

import net.minecraft.tags.TagKey;
import net.minecraft.util.ExtraCodecs;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.crafting.Ingredient;

import com.google.gson.JsonElement;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;

/**
 * 配方内容里那些「谁都要用」的编解码器，供内置种类与第三方种类共用。
 *
 * <p>
 * {@link #INGREDIENT_CODEC}：1.20.1 <b>没有</b> {@code Ingredient.CODEC} / {@code Ingredient.CODEC_NONEMPTY}
 * （那是 1.20.5+ 才有的），Forge 47.x 也没给 {@code Ingredient} 打上 codec，所以照 GTM
 * {@code SerializerIngredient#CODEC} 的做法，用 {@link ExtraCodecs#JSON} 在
 * {@link Ingredient#fromJson(JsonElement)} / {@link Ingredient#toJson()} 之间做 xmap，
 * 并额外校验「解析出来不能是空 Ingredient」。
 *
 * <p>
 * {@link #CHANCE_CODEC}：概率字段必须在 {@code [0, 1]} 之间，超范围时给出明确错误而不是静默截断。
 * 用 {@code comapFlatMap} 而不是 {@code Codec#validate} —— 1.20.1 自带的 DataFixerUpper 6.0.8
 * 还没有 {@code Codec#validate}。校验逻辑单独抽成方法：javac 17 对「lambda 里套三元泛型方法调用」
 * 这种写法会崩（TransTypes 的 ClassCastException），拆出来既避开编译器 bug 也更好读。
 */
public final class ContentCodecs {

    private ContentCodecs() {}

    /** 物品内容所用的 {@link Ingredient} 编解码器。 */
    public static final Codec<Ingredient> INGREDIENT_CODEC = ExtraCodecs.JSON
            .comapFlatMap(ContentCodecs::parseIngredient, Ingredient::toJson);

    /** 概率字段（{@code 0 ~ 1}）。 */
    public static final Codec<Float> CHANCE_CODEC = Codec.FLOAT
            .comapFlatMap(ContentCodecs::checkChance, Float::valueOf);

    private static DataResult<Ingredient> parseIngredient(JsonElement json) {
        try {
            Ingredient ingredient = Ingredient.fromJson(json);
            if (ingredient.isEmpty()) {
                return DataResult.error(() -> "ogmr: content ingredient is empty (expected an item id, a tag or an ingredient list)");
            }
            return DataResult.success(ingredient);
        } catch (Exception e) {
            return DataResult.error(() -> "ogmr: failed to parse content ingredient: " + e.getMessage());
        }
    }

    private static DataResult<Float> checkChance(Float chance) {
        if (chance == null || chance < 0f || chance > 1f) {
            return DataResult.error(
                    () -> "ogmr: content chance must be within [0, 1] (1.0 = 100%), got " + chance);
        }
        return DataResult.success(chance);
    }

    /** 便捷方法：物品标签 → {@link Ingredient}（写 codec 时常用）。 */
    public static Ingredient ingredientOf(TagKey<Item> tag) {
        return Ingredient.of(tag);
    }
}
