package rain.fox.ogmr.api.recipe;

import rain.fox.ogmr.api.OGMRValues;
import rain.fox.ogmr.utils.Formatting;

import org.jetbrains.annotations.Nullable;

/**
 * 配方相关的静态工具方法。
 *
 * <p>
 * 从 GTM 的 {@code api.recipe.RecipeHelper} 里只摘出「读一条配方的电压/能耗/时长」这一小组
 * 纯函数（GTM 的同名类主体是内容匹配与 IO 搬运，那些要靠 capability 代理，本库不做）。
 * 机器层、模块化子系统、GUI tooltip 都从这里取数值，避免各自重复实现反查逻辑。
 */
public final class RecipeHelper {

    private RecipeHelper() {}

    /**
     * 这条配方属于哪一档电压。
     *
     * <p>
     * 按 {@code |eut|} 用 {@link OGMRValues#getTierByVoltage(long)} 反查；
     * {@code eut <= 0}（发电配方 / 不耗电）按「无电压要求」处理，返回 {@link OGMRValues#ULV}（0）。
     */
    public static int getRecipeEUtTier(@Nullable OGMRRecipe recipe) {
        if (recipe == null) return 0;
        long eut = recipe.getEut();
        if (eut <= 0) return 0;
        return OGMRValues.getTierByVoltage(eut);
    }

    /** 每 tick 能耗（EU/t）；{@code eut < 0} 表示发电量。 */
    public static long getRecipeEU(@Nullable OGMRRecipe recipe) {
        return recipe == null ? 0L : recipe.getEut();
    }

    /** 整条配方的总能耗（EU = EU/t × duration）；发电配方为负。 */
    public static long getTotalEU(@Nullable OGMRRecipe recipe) {
        return recipe == null ? 0L : recipe.getTotalEU();
    }

    /**
     * 把能耗与时长拼成一行可读文本，例如 {@code "1.23k EU/t · 10.00s"}。
     *
     * <p>
     * 数值一律走 {@link Formatting}（大数字缩写 + tick 转秒），保证机器面板、JEI 预览、
     * Jade 显示出来的是同一种写法。
     */
    public static String formatEU(@Nullable OGMRRecipe recipe) {
        if (recipe == null) return "";
        return "%s EU/t · %s".formatted(
                Formatting.formatNumber(recipe.getEut()),
                Formatting.formatTicks(recipe.getDuration()));
    }
}
