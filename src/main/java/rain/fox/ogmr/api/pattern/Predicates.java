package rain.fox.ogmr.api.pattern;

import rain.fox.ogmr.api.OGMRValues;
import rain.fox.ogmr.api.machine.MachineDefinition;
import rain.fox.ogmr.api.machine.multiblock.PartAbility;
import rain.fox.ogmr.api.pattern.error.PatternMessageError;
import rain.fox.ogmr.api.pattern.predicates.PredicateBlockTag;
import rain.fox.ogmr.api.pattern.predicates.PredicateBlocks;
import rain.fox.ogmr.api.pattern.predicates.PredicateFluidTag;
import rain.fox.ogmr.api.pattern.predicates.PredicateFluids;
import rain.fox.ogmr.api.pattern.predicates.PredicateStates;
import rain.fox.ogmr.api.pattern.predicates.SimplePredicate;
import rain.fox.ogmr.api.pattern.util.IO;
import rain.fox.ogmr.api.recipe.OGMRRecipeType;

import com.lowdragmc.lowdraglib.utils.BlockInfo;

import net.minecraft.network.chat.Component;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.material.Fluid;

import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * 结构图案的「predicate 工厂」：{@code FactoryBlockPattern.start().aisle(...).where('A', Predicates.blocks(...))}。
 *
 * <p>
 * 从 GTM 的 {@code com.gregtechceu.gtceu.api.pattern.Predicates} 拆出。保留下来的入口：
 * {@code controller / states / blockStates / blocks / machines / blockTag / fluids / fluidTag /
 * custom / any / air / abilities / ability / autoAbilities}。
 *
 * <p>
 * 与 GTM 版本的差异（去 GT 内容化）：
 * <ul>
 * <li>删除 GT 内容专属入口：{@code heatingCoils()}、{@code cleanroomFilters()}、{@code powerSubstationBatteries()}、
 * {@code dataHatchPredicate(...)}、{@code frames(Material...)}、{@code lamps(...)}/{@code anyLamp()}/{@code lampsByColor(...)}
 * —— 它们分别依赖 GT 的 {@code GTCEuAPI.HEATING_COILS}、{@code CLEANROOM_FILTERS}、{@code PSS_BATTERIES}、
 * {@code ConfigHolder}、{@code GTMaterialBlocks}、{@code LampBlock} 注册表与 {@code IPipeNode}。
 * 需要这类规则时，请用 {@code custom(...)} 或 {@code blocks(...)} 自己拼；</li>
 * <li>{@code machines(MachineDefinition...)} 改用本库 {@code MachineDefinition#getBlock()}（GT 走的是
 * {@code IMachineBlock}）；</li>
 * <li>{@code IMachineBlock} 重载去掉；方块状态匹配统一走 {@code states(...)}；</li>
 * <li>{@code states(...)} 不再依赖 GT 的 {@code GTBlockStateProperties.ACTIVE}，
 * 改为「方块状态里若存在名为 {@code active} 的布尔属性，就自动把它的反状态也加进候选」——效果一致；</li>
 * <li>{@code abilities(...)} 用 {@code PartAbility#isApplicable} 之外的方式实现：GT 用 {@code getAllBlocks()}，
 * 本库只允许用 {@code getBlockRange(from, to)}，因此用 {@code getBlockRange(ULV, MAX)} 取「全部档位的方块」
 * （GT 的 PartAbility 不存在负档位注册，见 GTM {@code PartAbility#register}）；</li>
 * <li>{@code autoAbilities(OGMRRecipeType...)} 无法再按配方类型的 EU/物品/流体输入输出能力裁剪
 * （本库没有 capability 系统），默认按「耗电型多方块」处理：能量只进不出，物品/流体可进可出；
 * 发电机请显式调用 6 参数重载；</li>
 * <li>{@code ConfigHolder.INSTANCE.machines.enableMaintenance} 换成
 * {@link #setMaintenanceRequired(boolean)} 这个静态开关（默认开启）；</li>
 * <li>本文件用到 {@code PartAbility} 的这些常量（与 GTM 同名）：
 * {@code INPUT_ENERGY / OUTPUT_ENERGY / IMPORT_ITEMS / EXPORT_ITEMS / IMPORT_FLUIDS / EXPORT_FLUIDS /
 * MAINTENANCE / MUFFLER / PARALLEL_HATCH}；</li>
 * <li>{@code custom(Predicate<BlockInfo>, ...)} 无法与 GTM 的
 * {@code custom(Predicate<MultiblockState>, Supplier<BlockInfo[]>)} 直接重载共存
 * （两个函数式参数会让「隐式 lambda 调用」产生歧义），因此另起名为 {@code customBlock(...)}。</li>
 * </ul>
 */
public class Predicates {

    private Predicates() {}

    /** 是否需要维护仓（对应 GT 的 {@code machines.enableMaintenance} 配置）。 */
    private static volatile boolean maintenanceRequired = true;

    public static void setMaintenanceRequired(boolean required) {
        maintenanceRequired = required;
    }

    public static boolean isMaintenanceRequired() {
        return maintenanceRequired;
    }

    // ───────────────────────── 控制器 ─────────────────────────

    public static TraceabilityPredicate controller(TraceabilityPredicate predicate) {
        return predicate.setController();
    }

    /** 直接用一个机器定义当控制器（{@code MachineDefinition#getBlock()}）。 */
    public static TraceabilityPredicate controller(MachineDefinition definition) {
        return blocks(definition.getBlock()).setController();
    }

    // ───────────────────────── 方块 ─────────────────────────

    /** 精确匹配若干个方块状态。 */
    public static TraceabilityPredicate states(BlockState... allowedStates) {
        var candidates = new ArrayList<BlockState>();
        for (BlockState state : allowedStates) {
            candidates.add(state);
            BooleanProperty active = findActiveProperty(state);
            if (active != null) {
                candidates.add(state.setValue(active, !state.getValue(active)));
            }
        }
        return new TraceabilityPredicate(new PredicateStates(candidates.toArray(BlockState[]::new)));
    }

    /** {@link #states(BlockState...)} 的别名（不少调用方习惯叫 blockStates）。 */
    public static TraceabilityPredicate blockStates(BlockState... allowedStates) {
        return states(allowedStates);
    }

    public static TraceabilityPredicate blocks(Block... blocks) {
        return new TraceabilityPredicate(new PredicateBlocks(blocks));
    }

    /** 直接用 {@code BlockInfo} 的方块状态当匹配目标（预览/形状信息里常用）。 */
    public static TraceabilityPredicate blocks(BlockInfo... infos) {
        return states(Arrays.stream(infos)
                .filter(Objects::nonNull)
                .map(BlockInfo::getBlockState)
                .toArray(BlockState[]::new));
    }

    /** 用一组机器定义匹配（取它们的方块）。 */
    public static TraceabilityPredicate machines(MachineDefinition... definitions) {
        List<Block> all = new ArrayList<>(definitions.length);
        for (MachineDefinition definition : definitions) {
            if (definition != null) {
                all.add(definition.getBlock());
            }
        }
        return blocks(all.toArray(Block[]::new));
    }

    public static TraceabilityPredicate blockTag(TagKey<Block> tag) {
        return new TraceabilityPredicate(new PredicateBlockTag(tag));
    }

    // ───────────────────────── 流体 ─────────────────────────

    public static TraceabilityPredicate fluids(Fluid... fluids) {
        return new TraceabilityPredicate(new PredicateFluids(fluids));
    }

    public static TraceabilityPredicate fluidTag(TagKey<Fluid> tag) {
        return new TraceabilityPredicate(new PredicateFluidTag(tag));
    }

    // ───────────────────────── 自定义 ─────────────────────────

    /** 完全自定义的匹配规则（GTM 原签名，保留）。 */
    public static TraceabilityPredicate custom(Predicate<MultiblockState> predicate, Supplier<BlockInfo[]> candidates) {
        return new TraceabilityPredicate(predicate, candidates);
    }

    /**
     * 面向 {@link BlockInfo} 的自定义规则：匹配失败时用 {@code errorKey} 作为错误提示。
     * <p>
     * {@code errorKey} 既可以是语言键（{@code "ogmr.multiblock.pattern.error.xxx"}），
     * 也可以直接是一句原文（找不到翻译时会原样显示）。
     */
    public static TraceabilityPredicate customBlock(Predicate<BlockInfo> predicate, String errorKey) {
        return customBlock(predicate, () -> Component.translatable(errorKey));
    }

    /**
     * 面向 {@link BlockInfo} 的自定义规则：匹配失败时用 {@code errorMessage} 作为错误提示。
     */
    public static TraceabilityPredicate customBlock(Predicate<BlockInfo> predicate,
                                                    Supplier<Component> errorMessage) {
        return customBlock(predicate, errorMessage, null);
    }

    /**
     * 面向 {@link BlockInfo} 的自定义规则（带预览候选）。
     *
     * @param predicate    以「当前位置的方块信息」为输入的判定
     * @param errorMessage 失败时的提示文本（延迟求值）
     * @param candidates   预览用候选方块，可为 null
     */
    public static TraceabilityPredicate customBlock(Predicate<BlockInfo> predicate,
                                                    Supplier<Component> errorMessage,
                                                    @Nullable Supplier<BlockInfo[]> candidates) {
        PatternMessageError error = new PatternMessageError(errorMessage);
        SimplePredicate simple = new SimplePredicate(
                state -> predicate.test(BlockInfo.fromBlockState(state.getBlockState())), candidates) {

            @Override
            public boolean test(MultiblockState blockWorldState) {
                if (super.test(blockWorldState)) {
                    return true;
                }
                blockWorldState.setError(error);
                return false;
            }
        };
        return new TraceabilityPredicate(simple);
    }

    /** 任意方块。 */
    public static TraceabilityPredicate any() {
        return new TraceabilityPredicate(SimplePredicate.ANY);
    }

    /** 只匹配空气（可替换位置）。 */
    public static TraceabilityPredicate air() {
        return new TraceabilityPredicate(SimplePredicate.AIR);
    }

    // ───────────────────────── 仓室（PartAbility） ─────────────────────────

    /**
     * 匹配这些仓室能力下的所有方块（任意档位）。
     * <p>
     * 实现说明：GT 用 {@code PartAbility#getAllBlocks()}，本库只允许用
     * {@code getBlockRange(from, to)}，故取 {@code [ULV, MAX]} 全档位范围（GT 的 PartAbility 无负档位注册）。
     */
    public static TraceabilityPredicate abilities(PartAbility... abilities) {
        List<Block> all = new ArrayList<>();
        for (PartAbility ability : abilities) {
            if (ability == null) continue;
            all.addAll(ability.getBlockRange(OGMRValues.ULV, OGMRValues.MAX));
        }
        return blocks(all.toArray(Block[]::new));
    }

    /** 匹配某个仓室能力在指定档位的方块。 */
    public static TraceabilityPredicate ability(PartAbility ability, int tier) {
        return ability(ability, new int[] { tier });
    }

    /**
     * 匹配某个仓室能力在若干档位的方块。
     * <p>
     * 传空数组时等价于 GTM 的 {@code getAllBlocks()}（取全部档位）。
     */
    public static TraceabilityPredicate ability(PartAbility ability, int[] tiers) {
        if (tiers == null || tiers.length == 0) {
            return blocks(ability.getBlockRange(OGMRValues.ULV, OGMRValues.MAX).toArray(Block[]::new));
        }
        return blocks(ability.getBlocks(tiers).toArray(Block[]::new));
    }

    // ───────────────────────── 自动仓室组合 ─────────────────────────

    /**
     * 按配方类型自动生成仓室规则。
     *
     * <p>
     * <b>现在会读配方类型自己的声明</b>（比之前的「一律按耗电型处理」准确得多）：
     * <ul>
     * <li><b>能量</b>：看 {@link OGMRRecipeType#getEnergyIO()} ——
     * {@code IN}（用电器）要求<b>能源输入仓</b>，{@code OUT}（发电机）要求<b>能源输出仓</b>，
     * {@code BOTH} 两者都放，{@code NONE} 一个都不放。传多个配方类型时按「并集」处理
     * （只要有一个吃电就要求输入仓，只要有一个发电就要求输出仓）；</li>
     * <li><b>物品/流体</b>：看该类型的 {@code maxItemInputs/maxItemOutputs/maxFluidInputs/maxFluidOutputs}
     * —— 上限为 0 的方向就不放对应仓室（例如只吃不吐的机器不会再要求输出总线）。</li>
     * </ul>
     *
     * <p>
     * 不传配方类型（或传了 null）时退回旧的保守行为：耗电 + 物品/流体可进可出。
     * 需要更精细的控制（例如同时要维护仓/消声仓/并行仓）请直接用 6 参数重载。
     */
    public static TraceabilityPredicate autoAbilities(OGMRRecipeType... types) {
        if (types == null || types.length == 0) {
            return autoAbilities(true, false, true, true, true, true);
        }

        boolean energyIn = false;
        boolean energyOut = false;
        boolean itemIn = false;
        boolean itemOut = false;
        boolean fluidIn = false;
        boolean fluidOut = false;
        boolean any = false;

        for (OGMRRecipeType type : types) {
            if (type == null) continue;
            any = true;
            energyIn |= type.getEnergyIO().support(IO.IN);
            energyOut |= type.getEnergyIO().support(IO.OUT);
            itemIn |= type.getMaxItemInputs() > 0;
            itemOut |= type.getMaxItemOutputs() > 0;
            fluidIn |= type.getMaxFluidInputs() > 0;
            fluidOut |= type.getMaxFluidOutputs() > 0;
        }

        if (!any) return autoAbilities(true, false, true, true, true, true);
        return autoAbilities(energyIn, energyOut, itemIn, itemOut, fluidIn, fluidOut);
    }

    /**
     * 6 个布尔开关的完整版本（与 GTM 语义一致）。
     */
    public static TraceabilityPredicate autoAbilities(boolean checkEnergyIn,
                                                      boolean checkEnergyOut,
                                                      boolean checkItemIn,
                                                      boolean checkItemOut,
                                                      boolean checkFluidIn,
                                                      boolean checkFluidOut) {
        TraceabilityPredicate predicate = new TraceabilityPredicate();

        if (checkEnergyIn) {
            predicate = predicate.or(abilities(PartAbility.INPUT_ENERGY).setMinGlobalLimited(1)
                    .setMaxGlobalLimited(2).setPreviewCount(1));
        }
        if (checkEnergyOut) {
            predicate = predicate.or(abilities(PartAbility.OUTPUT_ENERGY).setMinGlobalLimited(1)
                    .setMaxGlobalLimited(2).setPreviewCount(1));
        }
        if (checkItemIn) {
            predicate = predicate.or(abilities(PartAbility.IMPORT_ITEMS).setPreviewCount(1));
        }
        if (checkItemOut) {
            predicate = predicate.or(abilities(PartAbility.EXPORT_ITEMS).setPreviewCount(1));
        }
        if (checkFluidIn) {
            predicate = predicate.or(abilities(PartAbility.IMPORT_FLUIDS).setPreviewCount(1));
        }
        if (checkFluidOut) {
            predicate = predicate.or(abilities(PartAbility.EXPORT_FLUIDS).setPreviewCount(1));
        }
        return predicate;
    }

    /**
     * 维护仓 / 消声仓 / 并行仓的常见组合（与 GTM 语义一致）。
     *
     * @param checkMaintenance 是否需要维护仓（1 个，受 {@link #setMaintenanceRequired(boolean)} 控制）
     * @param checkMuffler     是否需要消声仓（恰好 1 个）
     * @param checkParallel    是否可选并行仓（最多 1 个）
     */
    public static TraceabilityPredicate autoAbilities(boolean checkMaintenance, boolean checkMuffler,
                                                      boolean checkParallel) {
        TraceabilityPredicate predicate = new TraceabilityPredicate();
        if (checkMaintenance) {
            predicate = predicate.or(abilities(PartAbility.MAINTENANCE)
                    .setMinGlobalLimited(maintenanceRequired ? 1 : 0)
                    .setMaxGlobalLimited(1));
        }
        if (checkMuffler) {
            predicate = predicate.or(abilities(PartAbility.MUFFLER).setMinGlobalLimited(1).setMaxGlobalLimited(1));
        }
        if (checkParallel) {
            predicate = predicate.or(abilities(PartAbility.PARALLEL_HATCH).setMaxGlobalLimited(1).setPreviewCount(1));
        }
        return predicate;
    }

    // ───────────────────────── 内部工具 ─────────────────────────

    /**
     * 找方块状态里名为 {@code active} 的布尔属性（GT 用的是 {@code GTBlockStateProperties.ACTIVE}）。
     *
     * @return 找到则返回该属性，否则 null
     */
    @Nullable
    private static BooleanProperty findActiveProperty(BlockState state) {
        for (Property<?> property : state.getProperties()) {
            if (property instanceof BooleanProperty booleanProperty && "active".equals(property.getName())) {
                return booleanProperty;
            }
        }
        return null;
    }
}
