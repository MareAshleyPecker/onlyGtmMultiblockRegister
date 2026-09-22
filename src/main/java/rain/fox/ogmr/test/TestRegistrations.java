package rain.fox.ogmr.test;

import rain.fox.ogmr.Ogmr;
import rain.fox.ogmr.api.addon.AddonBootstrap;
import rain.fox.ogmr.api.energy.EnergyHatchPartMachine;
import rain.fox.ogmr.api.energy.EnergyHatchSize;
import rain.fox.ogmr.api.energy.EnergyHatchSizes;
import rain.fox.ogmr.api.energy.EnergyTypes;
import rain.fox.ogmr.api.gui.MachineUI;
import rain.fox.ogmr.api.gui.ProgressDirection;
import rain.fox.ogmr.api.lang.OGMRLang;
import rain.fox.ogmr.api.machine.MachineDefinition;
import rain.fox.ogmr.api.machine.MultiblockMachineDefinition;
import rain.fox.ogmr.api.machine.multiblock.PartAbility;
import rain.fox.ogmr.api.OGMRValues;
import rain.fox.ogmr.api.pattern.BlockPattern;
import rain.fox.ogmr.api.pattern.FactoryBlockPattern;
import rain.fox.ogmr.api.pattern.Predicates;
import rain.fox.ogmr.api.pattern.util.IO;
import rain.fox.ogmr.api.recipe.OGMRRecipeType;
import rain.fox.ogmr.api.registry.MachineRegistrar;
import rain.fox.ogmr.api.registry.OGMRCreativeTab;
import rain.fox.ogmr.threading.ThreadedHatches;
import rain.fox.ogmr.utils.ResourceLocations;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

/**
 * 测试包的全部注册内容 —— {@code rain.fox.ogmr.test}。
 *
 * <p>
 * ⚠️ 这个包<b>不是库的一部分</b>，是为了让你能在游戏里直接验证库的几条主链路而准备的。
 * 发布给玩家时可以整个删掉（或把 {@code test} 包排除出 jar）。
 *
 * <h3>注册出来的东西</h3>
 * <table border="1">
 * <caption>测试内容</caption>
 * <tr><th>注册名</th><th>是什么</th><th>验证哪条链路</th></tr>
 * <tr><td>{@code ogmr:test_multiblock}</td><td>3×3×3 多线程多方块</td>
 * <td>结构图案 + 多方块注册 + 多线程配方 + 仓室能力</td></tr>
 * <tr><td>{@code ogmr:lv_item_input_bus}</td><td>物品输入总线（9 格）</td><td>仓室注册 + Forge 能力分发</td></tr>
 * <tr><td>{@code ogmr:lv_item_output_bus}</td><td>物品输出总线（9 格）</td><td>同上</td></tr>
 * <tr><td>{@code ogmr:lv_energy_hatch}</td><td>能源输入仓</td><td>能量系统（EU/FE/AE/RF/J 显示）</td></tr>
 * <tr><td>{@code ogmr:lv_thread_hatch} 等</td><td>线程仓（一整排档位）</td><td>多线程</td></tr>
 * <tr><td>{@code ogmr:test_recipes}</td><td>配方类型 + 2 条配方</td><td>RecipeType / 配方注册</td></tr>
 * </table>
 *
 * <p>
 * 结构（三层，从后到前；{@code X} = 铁块，中间那层留空腔，{@code S} = 控制器）：
 * <pre>
 *   XXX   XXX   XXX
 *   XXX   X X   XSX      ← 中间层的中心是空气，仓室插在四壁上
 *   XXX   XXX   XXX
 * </pre>
 */
public final class TestRegistrations {

    private TestRegistrations() {}

    /** 测试包自己的注册器。 */
    public static final MachineRegistrar REGISTRAR = new MachineRegistrar(Ogmr.MOD_ID);

    /** 测试用的配方类型。 */
    public static final OGMRRecipeType TEST_RECIPES = OGMRRecipeType.register(
            Ogmr.id("test_recipes"), "ogmr_test");

