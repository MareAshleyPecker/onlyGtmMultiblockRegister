package rain.fox.ogmr.threading;

import java.util.List;

/**
 * 「可跑多线程配方」多方块控制器的标记接口。
 *
 * <p>
 * Java 重写自 GTEternalTime 的 {@code api/capability/IThreadedRecipeMachine.kt}，改动：
 * <ul>
 * <li>Kotlin 版继承 GTM 的 {@code IMultiController} 并自带 {@code threadHatch} / {@code threadCount}
 * 两个<b>默认实现</b>（现扫 {@code parts}、多仓取最大）；本版只留三个抽象方法，
 * 由 {@link ThreadedMultiblockMachine} 给出实现 —— 本库的 {@code IMultiController}
 * 不保证有 {@code parts} 这种访问器，与其在接口默认方法里赌它，不如把实现钉在基类里；</li>
 * <li>线程数从「最多的一台仓」改成<b>结构里所有仓求和</b>（{@link #getMaxThreads()}）——
 * 「装更多仓 = 更多线程」在结构层面更好解释（Kotlin 版取最大是为了堵「装两个仓白嫖」的漏洞，
 * 本库把这条约束交给图案（{@code maxGlobalLimited}）去表达）。</li>
 * </ul>
 *
 * <p>
 * 机器只要实现本接口（并把 {@code createRecipeLogic()} 重写成返回 {@link ThreadedRecipeLogic}）
 * 就获得「一台机器同时跑 N 条线程」的行为；现成的实现见 {@link ThreadedMultiblockMachine}。
 */
public interface IThreadedRecipeMachine {

    /**
     * 当前结构允许的最大线程数。
     *
     * <p>
     * 由结构里的线程仓求和得到（只算 {@link IThreadHatch#isActive()} 的仓），
     * 并且已经被 {@code OGMRConfig.maxThreadCount} 夹过一次；没装线程仓时退化为 1
     * （= 退化成「一台机器一条配方」的原版行为）。
     */
    int getMaxThreads();

    /** 结构里的线程仓（只读快照；结构没成型时为空表）。 */
    List<? extends IThreadHatch> getThreadHatches();

    /**
     * 本机的多线程配方逻辑。
     *
     * <p>
     * 正常情况下就是 {@code getRecipeLogic()} 自己（{@code createRecipeLogic()} 造出来的那个）；
     * 只有在「子类覆写了 {@code createRecipeLogic()} 却没返回 {@link ThreadedRecipeLogic}」
     * 这种接线错误下才返回 {@code null}。
     */
    ThreadedRecipeLogic getThreadedRecipeLogic();
}
