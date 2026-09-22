package rain.fox.ogmr.api.pattern;

import rain.fox.ogmr.api.machine.multiblock.IMultiController;
import rain.fox.ogmr.api.pattern.error.PatternError;
import rain.fox.ogmr.api.pattern.error.PatternStringError;
import rain.fox.ogmr.api.pattern.error.SinglePredicateError;
import rain.fox.ogmr.api.pattern.predicates.SimplePredicate;
import rain.fox.ogmr.api.pattern.util.IPatternFacingProvider;
import rain.fox.ogmr.api.pattern.util.PatternMatchContext;
import rain.fox.ogmr.api.pattern.util.RelativeDirection;

import com.lowdragmc.lowdraglib.utils.BlockInfo;

import lombok.Getter;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.items.IItemHandler;

import it.unimi.dsi.fastutil.ints.IntObjectPair;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.Reference2IntOpenHashMap;
import org.apache.commons.lang3.ArrayUtils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiPredicate;
import java.util.function.Consumer;

/**
 * 编译好的多方块结构图案：{@code [z][y][x]} 的 {@link TraceabilityPredicate} 三维数组 + 重复轴信息。
 *
 * <p>
 * 从 GTM 的 {@code com.gregtechceu.gtceu.api.pattern.BlockPattern} 拆出，保留：
 * <ul>
 * <li>{@link #aisleRepetitions} 重复轴（{@code [minRepeat, maxRepeat]}）与 {@link #formedRepetitionCount}
 * 实际成型时每条 aisle 用了几层；</li>
 * <li>{@link #checkPatternAt(MultiblockState, boolean)} / 带 centerPos+朝向的完整重载：
 * aisle 回退（retreat）搜索、单层计数校验、全局 minCount 校验、错误写回、{@code savePredicate} 时把
 * 位置→predicate 存进 matchContext（键 {@code "predicates"}）与 {@code "ioMap"}；</li>
 * <li>{@link #getPreview(int[])}：按重复次数生成结构预览（JEI/EMI 分页用），
 * {@link #getMatchingShapes()} 把重复轴全展开成形状列表；</li>
 * <li>{@link #autoBuild(Player, MultiblockState)}：玩家手持材料时自动搭结构（含从背包/容器里取料、
 * 放下后自动调朝向）。</li>
 * </ul>
 *
 * <p>
 * 与 GTM 版本的差异（去 GT 化；都是「删掉依赖 GT 的部分」，其余逻辑逐行保留）：
 * <ul>
 * <li>朝向信息不再从 {@code controller.self().getFrontFacing()/getUpwardsFacing()/isFlipped()/allowFlip()}
 * 读取（本库的 {@code MetaMachine} 没有这些方法），改走
 * {@link IPatternFacingProvider}：控制器实现了该接口就按它的朝向旋转，没实现则按「无固定朝向」处理
 * （四个水平方向各试一次、不翻转）。需要精确控制时直接调用带 centerPos+朝向的重载；</li>
 * <li>删除「共享仓室」检测（GT 的 {@code IMachineBlockEntity} + {@code IMultiPart#isFormed/canShared/hasController}）
 * 与 {@code ActiveBlock} 的 {@code "vaBlocks"} 记录 —— 两者都依赖 GT 的机器/方块体系。
 * 需要防多方块重叠时，请在使用方控制器里自行基于 {@link MultiblockState#sharedCache} 判断；</li>
 * <li>删除 {@code getTileEntity() instanceof IMachineBlockEntity} 分支（自动搭建与预览里的
 * 「机器方块实体 → MetaMachine」转换）；{@link #autoBuild} 结束时只对放下的 {@link BlockState} 调朝向，
 * 不再调用 GT 的 {@code MetaMachine#isFacingValid}；</li>
 * <li>{@code GTUtil.isSameItemSameTags} → 原版 {@code ItemStack.isSameItemSameTags}；</li>
 * <li>新增 {@link #getMatchingShapes()}：GTM 把重复轴展开（repetitionDFS）放在
 * {@code MultiblockMachineDefinition#getMatchingShapes()} 里，本库把它下放到 BlockPattern，
 * 算法与 GTM 完全一致，方便预览代码直接取「全部子图案」。</li>
 * </ul>
 */
public class BlockPattern {

    static Direction[] FACINGS = { Direction.SOUTH, Direction.NORTH, Direction.WEST, Direction.EAST, Direction.UP,
            Direction.DOWN };
    static Direction[] FACINGS_H = { Direction.SOUTH, Direction.NORTH, Direction.WEST, Direction.EAST };

