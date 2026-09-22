package rain.fox.ogmr.data;

import rain.fox.ogmr.Ogmr;
import rain.fox.ogmr.api.machine.MachineDefinition;
import rain.fox.ogmr.api.registry.OGMRRegistries;
import rain.fox.ogmr.utils.ResourceLocations;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.data.PackOutput;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraftforge.client.model.generators.BlockModelBuilder;
import net.minecraftforge.client.model.generators.BlockStateProvider;
import net.minecraftforge.common.data.ExistingFileHelper;

import org.jetbrains.annotations.Nullable;

/**
 * 机器/仓室方块的<b>模型与方块状态数据生成器</b> —— 把 addon 从「每个机器手写三个 JSON」里解放出来。
 *
 * <p>
 * 对每个属于本命名空间的 {@link MachineDefinition} 产出三份文件（都在 {@code src/generated/resources} 下）：
 * <ol>
 * <li>{@code assets/<ns>/blockstates/<name>.json} —— 一个 catch-all 变体
 * （{@code "variants": {"": {...}}}）。{@code MachineBlock} 有
 * {@code facing/active/formed} 三个属性，catch-all 让所有状态都指到同一个模型，
 * 不需要为每种组合各写一条；</li>
 * <li>{@code assets/<ns>/models/block/<name>.json} —— {@code cube_all}，
 * 贴图取 {@link MachineDefinition#getModelTexture()}；</li>
 * <li>{@code assets/<ns>/models/item/<name>.json} —— 指向上面那个方块模型。</li>
 * </ol>
 *
 * <p>
 * 贴图本身（PNG）<b>不生成</b> —— 那是美术资源。没配 {@link MachineDefinition#setModelTexture} 的机器
 * 会用 {@link #fallbackTexture}（默认 {@code minecraft:block/iron_block}），
 * 好处是「忘了配」只会显示成一块铁块，而不是紫黑相间的丢失贴图。
 *
 * <p>
 * 由 {@code AddonBootstrap} 在 {@code GatherDataEvent}（client 侧）自动挂上，addon 侧零配置。
 */
public class OGMRMachineModelProvider extends BlockStateProvider {

    /** 没配贴图时的兜底。 */
    public static final ResourceLocation DEFAULT_FALLBACK_TEXTURE = new ResourceLocation("minecraft",
            "block/iron_block");

    private final String modId;
    private final ResourceLocation fallbackTexture;

    public OGMRMachineModelProvider(PackOutput output, String modId,
                                    @Nullable ExistingFileHelper existingFileHelper) {
        this(output, modId, existingFileHelper, DEFAULT_FALLBACK_TEXTURE);
    }

    public OGMRMachineModelProvider(PackOutput output, String modId,
                                    @Nullable ExistingFileHelper existingFileHelper,
                                    ResourceLocation fallbackTexture) {
        super(output, modId, existingFileHelper);
        this.modId = modId;
        this.fallbackTexture = fallbackTexture != null ? fallbackTexture : DEFAULT_FALLBACK_TEXTURE;
    }

    @Override
    protected void registerStatesAndModels() {
        int count = 0;
        for (MachineDefinition definition : OGMRRegistries.MACHINES) {
            if (definition == null) continue;
            ResourceLocation id = definition.getId();
            if (!modId.equals(id.getNamespace())) continue;

            Block block = safeBlock(definition);
            if (block == null) continue;

            ResourceLocation texture = definition.getModelTexture() != null
                    ? definition.getModelTexture()
                    : fallbackTexture;
            BlockModelBuilder model = models().cubeAll(id.getPath(), texture);
            // blockstates/<name>.json + models/block/<name>.json
            simpleBlock(block, model);
            // models/item/<name>.json（物品栏里显示的模型）—— 1.20.1 的 simpleBlock 不会自动带，
            // 得显式补一次，否则机器物品在物品栏里是紫黑块。
            simpleBlockItem(block, model);
            count++;
        }
        Ogmr.LOGGER.info("ogmr: generated block models for {} machine(s) of '{}'", count, modId);
    }

    /**
     * 取机器对应的方块。
     *
     * <p>
     * ⚠️ <b>不能用 {@code definition.getBlock()}</b>：那条路走的是 {@code DeferredRegister} 的
     * {@code RegistryObject}，而 datagen 环境下它还没被解析（{@code get()} 抛
     * {@code NullPointerException: Registry Object not present}）。
     * 数据生成该读的是<b>已经冻结的注册表</b>，注册名就是机器 id。
     */
    @Nullable
    private static Block safeBlock(MachineDefinition definition) {
        Block block = BuiltInRegistries.BLOCK.get(definition.getId());
        if (block == null || block == Blocks.AIR) {
            Ogmr.LOGGER.warn("ogmr: skipping model for {} — no block is registered under that id",
                    ResourceLocations.pathOf(definition.getId()));
            return null;
        }
        return block;
    }
}
