package rain.fox.ogmr.modular;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * 模块化多方块的<b>无线对接表</b>。
 *
 * <p>
 * Java 重写自 GTEternalTime 的 {@code ETModuleNetwork}。
 *
 * <p>
 * 主机成型时登记、结构失效或区块卸载时注销；单元按「同维度 + 距离」找最近的一台主机。
 *
 * <p>
 * ⚠️ 三处生命周期必须成对（成型登记、失效注销、卸载注销）。漏一处就会在表里留悬空引用，
 * 之后每台单元都要白测一遍。只在服务端线程访问，因此不做并发保护；
 * 表本身用 {@link WeakHashMap} 装，机器被回收时会自己掉出去，作为最后一道保险。
 */
public final class ModuleNetwork {

    private ModuleNetwork() {}

    /** 已成型的主机（弱引用）。 */
    private static final Set<ModuleHostMachine> HOSTS = Collections.newSetFromMap(new WeakHashMap<>());

    public static void addHost(ModuleHostMachine host) {
        HOSTS.add(host);
    }

    public static void removeHost(ModuleHostMachine host) {
        HOSTS.remove(host);
    }

    /** 当前登记的主机数量（调试用）。 */
    public static int hostCount() {
        return HOSTS.size();
    }

    /**
     * 在 {@code range} 格内找一台已成型的主机。
     *
     * @return 最近的一台；同距离取先登记的；找不到返回 {@code null}
     */
    public static ModuleHostMachine findHost(Level level, BlockPos pos, int range) {
        ModuleHostMachine best = null;
        double bestDist = Double.MAX_VALUE;
        double rangeSqr = (double) range * range;
        for (ModuleHostMachine host : HOSTS) {
            if (!host.isFormed()) continue;
            if (host.getLevel() != level) continue;
            double dist = host.getPos().distSqr(pos);
            if (dist > rangeSqr) continue;
            if (dist < bestDist) {
                bestDist = dist;
                best = host;
            }
        }
        return best;
    }

    /** 清空（换存档/重载时用）。 */
    public static void clear() {
        HOSTS.clear();
    }
}
