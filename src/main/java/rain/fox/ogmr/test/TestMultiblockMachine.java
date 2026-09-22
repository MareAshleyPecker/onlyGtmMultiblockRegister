package rain.fox.ogmr.test;

import rain.fox.ogmr.api.energy.IEnergyContainer;
import rain.fox.ogmr.api.lang.OGMRLang;
import rain.fox.ogmr.api.machine.IMachineBlockEntity;
import rain.fox.ogmr.api.machine.trait.RecipeLogic;
import rain.fox.ogmr.api.OGMRValues;
import rain.fox.ogmr.api.recipe.Content;
import rain.fox.ogmr.api.recipe.OGMRRecipe;
import rain.fox.ogmr.threading.ThreadedMultiblockMachine;
import rain.fox.ogmr.threading.ThreadedRecipeLogic;
import rain.fox.ogmr.utils.Formatting;

import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * 测试用多线程多方块 —— {@code ogmr:test_multiblock}。
 *
 * <p>
 * 这是 {@code rain.fox.ogmr.test} 测试包里的「一台什么都验证得到」的机器，用来把库的几条主链路
 * 一次性跑通：
 * <ul>
 * <li><b>结构图案匹配</b>（3×3×3 铁块外壳 + 中心控制器，见 {@code TestRegistrations}）；</li>
 * <li><b>仓室注册与能力</b>：物品输入/输出总线（{@link TestItemBusPartMachine}）、
 * 能源仓（{@code EnergyHatchPartMachine}）、线程仓（{@code ThreadHatchPartMachine}）；</li>
 * <li><b>多线程配方</b>：继承 {@link ThreadedMultiblockMachine}，一台机器同时跑 N 条配方，
 * 线程数由结构里的线程仓求和；</li>
 * <li><b>能源系统</b>：从结构里的 {@link IEnergyContainer} 仓室扣电（FE），
 * 面板按仓室配置的单位显示；</li>
 * <li><b>真实搬运</b>：覆写 {@link TestRecipeLogic} 的四个容器钩子，
 * 配方真的从输入总线扣料、往输出总线放产物（不是空转）。</li>
 * </ul>
 *
 * <h3>怎么试</h3>
 * <pre>
 * /give @p ogmr:test_multiblock                 # 控制器
 * /give @p ogmr:lv_item_input_bus               # 物品输入总线
 * /give @p ogmr:lv_item_output_bus              # 物品输出总线
 * /give @p ogmr:lv_energy_hatch                 # 能源输入仓
 * /give @p ogmr:lv_thread_hatch                 # 线程仓（决定同时跑几条）
 * </pre>
 * 摆成 3×3×3 的铁块外壳（中间一层留空），把控制器放在正面的中心，其余位置随便插仓室，
 * 然后往里塞 {@code minecraft:iron_ingot}（配方：1 铁锭 → 1 金锭，100 tick）。
 */
public class TestMultiblockMachine extends ThreadedMultiblockMachine {

    /** 掉电/缺料时的原因键。 */
    public static final String LANG_NO_INPUT_BUS = "ogmr.test.multiblock.no_input_bus";
    public static final String LANG_NO_OUTPUT_BUS = "ogmr.test.multiblock.no_output_bus";
    public static final String LANG_NO_ENERGY_HATCH = "ogmr.test.multiblock.no_energy_hatch";
    public static final String LANG_THREADS = "ogmr.test.multiblock.threads";

    public TestMultiblockMachine(IMachineBlockEntity holder) {
        super(holder);
    }

    /**
     * 换成「会真的搬东西」的配方逻辑。
     *
     * <p>
     * ⚠️ 覆写时必须照抄「挂同步存储」那两行（{@link ThreadedMultiblockMachine#createRecipeLogic()} 已经写了），
     * 否则配方逻辑的 {@code @Persisted}/{@code @DescSynced} 字段不会存盘也不会同步。
     */
    @Override
    public RecipeLogic createRecipeLogic() {
        RecipeLogic logic = new TestRecipeLogic(this);
        if (holder.getRootStorage() != null) {
            holder.getRootStorage().attach(logic.getSyncStorage());
        }
        return logic;
    }