    /**
     * 测试用的<b>发电机</b>配方类型 —— 声明 {@code energyIO(IO.OUT)}。
     *
     * <p>
     * 这一行就是「用 IO 设置机器类型」的全部成本：声明之后，
     * <ul>
     * <li>{@code Predicates.autoAbilities(...)} 会自动要求结构里放<b>能源输出仓</b>而不是输入仓；</li>
     * <li>{@code MultiblockMachineBuilder} 会自动把这台多方块标成发电机（{@code isGenerator() == true}）。</li>
     * </ul>
     */
    public static final OGMRRecipeType TEST_GENERATOR_RECIPES = OGMRRecipeType
            .register(Ogmr.id("test_generator_recipes"), "ogmr_test")
            .energyIO(IO.OUT);

    /** 外壳方块（用原版铁块，省得再造一套方块与贴图）。 */
    public static final Block CASING = Blocks.IRON_BLOCK;

    /** 模型贴图的快捷写法：直接借用原版方块贴图（测试机不配美术资源）。 */
    private static ResourceLocation vanillaTexture(String block) {
        return ResourceLocations.of("minecraft", "block/" + block);
    }

    /** 3×3×3 多线程测试机（用电器）。 */
    public static MultiblockMachineDefinition TEST_MULTIBLOCK;

    /** 3×3×3 测试发电机（同样的结构，但配方类型声明了 {@code IO.OUT}，机器类型自动变发电机）。 */
    public static MultiblockMachineDefinition TEST_GENERATOR;

    /** 物品总线。 */
    public static MachineDefinition ITEM_INPUT_BUS;
    public static MachineDefinition ITEM_OUTPUT_BUS;

    /** 能源仓（输入/输出各一个档位，验证能量单位显示）。 */
    public static MachineDefinition ENERGY_INPUT_HATCH;
    public static MachineDefinition ENERGY_OUTPUT_HATCH;

    // ═══════════════ 注册 ═══════════════

    /**
     * 登记全部测试内容。由 {@link TestAddon} 在对应阶段调用，不要自己调。
     */
    static void registerAll() {
        registerMachineDefinitions();
        registerRecipes();
        initLang();
        // 自定义内容种类的 codec 自检（结果在日志里：content kind round-trip ... equal=true）
        TestContentKinds.selfCheck();
        // 界面自动注册自检（结果在日志里：UI of ogmr:xxx = 176x166, entries=.., autoLayout=..）
        logUiSummary();
    }

    /** 把每台测试机器的界面情况打到日志 —— datagen 阶段就会跑，不用进游戏才知道。 */
    static void logUiSummary() {
        for (MachineDefinition definition : new MachineDefinition[] { ITEM_INPUT_BUS, ITEM_OUTPUT_BUS,
                ENERGY_INPUT_HATCH, ENERGY_OUTPUT_HATCH, TEST_MULTIBLOCK, TEST_GENERATOR }) {
            if (definition == null) continue;
            Ogmr.LOGGER.info("ogmr test pack: UI of {} = {}", definition.getId(), describeUi(definition));
        }
    }

    /**
     * 建一个创造模式物品栏标签 —— 不建的话这些机器在物品栏里一个都看不到（只能用 {@code /give}）。
     * 由 {@link TestAddon#initialize()} 调用（那里才拿得到 mod 事件总线）。
     */
    static void registerCreativeTab() {
        OGMRCreativeTab.register(AddonBootstrap.modBus(), Ogmr.MOD_ID, "main",
                "itemGroup." + Ogmr.MOD_ID, REGISTRAR);
    }

    /** 阶段①：仓室能力要先于机器（多方块的 {@code autoAbilities} 依赖这些表）。 */
    static void registerAbilities() {
        // 标准能力（IMPORT_ITEMS / EXPORT_ITEMS / INPUT_ENERGY / OUTPUT_ENERGY）在 PartAbility 里已内置；
        // 线程仓的能力由 ThreadedHatches 自己登记。这里只是把「测试包用到的能力」显式点一下，
        // 保证即使将来标准常量被挪走，测试包也能自己把它们建出来。
        PartAbility.create("import_items");
        PartAbility.create("export_items");
        PartAbility.create("input_energy");
        PartAbility.create("output_energy");
    }

