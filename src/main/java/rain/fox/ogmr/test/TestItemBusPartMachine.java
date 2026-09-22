package rain.fox.ogmr.test;

import rain.fox.ogmr.api.lang.OGMRLang;
import rain.fox.ogmr.api.machine.IMachineBlockEntity;
import rain.fox.ogmr.api.machine.multiblock.part.MultiblockPartMachine;
import rain.fox.ogmr.api.machine.trait.NotifiableItemStackHandler;

import lombok.Getter;

import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.items.ItemStackHandler;

import java.util.List;

/**
 * 测试用「物品总线」仓室 —— 结构里负责进料/出料的那一格。
 *
 * <p>
 * 它不是库的一部分，而是 {@code rain.fox.ogmr.test} 测试包里给玩家上手用的最小可用仓室：
 * <ul>
 * <li>9 格物品栏，做成 {@link NotifiableItemStackHandler}（一个 trait）——
 * 于是 {@code MetaMachine.getCapability} 会自动把它暴露成 Forge 的 {@code ITEM_HANDLER}，
 * <b>漏斗/管道/AE2 总线可以直接往里塞东西</b>，不用写任何胶水；</li>
 * <li>输入总线：外部只能插、机器可以取（{@link #machineExtract}）；</li>
 * <li>输出总线：外部只能取、机器可以插。</li>
 * </ul>
 *
 * <p>
 * 「机器自己能不能取/插」和「外部能不能取/插」是两件事，所以下面两组方法分开：
 * 外部走 {@code NotifiableItemStackHandler} 的 allow 开关，机器内部走
 * {@link #machineExtract} / {@link #machineInsert}（直接操作底层 handler，绕过开关）。
 */
public class TestItemBusPartMachine extends MultiblockPartMachine {

    /** 每台总线的格数。 */
    public static final int SLOTS = 9;

    /** 面板文案。 */
    public static final String LANG_ROLE_INPUT = "ogmr.test.bus.input";
    public static final String LANG_ROLE_OUTPUT = "ogmr.test.bus.output";
    public static final String LANG_CONTENT = "ogmr.test.bus.content";

    @Getter
    private final boolean input;
    private final NotifiableItemStackHandler inventory;

    /**
     * @param holder 机器宿主
     * @param input  true = 输入总线（外部只进），false = 输出总线（外部只出）
     */
    public TestItemBusPartMachine(IMachineBlockEntity holder, boolean input) {
        super(holder);
        this.input = input;
        // 构造 MachineTrait 时会自动 attachTraits(this)，所以不用手动挂
        this.inventory = new NotifiableItemStackHandler(this, SLOTS, input, !input);
    }

    /** 底层物品栏（只读用途 / 给 UI 绑槽位）。 */
    public ItemStackHandler getInventory() {
        return inventory.getHandler();
    }

    // ═══════════════ 机器侧访问（绕过 allow 开关） ═══════════════

    /**
     * 从本总线里抽走最多 {@code requested} 个指定物品（跨格累加）。
     *
     * @param simulate true = 只试算不真扣
     * @return 真正（会）抽到的量；不足时返回实际数量（可能为空）
     */
    public ItemStack machineExtract(ItemStack requested, boolean simulate) {
        if (requested.isEmpty()) return ItemStack.EMPTY;
        ItemStackHandler handler = inventory.getHandler();
        int remaining = requested.getCount();
        ItemStack result = ItemStack.EMPTY;
        for (int slot = 0; slot < handler.getSlots() && remaining > 0; slot++) {
            ItemStack inSlot = handler.getStackInSlot(slot);
            if (inSlot.isEmpty() || !ItemStack.isSameItemSameTags(inSlot, requested)) continue;
            int take = Math.min(remaining, inSlot.getCount());
            ItemStack got = handler.extractItem(slot, take, simulate);
            if (got.isEmpty()) continue;
            if (result.isEmpty()) {
                result = got.copy();
            } else {
                result.grow(got.getCount());
            }
            remaining -= got.getCount();
        }
        return result;
    }

    /** 机器往本总线里插（跨格找空位），返回没插进去的余量。 */
    public ItemStack machineInsert(ItemStack stack, boolean simulate) {
        if (stack.isEmpty()) return ItemStack.EMPTY;
        ItemStackHandler handler = inventory.getHandler();
        ItemStack remaining = stack;
        for (int slot = 0; slot < handler.getSlots() && !remaining.isEmpty(); slot++) {
            remaining = handler.insertItem(slot, remaining, simulate);
        }
        return remaining;
    }

    /** 本总线里现在有多少个指定物品（跨格累加）。 */
    public int countOf(ItemStack prototype) {
        if (prototype.isEmpty()) return 0;
        ItemStackHandler handler = inventory.getHandler();
        int total = 0;
        for (int slot = 0; slot < handler.getSlots(); slot++) {
            ItemStack inSlot = handler.getStackInSlot(slot);
            if (!inSlot.isEmpty() && ItemStack.isSameItemSameTags(inSlot, prototype)) {
                total += inSlot.getCount();
            }
        }
        return total;
    }

    // ═══════════════ 面板 ═══════════════

    @Override
    public void addDisplayText(List<Component> textList) {
        super.addDisplayText(textList);
        textList.add(Component.translatable(input ? LANG_ROLE_INPUT : LANG_ROLE_OUTPUT));

        int used = 0;
        int total = 0;
        for (int slot = 0; slot < inventory.getSlots(); slot++) {
            ItemStack stack = inventory.getStackInSlot(slot);
            if (!stack.isEmpty()) {
                used++;
                total += stack.getCount();
            }
        }
        textList.add(Component.translatable(LANG_CONTENT, used, inventory.getSlots(), total));
    }

    public static void initLang() {
        OGMRLang.add(LANG_ROLE_INPUT, "Test Item Input Bus", "测试物品输入总线");
        OGMRLang.add(LANG_ROLE_OUTPUT, "Test Item Output Bus", "测试物品输出总线");
        OGMRLang.add(LANG_CONTENT, "Items: %s/%s slots used, %s total",
                "物品：占用 %s/%s 格，共 %s 个");
    }

    @Override
    public String toString() {
        return "TestItemBusPartMachine[" + (input ? "input" : "output") + "]";
    }
}
