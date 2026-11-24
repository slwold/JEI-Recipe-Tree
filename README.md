# JEI Recipe Tree

在 JEI 里点一下按钮，把这张配方摊开成一棵树——要做什么、缺什么、还差几个，一眼看清。

Minecraft **1.21.1 / NeoForge** 的 JEI 客户端附属：把配方展开成可交互的**多层配方树**，
给出总耗材、余料、制作步骤与背包进度。主要给专家整合包的前期用——还没有 AE 自动合成、
什么都得手搓的那段。

## 特性

- **一键展开多层树**：从配方页或侧栏展开后自动向下递归，不用一层层点；每个节点能换子配方、换替代材料。
- **算账**：总耗材、中间产物、催化剂（不消耗）、余料分开列；流体与化学品同样进树。
- **配方库**：配方由玩家自己导入（JEI 配方页导入后就地编辑），按组管理、可整包导出；
  树跟随库里的配方展开，不做整合包自动索引。
- **库存对照**：开制作模式时材料按"够 / 缺"标状态；装了 AE2 或 RS 直接读网络存量，
  没装就只认背包与面前的容器。
- **取材料**：装了 Sophisticated Item Actions 时，配方书里多一个「取材料」，按配方把缺的
  从附近容器搬进背包（距离与领地权限由服务端二次校验）。
- **六套配色**：树界面里点按钮即换，也可在配置里选。

它只算账：不替你动机器，也不往网络里塞合成任务。

## 怎么开始

1. 在 JEI 里打开任意一个配方。
2. 点配方页上的树形按钮，配方展开成树，自动向下递归。
3. 点树里的原料节点，换一条子配方，或换一种可用材料。
4. 点树界面的制作模式按钮，背包和附近箱子按够 / 缺着色。
5. 书签上 Ctrl+左键，按依赖顺序把料填进各机器的合成格；F8 打开配方库。

## 运行要求

- Minecraft 1.21.1，NeoForge ≥ 21.1.80
- JEI ≥ 19.27.0（编译基线 19.54.0.429；实机验证 19.64.0.455 与 19.69.0.456）
- 客户端模组，服务端不用装。
- 可选：MezzConfigGui（配置界面）、Sophisticated Item Actions（取材料）、
  AE2 / Refined Storage / Integrated Terminals / Sophisticated Storage（库存来源）

## 构建

```powershell
.\gradlew.bat build --offline      # 产物 build/libs/jeirecipetree-1.21.1-<版本>.jar
```

**本地依赖（不进版本库）**：AE2 兼容层需要 AE2 的 API jar 才能编译，请自行放入
`libs/appliedenergistics2-19.2.18.jar`（对应 AE2 19.2.18，NeoForge 版）。`libs/` 已被
`.gitignore` 忽略——它是第三方二进制；没有它时 `compileOnly files('libs/…')` 会解析失败，
`compat/ae2` 下相关类无法编译。

`build` 连带跑 **17 个 headless 测试 + 22 项构建期机检**，其中有代表性的：

| 任务 | 内容 |
| --- | --- |
| `testRecipeTreeMaterials` / `testCostCalculator` | 材料模型与成本向量（正负合并、真实副产物、顺序） |
| `testCoreItemRef` / `testCoreAmounts` / `testItemRefKeyParity` | `core` 层纯逻辑：身份键、饱和算术、键迁移对拍 |
| `testCraftQueue` / `testTransferPhase` | 递归队列与转移阶段，与迁移前算法逐轮对拍 |
| `testConfigBlocks` | 配置写盘契约：拼出的 TOML 必须被 spec 判为"正确"（注释差一行就会触发整文件重写） |
| `testComplexityBudget` | 复杂度预算（CC/LOC），防止单类膨胀 |
| `verifyJeiCompatibility` / `verifyMinecraftMixinTargets` / `verifyOwnMixins` | mixin 目标类与注入点在 JEI jar 中真实存在 |
| `verifyCoreIsolation` | `core` 层零 MC / JEI 引用 |
| `verifyUiLayers` / `verifyRenderLayers` / `verifyViewportFit` | 渲染层级、UI 分层与视口适配不许回潮 |
| `verifyIdentityLayer` / `verifyStorageDrivers` / `verifyDocCounts` | 身份键进主流程、存储走驱动聚合、文档类数不许漂 |