    /** 每条 aisle（z 轴层）的重复次数范围：{@code [minRepeat, maxRepeat]}。 */
    public final int[][] aisleRepetitions;
    public final RelativeDirection[] structureDir;
    protected final TraceabilityPredicate[][][] blockMatches; // [z][y][x]
    protected final int fingerLength; // z size
    protected final int thumbLength; // y size
    protected final int palmLength; // x size
    protected final int[] centerOffset; // x, y, z, minZ, maxZ
    /** 最近一次成型时，每条 aisle 实际用了几层。 */
    @Getter
    protected int[] formedRepetitionCount;

    public BlockPattern(TraceabilityPredicate[][][] predicatesIn, RelativeDirection[] structureDir,
                        int[][] aisleRepetitions, int[] centerOffset) {
        this.blockMatches = predicatesIn;
        this.fingerLength = predicatesIn.length;
        this.structureDir = structureDir;
        this.aisleRepetitions = aisleRepetitions;
        this.formedRepetitionCount = new int[aisleRepetitions.length];

        if (this.fingerLength > 0) {
            this.thumbLength = predicatesIn[0].length;

            if (this.thumbLength > 0) {
                this.palmLength = predicatesIn[0][0].length;
            } else {
                this.palmLength = 0;
            }
        } else {
            this.thumbLength = 0;
            this.palmLength = 0;
        }

        this.centerOffset = centerOffset;
    }

    /**
     * 按控制器自身的朝向检查结构。
     *
     * <p>
     * 朝向来源见 {@link IPatternFacingProvider}：控制器实现了它就旋转/翻转匹配，
     * 否则四个水平方向各试一次。
     */
    public boolean checkPatternAt(MultiblockState worldState, boolean savePredicate) {
        IMultiController controller = worldState.getController();
        if (controller == null) {
            worldState.setError(new PatternStringError("no controller found"));
            return false;
        }
        BlockPos centerPos = controller.self().getPos();
        Direction frontFacing = Direction.SOUTH;
        Direction upwardsFacing = Direction.NORTH;
        boolean hasFrontFacing = false;
        boolean allowsFlip = false;
        if (controller instanceof IPatternFacingProvider provider) {
            frontFacing = provider.getFrontFacing();
            upwardsFacing = provider.getUpwardsFacing();
            hasFrontFacing = provider.hasFrontFacing();
            allowsFlip = provider.allowFlip();
        }
        Direction[] facings = hasFrontFacing ? new Direction[] { frontFacing } :
                new Direction[] { Direction.SOUTH, Direction.NORTH, Direction.EAST, Direction.WEST };
        for (Direction direction : facings) {
            boolean result = checkPatternAt(worldState, centerPos, direction, upwardsFacing, false, savePredicate);
            if (result) {
                return true;
            } else if (allowsFlip) {
                return checkPatternAt(worldState, centerPos, direction, upwardsFacing, true, savePredicate);
            }
        }
        return false;
    }

    /** 结构尺寸 {@code [z, y, x]}。 */
    @Deprecated(forRemoval = true, since = "7.0")
    public int[] getDimensions() {
        return new int[] { fingerLength, thumbLength, palmLength };
    }

