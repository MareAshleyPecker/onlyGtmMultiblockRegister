# 烂尾项目ing


# onlyGtmMultiblockRegister（ogmr）

一个**独立**的「格雷式」多方块注册工具库 —— 把 GregTech Modern 的多方块注册体系整体拆出来，
去掉 GT 内容与 GTCEu 依赖，做成任何 mod 都能直接用的库。

- modid：`ogmr`
- MC：1.20.1 ／ Forge：`47.4.20`
- 依赖：**只有 Minecraft Forge + LDLib2**（可选 JEI 用于多方块预览）
- **不依赖 GTCEu**：源码里没有任何 `com.gregtechceu.*` 的引用

---

## 1. 这个库解决什么问题

写一个多方块机器，正常情况下要同时伺候四套系统：结构图案匹配、方块/物品/方块实体注册、
仓室能力登记、以及 UI（机器面板 + JEI 配方页 + 多方块预览）。GTM 把这四件事做得很好，
但它们是 GTCEu 内部的类，别的 mod 想用要么整体依赖 GTCEu，要么各写各的。

本库把这一整套**拆出来独立发布**：注册逻辑、结构匹配、UI 装配、配方类型与配方注册、
addon 工具链，全部在库内自洽；GTM 只作为「设计参考」，不是依赖。

---

## 2. 模块划分

| 需求 | 包 | 说明 |
|---|---|---|
| ① 多方块注册 | `api.registry.builder.MultiblockMachineBuilder`、`api.machine.MultiblockMachineDefinition` | 结构图案必填、示例结构、外观方块、仓室外观与排序、拆机返还 |
| ① 机器 UI 绘制 | `api.gui.MachineUI`、`api.gui.MachineUIWidget`、`api.gui.factory.MachineUIFactory` | 流式装配器：背景/标题/槽位/储罐/进度条/文本/玩家背包；**右键机器即开**；支持 `.mui` 自定义 UI |
| ② 仓室注册 | `api.registry.builder.PartBuilder`、`api.machine.multiblock.PartAbility`、`api.machine.multiblock.part.MultiblockPartMachine` | 仓室 = 可被结构替换的一格；能力按「档位 → 方块」登记 |
| ② 仓室 UI 绘制 | `api.gui.PartUI` | 简化版机器面板 |
| ③ rtui 绘制 | `api.recipe.ui.RecipeTypeUI`、`api.gui.RecipeTypeUIWidgets`、`api.gui.editor.OGMRRecipeTypeUIProject` | 配方类型 UI；`.rtui` 工程可被 LDLib 编辑器打开并热重载 |
| ④ RecipeType 注册 | `api.recipe.OGMRRecipeType` | 输入输出上限、分组、图标、音效、UI 挂载 |
| ⑤ 配方注册 | `api.recipe.RecipeBuilder`、`api.recipe.OGMRRecipeSerializer` | 流式 builder + datagen（`FinishedRecipe`）+ 运行时配方表 |
| ⑥ Addon 工具类 | `api.addon.@OGMRAddon`、`IOGMRAddon`、`AddonFinder`、`AddonBootstrap` | 注解扫描 + 固定阶段回调（对应 GTCEu 的 `@GTAddon`/`IGTAddon`） |
| ⑥ JEI 多方块预览 | `integration.jei.*` | 多方块结构的多页预览、翻层/旋转、hover 显示方块名 |
| ⑦ 能量系统 | `api.energy.*` | `IEnergyType` 可扩展 + `EnergyTypes` 注册表；内置 EU/FE/AE/RF/J，换算系数按 Mekanism / AE2 / CoFH 核对；仓室尺寸由使用者注册 |
| ⑦ 配方内容种类 | `api.recipe.content.*` | `IContentKind` 可扩展 + `ContentKinds` 注册表；内置物品/流体，第三方可加能量、魔力等 |
| ⑦ 创造标签 | `api.registry.OGMRCreativeTab` | 一行给 addon 建物品栏标签（不建的话机器只能用 `/give` 拿） |
| 模块化 | `modular.*` | 主机/单元/无线对接表；「模块物品决定等级」的多方块基类 |
| 多线程 | `threading.*` | 一台多方块同时跑 N 条配方，线程仓提供线程数 |

基础层：

- `api.registry.OGMRRegistry` —— 自带一层 K/V 注册表（定义对象不是 Forge 注册项），带冻结语义；
- `api.registry.OGMRRegistries` —— `MACHINES` / `MULTIBLOCKS` / `RECIPE_TYPES` / `PART_ABILITIES` 四张表；
- `api.pattern.*` —— 结构图案子系统（`FactoryBlockPattern` / `TraceabilityPredicate` / `Predicates` / `MultiblockShapeInfo`）；
- `api.lang.OGMRLang` —— 语言键收集器（基类里硬编码的原因文本在这里登记，datagen 统一写出中英两份）。

---

## 3. 构建

### 3.1 依赖清单（需要你自己准备）

| 依赖 | 必需 | 说明 |
|---|---|---|
| Minecraft Forge `1.20.1-47.4.20` | 是 | ForgeGradle 自动拉 |
| **LDLib2** `curse.maven:ldlib-626676:7809449` | 是 | UI 与方块实体同步的底座 |
| JEI `15.20.0.130`（`*-common-api` + `*-forge-api`） | 否 | 只有多方块预览需要；`-PenableJei=false` 可关掉 |

