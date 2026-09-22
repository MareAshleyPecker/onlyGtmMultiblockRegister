package rain.fox.ogmr.data;

import rain.fox.ogmr.Ogmr;
import rain.fox.ogmr.api.block.MachineBlock;
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
import net.minecraftforge.client.model.generators.ConfiguredModel;
import net.minecraftforge.common.data.ExistingFileHelper;

import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

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
            BlockModelBuilder baseModel = models().cubeAll(id.getPath(), texture);
            if (definition.hasFacingLayers()) {
                // 有「贴在朝向那一面」的层（覆盖层 / 成型层 / 发光层 / 口）：
                // blockstate 要按朝向（以及成型、工作状态）挑模型。
                // ⚠️ 不能再走 simpleBlock —— 那会写一条 catch-all 变体，和这些变体重叠，
                //    Forge 会直接抛 "Cannot set models for a state for which a partial match has already been"。
                registerLayeredVariants(definition, block, texture);
            } else {
                // blockstates/<name>.json + models/block/<name>.json（catch-all：所有状态同一个模型）
                simpleBlock(block, baseModel);
            }
            // models/item/<name>.json（物品栏里显示的模型）—— 1.20.1 的 simpleBlock 不会自动带，
            // 得显式补一次，否则机器物品在物品栏里是紫黑块。物品一律用「底盘」那个模型。
            simpleBlockItem(block, baseModel);
            count++;
        }
        Ogmr.LOGGER.info("ogmr: generated block models for {} machine(s) of '{}'", count, modId);
    }

    /**
     * 每层之间、以及层与底盘之间的间隔（格）—— 取 GTM 机器模型模板里的值（{@code from [0,0,-0.01]}）。
     *
     * <p>0.01 格 = 1/1600 方块：肉眼看不出来，但足够让深度测试分出先后（0.001 太贴，远距离会闪）。
     * 坐标必须落在 {@code [-16, 32]} 内，0.01 当然没问题；注意<b>不能</b>用「把整个面元素往外平移
     * 一个方块」那种写法，那会直接撞上 {@code -16} 的下界。
     */
    private static final float LAYER_OFFSET = 0.01f;

    /**
     * 层画在哪一面 —— 必须是 <b>SOUTH</b>。
     *
     * <p>
     * 因为 blockstate 的旋转用的是原版 {@code BlockStateProvider#directionalBlock} 那套角度
     * （{@code facing=north → y=180}），它假定模型的「正面」在 SOUTH 面。
     * 换掉这个常量就必须同步换掉 {@link #directionalRotationX}/{@link #directionalRotationY}。
     */
    private static final Direction CANONICAL_FACE = Direction.SOUTH;

    /**
     * 给「有贴在朝向那一面的层」的机器产出 blockstate。
     *
     * <p>
     * <b>每个「层组合」只出一份模型，方向交给 blockstate 的 {@code x}/{@code y} 旋转</b>
     * —— 这就是原版（和 GTM）的做法：模型只画一次、朝固定的一面，六个朝向靠
     * {@link ConfiguredModel} 的旋转复用同一份文件。所以一台仓室是「1 份模型 + 6 条变体」，
     * 而不是「6 份朝向各异的模型」。
     *
     * <p>
     * 旋转角度照抄 {@code BlockStateProvider#directionalBlock} 的算法：
     * {@code rotationX = DOWN ? 90 : UP ? -90 : 0}、{@code rotationY = 水平方向 ? toYRot() : 0}。
     * 这套算法假定<b>模型的「正面」在 SOUTH 面</b>（{@code facing=north} 时 {@code y=180} 正好把
     * 南面转到北面），所以本类的层一律画在 {@link #CANONICAL_FACE} 上。
     *
     * <p>
     * 层序（从下到上）：底盘 → 正面覆盖层 → 成型层（{@code formed=true} 才画）→
     * 发光层（{@code active=true} 才画）→ 口（永远最上层）。
     * 每层都是<b>一块只在那一面有贴图的薄片</b>，朝外那一个 face 才定义，其余 5 面不写 = 不渲染。
     *
     * <p>
     * 变体键只写「真的会改变模型」的属性：有发光层才写 {@code active}、有成型层才写 {@code formed}
     * —— 原版语义里没写到的属性是通配符，所以既能覆盖全部状态组合，又不会凭空多出一堆变体。
     * 每条变体都<b>完全指定</b>它写了的那几个属性，所以彼此不重叠（Forge 不允许重叠）。
     */
    private void registerLayeredVariants(MachineDefinition definition, Block block, ResourceLocation baseTexture) {
        RotationState rotation = definition.getRotationState();
        if (!rotation.hasFacing()) {
            Ogmr.LOGGER.warn("ogmr: {} 声明了覆盖层/口，但朝向设定是 NONE —— 这些东西画在哪一面表达不出来",
                    ResourceLocations.pathOf(definition.getId()));
            return;
        }
        boolean usesActive = definition.getEmissiveOverlayTexture() != null;
        boolean usesFormed = definition.getFormedOverlayTexture() != null;
        DirectionProperty property = rotation.getProperty();

        // 模型与方向无关（层画在 CANONICAL_FACE），所以先按「层组合」把模型都建出来，再复用
        BlockModelBuilder plain = layeredModel(definition, baseTexture, false, false);
        BlockModelBuilder formedModel = usesFormed ? layeredModel(definition, baseTexture, false, true) : null;
        BlockModelBuilder activeModel = usesActive ? layeredModel(definition, baseTexture, true, false) : null;
        BlockModelBuilder bothModel = usesActive && usesFormed ? layeredModel(definition, baseTexture, true, true) : null;

        var variants = getVariantBuilder(block);
        for (Direction direction : Direction.values()) {
            if (!rotation.test(direction)) continue;
            for (int activeIndex = 0; activeIndex < (usesActive ? 2 : 1); activeIndex++) {
                boolean active = activeIndex == 1;
                for (int formedIndex = 0; formedIndex < (usesFormed ? 2 : 1); formedIndex++) {
                    boolean formed = formedIndex == 1;
                    BlockModelBuilder model = active && formed ? bothModel : active ? activeModel
                            : formed ? formedModel : plain;

                    var partial = variants.partialState().with(property, direction);
                    if (usesActive) partial = partial.with(MachineBlock.ACTIVE, active);
                    if (usesFormed) partial = partial.with(MachineBlock.FORMED, formed);
                    partial.setModels(ConfiguredModel.builder()
                            .modelFile(model)
                            .rotationX(directionalRotationX(direction))
                            .rotationY(directionalRotationY(direction))
                            .build());
                }
            }
        }
    }

    /** {@code BlockStateProvider#directionalBlock} 的 X 旋转：上/下两个朝向各转 90/-90，其余不转。 */
    private static int directionalRotationX(Direction direction) {
        return direction == Direction.DOWN ? 90 : direction == Direction.UP ? -90 : 0;
    }

    /** {@code BlockStateProvider#directionalBlock} 的 Y 旋转：水平方向用 {@link Direction#toYRot()}。 */
    private static int directionalRotationY(Direction direction) {
        return direction.getAxis().isHorizontal() ? (int) direction.toYRot() : 0;
    }

    /**
     * 造一份「底盘 + 贴在朝向那一面的若干层」的模型。
     *
     * <p>
     * ⚠️ 两个硬约束决定了这里的做法：
     * <ol>
     * <li>MC 的模型继承里<b>子模型一旦自带 elements，父模型的 elements 会被整个替换</b>，
     * 所以底盘和每一层都自己写元素，不能用 {@code cubeAll(...).element()...}；</li>
     * <li>元素的坐标必须在 {@code [-16, 32]} 之内 —— 所以「往外凸一点点」这种做法在朝下/朝北/朝西
     * 三面会直接越界报 {@code Position out of range}。这里改成<b>把底盘整体缩进</b>
     * （{@code LAYER_STEP * (层数 + 1)}，最多 0.005 格，肉眼看不出来），层则依次向外排到方块表面为止。</li>
     * </ol>
     *
     * <p>层序（下 → 上）：正面覆盖层 → 成型层 → 发光层 → 口；后画的层更靠外，于是能盖住前面的层
     * （覆盖层是 cutout 的，透明像素被丢弃，所以下面的层在没画东西的地方依然看得见）。
     *
     * <p>方向参数已经没了：层一律画在 {@link #CANONICAL_FACE}，六个朝向由 blockstate 的
     * {@code x}/{@code y} 旋转复用这一份模型（原版 / GTM 的做法）。
     */
    private BlockModelBuilder layeredModel(MachineDefinition definition,
                                           ResourceLocation baseTexture, boolean active, boolean formed) {
        List<Layer> layers = new ArrayList<>();
        if (definition.getOverlayTexture() != null) {
            layers.add(new Layer("ov", "overlay", definition.getOverlayTexture()));
        }
        if (formed && definition.getFormedOverlayTexture() != null) {
            layers.add(new Layer("formed", "overlay_formed", definition.getFormedOverlayTexture()));
        }
        if (active && definition.getEmissiveOverlayTexture() != null) {
            layers.add(new Layer("act", "overlay_active", definition.getEmissiveOverlayTexture()));
        }
        if (definition.hasPort()) {
            layers.add(new Layer("port", "port", definition.getPortTexture()));
        }

        StringBuilder name = new StringBuilder(definition.getId().getPath());
        for (Layer layer : layers) {
            name.append('_').append(layer.namePart());
        }

        BlockModelBuilder model = models().getBuilder(name.toString()).texture("all", baseTexture);
        if (definition.isPortCutout() || definition.isOverlayCutout()) {
            // 覆盖层/口通常带透明像素，走 cutout 才不会把底下的底盘糊掉
            model.renderType("cutout");
        }

        // ⓪ 底盘：0..16 的整块，六面同一个贴图 + cullface（和原版 cube_all / GTM 机器模板一致）
        model.element()
                .from(0, 0, 0)
                .to(16, 16, 16)
                .allFaces((dir, face) -> face.texture("#all").cullface(dir))
                .end();

        // ①…各层：零厚度平面，一层比一层往外 0.01（GTM 的 hatch_machine.json 就是这么写的）
        int index = 0;
        for (Layer layer : layers) {
            addFlatLayer(model, CANONICAL_FACE, layer.textureKey(), layer.texture(), ++index);
        }
        return model;
    }

    /**
     * 模型里的一层。
     *
     * @param namePart   文件名里的短标签（{@code ov} / {@code formed} / {@code act} / {@code port}）
     * @param textureKey 模型里的纹理键（{@code overlay} / {@code overlay_formed} / …）
     * @param texture    贴图
     */
    private record Layer(String namePart, String textureKey, ResourceLocation texture) {}

    /**
     * 往模型里加一层「贴在某一面外侧的零厚度平面」——照抄 GTM {@code hatch_machine.json} 的写法：
     * {@code "from": [0,0,-0.01], "to": [16,16,-0.01]}，也就是<b>在朝外的那个轴上 from == to</b>。
     *
     * <p>
     * 三个要点：
     * <ol>
     * <li><b>零厚度</b>：只定义朝外那一个 face，其余 5 面不写 = 不渲染 →「只渲染这一面」；</li>
     * <li><b>显式 UV</b> {@code [0,0,16,16]}：与 GTM 模板一致，保证贴图整面铺满
     * （不写 UV 时 MC 会按元素包围盒自动推导，退化元素上容易得到意外的映射）；</li>
     * <li><b>层次靠 {@code offset}</b>：第 n 层在方块表面外 {@code 0.01 * n} 处，坐标只会在
     * {@code [0, 16 + 0.0x]} 或 {@code [-0.0x, 16]} 之间，绝不会碰到 {@code [-16, 32]} 的边界。</li>
     * </ol>
     */
    private static void addFlatLayer(BlockModelBuilder model, Direction side, String textureKey,
                                     ResourceLocation texture, int index) {
        model.texture(textureKey, texture);
        float offset = LAYER_OFFSET * index;
        var element = model.element();
        switch (side) {
            case UP -> element.from(0, 16 + offset, 0).to(16, 16 + offset, 16);
            case DOWN -> element.from(0, -offset, 0).to(16, -offset, 16);
            case SOUTH -> element.from(0, 0, 16 + offset).to(16, 16, 16 + offset);
            case NORTH -> element.from(0, 0, -offset).to(16, 16, -offset);
            case EAST -> element.from(16 + offset, 0, 0).to(16 + offset, 16, 16);
            case WEST -> element.from(-offset, 0, 0).to(-offset, 16, 16);
        }
        element.face(side).texture("#" + textureKey).uvs(0, 0, 16, 16).end().end();
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
