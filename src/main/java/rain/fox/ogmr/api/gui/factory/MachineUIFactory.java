package rain.fox.ogmr.api.gui.factory;

import rain.fox.ogmr.Ogmr;
import rain.fox.ogmr.api.machine.IMachineBlockEntity;
import rain.fox.ogmr.api.machine.MetaMachine;

import com.lowdragmc.lowdraglib.gui.factory.UIFactory;
import com.lowdragmc.lowdraglib.gui.modular.ModularUI;

import net.minecraft.client.Minecraft;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/**
 * 机器界面的「网络通道」——LDLib 的 {@link UIFactory} 实现，逐行对照 GTM 的
 * {@code com.gregtechceu.gtceu.api.gui.factory.MachineUIFactory} 移植。
 *
 * <p>
 * 为什么打开界面需要一个工厂而不是直接 {@code new ModularUI(...)}：
 * 服务端只能决定「打开谁的界面」，真正建界面的是客户端。工厂负责把 holder（这里是
 * {@link MetaMachine}）<b>压缩成一个方块坐标</b>同步过去，客户端按坐标把 BE 找回来、重建机器实例，
 * 再调 {@link MetaMachine#createUI(Player)} 建界面。少了这一步，服务端 new 出来的 widget 树
 * 在客户端是不存在的（界面会打开但内容空白 / 直接崩）。
 *
 * <p>
 * ⚠️ <b>必须注册</b>：{@link UIFactory#register(UIFactory)} 由 {@code Ogmr} 的构造器调用
 * （LDLib 的 {@code UIFactory} 构造器<b>不会</b>自动注册自己）。漏了它的症状是
 * 「客户端收到打开界面的包，但不知道用哪个工厂，界面打不开」，日志里是
 * {@code unknown ui factory}。
 */
public class MachineUIFactory extends UIFactory<MetaMachine> {

    /** 单例（{@code Ogmr} 构造期注册进 LDLib）。 */
    public static final MachineUIFactory INSTANCE = new MachineUIFactory();

    public MachineUIFactory() {
        super(Ogmr.id("machine"));
    }

    /** 注册到 LDLib 的工厂表（幂等，重复调用只是覆盖成自己）。 */
    public static void register() {
        UIFactory.register(INSTANCE);
    }

    @Override
    protected ModularUI createUITemplate(MetaMachine holder, Player entityPlayer) {
        return holder.createUI(entityPlayer);
    }

    /** 客户端：从同步包里读回方块坐标，再按坐标找回机器。 */
    @OnlyIn(Dist.CLIENT)
    @Override
    protected MetaMachine readHolderFromSyncData(FriendlyByteBuf syncData) {
        Level level = Minecraft.getInstance().level;
        if (level == null) return null;
        if (level.getBlockEntity(syncData.readBlockPos()) instanceof IMachineBlockEntity holder) {
            return holder.getMetaMachine();
        }
        return null;
    }

    /** 服务端：机器只需要一个坐标就能在客户端被找回来。 */
    @Override
    protected void writeHolderToSyncData(FriendlyByteBuf syncData, MetaMachine holder) {
        syncData.writeBlockPos(holder.getPos());
    }
}
