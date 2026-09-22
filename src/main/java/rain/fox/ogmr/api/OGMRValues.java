package rain.fox.ogmr.api;

import net.minecraft.network.chat.Component;

/**
 * 电压等级相关常量。
 *
 * <p>
 * 从 GTM 的 {@code GTValues} 里只取「多方块 / 仓室 / 配方」真正用到的那一部分（去 GT 内容化）：
 * 档位电压、档位名、档位缩写、按电压反查档位、档位名 → 文本。
 *
 * <p>
 * 注意：这里的 {@code V[]} 与 GTM 完全一致，所以本库注册出来的多方块/仓室可以无缝和 GTM 的
 * 能源仓、配方电压对得上（如果你选择与 GTM 共存的话）。
 */
public final class OGMRValues {

    private OGMRValues() {}

    /** 0 = 无电（原始/蒸汽），1 = ULV ... 14 = MAX。 */
    public static final int ULV = 0;
    public static final int LV = 1;
    public static final int MV = 2;
    public static final int HV = 3;
    public static final int EV = 4;
    public static final int IV = 5;
    public static final int LuV = 6;
    public static final int ZPM = 7;
    public static final int UV = 8;
    public static final int UHV = 9;
    public static final int UEV = 10;
    public static final int UIV = 11;
    public static final int UXV = 12;
    public static final int OpV = 13;
    public static final int MAX = 14;

    /** 档位数量。 */
    public static final int TIER_COUNT = 15;

    /** 每一档的电压（EU/t）；下标 = 档位。 */
    public static final long[] V = {
            8L, 32L, 128L, 512L, 2048L, 8192L, 32768L, 131072L,
            524288L, 2097152L, 8388608L, 33554432L, 134217728L, 536870912L, 2147483648L,
    };

    /** 每一档的电流（A）；下标 = 档位。 */
    public static final int[] VA = {
            1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1,
    };

    /** 档位英文缩写；下标 = 档位。 */
    public static final String[] VN = {
            "ULV", "LV", "MV", "HV", "EV", "IV", "LuV", "ZPM", "UV", "UHV", "UEV", "UIV", "UXV", "OpV", "MAX",
    };

    /** 档位颜色（用于 tooltip / UI）；下标 = 档位。 */
    public static final int[] VC = {
            0x535353, 0x7F7F7F, 0x9E9E9E, 0xC8C8C8, 0x3C5A99, 0x5A3C99,
            0x993C5A, 0x995A3C, 0x3C9999, 0x99A03C, 0xA03C99, 0x3CA05A,
            0xA05A3C, 0x5A3CA0, 0xFFFFFF,
    };

    /** 把档位名渲染成带颜色的文本（如 {@code §bLV}）。 */
    public static Component tierName(int tier) {
        int t = clampTier(tier);
        return Component.literal(VN[t]);
    }

    public static String tierNameRaw(int tier) {
        return VN[clampTier(tier)];
    }

    /** 按电压反查最接近的档位（用于「这条配方属于哪一档」）。 */
    public static int getTierByVoltage(long voltage) {
        if (voltage <= 0) return 0;
        int tier = 0;
        for (int i = 0; i < V.length; i++) {
            if (voltage >= V[i]) tier = i;
        }
        return tier;
    }

    /** 取某一档的总功率（电压 × 电流）。 */
    public static long getTierPower(int tier) {
        int t = clampTier(tier);
        return V[t] * VA[t];
    }

    public static int clampTier(int tier) {
        if (tier < 0) return 0;
        return Math.min(tier, TIER_COUNT - 1);
    }
}
