import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;

/**
 * ogmr 自带 GUI 贴图的生成器 —— 产物是 {@code src/main/resources/assets/ogmr/textures/gui/**} 下的 PNG。
 *
 * <p>
 * 为什么要生成而不是手画：这些贴图全是「纯色 + 描边」的九宫格，写程序比画图省事，
 * 而且改配色只要改这里的常量。{@code GuiTextures} 里的尺寸/切片参数必须与这里一致：
 *
 * <table border="1">
 * <caption>贴图与切片参数</caption>
 * <tr><th>文件</th><th>尺寸</th><th>九宫格边宽</th></tr>
 * <tr><td>base/background.png</td><td>16×16</td><td>4</td></tr>
 * <tr><td>base/info_background.png</td><td>16×16</td><td>2</td></tr>
 * <tr><td>base/slot.png</td><td>18×18</td><td>1</td></tr>
 * <tr><td>base/fluid_slot.png</td><td>18×18</td><td>1</td></tr>
 * <tr><td>recipe_type/base.png</td><td>16×16</td><td>4</td></tr>
 * <tr><td>recipe_type/slot.png</td><td>18×18</td><td>1</td></tr>
 * <tr><td>progress/bar_background.png</td><td>32×16</td><td>整图拉伸</td></tr>
 * <tr><td>progress/bar_filled.png</td><td>32×16</td><td>整图拉伸</td></tr>
 * </table>
 *
 * <p>
 * 用法（在仓库根目录，JDK 17 可以直接跑单文件源码）：
 *
 * <pre>{@code
 * java tools/GuiTextureGenerator.java
 * # 或者指定输出目录：
 * java tools/GuiTextureGenerator.java src/main/resources/assets/ogmr/textures/gui
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

    /** 「口」兜底贴图的配色。 */
    private static final int PORT_FRAME = 0xFF101010;
    private static final int PORT_BEVEL = 0xFF7A7A7A;
    private static final int PORT_BODY = 0xFF242424;
    private static final int PORT_BAR = 0xFF8B8B8B;

    private GuiTextureGenerator() {}

    public static void main(String[] args) throws IOException {
        // 默认写到 assets/ogmr/textures 根；下面按 gui/ 与 block/ 分目录
        File root = new File(args.length > 0 ? args[0] : "src/main/resources/assets/ogmr/textures");

        write(root, "gui/base/background.png", panel(16, PANEL_FILL, PANEL_BORDER, PANEL_LIGHT, PANEL_DARK));
        write(root, "gui/base/info_background.png", panel(16, INFO_FILL, INFO_BORDER, INFO_BORDER, INFO_FILL));
        write(root, "gui/base/slot.png", slot(18, SLOT_FILL, SLOT_SHADOW, SLOT_HIGHLIGHT));
        write(root, "gui/base/fluid_slot.png", panel(18, FLUID_FILL, FLUID_BORDER, FLUID_BORDER, FLUID_FILL));
        write(root, "gui/recipe_type/base.png", panel(16, RTUI_FILL, RTUI_BORDER, RTUI_BORDER, RTUI_FILL));
        write(root, "gui/recipe_type/slot.png", slot(18, SLOT_FILL, SLOT_SHADOW, SLOT_HIGHLIGHT));
        write(root, "gui/progress/bar_background.png", bar(32, 16, PROGRESS_EMPTY, PROGRESS_EMPTY_BORDER));
        write(root, "gui/progress/bar_filled.png", bar(32, 16, PROGRESS_FILLED, PROGRESS_FILLED_LIGHT));

        // 方块贴图：仓室「口」的兜底贴图（MachineDefinition.DEFAULT_PORT_TEXTURE = ogmr:block/machine/port_default）
        write(root, "block/machine/port_default.png", port());

        System.out.println("ogmr: textures written under " + root.getAbsolutePath());
    }

    /**
     * 「口」的兜底贴图：深色外框 + 内侧高光 + 中间两道横杠，看起来像一个开口。
     *
     * <p>作者要换自己的美术，就在注册时写 {@code .port(自己的贴图)}，不用动这张。
     */
    private static BufferedImage port() {
        BufferedImage image = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        fill(image, PORT_BODY);
        line(image, 1, 1, 14, true, PORT_BEVEL);
        line(image, 1, 1, 14, false, PORT_BEVEL);
        for (int y = 6; y <= 7; y++) {
            line(image, 3, y, 10, true, PORT_BAR);
        }
        for (int y = 10; y <= 11; y++) {
            line(image, 3, y, 10, true, PORT_BAR);
        }
        rect(image, 0, 0, 16, 16, PORT_FRAME);
        return image;
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
