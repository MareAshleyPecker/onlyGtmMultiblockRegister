package rain.fox.ogmr.threading;

import rain.fox.ogmr.api.lang.OGMRLang;
import rain.fox.ogmr.api.recipe.OGMRRecipe;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import lombok.Getter;
import lombok.Setter;

import org.jetbrains.annotations.Nullable;

/**
 * <b>一条线程</b>的运行时状态（不是 {@code RecipeLogicStatus} 那个机器级枚举）。
 *
 * <p>
 * Java 重写自 GTEternalTime 的 {@code thread/ThreadedRecipeStatus.kt}，改动：
 * <ul>
 * <li>Kotlin 版这个类其实是一个 {@code object}（工具类），装的是「语言键 + 同配方合并 + 产物合并 + 面板文本」
 * 一整套<b>显示层</b>逻辑，线程本体是逻辑类里的 {@code data class ThreadRec}；本版按需求把两者合成一个
 * 对象：<b>一个对象 = 一条线程</b>，既有自己的进度/状态，也负责把自己渲染成一行面板文本；</li>
 * <li>「同一种配方合并成组」（Kotlin 的 {@code groupSnapshots}）被删掉 —— 那是为 GTET 的
 * 「同配方多线程（fan-out）」显示服务的，本版每条线程跑一种自己的配方，不需要合并；</li>
 * <li>「产出明细」（Kotlin 的 {GroupSnapshot#outputs}：按物品合并、概率折算期望值、最多 4 种）
 * 简化成一句<b>产出摘要文本</b>（{@link #getLastOutputs()}）：只在面板上给玩家看一眼上一轮出了什么，
 * 不进网络、不进 Jade 的 NBT。</li>
 * </ul>
 *
 * <p>
 * 生命周期：{@code IDLE} →（逻辑开线程时 {@link #attachRecipe}）→ {@code WORKING} →
 * 到点结算回 {@code IDLE}（{@link #reset()}）；缺电/条件不满足时变成 {@code WAITING}（进度保留），
 * 出不了货这种硬错误是 {@code ERROR}。
 *
 * <p>
 * 存档只存「配方 id + 进度 + 状态」，不存配方本体（与 Kotlin 版一致：配方从配方库按 id 重取）。
 */
public class ThreadedRecipeStatus {

    /** 一条线程的状态。 */
    public enum State {

        /** 空闲（没有配方）。 */
        IDLE,
        /** 正在推进。 */
        WORKING,
        /** 有配方但在等（缺电 / 条件不满足），进度保留。 */
        WAITING,
        /** 出错（本条线程不再推进，等下一次重新开线程）。 */
        ERROR
    }

    // ═══════════════ 语言键（ogmr.threading.*） ═══════════════

    /** 空闲行：{@code #%s  idle}（参数 = 线程号）。 */
    public static final String LANG_LINE_IDLE = "ogmr.threading.line.idle";
    /** 推进行：{@code #%s  %s/%s t (%s%%)  %s}（参数 = 线程号、进度、总时长、百分比、配方 id）。 */
    public static final String LANG_LINE_PROGRESS = "ogmr.threading.line.progress";
    /** 等待行：{@code #%s  waiting: %s}（参数 = 线程号、原因）。 */
    public static final String LANG_LINE_WAITING = "ogmr.threading.line.waiting";
    /** 出错行：{@code #%s  error: %s}（参数 = 线程号、原因）。 */
    public static final String LANG_LINE_ERROR = "ogmr.threading.line.error";
    /** 上一轮产出（附加在行尾）：{@code  · out %s}（参数 = 产出摘要）。 */
    public static final String LANG_LINE_OUTPUTS = "ogmr.threading.line.outputs";
    /** 配方 id 未知时的占位。 */
    public static final String LANG_UNKNOWN_RECIPE = "ogmr.threading.unknown_recipe";

    // ═══════════════ NBT 键 ═══════════════

    private static final String TAG_RECIPE = "recipe";
    private static final String TAG_PROGRESS = "progress";
    private static final String TAG_DURATION = "duration";
    private static final String TAG_STATE = "state";
    private static final String TAG_FAILURE = "failure";
    private static final String TAG_OUTPUTS = "outputs";

