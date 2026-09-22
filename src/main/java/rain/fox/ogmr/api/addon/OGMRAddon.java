package rain.fox.ogmr.api.addon;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 把一个类标记成本库的 addon 入口。
 *
 * <p>
 * 拆自 GTM 的 {@code @GTAddon}，但语义更完整：本库的扫描器会把所有带这个注解的类实例化，
 * 按 {@link #priority()} 排序，然后在固定的阶段回调 {@link IOGMRAddon} 里对应的方法。
 *
 * <pre>{@code
 * @OGMRAddon(modId = "mymod")
 * public class MyAddon extends AbstractOGMRAddon {
 *     public MyAddon() { super("mymod"); }
 *
 *     @Override public void registerMultiblocks(OGMRRegisterEvent.RL<MultiblockMachineDefinition> event) {
 *         MY_MULTIS = OGMRMultiblocks.create(this, "my_multi", MyMultiMachine::new)
 *                 .recipeType(MyRecipeTypes.X)
 *                 .pattern(def -> FactoryBlockPattern.start()...)
 *                 .register();
 *     }
 * }
 * }</pre>
 *
 * <p>
 * 被注解的类 <b>必须</b> 有一个公开的无参构造函数。
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface OGMRAddon {

    /**
     * addon 的 mod id。留空时由 {@link IOGMRAddon#addonModId()} 决定。
     */
    String modId() default "";

    /**
     * 初始化优先级，<b>数字小的先跑</b>（默认 1000）。
     *
     * <p>
     * 只有在「B 依赖 A 注册出来的东西」时才需要显式指定；一般 addon 之间不应该有依赖。
     */
    int priority() default 1000;
}
