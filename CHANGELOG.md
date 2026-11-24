# 更新日志 / Changelog

> 版本线 2026-11-12 重排：原先 63 条 `2.x.y` 按阶段归并为 `2.0`–`2.15`，改名与重排本身作为
> `3.0` 起点（非功能改动，不记条目）。1.x 时代（另一个项目形态）的记录不在本文件内。

## 4.3.2 — 2026-11-24

死代码清理，无行为变化。

- 删 `Reflect` 整类（3.0 起零引用）。
- 删 20 项不可达代码：`isSupported`、`hasUserDefault`、`isNuggetToIngot`、`hasBookmarkedRecipe`、
  `bookmarkedRecipe`、`drawTree` / `drawDefault`、`displayOf` / `fromLegacy`、`inventoryAmountsByRef`、
  `nonItem` / `withDisplayAmount`、`amountInContainers`、`isChemicalKey`、`WARN_4MS` / `WARN_5MS`、
  `BBD_SIDEBAR_RENDERER` / `isHighlightedIntegratedEntry` 等。
- 删 `RecipeLookup` 候选查询的纳秒计时（计数侧保留）。
- 清理 32 个未使用的 import。

## 4.3.1 — 2026-11-24

- 修 JEI 引用找回：同一配方类型下枚举全部分类建索引，优先用会话内快照；挂在第二个分类下的
  配方（如 ironfurnaces）不再丢，内核计划不再退回旧树路径。
- 合成格计入供应，与背包同口径；内容变化立即刷新。
- 自动合成兜底：网络拒收回存时产物改放背包；每次转移前先清光标；回存等待 10 秒 → 约 0.7 秒。
- 新增内核对拍测试：向量内核与独立递归实现逐项比对（200 组随机依赖图）。
- 日志收口：默认每次合成只留开始、结束、失败三行；过程明细移到开发者模式，内容不变不重复打印。

## 4.3.0 — 2026-11-24

计算层重建：材料清单、侧栏行、自动合成步骤统一由按物品记账的内核产出。

- 按物品汇总需求、按每批产出向上取整、凑整余料对后续需求可见；不再按位置建树，没有节点数上限。
- 执行顺序按依赖拓扑排序，先中间品后成品；同一配方的多批合并成一步；互相依赖的配方有明确出口。
- 需求量按真实缺口显示；落库按真实用量；流体保留 mB 身份与数量。
- 多候选槽位按选中的材料合并成一行（石镐 18 + 圆石 54 → 72，不再拆两行）。
- RTS 合成终端只读链接存储；取产物按背包增量判定，适配其"点结果格后自动补格"的行为。
- 成本行 / 侧栏行提示恢复 JEI 富渲染；侧栏翻页按整页对齐；流体与化学品图标满格绘制。

## 4.2.7 — 2026-11-22

### 实机三项反馈：图标满格、单位统一、确认后弹树（照参考项目管线，不新建类）

- **流体/化学品图标满格**（照参考项目 `GenericIngredientUtil` + 归一化管线，零编译期依赖）：
  JEI/NeoForge 流体渲染器按 `amount` 相对 1000mB 决定填充高度——80mB 只画 1/12 高，
  视觉上是一条"有颜色的色带"（4.2.6 实机）。现在 `IngredientDisplay.renderTyped` 对
  流体 `setAmount(Integer.MAX_VALUE)`、化学品反射 `copyWithAmount(Long.MAX_VALUE)`
  强制满格，并照参考项目做 Z 层抬 120 + 按渲染器尺寸居中缩放到 16×16；真实数量完全
  脱离图标，由数量角标单独呈现（物品路径不动）。
- **单位统一**：`IngredientDisplay.measured` 在展示栈没有数量标记时按身份键
  （`chem:`/`fluid:` 前缀）兜底判定——同一化学品不再一处显示 `0.08 B`、另一处显示裸 `80`。
- **确认后打开树界面**：配方页「添加到树」→ 编辑器确认后，`RecipeTreeScreen.afterImport`
  改用**编辑后落库的 record** 建/换树（旧实现用原始 JEI layout，编辑内容丢失），并去掉
  `tree()==null` 限制——旧实现有树时整段短路，表现为"确认后树里未替换、界面不弹出"
  （空树/有树两种情况同根因）。`RecipeEditScreen.save` 两条入口改互斥分支，不再先切回
  配方页再抢回来。

## 4.2.6 — 2026-11-22

### 修通「+ → 编辑器」并让流体/化学品画本体（照上游实现，不新建类）

4.2.5 实机日志（neoforge 1.21.1，14:28:55）显示 `+ produced record ... opening editor`
之后编辑器确实 init（`bookmark grid bounds width=235`），但 0.8 秒后屏变回树屏
（`width=222`），没有 `saving`、没有 `bound to node`，玩家只能重来。

**根因一（配方不能转移）**：JEI `RecipeTransferButtonController.onPress` 在转移成功后
同步调 `onSuccessfulTransfer.run()` = `recipesGui::onClose`；19.54 源码
`RecipesGui.onClose()` 里 `minecraft.setScreen(parentScreen)`。我们在 `transferRecipe`
回调栈里 `setScreen(编辑器)`，紧接着被这一句顶掉。参考项目 `RecipeTreeOpenHelper`
正是用 `ClientTickEvent.Post` 队列延迟切屏绕开它（`onClientTickPost` → `minecraft.setScreen`），
还注册了 `ScreenEvent.Opening/Closing` 追踪"树被临时替换"。

- `RecipeEditScreen` 新增 `queueOpen` / `applyPendingOpen`：+ 只入队，不切屏。
- `JERTScreenInput.onClientTick`（现成的 Post 钩子）消费队列，等 JEI 那一栈跑完再切。
- `RecipeTreeScreen.jert$applyJeiRecipe` 改为入队，日志补打 inputs/catalysts 数量。
- `RecipeTreeJeiBridgeScreen` 删掉已失效的 `appliedScreen` 兜底（编辑器不在 + 栈里打开，
  它只会把树屏错记成编辑器），桥只转发与回弹树屏。

**根因二（流体图标与参考项目不一致）**：`+` 转来的配方只留下身份键，没把原始
`ITypedIngredient` 登记进 `IngredientDisplay.SOURCES`，编辑页化学品槽
`StoredRecipes.stack` 因此退化成屏障、或画成桶/容器。参考项目
`RecipeTreeJeiLookup` 全程用 `ITypedIngredient`（`getDisplayedIngredient()` 优先）走
JEI 原生 `IIngredientRenderer` 画本体。

- `RecipeArchive.recordFromJeiSlots`：照上游优先取 `getDisplayedIngredient()`，
  并对每个候选 `IngredientDisplay.registerSource(key, ingredient)`。编辑器 / 树 /
  目录页共用这份缓存，化学品与流体都画本体而非屏障或桶。

不新建/删除类、不动 mixin、不改构造签名；`verify-library` 钉住的字面量未变。

## 4.2.5 — 2026-11-22

### 修通「点节点 → 原生 + → 编辑器确认 → 接回节点」（照上游 pendingJeiSelection 语义）

对照参考项目 `JEI-Crafting-Tree` 的真实链路：它的 `pendingJeiSelection` 是**树屏实例字段**，
`+` 当帧就把配方挂到那个节点、回弹回同一实例，全程不跨屏、不等「保存」。我们要保留编辑器，
就多了一次切屏和一次静态 session 读取——绑定目标因此在多次 `setScreen` 之间丢失，
日志表现为 `+ clicked → redirecting close` 之后戛然而止，既没有 `bound to node`
也没有 `does not produce`（`resolveRecord` 的第一行 `!isResolving()` 是静默 return）。

- **绑定目标随编辑器实例走**：`RecipeTreeScreen.jert$applyJeiRecipe` 产出 record 后，把点节点
  记下的 key 用 `withBindTarget(...)` 传给 `RecipeEditScreen`；保存时直接 `resolveRecord(record, key)`，
  不再依赖静态 `pendingIngredient` 是否还活着。`RecipeTreeSession` 新增显式 key 重载，
  原 `resolveRecord(record)` 委托给它，语义不变。
- **先绑定、后切屏**：`save()` 里绑定挪到 `setScreen(parent)` 之前；配方页按钮那条
  （`layout != null`）走 `afterImport` 的时序保持不变，不受影响。
- **全链路可见**：`+ 产出 record`、`编辑器保存并绑节点`、`桥屏回弹目标`、
  `resolve 跳过原因`（isResolving/tree/pending 三值）都补了 summary 日志，
  下一轮实机能一眼定位断在哪一跳。

不新建/删除类、不动 mixin、不改 `RecipeEditScreen` 构造签名（用 setter），
`verify-library` 钉住的字面量保持不变。

## 4.2.4 — 2026-11-22

### 编辑器交互重做（玩家裁定：左键=拿起换位置，不再与中键同义）

- **左键 = 拿起/放下/交换**（背包同款）：空手点已填槽拿起（原槽清空，输出区
  最后一个不许拿走）；有手时空槽放下、已填槽交换（原内容上手，可连续轮换摆放）；
  手上的内容跟鼠标走，所有可放槽画白色边框，点空白或 Esc 丢弃。
- **中键/右键 = 数量浮层**（不变）：±1/10/100/1000 + 输入 + 设置。
- **占位符可拖**：底部按钮输入名字，Enter 确认后占位符**进手**跟鼠标，
  左键点槽放下——不再直接插到输入区末尾。
- **占位符输入不再反人类**：输入框下方黄字标明 "Enter 确认拿在手上 · Esc 取消"。
- 提示文案同步更新（拿起态操作提示、左键语义说明）。

## 4.2.3 — 2026-11-22

### 编辑器槽位画流体/化学品本体（玩家反馈"为啥不是流体图标"）

`drawSlot` 之前一律 `renderItem`，而流体的展示栈是**桶形代表栈**——槽里画出来
就是桶，不是流体。改为：流体槽走 JEI 流体渲染器画本体（复用
`FluidRecipeCompat.render`，4.1.9 语义在回退时丢失，本轮恢复并保留到重构后）；
化学品走新增的 `IngredientDisplay.renderByKey`（按键反查本运行期记过的 typed
ingredient 直接画本体——它的展示栈还原在没见过时会退化成屏障占位，绕开）。
都画不了才退回 `renderItem`（物品槽）。数量浮层的副本槽同一函数，自动生效。

## 4.2.2 — 2026-11-22

### 修复 4.2.1 三个断点（对照上游 pendingJeiSelection 语义，玩家实测反馈）

- **+ 点了没反应**：JEI 转移成功后会 `onClose()` 回到宿主（桥），桥把界面弹回树——
  刚打开的编辑器被瞬间顶掉。现在桥记住 apply 打开的界面（编辑器），回弹送编辑器。
- **保存后树没展开**：4.2.1 误删了 pending 节点绑定（以为纯库驱动就够）。恢复
  `beginResolution/resolveRecord`（照上游：点节点记住目标，+ 的配方显式绑回那个节点，
  产物对不上则不绑、库自动展开兜底），成功后 actionbar 提示"配方已保存并接到树节点"。
- **可观测性**：+ 点击、绑定成败、桥打开失败（`show()` 焦点解析不出配方时静默不
  开页）全部有 summary 日志，下次一眼定位。

**未实机验证**。验点：树上点子节点 → 配方页 + → 编辑器填好 → 保存 → 回树且节点
接上（actionbar 提示）；日志出现 "recipe tree transfer: + clicked"。

## 4.2.1 — 2026-11-22

### 编辑器 = 样板编码终端（对齐 AE2，玩家裁定方向）

以 AE2 样板编码为唯一参照重构编辑与树的链接方式（源码逐条核对
`CraftingTreeNode.request` / `AEKeyType.formatAmount` / `ReadableNumberConverter`）：

