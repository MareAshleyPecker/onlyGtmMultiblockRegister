package rain.fox.ogmr.api.recipe.content;

import rain.fox.ogmr.api.lang.OGMRLang;
import rain.fox.ogmr.api.recipe.Content;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.fluids.FluidStack;

import com.mojang.serialization.Codec;
import org.jetbrains.annotations.Nullable;

/**
 * 配方 IO 的「内容种类」—— 本库配方内容系统<b>可扩展</b>的那一半。
 *
 * <p>
 * 之前内容种类是 {@code Content} 里写死的 {@code enum Kind { ITEM, FLUID }}，
 * 第三方想加一种内容（能量、魔力、气体……）只能改库的源码。现在它是接口：
 * 自带两种（{@link ContentKinds#ITEM} / {@link ContentKinds#FLUID}）和新注册进来的种类
 * <b>功能上完全平权</b>，库内部也不对 {@code "item"} / {@code "fluid"} 做特判。
 *
 * <h3>一种内容种类要提供什么</h3>
 * <ol>
 * <li>{@link #getId()} —— JSON 里 {@code "type"} 字段的值，同一 id 只能注册一次；</li>
 * <li>{@link #codec()} —— 这种内容在配方 JSON 里的形状；</li>
 * <li>{@link #toNetwork} / {@link #fromNetwork} —— 网络形状（{@code type} 与 {@code chance} 由
 * {@link Content} 自己读写，这里只管载荷）；</li>
 * <li>可选的展示/匹配钩子 —— {@link #representativeItem}、{@link #matchesItem} 等，默认是不支持。</li>
 * </ol>
 *
 * <h3>自己加一种</h3>
 * <pre>{@code
 * public static final IContentKind MANA = ContentKinds.register(new AbstractContentKind(
 *         "mana", "Mana", "魔力") {
 *     @Override public Codec<Content> codec() { return MANA_CODEC; }
 *     @Override public void toNetwork(Content content, FriendlyByteBuf buf) { buf.writeVarInt(...); }
 *     @Override public Content fromNetwork(FriendlyByteBuf buf) { ... return Content.of(this, payload, 1, 1f); }
 *     @Override public ItemStack representativeItem(Content content) { return MANA_BOTTLE.getDefaultInstance(); }
 * });
 * }</pre>
 *
 * <p>
 * 自定义种类的内容载荷放在 {@link Content#payload()} 里（内置两种用
 * {@link Content#item()} / {@link Content#fluid()}），怎么解释由种类自己决定。
 */
public interface IContentKind {

    /**
     * 序列化名（JSON 的 {@code "type"} 字段、网络里的种类标记）。
     *
     * <p>
     * 约定用小写英文，例如 {@code "item"} / {@code "fluid"} / {@code "mana"}；
     * 名字不区分大小写地查表，但读写出去的就是这里返回的原样字符串。
     */
    String getId();

    /** 英文展示名（JEI/UI 上「这种内容叫什么」）。 */
    String getDisplayName();

    /** 中文展示名；返回 {@code null} 表示回退英文。 */
    @Nullable
    String getChineseName();

    /**
     * 这种内容在配方 JSON 里的编解码器。
     *
     * <p>
     * 必须产出一个 {@link Content#kind()} 等于本种类的内容；{@code "type"} 字段由
     * {@link Content#CODEC} 负责分派，这里不重复读它。
     */
    Codec<Content> codec();

    /** 写网络载荷（不含种类名与 {@code chance}，那两个由 {@link Content} 写）。 */
    void toNetwork(Content content, FriendlyByteBuf buf);

    /** 读网络载荷，与 {@link #toNetwork(Content, FriendlyByteBuf)} 严格对称。 */
    Content fromNetwork(FriendlyByteBuf buf);

    /** 这条内容是不是空的（空的内容不参与匹配，也不该出现在配方里）。 */
    default boolean isEmpty(Content content) {
        return content.payload() == null;
    }

    /** 代表物品堆（JEI/UI 展示用）；这种内容没有物品形态时返回空。 */
    default ItemStack representativeItem(Content content) {
        return ItemStack.EMPTY;
    }

    /** 代表流体（JEI/UI 展示用）；这种内容没有流体形态时返回空。 */
    default FluidStack representativeFluid(Content content) {
        return FluidStack.EMPTY;
    }

    /**
     * 这条内容能否被给定的物品堆满足（只判断「是不是这个东西」，不管数量）。
     *
     * <p>
     * 库里只在 {@code Content#matchesItem(ItemStack)} 上调用它，所以自定义种类
     * 不打算用物品当探针的话，保持默认的 false 即可。
     */
    default boolean matchesItem(Content content, ItemStack stack) {
        return false;
    }

    /** 这条内容能否被给定的流体堆满足（<b>含</b>数量判断，见 {@code Content#matchesFluid}）。 */
    default boolean matchesFluid(Content content, FluidStack stack) {
        return false;
    }

    /** 调试/日志用的简短描述。 */
    default String describe(Content content) {
        return "%s x%d".formatted(getId(), content.count());
    }

    /**
     * 登记这种内容种类的双语名（键 {@code ogmr.content.kind.<id>}）。
     *
     * <p>
     * 数据生成之前调用即可；{@link ContentKinds#initLang()} 会把已注册的全部走一遍。
     */
    default void registerLang() {
        OGMRLang.add("ogmr.content.kind." + getId(), getDisplayName(),
                getChineseName() != null ? getChineseName() : getDisplayName());
    }
}
