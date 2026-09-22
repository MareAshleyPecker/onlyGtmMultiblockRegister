package rain.fox.ogmr.api.registry;

import rain.fox.ogmr.api.machine.IMachineBlockEntity;
import rain.fox.ogmr.api.machine.MachineDefinition;
import rain.fox.ogmr.api.machine.MetaMachine;
import rain.fox.ogmr.api.machine.MultiblockMachineDefinition;
import rain.fox.ogmr.api.machine.multiblock.MultiblockControllerMachine;
import rain.fox.ogmr.api.machine.multiblock.part.MultiblockPartMachine;
import rain.fox.ogmr.api.registry.builder.MachineBuilder;
import rain.fox.ogmr.api.registry.builder.MultiblockMachineBuilder;
import rain.fox.ogmr.api.registry.builder.PartBuilder;

import lombok.Getter;

import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * 机器注册器 —— 一个 addon 的「注册入口」。
 *
 * <p>
 * 拆自 GTM 的 {@code GTRegistrate#machine} 一族方法 + Registrate 的 DeferredRegister 管理。
 * 本库不用 Registrate（要保持零第三方注册框架依赖），而是直接管三个 Forge 的
 * {@link DeferredRegister}：
 * <ul>
 * <li>{@link #BLOCKS} —— 机器方块；</li>
 * <li>{@link #ITEMS} —— 对应的物品；</li>
 * <li>{@link #BLOCK_ENTITIES} —— 方块实体类型；</li>
 * </ul>
 *
 * <p>
 * <b>用法</b>（在 {@code IOGMRAddon#initialize()} 里建、在注册阶段用）：
 * <pre>{@code
 * public final class MyRegistrar {
 *     public static final MachineRegistrar REGISTRAR = new MachineRegistrar("mymod");
 *     public static void register(IEventBus modBus) { REGISTRAR.attach(modBus); }
 * }
 *
 * // 注册一台多方块
 * public static final MultiblockMachineDefinition FOUNDRY =
 *     MyRegistrar.REGISTRAR.multiblock("foundry", FoundryMachine::new)
 *         .recipeType(MyRecipeTypes.FOUNDRY)
 *         .pattern(def -> FactoryBlockPattern.start()...)
 *         .register();
 * }</pre>
 *
 * <p>
 * ⚠️ 必须在 {@code FMLJavaModLoadingContext} 的 mod 事件总线触发注册事件之前
 * 调用 {@link #attach(IEventBus)}（一般就在 addon 的 {@code initialize()} 里）。
 */
public class MachineRegistrar {

    @Getter
    private final String modId;
    private final DeferredRegister<Block> blockRegister;
    private final DeferredRegister<Item> itemRegister;
    private final DeferredRegister<BlockEntityType<?>> blockEntityRegister;

    private final List<MachineBuilder<?, ?>> builders = new ArrayList<>();
    private boolean attached = false;

    public MachineRegistrar(String modId) {
        this.modId = modId;
        this.blockRegister = DeferredRegister.create(ForgeRegistries.BLOCKS, modId);
        this.itemRegister = DeferredRegister.create(ForgeRegistries.ITEMS, modId);
        this.blockEntityRegister = DeferredRegister.create(ForgeRegistries.BLOCK_ENTITY_TYPES, modId);
    }

    public DeferredRegister<Block> blocks() {
        return blockRegister;
    }

    public DeferredRegister<Item> items() {
        return itemRegister;
    }

    public DeferredRegister<BlockEntityType<?>> blockEntities() {
        return blockEntityRegister;
    }

    /** 把三个 DeferredRegister 挂到 mod 事件总线上（幂等）。 */
    public void attach(IEventBus modBus) {
        if (attached) return;
        attached = true;
        blockRegister.register(modBus);
        itemRegister.register(modBus);
        blockEntityRegister.register(modBus);
    }

    // ═══════════════ 三个注册入口 ═══════════════

    /**
     * 注册一台普通（单方块）机器。
     *
     * @param name      注册名（不含命名空间）
     * @param factory   方块实体 → 机器实例的工厂，例如 {@code SimpleMachine::new}
     */
    public MachineBuilder<MachineDefinition, ?> machine(String name,
                                                        Function<IMachineBlockEntity, ? extends MetaMachine> factory) {
        MachineBuilder<MachineDefinition, ?> builder = new MachineBuilder<>(this, name, factory,
                MachineDefinition::new);
        builders.add(builder);
        return builder;
    }

    /**
     * 注册一台多方块控制器。
     *
     * @param name    注册名
     * @param factory 方块实体 → 多方块控制器实例的工厂
     */
    public MultiblockMachineBuilder<MultiblockMachineDefinition, ?> multiblock(
                                                                                String name,
                                                                                Function<IMachineBlockEntity, ? extends MultiblockControllerMachine> factory) {
        MultiblockMachineBuilder<MultiblockMachineDefinition, ?> builder = new MultiblockMachineBuilder<>(this, name,
                factory);
        builders.add(builder);
        return builder;
    }

    /**
     * 注册一个仓室（Part）。
     *
     * @param name    注册名
     * @param factory 方块实体 → 仓室机器实例的工厂
     */
    public PartBuilder<MachineDefinition, ?> part(String name,
                                                  Function<IMachineBlockEntity, ? extends MultiblockPartMachine> factory) {
        PartBuilder<MachineDefinition, ?> builder = new PartBuilder<>(this, name, factory);
        builders.add(builder);
        return builder;
    }

    /** 已登记（但未必已 register）的 builder 数量，调试用。 */
    public int getBuilderCount() {
        return builders.size();
    }

    /**
     * 便利方法：给实体类型用（本库本身不用，留给 addon 扩展）。
     */
    public static <T extends net.minecraft.world.entity.Entity> DeferredRegister<EntityType<?>> entityRegister(
                                                                                                                String modId) {
        return DeferredRegister.create(ForgeRegistries.ENTITY_TYPES, modId);
    }
}
