package rain.fox.ogmr.api.item;

import rain.fox.ogmr.api.lang.OGMRLang;
import rain.fox.ogmr.api.machine.MachineDefinition;

import lombok.Getter;

import net.minecraft.network.chat.Component;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;

import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.function.BiConsumer;

/**
 * 本文件是从 GTM 的 {@code com.gregtechceu.gtceu.api.item.MetaMachineItem} 精简拆出来的。
 *
 * <p>
 * 机器对应的物品：它持有 {@link MachineDefinition}，因此可以在 tooltip 里补上定义中登记的内容。
 *
 * <p>
 * 与 GTM 的差别：没有下落管道连接、没有 {@code BlockEntityWithoutLevelRenderer}
 * （本库不提供自定义物品渲染器，需要的话由 addon 自己接 {@code IClientItemExtensions}）。
 */
public class MachineItem extends BlockItem {

    /** 没有 tooltipBuilder 时用的兜底文案键。 */
    public static final String LANG_DEFAULT_TOOLTIP = "ogmr.machine.tooltip.default";

    static {
        // 与库内其它基类同样的做法：类加载时就登记语言键（OGMRLang.add 是幂等的，
        // 且必须在 datagen 出 en_us/zh_cn 之前执行）。
        initLang();
    }

    /** 登记本类用到的语言键（幂等；也可以由 addon 的初始化流程显式调用一次）。 */
    public static void initLang() {
        OGMRLang.add(LANG_DEFAULT_TOOLTIP, "Registered by onlyGtmMultiblockRegister",
                "由 onlyGtmMultiblockRegister 注册");
    }

    @Getter
    private final MachineDefinition definition;

    public MachineItem(MachineDefinition definition, Properties properties) {
        super(definition.getBlock(), properties);
        this.definition = definition;
    }

    /**
     * tooltip：优先用 definition 里登记的 {@code tooltipBuilder}；
     * 如果它什么都没写（或者根本没设），补一句兜底文案。
     */
    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> tooltip, TooltipFlag flag) {
        super.appendHoverText(stack, level, tooltip, flag);

        BiConsumer<ItemStack, List<Component>> builder = definition.getTooltipBuilder();
        int before = tooltip.size();
        if (builder != null) {
            builder.accept(stack, tooltip);
        }
        if (tooltip.size() == before) {
            tooltip.add(Component.translatable(LANG_DEFAULT_TOOLTIP));
        }
    }
}
