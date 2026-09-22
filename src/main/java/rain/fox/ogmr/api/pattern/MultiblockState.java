package rain.fox.ogmr.api.pattern;

import rain.fox.ogmr.api.machine.multiblock.IMultiController;
import rain.fox.ogmr.api.pattern.error.PatternError;
import rain.fox.ogmr.api.pattern.error.PatternStringError;
import rain.fox.ogmr.api.pattern.predicates.SimplePredicate;
import rain.fox.ogmr.api.pattern.util.IO;
import rain.fox.ogmr.api.pattern.util.PatternMatchContext;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import lombok.Getter;
import lombok.Setter;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.objects.Reference2IntOpenHashMap;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.stream.Collectors;

/**
 * 结构匹配的「游标状态」：记录当前在检查哪个位置、命中/失败了什么、以及各方块/各 predicate 的计数。
 *
 * <p>
 * 从 GTM 的 {@code com.gregtechceu.gtceu.api.pattern.MultiblockState} 拆出，保留 {@link BlockPattern}
 * 与各 predicate 真正用到的那部分：位置/方块状态/方块实体缓存、{@link PatternMatchContext}、
 * 全局与单层计数、错误对象、IO、翻转标记、位置缓存。
 *
 * <p>
 * 与 GTM 版本的差异（去 GT 化，都是「删依赖」而不是改语义）：
 * <ul>
 * <li>{@link #getController()} 不再要求方块实体是 GT 的 {@code IMachineBlockEntity}：
 * 优先返回外部显式塞进来的控制器（{@link #setController(IMultiController)}），
 * 否则走 {@link #setControllerLookup(ControllerLookup)} 注册的取出函数
 * （由机器方块实体侧注册一次，把 {@code BlockEntity} 映射成 {@code IMultiController}）；</li>
 * <li>删除 {@code onBlockStateChanged(...)}：它依赖 GT 的 {@code ActiveBlock}、{@code IMultiController}
 * 的生命周期方法（{@code checkPatternWithLock/requestCheck/onStructureFormed/onStructureInvalid}）与
 * {@code MultiblockWorldSavedData} 的映射表。本库只负责「图案匹配」，结构失效/重检由使用方的
 * 控制器自己监听方块变化后调用 {@code checkPatternAt} 完成；</li>
 * <li>{@code GTCEu.LOGGER} 之类的日志与 {@code MultiblockWorldSavedData} 联动一并去掉。</li>
 * </ul>
 */
public class MultiblockState {

    /** 控制器所在区块没加载。 */
    public final static PatternError UNLOAD_ERROR = new PatternStringError("multiblocked.pattern.error.chunk");
    /** 还没开始检查。 */
    public final static PatternError UNINIT_ERROR = new PatternStringError("multiblocked.pattern.error.init");

    /** 从方块实体取出多方块控制器。由本库使用方（机器方块实体）注册一次。 */
    @FunctionalInterface
    public interface ControllerLookup {

        @Nullable
        IMultiController get(BlockEntity blockEntity);
    }

    private static volatile ControllerLookup controllerLookup;

    /** 注册「方块实体 → 控制器」的取出函数；传 null 可取消注册。 */
    public static void setControllerLookup(@Nullable ControllerLookup lookup) {
        controllerLookup = lookup;
    }

    @Nullable
    public static ControllerLookup getControllerLookup() {
        return controllerLookup;
    }

    private BlockPos pos;
    private BlockState blockState;
    private BlockEntity tileEntity;
    private boolean tileEntityInitialized;
    @Getter
    private final PatternMatchContext matchContext;
    @Getter
    private Reference2IntOpenHashMap<SimplePredicate> globalCount;
    @Getter
    private Reference2IntOpenHashMap<SimplePredicate> layerCount;
    public TraceabilityPredicate predicate;
    public IO io;
    public PatternError error;
    /** 结构是否处于翻转状态（匹配成功时由 {@link BlockPattern} 写入）。 */
    @Getter
    @Setter
    private boolean neededFlip = false;
    @Getter
    public final Level world;
    public final BlockPos controllerPos;
    /** 显式提供的控制器（优先于 {@link #controllerLookup}）。 */
    @Nullable
    private IMultiController controller;
    public IMultiController lastController;

    // persist
    public LongOpenHashSet cache;
    /** 方块状态缓存，避免同一次检查中重复 world.getBlockState() 调用 */
    public Long2ObjectOpenHashMap<BlockState> blockStateCache;
    /** 共享方块缓存，记录本次检查涉及的各方块位置 */
    public LongOpenHashSet sharedCache;

