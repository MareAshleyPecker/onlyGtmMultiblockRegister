package rain.fox.ogmr.threading;

/**
 * 「线程仓」部件能力接口。
 *
 * <p>
 * Java 重写自 GTEternalTime 的 {@code api/capability/IThreadHatch.kt}，改动：
 * <ul>
 * <li>Kotlin 版把线程数拆成两个语义 —— {@code threadCount}（当前生效、玩家可下调）与
 * {@code maxThreads}（变体表给的上限）；本版接口只保留 {@link #getThreadCount()}（当前生效值）
 * 一个方法，「上限」那一份退化成实现类自己的字段（见 {@link ThreadHatchPartMachine#getMaxThreads()}），
 * 因为线程逻辑只关心「这台机器现在能开几条」；</li>
 * <li>新增 {@link #isActive()} —— Kotlin 版靠 {@code parts} 里能不能找到这个仓来判断，
 * 本版让仓自己回答「现在可用吗」（成型 / 通电 …）。结构里装着一个没成型的仓不应该给出线程数，
 * 而 {@code getThreadCount()} 只管「这个仓的规格是多少」。</li>
 * </ul>
 *
 * <p>
 * 语义：把 N 台同型机器<b>融合成一台</b>。线程仓往控制器上声明「这台机器最多能同时跑几条线程」，
 * 由 {@link ThreadedRecipeLogic} 读取：
 * <ul>
 * <li>每条线程<b>自己计时</b>（A 配方 200 tick、B 配方 20 tick 互不影响，各自到点各自结算）、<b>自己吃电</b>、
 * <b>自己有输入输出</b>；</li>
 * <li>线程数是<b>线程条数</b>而不是「配方种数」；</li>
 * <li>整机处理次数上限 ≈ 线程数 × 每条线程的并行倍数（{@link ThreadedRecipeLogic#parallelPerThread()}）。</li>
 * </ul>
 *
 * <p>
 * ⚠️ 与「并行仓」的关系：线程仓是<b>独立能力</b>（见
 * {@link ThreadHatchPartMachine#threadAbility()}），不复用并行能力，也不实现并行接口 ——
 * 复用会让两者在结构里互斥（同一能力通常被 {@code maxGlobalLimited(1)} 限定数量）。
 */
public interface IThreadHatch {

    /** 这个线程仓<b>当前生效</b>的线程数（= 这台机器最多能同时跑几条线程）。 */
    int getThreadCount();

    /**
     * 这个线程仓现在是否可用。
     *
     * <p>
     * 默认实现（{@link ThreadHatchPartMachine#isActive()}）取「仓室已挂到成型结构上」，
     * 想接电的 addon 可以覆写成「有电 && 成型」。
     */
    boolean isActive();
}
