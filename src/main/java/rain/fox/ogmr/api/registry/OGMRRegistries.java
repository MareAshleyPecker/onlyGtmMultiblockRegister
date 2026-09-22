package rain.fox.ogmr.api.registry;

import rain.fox.ogmr.Ogmr;
import rain.fox.ogmr.api.machine.MachineDefinition;
import rain.fox.ogmr.api.machine.MultiblockMachineDefinition;
import rain.fox.ogmr.api.machine.multiblock.PartAbility;
import rain.fox.ogmr.api.recipe.OGMRRecipeType;

import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.eventbus.api.IEventBus;

/**
 * 本库的全部「非 Forge 注册表」。
 *
 * <p>
 * 拆自 GTM 的 {@code GTRegistries}，但只保留与多方块/仓室/配方相关的部分，并且去掉了 GT 内容
 * 特有的表（材料、矿脉、覆盖板……）。剩下三张表：
 * <ul>
 * <li>{@link #MACHINES} —— 所有机器定义（含多方块与仓室），键是 {@link ResourceLocation}；</li>
 * <li>{@link #RECIPE_TYPES} —— 所有配方类型；</li>
 * <li>{@link #PART_ABILITIES} —— 所有仓室能力（字符串键）。</li>
 * </ul>
 *
 * <p>
 * 冻结时机：GTM 是在 {@code GTMachines.init()} 末尾冻结机器表；本库同样在
 * {@code FMLCommonSetupEvent} 之前（即注册事件里）冻结，冻结之后再注册会直接抛异常，
 * 免得「机器静默没注册上」这种事故拖到进游戏才发现。
 */
public final class OGMRRegistries {

    private OGMRRegistries() {}

    // ═══════════════ 注册表 ═══════════════

    /** 机器定义表（多方块 / 单方块 / 仓室都在这里）。 */
    public static final OGMRRegistry.RL<MachineDefinition> MACHINES = new OGMRRegistry.RL<>(
            Ogmr.id("machine"));

    /** 多方块定义表（{@link #MACHINES} 的子集视图，单独放一份方便遍历）。 */
    public static final OGMRRegistry.RL<MultiblockMachineDefinition> MULTIBLOCKS = new OGMRRegistry.RL<>(
            Ogmr.id("multiblock"));

    /** 配方类型表。 */
    public static final OGMRRegistry.RL<OGMRRecipeType> RECIPE_TYPES = new OGMRRegistry.RL<>(
            Ogmr.id("recipe_type"));

    /** 仓室能力表（键是能力名）。 */
    public static final OGMRRegistry.String<PartAbility> PART_ABILITIES = new OGMRRegistry.String<>(
            Ogmr.id("part_ability"));

    // ═══════════════ 生命周期 ═══════════════

    public static void init(IEventBus modBus) {
        // 目前三张表都是纯内存表，不需要往 Forge 事件总线上挂东西；
        // 保留这个方法是为了将来要加 DeferredRegister（比如给定义对象配套的方块/物品）时有统一入口。
        Ogmr.LOGGER.debug("ogmr registries initialised");
    }

    /**
     * 冻结所有注册表 —— 在机器注册阶段结束、{@code FMLCommonSetupEvent} 之前调用。
     *
     * <p>
     * 已经冻结的表会被跳过（不抛异常），所以重复调用是安全的。
     */
    public static void freezeAll() {
        freeze(MACHINES);
        freeze(MULTIBLOCKS);
        freeze(RECIPE_TYPES);
        freeze(PART_ABILITIES);
    }

    private static void freeze(OGMRRegistry<?, ?> registry) {
        if (!registry.isFrozen()) registry.freeze();
    }

    /** 便捷方法：{@code gtceu:lv} 那样的 id 直接查机器。 */
    public static MachineDefinition machine(ResourceLocation id) {
        return MACHINES.get(id);
    }
}
