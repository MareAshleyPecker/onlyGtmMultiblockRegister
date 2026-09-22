package rain.fox.ogmr.api.machine.trait;

import rain.fox.ogmr.api.OGMRValues;
import rain.fox.ogmr.api.lang.OGMRLang;
import rain.fox.ogmr.api.machine.MachineDefinition;
import rain.fox.ogmr.api.machine.MetaMachine;
import rain.fox.ogmr.api.machine.TickableSubscription;
import rain.fox.ogmr.api.recipe.OGMRRecipe;
import rain.fox.ogmr.api.recipe.OGMRRecipeType;
import rain.fox.ogmr.api.recipe.RecipeBuilder;

import com.lowdragmc.lowdraglib.syncdata.IManaged;
import com.lowdragmc.lowdraglib.syncdata.IManagedStorage;
import com.lowdragmc.lowdraglib.syncdata.ITagSerializable;
import com.lowdragmc.lowdraglib.syncdata.annotation.DescSynced;
import com.lowdragmc.lowdraglib.syncdata.annotation.Persisted;
import com.lowdragmc.lowdraglib.syncdata.field.FieldManagedStorage;
import com.lowdragmc.lowdraglib.syncdata.field.ManagedFieldHolder;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.crafting.RecipeManager;

import lombok.Getter;
import lombok.Setter;

import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Objects;

/**
 * 精简版配方逻辑 —— 「找配方 → 查输入/电力 → 跑进度 → 出料」的状态机。
 *
 * <p>
 * 从 GTM 的 {@code api.machine.trait.RecipeLogic} 大幅精简：
 * <ul>
 * <li>砍掉：配方修饰器（{@code RecipeModifier}）、研究条件、概率缓存的 NBT、暂停（SUSPEND）、
 * 平行加工、tick 配方、失败原因表、工作音、fancy tooltip；</li>
 * <li>保留：状态机、进度/时长、{@code lastRecipe} 缓存、{@code markLastRecipeDirty}、
 * 子类覆写点（{@code matchRecipe} / {@code onRecipeFinish} / {@code onWorking} / {@code onWaiting}）、
 * 显示文本；</li>
 * <li>改用 LDLib 的 {@code @Persisted}/{@code @DescSynced} 做存档与同步（GTM 也是这套，
 * 只是它还挂了 GT 自己的 {@code ManagedFieldHolder} 层级）。</li>
 * </ul>
 *
 * <p>
 * <b>能源与容器都是抽象的</b>：本库没有能源系统，也没有规定机器怎么存物品/流体，所以
 * 「耗电」「查输入」「出料」全部留成可覆写的 protected 方法，默认实现是
 * 「电无限、输入永远够、输出永远放得下、不真正搬运任何东西」—— 也就是说
 * <b>不覆写就能跑通状态机，覆写后才接上真实容器</b>：
 * <ul>
 * <li>{@link #getEnergyStored()} / {@link #consumeEnergy(long)} / {@link #addEnergy(long)}
 * —— 接入真实能源系统时覆写这三个（默认电无限）；</li>
 * <li>{@link #hasInputs(OGMRRecipe)} / {@link #consumeInputs(OGMRRecipe)} /
 * {@link #hasOutputSpace(OGMRRecipe)} / {@link #outputProducts(OGMRRecipe)}
 * —— 接入真实物品/流体容器时覆写这四个。</li>
 * </ul>
 * <b>本类不引用任何 GTM 的能量容器</b>（{@code NotifiableEnergyContainer} 之类），
 * 以免本库被 GTCEu 反向绑死。
 *
 * <p>
 * <b>机器层需要做的两件事</b>：
 * <ol>
 * <li>在机器构造完之后调用一次 {@link #updateTickSubscription()}（模块化机器已经在
 * {@code onLoad()} / 状态变化处这么做），之后本类会通过
 * {@link MetaMachine#subscribeServerTick(TickableSubscription, Runnable)} 自己续订服务端 tick，
 * 机器层不需要再手写 {@code serverTick()} 转发；</li>
 * <li>把本 trait 作为 {@code @Persisted(subPersisted = true)} 字段挂到机器上（或者直接依赖
 * 构造器里已经完成的同步存储挂载），存档/同步才会带上
 * {@code status/progress/duration/lastRecipeId}。</li>
 * </ol>
 */
