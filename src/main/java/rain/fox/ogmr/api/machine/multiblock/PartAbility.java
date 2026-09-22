package rain.fox.ogmr.api.machine.multiblock;

import rain.fox.ogmr.Ogmr;
import rain.fox.ogmr.api.registry.OGMRRegistries;

import net.minecraft.world.level.block.Block;

import lombok.Getter;

import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;

import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Supplier;

import org.apache.commons.lang3.ArrayUtils;
import org.jetbrains.annotations.Nullable;

/**
 * 本文件是从 GTM 的 {@code com.gregtechceu.gtceu.api.machine.multiblock.PartAbility} 精简拆出来的。
 *
 * <p>
 * 「仓室能力」= 一个字符串名字 + 一张「档位 → 方块」的表。
 * 结构图案里的 {@code Predicates.abilities(...)} 与运行期的能力查询都读这张表，
 * 所以一个能力<b>只应该有一个实例</b>。
 *
 * <p>
 * 与 GTM 的差别：
 * <ul>
 * <li>GTM 里那 30 多个内容常量（线圈、激光、光算……）属于 GT 的内容体系，本库<b>不提供</b>；
 * 只保留 9 个「注册工具库自己就要用」的标准能力常量（见下方 static 字段），
 * 其余交给 addon 用 {@link #create(String)} 自己建；</li>
 * <li>{@code create(name)} 是新增的工厂：幂等（同名返回同一个实例）、线程安全，
 * 并会尝试登记进 {@link OGMRRegistries#PART_ABILITIES}；</li>
 * <li>{@code getAllBlocks()} 改为在 {@link #register(int, Block)} 时失效缓存，
 * 而不是 GTM 那种「第一次调用后永久记忆」（注册是分步进行的，记忆化会漏掉后注册的方块）。</li>
 * </ul>
 */
public class PartAbility {

    /**
     * 本进程内「按名字创建出来的能力」缓存。
     *
     * <p>
     * ⚠️ <b>必须声明在下面那批常量之前。</b>静态字段按声明顺序初始化，而常量在
     * {@code <clinit>} 里就会调用 {@link #create(String)}；如果本字段声明在常量后面，
     * 常量初始化时读到的它还是 {@code null}，直接 NPE 把 PartAbility 变成
     * 「Could not initialize class」——只在跑游戏时暴露。
     *
     * <p>
     * 它是 {@link #create(String)} 幂等性的兜底：即使注册表已经冻结
     * （{@code PART_ABILITIES} 表在注册阶段之外是冻结的，见 {@code OGMRRegistry}），
     * 同名调用也一定拿到同一个实例，不会出现「两张能力表各看各的」。
     */
    private static final ConcurrentMap<String, PartAbility> CREATED = new ConcurrentHashMap<>();

    // ═══════════════ 标准能力 ═══════════════

    /** 输入能量仓（耗电机器）。 */
    public static final PartAbility INPUT_ENERGY = create("input_energy");
    /** 输出能量仓（发电机）。 */
    public static final PartAbility OUTPUT_ENERGY = create("output_energy");
    /** 物品输入总线。 */
    public static final PartAbility IMPORT_ITEMS = create("import_items");
    /** 物品输出总线。 */
    public static final PartAbility EXPORT_ITEMS = create("export_items");
    /** 流体输入仓。 */
    public static final PartAbility IMPORT_FLUIDS = create("import_fluids");
    /** 流体输出仓。 */
    public static final PartAbility EXPORT_FLUIDS = create("export_fluids");
    /** 维护仓。 */
    public static final PartAbility MAINTENANCE = create("maintenance");
    /** 消声仓。 */
    public static final PartAbility MUFFLER = create("muffler");
    /** 并行控制仓。 */
    public static final PartAbility PARALLEL_HATCH = create("parallel_hatch");

    // ═══════════════ 工厂 ═══════════════

    /**
     * 档位 → 该档位下具备本能力的方块<b>供应器</b>。
     *
     * <p>
     * 存 {@link Supplier} 而不是直接存 {@link Block}：{@code PartBuilder} 登记能力时还在
     * <b>mod 构造期</b>，那会儿 Forge 的 {@code RegisterEvent} 还没跑，
     * 方块对应的 {@code RegistryObject} 是「未注册」状态，调 {@code get()} 会抛
     * {@code NullPointerException: Registry Object not present}。
     * 所以这里只登记「到时候去哪拿方块」，真正解析推迟到结构匹配/查询时（那时早已注册完）。
     */
    private final Int2ObjectMap<Set<Supplier<? extends Block>>> registry = new Int2ObjectOpenHashMap<>();

    /** getAllBlocks() 的缓存；{@link #register(int, Block)} 时失效。 */
    private volatile Set<Block> allBlocksCache;

    @Getter
    private final String name;

    public PartAbility(String name) {
        this.name = Objects.requireNonNull(name, "name");
    }

