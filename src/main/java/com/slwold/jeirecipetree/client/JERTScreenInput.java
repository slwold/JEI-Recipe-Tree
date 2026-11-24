package com.slwold.jeirecipetree.client;

import com.slwold.jeirecipetree.JERT;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.fml.common.EventBusSubscriber;

@EventBusSubscriber(value = Dist.CLIENT, modid = JERT.MODID)
public final class JERTScreenInput {

    private JERTScreenInput() {
    }

    @SubscribeEvent
    public static void onMousePressed(ScreenEvent.MouseButtonPressed.Pre event) {

        JERTLog.debug("press: legacy={} visible={} area={} btn={} xy=({},{})",
            RecipeTreeSidebarPanel.isLegacyInputProxy(),
            RecipeTreeSidebarPanel.isOverlayVisible(),
            RecipeTreeSidebarPanel.area(),
            event.getButton(), event.getMouseX(), event.getMouseY());
        if (!RecipeTreeSidebarPanel.isOverlayVisible()) {
            return;
        }
        if (RecipeTreeSidebarPanel.isLegacyInputProxy()) {

            RecipeTreeSidebarPanel.noteEventLayerTakeover();
        }
        RecipeTreeSidebarPanel.handleClickAt(
            event.getMouseX(), event.getMouseY(), event.getButton(), false,
            RecipeTreeSidebarPanel.InputSource.EVENT_LAYER);
    }

    @SubscribeEvent
    public static void onMouseScrolled(ScreenEvent.MouseScrolled.Pre event) {

        if (OverlayScrollRouter.dispatch(event.getMouseX(), event.getMouseY(), event.getScrollDeltaY())) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        MaterialFetch.tick();
    }
}