    // ═══════════════ 结构里的仓室查找 ═══════════════

    /** 结构里所有的物品总线。 */
    public List<TestItemBusPartMachine> itemBuses() {
        List<TestItemBusPartMachine> result = new ArrayList<>();
        for (var part : getParts()) {
            if (part instanceof TestItemBusPartMachine bus) result.add(bus);
        }
        return result;
    }

    /** 结构里所有的能源仓。 */
    public List<IEnergyContainer> energyHatches() {
        List<IEnergyContainer> result = new ArrayList<>();
        for (var part : getParts()) {
            if (part instanceof IEnergyContainer container) result.add(container);
        }
        return result;
    }

    public long storedEnergy() {
        long sum = 0;
        for (IEnergyContainer container : energyHatches()) sum += container.getEnergyStored();
        return sum;
    }

    // ═══════════════ 面板 ═══════════════

    @Override
    public void addDisplayText(List<Component> textList) {
        super.addDisplayText(textList);

        long inputBuses = itemBuses().stream().filter(TestItemBusPartMachine::isInput).count();
        long outputBuses = itemBuses().stream().filter(bus -> !bus.isInput()).count();
        if (inputBuses == 0) textList.add(Component.translatable(LANG_NO_INPUT_BUS));
        if (outputBuses == 0) textList.add(Component.translatable(LANG_NO_OUTPUT_BUS));

        List<IEnergyContainer> hatches = energyHatches();
        if (hatches.isEmpty()) {
            textList.add(Component.translatable(LANG_NO_ENERGY_HATCH));
        } else {
            textList.add(Component.translatable(LANG_THREADS,
                    Formatting.formatNumber(getMaxThreads()),
                    rain.fox.ogmr.api.energy.EnergyConversion.format(storedEnergy())));
        }
    }

    public static void initLang() {
        OGMRLang.add(LANG_NO_INPUT_BUS, "Missing item input bus", "缺少物品输入总线");
        OGMRLang.add(LANG_NO_OUTPUT_BUS, "Missing item output bus", "缺少物品输出总线");
        OGMRLang.add(LANG_NO_ENERGY_HATCH, "Missing energy hatch — running without power cost",
                "缺少能源仓 —— 不耗电运行");
        OGMRLang.add(LANG_THREADS, "Threads %s · Stored %s", "线程 %s · 已存 %s");
    }

    // ═══════════════ 配方逻辑：真的搬东西 ═══════════════

    /**
     * 测试机的配方逻辑：把「结构里的物品总线」接到配方逻辑的四个容器钩子上。
     *
     * <p>
     * 基类 {@link ThreadedRecipeLogic} 的默认实现是「输入永远够、输出永远放得下、不搬运」，
     * 那样机器会空转（面板上进度条会走，但物品不见少）—— 这里把它接成真实的：
     * <ul>
     * <li>{@link #hasInputs} —— 输入总线里凑得齐这条配方的物品输入吗；</li>
     * <li>{@link #consumeInputs} —— 真扣（跨格累加扣，够才扣，先用试算避免扣一半）；</li>
     * <li>{@link #hasOutputSpace} —— 输出总线塞得下产物吗（用模拟插入判断）；</li>
     * <li>{@link #outputProducts} —— 真放。</li>
     * </ul>
     * 电走基类已有的逻辑（扫结构里的 {@link IEnergyContainer}；一个能源仓都没有 = 不耗电）。
     */
    public static class TestRecipeLogic extends ThreadedRecipeLogic {

        private final TestMultiblockMachine multi;

        public TestRecipeLogic(TestMultiblockMachine multi) {
            super(multi);
            this.multi = multi;
        }