- **化学品键规范化**（修 4.2.0 的 + 匹配失败）：化学品身份恒定取注册名
  （反射 `getType().getRegistryName()`），不再依赖 JEI helper 可用性——
  之前 toString 退路带数量前缀（`chem:...#10 xxx`），同一化学品两种键，
  + 转来的产物永远对不上节点。库加载时旧键归一，新旧互通。
- **编辑器=样板编码终端**：JEI 原生 + 直接把配方整份填进编辑器草稿
  （新增 `RecipeEditJeiTransferHandler` 注册到 ShellMenu 宿主——编辑器本身就是
  容器屏，+ 随时可点，与 AE2 样板槽被填充同一机制）。保存落库，树自动展开。
- **去算子化**：`CandidatePicker` 删除；树上的手工绑节点流程
  （pending 解析/按记录绑定）退役——树的父子链接全自动由配方库驱动，
  谁的产物=输入就展开谁（AE2 自动合成同款，`CraftingTreeNode` 里多张样板
  按注册序全部参与、逐条补缺）。玩家的"替换"=对库增删改。
  唯一保留的 JEC 功能：自定义占位符（底部按钮，手写身份）。
- **JEC「手上标签」机制退役**：无携带态；槽交互 = 中键/右键数量浮层。
- **AE2 数量浮层**：副本槽 + 可输入框（流体/化学品按 B，物品按个数）+
  ±1/10/100/1000 两排（先调显示值）+ 删除/设置（设置才写回草稿）。
- **数量显示照 AE2 口径**：FULL（tooltip/输入）= mB÷1000 + " B"（最多 3 位小数，
  10 mB→"0.01 B"）；SLOT（角标，宽 4）= B 的 SI 缩写（1.2M mB→"1.2M"）。
  之前的"B 只在整百"口径按玩家最新裁定（照 AE2）作废。
- **化学品拖入**：走 JEI 类型包装（`createTypedIngredient`），键与树上同一构造，
  不 instanceof 猜类型——Mek 气体/熔融物等 JEI 注册的类型都能拖。

**未实机验证**。验点：①树上点节点→配方页原生 +→编辑器填好→保存→节点自动接上
（Mek 化学品配方同验）；②编辑器开着时 JEI 配方页 + 直接填编辑器；③中键浮层
±按钮与设置；④拖 Mek 碳进槽；⑤流体角标 0.01/1.2M 格式。

## 4.2.0 — 2026-11-22

### 物品获取重构：配方进树走 JEI 原生 +（照抄上游 JEI-Crafting-Tree 架构）

玩家裁定 4.1.7~4.1.12 方向不对，整体回退后按上游参考实现重做：树上点节点 →
打开**隐形容器桥**（`RecipeTreeJeiBridgeScreen`，挂空 `RecipeTreeTransferMenu`，
首帧即退回树屏）→ JEI 配方页 → 因为宿主是容器，**JEI 原生 + 按钮出现**（与 AE2
样板编码终端同一机制：universal transfer handler 按 containerClass 匹配）→
点击 → JEI 把配方槽位（`IRecipeSlotsView`，物品/流体/化学品全类型）原样交给树 →
直接从槽位构建库记录（身份/数量与树上同一构造）并接到节点，**随即回到树屏**。

新增：`RecipeTreeTransferMenu`（容器壳）/ `RecipeTreeJeiTransferTarget`（接口）/
`RecipeTreeJeiTransferHandler`（universal handler，注册进 JEI plugin）/
`RecipeTreeJeiBridgeScreen`（桥）；`RecipeArchive.recordFromJeiSlots`（JEI 槽位 →
库记录）；`RecipeTreeSession.resolveRecord`（按记录绑定节点）。

树上给节点选配方但产物对不上时留在配方页（不误建新树）；未选节点时 + 直接建新树根。

**未实机验证**。验点：树上点子节点 → JEI 配方页出现原生 + → 点击 → 自动回树、
节点接上配方（Mek 化学品/流体配方同验）；未选节点直接点 + → 建新树。

## 4.1.6 — 2026-11-22

### 侧栏全局按钮右移一格，避开 ExtendedAE Plus 的开关；撤销 4.1.5 加错位置的开关

玩家反馈 4.1.5 方向判断错误：与 ExtendedAE Plus「AE2 网络库存显示」开关重叠的
**不是配方页的树按钮**，而是 **JEI 侧栏底部的全局树按钮**（左键开树那颗）。
4.1.5 加的配方页开关 `recipeTreeButtonEnabled` 关掉后反而没有入口，已用 git revert 整体撤销。

反编译 `extendedae_plus-1.6.4.2` 实锤根因（字节码）：

```
EAP:  eap$networkOverlayButton.updateBounds(historyButton.getArea().moveRight(22))
本mod: jert$area = area.moveRight(area.getWidth() + 2)   // historyButton 宽 20 → 同样是 22
```

两个 mod 把各自的 20×20 按钮钉在 **JEI 查询历史按钮右侧的同一个矩形 [22,42)** 上，
绘制与点击完全重叠。

修复（`BookmarkOverlayMixin.jert$place`）：检测到 `extendedae_plus` 已加载时再让出一整格，
`step = 20 + 2 + 20 + 2 = 44`，本mod按钮排到 **[44,64)**，与 EAP 按钮间距 2px；
EAP 不在的实例保持原位 [22,42)，不让按钮悬在空格里。首次生效时记一条 debug 日志。

**未实机验证**。验点（`neoforge 1.21.1` 实例，装 EAP）：侧栏底部从左到右依次是
查询历史按钮 → EAP 开关 → 树按钮，三颗互不重叠；点击各自生效、tooltip 各自正确。

## 4.1.4 — 2026-11-21

### 配方页滚轮翻页：分页带被分类标签行的区域先吃掉了

玩家实测 4.1.3：配方页**点左右箭头能翻页、滚轮不行**；AE 面板的箭头正常。
说明 JEI 的按钮点击链路没坏，坏的只是滚轮这条路径。

`JeiInputAreaCompat.scrollRecipeNavigation` 原来的顺序是：

```
scrollRecipeTabs(...)  →  分类按钮带  →  分页按钮带
```

在 19.71 里 `RecipeGuiTabs.area` 覆盖整个分类标签行，常常连配方页的分页数字带一起罩住。
于是鼠标明明放在分页带上，却被 `tabs.area` 拦下当成"切分类"；分类只有一页时什么都不会发生，
表现就是滚轮完全没反应，而点击箭头走另一条链路所以正常。

改成**先判分页按钮带**，摘出来之后再交给分类带与标签行处理。

**未实机验证**。验点：19.71 实例配方页，鼠标放在「1/2」那条带上滚轮能翻页；
分类按钮带与标签行的滚轮仍然有效（不能被这次调整抢走）。

## 4.1.3 — 2026-11-21

### 兼容 JEI 19.71：修复 Mixin apply 失败导致的 JEI 屏幕打不开

玩家在 23:01 把实例升级到 **JEI 19.71.0.475** 后，进游戏直接断线画面。日志：

```
MixinApplyError: Mixin [jeirecipetree.mixins.json:JeiInputSceneMixin] FAILED during APPLY
InvalidInjectionException: @Inject::jert$extraAreas
Expected: (Lmezz/jei/common/input/UserInput; …CallbackInfoReturnable;)V
Found:    (DD …CallbackInfoReturnable;)V
```

**根因**：19.71 给 `GuiInputScene.findTarget` 新增了 `findTarget(UserInput)` 重载。
`javap` 确认旧的 `findTarget(double, double)` **仍然存在**。我们只写了方法名，Mixin 便按
handler 签名去推断目标重载，挑中了新重载，于是描述符对不上，整个 mixin apply 失败。

**修法**：把注入目标钉成完整描述符 `findTarget(DD)Ljava/util/Optional;`，
只匹配旧重载。这样 19.69（只有旧重载）和 19.71（两个重载）都能 apply，
不需要换编译基线，也不需要引用 19.71 才有的 `UserInput` 类型。

### 同一批 JEI 升级里发现的其它失效注入

`javap` 逐个核对 19.71 后发现这些目标已被移除或改名：

| mixin | 原目标 | 19.71 情况 |
|---|---|---|
| `RecipesGuiMixin` | `mouseScrolled` / `mouseClicked` | 已移除，改为 `createInputArea` / `registerInputCommands` |
| `RecipeGuiTabsMixin` | `createInputHandler` | 已移除/改名 |
| `IngredientLookupStateLegacy/ServiceMixin` | `create` | 已移除 |

这些注入原本没有 `require = 0`，在 19.71 上会接着崩。全部加上 `require = 0`，
让它们在新版 JEI 上静默跳过；旧版本行为不变。

**功能边界**：19.71 上 `RecipesGuiMixin` 不再生效，所以 4.1.2 补的「分页按钮点击兜底」
在 19.71 上不会执行——不过 19.71 的 `RecipesGui$RecipeCategoryScrollInputHandler` 仍在，
分页带滚轮由它继续处理。左键箭头是否恢复正常需要实机确认。

**未实机验证**。验点：19.71 实例能正常进 JEI 界面；19.69 实例行为与 4.1.2 一致。

## 4.1.2 — 2026-11-21

### 产物是化学品/流体时，整条链被放大几十上百倍

玩家截图：根需求 500 mB，下一层却写成「500」，再下一层银锭变成 **32K**、流体 1.0B。
500 × 64 = 32000，正好放大 500 倍。

根因在 `RecipeSnapshot.outputAmount`：它取的是**展示栈的 `getCount()`**。化学品与流体的展示栈
只是个图标壳，`count` 恒为 1（真实量在 `custom_data` 的标记里）。于是「每批产 500 mB」被当成
「每批产 1 个」，批数 = ⌈500 / 1⌉ = 500，下游每种输入都乘 500。

改成按真实单位取量（`IngredientDisplay.amountOf`：物品仍是个数，流体/化学品是 mB），
同时修正两处把副产物按 `getCount()` 相乘的地方。

### 配方页分页带滚轮不生效

配方页顶部「1/2」与左右箭头之间那条横带，滚轮原本交给
`RecipesGui$RecipeCategoryScrollInputHandler` —— 该类在当前 JEI（19.57）里**已经不存在**，
`@Pseudo` 注入静默失效。所以分类带好用、分页带没反应。

在 `RecipesGuiMixin.mouseScrolled` 里补上分页带：命中 `previousPage`/`nextPage` 两个按钮的
区间时，按滚动方向调 `logic.previousPage()` / `nextPage()`。

**未实机验证**。验点：① 500 mB 配方树上下一层应是 1 而不是 500，银锭不再是 32K；
② 鼠标放在「1/2」那条带上滚轮能翻页。

## 4.1.1 — 2026-11-21

### 大批量合成按批次拆分：一次只做背包放得下的量

队列原本就是按块推进的（装填一块 → 取产物 → 下一块），但**块的大小只看配方槽容量**
（每槽一叠），与玩家背包还剩多少空间无关。后果是：

- 背包接近满时，一次仍按整批取料，第一种材料就找不到落点，补料失败，
  表现为「点了填充没反应」；
- 材料种类多时，一批就要占掉好几格，背包不够时同样整步放弃，而不是少做几批。

现在块大小会再按「背包放得下多少」收敛（`RecipeTreeTransfer.inventoryBoundChunk`）：

```
每批消耗（按材料聚合，多槽同材料相加）
      ↓
背包容量 = 已有量 + 还能放多少（空槽 + 同物品未满的槽）
      ↓
批数上限 = 容量 / 每批消耗，取所有材料的最小值，且不低于 1
```

于是大批量合成自动拆成多个小块：每块都装得下、做得完、不残留，做完再继续下一块。
背包里已有的量算进容量——补料是"补到够"，不是"额外再加一批"。

同时新增 `StorageRestock.roomFor`：只统计空槽与同物品未满的槽，不会把料放到别的物品上
（那等于交换，会把玩家的东西挤到光标上）。

**未实机验证**。验证方法：剩少量背包格时点大批量合成，应当在日志看到
`restock: chunk 64 -> N (inventory room)`，并且能一批批做完，而不是直接失败。