> ⚠️ 只声明 JEI 的**两个 API jar**，不要声明 `jei-1.20.1-forge`（整包）：
> 它的 POM 会拽出 `jei-1.20.1-core/common/lib/gui` 一串内部模块，ForgeGradle 的
> `__obfuscated` 配置会去解析它们，缺一个 jar（离线环境很常见）整个构建就失败。
> 开发时要真跑 JEI 预览，把 JEI 整包丢进 `run/mods/` 即可。

```bash
gradlew build                     # 完整构建（含 JEI 集成）
gradlew build -PenableJei=false   # 无 JEI 环境下构建
gradlew runData                   # 数据生成（语言 + 配方）
```

产物：`build/libs/ogmr-1.0.0.jar`（+ `-sources.jar`），`reobfJar` 已接在 `jar` 之后。

> 国内网络：`settings.gradle` 与 `build.gradle` 已把阿里云 / 华为云 / 腾讯云 / BMCLAPI
> 排在官方源之前。

### 3.2 关于 gradle.properties

`org.gradle.java.home` 指向本机的 JDK 17（Gradle 8.8 跑不了 Java 25）。换机器要改这一行，
或者删掉让它走 `JAVA_HOME`。

### 3.3 配置项怎么读

每个配置项都有一个静态 getter，**请用它而不是自己摸 `OGMRConfig.INSTANCE`**：

```java
int threads   = OGMRConfig.getDefaultThreadCount();
int maxThreads= OGMRConfig.getMaxThreadCount();
int hostRange = OGMRConfig.getDefaultHostRange();
int recheck   = OGMRConfig.getDefaultHostRecheckInterval();
boolean logAddons = OGMRConfig.isLogAddonDiscovery();
boolean preview   = OGMRConfig.isAllowStructurePreview();
```

getter 做了三件事：

1. **配置未加载时降级**：Forge 的 `ConfigValue#get()` 在配置加载前会抛 `IllegalStateException`，
   而机器类的静态初始化、`@OGMRAddon` 扫描等可能跑得比配置加载更早。getter 这时返回该配置项的
   **声明默认值**（`OGMRConfig.DEFAULT_*` 常量），所以调用方不需要到处写 try/catch；
2. **热重载即时生效**：每次调用都重新读，不做缓存；整合包改完配置不用重启；
3. 需要原始 `ConfigValue`（监听变更、做 UI）时才用 `INSTANCE` 上的公开字段；
   addon 也可以监听 `ModConfigEvent`。

> ⚠️ `threading.shareThreadPoolAcrossMachines` 目前是**保留项，没有任何读取方** ——
> 本库多线程的「线程」是逻辑配方槽位（跑在服务端主线程），并不创建 OS 线程池。

---

## 4. 快速上手

```java
// ① 建一个注册器（一个 mod 一个）
public final class MyRegistrar {
    public static final MachineRegistrar REGISTRAR = new MachineRegistrar("mymod");
}

// ② 写 addon 入口
@OGMRAddon(modId = "mymod")
public class MyAddon extends AbstractOGMRAddon {
    public MyAddon() { super("mymod"); }

    @Override public void initialize() { MyRegistrar.REGISTRAR.attach(AddonBootstrap.modBus()); }

    @Override public void registerMultiblocks(OGMRRegisterEvent.RL<MultiblockMachineDefinition> event) {
        MyMultiblocks.init();
    }
}

// ③ 注册一台多方块
public static final MultiblockMachineDefinition FOUNDRY =
    MyRegistrar.REGISTRAR.multiblock("foundry", FoundryMachine::new)
        .tier(OGMRValues.HV)
        .recipeType(MyRecipeTypes.FOUNDRY)
        .appearanceBlock(CASING_STEEL)
        .pattern(def -> FactoryBlockPattern.start()
                .aisle("XXX", "XXX", "XXX")
                .aisle("X#X", "X X", "X#X")
                .aisle("XSX", "XXX", "XXX")
                .where('S', Predicates.controller(def.getBlock()))
                .where('X', Predicates.blocks(CASING_STEEL)
                        .or(Predicates.autoAbilities(def.getRecipeTypes())))
                .where('#', Predicates.air())
                .build())
        .register();

// ④ 注册一个仓室
public static final MachineDefinition ITEM_IMPORT_LV =
    MyRegistrar.REGISTRAR.part("lv_item_import_bus", h -> new ItemBusPartMachine(h, OGMRValues.LV, true))
        .tier(OGMRValues.LV)
        .abilities(PartAbility.IMPORT_ITEMS)
        .register();

// ⑤ 注册配方类型与配方
public static final OGMRRecipeType FOUNDRY_TYPE =
    OGMRRecipeType.register(new ResourceLocation("mymod", "foundry"), "mymod");

FOUNDRY_TYPE.recipeBuilder("steel_from_iron")
    .input(new ItemStack(Items.IRON_INGOT, 3))
    .output(new ItemStack(Items.IRON_BLOCK))
    .duration(200).eut(OGMRValues.V[OGMRValues.HV])
    .save(provider);
```

---

## 5. 能量系统（需求 7）

- 能量种类由 **`IEnergyType` 接口**定义（**不是枚举**，可扩展）：内置五种是
  `EnergyTypes.FE` / `EnergyTypes.EU` / `EnergyTypes.AE` / `EnergyTypes.RF` / `EnergyTypes.J`，
  **每个仓室自己选**；
