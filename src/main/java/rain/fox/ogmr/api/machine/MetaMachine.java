package rain.fox.ogmr.api.machine;

import com.lowdragmc.lowdraglib.syncdata.IManaged;
import com.lowdragmc.lowdraglib.syncdata.IManagedStorage;
import com.lowdragmc.lowdraglib.syncdata.ITagSerializable;
import com.lowdragmc.lowdraglib.syncdata.annotation.DescSynced;
import com.lowdragmc.lowdraglib.syncdata.annotation.Persisted;
import com.lowdragmc.lowdraglib.syncdata.field.FieldManagedStorage;
import com.lowdragmc.lowdraglib.syncdata.field.ManagedFieldHolder;
import com.lowdragmc.lowdraglib.gui.modular.IUIHolder;
import com.lowdragmc.lowdraglib.gui.modular.ModularUI;

import lombok.Getter;

import rain.fox.ogmr.api.block.MachineBlock;
import rain.fox.ogmr.api.gui.MachineUI;
import rain.fox.ogmr.api.gui.MachineUIWidget;
import rain.fox.ogmr.api.gui.factory.MachineUIFactory;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraftforge.client.model.data.ModelData;
import net.minecraftforge.common.capabilities.Capability;

import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 本文件是从 GTM 的 {@code com.gregtechceu.gtceu.api.machine.MetaMachine} 精简拆出来的。
 *
 * <p>
 * 「机器」的抽象基类：它<b>不是</b> BlockEntity，而是挂在 {@link IMachineBlockEntity} 上的一层逻辑对象。
 * 一个机器 = 一个 BE（负责方块、同步、持久化）+ 一个 MetaMachine（负责行为）+ 若干 {@link MachineTrait}
 * （负责物品/流体/能量/配方等能力）。
 *
 * <p>
 * 被砍掉的东西（相对 GTM）：覆盖板（cover）、工具交互（扳手/螺丝刀/软锤）、染色渲染状态、
 * AE2 能力、所有权（owner）、配方能力代理、UI（Fancy UI / GUI 纹理）。
 * 保留下来的只有「运行时核心」：生命周期、tick 订阅、同步存储、trait 容器、tooltip、持久化转发。
 *
 * <p>
 * <b>子类怎么写 fieldHolder</b>（沿用 GTM 约定）：
 *
 * <pre>{@code
 * public class MyMachine extends MetaMachine {
 *     public static final ManagedFieldHolder MANAGED_FIELD_HOLDER =
 *             MetaMachine.holder(MyMachine.class, MetaMachine.MANAGED_FIELD_HOLDER);
 *
 *     @Override
 *     public ManagedFieldHolder getFieldHolder() {
 *         return MANAGED_FIELD_HOLDER;
 *     }
 * }
 * }</pre>
 */
public abstract class MetaMachine implements IManaged, ITagSerializable<CompoundTag>, IUIHolder {

    /** 基类的字段持有者；子类把它作为 parent 传进 {@link #holder(Class, ManagedFieldHolder)}。 */
    public static final ManagedFieldHolder MANAGED_FIELD_HOLDER = new ManagedFieldHolder(MetaMachine.class);

    /**
     * class → ManagedFieldHolder 的缓存。
     *
     * <p>
     * 每个类只需要构建一次 holder（构建时要反射扫字段，很贵），所以这里按 class 缓存，
     * 而不是每个机器实例 new 一个。
     */
    private static final Map<Class<? extends MetaMachine>, ManagedFieldHolder> FIELD_HOLDER_CACHE = new ConcurrentHashMap<>();