## 4.1.0 — 2026-11-21

### 填充前自动补料：材料只在网络里时，点填充也能填进去

参考 AnvilCraft 的做法（`TerminalJeiTransferSupport` + `JeiBasicRecipeTransferHandlerMixin`）：
**不替任何 mod 写填充逻辑**，只在调用 JEI 的 `transferRecipe` 之前，把缺口搬进玩家背包。
JEI 的填充处理器只认玩家背包槽，料进了背包，它自己就能填——所以机器侧不需要逐个适配。

**缺口怎么算**（`RecipeTreeTransfer.computeNeeds`）

- 口径取自 JEI 自己的行为：每个输入槽按「每批消耗 × 批数」计算，单槽上限是 `maxStackSize`；
  多个槽要同一种材料时按总量相加；
- 减掉玩家背包现有量（只算背包，因为 JEI 也只认背包）；
- 没有物品形态的输入（流体、化学品）直接跳过——它们没法"拿在手上"，仍走原转移路径；
- AE2 合成终端跳过：它有自己的「直接填网格」包（`FillCraftingGridFromRecipePacket`），
  不需要绕背包。

**两条取料来源**（都复用目标 mod 自己的客户端→服务端包，本模组仍然纯客户端，不新增网络通道）

1. **当前菜单就是存储终端**：走 `StorageDriver` 新增的取料接口。AE2 的实现是
   `MEStorageMenu#handleInteraction(serial, PICKUP_OR_SET_DOWN)`：取一叠到光标 → 按缺口精确放进
   背包（整叠用左键，零头用右键一个一个放）→ 多余部分 `handleInteraction(-1, …)` 退回网络。
   净效果是"只取了需要的那几个"。
2. **当前菜单是机器、网络在旁边或背包里有终端**：交给 Sophisticated Item Actions 的补料请求
   （`SiaMaterialBridge`，与「一键取料」同一个入口），传的是"背包里最终应有多少"，然后轮询背包
   直到到账。SIA 自己扫 10 格内容器与终端物品，这一条不需要我们认识任何存储 mod。
   超时 30 帧（1.5 秒）就放手，退回原转移路径，由 JEI 报缺料。

**失败与收尾**

- 补料失败（背包满、光标不干净、服务端没响应、条目对不上、超时）→ 先尽量把光标内容退回网络，
  然后**继续走原来的转移**：玩家看到的仍是 JEI 自己的缺料提示，不会卡在一个"点了没反应"的死状态；
- 补料完成后自动恢复那次转移；递归合成时队列停在原步骤，不会跳步、也不会去等一个不存在的产物；
- `canTransfer`（按钮点击判定）在空跑时也会算缺口：材料在背包里不够、但能从存储补进来时，
  填充动作是放行的——否则玩家连点都点不下去。

**已知边界**

- 补料按"批数"计算，若某台机器的输入槽只接受 1 个而树要求多批，背包里会多出几个没被机器吃掉的料
  （不会被吞，也不会进网络，需要玩家自行处理）；
- 流体与化学品补不进背包（没有能装 1B 氧气的容器），这一条与 4.0 的 mB 记账不冲突；
- SIA 方案无法预先知道它扫到什么，所以按钮判定是"乐观放行"：点下去才知道能不能补到。

**未实机验证**：所有 4.1.0 行为都还没有在游戏里跑过。验证清单见提交说明。

## 4.0.7 — 2026-11-21

### 回退 4.0.6：滚轮导航的 4 个 mixin 恢复

4.0.6 把这 4 个 mixin 当成"重复 JEI 已有操作"删掉了。玩家装上去实测后确认：**删掉确实有影响，
这些接管是必要的**，因此整批回退（`git revert 9a7b8e5`，回退提交保留在历史里，没有改写 4.0.6）。

恢复内容：

- `mixin/RecipesGuiMixin`（`RecipesGui.mouseScrolled`）
- `mixin/JeiRecipeNavigationMixin`（`RecipeGui$RecipeCategoryScrollInputHandler.scroll`）
- `mixin/JeiInteractionTabsMixin`（`RecipeGuiTabs.scroll`）
- `mixin/RecipeGuiTabsMixin`（`RecipeGuiTabs.createInputHandler`，与原生 handler 合并）
- `jeirecipetree.mixins.json` 对应 4 条
- `JeiInputAreaCompat` 的 `insideBand` / `scrollRecipeNavigation` / `scrollRecipeTabs` / `buttonBand`
- `JeiInputSceneTest` 的滚轮断言与 `Navigation` / `Tabs` / `PageNavigation` / `Logic` 测试替身

**当初为什么删错**：判断依据只有一条——"注入目标是 JEI 自己的方法，所以 JEI 自己能处理"。
这条推理不成立：**方法存在 ≠ 事件会走到那里**。JEI 的输入链里靠后的通用处理器会先吃掉标签行的事件
（这正是 `JeiRecipeNavigationMixin` 的注释写明的原因），不提前接管就收不到。而且这些注入都是
`@Pseudo` + `require = 0`，失效时不报错、不崩，只表现为"滚轮不管用"，静态阅读看不出来。

**给后来的判断口径**：

1. 判断一个 mixin 是否多余，不能只看"目标是不是对方自己的方法"，要看**删掉后行为是否真的不变**；
2. 这类接管式兼容层没有自动化测试覆盖，**必须实机验证**；
3. 作者自己写在注释里的"为什么要在两个地方都处理"，就是它不能被简化掉的证据。

## 4.0.6 — 2026-11-21（已回退，见 4.0.7）

### 删除 JEI 滚轮导航的 4 个 mixin

> 注：本条改动已被 4.0.7 整批回退。保留原文是为了留下"为什么当时判断错了"的记录。

这四个 mixin 与配方树功能无关，只是重复接管 JEI 自己就能处理的滚轮操作：

| 删除的 mixin | 原来插在哪 |
|---|---|
| `RecipesGuiMixin` | `RecipesGui.mouseScrolled` |
| `JeiRecipeNavigationMixin` | `RecipesGui$RecipeCategoryScrollInputHandler.scroll` |
| `JeiInteractionTabsMixin` | `RecipeGuiTabs.scroll` |
| `RecipeGuiTabsMixin` | `RecipeGuiTabs.createInputHandler` |

同时删除了 `JeiInputAreaCompat` 里只服务它们的 `insideBand` / `scrollRecipeNavigation` /
`scrollRecipeTabs` / `buttonBand`，以及 `JeiInputSceneTest` 中对应的滚轮断言与
`Navigation` / `Tabs` / `PageNavigation` / `Logic` 测试替身。

## 4.0.5 — 2026-11-21

### 删除 JEI 侧栏的"树行占位"死代码

树行早已改由 `RecipeTreeSidebarPanel` 独立排版，但早期"往 JEI 书签网格里插换行占位元素"的那套机制
还留在代码里，两个 mixin 一直在拦 JEI 的侧栏渲染入口。核查确认它们已完全空转：

- `RecipeTreeFavorites.createRowBreak()` 全项目 **0 处调用**；
- `ROW_BREAKS` 只在每次 refresh 时 `clear()`，唯一 `add()` 写在上面那个死方法里；
- `isRowBreak()` 因此恒为 `false`；
- 两个 mixin 的注入体第一句就是 `if (ingredientList.stream().noneMatch(isRowBreak)) return;`，
  等于每次 JEI 侧栏刷新都白跑一次判定。

删除内容：

- `mixin/IngredientListRendererMixin`、`mixin/IngredientListRendererLegacyMixin`
  （以及 `jeirecipetree.mixins.json` 里两条）
- `RecipeTreeFavorites`：`createRowBreak`、`ROW_BREAKS`、`ROW_BREAK_INGREDIENT`、`isRowBreak`
- `JeiTreeIntegration.isRowBreak` 转发

JEI 侧栏的列表渲染从此完全回到 JEI 自己，不再有任何拦截。

**未经实机验证**。验证：打开 JEI 侧栏 / 配方页，书签与物品列表显示、滚动、分页与删除前一致。

## 4.0.4 — 2026-11-21

### 删除"终端条目置顶"整套代码

4.0.3 只是停用（保留实现不调用），玩家要求直接删掉——这功能无用，而且当初加的时候没说明，
属于擅自改别的 mod 的界面状态。这次整条链路连同 mixin 一起删除，不留空壳：

**删除的文件（都只服务于置顶）**

- `mixin/BeyondDimensionsNetMenuMixin`、`mixin/IntegratedTerminalMenuMixin`
- `mixin/IntegratedTerminalStorageMixin`、`mixin/RefinedStorageGridViewMixin`、
  `mixin/RefinedStorageRepositoryMixin`
- `jeirecipetree.mixins.json` 里对应 5 个条目同步移除

**删除的实现**

- `Ae2StorageIntegration`：`prioritizeVisibleEntries`（反射改 `IClientRepo.view` 顺序）、
  `restoreVisibleOrder`、`prioritizedRepo` / `repoWasPrioritized`
- `StorageNetworkIntegration`：`queueVisibleEntries` / `applyPendingVisibleEntries` /
  `prioritizeVisibleEntries`，以及 RS1/RS2、Beyond Dimensions、Better Beyond Dimensions、
  Integrated Terminals 五套 `prioritize*` 实现与其辅助方法
  （`sortExposedOrBackingList`、`findBackingList`、`isHighlighted*`、`identityIndex`、`sharesElements` 等）
- `RecipeTreeFavorites` / `JeiTreeIntegration` 的 `applyPendingNetworkPriority` /
  `applyCurrentNetworkPriority` 转发入口
- `AbstractContainerScreenMixin.render` 里两行调用，`BetterBeyondSidebarRendererMixin` 的 HEAD 注入

**保留**：终端里的配方树高亮（`renderNetworkHighlights` / `renderBetterBeyondHighlights` /
`renderIntegratedHighlights` 及其 mixin）。高亮只是叠色，不改列表顺序。

**未经实机验证**。验证：打开配方树前后，AE2 / RS 终端条目顺序完全一致；终端里属于树上的材料仍有高亮边框。

## 4.0.3 — 2026-11-21

### 不再改动存储终端的物品排列顺序

打开配方树之后，AE2 / RS / Beyond Dimensions 等终端的条目会被重排：树上的成品、中间产物、原料
被挪到列表最前面。这是早先做的"省一次找物品"，但它改的是**别的 mod 的界面状态**：

- 排序只在"同一个仓库实例、且之前确实排过"时才调 `updateView()` 还原，
  换终端、跳界面之后还原就不发生，终端顺序时好时坏；
- 玩家关掉树之后看到的是一个被改过顺序的终端，与他自己设置的排序不一致。

玩家反馈"碍事"，整条链路停用：

- `RecipeTreeFavorites` 不再调用 `queueVisibleEntries`；
- `StorageNetworkIntegration.prioritizeVisibleEntries` 改为只做收尾：调
  `Ae2StorageIntegration.restoreVisibleOrder()`，把之前排过序的 AE2 仓库按它自己的排序重建一次；
- AE2 的置顶实现（`Ae2StorageIntegration.prioritizeVisibleEntries`）、RS1/RS2、Beyond Dimensions、
  Better Beyond Dimensions、Integrated Terminals 的置顶实现都**保留但不调用**，
  以后要恢复只用接回一行调用，不必重新推导反射路径。

**未经实机验证**。验证：打开配方树后再看 AE2 终端，条目顺序应与玩家自己设置的排序一致，
不出现"树上材料全跑到最前面"。

## 4.0.2 — 2026-11-21

### 网络库存凭空翻倍：网络里 1 个，树记 2 个

**4.0.1 的根因判断是错的**，这一段更正它。玩家做的对照实验是：

| 物品放哪 | AE2 终端显示 | 树里记 |
|---|---|---|
| 网络里 1 个、背包清空 | 1 | **2** |
| 网络 0 个、物品栏 1 个 | 0 | 1 |

