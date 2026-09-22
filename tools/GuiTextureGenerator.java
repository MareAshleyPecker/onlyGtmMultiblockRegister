import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;

/**
 * ogmr 自带 GUI 贴图的生成器 —— 产物是 {@code src/main/resources/assets/ogmr/textures/gui/**} 下的 PNG。
 *
 * <p>
 * ⚠️ <b>只生成 LDLib 没有的那几张</b>：物品槽 / 流体槽 / 面板背景这类 LDLib 都有现成品
 * （{@code SlotWidget.ITEM_SLOT_TEXTURE}、{@code TankWidget.FLUID_SLOT_TEXTURE}、
 * {@code ResourceBorderTexture.BORDERED_BACKGROUND}），直接用它的，不要在这里重画一遍。
 *
 * <table border="1">
 * <caption>贴图与切片参数</caption>
 * <tr><th>文件</th><th>尺寸</th><th>切片</th><th>为什么还得自己出</th></tr>
 * <tr><td>base/info_background.png</td><td>16×16</td><td>九宫格 2</td><td>信息栏贴在面板外、压在世界画面上，需要半透明底板</td></tr>
 * <tr><td>progress/bar_background.png</td><td>32×16</td><td>整图拉伸</td><td>LDLib 没有现成的进度条贴图</td></tr>
 * <tr><td>progress/bar_filled.png</td><td>32×16</td><td>整图拉伸</td><td>同上</td></tr>
 * </table>
 *
 * <p>
 * 用法（在仓库根目录，JDK 17 可以直接跑单文件源码）：
 *
 * <pre>{@code
 * java tools/GuiTextureGenerator.java
 * # 或者指定输出目录：
 * java tools/GuiTextureGenerator.java src/main/resources/assets/ogmr/textures
 * }</pre>
 */
public final class GuiTextureGenerator {

    // ── 配色（ARGB） ──
    /** 面板底。 */
    private static final int PANEL_FILL = 0xFF3B3B3B;
    /** 面板外框。 */
    private static final int PANEL_BORDER = 0xFF9A9A9A;
    /** 面板高光（左上）。 */
    private static final int PANEL_LIGHT = 0xFF565656;
    /** 面板阴影（右下）。 */
    private static final int PANEL_DARK = 0xFF242424;

    /** rtui 面板底（比机器面板稍冷一点，便于区分）。 */
    private static final int RTUI_FILL = 0xFF2F3338;
    private static final int RTUI_BORDER = 0xFF8FA0B0;

    /** 物品槽：原版那种「内凹」观感 —— 底浅、左上暗、右下亮。 */
    private static final int SLOT_FILL = 0xFF8B8B8B;
    private static final int SLOT_SHADOW = 0xFF373737;
    private static final int SLOT_HIGHLIGHT = 0xFFFFFFFF;

    /** 流体槽。 */
    private static final int FLUID_FILL = 0xFF17242C;
    private static final int FLUID_BORDER = 0xFF4A7A9A;

    /** 信息栏：半透明深色，压在世界上也看得清。 */
    private static final int INFO_FILL = 0xC0101010;
    private static final int INFO_BORDER = 0xFF5A5A5A;

    /** 进度条。 */
    private static final int PROGRESS_EMPTY = 0xFF1E1E1E;
    private static final int PROGRESS_EMPTY_BORDER = 0xFF6A6A6A;
    private static final int PROGRESS_FILLED = 0xFF3F8F3F;
    private static final int PROGRESS_FILLED_LIGHT = 0xFF6FC46F;

    private GuiTextureGenerator() {}

    public static void main(String[] args) throws IOException {
        // 默认写到 assets/ogmr/textures 根
        File root = new File(args.length > 0 ? args[0] : "src/main/resources/assets/ogmr/textures");

        // 只生成 LDLib 没有的：信息栏半透明底板 + 进度条
        write(root, "gui/base/info_background.png", panel(16, INFO_FILL, INFO_BORDER, INFO_BORDER, INFO_FILL));
        write(root, "gui/progress/bar_background.png", bar(32, 16, PROGRESS_EMPTY, PROGRESS_EMPTY_BORDER));
        write(root, "gui/progress/bar_filled.png", bar(32, 16, PROGRESS_FILLED, PROGRESS_FILLED_LIGHT));

        // ⚠️ 槽位 / 面板背景用 LDLib 自带贴图，不在这里生成（见类注释）。
        // ⚠️ 方块贴图（口 / 覆盖层 / 底盘）也不在这里生成 —— 那些是从 GTM 搬来的美术资源，
        //    见 assets/ogmr/textures/block/** 与 README「贴图来源」一节。
        System.out.println("ogmr: GUI textures written under " + root.getAbsolutePath());
    }

    /**
     * 面板底图：铺底色 → 最外圈 1px 外框 → 左上 1px 高光 / 右下 1px 阴影。
     *
     * <p>外框宽度固定 1px（肉眼看就是「一圈线」），九宫格切片宽度由 {@code GuiTextures} 声明
     * （背景 4、信息栏 2），这里只要保证图片尺寸够切即可。
     */
    private static BufferedImage panel(int size, int fill, int border, int light, int dark) {
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        fill(image, fill);
        rect(image, 0, 0, size, size, border);
        // 外框内侧 1px 的斜面
        line(image, 1, 1, size - 2, true, light);
        line(image, 1, 1, size - 2, false, light);
        line(image, size - 2, size - 2, size - 2, true, dark);
        line(image, size - 2, size - 2, size - 2, false, dark);
        return image;
    }

    /** 槽位底图：原版那种内凹观感。 */
    private static BufferedImage slot(int size, int fill, int shadow, int highlight) {
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        fill(image, fill);
        line(image, 0, 0, size - 1, true, shadow);
        line(image, 0, 0, size - 1, false, shadow);
        line(image, size - 1, size - 1, size - 1, true, highlight);
        line(image, size - 1, size - 1, size - 1, false, highlight);
        return image;
    }

    /** 进度条底图：纯色 + 1px 外框（整图拉伸，不做九宫格）。 */
    private static BufferedImage bar(int width, int height, int fill, int accent) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        fill(image, fill);
        rect(image, 0, 0, width, height, accent);
        return image;
    }

    // ── 像素工具 ──

    private static void fill(BufferedImage image, int argb) {
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                image.setRGB(x, y, argb);
            }
        }
    }

    /** 画一个空心矩形（1px 线宽）。 */
    private static void rect(BufferedImage image, int x, int y, int width, int height, int argb) {
        line(image, x, y, width, true, argb);
        line(image, x, y, height, false, argb);
        line(image, x + width - 1, y, height, false, argb);
        line(image, x, y + height - 1, width, true, argb);
    }

    /** 画一条横线（{@code horizontal=true}）或竖线，长度 {@code length}。 */
    private static void line(BufferedImage image, int x, int y, int length, boolean horizontal, int argb) {
        for (int i = 0; i < length; i++) {
            int px = horizontal ? x + i : x;
            int py = horizontal ? y : y + i;
            if (px >= 0 && py >= 0 && px < image.getWidth() && py < image.getHeight()) {
                image.setRGB(px, py, argb);
            }
        }
    }

    private static void write(File root, String path, BufferedImage image) throws IOException {
        File file = new File(root, path);
        File parent = file.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("ogmr: cannot create directory " + parent);
        }
        ImageIO.write(image, "png", file);
        System.out.println("  " + path + " (" + image.getWidth() + "x" + image.getHeight() + ")");
    }
}