public class RecipeLogic implements IManaged, ITagSerializable<CompoundTag> {

    /** LDLib 的字段句柄：{@code @Persisted} / {@code @DescSynced} 字段靠它驱动存档与同步。 */
    public static final ManagedFieldHolder MANAGED_FIELD_HOLDER = new ManagedFieldHolder(RecipeLogic.class);

    // ═══════════════ 语言键 ═══════════════

    public static final String LANG_NO_RECIPE = "ogmr.recipe.no_recipe";
    public static final String LANG_WAITING_INPUT = "ogmr.recipe.waiting.input";
    public static final String LANG_WAITING_ENERGY = "ogmr.recipe.waiting.energy";
    public static final String LANG_WAITING_OUTPUT = "ogmr.recipe.waiting.output";
    public static final String LANG_PROGRESS = "ogmr.recipe.progress";
    public static final String LANG_EUT = "ogmr.recipe.eut";
    public static final String LANG_TIER = "ogmr.recipe.tier";
    public static final String LANG_STATUS_IDLE = "ogmr.recipe.status.idle";
    public static final String LANG_STATUS_WORKING = "ogmr.recipe.status.working";
    public static final String LANG_STATUS_WAITING = "ogmr.recipe.status.waiting";

    /**
     * 登记本类用到的全部语言键。
     *
     * <p>
     * 必须在 <b>数据生成之前</b> 被调用一次（惯例是在 {@code Ogmr} 的构造里，
     * 与其它基类的 {@code initLang()} 放在一起），否则 {@code en_us}/{@code zh_cn} 里不会有这些键。
     * 重复调用是安全的：{@link OGMRLang#add} 对同一个键保留先登记的文案。
     */
    public static void initLang() {
        OGMRLang.add(LANG_NO_RECIPE, "No recipe available", "无可用配方");
        OGMRLang.add(LANG_WAITING_INPUT, "Waiting for input items / fluids", "等待输入物品或流体");
        OGMRLang.add(LANG_WAITING_ENERGY, "Not enough energy", "能源不足");
        OGMRLang.add(LANG_WAITING_OUTPUT, "Output is blocked", "输出堵塞");
        OGMRLang.add(LANG_PROGRESS, "Progress: %s / %s ticks", "进度：%s / %s tick");
        OGMRLang.add(LANG_EUT, "Energy: %s EU/t", "能耗：%s EU/t");
        OGMRLang.add(LANG_TIER, "Voltage: %s", "电压：%s");
        OGMRLang.add(LANG_STATUS_IDLE, "Status: Idle", "状态：空闲");
        OGMRLang.add(LANG_STATUS_WORKING, "Status: Working", "状态：运行中");
        OGMRLang.add(LANG_STATUS_WAITING, "Status: Waiting", "状态：等待");
    }

    // ═══════════════ 字段 ═══════════════

    @Getter
    protected final MetaMachine machine;
    @Getter
    private final IManagedStorage syncStorage = new FieldManagedStorage(this);

    @Getter
    @Persisted
    @DescSynced
    private RecipeLogicStatus status = RecipeLogicStatus.IDLE;

    @Getter
    @Persisted
    @DescSynced
    private int progress = 0;

    @Getter
    @Persisted
    @DescSynced
    private int duration = 0;

    /** 只持久化 id（配方本体是一次性的加载结果，存档里存不住也不该存）。 */
    @Nullable
    @Persisted
    @DescSynced
    private ResourceLocation lastRecipeId = null;

    /** 运行时缓存的当前配方（不持久化，与 GTM 一致）。 */
    @Nullable
    private OGMRRecipe lastRecipe = null;

