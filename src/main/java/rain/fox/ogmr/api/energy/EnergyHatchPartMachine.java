package rain.fox.ogmr.api.energy;

import rain.fox.ogmr.api.OGMRValues;
import rain.fox.ogmr.api.lang.OGMRLang;
import rain.fox.ogmr.api.machine.IMachineBlockEntity;
import rain.fox.ogmr.api.machine.multiblock.PartAbility;
import rain.fox.ogmr.api.machine.multiblock.part.MultiblockPartMachine;

import net.minecraft.network.chat.Component;
import net.minecraftforge.energy.IEnergyStorage;

import lombok.Getter;

import java.util.List;
import java.util.Objects;

/**
 * 本文件是为 ogmr 新写的能量系统（需求 7）。
 *
 * <p>
 * 能源仓（能量仓室）：多方块结构上的一块「电池进出口」。
 * 它自己只做三件事：<b>存</b>（{@link EnergyContainer}）、<b>收</b>（{@link #acceptEnergy(long)}）、
 * <b>发</b>（{@link #extractEnergy(long)}）。
 *
 * <h3>尺寸来自注册表，不写死</h3>
 * <p>
 * 容量与速率来自构造时传入的 {@link EnergyHatchSize}（可注册，见 {@link EnergyHatchSizes}）。
 * 也有按名字构造的重载：名字查不到时列表里会给出「已注册的名字」方便排查。
 *
 * <h3>单位是构造参数</h3>
 * <p>
 * 每台仓室可以有自己的对外单位（{@link #getUnit()}）—— 内部存储永远是 <b>FE</b>，
 * 但报数与面板按配置的单位换算：
 * <ul>
 * <li>{@link #getStoredInUnit()} / {@link #getCapacityInUnit()} /
 * {@link #getMaxInputInUnit()} / {@link #getMaxOutputInUnit()} —— 按本仓室的单位报数；</li>
 * <li>{@link #acceptEnergyInUnit(long)} / {@link #extractEnergyInUnit(long)} ——
 * 按本仓室的单位收发（传入一个 EU 数就按 EU 解释）；</li>
 * <li>{@link #acceptEnergy(long)} / {@link #extractEnergy(long)} / {@link #getEnergyStored()} /
 * {@link #getEnergyCapacity()} —— <b>始终是 FE</b>。</li>
 * </ul>
 * 最后一条是刻意的：{@link IEnergyContainer} 的契约就是 FE，控制器/别的仓室之间搬运能量时
 * 如果按各自单位解释，一台上写着 EU 的仓室就会把「1000 FE」当成「1000 EU」吞下去（凭空多 4 倍）——
 * 这类 bug 极其难查。所以「按单位说话」的能力用显式带 {@code InUnit} 的方法提供，
 * 不污染 FE 语义的接口。
 *
 * <p>
 * 角色（{@link #isInput()}）只影响两件事：面板上显示「最大输入」还是「最大输出」、
 * 以及 addon 在注册 {@link PartAbility} 时把它归到输入能力还是输出能力。
 * <b>存储本身对两个角色是一样的</b>：控制器从输入仓抽能量、往输出仓灌能量，
 * 所以两个方向都得放行（挡掉哪一边由 addon 在注册能力时决定，不在本类里硬编码）。
 *
 * <h3>serverTick() 里为什么什么都不做</h3>
 * <p>
 * 需求原文提到「serverTick 里只做存储与收发」，但收发在本设计里是 <b>纯被动</b> 的：
 * 谁来拿/谁来给，谁就调 {@link #acceptEnergy(long)} / {@link #extractEnergy(long)}，
 * 仓室没有需要每 tick 自己做的事（不主动推、不主动拉），所以本类的覆写只调
 * {@code super.serverTick()} —— {@code MetaMachine} 的 javadoc 明确要求子类必须先调它，
 * 否则 {@code subscribeServerTick} 注册的任务会全部失效 —— 并把 tick 留给子类扩展。
 *
 * <p>
 * 想让输出仓自己往相邻方块灌能量的 addon：覆写 {@link #serverTick()}，
 * 先调 {@code super.serverTick()}，再在覆写里做（或先用
 * {@code subscribeServerTick(last, this::pushEnergy)} 挂个订阅，避免每 tick 都跑）。
 *
 * <p>
 * TODO（存盘 / 同步）：本类的存量目前没有走 LDLib 的 {@code @Persisted} / {@code @DescSynced}，
 * 所以「存档后重载」会丢掉仓里剩余的能量，客户端面板上读到的存量也可能是 0。
 * {@code MetaMachine} 已经落盘，路径是清楚的：给本类声明
 * {@code public static final ManagedFieldHolder MANAGED_FIELD_HOLDER =
 * MetaMachine.holder(EnergyHatchPartMachine.class, MultiblockPartMachine.MANAGED_FIELD_HOLDER);}
 * 并覆写 {@code getFieldHolder()}，再把存量做成 {@code @Persisted @DescSynced} 的 long
 * （或把 {@link EnergyContainer} 改造成 LDLib 的 trait）。
 * 这里刻意先不做：容器与镜像字段的双向同步必须上真机验证，
 * 凭空塞一个「看起来对」的同步字段比暂时不同步更危险。
 */
