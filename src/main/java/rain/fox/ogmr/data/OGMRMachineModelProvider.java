package rain.fox.ogmr.data;

import rain.fox.ogmr.Ogmr;
import rain.fox.ogmr.api.machine.MachineDefinition;
import rain.fox.ogmr.api.machine.RotationState;
import rain.fox.ogmr.api.registry.OGMRRegistries;
import rain.fox.ogmr.utils.ResourceLocations;

import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.data.PackOutput;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
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
            if (definition.hasPort()) {
                // 有「口」的机器：blockstate 按朝向挑模型（口只画在朝向那一面）。
                // ⚠️ 不能再走 simpleBlock —— 那会写一条 catch-all 变体，和「按朝向」的变体重叠，
                //    Forge 会直接抛 "Cannot set models for a state for which a partial match has already been"。
                registerPortVariants(definition, block, texture);
            } else {
                // blockstates/<name>.json + models/block/<name>.json（catch-all：所有状态同一个模型）
                simpleBlock(block, model);
            }
            // models/item/<name>.json（物品栏里显示的模型）—— 1.20.1 的 simpleBlock 不会自动带，
            // 得显式补一次，否则机器物品在物品栏里是紫黑块。物品一律用「底盘」那个模型。
            simpleBlockItem(block, model);
            count++;
        }
        Ogmr.LOGGER.info("ogmr: generated block models for {} machine(s) of '{}'", count, modId);
    }

    /** 口离方块表面的外凸量（格）—— 小到看不见厚度，但足以在深度测试里赢过底盘那一面。 */
    private static final float PORT_OFFSET = 0.002f;

    /**
     * 给有「口」的机器产出「按朝向挑模型」的 blockstate。
     *
     * <p>
     * 每个允许的朝向各生成一份自包含模型 {@code models/block/<name>_port_<dir>.json}：
     * 底盘整块 + 一块只在那一面有贴图的薄片（比 16 凸出 {@link #PORT_OFFSET} → 口永远在最上层）。
     * 覆盖掉 {@link #simpleBlock} 刚写的 catch-all 变体（同一份 blockstate JSON，后者胜）。
     *
     * <p>变体键只写 {@code facing=...}：{@code active}/{@code formed} 不写就是通配符（原版语义），
     * 于是 4（或 6）条变体就覆盖了全部状态组合。
     */
    private void registerPortVariants(MachineDefinition definition, Block block, ResourceLocation baseTexture) {
        RotationState rotation = definition.getRotationState();
        if (!rotation.hasFacing()) {
            Ogmr.LOGGER.warn("ogmr: {} 声明了口，但朝向设定是 NONE —— 口的朝向表达不出来",
                    ResourceLocations.pathOf(definition.getId()));
            return;
        }
        ResourceLocation portTexture = definition.getPortTexture();
        DirectionProperty property = rotation.getProperty();

        var variants = getVariantBuilder(block);
        for (Direction direction : Direction.values()) {
            if (!rotation.test(direction)) continue;
            BlockModelBuilder model = portModel(definition, direction, baseTexture, portTexture);
            variants.partialState().with(property, direction)
                    .modelForState().modelFile(model).addModel();
        }
    }

    /**
     * 造一份「底盘 + 口」的模型。
     *
     * <p>
     * ⚠️ 不能写成 {@code cubeAll(...).element()...}：MC 的模型继承里<b>子模型一旦自带 elements，
     * 父模型的 elements 会被整个替换</b>，那样底盘就没了。所以两份元素都自己写。
     */
    private BlockModelBuilder portModel(MachineDefinition definition, Direction portSide,
                                        ResourceLocation baseTexture, ResourceLocation portTexture) {
        String name = "%s_port_%s".formatted(definition.getId().getPath(), portSide.getName());
        BlockModelBuilder model = models().getBuilder(name)
                .texture("all", baseTexture)
                .texture("port", portTexture);
        if (definition.isPortCutout()) {
            model.renderType("cutout");
        }

        // ① 底盘：整块，六面同一个贴图
        model.element()
                .from(0, 0, 0)
                .to(16, 16, 16)
                .allFaces((dir, face) -> face.texture("#all"))
                .end();

        // ② 口：贴在朝向那一面的薄片，只定义朝外那一个面（其余 5 面不写 = 不渲染 → 「只渲染这一面」）
        float min = 16f - PORT_OFFSET;
        float max = 16f + PORT_OFFSET;
        var port = model.element();
        switch (portSide) {
            case DOWN -> port.from(0, -PORT_OFFSET, 0).to(16, PORT_OFFSET, 16);
            case UP -> port.from(0, min, 0).to(16, max, 16);
            case NORTH -> port.from(0, 0, -PORT_OFFSET).to(16, 16, PORT_OFFSET);
            case SOUTH -> port.from(0, 0, min).to(16, 16, max);
            case WEST -> port.from(-PORT_OFFSET, 0, 0).to(PORT_OFFSET, 16, 16);
            case EAST -> port.from(min, 0, 0).to(max, 16, 16);
        }
        port.face(portSide).texture("#port").end().end();

        return model;
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
