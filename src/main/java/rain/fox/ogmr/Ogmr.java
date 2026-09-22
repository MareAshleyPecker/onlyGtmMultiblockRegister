package rain.fox.ogmr;

import rain.fox.ogmr.api.addon.AddonBootstrap;
import rain.fox.ogmr.api.energy.EnergyHatchPartMachine;
import rain.fox.ogmr.api.energy.EnergyHatchSizes;
import rain.fox.ogmr.api.energy.EnergyTypes;
import rain.fox.ogmr.api.energy.EnergyUnit;
import rain.fox.ogmr.api.item.MachineItem;
import rain.fox.ogmr.api.lang.OGMRLang;
import rain.fox.ogmr.api.machine.trait.RecipeLogic;
import rain.fox.ogmr.api.recipe.OGMRRecipeSerializer;
import rain.fox.ogmr.api.recipe.content.ContentKinds;
import rain.fox.ogmr.api.registry.OGMRRegistries;
import rain.fox.ogmr.integration.jei.MultiblockPreviewLang;
import rain.fox.ogmr.modular.ModularMachine;
import rain.fox.ogmr.modular.ModuleHostMachine;
import rain.fox.ogmr.modular.ModuleMachine;
import rain.fox.ogmr.threading.ThreadedHatches;
import rain.fox.ogmr.threading.ThreadedRecipeStatus;

import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * onlyGtmMultiblockRegister —— 一个<b>独立</b>的多方块注册工具库。
 *
 * <p>
 * 本 mod 不含任何游戏内容（没有方块、没有物品、没有机器），它提供的是一整套「格雷式」的注册框架：
 * <ol>
 * <li><b>多方块注册</b>：{@code MultiblockMachineBuilder} / {@code MultiblockMachineDefinition} /
 * 结构图案匹配 / 成型预览；</li>
 * <li><b>仓室（Part）注册</b>：{@code PartAbility} + {@code PartBuilder} + 仓室机器基类；</li>
 * <li><b>机器 UI 绘制</b>：基于 LDLib 的 {@code MachineUI} 装配器；</li>
 * <li><b>rtui 绘制</b>：配方类型 UI（{@code RecipeTypeUI}）与编辑器工程（{@code .rtui}）；</li>
 * <li><b>RecipeType / 配方注册</b>：{@code RecipeTypeRegistrar} / {@code RecipeBuilder}；</li>
 * <li><b>Addon 工具</b>：{@code @OGMRAddon} + {@code IOGMRAddon} + 注解扫描；</li>
 * <li><b>模块化多方块</b>（{@code rain.fox.ogmr.modular}）与
 * <b>多线程配方</b>（{@code rain.fox.ogmr.threading}）。</li>
 * </ol>
 *
 * <p>
 * 依赖：Minecraft Forge（1.20.1-47.x）+ LDLib2。**不依赖 GTCEu**。
 */
@Mod(Ogmr.MOD_ID)
public class Ogmr {

    /** 本库的 mod id。 */
    public static final String MOD_ID = "ogmr";

    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    /**
     * 本库命名空间下的 id —— 本库内部构造 id 的<b>唯一入口</b>。
     *
     * <p>
     * 别处（包括 addon）请也走这里或 {@code ResourceLocations}，不要在代码里散写
     * {@code new ResourceLocation(...)}：从 NBT/网络/配置解析出来的字符串要用
     * {@link rain.fox.ogmr.utils.ResourceLocations#tryParse(String)}（解析失败返回 null 而不是炸），
     * 拼接命名空间/路径要用 {@code ResourceLocations.withPath(...)} 之类。
     */
    public static ResourceLocation id(String path) {
        return rain.fox.ogmr.utils.ResourceLocations.ogmr(path);
    }

    public Ogmr() {
        IEventBus modBus = FMLJavaModLoadingContext.get().getModEventBus();

        OGMRRegistries.init(modBus);
        OGMRLang.add("ogmr.tooltip.library", "Registered by onlyGtmMultiblockRegister",
                "由 onlyGtmMultiblockRegister 注册");

        // ogmr:generic 配方序列化器 —— 不注册的话数据包里的本库配方加载不了
        OGMRRecipeSerializer.register(modBus);

        // 注解扫描 @OGMRAddon 并把各 addon 挂到 mod 事件总线上（同时跑完 addon 的注册阶段）
        AddonBootstrap.init(modBus);

        // 库自己的文案：放在 addon 阶段之后，这样 addon 注册的能源仓尺寸等也在场
        initLibraryLang();

        modBus.addListener(this::commonSetup);

        MinecraftForge.EVENT_BUS.register(this);

        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, OGMRConfig.SPEC);
    }

    private void commonSetup(final FMLCommonSetupEvent event) {
        event.enqueueWork(() -> LOGGER.info("ogmr: {} addon(s) discovered", AddonBootstrap.getAddons().size()));
    }

    /**
     * 登记<b>库自己</b>用到的全部语言键。
     *
     * <p>
     * 必须在数据生成之前跑（这里是 mod 构造期的末尾），否则这些文案不会进
     * {@code assets/ogmr/lang/*.json}，玩家看到的就是裸键。
     * addon 的文案走 {@code IOGMRAddon#initLang()}，由 {@link AddonBootstrap} 负责调用。
     *
     * <p>
     * 新增子系统时记得在这里补一行 —— 漏了不会报错，只会「文案变成键名」。
     */
    private static void initLibraryLang() {
        // 配方 / 线程
        RecipeLogic.initLang();
        ThreadedHatches.initLang(); // 内部含 ThreadedRecipeLogic / ThreadHatchPartMachine / ThreadedMultiblockMachine
        ThreadedRecipeStatus.initLang();

        // 配方内容种类（内置物品/流体 + 第三方注册进来的）
        ContentKinds.initLang();

        // 能量
        EnergyTypes.initLang();
        EnergyUnit.initLang(); // 兼容层：顺带把自定义能量种类也登记上
        EnergyHatchPartMachine.initLang();
        EnergyHatchSizes.initLang();

        // 模块化
        ModuleMachine.initLang();
        ModuleHostMachine.initLang();
        ModularMachine.initLang();

        // 物品 / UI
        MachineItem.initLang();
        MultiblockPreviewLang.initLang(); // JEI 预览（这个类不碰 JEI，所以 datagen 环境也能调）
    }
}
