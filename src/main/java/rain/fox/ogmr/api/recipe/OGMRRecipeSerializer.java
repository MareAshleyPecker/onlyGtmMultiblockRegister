package rain.fox.ogmr.api.recipe;

import rain.fox.ogmr.Ogmr;
import rain.fox.ogmr.api.registry.OGMRRegistries;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.mojang.serialization.Codec;
import com.mojang.serialization.JsonOps;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * {@link OGMRRecipe} 的序列化器 —— 本库所有配方共用一个（注册名固定 {@code ogmr:generic}）。
 *
 * <p>
 * 从 GTM 的 {@code api.recipe.GTRecipeSerializer} 精简拆出来。GTM 要在 JSON/网络里
 * 逐一搬运 capability→Content 的多张映射表、概率逻辑表、条件、研究数据……本库的配方形状是固定的
 * 三件套（{@link Content} 列表 + duration/eut/tier），所以直接用一个
 * {@link RecordCodecBuilder} 描述，不再手写 fromJson。
 *
 * <p>
 * <b>与需求文档的一处偏差</b>：1.20.1 <b>没有</b> {@code StreamCodec}
 * （{@code net.minecraft.network.codec} 是 1.20.5 才进原版的），因此网络侧只能按该版本的契约
 * 实现 {@link #fromNetwork(ResourceLocation, FriendlyByteBuf)} / {@link #toNetwork(FriendlyByteBuf, OGMRRecipe)};
 * 逻辑侧仍然提供 {@link #CODEC} 供 JSON 与 datagen 使用。约定：{@code toNetwork}/{@code fromNetwork}
 * 的字段顺序与 {@link #CODEC} 的 JSON 字段一一对应。
 *
 * <p>
 * <b>接入方式</b>（本库的 {@code Ogmr} 主类里加一行即可）：
 *
 * <pre>{@code
 * OGMRRecipeSerializer.register(modBus);
 * }</pre>
 */
public class OGMRRecipeSerializer implements RecipeSerializer<OGMRRecipe> {

    /** {@code tier} 字段的「未指定」哨兵值：由 {@code |eut|} 反查。 */
    public static final int AUTO_TIER = -1;

    /**
     * 配方编解码器。
     *
     * <p>
     * JSON 形状（{@code "type": "ogmr:generic"} 由原版 {@code FinishedRecipe#serializeRecipe()} 补上）：
     *
     * <pre>{@code
     * {
     *   "type": "ogmr:generic",
     *   "recipe_type": "ogmr:example",
     *   "inputs":  [ { "type": "item",  "ingredient": { "item": "minecraft:iron_ingot" }, "count": 2 } ],
     *   "outputs": [ { "type": "fluid", "fluid": { "FluidName": "minecraft:water", "Amount": 1000 } } ],
     *   "duration": 200,
     *   "eut": 32,
     *   "tier": 1
     * }
     * }</pre>
     */
    public static final Codec<OGMRRecipe> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            OGMRRegistries.RECIPE_TYPES.codec().fieldOf("recipe_type").forGetter(OGMRRecipe::getRecipeType),
            Content.CODEC.listOf().optionalFieldOf("inputs", List.of()).forGetter(OGMRRecipe::getInputs),
            Content.CODEC.listOf().optionalFieldOf("outputs", List.of()).forGetter(OGMRRecipe::getOutputs),
            Codec.intRange(1, Integer.MAX_VALUE).optionalFieldOf("duration", OGMRRecipe.DEFAULT_DURATION)
                    .forGetter(OGMRRecipe::getDuration),
            Codec.LONG.optionalFieldOf("eut", 0L).forGetter(OGMRRecipe::getEut),
            Codec.INT.optionalFieldOf("tier", AUTO_TIER).forGetter(OGMRRecipe::getTier))
            .apply(instance, (recipeType, inputs, outputs, duration, eut, tier) -> new OGMRRecipe(
                    recipeType, null, inputs, outputs, duration, eut,
                    tier == null || tier == AUTO_TIER ? OGMRRecipe.tierFromEut(eut) : tier)));

    /** 配方序列化器的 Forge 注册表入口。 */
    public static final DeferredRegister<RecipeSerializer<?>> SERIALIZERS = DeferredRegister
            .create(ForgeRegistries.Keys.RECIPE_SERIALIZERS, Ogmr.MOD_ID);

    /** 全局单例 —— 所有 {@link OGMRRecipe} 都用它。 */
    public static final OGMRRecipeSerializer SERIALIZER = new OGMRRecipeSerializer();

    /** 注册名固定为 {@code ogmr:generic}。 */
    public static final RegistryObject<OGMRRecipeSerializer> REGISTERED = SERIALIZERS.register("generic",
            () -> SERIALIZER);

    private OGMRRecipeSerializer() {}

    /** 把 {@link #SERIALIZERS} 挂到 mod 事件总线上（在 {@code Ogmr} 的构造里调用一次）。 */
    public static void register(IEventBus modBus) {
        SERIALIZERS.register(modBus);
    }

    // ═══════════════ JSON ═══════════════

    /**
     * 从数据包 JSON 读出配方。
     *
     * <p>
     * 失败时抛 {@link JsonParseException}（而不是 DFU 默认的 {@link RuntimeException}）：原版
     * {@code RecipeManager} 只捕获 {@code JsonParseException} / {@code IllegalArgumentException}，
     * 抛对类型才能让「一条配方写错」退化成「日志里报一条 + 跳过这条配方」，而不是整个数据包加载崩溃。
     */
    @Override
    public OGMRRecipe fromJson(ResourceLocation id, JsonObject json) {
        String[] errorHolder = new String[1];
        Optional<OGMRRecipe> parsed = CODEC.parse(JsonOps.INSTANCE, json)
                .resultOrPartial(message -> errorHolder[0] = message);
        if (parsed.isEmpty()) {
            String message = "ogmr: failed to decode recipe %s: %s"
                    .formatted(id, errorHolder[0] == null ? "unknown decoding error" : errorHolder[0]);
            Ogmr.LOGGER.error(message);
            throw new JsonParseException(message);
        }
        return parsed.get().setId(id);
    }

    // ═══════════════ 网络 ═══════════════

    @Override
    public OGMRRecipe fromNetwork(ResourceLocation id, FriendlyByteBuf buf) {
        ResourceLocation recipeTypeId = buf.readResourceLocation();
        int duration = buf.readVarInt();
        long eut = buf.readLong();
        int tier = buf.readVarInt();
        List<Content> inputs = buf.readCollection(ArrayList::new, Content::fromNetwork);
        List<Content> outputs = buf.readCollection(ArrayList::new, Content::fromNetwork);

        OGMRRecipeType recipeType = OGMRRegistries.RECIPE_TYPES.get(recipeTypeId);
        if (recipeType == null) {
            String message = "ogmr: received recipe %s of unknown recipe type %s (client knows %d types)"
                    .formatted(id, recipeTypeId, OGMRRegistries.RECIPE_TYPES.size());
            Ogmr.LOGGER.error(message);
            throw new IllegalStateException(message);
        }
        return new OGMRRecipe(recipeType, id, inputs, outputs, duration, eut, tier);
    }

    @Override
    public void toNetwork(FriendlyByteBuf buf, OGMRRecipe recipe) {
        buf.writeResourceLocation(recipe.getRecipeType().getRegistryName());
        buf.writeVarInt(recipe.getDuration());
        buf.writeLong(recipe.getEut());
        buf.writeVarInt(recipe.getTier());
        buf.writeCollection(recipe.getInputs(), (buffer, content) -> content.toNetwork(buffer));
        buf.writeCollection(recipe.getOutputs(), (buffer, content) -> content.toNetwork(buffer));
    }

    // ═══════════════ 工具 ═══════════════

    /**
     * 把配方编码成 JSON（datagen 与调试用），失败返回 null 并打日志。
     *
     * @param recipe 配方；其 {@code id} 不会写进 JSON（由数据包文件名决定）
     */
    @Nullable
    public static JsonObject toJson(OGMRRecipe recipe) {
        String[] errorHolder = new String[1];
        Optional<JsonElement> encoded = CODEC.encodeStart(JsonOps.INSTANCE, recipe)
                .resultOrPartial(message -> errorHolder[0] = message);
        if (encoded.isEmpty() || !encoded.get().isJsonObject()) {
            Ogmr.LOGGER.error("ogmr: failed to encode recipe {}: {}", recipe.getId(),
                    errorHolder[0] == null ? "result is not a json object" : errorHolder[0]);
            return null;
        }
        return encoded.get().getAsJsonObject();
    }
}
