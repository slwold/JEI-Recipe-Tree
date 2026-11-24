package com.slwold.jeirecipetree.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.slwold.jeirecipetree.JERT;
import net.minecraft.client.KeyMapping;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.settings.KeyConflictContext;
import org.lwjgl.glfw.GLFW;

@EventBusSubscriber(modid = JERT.MODID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
public final class JERTKeyMappings {

    public static final String CATEGORY = "key.categories.jeirecipetree";

    public static final KeyMapping DUMP_DIAGNOSTICS = new KeyMapping(
        "key.jeirecipetree.dumpDiagnostics",
        KeyConflictContext.IN_GAME,
        InputConstants.Type.KEYSYM,
        GLFW.GLFW_KEY_F9,
        CATEGORY);

    public static final KeyMapping OPEN_LIBRARY = new KeyMapping(
        "key.jeirecipetree.openLibrary",
        KeyConflictContext.IN_GAME,
        InputConstants.Type.KEYSYM,
        GLFW.GLFW_KEY_F8,
        CATEGORY);

    private JERTKeyMappings() {
    }

    @SubscribeEvent
    static void onRegisterKeyMappings(RegisterKeyMappingsEvent event) {
        event.register(DUMP_DIAGNOSTICS);
        event.register(OPEN_LIBRARY);
    }

    public static boolean isLibraryKey(int keyCode) {
        return OPEN_LIBRARY.matches(keyCode, 0);
    }

    public static boolean isDumpKey(int keyCode) {
        return DUMP_DIAGNOSTICS.matches(keyCode, 0);
    }
}
