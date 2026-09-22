package rain.fox.ogmr.api.gui.editor;

import net.minecraft.resources.ResourceLocation;

/**
 * ogmr 新写 —— 可编辑 UI 的最小契约。
 *
 * <p>
 * 参考 GTM 的 {@code com.gregtechceu.gtceu.api.gui.editor.IEditableUI}，但因为不能依赖 GTCEu，
 * 这里重写并<b>简化</b>成两个泛型参数：{@code T} = 模板类型（一般是 {@code WidgetGroup}），
 * {@code M} = 绑定对象类型（机器 UI 是 {@code MetaMachine}）。
 *
 * <p>它把 LDLib 的 {@code .mui} 编辑器工作流拆成两半：
 * <ol>
 * <li>{@link #createDefault()} —— 产出一份<b>没有数据</b>的默认模板（进编辑器里摆位置用，
 * 或者当没有自定义 .mui 时的运行时布局）；</li>
 * <li>{@link #setupUI(Object, Object)} —— 往一份已有模板里<b>只做数据绑定</b>
 * （模板可能是 {@code createDefault()} 产出的，也可能是从 .mui 反序列化出来的）。</li>
 * </ol>
 *
 * <p>{@link #getGroupName()} / {@link #getUiPath()} 是给编辑器分组的元信息：前者是编辑器左侧
 * 「模板」树里的分类名，后者决定 .mui 文件的读写路径 {@code assets/<ns>/ui/machine/<path>.mui}。
 *
 * @param <T> 模板（UI 容器）类型
 * @param <M> 绑定的数据对象类型
 */
public interface IEditableUI<T, M> {

    /** 编辑器里显示的分组名（同一台机器的默认布局与自定义布局应当同名）。 */
    String getGroupName();

    /** UI 工程路径，决定 {@code .mui} 文件位置：{@code assets/<ns>/ui/machine/<path>.mui}。 */
    ResourceLocation getUiPath();

    /** 产出一份未绑定的默认模板。 */
    T createDefault();

    /**
     * 把数据绑定到模板上。
     *
     * <p><b>只做绑定，不要改布局</b> —— 模板可能是玩家自己用编辑器画的自定义 UI，
     * 往里面加 widget 会让自定义布局失效。
     */
    void setupUI(T template, M machine);
}