    /** 阶段②：机器与多方块。 */
    static void registerMachineDefinitions() {
        // ── 能源仓的尺寸表：库里只内置「可选预设」，不自动注册，所以要显式开一次 ──
        //    （Modular Machinery 的 8 级数值；不想要就自己 EnergyHatchSizes.register(...)）
        EnergyHatchSizes.registerDefaultPreset();
        // 取「小型」那档；拿不到就退回第 0 档。final 是给下面的 lambda 用的（lambda 捕获要求 effectively final）
        EnergyHatchSize preset = EnergyHatchSizes.get("small");
        final EnergyHatchSize hatchSize = preset != null ? preset : EnergyHatchSizes.of(0);

        // ── 物品总线 ──
        ITEM_INPUT_BUS = REGISTRAR
                .part("lv_item_input_bus", holder -> new TestItemBusPartMachine(holder, true))
                .tier(OGMRValues.LV)
                .abilities(PartAbility.IMPORT_ITEMS)
                .tooltips(Component.translatable("block.ogmr.lv_item_input_bus.tooltip"))
                .langValue("Test Item Input Bus", "测试物品输入总线")
                .modelTexture(vanillaTexture("iron_block"))
                .register();

        ITEM_OUTPUT_BUS = REGISTRAR
                .part("lv_item_output_bus", holder -> new TestItemBusPartMachine(holder, false))
                .tier(OGMRValues.LV)
                .abilities(PartAbility.EXPORT_ITEMS)
                .tooltips(Component.translatable("block.ogmr.lv_item_output_bus.tooltip"))
                .langValue("Test Item Output Bus", "测试物品输出总线")
                .modelTexture(vanillaTexture("iron_block"))
                .register();

        // ── 能源仓（两个都想验单位换算：输入仓按 EU 显示，输出仓按 RF 显示） ──
        ENERGY_INPUT_HATCH = REGISTRAR
                .part("lv_energy_hatch",
                        holder -> new EnergyHatchPartMachine(holder, OGMRValues.LV, hatchSize, EnergyTypes.EU, true))
                .tier(OGMRValues.LV)
                .abilities(PartAbility.INPUT_ENERGY)
                .langValue("Test Energy Input Hatch (EU display)", "测试能源输入仓（按 EU 显示）")
                .modelTexture(vanillaTexture("copper_block"))
                .register();

        ENERGY_OUTPUT_HATCH = REGISTRAR
                .part("lv_energy_output_hatch",
                        holder -> new EnergyHatchPartMachine(holder, OGMRValues.LV, hatchSize, EnergyTypes.RF, false))
                .tier(OGMRValues.LV)
                .abilities(PartAbility.OUTPUT_ENERGY)
                .langValue("Test Energy Output Hatch (RF display)", "测试能源输出仓（按 RF 显示）")
                .modelTexture(vanillaTexture("copper_block"))
                .register();

        // ── 线程仓 ──
        //    默认那张表（ThreadedHatches.registerThreadHatches(registrar)）是从 ZPM 起跳的，
        //    测试时拿不到那么高的档位，所以这里显式指定一组低档位：
        //    lv_thread_hatch=2 线程 / mv=4 / hv=8 —— 摆 3 个 LV 仓就是 6 条线程，肉眼能看出并行。
        ThreadedHatches.registerThreadHatches(REGISTRAR,
                new int[] { OGMRValues.LV, OGMRValues.MV, OGMRValues.HV },
                new int[] { 2, 4, 8 });

        // ── 多方块控制器 ──
        TEST_MULTIBLOCK = REGISTRAR
                .multiblock("test_multiblock", TestMultiblockMachine::new)
                .tier(OGMRValues.MV)
                .recipeType(TEST_RECIPES)
                .appearanceBlock(() -> CASING)
                .tooltips(
                        Component.translatable("block.ogmr.test_multiblock.tooltip.0"),
                        Component.translatable("block.ogmr.test_multiblock.tooltip.1"))
                .pattern(TestRegistrations::testPattern)
                // 面板上多显示一行「这是测试机」
                .additionalDisplay((controller, lines) -> lines.add(
                        Component.translatable("block.ogmr.test_multiblock.tooltip.0")))
                .langValue("Test Multiblock (threaded)", "测试多方块（多线程）")
                // 显式界面：标题 + 进度条 + 线程状态文本（演示「addon 自己配 UI」这条通道；
                // 不写这行的话 builder 会给一个零配置界面 —— 标题 + 背包 + 按仓储自动摆的槽位）
                .ui(MachineUI.create("test_multiblock", Ogmr.id("test_multiblock"))
                        .title()
                        .progress(62, 33, 24, 16, ProgressDirection.LEFT_TO_RIGHT,
                                machine -> machine instanceof TestMultiblockMachine multi
                                        ? multi.getRecipeLogic().getProgressPercent()
                                        : 0d)
                        .text(8, 58, machine -> machine instanceof TestMultiblockMachine multi
                                ? Component.translatable("ogmr.test.ui.threads", multi.getMaxThreads())
                                : Component.empty())
                        .playerInventory(8, 84))
                .modelTexture(vanillaTexture("iron_block"))
                .register();

        // ── 测试发电机：结构一模一样，只有配方类型的能量方向不同 ──
        //    注意这里**没有**调 .generator(true)：机器类型是从
        //    TEST_GENERATOR_RECIPES.isGenerator()（= energyIO(IO.OUT)）自动推出来的，
        //    同时 autoAbilities 也自动改成了「要能源输出仓」。这两点就是这次改动的验收点。
        TEST_GENERATOR = REGISTRAR
                .multiblock("test_generator", TestMultiblockMachine::new)
                .tier(OGMRValues.MV)
                .recipeType(TEST_GENERATOR_RECIPES)
                .appearanceBlock(() -> CASING)
                .tooltips(Component.translatable("block.ogmr.test_generator.tooltip"))
                .langValue("Test Generator (energy out)", "测试发电机（产能）")
                .modelTexture(vanillaTexture("gold_block"))
                .pattern(TestRegistrations::testPattern)
                .register();
    }

