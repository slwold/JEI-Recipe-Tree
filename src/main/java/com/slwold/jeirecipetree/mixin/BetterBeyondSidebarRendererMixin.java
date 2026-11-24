package com.slwold.jeirecipetree.mixin;

import com.slwold.jeirecipetree.client.JeiTreeIntegration;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "net.xuwu.betterbeyonddimensions.client.SidebarRenderer", remap = false)
public abstract class BetterBeyondSidebarRendererMixin {
    @Inject(method = "render", at = @At("TAIL"), remap = false, require = 0)
    private static void jert$highlightAfterRender(
        @Coerce Object host,
        GuiGraphics graphics,
        int mouseX,
        int mouseY,
        float partialTick,
        CallbackInfo ci
    ) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.screen instanceof AbstractContainerScreen<?> screen) {
            JeiTreeIntegration.renderBetterBeyondHighlights(graphics, screen);
        }
    }
}
