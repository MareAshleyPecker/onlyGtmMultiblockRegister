package rain.fox.ogmr.modular;

import rain.fox.ogmr.api.lang.OGMRLang;
import rain.fox.ogmr.api.machine.IMachineBlockEntity;
import rain.fox.ogmr.api.machine.multiblock.WorkableMultiblockMachine;
import rain.fox.ogmr.api.machine.trait.NotifiableItemStackHandler;
import rain.fox.ogmr.api.machine.trait.RecipeLogic;
import rain.fox.ogmr.api.OGMRValues;
import rain.fox.ogmr.api.pattern.BlockPattern;
import rain.fox.ogmr.api.recipe.OGMRRecipe;
import rain.fox.ogmr.api.recipe.RecipeHelper;

import lombok.Getter;

import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

/**
 * 「模块物品决定等级」的模块化多方块基类。
 *
 * <p>
 * Java 重写自 GTEternalTime 的 {@code ETModularMachine}（写法照 GTO 的 PCB 工厂）。
 *
 * <p>
 * 机制：模块槽里的物品 → {@link #getModuleTier()} → ① 换结构（{@link #patternOfTier(int)}）
 * ② 卡配方电压等级（{@link #maxRecipeTier()}）。
 * 子类只需要回答两件事：<b>什么物品算哪个等级</b>、<b>每个等级长什么样</b>。
 *
 * <p>
 * ⚠️ 等级 0（没模块 / 模块不合法）时 {@link #checkPattern()} 直接不通过 ——
 * 「等级不合法」自然表现为「结构不成型」，不需要另外写报错。
 */
public abstract class ModularMachine extends WorkableMultiblockMachine {

    /** 模块槽：只收 1 个、不参与配方 IO；内容一变就重算等级并重检结构。 */
    @Getter
    protected final NotifiableItemStackHandler moduleSlot;

    /** 当前等级（0 = 无模块 / 不合法）。 */
    @Getter
    protected int moduleTier = 0;

    public ModularMachine(IMachineBlockEntity holder) {
        super(holder);
        this.moduleSlot = new NotifiableItemStackHandler(this, 1, false, false) {

            @Override
            public void onContentsChanged() {
                super.onContentsChanged();
                onModuleChanged();
            }
        };
    }

    // ═══════════════ 子类要回答的两个问题 ═══════════════

    /** 模块物品 → 等级；返回 0 表示这个物品不是合法模块。 */
    protected abstract int tierOfModule(ItemStack stack);

    /** 等级 → 结构图案。 */
    protected abstract BlockPattern patternOfTier(int tier);

    // ═══════════════ 等级 ═══════════════

    /**
     * 该等级允许的配方电压等级上限（{@link OGMRValues} 里的档位）；
     * 返回 -1 表示不限。
     */
    public int maxRecipeTier() {
        return moduleTier - 1;
    }

    /** 等级 0 不成型（见类注释）。 */
    @Override
    public boolean checkPattern() {
        return moduleTier > 0 && super.checkPattern();
    }

    @Override
    public BlockPattern getPattern() {
        return patternOfTier(moduleTier);
    }

    /** 模块槽内容变了：重算等级 → 重检结构 → 让配方重新判定。 */
    public void onModuleChanged() {
        if (isRemote()) return;
        ItemStack stack = moduleSlot.getStackInSlot(0);
        int newTier = stack.isEmpty() ? 0 : tierOfModule(stack);
        if (newTier == moduleTier) return;
        moduleTier = newTier;
        requestCheck();
        getRecipeLogic().resetRecipeLogic();
        getRecipeLogic().markLastRecipeDirty();
        getRecipeLogic().updateTickSubscription();
    }

    @Override
    public RecipeLogic createRecipeLogic() {
        return new ModularRecipeLogic(this);
    }

    @Override
    public void onLoad() {
        super.onLoad();
        // 读档后等级要按内容重算一次（moduleTier 本身也持久化，但物品可能被替换过）
        if (moduleSlot != null) onModuleChanged();
    }

    // ═══════════════ 语言键 ═══════════════

    /** 「配方等级超出当前模块」的语言键（参数 = 允许的最高档位名）。 */
    public static final String LANG_TIER_TOO_LOW = "ogmr.machine.modular.tier_too_low";

    public static void initLang() {
        OGMRLang.add(LANG_TIER_TOO_LOW, "Recipe voltage tier is above the current module (max: %s)",
                "配方的电压等级超出当前模块（上限：%s）");
    }

    /**
     * 模块化多方块的配方逻辑：<b>等级不够的配方判为不可用</b>。
     *
     * <p>
     * 拦在 {@code matchRecipe}（内容匹配）这一层而不是「配方修改」那一层，原因有两条：
     * <ol>
     * <li>多方块的配方修改点在基类里通常是 final，子类根本覆盖不了；</li>
     * <li>在这里拒绝会带上<b>原因文本</b>，机器面板与 Jade 都能看到
     * 「为什么这条配方不跑」，比静默失败好得多。</li>
     * </ol>
     */
    public static class ModularRecipeLogic extends RecipeLogic {

        private final ModularMachine modular;

        public ModularRecipeLogic(ModularMachine modular) {
            super(modular);
            this.modular = modular;
        }

        @Override
        protected boolean matchRecipe(OGMRRecipe recipe) {
            int cap = modular.maxRecipeTier();
            if (cap >= 0) {
                int recipeTier = RecipeHelper.getRecipeEUtTier(recipe);
                if (recipeTier > cap) {
                    setFailureReason(Component.translatable(LANG_TIER_TOO_LOW, OGMRValues.tierNameRaw(cap)));
                    return false;
                }
            }
            return super.matchRecipe(recipe);
        }

        @Override
        public void addDisplayText(java.util.List<Component> textList) {
            super.addDisplayText(textList);
            textList.add(Component.translatable("ogmr.machine.modular.module_tier",
                    OGMRValues.tierNameRaw(modular.getModuleTier())));
        }
    }
}
