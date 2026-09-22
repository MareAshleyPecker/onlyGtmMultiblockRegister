package rain.fox.ogmr.api.recipe;

import rain.fox.ogmr.api.recipe.content.ContentCodecs;
import rain.fox.ogmr.api.recipe.content.ContentKinds;
import rain.fox.ogmr.api.recipe.content.IContentKind;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraftforge.fluids.FluidStack;

import com.mojang.serialization.Codec;
import org.jetbrains.annotations.Nullable;

import lombok.Getter;

import java.util.Objects;

/**
 * 一条配方 IO 内容 —— 「什么东西、多少、什么概率」，其中的「东西」由
 * {@link IContentKind} 决定（内置物品与流体两种，第三方可以自己加）。
 *
 * <p>
 * 从 GTM 的 {@code api.recipe.content.Content} + 各类 {@code RecipeCapability} 精简拆出来：
 * GTM 把内容按 capability（物品/流体/EU/研究……）拆成十几个类，内容本体是一个不透明的
 * {@code Object}，由 capability 负责序列化。本库的做法是：
 * <ul>
 * <li>种类是 {@link IContentKind}（<b>可扩展</b>，见 {@link ContentKinds}），负责这种内容的
 * JSON 形状、网络形状、以及展示/匹配钩子；</li>
 * <li>内置两种的载荷有具名字段（{@link #item()} / {@link #fluid()}），编译期就能确定类型；
 * 第三方种类的载荷放在 {@link #payload()} 里，怎么解释由种类自己决定。</li>
 * </ul>
 *
 * <p>
 * 概率语义与 GTM 不同：GTM 用「万分数（int chance / maxChance）」，本库用 <b>0~1 的 float</b>，
 * {@code 1.0f = 100%}、{@code 0.0f = 永不产出}。{@link #chance()} 只对输出有意义，
 * 输入端的概率被忽略（与 GTM 一致：输入不掷骰）。
 *
 * <p>
 * JSON 形状（由各 {@link IContentKind#codec()} 定义，{@code "type"} 字段负责分派）：
 *
 * <pre>{@code
 * { "type": "item",  "ingredient": {...}, "count": 2, "chance": 1.0, "nbt": true }
 * { "type": "fluid", "fluid": {...},      "chance": 0.5 }
 * }</pre>
 */
public final class Content {

    /**
     * 物品内容所用的 {@link Ingredient} 编解码器（实现在 {@link ContentCodecs}，这里只是转出来给
     * 习惯从 {@code Content} 拿的调用方用）。
     */
    public static final Codec<Ingredient> INGREDIENT_CODEC = ContentCodecs.INGREDIENT_CODEC;

    /**
     * 内容编解码器：以 {@code type} 字段在各内容种类之间分派。
     *
     * <p>
     * 认不出的 {@code type} 会拿到一个必定失败的 codec（见
     * {@link ContentKinds#codecFor(String)}），所以第三方种类只要注册进 {@link ContentKinds}，
     * 它的配方 JSON 立刻就能被解析 —— 不需要动这个字段。
     */
    public static final Codec<Content> CODEC = Codec.STRING.dispatch(
            "type",
            content -> content.kind.getId(),
            ContentKinds::codecFor);

    private final IContentKind kind;
    /** 自定义种类的载荷；内置两种为 null（它们的载荷在 item / fluid 里）。 */
    @Nullable
    private final Object payload;
    /** 物品种类时非空。 */
    @Nullable
    private final Ingredient item;
    /** 流体种类时非空。 */
    @Nullable
    private final FluidStack fluid;
    /** 物品数量；流体时等于 {@code fluid.getAmount()}。 */
    private final int count;
    private final float chance;
    /** 物品是否要求 NBT 一致。 */
    @Getter
    private final boolean nbtMatch;