合成格两组都是空的，所以 4.0.1 里那套"合成格被两个来源各算一次"解释不了这个差异。
真正的问题在 `Ae2StorageDriver.collectOpenSlots`：它用的是**黑名单**（"扫所有不属于玩家背包的槽"），
而 AE2 的槽位有两条它判不住：

1. **AE2 把玩家背包包了一层** `appeng.util.inv.PlayerInternalInventory` 再交给 `AppEngSlot`
   （`javap` 确认：`CraftingTermMenu` 用 `new PlayerInternalInventory(player.getInventory())`），
   于是 `slot.container == playerInventory` 这个引用比较**恒为 false**——整块玩家背包被当成
   "额外库存"并进了网络快照；
2. **WCWT 的样板预览用 `FakeSlot`**（合成样板 9 格、处理样板输入输出各 9 格、锻造/切石/铁砧预览），
   `getItem()` 返回真实 `ItemStack`，但它只是"样板里写了什么"，不是库存。

第一条就能造出"网络 1 → 2"：网络条目一份 + 背包包装槽再算一份。

改动：

- `collectOpenSlots` 改为**白名单**：只收 `appeng.menu.slot.CraftingMatrixSlot`（3×3 真实合成格）。
  结果槽、样板预览槽、样板供应器槽、背包包装槽一律不进网络快照。这是结构保证，
  以后再有 mod 往菜单里加虚拟槽也不会被误收。
- `SupplyLedger.countsTowardContainerSupply` 追加两条排除：
  - `appeng.util.inv.PlayerInternalInventory` 承载的槽（`scan()` 已按 `player.getInventory()`
    扫过，再扫一遍就是背包翻倍）；
  - `appeng.menu.slot.FakeSlot`（样板幽灵物品）。
- 开发者模式新增一行 `ae2: crafting grid merged: N slot(s)`，可以看到合成格到底贡献了几格。

`contentHash()` 与 `scan()` 共用同一套过滤，哈希口径同步，不会出现"哈希说没变、扫描说变了"。

**未经实机验证**。验证：网络里放 1 个、背包清空，树上应为 1；再把这 1 个摆进 3×3 合成格，
树上仍应为 1（不是 2，也不是 0）。

## 4.0.1 — 2026-11-21

### 合成格里的物品被两个来源各算一次：网络 1 个，树里记 2 个

> 注：这一条对**合成格**的重复计数修正有效（该排除保留至今），但"网络 1 个也变 2 个"
> 的真正原因不是这里 —— 见 4.0.2。

玩家截图：AE2 终端显示某物品 1 个，配方树记成 2 个；把东西摆进合成格时数字反而是对的。

根因是**同一条合成格被记了两遍**：

- `Ae2StorageDriver.collectOpenSlots` 把菜单里不属于玩家背包的槽位并进网络快照
  （注释写明这是为了"玩家把料放进网格后树仍看得见"）；
- `SupplyLedger.scan()` 的容器桶又遍历了一遍同一批槽位。

合成格槽位是 `appeng.menu.slot.CraftingMatrixSlot`，容器是 AE2 自己的 `InternalInventory`
（`javap` 确认：`CraftingTermMenu` 里 `new CraftingMatrixSlot(menu, InternalInventory, i)`），
原版 `slot.container instanceof CraftingContainer` 判不出来，所以两边都收了。

改动：

- `SupplyLedger.countsTowardContainerSupply` 按类名排除 `CraftingMatrixSlot`
  （与既有的 `RestrictedInputSlot` 同一套做法，不引入 AE2 编译期依赖）。
  合成格只保留网络快照那一份，总数回到 1；
- `contentHash()` 与 `scan()` 共用同一套槽位过滤，哈希口径同步修正，不会出现
  "哈希说没变、扫描说变了"的抖动；
- 开发者模式新增 `stock overlap` 日志：把"同一身份同时来自背包/容器/网络"的条目连
  三个来源的数量一起打出来，这类账再出现能一次定位。

**未经实机验证**。

## 4.0.0 — 2026-11-21

### 4.0 第一阶段：材料身份与「物品栈」解耦

树节点的载体一直是 `ItemStack`。物品没问题，流体靠"桶栈 + 隐藏映射表"伪装，
**Mekanism 化学品只能借 `getCheatItemStack` 换来的"创造模式化学品储罐"**：
1000 mB 氧气在树里显示成 1 个罐子，所有气体共用一个图标，数量恒为 1，点了也查不到配方。

参考了三份实现：`lhy512103/JEI-Crafting-Tree` 的
`MaterialKey(type, uid)` + `helper.getUid` + 反射 `ChemicalStack#getAmount` + 「图标可为空」的
展示模型；JEC 把所有非物品压成 `LPlaceholder`（能算数但丢语义）；AnvilCraft 的
`StorageJeiBridge`（回调优先、反射兜底）与「不消耗输入单独标记」。

改动：

- 新增 `IngredientDisplay`：类型化身份 `chem:<JEI 类型 uid>#<该类型唯一 id>`；
  真实数量（流体取 `FluidStack#getAmount`、化学品反射 `ChemicalStack#getAmount`、
  其余问 `IIngredientHelper#getAmount`）；原始 ingredient 留在内存表里，
  供渲染与点击查配方。展示栈仍借一个 `ItemStack` 当壳，身份与数量写进
  `custom_data`（`jert_identity` / `jert_amount`）——`copy()` 自动带走，不会漏。
- `ItemRef` 新增 `isChemical` / `chemicalType` / `chemicalUid`。
- `RecipeLookup` 的槽位抽取改成逐个 ingredient 判定；删掉「整槽没有物品才兜底代表物品」
  的分支——代表物品不再承担身份，只当图标。
- `Node.perCraft`、`RecipeArchive.slotAmount`、`StoredRecipes.sameSlots` 改读真实数量，
  不再用 `stack.getCount()`（那只是"画几个图标"）。
- 渲染与 tooltip 统一走 `IngredientDisplay.render`：流体 → JEI 类型渲染器 → 物品。
- 输出焦点按「流体 → 类型化 ingredient → 物品」三级构造，化学品终于点得进配方。
- 库还原：`chem:` 键先从运行期表还原，失败时用屏障占位**但保留身份**，
  不会悄悄换成一个不相干的物品。
- 守卫按新语义改写：允许隔离在 `IngredientDisplay` 内的「读数量」反射
  （JEI 对化学品的 `getAmount` 默认返回 -1），其余层仍禁止出现 mod 包名。

**这一版的结构（B 方案）**：

- 新增 `MaterialOption`（身份 + 数量 + 图标 + 原始 ingredient），它是树里一行的**权威描述**；
  `RecipeInput`、`Node`、候选选择、查配方全部按它工作，图标**允许为空**；
- `ItemStack` 降级为**兼容视图**：渲染定位、转移、库存扫描仍需要 16×16 的物品格，
  所以 `Node.stack()` / `Cost.stack()` / `RecipeInput.alternatives()` 保留，只返回"有图标的那些"；
  没有图标的材料照样参与记账与展开，不会因为画不出来而整行消失；
- 渲染优先按材料对象走（JEI 原生渲染），没有原始 ingredient 才退回展示栈。

**仍待处理**：

- `Cost.alternatives` 与 `CraftStep` 仍以 ItemStack 为视图（`material` 已并行存在）；
- 网络库存仍只并物品，化学品与流体不进账；
- 化学品不进转移（没有物品槽可放）。

### 记账口径：流体在计算里就是材料

按玩家定的边界：**只有「在 AE 里写样板」和「请求合成」这两个出口才涉及流体形态**，
其余场景（树展开、材料清单、缺口）流体就是一种普通材料。

- `RecipePlanner.consumeForNode` 删掉流体专用分支（"先折容器数再还原"）——它既不遍历等价候选，
  又依赖展示栈的 count，正是"输入 11 显示 1100"的老路径；
- `SupplyLedger.mapAmount` 变成恒等映射，保留它只为"记账单位只有一个出口"；
  注释里写明两个需要 `FluidStack` 的出口。

样板编码功能尚未实现，请求合成由 AE2 终端自身的样板执行，所以当前没有其它地方需要流体转换。

### 入口与性能：一条材料只算一次身份

- `RecipeLookup.extractSnapshot` 的输入槽改成 `slotOptions()` **直接产出材料对象**：
  原来的路径是 `displayedStacks`（算一次键）→ `MaterialOption.of`（再算一次）→ 分组签名（第三次），
  同一件材料要算三遍 JEI 唯一 id。现在每样只算一次，下游一律用 `option.ref()`，
  不再从展示栈反推身份。
- 建树不再把"桶/容器"当作流体的候选：`containerCandidate` 只保留在转移路径，
  流体槽不会再同时出现「水」和「水桶」两个候选。
- 删掉重复状态：`Node.stack`、`Cost.stack` 不再单独存一份，直接读 `material.display()`；
  两份状态会让"材料换了、图标没换"这类不同步无从排查，也白多一次 copy。
- 修正 `IngredientDisplay.amount` 的物品分支：JEI 的物品 helper 没有覆写 `getAmount`，
  默认返回 -1，之前会把一整栈当成 1 个；现在直接取 `stack.getCount()`。
- `compareVectorPlan` 的逐项对照只在开发者模式下执行（催化剂仍照常计算）——
  对照本身要给每条材料再算一次身份，纯诊断开销。

**预期**：身份计算从每材料约 3 次降到 1 次，建树耗时里占大头的身份部分应明显下降。
实测看启动日志里的 `tree build: … N item keys`。

**未经实机验证**。建议验证：Mekanism 冶金灌注机配方 → 树上氧气行应显示为气体图标 +
真实 mB（如 1000 mB 显示 1.0B），点击应打开氧气自己的配方页，材料清单不再出现"创造化学品储罐"。

## 3.10.12 — 2026-11-21

### 配方树的缺口口径把网络库存漏了

3.10.10 让 AE2 合成产物优先回 ME 网络。玩家随即指出：**配方树根本没看网络库存**——
产物进了网络、背包没动，树上照样显示"仍缺"。

原因在 `SupplyLedger.scan()`：它只扫玩家 0–35 和菜单里的容器槽，代码里有一句早期决策留下的
注释「C8 / R8：缺口口径只用 PLAYER，不再读网络库存」。3.10.5 把缺口改成 JEC 的
"库存抵扣需求"之后，这条口径就不成立了——AE2 终端里的料和产物本来就该算"手边可用"。

改动：

- `scan()` 新增网络桶（`scanNetwork`），通过 `StorageNetworkIntegration.snapshot()` 读取；
  驱动层本来就有 5 tick + 内容指纹缓存，**不是**早期那种每帧遍历 AE2/RS 全量条目的写法；
- `playerSupply()` → `planningSupply()`：玩家 0–35 **加** 当前终端的网络库存。
  不含菜单里的其它容器槽（箱子、工作台）——那些要玩家或 SIA 去搬；
- `contentHash()` 混入网络快照 revision：产物进网络时背包不动，只混背包哈希会一直返回
  合成前的缓存，树上看不到新东西；
- `verifyScanGating` 的三条断言按新口径改写（原来明文禁止出现
  `StorageNetworkIntegration.snapshot()`），并新增"网络必须走缓存快照、必须独立分桶"两条。

**已知边界**：只并网络**物品**，不并网络流体——`StoredFluid` 只有 identity key，
没有可用于渲染的代表 `ItemStack`，硬造空栈会让侧栏图标缺一格。流体缺口暂时维持原口径。

**未经实机验证**。验证方法：AE2 合成终端里 Ctrl+左键做一批 → 产物进网络后，
树上该材料的需求应立刻下降（不再显示仍缺），并且不需要手动点配方树键刷新。

## 3.10.11 — 2026-11-21

### 3.10.9 漏掉的：全屏里还有 8 处居中文字带阴影

3.10.9 只改了树画布那条 `drawTreeCenteredText`。玩家截图指出两处仍然"重影"：

