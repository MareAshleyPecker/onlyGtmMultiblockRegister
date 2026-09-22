package rain.fox.ogmr.api.registry.builder;

import rain.fox.ogmr.Ogmr;
import rain.fox.ogmr.api.block.MachineBlock;
import rain.fox.ogmr.api.blockentity.MachineBlockEntity;
import rain.fox.ogmr.api.gui.MachineUI;
import rain.fox.ogmr.api.item.MachineItem;
import rain.fox.ogmr.api.lang.OGMRLang;
import rain.fox.ogmr.api.machine.IMachineBlockEntity;
import rain.fox.ogmr.api.machine.MachineDefinition;
import rain.fox.ogmr.api.machine.MetaMachine;
import rain.fox.ogmr.api.recipe.OGMRRecipeType;
import rain.fox.ogmr.api.registry.MachineRegistrar;
import rain.fox.ogmr.api.registry.OGMRRegistries;
import rain.fox.ogmr.utils.Formatting;
import rain.fox.ogmr.utils.ResourceLocations;

import lombok.Getter;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraftforge.registries.RegistryObject;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * 机器注册 builder —— 本库的「注册逻辑」主干。
 *
 * <p>
 * 它把「一台机器」拆成四件必须一起注册的东西，并保证它们互相引用正确：
 * <ol>
 * <li>{@link MachineDefinition} —— 定义对象（不是 Forge 注册项，进 {@link OGMRRegistries#MACHINES}）；</li>
 * <li>机器方块 —— 进 addon 的 {@code BLOCKS}；</li>
 * <li>机器物品 —— 进 addon 的 {@code ITEMS}；</li>
 * <li>方块实体类型 —— 进 addon 的 {@code BLOCK_ENTITY_TYPES}。</li>
 * </ol>
 *
 * <p>
 * 后三者用 {@code DeferredRegister} 登记，所以 {@link #register()} 可以在 mod 构造期随时调用，
 * 真正的 Forge 注册发生在 {@code RegisterEvent}。
 *
 * <p>
 * <b>链式用法</b>：
 * <pre>{@code
 * public static final MachineDefinition COMPRESSOR =
 *     REGISTRAR.machine("compressor", SimpleMachine::new)
 *         .tier(OGMRValues.LV)
 *         .recipeType(MyRecipeTypes.COMPRESSING)
 *         .tooltips(Component.translatable("block.mymod.compressor.tooltip"))
 *         .register();
 * }</pre>
 *
 * @param <D> 定义类型（{@link MachineDefinition} 或 {@link rain.fox.ogmr.api.machine.MultiblockMachineDefinition}）
 * @param <B> 自身类型（用于链式返回）
 */
public class MachineBuilder<D extends MachineDefinition, B extends MachineBuilder<D, B>> {

    protected final MachineRegistrar registrar;
    @Getter
    protected final String name;
    @Getter
    protected final ResourceLocation id;
    protected final Function<IMachineBlockEntity, ? extends MetaMachine> machineFactory;
    protected final Function<ResourceLocation, D> definitionFactory;

    // ── 可链式配置的字段 ──
    protected final List<OGMRRecipeType> recipeTypes = new ArrayList<>();
    protected int tier = 0;
    protected VoxelShape shape = Shapes.block();
    protected Supplier<BlockState> appearance;
    protected BiConsumer<ItemStack, List<Component>> tooltipBuilder;
    protected final List<Component> tooltips = new ArrayList<>();
    protected int defaultPaintingColor = 0xFFFFFFFF;
    protected float hardness = 3.5f;
    protected float resistance = 3.5f;
    protected boolean noItem = false;
    /** 可编辑 UI（需求 1）：非空时机器面板优先读 assets/<ns>/ui/machine/<path>.mui。 */
    protected rain.fox.ogmr.api.gui.editor.EditableMachineUI editableUI;
    /** 机器界面（右键打开）；null = register() 时自动造一个零配置界面。 */
    protected rain.fox.ogmr.api.gui.MachineUI machineUI;
    /** 方块是否交给 BER 渲染（默认 false = 静态模型）。 */
    protected boolean useEntityRenderer;
    /** 英文显示名；null = datagen 时按 id 自动推导。 */
    protected String langValue;
    /** 中文显示名；null = 中文语言文件里回退成英文。 */
    protected String langValueZh;
    /** 方块模型的贴图（六面同贴图）；数据生成时用来产出 blockstates / models JSON。 */
    protected ResourceLocation modelTexture;

    // ── 注册结果 ──
    @Getter
    protected RegistryObject<Block> blockObject;
    @Getter
    protected RegistryObject<Item> itemObject;
    @Getter
    protected RegistryObject<BlockEntityType<?>> blockEntityObject;
    /** 注册之后的定义对象；未注册时为 null。 */
    @Getter
    protected D definition;

    public MachineBuilder(MachineRegistrar registrar, String name,
                          Function<IMachineBlockEntity, ? extends MetaMachine> machineFactory,
                          Function<ResourceLocation, D> definitionFactory) {
        this.registrar = registrar;
        this.name = name;
        this.id = ResourceLocations.of(registrar.getModId(), name);
        this.machineFactory = machineFactory;
        this.definitionFactory = definitionFactory;
    }

    @SuppressWarnings("unchecked")
    protected B self() {
        return (B) this;
    }

    // ═══════════════ 链式配置 ═══════════════

    /** 机器等级（用于物品染色、tooltip 与默认电压）。 */
    public B tier(int tier) {
        this.tier = tier;
        return self();
    }

    /** 这台机器能跑的配方类型（可以有多个）。 */
    public B recipeType(OGMRRecipeType type) {
        this.recipeTypes.add(type);
        return self();
    }

    public B recipeTypes(OGMRRecipeType... types) {
        this.recipeTypes.addAll(List.of(types));
        return self();
    }

    /** 碰撞箱（多方块控制器一般用 {@code Shapes.block()}）。 */
    public B shape(VoxelShape shape) {
        this.shape = shape;
        return self();
    }

    /** 外观方块状态（JEI 预览与仓室外观用它）。 */
    public B appearance(Supplier<BlockState> appearance) {
        this.appearance = appearance;
        return self();
    }

    /** 用某个方块的默认状态当外观。 */
    public B appearanceBlock(Supplier<? extends Block> block) {
        return appearance(() -> block.get().defaultBlockState());
    }

    public B tooltips(Component... components) {
        this.tooltips.addAll(List.of(components));
        return self();
    }

    public B tooltipBuilder(BiConsumer<ItemStack, List<Component>> builder) {
        this.tooltipBuilder = builder;
        return self();
    }

    public B defaultPaintingColor(int color) {
        this.defaultPaintingColor = color;
        return self();
    }

    public B blockProperties(float hardness, float resistance) {
        this.hardness = hardness;
        this.resistance = resistance;
        return self();
    }

    /** 不生成对应的物品（占位方块用）。 */
    public B noItem() {
        this.noItem = true;
        return self();
    }

    /**
     * 指定可编辑 UI（需求 1：机器 UI 绘制）。
     *
     * <p>
     * 一般这样拿：{@code .editableUI(MachineUI.create(name, Ogmr.id(name)).size(176,166)...buildEditable())}
     */
    public B editableUI(rain.fox.ogmr.api.gui.editor.EditableMachineUI ui) {
        this.editableUI = ui;
        return self();
    }

    /**
     * 指定英文显示名（不指定则按 id 自动推导，例如 {@code lv_item_bus → "Lv Item Bus"}）。
     *
     * <p>
     * 只给英文时中文语言文件回退成同一个名字；要中文名用 {@link #langValue(String, String)}。
     */
    public B langValue(String englishName) {
        this.langValue = englishName;
        return self();
    }

    /**
     * 指定机器界面（右键机器打开的那个）。
     *
     * <p>
     * 不指定也没关系 —— {@link #register()} 会给一个
     * {@link rain.fox.ogmr.api.gui.MachineUI#createDefault} 的零配置界面
     * （标题 + 玩家背包 + 按机器仓储自动摆的槽位），所以「机器注册了但右键没反应」不会发生。
     * 想要自己的布局就在这里给：
     * <pre>{@code
     * .ui(MachineUI.create("maceration", MyIds.id("maceration"))
     *         .title()
     *         .itemSlot(26, 20, 0, true)
     *         .progress(62, 33, 24, 16, ProgressDirection.LEFT_TO_RIGHT, machine::getProgress)
     *         .playerInventory(8, 84))
     * }</pre>
     */
    public B ui(rain.fox.ogmr.api.gui.MachineUI ui) {
        this.machineUI = ui;
        if (ui != null) {
            this.editableUI = ui.buildEditable();
        }
        return self();
    }

    /**
     * 方块是否交给方块实体渲染（BER）。
     *
     * <p>默认 false（静态模型，走数据生成的 blockstates/models）。⚠️ 设 true 之前请先注册 BER，
     * 否则方块在世界里<b>什么都不画</b>。
     */
    public B entityRenderer(boolean useEntityRenderer) {
        this.useEntityRenderer = useEntityRenderer;
        return self();
    }

    /**
     * 指定中英显示名 —— 一个机器名同时管住 {@code en_us} 与 {@code zh_cn}。
     *
     * <p>
     * 中文走 {@link OGMRLang}（键 {@code block.<ns>.<name>}），数据生成时写进 {@code zh_cn.json}；
     * 因为用的是 {@code putIfAbsent}，addon 之后再手工 {@code OGMRLang.add} 同一键也不会打架。
     */
    public B langValue(String englishName, String chineseName) {
        this.langValue = englishName;
        this.langValueZh = chineseName;
        return self();
    }

    /**
     * 指定方块模型的贴图（六面同贴图）。
     *
     * <p>
     * 指定之后 {@code gradlew runData} 会自动产出这个机器的
     * {@code blockstates/<name>.json} + {@code models/block/<name>.json} + {@code models/item/<name>.json}，
     * 不用自己手写。不指定则用兜底贴图（铁块），免得渲染成紫黑块。
     */
    public B modelTexture(ResourceLocation texture) {
        this.modelTexture = texture;
        return self();
    }

    // ═══════════════ 注册 ═══════════════

    /**
     * 真正注册：创建定义对象 + 登记方块/物品/方块实体。
     *
     * @return 注册好的定义对象
     */
    public D register() {
        D definition = definitionFactory.apply(id);

        // 先填基本属性
        definition.setTier(tier);
        definition.setRecipeTypes(recipeTypes.toArray(new OGMRRecipeType[0]));
        definition.setShape(shape);
        definition.setMachineSupplier(machineFactory::apply);
        definition.setDefaultPaintingColor(defaultPaintingColor);

        // 方块 / 物品 / 方块实体
        this.blockObject = registrar.blocks().register(name,
                () -> new MachineBlock(machineBlockProperties(), definition));
        if (!noItem) {
            this.itemObject = registrar.items().register(name,
                    () -> new MachineItem(definition, new Item.Properties()));
        }
        this.blockEntityObject = registrar.blockEntities().register(name,
                () -> BlockEntityType.Builder
                        .of((pos, state) -> new MachineBlockEntity(definition, pos, state), blockObject.get())
                        .build(null));

        definition.setBlockSupplier(blockObject);
        if (itemObject != null) definition.setItemSupplier(itemObject);
        definition.setBlockEntityTypeSupplier(blockEntityObject);
        definition.setAppearance(appearance != null ? appearance
                : () -> blockObject.get().defaultBlockState());
        if (editableUI != null) definition.setEditableUI(editableUI);
        if (modelTexture != null) definition.setModelTexture(modelTexture);
        if (langValue != null) definition.setLangValue(langValue);
        // UI 自动注册：addon 没给界面就给一个零配置的（标题 + 背包 + 按仓储自动摆的槽位），
        // 保证「机器注册完右键就能开出界面」。
        // 只写了 .editableUI(ui.buildEditable()) 的 addon 也能被认出（EditableMachineUI 记了 owner）。
        MachineUI resolvedUI = machineUI != null ? machineUI
                : editableUI != null && editableUI.getOwner() != null ? editableUI.getOwner()
                : MachineUI.createDefault(name, id);
        definition.setMachineUI(resolvedUI);
        definition.setUseEntityRenderer(useEntityRenderer);
        if (langValueZh != null) {
            String englishName = langValue != null ? langValue : Formatting.toEnglishName(id.getPath());
            OGMRLang.add("block." + id.getNamespace() + "." + id.getPath(), englishName, langValueZh);
        }

        // tooltip：显式给的 tooltips 优先，其次 tooltipBuilder
        if (!tooltips.isEmpty() || tooltipBuilder != null) {
            List<Component> fixed = List.copyOf(tooltips);
            BiConsumer<ItemStack, List<Component>> extra = tooltipBuilder;
            definition.setTooltipBuilder((stack, list) -> {
                list.addAll(fixed);
                if (extra != null) extra.accept(stack, list);
            });
        }

        // 进注册表
        OGMRRegistries.MACHINES.register(id, definition);

        this.definition = definition;
        return definition;
    }

    protected net.minecraft.world.level.block.state.BlockBehaviour.Properties machineBlockProperties() {
        return net.minecraft.world.level.block.state.BlockBehaviour.Properties.of()
                .strength(hardness, resistance)
                .noOcclusion();
    }

    /** 调试用：把定义打到日志里。 */
    protected void logRegistered() {
        Ogmr.LOGGER.debug("ogmr: registered machine {} ({})", id, definition != null ? definition.getClass().getSimpleName() : "?");
    }
}
