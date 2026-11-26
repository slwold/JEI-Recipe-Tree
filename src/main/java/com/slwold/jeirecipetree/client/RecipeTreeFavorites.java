package com.slwold.jeirecipetree.client;

import com.slwold.jeirecipetree.client.data.*;
import com.slwold.jeirecipetree.client.ui.*;
import com.slwold.jeirecipetree.client.jei.JeiReflectionCompat;
import com.slwold.jeirecipetree.client.jei.JeiTooltipCompat;
import com.slwold.jeirecipetree.client.jei.RecipeBookmarkNavigationContext;
import com.slwold.jeirecipetree.client.storage.Ae2StorageIntegration;
import com.slwold.jeirecipetree.client.storage.StorageNetworkIntegration;
import com.slwold.jeirecipetree.client.transfer.RecipeTreeTransfer;
import com.slwold.jeirecipetree.mixin.BookmarkListAccessor;
import com.mojang.blaze3d.platform.InputConstants;
import mezz.jei.api.constants.VanillaTypes;
import mezz.jei.api.gui.drawable.IDrawable;
import mezz.jei.api.ingredients.IIngredientHelper;
import mezz.jei.api.ingredients.IIngredientRenderer;
import mezz.jei.api.ingredients.ITypedIngredient;
import mezz.jei.api.recipe.IFocus;
import mezz.jei.api.recipe.RecipeIngredientRole;
import mezz.jei.api.recipe.category.IRecipeCategory;
import mezz.jei.api.runtime.IJeiRuntime;
import mezz.jei.api.runtime.IRecipesGui;
import mezz.jei.common.gui.JeiTooltip;
import mezz.jei.common.Internal;
import mezz.jei.common.platform.Services;
import mezz.jei.gui.bookmarks.BookmarkList;
import mezz.jei.gui.bookmarks.BookmarkType;
import mezz.jei.gui.bookmarks.IBookmark;
import mezz.jei.gui.overlay.elements.IElement;
import mezz.jei.gui.util.FocusUtil;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

public final class RecipeTreeFavorites {
    private static final long REFRESH_INTERVAL_NANOS = 200_000_000L;
    private static BookmarkList bookmarkList;
    private static List<IElement<?>> elements = List.of();
    private static Set<String> requiredIngredientKeys = Set.of();
    private static Set<String> intermediateIngredientKeys = Set.of();
    private static Set<String> finalProductIngredientKeys = Set.of();
    private static String signature = "";
    private static long lastRefreshNanos = Long.MIN_VALUE;
    private static Object lastRefreshMenu;
    private static long lastRefreshStorageRevision = Long.MIN_VALUE;
    private static int layoutNativeBookmarkCount = -1;
    private static int layoutBookmarkColumns = -1;
    private static boolean synchronizingNativeBookmarkLayout;
    private RecipeTreeFavorites() {
    }

    public static void bind(BookmarkList list) {
        bookmarkList = list;
    }

    private static void ensureBound() {
        if (bookmarkList != null) {
            return;
        }
        try {
            Object overlay = Internal.getJeiRuntime().getBookmarkOverlay();
            Class<?> type = overlay == null ? null : overlay.getClass();
            while (type != null) {
                try {
                    java.lang.reflect.Field field = type.getDeclaredField("bookmarkList");
                    field.setAccessible(true);
                    Object value = field.get(overlay);
                    if (value instanceof BookmarkList list) {
                        bookmarkList = list;
                        return;
                    }
                } catch (ReflectiveOperationException | RuntimeException ignored) {
                    type = type.getSuperclass();
                }
            }
        } catch (RuntimeException ignored) {

        }
    }

