package rain.fox.ogmr.api.energy;

/**
 * 一个换算系数的「精确整数比」表示（包内工具，不对外）。
 *
 * <p>
 * 目的是让整数换算全程不经过 {@code double}：{@code 2.0 = 2/1}、{@code 2.5 = 5/2}、
 * {@code 0.4 = 2/5} 都能无损写成整数比，于是 {@code amount × num ÷ den} 可以直接用
 * {@code Math.multiplyExact} + {@code Math.floorDiv} 算，大数值也不掉精度。
 *
 * <p>
 * 玩家把系数改成 {@code 1/3} 这种无限小数时 {@link #of(double)} 会返回「不可精确表示」，
 * 换算自动退回 {@code double} 路径（见 {@link EnergyConversion}）。
 *
 * <p>
 * 实例由 {@link EnergyTypes} 按能量种类缓存，系数被覆盖时缓存会失效重建。
 *
 * @param num 分子；{@code <= 0} 表示这个系数无法精确表示
 * @param den 分母；{@code <= 0} 表示这个系数无法精确表示
 */
record EnergyRatio(long num, long den) {

    /** 无法精确表示时的占位（走 double 路径）。 */
    static final EnergyRatio INEXACT = new EnergyRatio(0L, 0L);

    /**
     * 试着把 {@code value} 表示成分母 ≤ 64 的整数比；表示不出来就返回 {@link #INEXACT}。
     */
    static EnergyRatio of(double value) {
        if (!Double.isFinite(value) || value <= 0.0) return INEXACT;
        for (long d = 1L; d <= 64L; d++) {
            double scaled = value * (double) d;
            double rounded = Math.rint(scaled);
            // 9.0e15：double 还能精确表示整数的上限附近，超过就不值得走整数路径了
            if (rounded > 0.0 && rounded <= 9.0e15
                    && Math.abs(scaled - rounded) <= 1e-9 * Math.max(1.0, Math.abs(scaled))) {
                long n = (long) rounded;
                long g = gcd(n, d);
                return new EnergyRatio(n / g, d / g);
            }
        }
        return INEXACT;
    }

    boolean isExact() {
        return den > 0L && num > 0L;
    }

    private static long gcd(long a, long b) {
        while (b != 0L) {
            long t = a % b;
            a = b;
            b = t;
        }
        return a;
    }
}