- 内部一律以 **FE** 存储，对外按该仓室配置的能量种类换算与显示；
- 换算关系（`EnergyConversion.overrideRatios(...)` 或 `EnergyTypes.overrideRatio(type, x)` 可在配置里覆盖）：

  | 关系 | 值 | 出处（打开 mod 本体核对的） |
  |---|---|---|
  | 1 FE | 1 FE | 内部基准（Forge Energy） |
  | 1 EU | 4 FE = 10 J | GT/IC2 惯例；Mekanism `ic2ConversionRate` 默认 **10** |
  | 1 AE | 2 FE = 5 J | AE2 `PowerUnits.powerRatioForgeEnergy` 默认 **0.5** |
  | 1 RF | 1 FE | CoFH `IRedstoneFluxStorage extends IEnergyStorage`（无换算因子） |
  | 1 FE | 2.5 J | Mekanism `forgeConversionRate` 默认 **2.5** |

  > 这几条不是查资料抄的，是**打开各 mod 的 class 文件核对的**：
  > AE2 的 `PowerUnits.convertTo(target, amount)` 算 `amount × this.ratio ÷ target.ratio`，
  > `AE.ratio = 1.0`、`FE.ratio = 0.5`（默认）→ 恰好 ×2；
  > CoFH 的 `IRedstoneFluxStorage` **一个额外方法都没有**，就是 `IEnergyStorage` 的子接口 → RF 是 FE 的别名；
  > Mekanism 的 `GeneralConfig.forgeConversionRate` / `ic2ConversionRate` 默认值分别是 2.5 / 10。

### AE2 / 热力膨胀 / Mekanism 怎么接进来

**三家都不需要单独的适配器**，因为它们对外的能量面最终都落在 Forge Energy 上：

| mod | 它对外暴露什么 | 结果 |
|---|---|---|
| AE2 | `appeng.helpers.ForgeEnergyAdapter implements IEnergyStorage` | Energy Acceptor 按 FE 推拉 |
| 热力膨胀 / CoFH | `IRedstoneFluxStorage extends IEnergyStorage` | RF 就是 FE，直连 |
| Mekanism | `ForgeEnergyCompat` 暴露 `ForgeCapabilities.ENERGY`，内部按 `feConversionRate` 折成 J | 按 FE 推拉 |

所以只要把仓室的 Forge 能量口露出去，三家都能给它充能：

```java
IEnergyStorage forgeSide = myEnergyHatch.getContainer().asForgeStorage();
```

面板上想按某家的单位报数，就把那台仓室的 `IEnergyType` 设成对应的（`EnergyTypes.RF` / `AE` / `J`），
`getStoredInUnit()` / `getCapacityInUnit()` / `formatInUnit(long)` 会直接按该单位给数。

> ⚠️ Mekanism 的两个倍率是**玩家可配置**的；整合包改过之后用
> `EnergyTypes.overrideRatio(EnergyTypes.J, 1.0 / 新的fePerJoule)` 对齐即可。

### 用 IO 声明机器是「用电器」还是「发电机」

`OGMRRecipeType` 带一个**能量方向**（复用库里的 `IO` 枚举，全库只有这一套 IO 词汇）：

| 声明 | 含义 | 典型机器 |
|---|---|---|
| `IO.IN`（**默认**） | 用电器：机器吃能量 | 绝大多数加工机 |
| `IO.OUT` | 发电机：机器产能量（配方 `eut` 为负） | 内燃机、涡轮 |
| `IO.BOTH` | 双向（既耗电也发电） | 可逆储能 / 能量转换机 |
| `IO.NONE` | 不涉及能量 | 纯结构机、只搬物品流体 |

```java
public static final OGMRRecipeType GENERATOR_RECIPES =
        OGMRRecipeType.register(Ogmr.id("generator_recipes"), "mymod")
                .energyIO(IO.OUT);        // ← 只多这一行

// 之后什么都不用改：
REGISTRAR.multiblock("my_generator", MyGeneratorMachine::new)
        .recipeType(GENERATOR_RECIPES)
        .pattern(def -> FactoryBlockPattern.start()
                ...
                .where('X', Predicates.blocks(CASING).or(Predicates.autoAbilities(def.getRecipeTypes())))
                .build())
        .register();                       // ← 自动变成「发电机 + 要能源输出仓」
```

声明之后有三处**自动接线**，都不用手写：

1. **结构仓室**：`Predicates.autoAbilities(types...)` 按它决定要**能源输入仓**（`IN`）还是**能源输出仓**（`OUT`），
   `BOTH` 两个都要，`NONE` 一个都不要；传多个配方类型时按并集算。
   —— 在此之前 `autoAbilities` 只能一律按「耗电型」处理，发电机得手写 6 参数重载。
   同一处它还开始读 `maxItemInputs/maxItemOutputs/maxFluidInputs/maxFluidOutputs`：
   某个方向上限是 0 就不再要求对应仓室（早先也是无脑全开）。
2. **机器类型**：`MultiblockMachineBuilder#register()` 在**你没显式调 `.generator(...)`** 时，
   按配方类型自动把多方块标成发电机（`MultiblockMachineDefinition#isGenerator()`）。显式调过就以你为准。
3. **机器逻辑**：机器实现里用 `type.isConsumer()` / `type.isGenerator()` / `type.usesEnergy()` 分支自己的行为
   （面板显示「发电 XX EU/t」还是「耗电 XX EU/t」）。

不确定该填什么就不填（保持默认 `IN`）；想让它自己判断，可以等配方都注册完之后调一次：

```java
// 按已注册配方的 eut 正负推断：有发电配方→OUT，有耗电配方→IN，两种都有→BOTH
recipeType.inferEnergyIO();
```

> ⚠️ `inferEnergyIO()` 必须在配方注册完之后调用（例如 `FMLCommonSetupEvent`），注册期太早会一条都扫不到。