    public static Optional<ItemStack> bookmarkedCandidate(List<ItemStack> alternatives) {
        ensureBound();
        if (bookmarkList == null || alternatives == null || alternatives.isEmpty()) {
            return Optional.empty();
        }
        Set<String> candidateKeys = alternatives.stream()
            .filter(stack -> stack != null && !stack.isEmpty())
            .map(RecipeTreeData::ingredientKey)
            .collect(java.util.stream.Collectors.toSet());
        for (IElement<?> element : bookmarkList.getElements()) {
            Optional<IBookmark> bookmark = element.getBookmark();

            if (bookmark.isEmpty()
                || (!isIngredientBookmark(bookmark.get()) && !isRecipeBookmark(bookmark.get()))) {
                continue;
            }
            String bookmarkedKey = typedIngredientKey(element.getTypedIngredient());
            if (bookmarkedKey.isEmpty() || !candidateKeys.contains(bookmarkedKey)) {
                continue;
            }
            for (ItemStack alternative : alternatives) {
                if (RecipeTreeData.ingredientKey(alternative).equals(bookmarkedKey)
                    || FluidRecipeCompat.fluidKey(alternative).map(bookmarkedKey::equals).orElse(false)) {
                    return Optional.of(FluidRecipeCompat.copyWithDisplay(alternative));
                }
            }
        }
        return Optional.empty();
    }

    public static Set<String> bookmarkedIngredientKeys() {
        ensureBound();
        if (bookmarkList == null) {
            return Set.of();
        }
        Set<String> keys = new HashSet<>();
        for (IElement<?> element : bookmarkList.getElements()) {
            Optional<IBookmark> bookmark = element.getBookmark();
            if (bookmark.isEmpty() || !isIngredientBookmark(bookmark.get())) {
                continue;
            }
            String key = typedIngredientKey(element.getTypedIngredient());
            if (!key.isEmpty()) {
                keys.add(key);
            }
        }
        return Set.copyOf(keys);
    }

    private static boolean isIngredientBookmark(IBookmark bookmark) {
        return bookmark.getClass().getName().endsWith("IngredientBookmark");
    }

    private static boolean isRecipeBookmark(IBookmark bookmark) {
        return bookmark.getClass().getName().endsWith("RecipeBookmark");
    }

    private static Object invokeNoArg(Object value, String methodName) {
        try {
            return value.getClass().getMethod(methodName).invoke(value);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return null;
        }
    }

    private static String typedIngredientKey(ITypedIngredient<?> typed) {
        if (typed == null) {
            return "";
        }
        Optional<net.minecraft.world.item.ItemStack> item = typed.getIngredient(VanillaTypes.ITEM_STACK);
        if (item.isPresent() && !item.get().isEmpty()) {
            return RecipeTreeData.ingredientKey(item.get());
        }
        return FluidRecipeCompat.fluid(typed)
            .map(value -> "fluid:" + net.minecraft.core.registries.BuiltInRegistries.FLUID.getKey(value.getFluid()))
            .orElse("");
    }

    public static List<IElement<?>> elements() {
        return elements;
    }

    public static boolean isActive() {

        RecipeTreeData.Tree tree = RecipeTreeSession.tree();

        if (tree != null && tree.craftingMode() && elements.isEmpty()) {
            refreshThrottled();
        }

        return tree != null && tree.craftingMode();
    }

    public static boolean isRequired(ItemStack stack) {
        return stack != null
            && !stack.isEmpty()
            && isActive()
            && containsIngredientKey(requiredIngredientKeys, stack);
    }

    public static boolean isRequiredKey(String key) {
        return isActive() && key != null && requiredIngredientKeys.contains(key);
    }

    public static boolean isIntermediate(ItemStack stack) {
        return stack != null
            && !stack.isEmpty()
            && isActive()
            && containsIngredientKey(intermediateIngredientKeys, stack);
    }

    public static boolean isIntermediateKey(String key) {
        return isActive() && key != null && intermediateIngredientKeys.contains(key);
    }

    public static boolean isFinalProduct(ItemStack stack) {
        return stack != null
            && !stack.isEmpty()
            && isActive()
            && containsIngredientKey(finalProductIngredientKeys, stack);
    }

    public static boolean isFinalProductKey(String key) {
        return isActive() && key != null && finalProductIngredientKeys.contains(key);
    }