    /** 当前让机器停在 WAITING 的原因（语言键）；不在等待或原因未知时为 null。 */
    @Nullable
    @Getter
    private String waitingReason = null;

    /**
     * 「为什么这条配方不跑」—— 由子类在 {@link #matchRecipe(OGMRRecipe)} 里
     * {@link #setFailureReason(Component)} 写入（例如模块等级不够、主机没接上）。
     *
     * <p>
     * 与 {@link #waitingReason} 的区别：那个是基类自己给出的「缺料/缺电/堵输出」，
     * 这个是子类的业务原因；两者都会进 {@link #addDisplayText(List)}。
     *
     * <p>
     * 约定用法：在 {@link #matchRecipe(OGMRRecipe)} 里判定不通过时先调用 setter、再
     * {@code return false}（模块化机器的「等级不够 / 主机没接上」就是这么写的）。
     * 整轮搜索都没跑起来时，这条原因会由 {@link #addDisplayText(List)} 显示出来；
     * 上一次搜索留下的失败原因没有则为 null。
     */
    @Nullable
    @Getter
    @Setter
    @Persisted
    @DescSynced
    private Component failureReason = null;

    /** 标记「上次那条配方别再用了」（机器结构变化/输入被玩家动过时调用）。 */
    private boolean recipeDirty = false;

    /** 缺电后的重试延迟（tick）。 */
    private int runDelay = 0;

    @Nullable
    protected TickableSubscription subscription = null;

    public RecipeLogic(MetaMachine machine) {
        this.machine = Objects.requireNonNull(machine, "machine");
        // 把自己的同步存储挂到 BE 的根存储上（与 MachineTrait 构造器里那一行同理）：
        // 不挂的话 status/progress/duration/lastRecipeId 这几个 @Persisted/@DescSynced 字段
        // 既不会存盘也不会同步到客户端。
        if (machine.holder.getRootStorage() != null) {
            machine.holder.getRootStorage().attach(syncStorage);
        }
    }

    // ═══════════════ 基本访问器 ═══════════════

    /** 进度百分比 0~1，给 UI 进度条用。 */
    public double getProgressPercent() {
        return duration <= 0 ? 0d : Math.min(1d, progress / (double) duration);
    }

    /** 上次/当前加工的配方；缓存丢了就按 id 从运行时配方表找回来。 */
    @Nullable
    public OGMRRecipe getLastRecipe() {
        if (lastRecipe == null && lastRecipeId != null) {
            lastRecipe = RecipeBuilder.ALL_RECIPES.get(lastRecipeId);
        }
        return lastRecipe;
    }

    public boolean isWorking() {
        return status.isWorking();
    }

    public boolean isIdle() {
        return status.isIdle();
    }

    public boolean isWaiting() {
        return status.isWaiting();
    }

    /** 状态是否活跃（运行中或等待中）—— 机器贴图是否需要「有反应」。 */
    public boolean isActive() {
        return isWorking() || isWaiting();
    }

    // ═══════════════ 状态迁移 ═══════════════

    public void setStatus(RecipeLogicStatus status) {
        if (this.status == status) return;
        this.status = status;
        if (status.isWorking()) {
            // 跑起来了：上一轮的失败原因作废（子类在 matchRecipe 里写下的那条）
            failureReason = null;
        }
        if (!status.isWaiting()) {
            waitingReason = null;
            runDelay = 0;
        }
        // 把「正在工作」写进方块状态：覆盖层里的发光层（active=true 才画）就是按它选模型的。
        // 只在值真的变了的时候才刷方块（MetaMachine 里判过），所以这里每 tick 调也安全。
        machine.setActiveState(status.isWorking());
        updateTickSubscription();
        machine.onChanged();
    }

    /** 进入 WAITING 并记下原因（语言键，UI 直接 {@code Component.translatable} 它）。 */
    protected void setWaiting(String reasonKey) {
        this.waitingReason = reasonKey;
        setStatus(RecipeLogicStatus.WAITING);
        onWaiting();
    }