    // ═══════════════ 字段 ═══════════════

    /** 线程下标（= 槽位号，面板上的「第几条线程」）。 */
    @Getter
    private final int index;

    /**
     * 这条线程<b>自己那份</b>配方（不进存档 —— 存档只存 id）。
     *
     * <p>
     * 不是线程安全意义上的「工作副本」：本逻辑只在服务端主线程 tick 里碰它，
     * 之所以每条线程各持一份，是因为每条线程要各带自己的并行倍数与时长。
     */
    @Nullable
    @Getter
    private OGMRRecipe recipe;

    /** 当前配方的 id（{@code recipe} 为 null 时可能来自存档）。 */
    @Nullable
    private ResourceLocation recipeId;

    /** 本条线程自己的进度（tick）。 */
    @Getter
    private int progress;

    /** 开线程那一刻钉下来的耗时（tick）—— 钉死而不是每 tick 读配方，免得中途进度条乱跳。 */
    @Getter
    private int duration;

    @Getter
    private State state = State.IDLE;

    /**
     * 失败/等待原因（{@code null} = 没有）。
     *
     * <p>
     * 存的是<b>语言键</b>（例如 {@code ogmr.recipe.waiting.energy}）而不是成品文本：
     * 原因由逻辑层给出、由面板层 {@code Component.translatable} 渲染，中英各显示各的。
     */
    @Nullable
    @Getter
    @Setter
    private String failureReason;

    /** 上一轮完成时的产出摘要（面板显示用；{@link #reset()} 不会清它）。 */
    @Nullable
    @Getter
    @Setter
    private String lastOutputs;

    public ThreadedRecipeStatus(int index) {
        this.index = index;
    }

    // ═══════════════ 访问 ═══════════════

    /** 绑定这条线程要跑的配方（配方本体由调用方负责各线程一份）。 */
    public void attachRecipe(@Nullable OGMRRecipe recipe) {
        this.recipe = recipe;
        this.recipeId = recipe != null ? recipe.getId() : null;
        this.duration = recipe != null ? recipe.getDuration() : 0;
    }

    @Nullable
    public ResourceLocation getRecipeId() {
        if (recipeId == null && recipe != null) return recipe.getId();
        return recipeId;
    }

    public void setProgress(int progress) {
        this.progress = Math.max(0, progress);
    }

    public void setDuration(int duration) {
        this.duration = Math.max(0, duration);
    }

    public void setState(State state) {
        this.state = state != null ? state : State.IDLE;
    }

    /** 进度百分比（0~100）；时长为 0 时返回 0（除零比显示个 0% 糟糕得多）。 */
    public int getPercent() {
        if (duration <= 0) return 0;
        return Math.min(100, progress * 100 / duration);
    }

    /**
     * 这条线程此刻是不是「占着槽位」。
     *
     * <p>
     * {@code WAITING} 也算占着 —— 它在等电/等条件，进度与已经扣掉的料都还在，
     * 不能被别的配方抢走这个槽位（这与 Kotlin 版 {@code threads[slot] != null} 的判定等价）。
     */
    public boolean isRunning() {
        return state == State.WORKING || state == State.WAITING;
    }

    /**
     * 复位成空闲。
     *
     * <p>
     * ⚠️ {@link #lastOutputs} <b>故意保留</b>：面板上「上一轮出了什么」在机器空转时才有信息量，
     * 清掉的话玩家一抬头只能看到一片 idle。
     */
    public void reset() {
        this.recipe = null;
        this.recipeId = null;
        this.progress = 0;
        this.duration = 0;
        this.state = State.IDLE;
        this.failureReason = null;
    }

    // ═══════════════ 显示 ═══════════════

    /**
     * 面板上的一行文本：{@code #0  137/200 t (68%)  mymod:grinding}。
     *
     * <p>
     * 语言键全部走 {@code ogmr.threading.*}（见 {@link ThreadedRecipeLogic#initLang()}）。
     */
    public Component toDisplayText() {
        Component line = switch (state) {
            case IDLE -> Component.translatable(LANG_LINE_IDLE, index);
            case WORKING -> Component.translatable(LANG_LINE_PROGRESS,
                    index, progress, duration, getPercent(), recipeName());
            case WAITING -> Component.translatable(LANG_LINE_WAITING, index,
                    reasonText());
            case ERROR -> Component.translatable(LANG_LINE_ERROR, index,
                    reasonText());
        };
        if (lastOutputs != null && !lastOutputs.isEmpty() && !isRunning()) {
            line = line.copy().append(Component.translatable(LANG_LINE_OUTPUTS, lastOutputs));
        }
        return line;
    }