工具链：Gradle 8.9（wrapper）+ ModDevGradle + NeoForge 21.1.80 + JEI 19.54.0.429，离线可构建。

## 配置

NeoForge `ModConfigSpec` 的 9 项（`config/jeirecipetree-client.toml`，客户端）。装了
**MezzConfig GUI** 时由它渲染（枚举是选择器而不是循环按钮），没装则退回 NeoForge 自带界面：

| 键 | 作用 |
| --- | --- |
| `recipeTreeEnabled` | 配方树总开关 |
| `recipeIngredientDirectoryEnabled` | 多候选槽位的配方目录 |
| `hideRecipeBookmarkButton` | 隐藏 JEI 原生「加入书签」按钮（书签由树接管时用） |
| `disabledRecipeTypes` | 禁用参与树的配方类型列表 |
| `automaticCraftingEnabled` | Ctrl+左键递归合成 |
| `developerMode` | 输出诊断日志（输入链路、面板区域、驱动状态） |
| `sophisticatedRestockEnabled` | 配方书的「取材料」按钮（需 Sophisticated Item Actions） |
| `treeTheme` | 配色主题：`paper` / `classic` / `blueprint` / `blueprint_dark` / `ae2` / `dark` |
| `treeTextSize` | 界面文字大小：`small` / `normal` / `large` / `huge` |

数据文件在 `config/ject/`：

| 文件 | 作用 |
| --- | --- |
| `recipe_defaults.json` | 按输出保存的默认配方（每个 `*.json` 是一个分组） |
| `recipe_tree_session.json` | 跨换档 / 重启恢复的会话状态 |

## 目录

```
src/
├── core/java/com/slwold/jeirecipetree/core/   纯 JDK 层（零 MC/JEI 引用，12 个类）
│   ├── ItemRef / Amounts                      物品身份 / 饱和算术
│   ├── CostCalculator / CostVector            正负向量合并（树的计算内核）
│   ├── RecipeRecord / RecipeFingerprint       配方记录与指纹
│   ├── RecipeSource / CraftQueue / TransferPhase / CraftStepOrder
│   │                                          配方来源、递归队列、转移阶段、依赖排序
│   └── ViewportFit / StaleConfigKeys          视口适配 / 配置键迁移
├── main/java/com/slwold/jeirecipetree/
│   ├── JERT.java               入口与启动自检
│   ├── JERTConfig.java         配置
│   ├── JERTConfigReadiness.java 配置就绪回填
│   ├── client/                 配方树、库与编辑页、侧栏面板、转移、流体、存储集成
│   ├── compat/ae2|sia          可选模组适配（AE2 直接 API 驱动、SIA 补料桥）
│   └── mixin/                  16 个注入（JEI 主体 + 可选 mod 兼容）
├── test/java/                  17 个 headless 回归测试（18 个源文件，1 个是输入测试夹具）
gradle/
├── verify-*.gradle             12 个构建期机检（mixin/core/身份/存储/转移/可观测性/
│                               渲染/配置/文档/库/扫描/UI 视口）
└── compare-traces.gradle       F9 分析结果对拍（语义门禁）
```

## 已知限制

- 树按钮位于 JEI 配方页与书签侧栏，实际位置随 JEI 版本变化，升级 JEI 后需目视确认。
- 与 EMI 同时安装时两者会争抢同一界面，本模组不做让出。
- AE2 兼容层需要本地 AE2 API jar 才能编译（见「构建」）。
- `verifyJeiCompatibility` 只能证明注入点存在，不能证明行为正确；升级 JEI 需实机复查。

## 许可