    /**
     * 在指定中心位置与朝向下检查结构（GTM 的完整版本，行为逐行对齐）。
     *
     * @param worldState    匹配游标状态
     * @param centerPos     控制器所在位置
     * @param frontFacing   结构正面朝向
     * @param upwardsFacing 结构「上」朝向
     * @param isFlipped     是否上下翻转
     * @param savePredicate 是否把「位置 → predicate」记录进 matchContext（成型后要读时用 true）
     */
    public boolean checkPatternAt(MultiblockState worldState, BlockPos centerPos, Direction frontFacing,
                                  Direction upwardsFacing, boolean isFlipped, boolean savePredicate) {
        boolean findFirstAisle = false;
        int minZ = -centerOffset[4];
        worldState.clean();
        PatternMatchContext matchContext = worldState.getMatchContext();
        var globalCount = worldState.getGlobalCount();
        var layerCount = worldState.getLayerCount();
        int ordinal = frontFacing.ordinal();
        // Checking aisles
        for (int c = 0, z = minZ++, r; c < this.fingerLength; c++) {
            // Checking repeatable slices
            int validRepetitions = 0;
            loop:
            for (r = 0; (findFirstAisle ? r < aisleRepetitions[c][1] : z <= -centerOffset[3]); r++) {
                // Checking single slice
                layerCount.clear();

                for (int b = 0, y = -centerOffset[1]; b < this.thumbLength; b++, y++) {
                    for (int a = 0, x = -centerOffset[0]; a < this.palmLength; a++, x++) {
                        worldState.setError(null);
                        var bc = this.blockMatches[c];
                        if (bc == null) continue;
                        var bb = bc[b];
                        if (bb == null) continue;
                        TraceabilityPredicate predicate = bb[a];
                        if (predicate == null) continue;
                        BlockPos pos = setActualRelativeOffset(x, y, z, frontFacing, ordinal, upwardsFacing, isFlipped)
                                .offset(centerPos.getX(), centerPos.getY(), centerPos.getZ());
                        worldState.update(pos, predicate);
                        if (worldState.hasError() && worldState.error == MultiblockState.UNLOAD_ERROR) {
                            return false;
                        }
                        if (predicate.addCache()) {
                            worldState.addPosCache(pos);
                            if (savePredicate) {
                                matchContext.getOrCreate("predicates", HashMap::new).put(pos, predicate);
                            }
                        }
                        if (!predicate.test(worldState)) { // matching failed
                            if (findFirstAisle) {
                                if (r < aisleRepetitions[c][0]) {// retreat to see if the first aisle can start later
                                    r = c = 0;
                                    z = minZ++;
                                    matchContext.reset();
                                    findFirstAisle = false;
                                }
                            } else {
                                z++;// continue searching for the first aisle
                            }
                            continue loop;
                        }
                        matchContext.getOrCreate("ioMap", Long2ObjectOpenHashMap::new).put(worldState.getPos().asLong(),
                                worldState.io);
                    }
                }
                findFirstAisle = true;
                z++;

                // Check layer-local matcher predicate
                for (var it = layerCount.reference2IntEntrySet().fastIterator(); it.hasNext();) {
                    var entry = it.next();
                    if (entry.getIntValue() < entry.getKey().minLayerCount) {
                        worldState.setError(new SinglePredicateError(entry.getKey(), 3));
                        return false;
                    }
                }
                validRepetitions++;
            }
            // Repetitions out of range
            if (r < aisleRepetitions[c][0] || worldState.hasError() || !findFirstAisle) {
                if (!worldState.hasError()) {
                    worldState.setError(new PatternError());
                }
                return false;
            }

            // finished checking the aisle, so store the repetitions
            formedRepetitionCount[c] = validRepetitions;
        }

        // Check count matches amount
        for (var it = globalCount.reference2IntEntrySet().fastIterator(); it.hasNext();) {
            var entry = it.next();
            if (entry.getIntValue() < entry.getKey().minCount) {
                worldState.setError(new SinglePredicateError(entry.getKey(), 1));
                return false;
            }
        }

        worldState.setError(null);
        worldState.setNeededFlip(isFlipped);
        return true;
    }

    /**
     * 自动搭建：从玩家背包（或背包里容器的物品栏）取料，把结构一层层放出来，最后统一调整方块朝向。
     *
     * <p>朝向取自控制器的 {@link IPatternFacingProvider}，没实现则按 {@code SOUTH / NORTH / 不翻转}。
     */
    public void autoBuild(Player player, MultiblockState worldState) {
        IMultiController controller = worldState.getController();
        if (controller == null) return;
        Direction frontFacing = Direction.SOUTH;
        Direction upwardsFacing = Direction.NORTH;
        boolean isFlipped = false;
        if (controller instanceof IPatternFacingProvider provider) {
            frontFacing = provider.getFrontFacing();
            upwardsFacing = provider.getUpwardsFacing();
            isFlipped = provider.isFlipped();
        }
        autoBuild(player, worldState, frontFacing, upwardsFacing, isFlipped);
    }

