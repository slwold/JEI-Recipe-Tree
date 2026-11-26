package com.slwold.jeirecipetree.client.ui;
import com.slwold.jeirecipetree.client.*;
import com.slwold.jeirecipetree.client.jei.*;

import com.slwold.jeirecipetree.JERT;
import mezz.jei.api.gui.drawable.IDrawable;
import mezz.jei.api.gui.drawable.IScalableDrawable;
import mezz.jei.api.helpers.IGuiHelper;
import mezz.jei.common.Internal;
import mezz.jei.common.util.ImmutableRect2i;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;

public final class RecipeTreeIcons {

    private static final java.lang.reflect.Method BUTTON_FOR_STATE = findButtonForState();
    private static IDrawable globalTree;
    private static IDrawable recipeTree;
    private static IDrawable defaultRecipe;

    private RecipeTreeIcons() {
    }

    public static void initialize(IGuiHelper guiHelper) {
        globalTree = guiHelper.drawableBuilder(texture("recipe_tree_global.png"), 0, 0, 18, 18)
            .setTextureSize(18, 18)
            .build();
        recipeTree = guiHelper.drawableBuilder(texture("recipe_tree.png"), 0, 0, 10, 10)
            .setTextureSize(10, 10)
            .build();
        defaultRecipe = guiHelper.drawableBuilder(texture("recipe_default.png"), 0, 0, 10, 10)
            .setTextureSize(10, 10)
            .build();
    }

    public static void drawGlobalButton(GuiGraphics graphics, ImmutableRect2i area, boolean hovered, boolean selected) {
        if (area.isEmpty()) return;
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        getButtonForState(selected, hovered)
            .draw(graphics, area.getX(), area.getY(), area.getWidth(), area.getHeight());
        drawGlobalTree(graphics, area.getX() + (area.getWidth() - 18) / 2,
            area.getY() + (area.getHeight() - 18) / 2);
    }

    private static java.lang.reflect.Method findButtonForState() {
        try {
            return mezz.jei.common.gui.textures.Textures.class.getMethod(
                "getButtonForState", boolean.class, boolean.class, boolean.class);
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException("No supported JEI button skin selector", error);
        }
    }

    private static IScalableDrawable getButtonForState(boolean selected, boolean hovered) {
        try {
            return (IScalableDrawable) BUTTON_FOR_STATE.invoke(Internal.getTextures(), selected, true, hovered);
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException("Unable to read JEI button skin", error);
        }
    }

    public static void drawGlobalTree(GuiGraphics graphics, int x, int y) {
        if (globalTree != null) {
            globalTree.draw(graphics, x, y);
        }
    }

    public static IDrawable treeIcon() {
        return recipeTree;
    }

    public static IDrawable defaultIcon() {
        return defaultRecipe;
    }

    private static ResourceLocation texture(String name) {
        return ResourceLocation.fromNamespaceAndPath(JERT.MODID, "textures/gui/" + name);
    }
}