MIT，见 `LICENSE`；第三方依赖与参考声明见 `THIRD_PARTY_NOTICES.md`。

---

# JEI Recipe Tree (English)

One click in JEI unfolds the recipe into a tree — what you are making, what it needs, and how
many you are short, all at a glance.

A client-side JEI add-on for Minecraft **1.21.1 / NeoForge**: recipes are laid out as an
interactive **multi-layer tree** with total costs, leftovers, craft steps and inventory progress.
It is aimed at the early stage of expert packs, before AE autocrafting is up and everything is
still crafted by hand.

## Features

- **One-click multi-layer tree**: expanding from a recipe page or the sidebar recurses downward
  on its own; every node can swap its sub-recipe or pick a different valid ingredient.
- **The math**: total costs, intermediates, catalysts (not consumed) and leftovers are listed
  separately; fluids and chemicals take part in the tree as well.
- **Recipe library**: recipes are imported by the player (imported from a JEI recipe page and
  edited in place), managed in groups and exportable as a file. The tree follows the library —
  there is no modpack-wide automatic indexing.
- **Inventory comparison**: with crafting mode on, materials are tinted by "enough / short".
  With AE2 or Refined Storage installed, stock is read straight from the network; without them,
  only the inventory and the container in front of you are counted.
- **Fetch materials**: with Sophisticated Item Actions installed, the recipe book gains a
  "fetch materials" action that pulls the missing items from nearby containers into the inventory
  (distance and claim permissions are re-checked server side).
- **Six colour themes**: switched from a button in the tree screen, or from the config.

It does the math: it will not run your machines, and it will not push crafting jobs into your network.

## Getting started

1. Open any recipe in JEI.
2. Click the tree button on the recipe page. The recipe unfolds into a tree and recurses downward.
3. Click an ingredient node to substitute another sub-recipe, or to pick a different valid ingredient.
4. Click the crafting mode button in the tree screen to tint items by "enough / short".
5. Ctrl+click a bookmark to fill the crafting grids in dependency order; F8 opens the recipe library.

## Requirements

- Minecraft 1.21.1, NeoForge ≥ 21.1.80
- JEI ≥ 19.27.0 (compiled against 19.54.0.429; verified on 19.64.0.455 and 19.69.0.456)
- Client-side mod; nothing needs to be installed on the server.
- Optional: MezzConfigGui (config screen), Sophisticated Item Actions (fetch materials),
  AE2 / Refined Storage / Integrated Terminals / Sophisticated Storage (stock sources)

## Building

```powershell
.\gradlew.bat build --offline      # output build/libs/jeirecipetree-1.21.1-<version>.jar
```

**Local dependency (not in the repository)**: the AE2 compatibility layer needs the AE2 API jar to
compile. Place `libs/appliedenergistics2-19.2.18.jar` (AE2 19.2.18, NeoForge) there yourself.
`libs/` is ignored by `.gitignore` — it is a third-party binary. Without it,
`compileOnly files('libs/…')` fails to resolve and the classes under `compat/ae2` do not compile.

`build` also runs **17 headless tests + 22 build-time checks**, including:

| Task | Contents |
| --- | --- |
| `testRecipeTreeMaterials` / `testCostCalculator` | material model and cost vector (signed merge, real by-products, order) |
| `testCoreItemRef` / `testCoreAmounts` / `testItemRefKeyParity` | `core` layer pure logic: identity keys, saturating arithmetic, key-migration parity |
| `testCraftQueue` / `testTransferPhase` | recursive queue and transfer phases, round-by-round parity with the pre-migration algorithm |
| `testConfigBlocks` | config write contract: the assembled TOML must be judged "correct" by the spec (a one-line comment difference triggers a full rewrite) |
| `testComplexityBudget` | complexity budget (CC/LOC) against single-class bloat |
| `verifyJeiCompatibility` / `verifyMinecraftMixinTargets` / `verifyOwnMixins` | mixin target classes and injection points really exist in the JEI jar |
| `verifyCoreIsolation` | `core` layer references no MC / JEI classes |
| `verifyUiLayers` / `verifyRenderLayers` / `verifyViewportFit` | render layers, UI layering and viewport fit must not regress |
| `verifyIdentityLayer` / `verifyStorageDrivers` / `verifyDocCounts` | identity keys in the main path, storage through driver aggregation, doc class counts |

