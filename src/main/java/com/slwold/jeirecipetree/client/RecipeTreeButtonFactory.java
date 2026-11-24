package com.slwold.jeirecipetree.client;

import mezz.jei.api.gui.IRecipeLayoutDrawable;
import mezz.jei.api.gui.builder.ITooltipBuilder;
import mezz.jei.api.gui.buttons.IButtonState;
import mezz.jei.api.gui.buttons.IIconButtonController;
import mezz.jei.api.gui.drawable.IDrawable;
import mezz.jei.api.helpers.IGuiHelper;
import mezz.jei.api.recipe.advanced.IRecipeButtonControllerFactory;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

public final class RecipeTreeButtonFactory implements IRecipeButtonControllerFactory {
    public RecipeTreeButtonFactory(IGuiHelper guiHelper) {

    }

    @Override
    @Nullable
    public <T> IIconButtonController createButtonController(IRecipeLayoutDrawable<T> layout) {

        if (!RecipeLookup.isButtonCandidate(layout)) {
            return null;
        }
        return new Controller<>(layout);
    }

    private static final class Controller<T> implements IIconButtonController {
        private final IRecipeLayoutDrawable<T> layout;

        private Controller(IRecipeLayoutDrawable<T> layout) {
            this.layout = layout;
        }

        @Override
        public boolean onPress(mezz.jei.api.gui.inputs.IJeiUserInput input) {
            if (!SafeConfig.recipeTreeEnabled()) {
                return false;
            }
            if (input.isSimulate()) {
                return true;
            }
            RecipeTreeScreen.openFromRecipeButton(layout);
            return true;
        }

        @Override
        public void initState(IButtonState state) {
            IDrawable icon = RecipeTreeIcons.treeIcon();
            if (icon != null) {
                state.setIcon(icon);
            }
            updateState(state);
        }

        @Override
        public void updateState(IButtonState state) {
            state.setActive(SafeConfig.recipeTreeEnabled());
            state.setForcePressed(RecipeTreeSession.isCurrentResolution(layout));
        }

        @Override
        public void getTooltips(ITooltipBuilder tooltip) {
            tooltip.add(Component.translatable(RecipeTreeSession.canResolve(layout)
                ? "jeirecipetree.recipe_tree.resolve_button"
                : "jeirecipetree.recipe_tree.button"));
        }

    }
}
