package com.slwold.jeirecipetree.client;

import com.slwold.jeirecipetree.client.data.*;
import com.slwold.jeirecipetree.client.ui.*;
import com.slwold.jeirecipetree.client.jei.DirectoryRecipeCategory;
import mezz.jei.api.IModPlugin;
import mezz.jei.api.JeiPlugin;
import mezz.jei.api.helpers.IGuiHelper;
import mezz.jei.api.registration.IRecipeCategoryRegistration;
import mezz.jei.api.registration.IRecipeRegistration;
import mezz.jei.api.registration.IAdvancedRegistration;
import mezz.jei.api.runtime.IJeiRuntime;
import net.minecraft.resources.ResourceLocation;
import com.slwold.jeirecipetree.JERT;

@JeiPlugin
public final class DirectoryRecipePlugin implements IModPlugin {
    private static volatile DirectoryRecipeCategory category;
    private static volatile IJeiRuntime jeiRuntime;

    @Override
    public ResourceLocation getPluginUid() {
        return ResourceLocation.fromNamespaceAndPath(JERT.MODID, "jei_plugin");
    }

    @Override
    public void registerCategories(IRecipeCategoryRegistration registration) {
        IGuiHelper guiHelper = registration.getJeiHelpers().getGuiHelper();
        RecipeTreeIcons.initialize(guiHelper);
        category = new DirectoryRecipeCategory(guiHelper);
        registration.addRecipeCategories(category);
    }

    @Override
    public void registerRecipes(IRecipeRegistration registration) {

    }

    @Override
    public void registerGuiHandlers(mezz.jei.api.registration.IGuiHandlerRegistration registration) {

        registration.addGhostIngredientHandler(RecipeEditScreen.class, new EditDragHandler());
    }

    @Override
    public void registerAdvanced(IAdvancedRegistration registration) {
        IGuiHelper guiHelper = registration.getJeiHelpers().getGuiHelper();

        RecipeTreeDefaults.warmup();
        registration.addRecipeButtonFactory(new RecipeTreeButtonFactory(guiHelper));
        registration.addRecipeButtonFactory(new RecipeDefaultButtonFactory(guiHelper));

        JERTLog.summary("ready: input={} ae2Driver={} jeiRuntime={}",
            RecipeTreeSidebarPanel.isLegacyInputProxy() ? "legacy-proxy" : "event-layer",
            net.neoforged.fml.ModList.get().isLoaded("ae2") ? "registered" : "absent",
            getJeiRuntime() != null);
    }

    public static DirectoryRecipeCategory getCategory() {
        return category;
    }

    public static IJeiRuntime getJeiRuntime() {
        return jeiRuntime;
    }

    @Override
    public void onRuntimeAvailable(IJeiRuntime runtime) {
        jeiRuntime = runtime;
        RecipeTreeData.clearCaches();
        RecipeTreeDefaults.reload();
        RecipeTreeSession.resumeAfterRuntimeReload();
    }

    @Override
    public void onRuntimeUnavailable() {
        jeiRuntime = null;
        RecipeTreeData.clearCaches();
        RecipeTreeSession.suspendForRuntimeReload();
    }
}
