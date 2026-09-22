package rain.fox.ogmr.integration.jei;

import rain.fox.ogmr.api.pattern.MultiblockShapeInfo;

import lombok.Getter;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

import mezz.jei.api.gui.drawable.IDrawable;

import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 多方块结构预览的「一张画」。
 *
 * <p>
 * 把 {@link MultiblockShapeInfo} 里的 {@code BlockInfo[][][]} 摊平之后，用 <b>等轴测（2.5D）</b> 的方式
 * 逐格画 16×16 的方块贴图：
 * <ul>
 * <li>x / z 每前进一格，屏幕横向 ±8px、纵向 +4px（2:1 等轴测）；</li>
 * <li>y 每上升一格，屏幕纵向 −8px；</li>
 * <li>绘制顺序 = painter's algorithm：先远（rx+rz 小）后近，同一列先下后上，
 * 于是上层方块会正确压住下层的顶面。</li>
 * </ul>
 *
 * <p>
 * <b>为什么不用 GTM 那套真 3D：</b>GTM 的 {@code PatternPreviewWidget} 依赖 LDLib 的
 * {@code WorldSceneRenderer} + {@code TrackedDummyWorld} —— 在 JEI 初始化时现造一个假世界，
 * 把整台多方块塞进去做真 3D 渲染，还顺带做多方块成型校验。那是重度客户端方案（要 Level、
 * 要 GPU 状态、要清理缓存），跟「独立注册工具库」的定位不搭。这里刻意退化成
 * <b>纯 2D 等轴测贴图</b>：不需要假世界、不需要 Level，而「看清结构长什么样」这个目标仍然达成。
 * 唯一的代价是没有真视角的光照/遮挡关系，以及不提供「已成型」校验（JEI 预览本来也不该成型）。
 *
 * <p>
 * 交互（任务里说的「旋转 / 分层二选一」——这里两种都实现了，主推旋转）：
 * <ul>
 * <li><b>旋转</b>：滚轮 / 按住拖拽 → 绕 Y 轴 90° 一跳。由 {@link MultiblockInfoCategory} 的
 * input handler 转发到 {@link #rotate(int)} 与 {@link #drag(double)}；</li>
 * <li><b>分层</b>：{@link #nextLayer()} 在「全部层」与第 0..n-1 层之间循环，只画该层的格子。</li>
 * </ul>
 *
 * <p>
 * 本类是纯客户端的：只在 JEI 分类里被 new 出来（JEI 只在客户端加载）。
 */
public class StructurePreviewDrawable implements IDrawable {

    /** {@link #getLayer()} 的取值：不分层，画全部。 */
    public static final int ALL_LAYERS = -1;

    /** 单格方块贴图的边长。 */
    private static final int TILE = 16;

    /** 等轴测：x/z 每格在屏幕上横向移动的半格宽。 */
    private static final int STEP_SIDE = 8;

    /** 等轴测：x/z 每格在屏幕上纵向移动的四分之一格高（2:1 等轴测）。 */
    private static final int STEP_DEPTH = 4;

    /** 等轴测：y 每格在屏幕上抬升的高度。 */
    private static final int STEP_LEVEL = 8;

    /** 拖拽旋转：累计拖过这么多像素才跳四分之一圈。 */
    private static final double DRAG_STEP = 14.0D;

    /** 鼠标悬停时给那一格描的边（不透明白）。 */
    private static final int HOVER_OUTLINE = 0xFFFFFFFF;

    /** 画不出方块贴图时的兜底灰。 */
    private static final int FALLBACK_GRAY = 0xFF6E6E6E;

    /** 兜底贴片上的「假立体」阴影。 */
    private static final int FALLBACK_SHADE = 0x50000000;

    private final int areaWidth;
    private final int areaHeight;

    /** 结构在三个轴上的格数（只用于展示「尺寸」文本）。 */
    @Getter
    private final int sizeX;
    @Getter
    private final int sizeY;
    @Getter
    private final int sizeZ;

    /** 全部非空气格，已按 painter's algorithm 排好序。 */
    private final List<Cell> cells = new ArrayList<>();

    /** 绕 Y 轴的四分之一圈数，0..3 —— 即当前朝向。 */
    @Getter
    private int yaw;

    /** 当前只画哪一层（当前层）；{@link #ALL_LAYERS} = 全部层都画。 */
    @Getter
    private int layer = ALL_LAYERS;

    /** {@link #drag(double)} 的累计量。 */
    private double dragAccumulator;

    /** 结构在屏幕上（未缩放）的包围盒尺寸。 */
    private int boxWidth;
    private int boxHeight;

    /** 缩放系数：结构比预览框大时等比缩小；永远不会 > 1。 */
    private float scale = 1.0F;

    /** 结构左上角相对「预览框左上角」的偏移，在 {@link #layout()} 里算好，画之前就可读。 */
    private int innerX;
    private int innerY;

    private StructurePreviewDrawable(@Nullable BlockState[][][] states, int areaWidth, int areaHeight) {
        this.areaWidth = areaWidth;
        this.areaHeight = areaHeight;

        int x = 0;
        int y = 0;
        int z = 0;
        if (states != null) {
            x = states.length;
            for (int a = 0; a < states.length; a++) {
                BlockState[][] plane = states[a];
                if (plane == null) continue;
                y = Math.max(y, plane.length);
                for (int b = 0; b < plane.length; b++) {
                    BlockState[] line = plane[b];
                    if (line == null) continue;
                    z = Math.max(z, line.length);
                    for (int c = 0; c < line.length; c++) {
                        BlockState state = line[c];
                        if (state == null || state.isAir()) continue;
                        cells.add(new Cell(a, b, c, state));
                    }
                }
            }
        }
        this.sizeX = x;
        this.sizeY = y;
        this.sizeZ = z;

        layout();
    }

    /**
     * 由一份结构图案造一个预览画布；{@code shape} 允许为 null（机器没登记 shape 时）。
     *
     * @param shape      结构图案，可为 null
     * @param areaWidth  预览框宽（px）
     * @param areaHeight 预览框高（px）
     */
    public static StructurePreviewDrawable of(@Nullable MultiblockShapeInfo shape, int areaWidth, int areaHeight) {
        return new StructurePreviewDrawable(shape == null ? null : toStates(shape), areaWidth, areaHeight);
    }

    /**
     * 把一个 {@link MultiblockShapeInfo} 摊平成 {@code BlockState[][][]}。
     *
     * <p>
     * <b>这里是全类唯一碰 {@code BlockInfo} 的地方，而且刻意用 {@code var} 接返回值</b>：
     * 这样源码里<b>不需要 import</b> {@code BlockInfo}，于是无论同事最终把它放在
     * {@code rain.fox.ogmr.api.pattern} 还是直接用 LDLib 的 {@code com.lowdragmc.lowdraglib.utils.BlockInfo}
     * （GTM 用的是后者），本类都照样编译。契约只剩「有 {@code getBlockState()}」这一条。
     * 将来契约变了，只改这一个方法。
     */
    private static BlockState[][][] toStates(MultiblockShapeInfo shape) {
        var blocks = shape.getBlocks();
        BlockState[][][] out = new BlockState[blocks.length][][];
        for (int a = 0; a < blocks.length; a++) {
            var plane = blocks[a];
            if (plane == null) {
                out[a] = new BlockState[0][];
                continue;
            }
            out[a] = new BlockState[plane.length][];
            for (int b = 0; b < plane.length; b++) {
                var line = plane[b];
                if (line == null) {
                    out[a][b] = new BlockState[0];
                    continue;
                }
                out[a][b] = new BlockState[line.length];
                for (int c = 0; c < line.length; c++) {
                    var info = line[c];
                    out[a][b][c] = info == null ? null : info.getBlockState();
                }
            }
        }
        return out;
    }

    // ═══════════════════════ 投影 / 布局 ═══════════════════════

    /**
     * 按当前 {@link #yaw} 重算每一格的屏幕位置、包围盒、缩放与居中偏移。
     *
     * <p>
     * 格数不多（一台多方块撑死几百格），而且只在旋转/换 shape 时调用，所以直接整体重算，不做增量。
     */
    private void layout() {
        if (cells.isEmpty()) {
            boxWidth = 0;
            boxHeight = 0;
            scale = 1.0F;
            innerX = areaWidth / 2;
            innerY = areaHeight / 2;
            return;
        }

        int minX = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int minY = Integer.MAX_VALUE;
        int maxY = Integer.MIN_VALUE;

        int quarters = Math.floorMod(yaw, 4);
        for (Cell cell : cells) {
            // 绕 Y 轴旋转 90° * quarters，全部是整数运算，没有浮点误差。
            // 递推关系 (x,z) -> (z,-x)，连做两次正好得到 180°，所以这四档是真旋转而不是镜像。
            int rx;
            int rz;
            switch (quarters) {
                case 1 -> {
                    rx = cell.z;
                    rz = -cell.x;
                }
                case 2 -> {
                    rx = -cell.x;
                    rz = -cell.z;
                }
                case 3 -> {
                    rx = -cell.z;
                    rz = cell.x;
                }
                default -> {
                    rx = cell.x;
                    rz = cell.z;
                }
            }
            // 等轴测投影；再减掉半格，让 px/py 变成「贴图左上角」而不是中心
            cell.px = (rx - rz) * STEP_SIDE - STEP_SIDE;
            cell.py = (rx + rz) * STEP_DEPTH - cell.y * STEP_LEVEL - STEP_SIDE;
            cell.depth = rx + rz;

            minX = Math.min(minX, cell.px);
            maxX = Math.max(maxX, cell.px + TILE);
            minY = Math.min(minY, cell.py);
            maxY = Math.max(maxY, cell.py + TILE);
        }

        boxWidth = maxX - minX;
        boxHeight = maxY - minY;

        for (Cell cell : cells) {
            cell.px -= minX;
            cell.py -= minY;
        }

        scale = Math.min(1.0F, Math.min(areaWidth / (float) boxWidth, areaHeight / (float) boxHeight));
        innerX = Math.round((areaWidth - boxWidth * scale) / 2.0F);
        innerY = Math.round((areaHeight - boxHeight * scale) / 2.0F);

        // painter's algorithm：先远后近；同一深度先画下层，上层随后盖上去
        cells.sort(Comparator.<Cell>comparingInt(cell -> cell.depth)
                .thenComparingInt(cell -> cell.y));
    }

    // ═══════════════════════ IDrawable ═══════════════════════

    @Override
    public int getWidth() {
        return areaWidth;
    }

    @Override
    public int getHeight() {
        return areaHeight;
    }

    @Override
    public void draw(GuiGraphics graphics, int xOffset, int yOffset) {
        draw(graphics, xOffset, yOffset, -1.0D, -1.0D);
    }

    /**
     * 画结构本体，并给鼠标下的那一格描白边。
     *
     * @param xOffset      预览框左上角在配方里的 X
     * @param yOffset      预览框左上角在配方里的 Y
     * @param mouseX       鼠标 X（<b>相对预览框左上角</b>；-1 表示不画悬停框）
     * @param mouseY       鼠标 Y（同上）
     */
    public void draw(GuiGraphics graphics, int xOffset, int yOffset, double mouseX, double mouseY) {
        if (cells.isEmpty()) return;

        Cell hovered = cellAt(mouseX, mouseY);

        var pose = graphics.pose();
        pose.pushPose();
        pose.translate(xOffset + innerX, yOffset + innerY, 0.0F);
        pose.scale(scale, scale, 1.0F);

        for (Cell cell : cells) {
            if (!isVisible(cell)) continue;

            if (!cell.stack.isEmpty()) {
                // 正是这一步给出「等轴测方块」的观感：GUI 变换下的方块物品本身就是 2.5D 的
                graphics.renderItem(cell.stack, cell.px, cell.py);
            } else {
                // 没有对应物品的方块（流体、纯技术方块……）：退化成一块染色贴片，
                // 至少让人看出「这里有一格东西」，方块名照样能在 tooltip 里看到
                graphics.fill(cell.px, cell.py, cell.px + TILE, cell.py + TILE, cell.color);
                graphics.fill(cell.px, cell.py, cell.px + TILE, cell.py + 1, FALLBACK_SHADE);
                graphics.fill(cell.px, cell.py, cell.px + 1, cell.py + TILE, FALLBACK_SHADE);
            }

            if (cell == hovered) {
                outline(graphics, cell.px, cell.py, TILE, HOVER_OUTLINE);
            }
        }

        pose.popPose();
    }

    // ═══════════════════════ 交互 ═══════════════════════

    /** 绕 Y 轴转 {@code quarters} 个四分之一圈（可为负）。 */
    public void rotate(int quarters) {
        if (quarters == 0) return;
        yaw = Math.floorMod(yaw + quarters, 4);
        layout();
    }

    /**
     * 拖拽旋转：把水平拖拽量攒起来，攒够 {@link #DRAG_STEP} 像素跳四分之一圈。
     *
     * <p>
     * 攒而不直接换算，是因为 {@code handleMouseDragged} 每次只给一小段位移，
     * 直接按符号跳会让「轻轻一动就转 90°」，很难用。
     */
    public void drag(double dragX) {
        dragAccumulator += dragX;
        while (dragAccumulator >= DRAG_STEP) {
            dragAccumulator -= DRAG_STEP;
            rotate(1);
        }
        while (dragAccumulator <= -DRAG_STEP) {
            dragAccumulator += DRAG_STEP;
            rotate(-1);
        }
    }

    /**
     * 切到下一层；走到头就回到「全部层」。
     *
     * <p>
     * 循环顺序是 全部 → 第 1 层 → … → 第 n 层 → 全部（跟 GTM 的层级按钮一致）。
     */
    public void nextLayer() {
        if (sizeY <= 0) {
            layer = ALL_LAYERS;
            return;
        }
        layer = layer + 1 >= sizeY ? ALL_LAYERS : layer + 1;
    }

    /** 层数（= 结构在 y 方向的格数）。 */
    public int getLayerCount() {
        return sizeY;
    }

    /** 结构里非空气格的格数；0 表示「没得画」（机器没登记 shape，或者 shape 全是空气）。 */
    public boolean isEmpty() {
        return cells.isEmpty();
    }

    /**
     * 取鼠标下的那一格方块。
     *
     * @param mouseX 鼠标 X，<b>相对预览框左上角</b>
     * @param mouseY 鼠标 Y，<b>相对预览框左上角</b>
     * @return 方块状态；没命中返回 null
     */
    @Nullable
    public BlockState getBlockAt(double mouseX, double mouseY) {
        Cell cell = cellAt(mouseX, mouseY);
        return cell == null ? null : cell.state;
    }

    @Nullable
    private Cell cellAt(double mouseX, double mouseY) {
        if (cells.isEmpty() || scale <= 0.0F) return null;
        // 把鼠标坐标逆变换回「未缩放的贴图空间」
        double localX = (mouseX - innerX) / scale;
        double localY = (mouseY - innerY) / scale;

        // 倒着找 = 从最上层往下找，命中的就是眼睛看到的那一格
        for (int i = cells.size() - 1; i >= 0; i--) {
            Cell cell = cells.get(i);
            if (!isVisible(cell)) continue;
            if (localX >= cell.px && localX < cell.px + TILE
                    && localY >= cell.py && localY < cell.py + TILE) {
                return cell;
            }
        }
        return null;
    }

    private boolean isVisible(Cell cell) {
        return layer == ALL_LAYERS || cell.y == layer;
    }

    private static void outline(GuiGraphics graphics, int x, int y, int size, int color) {
        graphics.fill(x - 1, y - 1, x + size + 1, y, color);
        graphics.fill(x - 1, y + size, x + size + 1, y + size + 1, color);
        graphics.fill(x - 1, y, x, y + size, color);
        graphics.fill(x + size, y, x + size + 1, y + size, color);
    }

    /** 结构里的一格：位置 + 方块 + 预先算好的屏幕坐标。 */
    private static final class Cell {

        final int x;
        final int y;
        final int z;
        final BlockState state;

        /** 这一格要画的物品；方块没有对应物品时为 EMPTY。 */
        final ItemStack stack;

        /** 没有物品时的兜底颜色。 */
        final int color;

        /** 未缩放的贴图左上角（相对结构包围盒左上角）。 */
        int px;
        int py;

        /** painter's algorithm 用的深度键（rx + rz，越大越靠近观察者）。 */
        int depth;

        Cell(int x, int y, int z, BlockState state) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.state = state;

            ItemStack candidate = new ItemStack(state.getBlock());
            this.stack = candidate.isEmpty() ? ItemStack.EMPTY : candidate;

            // defaultMapColor() 在方块没指定 mapColor 时可能为 null（不是每个 Block 都设了），
            // 而这段是在 JEI 注册配方期跑的 —— 真 NPE 出去会让整台机器的预览被静默跳过。
            var mapColor = state.getBlock().defaultMapColor();
            int rgb = mapColor == null ? 0 : mapColor.col & 0xFFFFFF;
            this.color = rgb == 0 ? FALLBACK_GRAY : 0xFF000000 | rgb;
        }
    }
}