测试包里放了对照实验：`ogmr:test_generator` 与 `ogmr:test_multiblock` **结构一模一样**，
只有配方类型的能量方向不同（`OUT` vs 默认 `IN`），因此前者自动要能源输出仓、自动是发电机。

### 加一种自己的能量（RF / 魔力 / 任意）

能量种类是接口不是枚举，所以第三方不需要改本库源码：

```java
// 一行注册：id / 缩写 / 英文名 / 中文名 / 「1 个本单位 = ? FE」
public static final IEnergyType RF = EnergyTypes.register(
        new AbstractEnergyType("rf", "RF", "RF (Redstone Flux)", "RF（红石能量）", 1.0) {},
        "redstone_flux", "红石能量");   // 别名：配置里写别名也认

// 然后就能当单位用：
new EnergyHatchPartMachine(holder, tier, size, RF, true);
EnergyTypes.displayTypes();                        // 面板上想显示几种就加几种
EnergyTypes.overrideRatio(RF, 2.0);                // 整合包改换算比
EnergyConversion.convert(1000, EnergyTypes.EU, RF) // 任意两种能量互转
```

要点：
- 只需回答 **`1 个本单位 = ? FE`**（`getFePerUnit()`），正反向换算、取整、显示全部自动接上；
- 实现要**无状态**（`getFePerUnit()` 会被高频调用）；运行期覆盖走 `EnergyTypes.overrideRatio`，
  **不会写回你的对象**，所以类型可以做成不可变；
- `EnergyTypes.byName(String)` 认 id / 缩写 / 别名，**认不出回落到 FE 而不是 null** ——
  配置写错名字不该让机器加载崩掉；
- 旧的枚举 `EnergyUnit` 保留为**兼容层**（`implements IEnergyType`，方法全部转发到
  `EnergyTypes`），老代码 `EnergyUnit.FE` 仍能编译；新代码请直接用 `IEnergyType`。

- **能源仓容量不写死在库里**：`EnergyHatchSizes.register(new EnergyHatchSize(...))` 由使用者自行定义；
  库里只提供一份 `registerDefaultPreset()`（Modular Machinery 的 8 级数值）作为可选预设。

## 6. 与 GTM 的关系

本库的**设计**大量参考 GregTech Modern（LGPL-3.0）的 `api.machine` / `api.pattern` / `api.gui` /
`api.registry` 等包，但代码是重写的、并且去掉了 GT 内容与 GTCEu 类型。因此：

- 本库与 GTM **可以共存**，互不依赖；
- 本库注册的机器不会出现在 GTM 的机器表里，反之亦然；
- 若将来要做桥接（把本库的定义映射到 GTM），那应该是一个单独的 adapter mod，而不是让本库反向依赖 GTM。

## 7. 许可

LGPL-3.0（与 GTM 一致，便于参考其实现）。

---

## 8. 代码约定（写给要改这个库的人）

### 8.1 Lombok：什么时候用，什么时候**不**用

本库用 Lombok，但只让它干「纯字段访问器」这一件事。判定标准是**方法体只有 `return field;` / `this.field = arg;`**。

**用 Lombok**：

```java
@Getter
@Accessors(chain = true)   // 仅在原来就返回 this 类型时
private int tier;
```

**不要交给 Lombok，手写**（否则要么编不过，要么悄悄改了 API）：

| 情况 | 为什么 | 例子 |
|---|---|---|
| 返回**泛型类型参数**的链式 setter | Lombok 只能生成声明类的类型，生不出 `B` | `MachineBuilder` / `PartBuilder` / `MultiblockMachineBuilder` 里的 `public B tier(int)` |
| 无 `get`/`is`/`set` 前缀的 DSL 方法 | 那是 DSL，不是访问器 | `size(...)`、`background(...)`、`pattern(...)`、`title()` |
| getter 里有逻辑 | 惰性初始化 / 缓存 / 兜底 / 规范化都得看得见 | `MachineDefinition#getShape(Direction)`（按朝向缓存）、`MetaMachine#getFieldHolder()` |
| 字段名与 Lombok 生成名不一致 | 例如 `boolean formed` 现在暴露的是 `getFormed()`，Lombok 只会生成 `isFormed()` | 这类保留手写 |
| record 的访问器 | record 自带 | `EnergyHatchSize`、`Content` |

`@Accessors(chain = true)` 可以用（保持「返回 this」的现状），但**不要用 `fluent = true`** ——
那会去掉 `get`/`set` 前缀，等于换掉公开 API。

> 接口实现（`@Override`）可以删掉手写的、交给 Lombok —— 前提是 Lombok 生成的签名与原来**逐字一致**
> （名字、返回类型、参数）。`boolean` 字段要特别小心 `is` / `get` 的差异。


---

## 9. 怎么测试（`rain.fox.ogmr.test` 测试包）

工程里单开了一个 **`rain.fox.ogmr.test`** 包，放了台「什么都能验一遍」的测试机。
它不是库的一部分，直接开 `runClient` 就能用；发布时可以整个删掉。

### 9.1 注册了哪些东西

| 注册名 | 是什么 |
|---|---|
| `ogmr:test_multiblock` | 3×3×3 多线程多方块（控制器） |
| `ogmr:lv_item_input_bus` / `ogmr:lv_item_output_bus` | 物品总线（各 9 格，走 Forge `ITEM_HANDLER`） |
| `ogmr:lv_energy_hatch` / `ogmr:lv_energy_output_hatch` | 能源仓（分别按 **EU** / **RF** 显示，验证单位换算） |
| `ogmr:lv_thread_hatch` / `mv_thread_hatch` / `hv_thread_hatch` | 线程仓（2 / 4 / 8 条线程） |
| `ogmr:test_recipes` | 配方类型 + 3 条配方 |