public class EnergyHatchPartMachine extends MultiblockPartMachine implements IEnergyContainer {

    // ═══════════════ 语言键 ═══════════════

    /** 「能源输入仓」。 */
    public static final String LANG_ROLE_INPUT = "ogmr.energy.hatch.role.input";

    /** 「能源输出仓」。 */
    public static final String LANG_ROLE_OUTPUT = "ogmr.energy.hatch.role.output";

    /** 「容量：%s（%s）」。 */
    public static final String LANG_CAPACITY = "ogmr.energy.hatch.capacity";

    /** 「已存：%s（%s）（%s）」。 */
    public static final String LANG_STORED = "ogmr.energy.hatch.stored";

    /** 「最大输入：%s/t（%s/t）」。 */
    public static final String LANG_MAX_INPUT = "ogmr.energy.hatch.max_input";

    /** 「最大输出：%s/t（%s/t）」。 */
    public static final String LANG_MAX_OUTPUT = "ogmr.energy.hatch.max_output";

    /** 「总览：%s」（三单位并排，见 {@link EnergyConversion#format(long)}）。 */
    public static final String LANG_OVERVIEW = "ogmr.energy.hatch.overview";

    // ═══════════════ 状态 ═══════════════

    /** 真正的存储（线程安全的加减法都在它里面），单位恒为 FE。 */
    private final EnergyContainer energyContainer;

    /** 尺寸定义（容量 / 速率 / 名字），来自注册表或调用方自建。 */
    @Getter
    private final EnergyHatchSize size;

    /**
     * 电压档位（{@link OGMRValues} 的 ULV=0 … MAX=14）。
     *
     * <p>
     * 覆写 {@code MetaMachine.getTier()}（它默认转发 {@code getDefinition().getTier()}）：
     * 这里以构造时传入的 {@code tier} 为准 —— 因为仓室往往是「一个方块定义配多档」注册的，
     * 实例参数比定义上的默认值更可靠。
     */
    @Getter
    private final int tier;

    /**
     * 本仓室的对外单位（内部存储仍是 FE）。
     *
     * <p>
     * 类型是 {@link IEnergyType} 而不是枚举 —— 第三方注册的自定义能量种类（RF、魔力……）
     * 可以直接塞进来，本仓室不需要认识它。
     */
    @Getter
    private final IEnergyType unit;

    /** true = 输入仓，false = 输出仓。 */
    @Getter
    private final boolean isInput;

    /**
     * 主构造。
     *
     * @param holder  机器宿主（由 {@code MetaMachine} 提供）
     * @param tier    电压档位，仅用于显示与 {@link #getTier()}，不参与容量计算
     * @param size    尺寸定义，<b>不可以为 null</b>；想按名字查表请用
     *                {@link #EnergyHatchPartMachine(IMachineBlockEntity, int, String, IEnergyType, boolean)}
     * @param unit    对外单位（任意 {@link IEnergyType}），null 时退回 {@code size.defaultUnit()}
     * @param isInput true = 输入仓，false = 输出仓
     */
    public EnergyHatchPartMachine(IMachineBlockEntity holder, int tier, EnergyHatchSize size, IEnergyType unit,
                                  boolean isInput) {
        super(holder);
        this.size = Objects.requireNonNull(size, "energy hatch size must not be null");
        this.tier = tier;
        this.unit = unit != null ? unit : this.size.defaultUnit();
        this.isInput = isInput;
        this.energyContainer = new EnergyContainer(this.size.capacityFe(), this.size.maxInputFe(),
                this.size.maxOutputFe());
    }

    /** 单位用尺寸定义里的默认值。 */
    public EnergyHatchPartMachine(IMachineBlockEntity holder, int tier, EnergyHatchSize size, boolean isInput) {
        this(holder, tier, size, null, isInput);
    }

    /**
     * 按名字从注册表取尺寸（{@link EnergyHatchSizes#get(String)}，名字大小写/空格不敏感）。
     *
     * @param sizeName 注册名，例如 {@code "reinforced"}
     * @throws IllegalArgumentException 名字没注册过（异常信息里会列出已注册的名字，方便排查）
     */
    public EnergyHatchPartMachine(IMachineBlockEntity holder, int tier, String sizeName, IEnergyType unit,
                                  boolean isInput) {
        this(holder, tier, resolve(sizeName), unit, isInput);
    }