- 空树欢迎页的按钮「从配方库手动建树」；
- 点左上角「+」打开的配方库选择器：「配方库（N 条）」标题、
  「库是空的：先在 JEI 配方页点『添加到配方树』」提示、「打开配方库」与页码。

原因相同：它们都直接调 `GuiGraphics#drawCenteredString(Font, Component, int, int, int)`，
**这个五参重载默认 `shadow = true`**——阴影按 +1px 再描一遍，在浅色纸底上就是一圈深色晕。

处理方式：在 `TreeText` 增加统一出口 `centered(...)`（`drawString(..., false)` + 手动居中），
把全屏所有五参调用收口到它：

| 位置 | 内容 |
|---|---|
| `RecipeTreeScreen` | 欢迎按钮、选择器标题/空态/打开库/页码、翻页按钮 `<` `>`、帮助 `?`、左上角 `+` |
| `CandidatePicker` | 分区标签（物品 / 流体） |
| `RecipeEditPanel` | 分区 `+`、底部按钮、槽位按钮 |

`RecipeTreeScreen` 原有的 `drawCenteredNoShadow` 改为委托 `TreeText.centered`，不再有两份实现。

**未经实机验证**：判断只能看截图——欢迎页、选择器面板、编辑页三处的字不应再有深色描边。

## 3.10.10 — 2026-11-21

### AE2 产物优先回到 ME 网络，不再塞背包

3.10.8 把发包路径修通之后，产物是"取到光标 → 找背包空位点进去 → 找不到才存网络"。
玩家明确要求：**在合成终端里合成，产物应该回到网络**——塞背包既占掉玩家 36 格，
背包满时还会让产物卡在光标上、整个递归队列停住。

AE2 自己有这条正规入口，字节码核对过（19.2.18）：

```
MEStorageMenu.handleInteraction(long, InventoryAction)
  → isClientSide 时发 MEInteractionPacket(containerId, slotSerial, action)
  → 服务端 handleInteraction：serial == -1 时 key 传 null（表示"光标"）
  → handleNetworkInteraction：key == null 时
        SPLIT_OR_PLACE_SINGLE / ROLL_DOWN → putCarriedItemIntoNetwork(true)   单个
        PICKUP_OR_SET_DOWN              → putCarriedItemIntoNetwork(false)  整栈
```

我们本来就是 `handleInteraction(-1, PICKUP_OR_SET_DOWN)`，路径正确，只是**排在背包之后**。
本版把优先级调过来：

- 光标有产物 → **先存网络**；网络不可用（断链 / 版本不匹配）才退回背包；
- 新增 `TransferStepState.ae2NetworkSkip`：同一件产物试过存网络失败后不再重试，
  否则"优先网络"与"失败退回背包"会每帧互相打转，产物永远卡在光标；
- 存网络超时不再直接清空队列，改为打日志 + 切背包放置（产物一定落地，不会挂住）。

**本版与 3.10.8 / 3.10.9 一样，尚未实机验证**——3.10.8 至今一次都没在 AE2 终端里跑过
（实例日志里零条 `transfer ->`）。验证时看：终端里 Ctrl+左键 → 产物出现在 ME 网络而不是背包；
断网再试一次 → 产物落背包且日志出现 `AE2 network deposit timed out, falling back to inventory`。

## 3.10.9 — 2026-11-21

### 字重影/发虚：倍率不是整数，阴影又叠了一层

玩家反馈"字体有重影模糊"。查下来是两个独立原因叠在一起：

1. **字号倍率是非整数**。`large = 1.25`、`huge = 1.5`、`small = 0.75`——原版是 1px 位图字体，
   放大走 `PoseStack.scale`，本质是对已生成的字形纹理做采样，不是重新排版。整数倍
   （2、3）是每个源像素映射成一个 N×N 方块，边缘还是硬的；1.25 这种比例会让源像素摊到
   非整数个目标像素上，笔画一半糊一半锐，就是"重影发虚"。
   参照实现 `lhy512103/JEI-Crafting-Tree` 全屏树里 `.scale(` 只有画布 zoom 一处、
   **完全没有字号档位**，所以它不存在这个问题。
2. **居中文字默认带阴影**。`drawTreeCenteredText` 用的是 `drawCenteredString` 五参重载，
   它默认 `shadow = true`：阴影按 +1px 再描一遍，与字形本身叠在一起又是一层"重影"。

改动：

- `TreeTextSize`：`small 0.75 → 0.5`（1/2 干净分频）、`large 1.25 → 2.0`、`huge 1.5 → 3.0`。
  枚举常量名不变，老配置里的 `"large"` 照常解析，只是倍率换成 2。
- `drawTreeCenteredText` 改走 `drawCenteredNoShadow`（`drawString(..., false)` 手动居中），
  画布上所有文字统一不带阴影。
- 配置文案（zh/en、`JERTConfig.comment`、`DEFAULT_BLOCKS`）同步改掉旧倍率。

### 支持字体资源包

`AmountText.latin()` 原先硬编码 `Style.withFont(Minecraft.DEFAULT_FONT)`：数字永远用原版位图
字体，玩家装了字体美化包也只有一个中文字体、一个原版数字，一屏两种字形。

- 新增配置 `useGameFontForNumbers`（默认 `false`，保持数字最清晰）。
  打开后数字不再覆盖字体，完全跟随游戏当前字体 / 资源包，与中文标签同一套字形。
  代价：资源包若没提供 0-9，会退回 unifont 的断续笔画。
- 字体模式改变会清空 `AmountText` 的组件缓存，并让 `TreeText.sync()` 返回 `true`
  触发重排——同一串数字换字形后宽度会变，不重排会顶出胶条。
- `KNOWN_KEYS` / `DEFAULT_BLOCKS` / 中英文 lang 均已登记（配置自愈会删掉未登记的键）。

### 未解决的部分（说清楚，不夸大）

画布 `zoom` 仍是连续值（滚轮 0.1 一档）。**在 zoom ≠ 1 时，文字仍然会跟着镜头重采样**——
这与参考实现一致（它的 zoom 也是 0.2–4.0 连续）。要彻底消除只能把 zoom 量化到固定档位，
代价是滚轮缩放变跳档；本轮没做，等玩家决定。

**本版未经实机验证**，只过了构建与全部源码守卫。判断依据只能靠截图：同一棵树下，
`normal` / `large` / `huge` 三档各截一张，看 2 倍、3 倍是否锐利、居中文字是否还有重影。

## 3.10.8 — 2026-11-21

### AE2：搬料与取产物原来是"根本没发包"，不是语义不对

对照 `lhy512103/JEI-Crafting-Tree` 的 AE2 层查下来的第一件事是一条硬伤：

`Ae2StorageIntegration` 找的是 `appeng.core.network.NetworkHandler`。**AE2 19.x（MC 1.21.1）
没有这个类**——1.20.x 才有 `appeng.core.sync.network.NetworkHandler`；19.x 已经把
`ServerboundPacket` 换成 NeoForge 的 `CustomPacketPayload`，自己也是用
`PacketDistributor.sendToServer(...)` 发的（javap 19.2.18：`InventoryActionPacket` /
`FillCraftingGridFromRecipePacket` 都是 record + `CustomPacketPayload.Type`）。
于是 `firstLoadable(NETWORK_HANDLERS)` 恒为 null，两个方法都静默 `return false`：
合成终端里搬料没反应、取产物没反应，日志一个字都没有。

- 改成 `sendPayload(...)`：负载是 `CustomPacketPayload` 就走 NeoForge 的
  `PacketDistributor.sendToServer`（不注册我们自己的通道，客户端仍能进没装本模组的服务器）；
  只有老版本 AE2 才回落它的 `NetworkHandler`；
- 每一处失败都有去重日志（`ae2: ...`）：负载类型不认识、枚举/构造器缺失、发送失败、
  仓库未同步、链路断开——"看起来像没实现"以后一眼能判；
- **`NetworkHandler` 常量只留 1.20.x 那一条**，并在 `verify-storage` 里钉住：
  不许再出现 `appeng.core.network.NetworkHandler`，必须走 PacketDistributor。

### AE2 库存来源对齐参考实现（`Ae2ClientMenuInventoryProvider`）

- 驱动认领范围从 `CraftingTermMenu` 放宽到 **`MEStorageMenu`**（物品终端、样板终端等
  也走直接 API，不必绕反射兜底）；
- 库存里并入**打开菜单中不属于玩家背包的槽位**（合成终端的 3×3）——这些料服务端确实
  可用，但客户端仓库不包含它们；不算进来，玩家把料放进网格后树仍显示缺、反复补料。
  结果槽（`CraftingTermSlot`）排除：那是"刚做出来还没取走"的产物，算进库存会让缺口提前归零；
- 刷新固定 **5 tick** + 内容指纹（参考实现的节拍）：仓库条目动辄上千条，逐帧重扫没必要，
  而且会让 `analyze()` 的 revision 每帧都变、整个侧栏跟着重建；
- **断链记 0**：AE2 断网后仍保留上一次同步的条目，直接采信会让"网络里有多少"虚高，
  于是树判定已满足、补料也不再发。

## 3.10.7 — 2026-11-18

### 整棵结构常驻：行不再随库存增删，只把数字变 0

3.10.6 只保住了"这一层还被需要"的行：某个中间物一旦收够，`calculatePlan` 就不再往下走，
它下游的原料行仍然会消失。玩家要的是"结构常驻、数字归零"。

做法是把**一个数字拆成两个**：

- `Node.wanted`：**结构量**——这条分支按满批要多少，与库存无关。决定"有没有这一行"。
- `Node.amount`：**计划量**——扣完库存与兄弟溢出后还要多少。决定"这一行写几个"。

`calculatePlan(node, wanted, desired, …)` 同时下发给子节点：`wanted` 按满批批次算
（`fullCrafts`），`desired` 按净批次算（`crafts`，库存满足时为 0）。于是：

- 子树照旧铺满，`collectCraftSteps` 的判定从 `amount > 0` 改成 `wanted > 0`，
  缺 0 批的中间物仍有行、batch 为 0；
- 叶子材料无论净值是否为 0 都登记（`addCost(costs, node, remaining, wanted)`）；
- 画布节点与角标显示计划量：收够的节点照常显示"要 60"，它下面显示 0——一眼看出
  "这儿不用再做了"；
- 侧栏步骤行的缺口改为读 `CraftStep.remaining`（制作模式，含兄弟溢出）；非制作模式
  没有库存与缺口可言，仍按结构量显示。

`RecipePlanner.resetAmounts` 同时清 `wanted`，避免上一轮的值残留。

## 3.10.6 — 2026-11-17

### 行不再因"材料够了"而消失；算子页有提示；左键选配方真的生效

三件事，用户报的都是"看起来没反应"。

**一、行集合由配方结构决定，不再由库存决定**

3.10.5 把需求改成净值（JEC 口径）之后，`calculatePlan` 一旦算出 `remaining <= 0` 就直接
`return`，`RecipePlanner.addCost` 又把 `amount <= 0` 丢掉——于是**库存够了的材料整行消失**，
材料清单跟着少一格，中间物行（`collectCraftSteps` 要求 `crafts > 0`）也一样。
净值是对的，但"行在不在"不该由库存回答。

- 叶子材料：净值 0 也登记（`addCost(costs, node, 0, desired)`），行还在，角标 0、绿框；
- 中间物行：判定从 `crafts > 0` 改成 `amount > 0`（= 这个节点确实参与了计划），
  缺 0 批时 batches 为 0，行保留；下钻仍只按净值走，所以满足的分支不会继续铺子材料；
- `Cost` 拆出两个数：`required`（净缺口，红态/补料/角标用它）+ `wanted`（结构需求，
  界面「需求」列用它）。只留净缺口时，"需求 53 / 已有 7" 会被读成"53 里我有 7"，
  而角标又写 53；只留结构量，缺口又算不回来。