    private Content(IContentKind kind, @Nullable Object payload, @Nullable Ingredient item,
                    @Nullable FluidStack fluid, int count, float chance, boolean nbtMatch) {
        this.kind = Objects.requireNonNull(kind, "content kind");
        this.payload = payload;
        this.item = item;
        this.fluid = fluid;
        this.count = count;
        this.chance = chance;
        this.nbtMatch = nbtMatch;
    }

    // ═══════════════ 静态工厂 ═══════════════

    /** 物品内容：{@code count} 最小为 1，{@code chance} 会被截断到 [0, 1]，要求 NBT 一致。 */
    public static Content item(Ingredient ingredient, int count, float chance) {
        return item(ingredient, count, chance, true);
    }

    /** 物品内容，并显式指定是否要求 NBT 一致（物品 JSON 的 {@code nbt} 字段 / 网络读取走这里）。 */
    public static Content item(Ingredient ingredient, int count, float chance, boolean nbtMatch) {
        return new Content(ContentKinds.ITEM, null, Objects.requireNonNull(ingredient, "ingredient"),
                null, Math.max(1, count), clampChance(chance), nbtMatch);
    }

    public static Content item(Ingredient ingredient, int count) {
        return item(ingredient, count, 1f);
    }

    public static Content item(Ingredient ingredient) {
        return item(ingredient, 1, 1f);
    }

    /** 由物品堆构造：数量取 {@code stack.getCount()}，Ingredient 只取物品与 NBT。 */
    public static Content item(ItemStack stack) {
        return item(Ingredient.of(stack), Math.max(1, stack.getCount()), 1f);
    }

    public static Content item(TagKey<Item> tag) {
        return item(Ingredient.of(tag), 1, 1f);
    }

    /** 流体内容：数量取 {@code stack.getAmount()}，内部存的是副本，不受调用方后续改动影响。 */
    public static Content fluid(FluidStack stack, float chance) {
        FluidStack copy = Objects.requireNonNull(stack, "fluid").copy();
        return new Content(ContentKinds.FLUID, null, null, copy,
                Math.max(1, copy.getAmount()), clampChance(chance), false);
    }

    public static Content fluid(FluidStack stack) {
        return fluid(stack, 1f);
    }

    /**
     * <b>自定义内容种类</b>的工厂 —— 物品/流体请用 {@link #item} / {@link #fluid}。
     *
     * <p>
     * {@code payload} 就是这种内容的载荷（能量数值、魔力点数、气体……），本类不解释它，
     * 原样交给 {@link #kind()} 的 {@link IContentKind#codec()} 与
     * {@link IContentKind#toNetwork(Content, FriendlyByteBuf)}；它必须非空，否则内容算「空」。
     *
     * @throws IllegalArgumentException 传了内置的物品/流体种类（它们有专门工厂，用这个会丢载荷）
     */
    public static Content of(IContentKind kind, Object payload, int count, float chance) {
        Objects.requireNonNull(kind, "content kind");
        if (kind == ContentKinds.ITEM || kind == ContentKinds.FLUID) {
            throw new IllegalArgumentException("ogmr: use Content.item(...) / Content.fluid(...) for the built-in "
                    + "content kind '" + kind.getId() + "' — Content.of(...) is meant for custom kinds");
        }
        Objects.requireNonNull(payload, "content payload for kind '" + kind.getId() + "'");
        return new Content(kind, payload, null, null, Math.max(1, count), clampChance(chance), false);
    }

    // ═══════════════ 读取 ═══════════════

    /** 内容种类（可扩展；内置是 {@link ContentKinds#ITEM} / {@link ContentKinds#FLUID}）。 */
    public IContentKind kind() {
        return kind;
    }

    /** 自定义种类的载荷；内置两种返回 {@code null}（用 {@link #item()} / {@link #fluid()}）。 */
    @Nullable
    public Object payload() {
        return payload;
    }

    /** 不可用（物品 Ingredient 为空 / 流体为空 / 自定义载荷为空）时为 true。 */
    public boolean isEmpty() {
        return kind.isEmpty(this);
    }

    /** 物品 Ingredient；流体或自定义内容返回 null。 */
    @Nullable
    public Ingredient item() {
        return item;
    }