    /** 中止当前配方并回到初始状态。 */
    public void resetRecipeLogic() {
        this.recipeDirty = false;
        this.lastRecipe = null;
        this.lastRecipeId = null;
        this.progress = 0;
        this.duration = 0;
        this.runDelay = 0;
        this.failureReason = null;
        setStatus(RecipeLogicStatus.IDLE);
        updateTickSubscription();
    }

    /** 把「上次那条配方」标记为脏：下一轮不会优先复用，而是重新搜索。 */
    public void markLastRecipeDirty() {
        this.recipeDirty = true;
    }

    /**
     * 订阅/刷新服务端 tick。
     *
     * <p>
     * 幂等：{@link MetaMachine#subscribeServerTick(TickableSubscription, Runnable)} 会复用还活着的
     * 旧订阅，所以可以跟在 {@link #setStatus(RecipeLogicStatus)} 后面随便调。
     * 机器层在构造完成 / 读档 / 结构变化后调一次即可。
     */
    public void updateTickSubscription() {
        if (machine.isRemote()) return;
        subscription = machine.subscribeServerTick(subscription, this::serverTick);
    }

    /** 机器载入世界时由 {@link MetaMachine#onLoad()} 转发（本类不是 trait 时由机器层主动调用）。 */
    public void onMachineLoad() {
        updateTickSubscription();
    }

    // ═══════════════ 状态机 ═══════════════

    /** 服务端每 tick 调用一次。 */
    public void serverTick() {
        // 配方逻辑只在服务端跑：客户端靠 @DescSynced 字段拿状态
        if (machine.isRemote()) return;

        OGMRRecipe recipe = lastRecipe;
        if (recipe != null && isWorking()) {
            if (progress < duration) {
                if (runDelay > 0) {
                    runDelay--;
                } else {
                    handleRecipeWorking();
                }
            }
            if (lastRecipe != null && progress >= duration) {
                onRecipeFinish();
            }
            return;
        }

        // 有配方但缺料/缺电：每 tick 重试一次，够了就自动转 WORKING
        if (recipe != null && isWaiting()) {
            handleRecipeWorking();
            return;
        }

        findAndHandleRecipe();
    }

    /** 找一条能跑的配方并启动它；找不到就回到 IDLE。 */
    protected void findAndHandleRecipe() {
        boolean dirty = recipeDirty;
        recipeDirty = false;

        // 优先复用上次那条（连续加工时省一次全表搜索）
        OGMRRecipe previous = lastRecipe;
        if (!dirty && previous != null && setupRecipe(previous)) return;

        if (checkMatchedRecipeAvailable()) return;

        lastRecipe = null;
        lastRecipeId = null;
        progress = 0;
        duration = 0;
        if (!isWaiting()) setStatus(RecipeLogicStatus.IDLE);
    }