### 9.2 开测

```bash
gradlew runClient
```

```mcfunction
/give @p ogmr:test_multiblock
/give @p ogmr:lv_item_input_bus
/give @p ogmr:lv_item_output_bus
/give @p ogmr:lv_energy_hatch
/give @p ogmr:lv_thread_hatch 4
```

搭法（3×3×3 铁块外壳，中间一层是空腔；`X` = 铁块，空腔里放仓室）：

```
   XXX   XXX   XXX
   XXX   X X   XSX      ← 中间层正面中心是控制器 S
   XXX   XXX   XXX
```

然后：

1. 往 **输入总线** 里塞 `minecraft:iron_ingot`（配方：1 铁锭 → 1 金锭，100 tick）；
2. 想看多线程并行，就塞 `minecraft:sand`（1 沙 → 1 玻璃，20 tick）——三个 LV 线程仓 = 6 条线程，
   面板上会同时出现多行进度；
3. 输出总线会收到产物；漏斗/管道也可以直接往总线上接（能力分发已接好）。

### 9.3 这个包顺便验证了什么

| 链路 | 怎么看出来 |
|---|---|
| 结构图案匹配 | 外壳摆错就永远不成型；面板会显示成型状态 |
| 多方块注册 | 控制器物品能放下、能打开面板 |
| 仓室注册 + 能力 | 漏斗能往输入总线塞东西、从输出总线抽东西 |
| 实时搬运 | 输入总线的铁锭**真的会变少**，输出总线**真的会多出金锭**（不是空转） |
| 多线程 | 装几个线程仓就有几倍吞吐；面板逐线程显示进度 |
| 能量系统 | 能源仓按 EU / RF 显示同一个存量；线缆可直接充能 |
| 配方注册 | 三条配方同时可用；`gradlew runData` 会把它们写成数据包 JSON |
| rtui / 机器 UI | 右键任意机器都会开面板：多方块是「进度条 + 线程数文本」，仓室是「按仓储自动摆的槽位 + 状态文本」 |
| UI 自动注册 | 日志里每台机器一行 `UI of ogmr:xxx = 176x166, entries=.., autoLayout=..`（`gradlew runData` 就能看到） |
| 自定义配方内容种类 | `ogmr:test_recipes` 里的 `energy_charge` 配方，输入是自定义种类 `test_energy`（见 `TestContentKinds`） |
| JEI 预览 | 装 JEI 后在 JEI 里能翻到「多方块结构」分类，看到这台机的 3×3×3 预览 |

### 9.4 语言文本

库自己的语言文件是**手写随库发布**的（`src/main/resources/assets/ogmr/lang/{en_us,zh_cn}.json`），
所以测试机的中英文名、面板文案**开箱就有**，不需要先跑数据生成。

`OGMRLangProvider`（datagen）因此**不再为 `ogmr` 命名空间生成文件**，只负责 addon 自己的命名空间 ——
避免同一个路径在两个 source root 里各一份、jar 里出现重复条目。

> 结构图案的错误提示目前用的是 GTM 的语言键（`gtceu.multiblock.pattern.error*`），
> 在没装 GTM 的环境里会显示成键名。这是结构图案子系统从 GTM 移植时保留原文案的结果，
> 要不要改成 `ogmr.*` 键由你定。

---

## 10. 资源自动生成（`gradlew runData`）

### 10.1 机器的 blockstate / 模型不用手写

注册时给一句贴图，剩下三份 JSON 由 datagen 产出（落在 `src/generated/resources`）：

```java
REGISTRAR.part("lv_item_bus", ItemBusPartMachine::new)
        .tier(OGMRValues.LV)
        .abilities(PartAbility.IMPORT_ITEMS)
        .modelTexture(new ResourceLocation("mymod", "block/casing"))   // ← 只多这一行
        .register();
```

`gradlew runData` 之后得到：

```
assets/<ns>/blockstates/<name>.json   ← catch-all 变体（facing/active/formed 全指同一个模型）
assets/<ns>/models/block/<name>.json  ← cube_all，贴图就是给的那张
assets/<ns>/models/item/<name>.json   ← 物品栏模型
data/<ns>/recipes/*.json              ← addon 在 registerRecipes() 里交出来的配方
```

没写 `.modelTexture(...)` 的机器用兜底贴图（铁块），**不会**渲染成紫黑块。贴图 PNG 本身不生成。

### 10.2 三个坑（都真踩过）

**① 别把 datagen 的 `--output` 指到 `src/main/resources`。**
Forge 的 HashCache 会把「输出目录里不是本次生成出来的文件」当 stale **删掉** ——
手写的 `mods.toml`、`pack.mcmeta`、lang 会被清空。保持 `--output src/generated/resources`。

**② 注册器实例必须是同一个。**
`AbstractOGMRAddon.registrar()` 是**懒创建**的；如果 addon 里另放了静态注册器
（`public static final MachineRegistrar REGISTRAR = new MachineRegistrar(modId)`），
必须在 `initialize()` 里挂**那个**：

```java
@Override public void initialize() {
    attach(MyRegistrar.REGISTRAR);   // ✅ 不要调 super.initialize()（它挂的是另一个实例）
}
```

挂错的表现很阴：机器都进了本库的注册表、日志一切正常，但**方块从没进 Forge 注册表** ——
编译期毫无提示，进游戏 / 跑 datagen 才炸成 `NullPointerException: Registry Object not present`。