    /**
     * 自动搭建（显式指定朝向版本）。
     *
     * @param player        操作的玩家（创造模式不消耗物品）
     * @param worldState    匹配游标状态
     * @param facing        结构正面朝向
     * @param upwardsFacing 结构「上」朝向
     * @param isFlipped     是否按翻转状态摆放
     */
    public void autoBuild(Player player, MultiblockState worldState, Direction facing, Direction upwardsFacing,
                          boolean isFlipped) {
        Level world = player.level();
        int minZ = -centerOffset[4];
        worldState.clean();
        IMultiController controller = worldState.getController();
        if (controller == null) return;
        BlockPos centerPos = controller.self().getPos();
        var cacheGlobal = worldState.getGlobalCount();
        var cacheLayer = worldState.getLayerCount();
        Map<BlockPos, Object> blocks = new HashMap<>();
        Set<BlockPos> placeBlockPos = new HashSet<>();
        blocks.put(centerPos, controller);
        for (int c = 0, z = minZ++, r; c < this.fingerLength; c++) {
            for (r = 0; r < aisleRepetitions[c][0]; r++) {
                cacheLayer.clear();
                for (int b = 0, y = -centerOffset[1]; b < this.thumbLength; b++, y++) {
                    for (int a = 0, x = -centerOffset[0]; a < this.palmLength; a++, x++) {
                        TraceabilityPredicate predicate = this.blockMatches[c][b][a];
                        BlockPos pos = setActualRelativeOffset(x, y, z, facing, upwardsFacing, isFlipped)
                                .offset(centerPos.getX(), centerPos.getY(), centerPos.getZ());
                        worldState.update(pos, predicate);
                        if (!world.isEmptyBlock(pos)) {
                            blocks.put(pos, world.getBlockState(pos));
                            for (SimplePredicate limit : predicate.limited) {
                                limit.testLimited(worldState);
                            }
                        } else {
                            boolean find = false;
                            BlockInfo[] infos = new BlockInfo[0];
                            for (SimplePredicate limit : predicate.limited) {
                                if (limit.minLayerCount > 0) {
                                    int curr = cacheLayer.getInt(limit);
                                    if (curr < limit.minLayerCount &&
                                            (limit.maxLayerCount == -1 || curr < limit.maxLayerCount)) {
                                        cacheLayer.addTo(limit, 1);
                                    } else {
                                        continue;
                                    }
                                } else {
                                    continue;
                                }
                                infos = limit.candidates == null ? null : limit.candidates.get();
                                find = true;
                                break;
                            }
                            if (!find) {
                                for (SimplePredicate limit : predicate.limited) {
                                    if (limit.minCount > 0) {
                                        int curr = cacheGlobal.getInt(limit);
                                        if (curr < limit.minCount && (limit.maxCount == -1 || curr < limit.maxCount)) {
                                            cacheGlobal.addTo(limit, 1);
                                        } else {
                                            continue;
                                        }
                                    } else {
                                        continue;
                                    }
                                    infos = limit.candidates == null ? null : limit.candidates.get();
                                    find = true;
                                    break;
                                }
                            }
                            if (!find) { // no limited
                                for (SimplePredicate limit : predicate.limited) {
                                    if (limit.maxLayerCount != -1 &&
                                            cacheLayer.getOrDefault(limit, Integer.MAX_VALUE) == limit.maxLayerCount) {
                                        continue;
                                    }
                                    if (limit.maxCount != -1 &&
                                            cacheGlobal.getOrDefault(limit, Integer.MAX_VALUE) == limit.maxCount) {
                                        continue;
                                    }
                                    cacheLayer.addTo(limit, 1);
                                    cacheGlobal.addTo(limit, 1);
                                    infos = ArrayUtils.addAll(infos,
                                            limit.candidates == null ? null : limit.candidates.get());
                                }
                                for (SimplePredicate common : predicate.common) {
                                    infos = ArrayUtils.addAll(infos,
                                            common.candidates == null ? null : common.candidates.get());
                                }
                            }

                            List<ItemStack> candidates = new ArrayList<>();
                            if (infos != null) {
                                for (BlockInfo info : infos) {
                                    if (info.getBlockState().getBlock() != Blocks.AIR) {
                                        candidates.add(info.getItemStackForm());
                                    }
                                }
                            }

                            // check inventory
                            ItemStack found = null;
                            int foundSlot = -1;
                            IItemHandler handler = null;
                            if (!player.isCreative()) {
                                var foundHandler = getMatchStackWithHandler(candidates,
                                        player.getCapability(ForgeCapabilities.ITEM_HANDLER));
                                if (foundHandler != null) {
                                    foundSlot = foundHandler.firstInt();
                                    handler = foundHandler.second();
                                    found = handler.getStackInSlot(foundSlot).copy();
                                }
                            } else {
                                for (ItemStack candidate : candidates) {
                                    found = candidate.copy();
                                    if (!found.isEmpty() && found.getItem() instanceof BlockItem) {
                                        break;
                                    }
                                    found = null;
                                }
                            }
                            if (found == null) continue;
                            BlockItem itemBlock = (BlockItem) found.getItem();
                            BlockPlaceContext context = new BlockPlaceContext(world, player, InteractionHand.MAIN_HAND,
                                    found, BlockHitResult.miss(player.getEyePosition(0), Direction.UP, pos));
                            InteractionResult interactionResult = itemBlock.place(context);
                            if (interactionResult != InteractionResult.FAIL) {
                                placeBlockPos.add(pos);
                                if (handler != null) {
                                    handler.extractItem(foundSlot, 1, false);
                                }
                            }
                            blocks.put(pos, world.getBlockState(pos));
                        }
                    }
                }
                z++;
            }
        }
        // 调整朝向：只处理刚刚放下的方块（GT 里还会对 MetaMachine 调 isFacingValid，本库没有该 API）
        blocks.forEach((pos, block) -> {
            if (!(block instanceof IMultiController)) {
                if (block instanceof BlockState state && placeBlockPos.contains(pos)) {
                    resetFacing(pos, state, facing, (p, f) -> {
                        Object object = blocks.get(p.relative(f));
                        return object == null ||
                                (object instanceof BlockState other && other.getBlock() == Blocks.AIR);
                    }, newState -> world.setBlock(pos, newState, 3));
                }
            }
        });
    }

