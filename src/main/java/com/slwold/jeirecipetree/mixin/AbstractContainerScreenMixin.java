package com.slwold.jeirecipetree.mixin;

import com.slwold.jeirecipetree.client.JeiTreeIntegration;
import com.slwold.jeirecipetree.client.RenderLayers;

import com.slwold.jeirecipetree.client.RecipeTreeTransfer;
import com.slwold.jeirecipetree.client.SupplyLedger;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(AbstractContainerScreen.class)
public abstract class AbstractContainerScreenMixin {

    @Shadow
    protected int leftPos;

    @Shadow
    protected int topPos;

    @Inject(method = "render", at = @At("HEAD"))
    private void jert$updateRecipeTreeCrafting(
        GuiGraphics graphics,
        int mouseX,
        int mouseY,
        float partialTick,
        CallbackInfo ci
    ) {

        JeiTreeIntegration.refreshThrottled();
        RecipeTreeTransfer.tick((Screen) (Object) this);
    }

    @Inject(method = "renderSlot", at = @At("TAIL"))
    private void jert$highlightRequiredInventoryStack(
        GuiGraphics graphics,
        Slot slot,
        CallbackInfo ci
    ) {

        if (!SupplyLedger.craftingMode()) {
            return;
        }
        if (slot.getItem().isEmpty()) {
            return;
        }
        var player = net.minecraft.client.Minecraft.getInstance().player;
        AbstractContainerScreen<?> screen = (AbstractContainerScreen<?>) (Object) this;

        if (player != null
            && screen.getMenu() == player.inventoryMenu
            && !(slot.container instanceof Inventory)) {
            return;
        }

        int kind = JeiTreeIntegration.classify(slot.getItem());
        if (kind == 0) {
            return;
        }
        boolean finalProduct = (kind & JeiTreeIntegration.CLASS_FINAL) != 0;
        boolean intermediate = (kind & JeiTreeIntegration.CLASS_INTERMEDIATE) != 0;
        int fill = finalProduct ? 0x4433CC66 : (intermediate ? 0x44FF2222 : 0x3300BBFF);
        int border = finalProduct ? 0xDD66FF88 : (intermediate ? 0xDDFF5555 : 0xCC55DDFF);
        graphics.pose().pushPose();

        graphics.pose().translate(0, 0, RenderLayers.SLOT_HIGHLIGHT);
        graphics.fill(slot.x, slot.y, slot.x + 16, slot.y + 16, fill);
        graphics.fill(slot.x, slot.y, slot.x + 16, slot.y + 1, border);
        graphics.fill(slot.x, slot.y + 15, slot.x + 16, slot.y + 16, border);
        graphics.fill(slot.x, slot.y, slot.x + 1, slot.y + 16, border);
        graphics.fill(slot.x + 15, slot.y, slot.x + 16, slot.y + 16, border);
        graphics.pose().popPose();
    }

    @Inject(method = "render", at = @At("TAIL"))
    private void jert$highlightNetworkStorage(
        GuiGraphics graphics,
        int mouseX,
        int mouseY,
        float partialTick,
        CallbackInfo ci
    ) {
        if (!JeiTreeIntegration.isActive()) {
            return;
        }
        AbstractContainerScreen<?> screen = (AbstractContainerScreen<?>) (Object) this;
        for (Slot slot : screen.getMenu().slots) {
            if (!JeiTreeIntegration.isNetworkStorageSlot(slot) || slot.getItem().isEmpty()) {
                continue;
            }
            int kind = JeiTreeIntegration.classify(slot.getItem());
            if (kind == 0) {
                continue;
            }
            boolean finalProduct = (kind & JeiTreeIntegration.CLASS_FINAL) != 0;
            boolean intermediate = (kind & JeiTreeIntegration.CLASS_INTERMEDIATE) != 0;
            int fill = finalProduct ? 0x4433CC66 : (intermediate ? 0x44FF2222 : 0x3300BBFF);
            int border = finalProduct ? 0xDD66FF88 : (intermediate ? 0xDDFF5555 : 0xCC55DDFF);

            int x = screen.getGuiLeft() + slot.x;
            int y = screen.getGuiTop() + slot.y;
            graphics.pose().pushPose();
            graphics.pose().translate(0, 0, RenderLayers.SLOT_HIGHLIGHT);
            graphics.fill(x, y, x + 16, y + 16, fill);
            graphics.fill(x, y, x + 16, y + 1, border);
            graphics.fill(x, y + 15, x + 16, y + 16, border);
            graphics.fill(x, y, x + 1, y + 16, border);
            graphics.fill(x + 15, y, x + 16, y + 16, border);
            graphics.pose().popPose();
        }
        JeiTreeIntegration.renderNetworkHighlights(graphics, screen);
    }
}
