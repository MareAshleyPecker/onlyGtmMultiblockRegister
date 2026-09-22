package rain.fox.ogmr.test;

import rain.fox.ogmr.Ogmr;
import rain.fox.ogmr.api.recipe.Content;
import rain.fox.ogmr.api.recipe.content.AbstractContentKind;
import rain.fox.ogmr.api.recipe.content.ContentCodecs;
import rain.fox.ogmr.api.recipe.content.ContentKinds;
import rain.fox.ogmr.api.recipe.content.IContentKind;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import com.google.gson.JsonElement;
import com.mojang.serialization.Codec;
import com.mojang.serialization.JsonOps;
import com.mojang.serialization.codecs.RecordCodecBuilder;

/**
 * 测试包自己的一种配方内容种类 —— {@link IContentKind} <b>可扩展性的活例子</b>。
 *
 * <p>
 * 它演示「第三方想在配方里加一种新内容」要写多少东西：一个类、一个 {@code register}，
 * 加上自己的 JSON 形状与网络形状，然后配方 JSON 就能写
 * {@code { "type": "test_energy", "eu": 500 }}。
 * 库本体不需要为它改任何一行 —— 这正是这次把写死的 {@code Content.Kind} 改成接口的目的。
 *
 * <p>
 * 载荷是一个 {@code Long}（EU 数），放在 {@link Content#payload()} 里；
 * 数量字段 {@code count} 对本种类没有意义，固定为 1。
 */
public final class TestContentKinds {

    private TestContentKinds() {}

    /**
     * 这种内容的 JSON 形状：{@code { "type": "test_energy", "eu": 500, "chance": 1.0 }}。
     *
     * <p>
     * ⚠️ 必须声明在 {@link #TEST_ENERGY} <b>之前</b>：注册时会立刻调一次 {@code codec()} 做非空校验，
     * 声明在后面的话这时候它还是 null。里面引用 {@code TEST_ENERGY} 得写全类名
     * （{@code TestContentKinds.TEST_ENERGY}）—— 简单名会踩 javac 的
     * {@code illegal forward reference}（字段声明在下面）；写成全类名就只是「解码时才读」了。
     */
    private static final Codec<Content> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.LONG.fieldOf("eu").forGetter(TestContentKinds::eu),
            ContentCodecs.CHANCE_CODEC.optionalFieldOf("chance", 1f).forGetter(Content::chance))
            .apply(instance, (eu, chance) -> Content.of(TestContentKinds.TEST_ENERGY, eu, 1, chance)));

    /** 演示用内容类型：一个「注入 EU」的载荷。 */
    public static final IContentKind TEST_ENERGY = ContentKinds.register(new AbstractContentKind(
            "test_energy", "Test Energy", "测试能量") {

        @Override
        public Codec<Content> codec() {
            return CODEC;
        }

        @Override
        public void toNetwork(Content content, FriendlyByteBuf buf) {
            buf.writeVarLong(eu(content));
        }

        @Override
        public Content fromNetwork(FriendlyByteBuf buf) {
            return Content.of(this, buf.readVarLong(), 1, 1f);
        }

        /** 展示成红石：本种类没有真正的物品形态，借用一下让 UI 有东西可画。 */
        @Override
        public ItemStack representativeItem(Content content) {
            return new ItemStack(Items.REDSTONE, (int) Math.min(64, Math.max(1, eu(content) / 100)));
        }

        @Override
        public String describe(Content content) {
            return "Content[test_energy=%d EU]".formatted(eu(content));
        }
    });

    /** 造一条测试能量内容（给测试配方用）。 */
    public static Content energy(long euAmount) {
        return Content.of(TEST_ENERGY, euAmount, 1, 1f);
    }

    /** 登记这种内容的名字（库的 {@code ContentKinds#initLang()} 也会带上它，这里显式来一次做示范）。 */
    static void initLang() {
        TEST_ENERGY.registerLang();
    }

    /**
     * 自检：拿 {@link Content#CODEC} 把自己这种内容编码成 JSON 再解回来。
     *
     * <p>
     * 这一步验证的正是「可扩展」的关键一环：{@code Content.CODEC} 靠 {@code ContentKinds} 查到
     * 本种类的 codec，而不是写死 item/fluid 两个分支。跑 datagen 时看日志里的
     * {@code content kind round-trip} 一行即可（{@code equal=true} 表示自定义种类真的走通了）。
     */
    static void selfCheck() {
        Content original = energy(500);
        try {
            JsonElement json = Content.CODEC.encodeStart(JsonOps.INSTANCE, original)
                    .getOrThrow(false, message -> Ogmr.LOGGER.error("ogmr test pack: encode failed: {}", message));
            Content decoded = Content.CODEC.parse(JsonOps.INSTANCE, json)
                    .getOrThrow(false, message -> Ogmr.LOGGER.error("ogmr test pack: decode failed: {}", message));
            Ogmr.LOGGER.info("ogmr test pack: content kind round-trip {} -> {} -> {} (equal={})",
                    original, json, decoded, original.equals(decoded));
        } catch (RuntimeException e) {
            Ogmr.LOGGER.error("ogmr test pack: custom content kind round-trip crashed", e);
        }
    }

    /** 读载荷（不是 Long 就是 0，防御一下）。 */
    private static long eu(Content content) {
        Object payload = content.payload();
        return payload instanceof Long value ? value : 0L;
    }
}