    /**
     * 按每条 aisle 的重复次数生成结构预览 {@code [z][y][x]}（JEI/EMI 分页用）。
     *
     * @param repetition 长度 = {@link #aisleRepetitions}.length，每层重复几次
     */
    public BlockInfo[][][] getPreview(int[] repetition) {
        var cacheGlobal = new Reference2IntOpenHashMap<SimplePredicate>();
        Map<BlockPos, BlockInfo> blocks = new HashMap<>();
        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxY = Integer.MIN_VALUE;
        int maxZ = Integer.MIN_VALUE;
        for (int l = 0, x = 0; l < this.fingerLength; l++) {
            for (int r = 0; r < repetition[l]; r++) {
                // Checking single slice
                var cacheLayer = new Reference2IntOpenHashMap<SimplePredicate>();
                for (int y = 0; y < this.thumbLength; y++) {
                    for (int z = 0; z < this.palmLength; z++) {
                        TraceabilityPredicate predicate = this.blockMatches[l][y][z];
                        boolean find = false;
                        BlockInfo[] infos = null;
                        for (SimplePredicate limit : predicate.limited) { // check layer and previewCount
                            if (limit.minLayerCount > 0) {
                                if (cacheLayer.getInt(limit) < limit.minLayerCount) {
                                    cacheLayer.addTo(limit, 1);
                                } else {
                                    continue;
                                }
                                if (cacheGlobal.getInt(limit) < limit.previewCount) {
                                    cacheGlobal.addTo(limit, 1);
                                } else {
                                    continue;
                                }
                            } else {
                                continue;
                            }
                            infos = limit.candidates == null ? null : limit.candidates.get();
                            find = true;
                            break;
                        }
                        if (!find) { // check global and previewCount
                            for (SimplePredicate limit : predicate.limited) {
                                if (limit.minCount == -1 && limit.previewCount == -1) continue;
                                if (cacheGlobal.getInt(limit) < limit.previewCount) {
                                    cacheGlobal.addTo(limit, 1);
                                } else if (limit.minCount > 0) {
                                    if (cacheGlobal.getInt(limit) < limit.minCount) {
                                        cacheGlobal.addTo(limit, 1);
                                    } else {
                                        continue;
                                    }
                                } else {
                                    continue;
                                }
                                infos = limit.candidates == null ? null : limit.candidates.get();
                                find = true;
                                break;
                            }
                        }
                        if (!find) { // check common with previewCount
                            for (SimplePredicate common : predicate.common) {
                                if (common.previewCount > 0) {
                                    if (cacheGlobal.getInt(common) < common.previewCount) {
                                        cacheGlobal.addTo(common, 1);
                                    } else {
                                        continue;
                                    }
                                } else {
                                    continue;
                                }
                                infos = common.candidates == null ? null : common.candidates.get();
                                find = true;
                                break;
                            }
                        }
                        if (!find) { // check without previewCount
                            for (SimplePredicate common : predicate.common) {
                                if (common.previewCount == -1) {
                                    infos = common.candidates == null ? null : common.candidates.get();
                                    find = true;
                                    break;
                                }
                            }
                        }
                        if (!find) { // check max
                            for (SimplePredicate limit : predicate.limited) {
                                if (limit.previewCount != -1) continue;
                                if (limit.maxCount != -1 || limit.maxLayerCount != -1) {
                                    if (cacheGlobal.getOrDefault(limit, 0) < limit.maxCount) {
                                        cacheGlobal.addTo(limit, 1);
                                    } else if (cacheLayer.getOrDefault(limit, 0) < limit.maxLayerCount) {
                                        cacheLayer.addTo(limit, 1);
                                    } else {
                                        continue;
                                    }
                                }

                                infos = limit.candidates == null ? null : limit.candidates.get();
                                break;
                            }
                        }
                        BlockInfo info = infos == null || infos.length == 0 ? BlockInfo.EMPTY : infos[0];
                        BlockPos pos = setActualRelativeOffset(z, y, x, Direction.NORTH, Direction.UP, false);

                        blocks.put(pos, info);
                        minX = Math.min(pos.getX(), minX);
                        minY = Math.min(pos.getY(), minY);
                        minZ = Math.min(pos.getZ(), minZ);
                        maxX = Math.max(pos.getX(), maxX);
                        maxY = Math.max(pos.getY(), maxY);
                        maxZ = Math.max(pos.getZ(), maxZ);
                    }
                }
                x++;
            }
        }
        BlockInfo[][][] result = (BlockInfo[][][]) Array.newInstance(BlockInfo.class, maxX - minX + 1, maxY - minY + 1,
                maxZ - minZ + 1);
        int finalMinX = minX;
        int finalMinY = minY;
        int finalMinZ = minZ;
        blocks.forEach((pos, info) -> {
            // 朝向自动调整：只按「朝向的那一侧是空的」来判断（GT 还会询问机器的 isFacingValid，本库无此 API）
            resetFacing(pos, info.getBlockState(), null, (p, f) -> {
                BlockInfo blockInfo = blocks.get(p.relative(f));
                return blockInfo == null || blockInfo.getBlockState().getBlock() == Blocks.AIR;
            }, info::setBlockState);
            result[pos.getX() - finalMinX][pos.getY() - finalMinY][pos.getZ() - finalMinZ] = info;
        });
        return result;
    }

