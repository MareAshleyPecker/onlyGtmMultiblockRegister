package rain.fox.ogmr.api.machine.multiblock.part;

import rain.fox.ogmr.api.machine.IMachineBlockEntity;
import rain.fox.ogmr.api.machine.MetaMachine;
import rain.fox.ogmr.api.machine.multiblock.IMultiController;
import rain.fox.ogmr.api.machine.multiblock.IMultiPart;
import rain.fox.ogmr.api.machine.multiblock.PartAbility;
import rain.fox.ogmr.api.registry.OGMRRegistries;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;

import lombok.Getter;

import java.util.ArrayList;
import java.util.List;

/**
 * 仓室机器的基类 —— 需求 2 的运行时那一半。
 *
 * <p>
 * 拆自 GTM 的 {@code MultiblockPartMachine}，但能力来源换了实现：
 * GTM 是在 builder 里把能力列表塞进机器实例；本库改成 <b>反查注册表</b> ——
 * 因为 {@code PartAbility.register(tier, block)} 已经把「能力 → 方块」记下来了，
 * 所以只要知道自己的方块，就能反推出自己有哪些能力，避免在构造期传参
 * （构造期传参在 Java 里会踩「super() 之前不能用 this」的坑）。
 *
 * <p>
 * 子类只需要关心自己的业务（库存、储罐、能源……），结构生命周期由基类管。
 */
public abstract class MultiblockPartMachine extends MetaMachine implements IMultiPart {

    /** 当前挂着的控制器；结构未成型时为 null。 */
    @Getter
    protected IMultiController controller;

    /** 能力缓存（第一次查询时算出）。 */
    private List<PartAbility> abilityCache;

    public MultiblockPartMachine(IMachineBlockEntity holder) {
        super(holder);
    }

    // ═══════════════ 能力 ═══════════════

    /** 本仓室具备的全部能力（由方块反查注册表得到）。 */
    public List<PartAbility> getAbilities() {
        if (abilityCache == null) {
            List<PartAbility> list = new ArrayList<>();
            Block block = getDefinition() != null ? getDefinition().getBlock() : null;
            if (block != null) {
                for (PartAbility ability : OGMRRegistries.PART_ABILITIES) {
                    if (ability.isApplicable(block)) list.add(ability);
                }
            }
            abilityCache = List.copyOf(list);
        }
        return abilityCache;
    }

    public boolean hasAbility(PartAbility ability) {
        return getAbilities().contains(ability);
    }

    // ═══════════════ 结构生命周期 ═══════════════

    @Override
    public boolean isFormed() {
        return controller != null && controller.isFormed();
    }

    @Override
    public void addedToController(IMultiController controller) {
        this.controller = controller;
    }

    @Override
    public void removedFromController(IMultiController controller) {
        if (this.controller == controller) this.controller = null;
    }

    @Override
    public MetaMachine self() {
        return this;
    }

    @Override
    public Level getLevel() {
        return super.getLevel();
    }

    @Override
    public BlockPos getPos() {
        return super.getPos();
    }

    /** 仓室面板：默认加一行「能力」文本，子类覆写时先调 super。 */
    @Override
    public void addDisplayText(List<Component> textList) {
        super.addDisplayText(textList);
        var abilities = getAbilities();
        if (!abilities.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < abilities.size(); i++) {
                if (i > 0) sb.append(", ");
                sb.append(abilities.get(i).getName());
            }
            textList.add(Component.literal(sb.toString()));
        }
    }
}