    /**
     * 按机器定义的配方类型，在配方表里找第一条能用的配方并启动。
     *
     * <p>
     * 过滤顺序：配方类型 → {@link #matchRecipe(OGMRRecipe)} → 输出空间 → 输入 → 电力。
     * 结构上匹配、只是缺料/缺电的配方不会让搜索失败，而是把机器置为 {@link RecipeLogicStatus#WAITING}
     * 并记下原因，这样 UI 能显示「等待输入」而不是「无配方」。
     *
     * <p>
     * 子类在 {@link #matchRecipe(OGMRRecipe)} 里用 {@link #setFailureReason(Component)} 写下的原因
     * 会被保留到字段里，供 {@link #addDisplayText(List)} 显示（整轮搜索都没跑起来时才生效）。
     *
     * @return 是否成功启动了某条配方（启动成功后状态为 {@link RecipeLogicStatus#WORKING}）
     */
    public boolean checkMatchedRecipeAvailable() {
        MachineDefinition definition = machine.getDefinition();
        if (definition == null) return false;
        OGMRRecipeType[] recipeTypes = definition.getRecipeTypes();
        if (recipeTypes == null || recipeTypes.length == 0) return false;

        Component matchedFailure = null;
        String blockedReason = null;
        for (OGMRRecipeType recipeType : recipeTypes) {
            if (recipeType == null) continue;
            for (OGMRRecipe recipe : getRecipesFor(recipeType)) {
                // 每条配方重新判定：原因归零，交给 matchRecipe 自己填
                this.failureReason = null;
                if (!matchRecipe(recipe)) {
                    if (failureReason != null) matchedFailure = failureReason;
                    continue;
                }
                if (!hasOutputSpace(recipe)) {
                    if (blockedReason == null) blockedReason = LANG_WAITING_OUTPUT;
                    continue;
                }
                if (!hasInputs(recipe)) {
                    if (blockedReason == null) blockedReason = LANG_WAITING_INPUT;
                    continue;
                }
                if (recipe.isConsumer() && getEnergyStored() < recipe.getEut()) {
                    if (blockedReason == null) blockedReason = LANG_WAITING_ENERGY;
                    continue;
                }
                if (setupRecipe(recipe)) return true;
            }
        }

        // 一条都没跑起来：留下子类给的最后一条失败原因（没有就保持 null）
        this.failureReason = matchedFailure;
        if (blockedReason != null) setWaiting(blockedReason);
        return false;
    }

    /**
     * 某个配方类型下要搜索的配方列表。
     *
     * <p>
     * 优先用服务端 {@link RecipeManager} 里真实加载的配方（数据包改一条配方就能生效），
     * 服务端不存在（例如客户端、单测）时退回运行时表
     * {@link RecipeBuilder#ALL_RECIPES}（{@code save()} 期间登记的）。
     */
    protected List<OGMRRecipe> getRecipesFor(OGMRRecipeType recipeType) {
        MinecraftServer server = machine.getLevel() == null ? null : machine.getLevel().getServer();
        if (server != null) {
            List<OGMRRecipe> loaded = server.getRecipeManager().getAllRecipesFor(recipeType);
            if (loaded != null && !loaded.isEmpty()) return loaded;
        }
        return RecipeBuilder.byType(recipeType);
    }

    /**
     * 启动一条配方：查一次输入/输出/电力 → 扣输入 → 置 WORKING。
     *
     * <p>
     * 输入在<b>启动时</b>扣掉（与 GTM 一致：{@code setupRecipe} 做 {@code handleRecipeIO(IO.IN)}），
     * 输出在 {@link #onRecipeFinish()} 里产出。
     *
     * @return 是否启动成功；失败时机器会被置为 WAITING 并带上原因
     */
    protected boolean setupRecipe(OGMRRecipe recipe) {
        if (recipe == null) return false;
        if (!matchRecipe(recipe)) return false;
        if (!hasOutputSpace(recipe)) {
            setWaiting(LANG_WAITING_OUTPUT);
            return false;
        }
        if (!hasInputs(recipe)) {
            setWaiting(LANG_WAITING_INPUT);
            return false;
        }
        if (recipe.isConsumer() && getEnergyStored() < recipe.getEut()) {
            setWaiting(LANG_WAITING_ENERGY);
            return false;
        }
        if (!consumeInputs(recipe)) {
            setWaiting(LANG_WAITING_INPUT);
            return false;
        }

        this.lastRecipe = recipe;
        this.lastRecipeId = recipe.getId();
        this.progress = 0;
        this.duration = recipe.getDuration();
        this.runDelay = 0;
        // 找到新配方：子类上一轮写下的失败原因作废
        this.failureReason = null;
        setStatus(RecipeLogicStatus.WORKING);
        return true;
    }