- 顺手校正一处口径：`MaterialFetch` 的注释写明"请求量传目标总量，SIA 会再减一次背包"，
  但 3.10.5 之后它拿到的是净缺口，会被减两遍。改用 `wanted`。

**二、算子页（选择器）补悬停提示**

`CandidatePicker.render` 只画格子和白框，全类没有一次 `renderTooltip`。选择器盖住整屏，
JEI 自己的悬停提示进不来，所以名称必须自己画：物品名 + 身份键（灰）+ 标签成员数；
金框那一格补一句「已在候选里」。

**三、左键给子节点指定配方**

链路本来是通的：左键 → JEI 配方页 → 树按钮 → 编辑页 → 保存。问题在"保存之后"：
`RecipeTreeSession.resolve` 只调 `tree.rebuild()`，而 `RecipeLibrary.match` 返回
`matches.get(0)`——**库里最早那条**。新选的配方排在队尾，永远轮不到，表现就是"选了没反应"。

- `resolve(layout, recordId)`：重建前 `tree.bindResolution(节点键, 配方)`；
  库记录的 ref 没有 JEI 类别，只能按落库 id 绑定（编辑页把 `record.id()` 传出来）；
- `preferredRecipe` 的显式分支：`RecipeLookup.snapshot` 失败时回落到 `StoredRecipes.byId`
  （否则库配方查不到快照，又被静默换成库默认）；
- 绑定写进会话文件（新字段 `resolutions`，老文件没有它按库默认走），重开不会悄悄变回去。

**四、删掉树里的「物品目录」死路（统一入口）**

`showCandidateDirectory` 打开的 JEI 目录页只注册了 INPUT 槽，全项目没有任何回调把点击写回
节点——点了什么都进不了树。现在左键图标与图标右上的「+」是同一个入口（`chooseInput`：
先选"这件材料是谁"，选完再点一次进配方页）。JEI 配方页自带的那套目录
（`DirectoryIngredientElement` + 配置开关）与它无关，原样保留。

帮助文本同步写清三个「+」的区别：图标右上 = 选材料、节点左上 = 搬材料、屏幕左上 = 换树根。

## 3.10.5 — 2026-11-16

### 需求按 JEC 口径扣库存；流体单位统一成 mB

两件事一起交付。

**一、中间物已有就不再要它的原料（JEC 口径）**

实机表现：`红砖 需求60/已有7/仍缺53`、`黏土球 需求60/已有53/仍缺7`——黏土球的 60 是按满批 60 个红砖算的，
明明已经有 7 个砖。根因是两套算法两套口径：

- `calculatePlan()` 只用 `remainders`（兄弟溢出）扣减，**从不读库存** → 成本表永远是满批；
- `calculateProgress()` 会扣库存，但结果只进 `missingCosts`；
- 界面再拿"需求 − 已有"算缺口，于是原料行与中间物行互相打架。

改成 JEC 的做法（`CostList`：`+库存 − 需求` 成净缺口，之后只对负值展开配方）：
`calculatePlan` 新增 `available` 参数，先 `consumeForNode(available, …)` 再扣兄弟溢出，
子材料按剩下的 `remaining` 决定批次数。`craftingMode` 关闭时传空表，回到满批口径。
`compareVectorPlan` 的向量也并入同一份库存，否则对照全是库存造成的假阳性。

界面按 JEC 的两列走：`仍缺`（成本行的 required 已是净缺口，不再二次扣减）+ `已有`（去掉 `/总量`）。

**二、流体：输入 11 显示 1100 的真正原因**

树里流体的记账单位是"份"（≈代表栈的 mB），而编辑界面写进去的是 mB，同一个数字被换算两遍。
3.10.5 把单位统一成 **mB**：

- `FluidRecipeCompat.amountForUnits()` 改成恒等（不再乘代表栈的 mB）
- `RecipeArchive.records()` 不再把 mB 折成容器个数（`verify-library` 的守卫同步反向）
- `RecipeTreeData.Node` 新增 `perCraft`：物品=个数、流体=**mB**，不再拿展示栈的 `count`
  （那是桶数，2500 mB 会被算成 3）当乘数

顺手修编辑器：

- 输入 `0` 变 `00`：数字只由 `charTyped` 接收，`keyPressed` 不再重复追加同一个字符
- 滚轮步进：默认 10 mB、Ctrl 100 mB、Shift 1000 mB（原来只有 100 / 1000 两档，250 滚不出来）；
  物品仍然 ±1 / Shift ±16
- 槽上**右键**直接打开数量输入（原来必须先空手、再左键弹浮层）

## 3.10.4 — 2026-11-14

### 帮助文本补齐修饰键，并把说错的地方改对

帮助里原来只写了「Shift+左键从背包自动选」——既没提「推演」，也说错了机制（推演是按候选配方
自动取第一个，跟背包里的东西无关；C6/R6 之后就与库存解耦了）。中英两份一起改：

- 补上 **Shift+左键 = 推演**（按候选配方取第一个，跳过自动选择里排除掉的）
- 补上 **Shift+右键 = 取消推演**（退回原料，不再套用库里默认配方）
- 右键折叠写明「再右键展开，折叠状态会一直保留」（对应 3.10.3 的持久化）
- 左键材料节点改成真实行为：进入「给它选配方」，打开 JEI 配方列表挑一个；
  多候选未锁定的节点先弹候选目录；左键分类图标才是打开当前配方的原页
- 删掉「递归合成优先选择下层材料已具备的候选」——C6 之后已不按库存挑候选，这句是旧的
- 书签行写成「要带修饰键」：Shift 只搬直接材料、Ctrl 递归合成、普通左键交还 JEI
- `autoResolve` 里那句过时注释（写成 Ctrl-click）也改成 Shift-left-click

## 3.10.3 — 2026-11-14

### 取消推演真的生效了；右键折起的节点不再自己弹回来

玩家反馈：**「取消展开子节点只在 shift+左键推演的配方生效，展开错了的子节点却关不掉」**。

两个独立原因，都修了：

1. **`clearResolution` 是个空操作**。它把 `resolutions.put(key, null)` 当成「这位置不要默认配方」，
   但 `preferredRecipe` 读的是 `resolutions.get(key)`，`null` 与「没记录」完全一样，于是立刻回落到
   `StoredRecipes.forTarget(...)`，节点又照默认配方展开了——只有手动选过配方的节点（键存在且非 null）
   才看得出「取消」有反应。现在单独记一个 `suppressedResolutions` 集合：取消后该位置就是原料，
   再点「推演」（`resolve`）会把这个抑制撤掉。
2. **右键折叠的状态活不过一次 rebuild**。折叠只翻 `node.expanded`，而 `rebuild()` 每次都重建整棵
   `Node` 树，默认又是展开的；只要中途发生重建（选配方、改候选、库存变化后的重算路径），
   折起的节点就自己回来了。现在 `collapsedPaths` 按路径记住折起状态，重建出来的同路径节点继续保持折起。

## 3.10.2 — 2026-11-13

### 单独把「自动恢复后立即打开供应视图」捡回来

3.10.1 整棵回退到 `v3.10` 时，这条修复被连带撤掉了。它跟精妙那条线无关，单独取回：

- `RecipeTreeSession` 现在在模式切换 / 打开 / 恢复这几处都同步 `SupplyLedger.setCraftingMode(...)`，
  自动恢复出来的制作树会立刻打开供应视图（`restore scans` 不再为 0）
- 其余仍与 `v3.10` 一致：`git diff v3.10 -- src/` 只有这一个文件、11 行

## 3.10.1 — 2026-11-13

### 回退到 3.10 的代码状态

玩家裁定：精妙合成升级那条线到此为止，代码按 **3.10** 算。工作树内容与标签 `v3.10` 完全一致
（`git diff v3.10 -- src/` 为空），3.11–3.15.8 的改动全部撤掉：

- 删掉 `ContainerContentTraceMixin` 及其 `mixins.json` 条目
- `AbstractContainerScreenMixin` 里撤掉槽点击追踪
- `RecipeTreeTransfer` 回到 3.10：没有 `dumpMenuOnce` 的升级容器清单、没有 `customResultSlot`、
  没有 `tickClient`、没有探针心跳；第三方合成界面重新走 `timed machine: inputs only`（只装料）
- `RecipeTreeSession` 回到 3.10，**连带撤掉 3.12 的「自动恢复后立即打开供应视图」**；
  如果之后还想要这条，`c7e836f` 可以单独捡回

3.11–3.15.8 的实现仍在 Git 历史里（标签 `v3.11`…`v3.15.8`），需要时可直接取用。

## 3.15 — 2026-11-13

### 3.15.8 停掉精妙合成升级的专用逻辑（保留通用部分）

精妙这条线到此为止：不再猜它怎么取产物，也不再替它开标签。

- 删除 `openCraftingTab` / `hasCraftingUpgrade` / `craftingContainer` / `invokeWithArgs`，
  递归与普通转移都不再主动切标签；「找不到结果槽」回到只认 `hasGenericResultSlot` 的旧判定，
  精妙的合成界面重新落回 `timed machine: inputs only`（只装料）
- 保留 `customResultSlot`（按槽类型找 `ResultSlot`，对任何非原版菜单都成立，不是精妙专用）
- 保留 `tickClient`（`StorageScreenBase` 这类重写 `render` 不调 super 的界面，异步队列仍要有人驱动）
- 保留探针心跳日志（仅在开发者模式）

### 3.15.7 普通转移（shift）也先把合成标签打开

实机截图：桶的第一行存储格里躺着 5 种材料（染料 54、木板 54、木板 54、原木 30、木棍 8），
而右侧那个 3×3 合成格和下面的结果槽**是空的**。日志对得上：13:07:48 / 13:07:55 两次都是
`ctrl=false shift=true recursive=false`——**shift 走的是"只装料"分支**，而精妙的 `quickMoveStack`
在合成标签没展开时会一路落到存储区（`mergeStackToStorage`），于是料全进了桶。

- `transfer(..., recursive=false)` 在 `runTransfer` 之前也调 `openCraftingTab(menu)`：
  标签一展开，`shouldShiftClickIntoOpenTabFirst()` 才会把料导向合成格，而不是存储区
- 与原版合成台语义一致：shift 只负责把料摆好，产物仍由玩家自己点

### 3.15.6 节拍器改由客户端 tick 兜底：精妙界面根本不会触发我们的 render 注入

3.15.5 的实机日志里，13:02 起新会话只有 `container content: InventoryMenu` 和一串 `press/click`，
**连一行 `craft tab:` 都没有**。查精妙核心的字节码才看明白：

```
StorageScreenBase.render(...) { renderBackground(...); renderSuper(...); }   // 全程不调 super.render
```

`StorageScreenBase` 把 `AbstractContainerScreen.render` 整个重写了（自己 `renderBg`、画槽、画标签），
而我们的节拍器挂在 `AbstractContainerScreen.render` 的 HEAD。于是对精妙的界面，这条注入
**一次都不会触发**：转移包发出去了（`transfer -> done=true`），异步状态机却原地不动，
产物自然永远没人取——这才是「只装料、不合成」的最后一层原因。

- 新增 `RecipeTreeTransfer.tickClient()`：每客户端 tick 检查一次，本 tick 若有渲染路径驱动过就不管，
  没有就自己驱动一次。渲染路径（原版界面，每帧驱动）行为完全不变
- `JERTScreenInput.onClientTick` 调用它（与 `MaterialFetch.tick()` 同处）

### 3.15.5 转移前主动把合成标签打开，并给结果探针加心跳

3.15.4 的实机日志把最后一环补齐了：

```
craft menu: class=StorageContainerMenu id=2 slots=63 resultSlot=-1 genericResult=true
  upgrades= CraftingUpgradeContainer:10 [-1/64 …] [-1/73 CraftingUpgradeContainer$2 216,145]
```