    private static boolean containsIngredientKey(Set<String> keys, ItemStack stack) {
        String key = RecipeTreeData.ingredientKey(stack);
        return keys.contains(key)
            || FluidRecipeCompat.displayFluidKey(stack).map(keys::contains).orElse(false);
    }

    public static final int CLASS_FINAL = 1;

    public static final int CLASS_INTERMEDIATE = 2;

    public static final int CLASS_REQUIRED = 4;

    public static int classify(ItemStack stack) {
        if (stack == null || stack.isEmpty() || !isActive()) {
            return 0;
        }
        int mask = 0;
        String key = RecipeTreeData.ingredientKey(stack);
        if (finalProductIngredientKeys.contains(key)) {
            mask |= CLASS_FINAL;
        }
        if (intermediateIngredientKeys.contains(key)) {
            mask |= CLASS_INTERMEDIATE;
        }
        if (requiredIngredientKeys.contains(key)) {
            mask |= CLASS_REQUIRED;
        }
        if (mask == 0) {

            var displayKey = FluidRecipeCompat.displayFluidKey(stack);
            if (displayKey.isPresent()) {
                String fluidKey = displayKey.get();
                if (finalProductIngredientKeys.contains(fluidKey)) {
                    mask |= CLASS_FINAL;
                }
                if (intermediateIngredientKeys.contains(fluidKey)) {
                    mask |= CLASS_INTERMEDIATE;
                }
                if (requiredIngredientKeys.contains(fluidKey)) {
                    mask |= CLASS_REQUIRED;
                }
            }
        }
        return mask;
    }

    public static boolean isNetworkStorageSlot(Slot slot) {
        if (slot == null) {
            return false;
        }
        String name = slot.getClass().getName();

        return name.endsWith(".RepoSlot");
    }

    public static void renderVirtualNetworkHighlights(
        GuiGraphics graphics,
        AbstractContainerScreen<?> screen
    ) {
        StorageNetworkIntegration.renderVirtualStorageHighlights(graphics, screen);
    }

    public static void renderBetterBeyondHighlights(
        GuiGraphics graphics,
        AbstractContainerScreen<?> screen
    ) {
        StorageNetworkIntegration.renderBetterBeyondHighlights(graphics, screen);
    }

    public static void renderIntegratedTerminalHighlightsBeforeTooltip(
        GuiGraphics graphics,
        AbstractContainerScreen<?> screen
    ) {
        StorageNetworkIntegration.renderIntegratedTerminalHighlightsBeforeTooltip(graphics, screen);
    }

    public static void refreshThrottled() {
        long now = System.nanoTime();
        Object menu = Ae2StorageIntegration.activeMenu();
        RecipeTreeData.Tree activeTree = RecipeTreeSession.craftingTree();
        long storageRevision = activeTree != null && activeTree.craftingMode()
            ? StorageNetworkIntegration.snapshotRevision()
            : Long.MIN_VALUE;
        long elapsed = now - lastRefreshNanos;
        if (menu == lastRefreshMenu
            && storageRevision == lastRefreshStorageRevision
            && lastRefreshNanos != Long.MIN_VALUE
            && elapsed >= 0L
            && elapsed < REFRESH_INTERVAL_NANOS) {
            return;
        }
        refreshNow();
    }

    public static void refreshBeforeBookmarkListNotification() {
        RecipeTreeData.Tree tree = RecipeTreeSession.craftingTree();
        if (synchronizingNativeBookmarkLayout
            || bookmarkList == null
            || tree == null
            || !tree.craftingMode()) {
            return;
        }
        int columns = bookmarkColumns();
        int nativeBookmarks = nativeBookmarkCount();
        if (columns == layoutBookmarkColumns && nativeBookmarks == layoutNativeBookmarkCount) {
            return;
        }
        synchronizingNativeBookmarkLayout = true;
        try {
            refreshNow();
        } finally {
            synchronizingNativeBookmarkLayout = false;
        }
    }