**③ 写 datagen 代码时用 `BuiltInRegistries.BLOCK.get(id)` 取方块，不要用 `RegistryObject#get()`** ——
后者在 datagen 环境还没解析，会抛上面那个 NPE。

**④ 方块渲染方式默认必须是静态模型。** `MachineBlock#getRenderShape` 返回
`ENTITYBLOCK_ANIMATED` 表示「静态模型别画、交给方块实体渲染器（BER）」—— 本库默认**不注册 BER**，
所以那样写出来的机器在世界里**什么都不画**（看起来像「贴图是空的」，而物品栏里一切正常）。
要动态渲染就先自己在客户端注册 BER，再在注册时声明 `.entityRenderer(true)`。

---

## 11. 机器界面（需求 ①）

### 11.1 右键就能开，不用配

注册任何机器（单方块 / 多方块 / 仓室）时，builder 都会保证它**有一个可打开的界面**：
addon 什么都不写也照样能开 —— 没给 UI 时自动造一个零配置界面。

```java
// 自动界面 = 标题 + 玩家背包 + 右侧信息栏（MetaMachine#addDisplayText 的内容）
//           + 按机器实际暴露的物品/流体仓储自动摆的槽位（最多 18 格物品 + 3 个储罐）
REGISTRAR.part("lv_item_bus", ItemBusPartMachine::new)
        .tier(OGMRValues.LV)
        .abilities(PartAbility.IMPORT_ITEMS)
        .register();     // ← 就这一行，右键即有 9 格物品总线面板
```

打通这条链路的三段（都在库里，addon 不用管）：

1. `api.gui.factory.MachineUIFactory`（LDLib `UIFactory`）——把「打开哪台机器的界面」压成一个方块坐标同步给客户端；
   由 `Ogmr` 构造期调用 `MachineUIFactory.register()` 注册。**漏注册的症状是「客户端收到包但界面打不开」。**
2. `MetaMachine#tryToOpenUI` —— 服务端发起打开动作（`MachineBlock#use` 调它）；
   `MetaMachine` 顺带实现了 LDLib 的 `IUIHolder`。
3. `MetaMachine#createUI` —— 用 `MachineDefinition#getMachineUI()` 建 `ModularUI`。

### 11.2 想要自己的布局

```java
.ui(MachineUI.create("maceration", MyIds.id("maceration"))
        .title()
        .itemSlot(26, 20, 0, true)
        .itemSlot(26, 42, 1, false)
        .progress(62, 33, 24, 16, ProgressDirection.LEFT_TO_RIGHT,
                  machine -> machine instanceof MyMachine m ? m.getRecipeLogic().getProgressPercent() : 0d)
        .text(8, 58, machine -> Component.translatable("mymod.maceration.hint"))
        .playerInventory(8, 84))
```

- 动态文本/进度的取值器**带着机器**：一份 `MachineUI` 是所有同类机器共用的模板，装配时才知道是哪一台。
- 槽位/储罐按《下标》绑定（`.itemSlot(x, y, index, output)`），仓储从方块实体的 Forge 能力上取；
  拿不到能力时降级成纯背景占位框，不会崩也不会显示假数据。
- 编辑器路线不变：`assets/<ns>/ui/machine/<path>.mui` 存在时优先用它反序列化 + 只做数据绑定
  （LDLib 的 UI 编辑器产出，保存后调 `EditableMachineUI#reloadCustomUI()` 热重载）。
- 默认的右侧信息栏贴在面板外，`ModularUI` 的尺寸会自动加上它（`MachineUIWidget#getFullWidth()`），
  不想显示就 `setInfoPanelVisible(false)`。

### 11.3 界面贴图从哪来

**能借就借**：物品槽、流体槽、面板背景这些 LDLib 都自带现成品，本库直接用它的，不再自己画一张
（早期版本自己生成过，纯属重复劳动，已删）：

| 用途 | 用的贴图 |
|---|---|
| 物品槽 | `SlotWidget.ITEM_SLOT_TEXTURE` |
| 流体槽 | `TankWidget.FLUID_SLOT_TEXTURE` |
| 机器面板背景 | `ResourceBorderTexture.BORDERED_BACKGROUND` |
| rtui 面板背景 | `ResourceBorderTexture.BORDERED_BACKGROUND_BLUE` |

只有两张（组）是 LDLib 没有的，由 `tools/GuiTextureGenerator.java` 生成：

- `gui/base/info_background.png` —— 信息栏的半透明底板（它贴在面板外、直接压在世界画面上，必须半透明）；
- `gui/progress/bar_{background,filled}.png` —— 进度条（LDLib 的 `ProgressWidget` 只收贴图，不自带）。

```bash
java tools/GuiTextureGenerator.java   # 默认写到 src/main/resources/assets/ogmr/textures
```

想完全不依赖这几张 png，客户端初始化时调一次 `GuiTextures.setForceFallback(true)`，
它们会退化成「纯色铺底 + 描边」的代码贴图（槽位与面板不受影响 —— 那些本来就来自 LDLib）。

**方块贴图**（仓室的口、覆盖层、外壳）也不在这里生成 —— 那些是从 GTM 搬来的美术资源，见 §14。

---

## 12. 仓室的朝向与「口」（port facing）

### 12.1 朝向设定 `RotationState`

| 值 | 方块状态属性 | 谁在用 |
|---|---|---|
| `NONE` | 无 | 外壳、没有正面的方块 |
| `Y_AXIS` | 原版 `horizontal_facing`（4 向） | 普通机器、多方块控制器（**默认**） |
| `ALL` | 原版 `facing`（6 向） | **仓室**（`PartBuilder` 默认就给这个） |