    /**
     * 把每条 aisle 的重复次数全部展开，生成「所有可能的子图案」形状列表。
     *
     * <p>
     * 与 GTM 的 {@code MultiblockMachineDefinition#getMatchingShapes()} 算法一致（repetitionDFS）：
     * 每条重复轴从 minRepeat 枚举到 maxRepeat，组合出全部 {@link MultiblockShapeInfo}，
     * 供 JEI/EMI 的多页结构预览使用。
     *
     * <p>
     * 说明：GTM 7.5.3 的 pattern 包里<b>没有</b> {@code getSubPatternFromBlockInfo} 这个方法
     * （已在整个 gtceu 源码树里确认），「按重复轴切子图案」这件事在 GTM 里就是这里的 repetitionDFS +
     * {@link #getPreview(int[])}，所以本库把它放在这里提供。
     */
    public List<MultiblockShapeInfo> getMatchingShapes() {
        return repetitionDFS(this, new ArrayList<>(), this.aisleRepetitions, new IntArrayList());
    }

    private static List<MultiblockShapeInfo> repetitionDFS(BlockPattern pattern, List<MultiblockShapeInfo> pages,
                                                           int[][] aisleRepetitions, IntArrayList repetitionStack) {
        if (repetitionStack.size() == aisleRepetitions.length) {
            int[] repetition = new int[repetitionStack.size()];
            for (int i = 0; i < repetitionStack.size(); i++) {
                repetition[i] = repetitionStack.getInt(i);
            }
            pages.add(new MultiblockShapeInfo(pattern.getPreview(repetition)));
        } else {
            for (int i = aisleRepetitions[repetitionStack.size()][0]; i <=
                    aisleRepetitions[repetitionStack.size()][1]; i++) {
                repetitionStack.push(i);
                repetitionDFS(pattern, pages, aisleRepetitions, repetitionStack);
                repetitionStack.popInt();
            }
        }
        return pages;
    }

    private void resetFacing(BlockPos pos, BlockState blockState, Direction facing,
                             BiPredicate<BlockPos, Direction> checker, Consumer<BlockState> consumer) {
        if (blockState.hasProperty(BlockStateProperties.FACING)) {
            tryFacings(blockState, pos, checker, consumer, BlockStateProperties.FACING,
                    facing == null ? FACINGS : ArrayUtils.addAll(new Direction[] { facing }, FACINGS));
        } else if (blockState.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) {
            tryFacings(blockState, pos, checker, consumer, BlockStateProperties.HORIZONTAL_FACING,
                    facing == null || facing.getAxis() == Direction.Axis.Y ? FACINGS_H :
                            ArrayUtils.addAll(new Direction[] { facing }, FACINGS_H));
        }
    }