    /**
     * 静态工具：取（或首次构建）某个机器类的字段持有者。
     *
     * <p>
     * 用法固定为「自己的 class + 父类的 holder」：
     * {@code MetaMachine.holder(MyMachine.class, MetaMachine.MANAGED_FIELD_HOLDER)}。
     * 同一个 class 重复调用只会构建一次，线程安全。
     *
     * @param clazz  机器类
     * @param parent 父类的字段持有者（把父类字段合并进来）
     */
    public static ManagedFieldHolder holder(Class<? extends MetaMachine> clazz, ManagedFieldHolder parent) {
        return FIELD_HOLDER_CACHE.computeIfAbsent(clazz, c -> new ManagedFieldHolder(c, parent));
    }

    // ═══════════════ 字段 ═══════════════

    /** 宿主 BE。 */
    public final IMachineBlockEntity holder;

    /** 本机器自己的同步存储（@Persisted / @DescSynced 字段都在里面）。 */
    private final FieldManagedStorage syncStorage = new FieldManagedStorage(this);

    /** 染色；-1 = 未染色。 */
    @Getter
    @Persisted
    @DescSynced
    private int paintingColor = -1;

    /** 已挂载的 trait（活列表，请勿直接修改）。 */
    @Getter
    private final List<MachineTrait> traits = new ArrayList<>();

    /** 正在生效的服务端 tick 订阅。 */
    private final List<TickableSubscription> serverTicks = new ArrayList<>();

    /** 本 tick 内新提交的订阅（下一 tick 生效，避免遍历时改列表）。 */
    private final List<TickableSubscription> waitingToAdd = new ArrayList<>();

    protected MetaMachine(IMachineBlockEntity holder) {
        this.holder = holder;
        // 把自己的同步存储挂到 BE 的根存储上：这样 @Persisted / @DescSynced 字段才会被
        // MachineBlockEntity 的存盘与每 tick 同步真正扫到。
        if (holder.getRootStorage() != null) {
            holder.getRootStorage().attach(syncStorage);
        }
    }

    // ═══════════════ 同步存储 ═══════════════

    @Override
    public ManagedFieldHolder getFieldHolder() {
        return MANAGED_FIELD_HOLDER;
    }

    @Override
    public IManagedStorage getSyncStorage() {
        return syncStorage;
    }

    // ═══════════════ 环境查询 ═══════════════

    /** 所在世界；BE 还没进世界时为 null。 */
    public Level getLevel() {
        return holder.level();
    }

    public BlockPos getPos() {
        return holder.pos();
    }

    /** 是否运行在客户端（含「还没进世界」的初始阶段）。 */
    public boolean isRemote() {
        Level level = getLevel();
        return level == null || level.isClientSide;
    }

    /** BE 是否已经失效（被移除）。 */
    public boolean isInValid() {
        return holder.self().isRemoved();
    }

    public MachineDefinition getDefinition() {
        return holder.getDefinition();
    }

    /** 机器等级；默认转发 definition 的等级。 */
    public int getTier() {
        return getDefinition().getTier();
    }

    /** 便捷方法：取某个坐标上的机器（没有则 null）。 */
    public static MetaMachine getMachine(BlockGetter level, BlockPos pos) {
        if (level != null && level.getBlockEntity(pos) instanceof IMachineBlockEntity machineBlockEntity) {
            return machineBlockEntity.getMetaMachine();
        }
        return null;
    }

    // ═══════════════ 染色 ═══════════════

    public void setPaintingColor(int color) {
        if (this.paintingColor == color) return;
        this.paintingColor = color;
        // paintingColor 带 @DescSynced/@Persisted，LDLib 的字段回调会在下一次
        // defaultServerTick 时自动把它标记成脏并同步，这里不用手工 markDirty。
    }

    public int getDefaultPaintingColor() {
        return getDefinition().getDefaultPaintingColor();
    }

    // ═══════════════ 生命周期 ═══════════════

