package com.slwold.jeirecipetree.mixin;

import com.slwold.jeirecipetree.client.jei.JeiReflectionCompat;
import mezz.jei.gui.recipes.IRecipeGuiLogic;
import mezz.jei.gui.recipes.RecipesGui;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = RecipesGui.class, remap = false)
public abstract class RecipesGuiMixin {
    @Shadow @Final private IRecipeGuiLogic logic;

    @Inject(method = "mouseScrolled", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private void jert$scrollNavigation(
        double mouseX,
        double mouseY,
        double scrollX,
        double scrollY,
        CallbackInfoReturnable<Boolean> cir
    ) {
        if (scrollY == 0) {
            return;
        }
        if (insideBand(mouseX, mouseY, com.slwold.jeirecipetree.client.jei.JeiReflectionCompat.readField(this, "previousRecipeCategory"), com.slwold.jeirecipetree.client.jei.JeiReflectionCompat.readField(this, "nextRecipeCategory"))) {
            if (scrollY < 0) {
                logic.nextRecipeCategory();
            } else {
                logic.previousRecipeCategory();
            }
            cir.setReturnValue(true);
            return;
        }

        if (insideBand(mouseX, mouseY, com.slwold.jeirecipetree.client.jei.JeiReflectionCompat.readField(this, "previousPage"), com.slwold.jeirecipetree.client.jei.JeiReflectionCompat.readField(this, "nextPage"))) {
            if (scrollY < 0) {
                logic.nextPage();
            } else {
                logic.previousPage();
            }
            cir.setReturnValue(true);
        }
    }

    private static boolean insideBand(double mouseX, double mouseY, Object left, Object right) {
        Object leftArea = com.slwold.jeirecipetree.client.jei.JeiReflectionCompat.tryInvoke(left, "getArea");
        Object rightArea = com.slwold.jeirecipetree.client.jei.JeiReflectionCompat.tryInvoke(right, "getArea");
        if (!(leftArea instanceof mezz.jei.common.util.ImmutableRect2i a)
            || !(rightArea instanceof mezz.jei.common.util.ImmutableRect2i b)) return false;
        return mouseX >= a.getX() && mouseX <= b.getX() + b.getWidth()
            && mouseY >= a.getY() - 2 && mouseY <= a.getY() + a.getHeight() + 2;
    }

    @Inject(method = "mouseClicked", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private void jert$pageButtonClick(
        double mouseX,
        double mouseY,
        int button,
        CallbackInfoReturnable<Boolean> cir
    ) {
        if (button != 0) {
            return;
        }
        Object previous = com.slwold.jeirecipetree.client.jei.JeiReflectionCompat.readField(this, "previousPage");
        if (hits(previous, mouseX, mouseY)) {
            boolean moved = logic.previousPage();
            com.slwold.jeirecipetree.client.JERTLog.debug(
                "page button: previous at ({}, {}) -> {}", mouseX, mouseY, moved
            );
            cir.setReturnValue(true);
            return;
        }
        Object next = com.slwold.jeirecipetree.client.jei.JeiReflectionCompat.readField(this, "nextPage");
        if (hits(next, mouseX, mouseY)) {
            boolean moved = logic.nextPage();
            com.slwold.jeirecipetree.client.JERTLog.debug(
                "page button: next at ({}, {}) -> {}", mouseX, mouseY, moved
            );
            cir.setReturnValue(true);
        }
    }

    private static boolean hits(Object button, double mouseX, double mouseY) {
        if (button instanceof mezz.jei.gui.elements.IconButton icon) {
            return icon.isMouseOver(mouseX, mouseY);
        }
        Object area = com.slwold.jeirecipetree.client.jei.JeiReflectionCompat.tryInvoke(button, "getArea");
        if (area instanceof mezz.jei.common.util.ImmutableRect2i rect) {
            return mouseX >= rect.getX() && mouseX <= rect.getX() + rect.getWidth()
                && mouseY >= rect.getY() && mouseY <= rect.getY() + rect.getHeight();
        }
        return false;
    }

}
