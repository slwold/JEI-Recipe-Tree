package com.slwold.jeirecipetree.client;

import net.neoforged.fml.ModContainer;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;

public final class ConfigScreenRegistration {

    private static final String MEZZ_CONFIG_GUI = "mezz_config_gui";

    private ConfigScreenRegistration() {
    }

    public static void register(ModContainer container) {
        if (ModList.get().isLoaded(MEZZ_CONFIG_GUI)) {

            JERTLog.summary("config screen: deferring to {} (native config discovery)", MEZZ_CONFIG_GUI);
            return;
        }
        container.registerExtensionPoint(IConfigScreenFactory.class, ConfigurationScreen::new);
        JERTLog.summary("config screen: using NeoForge built-in screen ({} not installed)", MEZZ_CONFIG_GUI);
    }
}
