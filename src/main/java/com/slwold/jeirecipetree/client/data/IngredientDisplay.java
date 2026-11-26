package com.slwold.jeirecipetree.client.data;
import com.slwold.jeirecipetree.client.*;
import com.slwold.jeirecipetree.client.jei.*;
import com.slwold.jeirecipetree.client.storage.*;
import com.slwold.jeirecipetree.client.transfer.*;
import com.slwold.jeirecipetree.client.ui.*;

import com.mojang.datafixers.util.Either;
import com.slwold.jeirecipetree.core.ItemRef;
import mezz.jei.api.constants.VanillaTypes;
import mezz.jei.api.ingredients.IIngredientHelper;
import mezz.jei.api.ingredients.IIngredientRenderer;
import mezz.jei.api.ingredients.ITypedIngredient;
import mezz.jei.api.ingredients.subtypes.UidContext;
import mezz.jei.api.neoforge.NeoForgeTypes;
import mezz.jei.api.runtime.IIngredientManager;
import mezz.jei.common.gui.JeiTooltip;
import mezz.jei.common.util.SafeIngredientUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.world.inventory.tooltip.TooltipComponent;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.CustomData;
import net.neoforged.neoforge.fluids.FluidStack;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public final class IngredientDisplay {

    static final String CHEMICAL_PREFIX = "chem:";

    private static final String AMOUNT_TAG = "jert_amount";

    private static final Map<String, ITypedIngredient<?>> SOURCES = new ConcurrentHashMap<>();

    private static final java.util.concurrent.atomic.AtomicInteger NON_ITEM_DISPLAYS =
        new java.util.concurrent.atomic.AtomicInteger();

    private static final ClassValue<Optional<Method>> CHEMICAL_AMOUNT = new ClassValue<>() {
        @Override
        protected Optional<Method> computeValue(Class<?> type) {
            if (!type.getName().startsWith("mekanism.api.chemical.ChemicalStack")) {
                return Optional.empty();
            }
            try {
                Method method = type.getMethod("getAmount");
                return method.getReturnType() == long.class ? Optional.of(method) : Optional.empty();
            } catch (ReflectiveOperationException | LinkageError ignored) {
                return Optional.empty();
            }
        }
    };

    private static final ClassValue<Optional<Method>> CHEMICAL_FULL_ICON = new ClassValue<>() {
        @Override
        protected Optional<Method> computeValue(Class<?> type) {
            if (!type.getName().startsWith("mekanism.api.chemical.ChemicalStack")) {
                return Optional.empty();
            }
            try {
                return Optional.of(type.getMethod("copyWithAmount", long.class));
            } catch (ReflectiveOperationException | LinkageError ignored) {
                return Optional.empty();
            }
        }
    };

    private IngredientDisplay() {
    }

    static void clear() {
        SOURCES.clear();
        INDEXED.set(false);
        REPORTED_MISSES.clear();
    }

    private static final java.util.concurrent.atomic.AtomicBoolean INDEXED =
        new java.util.concurrent.atomic.AtomicBoolean();

    private static final java.util.Set<String> REPORTED_MISSES =
        java.util.concurrent.ConcurrentHashMap.newKeySet();

    static void ensureIndexed() {
        if (!INDEXED.compareAndSet(false, true)) {
            return;
        }
        IIngredientManager ingredientManager = manager();
        if (ingredientManager == null) {
            INDEXED.set(false);
            return;
        }
        long started = System.nanoTime();
        int before = SOURCES.size();
        try {
            for (mezz.jei.api.ingredients.IIngredientType<?> type
                : ingredientManager.getRegisteredIngredientTypes()) {
                if (type.equals(VanillaTypes.ITEM_STACK)) {
                    continue;
                }
                indexType(ingredientManager, type);
            }
        } catch (RuntimeException | LinkageError exception) {
            JERTLog.warn("ingredient index: aborted after {} entries", SOURCES.size() - before);
        }
        JERTLog.summary("ingredient index: {} indexed in {} ms ({} known)",
            SOURCES.size() - before, (System.nanoTime() - started) / 1_000_000, SOURCES.size());
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void indexType(IIngredientManager ingredientManager,
                                  mezz.jei.api.ingredients.IIngredientType<?> type) {
        java.util.Collection<Object> values;
        try {
            values = (java.util.Collection<Object>) ingredientManager.getAllIngredients(
                (mezz.jei.api.ingredients.IIngredientType<Object>) type);
        } catch (RuntimeException | LinkageError ignored) {
            return;
        }
        if (values == null) {
            return;
        }
        for (Object value : values) {
            try {
                ingredientManager
                    .createTypedIngredient((mezz.jei.api.ingredients.IIngredientType<Object>) type, value)
                    .ifPresent(ingredient -> {
                        String identity = key(ingredient);
                        if (!identity.isBlank()) {
                            SOURCES.putIfAbsent(identity, ingredient);

                            SOURCES.putIfAbsent(canonicalKey(identity), ingredient);
                            SOURCES.putIfAbsent(rawKey(ingredient), ingredient);
                        }
                    });
            } catch (RuntimeException | LinkageError ignored) {

            }
        }
    }

    static String key(ITypedIngredient<?> ingredient) {
        if (ingredient == null) {
            return "";
        }
        Optional<FluidStack> fluid = fluid(ingredient);
        if (fluid.isPresent() && !fluid.get().isEmpty()) {
            return FluidRecipeCompat.fluidKey(fluid.get());
        }
        Optional<ItemStack> item = item(ingredient);
        if (item.isPresent() && !item.get().isEmpty()) {
            return ItemKeys.legacy(ItemKeys.of(item.get()));
        }
        return CHEMICAL_PREFIX + ingredient.getType().getUid() + "#" + uid(ingredient);
    }

    static long amount(ITypedIngredient<?> ingredient) {
        if (ingredient == null) {
            return 0L;
        }
        Optional<FluidStack> fluid = fluid(ingredient);
        if (fluid.isPresent() && !fluid.get().isEmpty()) {
            return Math.max(0L, fluid.get().getAmount());
        }

        Optional<ItemStack> stack = item(ingredient);
        if (stack.isPresent()) {
            return Math.max(1L, stack.get().getCount());
        }
        Object raw = ingredient.getIngredient();
        long chemical = chemicalAmount(raw);
        if (chemical > 0L) {
            return chemical;
        }
        long helperAmount = helperAmount(ingredient);
        return helperAmount > 0L ? helperAmount : 1L;
    }

    static Optional<ItemStack> display(ITypedIngredient<?> ingredient) {
        if (ingredient == null) {
            return Optional.empty();
        }
        Optional<FluidStack> fluid = fluid(ingredient);
        if (fluid.isPresent()) {
            Optional<ItemStack> container = FluidRecipeCompat.representativeContainer(ingredient);
            String fluidKey = FluidRecipeCompat.fluidKey(fluid.get());
            long fluidAmount = Math.max(0L, fluid.get().getAmount());
            container.ifPresent(stack -> SOURCES.putIfAbsent(fluidKey, ingredient));

            return container.map(stack -> {
                ItemStack displayed = FluidRecipeCompat.copyWithDisplay(stack);
                mark(displayed, fluidKey, fluidAmount);
                return displayed;
            });
        }
        Optional<ItemStack> item = item(ingredient);
        if (item.isPresent() && !item.get().isEmpty()) {
            return Optional.of(item.get().copy());
        }
        String key = key(ingredient);
        ItemStack cheat = cheatStack(ingredient);
        if (cheat.isEmpty()) {
            return Optional.empty();
        }
        long amount = amount(ingredient);
        SOURCES.put(key, ingredient);
        SOURCES.putIfAbsent(canonicalKey(key), ingredient);
        SOURCES.putIfAbsent(rawKey(ingredient), ingredient);
        NON_ITEM_DISPLAYS.incrementAndGet();
        ItemStack displayed = cheat.copyWithCount(1);
        mark(displayed, key, amount);
        return Optional.of(displayed);
    }

    public static boolean measured(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        if (FluidRecipeCompat.treeFluid(stack).isPresent()) {
            return true;
        }

        ItemRef ref = ItemKeys.of(stack);
        return ref.isFluid() || ref.isChemical();
    }

    static long amountOf(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return 0L;
        }
        long marked = markedAmount(stack);
        if (marked > 0L) {
            return marked;
        }
        Optional<FluidStack> fluid = FluidRecipeCompat.treeFluid(stack);
        if (fluid.isPresent()) {
            return Math.max(0L, fluid.get().getAmount());
        }
        return stack.getCount();
    }

    static long perCraft(ItemStack stack) {
        return Math.max(1L, amountOf(stack));
    }

    public static Optional<ITypedIngredient<?>> sourceOf(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return Optional.empty();
        }
        return Optional.ofNullable(sourceFor(ItemKeys.legacy(ItemKeys.of(stack))));
    }

    private static ITypedIngredient<?> sourceFor(String key) {
        if (key == null || key.isBlank()) {
            return null;
        }
        ITypedIngredient<?> source = SOURCES.get(key);
        if (source != null) {
            return source;
        }
        String canonical = canonicalKey(key);
        source = SOURCES.get(canonical);
        if (source != null) {
            return source;
        }

        ensureIndexed();
        source = SOURCES.get(canonical);
        if (source != null) {
            return source;
        }
        source = SOURCES.get(key);
        if (source == null && key.startsWith(CHEMICAL_PREFIX) && REPORTED_MISSES.add(key)) {

            JERTLog.summary("ingredient lookup: no source for '{}' ({} chemical keys indexed, sample={})",
                key, chemicalKeyCount(), sampleChemicalKey());
        }
        return source;
    }

    private static int chemicalKeyCount() {
        int count = 0;
        for (String key : SOURCES.keySet()) {
            if (key.startsWith(CHEMICAL_PREFIX)) {
                count++;
            }
        }
        return count;
    }

    private static String sampleChemicalKey() {
        for (String key : SOURCES.keySet()) {
            if (key.startsWith(CHEMICAL_PREFIX)) {
                return key;
            }
        }
        return "none";
    }

    static Optional<ItemStack> displayForKey(String key, long amount) {
        ITypedIngredient<?> source = sourceFor(key);
        if (source == null) {
            source = itemSourceFor(key);
        }
        if (source == null) {
            return Optional.empty();
        }
        return display(source).map(stack -> {
            mark(stack, canonicalKey(key), Math.max(1L, amount));
            return stack;
        });
    }

    private static ITypedIngredient<?> itemSourceFor(String key) {
        if (key == null || key.isBlank() || key.startsWith(CHEMICAL_PREFIX)) {
            return null;
        }
        IIngredientManager ingredientManager = manager();
        if (ingredientManager == null) {
            return null;
        }
        net.minecraft.resources.ResourceLocation id = net.minecraft.resources.ResourceLocation.tryParse(key);
        if (id == null) {
            return null;
        }
        Item item = net.minecraft.core.registries.BuiltInRegistries.ITEM.get(id);
        if (item == null || item == net.minecraft.world.item.Items.AIR) {
            return null;
        }
        ITypedIngredient<?> source = ingredientManager
            .createTypedIngredient(VanillaTypes.ITEM_STACK, new ItemStack(item))
            .orElse(null);
        if (source == null) {
            return null;
        }
        SOURCES.putIfAbsent(key, source);
        String canonical = canonicalKey(key);
        if (canonical != null && !canonical.isBlank()) {
            SOURCES.putIfAbsent(canonical, source);
        }
        return source;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    static Optional<mezz.jei.api.recipe.IFocus<?>> createOutputFocus(
        mezz.jei.api.runtime.IJeiRuntime runtime,
        ItemStack stack
    ) {
        if (runtime == null) {
            return Optional.empty();
        }
        Optional<ITypedIngredient<?>> source = sourceOf(stack);
        if (source.isEmpty()) {
            return Optional.empty();
        }
        try {
            mezz.jei.api.ingredients.IIngredientType<Object> type =
                (mezz.jei.api.ingredients.IIngredientType<Object>) source.get().getType();
            Object value = source.get().getIngredient();
            return Optional.of(runtime.getJeiHelpers().getFocusFactory()
                .<Object>createFocus(mezz.jei.api.recipe.RecipeIngredientRole.OUTPUT, type, value));
        } catch (RuntimeException ignored) {
            return Optional.empty();
        }
    }

    public static boolean render(GuiGraphics graphics, MaterialOption material, int x, int y) {
        if (material == null) {
            return false;
        }
        if (material.typed() != null && renderTyped(graphics, material.typed(), x, y)) {
            return true;
        }
        if (!material.display().isEmpty()) {
            return render(graphics, material.display(), x, y);
        }
        return false;
    }

    public static boolean renderTooltip(GuiGraphics graphics, MaterialOption material, int mouseX, int mouseY) {
        if (material == null) {
            return false;
        }
        if (material.typed() != null && renderTypedTooltip(graphics, material.typed(), mouseX, mouseY)) {
            return true;
        }
        return material.display().isEmpty()
            ? false
            : renderTooltip(graphics, material.display(), mouseX, mouseY);
    }

    public static boolean render(GuiGraphics graphics, ITypedIngredient<?> ingredient, int x, int y) {
        return ingredient != null && renderTyped(graphics, ingredient, x, y);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    public static ITypedIngredient<?> iconTyped(ITypedIngredient<?> ingredient) {
        if (ingredient == null) {
            return null;
        }
        IIngredientManager manager = manager();
        Object full = iconValue(ingredient);
        if (manager == null || full == ingredient.getIngredient()) {
            return ingredient;
        }
        try {
            Object created = manager.<Object>createTypedIngredient(
                (mezz.jei.api.ingredients.IIngredientType<Object>) ingredient.getType(), full);
            if (created instanceof Optional<?> optional
                && optional.isPresent()
                && optional.get() instanceof ITypedIngredient<?> typed) {
                return typed;
            }
        } catch (RuntimeException | LinkageError ignored) {

        }
        return ingredient;
    }

    private static boolean renderTyped(GuiGraphics graphics, ITypedIngredient<?> ingredient, int x, int y) {
        IIngredientManager manager = manager();
        if (manager == null) {
            return false;
        }
        try {
            IIngredientRenderer<Object> renderer = renderer(manager, ingredient);
            renderer.render(graphics, iconValue(ingredient), x, y);
            return true;
        } catch (RuntimeException | LinkageError ignored) {
            return false;
        }
    }

    private static Object iconValue(ITypedIngredient<?> ingredient) {
        Object raw = ingredient.getIngredient();
        Optional<FluidStack> fluid = fluid(ingredient);
        if (fluid.isPresent()) {
            FluidStack full = fluid.get().copy();
            full.setAmount(Integer.MAX_VALUE);
            return full;
        }
        if (raw == null) {
            return null;
        }
        Optional<Method> copy = CHEMICAL_FULL_ICON.get(raw.getClass());
        if (copy.isEmpty()) {
            return raw;
        }
        try {
            return copy.get().invoke(raw, Long.MAX_VALUE);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            return raw;
        }
    }

    static List<Component> tooltipLines(ITypedIngredient<?> ingredient) {
        if (ingredient == null) {
            return List.of();
        }
        JeiTooltip tooltip = new JeiTooltip();
        if (!collectRich(tooltip, ingredient)) {
            return List.of();
        }
        return textLines(tooltip);
    }

    static List<Component> tooltipLines(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return List.of();
        }
        Optional<ITypedIngredient<?>> source = sourceOf(stack);
        if (source.isPresent()) {
            List<Component> lines = tooltipLines(source.get());
            if (!lines.isEmpty()) {
                return lines;
            }
        }
        return vanillaLines(stack);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    public static boolean collectRich(JeiTooltip tooltip, ITypedIngredient<?> ingredient) {
        if (tooltip == null || ingredient == null) {
            return false;
        }
        IIngredientManager manager = manager();
        if (manager == null) {
            return false;
        }
        try {
            IIngredientRenderer<Object> renderer = renderer(manager, ingredient);
            SafeIngredientUtil.getRichTooltip(
                tooltip, manager, renderer, (ITypedIngredient<Object>) ingredient);
            return !tooltip.getLines().isEmpty();
        } catch (RuntimeException | LinkageError ignored) {
            return false;
        }
    }

    public static boolean collectRich(JeiTooltip tooltip, ItemStack stack) {
        if (tooltip == null || stack == null || stack.isEmpty()) {
            return false;
        }
        Optional<ITypedIngredient<?>> source = sourceOf(stack);
        if (source.isPresent() && collectRich(tooltip, source.get())) {
            return true;
        }
        ItemRef ref = ItemKeys.of(stack);
        if (ref.isChemical() || ref.isFluid()) {
            return false;
        }
        IIngredientManager manager = manager();
        if (manager == null) {
            return false;
        }
        try {
            Optional<ITypedIngredient<ItemStack>> typed =
                manager.createTypedIngredient(VanillaTypes.ITEM_STACK, stack);
            return typed.isPresent() && collectRich(tooltip, typed.get());
        } catch (RuntimeException | LinkageError ignored) {
            return false;
        }
    }

    private static List<Component> textLines(JeiTooltip tooltip) {
        List<Component> lines = new ArrayList<>();
        for (Either<FormattedText, TooltipComponent> line : tooltip.getLines()) {
            line.left().ifPresent(text -> lines.add(
                text instanceof Component component ? component : Component.literal(text.getString())));
        }
        return lines;
    }

    public static List<Component> vanillaLines(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return List.of();
        }
        Minecraft minecraft = Minecraft.getInstance();
        return stack.getTooltipLines(
            Item.TooltipContext.of(minecraft.level), minecraft.player, TooltipFlag.Default.NORMAL);
    }

    private static boolean renderTypedTooltip(
        GuiGraphics graphics,
        ITypedIngredient<?> ingredient,
        int mouseX,
        int mouseY
    ) {
        JeiTooltip tooltip = new JeiTooltip();
        if (!collectRich(tooltip, ingredient)) {
            return false;
        }
        try {
            tooltip.draw(graphics, mouseX, mouseY);
            return true;
        } catch (RuntimeException | LinkageError ignored) {
            return false;
        }
    }

    public static boolean render(GuiGraphics graphics, ItemStack stack, int x, int y) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        if (FluidRecipeCompat.render(graphics, stack, x, y)) {
            return true;
        }
        Optional<ITypedIngredient<?>> source = sourceOf(stack);
        if (source.isEmpty()) {
            return false;
        }
        IIngredientManager manager = manager();
        if (manager == null) {
            return false;
        }
        try {
            IIngredientRenderer<Object> renderer = renderer(manager, source.get());
            renderer.render(graphics, iconValue(source.get()), x, y);
            return true;
        } catch (RuntimeException | LinkageError ignored) {
            return false;
        }
    }

    public static boolean renderTooltip(GuiGraphics graphics, ItemStack stack, int mouseX, int mouseY) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        JeiTooltip tooltip = new JeiTooltip();
        if (collectRich(tooltip, stack)) {
            try {
                tooltip.draw(graphics, mouseX, mouseY);
                return true;
            } catch (RuntimeException | LinkageError ignored) {

            }
        }
        return FluidRecipeCompat.renderTooltip(graphics, stack, mouseX, mouseY);
    }

    @SuppressWarnings("unchecked")
    private static IIngredientRenderer<Object> renderer(IIngredientManager manager, ITypedIngredient<?> source) {
        return (IIngredientRenderer<Object>) manager.getIngredientRenderer(source.getType());
    }

    private static IIngredientManager manager() {
        return DirectoryRecipePlugin.getJeiRuntime() == null
            ? null
            : DirectoryRecipePlugin.getJeiRuntime().getIngredientManager();
    }

    private static Optional<FluidStack> fluid(ITypedIngredient<?> ingredient) {
        return ingredient.getIngredient(NeoForgeTypes.FLUID_STACK);
    }

    private static Optional<ItemStack> item(ITypedIngredient<?> ingredient) {
        return ingredient.getIngredient(VanillaTypes.ITEM_STACK).filter(stack -> !stack.isEmpty());
    }

    private static String uid(ITypedIngredient<?> ingredient) {
        IIngredientManager manager = manager();
        if (manager != null) {
            try {

                String identity = stripAmount(String.valueOf(
                    helper(manager, ingredient).getUid(ingredient.getIngredient(), UidContext.Ingredient)));
                if (!identity.isBlank()) {
                    return identity;
                }
            } catch (RuntimeException | LinkageError ignored) {

            }
        }
        return stripAmount(String.valueOf(ingredient.getIngredient()));
    }

    private static String rawKey(ITypedIngredient<?> ingredient) {
        if (ingredient == null) {
            return "";
        }
        return CHEMICAL_PREFIX + ingredient.getType().getUid() + "#"
            + stripAmount(String.valueOf(ingredient.getIngredient()));
    }

    static String canonicalKey(String key) {
        if (key == null || !key.startsWith(CHEMICAL_PREFIX)) {
            return key;
        }
        int hash = key.indexOf('#', CHEMICAL_PREFIX.length());
        if (hash < 0) {
            return key;
        }
        return key.substring(0, hash + 1) + stripAmount(key.substring(hash + 1));
    }

    private static String stripAmount(String text) {
        String value = text == null ? "" : text.trim();
        int space = value.indexOf(' ');
        if (space <= 0) {
            return value;
        }
        String head = value.substring(0, space);
        boolean numeric = true;
        for (int i = 0; i < head.length(); i++) {
            if (!Character.isDigit(head.charAt(i))) {
                numeric = false;
                break;
            }
        }
        return numeric ? value.substring(space + 1).trim() : value;
    }

    private static long helperAmount(ITypedIngredient<?> ingredient) {
        IIngredientManager manager = manager();
        if (manager == null) {
            return -1L;
        }
        try {
            return helper(manager, ingredient).getAmount(ingredient.getIngredient());
        } catch (RuntimeException | LinkageError ignored) {
            return -1L;
        }
    }

    private static ItemStack cheatStack(ITypedIngredient<?> ingredient) {
        IIngredientManager manager = manager();
        if (manager == null) {
            return ItemStack.EMPTY;
        }
        try {
            ItemStack stack = helper(manager, ingredient).getCheatItemStack(ingredient.getIngredient());
            return stack == null ? ItemStack.EMPTY : stack;
        } catch (RuntimeException | LinkageError ignored) {
            return ItemStack.EMPTY;
        }
    }

    @SuppressWarnings("unchecked")
    private static IIngredientHelper<Object> helper(IIngredientManager manager, ITypedIngredient<?> ingredient) {
        return (IIngredientHelper<Object>) manager.getIngredientHelper(ingredient.getType());
    }

    static long chemicalAmount(Object raw) {
        if (raw == null) {
            return 0L;
        }
        Optional<Method> method = CHEMICAL_AMOUNT.get(raw.getClass());
        if (method.isEmpty()) {
            return 0L;
        }
        try {
            Object value = method.get().invoke(raw);
            return value instanceof Number number ? Math.max(0L, number.longValue()) : 0L;
        } catch (ReflectiveOperationException | LinkageError ignored) {
            return 0L;
        }
    }

    static void mark(ItemStack stack, String key, long amount) {
        if (stack == null || stack.isEmpty() || key == null || key.isBlank()) {
            return;
        }
        ItemKeys.withIdentity(stack, key);
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data == null) {
            return;
        }
        CompoundTag tag = data.copyTag();

        ItemRef ref = ItemRef.of(key);
        boolean measured = ref.isFluid() || ref.isChemical();
        if (measured && amount > 0L) {
            tag.putLong(AMOUNT_TAG, amount);
        } else {
            tag.remove(AMOUNT_TAG);
        }
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
    }

    static long markedAmount(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return 0L;
        }
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        return data == null ? 0L : Math.max(0L, data.copyTag().getLong(AMOUNT_TAG));
    }

    static boolean isChemicalKey(ItemRef ref) {
        return ref != null && ref.isChemical();
    }

    static int nonItemDisplayCount() {
        return NON_ITEM_DISPLAYS.get();
    }
}
