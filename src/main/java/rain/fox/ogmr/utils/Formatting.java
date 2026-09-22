package rain.fox.ogmr.utils;

/**
 * 数值格式化工具。
 *
 * <p>
 * 拆自 GTM 的 {@code FormattingUtil}，只保留本库用得到的那几个：大数字缩写（1.25k / 3.40M / 2.10G）
 * 与百分比。机器面板、仓室 tooltip、JEI 预览都要用它把 EU/FE/J 的数值显示得能看懂。
 */
public final class Formatting {

    private Formatting() {}

    private static final String[] SUFFIXES = { "", "k", "M", "G", "T", "P", "E" };

    /**
     * 把长整型缩写成人类可读形式：{@code 1250 → "1.25k"}、{@code 3400000 → "3.40M"}。
     *
     * <p>
     * 小于 1000 时原样输出（不带小数），避免「32 → 32.00」这种噪音。
     */
    public static String formatNumber(long value) {
        if (value == Long.MIN_VALUE) return "-" + formatNumber(Long.MAX_VALUE);
        if (value < 0) return "-" + formatNumber(-value);
        if (value < 1000L) return Long.toString(value);

        int idx = 0;
        double v = value;
        while (v >= 1000.0 && idx < SUFFIXES.length - 1) {
            v /= 1000.0;
            idx++;
        }
        return String.format("%.2f%s", v, SUFFIXES[idx]);
    }

    /** 浮点版（进度百分比之类）。 */
    public static String formatNumber(double value) {
        return formatNumber((long) value);
    }

    /** 百分比，保留一位小数：{@code 0.5 → "50.0%"}。 */
    public static String formatPercent(double ratio) {
        return String.format("%.1f%%", ratio * 100.0);
    }

    /** 把 tick 数说成「x.xx 秒」。 */
    public static String formatTicks(int ticks) {
        if (ticks < 20) return ticks + "t";
        return String.format("%.2fs", ticks / 20.0);
    }

    /**
     * 把注册名转成「看着像人话」的英文名：{@code "lv_item_input_bus" → "Lv Item Input Bus"}。
     *
     * <p>
     * 拆自 GTM 的 {@code FormattingUtil.toEnglishName}。用途是给 datagen 当<b>兜底</b>：
     * addon 没显式给英文名（{@code MachineDefinition#getLangValue()}）时，
     * 自动生成的 {@code block.<ns>.<name>} 至少不是裸键。
     *
     * <p>
     * ⚠️ 全大写缩写（LV/MV/AE2……）转不出来 —— {@code "lv_item_bus"} 会变成 {@code "Lv Item Bus"} 而不是
     * {@code "LV Item Bus"}。想要好看的显示名就显式给 {@code langValue}。
     */
    public static String toEnglishName(String registryName) {
        if (registryName == null || registryName.isEmpty()) return "";
        String[] words = registryName.toLowerCase(java.util.Locale.ROOT).split("[_/]");
        StringBuilder sb = new StringBuilder(registryName.length());
        for (String word : words) {
            if (word.isEmpty()) continue;
            if (!sb.isEmpty()) sb.append(' ');
            sb.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return sb.toString();
    }
}
