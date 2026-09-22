package rain.fox.ogmr.api.registry;

import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.RegistryObject;

/**
 * 给 addon 一键建物品栏标签的便捷方法（本库自己不建标签，避免和 addon 的排版打架）。
 *
 * <p>
 * 不这么做的话，注册出来的机器虽然存在于 {@code minecraft:give} 里，但在创造模式物品栏里
 * <b>一个都看不到</b>（1.20.1 的物品不进标签就等于隐藏），测试时很难受。
 *
 * <pre>{@code
 * // addon 的 initialize() 里：
 * OGMRCreativeTab.register(AddonBootstrap.modBus(), "mymod", "main", "itemGroup.mymod", MyRegistrar.REGISTRAR);
 * // 记得同时登记标题键：
 * OGMRLang.add("itemGroup.mymod", "My Machines", "我的机器");
 * }</pre>
 *
 * @param modBus      mod 事件总线（addon 里用 {@code AddonBootstrap.modBus()}）
 * @param modId       命名空间
 * @param name        标签注册名（一般用 {@code "main"}）
 * @param titleKey    标题语言键（一般 {@code "itemGroup." + modId}）
 * @param registrars  要收录的注册器；每个注册器里所有已登记的机器物品都会进这个标签
 */
public final class OGMRCreativeTab {

    private OGMRCreativeTab() {}

    /** 创建、登记并返回这个标签的 {@code DeferredRegister}。 */
    public static DeferredRegister<CreativeModeTab> register(IEventBus modBus, String modId, String name,
                                                             String titleKey, MachineRegistrar... registrars) {
        DeferredRegister<CreativeModeTab> tabs = DeferredRegister.create(Registries.CREATIVE_MODE_TAB, modId);

        tabs.register(name, () -> CreativeModeTab.builder()
                .title(Component.translatable(titleKey))
                .icon(() -> iconOf(registrars))
                .displayItems((parameters, output) -> {
                    for (MachineRegistrar registrar : registrars) {
                        if (registrar == null) continue;
                        for (RegistryObject<Item> item : registrar.items().getEntries()) {
                            output.accept(item.get());
                        }
                    }
                })
                .build());

        tabs.register(modBus);
        return tabs;
    }

    /** 标签图标 = 第一个注册器里的第一个物品；一个都没有时退回空堆（不会崩）。 */
    private static ItemStack iconOf(MachineRegistrar... registrars) {
        for (MachineRegistrar registrar : registrars) {
            if (registrar == null) continue;
            for (RegistryObject<Item> item : registrar.items().getEntries()) {
                return new ItemStack(item.get());
            }
        }
        return ItemStack.EMPTY;
    }
}