    public static void refreshNow() {

        long slowStart = Perf.startAlways();
        try {
            refreshNowMeasured();
        } finally {
            Perf.endAdaptive(Perf.REFRESH, slowStart);
        }
    }

    private static void refreshNowMeasured() {
        lastRefreshNanos = System.nanoTime();
        lastRefreshMenu = Ae2StorageIntegration.activeMenu();
        RecipeTreeData.Tree tree = RecipeTreeSession.tree();
        lastRefreshStorageRevision = tree != null && tree.craftingMode()
            ? StorageNetworkIntegration.snapshotRevision()
            : Long.MIN_VALUE;
        IJeiRuntime runtime = DirectoryRecipePlugin.getJeiRuntime();
        List<IElement<?>> finalProducts = new ArrayList<>();
        List<IElement<?>> intermediateProducts = new ArrayList<>();
        List<IElement<?>> rawMaterials = new ArrayList<>();
        Set<String> nextRequired = new HashSet<>();
        Set<String> nextIntermediate = new HashSet<>();
        Set<String> nextFinal = new HashSet<>();
        StringBuilder nextSignature = new StringBuilder();
        boolean hideRawRows = SafeConfig.hideRawMaterialRows();
        int columns = bookmarkColumns();
        int nativeBookmarks = 0;

        Map<String, Long> inventoryAmounts = Map.of();

        if (tree != null && runtime != null) {
            inventoryAmounts = SupplyLedger.planningAmounts();
            nativeBookmarks = nativeBookmarkCount();

            nextSignature.append('L').append(columns).append(':').append(nativeBookmarks)
                .append(":H").append(hideRawRows ? 1 : 0).append(';');
            String rootKey = tree.root().ingredientKey();
            nextFinal.add(rootKey);
            for (RecipeTreeData.CraftStep step : tree.craftingSteps()) {
                String stepKey = RecipeTreeData.ingredientKey(step.stack());
                long owned = SupplyLedger.inventoryAmount(step.alternatives(), inventoryAmounts);
                if (!stepKey.equals(rootKey)) {
                    for (ItemStack alternative : step.alternatives()) {
                        nextIntermediate.add(RecipeTreeData.ingredientKey(alternative));
                    }
                }
                createTyped(runtime, step.stack()).ifPresent(typed -> {
                    SyntheticBookmark bookmark = new SyntheticBookmark(typed, step, null, !stepKey.equals(rootKey));
                    if (stepKey.equals(rootKey)) {
                        finalProducts.add(bookmark.getElement());
                    } else {
                        intermediateProducts.add(bookmark.getElement());
                    }
                    nextSignature.append('R').append(step.recipe().key())
                        .append(':').append(owned).append(':').append(step.total())
                        .append(':').append(step.alternatives().stream()
                            .map(RecipeTreeData::ingredientKey).sorted().toList())
                        .append(':').append(step.selectedInputs()).append(';');
                });
            }

            RecipeTreeData.Analysis analysis = tree.analyze();
            for (RecipeTreeData.Cost cost : analysis.costs()) {

                long owned = cost.supplied();
                for (ItemStack alternative : cost.alternatives()) {
                    nextRequired.add(RecipeTreeData.ingredientKey(alternative));
                }
                createTyped(runtime, cost.stack()).ifPresent(typed -> {
                    SyntheticBookmark bookmark = new SyntheticBookmark(typed, null, cost, false);
                    rawMaterials.add(bookmark.getElement());
                    nextSignature.append('C').append(cost.alternatives().stream()
                            .map(RecipeTreeData::ingredientKey).sorted().toList())
                        .append(':').append(owned).append(':').append(cost.required()).append(';');
                });
            }
        }

        List<IElement<?>> next = new ArrayList<>();

        groupSizes = new int[] {finalProducts.size(), intermediateProducts.size(),
            hideRawRows ? 0 : rawMaterials.size()};

        next.addAll(finalProducts);
        next.addAll(intermediateProducts);
        if (!hideRawRows) {
            next.addAll(rawMaterials);
        }

        String newSignature = nextSignature.toString();
        boolean changed = !newSignature.equals(signature);
        elements = List.copyOf(next);
        requiredIngredientKeys = Set.copyOf(nextRequired);
        intermediateIngredientKeys = Set.copyOf(nextIntermediate);
        finalProductIngredientKeys = Set.copyOf(nextFinal);

        layoutNativeBookmarkCount = tree != null && tree.craftingMode() && runtime != null
            ? nativeBookmarks
            : -1;
        layoutBookmarkColumns = tree != null && tree.craftingMode() && runtime != null
            ? columns
            : -1;
        signature = newSignature;
        if (changed && bookmarkList != null && !synchronizingNativeBookmarkLayout) {
            ((BookmarkListAccessor) (Object) bookmarkList).jert$notifyListenersOfChange();
        }
    }

