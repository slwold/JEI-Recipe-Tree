package com.slwold.jeirecipetree.client;

import mezz.jei.api.ingredients.ITypedIngredient;
import mezz.jei.api.constants.VanillaTypes;
import mezz.jei.api.neoforge.NeoForgeTypes;
import mezz.jei.api.ingredients.IIngredientRenderer;
import mezz.jei.api.runtime.IIngredientManager;
import mezz.jei.api.recipe.IFocus;
import mezz.jei.api.recipe.RecipeIngredientRole;
import mezz.jei.api.runtime.IJeiRuntime;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.material.Fluid;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.FluidType;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.fluids.capability.IFluidHandlerItem;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public final class FluidRecipeCompat {
    private static final IdentityHashMap<ItemStack, FluidStack> DISPLAY_FLUIDS = new IdentityHashMap<>();
    private static final Map<String, ItemStack> FLUID_REPRESENTATIONS = new ConcurrentHashMap<>();
    private static final List<String> FLUID_ACCESSOR_NAMES = List.of(
        "getFluidStack", "getReadOnlyStack", "getRenderStack", "getStack",
        "toFluidStack", "toStack", "getInstance", "getIngredient", "getResource",
        "getFluidResource", "getFluid", "fluid", "getWhat", "getKey", "getSource"
    );
    private static final Map<Class<?>, List<Method>> FLUID_ACCESSORS = new ConcurrentHashMap<>();

    private FluidRecipeCompat() {
    }

    public record FluidInfo(String key, long amount) {
    }

    public static Optional<FluidInfo> describeFluid(Object value) {
        return describeFluid(value, Collections.newSetFromMap(new IdentityHashMap<>()), 0);
    }

    private static Optional<FluidInfo> describeFluid(Object value, Set<Object> seen, int depth) {
        if (value == null || depth > 4 || !seen.add(value)) {
            return Optional.empty();
        }
        if (value instanceof FluidStack stack) {
            if (stack.isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(new FluidInfo(
                "fluid:" + BuiltInRegistries.FLUID.getKey(stack.getFluid()),
                Math.max(0L, stack.getAmount())
            ));
        }
        if (value instanceof ItemStack stack) {
            return displayFluid(stack).map(fluid -> new FluidInfo(
                "fluid:" + BuiltInRegistries.FLUID.getKey(fluid.getFluid()),
                Math.max(0L, fluid.getAmount())
            ));
        }
        if (value instanceof Fluid fluid) {
            return Optional.of(new FluidInfo(
                "fluid:" + BuiltInRegistries.FLUID.getKey(fluid),
                0L
            ));
        }
        for (Method method : FLUID_ACCESSORS.computeIfAbsent(value.getClass(), FluidRecipeCompat::findFluidAccessors)) {
            try {
                Optional<FluidInfo> result = describeFluid(method.invoke(value), seen, depth + 1);
                if (result.isPresent()) {
                    return result;
                }
            } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {

            }
        }
        return Optional.empty();
    }

    private static List<Method> findFluidAccessors(Class<?> type) {
        List<Method> methods = new ArrayList<>();
        for (String name : FLUID_ACCESSOR_NAMES) {
            try {
                Method method = type.getMethod(name);
                try {
                    method.trySetAccessible();
                } catch (RuntimeException ignored) {

                }
                methods.add(method);
            } catch (NoSuchMethodException | SecurityException ignored) {

            }
        }
        return List.copyOf(methods);
    }

    static void clear() {
        DISPLAY_FLUIDS.clear();
        FLUID_REPRESENTATIONS.clear();
        FLUID_ACCESSORS.clear();
    }

    static Optional<FluidStack> fluid(ITypedIngredient<?> ingredient) {
        return ingredient == null
            ? Optional.empty()
            : ingredient.getIngredient(NeoForgeTypes.FLUID_STACK);
    }

    static Optional<ITypedIngredient<?>> scaled(
        ITypedIngredient<?> ingredient,
        int batches,
        IIngredientManager manager
    ) {
        Optional<FluidStack> value = fluid(ingredient);
        if (value.isEmpty() || batches <= 1) {
            return Optional.ofNullable(ingredient);
        }
        long amount = (long) value.get().getAmount() * batches;
        if (amount > Integer.MAX_VALUE) {
            return Optional.of(ingredient);
        }
        FluidStack copy = value.get().copyWithAmount((int) amount);
        return manager.createTypedIngredient(NeoForgeTypes.FLUID_STACK, copy)
            .map(stack -> (ITypedIngredient<?>) stack);
    }

    static List<ItemStack> matchingContainers(Player player, FluidStack required, int batches) {
        if (player == null || required == null || required.isEmpty()) {
            return List.of();
        }
        int requiredAmount = safeAmount(required.getAmount(), batches);
        for (ItemStack inventoryStack : player.getInventory().items) {
            if (inventoryStack.isEmpty()) {
                continue;
            }
            IFluidHandlerItem handler = inventoryStack.getCapability(Capabilities.FluidHandler.ITEM);
            if (handler == null) {
                continue;
            }
            FluidStack contained = handler.drain(Integer.MAX_VALUE, IFluidHandler.FluidAction.SIMULATE);
            if (contained == null || contained.isEmpty()
                || !FluidStack.isSameFluidSameComponents(contained, required)) {
                continue;
            }
            int perContainer = Math.max(1, contained.getAmount());
            int containers = (int) Math.min(Integer.MAX_VALUE,
                Math.max(1L, ((long) requiredAmount + perContainer - 1L) / perContainer));
            if (containers <= inventoryStack.getCount()) {
                ItemStack candidate = inventoryStack.copy();
                candidate.setCount(containers);
                return List.of(candidate);
            }
        }
        return List.of();
    }

    static List<ItemStack> matchingContainers(Player player, ITypedIngredient<?> ingredient) {
        return fluid(ingredient)
            .map(value -> matchingContainers(player, value, 1))
            .orElseGet(List::of);
    }

    static Optional<ItemStack> representativeContainer(ITypedIngredient<?> ingredient) {
        return fluid(ingredient).map(value -> {

            var bucketItem = value.getFluid().getBucket();
            ItemStack bucket = new ItemStack(bucketItem == Items.AIR ? Items.BUCKET : bucketItem);

            int count = (int) Math.min(Integer.MAX_VALUE, containerCount(value.getAmount()));
            bucket.setCount(count);
            registerDisplay(bucket, value);
            return bucket;
        }).filter(stack -> !stack.isEmpty());
    }

    static Optional<ItemStack> containerCandidate(ITypedIngredient<?> ingredient) {
        return fluid(ingredient).map(value -> {
            var bucketItem = value.getFluid().getBucket();
            ItemStack bucket = new ItemStack(bucketItem == Items.AIR ? Items.BUCKET : bucketItem);
            bucket.setCount((int) Math.min(Integer.MAX_VALUE, containerCount(value.getAmount())));
            return bucket;
        }).filter(stack -> !stack.isEmpty());
    }

    private static void registerDisplay(ItemStack stack, FluidStack fluid) {
        if (stack == null || stack.isEmpty() || fluid == null || fluid.isEmpty()) {
            return;
        }
        DISPLAY_FLUIDS.put(stack, fluid.copy());
    }

    static Optional<FluidStack> displayFluid(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return Optional.empty();
        }
        FluidStack fluid = DISPLAY_FLUIDS.get(stack);
        if (fluid != null) {
            return Optional.of(fluid.copy());
        }

        ItemStack probe = stack;
        if (stack.getCount() > 1) {
            probe = stack.copy();
            probe.setCount(1);
        }
        if (probe.getCapability(Capabilities.FluidHandler.ITEM) instanceof IFluidHandlerItem handler) {
            FluidStack contained = handler.drain(Integer.MAX_VALUE, IFluidHandler.FluidAction.SIMULATE);
            if (contained != null && !contained.isEmpty()) {
                return Optional.of(contained.copy());
            }
        }
        return Optional.empty();
    }

    static Optional<FluidStack> treeFluid(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return Optional.empty();
        }
        FluidStack fluid = DISPLAY_FLUIDS.get(stack);
        return fluid == null ? Optional.empty() : Optional.of(fluid.copy());
    }

    static Optional<String> fluidKey(ItemStack stack) {
        return treeFluid(stack).map(value -> fluidKey(value));
    }

    static String fluidKey(FluidStack value) {
        return value == null || value.isEmpty()
            ? ""
            : "fluid:" + net.minecraft.core.registries.BuiltInRegistries.FLUID.getKey(value.getFluid());
    }

    static Optional<String> displayFluidKey(ItemStack stack) {
        return displayFluid(stack)
            .map(FluidRecipeCompat::fluidKey)
            .filter(key -> !key.isEmpty());
    }

    static Optional<ItemStack> fluidRepresentation(ItemStack stack) {
        Optional<FluidStack> value = displayFluid(stack);
        if (value.isEmpty() || fluidKey(stack).isPresent()) {
            return Optional.empty();
        }
        return Optional.of(cachedRepresentation(value.get()));
    }

    static Optional<ITypedIngredient<?>> toTyped(IIngredientManager manager, ItemStack stack) {
        if (manager == null || stack == null || stack.isEmpty()) {
            return Optional.empty();
        }
        Optional<FluidStack> fluid = treeFluid(stack);
        if (fluid.isPresent()) {
            return manager.createTypedIngredient(NeoForgeTypes.FLUID_STACK, fluid.get())
                .map(value -> (ITypedIngredient<?>) value);
        }
        return manager.createTypedIngredient(VanillaTypes.ITEM_STACK, stack)
            .map(value -> (ITypedIngredient<?>) value);
    }

    static long containerCount(long millibuckets) {
        return Math.max(1, (millibuckets + FluidType.BUCKET_VOLUME - 1) / FluidType.BUCKET_VOLUME);
    }

    static long amountInContainers(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return 0L;
        }
        Optional<FluidStack> fluid = displayFluid(stack);
        if (fluid.isEmpty()) {
            return 0L;
        }
        try {
            return Math.multiplyExact(
                Math.max(0L, fluid.get().getAmount()),
                Math.max(1L, stack.getCount())
            );
        } catch (ArithmeticException overflow) {
            return Long.MAX_VALUE;
        }
    }

    static Optional<ItemStack> representativeForKey(String key, long amount) {
        if (key == null || !key.startsWith("fluid:") || amount <= 0) {
            return Optional.empty();
        }
        ResourceLocation id = ResourceLocation.tryParse(key.substring("fluid:".length()));
        if (id == null) {
            return Optional.empty();
        }
        Fluid fluid = BuiltInRegistries.FLUID.get(id);
        if (fluid == null || fluid == net.minecraft.world.level.material.Fluids.EMPTY) {
            return Optional.empty();
        }
        int safeAmount = (int) Math.min(Integer.MAX_VALUE, amount);
        FluidStack value = new FluidStack(fluid, safeAmount);
        return Optional.of(cachedRepresentation(value));
    }

    private static ItemStack cachedRepresentation(FluidStack value) {
        String cacheKey = fluidKey(value) + "|" + value.getAmount();
        ItemStack prototype = FLUID_REPRESENTATIONS.computeIfAbsent(cacheKey, ignored -> {
            var bucketItem = value.getFluid().getBucket();
            ItemStack bucket = new ItemStack(bucketItem == Items.AIR ? Items.BUCKET : bucketItem);
            registerDisplay(bucket, value);
            return bucket;
        });
        return copyWithDisplay(prototype);
    }

    static long amountForUnits(ItemStack stack, long units) {
        return Math.max(0L, units);
    }

    static String formatAmount(long millibuckets) {
        long value = Math.max(0, millibuckets);
        if (value < FluidType.BUCKET_VOLUME) {
            return value + "mB";
        }
        if (value % 100 != 0) {
            return value + "mB";
        }
        if (value % FluidType.BUCKET_VOLUME == 0) {
            return (value / FluidType.BUCKET_VOLUME) + "B";
        }
        return String.format(Locale.ROOT, "%.1fB", value / (double) FluidType.BUCKET_VOLUME);
    }

    static Optional<IFocus<FluidStack>> createOutputFocus(IJeiRuntime runtime, ItemStack stack) {
        Optional<FluidStack> value = treeFluid(stack);
        if (runtime == null || value.isEmpty()) {
            return Optional.empty();
        }
        try {
            return Optional.of(runtime.getJeiHelpers().getFocusFactory().createFocus(
                RecipeIngredientRole.OUTPUT,
                NeoForgeTypes.FLUID_STACK,
                value.get()
            ));
        } catch (IllegalArgumentException ignored) {
            return Optional.empty();
        }
    }

    static ItemStack copyWithDisplay(ItemStack stack) {
        ItemStack copy = stack == null ? ItemStack.EMPTY : stack.copy();
        if (stack != null) {
            FluidStack fluid = DISPLAY_FLUIDS.get(stack);
            if (fluid != null) {
                DISPLAY_FLUIDS.put(copy, fluid.copy());
            }
        }
        return copy;
    }

    static boolean render(GuiGraphics graphics, ItemStack stack, int x, int y) {
        Optional<FluidStack> value = treeFluid(stack);
        if (value.isEmpty()) {
            return false;
        }
        IIngredientManager manager = DirectoryRecipePlugin.getJeiRuntime() == null
            ? null
            : DirectoryRecipePlugin.getJeiRuntime().getIngredientManager();
        if (manager == null) {
            return false;
        }
        IIngredientRenderer<FluidStack> renderer = manager.getIngredientRenderer(NeoForgeTypes.FLUID_STACK);

        FluidStack icon = value.get().copy();
        icon.setAmount(Integer.MAX_VALUE);
        renderer.render(graphics, icon, x, y);
        return true;
    }

    static boolean renderTooltip(GuiGraphics graphics, ItemStack stack, int mouseX, int mouseY) {
        Optional<FluidStack> value = treeFluid(stack);
        if (value.isEmpty()) {
            return false;
        }
        IIngredientManager manager = DirectoryRecipePlugin.getJeiRuntime() == null
            ? null
            : DirectoryRecipePlugin.getJeiRuntime().getIngredientManager();
        if (manager == null) {
            return false;
        }
        IIngredientRenderer<FluidStack> renderer = manager.getIngredientRenderer(NeoForgeTypes.FLUID_STACK);
        List<Component> tooltip = renderer.getTooltip(value.get(), TooltipFlag.Default.NORMAL);
        graphics.renderTooltip(Minecraft.getInstance().font, tooltip, Optional.empty(), mouseX, mouseY);
        return true;
    }

    private static int safeAmount(int amount, int batches) {
        long value = (long) Math.max(1, amount) * Math.max(1, batches);
        return (int) Math.min(Integer.MAX_VALUE, value);
    }
}
