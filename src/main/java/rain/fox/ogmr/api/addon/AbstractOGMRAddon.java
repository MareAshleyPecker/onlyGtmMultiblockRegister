package rain.fox.ogmr.api.addon;

import rain.fox.ogmr.api.registry.MachineRegistrar;

/**
 * addon 的推荐基类 —— 把「注册器」这件事顺手管掉。
 *
 * <p>
 * 绝大多数 addon 只需要一个 {@link MachineRegistrar}。继承本类之后：
 * <pre>{@code
 * @OGMRAddon
 * public class MyAddon extends AbstractOGMRAddon {
 *     public MyAddon() { super("mymod"); }
 *
 *     @Override
 *     public void registerMultiblocks(OGMRRegisterEvent.RL<MultiblockMachineDefinition> event) {
 *         MyMultiblocks.init();
 *     }
 * }
 *
 * // 任何地方都能拿到注册器：
 * MultiblockMachineDefinition def = MyAddon.REGISTRAR.multiblock("foundry", FoundryMachine::new)...register();
 * }</pre>
 *
 * <p>
 * 不想继承也行 —— {@link IOGMRAddon} 是接口，机器注册器可以自己 new。
 */
public abstract class AbstractOGMRAddon implements IOGMRAddon {

    private final String modId;
    private final int priority;
    private MachineRegistrar registrar;

    protected AbstractOGMRAddon(String modId) {
        this(modId, 1000);
    }

    protected AbstractOGMRAddon(String modId, int priority) {
        this.modId = modId;
        this.priority = priority;
    }

    @Override
    public final String addonModId() {
        return modId;
    }

    @Override
    public int priority() {
        return priority;
    }

    /**
     * 本 addon 的机器注册器（懒创建）。
     *
     * <p>
     * 第一次调用时创建并把它的三个 {@code DeferredRegister} 挂到 mod 事件总线上，
     * 所以 {@link #initialize()} 里调用一次即可，之后任何阶段都能直接用。
     */
    public MachineRegistrar registrar() {
        if (registrar == null) {
            registrar = new MachineRegistrar(modId);
        }
        return registrar;
    }

    /**
     * 把<b>你自己的</b>注册器挂到 mod 事件总线上。
     *
     * <p>
     * ⚠️ addon 通常会在自己的类里放一个静态注册器
     * （{@code public static final MachineRegistrar REGISTRAR = new MachineRegistrar(modId);}）。
     * 那种写法下<b>必须</b>用本方法把它挂上去，别依赖 {@link #registrar()} ——
     * 后者会 new 一个<b>不同的</b>实例，于是「附着的是 A、注册用的是 B」：
     * 机器进了本库的注册表，但方块从没进 Forge 注册表。
     * 这种错误编译期毫无提示，进游戏 / 跑 datagen 才会炸成
     * {@code NullPointerException: Registry Object not present}。
     *
     * <pre>{@code
     * @Override public void initialize() {
     *     attach(MyRegistrar.REGISTRAR);   // 而不是 super.initialize()
     * }
     * }</pre>
     */
    protected void attach(MachineRegistrar registrar) {
        registrar.attach(AddonBootstrap.modBus());
    }

    /**
     * 默认实现：把 {@link #registrar()} 挂上 mod 事件总线。
     *
     * <p>
     * 只有「注册器就是 {@link #registrar()} 返回的那一个」时才能这么用；
     * 用自定义静态注册器的请改调 {@link #attach(MachineRegistrar)}。
     */
    @Override
    public void initialize() {
        attach(registrar());
    }
}