    private static int[] groupSizes = new int[] {0, 0, 0};

    public static int[] groupSizes() {
        return groupSizes.clone();
    }

    public static List<IElement<?>> rows() {
        return elements;
    }

    private static int nativeBookmarkCount() {
        if (bookmarkList == null) {
            return 0;
        }
        int count = 0;
        for (IElement<?> element : bookmarkList.getElements()) {
            Optional<IBookmark> bookmark = element.getBookmark();
            if (bookmark.isPresent() && !bookmark.get().getClass().getName().contains("SyntheticBookmark")) {
                count++;
            }
        }
        return count;
    }

    private static int bookmarkColumns() {

        int laidOut = laidOutBookmarkColumns();
        if (laidOut > 0) {
            return laidOut;
        }
        int fallback = 9;
        try {
            Object config = invokeNoArg(JeiReflectionCompat.clientConfigs(), "getBookmarkListConfig");
            Object value;
            try {
                value = config.getClass().getMethod("getMaxColumns").invoke(config);
            } catch (ReflectiveOperationException ignored) {
                value = config.getClass().getMethod("maxColumns").invoke(config);
                try {
                    value = value.getClass().getMethod("getValue").invoke(value);
                } catch (ReflectiveOperationException ignoredValue) {

                }
            }
            if (value instanceof Number number) {
                return Math.max(1, number.intValue());
            }
        } catch (ReflectiveOperationException | RuntimeException ignored) {

        }
        return fallback;
    }

    private static int laidOutBookmarkColumns() {
        try {
            Object overlay = Internal.getJeiRuntime().getBookmarkOverlay();
            if (overlay == null) {
                return -1;
            }
            Object contents = null;
            Class<?> type = overlay.getClass();
            while (type != null && contents == null) {
                try {
                    java.lang.reflect.Field field = type.getDeclaredField("contents");
                    field.setAccessible(true);
                    contents = field.get(overlay);
                } catch (ReflectiveOperationException | RuntimeException ignored) {
                    type = type.getSuperclass();
                }
            }
            if (contents == null) {
                return -1;
            }
            Object slotsValue = invokeNoArg(contents, "getSlots");
            List<?> slots;
            if (slotsValue instanceof java.util.stream.Stream<?> stream) {
                slots = stream.toList();
            } else if (slotsValue instanceof Iterable<?> iterable) {
                List<Object> copy = new ArrayList<>();
                iterable.forEach(copy::add);
                slots = copy;
            } else {
                return -1;
            }
            int firstRow = Integer.MAX_VALUE;
            for (Object slot : slots) {
                Object area = invokeNoArg(slot, "getArea");
                Object y = invokeNoArg(area, "getY");
                if (y instanceof Number number) {
                    firstRow = Math.min(firstRow, number.intValue());
                }
            }
            if (firstRow == Integer.MAX_VALUE) {
                return -1;
            }
            int columns = 0;
            for (Object slot : slots) {
                Object area = invokeNoArg(slot, "getArea");
                Object y = invokeNoArg(area, "getY");
                if (y instanceof Number number && number.intValue() == firstRow) {
                    columns++;
                }
            }
            return columns > 0 ? columns : -1;
        } catch (RuntimeException ignored) {
            return -1;
        }
    }

