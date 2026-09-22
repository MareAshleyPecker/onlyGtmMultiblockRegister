package rain.fox.ogmr.data;

import net.minecraft.data.PackOutput;
import net.minecraft.data.recipes.FinishedRecipe;
import net.minecraft.data.recipes.RecipeProvider;

import java.util.List;
import java.util.function.Consumer;

/**
 * 把 addon 在 {@code registerRecipes(Consumer<FinishedRecipe>)} 里交出来的配方
 * 统一写成数据包 JSON。
 *
 * <p>
 * 拆自 GTM 的做法：GTM 的配方全部走 datagen，addon 通过 {@code IGTAddon#addRecipes}
 * 拿到一个 {@code Consumer<FinishedRecipe>} 往里塞。这里保留同样的入口，
 * 只是把「塞进来的东西」先收集起来，再交给原版的 {@link RecipeProvider} 写出。
 */
public class OGMRRecipeProvider extends RecipeProvider {

    private final List<FinishedRecipe> recipes;

    public OGMRRecipeProvider(PackOutput output, String modId, List<FinishedRecipe> recipes) {
        // RecipeProvider 的 modId 参数在 1.20.1 里并不存在，这里只是为了将来扩展保留 modId 字段
        super(output);
        this.recipes = List.copyOf(recipes);
    }

    @Override
    protected void buildRecipes(Consumer<FinishedRecipe> writer) {
        recipes.forEach(writer);
    }
}
