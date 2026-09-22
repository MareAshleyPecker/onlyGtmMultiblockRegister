package rain.fox.ogmr.threading;

import rain.fox.ogmr.api.lang.OGMRLang;
import rain.fox.ogmr.api.machine.IMachineBlockEntity;
import rain.fox.ogmr.api.machine.multiblock.WorkableMultiblockMachine;
import rain.fox.ogmr.api.machine.trait.RecipeLogic;
import rain.fox.ogmr.utils.Formatting;

import net.minecraft.network.chat.Component;

import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * 「多线程多方块」的示例基类：一台机器同时跑 N 条配方。
 *
 * <p>
 * Java 重写自 GTEternalTime 里「实现 {@code IThreadedRecipeMachine} + 覆写 {@code createRecipeLogic}」
 * 的那套接线（GTET 侧是一个标记接口 + 每台机器自己写 {@code createRecipeLogic}，
 * 没有公共基类），改动：
 * <ul>
 * <li>把接线固化成基类：{@link #createRecipeLogic()} 直接返回 {@link ThreadedRecipeLogic}，
 * 线程仓缓存与线程数刷新由结构生命周期回调统一维护，addon 只要继承本类 + 画结构图案；</li>
 * <li>线程数从「取最大的一个线程仓」改成<b>结构里所有仓求和</b>，并被
 * {@code OGMRConfig.maxThreadCount} 夹一次；没装仓（或仓都没成型）时退化为 1
 * —— 退化成「一台机器一条配方」的原版行为，而不是「一条都不跑」；</li>
 * <li>Kotlin 版的线程仓默认实现是「现扫 {@code getParts()}」（不缓存）；本版缓存一份，
 * 在 {@link #onStructureFormed()}/{@link #onStructureInvalid()}/{@link #onUnload()} 里刷新 ——
 * 部件的装卸就发生在这三个时刻，缓存不会过期，也省掉每 tick 扫结构。</li>
 * </ul>
 *
 * <h3>子类要做的三件事</h3>
 * <ol>
 * <li>画结构图案：线程仓位置用 {@code Predicates.abilities(ThreadHatchPartMachine.threadAbility())}
 * 匹配（否则结构里装不上线程仓，线程数永远是 1）；</li>
 * <li>把配方类型配好（{@code .recipeType(...)}）—— 线程逻辑就按机器定义里的配方类型找候选配方；</li>
 * <li>接上真实的物品/流体/能源容器：覆写 {@link #createRecipeLogic()} 返回自己的
 * {@link ThreadedRecipeLogic} 子类，在子类里覆写 {@code hasInputs}/{@code consumeInputs}/
 * {@code hasOutputSpace}/{@code outputProducts}。</li>
 * </ol>
 *
 * <p>
 * ⚠️ 与并行（parallel）的关系见 {@link ThreadedRecipeLogic} 的类注释：
 * <b>本逻辑自己按线程施加并行，机器的配方修改器里不要叠加并行修改器</b>。
 */
public abstract class ThreadedMultiblockMachine extends WorkableMultiblockMachine implements IThreadedRecipeMachine {

    /** 面板：{@code Thread hatches %s · up to %s threads}（参数 = 仓数、线程数上限）。 */
    public static final String LANG_HATCHES = "ogmr.threading.machine.hatches";
    /** 面板：{@code No thread hatch — running as a single-thread machine}。 */
    public static final String LANG_NO_HATCH = "ogmr.threading.machine.no_hatch";

    /** 结构里的线程仓缓存（成型/失效/卸载时刷新）。 */
    private final List<IThreadHatch> threadHatches = new ArrayList<>();

    public ThreadedMultiblockMachine(IMachineBlockEntity holder) {
        super(holder);
    }

    // ═══════════════ 接线 ═══════════════

    /**
     * 本机的配方逻辑就是 {@link ThreadedRecipeLogic}（多线程内核）。
     *
     * <p>
     * 需要接真实容器时覆写本方法，返回自己的 {@link ThreadedRecipeLogic} 子类；
     * ⚠️ 覆写时请照抄下面这段「挂同步存储」——{@link RecipeLogic} 不是 {@code MachineTrait}，
     * 它的 {@code FieldManagedStorage} 必须显式挂到 BE 的根存储上，
     * 否则那几个 {@code @Persisted}/{@code @DescSynced} 字段（状态/进度/时长/配方 id）
     * 既不会存盘也不会同步（基类的实现就是这么做的，本方法把它重写了一遍）。
     */
    @Override
    public RecipeLogic createRecipeLogic() {
        RecipeLogic logic = new ThreadedRecipeLogic(this);
        if (holder.getRootStorage() != null) {
            holder.getRootStorage().attach(logic.getSyncStorage());
        }
        return logic;
    }

    @Override
    @Nullable
    public ThreadedRecipeLogic getThreadedRecipeLogic() {
        RecipeLogic logic = getRecipeLogic();
        return logic instanceof ThreadedRecipeLogic threaded ? threaded : null;
    }

    // ═══════════════ 线程仓 ═══════════════

    @Override
    public List<? extends IThreadHatch> getThreadHatches() {
        if (threadHatches.isEmpty() && isFormed()) refreshThreadHatches();
        return List.copyOf(threadHatches);
    }

    /**
     * 当前结构允许的最大线程数 = 结构里所有线程仓的当前线程数之<b>和</b>（只算可用的仓）。
     *
     * <p>
     * 没装线程仓（或者仓都没成型）时返回 1：线程逻辑照常跑，只是只有一条线程可用，
     * 行为退化回原版「一台机器一条配方」。
     */
    @Override
    public int getMaxThreads() {
        if (threadHatches.isEmpty() && isFormed()) refreshThreadHatches();

        int sum = 0;
        for (IThreadHatch hatch : threadHatches) {
            if (hatch.isActive()) sum += Math.max(0, hatch.getThreadCount());
        }
        if (sum <= 0) return 1;
        return Math.min(sum, ThreadedRecipeLogic.maxThreadCount());
    }

    /** 重新扫描结构里的线程仓（成型/失效时调用）。 */
    protected void refreshThreadHatches() {
        threadHatches.clear();
        for (var part : getParts()) {
            if (part instanceof IThreadHatch hatch) threadHatches.add(hatch);
        }
    }

    /**
     * 把「结构现在的线程数」推给配方逻辑。
     *
     * <p>
     * 只在结构成型时推：{@link ThreadedRecipeLogic} 会把它夹到 {@code OGMRConfig.maxThreadCount}，
     * 并保证已开线程不被砍掉（见它类注释里的槽位规则）。
     */
    protected void applyThreadCount() {
        ThreadedRecipeLogic logic = getThreadedRecipeLogic();
        if (logic == null) return;
        logic.setThreadCount(isFormed() ? getMaxThreads() : 0);
        // 线程数变了，候选池与「上次那条配方」都可能过期
        logic.markLastRecipeDirty();
    }

    // ═══════════════ 结构生命周期 ═══════════════

    @Override
    public void onStructureFormed() {
        super.onStructureFormed();
        refreshThreadHatches();
        applyThreadCount();
        ThreadedRecipeLogic logic = getThreadedRecipeLogic();
        if (logic != null) {
            // 配方逻辑没活干时会自己退订 tick（基类行为），成型时唤醒一次
            logic.updateTickSubscription();
        }
    }

    @Override
    public void onStructureInvalid() {
        threadHatches.clear();
        ThreadedRecipeLogic logic = getThreadedRecipeLogic();
        if (logic != null) logic.setThreadCount(0);
        super.onStructureInvalid();
    }

    @Override
    public void onUnload() {
        threadHatches.clear();
        super.onUnload();
    }

    // ═══════════════ 面板 ═══════════════

    @Override
    public void addDisplayText(List<Component> textList) {
        super.addDisplayText(textList);

        if (threadHatches.isEmpty()) {
            textList.add(Component.translatable(LANG_NO_HATCH));
            return;
        }
        textList.add(Component.translatable(LANG_HATCHES,
                Formatting.formatNumber(threadHatches.size()),
                Formatting.formatNumber(getMaxThreads())));
    }

    // ═══════════════ 语言 ═══════════════

    /** 登记本类用到的语言键（幂等）。 */
    public static void initLang() {
        OGMRLang.add(LANG_HATCHES, "Thread hatches %s · up to %s threads",
                "线程仓 %s 个 · 最多 %s 条线程");
        OGMRLang.add(LANG_NO_HATCH, "No thread hatch — running as a single-thread machine",
                "没有线程仓 —— 按单线程机器运行");
    }

    @Override
    public String toString() {
        return "ThreadedMultiblockMachine[hatches=%d, maxThreads=%d]".formatted(
                threadHatches.size(), getMaxThreads());
    }
}
