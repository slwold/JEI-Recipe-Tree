# 编码规范（ECT / Expedition-Crafting-Tree）

> 目标：**读起来像 Python 一样直白**——显式优于隐式、短函数、平铺控制流、命名即注释。
> Java 没有缩进语法，所以"Python 标准"在这里落成 8 条可检查的硬规则 + 4 条软约束。
>
> 本规范对**重写产生的新代码强制生效**；存量代码在迁移时按批次逐步收敛，不要求一次性整改。

---

## 一、八条硬规则（构建/评审时逐条核对）

| # | 规则 | 判据（可机检或有明确边界） |
|---|---|---|
| S1 | **函数体 ≤ 40 行**（不含 javadoc） | 超过就拆；数据转换算"一个动作一个函数" |
| S2 | **参数 ≤ 4 个**，超过用 record 封装 | 典型：`(tree, node, stack, flags, x, y)` → `RenderTarget` |
| S3 | **嵌套 ≤ 3 层**（if/for/try 合计） | 第 3 层还出现分支 → 提取方法或早返回 |
| S4 | **早返回，禁止深 else** | `if (!ok) return AZ;` 优于 `if (ok) { ...20 行... }` |
| S5 | **禁止魔法数** | 尺寸/颜色/z 层必须来自常量或 `UI-SPEC` 表；`0xFF8099FF` 直接写在绘制语句里算违规 |
| S6 | **命名不留缩写** | 禁用 `mgr` `btn` `cfg` `idx` `tmp`；`recipeTreeSession` 而非 `rts`。仅允许 JDK/MC 既有缩写（`x/y`, `id`） |
| S7 | **公开方法必须有 javadoc**，写"为什么"而不是"是什么" | `/** 返回玩家 0–35 格的量；容器与网络不计（口径见 UI-SPEC R7）。 */` |
| S8 | **无副作用优先** | 计算与 IO/渲染分离：纯函数放 `core`，副作用放 `platform`/`ui` |

## 二、四条软约束

- **C1 显式优于隐式**：不用隐式 `this` 链、不用可变静态字段做跨调用通信（现存 `cachedXxx` 一类需要在重写时收进实例）。
- **C2 异常只用于意外**：可预期的失败走返回值/枚举（`MaterialFetch.Outcome` 是范例）；`catch` 必须写明"为什么可以吞"。
- **C3 一族常量集中**：颜色、尺寸、z 层、超时阈值各一处；跨模块共用则提到 `core/Constants` 或 `ui/UiMetrics`。
- **C4 注释写"陷阱"**：不需要复述代码；需要记录的是"为什么不能那样写"（本项目 CHANGELOG 里的事故就是素材）。

## 三、分层与依赖方向

```
core/     纯逻辑：树模型、分析、供应口径、取料规则     ← 不 import loader / GUI / MC 客户端类
domain/   MC 数据类型适配：ItemRef ↔ ItemStack        ← 只 import MC 本体，不 import loader
ui/       屏幕、绘制、命中、输入                      ← 可用 MC 客户端 + JEI
platform/ 配置、事件、注册、mixin 引导                 ← 可用 loader API
```

**唯一允许的依赖方向**：`platform → ui → domain → core`。反向依赖一律视为设计错误。
这条决定了"能不能迁到 1.20.1"——**core 与 domain 与版本无关**，只有 `ui` 和 `platform` 需要按版本各写一份。

## 四、可读性样例（同一段逻辑的两种写法）

```java
// ✗ 现状风格：深嵌套 + 魔法数 + 缩写
for (var s : menu.slots) {
    if (s.container == inv || !cnt(s)) continue;
    var st = s.getItem();
    if (!st.isEmpty()) { add(all, st, st.getCount()); add(pl, st, st.getCount()); }
}

// ✓ 规范写法：意图命名 + 单一职责 + 早返回
for (Slot slot : menu.slots) {
    if (isPlayerSlot(slot) || !isSupplySlot(slot)) {
        continue;
    }
    ItemStack stack = slot.getItem();
    if (stack.isEmpty()) {
        continue;
    }
    supply.recordContainer(stack);
}
```

## 五、评审清单（每次提交前）

- [ ] 有没有 > 40 行的函数？有没有 > 4 参数的签名？
- [ ] 有没有把颜色/尺寸/z 层写成字面量？
- [ ] 新增的 `catch` 是否写清了吞掉的理由？
- [ ] 新增的 `static` 可变状态是否必要？（默认不允许）
- [ ] 公开 API 是否都有 javadoc 且解释"为什么"？
- [ ] 依赖方向是否只向下（platform → ui → domain → core）？
- [ ] 新增分支是否有日志/断言便于定位（见 §6）？

### 5.1 版本控制与交付

源码用 Git 管理（`main` 分支）。每轮交付的固定动作：

| 动作 | 说明 |
|---|---|
| 构建 + 全部机检 | `./gradlew build --offline`（17 个 headless 测试 + 23 项构建期机检） |
| 部署实例 | 替换 `26.3-Vanilla/mods/` 下的 jar，并**移除旧版**（防版本错位） |
| Git 提交 | 一次交付一个语义完整的提交；信息写清"改了什么、为什么、门禁结果" |
| 打标签 | 交付版本打 `v<版本>` 标签，作为可回滚的锚点 |
| CHANGELOG | 只记**玩家可感知的功能变化**；改名、版本重排、注释/文档整理、重构不写条目 |
| 记忆日志 | 工作区记忆追加当日进展与下一步 |
| 发布形态 | **私有仓保留全部注释；公有发布前清洗掉 .java / .gradle 里的所有注释**（见 §5.2） |

