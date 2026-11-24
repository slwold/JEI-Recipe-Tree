# 第三方声明 / Third-party notices

本项目以 **MIT** 许可发布（全文见 `LICENSE`）。下列项目被**依赖**、或被**参考了实现思路**；
本仓库不打包它们的源码。JEI 与 MezzConfig 为运行时依赖，需用户自行安装。

## Just Enough Items (JEI)

MIT，copyright mezz — <https://github.com/mezz/JustEnoughItems>

客户端附属件。编译期依赖其公开 API；运行期对少量内部类做 mixin 注入（书签侧栏、输入分发、
渲染层级）。未复制 JEI 源码，也不随本模组分发 JEI。

## EMI

MIT，copyright (c) 2022 Emi — <https://github.com/emilyploszaj/emi>

配方树的数据模型、默认配方行为与交互设计参考了 EMI 的实现。EMI 自带的原版默认配方表
未随包分发：整合包会重写配方 id，固定表不可信。

## MezzConfig / MezzConfigGui

MIT，copyright mezz — <https://github.com/mezz/MezzConfig> ·
<https://github.com/mezz/MezzConfigGui>

安装 MezzConfigGui 时，配置界面交由它渲染（枚举项走选择器而不是循环按钮）；未安装则回退
NeoForge 自带界面。对接走扩展点与反射，不复制其源码。

## Sophisticated Item Actions (SIA)

**All Rights Reserved**，copyright P3pp3rF1y —
<https://github.com/P3pp3rF1y/SophisticatedItemActions>

仅通过反射调用其 `ItemTransferHandler#restockRecipeItems`，用于配方书的「取材料」按钮；
不引入构建期依赖、不复制任何代码、不打包其产物。未安装时按钮不出现，相关路径整体下线。

## 可选兼容目标

以下模组仅通过 `@Pseudo` mixin 与反射对接，代码不打包；缺失时对应分支为空操作：

- Applied Energistics 2 — 样板与终端集成
- Refined Storage — 网格视图与资源仓库钩子
- Integrated Terminals (Cyclops) — 终端存储钩子
- Beyond Dimensions / Better Beyond Dimensions — 侧栏与菜单钩子
- Sophisticated Storage / Backpacks — 背包提供者

## MIT 许可证全文（EMI）

The MIT License (MIT)

Copyright (c) 2022 Emi

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in
all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
THE SOFTWARE.
