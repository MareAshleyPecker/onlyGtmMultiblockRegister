package rain.fox.ogmr.api.machine;

import lombok.Getter;

/**
 * 本文件是从 GTM 的 {@code com.gregtechceu.gtceu.api.machine.TickableSubscription} 精简拆出来的。
 *
 * <p>
 * 一个「可取消的服务端 tick 订阅」。{@link MetaMachine} 内部维护订阅列表，每个服务端 tick 触发一次；
 * 被 {@link #unsubscribe()} 之后不会再执行，并在下一次 tick 时自动从列表里摘掉。
 *
 * <p>
 * 典型用法（和 GTM 一致）：
 *
 * <pre>{@code
 * private TickableSubscription subscription;
 *
 * @Override
 * public void onLoad() {
 *     super.onLoad();
 *     subscription = subscribeServerTick(subscription, this::tick);
 * }
 * }</pre>
 *
 * 注意 {@code subscribeServerTick(last, runnable)} 的语义是 <b>幂等续订</b>：{@code last} 还活着就直接复用，
 * 不会重复订阅。
 */
public class TickableSubscription {

    /** 每 tick 执行的逻辑。 */
    private final Runnable runnable;

    /** 是否仍然有效（false = 已取消）。 */
    @Getter
    private boolean alive;

    public TickableSubscription(Runnable runnable) {
        this.runnable = runnable;
        this.alive = true;
    }

    /**
     * GTM 里这个名字叫 {@code isStillSubscribed()}，语义完全相同。
     *
     * <p>
     * 保留它是为了让「直接照抄 GTM 的 RecipeLogic / 机器子类」也能编译过 —— 那些代码普遍写的是
     * {@code subscription.isStillSubscribed()}。
     */
    public boolean isStillSubscribed() {
        return alive;
    }

    /** 取消订阅。调用后本 tick 内不会再执行，且会被 {@link MetaMachine} 从订阅列表里移除。 */
    public void unsubscribe() {
        this.alive = false;
    }

    /** 由 {@link MetaMachine} 的 tick 逻辑调用；也可以手动调用（已取消时是空操作）。 */
    public void run() {
        if (alive) {
            runnable.run();
        }
    }
}