    private void tryFacings(BlockState blockState, BlockPos pos, BiPredicate<BlockPos, Direction> checker,
                            Consumer<BlockState> consumer, Property<Direction> property, Direction[] facings) {
        Direction found = null;
        for (Direction facing : facings) {
            if (checker.test(pos, facing)) {
                found = facing;
                break;
            }
        }
        if (found == null) {
            found = Direction.NORTH;
        }
        consumer.accept(blockState.setValue(property, found));
    }

    // ordinal 版本的偏移计算，用 int 比较替代 Direction 枚举比较以加速
    private BlockPos setActualRelativeOffset(int x, int y, int z, Direction facing, int ordinal, Direction upwardsFacing,
                                             boolean isFlipped) {
        int[] c0 = new int[] { x, y, z }, c1 = new int[3];
        boolean down = ordinal == 0;
        if (down || ordinal == 1) {
            int of = down ? upwardsFacing.ordinal() : upwardsFacing.getOpposite().ordinal();
            for (int i = 0; i < 3; i++) {
                switch (structureDir[i].getActualOrdinal(of)) {
                    case 1 -> c1[1] = c0[i];
                    case 0 -> c1[1] = -c0[i];
                    case 4 -> c1[0] = -c0[i];
                    case 5 -> c1[0] = c0[i];
                    case 2 -> c1[2] = -c0[i];
                    case 3 -> c1[2] = c0[i];
                }
            }
            int xOffset = upwardsFacing.getStepX();
            int tmp;
            if (xOffset == 0) {
                tmp = c1[2];
                int zOffset = upwardsFacing.getStepZ();
                c1[2] = zOffset > 0 ? c1[1] : -c1[1];
                c1[1] = zOffset > 0 ? -tmp : tmp;
            } else {
                tmp = c1[0];
                c1[0] = xOffset > 0 ? c1[1] : -c1[1];
                c1[1] = xOffset > 0 ? -tmp : tmp;
            }
            if (isFlipped) {
                if (upwardsFacing == Direction.NORTH || upwardsFacing == Direction.SOUTH) {
                    c1[0] = -c1[0];
                } else {
                    c1[2] = -c1[2];
                }
            }
        } else {
            for (int i = 0; i < 3; i++) {
                switch (structureDir[i].getActualOrdinal(ordinal)) {
                    case 1 -> c1[1] = c0[i];
                    case 0 -> c1[1] = -c0[i];
                    case 4 -> c1[0] = -c0[i];
                    case 5 -> c1[0] = c0[i];
                    case 2 -> c1[2] = -c0[i];
                    case 3 -> c1[2] = c0[i];
                }
            }
            boolean east = upwardsFacing == Direction.EAST;
            if (east || upwardsFacing == Direction.WEST) {
                int xOffset = east ? facing.getClockWise().getStepX() : facing.getClockWise().getOpposite().getStepX();
                int tmp;
                if (xOffset == 0) {
                    tmp = c1[2];
                    int zOffset = east ? facing.getClockWise().getStepZ() : facing.getClockWise().getOpposite().getStepZ();
                    c1[2] = zOffset > 0 ? -c1[1] : c1[1];
                    c1[1] = zOffset > 0 ? tmp : -tmp;
                } else {
                    tmp = c1[0];
                    c1[0] = xOffset > 0 ? -c1[1] : c1[1];
                    c1[1] = xOffset > 0 ? tmp : -tmp;
                }
            } else if (upwardsFacing == Direction.SOUTH) {
                c1[1] = -c1[1];
                if (facing.getStepX() == 0) {
                    c1[0] = -c1[0];
                } else {
                    c1[2] = -c1[2];
                }
            }
            if (isFlipped) {
                if (upwardsFacing == Direction.NORTH || upwardsFacing == Direction.SOUTH) {
                    if (ordinal == 2 || ordinal == 3) {
                        c1[0] = -c1[0];
                    } else {
                        c1[2] = -c1[2];
                    }
                } else {
                    c1[1] = -c1[1];
                }
            }
        }
        return new BlockPos(c1[0], c1[1], c1[2]);
    }