- `slot.index` 是 **64–73**（说明这 10 个槽确实被 `addSlot` 过），但 `menu.slots.indexOf` 全是 **-1**，整表只有 63 个槽。结论：**标签没展开时它们被从菜单里摘掉了**，原版点击包点不到；展开后才挂回来。这解释了为什么「必须手动切一下标签」。
- 转移之后日志再没有任何后续行——探针没在跑，还是跑了没结果，从日志里分不出来。

于是：

- `startNextCraft` 在算结果槽之前先调 `openCraftingTab`：反射读 `upgradeContainers` 里带 `getRecipeSlots()` 的那个容器，没展开就 `setOpenTabId(getUpgradeContainerId())`。槽位回到 `menu.slots` 后才有可能被点到
- 结果探针每 10 帧打一行 `probe: waiting result slot, slots=… noPlace=…`：槽数有没有从 63 变 73、结果槽有没有出现，一眼可见

### 3.15.4 精妙合成升级：认出它的结果槽，别再当「定时机器」只装料

实机截图确认了界面长什么样：合成升级是桶界面右侧的一个标签页，标签展开后才有 3×3 输入格和下方那个结果槽。

3.15 的结论是「那 10 个槽没挂进 `menu.slots`」，但精妙自己的 CraftingTweaks 兼容层是用 `getRecipeSlots().getFirst().index` 当网格起点的（`CraftingUpgradeTweakProvider.getCraftingGridStart` 字节码），说明**标签展开后它们确实在 `menu.slots` 里有正常序号**；之前 dump 到 -1 的那次，标签是关着的。两种状态都得能走通，所以两头都补：

- `instantResultSlot` 末尾加 `customResultSlot`：按槽类型找 `ResultSlot`（不可放物槽的通用契约），不再依赖「菜单必须是原版那几种」的类型分支。这是「标签已展开」时生效的主路径
- 找不到结果槽时，如果菜单里挂着带合成网格的升级容器（`hasCraftingUpgrade`，反射读 `upgradeContainers` + `getRecipeSlots`），就进入结果探针等待，而不是立刻 `clearPending("timed machine: inputs only")`。精妙的 JEI 转移处理器自己会把标签展开，探针每帧重扫 `menu.slots`，标签一开就能接上
- 真正的定时机器（没有可等的结果槽）仍然是原来那条兜底，不点任何猜测槽位

### 3.15.3 定死精妙合成槽的位置，并记录客户端真实发出的点击

3.14 的日志把矛盾解开了：那 10 个槽的菜单序号**全是 -1**，`slotGroups` 也只有 264 个存储格 + 36 个背包格。也就是说，合成升级的 9 格输入与结果槽**根本不挂在 `menu.slots` 里**，原版点击包无法定位它们——这才是「只放料、不合成」的真正原因（我们点到的其实是存储格）。

精妙的 `CraftingUpgradeContainer.handlePacket` 只认三个操作（`shiftClickIntoStorage` / `selectResult` / `refill_crafting_grid`），没有「取产物」。它取产物到底发什么包，只能看实际发出的点击。

- 新增 `ClientClickTraceMixin`：只记录、不改行为。开发者模式下，在 Storage/Crafting 类菜单里把客户端发出的 `menuId / slot / 按钮 / 类型` 打一行
- `upgrades=` 每个槽补打容器索引，格式 `[菜单序号/容器索引 类名 x,y]`

## 3.14 — 2026-11-13

### 诊断再进一步：升级容器的每个槽在不在 `menu.slots` 里

3.13 的日志出现了矛盾：`upgrades= CraftingUpgradeContainer:10`（容器在、10 个槽都在）却又 `resultSlotInstances=0`（菜单里没有结果槽）。这两条只有在「容器自己持有槽、但没挂进菜单」时才同时成立。

- `upgrades=` 现在逐槽打印 `[菜单序号 类名 x,y]`，序号 `-1` 就是没挂进菜单
- `hiddenGroups=` 换成 `slotGroups=`：全部槽按「槽类/容器类」分组统计，不再只看隐藏的那部分
- 行为无变化

## 3.13 — 2026-11-13

### 诊断增强：合成菜单打印升级容器清单与隐藏槽分组

3.11 那行日志已经能看，但信息不够定因：`resultSlotInstances=0` 说明菜单里没有 `ResultSlot`，`hidden` 只列了前几格，看不出那 120 个隐藏槽是什么成分。

- 新增 `upgrades=`：反射读菜单的 `upgradeContainers`，逐个打印「容器类名:槽数」。装了合成升级时这里必然出现 `CraftingUpgradeContainer:10`（9 个输入 + 1 个结果），没有就是该界面根本没挂合成升级
- `hidden` 改成 `hiddenGroups=`：按「槽类/容器类」分组统计，直接看出隐藏槽的成分
- 行为无变化

## 3.12 — 2026-11-13

### 自动恢复的配方树：供应状态不再要手点一次才刷新

现象：重进世界后配方树自动恢复（侧栏树行在），但所有材料都按「库存 0」显示，必须手动点一次配方树按钮打开树页，状态才变正确。

原因：`SupplyLedger` 的供应视图有个总开关 `craftingModeEnabled`，而它此前**只有 `RecipeTreeScreen.tick()` 一处写入**。自动恢复的树不会经过树页，开关一直是 `false`，`snapshot()` 直接返回空表——恢复路径的日志 `restore scans: 0 total` 就是这条，整条恢复流程一次都没扫过库存。

- `ensureRestored()` 恢复成功后立即 `SupplyLedger.setCraftingMode(true)`，随后的 `refreshNow()` 才拿到真实库存
- `setCraftingMode()` / `restoreCraftingTree()` / `clear()` 三处会话切换同步这个开关，不再依赖「树页正开着」
- 树页 `tick()` 的写入保留：查看模式仍然零扫描（R4）

## 3.11 — 2026-11-13

### 诊断版：打印合成菜单结构（定位精妙合成台「只收料不合成」）

现象：在精妙存储的合成升级界面里从配方树搬运，材料进得去、产物不出来，日志停在 `transfer: timed machine: inputs only | resultSlot=-1`。

这条说明 `instantResultSlot()` 与 `hasGenericResultSlot()` 同时失败，但两者为什么会失败还分不清：结果槽可能压根没挂进菜单，也可能挂进去了但判定没认出来。

- 新增 `dumpMenuOnce()`：每次换菜单记一条，含菜单类名、`containerId`、槽数、`ResultSlot` 实例数、不可放物槽清单（结果槽特征）、坐标为负的隐藏槽清单（含各自 container 类型）
- 同一个菜单只记一次（按 类名 + containerId + 槽数 去重），不刷屏
- 纯诊断，行为没有任何变化

## 3.10 — 2026-11-13

### 输入条与底部按钮的「误导」：输入态下按钮接替回车/ Esc

输入条正好压在「保存 / 关闭」上方，玩家会当成「确认 / 取消」——但两者根本不是一回事：回车确认这条输入，`保存` 是把主组整份写回磁盘，`关闭` 是关掉整个配方库。

- 输入条打开时，底部两颗按钮**接替回车与 Esc**：左=确认这条输入（`commitPrompt`），右=放弃这条输入
- 文案同步变成「确认 / 取消」（新增 `library.confirm` / `library.cancel`，中英各一条），不再写着「保存」却干确认的事
- 输入条关掉后按钮回到「保存 / 关闭」的原有语义

## 3.9 — 2026-11-13

### 树行不再画到 ESC 菜单上：区域只对「它所属的那块屏」有效

3.6 把判定简化成「区域非空 + 有树行」之后，暂停菜单上也长出了一条树行。

原因是 `area` 是静态字段、跨屏存活：JEI 在 ESC 菜单这类界面上既不放书签、也不刷新书签边界，
我们手里还留着上一块屏算出来的 55px 区域，照画不误。

- `RecipeTreeSidebarPanel` 记录 `areaScreen`（`setArea` 时取当前 screen，`clear` 时置空）
- `canDraw()` 增加一条：`areaScreen == Minecraft.getInstance().screen`
- 效果：切到 ESC 菜单时区域被判为「不属于这块屏」，不画、也不吃点击；回到容器界面后 JEI 重新算边界，树行随之回来

## 3.8 — 2026-11-13

### 配方库面板底部布局重排 + 顶部两行按钮对齐

三处都是坐标打架，不是观感问题：

- **输入条压在保存/关闭上**：输入条 y = `LIST_TOP + 7×18 + 4` = 206，保存/关闭按钮同样从 206 起，完全重合。现在自上而下是：列表 202 → 输入条 204..224 / 提示行 206 → 保存·关闭 228..244，面板 242 → 252
- **两行按钮不对称**：第二行原本自己用 52/44 宽、右列还左移 6px（右缘 164 vs 168）。现在两行共用三个常量列（8/52、64/58、126/42），右缘统一 168、左右留白各 8；底部保存/关闭也统一成 42 宽、右缘对齐 168
- **搜索框里带着「搜索: 」前缀**：激活时把 label 塞进了输入内容，58px 宽里近一半被前缀吃掉，看不到自己打的字。现在输入态只显示字符 + 光标，「搜索」只在空且未激活时作占位
- 顺带删掉不再使用的 `gui.jeirecipetree.library.search.value` 文案键（中英各一条）

## 3.7 — 2026-11-13

### 配方库面板：文字按像素宽度收口；删除提示改说产物名

提示行直接贴配方 id（`minecraft:smelting/minecraft:iron_ingot_from_smelting_deepslate_iron_ore`，70+ 字符）会一路画到面板外、压在右侧物品列表上。

- 新增 `drawFitted`（按像素宽度截断 + 省略号）与 `drawWrapped`（按像素折行），面板内单行文字全部走它：提示行、列表行概要、搜索框、输入条
- 列表行原先只限「20 个字符」，中文环境下 20 个汉字约 180px，同样会戳出去
- 删除提示改为产物名：`已删除：铁锭` / `Deleted: Iron Ingot`（文案从「已删除并保存」缩短）；取不到产物才退回截断后的 id
- `icon()` 的产物解析抽成 `outputStack()`，提示语与图标共用同一份

## 3.6 — 2026-11-13

### 撤掉 3.5 的反射判定（它会返回 false，导致树行整块不显示）

3.5 试图用 `hasValidScreen()` + `isOverlayEnabled()` + `isBookmarkOverlayEnabled()` 判断「JEI 在不在」。实机日志：

```
bookmark grid bounds: area=[0,0,222,221] rows=5 active=true enabled=true height=55
press: visible=false area=[0,166,222,55]      ← 区域算对了，可见性却是 false
click -> rejected by gate
```

区域和行数都正常，唯独那组反射开关返回 false，于是 `render` 一次都没跑。

- 删除 `jert$jeiOverlayOn()`，判定回到「JEI 给没给地方（area 非空）+ 有树行」
- 保留 `RecipeTreeScreen` 的直接排除（全屏树不预留、不重复画）
- 依据：能走到 `drawForeground` 的 TAIL，本身就说明 JEI 正在这块屏上画书签 overlay

## 3.5 — 2026-11-13

### 补回「有 JEI 才出现」的判定，并排除全屏树界面

3.4 把 `isListDisplayed()` 整个摘掉后出现副作用：全屏树界面自己也被画上一条侧栏树行（此前正是 `jert$list` 在树界面把 `isListDisplayed()` 强制成 false 才挡住的）。

- 新增 `jert$jeiOverlayOn()`：`guiPropertiesCache.hasValidScreen()` 且 `toggleState.isOverlayEnabled()` 且 `isBookmarkOverlayEnabled()` 都为真才画。反射读取，读不到按「在」处理（宁可多画一条，也不因 JEI 内部改名整块消失）
- `preferredHeight` 与绘制都在 `RecipeTreeScreen` 上直接返回 0／不画：全屏树自己就是主体，不预留、不重复
- 仍然不把 `contents.hasRoom()`、`!bookmarkList.isEmpty()` 纳入判定——那两条与树行无关，是 3.4 修掉的根因