```java
REGISTRAR.part("lv_item_input_bus", ItemBusPartMachine::new)
        .tier(OGMRValues.LV)
        .abilities(PartAbility.IMPORT_ITEMS)
        .port()                       // ← 开一个「口」；贴图不写就用库自带那张
        .register();
```

**摆放朝向**（照 GTM `MetaMachineBlock#getStateForPlacement` 的规则）：玩家放置时朝向
= `player.getDirection().getOpposite()`，也就是**口正对着玩家**；若玩家站在方块正上/正下方附近
朝下/朝上放，且该方块是 `ALL`（仓室），则改成朝 `UP` / `DOWN` —— 仓室摆在地板、天花板上时口也朝外。

> ⚠️ `ALL` 用的属性对象是原版 `facing`，和 `MachineBlock.FACING`（水平四向那个常量）**不是同一个属性**。
> 读朝向一律走 `definition.getFacing(state)`：
>
> ```java
> Direction facing = def.getFacing(level.getBlockState(pos));   // ✅ NONE 时给 NORTH，不抛异常
> // state.getValue(MachineBlock.FACING)                        // ❌ 对六向仓室抛 IllegalArgumentException
> ```

### 12.2 「口」怎么渲染：只画这一面，且在最上层

**方向不进模型文件**：一份模型只画一次（层固定在模型的 SOUTH 面），六个朝向由 blockstate 的
`x`/`y` 旋转复用同一份文件 —— 这是原版 `BlockStateProvider#directionalBlock` 与 GTM 的做法，
所以一台仓室是「**1 份模型 + 6 条变体**」，不是「6 份朝向各异的模型」。

```
models/block/<name>_port.json           ← 底盘整块 + 只在 SOUTH 面有贴图的薄片
blockstates/<name>.json                 ← facing=north/south/east/west/up/down → 同一份模型 + 旋转
models/item/<name>.json                 ← 物品栏仍用「底盘」模型（不带口）
```

旋转角度照抄 Forge 的 `directionalBlock`（模型正面假定在 SOUTH）：

| facing | 旋转 | facing | 旋转 |
|---|---|---|---|
| `south` | — | `up` | `x=-90` |
| `north` | `y=180` | `down` | `x=90` |
| `west` | `y=90` | | |
| `east` | `y=270` | | |

- **只这一面**：口的薄片**只定义朝外那一个 face**，其余 5 面不写 = 不渲染；
- **最上层**：底盘整体缩进 `0.001×(层数+1)`、各层依次往外排到方块表面，所以后画的层永远盖住前面的；
- **alpha**：默认 `render_type: minecraft:cutout`（口贴图带透明像素时必需）；
  贴图完全不透明时用 `.portCutout(false)` 关掉。

贴图由作者自己给：`.port(你自己的贴图)`。不写参数就是本库自带的兜底贴图
（`MachineDefinition.DEFAULT_PORT_TEXTURE` = `ogmr:block/overlay/machine/overlay_hatch`，从 GTM 搬的）。

### 12.3 覆盖层贴图（照 GTM 的 overlay 那套拆的）

机器（尤其多方块控制器）的贴图不止「一层外壳」。注册时可以给三类覆盖层，全都贴在**朝向那一面**：

| 链式方法 | GTM 对应 | 什么时候显示 | 层序 |
|---|---|---|---|
| `.overlay(贴图)` / `.overlay()` | `overlay_front` | 总是 | 1（最下） |
| `.formedOverlay(贴图)` / `.formedOverlay()` | 模型属性 `IS_FORMED` 用的 overlay | `formed=true` 时 | 2 |
| `.emissiveOverlay(贴图)` / `.emissiveOverlay()` | `overlay_front_emissive` | `active=true`（正在工作）时 | 3 |
| `.port(...)` | 仓室的「口」 | 总是 | 4（最上） |

```java
REGISTRAR.multiblock("large_foundry", LargeFoundryMachine::new)
        .tier(OGMRValues.HV)
        .recipeType(MyRecipeTypes.FOUNDRY)
        .appearanceBlock(() -> CASING)
        .modelTexture(CASING_TEXTURE)   // 底盘（六面）
        .overlay(CONTROLLER_OVERLAY)    // 正面花纹
        .formedOverlay()                // 成型后才出现的层
        .emissiveOverlay()              // 跑配方时亮起来的那层
        .pattern(...)
        .register();
```

- 数据生成会按「**层组合**」产出模型（方向不算，方向由旋转实现）：例如
  `models/block/<name>_ov_formed_act.json` = 底盘 + 正面层 + 成型层 + 发光层，
  `blockstates/<name>.json` 里对应的变体键是 `facing=east,active=true,formed=true`。
  用不到的属性不会出现在变体键里（原版语义：没写 = 通配符），所以一个控制器最多也就 4 份模型
  × 4~6 条朝向变体，不会文件爆炸。
- 覆盖层默认走 `minecraft:cutout` 渲染层（`render_type`），这样带透明像素的贴图不会把底盘糊掉；
  全不透明的贴图可以 `.overlayCutout(false)` / `.portCutout(false)` 关掉。
- 层与层之间靠「底盘整体缩进 + 每层依次靠外」实现前后关系：
  MC 要求模型元素坐标在 `[-16, 32]`，所以**不能**用「往外凸一点」的常规做法（朝下/朝北/朝西会直接越界报
  `Position out of range`）。缩进量是 `0.001 × (层数+1)`，肉眼看不出来。
