package rain.fox.ogmr.api.addon;

import rain.fox.ogmr.api.machine.MachineDefinition;
import rain.fox.ogmr.api.machine.MultiblockMachineDefinition;
import rain.fox.ogmr.api.registry.OGMRRegisterEvent;
import rain.fox.ogmr.api.recipe.OGMRRecipeType;

import net.minecraft.data.recipes.FinishedRecipe;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;

import java.util.function.Consumer;

/**
 * addon 的契约 —— 本库版的 {@code IGTAddon}。
 *
 * <p>
 * 所有方法都有默认空实现，addon 只覆写自己关心的阶段。
 *
 * <p>
 * <b>阶段顺序</b>（由 {@link AddonBootstrap} 驱动，全部在一次启动内按此顺序执行）：
 * <ol>
 * <li>{@link #initialize()} —— mod 构造期。在这里 new 出注册器、注册 DeferredRegister；</li>
 * <li>{@link #registerPartAbilities()} —— 仓室能力要最先（多方块的 {@code abilities(...)} 依赖它）；</li>
 * <li>{@link #registerRecipeTypes(OGMRRegisterEvent.RL)} —— 配方类型；</li>
 * <li>{@link #registerMachines(OGMRRegisterEvent.RL)} —— 单方块机器与仓室；</li>
 * <li>{@link #registerMultiblocks(OGMRRegisterEvent.RL)} —— 多方块；</li>
 * <li>{@link #registerUI()} —— UI 工程（{@code .mui} / {@code .rtui}）与自定义 widget；</li>
 * <li>{@link #registerRecipes(Consumer)} —— 数据生成期的配方；</li>
 * <li>{@link #setup(FMLCommonSetupEvent)} —— 收尾（跨 mod 交互、能力挂载等）。</li>
 * </ol>
 */
public interface IOGMRAddon {

    /**
     * 本 addon 使用的 mod id（内容命名空间）。
     *
     * <p>
     * ⚠️ 这个 id 决定了 addon 能往哪些注册表里写东西：{@code OGMRRegistry} 会校验「当前加载的 mod」
     * 是否是命名空间的拥有者，防止 A 的 addon 往 B 的表里塞内容。
     */
    String addonModId();

    /**
     * 初始化顺序，数字小的先跑。
     */
    default int priority() {
        return 1000;
    }

    /**
     * 第一阶段：mod 构造期。
     *
     * <p>
     * 在这里创建 {@code MachineRegistrar} / {@code RecipeTypeRegistrar} 之类的注册器，
     * 并把它们内部的 {@code DeferredRegister} 挂到 mod 事件总线上。
     */
    default void initialize() {}

    /**
     * 登记自定义仓室能力（{@code PartAbility}）。
     */
    default void registerPartAbilities() {}

    /**
     * 登记配方类型。
     */
    default void registerRecipeTypes(OGMRRegisterEvent.RL<OGMRRecipeType> event) {}

    /**
     * 登记单方块机器与仓室。
     */
    default void registerMachines(OGMRRegisterEvent.RL<MachineDefinition> event) {}

    /**
     * 登记多方块。
     */
    default void registerMultiblocks(OGMRRegisterEvent.RL<MultiblockMachineDefinition> event) {}

    /**
     * 登记 UI：机器 UI 工程、配方类型 UI（rtui）与自定义 widget。
     */
    default void registerUI() {}

    /**
     * 登记本 addon 用到的语言键 —— <b>必须在数据生成之前</b>执行。
     */
    default void initLang() {}

    /**
     * 数据生成期的配方注册。
     */
    default void registerRecipes(Consumer<FinishedRecipe> provider) {}

    /**
     * 收尾阶段（对应 Forge 的 {@code FMLCommonSetupEvent}）。
     */
    default void setup(FMLCommonSetupEvent event) {}

    /**
     * 是否要求「高电压等级内容」——本库用来决定默认是否放开高 Tier 的机器注册。
     */
    default boolean requiresHighTier() {
        return false;
    }
}
