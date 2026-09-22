package rain.fox.ogmr.api.machine.multiblock;

import rain.fox.ogmr.api.machine.IMachineBlockEntity;
import rain.fox.ogmr.api.machine.MetaMachine;
import rain.fox.ogmr.api.machine.trait.RecipeLogic;

import com.lowdragmc.lowdraglib.syncdata.field.ManagedFieldHolder;

import net.minecraft.network.chat.Component;

import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * 本文件是从 GTM 的 {@code com.gregtechceu.gtceu.api.machine.multiblock.WorkableMultiblockMachine}
 * 精简拆出来的。
 *
 * <p>
 * 「能跑配方的多方块」= {@link MultiblockControllerMachine} + 一个 {@link RecipeLogic}。
 *
 * <p>
 * 与 GTM 的差别（本库精简版只保留「会工作的多方块」这一件事）：
 * <ul>
 * <li>没有能力代理（{@code capabilitiesProxy} / {@code capabilitiesFlat}）、配方修饰器、
 * 消声、虚空模式、主动配方类型切换、洁净室、仓室 trait 的 flatten；</li>
 * <li>{@code recipeLogic} 改成<b>懒加载</b>（GTM 在构造器里就建），因此任何「和逻辑状态有关」的
 * 访问都要先过 {@link #getRecipeLogic()}；{@link #onLoad()} 里会主动创建一次，
 * 保证它赶得上第一次存盘/同步。</li>
 * </ul>
 *
 * <p>
 * <b>tick 由谁驱动</b>：{@link RecipeLogic#updateTickSubscription()} 会通过
 * {@link MetaMachine#subscribeServerTick(rain.fox.ogmr.api.machine.TickableSubscription, Runnable)}
 * 自己订阅服务端 tick，所以本类<b>不再</b>在 {@link #serverTick()} 里转发
 * {@code recipeLogic.serverTick()} —— 两处同时驱动会让配方每 tick 跑两遍（2 倍速）。
 * 本类在 {@code onLoad()} 与结构成型/失效时调一次 {@code updateTickSubscription()} 即可。
 */
public class WorkableMultiblockMachine extends MultiblockControllerMachine {

    /** 子类的字段持有者按 GTM 约定拼装。 */
    public static final ManagedFieldHolder MANAGED_FIELD_HOLDER = MetaMachine.holder(
            WorkableMultiblockMachine.class, MultiblockControllerMachine.MANAGED_FIELD_HOLDER);

    /**
     * 配方逻辑（懒加载）。
     *
     * <p>
     * 刻意不加 {@code @Persisted}：{@link RecipeLogic} 在构造器里已经把自己的
     * {@code FieldManagedStorage} 挂到 BE 的根存储上了，它的
     * {@code status/progress/duration/lastRecipeId} 会自行存盘与同步；
     * 在机器上再标一次会把整个逻辑对象当成嵌套对象重复序列化。
     */
    @Nullable
    private RecipeLogic recipeLogic;

    public WorkableMultiblockMachine(IMachineBlockEntity holder) {
        super(holder);
    }

    @Override
    public ManagedFieldHolder getFieldHolder() {
        return MANAGED_FIELD_HOLDER;
    }

    // ═══════════════ 配方逻辑 ═══════════════

    /** 本机器的配方逻辑（第一次访问时创建）。 */
    public RecipeLogic getRecipeLogic() {
        if (recipeLogic == null) {
            recipeLogic = createRecipeLogic();
        }
        return recipeLogic;
    }

    /**
     * 创建配方逻辑。
     *
     * <p>
     * 子类换成自己的 {@link RecipeLogic} 子类（例如 {@code ThreadedRecipeLogic}）时覆写这里。
     * 不用自己挂同步存储 —— {@code RecipeLogic} 的构造器已经做了。
     */
    public RecipeLogic createRecipeLogic() {
        return new RecipeLogic(this);
    }

    /** 配方是否正在加工（UI / Jade 用）。 */
    public boolean isWorking() {
        return getRecipeLogic().isWorking();
    }

    /** 当前加工进度（tick）。 */
    public int getProgress() {
        return getRecipeLogic().getProgress();
    }

    // ═══════════════ 生命周期 ═══════════════

    @Override
    public void onLoad() {
        super.onLoad();
        // 提前创建 + 订阅服务端 tick：否则第一次存盘/同步时逻辑还没挂上去
        getRecipeLogic().onMachineLoad();
    }

    @Override
    public void onStructureFormed() {
        super.onStructureFormed();
        // 仓室变了 → 能跑的配方可能变了，让配方逻辑重新搜索并刷新订阅
        getRecipeLogic().markLastRecipeDirty();
        getRecipeLogic().updateTickSubscription();
    }

    @Override
    public void onStructureInvalid() {
        super.onStructureInvalid();
        getRecipeLogic().markLastRecipeDirty();
        getRecipeLogic().updateTickSubscription();
    }

    // ═══════════════ 展示 ═══════════════

    @Override
    public void addDisplayText(List<Component> textList) {
        super.addDisplayText(textList);
        // 配方状态/进度/等待原因的文案由 RecipeLogic 自己管（它那边有配套的语言键）
        getRecipeLogic().addDisplayText(textList);
    }
}
