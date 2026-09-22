package rain.fox.ogmr.data;

import rain.fox.ogmr.api.lang.OGMRLang;
import rain.fox.ogmr.api.machine.MachineDefinition;
import rain.fox.ogmr.api.registry.OGMRRegistries;
import rain.fox.ogmr.utils.Formatting;

import net.minecraft.data.PackOutput;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.common.data.LanguageProvider;
import net.minecraftforge.data.event.GatherDataEvent;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 把本库 + addon 用 {@link OGMRLang} 登记的语言键写成 {@code en_us.json} / {@code zh_cn.json}。
 *
 * <p>
 * 为什么要自己写而不是用 Registrate 的 lang provider：本库没有 Registrate，而且语言键的来源
 * 不是「注册项」而是「基类里硬编码的原因文本」（例如「附近 %s 格内没有主机」），
 * 所以需要一个与注册表无关的收集器 —— 那就是 {@link OGMRLang}。
 */
public final class OGMRLangProvider extends LanguageProvider {

    private final Map<String, String> entries;
    private final String locale;

    private OGMRLangProvider(PackOutput output, String modId, String locale, Map<String, String> entries) {
        super(output, modId, locale);
        this.entries = entries;
        this.locale = locale;
    }

    @Override
    protected void addTranslations() {
        entries.forEach(this::add);
    }

    /** 已经为哪些 modid 生成过（避免 GatherDataEvent 重复触发时重复 addProvider）。 */
    private static final Set<String> GENERATED = ConcurrentHashMap.newKeySet();

    /**
     * 为某个 mod id 注册中英两份 provider。
     *
     * <p>
     * 写出两类键：
     * <ol>
     * <li>{@link OGMRLang} 里登记的文案（库的 + 这个 addon 在 {@code initLang()} 里加自己的）；</li>
     * <li>该命名空间下每台机器的 {@code block.<ns>.<name>} ——
     * 英文取 {@code MachineDefinition#getLangValue()}，没给就按 id 推导；
     * 中文取 addon 显式登记的（没有则先用英文兜底，至少不显示裸键）。</li>
     * </ol>
     *
     * <p>
     * 显式登记过的键优先：机器名用 {@code putIfAbsent} 补，不会覆盖 addon 自己写的文案。
     */
    public static void register(GatherDataEvent event, String modId) {
        if (!GENERATED.add(modId)) return;

        Map<String, String> en = OGMRLang.english();
        Map<String, String> zh = OGMRLang.chinese();
        addMachineNames(modId, en, zh);

        event.getGenerator().addProvider(event.includeClient(),
                new OGMRLangProvider(event.getGenerator().getPackOutput(), modId, "en_us", en));
        event.getGenerator().addProvider(event.includeClient(),
                new OGMRLangProvider(event.getGenerator().getPackOutput(), modId, "zh_cn", zh));
    }

    /**
     * 给该命名空间里的每台机器补一条 {@code block.<ns>.<name>}。
     *
     * <p>
     * 键名与 MC 的方块/物品本地化键一致（{@code BlockItem} 默认继承方块的），
     * 所以这一条同时管住了方块与物品在物品栏里的显示名。
     */
    private static void addMachineNames(String modId, Map<String, String> en, Map<String, String> zh) {
        for (MachineDefinition definition : OGMRRegistries.MACHINES) {
            if (definition == null) continue;
            ResourceLocation id = definition.getId();
            if (id == null || !modId.equals(id.getNamespace())) continue;

            String key = "block." + id.getNamespace() + "." + id.getPath();
            String english = definition.getLangValue() != null && !definition.getLangValue().isBlank()
                    ? definition.getLangValue()
                    : Formatting.toEnglishName(id.getPath());
            en.putIfAbsent(key, english);
            zh.putIfAbsent(key, english);
        }
    }
}