    private static Optional<ITypedIngredient<?>> createTyped(IJeiRuntime runtime, ItemStack source) {
        ItemStack stack = FluidRecipeCompat.copyWithDisplay(source);
        stack.setCount(1);

        Optional<ITypedIngredient<?>> body = IngredientDisplay.sourceOf(stack);
        if (body.isPresent()) {
            return body;
        }
        return FluidRecipeCompat.toTyped(runtime.getIngredientManager(), stack);
    }

    public static String amount(long value) {
        if (value >= 1_000_000_000L) {
            return String.format(java.util.Locale.ROOT, "%.1fB", value / 1_000_000_000.0).replace(".0", "");
        }
        if (value >= 1_000_000L) {
            return String.format(java.util.Locale.ROOT, "%.1fM", value / 1_000_000.0).replace(".0", "");
        }
        if (value >= 10_000L) {
            return String.format(java.util.Locale.ROOT, "%.1fK", value / 1_000.0).replace(".0", "");
        }
        return Long.toString(value);
    }

    private static final class SyntheticBookmark implements IBookmark {
        private final IElement<?> element;
        private final BookmarkType type;

        private SyntheticBookmark(
            ITypedIngredient<?> typed,
            RecipeTreeData.CraftStep step,
            RecipeTreeData.Cost cost,
            boolean intermediate
        ) {
            this.type = step == null ? BookmarkType.INGREDIENT : BookmarkType.RECIPE;
            this.element = SyntheticElement.create(this, typed, step, cost, intermediate);
        }

        @Override
        public BookmarkType getType() {
            return type;
        }

        @Override
        public IElement<?> getElement() {
            return element;
        }

        @Override
        public boolean isVisible() {
            return true;
        }

        @Override
        public void setVisible(boolean visible) {
        }
    }

    private static final class SyntheticElement implements InvocationHandler, TreeRowAccess {

        private final TreeRow row;

        private SyntheticElement(TreeRow row) {
            this.row = row;
        }

        private static IElement<?> create(
            IBookmark bookmark,
            ITypedIngredient<?> typed,
            RecipeTreeData.CraftStep step,
            RecipeTreeData.Cost cost,
            boolean intermediate
        ) {
            TreeRow row = new TreeRow(bookmark, typed, step, cost, intermediate);
            return (IElement<?>) Proxy.newProxyInstance(
                IElement.class.getClassLoader(),
                new Class<?>[]{ IElement.class, TreeRowAccess.class },
                new SyntheticElement(row)
            );
        }

        private IDrawable createRenderOverlay() {
            long remaining = row.remainingDisplayAmount();
            int color = row.step() == null
                ? (remaining == 0 ? 0xFF55FF55 : 0xFFFF5555)
                : (remaining == 0 ? 0xFF55FF55 : (row.intermediate() ? 0xFFFFAA33 : 0xFF55CCFF));
            return new AmountOverlay(row.remainingText(remaining), color);
        }

        @SuppressWarnings({"rawtypes", "unchecked"})
        private void show(IRecipesGui recipesGui, FocusUtil focusUtil, List<RecipeIngredientRole> roles) {
            if (row.step() != null) {
                IRecipeCategory category = row.step().recipe().category();
                if (category == null) {

                    recipesGui.show(focusUtil.createFocuses(row.typed(), roles));
                    return;
                }
                if (!roles.isEmpty() && !roles.contains(RecipeIngredientRole.OUTPUT)) {
                    recipesGui.show(focusUtil.createFocuses(row.typed(), roles));
                    return;
                }
                List<IFocus<?>> focuses = focusUtil.createFocuses(
                    row.typed(),
                    List.of(RecipeIngredientRole.OUTPUT)
                );
                IJeiRuntime runtime = DirectoryRecipePlugin.getJeiRuntime();
                if (runtime == null) {
                    recipesGui.showRecipes(category, List.of(row.step().recipe().recipe()), focuses);
                    return;
                }

                RecipeBookmarkNavigationContext.showInCategoryFirst(
                    category,
                    () -> recipesGui.show(focuses)
                );
                return;
            }
            List<IFocus<?>> focuses = focusUtil.createFocuses(row.typed(), roles);
            recipesGui.show(focuses);
        }