    /**
     * 按名字取（或创建）一个能力：<b>幂等 + 线程安全</b>。
     *
     * <p>
     * 查找顺序：{@link OGMRRegistries#PART_ABILITIES}（已登记的表项优先）→ 本进程内的创建缓存 →
     * 新建一个并尽力登记进注册表。
     *
     * <p>
     * 注意：注册表在注册阶段之外是冻结的，此时「登记」会被跳过（只打一条 warn），
     * 但本方法仍然保证同名返回同一实例。
     *
     * @param name 能力名（约定用 snake_case，例如 {@code import_items}）
     */
    public static PartAbility create(String name) {
        Objects.requireNonNull(name, "name");

        PartAbility registered = OGMRRegistries.PART_ABILITIES.get(name);
        if (registered != null) return registered;

        return CREATED.computeIfAbsent(name, n -> {
            PartAbility ability = new PartAbility(n);
            try {
                if (!OGMRRegistries.PART_ABILITIES.isFrozen() && !OGMRRegistries.PART_ABILITIES.containKey(n)) {
                    OGMRRegistries.PART_ABILITIES.registerOrOverride(n, ability);
                } else {
                    Ogmr.LOGGER.warn(
                            "ogmr: part ability '{}' was created while PART_ABILITIES is frozen (or already contains it); "
                                    + "it will only be visible to its own holder. Register abilities during the registration phase.",
                            n);
                }
            } catch (Throwable t) {
                // 拿不到注册表项最多是「MultiblockPartMachine#getAbilities() 不列这条能力」，
                // 不该让类加载/世界 tick 崩掉。
                Ogmr.LOGGER.warn("ogmr: failed to register part ability '{}'", n, t);
            }
            return ability;
        });
    }

    // ═══════════════ 登记 / 查询 ═══════════════

    /** 把「某个档位的某个方块」登记成具备本能力（由 {@code PartBuilder} 在注册时调用）。 */
    public void register(int tier, Block block) {
        register(tier, () -> block);
    }

    /**
     * 登记一个<b>延迟解析</b>的方块（推荐，见 {@link #registry} 的说明）。
     *
     * <p>
     * {@code PartBuilder} 走的就是这个重载：传入 {@code RegistryObject} 之类的供应器，
     * 方块在 Forge 注册完成后才被真正取出来。
     */
    public void register(int tier, Supplier<? extends Block> blockSupplier) {
        if (blockSupplier == null) return;
        registry.computeIfAbsent(tier, t -> new LinkedHashSet<>()).add(blockSupplier);
        // 有新方块进来，缓存失效
        allBlocksCache = null;
    }

    /** 所有已登记方块（各档位并集）。
     *
     * <p>
     * 供应器还没法解析时（例如在 Forge 注册完成之前被问到）返回「当前拿得到的部分」，
     * 且<b>不写入缓存</b>，这样注册完成后再问一次就能拿到完整结果。
     */
    public Collection<Block> getAllBlocks() {
        Set<Block> cached = allBlocksCache;
        if (cached != null) return cached;

        Set<Block> all = new HashSet<>();
        boolean complete = collect(all, null, Integer.MIN_VALUE, Integer.MAX_VALUE);
        if (complete) {
            cached = Set.copyOf(all);
            allBlocksCache = cached;
        }
        return all;
    }

    /**
     * 把符合条件的档位的方块收进 {@code out}。
     *
     * @return 是否全部解析成功（false = 有供应器还没准备好，调用方不要缓存结果）
     */
    private boolean collect(Set<Block> out, @Nullable int[] tiers, int from, int to) {
        for (Int2ObjectMap.Entry<Set<Supplier<? extends Block>>> entry : registry.int2ObjectEntrySet()) {
            int tier = entry.getIntKey();
            if (tier < from || tier > to) continue;
            if (tiers != null && !ArrayUtils.contains(tiers, tier)) continue;
            for (Supplier<? extends Block> supplier : entry.getValue()) {
                try {
                    Block block = supplier.get();
                    if (block != null) out.add(block);
                } catch (Throwable notRegisteredYet) {
                    // RegistryObject 还没注册：这次结果不完整，交给调用方决定是否缓存
                    return false;
                }
            }
        }
        return true;
    }

    /** 这个方块是否具备本能力。 */
    public boolean isApplicable(Block block) {
        return getAllBlocks().contains(block);
    }

    /** 指定若干档位下的全部方块。 */
    public Collection<Block> getBlocks(int... tiers) {
        Set<Block> result = new HashSet<>();
        collect(result, tiers, Integer.MIN_VALUE, Integer.MAX_VALUE);
        return result;
    }

    /** 档位区间 {@code [from, to]}（闭区间）内的全部方块。 */
    public Collection<Block> getBlockRange(int from, int to) {
        Set<Block> result = new HashSet<>();
        collect(result, null, from, to);
        return result;
    }

    @Override
    public String toString() {
        return "PartAbility[" + name + "]";
    }
}