    /**
     * 字段（@Persisted）变脏时由 LDLib 回调。
     *
     * <p>
     * TODO(ogmr): GTM 的等价写法是 {@code markDirty("")}（即 LDLib 的 {@code IManaged#markDirty(String)}），
     * 但 LDLib 要求 name 必须是「本类已登记的被同步字段名」，传空串会在运行时抛
     * {@code IllegalArgumentException("No sync field with name ")}，所以这里改成直接标记 BE 脏。
     */
    @Override
    public void onChanged() {
        Level level = getLevel();
        if (level != null && !level.isClientSide && level.getServer() != null) {
            // 字段回调可能发生在别的线程（异步同步线程），回主线程再动 BE。
            level.getServer().execute(this::markDirty);
        } else if (level == null) {
            markDirty();
        }
    }

    /** 标记 BE 脏（等价于原版 {@code BlockEntity#setChanged}）。 */
    public void markDirty() {
        holder.self().setChanged();
    }

    // ═══════════════ 方块状态（渲染用） ═══════════════

    /**
     * 把机器的工作/成型状态写进方块状态 —— 覆盖层贴图就是按这两个属性选模型的
     * （见 {@link MachineBlock#ACTIVE} / {@link MachineBlock#FORMED}）。
     *
     * <p>
     * 属性不存在（例如没建出该属性的方块）时什么都不做，不抛异常；值没变时也直接返回，
     * 免得每 tick 都刷一次方块。
     */
    public void setBlockStateBoolean(BooleanProperty property, boolean value) {
        if (property == null) return;
        Level level = getLevel();
        if (level == null || level.isClientSide) return;
        BlockPos pos = getPos();
        BlockState state = level.getBlockState(pos);
        if (!state.hasProperty(property) || state.getValue(property) == value) return;
        // flag 3 = 通知客户端 + 触发邻居更新的常规更新
        level.setBlock(pos, state.setValue(property, value), Block.UPDATE_ALL);
    }

    /** 正在工作（覆盖层里的「发光层」按它显示）。 */
    public void setActiveState(boolean active) {
        setBlockStateBoolean(MachineBlock.ACTIVE, active);
    }

    /** 多方块是否成型（覆盖层里的「成型层」按它显示）。 */
    public void setFormedState(boolean formed) {
        setBlockStateBoolean(MachineBlock.FORMED, formed);
    }

    /** BE 载入世界时调用：转发给所有 trait。 */
    public void onLoad() {
        for (MachineTrait trait : traits) {
            trait.onMachineLoad();
        }
    }

    /** BE 离开世界时调用：转发给所有 trait，并清空 tick 订阅。 */
    public void onUnload() {
        for (MachineTrait trait : traits) {
            trait.onMachineUnLoad();
        }
        for (TickableSubscription serverTick : serverTicks) {
            serverTick.unsubscribe();
        }
        serverTicks.clear();
        synchronized (waitingToAdd) {
            waitingToAdd.clear();
        }
    }

    /** 相邻方块变化。 */
    public void onNeighborChanged(Block block, BlockPos fromPos, boolean isMoving) {}

    /** 机器被旋转（朝向变化）。 */
    public void onRotated(Direction oldFacing, Direction newFacing) {}

    /**
     * 服务端每 tick。
     *
     * <p>
     * 默认实现只驱动 tick 订阅；<b>子类覆写时请务必先调用 {@code super.serverTick()}</b>，
     * 否则 {@link #subscribeServerTick} 注册的任务会全部失效。
     */
    public void serverTick() {
        executeTick();
    }

    /**
     * 客户端每 tick；默认空实现。
     *
     * <p>
     * 注意：这里刻意<b>不加</b> {@code @OnlyIn(Dist.CLIENT)} —— 它会被 MachineBlock 的 ticker
     * 在公共代码里引用，加注解在服务端有被裁剪/链接失败的风险。子类覆写时也要保证方法体在服务端安全。
     */
    public void clientTick() {}