        private boolean handleClick(Object input) {
            InputConstants.Key key = JeiReflectionCompat.inputKey(input);

            if (row.step() != null && jeiCheatItemsEnabled()) {
                return false;
            }
            if (row.step() == null
                || key.getType() != InputConstants.Type.MOUSE
                || key.getValue() != 0) {
                return false;
            }

            boolean ctrl = net.minecraft.client.gui.screens.Screen.hasControlDown();
            boolean shift = net.minecraft.client.gui.screens.Screen.hasShiftDown();
            if (!ctrl && !shift) {
                return false;
            }
            boolean recursive = ctrl && SafeConfig.automaticCraftingEnabled();
            if (ctrl && !recursive) {

                return false;
            }
            if (JeiReflectionCompat.isInputSimulated(input)) {

                return RecipeTreeTransfer.canTransfer(row.step(), recursive);
            }
            RecipeTreeTransfer.transfer(row.step(), recursive);
            return true;
        }

        private static boolean jeiCheatItemsEnabled() {
            try {
                return Internal.getClientToggleState().isCheatItemsEnabled();
            } catch (RuntimeException ignored) {

                return false;
            }
        }

        private void getTooltip(JeiTooltip tooltip, Object tooltipHelper, Object renderer, Object helper) throws Throwable {
            @SuppressWarnings("rawtypes")
            IIngredientRenderer ingredientRenderer = (IIngredientRenderer) renderer;
            @SuppressWarnings("rawtypes")
            IIngredientHelper ingredientHelper = (IIngredientHelper) helper;
            invokeIngredientTooltipHelper(tooltipHelper, tooltip, ingredientRenderer, ingredientHelper);
            if (FluidRecipeCompat.fluid(row.typed()).isPresent()) {
                FluidRecipeCompat.fluid(row.typed()).ifPresent(value -> tooltip.add(
                    Component.translatable(
                        "jeirecipetree.recipe_tree.favorite.fluid_amount",
                        FluidRecipeCompat.formatAmount(value.getAmount())
                    ).withStyle(ChatFormatting.GRAY)
                ));
            } else {
                @SuppressWarnings("unchecked")
                IIngredientHelper<ItemStack> itemHelper = (IIngredientHelper<ItemStack>) ingredientHelper;
                @SuppressWarnings("unchecked")
                IIngredientRenderer<ItemStack> itemRenderer = (IIngredientRenderer<ItemStack>) ingredientRenderer;
                List<ItemStack> alternatives = row.tooltipAlternatives();
                if (alternatives.size() > 1) {
                    itemHelper.getTagKeyEquivalent(alternatives).ifPresent(tagKey -> {
                        tooltip.add(Component.translatable("jei.tooltip.recipe.tag", "")
                            .withStyle(ChatFormatting.GRAY));
                        tooltip.add(Services.PLATFORM.getRenderHelper().getName(tagKey)
                            .copy().withStyle(ChatFormatting.GRAY));
                    });
                    JeiTooltipCompat.createTagContent(
                        DirectoryRecipePlugin.getJeiRuntime(), itemRenderer, alternatives
                    ).ifPresent(tooltip::add);
                }
            }

            long owned = row.owned();
            ItemStack source = row.sourceStack();
            boolean fluid = IngredientDisplay.measured(source);
            long remaining = row.remainingDisplayAmount();
            tooltip.add(Component.translatable(
                "jeirecipetree.recipe_tree.favorite.remaining",
                fluid ? FluidRecipeCompat.formatAmount(remaining) : row.quantityText(remaining)
            ).withStyle(ChatFormatting.GRAY));
            tooltip.add(Component.translatable(
                "jeirecipetree.recipe_tree.favorite.obtained",
                fluid ? FluidRecipeCompat.formatAmount(owned) : row.quantityText(owned)
            ).withStyle(ChatFormatting.GRAY));
            if (row.step() != null) {
                tooltip.add(Component.translatable("jeirecipetree.recipe_tree.favorite.lookup")
                    .withStyle(ChatFormatting.GRAY));
                String clickKey = row.intermediate()
                    ? "jeirecipetree.recipe_tree.favorite.click_intermediate"
                    : "jeirecipetree.recipe_tree.favorite.click";
                tooltip.add(Component.translatable(clickKey).withStyle(ChatFormatting.AQUA));
                if (SafeConfig.automaticCraftingEnabled()) {
                    tooltip.add(Component.translatable("jeirecipetree.recipe_tree.favorite.control_click")
                        .withStyle(ChatFormatting.AQUA));
                    if (jeiCheatItemsEnabled()) {
                        tooltip.add(Component.translatable("jeirecipetree.recipe_tree.favorite.close_cheat_mode")
                            .withStyle(ChatFormatting.RED));
                    }
                }
            }
        }