    /**
     * 测试机共用的 3×3×3 结构图案（放在这里是为了让两台机器共用一份，避免复制粘贴走样）。
     */
    private static BlockPattern testPattern(MultiblockMachineDefinition def) {
        return FactoryBlockPattern.start()
                // 第 1 层（后）：整面铁块
                .aisle("XXX", "XXX", "XXX")
                // 第 2 层（中）：中心空腔，仓室插在四壁
                .aisle("XXX", "X X", "XXX")
                // 第 3 层（前）：控制器在正面正中
                .aisle("XXX", "XSX", "XXX")
                .where('S', Predicates.controller(Predicates.blocks(def.getBlock())))
                .where('X', Predicates.blocks(CASING)
                        .setMinGlobalLimited(10)
                        .or(Predicates.autoAbilities(def.getRecipeTypes())))
                .where(' ', Predicates.air())
                .build();
    }

    /** 阶段③：配方（运行时直接进 RecipeBuilder.ALL_RECIPES，不依赖数据生成）。 */
    static void registerRecipes() {
        // 1 铁锭 → 1 金锭，100 tick，HV 耗电。
        // save(consumer) 会同时把配方写进 RecipeBuilder.ALL_RECIPES，
        // 所以不进数据生成也能跑 —— 测试时把这个 consumer 当垃圾桶即可。
        TEST_RECIPES.recipeBuilder("iron_to_gold")
                .input(new ItemStack(Items.IRON_INGOT, 1))
                .output(new ItemStack(Items.GOLD_INGOT, 1))
                .duration(100)
                .eut(OGMRValues.V[OGMRValues.HV])
                .save(finished -> {});

        // 1 圆石 → 1 石头，40 tick，LV 耗电（验证「同一台机器跑两种配方」+ 多线程各跑各的）。
        TEST_RECIPES.recipeBuilder("cobble_to_stone")
                .input(new ItemStack(Items.COBBLESTONE, 1))
                .output(new ItemStack(Items.STONE, 1))
                .duration(40)
                .eut(OGMRValues.V[OGMRValues.LV])
                .save(finished -> {});

        // 1 沙 → 1 玻璃，20 tick，ULV（最快的配方，方便肉眼看出线程并行）。
        TEST_RECIPES.recipeBuilder("sand_to_glass")
                .input(new ItemStack(Items.SAND, 1))
                .output(new ItemStack(Items.GLASS, 1))
                .duration(20)
                .eut(OGMRValues.V[OGMRValues.ULV])
                .save(finished -> {});

        // ── 自定义内容种类的配方：验证「第三方内容种类」这条链路 ──
        //    输入是 TestContentKinds.TEST_ENERGY（库不认识的种类），产出照旧是物品。
        //    它能进运行时配方表、能写进配方 JSON、也能走网络 —— 全部由种类自己实现。
        TEST_RECIPES.recipeBuilder("energy_charge")
                .input(TestContentKinds.energy(500))
                .output(new ItemStack(Items.DIAMOND, 1))
                .duration(60)
                .eut(OGMRValues.V[OGMRValues.LV])
                .save(finished -> {});

        // ── 发电机配方：eut 为<b>负</b>表示产电（见 OGMRRecipe#isGenerator）──
        //    1 煤炭 → 无产物，80 tick，产能 MV（-128 EU/t）。
        //    这条配方配合 TEST_GENERATOR_RECIPES 的 energyIO(IO.OUT)：
        //    结构里要放能源输出仓，机器类型自动是发电机。
        TEST_GENERATOR_RECIPES.recipeBuilder("coal_generator")
                .input(new ItemStack(Items.COAL, 1))
                .duration(80)
                .eut(-OGMRValues.V[OGMRValues.MV])
                .save(finished -> {});
    }

