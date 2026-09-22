package rain.fox.ogmr.modular;

import rain.fox.ogmr.api.lang.OGMRLang;
import rain.fox.ogmr.api.machine.IMachineBlockEntity;
import rain.fox.ogmr.api.machine.TickableSubscription;
import rain.fox.ogmr.api.machine.multiblock.WorkableMultiblockMachine;
import rain.fox.ogmr.api.machine.trait.RecipeLogic;
import rain.fox.ogmr.api.OGMRValues;

import lombok.Getter;

import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * 模块化多方块的<b>单元（子机）</b>。
 *
 * <p>
 * Java 重写自 GTEternalTime 的 {@code ETModuleMachine}。
 *
 * <p>
 * 一句话机器逻辑：<b>附近 {@link #hostRange()} 格内有成型的主机 + 配方等级不超过主机 + 付得起每 tick 的电 → 才跑这条配方。</b>
 *
 * <p>
 * 等级分工（按需求定死）：
 * <ul>
 * <li><b>配方等级以主机算</b> —— {@link #recipeTier()} 决定「这条配方配不配在本单元跑」；</li>
 * <li><b>处理等级以自己算</b> —— {@link #processingTier()} 是本单元自己结构的电压档位（超频 / 并行按它走）。</li>
 * </ul>
 *
 * <p>
 * ⚠️ 扣电请在 {@link #onWorkingTick()}（每工作 tick 一次）里做，不要放在
 * {@link #recipeRequirement()} / 配方匹配里 —— 那一层是<b>模拟</b>匹配，一 tick 会被问好几次。
 */
public abstract class ModuleMachine extends WorkableMultiblockMachine {

    /** 单元从哪取电。 */
    public enum PowerSource {
        /** 自己的能源仓。 */
        SELF,
        /** 主机的能源仓（单元不自己耗电，主机统一供电）。 */
        HOST,
    }

    /** 对接上的主机（服务端瞬态；重进世界后由周期重查重新接上）。 */
    @Getter
    private ModuleHostMachine host;

    private TickableSubscription tickSub;
    private int tickCounter;

    public ModuleMachine(IMachineBlockEntity holder) {
        super(holder);
    }

    // ═══════════════ 子类要回答的问题 ═══════════════

    /** 默认从哪取电；自己带能源仓的单元可以覆写成 {@link PowerSource#SELF}。 */
    protected PowerSource defaultPowerSource() {
        return PowerSource.HOST;
    }

    /** 处理等级：<b>以自己算</b>。 */
    public int processingTier() {
        return getTier();
    }

    /** 配方等级：<b>以主机算</b>（没主机时退化成自己，方便单独调试）。 */
    public int recipeTier() {
        ModuleHostMachine h = host;
        return h != null ? h.hostTier() : processingTier();
    }

    /** 本单元每 tick 要消耗的能量（FE）；子类按自己的配方与倍率折算。 */
    protected long euPerTick() {
        return 0;
    }

    /**
     * <b>一句话机器逻辑</b>：跑这条配方需要什么条件。
     *
     * @return {@code null} = 条件都满足；返回原因 = 不开工，并把这句话显示给玩家
     */
    public Component recipeRequirement() {
        if (host == null) {
            return Component.translatable(LANG_NO_HOST, hostRange());
        }
        return null;
    }

    /** 供子类覆写：每个工作 tick 调一次，扣电写在这里。 */
    protected void onWorkingTick() {}

    // ═══════════════ 供电 ═══════════════

    /**
     * 扣电：返回<b>实际扣到</b>的量（不够就是不够，调用方按返回值决定开工 / 停机）。
     *
     * @param amount 要扣多少（FE）
     */
    public long consumeEu(long amount) {
        return consumeEu(amount, defaultPowerSource());
    }

    public long consumeEu(long amount, PowerSource source) {
        if (amount <= 0) return 0;
        return switch (source) {
            case SELF -> getEnergyStored() > 0 ? Math.min(amount, takeOwnEnergy(amount)) : 0;
            case HOST -> host != null ? host.consumeHostEu(amount) : 0;
        };
    }

    /** 主机现在有多少能量（借电前先看一眼，避免「扣不到再回滚」）。 */
    public long hostAvailableEu() {
        return host != null ? host.availableEu() : 0;
    }

    /** 自己的储能，默认 0（子类接了能源仓再覆写）。 */
    protected long getEnergyStored() {
        return 0;
    }

    /** 扣自己的储能，默认扣不动（返回 0）。 */
    protected long takeOwnEnergy(long amount) {
        return 0;
    }

    // ═══════════════ 主机对接 ═══════════════

    /** 找一台主机接上；已经接着且主机还在成型状态就不动。 */
    public void recheckHost() {
        if (isRemote()) return;
        ModuleHostMachine current = host;
        if (current != null && current.isFormed()) return;
        var level = getLevel();
        if (level == null) return;
        bindHost(ModuleNetwork.findHost(level, getPos(), hostRange()));
    }

    /** 手动绑定一台主机（调试 / 特殊结构用）。 */
    public void bindHost(ModuleHostMachine newHost) {
        if (host == newHost) return;
        if (host != null) host.detachModule(this);
        host = newHost;
        if (newHost != null) newHost.attachModule(this);
        if (newHost != null) {
            getRecipeLogic().resetRecipeLogic();
            getRecipeLogic().markLastRecipeDirty();
            getRecipeLogic().updateTickSubscription();
        }
    }

    private void unbindHost() {
        if (host == null) return;
        host.detachModule(this);
        host = null;
        getRecipeLogic().markLastRecipeDirty();
    }

    // ═══════════════ 生命周期 ═══════════════

    @Override
    public RecipeLogic createRecipeLogic() {
        return new ModuleRecipeLogic(this);
    }

    @Override
    public void onStructureFormed() {
        super.onStructureFormed();
        recheckHost();
        tickSub = subscribeServerTick(tickSub, this::onModuleTick);
    }

    @Override
    public void onStructureInvalid() {
        if (tickSub != null) {
            tickSub.unsubscribe();
            tickSub = null;
        }
        unbindHost();
        super.onStructureInvalid();
    }

    /** ⚠️ 卸载同样要解绑，否则主机的单元表里会留悬空引用。 */
    @Override
    public void onUnload() {
        if (tickSub != null) {
            tickSub.unsubscribe();
            tickSub = null;
        }
        unbindHost();
        super.onUnload();
    }

    /** 每 {@link #recheckInterval()} tick 重找一次主机：主机后成型、被拆、区块重载都能自己接回来。 */
    private void onModuleTick() {
        if (isRemote()) return;
        if (++tickCounter < recheckInterval()) return;
        tickCounter = 0;
        recheckHost();
    }

    // ═══════════════ 面板文本 ═══════════════

    @Override
    public void addDisplayText(List<Component> textList) {
        super.addDisplayText(textList);

        ModuleHostMachine linked = host;
        if (linked == null) {
            textList.add(Component.translatable(LANG_NO_HOST, hostRange()));
        } else {
            int distance = (int) Math.sqrt(linked.getPos().distSqr(getPos()));
            textList.add(Component.translatable(LANG_HOST_LINKED, distance));
        }

        textList.add(Component.translatable(LANG_TIER_SPLIT,
                OGMRValues.tierNameRaw(processingTier()),
                OGMRValues.tierNameRaw(recipeTier())));
    }

    // ═══════════════ 参数与语言键 ═══════════════

    /** 主机的最远对接距离（格）。 */
    public static int hostRange() {
        return ModuleHostMachine.hostRange();
    }

    /** 重找主机的间隔（tick）。读配置 getter；配置未加载时它自己会回落到默认值。 */
    public static int recheckInterval() {
        return rain.fox.ogmr.OGMRConfig.getDefaultHostRecheckInterval();
    }

    /** 「附近没有主机」的原因键（参数 = 距离）。 */
    public static final String LANG_NO_HOST = "ogmr.machine.module.no_host";
    /** 「配方等级高于主机」的原因键（参数 = 主机等级名）。 */
    public static final String LANG_TIER_TOO_LOW = "ogmr.machine.module.tier_too_low";
    /** 已对接主机（参数 = 距离，格）。 */
    public static final String LANG_HOST_LINKED = "ogmr.machine.module.host_linked";
    /** 等级分工（参数 = 处理等级名、配方等级名）。 */
    public static final String LANG_TIER_SPLIT = "ogmr.machine.module.tier_split";
    /** 「重新对接」按钮说明文字。 */
    public static final String LANG_RECHECK = "ogmr.machine.module.recheck";

    public static void initLang() {
        OGMRLang.add(LANG_NO_HOST, "No module host within %s blocks", "附近 %s 格内没有模块主机");
        OGMRLang.add(LANG_TIER_TOO_LOW, "Recipe voltage tier is above the host (%s)",
                "配方的电压等级高于主机（%s）");
        OGMRLang.add(LANG_HOST_LINKED, "Module host linked (%s blocks)", "已对接模块主机（距离 %s 格）");
        OGMRLang.add(LANG_TIER_SPLIT, "Processing tier %s (own) · Recipe tier %s (host)",
                "处理等级 %s（以自己算）· 配方等级 %s（以主机算）");
        OGMRLang.add(LANG_RECHECK, "Relink host", "重新对接主机");
    }
}