        @Override
        public ITypedIngredient<?> typedIngredient() {
            return row.typedIngredient();
        }

        @Override
        public List<Component> tooltipLines() {
            return row.tooltipLines();
        }

        @Override
        public boolean canTransfer(boolean recursive) {
            return row.canTransfer(recursive);
        }

        @Override
        public boolean transfer(boolean recursive) {
            return row.transfer(recursive);
        }

        @Override
        @SuppressWarnings("unchecked")
        public Object invoke(Object proxy, Method method, Object[] arguments) throws Throwable {
            Object[] args = arguments == null ? new Object[0] : arguments;
            return switch (method.getName()) {

                case "getTypedIngredient" -> IngredientDisplay.iconTyped(row.typed());
                case "getBookmark" -> Optional.of(row.bookmark());
                case "createRenderOverlay" -> createRenderOverlay();
                case "show" -> {
                    show((IRecipesGui) args[0], (FocusUtil) args[1], (List<RecipeIngredientRole>) args[2]);
                    yield null;
                }
                case "prepareAction" -> JeiReflectionCompat.prepareAction(args[0], this::handleClick);
                case "handleClick" -> handleClick(args[0]);
                case "typedIngredient" -> row.typed();
                case "tooltipLines" -> tooltipLines();
                case "canTransfer" -> canTransfer((Boolean) args[0]);
                case "transfer" -> transfer((Boolean) args[0]);
                case "getTooltip" -> {
                    getTooltip((JeiTooltip) args[0], args[1], args[2], args[3]);
                    yield null;
                }
                case "isVisible" -> true;
                case "tick" -> null;
                case "equals" -> proxy == args[0];
                case "hashCode" -> System.identityHashCode(proxy);
                case "toString" -> "JERT recipe-tree bookmark " + row.typed().getIngredient();
                default -> throw new UnsupportedOperationException("Unsupported JEI element method: " + method);
            };
        }

        private void invokeIngredientTooltipHelper(
            Object tooltipHelper,
            JeiTooltip tooltip,
            IIngredientRenderer ingredientRenderer,
            IIngredientHelper ingredientHelper
        ) throws Throwable {
            Method target = null;
            for (Method method : tooltipHelper.getClass().getMethods()) {
                if (method.getName().equals("getIngredientTooltip") && method.getParameterCount() == 4) {
                    target = method;
                    break;
                }
            }
            if (target == null) {
                throw new NoSuchMethodException(tooltipHelper.getClass().getName() + "#getIngredientTooltip");
            }
            try {
                target.invoke(tooltipHelper, tooltip, row.typed(), ingredientRenderer, ingredientHelper);
            } catch (InvocationTargetException exception) {
                throw exception.getCause();
            }
        }

    }
}