        private List<TestItemBusPartMachine> inputs() {
            return multi.itemBuses().stream().filter(TestItemBusPartMachine::isInput).toList();
        }

        private List<TestItemBusPartMachine> outputs() {
            return multi.itemBuses().stream().filter(bus -> !bus.isInput()).toList();
        }

        @Override
        protected boolean hasInputs(OGMRRecipe recipe) {
            List<Content> itemInputs = recipe.getItemInputs();
            if (itemInputs.isEmpty()) return true;
            List<TestItemBusPartMachine> buses = inputs();
            if (buses.isEmpty()) return false;

            for (Content content : itemInputs) {
                ItemStack prototype = content.representativeItem();
                if (prototype.isEmpty()) continue;
                int need = Math.max(1, content.count());
                int have = 0;
                for (TestItemBusPartMachine bus : buses) {
                    have += bus.countOf(prototype);
                    if (have >= need) break;
                }
                if (have < need) return false;
            }
            return true;
        }

        @Override
        protected boolean consumeInputs(OGMRRecipe recipe) {
            List<Content> itemInputs = recipe.getItemInputs();
            if (itemInputs.isEmpty()) return true;
            List<TestItemBusPartMachine> buses = inputs();
            if (buses.isEmpty()) return false;

            // 机器侧抽料必须走 machineExtract：NotifiableItemStackHandler 的 allow 开关是给「外部」用的，
            // 输入总线对外只进不出，直接调 inventory.extractItem 会永远返回空。
            for (Content content : itemInputs) {
                ItemStack prototype = content.representativeItem();
                if (prototype.isEmpty()) continue;
                int need = Math.max(1, content.count());
                int got = 0;
                for (TestItemBusPartMachine bus : buses) {
                    if (got >= need) break;
                    ItemStack request = prototype.copyWithCount(need - got);
                    ItemStack extracted = bus.machineExtract(request, false);
                    got += extracted.getCount();
                }
                if (got < need) return false;
            }
            return true;
        }

        @Override
        protected boolean hasOutputSpace(OGMRRecipe recipe) {
            List<Content> itemOutputs = recipe.getItemOutputs();
            if (itemOutputs.isEmpty()) return true;
            List<TestItemBusPartMachine> buses = outputs();
            if (buses.isEmpty()) return false;

            for (Content content : itemOutputs) {
                ItemStack prototype = content.representativeItem();
                if (prototype.isEmpty()) continue;
                int need = Math.max(1, content.count());
                int room = 0;
                for (TestItemBusPartMachine bus : buses) {
                    ItemStack leftover = bus.machineInsert(prototype.copyWithCount(need), true);
                    room += need - leftover.getCount();
                    if (room >= need) break;
                }
                if (room < need) return false;
            }
            return true;
        }

        @Override
        protected void outputProducts(OGMRRecipe recipe) {
            List<TestItemBusPartMachine> buses = outputs();
            if (buses.isEmpty()) return;

            for (Content content : recipe.getItemOutputs()) {
                ItemStack prototype = content.representativeItem();
                if (prototype.isEmpty()) continue;
                // 概率产出：chance() 是 0~1，这里简单地按概率丢弃（测试机不做「必出/加成」那套）
                if (!content.isGuaranteed() && multi.getLevel() != null
                        && multi.getLevel().random.nextFloat() > content.chance()) {
                    continue;
                }
                ItemStack remaining = prototype.copyWithCount(Math.max(1, content.count()));
                for (TestItemBusPartMachine bus : buses) {
                    if (remaining.isEmpty()) break;
                    remaining = bus.machineInsert(remaining, false);
                }
            }
        }

        @Override
        public void addDisplayText(List<Component> textList) {
            super.addDisplayText(textList);
            textList.add(Component.literal(OGMRValues.tierNameRaw(multi.getTier())
                    + " · " + multi.itemBuses().size() + " buses"));
        }
    }
}