    /** 跑一个 tick：扣电（或发电）→ 推进进度。缺电则转 WAITING 并延迟重试。 */
    protected void handleRecipeWorking() {
        OGMRRecipe recipe = lastRecipe;
        if (recipe == null) {
            resetRecipeLogic();
            return;
        }

        if (recipe.isConsumer()) {
            long eut = recipe.getEut();
            if (getEnergyStored() < eut || consumeEnergy(eut) < eut) {
                setWaiting(LANG_WAITING_ENERGY);
                // 缺电时别每 tick 都硬试：退避重试（GTM 是 runAttempt * 60）
                runDelay = Math.min(20, runDelay + 5);
                return;
            }
        } else if (recipe.isGenerator()) {
            addEnergy(-recipe.getEut());
        }

        setStatus(RecipeLogicStatus.WORKING);
        onWorking();
        progress++;
    }

    /**
     * 一条配方跑完：出料 → 进度归零 → 尽量接着跑同一条（连续加工），否则回 IDLE。
     *
     * <p>
     * 「接着跑」与 GTM 一样要重新过一遍输入/输出/电力检查，所以玩家把输出仓塞满时
     * 机器会自然地停下来而不是吐一地东西。
     */
    protected void onRecipeFinish() {
        OGMRRecipe recipe = lastRecipe;
        if (recipe == null) {
            resetRecipeLogic();
            return;
        }

        outputProducts(recipe);
        progress = 0;
        recipeDirty = false;
        runDelay = 0;

        if (setupRecipe(recipe)) return;

        // 跑不动了：清掉当前配方（保留 setupRecipe 给出的 WAITING 原因）
        lastRecipe = null;
        lastRecipeId = null;
        duration = 0;
        if (!isWaiting()) setStatus(RecipeLogicStatus.IDLE);
    }

    // ═══════════════ 覆写点 ═══════════════

    /**
     * 这条配方对本机器是否可用（结构/条件不满足时返回 false）。
     *
     * <p>
     * 默认恒 true，也就是「配方类型对上了就能跑」。子类要加门槛（例如需要某个仓室、
     * 需要某个方块状态、需要研究）就覆写它 —— 返回 false 表示「这条配方不可用」，
     * 搜索会继续看下一条，而不是让机器停在 WAITING。
     */
    protected boolean matchRecipe(OGMRRecipe recipe) {
        return true;
    }

    /**
     * 输入是否满足。
     *
     * <p>
     * TODO（机器层）：接入真实物品/流体容器后覆写，按
     * {@code recipe.getItemInputs()} / {@code getFluidInputs()} 逐条比对
     * （可用 {@code Content#matchesItem}/{@code matchesFluid}）。默认恒 true。
     */
    protected boolean hasInputs(OGMRRecipe recipe) {
        return true;
    }

    /**
     * 消耗输入（在 {@link #setupRecipe} 里调用一次）。
     *
     * <p>
     * TODO（机器层）：覆写后必须<b>真的扣掉</b>东西，并返回是否扣除成功；
     * 返回 false 会被当作「输入不足」处理。默认恒 true 且什么都不做。
     */
    protected boolean consumeInputs(OGMRRecipe recipe) {
        return true;
    }

    /** 输出空间是否放得下（默认恒 true）。TODO（机器层）：接入真实容器后覆写。 */
    protected boolean hasOutputSpace(OGMRRecipe recipe) {
        return true;
    }

    /** 产出物品/流体（在 {@link #onRecipeFinish()} 里调用一次）。默认什么都不做。 */
    protected void outputProducts(OGMRRecipe recipe) {}

    /**
     * 当前可用的能量（EU）。
     *
     * <p>
     * 默认 {@link Long#MAX_VALUE}（本库没有能源系统，等于「电无限」）。
     * <b>接入真实能源系统时覆写这个方法和 {@link #consumeEnergy(long)}。</b>
     */
    protected long getEnergyStored() {
        return Long.MAX_VALUE;
    }

    /**
     * 抽走 {@code amount} EU。
     *
     * <p>
     * 返回值是<b>实际抽到的量</b>：小于 {@code amount} 视为供能不足，机器会转 WAITING。
     * 默认原样返回 {@code amount}（配合默认的 {@link #getEnergyStored()} 就是电无限）。
     * <b>接入真实能源系统时覆写这个方法和 {@link #getEnergyStored()}。</b>
     */
    protected long consumeEnergy(long amount) {
        return amount;
    }

