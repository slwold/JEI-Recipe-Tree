package com.slwold.jeirecipetree.client;

import com.slwold.jeirecipetree.JERT;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import org.lwjgl.glfw.GLFW;

@EventBusSubscriber(modid = JERT.MODID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.GAME)
public final class DiagnosticHotkey {

    private DiagnosticHotkey() {
    }

    @SubscribeEvent
    static void onKeyInput(InputEvent.Key event) {
        if (Minecraft.getInstance().screen != null) {

            return;
        }
        if (event.getAction() != GLFW.GLFW_PRESS) {
            return;
        }

        if (JERTKeyMappings.isLibraryKey(event.getKey())) {
            RecipeLibraryScreen.open(null);
            return;
        }
        if (!JERTKeyMappings.isDumpKey(event.getKey())) {
            return;
        }
        if (isShift(event.getModifiers()) || isControl(event.getModifiers())) {

            return;
        }
        Diagnostics.dump();
    }

    @SubscribeEvent
    static void onScreenKeyPressed(ScreenEvent.KeyPressed.Pre event) {
        if (JERTKeyMappings.isLibraryKey(event.getKeyCode())) {
            RecipeLibraryScreen.open(Minecraft.getInstance().screen);
            return;
        }
        if (!JERTKeyMappings.isDumpKey(event.getKeyCode())) {
            return;
        }
        if (isControl(event.getModifiers())) {
            RecipeArchive.importFromJeiScreen();
        } else if (isShift(event.getModifiers())) {
            RecipeArchive.exportCurrentTree();
        } else {
            Diagnostics.dump();
        }
    }

    private static boolean isShift(int modifiers) {
        return (modifiers & GLFW.GLFW_MOD_SHIFT) != 0;
    }

    private static boolean isControl(int modifiers) {
        return (modifiers & GLFW.GLFW_MOD_CONTROL) != 0;
    }
}
