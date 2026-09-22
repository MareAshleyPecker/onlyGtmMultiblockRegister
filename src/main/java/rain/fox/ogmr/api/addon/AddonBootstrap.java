package rain.fox.ogmr.api.addon;

import rain.fox.ogmr.Ogmr;
import rain.fox.ogmr.OGMRConfig;
import rain.fox.ogmr.api.machine.MachineDefinition;
import rain.fox.ogmr.api.machine.MultiblockMachineDefinition;
import rain.fox.ogmr.api.registry.OGMRRegisterEvent;
import rain.fox.ogmr.api.registry.OGMRRegistries;
import rain.fox.ogmr.api.recipe.OGMRRecipeType;
import rain.fox.ogmr.data.OGMRLangProvider;
import rain.fox.ogmr.data.OGMRRecipeProvider;

import net.minecraftforge.data.event.GatherDataEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * addon 生命周期驱动 —— 注册阶段的调度器。
 *
 * <p>
 * 做四件事：
 * <ol>
 * <li>扫描并实例化所有 {@link OGMRAddon}（见 {@link AddonFinder}）；</li>
 * <li>在 <b>mod 构造期</b> 按固定顺序跑完注册阶段（顺序见 {@link IOGMRAddon} 类注释）。
 * 必须放在构造期而不是 {@code FMLCommonSetupEvent}：注册阶段要创建方块/物品的
 * {@code DeferredRegister} 条目，而 Forge 的 {@code RegisterEvent} 在 setup 之前就已经结束了；</li>
 * <li>跑完 {@link OGMRRegistries#freezeAll()} 冻结注册表 —— 之后再注册直接抛异常，
 * 免得「机器静默没注册上」拖到进游戏才发现；</li>
 * <li>把 {@code FMLCommonSetupEvent} / {@code GatherDataEvent} 转发给 addon。</li>
 * </ol>
 *
 * <p>
 * 每个阶段除了直接回调 addon，还会把对应的 {@link OGMRRegisterEvent} 发到 mod 事件总线上，
 * 这样「不是 addon、但也想挂一笔」的 mod 可以 {@code addGenericListener} 监听。
 */
public final class AddonBootstrap {

    private static List<IOGMRAddon> addons = List.of();
    private static boolean initialised = false;
    private static IEventBus modBus;

    private AddonBootstrap() {}

    /**
     * 本库的 mod 事件总线（{@link #init(IEventBus)} 之后可用）。
     *
     * <p>
     * addon 在 {@code initialize()} 里要用它来 {@code DeferredRegister.register(...)}，
     * 例如 {@code MyRegistrar.REGISTRAR.attach(AddonBootstrap.modBus())}。
     */
    public static IEventBus modBus() {
        if (modBus == null) {
            throw new IllegalStateException("AddonBootstrap 还没初始化：请确认 ogmr 是本 addon 的依赖之一");
        }
        return modBus;
    }

    public static boolean isInitialised() {
        return initialised;
    }

    public static List<IOGMRAddon> getAddons() {
        return addons;
    }

    public static IOGMRAddon getAddon(String modId) {
        for (IOGMRAddon addon : addons) {
            if (addon.addonModId().equals(modId)) return addon;
        }
        return null;
    }

    /** 在 mod 构造函数里调用一次。 */
    public static void init(IEventBus modBus) {
        if (initialised) return;
        initialised = true;
        AddonBootstrap.modBus = modBus;

        addons = AddonFinder.getAddons();
        if (addons.isEmpty()) {
            Ogmr.LOGGER.info("ogmr: 没有发现 @OGMRAddon；库已加载但处于空闲状态");
        } else if (logDiscovery()) {
            Ogmr.LOGGER.info("ogmr: 发现 {} 个 addon: {}", addons.size(),
                    addons.stream().map(IOGMRAddon::addonModId).toList());
        }

        // ① initialize —— 建注册器、把 DeferredRegister 挂上总线
        forEach("initialize", IOGMRAddon::initialize);

        // ② 仓室能力必须最先（多方块的 abilities(...) 依赖它）
        forEach("registerPartAbilities", IOGMRAddon::registerPartAbilities);

        // ③ 配方类型
        OGMRRegisterEvent.RL<OGMRRecipeType> recipeTypeEvent = new OGMRRegisterEvent.RL<>(
                OGMRRegistries.RECIPE_TYPES, OGMRRecipeType.class);
        forEach("registerRecipeTypes", a -> a.registerRecipeTypes(recipeTypeEvent));
        modBus.post(recipeTypeEvent);

        // ④ 单方块机器与仓室
        OGMRRegisterEvent.RL<MachineDefinition> machineEvent = new OGMRRegisterEvent.RL<>(
                OGMRRegistries.MACHINES, MachineDefinition.class);
        forEach("registerMachines", a -> a.registerMachines(machineEvent));
        modBus.post(machineEvent);

        // ⑤ 多方块
        OGMRRegisterEvent.RL<MultiblockMachineDefinition> multiblockEvent = new OGMRRegisterEvent.RL<>(
                OGMRRegistries.MULTIBLOCKS, MultiblockMachineDefinition.class);
        forEach("registerMultiblocks", a -> a.registerMultiblocks(multiblockEvent));
        modBus.post(multiblockEvent);

        // ⑥ UI / 语言
        forEach("registerUI", IOGMRAddon::registerUI);
        forEach("initLang", IOGMRAddon::initLang);

        // ⑦ 冻结
        OGMRRegistries.freezeAll();
        Ogmr.LOGGER.info("ogmr: 注册表已冻结（{} 台机器 / {} 个配方类型）",
                OGMRRegistries.MACHINES.size(), OGMRRegistries.RECIPE_TYPES.size());

        // ⑧ 收尾与数据生成
        modBus.addListener(AddonBootstrap::onCommonSetup);
        modBus.addListener(AddonBootstrap::onGatherData);
    }

    // ─────────────── 内部 ───────────────

    private static void forEach(String phase, Consumer<IOGMRAddon> action) {
        for (IOGMRAddon addon : addons) {
            try {
                action.accept(addon);
            } catch (Throwable t) {
                Ogmr.LOGGER.error("ogmr: addon {} 在 {}() 阶段抛异常", addon.addonModId(), phase, t);
            }
        }
    }

    private static void onCommonSetup(FMLCommonSetupEvent event) {
        event.enqueueWork(() -> forEach("setup", addon -> addon.setup(event)));
    }

    private static void onGatherData(GatherDataEvent event) {
        boolean server = event.includeServer();
        boolean client = event.includeClient();

        for (IOGMRAddon addon : addons) {
            // 语言：把 OGMRLang 里登记的键写成 addon 自己命名空间的 lang json
            if (client) {
                OGMRLangProvider.register(event, addon.addonModId());
                // 机器/仓室的 blockstate + 模型 JSON：按注册表里的 MachineDefinition 自动产出
                event.getGenerator().addProvider(true,
                        new rain.fox.ogmr.data.OGMRMachineModelProvider(
                                event.getGenerator().getPackOutput(), addon.addonModId(),
                                event.getExistingFileHelper()));
            }
            // 配方：收进一个 provider，再由 provider 统一写出
            if (server) {
                List<net.minecraft.data.recipes.FinishedRecipe> collected = new ArrayList<>();
                try {
                    addon.registerRecipes(collected::add);
                } catch (Throwable t) {
                    Ogmr.LOGGER.error("ogmr: addon {} 在 registerRecipes() 阶段抛异常", addon.addonModId(), t);
                }
                if (!collected.isEmpty()) {
                    // RecipeProvider 收 PackOutput（1.20.1 的 DataGenerator#getPackOutput），不是 DataGenerator 本身
                    event.getGenerator().addProvider(true,
                            new OGMRRecipeProvider(event.getGenerator().getPackOutput(), addon.addonModId(),
                                    collected));
                }
            }
        }
    }

    private static boolean logDiscovery() {
        return OGMRConfig.isLogAddonDiscovery();
    }
}