    /**
     * 存进 {@code amount} EU（发电配方用；{@code eut < 0} 时每 tick 调用）。
     *
     * <p>
     * 返回值是<b>实际存进去的量</b>，默认原样返回。接入真实能源系统时覆写。
     */
    protected long addEnergy(long amount) {
        return amount;
    }

    /** 开始跑一个 tick 时调用（默认空实现）。 */
    protected void onWorking() {}

    /** 转入 WAITING 时调用（默认空实现）。 */
    protected void onWaiting() {}

    // ═══════════════ 显示 ═══════════════

    /**
     * 状态/进度文本，由机器层的 {@code addDisplayText} 转发进来。
     *
     * <p>
     * 输出顺序：状态行 → 配方能耗/电压 → 进度或等待原因 → 子类的失败原因。
     * 子类（{@code ModuleRecipeLogic} 等）如果在自己的 {@code addDisplayText} 里已经补过
     * {@link #getFailureReason()}，可以把这里那一行忽略掉，避免同一句显示两遍。
     */
    public void addDisplayText(List<Component> textList) {
        if (textList == null) return;

        textList.add(Component.translatable(statusLangKey()));

        OGMRRecipe recipe = getLastRecipe();
        if (recipe != null) {
            textList.add(Component.translatable(LANG_EUT, recipe.getEut()));
            textList.add(Component.translatable(LANG_TIER, OGMRValues.tierNameRaw(recipe.getTier())));
        }

        if (isWorking()) {
            textList.add(Component.translatable(LANG_PROGRESS, progress, duration));
        } else if (isWaiting()) {
            textList.add(Component.translatable(waitingReason == null ? LANG_WAITING_INPUT : waitingReason));
        } else if (failureReason != null) {
            // 空闲且子类给了业务原因（等级不够 / 主机没接上……）：显示它而不是笼统的「无配方」
            textList.add(failureReason);
        } else {
            textList.add(Component.translatable(LANG_NO_RECIPE));
        }
    }

    /** 当前状态对应的语言键。 */
    public String statusLangKey() {
        return switch (status) {
            case WORKING -> LANG_STATUS_WORKING;
            case WAITING -> LANG_STATUS_WAITING;
            case IDLE -> LANG_STATUS_IDLE;
        };
    }

    // ═══════════════ IManaged / ITagSerializable ═══════════════

    @Override
    public ManagedFieldHolder getFieldHolder() {
        return MANAGED_FIELD_HOLDER;
    }

    @Override
    public void onChanged() {
        machine.onChanged();
    }

    /**
     * 手写 NBT 存档。
     *
     * <p>
     * {@code @Persisted} 字段其实已经由 LDLib 的字段系统存了；这里额外提供
     * {@link ITagSerializable} 是为了兼容「把 trait 丢进自定义容器/网络包」的用法，
     * 内容与那几个字段保持一致。
     */
    @Override
    public CompoundTag serializeNBT() {
        CompoundTag tag = new CompoundTag();
        tag.putString("status", status.getSerializedName());
        tag.putInt("progress", progress);
        tag.putInt("duration", duration);
        if (lastRecipeId != null) {
            tag.putString("last_recipe", lastRecipeId.toString());
        }
        return tag;
    }

    @Override
    public void deserializeNBT(CompoundTag tag) {
        if (tag == null) return;
        this.status = RecipeLogicStatus.byName(tag.getString("status"));
        this.progress = Math.max(0, tag.getInt("progress"));
        this.duration = Math.max(0, tag.getInt("duration"));
        this.lastRecipeId = tag.contains("last_recipe") ?
                ResourceLocation.tryParse(tag.getString("last_recipe")) : null;
        this.lastRecipe = null;
        this.waitingReason = null;
        this.failureReason = null;
        this.recipeDirty = false;
    }

    @Override
    public String toString() {
        return "RecipeLogic[%s, %d/%d, recipe=%s]".formatted(status, progress, duration, lastRecipeId);
    }
}
