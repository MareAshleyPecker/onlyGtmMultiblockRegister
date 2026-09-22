package rain.fox.ogmr.test;

import rain.fox.ogmr.utils.ResourceLocations;

import net.minecraft.resources.ResourceLocation;

/**
 * 测试包用到的贴图常量 —— <b>全部是从 GTM 搬过来的美术资源</b>（见 README「贴图来源」）。
 *
 * <p>
 * 搬的时候保留了 GTM 的原始目录结构（{@code block/overlay/machine/**}、{@code block/casings/**}），
 * 所以想换成自己的美术，照着同样的路径放一张同名 png 就行 —— 或者直接用
 * {@code .modelTexture(...)} / {@code .port(...)} / {@code .overlay(...)} 指到别处。
 *
 * <p>
 * 「口」按仓室用途分了类（GTM 也是这么分的）：物品仓用 item_hatch_input/output、
 * 能源仓用 energy_1a_in/out（带 emissive 发光件）、线程仓用通用的 overlay_hatch。
 */
public final class TestTextures {

    private TestTextures() {}

    // ── 底盘（外壳）──
    public static final ResourceLocation CASING_STEEL = ogmr("block/casings/solid/machine_casing_solid_steel");
    public static final ResourceLocation CASING_HEATPROOF = ogmr("block/casings/solid/machine_casing_heatproof");

    // ── 仓室的「口」──
    /** 通用仓室口。 */
    public static final ResourceLocation PORT_HATCH = ogmr("block/overlay/machine/overlay_hatch");
    /** 物品输入 / 输出仓。 */
    public static final ResourceLocation PORT_ITEM_IN = ogmr("block/overlay/machine/overlay_item_hatch_input");
    public static final ResourceLocation PORT_ITEM_OUT = ogmr("block/overlay/machine/overlay_item_hatch_output");
    /** 流体输入 / 输出仓（暂时没机器用，留着方便以后加）。 */
    public static final ResourceLocation PORT_FLUID_IN = ogmr("block/overlay/machine/overlay_fluid_hatch_input");
    public static final ResourceLocation PORT_FLUID_OUT = ogmr("block/overlay/machine/overlay_fluid_hatch_output");
    /** 能源输入 / 输出仓，以及与它们配套的发光层。 */
    public static final ResourceLocation PORT_ENERGY_IN = ogmr("block/overlay/machine/overlay_energy_1a_in");
    public static final ResourceLocation PORT_ENERGY_IN_EMISSIVE = ogmr("block/overlay/machine/overlay_energy_1a_in_emissive");
    public static final ResourceLocation PORT_ENERGY_OUT = ogmr("block/overlay/machine/overlay_energy_1a_out");
    public static final ResourceLocation PORT_ENERGY_OUT_EMISSIVE = ogmr("block/overlay/machine/overlay_energy_1a_out_emissive");

    // ── 多方块控制器的正面层（GTM 的 overlay_front 那一套）──
    /** 待机正面。 */
    public static final ResourceLocation CONTROLLER_FRONT = ogmr("block/machine/overlay/front");
    /** 工作态正面（成型层用它：成型后亮起来）。 */
    public static final ResourceLocation CONTROLLER_FRONT_ACTIVE = ogmr("block/machine/overlay/front_active");
    /** 工作态正面的发光件（发光层用它）。 */
    public static final ResourceLocation CONTROLLER_FRONT_ACTIVE_EMISSIVE = ogmr("block/machine/overlay/front_active_emissive");

    private static ResourceLocation ogmr(String path) {
        return ResourceLocations.ogmr(path);
    }
}
