package com.slwold.jeirecipetree.client.ui;
import com.slwold.jeirecipetree.client.data.*;
import com.slwold.jeirecipetree.client.*;
import com.slwold.jeirecipetree.client.jei.*;

import mezz.jei.api.gui.IRecipeLayoutDrawable;
import mezz.jei.api.gui.builder.ITooltipBuilder;
import mezz.jei.api.gui.buttons.IButtonState;
import mezz.jei.api.gui.buttons.IIconButtonController;
import mezz.jei.api.gui.drawable.IDrawable;
import mezz.jei.api.helpers.IGuiHelper;
import net.minecraft.world.item.ItemStack;
import mezz.jei.api.recipe.advanced.IRecipeButtonControllerFactory;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

public final class RecipeDefaultButtonFactory implements IRecipeButtonControllerFactory {
    public RecipeDefaultButtonFactory(IGuiHelper guiHelper) {
    }

    @Override
    @Nullable
    public <T> IIconButtonController createButtonController(IRecipeLayoutDrawable<T> layout) {

        if (!RecipeLookup.isButtonCandidate(layout)) {
            return null;
        }
        return new Controller(layout);
    }

    private static final class Controller implements IIconButtonController {
        private final IRecipeLayoutDrawable<?> layout;
        @Nullable private RecipeTreeData.RecipeRef ref;
        @Nullable private java.util.List<ItemStack> outputs;

        private Controller(IRecipeLayoutDrawable<?> layout) {
            this.layout = layout;
        }

        @Nullable
        private RecipeTreeData.RecipeRef ref() {
            if (ref == null) {
                ref = RecipeLookup.refOf(layout);
            }
            return ref;
        }

        private java.util.List<ItemStack> outputs() {
            if (outputs == null) {
                outputs = RecipeLookup.outputStacks(layout);
            }
            return outputs;
        }

        @Override
        public boolean onPress(mezz.jei.api.gui.inputs.IJeiUserInput input) {
            if (!SafeConfig.recipeTreeEnabled()) {
                return false;
            }
            if (input.isSimulate()) {
                return true;
            }
            RecipeTreeDefaults.toggle(ref(), outputs());

            RecipeTreeDefaults.getStatus(ref(), outputs());
            return true;
        }

        @Override
        public void initState(IButtonState state) {
            IDrawable icon = RecipeTreeIcons.defaultIcon();
            if (icon != null) {
                state.setIcon(icon);
            }
            updateState(state);
        }

        @Override
        public void updateState(IButtonState state) {

            state.setActive(SafeConfig.recipeTreeEnabled());
            RecipeTreeDefaults.Status cached = RecipeTreeDefaults.cachedStatus(ref());
            state.setForcePressed(cached == RecipeTreeDefaults.Status.FULL);
        }

        @Override
        public void getTooltips(ITooltipBuilder tooltip) {
            RecipeTreeDefaults.Status status = RecipeTreeDefaults.getStatus(ref(), outputs());
            String key = switch (status) {
                case FULL -> "jeirecipetree.recipe_tree.default.unset";
                case PARTIAL -> "jeirecipetree.recipe_tree.default.partial";
                case EMPTY -> "jeirecipetree.recipe_tree.default.set";
            };
            tooltip.add(Component.translatable(key));
        }

    }
}
