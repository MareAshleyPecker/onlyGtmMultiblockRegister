package rain.fox.ogmr.threading;

import rain.fox.ogmr.OGMRConfig;
import rain.fox.ogmr.api.energy.IEnergyContainer;
import rain.fox.ogmr.api.lang.OGMRLang;
import rain.fox.ogmr.api.machine.MachineDefinition;
import rain.fox.ogmr.api.machine.multiblock.WorkableMultiblockMachine;
import rain.fox.ogmr.api.machine.trait.RecipeLogic;
import rain.fox.ogmr.api.machine.trait.RecipeLogicStatus;
import rain.fox.ogmr.api.recipe.Content;
import rain.fox.ogmr.api.recipe.OGMRRecipe;
import rain.fox.ogmr.api.recipe.OGMRRecipeType;
import rain.fox.ogmr.api.recipe.RecipeBuilder;
import rain.fox.ogmr.utils.Formatting;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.fluids.FluidStack;

import lombok.Getter;

import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * 「多线程配方逻辑内核」。
 *
 * <p>
 * Java 重写自 GTEternalTime 的 {@code thread/ThreadedRecipeLogic.kt}，改动：
 * <ul>
 * <li>Kotlin 版是 800 行、把「找配方 → 套超频/批处理修改器 → 按聚合预算算每线程并行 → 真扣料」整套
 * 都自己实现（因为 GTM 的并行/超频全在 {@code ParallelLogic}/{@code ModifierFunction} 里）；
 * 本库没有那套修改器链路，所以本类<b>只保留调度内核</b>：找配方、每线程计时、每线程扣电、
 * 到点结算，其余（物品/流体输入输出、电的存取）全部走 {@link RecipeLogic} 已经留好的覆写点
 * （{@link #hasInputs}/{@link #consumeInputs}/{@link #hasOutputSpace}/{@link #outputProducts}/
 * {@link #getEnergyStored}/{@link #consumeEnergy}/{@link #addEnergy}）；</li>
 * <li><b>删掉了「同配方多线程（fan-out）」</b>：Kotlin 版第二轮会把空闲线程再发给<b>已经在跑的</b>
 * 同一种配方，并为它做「一组一预算、再均分」的防超发记账（{@code planThreadParallel} /
 * {@code committedUnits}，整整 100 行）。本版回到第一轮语义 —— <b>一条线程一种配方</b>，
 * 空闲线程只会去找「还没有别的线程在跑」的配方。因此也不需要 {@code isSameRecipe} 的并行记账，
 * 只按配方 id 判「这种配方是不是已经被占了」；</li>
 * <li>并行口径改成显式的 {@link #parallelPerThread()}（默认 1）：Kotlin 版从并行仓现读
 * {@code getCurrentParallel()} 并用 {@code ParallelLogic#getParallelAmount} 收缩，
 * 本库没有并行仓，所以留成一个倍数钩子（见类注释末尾的契约）；</li>
 * <li>状态改成「基类状态 + 每线程状态」两层：基类的 {@link RecipeLogicStatus} 依旧反映整机
 * （有线程在推进 = WORKING、有线程在等 = WAITING、一条都没有 = IDLE），
 * 每条线程自己的进度/状态/原因在 {@link ThreadedRecipeStatus} 里；</li>
 * <li>存档走 {@link #serializeNBT()}/{@link #deserializeNBT(CompoundTag)}（LDLib 的
 * {@code ITagSerializable} 通道，基类已经在用），只存「配方 id + 进度」；</li>
 * <li>Kotlin 版的「临时线程诊断日志」{@code diagnose()} 与配置项 {@code SendThreadDiagnosticlog}
 * 没有移植（本库没有对应配置项），改成 {@link #getRunningThreadCount()} /
 * {@link #getTotalConsumedEu()} 这类只读访问器，需要时 addon 自己打日志。</li>
 * </ul>
 *
 * <h3>调度策略</h3>
 * <p>
 * 每个 {@code serverTick}：
 * <ol>
 * <li><b>推进</b>：遍历所有<b>已在跑</b>的线程槽，各自扣每 tick 的电（EU/t）→ {@code progress++}，
 * 到点就出料并释放槽位。线程之间完全独立，一条线程在等电不影响别的线程；</li>
 * <li><b>开新线程</b>：只有存在空闲槽位时才去找配方，并且<b>每 5 tick 才搜一次</b>
 * （{@link #SEARCH_INTERVAL}，避免每 tick 翻配方库）。候选顺序 = 机器定义里的配方类型 →
 * {@link RecipeLogic#getRecipesFor(OGMRRecipeType)} → {@link #matchRecipe} 复核 →
 * 输出空间 → 输入 → 电力 → {@link #bindThreadRecipe} → 真扣料；任何一步失败只是「这条候选不能用」，
 * 不影响别的线程。一条候选只能占一条线程（同一配方 id 已经在跑就跳过）；</li>
 * <li><b>上限</b>：能开几条线程由 {@link #getThreadCount()} 决定，它由机器从结构里的线程仓求和
 * 得到（见 {@link ThreadedMultiblockMachine#getMaxThreads()}），并被
 * {@code OGMRConfig.maxThreadCount} 夹过。</li>
 * </ol>
 *
 * <h3>⚠️ 与 GTM 的并行（parallel）机制的关系 —— 不要叠加并行修改器</h3>
 * <p>
 * <b>本逻辑自己按线程施加并行</b>：一台机器的吞吐 = 线程数 × 单线程吞吐，而「单线程吞吐」由
 * {@link #parallelPerThread()} 给出（默认 1，即一条线程一次配方）。
 * 因此接入本逻辑的多方块，<b>不要再叠加并行修改器</b>（GTM 的 {@code PARALLEL_HATCH} 那类）：
 * 线程层已经乘过一次，再乘一次就是「并行²」，而且这种超发会绕过本类的记账
 * （本类只按「几个线程 × 每线程几倍」算吞吐，看不到上游修改器偷偷乘的那一份）。
 * 想要「一条线程跑多次」，请覆写 {@link #parallelPerThread()} 而不是挂并行修改器。
 *
 * <h3>v1 已知简化（诚实标注）</h3>
 * <ol>
 * <li><b>不主动退订 tick</b>：机器成型期间保持每 tick 一次 {@link #serverTick()}（空转时开销很小），
 * 而不是像 Kotlin 版那样「一条线程都没有就退订、等部件内容变化再唤醒」——
 * 本库没有「谁来唤醒」的现成契约，硬退订有把机器卡死的风险；</li>
 * <li><b>等待时进度不回退</b>：Kotlin 版缺电会按 {@code regressWhenWaiting()} 把进度退回 1；
 * 本版原地保留进度，来电后从原地继续；</li>
 * <li><b>发电配方只记账不出电</b>：{@code eut < 0} 的配方会调 {@link #addEnergy(long)}，
 * 但默认实现什么都不做（与基类一致）；</li>
 * <li><b>不打断已在跑的线程</b>：{@code markLastRecipeDirty()} 只影响之后新开的线程，
 * 已经在跑的线程跑完为止（它们的时长/倍率是开线程那一刻的快照）；</li>
 * <li><b>恢复是尽力而为</b>：读档时按配方 id 从 {@link RecipeBuilder#ALL_RECIPES} 重取，
 * 配方被数据包改掉 / 结构没成型 → 那条线程直接消失（已经扣掉的料不退还，与基类
 * {@code resetRecipeLogic()} 的行为一致），恢复时<b>不再扣一次料</b>；</li>
 * <li><b>出料口满了会丢产物</b>：{@link #outputProducts(OGMRRecipe)} 的返回值为空，
 * 与基类 {@code RecipeLogic#onRecipeFinish} 的行为一致（它同样不看结果）；</li>
 * <li><b>机器级 {@code onWorking()}/{@code onWaiting()} 每 tick 只调一次</b>（不是每条线程一次），
 * 部件回调不会被线程数放大 —— 代价是「某条线程的独立失败」无法通过这两个钩子表达；</li>
 * <li><b>候选池是缓存的</b>：{@link RecipeBuilder} / {@code RecipeManager} 的搜索结果是按
 * 机器定义里的配方类型算出来的快照，靠 {@link #markLastRecipeDirty()} 失效（结构变化、
 * 部件内容变化时机器层会调它）。数据包热改配方需要它被调一次才会看到。</li>
 * <li><b>整机口径的「单配方进度」靠覆写 getter 镜像</b>：Kotlin 版把「下标最小的在跑线程」
 * 写进基类的四个<b>已同步</b>字段；本库基类的 {@code progress}/{@code duration}/{@code lastRecipe}
 * 都是 <b>private</b>（子类写不进去），所以只能在 getter 这一层镜像
 * （{@link #getProgress()}/{@link #getDuration()}/{@link #getProgressPercent()}/{@link #getLastRecipe()}）——
 * 服务端读得到「其中一条线程」的进度，客户端仍然读不到（线程表不进同步字段）。</li>
 * </ol>
 *
 * @author rain fox（Java 重写）
 */
public class ThreadedRecipeLogic extends RecipeLogic {

    // ═══════════════ 语言键（ogmr.threading.*） ═══════════════

    /** 整机线程状态：{@code Threads %s / %s in use}（参数 = 在用、上限）。 */
    public static final String LANG_THREADS = "ogmr.threading.status";
    /** 整机累计耗电：{@code Consumed %s EU in total}（参数 = 已格式化的数字）。 */
    public static final String LANG_TOTAL_EU = "ogmr.threading.total_eu";
    /** 明细截断：{@code ...and %s more threads (showing first %s)}。 */
    public static final String LANG_MORE = "ogmr.threading.more";
    /** 线程等待原因：{@code Not enough energy}（沿用基类的口径，本类也用它）。 */
    public static final String LANG_NO_ENERGY = RecipeLogic.LANG_WAITING_ENERGY;

    // ═══════════════ 常量 ═══════════════

    /** 空闲槽位找配方的节流间隔（tick）。 */
    public static final int SEARCH_INTERVAL = 5;

    /** 面板最多列几条线程（再多就是刷屏；其余用一行「还有 N 条」带过）。 */
    public static final int MAX_DISPLAY_THREADS = 8;

    /** 产出摘要里最多列几种产物。 */
    private static final int MAX_SUMMARY_KINDS = 4;

    /** 线程表在 NBT 里的键。 */
    private static final String TAG_THREADS = "ogmr_threads";

    /**
     * 线程数上限的兜底值。
     *
     * <p>
     * 现在「配置没加载时用什么默认值」这件事统一由 {@link OGMRConfig} 的 getter 负责，
     * 这里只是把那个默认值再引用一次，避免两处各写一个 64 而慢慢漂移。
     */
    private static final int FALLBACK_MAX_THREADS = OGMRConfig.MAX_THREAD_COUNT;

    // ═══════════════ 字段 ═══════════════

    /**
     * 持有本逻辑的多方块。
     *
     * <p>
     * 基类已经有一个 {@code protected final MetaMachine machine}，这里再存一份强类型的引用
     * （要调多方块专有的 {@code getParts()}），所以刻意换了名字，避免遮蔽基类字段。
     */
    private final WorkableMultiblockMachine workableMachine;

    /**
     * 线程槽表：下标 = 槽位，对象永远非 null（{@link ThreadedRecipeStatus#getRecipe()} 为 null = 空闲）。
     *
     * <p>
     * 只增不减（见 {@link #ensureCapacity()}）：玩家下调线程数时超出的槽位保留到跑完为止
     * —— 砍掉一个已经在跑的线程等于把它扣过的料凭空吞了。
     */
    private ThreadedRecipeStatus[] threads = new ThreadedRecipeStatus[0];

    /** 每条线程最近一轮实际吃掉的电（EU）；开线程/结算时清零。 */
    private long[] consumedEu = new long[0];

    /** 当前生效的线程数上限（由机器按结构里的线程仓设置）。 */
    @Getter
    private int threadCount;

    /** 整机累计耗电（EU），统计用。 */
    @Getter
    private long totalConsumedEu;

    /** 搜索节流计数。 */
    private int searchCooldown;

    /** 候选配方池（按机器的配方类型算出来的快照；见类注释「v1 已知简化」第 8 条）。 */
    @Nullable
    private List<OGMRRecipe> candidatePool;

    /** 候选池是否需要重建。 */
    private boolean poolDirty = true;

    /** 存档读出来、还没恢复的线程表。 */
    @Nullable
    private ListTag pendingRestore;

    /** 本 tick 缓存的能源仓（{@code IEnergyContainer}）。 */
    private List<IEnergyContainer> energyHatches = List.of();

    /**
     * @param machine 持有本逻辑的多方块（通常会实现 {@link IThreadedRecipeMachine}，
     *                线程数由结构里的线程仓决定；不实现时线程数保持在配置的默认值）
     */
    public ThreadedRecipeLogic(WorkableMultiblockMachine machine) {
        super(machine);
        this.workableMachine = machine;
        this.threadCount = clampThreadCount(defaultThreadCount());
    }

    // ═══════════════ 线程数 ═══════════════

    /**
     * 设置当前生效的线程数（夹到 {@code [0, OGMRConfig.maxThreadCount]}）。
     *
     * <p>
     * 允许 0：结构失效时机器会调 {@code setThreadCount(0)}，表示「一条都别开」。
     * 下调<b>不会</b>杀掉已经在跑的线程（见 {@link #threads} 的注释）。
     */
    public void setThreadCount(int count) {
        int clamped = clampThreadCount(count);
        if (clamped == threadCount) return;
        threadCount = clamped;
        ensureCapacity();
    }

    /** 正在跑的线程条数。 */
    public int getRunningThreadCount() {
        int running = 0;
        for (ThreadedRecipeStatus status : threads) {
            if (status.getRecipe() != null) running++;
        }
        return running;
    }

    /** 第 {@code index} 条线程的状态对象（下标越界时返回 null）。 */
    @Nullable
    public ThreadedRecipeStatus getThreadStatus(int index) {
        return index >= 0 && index < threads.length ? threads[index] : null;
    }

    /** 第 {@code index} 条线程最近一轮吃掉的电（EU）。 */
    public long getConsumedEu(int index) {
        return index >= 0 && index < consumedEu.length ? consumedEu[index] : 0L;
    }

    /** 单机线程数上限（读配置 getter；配置未加载时它自己会回落到默认值）。 */
    public static int maxThreadCount() {
        return Math.max(1, OGMRConfig.getMaxThreadCount());
    }

    /** 默认线程数（读配置 getter；配置未加载时它自己会回落到默认值）。 */
    public static int defaultThreadCount() {
        return Math.max(1, OGMRConfig.getDefaultThreadCount());
    }

    private static int clampThreadCount(int count) {
        if (count <= 0) return 0;
        return Math.min(count, maxThreadCount());
    }

    // ═══════════════ 主循环 ═══════════════

    /**
     * 服务端每 tick 一次：先推进所有在跑的线程，再（节流地）给空闲槽位找新配方。
     *
     * <p>
     * 这里<b>不做重活</b>：找配方被 {@link #SEARCH_INTERVAL} 节流，候选池是缓存的，
     * 每条线程每 tick 只做「扣电 + progress++」两件事。
     */
    @Override
    public void serverTick() {
        // 与基类一致：配方逻辑只在服务端跑
        if (machine.isRemote()) return;

        // 结构没成型：清掉线程表（否则会留下跑不了的幽灵线程）
        if (!workableMachine.isFormed()) {
            if (getRunningThreadCount() > 0) {
                clearThreads();
                setStatus(RecipeLogicStatus.IDLE);
            }
            return;
        }

        ensureCapacity();
        restoreThreadsIfPossible();
        refreshEnergyHatches();

        final int limit = Math.min(threadCount, threads.length);
        boolean progressed = false;
        boolean waitingNow = false;

        for (int slot = 0; slot < threads.length; slot++) {
            ThreadedRecipeStatus status = threads[slot];
            if (status.getRecipe() == null) continue;
            String reason = advanceThread(slot, status);
            if (reason == null) {
                progressed = true;
            } else {
                waitingNow = true;
            }
        }

        boolean started = false;
        if (searchCooldown > 0) searchCooldown--;
        if (searchCooldown <= 0) {
            searchCooldown = SEARCH_INTERVAL;
            if (freeSlot(limit) >= 0) started = startThreads(limit);
        }
        if (started) {
            // 刚开出线程：保证 tick 订阅是活的（机器实现 RecipeLogic.TickSubscriptionHost 时才有实际动作）
            updateTickSubscription();
        }

        syncStatus(progressed, waitingNow);
    }

    /**
     * 推进一条线程。
     *
     * @return {@code null} = 这一 tick 正常推进了；非 null = 没推进 + 这个原因（语言键）
     */
    private String advanceThread(int slot, ThreadedRecipeStatus status) {
        OGMRRecipe recipe = status.getRecipe();
        if (recipe == null) return null;

        // ① 每 tick 的电（每条线程各扣各的）
        if (recipe.isConsumer()) {
            long eut = recipe.getEut();
            if (getEnergyStored() < eut || consumeEnergy(eut) < eut) {
                status.setState(ThreadedRecipeStatus.State.WAITING);
                status.setFailureReason(LANG_NO_ENERGY);
                // 进度原地保留（见类注释「v1 已知简化」第 2 条）
                return LANG_NO_ENERGY;
            }
            consumedEu[slot] += eut;
            totalConsumedEu += eut;
        } else if (recipe.isGenerator()) {
            addEnergy(-recipe.getEut());
        }

        // ② 推进一格；到点结算
        status.setState(ThreadedRecipeStatus.State.WORKING);
        status.setFailureReason(null);
        status.setProgress(status.getProgress() + 1);
        if (status.getProgress() >= status.getDuration()) finishThread(slot, status);
        return null;
    }

    /** 一条线程到点：出料 → 记下产出摘要 → 释放槽位。 */
    private void finishThread(int slot, ThreadedRecipeStatus status) {
        OGMRRecipe recipe = status.getRecipe();
        if (recipe != null) {
            outputProducts(recipe);
            status.setLastOutputs(summarizeOutputs(recipe));
        }
        consumedEu[slot] = 0L;
        status.reset();
    }

    // ═══════════════ 开新线程 ═══════════════

    /**
     * 给空闲槽位找配方（一条线程一种配方）。
     *
     * @param limit 当前生效的线程数上限（只有它以内的槽位能被占）
     * @return 这一轮有没有开出新线程
     */
    private boolean startThreads(int limit) {
        List<OGMRRecipe> pool = candidatePool();
        if (pool.isEmpty()) return false;

        boolean started = false;
        for (OGMRRecipe candidate : pool) {
            int slot = freeSlot(limit);
            if (slot < 0) break;
            if (candidate == null) continue;
            // 同一种配方只能占一条线程（Kotlin 版第一轮的语义）
            if (isRunningElsewhere(candidate)) continue;
            if (!matchRecipe(candidate)) continue;
            if (!hasOutputSpace(candidate)) continue;
            if (!hasInputs(candidate)) continue;
            if (candidate.isConsumer() && getEnergyStored() < candidate.getEut()) continue;

            OGMRRecipe bound = bindThreadRecipe(candidate);
            if (bound == null) continue;
            // 真扣料：失败只是「这条候选不能用」（前面已模拟匹配过）
            if (!consumeInputs(bound)) continue;

            ThreadedRecipeStatus status = threads[slot];
            status.attachRecipe(bound);
            status.setProgress(0);
            status.setState(ThreadedRecipeStatus.State.WORKING);
            status.setFailureReason(null);
            consumedEu[slot] = 0L;
            started = true;
        }
        return started;
    }

    /**
     * 把候选配方变成「这条线程自己那份」：先拷一份，再套 {@link #parallelPerThread()} 的倍数。
     *
     * <p>
     * 各线程必须各持一份：时长是开线程那一刻钉下来的，倍率也是各线程自己的。
     *
     * <p>
     * 覆写点：本库没有 GTM 的 {@code fullModifyRecipe}（超频/批处理修改器链路），
     * 要加「机器自己的配方修改」就在这里改 —— {@link OGMRRecipe} 提供
     * {@code setDuration(int)} / {@code setEut(long)}，内容表是不可变的，倍数请走
     * {@link #parallelPerThread()} 或自己 {@code copy()} 出新的 {@link Content} 列表。
     *
     * @return 这条线程要跑的配方；{@code null} = 这条候选当下开不出线程
     */
    @Nullable
    protected OGMRRecipe bindThreadRecipe(OGMRRecipe origin) {
        if (origin == null) return null;
        OGMRRecipe copy = origin.copy();
        int parallels = parallelPerThread();
        return parallels > 1 ? applyParallel(copy, parallels) : copy;
    }

    /**
     * <b>每条线程</b>施加的并行倍数（默认 1）。
     *
     * <p>
     * 整机吞吐 = 线程数 × 本倍数，所以这个值就是「线程之上再叠一层并行」的唯一入口
     * （见类注释末尾的契约）。默认 1 表示一条线程就加工一次配方。
     */
    protected int parallelPerThread() {
        return 1;
    }

    /**
     * 把并行倍数套到配方上：内容 ×p、EU/t ×p（时长不变）。
     *
     * <p>
     * 产物数量也一起乘 —— 与「同一种配方同时加工 p 次」的口径一致。
     * 概率内容的 {@code chance} 不动（输入端本来就不掷骰，输出端掷一次总比掷 p 次省事）。
     */
    protected OGMRRecipe applyParallel(OGMRRecipe recipe, int parallels) {
        if (recipe == null || parallels <= 1) return recipe;
        List<Content> inputs = scaleContents(recipe.getInputs(), parallels);
        List<Content> outputs = scaleContents(recipe.getOutputs(), parallels);
        long eut = saturatingMultiply(recipe.getEut(), parallels);
        return new OGMRRecipe(recipe.getRecipeType(), recipe.getId(), inputs, outputs,
                recipe.getDuration(), eut);
    }

    private static List<Content> scaleContents(List<Content> contents, int parallels) {
        List<Content> scaled = new ArrayList<>(contents.size());
        for (Content content : contents) {
            long count = (long) content.count() * parallels;
            scaled.add(content.withCount((int) Math.min(count, Integer.MAX_VALUE)));
        }
        return scaled;
    }

    private static long saturatingMultiply(long value, int factor) {
        long result = value * factor;
        if (value != 0 && (result / factor != value)) return value > 0 ? Long.MAX_VALUE : Long.MIN_VALUE;
        return result;
    }

    /** 这种配方是不是已经有别的线程在跑（按配方 id 判定；id 为 null 时退化成引用相等）。 */
    private boolean isRunningElsewhere(OGMRRecipe candidate) {
        ResourceLocation id = candidate.getId();
        for (ThreadedRecipeStatus status : threads) {
            OGMRRecipe running = status.getRecipe();
            if (running == null) continue;
            ResourceLocation other = running.getId();
            if (id != null && other != null) {
                if (id.equals(other)) return true;
            } else if (running == candidate) {
                return true;
            }
        }
        return false;
    }

    /**
     * 候选配方池：机器定义里的每个配方类型 → {@link RecipeLogic#getRecipesFor(OGMRRecipeType)}
     * （服务端优先用 {@code RecipeManager} 里真实加载的配方）。
     *
     * <p>
     * 结果是缓存的：搜索每 5 tick 跑一次，而翻配方库不便宜（见类注释「v1 已知简化」第 8 条）。
     */
    private List<OGMRRecipe> candidatePool() {
        List<OGMRRecipe> cached = candidatePool;
        if (!poolDirty && cached != null) return cached;

        poolDirty = false;
        List<OGMRRecipe> pool = new ArrayList<>();
        MachineDefinition definition = machine.getDefinition();
        OGMRRecipeType[] recipeTypes = definition == null ? null : definition.getRecipeTypes();
        if (recipeTypes != null) {
            for (OGMRRecipeType recipeType : recipeTypes) {
                if (recipeType == null) continue;
                List<OGMRRecipe> recipes = getRecipesFor(recipeType);
                if (recipes != null) pool.addAll(recipes);
            }
        }
        if (pool.isEmpty()) {
            // 空池**不进缓存**：配方在客户端早期/服务端配方未加载时会短暂为空，
            // 把它缓存下来会让机器永远闲着。空的代价只是一次列表查询，5 tick 重试一次可以接受。
            poolDirty = true;
        }
        candidatePool = pool;
        return pool;
    }

    // ═══════════════ 槽位与状态 ═══════════════

    /** 按当前线程数把槽表<b>加长</b>（不缩短），并给新槽建好状态对象。 */
    private void ensureCapacity() {
        int need = Math.max(1, threadCount);
        if (threads.length >= need) return;

        ThreadedRecipeStatus[] grownThreads = new ThreadedRecipeStatus[need];
        System.arraycopy(threads, 0, grownThreads, 0, threads.length);
        for (int i = threads.length; i < need; i++) {
            grownThreads[i] = new ThreadedRecipeStatus(i);
        }
        threads = grownThreads;

        long[] grownEu = new long[need];
        System.arraycopy(consumedEu, 0, grownEu, 0, consumedEu.length);
        consumedEu = grownEu;
    }

    /** 线程上限以内的第一个空闲槽位；没有则返回 -1。 */
    private int freeSlot(int limit) {
        int bound = Math.min(limit, threads.length);
        for (int i = 0; i < bound; i++) {
            if (threads[i].getRecipe() == null) return i;
        }
        return -1;
    }

    /** 清空所有线程（结构失效/整机复位时用）。 */
    private void clearThreads() {
        for (int i = 0; i < threads.length; i++) {
            threads[i].reset();
            consumedEu[i] = 0L;
        }
    }

    /** 把基类的整机状态对齐到「线程表的实际状态」。 */
    private void syncStatus(boolean progressed, boolean waitingNow) {
        if (getRunningThreadCount() == 0) {
            if (!getStatus().isIdle()) setStatus(RecipeLogicStatus.IDLE);
            return;
        }
        if (progressed) {
            setStatus(RecipeLogicStatus.WORKING);
        } else if (waitingNow) {
            // setWaiting 每次都调 onWaiting()，所以只在状态真的变了的时候调
            if (!getStatus().isWaiting()) setWaiting(LANG_NO_ENERGY);
        } else if (getStatus().isIdle()) {
            setStatus(RecipeLogicStatus.WORKING);
        }
    }

    // ═══════════════ 电 ═══════════════

    /** 把结构里所有 {@code IEnergyContainer} 仓室抓一份快照（每 tick 刷新一次）。 */
    private void refreshEnergyHatches() {
        List<IEnergyContainer> hatches = new ArrayList<>();
        for (var part : workableMachine.getParts()) {
            if (part instanceof IEnergyContainer container) hatches.add(container);
        }
        energyHatches = hatches;
    }

    /**
     * 当前可用能量（EU）：结构里所有能源仓之和。
     *
     * <p>
     * 一个能源仓都没有时返回 {@link Long#MAX_VALUE}（= 电无限，与基类默认一致）——
     * 「拿不到就当成无限」是为了让「只想试试线程调度」的机器不至于一条都开不出来。
     */
    @Override
    protected long getEnergyStored() {
        List<IEnergyContainer> hatches = energyHatches;
        if (hatches.isEmpty()) return Long.MAX_VALUE;
        long sum = 0L;
        for (IEnergyContainer hatch : hatches) {
            sum += hatch.getEnergyStored();
            if (sum < 0L) return Long.MAX_VALUE; // 溢出保护
        }
        return sum;
    }

    /**
     * 从结构里的能源仓抽出 {@code amount} EU，返回<b>实际抽到</b>的量。
     *
     * <p>
     * 逐个仓抽，抽不满就少抽（永远不会抽成负数）；一个能源仓都没有时原样返回
     * {@code amount}（电无限），与基类默认行为一致。
     */
    @Override
    protected long consumeEnergy(long amount) {
        if (amount <= 0L) return 0L;
        List<IEnergyContainer> hatches = energyHatches;
        if (hatches.isEmpty()) return amount;

        long remaining = amount;
        long consumed = 0L;
        for (IEnergyContainer hatch : hatches) {
            if (remaining <= 0L) break;
            long got = hatch.extractEnergy(remaining);
            if (got <= 0L) continue;
            consumed += got;
            remaining -= got;
        }
        return consumed;
    }

    // ═══════════════ 生命周期 / 存档 ═══════════════

    /** 结构变化会让候选池过期（机器层在成型/失效时会调 {@code markLastRecipeDirty()}）。 */
    @Override
    public void markLastRecipeDirty() {
        super.markLastRecipeDirty();
        this.poolDirty = true;
    }

    /** 整机复位：线程表必须一起清掉（否则会留下跑不了的幽灵线程）。 */
    @Override
    public void resetRecipeLogic() {
        super.resetRecipeLogic();
        clearThreads();
        pendingRestore = null;
        candidatePool = null;
        poolDirty = true;
        totalConsumedEu = 0L;
        searchCooldown = 0;
    }

    /**
     * 线程表 → NBT（只存「占着槽位」的线程；每条的内容见 {@link ThreadedRecipeStatus#serializeNBT()}）。
     *
     * <p>
     * 可以单独用：addon 想自己挑持久化通道时把它塞进自己的 tag 即可。
     */
    public ListTag saveThreads() {
        ListTag list = new ListTag();
        for (ThreadedRecipeStatus status : threads) {
            if (status.getRecipe() == null) continue;
            list.add(status.serializeNBT());
        }
        return list;
    }

    /** 载入线程表（下一次 {@link #serverTick()} 时尽力恢复；见类注释「v1 已知简化」第 5 条）。 */
    public void loadThreads(@Nullable ListTag list) {
        if (list == null || list.isEmpty()) {
            pendingRestore = null;
            return;
        }
        pendingRestore = list;
    }

    /** 存档：基类的字段 + 本类的线程表。 */
    @Override
    public CompoundTag serializeNBT() {
        CompoundTag tag = super.serializeNBT();
        ListTag list = saveThreads();
        if (!list.isEmpty()) tag.put(TAG_THREADS, list);
        return tag;
    }

    /** 读档：与 {@link #serializeNBT()} 对称；线程表只是登记下来，等结构成型后再恢复。 */
    @Override
    public void deserializeNBT(CompoundTag tag) {
        super.deserializeNBT(tag);
        loadThreads(tag == null ? null : tag.getList(TAG_THREADS, Tag.TAG_COMPOUND));
    }

    /**
     * 按存档里的配方 id 把线程恢复起来（<b>不再扣一次料</b>：料在存档前就扣过了）。
     *
     * <p>
     * 只在结构成型时才恢复；配方已被数据包删掉 / id 找不到 → 那条线程直接消失。
     */
    private void restoreThreadsIfPossible() {
        ListTag pending = pendingRestore;
        if (pending == null) return;
        if (!workableMachine.isFormed()) return;
        pendingRestore = null;

        ensureCapacity();
        int limit = Math.min(threadCount, threads.length);
        int slot = 0;
        for (int i = 0; i < pending.size() && slot < limit; i++) {
            CompoundTag entry = pending.getCompound(i);
            ThreadedRecipeStatus status = threads[slot];
            status.deserializeNBT(entry);

            ResourceLocation id = status.getRecipeId();
            OGMRRecipe recipe = id == null ? null : RecipeBuilder.ALL_RECIPES.get(id);
            if (recipe == null) {
                status.reset();
                continue;
            }
            OGMRRecipe bound = bindThreadRecipe(recipe);
            if (bound == null) {
                status.reset();
                continue;
            }
            status.attachRecipe(bound);
            status.setProgress(Math.min(status.getProgress(), bound.getDuration()));
            status.setState(ThreadedRecipeStatus.State.WORKING);
            status.setFailureReason(null);
            consumedEu[slot] = 0L;
            slot++;
        }
    }

    // ═══════════════ 整机口径镜像 ═══════════════

    /**
     * 下标最小的那条在跑线程（没有在跑的线程时返回 null）。
     *
     * <p>
     * Kotlin 版把这条线程镜像进基类的同步字段，让机器 UI / Jade 那套「单配方进度」照旧能用；
     * 本库基类的字段是 private，只能在下面这几个 getter 上镜像 ——
     * 多条线程同时跑时它们只反映<b>一条</b>线程（有意为之的 v1 简化）。
     */
    @Nullable
    protected ThreadedRecipeStatus primaryThread() {
        for (ThreadedRecipeStatus status : threads) {
            if (status.getRecipe() != null) return status;
        }
        return null;
    }

    @Override
    public int getProgress() {
        ThreadedRecipeStatus primary = primaryThread();
        return primary != null ? primary.getProgress() : 0;
    }

    @Override
    public int getDuration() {
        ThreadedRecipeStatus primary = primaryThread();
        return primary != null ? primary.getDuration() : 0;
    }

    @Override
    public double getProgressPercent() {
        ThreadedRecipeStatus primary = primaryThread();
        if (primary == null || primary.getDuration() <= 0) return 0d;
        return Math.min(1d, primary.getProgress() / (double) primary.getDuration());
    }

    /** 当前配方的镜像：优先给「下标最小的在跑线程」那条，没有在跑线程时回落到基类的缓存。 */
    @Override
    @Nullable
    public OGMRRecipe getLastRecipe() {
        ThreadedRecipeStatus primary = primaryThread();
        return primary != null ? primary.getRecipe() : super.getLastRecipe();
    }

    // ═══════════════ 显示 ═══════════════

    /**
     * 逐线程一行：{@code #0  137/200 t (68%)  mymod:grinding}。
     *
     * <p>
     * 线程多时只列前 {@link #MAX_DISPLAY_THREADS} 条，再加一行「还有 N 条」。
     *
     * <p>
     * ⚠️ 两条口径：
     * <ul>
     * <li><b>只在服务端求值</b>：线程表没有做 {@code @DescSynced} 同步，客户端读到的是空表，
     * 显示「在用 0 / 4」比不显示更误导，所以客户端直接收手；</li>
     * <li><b>不调 super</b>：基类那几行是「单配方」口径（进度/当前配方），在多线程机上是噪音；
     * 整机状态由本类的第一行给出。</li>
     * </ul>
     */
    @Override
    public void addDisplayText(List<Component> textList) {
        if (machine.isRemote()) return;

        int limit = Math.min(threadCount, threads.length);
        if (limit <= 0) return;

        textList.add(Component.translatable(LANG_THREADS,
                Formatting.formatNumber(getRunningThreadCount()),
                Formatting.formatNumber(limit)));
        if (totalConsumedEu > 0L) {
            textList.add(Component.translatable(LANG_TOTAL_EU, Formatting.formatNumber(totalConsumedEu)));
        }

        int shown = Math.min(limit, MAX_DISPLAY_THREADS);
        for (int i = 0; i < shown; i++) {
            textList.add(threads[i].toDisplayText());
        }
        if (limit > shown) {
            textList.add(Component.translatable(LANG_MORE,
                    Formatting.formatNumber(limit - shown),
                    Formatting.formatNumber(shown)));
        }
    }

    /**
     * 一条配方的产出摘要（面板显示用）：{@code 铁粉 ×2、水 100mB}。
     *
     * <p>
     * Kotlin 版这里是「按物品合并 + 概率折算期望值 + 最多 4 种 + 图标/名字两套口径」的一大套显示逻辑；
     * 本版只取代表性物品/流体堆的名字与数量，最多 {@link #MAX_SUMMARY_KINDS} 种，够看一眼就行。
     */
    protected String summarizeOutputs(OGMRRecipe recipe) {
        StringBuilder builder = new StringBuilder();
        int shown = 0;
        for (Content content : recipe.getOutputs()) {
            if (content.isEmpty()) continue;
            // 展示口径按「先物品、再流体、都不行就交给内容种类自己描述」的顺序退让，
            // 这样第三方注册的内容种类（能量、魔力……）也能出现在摘要里，不用改这里。
            String text = null;
            ItemStack stack = content.representativeItem();
            if (!stack.isEmpty()) {
                text = stack.getHoverName().getString() + " ×" + Formatting.formatNumber(content.count());
            } else {
                FluidStack fluid = content.representativeFluid();
                if (!fluid.isEmpty()) {
                    text = fluid.getDisplayName().getString() + " " + Formatting.formatNumber(content.count()) + "mB";
                }
            }
            if (text == null) text = content.toString();
            if (shown > 0) builder.append(", ");
            builder.append(text);
            shown++;
            if (shown >= MAX_SUMMARY_KINDS) {
                builder.append("…");
                break;
            }
        }
        return builder.toString();
    }

    // ═══════════════ 语言 ═══════════════

    /**
     * 登记本子系统用到的全部语言键（幂等）。
     *
     * <p>
     * 必须在<b>数据生成之前</b>调用一次（惯例是 addon 的 {@code initLang()} 里，
     * 或直接在 {@code registerPartAbilities()} 附近调一次）；
     * {@link ThreadedHatches} 的静态初始化会顺手调它，所以「用便捷注册」的 addon 不必再管。
     */
    public static void initLang() {
        OGMRLang.add(LANG_THREADS, "Threads %s / %s in use", "线程 %s / %s 在用");
        OGMRLang.add(LANG_TOTAL_EU, "Consumed %s EU in total", "累计耗电 %s EU");
        OGMRLang.add(LANG_MORE, "  ...and %s more threads (showing first %s)",
                "  ……还有 %s 条线程（仅显示前 %s 条）");
        ThreadedRecipeStatus.initLang();
    }

    @Override
    public String toString() {
        return "ThreadedRecipeLogic[%d/%d threads, %d EU]".formatted(
                getRunningThreadCount(), threadCount, totalConsumedEu);
    }
}