## 3.4 — 2026-11-13

### 侧栏树行的显示不再借 JEI 的判断（修「只有 JEI 没有咱们」）

实机现象：JEI 配置界面、暂停菜单这类**非容器界面**里，JEI 自己的书签网格在，我们的树行整块没有；容器界面里正常。

根因在渲染入口，不在布局（日志已证明 `height=55 columns=11 slots=5` 一直算得对）：

```java
// JEI 1.21.1 BookmarkOverlay 原文
public boolean isListDisplayed() {
    return toggleState.isBookmarkOverlayEnabled()
        && guiPropertiesCache.hasValidScreen()
        && contents.hasRoom()
        && !bookmarkList.isEmpty();
}
```

我们的 `render` 完全挂在这一个判断上，而后两条与树行毫无关系：书签列表为空、或我们切走 57px 后网格没了空间，都会让它变 false，于是树行跟着消失。

- `drawForeground` 的注入改为**不再判断** `isListDisplayed()`，只看「区域可用 + 有树行」（`RecipeTreeSidebarPanel.canDraw()`）
- `setOverlayVisible(false)` 不再清空 `area`：区域是布局事实，只归 `updateBounds` 的 Redirect 与 `clear()` 管。此前任何一帧 false 都会把区域抹掉、下一帧再算回来
- 3.3.1 的判定日志仍在，但改为**值变化时才记**——`updateBounds` 实机会被每帧调用，原写法一次开界面刷上百行
- 撤掉 3.3.3 的洋红调试边框（诊断用途已完成）

## 3.3.3 — 2026-11-13

### 开发者模式下给侧栏树面板画一圈洋红边框

"看不到树行"有三种病，肉眼分不清：区域没留、留了但没树行数据、画了但被别的层盖住。

- `developerMode` 开启时，面板矩形描一圈洋红 1px 边框（关掉配置即消失）
- 区域预留但树行为空时记一条 summary：`sidebar tree panel: area reserved but no rows yet`
- 配合 3.3.1 的 `bookmark grid bounds` 一行，三种病可以对号入座

## 3.3.2 — 2026-11-13

### 侧栏面板可见行数算多了

实机日志：`height=55 columns=11 visibleRows=2`，但 55px 里已经含 3px 线距 + 20px 翻页条 + 2px 间隔
+ 上下内边距，净空间只够 **1 行**（18px）。`setArea` 只扣了内边距就除步距，于是：

- 第二行会画到面板之外，压住 JEI 自己的底部按钮
- 命中区多出一排幽灵槽位（点空白也有反应）

改为扣掉"线距 + 翻页条 + 间隔 + 内边距"再除步距，与 `preferredHeight` 的算法对齐。

## 3.3.1 — 2026-11-13

### 侧栏树行：加一条判定用的诊断日志

"侧栏看不到树行"有两种可能：区域没预留，或者根本没有树行数据。之前只能靠猜。

- `BookmarkOverlay` 的 `updateBounds` 处输出一行 debug：
  `bookmark grid bounds: area=… WxH rows=… active=… enabled=… height=…`
- 预留成功时的 summary 日志保留（高度/列数/可见行数/槽数）
- 无行为改动，只为把上面两种可能分开

## 3.3 — 2026-11-13

### 子节点改固定步距（修横向堆叠）；侧栏树行自愈

**一、全屏树：兄弟节点按固定步距排**

2.15.8 为了"底板宽度按数字自适应"，把布局改成"上一个叶子的右缘 + 留白 8"推进光标。叶子只有 51 宽，
算出来的中心距是 59，而配方节点宽 72 —— **每对兄弟横向压进 13px**，实机就是"子节点叠在一起"。

- 新增 `TreeLayout.X_PITCH = RECIPE_NODE_WIDTH + COST_SPACING`（80），布局期只做整数加法
- 不变量：同层任意两节点中心距恒为 80 的整数倍，底板最宽 72 → 间隙恒 ≥ 8，结构上不可能相交
- `rebuild()` 不再接收"节点宽度函数"（删 `widthFn` / `widthOf`）：布局期**不再量任何文本**，
  省掉每节点一次 `font.width`，也不再需要客户端字体上下文
- `nodeWidth()` 夹到 `RECIPE_NODE_WIDTH`：底板不会超过步距能容下的宽度
- 代价：叶子放进 80 的槽里左右各空 14.5，整棵树比按内容排宽约三成（自动适配会相应缩小）

**二、侧栏树行：高度算 0 之后不再重算**

JEI 只在窗口属性变化时调 `updateBounds`。首次调用若树行还没刷新出来，`preferredHeight` 返回 0，
这一屏之后不会再算 —— 表现是"JEI 书签在、我们的树行整块不见"。

- `preferredHeight` 先问一次 `JeiTreeIntegration.isActive()`（内部按节流刷新），避免首帧判空
- mixin 记住 JEI 传进来的可用区域与排除区；绘制时发现"有树行但没有区域"就补切一次并让 JEI 重排网格
- 预留成功记一条 `summary` 日志（高度/列数/可见行数/槽数），下次对不上截图可直接看日志

## 3.2 — 2026-11-12

### 配置界面按语言显示描述

- 配置项与分类的描述改用 MezzConfigGui 的 `<翻译键>.tooltip`：装了 MezzConfigGui 时按当前
  语言显示单一种语言，没装时回退到 TOML 注释（中英双语）。
- 配方库提示里的「F8」改成读取实际按键绑定：玩家改键后提示不再指错键。

## 3.1 — 2026-11-12

### 界面文案全部走语言文件

- 配方库（F8）、配方编辑页、候选选择器的所有文字改用翻译键，补全 `en_us`；
  非中文客户端不再看到硬编码中文。
- 键位名称去掉硬编码的「（F8）」「（F9）」：玩家改键后文案不再自相矛盾。
- 启动期冲突提示（JEI-Enhancements / jei_plus_plus）统一改为英文日志。

## 2.15 — 2026-11-12

### 配置界面、字号、侧栏树面板

- **配置界面交给 MezzConfig GUI**：检测到 `mezz_config_gui` 就不再注册自带界面（此前是我们自己把它挡住）。
- **字号四档可选**（`small` / `normal` / `large` / `huge`）；配置项从"手打 id"改成点选清单（枚举），
  并补配置写盘契约测试，注释差一行也不许触发整文件重写。
- **画布文字**：统一白字（含"总耗材"），背板逐版收紧直至全部去掉；节点与成本行经历
  "彩色胶条 → 缎带贴图 → 矩形"三版，两端锯齿两版都被否，最终定矩形 + 数字左贴图标。
- **数字**：只保留一种字号、拉丁字体（中韩日环境 unifont 下数字断续）、不画阴影；
  宽度随材料区自适应（布局与绘制收敛成同一个宽度函数）。
- **侧栏树面板整套照 JEI 源码对齐**：槽距 18 与列数公式（受 `maxColumns` 约束）、
  翻页条（`PageNavigation` 的按钮尺寸/配色/整页翻）、左边留白 `BORDER_MARGIN`（无底 6、有底 13）、
  分隔线虚线/实线分档、无底不画槽位格。
- **状态改由边框表达**：够/缺不再用数字颜色，改画槽位外圈 1px 彩色边框（绿=够、红=缺、
  橙=中间产物缺料、蓝=其他），数字统一白色，去掉左上角 2×2 色点。hover 保持 JEI 原样（50% 白遮罩）。
- **树页背景**：恢复"纸色 + 细格 13 + 每 5 格实线主线"；中途曾误改为只留虚线，已撤回。

## 2.14 — 2026-11-11

### 六套配色、唯一树界面、角标可读性

- 配色从四套扩到**六套主题**（paper / classic / blueprint / blueprint_dark / ae2 / dark），配置可切，
  树界面里也有切换按钮（左键下一套、右键上一套）。
- 节点底板覆盖所有节点（含叶子材料），带底色与描边。
- **树界面唯一化**；数量角标不再重影（落点取整、只按 1 或 0.5 分频、不画阴影）。
- 数字改拉丁字体（中文环境的 unifont 数字断笔）；浅底主题的墨色压深到可读；主题按钮左右键分工。
- 库存扫描不再只在切换制作模式时发生；网格跟着平移缩放走（图纸观感）；打开树页不再自动弹配方库。

## 2.13 — 2026-11-11

### 编辑页交互换成 JEC 状态机；层高审查

- 编辑页改成 JEC 的"手上标签"模型：点槽选候选、滚轮加减；提示文案补上"拖 JEI 图标进槽"。
- 悬停/可放下提示改回 1px 边框（填充会糊住图标）；拖动时槽位终于显绿（JEI 自己那层被面板盖住）；
  拖动高亮不再"闪一下就灭"。
- 层高审查又找出两处被物品图标盖住的元素并抬层。
- 树页背景：26px 单层实线网格 → 13px 细格 + 每 5 格主线。

## 2.12 — 2026-11-11

### 对照 JEC 源码补齐三件套

GuiLabel 四页签、WAmount 槽位浮层、GuiRecipe 三件套逐项对齐。

## 2.11 — 2026-11-11

### 候选选择器移进配方树

选择器不再单开界面，移进树内并修掉图标穿模；编辑页定位照搬 JEC。

## 2.10 — 2026-11-11

### 库管理与编辑页改通用 GUI

- 库管理 / 编辑页改成通用 GUI，JEI 不再被压掉；增删改查全部按钮化。
- JEI 侧栏"可拖不可见"根治；候选改为"JEI 悬停那排同类物品"；删除"原配方（只读）"框。

## 2.9 — 2026-11-11

### 点槽 = 选候选

点槽直接选候选（JEC 的 GuiLabel），数量加减交给滚轮；数量角标不再压图标。

## 2.8 — 2026-11-11

### 编辑改成独立界面

原配方自己画，槽位不再被 JEI 压住；新增槽位绑 N 键。

## 2.7 — 2026-11-11

### 编辑面板重做，流体用量修正

- 编辑面板照 JEC 的槽位重做，并修掉"JEI 画在面板上面"。
- 流体用量不再被磨成 1 mB；多候选图标不再自己轮播。
- 实机三连修：编辑不再盖掉 JEI、说明不再压按钮、库入口找得到。

## 2.6 — 2026-11-11

### 树背景换成纸色方格

按玩家给的参照稿把背景做成纸色方格。

## 2.5 — 2026-11-11

### 修「一键合成」失效

修掉两个让一键合成失效的真问题；建布局时再找回一次配方对象作兜底。

## 2.4 — 2026-11-11

### 从零新建配方

补上 JEC 的「新建」：不依赖已有配方也能从空库建条目。

## 2.3 — 2026-11-11

### 占位符材料与数字键填数量

占位符材料 + 数字键直接填数量，JEC 的最后两块标签功能对齐。

## 2.2 — 2026-11-11

### 流体进编辑槽、配方分组

- 流体进得了编辑槽；配方可以分组。
- 分组能改名、能删；移组不再只从 `default` 拔。

## 2.1 — 2026-11-11

### 手动建树

不回 JEI 配方页也能开树，直接按库里的配方手动建。

## 2.0 — 2026-11-11

### 底层换成 JEC 的机制

不再"树上一个按钮直写库"，改为 **导入 → 编辑 → 保存** 三段：

- `RecipeDraft` 内存草稿 + `RecipeEditScreen` 三组槽（输出 / 催化剂 / 输入），保存才落库。
- 配方库管理页（F8）：列出、选中、删除；整份替换写盘，沿用坏库守卫（手写 JSON 写坏时拒绝覆盖 + `.bak`）。
- 库分组（目录里每个 `*.json` 一组）、整包导出（X 键）、催化剂进计算并单独成组、
  编辑页加槽 / 换候选 / 从 JEI 拖图标进槽、库内配方可改。
- 多候选槽的图标按 1.5 s 轮播；库存口径仍取玩家 0–35 槽（与 SIA 的补料范围一致）。
