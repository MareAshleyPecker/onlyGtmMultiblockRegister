package rain.fox.ogmr.api.recipe.content;

import rain.fox.ogmr.api.recipe.Content;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.fluids.FluidStack;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import java.util.Objects;

/**
 * 内置内容种类：<b>流体</b>（{@code "type": "fluid"}）。
 *
 * <p>
 * JSON 形状：
 * <pre>{@code
 * { "type": "fluid", "fluid": {...}, "chance": 0.5 }
 * }</pre>
 *
 * <p>
 * 数量不在 JSON 里单独写：它等于 {@code fluid.amount}（GTM 也是这样，避免
 * 「数量」和「流体量」两处打架）。{@link #representativeItem(Content)} 返回对应流体的桶，
 * 这样 JEI 里流体配方也能像物品一样被展示出来。
 */
public final class FluidContentKind extends AbstractContentKind {

    public static final String ID = "fluid";

    FluidContentKind() {
        super(ID, "Fluid", "流体");
    }

    private static final class Holder {

        static final Codec<Content> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                FluidStack.CODEC.fieldOf("fluid").forGetter(content -> Objects.requireNonNull(content.fluid())),
                ContentCodecs.CHANCE_CODEC.optionalFieldOf("chance", 1f).forGetter(Content::chance))
                .apply(instance, (fluid, chance) -> Content.fluid(fluid, chance)));
    }

    @Override
    public Codec<Content> codec() {
        return Holder.CODEC;
    }

    @Override
    public void toNetwork(Content content, FriendlyByteBuf buf) {
        buf.writeFluidStack(Objects.requireNonNull(content.fluid(), "fluid content without fluid"));
    }

    @Override
    public Content fromNetwork(FriendlyByteBuf buf) {
        return Content.fluid(buf.readFluidStack(), 1f);
    }

    @Override
    public boolean isEmpty(Content content) {
        FluidStack fluid = content.fluid();
        return fluid == null || fluid.isEmpty();
    }

    @Override
    public FluidStack representativeFluid(Content content) {
        FluidStack fluid = content.fluid();
        if (fluid == null || fluid.isEmpty()) return FluidStack.EMPTY;
        FluidStack copy = fluid.copy();
        copy.setAmount(content.count());
        return copy;
    }

    @Override
    public boolean matchesFluid(Content content, FluidStack stack) {
        FluidStack fluid = content.fluid();
        if (fluid == null || fluid.isEmpty() || stack.isEmpty()) return false;
        if (!fluid.isFluidStackIdentical(stack) && !fluid.isFluidEqual(stack)) return false;
        return stack.getAmount() >= content.count();
    }

    @Override
    public String describe(Content content) {
        return "Content[fluid=%s x%d, chance=%s]".formatted(content.fluid(), content.count(), content.chance());
    }
}