    /** 触发本 tick 已登记的订阅，并清理已取消的。 */
    private void executeTick() {
        synchronized (waitingToAdd) {
            if (!waitingToAdd.isEmpty()) {
                serverTicks.addAll(waitingToAdd);
                waitingToAdd.clear();
            }
        }

        var iter = serverTicks.iterator();
        while (iter.hasNext()) {
            TickableSubscription tickable = iter.next();
            if (tickable.isAlive()) {
                tickable.run();
            }
            if (isInValid()) break;
            if (!tickable.isAlive()) {
                iter.remove();
            }
        }
    }

    // ═══════════════ tick 订阅 ═══════════════

    /**
     * 幂等续订：{@code last} 还活着就复用，否则新建一个。
     *
     * <p>
     * 这是给 RecipeLogic 之类「跟着状态反复重订」的代码用的标准写法：
     * {@code subscription = subscribeServerTick(subscription, this::serverTick);}
     */
    public TickableSubscription subscribeServerTick(TickableSubscription last, Runnable runnable) {
        if (last == null || !last.isAlive()) {
            return subscribeServerTick(runnable);
        }
        return last;
    }

    /**
     * 新建一个服务端 tick 订阅。
     *
     * @return 新订阅；<b>客户端返回 null</b>（客户端没有服务端 tick），调用方需要容忍 null
     */
    public TickableSubscription subscribeServerTick(Runnable runnable) {
        TickableSubscription subscription = new TickableSubscription(runnable);
        if (!isRemote()) {
            synchronized (waitingToAdd) {
                waitingToAdd.add(subscription);
            }
            return subscription;
        }
        return null;
    }

    /** 取消订阅（null 安全）。 */
    public void unsubscribe(TickableSubscription subscription) {
        if (subscription != null) {
            subscription.unsubscribe();
        }
    }

    /** 请求渲染刷新。 */
    public void scheduleRenderUpdate() {
        holder.scheduleRenderUpdate();
    }

    // ═══════════════ trait 容器 ═══════════════

    /**
     * 挂载 trait。
     *
     * <p>
     * 注意：trait 只应该在机器构造期间创建（trait 构造器里会自动调用本方法），
     * 运行期不要动态增删。
     */
    public void attachTraits(MachineTrait... traits) {
        Collections.addAll(this.traits, traits);
    }

    /** 取第一个匹配类型的 trait；没有则返回 null。 */
    public <T extends MachineTrait> T getTrait(Class<T> type) {
        for (MachineTrait trait : traits) {
            if (type.isInstance(trait)) {
                return type.cast(trait);
            }
        }
        return null;
    }

    // ═══════════════ 能力分发 ═══════════════

    /**
     * Forge 能力查询 —— 让机器能被漏斗、管道、AE2、能量线缆之类的外部东西访问。
     *
     * <p>
     * 默认实现：<b>依次问每个 trait</b>，谁先给出非 null 就用谁（见
     * {@link MachineTrait#getCapability})。所以一块物品栏只要是个 trait
     * （例如 {@code NotifiableItemStackHandler}）就自动可被外部访问，机器类不用写一行胶水。
     *
     * <p>
     * 需要更精细的朝向/权限控制（比如「只有正面能插入」）时覆写本方法。
     *
     * @param capability 要查的能力
     * @param side       访问方向；{@code null} 表示「不指定方向」（多数机器不区分）
     */
    @Nullable
    public <T> T getCapability(Capability<T> capability, @Nullable Direction side) {
        for (MachineTrait trait : traits) {
            T value = trait.getCapability(capability, side);
            if (value != null) return value;
        }
        return null;
    }

    // ═══════════════ 展示 / 模型 ═══════════════

    /** GUI / Jade 用的文本行；默认空，子类覆写。 */
    public void addDisplayText(List<Component> textList) {}

    // ═══════════════ UI ═══════════════

    /**
     * 右键本机器时要不要打开界面。
     *
     * <p>
     * 默认「有界面就开」——{@link MachineDefinition#getMachineUI()} 由 builder 保证非空
     * （没显式配就给一个零配置的默认界面），所以任何机器右键都有反应。
     * 想让某些情况（比如手里拿着扳手）不弹界面，覆写它返回 false。
     */
    public boolean shouldOpenUI(Player player, InteractionHand hand, BlockHitResult hit) {
        return getDefinition().hasUI();
    }

