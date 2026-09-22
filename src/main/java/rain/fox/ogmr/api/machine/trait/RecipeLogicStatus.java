package rain.fox.ogmr.api.machine.trait;

import lombok.Getter;

import net.minecraft.util.StringRepresentable;

/**
 * 配方逻辑的运行状态。
 *
 * <p>
 * 从 GTM 的 {@code RecipeLogic.Status} 精简拆出来：GTM 还有一个 {@code SUSPEND}
 * （「机器被暂停 / 电力不足自动挂起」），本库先不做暂停功能，所以只留三态：
 * <ul>
 * <li>{@link #IDLE} —— 空闲：没有找到可用配方；</li>
 * <li>{@link #WORKING} —— 运行中：正在加工一条配方；</li>
 * <li>{@link #WAITING} —— 等待：找到了配方但缺料 / 缺电 / 输出堵塞。</li>
 * </ul>
 *
 * <p>
 * 实现 {@link StringRepresentable} 是刚需：LDLib 的 {@code EnumAccessor} 同步枚举字段时
 * 优先取 {@link #getSerializedName()}（没实现才退化成 {@code Enum#name()}），
 * 而且将来要挂到方块状态属性上也用得着。
 */
public enum RecipeLogicStatus implements StringRepresentable {

    IDLE("idle"),
    WORKING("working"),
    WAITING("waiting");

    @Getter
    private final String serializedName;

    RecipeLogicStatus(String serializedName) {
        this.serializedName = serializedName;
    }

    public boolean isIdle() {
        return this == IDLE;
    }

    public boolean isWorking() {
        return this == WORKING;
    }

    public boolean isWaiting() {
        return this == WAITING;
    }

    /** 按序列化名取枚举；不认识的名字回退到 {@link #IDLE}（存档/网络脏数据不该让机器崩）。 */
    public static RecipeLogicStatus byName(String name) {
        for (RecipeLogicStatus status : values()) {
            if (status.serializedName.equals(name)) return status;
        }
        return IDLE;
    }
}