    /** 按名字查表 + 用尺寸定义里的默认单位。 */
    public EnergyHatchPartMachine(IMachineBlockEntity holder, int tier, String sizeName, boolean isInput) {
        this(holder, tier, resolve(sizeName), null, isInput);
    }

    private static EnergyHatchSize resolve(String sizeName) {
        EnergyHatchSize found = EnergyHatchSizes.get(sizeName);
        if (found == null) {
            throw new IllegalArgumentException("未注册的能量仓尺寸 [" + sizeName + "]，已注册的有："
                    + EnergyHatchSizes.all());
        }
        return found;
    }

    // ═══════════════ 属性 ═══════════════

    /** 尺寸名，例如 {@code "reinforced"}。 */
    public String getSizeName() {
        return size.name();
    }

    /** 尺寸在注册表里的下标；自定义（未注册）尺寸返回 -1。 */
    public int getSizeIndex() {
        return EnergyHatchSizes.indexOf(size.name());
    }

    /** 存储本体（调试 / 需要直接搬运时用；正常请走本类的收发方法）。 */
    public EnergyContainer getContainer() {
        return energyContainer;
    }

    /**
     * 把仓室当作 Forge 的 {@code ENERGY} 能力暴露出去 —— 这样线缆、AE2 的能源接收器、
     * Mekanism 的通用线缆都能直接给它充能（三家对外都走 Forge Energy，见
     * {@link EnergyConversion} 类注释）。
     *
     * <p>
     * 输入仓暴露可收的视图、输出仓暴露可取的视图，都是同一个存储本体；方向不敏感。
     */
    @Override
    @SuppressWarnings("unchecked")
    public <T> T getCapability(net.minecraftforge.common.capabilities.Capability<T> capability,
                               net.minecraft.core.Direction side) {
        if (capability == net.minecraftforge.common.capabilities.ForgeCapabilities.ENERGY) {
            return (T) energyContainer.asForgeStorage();
        }
        return super.getCapability(capability, side);
    }

    // ═══════════════ IEnergyContainer（一律 FE） ═══════════════

    @Override
    public long getEnergyStored() {
        return energyContainer.getEnergyStored();
    }

    @Override
    public long getEnergyCapacity() {
        return energyContainer.getEnergyCapacity();
    }

    @Override
    public long getMaxInput() {
        return energyContainer.getMaxInput();
    }

    @Override
    public long getMaxOutput() {
        return energyContainer.getMaxOutput();
    }

    @Override
    public long acceptEnergy(long amount) {
        return energyContainer.acceptEnergy(amount);
    }

    @Override
    public long extractEnergy(long amount) {
        return energyContainer.extractEnergy(amount);
    }

    /**
     * 把本仓室当成 Forge Energy 接口暴露给别的 mod
     * （管道、发电机、别的 mod 的机器直接往这里塞/抽）。Forge 那边永远按 FE 说话。
     */
    public IEnergyStorage asForgeStorage() {
        return energyContainer.asForgeStorage();
    }

    // ═══════════════ 按本仓室单位报数 / 收发 ═══════════════

    /** 已存能量，按本仓室的单位（{@link #getUnit()}）换算，向下取整。 */
    public long getStoredInUnit() {
        return EnergyConversion.fromFe(getEnergyStored(), unit);
    }

    /** 容量，按本仓室的单位换算，向下取整。 */
    public long getCapacityInUnit() {
        return EnergyConversion.fromFe(getEnergyCapacity(), unit);
    }

    /** 最大输入，按本仓室的单位换算，向下取整。 */
    public long getMaxInputInUnit() {
        return EnergyConversion.fromFe(getMaxInput(), unit);
    }

    /** 最大输出，按本仓室的单位换算，向下取整。 */
    public long getMaxOutputInUnit() {
        return EnergyConversion.fromFe(getMaxOutput(), unit);
    }

    /**
     * 收能量，{@code amount} 的单位是<b>本仓室的单位</b>。
     *
     * @return 实际收下的量（同样以本仓室的单位计，向下取整）
     */
    public long acceptEnergyInUnit(long amount) {
        long acceptedFe = energyContainer.acceptEnergy(EnergyConversion.toFe(amount, unit));
        return EnergyConversion.fromFe(acceptedFe, unit);
    }

    /**
     * 发能量，{@code amount} 的单位是<b>本仓室的单位</b>。
     *
     * @return 实际给出的量（同样以本仓室的单位计，向下取整）
     */
    public long extractEnergyInUnit(long amount) {
        long extractedFe = energyContainer.extractEnergy(EnergyConversion.toFe(amount, unit));
        return EnergyConversion.fromFe(extractedFe, unit);
    }

