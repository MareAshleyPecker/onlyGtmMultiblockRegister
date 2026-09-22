package rain.fox.ogmr.api.machine.multiblock;

import rain.fox.ogmr.api.machine.IMachineBlockEntity;
import rain.fox.ogmr.api.machine.MetaMachine;
import rain.fox.ogmr.api.machine.MultiblockMachineDefinition;
import rain.fox.ogmr.api.pattern.BlockPattern;
import rain.fox.ogmr.api.pattern.MultiblockState;
import rain.fox.ogmr.api.pattern.util.IPatternFacingProvider;

import com.lowdragmc.lowdraglib.syncdata.annotation.DescSynced;
import com.lowdragmc.lowdraglib.syncdata.annotation.Persisted;
import com.lowdragmc.lowdraglib.syncdata.field.ManagedFieldHolder;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;

import lombok.Getter;

import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 本文件是从 GTM 的 {@code com.gregtechceu.gtceu.api.machine.multiblock.MultiblockControllerMachine}
 * 精简拆出来的。
 *
 * <p>
 * 多方块控制器的运行时基类。相对 GTM 砍掉了：
 * <ul>
 * <li>异步线程结构检查（{@code MultiblockWorldSavedData} / {@code asyncCheckPattern}）——
 * 本库直接在服务端 tick 里做，按 {@link #CHECK_INTERVAL} 节流；</li>
 * <li>并行仓（{@code IParallelHatch}）、自动搭建入口、维护仓/消声仓的特殊处理；</li>
 * <li>渲染状态属性（用 {@link Persisted} {@link DescSynced} 的 {@code formed} 字段替代）。</li>
 * </ul>
 *
 * <p>
 * <b>本类实现了 {@link IPatternFacingProvider}</b>：{@link BlockPattern} 会根据
 * {@link #getFrontFacing()} / {@link #getUpwardsFacing()} / {@link #allowFlip()} / {@link #isFlipped()}
 * 做朝向旋转与上下翻转匹配。
 *
 * <p>
 * <b>子类覆写</b>：{@link #onStructureFormed()} / {@link #onStructureInvalid()} 覆写时必须调用
 * {@code super}，否则仓室列表不会被维护。
 */
public class MultiblockControllerMachine extends MetaMachine implements IMultiController, IPatternFacingProvider {

    /** 子类的字段持有者按 GTM 约定拼装。 */
    public static final ManagedFieldHolder MANAGED_FIELD_HOLDER = MetaMachine.holder(
            MultiblockControllerMachine.class, MetaMachine.MANAGED_FIELD_HOLDER);

    /** 结构检查的最小间隔（tick）。{@link #requestCheck()} 可以把下一次检查提前到下一个 tick。 */
    public static final int CHECK_INTERVAL = 40;

    /** 结构是否成型。 */
    @Getter
    @Persisted
    @DescSynced
    protected boolean formed;

    /** 当前结构是否为「上下翻转」状态（由图案匹配写入）。 */
    @Getter
    @Persisted
    @DescSynced
    protected boolean flipped;

    /** 当前挂着的仓室。 */
    @Getter
    protected final List<IMultiPart> parts = new ArrayList<>();

    /** 结构匹配游标状态（懒加载）。 */
    @Nullable
    protected MultiblockState multiblockState;

    /** 正在检查结构（防重入）。 */
    protected volatile boolean checking;

    /** 距离下一次结构检查还有多少 tick。 */
    protected volatile int checkCooldown;

    public MultiblockControllerMachine(IMachineBlockEntity holder) {
        super(holder);
    }

    // ═══════════════ 基本访问器 ═══════════════

    @Override
    public ManagedFieldHolder getFieldHolder() {
        return MANAGED_FIELD_HOLDER;
    }

    @Override
    public MultiblockMachineDefinition getDefinition() {
        return (MultiblockMachineDefinition) super.getDefinition();
    }

    @Override
    public MetaMachine self() {
        return this;
    }

    @Override
    public void addPart(IMultiPart part) {
        if (part != null && !parts.contains(part)) {
            parts.add(part);
        }
    }

    @Override
    public void removePart(IMultiPart part) {
        parts.remove(part);
    }

    /** 结构匹配用的状态对象（每次检查前会 {@code clean()}）。 */
    public MultiblockState getMultiblockState() {
        if (multiblockState == null) {
            multiblockState = new MultiblockState(getLevel(), getPos());
        }
        return multiblockState;
    }

    // ═══════════════ 朝向（IPatternFacingProvider） ═══════════════

    /** 控制器正面朝向：按定义声明的朝向属性读，没朝向属性就是 NORTH。 */
    @Override
    public Direction getFrontFacing() {
        Level level = getLevel();
        if (level != null) {
            return getDefinition().getFacing(level.getBlockState(getPos()));
        }
        return Direction.NORTH;
    }

    /** 机器方块带朝向属性时才算「有固定朝向」（否则图案按四个水平方向各试一次）。 */
    @Override
    public boolean hasFrontFacing() {
        Level level = getLevel();
        return level != null && getDefinition().hasFacing();
    }

    /** 本库精简版不支持「上朝向」，恒为 NORTH。 */
    @Override
    public Direction getUpwardsFacing() {
        return Direction.NORTH;
    }

    /** 是否允许结构上下翻转匹配（取 definition，builder 默认 true）。 */
    @Override
    public boolean allowFlip() {
        return getDefinition().isAllowFlip();
    }

    // ═══════════════ 生命周期 ═══════════════

    @Override
    public void onLoad() {
        super.onLoad();
        // 载入后马上安排一次结构检查（避免存档重载后必须等 40 tick）
        requestCheck();
    }

    @Override
    public void onNeighborChanged(Block block, BlockPos fromPos, boolean isMoving) {
        super.onNeighborChanged(block, fromPos, isMoving);
        // 控制器周围的方块变化可能让结构失效/成型：安排一次检查。
        // TODO(ogmr): 仓室自己（远离控制器）被破坏时不会通知到这里 —— 目前的兜底是
        //   serverTick 里每 CHECK_INTERVAL tick 无条件重检一次（见 serverTick）。
        requestCheck();
    }

    /**
     * 服务端每 tick。
     *
     * <p>
     * 每 {@link #CHECK_INTERVAL} tick 做一次结构检查（含「还没成型」时的持续重试），
     * {@link #requestCheck()} 可以把下一次检查提前到下一个 tick。
     */
    @Override
    public void serverTick() {
        super.serverTick();
        if (isRemote()) return;

        if (checkCooldown > 0) {
            checkCooldown--;
            return;
        }
        checkCooldown = CHECK_INTERVAL;
        performStructureCheck();
    }

    // ═══════════════ 结构判定 ═══════════════

    /** 请求一次结构检查（下一个 tick 生效；客户端调用是空操作）。 */
    public void requestCheck() {
        if (isRemote()) return;
        this.checkCooldown = 0;
    }

    /**
     * 结构判定：默认走真正的图案匹配（{@link #matchPattern()}）。
     *
     * <p>
     * 子类可以覆写来做特殊判定（例如「必须有某个条件的多方块才算成型」）。
     */
    public boolean checkPattern() {
        return matchPattern();
    }

    /**
     * 真正的图案匹配：把控制器朝向交给 {@link BlockPattern#checkPatternAt(MultiblockState, boolean)}。
     *
     * <p>
     * 匹配成功时会把「是否需要翻转」记到 {@link #flipped}。
     */
    protected boolean matchPattern() {
        Level level = getLevel();
        if (level == null || level.isClientSide) return false;
        BlockPattern pattern = getPattern();
        if (pattern == null) return false;

        MultiblockState state = getMultiblockState();
        state.clean();
        // 显式把控制器交给 state：BlockPattern 靠它取中心坐标与朝向
        state.setController(this);
        boolean matched = pattern.checkPatternAt(state, false);
        if (matched) {
            this.flipped = state.isNeededFlip();
        }
        return matched;
    }

    /** 本控制器使用的结构图案（来自 definition 的 patternFactory）。 */
    @Nullable
    public BlockPattern getPattern() {
        return getDefinition().getPatternFactory().get();
    }

    /** 跑一次结构检查并驱动成型/失效的生命周期。 */
    protected void performStructureCheck() {
        if (checking) return;
        checking = true;
        try {
            boolean matched = checkPattern();
            if (matched) {
                formed = true;
                onStructureFormed();
            } else if (formed) {
                formed = false;
                onStructureInvalid();
            }
        } finally {
            checking = false;
        }
    }

    /**
     * 结构成型：收集仓室、排序、并通知每个仓室。
     *
     * <p>
     * 覆写时请调用 {@code super.onStructureFormed()}。
     */
    public void onStructureFormed() {
        formed = true;
        // 成型状态写进方块状态：覆盖层里的「成型层」（formed=true 才画）按它选模型
        setFormedState(true);
        parts.clear();
        MultiblockState state = multiblockState;
        if (state != null) {
            collectParts(state);
        }
        parts.sort(getPartSorter());
        for (IMultiPart part : new ArrayList<>(parts)) {
            part.addedToController(this);
        }
    }

    /**
     * 结构失效：通知每个仓室自己被摘掉，然后清空列表。
     *
     * <p>
     * 覆写时请调用 {@code super.onStructureInvalid()}。
     */
    public void onStructureInvalid() {
        formed = false;
        setFormedState(false);
        for (IMultiPart part : new ArrayList<>(parts)) {
            part.removedFromController(this);
        }
        parts.clear();
    }

    /**
     * 从匹配状态里收集仓室。
     *
     * <p>
     * TODO(ogmr): GTM 是从图案的 matchContext（{@code "parts"} 集合，由 ActiveBlock 记录）里取仓室的；
     * 本库的 pattern 包（去 GT 化移植）没有那套记录机制，所以这里改成遍历
     * {@link MultiblockState#getCache()}（本次匹配扫过的所有坐标）反查 BE 上的 {@link IMultiPart}。
     * 如果 pattern 包以后补上「成型时记录仓室」，把这里换成读 matchContext 会更准。
     */
    protected void collectParts(MultiblockState state) {
        Level level = getLevel();
        if (level == null) return;
        for (BlockPos pos : state.getCache()) {
            if (!level.isLoaded(pos)) continue;
            if (level.getBlockEntity(pos) instanceof IMachineBlockEntity holder) {
                MetaMachine machine = holder.getMetaMachine();
                if (machine instanceof IMultiPart part && !parts.contains(part)) {
                    parts.add(part);
                }
            }
        }
    }

    /** 仓室排序器：优先用 definition 里配的，没有则按坐标排序（保证顺序稳定）。 */
    protected Comparator<IMultiPart> getPartSorter() {
        Comparator<IMultiPart> sorter = getDefinition().getPartSorter() == null ? null :
                getDefinition().getPartSorter().apply(this);
        if (sorter != null) return sorter;
        // 默认：按 (y, x, z) 排序 —— 与 GTM 的默认顺序一致，避免结构每次重检后仓室顺序抖动
        return Comparator.<IMultiPart>comparingInt(part -> part.getPos().getY())
                .thenComparingInt(part -> part.getPos().getX())
                .thenComparingInt(part -> part.getPos().getZ());
    }

    // ═══════════════ 展示 ═══════════════

    @Override
    public void addDisplayText(List<Component> textList) {
        super.addDisplayText(textList);
        if (!formed) return;
        // definition 里配的「额外文本」（一般是仓室汇总信息）
        getDefinition().getAdditionalDisplay().accept(this, textList);
    }
}