    /** 流体堆（内部引用，别改它）；物品或自定义内容返回 null。 */
    @Nullable
    public FluidStack fluid() {
        return fluid;
    }

    public int count() {
        return count;
    }

    public float chance() {
        return chance;
    }

    /** 是否是一定会掷中的概率（{@code >= 1}）。 */
    public boolean isGuaranteed() {
        return chance >= 1f;
    }

    /**
     * 取一个可展示/可比较的代表物品堆（数量 = {@link #count()}）。
     *
     * <p>
     * 流体内容、空 Ingredient、以及标签未加载（客户端早期）这些情况都返回 {@link ItemStack#EMPTY}，
     * 因此 UI 层可以放心直接调用；要不要给非物品内容一个物品形态，由种类自己覆写
     * {@link IContentKind#representativeItem(Content)} 决定。
     */
    public ItemStack representativeItem() {
        return kind.representativeItem(this);
    }

    /** 取流体副本（数量 = {@link #count()}），用于展示或输出；物品内容返回空流体。 */
    public FluidStack representativeFluid() {
        return kind.representativeFluid(this);
    }

    // ═══════════════ 匹配 ═══════════════

    /**
     * 这个内容能否被给定的物品堆满足（只判断「是不是这个东西」，不判断数量够不够）。
     *
     * <p>
     * 具体判定由种类给（见 {@link IContentKind#matchesItem(Content, ItemStack)}），
     * 物品种类的规则是：Ingredient 命中 + 需要时 NBT 一致。
     */
    public boolean matchesItem(ItemStack stack) {
        return kind.matchesItem(this, stack);
    }

    /**
     * 这个内容能否被给定的流体堆满足（<b>包含</b>数量判断：{@code stack.getAmount() >= count()}）。
     */
    public boolean matchesFluid(FluidStack stack) {
        return kind.matchesFluid(this, stack);
    }

    // ═══════════════ 派生（不可变） ═══════════════

    public Content withChance(float newChance) {
        return new Content(kind, payload, item, fluid, count, clampChance(newChance), nbtMatch);
    }

    public Content withCount(int newCount) {
        return new Content(kind, payload, item, fluid, Math.max(1, newCount), chance, nbtMatch);
    }

    public Content withNbtMatch(boolean newNbtMatch) {
        return new Content(kind, payload, item, fluid, count, chance, newNbtMatch);
    }

    public Content copy() {
        return new Content(kind, payload, item, fluid == null ? null : fluid.copy(), count, chance, nbtMatch);
    }

    // ═══════════════ 序列化 ═══════════════

    /** 写网络（种类名 + 概率 + 种类自己的载荷，形状与 {@link #CODEC} 的 JSON 字段一一对应）。 */
    public void toNetwork(FriendlyByteBuf buf) {
        buf.writeUtf(kind.getId());
        buf.writeFloat(chance);
        kind.toNetwork(this, buf);
    }

    /** 读网络，与 {@link #toNetwork(FriendlyByteBuf)} 严格对称。 */
    public static Content fromNetwork(FriendlyByteBuf buf) {
        IContentKind kind = ContentKinds.byName(buf.readUtf());
        float chance = buf.readFloat();
        return kind.fromNetwork(buf).withChance(chance);
    }

    // ═══════════════ 杂项 ═══════════════

    private static float clampChance(float chance) {
        if (chance < 0f) return 0f;
        return Math.min(chance, 1f);
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof Content other)) return false;
        return count == other.count &&
                Float.compare(chance, other.chance) == 0 &&
                nbtMatch == other.nbtMatch &&
                kind == other.kind &&
                Objects.equals(payload, other.payload) &&
                Objects.equals(item, other.item) &&
                Objects.equals(fluid, other.fluid);
    }

    @Override
    public int hashCode() {
        return Objects.hash(kind, payload, item, fluid, count, chance, nbtMatch);
    }

    @Override
    public String toString() {
        return kind.describe(this);
    }
}