    // 保留原始枚举版本供 autoBuild 等方法使用
    private BlockPos setActualRelativeOffset(int x, int y, int z, Direction facing, Direction upwardsFacing,
                                             boolean isFlipped) {
        int[] c0 = new int[] { x, y, z }, c1 = new int[3];
        if (facing == Direction.UP || facing == Direction.DOWN) {
            Direction of = facing == Direction.DOWN ? upwardsFacing : upwardsFacing.getOpposite();
            for (int i = 0; i < 3; i++) {
                switch (structureDir[i].getActualDirection(of)) {
                    case UP -> c1[1] = c0[i];
                    case DOWN -> c1[1] = -c0[i];
                    case WEST -> c1[0] = -c0[i];
                    case EAST -> c1[0] = c0[i];
                    case NORTH -> c1[2] = -c0[i];
                    case SOUTH -> c1[2] = c0[i];
                }
            }
            int xOffset = upwardsFacing.getStepX();
            int zOffset = upwardsFacing.getStepZ();
            int tmp;
            if (xOffset == 0) {
                tmp = c1[2];
                c1[2] = zOffset > 0 ? c1[1] : -c1[1];
                c1[1] = zOffset > 0 ? -tmp : tmp;
            } else {
                tmp = c1[0];
                c1[0] = xOffset > 0 ? c1[1] : -c1[1];
                c1[1] = xOffset > 0 ? -tmp : tmp;
            }
            if (isFlipped) {
                if (upwardsFacing == Direction.NORTH || upwardsFacing == Direction.SOUTH) {
                    c1[0] = -c1[0]; // flip X-axis
                } else {
                    c1[2] = -c1[2]; // flip Z-axis
                }
            }
        } else {
            for (int i = 0; i < 3; i++) {
                switch (structureDir[i].getActualDirection(facing)) {
                    case UP -> c1[1] = c0[i];
                    case DOWN -> c1[1] = -c0[i];
                    case WEST -> c1[0] = -c0[i];
                    case EAST -> c1[0] = c0[i];
                    case NORTH -> c1[2] = -c0[i];
                    case SOUTH -> c1[2] = c0[i];
                }
            }
            if (upwardsFacing == Direction.WEST || upwardsFacing == Direction.EAST) {
                int xOffset = upwardsFacing == Direction.EAST ? facing.getClockWise().getStepX() :
                        facing.getClockWise().getOpposite().getStepX();
                int zOffset = upwardsFacing == Direction.EAST ? facing.getClockWise().getStepZ() :
                        facing.getClockWise().getOpposite().getStepZ();
                int tmp;
                if (xOffset == 0) {
                    tmp = c1[2];
                    c1[2] = zOffset > 0 ? -c1[1] : c1[1];
                    c1[1] = zOffset > 0 ? tmp : -tmp;
                } else {
                    tmp = c1[0];
                    c1[0] = xOffset > 0 ? -c1[1] : c1[1];
                    c1[1] = xOffset > 0 ? tmp : -tmp;
                }
            } else if (upwardsFacing == Direction.SOUTH) {
                c1[1] = -c1[1];
                if (facing.getStepX() == 0) {
                    c1[0] = -c1[0];
                } else {
                    c1[2] = -c1[2];
                }
            }
            if (isFlipped) {
                if (upwardsFacing == Direction.NORTH || upwardsFacing == Direction.SOUTH) {
                    if (facing == Direction.NORTH || facing == Direction.SOUTH) {
                        c1[0] = -c1[0]; // flip X-axis
                    } else {
                        c1[2] = -c1[2]; // flip Z-axis
                    }
                } else {
                    c1[1] = -c1[1]; // flip Y-axis
                }
            }
        }
        return new BlockPos(c1[0], c1[1], c1[2]);
    }

    @Nullable
    private static IntObjectPair<IItemHandler> getMatchStackWithHandler(
                                                                         List<ItemStack> candidates,
                                                                         LazyOptional<IItemHandler> cap) {
        IItemHandler handler = cap.resolve().orElse(null);
        if (handler == null) {
            return null;
        }
        for (int i = 0; i < handler.getSlots(); i++) {
            @NotNull
            ItemStack stack = handler.getStackInSlot(i);
            if (stack.isEmpty()) continue;

            @NotNull
            LazyOptional<IItemHandler> stackCap = stack.getCapability(ForgeCapabilities.ITEM_HANDLER);
            if (stackCap.isPresent()) {
                var rt = getMatchStackWithHandler(candidates, stackCap);
                if (rt != null) {
                    return rt;
                }
            } else if (candidates.stream().anyMatch(candidate -> ItemStack.isSameItemSameTags(candidate, stack)) &&
                    !stack.isEmpty() && stack.getItem() instanceof BlockItem) {
                        return IntObjectPair.of(i, handler);
                    }
        }
        return null;
    }
}