    /** 某个 FE 值换算成本仓室的单位后带单位缩写的文本，例如 {@code "312 EU"}。 */
    public String formatInUnit(long fe) {
        return EnergyConversion.formatAmount(EnergyConversion.fromFe(fe, unit), unit);
    }

    // ═══════════════ 面板 ═══════════════

    @Override
    public void addDisplayText(List<Component> textList) {
        super.addDisplayText(textList);

        // 第一行：LV 能源输入仓 · 微型能源仓 [FE]
        textList.add(Component.literal(OGMRValues.tierNameRaw(tier) + " ")
                .append(Component.translatable(isInput ? LANG_ROLE_INPUT : LANG_ROLE_OUTPUT))
                .append(" · ")
                .append(sizeNameComponent())
                .append(" [" + unit.getSymbol() + "]"));

        // 容量：按本仓室的单位 + 等值 FE
        textList.add(Component.translatable(LANG_CAPACITY,
                formatInUnit(getEnergyCapacity()),
                EnergyConversion.formatAmount(getEnergyCapacity(), EnergyUnit.FE)));

        // 已存：按本仓室的单位 + 等值 FE + 百分比
        textList.add(Component.translatable(LANG_STORED,
                formatInUnit(getEnergyStored()),
                EnergyConversion.formatAmount(getEnergyStored(), EnergyUnit.FE),
                getStoredPercentText()));

        // 输入仓显示最大输入，输出仓显示最大输出 —— 各自最关心的那个速率
        long rateFe = isInput ? getMaxInput() : getMaxOutput();
        textList.add(Component.translatable(isInput ? LANG_MAX_INPUT : LANG_MAX_OUTPUT,
                formatInUnit(rateFe),
                EnergyConversion.formatAmount(rateFe, EnergyTypes.FE)));

        // 多单位并排总览。配的单位本来就是基准（FE）时，上面几行已经把它说全了，不再重复一行
        if (!unit.isBaseUnit()) {
            textList.add(Component.translatable(LANG_OVERVIEW, EnergyConversion.format(getEnergyStored())));
        }
    }

    // ═══════════════ tick ═══════════════

    /**
     * 服务端 tick。
     *
     * <p>
     * 本类没有每 tick 要做的事（收发是被动的，见类注释），但<b>必须</b>调
     * {@code super.serverTick()}：{@code MetaMachine} 的 javadoc 明确要求子类先调它，
     * 漏掉会让 {@code subscribeServerTick} 注册的任务全部失效（这类 bug 极难查）。
     * 之所以仍然覆写，是给子类留一个语义明确的扩展点 —— 想主动推能量就在这里做，
     * 记得先 {@code super.serverTick()}。
     */
    @Override
    public void serverTick() {
        super.serverTick();
    }

    /** 尺寸名文本：有语言键就用翻译，没有就退回裸名字（别让面板上出现裸 key）。 */
    private Component sizeNameComponent() {
        String key = size.langKey();
        return OGMRLang.contains(key) ? Component.translatable(key) : Component.literal(size.name());
    }

    // ═══════════════ 语言 ═══════════════

    /**
     * 登记本类用到的双语文本（幂等）。
     *
     * <p>
     * 顺带把 {@link EnergyTypes} 里<b>所有已注册能量种类</b>（内置四种 + 第三方注册的）的双语名、
     * 以及当前仓室表里的尺寸名一起登记 —— 这几张表本来就属于能量系统，分开调容易漏。
     * 想让内置 8 级预设带上「微型能源仓」这类中文名，先调
     * {@link EnergyHatchSizes#registerDefaultPreset()}。
     */
    public static void initLang() {
        EnergyTypes.initLang();
        EnergyHatchSizes.initLang();
        OGMRLang.add(LANG_ROLE_INPUT, "Energy Input Hatch", "能源输入仓");
        OGMRLang.add(LANG_ROLE_OUTPUT, "Energy Output Hatch", "能源输出仓");
        OGMRLang.add(LANG_CAPACITY, "Capacity: %s (%s)", "容量：%s（%s）");
        OGMRLang.add(LANG_STORED, "Stored: %s (%s, %s)", "已存：%s（%s，%s）");
        OGMRLang.add(LANG_MAX_INPUT, "Max Input: %s/t (%s/t)", "最大输入：%s/t（%s/t）");
        OGMRLang.add(LANG_MAX_OUTPUT, "Max Output: %s/t (%s/t)", "最大输出：%s/t（%s/t）");
        OGMRLang.add(LANG_OVERVIEW, "Total: %s", "总览：%s");
    }

    @Override
    public String toString() {
        return "EnergyHatchPartMachine[" + OGMRValues.tierNameRaw(tier) + " " + size.name()
                + (isInput ? " in" : " out") + ", unit " + unit.getSymbol() + ", " + energyContainer + "]";
    }
}