Toolchain: Gradle 8.9 (wrapper) + ModDevGradle + NeoForge 21.1.80 + JEI 19.54.0.429, builds offline.

## Configuration

Nine options on NeoForge `ModConfigSpec` (`config/jeirecipetree-client.toml`, client side).
With **MezzConfig GUI** installed it renders the screen (enums become selectors instead of
cycle buttons); otherwise NeoForge's own screen is used:

| Key | Purpose |
| --- | --- |
| `recipeTreeEnabled` | master switch of the recipe tree |
| `recipeIngredientDirectoryEnabled` | recipe directory for multi-candidate slots |
| `hideRecipeBookmarkButton` | hide JEI's native "add bookmark" button (when bookmarks are handled by the tree) |
| `disabledRecipeTypes` | recipe types excluded from the tree |
| `automaticCraftingEnabled` | Ctrl+click recursive crafting |
| `developerMode` | diagnostic logs (input chain, panel areas, driver state) |
| `sophisticatedRestockEnabled` | the "fetch materials" button in the recipe book (needs Sophisticated Item Actions) |
| `treeTheme` | colour theme: `paper` / `classic` / `blueprint` / `blueprint_dark` / `ae2` / `dark` |
| `treeTextSize` | UI text size: `small` / `normal` / `large` / `huge` |

Data files live in `config/ject/`:

| File | Purpose |
| --- | --- |
| `recipe_defaults.json` | default recipes saved per output (each `*.json` is one group) |
| `recipe_tree_session.json` | session state restored across dimension changes and restarts |

## Layout

```
src/
├── core/java/com/slwold/jeirecipetree/core/   pure JDK layer (no MC/JEI references, 12 classes)
│   ├── ItemRef / Amounts                       item identity / saturating arithmetic
│   ├── CostCalculator / CostVector             signed vector merge (the tree's calculation kernel)
│   ├── RecipeRecord / RecipeFingerprint        recipe records and fingerprints
│   ├── RecipeSource / CraftQueue / TransferPhase / CraftStepOrder
│   │                                           recipe sources, recursive queue, transfer phases, dependency order
│   └── ViewportFit / StaleConfigKeys           viewport fit / config key migration
├── main/java/com/slwold/jeirecipetree/
│   ├── JERT.java               entry point and startup summary
│   ├── JERTConfig.java         config
│   ├── JERTConfigReadiness.java config readiness backfill
│   ├── client/                 recipe tree, library & editor pages, sidebar panel, transfer, fluids, storage integration
│   ├── compat/ae2|sia          optional mod adapters (AE2 direct-API driver, SIA restock bridge)
│   └── mixin/                  16 injections (JEI core + optional mod compatibility)
├── test/java/                  17 headless regression tests (18 source files, one is an input-test fixture)
gradle/
├── verify-*.gradle             12 build-time checks (mixin / core / identity / storage / transfer /
│                               observability / render / config / docs / library / scan gating / UI viewport)
└── compare-traces.gradle       F9 analysis parity (semantic gate)
```

## Known limitations

- The tree button sits on the JEI recipe page and bookmark sidebar; its exact position follows the
  JEI version, so confirm visually after upgrading JEI.
- Installed alongside EMI, the two fight over the same screens; this mod does not yield.
- The AE2 compatibility layer needs a local AE2 API jar to compile (see "Building").
- `verifyJeiCompatibility` proves injection points exist, not that behaviour is correct; re-check
  in game after upgrading JEI.

## License

MIT, see `LICENSE`; third-party dependencies and reference notices in `THIRD_PARTY_NOTICES.md`.