    private Component recipeName() {
        ResourceLocation id = getRecipeId();
        return id != null ? Component.literal(id.toString()) : Component.translatable(LANG_UNKNOWN_RECIPE);
    }

    private Component reasonText() {
        // failureReason 存的是**语言键**（例如 ogmr.recipe.waiting.energy）；translatable 对
        // 「不是键的裸文本」会原样显示，所以两种写法都安全
        return failureReason != null && !failureReason.isEmpty() ?
                Component.translatable(failureReason) : Component.translatable(LANG_UNKNOWN_RECIPE);
    }

    // ═══════════════ 存档 ═══════════════

    /**
     * 只存「配方 id + 进度 + 时长 + 状态 + 原因 + 上一轮产出」。
     *
     * <p>
     * 不存配方本体：配方从配方库按 id 能重新取到，存 id 就不会「配方被数据包改掉后存档读不回来」。
     */
    public CompoundTag serializeNBT() {
        CompoundTag tag = new CompoundTag();
        ResourceLocation id = getRecipeId();
        if (id != null) tag.putString(TAG_RECIPE, id.toString());
        tag.putInt(TAG_PROGRESS, progress);
        tag.putInt(TAG_DURATION, duration);
        tag.putString(TAG_STATE, state.name());
        if (failureReason != null) tag.putString(TAG_FAILURE, failureReason);
        if (lastOutputs != null) tag.putString(TAG_OUTPUTS, lastOutputs);
        return tag;
    }

    /**
     * 与 {@link #serializeNBT()} 对称。
     *
     * <p>
     * ⚠️ 读回来的<b>不是</b>可跑的线程：{@link #recipe} 仍然是 null，只有 id 与进度；
     * 想真的跑起来得由 {@link ThreadedRecipeLogic} 按 id 重取配方再 {@link #attachRecipe}。
     */
    public void deserializeNBT(CompoundTag tag) {
        if (tag == null) return;
        this.recipe = null;
        this.recipeId = tag.contains(TAG_RECIPE) ? ResourceLocation.tryParse(tag.getString(TAG_RECIPE)) : null;
        this.progress = Math.max(0, tag.getInt(TAG_PROGRESS));
        this.duration = Math.max(0, tag.getInt(TAG_DURATION));
        this.state = parseState(tag.getString(TAG_STATE));
        this.failureReason = tag.contains(TAG_FAILURE) ? tag.getString(TAG_FAILURE) : null;
        this.lastOutputs = tag.contains(TAG_OUTPUTS) ? tag.getString(TAG_OUTPUTS) : null;
    }

    private static State parseState(String name) {
        for (State value : State.values()) {
            if (value.name().equals(name)) return value;
        }
        return State.IDLE;
    }

    // ═══════════════ 语言 ═══════════════

    /** 登记本类用到的语言键（幂等；由 {@link ThreadedRecipeLogic#initLang()} 统一调用）。 */
    public static void initLang() {
        OGMRLang.add(LANG_LINE_IDLE, "#%s  idle", "#%s  空闲");
        OGMRLang.add(LANG_LINE_PROGRESS, "#%s  %s/%s t (%s%%)  %s", "#%s  %s/%s t（%s%%）  %s");
        OGMRLang.add(LANG_LINE_WAITING, "#%s  waiting: %s", "#%s  等待：%s");
        OGMRLang.add(LANG_LINE_ERROR, "#%s  error: %s", "#%s  出错：%s");
        OGMRLang.add(LANG_LINE_OUTPUTS, "  · out %s", "  · 产出 %s");
        OGMRLang.add(LANG_UNKNOWN_RECIPE, "unknown", "未知");
    }

    @Override
    public String toString() {
        return "ThreadedRecipeStatus[#%d, %s, %d/%d, %s]".formatted(index, getRecipeId(), progress, duration, state);
    }
}