- ⚠️ 模型里 {`face.texture(...)`} 收的是**引用**，必须带 `#`（`#overlay` 而不是 `overlay`）——
  少了 `#` 会生成 `"texture": "minecraft:overlay"`，游戏里就是缺失贴图。

### 12.4 成型 / 工作状态真的会写进方块状态

覆盖层靠方块状态选模型，所以这两个状态必须真的被写下去（以前是 TODO，现在接好了）：

| 状态 | 谁写 | 时机 |
|---|---|---|
| `formed` | `MultiblockControllerMachine#onStructureFormed/Invalid` | 结构成型 / 失效 |
| `active` | `RecipeLogic#setStatus` | 配方状态变化（{@code WORKING} 为真） |

两者都走 `MetaMachine#setBlockStateBoolean(...)`（值没变就不刷方块，所以每 tick 调也安全）。

### 12.5 已知边界

- 口与覆盖层都固定在**朝向那一面**（不再单独开第二个属性）—— 想让它们换面，就改朝向；
- `RotationState.NONE` + 覆盖层/口会打一条 warn 并跳过这些模型（没有朝向就表达不出画在哪一面）；
- 物品模型与 JEI 预览都用底盘模型，所以预览里看不到覆盖层与口（进世界才看得到）；
- 发光层是「静态贴图换层」，不是真正的全亮渲染（静态模型做不到 fullbright，要那种效果得自己注册 BER
  再 `.entityRenderer(true)`）。



---

## 13. 加一种自己的配方内容（`IContentKind`）

物品和流体只是**两种内置实现**，不是写死的分支。第三方想加「能量 / 魔力 / 气体」：

```java
public static final IContentKind MANA = ContentKinds.register(new AbstractContentKind(
        "mana", "Mana", "魔力") {

    @Override public Codec<Content> codec() { return MANA_CODEC; }          // JSON 形状
    @Override public void toNetwork(Content content, FriendlyByteBuf buf) { buf.writeVarInt(...); }
    @Override public Content fromNetwork(FriendlyByteBuf buf) { return Content.of(this, buf.readVarInt(), 1, 1f); }
    @Override public ItemStack representativeItem(Content content) { return MANA_BOTTLE.getDefaultInstance(); }
});
```

要点：

- 载荷放在 `Content#payload()` 里（`Content.of(kind, payload, count, chance)`）；内置两种用 `item()` / `fluid()`。
- `Content#CODEC` 靠 `ContentKinds` 按 `"type"` 字段分派，**不认识的名字会直接报错**（不会静默当成物品）。
- ⚠️ 注册必须早于数据包加载（一般放 mod 构造期），否则那种 `type` 的配方整条加载失败。
- 语言键 `ogmr.content.kind.<id>` 由 `IContentKind#registerLang()` 生成（`ContentKinds#initLang()` 会带上已注册的全部）。
- 库内部不再特判物品/流体：`OGMRRecipe#getItemInputs` 只是 `filter(ContentKinds.ITEM, ...)` 的便捷写法，
  你自己那种内容用 `OGMRRecipe.filter(MANA, inputs)` 筛。

---

## 14. 贴图来源与许可

本库**不含自制美术**，方块贴图都是从 **GregTech Modern（GTM）** 搬过来的，保留了 GTM 的原始目录结构，
方便对照与替换：

| 用途 | 路径 | 说明 |
|---|---|---|
| 仓室的口 | `block/overlay/machine/overlay_{hatch,item_hatch*,fluid_hatch*,energy_1a_*}.png` | 按用途分：物品仓 input/output、能源仓 in/out（带 `_emissive` 发光件） |
| 控制器正面 | `block/machine/overlay/front{,_active,_active_emissive,_emissive}.png` | GTM 多方块控制器的待机 / 工作态正面 |
| 外壳 | `block/casings/solid/machine_casing_solid_steel.png`、`machine_casing_heatproof.png` | 测试包用的底盘 |

- 来源：GregTech Modern（`assets/gtceu/textures/**`），与本库同为 **LGPL-3.0**，可随库一起分发；
  换掉它们不影响任何代码（贴图都是注册时用 `ResourceLocation` 指定的）。
- 界面（GUI）贴图则是本库用 `tools/GuiTextureGenerator.java` **程序生成**的（纯色 + 描边，
  零美术依赖），与上表无关。

## 15. 怎么验证「口」和覆盖层确实渲染了

覆盖层/口都只在**朝向那一面**、并且有的层只在特定状态出现，所以看一眼没看到不代表坏了。最快的验证方式：

```mcfunction
// ① 把一台机器直接摆成「正在工作 + 已成型」，一次看全三层（不用真去搭多方块）
/setblock ~ ~ ~ ogmr:test_multiblock[facing=north,active=true,formed=true] replace

// ② 只看工作态发光层
/setblock ~ ~ ~ ogmr:test_generator[facing=north,active=true] replace

// ③ 仓室的口：摆成一排不同朝向，从北/东/南/西各看一次
/give @p ogmr:lv_item_input_bus 6
```

判断要点：

- 口/覆盖层**只在朝向那一面**有 —— 从其它五个面看就是干净的外壳贴图（这是设计如此，不是没渲染）；
- `active` 只在配方逻辑真的在跑时为真（发电机喂煤、机器里塞原料），否则发光层不会出现；
- `formed` 只在多方块成型时为真；
- 世界里的模型来自 `src/generated/resources/assets/ogmr/models/block/<name>_*.json`，
  里面能看到底层与各层分别引用哪张贴图，对不上就是注册时贴图给错了。



