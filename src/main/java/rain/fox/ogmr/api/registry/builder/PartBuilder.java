package rain.fox.ogmr.api.registry.builder;

import rain.fox.ogmr.api.machine.IMachineBlockEntity;
import rain.fox.ogmr.api.machine.MachineDefinition;
import rain.fox.ogmr.api.machine.multiblock.PartAbility;
import rain.fox.ogmr.api.machine.multiblock.part.MultiblockPartMachine;
import rain.fox.ogmr.api.registry.MachineRegistrar;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * 仓室（Part）注册 builder —— 需求 2。
 *
 * <p>
 * 仓室在结构里是「可以被替换的那一格」。它比普通机器多一件事：<b>登记能力</b>。
 * 每个能力（{@link PartAbility}）内部维护「档位 → 方块」的表，
 * 结构图案里的 {@code autoAbilities(...)} 与运行时的能力查询都靠这张表。
 *
 * <pre>{@code
 * public static final MachineDefinition ITEM_IMPORT_BUS_LV =
 *     REGISTRAR.part("lv_item_import_bus", h -> new ItemBusPartMachine(h, LV, IO.IN))
 *         .tier(OGMRValues.LV)
 *         .abilities(PartAbility.IMPORT_ITEMS)
 *         .register();
 * }</pre>
 */
public class PartBuilder<D extends MachineDefinition, B extends PartBuilder<D, B>> extends MachineBuilder<D, B> {

    private final List<PartAbility> abilities = new ArrayList<>();

    @SuppressWarnings("unchecked")
    public PartBuilder(MachineRegistrar registrar, String name,
                       Function<IMachineBlockEntity, ? extends MultiblockPartMachine> factory) {
        // ⚠️ D 是 F-bounded 的类型变量，构造器引用推不出来，必须显式转型
        super(registrar, name, factory::apply, id -> (D) new MachineDefinition(id));

        // 仓室默认不遮挡、硬度低一点，外观由 addon 自己定
        blockProperties(3.0f, 3.0f);
    }

    /** 登记能力（可多次调用累加）。 */
    public B abilities(PartAbility... abilities) {
        this.abilities.addAll(List.of(abilities));
        return self();
    }

    /** 已声明的能力（只读）。 */
    public List<PartAbility> getAbilities() {
        return List.copyOf(abilities);
    }

    @Override
    public D register() {
        D definition = super.register();

        // 把「本档位的这个方块」登记进每个能力里 —— 结构匹配与运行时查询都读这张表。
        // ⚠️ 传的是 blockObject（供应器）而不是 definition.getBlock()：现在还在 mod 构造期，
        //    Forge 的 RegisterEvent 没跑，RegistryObject.get() 会抛 "Registry Object not present"。
        //    PartAbility 内部会在真正需要时（结构匹配/查询）才解析它。
        for (PartAbility ability : abilities) {
            ability.register(tier, blockObject);
        }

        logRegistered();
        return definition;
    }
}