    /** 阶段④：语言（进 OGMRLang，跑 runData 时写出中英两份）。 */
    static void initLang() {
        TestItemBusPartMachine.initLang();
        TestMultiblockMachine.initLang();
        TestContentKinds.initLang();

        // 机器名（block.ogmr.*）不在这里写 —— 上面每个 builder 的 .langValue(en, zh) 已经登记掉了，
        // 这里只补 tooltip 这类机器名之外的文案。
        OGMRLang.add("itemGroup." + Ogmr.MOD_ID,
                "Only GT Multiblock Register", "格雷多方块注册库");
        OGMRLang.add("ogmr.test.ui.threads",
                "Thread hatches installed · max %s threads", "已装线程仓 · 最多 %s 条线程");
        OGMRLang.add("block.ogmr.test_multiblock.tooltip.0",
                "ogmr test machine — verifies structure, hatches, threading and energy.",
                "ogmr 测试机 —— 验证结构、仓室、多线程与能量。");
        OGMRLang.add("block.ogmr.test_multiblock.tooltip.1",
                "3x3x3 iron shell; insert item buses, an energy hatch and thread hatches.",
                "3×3×3 铁块外壳；插入物品总线、能源仓与线程仓。");
        OGMRLang.add("block.ogmr.lv_item_input_bus.tooltip",
                "Puts items into the multiblock (hoppers/pipes can feed this side).",
                "把物品送进多方块（漏斗/管道可以喂这一面）。");
        OGMRLang.add("block.ogmr.lv_item_output_bus.tooltip",
                "Takes products out of the multiblock.",
                "把产物从多方块里取出来。");
        OGMRLang.add("block.ogmr.test_generator.tooltip",
                "Same 3x3x3 shell, but its recipe type declares IO.OUT — so it takes an ENERGY OUTPUT hatch and is flagged as a generator automatically.",
                "同样的 3×3×3 外壳，但配方类型声明了 IO.OUT —— 所以它自动要能源输出仓，并被标记为发电机。");
    }

    /** 调试：把注册结果打到日志。 */
    static void logSummary() {
        Ogmr.LOGGER.info("ogmr test pack: multiblock={}, inputBus={}, outputBus={}, energyIn={}, energyOut={}, threads={}",
                TEST_MULTIBLOCK != null ? TEST_MULTIBLOCK.getId() : "null",
                ITEM_INPUT_BUS != null ? ITEM_INPUT_BUS.getId() : "null",
                ITEM_OUTPUT_BUS != null ? ITEM_OUTPUT_BUS.getId() : "null",
                ENERGY_INPUT_HATCH != null ? ENERGY_INPUT_HATCH.getId() : "null",
                ENERGY_OUTPUT_HATCH != null ? ENERGY_OUTPUT_HATCH.getId() : "null",
                EnergyHatchSizes.sizeCount());
    }

    /** 把一台机器的界面情况说成一行日志。 */
    private static String describeUi(MachineDefinition definition) {
        if (!definition.hasUI()) return "NONE (右键不会有反应)";
        var ui = definition.getMachineUI();
        return "%dx%d, entries=%d, autoLayout=%s, entityRenderer=%s".formatted(
                ui.getWidth(), ui.getHeight(), ui.getEntryCount(), ui.isAutoLayout(),
                definition.isUseEntityRenderer());
    }
}