    public MultiblockState(Level world, BlockPos controllerPos) {
        this.world = world;
        this.controllerPos = controllerPos;
        this.error = UNINIT_ERROR;
        this.matchContext = new PatternMatchContext();
        // GT 里这些容器只在 clean()/clearCache() 里创建，提前到构造器初始化一次，
        // 免得「没调 clean() 就直接读 getBlockState()」时 NPE（行为上只多不少）。
        this.globalCount = new Reference2IntOpenHashMap<>();
        this.layerCount = new Reference2IntOpenHashMap<>();
        this.cache = new LongOpenHashSet();
        this.sharedCache = new LongOpenHashSet();
        this.blockStateCache = new Long2ObjectOpenHashMap<>();
    }

    /** 每次重新匹配结构前调用：清空上下文与全部计数/缓存。 */
    public void clean() {
        this.matchContext.reset();
        this.globalCount = new Reference2IntOpenHashMap<>();
        this.layerCount = new Reference2IntOpenHashMap<>();
        cache = new LongOpenHashSet();
        sharedCache = new LongOpenHashSet();
        blockStateCache = new Long2ObjectOpenHashMap<>();
    }

    /** 只清计数与位置缓存，保留 matchContext（结构成型后仍要读上下文时用）。 */
    public void clearCache() {
        this.globalCount = new Reference2IntOpenHashMap<>();
        this.layerCount = new Reference2IntOpenHashMap<>();
        this.blockStateCache = new Long2ObjectOpenHashMap<>();
        this.predicate = null;
        this.blockState = null;
        this.tileEntity = null;
        this.tileEntityInitialized = false;
    }

    /** 把游标移到某个位置，并记下该位置要用的 predicate。 */
    public void update(BlockPos posIn, TraceabilityPredicate predicate) {
        this.pos = posIn;
        this.blockState = null;
        this.tileEntity = null;
        this.tileEntityInitialized = false;
        this.predicate = predicate;
        this.error = null;
        if (!world.isLoaded(posIn)) {
            error = UNLOAD_ERROR;
        }
    }

    /** 取出控制器；区块没加载时记上 {@link #UNLOAD_ERROR} 并返回 null。 */
    @Nullable
    public IMultiController getController() {
        if (controller != null) {
            return lastController = controller;
        }
        if (world.isLoaded(controllerPos)) {
            ControllerLookup lookup = controllerLookup;
            if (lookup != null) {
                IMultiController found = lookup.get(world.getBlockEntity(controllerPos));
                if (found != null) {
                    return lastController = found;
                }
            }
        } else {
            error = UNLOAD_ERROR;
        }
        return null;
    }

    /** 显式指定控制器（不依赖 {@link ControllerLookup} 注册）。 */
    public void setController(@Nullable IMultiController controller) {
        this.controller = controller;
    }

    public boolean hasError() {
        return error != null;
    }

    public void setError(PatternError error) {
        this.error = error;
        if (error != null) {
            error.setWorldState(this);
        }
    }

    public BlockState getBlockState() {
        if (this.blockState == null) {
            this.blockState = blockStateCache.computeIfAbsent(pos.asLong(), k -> this.world.getBlockState(pos));
        }
        return this.blockState;
    }

    @Nullable
    public BlockEntity getTileEntity() {
        if (!getBlockState().hasBlockEntity()) {
            return null;
        }
        if (this.tileEntity == null && !this.tileEntityInitialized) {
            this.tileEntity = this.world.getBlockEntity(this.pos);
            this.tileEntityInitialized = true;
        }

        return this.tileEntity;
    }

    public BlockPos getPos() {
        return this.pos.immutable();
    }

    public BlockState getOffsetState(Direction face) {
        if (pos instanceof BlockPos.MutableBlockPos) {
            ((BlockPos.MutableBlockPos) pos).move(face);
            BlockState blockState = world.getBlockState(pos);
            ((BlockPos.MutableBlockPos) pos).move(face.getOpposite());
            return blockState;
        }
        return world.getBlockState(this.pos.relative(face));
    }

    public void addPosCache(BlockPos pos) {
        cache.add(pos.asLong());
    }

    public boolean isPosInCache(BlockPos pos) {
        return cache.contains(pos.asLong());
    }

    public Collection<BlockPos> getCache() {
        return cache.longStream().mapToObj(BlockPos::of).collect(Collectors.toSet());
    }
}
