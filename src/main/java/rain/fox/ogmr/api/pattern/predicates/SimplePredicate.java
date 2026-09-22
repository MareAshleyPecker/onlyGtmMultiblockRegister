package rain.fox.ogmr.api.pattern.predicates;

import rain.fox.ogmr.api.pattern.MultiblockState;
import rain.fox.ogmr.api.pattern.TraceabilityPredicate;
import rain.fox.ogmr.api.pattern.error.PatternStringError;
import rain.fox.ogmr.api.pattern.error.SinglePredicateError;
import rain.fox.ogmr.api.pattern.util.IO;

import com.lowdragmc.lowdraglib.utils.BlockInfo;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;

import it.unimi.dsi.fastutil.longs.Long2ObjectArrayMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 单个方块匹配规则：结构里「一个字符」背后的判定与候选列表。
 *
 * <p>
 * 从 GTM 的 {@code com.gregtechceu.gtceu.api.pattern.predicates.SimplePredicate} 拆出。
 * {@link TraceabilityPredicate} 就是把若干 SimplePredicate 按 common（普通）/ limited（有数量限制）两组装起来。
 *
 * <p>
 * 与 GTM 版本的差异（去 GT 化）：
 * <ul>
 * <li>{@code com.gregtechceu.gtceu.api.capability.recipe.IO} → 本库的
 * {@link rain.fox.ogmr.api.pattern.util.IO}；</li>
 * <li>删除 GTCEu 客户端判定分支：{@link #getCandidates()} 统一走
 * {@code BlockInfo#getItemStackForm()}（LDLib 的这个重载不依赖 Level，服务端也能用）；</li>
 * <li>删除 {@code @OnlyIn(Dist.CLIENT)} 与 GT 的 {@code LangHandler}：
 * {@link #getToolTips(TraceabilityPredicate)} 改用 {@code Component.translatable}
 * 渲染同样的语言键（{@code gtceu.multiblock.pattern.error.limited.*} 等键保持原文，玩家看到的文案不变）；</li>
 * <li>其余字段语义、判定顺序、错误类型与 GTM 完全一致。</li>
 * </ul>
 */
public class SimplePredicate {

    /** 匹配任何方块（结构里的空气/任意位置填充）。 */
    public static SimplePredicate ANY = new SimplePredicate("any", x -> true, null);
    /** 只匹配空气（可替换位置）。 */
    public static SimplePredicate AIR = new SimplePredicate("air",
            blockWorldState -> blockWorldState.getWorld().isEmptyBlock(blockWorldState.getPos()), null);

    @Nullable
    public Supplier<BlockInfo[]> candidates;
    public Predicate<MultiblockState> predicate;
    public List<Component> toolTips;
    public int minCount = -1;
    public int maxCount = -1;
    public int minLayerCount = -1;
    public int maxLayerCount = -1;
    public int previewCount = -1;
    public boolean disableRenderFormed = false;
    public IO io = IO.BOTH;
    public String slotName;
    public String nbtParser;

    public final String type;

    public SimplePredicate() {
        this("unknown");
    }

    public SimplePredicate(String type) {
        this.type = type;
    }

    public SimplePredicate(Predicate<MultiblockState> predicate, @Nullable Supplier<BlockInfo[]> candidates) {
        this();
        this.predicate = predicate;
        this.candidates = candidates;
    }

    public SimplePredicate(String type, Predicate<MultiblockState> predicate,
                           @Nullable Supplier<BlockInfo[]> candidates) {
        this(type);
        this.predicate = predicate;
        this.candidates = candidates;
    }

    public SimplePredicate buildPredicate() {
        return this;
    }

    /**
     * 候选方块/物品的 tooltip 文本（结构预览、JEI/EMI 页用）。
     * <p>
     * 本库不再使用 {@code @OnlyIn(Dist.CLIENT)}，调用方自行决定是否只在客户端调用。
     */
    public List<Component> getToolTips(TraceabilityPredicate predicates) {
        List<Component> result = new ArrayList<>();
        if (toolTips != null) {
            result.addAll(toolTips);
        }
        if (minCount == maxCount && maxCount != -1) {
            result.add(Component.translatable("gtceu.multiblock.pattern.error.limited_exact", minCount));
        } else if (minCount != maxCount && minCount != -1 && maxCount != -1) {
            result.add(Component.translatable("gtceu.multiblock.pattern.error.limited_within", minCount, maxCount));
        } else {
            if (minCount != -1) {
                result.add(Component.translatable("gtceu.multiblock.pattern.error.limited.1", minCount));
            }
            if (maxCount != -1) {
                result.add(Component.translatable("gtceu.multiblock.pattern.error.limited.0", maxCount));
            }
        }
        if (predicates == null) return result;
        if (predicates.isSingle()) {
            result.add(Component.translatable("gtceu.multiblock.pattern.single"));
        }
        if (predicates.hasAir()) {
            result.add(Component.translatable("gtceu.multiblock.pattern.replaceable_air"));
        }
        return result;
    }

    public boolean test(MultiblockState blockWorldState) {
        if (predicate.test(blockWorldState)) {
            return checkInnerConditions(blockWorldState);
        }
        return false;
    }

    public boolean testLimited(MultiblockState blockWorldState) {
        if (testGlobal(blockWorldState) && testLayer(blockWorldState)) {
            return checkInnerConditions(blockWorldState);
        }
        return false;
    }

    private boolean checkInnerConditions(MultiblockState blockWorldState) {
        if (disableRenderFormed) {
            blockWorldState.getMatchContext().getOrCreate("renderMask", LongOpenHashSet::new)
                    .add(blockWorldState.getPos().asLong());
        }
        if (io != IO.BOTH) {
            if (blockWorldState.io == IO.BOTH) {
                blockWorldState.io = io;
            } else if (blockWorldState.io != io) {
                blockWorldState.io = null;
            }
        }
        if (nbtParser != null && !blockWorldState.getWorld().isClientSide) {
            BlockEntity te = blockWorldState.getTileEntity();
            if (te != null) {
                CompoundTag nbt = te.saveWithFullMetadata();
                if (Pattern.compile(nbtParser).matcher(nbt.toString()).find()) {
                    return true;
                }
            }
            blockWorldState.setError(new PatternStringError("The NBT fails to match"));
            return false;
        }
        if (slotName != null) {
            Long2ObjectMap<Set<String>> slots = blockWorldState.getMatchContext().getOrCreate("slots",
                    Long2ObjectArrayMap::new);
            slots.computeIfAbsent(blockWorldState.getPos().asLong(), s -> new HashSet<>()).add(slotName);
            return true;
        }
        return true;
    }

    /** 全局数量计数（上限判定）。 */
    public boolean testGlobal(MultiblockState blockWorldState) {
        if (minCount == -1 && maxCount == -1) return true;
        boolean base = predicate.test(blockWorldState);
        int count = blockWorldState.getGlobalCount().mergeInt(this, base ? 1 : 0, Integer::sum);
        if (maxCount == -1 || count <= maxCount) return base;
        blockWorldState.setError(new SinglePredicateError(this, 0));
        return false;
    }

    /** 单层（一条 aisle / 一层）数量计数（上限判定）。 */
    public boolean testLayer(MultiblockState blockWorldState) {
        if (minLayerCount == -1 && maxLayerCount == -1) return true;
        boolean base = predicate.test(blockWorldState);
        int count = blockWorldState.getLayerCount().mergeInt(this, base ? 1 : 0, Integer::sum);
        if (maxLayerCount == -1 || count <= maxLayerCount) return base;
        blockWorldState.setError(new SinglePredicateError(this, 2));
        return false;
    }

    /** 候选方块对应的物品（错误提示 / 预览用）。 */
    public List<ItemStack> getCandidates() {
        if (candidates == null) return Collections.emptyList();
        return Arrays.stream(this.candidates.get())
                .filter(info -> info.getBlockState().getBlock() != Blocks.AIR)
                .map(BlockInfo::getItemStackForm)
                .collect(Collectors.toList());
    }
}