### 5.2 私有与公有的差异（只差注释）

- **私有仓**：注释就是设计依据（为什么这么写、哪种写法试过会坏），全部保留。
- **公有发布**：另存一份目录，跑 `python tools/strip_comments.py <目录>`，把 `.java` 与
  `.gradle` 里的行注释、块注释、javadoc 全部删掉；字符串/文本块里的内容（URL、TOML 注释块、
  JSON 字面量）不受影响。
- 配置文件（`*.toml` / `*.properties` / `*.json` / `*.mcmeta`）与文档（`*.md`）**不清洗**——
  那是给玩家和维护者看的说明，不是代码注释。
- 清洗后必须重新 `./gradlew build --offline`：机检**不许依赖注释文本**（断言只能看代码结构）。

`backups/` 目录**不再承担版本历史职责**（Git 逐次提交比 zip 快照更可追溯、也不占仓库体积），
既有快照保留在磁盘上不动；`.gitignore` 已将其与 `libs/`（AE2 API 二进制）排除。

---

## 六、开发者模式：可观测性契约（不可裁剪）

> 用户的硬要求：**没有日志就无法定位问题**。因此"能不能观测"是功能的一部分，不是可选项。
> 现有 `JERTLog` 已具备四档，本节把它固化成对**新代码**的强制约定。

### 6.1 日志四档（语义固定）

| 档 | 门控 | 用途 | 禁止 |
|---|---|---|---|
| `debug` | `developerMode=true` 时输出 | 决策点：输入命中、区域计算、驱动选择、缓存命中/失效、分支为何不走 | 热路径无脑拼接字符串 |
| `warn` | 常开 | 降级但可用：驱动下线、mixin 目标缺失、存储源异常、SIA 连续失败 | 用 warn 当普通流程日志 |
| `error` | 常开 | 功能失败且用户可感知 | 吞异常后不记 |
| `summary` | 常开，启动一行 | 环境快照：JEI 版本、输入架构、已加载驱动、可选模组矩阵、developerMode 状态 | 每帧输出 |

**硬规则**：每个**静默 return** 分支必须有一条 `debug`（例如 `if (!overlayVisible) { log.debug("skip: overlay hidden"); return; }`）。
本次迁移的所有重写代码都要满足——这是"为什么没反应"类问题的唯一线索来源。

### 6.2 性能计时（`Perf` 门面）

| 指标 | 采集点 | 阈值（开发者模式告警） |
|---|---|---|
| `perf.analyze` | 树分析一轮 | > 8 ms → `warn`（节流 10 s 一次） |
| `perf.render` | 单帧树渲染 | > 4 ms → `debug` |
| `perf.storage` | 存储源快照 | > 5 ms → `debug` |
| `perf.fetch` | 一次取料（含分包） | 批次 > 1 时记录总批数 |

约定：计时代码必须**在 `developerMode=false` 时零成本**（先判开关，再取 `System.nanoTime()`）。

### 6.3 诊断快照（一键 dump）

- 触发：开发者模式下按 `F9`（不占用游戏已有键位）→ 向日志输出一次性快照：
  树规模（节点数/深度/展开数）、缓存状态（命中率、上次失效原因）、各存储源条数与耗时、
  取料队列长度、最近 5 次 `analyze` 耗时、当前 screen/menu 类型。
- 目的：用户报"卡"或"没反应"时，只要一张快照就能定位到层（数据/存储/渲染/输入）。

### 6.4 启动自检（`summary` 一行）

```
JEI Recipe Tree 3.0 | MC 1.21.1 | JEI 19.69.0.456 |
drivers=[ae2, rs, sophisticated] | optional=[create, sia, modernui] | dev=false
```

- 版本错位（跑了旧 jar）是最常见的排查陷阱之一，这一行把"实际运行版本"钉在日志里。


### 6.5 日志级别表（4.1.43 修订，作废 4.1.40 的"过程一律 INFO"）

> 修订理由：实测一次 40 步合成产出 1043 行日志，`log_deduplicator` 还会把被压制的行
> 以摘要形式再打一次（`[suppressed N times]`），"过程写 INFO"在实际使用中等于刷屏。
> 本节替代 4.1.40 定的口径；两份口径不得并存。

**常开（一次合成的新增行 ≤ 3）**

| 日志 | 级别 | 说明 |
|---|---|---|
| `transfer start` | INFO | 每次合成一行，带 origin/target/want/steps |
| `transfer end` | INFO | `result=ok` 或 `result=partial` + 固定枚举 reason + took |
| `transfer step fail` | **ERROR** | 本次定为终值，不再变动；带 phase/reason/waited/grid/missing |
| 启动自检 | INFO | 不计入合成的行数 |

**只在开发者模式（`debug`）**

- `transfer step begin / fill / done`
- `restock done`
- `vector cost rows total/shown/hidden`（含 `maxHiddenSlot / maxHiddenCount`）
- 输入链路：`press:`、`click -> no hit`、`bookmark grid bounds`
  —— **不得删除**，它们是"点了没反应"的唯一线索，只降级别
- `vector compare`、`supply content changed`
  —— 另需"内容签名不变就不输出"的门控（每次刷新都会走到，级别低也会刷屏）

**允许删除的只有一类**

- 有汇总行兜底的逐行明细：`vector cost row hidden`（汇总已含 `maxHiddenSlot`）

**验证判据**

- `developerMode=false`：一次合成 `grep "JERT"` 的新增行 ≤ 3（启动自检不计）
- `developerMode=true`：先定宽松上限 ≤ 400 行，实测后按真实值收紧
