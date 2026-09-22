package rain.fox.ogmr.api.recipe.content;

import rain.fox.ogmr.api.recipe.Content;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import java.util.Objects;

/**
 * 内置内容种类：<b>物品</b>（{@code "type": "item"}）。
 *
 * <p>
 * JSON 形状：
 * <pre>{@code
 * { "type": "item", "ingredient": {...}, "count": 2, "chance": 1.0, "nbt": true }
 * }</pre>
 *
 * <p>
 * {@code nbt} 为 true 时要求 NBT 一致：以 Ingredient 候选项里第一个带 tag 的为准，
 * Ingredient 本身没带 tag（纯物品/纯标签）时退化成普通物品匹配 —— 这与 GTM 把
 * {@code StrictNBTIngredient} 做成可选能力是同一个思路，只是这里由内容自己记账。
 */
public final class ItemContentKind extends AbstractContentKind {

    public static final String ID = "item";

    ItemContentKind() {
        super(ID, "Item", "物品");
    }

    /** 编解码器放在静态内部类里懒建，避免「建种类」和「建 codec」互相牵扯类初始化顺序。 */
    private static final class Holder {

        static final Codec<Content> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                ContentCodecs.INGREDIENT_CODEC.fieldOf("ingredient").forGetter(content -> Objects.requireNonNull(content.item())),
                Codec.INT.optionalFieldOf("count", 1).forGetter(Content::count),
                ContentCodecs.CHANCE_CODEC.optionalFieldOf("chance", 1f).forGetter(Content::chance),
                Codec.BOOL.optionalFieldOf("nbt", true).forGetter(Content::isNbtMatch))
                .apply(instance, (ingredient, count, chance, nbt) -> Content.item(ingredient, count, chance, nbt)));
    }

    @Override
    public Codec<Content> codec() {
        return Holder.CODEC;
    }

    @Override
    public void toNetwork(Content content, FriendlyByteBuf buf) {
        buf.writeVarInt(content.count());
        buf.writeBoolean(content.isNbtMatch());
        Objects.requireNonNull(content.item(), "item content without ingredient").toNetwork(buf);
    }

    @Override
    public Content fromNetwork(FriendlyByteBuf buf) {
        int count = buf.readVarInt();
        boolean nbt = buf.readBoolean();
        Ingredient ingredient = Ingredient.fromNetwork(buf);
        return Content.item(ingredient, count, 1f, nbt);
    }

    @Override
    public boolean isEmpty(Content content) {
        Ingredient item = content.item();
        return item == null || item.isEmpty() || content.count() <= 0;
    }

    @Override
    public ItemStack representativeItem(Content content) {
        Ingredient item = content.item();
        if (item == null || item.isEmpty()) return ItemStack.EMPTY;
        ItemStack[] candidates;
        try {
            candidates = item.getItems();
        } catch (Exception e) {
            // 标签尚未加载（例如客户端资源重载中）时会抛；这不是错误，静默退化成空
            return ItemStack.EMPTY;
        }
        if (candidates.length == 0) return ItemStack.EMPTY;
        ItemStack first = candidates[0];
        ItemStack stack = new ItemStack(first.getItem(), content.count());
        if (first.hasTag()) stack.setTag(first.getTag().copy());
        return stack;
    }

    @Override
    public boolean matchesItem(Content content, ItemStack stack) {
        Ingredient item = content.item();
        if (item == null || stack.isEmpty()) return false;
        if (!item.test(stack)) return false;
        if (!content.isNbtMatch()) return true;
        ItemStack[] candidates;
        try {
            candidates = item.getItems();
        } catch (Exception e) {
            return true;
        }
        for (ItemStack candidate : candidates) {
            if (candidate.hasTag()) return ItemStack.isSameItemSameTags(candidate, stack);
        }
        return true;
    }

    @Override
    public String describe(Content content) {
        return "Content[item=%s x%d, chance=%s, nbt=%s]".formatted(
                content.item(), content.count(), content.chance(), content.isNbtMatch());
    }
}