    /**
     * 尝试打开界面（{@code MachineBlock#use} 调它）。
     *
     * <p>
     * 打开动作只在<b>服务端</b>发起（{@link MachineUIFactory} 会把「打开哪个 BE 的界面」同步给客户端）；
     * 客户端这一侧返回 {@link InteractionResult#sidedSuccess} 让挥手动画正常走。
     */
    public InteractionResult tryToOpenUI(Player player, InteractionHand hand, BlockHitResult hit) {
        if (!shouldOpenUI(player, hand, hit)) {
            return InteractionResult.PASS;
        }
        if (player instanceof ServerPlayer serverPlayer) {
            MachineUIFactory.INSTANCE.openUI(this, serverPlayer);
        }
        return InteractionResult.sidedSuccess(isRemote());
    }

    /**
     * 造这个机器的界面（LDLib 的 {@link ModularUI}）；没有界面时返回 {@code null}。
     *
     * <p>
     * 布局来自 {@link MachineDefinition#getMachineUI()}：默认那张零配置界面 = 标题 + 玩家背包
     * + 按机器实际暴露的物品/流体仓储自动摆的槽位（见 {@link MachineUI#createDefault}）。
     * 想自定义就覆写本方法，或者注册机器时用 {@code builder.ui(MachineUI...)}。
     */
    @Override
    @Nullable
    public ModularUI createUI(Player player) {
        MachineUI ui = getDefinition().getMachineUI();
        if (ui == null) {
            return null;
        }
        MachineUIWidget widget = new MachineUIWidget(this, ui);
        return new ModularUI(widget.getFullWidth(), widget.getFullHeight(), this, player).widget(widget);
    }

    // ── LDLib 的 IUIHolder 契约 ──

    @Override
    public boolean isInvalid() {
        return isInValid();
    }

    @Override
    public void markAsDirty() {
        markDirty();
    }

    /** 供模型数据（Forge ModelData）使用；默认转发给所有 trait。 */
    public void updateModelData(ModelData.Builder builder) {
        for (MachineTrait trait : traits) {
            trait.updateModelData(builder);
        }
    }

    // ═══════════════ 持久化 ═══════════════

    /**
     * 自定义持久化（存不进 LDLib 字段系统的数据，例如可选依赖相关的东西）。
     *
     * <p>
     * 默认转发给所有 trait。
     *
     * @param forDrop true = 这是「被破坏掉落成物品」时保存
     */
    public void saveCustomPersistedData(CompoundTag tag, boolean forDrop) {
        for (MachineTrait trait : traits) {
            trait.saveCustomPersistedData(tag, forDrop);
        }
    }

    public void loadCustomPersistedData(CompoundTag tag) {
        for (MachineTrait trait : traits) {
            trait.loadCustomPersistedData(tag);
        }
    }

    /**
     * 序列化成 NBT。
     *
     * <p>
     * TODO(ogmr): 本精简版只走「自定义持久化」；{@code @Persisted} 字段由
     * {@code MachineBlockEntity#saveAdditional → saveManagedPersistentData()} 负责，不经过这里。
     * 如果以后要把 MetaMachine 当成嵌套对象塞进别的 NBT 字段里，需要在这里补上完整字段读写。
     */
    @Override
    public CompoundTag serializeNBT() {
        CompoundTag tag = new CompoundTag();
        saveCustomPersistedData(tag, false);
        return tag;
    }

    @Override
    public void deserializeNBT(CompoundTag tag) {
        loadCustomPersistedData(tag);
    }

    // ═══════════════ 物品 ═══════════════

    /** 由 definition 生成的代表物品（掉落 / GUI 里显示用）。 */
    public ItemStack getItemStack() {
        return getDefinition().asStack();
    }
}
